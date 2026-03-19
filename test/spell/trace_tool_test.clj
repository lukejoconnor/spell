(ns spell.trace-tool-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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
                   (prune)
                   (def x 1)
                   (rethink "drop that")
                   (prune 2))]
    (is (= 3 (count (tt/collect-rethinks program))))
    (let [[r1 r2 r3] (tt/collect-rethinks program)]
      (is (= 'prune (:kind r1)))
      (is (= '(prune) (:marker r1)))
      (is (= '(think "first") (:previous r1)))
      (is (= 'rethink (:kind r2)))
      (is (= '(rethink "drop that") (:marker r2)))
      (is (= '(def x 1) (:previous r2)))
      (is (= 'prune (:kind r3)))
      (is (= '(prune 2) (:marker r3)))
      (is (= '(rethink "drop that") (:previous r3))))))

(deftest collect-trace-rethinks-response-only-test
  (let [trace {:nodes [{:id 0
                        :program '(quine completion
                                    (eval (do (think "a") (prune))))
                        :response "(think \"a\") (prune)"}
                       {:id 1
                        :program '(do (think "a") (prune) (think "b") (rethink "drop b"))
                        :response "(think \"b\") (rethink \"drop b\")"}]}]
    (testing "inherited pruning state in full programs is not re-counted"
      (let [items (tt/collect-trace-rethinks trace)]
        (is (= 2 (count items)))
        (is (= #{0 1} (set (map :node-id items))))
        (is (= ['prune 'rethink]
               (mapv :kind items)))
        (is (= [[2] [2]]
               (mapv :path items)))))))

(deftest trace-summary-test
  (let [trace {:nodes [{:id 0
                        :depth 0
                        :program '(do
                                    (think "a")
                                    (prune)
                                    (rethink "drop a")
                                    (io/sh "ls")
                                    (globals/set :k 1)
                                    (defn helper [x] (math/sqrt x))
                                    (persist state))
                        :response "(think \"a\") (prune) (rethink \"drop a\") (io/sh \"ls\") (globals/set :k 1) (defn helper [x] (math/sqrt x)) (persist state)"}
                       {:id 1
                        :depth 1
                       :error "boom"
                        :program '(do
                                    (fn [y] (strings/replace y "a" "b"))
                                    (!compact)
                                    (future (io/slurp "foo")))
                        :response "(fn [y] (strings/replace y \"a\" \"b\")) (!compact) (future (io/slurp \"foo\"))"}
                       {:id 2
                        :depth 0
                       :program '(do
                                    (!ask-await worker)
                                    (agents/!ask worker "hi")
                                    (patterns/team {:goal "x"})
                                    (leaf-llm prompt)
                                    (!llm-self "continue")
                                    (agents/spawn llms/coder "prompt"))
                        :response "(!ask-await worker) (agents/!ask worker \"hi\") (patterns/team {:goal \"x\"}) (leaf-llm prompt) (!llm-self \"continue\") (agents/spawn llms/coder \"prompt\")"}
                       {:id 3
                        :depth 0
                        :error "fatal"
                        :program '(do (!print :done))
                        :response "(!print :done)"}]}
        summary (tt/trace-summary "traces/example" trace)
        pruned-think (count (pr-str '(think "a")))
        pruned-prune (count (pr-str '(prune)))]
    (is (= "traces/example" (:trace-dir summary)))
    (is (= 4 (:node-count summary)))
    (is (= {'think 1
            'prune 1
            'rethink 1
            '!compact 1
            '!llm-self 1
            '!ask-await 1
            'persist 1
            '!print 1
            'leaf-llm 1
            'future 1
            'defn 1
            'fn 1}
           (:tracked-counts summary)))
    (is (= {"sh" 1 "slurp" 1}
           (get-in summary [:namespace-usage "io"])))
    (is (= {"set" 1}
           (get-in summary [:namespace-usage "globals"])))
    (is (= {"spawn" 1
            "!ask" 1}
           (get-in summary [:namespace-usage "agents"])))
    (is (= {"team" 1}
           (get-in summary [:namespace-usage "patterns"])))
    (is (= {"sqrt" 1}
           (get-in summary [:namespace-usage "math"])))
    (is (= {"replace" 1}
           (get-in summary [:namespace-usage "strings"])))
    (is (= {"coder" 1}
           (get-in summary [:namespace-usage "llms"])))
    (is (= {:count 2
            :total-chars (+ pruned-think pruned-prune)
            :mean-chars (/ (double (+ pruned-think pruned-prune)) 2.0)
            :max-chars pruned-think}
           (:pruning-stats summary)))
    (is (= [{:node-id 0
             :path [2]
             :kind 'prune
             :chars-pruned pruned-think
             :head-sym 'think}
            {:node-id 0
             :path [3]
             :kind 'rethink
             :chars-pruned pruned-prune
             :head-sym 'prune}]
           (:pruning-details summary)))
    (is (= [{:node-id 1
             :error "boom"
             :recovered? true
             :recovered-by 2}
            {:node-id 3
             :error "fatal"
             :recovered? false
             :recovered-by nil}]
           (:errors summary)))
    (is (= #{:persist-used
             :globals-used
             :agents-used
             :patterns-used
             :nontrivial-math
             :nontrivial-strings
             :compact-used
             :llm-self-used
             :concurrency-used
             :leaf-llm-used
             :function-definitions}
           (:flags summary)))))

(deftest trace-summary-skips-unparseable-responses-test
  (let [trace {:nodes [{:id 0
                        :depth 0
                        :program '(do (persist state))
                        :response "(persist state)"}
                       {:id 1
                        :depth 0
                        :program '(do (!print :bad))
                        :response "(foo] (bar)"}]}
        summary (tt/trace-summary "traces/example" trace)]
    (is (= {'persist 1}
           (:tracked-counts summary)))
    (is (= []
           (:errors summary)))
    (is (= [{:node-id 1
             :error "Unmatched delimiter: ]"}]
           (:response-parse-errors summary)))
    (is (contains? (:flags summary) :response-parse-errors))))

(deftest trace-summary-consecutive-errors-stay-fatal-test
  (let [trace {:nodes [{:id 0
                        :depth 0
                        :error "first failure"}
                       {:id 1
                        :depth 0
                        :error "second failure"}]}
        summary (tt/trace-summary "traces/example" trace)
        row (tt/summary-tsv-row summary)]
    (is (= [{:node-id 0
             :error "first failure"
             :recovered? false
             :recovered-by nil}
            {:node-id 1
             :error "second failure"
             :recovered? false
             :recovered-by nil}]
           (:errors summary)))
    (is (= [2 0 ""]
           (take-last 3 row)))))

(deftest summary-tsv-row-test
  (let [summary {:trace-dir "traces/example"
                 :node-count 2
                 :tracked-counts {'think 3
                                  'prune 2
                                  'rethink 1
                                  '!extend 2
                                  '!call-now 4
                                  '!peek-now 1
                                  '!peek 2
                                  '!compact 1
                                  '!llm-self 1
                                  '!ask-await 2
                                  'persist 1
                                  '!print 5
                                  '!describe 1
                                  'leaf-llm 1
                                  'future 2
                                  'defn 1
                                  'fn 3}
                 :pruning-stats {:count 3 :mean-chars 12.0 :total-chars 36 :max-chars 20}
                 :namespace-usage {"io" {"sh" 2}
                                   "agents" {"spawn" 1}
                                   "globals" {"get" 1}
                                   "blocking" {"await" 2}
                                   "patterns" {"team" 1}
                                   "math" {"sqrt" 1}
                                   "strings" {"replace" 3}
                                   "llms" {"fast" 1}}
                 :errors [{:node-id 1 :recovered? true}
                          {:node-id 2 :recovered? false}]
                 :flags #{:concurrency-used :function-definitions}}]
    (is (= ["traces/example" 2 3 2 1 "12.0" 36 20 2 4 3 1 1 2 1 5 1 1 2 1 3 2 1 1 2 1 1 3 1 1 1
            ":concurrency-used :function-definitions"]
           (tt/summary-tsv-row summary)))))

(deftest run-tool-mode-validation-test
  (testing "context-management modes have coherent option validation"
    (is (= {:exit 1
            :message "Choose at most one of --rethinks, --context-trajectory, or --summary"}
           (tt/run-tool {:trace-dir "unused"
                         :rethinks true
                         :context-trajectory true}
                        "")))
    (is (= {:exit 1
            :message "Mode requires --trace-dir, --trace-root, or --results-jsonl"}
           (tt/run-tool {:context-trajectory true} "")))))

(deftest summary-tsv-validation-test
  (is (= {:exit 1
          :message "--tsv requires --summary with --trace-root"}
         (tt/run-tool {:trace-dir "unused" :tsv true} "")))
  (is (= {:exit 1
          :message "--tsv requires --summary with --trace-root"}
         (tt/run-tool {:trace-root "unused" :tsv true} ""))))

(deftest run-tool-summary-trace-root-test
  (let [tmp-root (-> (java.nio.file.Files/createTempDirectory "trace-tool-summary-root"
                                                              (make-array java.nio.file.attribute.FileAttribute 0))
                     (.toFile))
        trace-dir (io/file tmp-root "trace-a")
        trace-file (io/file trace-dir "trace.edn")]
    (.mkdirs trace-dir)
    (spit trace-file "{:nodes []}")
    (let [result (atom nil)]
      (with-out-str
        (reset! result
                (tt/run-tool {:trace-root (.getPath tmp-root)
                              :summary true}
                             "")))
      (is (= {:exit 0 :message nil}
             @result)))))

(deftest run-tool-summary-tsv-trace-root-tolerates-unparseable-responses-test
  (let [tmp-root (-> (java.nio.file.Files/createTempDirectory "trace-tool-summary-root-tsv"
                                                              (make-array java.nio.file.attribute.FileAttribute 0))
                     (.toFile))
        bad-trace-dir (io/file tmp-root "trace-a")
        good-trace-dir (io/file tmp-root "trace-b")
        bad-trace-file (io/file bad-trace-dir "trace.edn")
        good-trace-file (io/file good-trace-dir "trace.edn")]
    (.mkdirs bad-trace-dir)
    (.mkdirs good-trace-dir)
    (spit bad-trace-file
          "{:nodes [{:id 0 :depth 0 :program (do (persist state)) :response \"(foo] (bar)\"}]}")
    (spit good-trace-file
          "{:nodes [{:id 0 :depth 0 :program (do (!print :ok)) :response \"(!print :ok)\"}]}")
    (let [result (atom nil)
          output (with-out-str
                   (reset! result
                           (tt/run-tool {:trace-root (.getPath tmp-root)
                                         :summary true
                                         :tsv true}
                                        "")))
          lines (str/split-lines output)]
      (is (= {:exit 0 :message nil}
             @result))
      (is (= 3 (count lines)))
      (is (str/includes? (second lines) ":response-parse-errors"))
      (is (str/includes? (nth lines 2) "\t1\t0\t")))))

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
                        :program '(do (think "a") (prune))
                        :response "(think \"a\") (prune)"}
                       {:id 1
                        :file "0001.spl"
                        :program '(do
                                    (think "a")
                                    (prune)
                                    (def payload {:id 1})
                                    (rethink "drop payload"))
                        :response "(def payload {:id 1}) (rethink \"drop payload\")"}
                       {:id 2
                        :file "0002.spl"
                        :program '(do
                                    (think "a")
                                    (prune)
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
    (is (= [1 1 0 0] (mapv :pruning-count rows)))
    (is (pos? (:pruned-chars (first rows))))
    (is (pos? (:pruned-chars (second rows))))
    (is (zero? (:pruned-chars (nth rows 2))))
    (is (zero? (:pruned-chars (nth rows 3))))))
