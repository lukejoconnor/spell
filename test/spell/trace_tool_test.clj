(ns spell.trace-tool-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.trace-tool :as tt]))

(deftest skeletonize-form-test
  (testing "keeps short strings while preserving program structure"
    (let [program '(do (def msg "hello") (think "long analysis") (!print msg))
          skel (tt/skeletonize-form program)]
      (is (= 'do (first skel)))
      (is (= '(def msg "hello") (second skel)))
      (is (= '(think "long analysis") (nth skel 2)))
      (is (= '(!print msg) (nth skel 3))))))

(deftest skeletonize-form-truncation-test
  (let [s "abcdefghijklmnopqrstuvwxyz1234567890"
        form `(think ~s)]
    (testing "default truncates to 32 chars with ellipsis"
      (is (= `(think "abcdefghijklmnopqrstuvwxyz123456…")
             (tt/skeletonize-form form))))
    (testing "configurable truncation length"
      (is (= `(think "abcdefghij…")
             (tt/skeletonize-form form {:max-string-chars 10}))))
    (testing "disable truncation with -1"
      (is (= form
             (tt/skeletonize-form form {:max-string-chars -1})))))) 

(deftest response-forms-test
  (testing "parses response suffix forms directly"
    (is (= '[(foo 1) (bar "x")]
           (tt/response-forms "(foo 1) (bar \"x\")"))))
  (testing "strips trailing prompt-closing delimiters until suffix parses"
    (is (= '[(def x 42)]
           (tt/response-forms "(def x 42)))"))))
  (testing "preserves top-level form boundaries for quoted forms"
    (is (= '[(think "a") (quote (!call-now x))]
           (tt/response-forms "(think \"a\") '(!call-now x)")))))

(deftest count-function-calls-all-nodes-test
  (let [trace {:nodes [{:id 0
                        :program '(do (foo 1))
                        :response "(foo 1)"}
                       {:id 1
                        :program '(do (foo 1) (bar "x") (foo 2))
                        :response "(bar \"x\") (foo 2)"}
                       {:id 2
                        :program '(do (foo 1) (bar "x") (foo 2) (foo 2))
                        :response "(foo 2)"}]}
        fns #{'foo}]
    (testing "counts response suffixes only, not inherited program prefixes"
      (is (= {'foo 3}
             (:counts (tt/count-function-calls trace {:fns fns}))))
      (is (= 3
             (count (:instances (tt/count-function-calls trace {:fns fns}))))))))

(deftest count-function-calls-response-only-test
  (let [trace {:nodes [{:id 0
                        :program '(quine completion (eval (do (foo 1) (bar "x"))))
                        :response "(foo 1) (bar \"x\")"}
                       {:id 1
                        :program '(do (foo 1) (bar "x") (foo 2))
                        :response "(foo 2)"}]}
        fns #{'foo 'bar}]
    (testing "counts only stored response forms regardless of full program shape"
      (let [{:keys [counts instances]} (tt/count-function-calls trace {:fns fns})]
        (is (= {'foo 2 'bar 1} counts)
            "foo 1 from node 0 response + foo 2 from node 1 response; bar only in node 0 response")
        (is (= [[0] [1] [0]]
               (mapv :path instances)))))))

(deftest count-function-calls-repeated-response-form-test
  (let [trace {:nodes [{:id 0
                        :program '(do (foo 1))
                        :response "(foo 1)"}
                       {:id 1
                        :program '(do (foo 1) (foo 1))
                        :response "(foo 1)"}]}
        fns #{'foo}]
    (testing "same local call in different responses is counted twice"
      (is (= {'foo 2}
             (:counts (tt/count-function-calls trace {:fns fns})))))))

(deftest count-function-calls-in-forms-test
  (let [forms (tt/response-forms "(foo 1) (foo 1) (bar \"x\")")]
    (is (= {'foo 2}
           (:counts (tt/count-function-calls-in-forms forms {:fns #{'foo}}))))
    (is (= 2
           (count (:instances (tt/count-function-calls-in-forms forms {:fns #{'foo}})))))))

(deftest collect-rethinks-test
  (let [program '(do
                   (think "first")
                   (rethink "drop that")
                   (def x 1)
                   (rethink "drop x"))]
    (is (= 2 (count (tt/collect-rethinks program))))
    (let [[r1 r2] (tt/collect-rethinks program)]
      (is (= '(rethink "drop that") (:rethink r1)))
      (is (= '(think "first") (:previous r1)))
      (is (= '(rethink "drop x") (:rethink r2)))
      (is (= '(def x 1) (:previous r2))))))

(deftest collect-trace-rethinks-response-only-test
  (let [trace {:nodes [{:id 0
                        :program '(quine completion
                                    (eval (do (think "a") (rethink "drop a"))))
                        :response "(think \"a\") (rethink \"drop a\")"}
                       {:id 1
                        :program '(do (think "a") (rethink "drop a") (think "b") (rethink "drop b"))
                        :response "(think \"b\") (rethink \"drop b\")"}]}]
    (testing "inherited rethink state in full programs is not re-counted"
      (let [items (tt/collect-trace-rethinks trace)]
        (is (= 2 (count items)))
        (is (= #{0 1} (set (map :node-id items))))
        (is (= [[2] [2]]
               (mapv :path items)))))))

(deftest select-node-default-prefers-latest-default-program-test
  (let [trace {:nodes [{:id 0 :variant :default :program '(do 1)}
                       {:id 1 :variant :leaf :program '(do 2)}
                       {:id 2 :variant :default :program '(do 3)}]}]
    (is (= 2 (:id (tt/select-node trace nil))))))

(deftest results-error-resolution-test
  (let [rows [{:item_id "ok-1" :status "ok" :metadata {}}
              {:item_id "bad-2"
               :status "error"
               :error_type "missing-tool-call"
               :error_message "tool missing"
               :metadata {:raw {:trace_dir "traces/2026-03-01T20-37-56"}}}]
        row (tt/last-error-record rows)]
    (is (= "bad-2" (:item_id row)))
    (is (= "traces/2026-03-01T20-37-56" (tt/trace-dir-from-record row)))))
