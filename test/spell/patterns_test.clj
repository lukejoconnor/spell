(ns spell.patterns-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.stdlib :as stdlib]
            [spell.runtime :as runtime]
            [spell.io :as sio]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

(def fix-loop (:fix-loop stdlib/patterns))

(defn- create-temp-git-repo
  "Create a temp directory with an initialized git repo. Returns path string.
   Commits a test script that checks for the given file."
  [test-file-name]
  (let [dir (java.io.File/createTempFile "spell-fix-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (spit (str dir "/run_tests.sh")
          (str "#!/bin/bash\ntest -f " test-file-name))
    (sio/sh (str "cd " dir " && git init"
                 " && chmod +x run_tests.sh"
                 " && git add run_tests.sh"
                 " && git commit -m init"))
    (str dir)))

(defn- create-temp-git-repo-multi
  "Like create-temp-git-repo but checks for multiple files."
  [& file-names]
  (let [dir (java.io.File/createTempFile "spell-fix-test" "")
        checks (str/join " && " (map #(str "test -f " %) file-names))]
    (.delete dir)
    (.mkdirs dir)
    (spit (str dir "/run_tests.sh")
          (str "#!/bin/bash\n" checks))
    (sio/sh (str "cd " dir " && git init"
                 " && chmod +x run_tests.sh"
                 " && git add run_tests.sh"
                 " && git commit -m init"))
    (str dir)))

(defn- cleanup-dir [dir]
  (sio/sh (str "rm -rf " dir)))

(defn- sh-in-dir
  "Create a wrapper around sio/sh that runs commands in the given directory.
   Captures the original sio/sh before with-redefs replaces it."
  [dir]
  (let [original-sh sio/sh]
    (fn [cmd & more]
      (apply original-sh (str "cd " dir " && " cmd) more))))

;; =============================================================================
;; Happy path: worker fixes on first attempt
;; =============================================================================

(deftest fix-loop-happy-path-test
  (testing "returns {:pass true} when worker fixes on first attempt"
    (let [dir (create-temp-git-repo "fixed.txt")]
      (try
        (let [reflector-calls (atom [])
              worker-calls (atom [])

              reflector (fn [prompt]
                          (swap! reflector-calls conj prompt)
                          {:diagnosis "Create fixed.txt to make the test pass"
                           :keep-changes false
                           :panic false})

              worker (fn [prompt]
                       (swap! worker-calls conj prompt)
                       (spit (str dir "/fixed.txt") "fixed")
                       "done")]

          (with-redefs [sio/sh (sh-in-dir dir)]
            (let [result (fix-loop {:test "./run_tests.sh"
                                    :issue "Tests fail because fixed.txt is missing"
                                    :reflector reflector
                                    :worker worker
                                    :max-retries 3})]
              (is (= true (:pass result)))
              (is (= 1 (count @reflector-calls)))
              (is (= 1 (count @worker-calls)))
              (is (str/includes? (first @worker-calls) "Create fixed.txt")))))
        (finally
          (cleanup-dir dir))))))

;; =============================================================================
;; Tests already passing: returns {:pass true} immediately
;; =============================================================================

(deftest fix-loop-already-passing-test
  (testing "returns {:pass true} immediately when tests already pass"
    (let [dir (java.io.File/createTempFile "spell-fix-test" "")]
      (.delete dir)
      (.mkdirs dir)
      ;; Test that always passes
      (spit (str dir "/run_tests.sh") "#!/bin/bash\nexit 0")
      (sio/sh (str "cd " dir " && git init && chmod +x run_tests.sh"
                   " && git add run_tests.sh && git commit -m init"))
      (try
        (let [reflector-calls (atom 0)
              reflector (fn [_] (swap! reflector-calls inc)
                          {:diagnosis "" :keep-changes false :panic false})]

          (with-redefs [sio/sh (sh-in-dir dir)]
            (let [result (fix-loop {:test "./run_tests.sh"
                                    :issue "Some issue"
                                    :reflector reflector
                                    :worker (fn [_] nil)
                                    :max-retries 3})]
              (is (= true (:pass result)))
              (is (= 0 @reflector-calls)))))
        (finally
          (cleanup-dir (str dir)))))))

;; =============================================================================
;; Retry with keep-changes: partial fix, then complete fix
;; =============================================================================

(deftest fix-loop-retry-keep-changes-test
  (testing "keeps changes when reflector says to, continues fixing"
    (let [dir (create-temp-git-repo-multi "file-a.txt" "file-b.txt")]
      (try
        (let [worker-call-count (atom 0)
              reflector-call-count (atom 0)

              worker (fn [_prompt]
                       (let [n (swap! worker-call-count inc)]
                         (case n
                           1 (spit (str dir "/file-a.txt") "a")
                           2 (spit (str dir "/file-b.txt") "b"))
                         "done"))

              reflector (fn [_prompt]
                          (let [n (swap! reflector-call-count inc)]
                            (case n
                              1 {:diagnosis "Create file-a.txt and file-b.txt"
                                 :keep-changes false
                                 :panic false}
                              2 {:diagnosis "file-a.txt is correct, now create file-b.txt"
                                 :keep-changes true
                                 :panic false})))]

          (with-redefs [sio/sh (sh-in-dir dir)]
            (let [result (fix-loop {:test "./run_tests.sh"
                                    :issue "Need both file-a.txt and file-b.txt"
                                    :reflector reflector
                                    :worker worker
                                    :max-retries 5})]
              (is (= true (:pass result)))
              (is (= 2 @worker-call-count))
              (is (= 2 @reflector-call-count)))))
        (finally
          (cleanup-dir dir))))))

;; =============================================================================
;; Retry with discard: wrong fix, revert, try again
;; =============================================================================

(deftest fix-loop-retry-discard-test
  (testing "discards changes when reflector says to, starts fresh"
    (let [dir (create-temp-git-repo "correct.txt")]
      (try
        (let [worker-call-count (atom 0)

              worker (fn [_prompt]
                       (let [n (swap! worker-call-count inc)]
                         (case n
                           1 (spit (str dir "/wrong.txt") "wrong")
                           2 (spit (str dir "/correct.txt") "correct"))
                         "done"))

              reflector-call-count (atom 0)
              reflector (fn [_prompt]
                          (let [n (swap! reflector-call-count inc)]
                            (case n
                              1 {:diagnosis "Create correct.txt"
                                 :keep-changes false
                                 :panic false}
                              2 {:diagnosis "Wrong file created. Create correct.txt instead"
                                 :keep-changes false
                                 :panic false})))]

          (with-redefs [sio/sh (sh-in-dir dir)]
            (let [result (fix-loop {:test "./run_tests.sh"
                                    :issue "Need correct.txt"
                                    :reflector reflector
                                    :worker worker
                                    :max-retries 5})]
              (is (= true (:pass result)))
              (is (= 2 @worker-call-count))
              ;; wrong.txt should have been cleaned by git clean -fd
              (is (not (.exists (io/file (str dir "/wrong.txt"))))))))
        (finally
          (cleanup-dir dir))))))

;; =============================================================================
;; Panic on initial diagnosis
;; =============================================================================

(deftest fix-loop-panic-test
  (testing "returns {:fail reason} when reflector panics on initial diagnosis"
    (let [dir (create-temp-git-repo "impossible.txt")]
      (try
        (let [reflector (fn [_prompt]
                          {:diagnosis "This is fundamentally unsolvable"
                           :keep-changes false
                           :panic true})
              worker-calls (atom 0)
              worker (fn [_] (swap! worker-calls inc) nil)]

          (with-redefs [sio/sh (sh-in-dir dir)]
            (let [result (fix-loop {:test "./run_tests.sh"
                                    :issue "Unsolvable issue"
                                    :reflector reflector
                                    :worker worker
                                    :max-retries 3})]
              (is (nil? (:pass result)))
              (is (string? (:fail result)))
              (is (str/includes? (:fail result) "unsolvable"))
              (is (= 0 @worker-calls)))))
        (finally
          (cleanup-dir dir))))))

;; =============================================================================
;; Panic after failed attempt
;; =============================================================================

(deftest fix-loop-panic-after-attempt-test
  (testing "returns {:fail reason} when reflector panics after a failed fix"
    (let [dir (create-temp-git-repo "impossible.txt")]
      (try
        (let [reflector-call-count (atom 0)
              reflector (fn [_prompt]
                          (let [n (swap! reflector-call-count inc)]
                            (case n
                              1 {:diagnosis "Try creating fix.txt"
                                 :keep-changes false
                                 :panic false}
                              2 {:diagnosis "After reviewing the diff, this is stuck"
                                 :keep-changes false
                                 :panic true})))
              worker (fn [_] (spit (str dir "/fix.txt") "attempt") nil)]

          (with-redefs [sio/sh (sh-in-dir dir)]
            (let [result (fix-loop {:test "./run_tests.sh"
                                    :issue "Failing tests"
                                    :reflector reflector
                                    :worker worker
                                    :max-retries 3})]
              (is (nil? (:pass result)))
              (is (string? (:fail result)))
              (is (str/includes? (:fail result) "stuck")))))
        (finally
          (cleanup-dir dir))))))

;; =============================================================================
;; Max retries exhausted
;; =============================================================================

(deftest fix-loop-max-retries-test
  (testing "returns {:fail reason} when max retries exhausted"
    (let [dir (create-temp-git-repo "impossible.txt")]
      (try
        (let [worker-calls (atom 0)
              worker (fn [_]
                       (swap! worker-calls inc)
                       (spit (str dir "/attempt.txt") (str "attempt-" @worker-calls))
                       nil)
              reflector-calls (atom 0)
              reflector (fn [_]
                          (let [n (swap! reflector-calls inc)]
                            {:diagnosis (str "Try harder (attempt " n ")")
                             :keep-changes false
                             :panic false}))]

          (with-redefs [sio/sh (sh-in-dir dir)]
            (let [result (fix-loop {:test "./run_tests.sh"
                                    :issue "Unfixable issue"
                                    :reflector reflector
                                    :worker worker
                                    :max-retries 2})]
              (is (nil? (:pass result)))
              (is (string? (:fail result)))
              (is (str/includes? (:fail result) "Max retries"))
              (is (= 2 @worker-calls)))))
        (finally
          (cleanup-dir dir))))))
