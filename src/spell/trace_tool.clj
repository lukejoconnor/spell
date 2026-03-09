(ns spell.trace-tool
  "Single-trace analysis tooling for Spell traces.

   Primary goals:
   - Skeletonize a selected trace node's program by collapsing strings to ellipsis
   - Count function call usage without double-counting inherited prefixes across extensions
   - Resolve the latest errored benchmark record to a concrete trace directory"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [spell.parse :as parse])
  (:gen-class))

(def ^:private tracked-form-order
  ['think
   'rethink
   '!extend
   '!call-now
   '!peek-now
   '!peek
   '!compact
   '!llm-self
   '!ask-await
   'persist
   '!print
   '!describe
   'leaf-llm
   'future
   'defn
   'fn])

(def ^:private tracked-form-set
  (set tracked-form-order))

(def ^:private namespace-order
  ["io" "agents" "globals" "blocking" "patterns" "math" "strings" "llms"])

(def ^:private namespace-prefixes
  (set namespace-order))

(def ^:private nontrivial-strings
  #{"re-find" "re-matches" "re-seq" "replace"
    "index-of" "last-index-of" "subs"
    "lower-case" "upper-case" "capitalize"})

(def ^:private summary-tsv-columns
  ["trace_dir" "nodes" "think" "rethink" "rethink_mean_c" "rethink_total_c" "rethink_max_c"
   "extend" "call_now" "peek" "compact" "llm_self" "ask_await" "persist" "print" "describe"
   "leaf_llm" "future" "defn" "fn"
   "io" "agents" "globals" "blocking" "patterns" "math_fns" "strings_fns" "llms"
   "errors_fatal" "errors_recovered" "flags"])

(def cli-options
  [[nil "--trace-dir DIR" "Trace directory (e.g., traces/2026-03-02T07-04-01)"]
   [nil "--trace-root DIR" "Directory containing many trace dirs (recursive scan for trace.edn)"]
   [nil "--results-jsonl FILE" "Unified benchmark JSONL file; resolves latest errored item to trace dir"]
   [nil "--node ID" "Node id to skeletonize (default: last node id)"
    :parse-fn #(Integer/parseInt %)]
   [nil "--string-truncate N" "Max string chars to keep before appending … (default: 32, -1 = no truncation)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % -1) "Must be >= -1"]]
   [nil "--fn SYMBOL" "Function symbol to count (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--count-all-nodes" "Count function calls across all nodes (default: selected node only)"]
   [nil "--rethinks" "Report rethink forms and preceding expressions (single trace or trace root)"]
   [nil "--context-trajectory" "Report per-node context size trajectory (single trace or trace root)"]
   [nil "--summary" "Report tracked-form, namespace, rethink, and error summary (single trace, trace root, or results JSONL)"]
   [nil "--tsv" "Print summary output as TSV rows (requires --summary with --trace-root)"]
   ["-h" "--help" "Show help"]])

(defn- usage [usage-summary]
  (str/join
   "\n"
   ["spell.trace-tool - analyze a single Spell trace"
    ""
    "Usage:"
    "  clj -M -m spell.trace-tool --trace-dir DIR [--node ID] [--fn SYMBOL ...]"
    "  clj -M -m spell.trace-tool --trace-dir DIR --summary"
    "  clj -M -m spell.trace-tool --trace-root DIR --rethinks"
    "  clj -M -m spell.trace-tool --trace-root DIR --context-trajectory"
    "  clj -M -m spell.trace-tool --trace-root DIR --summary [--tsv]"
    "  clj -M -m spell.trace-tool --results-jsonl FILE [--fn SYMBOL ...]"
    "  clj -M -m spell.trace-tool --results-jsonl FILE --summary"
    ""
    "Notes:"
    "  - --results-jsonl resolves the latest errored item and uses its trace_dir"
    "  - Function call counting defaults to selected node only (typically latest extension node)"
    "  - Use --count-all-nodes to aggregate response-only calls across nodes"
    ""
    "Options:"
    usage-summary]))

(defn- read-edn-file [path]
  ;; trace.edn is pretty-printed Clojure data (not strict EDN),
  ;; so it may include reader forms like @deref.
  (binding [*read-eval* false]
    (read-string (slurp (io/file path)))))

(defn- read-jsonl [path]
  (with-open [r (io/reader path)]
    (->> (line-seq r)
         (remove str/blank?)
         (mapv #(json/read-str % :key-fn keyword)))))

(defn- error-record? [row]
  (or (contains? #{"error" "timeout"} (:status row))
      (some? (:error_type row))
      (some? (:error_message row))))

(defn last-error-record
  "Return the latest errored record from unified JSONL rows, or nil."
  [rows]
  (last (filter error-record? rows)))

(defn trace-dir-from-record
  "Extract trace dir from a unified JSONL record.
   Checks direct metadata first, then nested raw response metadata."
  [row]
  (or (get-in row [:metadata :trace_dir])
      (get-in row [:metadata :raw :trace_dir])))

(defn resolve-trace-from-results
  "Resolve latest errored record from results JSONL.
   Returns:
   - :latest-error => latest errored row
   - :record => row used for trace analysis (latest with available trace_dir)
   - :trace-dir => resolved trace dir (may be nil)"
  [results-jsonl]
  (let [errors (->> (read-jsonl results-jsonl)
                    (filter error-record?)
                    vec)
        latest (last errors)
        chosen (some (fn [row]
                       (when-let [d (trace-dir-from-record row)]
                         {:record row :trace-dir d}))
                     (reverse errors))]
    (when latest
      (merge {:latest-error latest}
             (or chosen {:record latest :trace-dir nil})))))

(defn load-trace
  "Load a Spell trace directory.
   Returns {:dir str :trace map}."
  [trace-dir]
  (let [dir-file (io/file trace-dir)
        trace-file (io/file dir-file "trace.edn")]
    (when-not (.exists trace-file)
      (throw (ex-info "trace.edn not found" {:trace-dir trace-dir :path (.getPath trace-file)})))
    {:dir (.getPath dir-file)
     :trace (read-edn-file trace-file)}))

(defn- last-node-id [trace]
  (->> (:nodes trace)
       (map :id)
       (reduce max -1)))

(defn- default-program-node-ids [trace]
  (->> (:nodes trace)
       (filter (fn [n] (and (:program n) (= :default (:variant n)))))
       (map :id)))

(defn- program-node-ids [trace]
  (->> (:nodes trace)
       (filter :program)
       (map :id)))

(defn- default-target-node-id [trace]
  ;; Most traces are extension chains; use the latest default+program node.
  ;; Fallbacks preserve utility on error traces with no parsed program.
  (or (last (sort (default-program-node-ids trace)))
      (last (sort (program-node-ids trace)))
      (last-node-id trace)))

(defn select-node
  "Select node by id; default is last node by id."
  [trace node-id]
  (let [target-id (if (some? node-id) node-id (default-target-node-id trace))]
    (or (some #(when (= (:id %) target-id) %) (:nodes trace))
        (throw (ex-info "Node not found" {:node-id target-id})))))

(defn skeletonize-form
  "Replace all string literals with a compact placeholder while preserving structure."
  ([form] (skeletonize-form form {:max-string-chars 32}))
  ([form {:keys [max-string-chars] :or {max-string-chars 32}}]
   (letfn [(truncate-str [s]
             (cond
               (or (nil? max-string-chars) (= -1 max-string-chars)) s
               (<= (count s) max-string-chars) s
               :else (str (subs s 0 max-string-chars) "…")))
           (walk [x]
             (cond
               (string? x) (truncate-str x)
               (vector? x) (mapv walk x)
               (map? x) (into (empty x) (map (fn [[k v]] [(walk k) (walk v)])) x)
               (set? x) (->> x (map walk) set)
               (seq? x) (apply list (map walk x))
               :else x))]
     (walk form))))

(def ^:private unmatched-delimiter-re
  #"^Unmatched delimiter: (.)$")

(defn- strip-trailing-unmatched-delimiter
  "If the reader reports an unmatched trailing delimiter, strip exactly one
   trailing instance of that delimiter and return the trimmed response text."
  [s error-msg]
  (when-let [[_ bad-delim] (re-matches unmatched-delimiter-re (or error-msg ""))]
    (let [trimmed (str/trimr s)
          n (count trimmed)
          delim-char (.charAt ^String bad-delim 0)]
      (when (and (pos? n)
                 (= delim-char (.charAt ^String trimmed (dec n))))
        (subs trimmed 0 (dec n))))))

(defn response-forms
  "Parse the stored trace :response suffix into top-level forms.
   Uses only :response, without inferring boundaries from :program."
  [response]
  (let [response (or response "")]
    (letfn [(parse-text [text]
              (try
                (parse/read-all text)
                (catch RuntimeException e
                  (if-let [trimmed (strip-trailing-unmatched-delimiter text (.getMessage e))]
                    (parse-text trimmed)
                    (throw e)))))]
      (parse-text (parse/balance-parens response)))))

(defn- call-head [form]
  (when (seq? form)
    (let [h (first form)]
      (when (symbol? h) h))))

(defn collect-call-instances
  "Collect call instances as {:fn sym :path vec :form call-form}.
   Path is structural index path rooted at the provided form."
  ([form] (collect-call-instances form []))
  ([form path]
   (let [head (call-head form)
         current (when head
                   [{:fn head :path path :form form}])
         extra (when (and (seq? form)
                          (contains? #{'agents/spawn 'agents/!spawn-ask} head)
                          (symbol? (second form))
                          (namespace (second form)))
                 [{:fn (second form)
                   :path (conj path 1)
                   :form (second form)}])
         descend-seq (fn [xs]
                       (mapcat (fn [[idx child]]
                                 (collect-call-instances child (conj path idx)))
                               (map-indexed vector xs)))
         children (cond
                    (seq? form) (descend-seq form)
                    (vector? form) (descend-seq form)
                    (map? form) (mapcat (fn [[k v]]
                                          (concat (collect-call-instances k (conj path :k))
                                                  (collect-call-instances v (conj path :v k))))
                                        form)
                    (set? form) (mapcat (fn [[idx child]]
                                          (collect-call-instances child (conj path :s idx)))
                                        (map-indexed vector (sort-by pr-str form)))
                    :else nil)]
     (concat current extra children))))

(defn- collect-call-instances-in-forms
  "Collect call instances across top-level response forms.
   Paths are rooted at top-level response-form indexes."
  [forms]
  (mapcat (fn [[idx form]]
            (collect-call-instances form [idx]))
          (map-indexed vector forms)))

(defn count-function-calls
  "Count function call instances across all trace nodes.

   opts:
   - :fns set of symbols to include (nil = all)"
  [trace {:keys [fns]}]
  (let [nodes (filter :program (:nodes trace))]
    (loop [remaining nodes
           counts {}
           instances []]
      (if (empty? remaining)
        {:counts counts
         :instances instances}
        (let [node (first remaining)
              raw-instances (->> (collect-call-instances-in-forms (response-forms (:response node)))
                                 (filter #(or (nil? fns) (contains? fns (:fn %)))))
              accepted raw-instances
              counts' (reduce (fn [m inst] (update m (:fn inst) (fnil inc 0))) counts accepted)
              tagged (map #(assoc % :node-id (:id node)) accepted)]
          (recur (rest remaining)
                 counts'
                 (into instances tagged)))))))

(defn count-function-calls-in-forms
  "Count function call instances inside parsed response forms.
   opts:
   - :fns set of symbols to include (nil = all)
   - :node-id node id for reporting context"
  [forms {:keys [fns node-id]}]
  (let [instances (->> (collect-call-instances-in-forms forms)
                       (filter #(or (nil? fns) (contains? fns (:fn %))))
                       (map #(assoc % :node-id node-id)))]
    {:counts (reduce (fn [m inst] (update m (:fn inst) (fnil inc 0))) {} instances)
     :instances instances}))

(defn- parse-program-node-responses
  "Parse response suffixes for all program nodes.
   Summary mode uses this best-effort view so one malformed response does not
   abort the whole trace/root report."
  [trace]
  (->> (:nodes trace)
       (filter :program)
       (mapv (fn [node]
               (try
                 {:node-id (:id node)
                  :forms (response-forms (:response node))}
                 (catch RuntimeException e
                   {:node-id (:id node)
                    :parse-error (.getMessage e)}))))))

(defn- count-function-calls-in-parsed-nodes [parsed-nodes {:keys [fns]}]
  (reduce (fn [{:keys [counts instances]} {:keys [node-id forms]}]
            (let [{node-counts :counts
                   node-instances :instances}
                  (count-function-calls-in-forms forms {:fns fns :node-id node-id})]
              {:counts (merge-with + counts node-counts)
               :instances (into instances node-instances)}))
          {:counts {}
           :instances []}
          (filter :forms parsed-nodes)))

(defn- rethink-form? [form]
  (and (seq? form)
       (= 'rethink (first form))))

(defn collect-rethinks
  "Collect rethink forms and their preceding sibling expressions.
   Returns seq of:
   {:path vec :rethink form :previous form-or-nil}"
  ([form] (collect-rethinks form []))
  ([form path]
   (letfn [(walk-indexed [xs]
             (let [v (vec xs)]
               (mapcat
                (fn [idx]
                  (let [child (nth v idx)
                        child-path (conj path idx)
                        here (when (rethink-form? child)
                               [{:path child-path
                                 :rethink child
                                 :previous (when (pos? idx) (nth v (dec idx)))}])]
                    (concat here (collect-rethinks child child-path))))
                (range (count v)))))]
     (cond
       (seq? form) (walk-indexed form)
       (vector? form) (walk-indexed form)
       (map? form) (mapcat (fn [[k v]]
                             (concat (collect-rethinks k (conj path :k))
                                     (collect-rethinks v (conj path :v k))))
                           form)
       (set? form) (mapcat (fn [[idx child]]
                             (collect-rethinks child (conj path :s idx)))
                           (map-indexed vector (sort-by pr-str form)))
       :else nil))))

(defn- collect-rethinks-in-forms
  "Collect rethink forms across top-level response forms.
   Uses a synthetic `do` wrapper so top-level sibling relationships are preserved."
  [forms]
  (when (seq forms)
    (collect-rethinks (list* 'do forms))))

(defn- collect-trace-rethinks-in-parsed-nodes [parsed-nodes]
  (->> parsed-nodes
       (filter :forms)
       (mapcat (fn [{:keys [node-id forms]}]
                 (map #(assoc % :node-id node-id)
                      (collect-rethinks-in-forms forms))))))

(defn collect-trace-rethinks
  "Collect rethinks from all program nodes in one trace.
   Adds :node-id for reporting."
  [trace]
  (->> (:nodes trace)
       (filter :program)
       (mapcat (fn [node]
                 (map #(assoc % :node-id (:id node))
                      (collect-rethinks-in-forms (response-forms (:response node))))))))

(defn find-trace-dirs
  "Return sorted paths for directories under root containing trace.edn."
  [root]
  (let [root-file (io/file root)]
    (when-not (.exists root-file)
      (throw (ex-info "Trace root not found" {:trace-root root})))
    (->> (file-seq root-file)
         (filter #(.isDirectory ^java.io.File %))
         (filter #(-> (io/file % "trace.edn") .exists))
         (map #(.getPath ^java.io.File %))
         sort)))

(defn- parse-symbol-set [strs]
  (when (seq strs)
    (set (map symbol strs))))

(defn- classify-sym [sym]
  (let [sym-ns (namespace sym)
        sym-name (name sym)]
    (cond
      (contains? tracked-form-set sym)
      {:bucket :tracked :sym sym}

      (contains? namespace-prefixes sym-ns)
      {:bucket :namespace :namespace sym-ns :fn-name sym-name}

      :else nil)))

(defn- path->str [path]
  (str "[" (str/join " " (map pr-str path)) "]"))

(defn- pruned-size
  "Size in characters of a printed pruned form."
  [form]
  (count (or (some-> form pr-str) "")))

(defn- node-context-size
  "Estimate context size for a node by counting its node .spl file chars.
   Falls back to program form size when the file is missing."
  [trace-dir node]
  (let [file-name (:file node)
        file-path (when file-name
                    (.getPath (io/file trace-dir (str file-name))))]
    (or (when (and file-path (.exists (io/file file-path)))
          (count (slurp file-path)))
        (when-let [program (:program node)]
          (count (pr-str program)))
        0)))

(defn- context-trajectory-items [trace-dir trace]
  (let [nodes (->> (:nodes trace)
                   (sort-by :id)
                   vec)]
    (loop [remaining nodes
           prev-size nil
           out []]
      (if (empty? remaining)
        out
        (let [node (first remaining)
              size (node-context-size trace-dir node)
              delta (when (some? prev-size) (- size prev-size))
              rethinks (collect-rethinks-in-forms (response-forms (:response node)))
              pruned (reduce + 0 (map (comp pruned-size :previous) rethinks))
              row {:node-id (:id node)
                   :chars size
                   :delta delta
                   :rethink-count (count rethinks)
                   :pruned-chars pruned}]
          (recur (rest remaining)
                 size
                 (conj out row)))))))

(defn- empty-namespace-usage []
  (zipmap namespace-order (repeat {})))

(defn- tracked-counts-for-summary [counts]
  (reduce (fn [m sym]
            (let [n (get counts sym 0)]
              (if (pos? n)
                (assoc m sym n)
                m)))
          {}
          tracked-form-order))

(defn- namespace-usage-for-summary [counts]
  (reduce-kv
   (fn [usage sym n]
     (if-let [{:keys [bucket namespace fn-name]} (classify-sym sym)]
       (if (= :namespace bucket)
         (update-in usage [namespace fn-name] (fnil + 0) n)
         usage)
       usage))
   (empty-namespace-usage)
   counts))

(defn- rethink-detail [item]
  (let [chars-pruned (pruned-size (:previous item))]
    {:node-id (:node-id item)
     :path (:path item)
     :chars-pruned chars-pruned
     :head-sym (call-head (:previous item))}))

(defn- rethink-stats [details]
  (let [count' (count details)
        total (reduce + 0 (map :chars-pruned details))
        max' (reduce max 0 (map :chars-pruned details))]
    {:count count'
     :total-chars total
     :mean-chars (if (pos? count')
                   (/ (double total) count')
                   0.0)
     :max-chars max'}))

(defn- recovered-by-node-id [sorted-nodes node]
  (let [later-nodes (filter #(< (:id node) (:id %)) sorted-nodes)
        depth (:depth node)
        bounded (if (some? depth)
                  (filter #(or (nil? (:depth %))
                               (<= (:depth %) depth))
                          later-nodes)
                  later-nodes)
        recovered-by (remove :error bounded)]
    (some-> recovered-by first :id)))

(defn- summarize-errors [trace]
  (let [sorted-nodes (sort-by :id (:nodes trace))]
    (->> sorted-nodes
         (filter :error)
         (mapv (fn [node]
                 (let [recovered-by (recovered-by-node-id sorted-nodes node)]
                   {:node-id (:id node)
                    :error (:error node)
                    :recovered? (some? recovered-by)
                    :recovered-by recovered-by}))))))

(defn- namespace-total [summary namespace]
  (reduce + 0 (vals (get-in summary [:namespace-usage namespace] {}))))

(defn- trace-summary-flags [summary]
  (let [tracked (:tracked-counts summary)
        math-used? (pos? (namespace-total summary "math"))
        strings-used? (get-in summary [:namespace-usage "strings"])
        nontrivial-strings-used?
        (some (fn [[fn-name n]]
                (and (pos? n) (contains? nontrivial-strings fn-name)))
              strings-used?)
        function-definitions? (or (pos? (get tracked 'defn 0))
                                  (pos? (get tracked 'fn 0)))]
    (cond-> #{}
      (pos? (get tracked 'persist 0)) (conj :persist-used)
      (pos? (namespace-total summary "globals")) (conj :globals-used)
      (pos? (namespace-total summary "agents")) (conj :agents-used)
      (pos? (namespace-total summary "patterns")) (conj :patterns-used)
      math-used? (conj :nontrivial-math)
      nontrivial-strings-used? (conj :nontrivial-strings)
      (pos? (get tracked '!compact 0)) (conj :compact-used)
      (pos? (get tracked '!llm-self 0)) (conj :llm-self-used)
      (or (pos? (get tracked 'future 0))
          (pos? (get tracked '!ask-await 0))
          (pos? (namespace-total summary "blocking"))) (conj :concurrency-used)
      (pos? (get tracked 'leaf-llm 0)) (conj :leaf-llm-used)
      function-definitions? (conj :function-definitions))))

(defn trace-summary
  "Compute a single trace summary for batch triage and reporting."
  [trace-dir trace]
  (let [parsed-nodes (parse-program-node-responses trace)
        parse-errors (->> parsed-nodes
                          (keep (fn [{:keys [node-id parse-error]}]
                                  (when parse-error
                                    {:node-id node-id
                                     :error parse-error})))
                          vec)
        {:keys [counts]} (count-function-calls-in-parsed-nodes parsed-nodes {:fns nil})
        rethink-details (->> (collect-trace-rethinks-in-parsed-nodes parsed-nodes)
                             (map rethink-detail)
                             vec)
        summary {:trace-dir trace-dir
                 :node-count (count (:nodes trace))
                 :tracked-counts (tracked-counts-for-summary counts)
                 :rethink-stats (rethink-stats rethink-details)
                 :rethink-details rethink-details
                 :namespace-usage (namespace-usage-for-summary counts)
                 :response-parse-errors parse-errors
                 :errors (summarize-errors trace)}]
    (assoc summary
           :flags (cond-> (trace-summary-flags summary)
                    (seq parse-errors) (conj :response-parse-errors)))))

(defn- format-count [n]
  (format "%,dc" (long n)))

(defn- format-mean-count [n]
  (if (= n (Math/rint n))
    (format-count n)
    (format "%.1fc" (double n))))

(defn- print-error-resolution [results-file latest-error record trace-dir]
  (println (str "Results file: " results-file))
  (println "Latest error (source-of-truth):")
  (println (str "  item_id: " (:item_id latest-error)))
  (println (str "  status: " (:status latest-error)))
  (when-let [t (:error_type latest-error)]
    (println (str "  error_type: " t)))
  (when-let [m (:error_message latest-error)]
    (println (str "  error_message: " m)))
  (println (str "  trace_dir: " (or (trace-dir-from-record latest-error) "<not found in latest error>")))
  (when (not= (:item_id latest-error) (:item_id record))
    (println (str "Using nearest prior errored trace with trace_dir: item_id=" (:item_id record)))
    (println (str "  resolved_trace_dir: " trace-dir)))
  (println))

(defn- print-node-summary [trace-dir trace node]
  (println (str "Trace: " trace-dir))
  (println (str "Nodes: " (count (:nodes trace)) " (root=" (:root trace) ")"))
  (println (str "Selected node: " (:id node)
                " depth=" (:depth node)
                " parent=" (:parent node)
                " variant=" (:variant node)))
  (when-let [err (:error node)]
    (println (str "Node error: " err)))
  (println))

(defn- print-skeleton [node max-string-chars]
  (if-let [program (:program node)]
    (do
      (println "Skeletonized program:")
      (pp/pprint (skeletonize-form program {:max-string-chars max-string-chars}))
      (println))
    (println "No :program on selected node (possibly leaf-only node).\n")))

(defn- print-call-counts [{:keys [counts instances]}]
  (if (empty? counts)
    (println "No matching function calls found.\n")
    (do
      (println "Function call counts:")
      (doseq [[f n] (sort-by (juxt (comp - val) (comp str key)) counts)]
        (println (format "  %s: %d" f n)))
      (println)
      (println "Accepted call instances:")
      (doseq [inst instances]
        (println (format "  node=%s fn=%s path=%s"
                         (:node-id inst)
                         (:fn inst)
                         (path->str (:path inst)))))
      (println))))

(defn- print-rethinks-for-trace [trace-dir trace max-string-chars]
  (let [items (collect-trace-rethinks trace)]
    (println (str "Trace: " trace-dir))
    (println (str "Rethinks: " (count items)))
    (if (empty? items)
      (println "  (none)")
      (doseq [it items]
        (println (format "  node=%s path=%s" (:node-id it) (path->str (:path it))))
        (print "    rethink: ")
        (pp/pprint (skeletonize-form (:rethink it) {:max-string-chars max-string-chars}))
        (print "    previous: ")
        (if-let [prev (:previous it)]
          (pp/pprint prev)
          (println "<none>"))))
    (println)))

(defn- print-context-trajectory-for-trace [trace-dir trace]
  (let [items (context-trajectory-items trace-dir trace)]
    (println (str "Trace: " trace-dir))
    (println "Context Trajectory:")
    (if (empty? items)
      (println "  (none)")
      (doseq [{:keys [node-id chars delta rethink-count pruned-chars]} items]
        (let [delta-part (if (some? delta)
                           (format "  (%+,dc)" delta)
                           "")
              note-part (if (pos? rethink-count)
                          (str "  [rethink: pruned " (format-count pruned-chars) "]")
                          "")]
          (println (format "  %04d: %,7dc%s%s"
                           node-id
                           chars
                           delta-part
                           note-part)))))
    (println)))

(defn- print-summary [{:keys [trace-dir node-count tracked-counts rethink-stats rethink-details
                              namespace-usage errors flags]}]
  (println (str "Trace: " trace-dir))
  (println (str "Nodes: " node-count))
  (println)
  (println "=== Tracked Forms ===")
  (if-let [items (seq (filter (comp pos? second)
                              (map (fn [sym] [sym (get tracked-counts sym 0)])
                                   tracked-form-order)))]
    (doseq [[sym n] items]
      (if (= 'rethink sym)
        (println (format "  %s: %d  (total: %s  mean: %s  max: %s)"
                         sym
                         n
                         (format-count (:total-chars rethink-stats))
                         (format-mean-count (:mean-chars rethink-stats))
                         (format-count (:max-chars rethink-stats))))
        (println (format "  %s: %d" sym n))))
    (println "  (none)"))
  (println)
  (println "=== Namespace Usage ===")
  (doseq [namespace namespace-order]
    (let [items (get namespace-usage namespace)]
      (println
       (format "  %s: %s"
               namespace
               (if (seq items)
                 (str/join " "
                           (for [[fn-name n] (sort-by key items)]
                             (format "%s(%d)" fn-name n)))
                 "(none)")))))
  (when (seq rethink-details)
    (println)
    (println "=== Rethink Details ===")
    (doseq [{:keys [node-id path chars-pruned head-sym]} rethink-details]
      (println (format "  node=%s path=%s pruned=%s head=%s"
                       node-id
                       (path->str path)
                       (format-count chars-pruned)
                       (or head-sym "<none>")))))
  (println)
  (println "=== Errors ===")
  (if (seq errors)
    (doseq [{:keys [node-id error recovered? recovered-by]} errors]
      (println (format "  node=%s: %s  recovered=%s%s"
                       node-id
                       (pr-str error)
                       recovered?
                       (if recovered-by
                         (str " recovered-by=" recovered-by)
                         ""))))
    (println "  (none)"))
  (println)
  (println "=== Investigation Flags ===")
  (if (seq flags)
    (println (str "  " (str/join " " (sort-by name flags))))
    (println "  (none)"))
  (println))

(defn summary-tsv-row
  "Return a TSV-ready row for a trace summary."
  [{:keys [trace-dir node-count tracked-counts rethink-stats errors flags] :as summary}]
  (let [peek-count (+ (get tracked-counts '!peek-now 0)
                      (get tracked-counts '!peek 0))
        fatal-errors (count (remove :recovered? errors))
        recovered-errors (count (filter :recovered? errors))]
    [trace-dir
     node-count
     (get tracked-counts 'think 0)
     (get tracked-counts 'rethink 0)
     (format "%.1f" (double (:mean-chars rethink-stats)))
     (:total-chars rethink-stats)
     (:max-chars rethink-stats)
     (get tracked-counts '!extend 0)
     (get tracked-counts '!call-now 0)
     peek-count
     (get tracked-counts '!compact 0)
     (get tracked-counts '!llm-self 0)
     (get tracked-counts '!ask-await 0)
     (get tracked-counts 'persist 0)
     (get tracked-counts '!print 0)
     (get tracked-counts '!describe 0)
     (get tracked-counts 'leaf-llm 0)
     (get tracked-counts 'future 0)
     (get tracked-counts 'defn 0)
     (get tracked-counts 'fn 0)
     (namespace-total summary "io")
     (namespace-total summary "agents")
     (namespace-total summary "globals")
     (namespace-total summary "blocking")
     (namespace-total summary "patterns")
     (namespace-total summary "math")
     (namespace-total summary "strings")
     (namespace-total summary "llms")
     fatal-errors
     recovered-errors
     (str/join " " (sort-by name flags))]))

(defn- print-summary-tsv-header []
  (println (str/join "\t" summary-tsv-columns)))

(defn- print-summary-tsv-row [summary]
  (println (str/join "\t" (summary-tsv-row summary))))

(defn run-tool
  [{:keys [trace-dir trace-root results-jsonl node count-all-nodes rethinks
           context-trajectory summary tsv help string-truncate]
    :as options}
   usage-summary]
  (let [mode-count (count (filter true? [rethinks context-trajectory summary]))]
    (cond
    help
    {:exit 0 :message (usage usage-summary)}

    (> mode-count 1)
    {:exit 1 :message "Choose at most one of --rethinks, --context-trajectory, or --summary"}

    (and tsv (not (and summary trace-root)))
    {:exit 1 :message "--tsv requires --summary with --trace-root"}

    (and (pos? mode-count)
         (nil? trace-dir)
         (nil? trace-root)
         (nil? results-jsonl))
    {:exit 1 :message "Mode requires --trace-dir, --trace-root, or --results-jsonl"}

    (and trace-root (not (or rethinks context-trajectory summary)))
    {:exit 1 :message "--trace-root is currently supported with --rethinks, --context-trajectory, or --summary mode only"}

    (and (nil? trace-dir) (nil? results-jsonl) (nil? trace-root))
    {:exit 1 :message (str "Must provide --trace-dir, --trace-root, or --results-jsonl\n\n" (usage usage-summary))}

    :else
    (if trace-root
      (do
        (when tsv
          (print-summary-tsv-header))
        (doseq [d (find-trace-dirs trace-root)]
          (let [{:keys [trace dir]} (load-trace d)]
            (cond
              summary
              (let [trace-summary (trace-summary dir trace)]
                (if tsv
                  (print-summary-tsv-row trace-summary)
                  (print-summary trace-summary)))

              rethinks
              (print-rethinks-for-trace dir trace (or string-truncate 32))
              :else
              (print-context-trajectory-for-trace dir trace))))
        {:exit 0 :message nil})
      (let [resolution (if results-jsonl
                       (if-let [{:keys [latest-error record trace-dir]} (resolve-trace-from-results results-jsonl)]
                         {:latest-error latest-error :record record :trace-dir trace-dir :from-results? true}
                         {:exit 1 :message (str "No errored records found in " results-jsonl)})
                       {:trace-dir trace-dir :from-results? false})]
        (if (:exit resolution)
          resolution
          (let [{:keys [latest-error record trace-dir from-results?]} resolution]
            (if (nil? trace-dir)
              {:exit 1
               :message (str "Resolved error record has no trace_dir in metadata: "
                             (select-keys latest-error [:item_id :status :error_type]))}
              (let [{:keys [trace dir]} (load-trace trace-dir)
                    fn-set (parse-symbol-set (:fn options))
                    special-mode? (or rethinks context-trajectory summary)
                    needs-target-node? (or (and fn-set (not count-all-nodes) (not summary))
                                           (not special-mode?))
                    target-node (when needs-target-node?
                                  (select-node trace node))]
                (when from-results?
                  (print-error-resolution results-jsonl latest-error record trace-dir))
                (cond
                  summary
                  (print-summary (trace-summary dir trace))

                  rethinks
                  (print-rethinks-for-trace dir trace (or string-truncate 32))

                  context-trajectory
                  (print-context-trajectory-for-trace dir trace)

                  :else
                  (do
                    (print-node-summary dir trace target-node)
                    (print-skeleton target-node (or string-truncate 32))))
                (when (and fn-set (not summary))
                  (if count-all-nodes
                    (do
                      (println "Call counting scope: all nodes (response-only)")
                      (println)
                      (print-call-counts
                       (count-function-calls trace {:fns fn-set})))
                    (do
                      (println "Call counting scope: selected node response only")
                      (println)
                      (if-let [_ (:program target-node)]
                        (print-call-counts
                         (count-function-calls-in-forms (response-forms (:response target-node))
                                                        {:fns fn-set :node-id (:id target-node)}))
                        (println "Selected node has no parsed program; no response code available.\n")))))
                {:exit 0 :message nil})))))))))

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (seq errors)
      (do
        (binding [*out* *err*]
          (doseq [e errors] (println e))
          (println)
          (println (usage summary)))
        (System/exit 1))

      :else
      (let [{:keys [exit message]} (run-tool options summary)]
        (when (seq message)
          (if (zero? exit)
            (println message)
            (binding [*out* *err*] (println message))))
        (System/exit exit)))))
