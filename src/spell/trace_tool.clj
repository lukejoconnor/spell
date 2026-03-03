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
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

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
   [nil "--no-dedupe" "Disable cross-node dedupe (counts repeated inherited prefix calls)"]
   [nil "--rethinks" "Report rethink forms and preceding expressions (single trace or trace root)"]
   ["-h" "--help" "Show help"]])

(defn- usage [summary]
  (str/join
   "\n"
   ["spell.trace-tool - analyze a single Spell trace"
    ""
   "Usage:"
   "  clj -M -m spell.trace-tool --trace-dir DIR [--node ID] [--fn SYMBOL ...]"
   "  clj -M -m spell.trace-tool --trace-root DIR --rethinks"
   "  clj -M -m spell.trace-tool --results-jsonl FILE [--fn SYMBOL ...]"
    ""
    "Notes:"
    "  - --results-jsonl resolves the latest errored item and uses its trace_dir"
    "  - Function call counting defaults to selected node only (typically latest extension node)"
    "  - Use --count-all-nodes to aggregate across nodes; this mode dedupes by default"
    ""
    "Options:"
    summary]))

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

(defn- call-head [form]
  (when (seq? form)
    (let [h (first form)]
      (when (symbol? h) h))))

(defn collect-call-instances
  "Collect call instances as {:fn sym :path vec :form call-form}.
   Path is structural index path rooted at the provided form."
  ([form] (collect-call-instances form []))
  ([form path]
   (let [current (when-let [f (call-head form)]
                   [{:fn f :path path :form form}])
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
     (concat current children))))

(defn- call-fingerprint [inst]
  ;; Use skeletonized form to keep fingerprints stable while avoiding giant strings.
  [(:fn inst)
   (:path inst)
   ;; Always collapse strings fully for dedupe stability regardless of display settings.
   (pr-str (skeletonize-form (:form inst) {:max-string-chars 0}))])

(defn count-function-calls
  "Count function call instances across all trace nodes with optional dedupe.

   opts:
   - :fns set of symbols to include (nil = all)
   - :dedupe? true => do not double-count repeated inherited prefix calls across nodes"
  [trace {:keys [fns dedupe?] :or {dedupe? true}}]
  (let [nodes (filter :program (:nodes trace))]
    (loop [remaining nodes
           seen #{}
           counts {}
           instances []]
      (if (empty? remaining)
        {:counts counts
         :instances instances}
        (let [node (first remaining)
              raw-instances (->> (collect-call-instances (:program node))
                                 (filter #(or (nil? fns) (contains? fns (:fn %)))))
              accepted (if dedupe?
                         (remove #(contains? seen (call-fingerprint %)) raw-instances)
                         raw-instances)
              seen' (if dedupe?
                      (into seen (map call-fingerprint accepted))
                      seen)
              counts' (reduce (fn [m inst] (update m (:fn inst) (fnil inc 0))) counts accepted)
              tagged (map #(assoc % :node-id (:id node)) accepted)]
          (recur (rest remaining)
                 seen'
                 counts'
                 (into instances tagged)))))))

(defn count-function-calls-in-form
  "Count function call instances inside one program form.
   opts:
   - :fns set of symbols to include (nil = all)
   - :node-id node id for reporting context"
  [program {:keys [fns node-id]}]
  (let [instances (->> (collect-call-instances program)
                       (filter #(or (nil? fns) (contains? fns (:fn %))))
                       (map #(assoc % :node-id node-id)))]
    {:counts (reduce (fn [m inst] (update m (:fn inst) (fnil inc 0))) {} instances)
     :instances instances}))

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

(defn collect-trace-rethinks
  "Collect rethinks from all program nodes in one trace.
   Adds :node-id for reporting."
  [trace]
  (->> (:nodes trace)
       (filter :program)
       (mapcat (fn [node]
                 (map #(assoc % :node-id (:id node))
                      (collect-rethinks (:program node)))))))

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

(defn- path->str [path]
  (str "[" (str/join " " (map pr-str path)) "]"))

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

(defn run-tool
  [{:keys [trace-dir trace-root results-jsonl node no-dedupe count-all-nodes rethinks help string-truncate]
    :as options}
   summary]
  (cond
    help
    {:exit 0 :message (usage summary)}

    (and rethinks (nil? trace-dir) (nil? trace-root))
    {:exit 1 :message "Rethink mode requires --trace-dir or --trace-root"}

    (and trace-root (not rethinks))
    {:exit 1 :message "--trace-root is currently supported with --rethinks mode only"}

    (and (nil? trace-dir) (nil? results-jsonl) (nil? trace-root))
    {:exit 1 :message (str "Must provide --trace-dir, --trace-root, or --results-jsonl\n\n" (usage summary))}

    :else
    (if trace-root
      (do
        (doseq [d (find-trace-dirs trace-root)]
          (let [{:keys [trace dir]} (load-trace d)]
            (print-rethinks-for-trace dir trace (or string-truncate 32))))
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
                    target-node (select-node trace node)
                    fn-set (parse-symbol-set (:fn options))]
                (when from-results?
                  (print-error-resolution results-jsonl latest-error record trace-dir))
                (if rethinks
                  (print-rethinks-for-trace dir trace (or string-truncate 32))
                  (do
                    (print-node-summary dir trace target-node)
                    (print-skeleton target-node (or string-truncate 32))))
                (when fn-set
                  (if count-all-nodes
                    (do
                      (println (str "Call counting scope: all nodes (" (if no-dedupe "naive" "deduped") ")"))
                      (println)
                      (print-call-counts
                       (count-function-calls trace {:fns fn-set :dedupe? (not no-dedupe)})))
                    (do
                      (println "Call counting scope: selected node only")
                      (println)
                      (if-let [program (:program target-node)]
                        (print-call-counts
                         (count-function-calls-in-form program {:fns fn-set :node-id (:id target-node)}))
                        (println "Selected node has no :program; no call counts available.\n")))))
                {:exit 0 :message nil}))))))))

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
