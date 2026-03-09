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
(def relay (:relay stdlib/patterns))
(def team (:team stdlib/patterns))
(def sh-test (:sh-test sio/io-namespace))
(def stub-ask-await (fn [fut] (deref (:ref fut) 5000 :timeout)))

(defn- run-fix-loop [opts env]
  (binding [eval/*builtins* (assoc eval/*builtins* '!ask-await stub-ask-await)
            eval/*spell-env* (merge {'strings stdlib/strings
                                     'io (assoc sio/io-namespace :sh sio/sh)}
                                    env)]
    (eval/invoke-fn fix-loop [opts])))

(defn- run-ralph [opts env]
  (binding [eval/*spell-env* (merge {'strings stdlib/strings
                                     'io (assoc sio/io-namespace :sh sio/sh)}
                                    env)]
    (eval/invoke-fn ralph [opts])))

(defn- run-relay [opts env]
  (binding [eval/*builtins* (assoc eval/*builtins* '!ask-await stub-ask-await)
            eval/*spell-env* (merge {'strings stdlib/strings
                                     'patterns stdlib/patterns}
                                    env)]
    (eval/invoke-fn relay [opts])))

(defn- run-team [opts env]
  (binding [eval/*builtins* (assoc eval/*builtins* '!ask-await stub-ask-await)
            eval/*spell-env* (merge {'strings stdlib/strings
                                     'patterns stdlib/patterns
                                     'io (assoc sio/io-namespace :sh sio/sh :exec sio/exec)}
                                    env)]
    (eval/invoke-fn team [opts])))

(defn- create-temp-git-repo
  [files]
  (let [dir (java.io.File/createTempFile "spell-fix-loop" "")]
    (.delete dir)
    (.mkdirs dir)
    (doseq [[path content] files]
      (spit (str dir "/" path) content))
    (doseq [[path _] files]
      (when (str/ends-with? path ".sh")
        (.setExecutable (java.io.File. (str dir "/" path)) true)))
    (sio/exec ["git" "init" (str dir)])
    (sio/exec ["git" "-C" (str dir) "config" "user.email" "test@example.com"])
    (sio/exec ["git" "-C" (str dir) "config" "user.name" "test"])
    (sio/exec ["git" "-C" (str dir) "add" "-A"])
    (sio/exec ["git" "-C" (str dir) "commit" "-m" "init"])
    (sio/exec ["git" "-C" (str dir) "branch" "-M" "main"])
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

(deftest team-pattern-conflict-resolution-test
  (testing "team executes dependency waves, resolves a merge conflict, and branches later work from integrated HEAD"
    (let [dir (create-temp-git-repo {"shared.txt" "base\n"})
          register-calls (atom [])
          planner-calls (atom 0)
          verifier-msgs (atom [])
          send-calls (atom [])
          worker-results (atom {})
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-fn (fn [target msg]
                    (swap! send-calls conj {:target target :msg msg})
                    (let [task-id (get-in msg [:task :id])
                          worktree (:worktree-path msg)]
                      (case task-id
                        :task-a
                        (spit (str worktree "/shared.txt") "alpha\n")

                        :task-b
                        (spit (str worktree "/shared.txt") "beta\n")

                        :task-c
                        (do
                          (is (= "alpha\nbeta\n" (slurp (str worktree "/shared.txt"))))
                          (spit (str worktree "/done.txt") "ok")))
                      (swap! worker-results assoc target {:summary (str "finished " (name task-id))})))
          send-await-fn (fn [handle msg]
                          (cond
                            (str/starts-with? (name handle) "team-planner-")
                            (do
                              (swap! planner-calls inc)
                              "[{:id :task-a :title \"Task A\" :context \"Write alpha\" :depends []}
                                {:id :task-b :title \"Task B\" :context \"Write beta\" :depends []}
                                {:id :task-c :title \"Task C\" :context \"Check merged file and add done\" :depends [:task-a :task-b]}]")

                            (str/starts-with? (name handle) "team-verifier-")
                            (do
                              (swap! verifier-msgs conj msg)
                              (if (= :conflict (:merge-status msg))
                                (do
                                  (spit (str (:integration-path msg) "/shared.txt") "alpha\nbeta\n")
                                  (sio/sh "git add shared.txt")
                                  {:approved true
                                   :summary "resolved shared.txt conflict"
                                   :commit-msg "team: resolve shared conflict"
                                   :panic false
                                   :resolved-conflict true})
                                {:approved true
                                 :summary (str "approved " (get-in msg [:task :id]))
                                 :commit-msg (str "team: " (name (get-in msg [:task :id])))
                                 :panic false
                                 :resolved-conflict false}))

                            :else
                            {:approved false :summary "unexpected handle"}))
          completion-promise-fn (fn [handle] handle)
          await-all-fn (fn [tokens]
                         (mapv #(get @worker-results %) tokens))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-team {:goal "Implement the shared file flow"
                                  :shared-context "Use shared.txt"
                                  :max-retries 1}
                                 {'agents {:register register-fn
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn
                                             :completion-promise completion-promise-fn
                                             :await-all await-all-fn}})
                end-branch (str/trim (:out (sio/sh "git rev-parse --abbrev-ref HEAD")))]
            (is (= :completed (:status result)))
            (is (= 1 @planner-calls))
            (is (= 5 (count @register-calls)))
            (is (= [:task-a :task-b :task-c]
                   (mapv #(get-in % [:msg :task :id]) @send-calls)))
            (is (= [:clean :conflict :clean]
                   (mapv :merge-status @verifier-msgs)))
            (is (= "alpha\nbeta\n" (slurp (str dir "/shared.txt"))))
            (is (= "ok" (slurp (str dir "/done.txt"))))
            (is (= (:branch result) end-branch))
            (is (every? #(= :completed (:status %)) (:tasks result)))
            (is (some #(str/includes? (:completion %) "planner agent in patterns/team")
                      @register-calls))
            (is (some #(str/includes? (:completion %) "verifier agent in patterns/team")
                      @register-calls))))
        (finally
          (cleanup-dir dir))))))

(deftest team-pattern-failure-cleans-up-test
  (testing "team returns to the original branch on total failure and blocks dependent tasks"
    (let [dir (create-temp-git-repo {"shared.txt" "base\n"})
          register-calls (atom [])
          send-calls (atom [])
          worker-results (atom {})
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-fn (fn [target msg]
                    (swap! send-calls conj {:target target :msg msg})
                    (spit (str (:worktree-path msg) "/rejected.txt") "nope")
                    (swap! worker-results assoc target {:summary "made rejected change"}))
          send-await-fn (fn [handle _msg]
                          (if (str/starts-with? (name handle) "team-planner-")
                            "[{:id :task-a :title \"Task A\" :context \"Create rejected.txt\" :depends []}
                              {:id :task-b :title \"Task B\" :context \"Depends on task A\" :depends [:task-a]}]"
                            {:approved false
                             :summary "rejecting merge for test"
                             :commit-msg "unused"
                             :panic false
                             :resolved-conflict false}))
          completion-promise-fn (fn [handle] handle)
          await-all-fn (fn [tokens]
                         (mapv #(get @worker-results %) tokens))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [orig-branch (str/trim (:out (sio/sh "git rev-parse --abbrev-ref HEAD")))
                result (run-team {:goal "Try a failing task"
                                  :max-retries 1}
                                 {'agents {:register register-fn
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn
                                             :completion-promise completion-promise-fn
                                             :await-all await-all-fn}})
                end-branch (str/trim (:out (sio/sh "git rev-parse --abbrev-ref HEAD")))]
            (is (= :failed (:status result)))
            (is (nil? (:branch result)))
            (is (= orig-branch end-branch))
            (is (= [:task-a] (mapv #(get-in % [:msg :task :id]) @send-calls)))
            (is (= [:failed :failed] (mapv :status (:tasks result))))
            (is (not (.exists (java.io.File. (str dir "/rejected.txt")))))
            (is (str/blank? (:out (sio/sh "git status --porcelain"))))
            (is (= 2 (count (:failed result))))
            (is (= 3 (count @register-calls)))))
        (finally
          (cleanup-dir dir))))))

(deftest team-pattern-worker-commit-failure-preserves-worktree-test
  (testing "team fails safely and preserves the worker worktree when the worker commit is rejected"
    (let [dir (create-temp-git-repo {"base.txt" "base\n"})
          preserved-worktree (atom nil)
          verifier-calls (atom 0)
          original-exec sio/exec
          register-fn (fn [handle completion] handle)
          send-fn (fn [target msg]
                    (reset! preserved-worktree (:worktree-path msg))
                    (spit (str (:worktree-path msg) "/worker.txt") "edited\n"))
          send-await-fn (fn [handle _msg]
                          (if (str/starts-with? (name handle) "team-planner-")
                            "[{:id :task-a :title \"Task A\" :context \"Write worker.txt\" :depends []}]"
                            (do
                              (swap! verifier-calls inc)
                              {:approved true
                               :summary "unexpected verifier call"
                               :commit-msg "unused"
                               :panic false
                               :resolved-conflict false})))
          exec-fn (fn [args]
                    (if (and (= "git" (first args))
                             (= "-C" (second args))
                             (= @preserved-worktree (nth args 2 nil))
                             (= "commit" (nth args 3 nil)))
                      {:exit 1 :out "" :err "hook rejected commit"}
                      (original-exec args)))
          completion-promise-fn (fn [handle] handle)
          await-all-fn (fn [tokens]
                         (mapv (fn [_] {:summary "wrote worker.txt"}) tokens))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-team {:goal "Trigger a worker commit failure"
                                  :max-retries 2}
                                 {'agents {:register register-fn
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn
                                             :completion-promise completion-promise-fn
                                             :await-all await-all-fn}
                                  'io (assoc sio/io-namespace
                                             :sh sio/sh
                                             :exec exec-fn)})
                task (first (:tasks result))]
            (reset! preserved-worktree (:worktree task))
            (is (= :failed (:status result)))
            (is (= 0 @verifier-calls))
            (is (= :failed (:status task)))
            (is (str/includes? (:error task) "Worker commit failed"))
            (is (str/includes? (:error task) (:worktree task)))
            (is (.exists (java.io.File. (str (:worktree task) "/worker.txt"))))
            (is (not (.exists (java.io.File. (str dir "/worker.txt")))))))
        (finally
          (when @preserved-worktree
            (sio/exec ["git" "-C" dir "worktree" "remove" "--force" @preserved-worktree]))
          (cleanup-dir dir))))))

(deftest team-pattern-plan-validation-reprompt-test
  (testing "team rejects out-of-order dependencies and re-prompts the planner"
    (let [dir (create-temp-git-repo {"base.txt" "base\n"})
          planner-msgs (atom [])
          planner-calls (atom 0)
          send-fn (fn [_target msg]
                    (spit (str (:worktree-path msg) "/done.txt") "ok"))
          send-await-fn (fn [handle msg]
                          (cond
                            (str/starts-with? (name handle) "team-planner-")
                            (do
                              (swap! planner-calls inc)
                              (swap! planner-msgs conj msg)
                              (if (= 1 @planner-calls)
                                "[{:id :task-b :title \"Task B\" :context \"Depends on task A\" :depends [:task-a]}
                                  {:id :task-a :title \"Task A\" :context \"Create done.txt\" :depends []}]"
                                "[{:id :task-a :title \"Task A\" :context \"Create done.txt\" :depends []}
                                  {:id :task-b :title \"Task B\" :context \"Check done.txt\" :depends [:task-a]}]"))

                            (str/starts-with? (name handle) "team-verifier-")
                            {:approved true
                             :summary "approved"
                             :commit-msg (str "team: " (name (get-in msg [:task :id])))
                             :panic false
                             :resolved-conflict false}

                            :else
                            {:approved false
                             :summary "unexpected handle"
                             :panic true
                             :resolved-conflict false}))
          completion-promise-fn (fn [handle] handle)
          await-all-fn (fn [tokens]
                         (mapv (fn [_] {:summary "done"}) tokens))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-team {:goal "Use planner feedback"
                                  :max-retries 1}
                                 {'agents {:register (fn [handle completion] handle)
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn
                                             :completion-promise completion-promise-fn
                                             :await-all await-all-fn}})]
            (is (= :completed (:status result)))
            (is (= 2 @planner-calls))
            (is (str/includes? (:feedback (second @planner-msgs))
                               "Tasks must be returned in dependency order"))))
        (finally
          (cleanup-dir dir))))))

(deftest team-pattern-verifier-panic-test
  (testing "team fails fast when the verifier panics instead of retrying the task"
    (let [dir (create-temp-git-repo {"base.txt" "base\n"})
          register-calls (atom [])
          send-calls (atom [])
          verifier-calls (atom 0)
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-fn (fn [target msg]
                    (swap! send-calls conj {:target target :msg msg})
                    (spit (str (:worktree-path msg) "/panic.txt") "bad\n"))
          send-await-fn (fn [handle msg]
                          (if (str/starts-with? (name handle) "team-planner-")
                            "[{:id :task-a :title \"Task A\" :context \"Write panic.txt\" :depends []}]"
                            (do
                              (swap! verifier-calls inc)
                              {:approved false
                               :summary "panic: fail immediately"
                               :commit-msg "unused"
                               :panic true
                               :resolved-conflict false})))
          completion-promise-fn (fn [handle] handle)
          await-all-fn (fn [tokens]
                         (mapv (fn [_] {:summary "panic change"}) tokens))]
      (try
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-team {:goal "Panic after rejection"
                                  :max-retries 5}
                                 {'agents {:register register-fn
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn
                                             :completion-promise completion-promise-fn
                                             :await-all await-all-fn}})
                verifier-prompt (some #(when (str/includes? (:completion %) "verifier agent in patterns/team")
                                         (:completion %))
                                      @register-calls)]
            (is (= :failed (:status result)))
            (is (= 1 @verifier-calls))
            (is (= 1 (count @send-calls)))
            (is (= :failed (:status (first (:tasks result)))))
            (is (= "panic: fail immediately" (:error (first (:tasks result)))))
            (is (str/includes? verifier-prompt "fresh worker/worktree"))
            (is (str/includes? verifier-prompt "Set :panic true only when the task should fail immediately"))))
        (finally
          (cleanup-dir dir))))))

(deftest team-pattern-approved-commit-message-is-not-shell-expanded-test
  (testing "approved commit messages are passed literally without shell expansion"
    (let [dir (create-temp-git-repo {"base.txt" "base\n"})
          sentinel (str (java.io.File/createTempFile "spell-team-shell" ".txt"))
          malicious-msg (str "team: literal $(touch " sentinel ")")
          send-fn (fn [_target msg]
                    (spit (str (:worktree-path msg) "/safe.txt") "ok\n"))
          send-await-fn (fn [handle msg]
                          (if (str/starts-with? (name handle) "team-planner-")
                            "[{:id :task-a :title \"Task A\" :context \"Write safe.txt\" :depends []}]"
                            {:approved true
                             :summary "approved"
                             :commit-msg malicious-msg
                             :panic false
                             :resolved-conflict false}))
          completion-promise-fn (fn [handle] handle)
          await-all-fn (fn [tokens]
                         (mapv (fn [_] {:summary "safe change"}) tokens))]
      (try
        (.delete (java.io.File. sentinel))
        (with-redefs [sio/sh (sh-in-dir dir)]
          (let [result (run-team {:goal "Use literal commit message"
                                  :max-retries 1}
                                 {'agents {:register (fn [handle completion] handle)
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn
                                             :completion-promise completion-promise-fn
                                             :await-all await-all-fn}})
                last-msg (:out (sio/exec ["git" "-C" dir "log" "-1" "--pretty=%B"]))]
            (is (= :completed (:status result)))
            (is (not (.exists (java.io.File. sentinel))))
            (is (= malicious-msg last-msg))))
        (finally
          (.delete (java.io.File. sentinel))
          (cleanup-dir dir))))))

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

(deftest relay-solved-first-round-test
  (testing "relay returns a confirmed first-round solution and records worker handles"
    (let [register-calls (atom [])
          send-await-calls (atom [])
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (swap! send-await-calls conj {:handle handle :msg msg})
                          (if (str/starts-with? (name handle) "relay-worker-")
                            {:status :solved
                             :report "Computed the product directly."
                             :answer 42}
                            {:confirmed true
                             :feedback "Checked independently and confirmed."}))
          result (run-relay {:problem "What is 6 * 7?"
                             :max-rounds 3}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          report (first (:rounds result))]
      (is (= true (:solved result)))
      (is (= 42 (:answer result)))
      (is (= 1 (count (:rounds result))))
      (is (= 0 (:round report)))
      (is (= :solved (:status report)))
      (is (= "Computed the product directly." (:report report)))
      (is (= (-> @register-calls first :handle) (:worker-handle report)))
      (is (= 2 (count @register-calls)))
      (is (= 2 (count @send-await-calls)))
      (is (= :solve (get-in (first @send-await-calls) [:msg :kind])))
      (is (= 0 (get-in (first @send-await-calls) [:msg :round])))
      (is (= [] (get-in (first @send-await-calls) [:msg :previous-reports])))
      (is (= :verify (get-in (second @send-await-calls) [:msg :kind])))
      (is (= report (get-in (second @send-await-calls) [:msg :candidate-report])))
      (is (str/includes? (-> @register-calls first :completion) "may message previous workers"))
      (is (str/includes? (-> @register-calls first :completion) "{:kind :clarify :question str}"))
      (is (str/includes? (-> @register-calls first :completion) "{:status :clarified :report"))
      (is (str/includes? (-> @register-calls first :completion) "Do not continue your completion after that reply"))
      (is (str/includes? (-> @register-calls second :completion) "may message previous workers"))
      (is (str/includes? (-> @register-calls second :completion) "{:kind :clarify :question"))
      (is (str/includes? (-> @register-calls second :completion) "{:status :clarified :report")))))

(deftest relay-progress-then-solved-test
  (testing "relay accumulates prior reports and uses a fresh worker each round"
    (let [register-calls (atom [])
          worker-msgs (atom [])
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (if (str/starts-with? (name handle) "relay-worker-")
                            (do
                              (swap! worker-msgs conj {:handle handle :msg msg})
                              (case (:round msg)
                                0 {:status :progress
                                   :report "Tried substitution; not enough yet."}
                                {:status :solved
                                 :report "Second approach succeeds."
                                 :answer 9}))
                            {:confirmed true
                             :feedback "Verified the second approach."}))
          result (run-relay {:problem "Solve the toy problem"
                             :max-rounds 3}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          reports (:rounds result)
          worker-handles (mapv :handle (filter #(str/starts-with? (name (:handle %)) "relay-worker-")
                                               @register-calls))]
      (is (= true (:solved result)))
      (is (= 9 (:answer result)))
      (is (= 2 (count reports)))
      (is (= [:progress :solved] (mapv :status reports)))
      (is (= 2 (count worker-handles)))
      (is (= 2 (count (set worker-handles))))
      (is (= worker-handles (mapv :worker-handle reports)))
      (is (= [(first reports)]
             (get-in (second @worker-msgs) [:msg :previous-reports]))))))

(deftest relay-verification-rejects-then-continues-test
  (testing "relay re-registers the verifier per attempt and carries rejection reports forward"
    (let [register-calls (atom [])
          worker-msgs (atom [])
          verifier-msgs (atom [])
          verifier-call-count (atom 0)
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (if (str/starts-with? (name handle) "relay-worker-")
                            (do
                              (swap! worker-msgs conj {:handle handle :msg msg})
                              (case (:round msg)
                                0 {:status :solved
                                   :report "I think the answer is 10."
                                   :answer 10}
                                {:status :solved
                                 :report "Recomputed carefully; answer is 12."
                                 :answer 12}))
                            (do
                              (swap! verifier-msgs conj {:handle handle :msg msg})
                              (case (swap! verifier-call-count inc)
                                1 "not a map"
                                2 {:confirmed false
                                   :feedback "Answer 10 is inconsistent with the problem constraints."}
                                {:confirmed true
                                 :feedback "Confirmed 12 independently."}))))
          result (run-relay {:problem "Find the correct answer"
                             :max-rounds 4}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          reports (:rounds result)
          verifier-handles (mapv :handle (filter #(str/starts-with? (name (:handle %)) "relay-verifier-")
                                                 @register-calls))
          rejected-report (second reports)]
      (is (= true (:solved result)))
      (is (= 12 (:answer result)))
      (is (= [:solved :rejected :solved] (mapv :status reports)))
      (is (= 3 (count verifier-handles)))
      (is (= 3 (count (set verifier-handles))))
      (is (= "Answer 10 is inconsistent with the problem constraints."
             (:report rejected-report)))
      (is (= (:worker-handle (first reports)) (:worker-handle rejected-report)))
      (is (= (second verifier-handles) (:verifier-handle rejected-report)))
      (is (str/includes? (get-in (second @verifier-msgs) [:msg :feedback])
                         "Verifier must return"))
      (is (= [(first reports) rejected-report]
             (get-in (second @worker-msgs) [:msg :previous-reports]))))))

(deftest relay-invalid-report-and-max-rounds-test
  (testing "relay normalizes invalid worker output into stuck reports and stops at max rounds"
    (let [register-calls (atom [])
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [_handle msg]
                          (case (:round msg)
                            0 "garbage response"
                            "{:status :solved :report \"missing answer\"}"))
          result (run-relay {:problem "Keep trying"
                             :max-rounds 2}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          reports (:rounds result)]
      (is (= false (:solved result)))
      (is (= 2 (count reports)))
      (is (= [:stuck :stuck] (mapv :status reports)))
      (is (= 2 (count @register-calls)))
      (is (= 2 (count (set (map :worker-handle reports)))))
      (is (str/includes? (:report (first reports)) "garbage response"))
      (is (str/includes? (:report (second reports)) ":answer is required")))))
