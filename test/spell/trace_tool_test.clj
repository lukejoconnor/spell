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

(deftest count-function-calls-dedupe-test
  (let [trace {:nodes [{:id 0 :program '(do (foo 1) (bar "x"))}
                       {:id 1 :program '(do (foo 1) (bar "x") (foo 2))}
                       {:id 2 :program '(do (foo 1) (bar "x") (foo 2) (foo 2))}]}
        fns #{'foo}]
    (testing "dedupe avoids inherited-prefix double counting"
      (is (= {'foo 3}
             (:counts (tt/count-function-calls trace {:fns fns :dedupe? true}))))
      (is (= 3
             (count (:instances (tt/count-function-calls trace {:fns fns :dedupe? true}))))))

    (testing "no-dedupe counts all repeated occurrences across nodes"
      (is (= {'foo 6}
             (:counts (tt/count-function-calls trace {:fns fns :dedupe? false}))))
      (is (= 6
             (count (:instances (tt/count-function-calls trace {:fns fns :dedupe? false}))))))))

(deftest count-function-calls-in-form-test
  (let [program '(do (foo 1) (foo 1) (bar "x"))]
    (is (= {'foo 2}
           (:counts (tt/count-function-calls-in-form program {:fns #{'foo}}))))
    (is (= 2
           (count (:instances (tt/count-function-calls-in-form program {:fns #{'foo}})))))))

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
