(ns spell.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.core :refer [run-spell]]
            [spell.tools :refer [default-tools]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; read-name tool tests
;; =============================================================================

(deftest read-name-test
  (testing "read-name reads from name.txt"
    ;; Create a temporary name.txt file
    (spit "name.txt" "Alice")
    (try
      (is (= "Alice" (run-spell '(read-name))))
      (finally
        (io/delete-file "name.txt"))))

  (testing "read-name trims whitespace"
    (spit "name.txt" "  Bob  \n")
    (try
      (is (= "Bob" (run-spell '(read-name))))
      (finally
        (io/delete-file "name.txt"))))

  (testing "read-name throws when file missing"
    (is (thrown-with-msg? Exception #"name.txt not found"
          (run-spell '(read-name)))))

  (testing "read-name can be used in expressions"
    (spit "name.txt" "Charlie")
    (try
      (is (= "Hello, Charlie!" (run-spell '(cat "Hello, " (read-name) "!"))))
      (finally
        (io/delete-file "name.txt")))))

;; =============================================================================
;; bash tool tests
;; =============================================================================

(deftest bash-test
  (testing "bash returns a map with :exit :out :err"
    (let [result (run-spell '(bash "echo hello"))]
      (is (map? result))
      (is (= 0 (:exit result)))
      (is (= "hello" (:out result)))
      (is (= "" (:err result)))))

  (testing "bash captures exit code on failure"
    (let [result (run-spell '(bash "exit 42"))]
      (is (= 42 (:exit result)))))

  (testing "bash captures stderr"
    (let [result (run-spell '(bash "echo oops >&2; exit 1"))]
      (is (= 1 (:exit result)))
      (is (= "oops" (:err result)))))

  (testing "bash output accessible with keywords"
    (is (= "hi" (run-spell '(:out (bash "echo hi")))))
    (is (= 0 (run-spell '(:exit (bash "true"))))))

  (testing "bash output usable with get"
    (is (= "world" (run-spell '(get (bash "echo world") :out)))))

  (testing "bash output usable in expressions"
    (is (= "result: ok"
           (run-spell '(cat "result: " (:out (bash "echo ok")))))))

  (testing "bash timeout"
    (binding [spell.tools/*bash-timeout* 1]
      (let [result (run-spell '(bash "sleep 10"))]
        (is (= -1 (:exit result)))
        (is (str/includes? (:err result) "timed out"))))))

;; =============================================================================
;; strip-parens and reopen tests
;; =============================================================================

(deftest strip-parens-test
  (testing "removes trailing close-parens"
    (is (= "(do (+ 1 2" (run-spell '(strip-parens 2 "(do (+ 1 2))")))))

  (testing "removes one paren"
    (is (= "(+ 1 2" (run-spell '(strip-parens 1 "(+ 1 2)")))))

  (testing "zero parens is identity"
    (is (= "(+ 1 2)" (run-spell '(strip-parens 0 "(+ 1 2)")))))

  (testing "ignores trailing whitespace"
    (is (= "(do (+ 1 2" (run-spell '(strip-parens 2 "(do (+ 1 2))  \n")))))

  (testing "throws when not enough parens"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not enough closing parens"
          (run-spell '(strip-parens 5 "))")))))

  (testing "throws on non-paren character"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected"
          (run-spell '(strip-parens 2 "(+ 1 2)x)"))))))

(deftest reopen-test
  (testing "strips exactly 3 trailing parens"
    (is (= "(outer (do (+ 1 2" (run-spell '(reopen "(outer (do (+ 1 2)))")))))

  (testing "works on completion-like prefix with spell-eval"
    (is (= "(def interior (spell-eval (do " (run-spell '(reopen "(def interior (spell-eval (do )))")))))

  (testing "works on quine-style prefix"
    (is (= "(quine completion (spell-eval (do "
           (run-spell '(reopen "(quine completion (spell-eval (do )))"))))))

;; =============================================================================
;; default-tools tests
;; =============================================================================

(deftest default-tools-test
  (testing "default-tools contains read-name, bash, and file tools"
    (is (= 6 (count default-tools)))
    (is (= #{'read-name 'bash 'read-file 'write-file 'str-replace 'replace-lines}
           (set (map :name default-tools)))))

  (testing "tool definitions have required keys"
    (doseq [tool default-tools]
      (is (contains? tool :name))
      (is (contains? tool :fn))
      (is (contains? tool :doc)))))
