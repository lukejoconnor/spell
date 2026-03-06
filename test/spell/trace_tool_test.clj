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

(deftest classify-pruned-content-test
  (testing "classifies common pruned form shapes"
    (is (= "none" (tt/classify-pruned-content nil)))
    (is (= "def/string" (tt/classify-pruned-content '(def body "file content"))))
    (is (= "def/map" (tt/classify-pruned-content '(def payload {:ok true}))))
    (is (= "def/map" (tt/classify-pruned-content '(def payload (hash-map :ok true)))))
    (is (= "def/expr" (tt/classify-pruned-content '(def out (get payload :ok)))))
    (is (= "think" (tt/classify-pruned-content '(think "note"))))
    (is (= "other" (tt/classify-pruned-content '(+ 1 2))))))

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

(deftest summarize-rethinks-test
  (let [items [{:rethink '(rethink "!peek-now binding disappears: tmp")
                :previous '(def snapshot {:a 1})}
               {:rethink '(rethink "try another plan")
                :previous '(def content "hello")}]
        summary (#'tt/summarize-rethinks items)]
    (is (= 2 (:total summary)))
    (is (= 1 (:system-count summary)))
    (is (= 1 (:model-count summary)))
    (is (= (count (pr-str '(def snapshot {:a 1})))
           (:system-pruned summary)))
    (is (= (count (pr-str '(def content "hello")))
           (:model-pruned summary)))))

(deftest context-trajectory-items-test
  (let [tmp-dir (-> (java.nio.file.Files/createTempDirectory "trace-tool-test"
                                                              (make-array java.nio.file.attribute.FileAttribute 0))
                    (.toFile))
        file-0 (io/file tmp-dir "0000.spl")
        file-1 (io/file tmp-dir "0001.spl")
        file-2 (io/file tmp-dir "0002.spl")
        _ (spit file-0 (apply str (repeat 300 "a")))
        _ (spit file-1 (apply str (repeat 3500 "b")))
        _ (spit file-2 (apply str (repeat 400 "c")))
        trace {:nodes [{:id 0 :file "0000.spl" :program '(do (def x 1))}
                       {:id 1
                        :file "0001.spl"
                        :program '(do
                                    (def payload {:id 1 :tool "grep"})
                                    (rethink "!peek-now binding disappears: payload"))}
                       {:id 2
                        :file "0002.spl"
                        :program '(quine completion (eval (do (think "worker reset") 42)))}
                       {:id 3 :file "missing.spl" :program '(do (foo "bar"))}]}
        rows (#'tt/context-trajectory-items (.getPath tmp-dir) trace)
        fallback-size (count (pr-str '(do (foo "bar"))))]
    (is (= [300 3500 400 fallback-size] (mapv :chars rows)))
    (is (= [nil 3200 -3100 (- fallback-size 400)] (mapv :delta rows)))
    (is (= 1 (:rethink-count (second rows))))
    (is (pos? (:pruned-chars (second rows))))
    (is (true? (:agent-boundary? (nth rows 2))))
    (is (false? (:agent-boundary? (second rows))))))
