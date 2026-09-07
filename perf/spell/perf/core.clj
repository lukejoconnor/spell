(ns spell.perf.core
  "Bounded local runtime benchmarks; no network/model inference."
  (:require [spell.api :as api]
            [spell.eval :as ev]
            [spell.parse :as parse]
            [spell.provider :as provider]
            [spell.trace :as trace]
            [spell.io :as sio]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- ensure! [ok message data]
  (when-not ok (throw (ex-info message data))))

(defn- bounded! [n maximum label]
  (ensure! (and (integer? n) (<= 1 n maximum))
           "Parameter outside finite benchmark bound" {:parameter label :value n :maximum maximum})
  n)

(defn- owned-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
             (.toPath (io/file "perf")) ".core-fixture-"
             (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- remove-owned! [dir]
  (when (and dir (.exists ^java.io.File dir))
    (doseq [f (reverse (file-seq dir))]
      (io/delete-file f))))

(defn- cleanup! [fixture]
  (when fixture
    (let [state @fixture]
      (try (remove-owned! (:dir state))
           (finally
             (when-let [t (:held-trace state)] (reset! t nil))
             (reset! fixture nil))))))

(defn- evaluator-run [{:keys [iterations evals]}]
  (bounded! iterations 10000 :iterations)
  (bounded! evals 20 :evals)
  (let [form (list '+ 1
                   (list 'loop '[i 0 acc 0]
                         (list 'if (list '< 'i iterations)
                               '(recur (+ i 1) (+ acc i)) 'acc)))
        expected (inc (quot (* iterations (dec iterations)) 2))]
    (dotimes [_ evals]
      (let [r (ev/spell-eval form {})]
        (ensure! (and (not (:err r)) (= expected (:ok r)))
                 "Non-tail evaluator result mismatch" {:expected expected :actual (:ok r) :error (:err r)})))
    {:evals evals :loop-iterations (* evals iterations) :result expected}))

(defn- parse-run [{:keys [forms]}]
  (bounded! forms 5000 :forms)
  (let [text (str "(do " (apply str (repeat forms "(when true (+ 1 2)) ")) "42)")
        parsed (parse/read-first text)
        expanded (ev/expand-expr parsed {})
        r (ev/spell-eval expanded {})]
    (ensure! (and (not (:err r)) (= 42 (:ok r)))
             "Parse/expand/eval mismatch" {:actual (:ok r) :error (:err r)})
    {:sibling-forms forms :source-chars (count text) :result 42}))

(defn- serial-run [{:keys [calls padding] :or {padding 0}} opts]
  (bounded! calls 100 :calls)
  (ensure! (and (integer? padding) (<= 0 padding 4096)) "Invalid padding" {:padding padding})
  (let [counter (atom 0)
        pad (apply str (repeat padding " "))
        p (provider/test-provider
            {:response-fn (fn [_]
                            (let [i (swap! counter inc)]
                              (ensure! (<= i calls) "Unexpected additional provider call" {:calls i})
                              (str pad (if (< i calls) "'(!extend))))" "42)))"))))})
        ;; The +1 continuation MUST survive the nested self-call returning 42.
        init (pr-str (list 'eval (list 'do (list 'quote
                          (list '+ 1 (list '!llm-self "(quine completion (eval (do "))))))
        r (api/run (merge {:model-profile p
                           :agent-profile "config/agent-profiles/base-tc.agent.edn"
                           :init init :depth (+ calls 10)
                           :context-max-chars 2000000}
                          opts))]
    (ensure! (= 43 (:result r)) "Serial non-tail return mismatch"
             {:expected 43 :actual (:result r) :error (:error r)})
    (ensure! (= calls @counter) "Serial provider call count mismatch" {:expected calls :actual @counter})
    {:calls @counter :result (:result r) :padding-chars-per-call padding}))

(defn- trace-setup [{:keys [mode]}]
  (ensure! (contains? #{:off :trace :verbose :both :held-trace} mode)
           "Unknown trace mode" {:mode mode})
  (atom (if (= mode :held-trace)
          {:held-trace (trace/new-trace)}
          {:dir (owned-dir)})))

(defn- trace-measure [{:keys [mode calls prompt-chars] :as params} fixture]
  (bounded! calls 100 :calls)
  (if (= mode :held-trace)
    (let [t (:held-trace @fixture)]
      (bounded! prompt-chars 100000 :prompt-chars)
      (binding [trace/*trace* t]
        (dotimes [i calls]
          (let [prompt (str i ":" (apply str (repeat prompt-chars "x")))
                id (trace/begin-node! nil 0 :leaf prompt)]
            (trace/complete-node! id {:response "42" :raw-text "42" :value 42}))))
      (ensure! (= calls (count (:nodes @t))) "Held trace count mismatch" {:calls calls})
      (ensure! (every? #(= 42 (:value %)) (:nodes @t))
               "Held trace values mismatch" {:calls calls})
      {:nodes (count (:nodes @t)) :payload-chars-per-node prompt-chars :result 42})
    (let [dir (:dir @fixture)
          tracing? (contains? #{:trace :both} mode)
          logging? (contains? #{:verbose :both} mode)
          trace-dir (io/file dir "trace")
          log-file (io/file dir "verbose.log")
          opts (cond-> {} tracing? (assoc :trace-dir (.getPath trace-dir)))
          result (if logging?
                   (with-open [writer (io/writer log-file :encoding "UTF-8")]
                     (serial-run params (assoc opts :log-writer writer)))
                   (serial-run params opts))
          trace-file (io/file trace-dir "trace.edn")
          trace-data (when tracing?
                       (ensure! (.isFile trace-file) "Missing completed trace" {:mode mode})
                       (edn/read-string (slurp trace-file)))
          nodes (if tracing? (count (:nodes trace-data)) 0)
          trace-bytes (if tracing?
                        (reduce + 0 (map #(.length ^java.io.File %) (filter #(.isFile ^java.io.File %) (file-seq trace-dir)))) 0)
          log-bytes (if logging? (.length log-file) 0)]
      (when tracing?
        (ensure! (= calls nodes) "Completed trace count mismatch" {:expected calls :actual nodes})
        (ensure! (every? #(= 42 (:value %)) (:nodes trace-data))
                 "Completed trace values mismatch" {:mode mode :nodes nodes}))
      (when logging? (ensure! (pos? log-bytes) "Empty verbose log" {:mode mode}))
      (assoc result :nodes nodes :trace-bytes trace-bytes :log-bytes log-bytes))))

(defn- boundary-setup [{:keys [mode lines]}]
  (ensure! (contains? #{:json :sse :read-lines} mode) "Unknown boundary mode" {:mode mode})
  (let [fixture (atom {})]
    (try
      (when (= mode :read-lines)
        (bounded! lines 100000 :lines)
        (ensure! (<= 10 lines) "Need at least ten lines" {:lines lines})
        (let [dir (owned-dir)
              file (io/file dir "numbered.txt")]
          (swap! fixture assoc :dir dir :file file)
          (with-open [w (io/writer file :encoding "UTF-8")]
            (dotimes [i lines] (.write w (str "line-" (inc i) "\n"))))))
      fixture
      (catch Throwable t (cleanup! fixture) (throw t)))))

(defn- event [m] (str "data: " (json/write-str m) "\n\n"))

(defn- boundary-measure [{:keys [mode chars events lines]} fixture]
  (case mode
    :json
    (do
      (bounded! chars 1000000 :chars)
      (let [unit "xλ\"\\\n"
            prompt (subs (apply str (repeat (inc (quot chars (count unit))) unit)) 0 chars)
            body (#'provider/codex-tc-request-body
                   "gpt-6-astra" prompt "SYSTEM" nil "medium" nil nil)
            encoded (json/write-str body)
            roundtrip (json/read-str encoded :key-fn keyword)]
        (ensure! (= prompt (get-in roundtrip [:input 0 :content 0 :text]))
                 "Provider request roundtrip mismatch" {:chars chars})
        (swap! fixture assoc :retained {:prompt prompt :body body :encoded encoded :roundtrip roundtrip})
        {:prompt-chars chars :encoded-bytes (alength (.getBytes encoded "UTF-8"))
         :text-hash (hash prompt)}))
    :sse
    (do
      (bounded! events 10000 :events)
      (let [output (str (apply str (repeat events " ")) "42)))")
            delta (event {:type "response.custom_tool_call_input.delta"
                          :output_index 0 :item_id "call-1" :delta " "})
            sse (str (apply str (repeat events delta))
                     (event {:type "response.output_item.done" :output_index 0
                             :item {:id "call-1" :type "custom_tool_call" :name "spell_suffix" :input output}})
                     (event {:type "response.completed"
                             :response {:status "completed" :output []
                                        :usage {:input_tokens 2 :output_tokens 1 :total_tokens 3}}}))
            r (#'provider/parse-codex-tc-stream sse)]
        (ensure! (= output (:text r)) "Exact SSE finished tool output mismatch" {:events events})
        (swap! fixture assoc :retained {:sse sse :parsed r})
        {:delta-events events :total-events (+ events 2) :sse-chars (count sse)
         :output-chars (count (:text r)) :text-hash (hash (:text r))}))
    :read-lines
    (let [file (:file @fixture)
          selected (sio/read-lines (.getPath file) 1 11)
          expected (mapv #(str "line-" %) (range 1 11))]
      (ensure! (= expected selected) "Selected line contents mismatch" {:lines lines})
      (ensure! (= 1 (:spell/first-line (meta selected))) "Selected line metadata mismatch" {})
      (swap! fixture assoc :retained selected)
      {:file-lines lines :file-bytes (.length file) :returned-lines (count selected)
       :returned-chars (reduce + (map count selected)) :first-line 1})))

(def scenarios
  [{:id :core-evaluator
    :description "Generated loop evaluation with non-tail +1 continuation; construction, eval and assertions."
    :params (vec (for [iterations [100 1000 10000] evals [4 20]] {:iterations iterations :evals evals}))
    :bounds {:max-iterations-per-eval 10000 :max-evals 20 :max-total-loop-iterations 200000}
    :notes "Units: eval invocations and total loop iterations. No retained graph or I/O; not parser latency."
    :run evaluator-run}
   {:id :core-parse-expand
    :description "Generate valid sibling source, parse-first, recursively expand, evaluate and check 42."
    :params (mapv #(hash-map :forms %) [4 20 100 1000 5000])
    :bounds {:max-sibling-forms 5000}
    :notes "Units: sibling forms and source characters, not nesting/context depth. Includes source construction and evaluation; no malformed recovery."
    :run parse-run}
   {:id :core-serial-self-calls
    :description "API compile/discovery + deterministic receiving !extend chain + non-tail return + shutdown."
    :params (mapv #(hash-map :calls % :padding 0) [4 20 100])
    :bounds {:max-calls 100 :max-depth-option 110 :context-max-chars 2000000 :max-padding-per-call 4096}
    :notes "Fresh test provider/counter and API run-local resources each invocation. Terminal 42 must return through +1 as 43; exact call counts. No paid calls/network/latency. Allocation covers caller thread only, not futures. Requested large failures must not be silently downscaled."
    :run (fn [params] (serial-run params {}))}
   {:id :core-trace-log
    :description "Identical serial chains with off/trace/file-log/both, plus separately labelled strongly held trace nodes."
    :params (vec (concat
                   (for [mode [:off :trace :verbose :both] calls [4 20] padding [0 1024]]
                     {:mode mode :calls calls :padding padding})
                   (for [calls [4 20] prompt-chars [1000 100000]]
                     {:mode :held-trace :calls calls :prompt-chars prompt-chars})))
    :bounds {:max-calls 100 :max-padding-per-call 4096 :max-held-prompt-chars-per-node 100000}
    :notes "Timed lifecycle includes worktree-local directory/writer setup, API compilation/shutdown, evidence file parsing/counting, assertions and cleanup. File modes retain no API trace after return (not peak heap). Held-trace mode bypasses API and retains trace atom until cleanup; prompt-chars counts repeated payload characters plus an additional node-index prefix. Reports live retained graph, not file I/O. API flushes writer; scenario closes it. GC delta is not RSS; future allocation excluded."
    :setup trace-setup :measure trace-measure :cleanup cleanup!}
   {:id :core-provider-local-boundaries
    :description "Parameterized request JSON roundtrip, synthetic SSE scan, or ten selected lines from a larger local file."
    :params (vec (concat (map #(hash-map :mode :json :chars %) [10000 100000 1000000])
                         (map #(hash-map :mode :sse :events %) [100 1000 10000])
                         (map #(hash-map :mode :read-lines :lines %) [1000 10000 100000])))
    :bounds {:max-prompt-chars 1000000 :max-delta-events 10000 :max-file-lines 100000 :selected-lines 10}
    :notes "Private provider Vars pinned to packet revision. JSON includes builder, encode/decode, Unicode/escape exact check and intentionally held input/body/encoded/decoded objects; model name is inert builder data. SSE includes synthetic event generation and exact finished output check, no socket/auth/timeout logic; held input and parsed result. Local read-lines(1,11) internally slurps all bytes; timed setup writes numbered file, then read/check/delete. Warm/local cache uncontrolled; returned ten-line SubVector retains the full split-lines backing vector. Only tiny evidence maps escape. Strong references cleared even on failed measurement when harness calls cleanup."
    :setup boundary-setup :measure boundary-measure :cleanup cleanup!}])
