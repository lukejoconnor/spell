(require '[clojure.data.json :as json] '[clojure.string :as str] '[spell.test-helpers :as th] '[spell.eval :as ev] '[spell.trace :as trace])
(def checks (atom 0))
(defn check! [ok message] (swap! checks inc) (when-not ok (throw (ex-info message {}))))
(def source-waits (count (re-seq #"\(Thread/sleep \(rand-int 500\)\)" (slurp "src/spell/llm.clj"))))
(check! (#{0 2} source-waits) "Expected exact two-wait control or zero-wait candidate")
(defn run-case [kind verbose? trace?]
  (let [writer (java.io.StringWriter.) t (when trace? (trace/new-trace))
        waits (atom 0) calls (atom 0) original-rand-int clojure.core/rand-int
        response (if (= kind :self) "42)" "offline leaf response")
        input (if (= kind :self) "(do " "offline leaf")
        expected (if (= kind :self) 42 response)
        opts {:response-fn (fn [_] (swap! calls inc) response)}
        invoke (if (= kind :self) (th/make-test-runner opts) (th/make-test-leaf-llm opts))
        started (System/nanoTime)
        value (with-redefs [clojure.core/rand-int (fn [n] (if (= n 500) (do (swap! waits inc) 250) (original-rand-int n)))]
                (binding [ev/*verbose* verbose? ev/*log-writer* writer ev/*llm-depth* 0 trace/*trace* t trace/*trace-node-id* nil]
                  (th/with-test-run #(invoke input))))
        elapsed (/ (- (System/nanoTime) started) 1000000.0)
        raw-log (str writer)
        events (->> (str/split-lines raw-log) (map str/trim)
                    (filter #(or (re-matches #"=== (?:Leaf )?LLM Call \(depth [0-9]+\) ===" %) (str/starts-with? % "Prompt: ") (str/starts-with? % "Response: "))) vec)
        variant (if (= kind :self) :default :leaf)]
    (check! (= expected value) "Return value changed")
    (check! (= 1 @calls) "Provider lifecycle call count changed")
    (check! (= @waits (if (and verbose? (= source-waits 2)) 1 0)) "Unexpected presentation wait count")
    (if verbose?
      (do (check! (= 3 (count events)) "Expected header, prompt, response exactly once")
          (check! (boolean (re-matches (if (= kind :self) #"=== LLM Call \(depth [0-9]+\) ===" #"=== Leaf LLM Call \(depth [0-9]+\) ===") (first events))) "Header order/content")
          (check! (= [(str "Prompt: " (pr-str input)) (str "Response: " response)] (subvec events 1)) "Prompt/response order/content"))
      (check! (= "" raw-log) "Nonverbose emitted output"))
    (when trace?
      (let [nodes (:nodes @t) node (first nodes)]
        (check! (= 1 (count nodes)) "Trace lifecycle node count")
        (check! (= {:variant variant :prompt input :response response :value expected :raw-text (if (= kind :self) (str input response) response)}
                   (select-keys node [:variant :prompt :response :value :raw-text])) "Trace semantic content")
        (check! (and (number? (:start-ms node)) (number? (:end-ms node)) (<= (:start-ms node) (:end-ms node)) (not (contains? node :error))) "Trace completion")))
    {:kind kind :verbose verbose? :trace trace? :elapsed_ms elapsed :wait_calls @waits :intentional_wait_ms (* 250 @waits) :provider_calls @calls :value value :log raw-log :events events
     :trace_semantics (when t (mapv #(select-keys % [:variant :prompt :response :value :raw-text :depth :parent]) (:nodes @t)))}))
(try
  (let [cases (vec (for [kind [:self :leaf] verbose? [false true] trace? [false true]] (run-case kind verbose? trace?)))]
    (println (json/write-str {:source_waits source-waits :assertions @checks :cases cases})))
  (catch Throwable e (.printStackTrace e) (System/exit 1))
  (finally (shutdown-agents)))
