(ns spell.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.parse :refer [read-all]]))

;; =============================================================================
;; read-all tests
;; =============================================================================

(deftest read-all-test
  (testing "single form"
    (is (= ['(+ 1 2)] (read-all "(+ 1 2)"))))

  (testing "multiple forms"
    (is (= ['(def x 1) '(def y 2) '(+ x y)]
           (read-all "(def x 1) (def y 2) (+ x y)"))))

  (testing "empty string"
    (is (= [] (read-all ""))))

  (testing "do block followed by defs (call-now pattern)"
    (is (= ['(do (def response "hi") (def return 42))
            '(def files "result")]
           (read-all "(do (def response \"hi\") (def return 42))\n(def files \"result\")"))))

  (testing "mixed form types"
    (is (= [42 "hello" '(+ 1 2)]
           (read-all "42 \"hello\" (+ 1 2)")))))
