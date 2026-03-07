(ns spell.trace-tool-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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

(deftest run-tool-mode-validation-test
  (testing "context-management modes have coherent option validation"
    (is (= {:exit 1
            :message "Choose either --rethinks or --context-trajectory, not both"}
           (tt/run-tool {:trace-dir "unused"
                         :rethinks true
                         :context-trajectory true}
                        "")))
    (is (= {:exit 1
            :message "Mode requires --trace-dir, --trace-root, or --results-jsonl"}
           (tt/run-tool {:context-trajectory true} "")))))

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

(deftest context-trajectory-items-response-only-test
  (let [tmp-dir (-> (java.nio.file.Files/createTempDirectory "trace-tool-test"
                                                              (make-array java.nio.file.attribute.FileAttribute 0))
                    (.toFile))
        file-0 (io/file tmp-dir "0000.spl")
        file-1 (io/file tmp-dir "0001.spl")
        file-2 (io/file tmp-dir "0002.spl")
        _ (spit file-0 (apply str (repeat 300 "a")))
        _ (spit file-1 (apply str (repeat 3500 "b")))
        _ (spit file-2 (apply str (repeat 400 "c")))
        trace {:nodes [{:id 0
                        :file "0000.spl"
                        :program '(do (think "a") (rethink "drop a"))
                        :response "(think \"a\") (rethink \"drop a\")"}
                       {:id 1
                        :file "0001.spl"
                        :program '(do
                                    (think "a")
                                    (rethink "drop a")
                                    (def payload {:id 1})
                                    (rethink "drop payload"))
                        :response "(def payload {:id 1}) (rethink \"drop payload\")"}
                       {:id 2
                        :file "0002.spl"
                        :program '(do
                                    (think "a")
                                    (rethink "drop a")
                                    (def payload {:id 1})
                                    (rethink "drop payload")
                                    (think "continue"))
                        :response ""}
                       {:id 3
                        :file "missing.spl"
                        :program '(do (foo "bar"))
                        :response nil}]}
        rows (#'tt/context-trajectory-items (.getPath tmp-dir) trace)
        fallback-size (count (pr-str '(do (foo "bar"))))]
    (is (= [300 3500 400 fallback-size] (mapv :chars rows)))
    (is (= [nil 3200 -3100 (- fallback-size 400)] (mapv :delta rows)))
    (is (= [1 1 0 0] (mapv :rethink-count rows)))
    (is (pos? (:pruned-chars (first rows))))
    (is (pos? (:pruned-chars (second rows))))
    (is (zero? (:pruned-chars (nth rows 2))))
    (is (zero? (:pruned-chars (nth rows 3))))))
