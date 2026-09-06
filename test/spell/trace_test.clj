(ns spell.trace-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [spell.trace :as trace]
            [spell.core :as spell]
            [spell.eval :as eval]
            [spell.provider :as provider]
            [spell.test-helpers :as th]))

(use-fixtures :each th/with-test-run)

;; =============================================================================
;; Unit tests
;; =============================================================================

(deftest new-trace-test
  (testing "creates empty trace atom"
    (let [t (trace/new-trace)]
      (is (= {:nodes [] :next-id 0 :root nil} @t)))))

(deftest begin-node-root-test
  (testing "first node becomes root"
    (binding [trace/*trace* (trace/new-trace)]
      (let [id (trace/begin-node! nil 0 :default "(do ")]
        (is (= 0 id))
        (is (= 0 (:root @trace/*trace*)))
        (let [node (first (:nodes @trace/*trace*))]
          (is (= 0 (:id node)))
          (is (nil? (:parent node)))
          (is (= 0 (:depth node)))
          (is (= :default (:variant node)))
          (is (= "(do " (:prompt node)))
          (is (number? (:start-ms node)))
          (is (= [] (:children node))))))))

(deftest begin-node-child-test
  (testing "child node links to parent"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(do ")
      (let [child-id (trace/begin-node! 0 1 :default "(inner ")]
        (is (= 1 child-id))
        (is (= 0 (:parent (nth (:nodes @trace/*trace*) 1))))
        ;; Parent has child registered
        (is (= [{:child-id 1 :call-prompt "(inner "}]
               (:children (first (:nodes @trace/*trace*)))))))))

(deftest begin-node-multiple-children-test
  (testing "multiple children registered in order"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(root ")
      (trace/begin-node! 0 1 :default "(child-a ")
      (trace/begin-node! 0 1 :leaf "child-b prompt")
      (let [root (first (:nodes @trace/*trace*))]
        (is (= 2 (count (:children root))))
        (is (= 1 (:child-id (first (:children root)))))
        (is (= 2 (:child-id (second (:children root)))))))))

(deftest begin-node-concurrent-ids-test
  (testing "concurrent nodes receive unique contiguous IDs"
    (binding [trace/*trace* (trace/new-trace)]
      (let [start (promise)
            workers (doall
                     (repeatedly 100
                                 #(future
                                    @start
                                    (trace/begin-node! nil 0 :default "(do "))))]
        (deliver start true)
        (let [ids (mapv deref workers)
              {:keys [nodes next-id]} @trace/*trace*]
          (is (= (set (range 100)) (set ids)))
          (is (= (vec (range 100)) (mapv :id nodes)))
          (is (= 100 next-id)))))))

(deftest complete-node-success-test
  (testing "records value on success"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(do ")
      (trace/complete-node! 0
        {:response "42)" :raw-text "(do 42)" :program '(do 42) :value 42})
      (let [node (first (:nodes @trace/*trace*))]
        (is (= "42)" (:response node)))
        (is (= "(do 42)" (:raw-text node)))
        (is (= '(do 42) (:program node)))
        (is (= 42 (:value node)))
        (is (not (contains? node :error)))
        (is (number? (:end-ms node)))))))

(deftest complete-node-error-test
  (testing "records error, omits value"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(do ")
      (trace/complete-node! 0
        {:response "bad)" :raw-text "(do bad)"
         :error (Exception. "Unbound symbol")})
      (let [node (first (:nodes @trace/*trace*))]
        (is (= "Unbound symbol" (:error node)))
        (is (not (contains? node :value)))))))

(deftest complete-node-nil-value-test
  (testing "nil is recorded as a valid value"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(do ")
      (trace/complete-node! 0 {:response "nil)" :raw-text "(do nil)" :value nil})
      (let [node (first (:nodes @trace/*trace*))]
        (is (contains? node :value))
        (is (nil? (:value node)))))))

(deftest complete-node-hooked-test
  (testing "records hooked program when present"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(do ")
      (trace/complete-node! 0
        {:response "10)" :raw-text "(do 10)"
         :program '(do 10) :hooked '(do 10 (+ 5)) :value 15})
      (let [node (first (:nodes @trace/*trace*))]
        (is (= '(do 10) (:program node)))
        (is (= '(do 10 (+ 5)) (:hooked node)))))))

;; =============================================================================
;; Tree visualization
;; =============================================================================

(deftest tree-str-single-node-test
  (testing "single node renders"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(do (def x 1) ")
      (trace/complete-node! 0 {:value 42})
      (let [tree (trace/tree-str @trace/*trace*)]
        (is (str/includes? tree "0"))
        (is (str/includes? tree "(do (def x 1) "))
        (is (str/includes? tree "42"))))))

(deftest tree-str-nested-test
  (testing "nested tree renders with connectors"
    (binding [trace/*trace* (trace/new-trace)]
      (trace/begin-node! nil 0 :default "(root ")
      (trace/begin-node! 0 1 :default "(child-a ")
      (trace/begin-node! 0 1 :leaf "(child-b ")
      (trace/complete-node! 0 {:value "root-val"})
      (trace/complete-node! 1 {:value "a-val"})
      (trace/complete-node! 2 {:value "b-val"})
      (let [tree (trace/tree-str @trace/*trace*)]
        (is (str/includes? tree "0"))
        (is (str/includes? tree "1"))
        (is (str/includes? tree "2 [leaf]"))))))

;; =============================================================================
;; File output
;; =============================================================================

(deftest write-trace-test
  (testing "writes trace files to directory"
    (let [dir (str "target/test-traces/" (System/currentTimeMillis))]
      (try
        (let [trace-atom (trace/new-trace)]
          (binding [trace/*trace* trace-atom]
            (trace/begin-node! nil 0 :default "(do ")
            (trace/complete-node! 0
              {:response "42)" :raw-text "(do 42)"
               :program '(do 42) :value 42})
            (trace/begin-node! 0 1 :leaf "summarize this")
            (trace/complete-node! 1
              {:response "A summary." :raw-text "A summary." :value "A summary."})
            (trace/write-trace! @trace-atom dir)))
        ;; Check files exist
        (is (.exists (io/file dir "trace.edn")))
        (is (.exists (io/file dir "0000.spl")))
        (is (.exists (io/file dir "0001.txt")))
        (is (.exists (io/file dir "tree.txt")))
        ;; Check content
        (is (= "(do 42)" (slurp (io/file dir "0000.spl"))))
        (is (= "A summary." (slurp (io/file dir "0001.txt"))))
        ;; trace.edn should not contain raw-text
        (let [edn-str (slurp (io/file dir "trace.edn"))]
          (is (str/includes? edn-str ":file"))
          (is (not (str/includes? edn-str ":raw-text"))))
        (finally
          (doseq [f (.listFiles (io/file dir))] (.delete f))
          (.delete (io/file dir)))))))

;; =============================================================================
;; Integration: trace through actual llm calls with dummy provider
;; =============================================================================

(deftest integration-single-call-test
  (testing "single llm call creates one traced node"
    (binding [trace/*trace* (trace/new-trace)]
      (let [llm (th/make-test-runner {:response "42)"})]
        (let [result (llm "(do ")]
          (is (= 42 result))
          (let [{:keys [nodes root]} @trace/*trace*]
            (is (= 1 (count nodes)))
            (is (= 0 root))
            (let [node (first nodes)]
              (is (= 0 (:id node)))
              (is (nil? (:parent node)))
              (is (= :default (:variant node)))
              (is (= 42 (:value node)))
              (is (= [] (:children node))))))))))

(deftest integration-nested-calls-test
  (testing "nested llm calls produce correct trace tree"
    (let [call-count (atom 0)
          responses ["'(cat \"hello \" (!llm-self \"(do \"))"
                     "\"world\")"]]
      (binding [trace/*trace* (trace/new-trace)]
        (let [llm (th/make-test-runner
                   {:response-fn (fn [_]
                                   (let [r (nth responses @call-count)]
                                     (swap! call-count inc)
                                     r))})]
          (let [result (llm "(eval (do ")]
            (is (= "hello world" result))
            (let [{:keys [nodes root]} @trace/*trace*]
              ;; Two nodes: root + child
              (is (= 2 (count nodes)))
              (is (= 0 root))
              ;; Root node
              (let [root-node (nth nodes 0)]
                (is (nil? (:parent root-node)))
                (is (= "hello world" (:value root-node)))
                (is (= 1 (count (:children root-node))))
                (is (= 1 (:child-id (first (:children root-node))))))
              ;; Child node
              (let [child-node (nth nodes 1)]
                (is (= 0 (:parent child-node)))
                (is (= 2 (:depth child-node)))
                (is (= "world" (:value child-node)))
                (is (= [] (:children child-node)))))))))))

(deftest integration-error-recorded-test
  (testing "eval error is captured in trace"
    (binding [trace/*trace* (trace/new-trace)]
      (let [llm (th/make-test-runner {:response "undefined-symbol)"} :recover false)]
        (is (thrown? Exception (llm "(do ")))
        (let [node (first (:nodes @trace/*trace*))]
          (is (some? (:error node)))
          (is (not (contains? node :value))))))))

(deftest integration-tracing-off-test
  (testing "no trace overhead when *trace* is nil"
    (binding [trace/*trace* nil]
      (let [llm (th/make-test-runner {:response "42)"})]
        (is (= 42 (llm "(do ")))))))

(deftest integration-three-deep-test
  (testing "three levels of nesting produce correct parent chain"
    (let [call-count (atom 0)
          responses ["'(!llm-self \"(eval (do \")"
                     "'(!llm-self \"(eval (do \")"
                     "99)))"]]
      (binding [trace/*trace* (trace/new-trace)]
        (let [llm (th/make-test-runner
                   {:response-fn (fn [_]
                                   (let [r (nth responses @call-count)]
                                     (swap! call-count inc)
                                     r))})]
          (let [result (llm "(eval (do ")]
            (is (= 99 result))
            (let [nodes (:nodes @trace/*trace*)]
              (is (= 3 (count nodes)))
              ;; Parent chain: 0 -> 1 -> 2
              (is (nil? (:parent (nth nodes 0))))
              (is (= 0 (:parent (nth nodes 1))))
              (is (= 1 (:parent (nth nodes 2))))
              ;; Depths
              (is (= 1 (:depth (nth nodes 0))))
              (is (= 2 (:depth (nth nodes 1))))
              (is (= 3 (:depth (nth nodes 2))))
              ;; All return 99
              (is (= 99 (:value (nth nodes 0))))
              (is (= 99 (:value (nth nodes 1))))
              (is (= 99 (:value (nth nodes 2)))))))))))
