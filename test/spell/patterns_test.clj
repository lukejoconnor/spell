(ns spell.patterns-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [spell.eval :as eval]
            [spell.io :as sio]
            [spell.runtime :as runtime]
            [spell.stdlib :as stdlib]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

(def fix-loop (:fix-loop stdlib/patterns))
(def ralph (:ralph stdlib/patterns))
(def sh-test (:sh-test sio/io-namespace))

(defn- run-fix-loop [opts env]
  (binding [eval/*spell-env* (merge {'strings stdlib/strings
                                     'io (assoc sio/io-namespace :sh sio/sh)
                                     'futures {:!ask-await (fn [fut]
                                                             (deref (:ref fut) 5000 :timeout))}}
                                    env)]
    (eval/invoke-fn fix-loop [opts])))

(defn- run-ralph [opts env]
  (binding [eval/*spell-env* (merge {'strings stdlib/strings
                                     'io (assoc sio/io-namespace :sh sio/sh)}
                                    env)]
    (eval/invoke-fn ralph [opts])))

(defn- create-temp-git-repo
  [files]
  (let [dir (java.io.File/createTempFile "spell-fix-loop" "")]
    (.delete dir)
    (.mkdirs dir)
    (doseq [[path content] files]
      (spit (str dir "/" path) content))
    (sio/sh (str "cd " dir
                 " && git init"
                 " && git config user.email test@example.com"
                 " && git config user.name test"
                 " && find . -maxdepth 1 -name '*.sh' -exec chmod +x {} +"
                 " && git add -A"
                 " && git commit -m init"))
    (str dir)))

(defn- cleanup-dir [dir]
  (sio/sh (str "rm -rf " dir)))

(defn- sh-in-dir
  [dir]
  (let [original-sh sio/sh]
    (fn [cmd & more]
      (apply original-sh (str "cd " dir " && " cmd) more))))

(deftest ralph-pattern-test
  (testing "ralph retries until test-fn passes and sends final result to parent"
    (let [spawn-calls (atom [])
          send-await-calls (atom [])
          send-calls (atom [])
          done (promise)
          spawn-fn (fn [prompt handle-name]
                     (swap! spawn-calls conj {:prompt prompt :handle handle-name})
                     handle-name)
          send-await-fn
          (fn [handle msg]
            (swap! send-await-calls conj {:handle handle :msg msg})
            (let [attempt (:attempt msg)]
              (if (>= attempt 2)
                {:ok "fixed!"}
                {:error (str "still-broken-" attempt)})))
          send-fn (fn [target msg]
                    (swap! send-calls conj {:target target :msg msg})
                    (when (= target :parent)
                      (deliver done msg)))]
      (let [started (run-ralph {:task "Repair bug"
                                :max-retries 5
                                :test-fn (fn [r] (:ok r))
                                :worker-prompt "worker"}
                               {'agents {:current-handle (fn [] :parent)
                                         :spawn spawn-fn
                                         :send send-fn}
                                'blocking {:send-await send-await-fn}})
            final-result (deref done 5000 :timeout)]
        (is (= true (:started started)))
        (is (= {:pass {:ok "fixed!"}} final-result))
        (is (= 1 (count @spawn-calls)))
        (is (= 3 (count @send-await-calls)))
        (is (= [0 1 2] (mapv #(get-in % [:msg :attempt]) @send-await-calls)))
        (is (= :parent (:target (last @send-calls))))))))

(deftest ralph-pattern-runtime-integration-test
  (testing "ralph retries using runtime send-await and sends final pass to parent"
    (let [parent-handle :ralph-parent
          base-raw "(quine completion (eval (do )))"
          spawn-calls (atom [])
          send-await-calls (atom [])
          send-calls (atom [])
          worker-runs (atom 0)
          done (promise)
          spawn-fn
          (fn [prompt handle-name]
            (swap! spawn-calls conj {:prompt prompt :handle handle-name})
            (runtime/start-box handle-name
                               (fn [_]
                                 (let [n (swap! worker-runs inc)]
                                   (if (>= n 3)
                                     {:ok "fixed!"}
                                     {:error (str "still-broken-" (dec n))})))
                               base-raw
                               parent-handle)
            handle-name)
          send-await-fn
          (fn [handle msg]
            (swap! send-await-calls conj {:handle handle :msg msg})
            (runtime/send-await handle msg))
          send-fn
          (fn [target msg]
            (swap! send-calls conj {:target target :msg msg})
            (when (= target parent-handle)
              (deliver done msg)))]
      (runtime/register! parent-handle)
      (let [started (binding [runtime/*current-handle* parent-handle]
                      (run-ralph {:task "Repair bug"
                                  :max-retries 5
                                  :test-fn (fn [r] (:ok r))
                                  :worker-prompt "worker"}
                                 {'agents {:current-handle (fn [] parent-handle)
                                           :spawn spawn-fn
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn}}))
            final-result (deref done 5000 :timeout)
            worker-calls @send-await-calls]
        (is (= true (:started started)))
        (is (= {:pass {:ok "fixed!"}} final-result))
        (is (= 1 (count @spawn-calls)))
        (is (= 3 @worker-runs))
        (is (= 3 (count worker-calls)))
        (is (= [0 1 2] (mapv #(get-in % [:msg :attempt]) worker-calls)))
        (is (= parent-handle (:target (last @send-calls))))))))

(deftest fix-loop-happy-path-blocking-send-await-lifecycle-test
  (testing "uses blocking/send-await for reflector and worker"
    (let [dir (create-temp-git-repo {"run_tests.sh" "#!/bin/bash\ntest -f fixed.txt"})
          register-calls (atom [])
          send-await-calls (atom [])
          reflect-count (atom 0)
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (swap! send-await-calls conj {:handle handle :msg msg})
                          (case (:kind msg)
                            :reflect (do
                                       (swap! reflect-count inc)
                                       (if (= 1 @reflect-count)
                                         {:resolved false
                                          :diagnosis "Create fixed.txt."
                                          :test "./run_tests.sh"
                                          :panic false}
                                         {:resolved true
                                          :diagnosis "Issue resolved."
                                          :test "./run_tests.sh"
                                          :panic false}))
                            :repair (do
                                      (spit (str dir "/fixed.txt") "ok")
                                      {:summary "created fixed.txt"})
                            {:panic true :diagnosis (str "unexpected kind " (pr-str msg))}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        "Missing fixed.txt causes failure"
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})]
            (is (= true (:pass result)))
            (is (= "Issue resolved." (:diagnosis result)))
            (is (= "./run_tests.sh" (:test result)))
            (is (= "created fixed.txt" (:last-worker-summary result)))
            (is (keyword? (:reflector-handle result)))
            (is (keyword? (:worker-handle result)))
            (is (= 2 (count @register-calls)))
            (is (= 3 (count @send-await-calls)))
            (is (= [:reflect :repair :reflect]
                   (mapv #(get-in % [:msg :kind]) @send-await-calls)))
            (is (= (-> @register-calls first :handle) (:reflector-handle result)))
            (is (= (-> @register-calls second :handle) (:worker-handle result)))
            (is (= "created fixed.txt"
                   (get-in (last @send-await-calls) [:msg :last-worker-summary])))
            (is (str/includes? (get-in (last @send-await-calls) [:msg :last-test-output]) "EXIT: 0"))
            (is (some #(str/includes? (:completion %) "reflector agent in patterns/fix-loop")
                      @register-calls))
            (is (some #(str/includes? (:completion %) "code repair worker used by patterns/fix-loop")
                      @register-calls))
            (is (every? #(str/includes? (:completion %) "shell commands, process execution and file watching.")
                        @register-calls))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-retry-loop-test
  (testing "retries with updated reflector context and same registered agents"
    (let [dir (create-temp-git-repo {"run_tests.sh" "#!/bin/bash\ntest -f file-a.txt && test -f file-b.txt"})
          register-calls (atom [])
          reflector-msgs (atom [])
          reflect-count (atom 0)
          repair-count (atom 0)
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [_handle msg]
                          (case (:kind msg)
                            :reflect (do
                                       (swap! reflect-count inc)
                                       (swap! reflector-msgs conj msg)
                                       (case @reflect-count
                                         1 {:resolved false
                                            :diagnosis "Create file-a.txt and file-b.txt."
                                            :test "./run_tests.sh"
                                            :panic false}
                                         2 {:resolved false
                                            :diagnosis "file-a.txt exists. Create file-b.txt next."
                                            :test "./run_tests.sh"
                                            :panic false}
                                         {:resolved true
                                          :diagnosis "Both files exist and issue is resolved."
                                          :test "./run_tests.sh"
                                          :panic false}))
                            :repair (do
                                      (swap! repair-count inc)
                                      (case @repair-count
                                        1 (spit (str dir "/file-a.txt") "a")
                                        2 (do
                                            (spit (str dir "/file-a.txt") "a")
                                            (spit (str dir "/file-b.txt") "b"))
                                        nil)
                                      (case @repair-count
                                        1 {:summary "added file-a only"}
                                        {:summary "added file-b too"}))
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        {:issue "Need both files"
                         :max-retries 3}
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})]
            (is (= true (:pass result)))
            (is (= "Both files exist and issue is resolved." (:diagnosis result)))
            (is (= "./run_tests.sh" (:test result)))
            (is (= "added file-b too" (:last-worker-summary result)))
            (is (= (-> @register-calls first :handle) (:reflector-handle result)))
            (is (= (-> @register-calls second :handle) (:worker-handle result)))
            (is (= 3 @reflect-count))
            (is (= 2 @repair-count))
            (is (= 2 (count @register-calls)))
            (is (= [0 1 2] (mapv :attempt @reflector-msgs)))
            (is (= "Create file-a.txt and file-b.txt."
                   (:last-diagnosis (second @reflector-msgs))))
            (is (= "added file-a only"
                   (:last-worker-summary (second @reflector-msgs))))
            (is (= "./run_tests.sh"
                   (:active-test (second @reflector-msgs))))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-panic-path-test
  (testing "panic from reflector returns fail and does not invoke worker"
    (let [dir (create-temp-git-repo {"run_tests.sh" "#!/bin/bash\ntest -f impossible.txt"})
          register-count (atom 0)
          worker-calls (atom 0)
          register-fn (fn [handle completion]
                        (swap! register-count inc)
                        handle)
          send-await-fn (fn [_handle msg]
                          (case (:kind msg)
                            :reflect {:diagnosis "Fundamentally unsolvable"
                                      :test "./run_tests.sh"
                                      :panic true}
                            :repair (do
                                      (swap! worker-calls inc)
                                      {:summary "should not run"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [orig-branch (str/trim (:out (sio/sh "git rev-parse --abbrev-ref HEAD")))
                result (run-fix-loop
                        {:issue "Impossible bug"
                         :max-retries 2}
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})
                end-branch (str/trim (:out (sio/sh "git rev-parse --abbrev-ref HEAD")))]
            (is (nil? (:pass result)))
            (is (string? (:fail result)))
            (is (str/includes? (:fail result) "unsolvable"))
            (is (= true (:panic result)))
            (is (nil? (:test result)))
            (is (keyword? (:reflector-handle result)))
            (is (keyword? (:worker-handle result)))
            (is (= 0 @worker-calls))
            (is (= 2 @register-count))
            (is (= orig-branch end-branch))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-reflector-string-map-test
  (testing "parses string-map reflector responses and uses reflector test command"
    (let [dir (create-temp-git-repo
               {"focused.sh" "#!/bin/bash\ntest -f target.txt && test -f secondary.txt"
                "broad.sh" "#!/bin/bash\nexit 1"})
          commands (atom [])
          reflect-count (atom 0)
          sh-base (sh-in-dir dir)
          tracking-sh (fn [cmd & more]
                        (swap! commands conj cmd)
                        (apply sh-base cmd more))
          register-fn (fn [handle completion] handle)
          send-await-fn (fn [_handle msg]
                          (case (:kind msg)
                            :reflect (do
                                       (swap! reflect-count inc)
                                       (if (= 1 @reflect-count)
                                         "{:resolved false :diagnosis \"Create target.txt and secondary.txt\" :test \"./focused.sh\" :panic false}"
                                         "{:resolved true :diagnosis \"Issue is resolved\" :test \"./focused.sh\" :panic false}"))
                            :repair (do
                                      (spit (str dir "/target.txt") "t")
                                      (spit (str dir "/secondary.txt") "s")
                                      {:summary "created both"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh tracking-sh]
          (let [result (run-fix-loop
                        {:issue "Need both files"
                         :max-retries 2}
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})]
            (is (= true (:pass result)))
            (is (some #{"./focused.sh"} @commands))
            (is (not (some #{"./broad.sh"} @commands)))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-reflector-must-provide-test-command
  (testing "invalid reflector schema reprompts reflector without running worker"
    (let [dir (create-temp-git-repo {"run_tests.sh" "#!/bin/bash\ntest -f target.txt"})
          worker-calls (atom 0)
          reflect-count (atom 0)
          reflector-msgs (atom [])
          register-fn (fn [handle completion] handle)
          send-await-fn (fn [_handle msg]
                          (when (= :reflect (:kind msg))
                            (swap! reflect-count inc)
                            (swap! reflector-msgs conj msg))
                          (case (:kind msg)
                            :reflect (if (= 1 @reflect-count)
                                       {:diagnosis "Need to create target.txt"
                                        :resolved false
                                        :panic false}
                                       {:diagnosis "Stopping after invalid schema"
                                        :test "./run_tests.sh"
                                        :panic true})
                            :repair (do
                                      (swap! worker-calls inc)
                                      {:summary "should not run"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        {:issue "Need target file"
                         :max-retries 2}
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})]
            (is (nil? (:pass result)))
            (is (string? (:fail result)))
            (is (str/includes? (:fail result) "Stopping after invalid schema"))
            (is (= 0 @worker-calls))
            (is (= 2 @reflect-count))
            (is (str/includes? (:feedback (second @reflector-msgs))
                               "Invalid reflector schema"))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-failed-worker-attempt-does-not-leak-to-orig-branch-test
  (testing "failed worker edits are reset before retry/cleanup"
    (let [dir (create-temp-git-repo {"run_tests.sh" "#!/bin/bash\ntest -f target.txt"})
          register-fn (fn [handle completion] handle)
          send-await-fn (fn [_handle msg]
                          (case (:kind msg)
                            :reflect {:resolved false
                                      :diagnosis "Create target.txt"
                                      :test "./run_tests.sh"
                                      :panic false}
                            :repair (do
                                      (spit (str dir "/leak.txt") "should-not-leak")
                                      {:summary "wrote leak.txt"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [orig-branch (str/trim (:out (sio/sh "git rev-parse --abbrev-ref HEAD")))
                result (run-fix-loop
                        {:issue "Need target file"
                         :max-retries 1}
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})
                end-branch (str/trim (:out (sio/sh "git rev-parse --abbrev-ref HEAD")))]
            (is (nil? (:pass result)))
            (is (str/includes? (:fail result) "Max worker retries"))
            (is (= "./run_tests.sh" (:test result)))
            (is (= "wrote leak.txt" (:last-worker-summary result)))
            (is (keyword? (:reflector-handle result)))
            (is (keyword? (:worker-handle result)))
            (is (= orig-branch end-branch))
            (is (not (.exists (java.io.File. (str dir "/leak.txt")))))
            (is (str/blank? (:out (sio/sh "git status --porcelain"))))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-reflector-result-mismatch-reprompts-without-consuming-worker-retries
  (testing "resolved/test-result mismatch reprompts reflector and does not increment worker attempt"
    (let [dir (create-temp-git-repo
               {"run_tests.sh" "#!/bin/bash\ntest -f target.txt"
                "always_pass.sh" "#!/bin/bash\nexit 0"
                "always_fail.sh" "#!/bin/bash\nexit 1"})
          reflect-count (atom 0)
          repair-count (atom 0)
          reflector-msgs (atom [])
          register-fn (fn [handle completion] handle)
          send-await-fn
          (fn [_handle msg]
            (case (:kind msg)
              :reflect (do
                         (swap! reflect-count inc)
                         (swap! reflector-msgs conj msg)
                         (case @reflect-count
                           1 {:resolved false
                              :diagnosis "Need target file."
                              :test "./always_pass.sh"
                              :panic false}
                           2 {:resolved false
                              :diagnosis "Need target file."
                              :test "./run_tests.sh"
                              :panic false}
                           3 {:resolved true
                              :diagnosis "Looks resolved."
                              :test "./always_fail.sh"
                              :panic false}
                           {:resolved true
                            :diagnosis "Issue is resolved."
                            :test "./run_tests.sh"
                            :panic false}))
              :repair (do
                        (swap! repair-count inc)
                        (spit (str dir "/target.txt") "ok")
                        {:summary "created target.txt"})
              {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        {:issue "Need target file"
                         :max-retries 1}
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})]
            (is (= true (:pass result)))
            (is (= "Issue is resolved." (:diagnosis result)))
            (is (= "./run_tests.sh" (:test result)))
            (is (= "created target.txt" (:last-worker-summary result)))
            (is (keyword? (:reflector-handle result)))
            (is (keyword? (:worker-handle result)))
            (is (= 4 @reflect-count))
            (is (= 1 @repair-count))
            (is (= [0 0 1 1] (mapv :attempt @reflector-msgs)))
            (is (str/includes? (:feedback (second @reflector-msgs))
                               "Expected test pass == resolved flag"))
            (is (str/includes? (:feedback (nth @reflector-msgs 3))
                               "Expected test pass == resolved flag"))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-reset-worker-fresh-handle-test
  (testing "reset-worker registers and uses a fresh worker handle"
    (let [dir (create-temp-git-repo {"run_tests.sh" "#!/bin/bash\ntest -f target.txt"})
          register-calls (atom [])
          repair-handles (atom [])
          reflect-count (atom 0)
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (case (:kind msg)
                            :reflect (do
                                       (swap! reflect-count inc)
                                       (if (= 1 @reflect-count)
                                         {:resolved false
                                          :diagnosis "Create target.txt with a fresh worker."
                                          :test "./run_tests.sh"
                                          :panic false
                                          :reset-worker true}
                                         {:resolved true
                                          :diagnosis "Issue resolved."
                                          :test "./run_tests.sh"
                                          :panic false}))
                            :repair (do
                                      (swap! repair-handles conj handle)
                                      (spit (str dir "/target.txt") "ok")
                                      {:summary "created target.txt"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        {:issue "Need target file"
                         :max-retries 1}
                        {'agents {:register register-fn}
                         'blocking {:send-await send-await-fn}})
                registered-handles (mapv :handle @register-calls)]
            (is (= true (:pass result)))
            (is (= 3 (count @register-calls)))
            (is (= 1 (count @repair-handles)))
            (is (= (nth registered-handles 2) (first @repair-handles)))
            (is (= (nth registered-handles 2) (:worker-handle result)))
            (is (not= (nth registered-handles 1) (nth registered-handles 2)))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-test-thunk-test
  (testing "fix-loop accepts a single test thunk"
    (let [dir (create-temp-git-repo {})
          reflect-count (atom 0)
          send-await-fn (fn [_handle msg]
                          (case (:kind msg)
                            :reflect (do
                                       (swap! reflect-count inc)
                                       {:resolved (= 2 @reflect-count)
                                        :diagnosis (if (= 1 @reflect-count)
                                                     "Create target.txt."
                                                     "Issue resolved.")
                                        :test (fn []
                                                {:pass (.exists (java.io.File. (str dir "/target.txt")))
                                                 :output "checked target.txt"})
                                        :panic false})
                            :repair (do
                                      (spit (str dir "/target.txt") "ok")
                                      {:summary "created target.txt"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        {:issue "Need target file"
                         :max-retries 1}
                        {'agents {:register (fn [handle _completion] handle)}
                         'blocking {:send-await send-await-fn}})]
            (is (= true (:pass result)))
            (is (= "Issue resolved." (:diagnosis result)))
            (is (fn? (:test result)))
            (is (= "created target.txt" (:last-worker-summary result)))))
        (finally
          (cleanup-dir dir))))))

(deftest fix-loop-test-vector-test
  (testing "fix-loop requires all vectorized test thunks to pass"
    (let [dir (create-temp-git-repo {})
          reflect-count (atom 0)
          send-await-fn (fn [_handle msg]
                          (case (:kind msg)
                            :reflect (do
                                       (swap! reflect-count inc)
                                       {:resolved (= 2 @reflect-count)
                                        :diagnosis (if (= 1 @reflect-count)
                                                     "Create both files."
                                                     "Both files exist.")
                                        :test [(fn []
                                                 {:pass (.exists (java.io.File. (str dir "/file-a.txt")))
                                                  :output "checked file-a"})
                                               (fn []
                                                 {:pass (.exists (java.io.File. (str dir "/file-b.txt")))
                                                  :output "checked file-b"})]
                                        :panic false})
                            :repair (do
                                      (spit (str dir "/file-a.txt") "a")
                                      (spit (str dir "/file-b.txt") "b")
                                      {:summary "created both files"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        {:issue "Need two files"
                         :max-retries 1}
                        {'agents {:register (fn [handle _completion] handle)}
                         'blocking {:send-await send-await-fn}})]
            (is (= true (:pass result)))
            (is (vector? (:test result)))
            (is (= 2 (count (:test result))))
            (is (= "created both files" (:last-worker-summary result)))))
        (finally
          (cleanup-dir dir))))))

(deftest sh-test-pattern-test
  (testing "io/sh-test bakes a shell command into a reusable Spell thunk"
    (let [thunk (eval/invoke-fn sh-test ["echo hello"])]
      (is (= true (:spell/fn thunk)))
      (with-redefs [sio/sh (fn [cmd & _]
                             {:exit 0 :out (str "ran " cmd) :err ""})]
        (let [result (binding [eval/*spell-env* {'io (assoc sio/io-namespace :sh sio/sh)}]
                       (eval/invoke-fn thunk []))]
          (is (= true (:pass result)))
          (is (str/includes? (:output result) "COMMAND: echo hello"))
          (is (str/includes? (:output result) "EXIT: 0")))))))

(deftest fix-loop-thunk-error-handling-test
  (testing "thunk test errors reprompt the reflector without calling the worker"
    (let [dir (create-temp-git-repo {})
          worker-calls (atom 0)
          reflect-count (atom 0)
          reflector-msgs (atom [])
          send-await-fn (fn [_handle msg]
                          (case (:kind msg)
                            :reflect (do
                                       (swap! reflect-count inc)
                                       (swap! reflector-msgs conj msg)
                                       (if (= 1 @reflect-count)
                                         {:resolved false
                                          :diagnosis "Investigate failing thunk."
                                          :test (fn [] (throw (Exception. "boom")))
                                          :panic false}
                                         {:panic true
                                          :diagnosis "Stopping after thunk failure"}))
                            :repair (do
                                      (swap! worker-calls inc)
                                      {:summary "saw thunk error"})
                            {:panic true :diagnosis "unexpected message"}))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-fix-loop
                        {:issue "Need target file"
                         :max-retries 1}
                        {'agents {:register (fn [handle _completion] handle)}
                         'blocking {:send-await send-await-fn}})]
            (is (nil? (:pass result)))
            (is (str/includes? (:fail result) "Stopping after thunk failure"))
            (is (= 0 @worker-calls))
            (is (= 2 @reflect-count))
            (is (str/includes? (:feedback (second @reflector-msgs))
                               "Test execution failed before any worker call"))
            (is (str/includes? (:feedback (second @reflector-msgs))
                               "THUNK ERROR"))))
        (finally
          (cleanup-dir dir))))))
