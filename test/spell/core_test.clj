(ns spell.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.core :as spell :refer [run-spell]]
            [spell.eval :as eval]
            [spell.io :as io]
            [clojure.java.io :as jio]
            [clojure.string :as str]))

(defmacro with-effects
  "Run body with effect namespaces merged into *builtins* (simulates eval's second pass)."
  [& body]
  `(binding [eval/*builtins* (merge eval/*builtins* spell/all-namespaces)]
     ~@body))

;; =============================================================================
;; io/sh tests (replaces bash tests)
;; =============================================================================

(deftest sh-test
  (with-effects
    (testing "io/sh returns a map with :exit :out :err"
      (let [result (run-spell '(io/sh "echo hello"))]
        (is (map? result))
        (is (= 0 (:exit result)))
        (is (= "hello" (:out result)))
        (is (= "" (:err result)))))

    (testing "io/sh captures exit code on failure"
      (let [result (run-spell '(io/sh "exit 42"))]
        (is (= 42 (:exit result)))))

    (testing "io/sh captures stderr"
      (let [result (run-spell '(io/sh "echo oops >&2; exit 1"))]
        (is (= 1 (:exit result)))
        (is (= "oops" (:err result)))))

    (testing "io/sh output accessible with keywords"
      (is (= "hi" (run-spell '(:out (io/sh "echo hi")))))
      (is (= 0 (run-spell '(:exit (io/sh "true"))))))

    (testing "io/sh output usable with get"
      (is (= "world" (run-spell '(get (io/sh "echo world") :out)))))

    (testing "io/sh output usable in expressions"
      (is (= "result: ok"
             (run-spell '(cat "result: " (:out (io/sh "echo ok")))))))

    (testing "io/sh timeout"
      (binding [io/*sh-timeout* 1]
        (let [result (run-spell '(io/sh "sleep 10"))]
          (is (= -1 (:exit result)))
          (is (str/includes? (:err result) "timed out")))))))

;; =============================================================================
;; io/read-file and io/write-file tests
;; =============================================================================

(deftest file-operations-test
  (with-effects
    (testing "io/write-file and io/read-file roundtrip"
      (let [test-file "/tmp/spell-test-file.txt"]
        (try
          (is (= {:ok test-file}
                 (run-spell (list 'io/write-file test-file "line1\nline2\nline3"))))
          (is (= "1: line1\n2: line2\n3: line3"
                 (run-spell (list 'io/read-file test-file))))
          (finally
            (jio/delete-file test-file true)))))

    (testing "io/read-file with line range"
      (let [test-file "/tmp/spell-test-range.txt"]
        (try
          (run-spell (list 'io/write-file test-file "a\nb\nc\nd\ne"))
          (is (= "2: b\n3: c"
                 (run-spell (list 'io/read-file test-file 2 4))))
          (finally
            (jio/delete-file test-file true)))))

    (testing "io/slurp and io/spit"
      (let [test-file "/tmp/spell-test-slurp.txt"]
        (try
          (is (= {:ok test-file}
                 (run-spell (list 'io/spit test-file "hello world"))))
          (is (= {:ok "hello world"}
                 (run-spell (list 'io/slurp test-file))))
          (finally
            (jio/delete-file test-file true)))))))

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
  (testing "returns the same quine when no extra forms are supplied"
    (is (= '(quine completion (eval (do (+ 1 2))))
           (run-spell '(reopen '(quine completion (eval (do (+ 1 2)))))))))

  (testing "splices extra forms into the quine do block"
    (is (= '(quine completion (eval (do (def x 1) (def y 2))))
           (run-spell '(reopen '(quine completion (eval (do (def x 1))))
                              (def y 2))))))

  (testing "treats appended expressions as source forms without evaluating them"
    (is (= ['(quine completion (eval (do (def x 1) x))) 9]
           (run-spell '(let [x 9
                             reopened (reopen '(quine completion (eval (do)))
                                              (def x 1)
                                              x)]
                         [reopened x])))))

  (testing "serialize-prefix turns a quine into an open prefix string"
    (is (= "(quine completion (eval (do (def x 1) "
           (run-spell '(serialize-prefix '(quine completion (eval (do (def x 1))))))))))
