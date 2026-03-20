(ns spell.agent
  "Agent definition loader and compiler.

   Loads .agent.edn files into plain data specs, then compiles those specs
   into runnable spawn-agent functions.

   Inheritance via :base:
     {:base parent.agent.edn
      :name child
      :namespaces {extra stdlib/extra}}

   Merge semantics:
   - Scalars (:name, :model, etc.): child overrides parent
   - :namespaces: maps are merged (child adds to / overrides parent)

   Resolution patterns for namespace values:
   - stdlib/X           → stdlib namespace
   - stdlib/X/Y         → nested item from stdlib
   - file.clj/var       → load-file, resolve var
   - file.agent.edn     → load agent spec → compile spawn-agent fn
   - {:file f :items m} → submap of vars from file
   - {:file f}          → slurp file as string"
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [spell.runtime :as runtime]
            [spell.format :as format]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.stdlib :as stdlib]
            [spell.io :as io]
            [spell.web :as web]
            [spell.react :as react]
            [spell.context-folding :as context-folding]))

;; =============================================================================
;; Stdlib resolution
;; =============================================================================

(def stdlib-namespaces
  "All stdlib namespaces available via stdlib/ prefix.
   Note: io/ is effectful and can be omitted in custom agent profiles.
   Seqs, fns, and bit- ops are in core-builtins (matching Clojure)."
  {'io io/io-namespace
   'io-read io/io-read-namespace
   'io-write io/io-write-namespace
   'io-exec io/io-exec-namespace
   'web web/web-namespace
   'globals globals/globals-namespace
   'agents runtime/agents-namespace
   'builtins stdlib/builtins-namespace
   'reminders stdlib/reminders-namespace
   'strings stdlib/strings
   'math stdlib/math
   'patterns stdlib/patterns
   'react react/react
   'context-folding context-folding/context-folding})

(defn- resolve-stdlib-path
  "Resolve a stdlib/X or stdlib/X/Y path.
   Returns the namespace or nested item."
  [path-parts]
  (loop [parts path-parts
         current stdlib-namespaces]
    (if (empty? parts)
      current
      (let [part (first parts)
            next-val (get current (symbol part))]
        (if (nil? next-val)
          (throw (ex-info (str "Unknown stdlib path: stdlib/" (str/join "/" path-parts))
                          {:path path-parts}))
          (recur (rest parts) next-val))))))

;; =============================================================================
;; File loading
;; =============================================================================

(defn- load-clj-file
  "Load a .clj file and return a map of all public vars."
  [path base-dir]
  (let [full-path (if (str/starts-with? path "/")
                    path
                    (str base-dir "/" path))]
    (try
      (load-file full-path)
      (catch Exception e
        (throw (ex-info (str "Failed to load Clojure file: " full-path)
                        {:path full-path :error (.getMessage e)}))))))

(defn- resolve-clj-var
  "Resolve a var from a loaded Clojure file.
   Path format: file.clj/var-name"
  [path base-dir clj-cache]
  (let [[file-part var-part] (str/split path #"/" 2)]
    (when-not var-part
      (throw (ex-info (str "Invalid .clj reference, expected file.clj/var: " path)
                      {:path path})))
    ;; Load file if not cached
    (when-not (contains? @clj-cache file-part)
      (load-clj-file file-part base-dir)
      (swap! clj-cache assoc file-part true))
    ;; Resolve the var - it should now be in the current namespace
    (let [var-sym (symbol var-part)]
      (if-let [v (resolve var-sym)]
        @v
        (throw (ex-info (str "Var not found after loading " file-part ": " var-part)
                        {:file file-part :var var-part}))))))

(defn- slurp-file
  "Read a file relative to base-dir."
  [path base-dir]
  (let [full-path (if (str/starts-with? path "/")
                    path
                    (str base-dir "/" path))]
    (try
      (slurp full-path)
      (catch Exception e
        (throw (ex-info (str "Failed to read file: " full-path)
                        {:path full-path :error (.getMessage e)}))))))

;; =============================================================================
;; Forward declarations for recursive spec loading / compilation
;; =============================================================================

(declare load-agent-spec compile-agent-spec)

;; =============================================================================
;; Value resolution
;; =============================================================================

(defn- join-doc-snippets
  [left right]
  (str/join "; " (remove str/blank? [left right])))

(defn- join-guides
  [left right]
  (str/join "\n\n" (remove str/blank? [left right])))

(defn- merge-namespace-maps
  [& ns-maps]
  (reduce (fn [merged ns-map]
            (let [merged-docs (:docs merged)
                  ns-docs (:docs ns-map)]
              (-> (merge merged (dissoc ns-map :short-docs :docs :detail))
                  (assoc :short-docs (join-doc-snippets (:short-docs merged) (:short-docs ns-map)))
                  (assoc :docs (cond-> (merge (dissoc merged-docs :guide)
                                              (dissoc ns-docs :guide))
                                 (or (:guide merged-docs) (:guide ns-docs))
                                 (assoc :guide (join-guides (:guide merged-docs) (:guide ns-docs)))))
                  (assoc :detail (merge (:detail merged) (:detail ns-map))))))
          {}
          ns-maps))

(defn- resolve-namespace-value
  "Resolve a single namespace value according to pattern rules.

   Patterns:
   - stdlib/X or stdlib/X/Y  → stdlib namespace/item
   - [a b c]                 → resolve and merge namespace maps
   - file.clj/var            → var from Clojure file
   - file.agent.edn          → load agent spec → compiled spawn-agent fn
   - {:file f :items {...}}  → submap of vars from file
   - {:file f}               → file content as string"
  [value base-dir clj-cache compile-agent-fn]
  (cond
    ;; Map with :file key
    (and (map? value) (:file value))
    (let [file-path (str (:file value))]
      (if (:items value)
        ;; {:file f :items {name var ...}} → submap
        (do
          (load-clj-file file-path base-dir)
          (swap! clj-cache assoc file-path true)
          (into {}
                (map (fn [[k v]]
                       [k (if-let [resolved (resolve (symbol (str v)))]
                            @resolved
                            (throw (ex-info (str "Var not found: " v)
                                            {:file file-path :var v})))])
                     (:items value))))
        ;; {:file f} → slurp as string
        (slurp-file file-path base-dir)))

    ;; Vector - merge resolved namespace maps
    (vector? value)
    (let [resolved (mapv #(resolve-namespace-value % base-dir clj-cache compile-agent-fn) value)]
      (when-not (every? map? resolved)
        (throw (ex-info "Vector namespace values must resolve to namespace maps"
                        {:value value
                         :resolved-types (mapv type resolved)})))
      (apply merge-namespace-maps resolved))

    ;; Symbol - check pattern
    (symbol? value)
    (let [s (str value)]
      (cond
        ;; stdlib/X pattern
        (str/starts-with? s "stdlib/")
        (let [path-parts (rest (str/split s #"/"))]
          (resolve-stdlib-path path-parts))

        ;; file.clj/var pattern
        (and (str/includes? s ".clj/")
             (not (str/ends-with? s ".clj")))
        (resolve-clj-var s base-dir clj-cache)

        ;; file.agent.edn pattern
        (str/ends-with? s ".agent.edn")
        (compile-agent-fn (load-agent-spec s base-dir))

        :else
        (throw (ex-info (str "Unknown namespace value pattern: " value)
                        {:value value}))))

    ;; String - treat as literal
    (string? value)
    value

    :else
    (throw (ex-info (str "Invalid namespace value: " value)
                    {:value value :type (type value)}))))

(defn- resolve-namespaces
  "Resolve all namespace entries in the agent definition."
  [namespaces base-dir compile-agent-fn]
  (let [clj-cache (atom {})]
    (into {}
          (map (fn [[k v]]
                 [(symbol k) (resolve-namespace-value v base-dir clj-cache compile-agent-fn)])
               namespaces))))

;; =============================================================================
;; System prompt resolution
;; =============================================================================

(defn- resolve-system-prompt
  "Resolve system prompt - either inline string or {:file path}."
  [system base-dir]
  (cond
    (nil? system) nil
    (string? system) system
    (and (map? system) (:file system))
    (slurp-file (str (:file system)) base-dir)
    :else
    (throw (ex-info "Invalid :system value, expected string or {:file path}"
                    {:system system}))))

;; =============================================================================
;; Inheritance
;; =============================================================================

(defn- read-agent-edn
  "Read and parse an agent .edn file. Returns raw EDN map."
  [path base-dir]
  (let [full-path (if (and base-dir (not (str/starts-with? path "/")))
                    (str base-dir "/" path)
                    path)]
    (try
      (edn/read-string (slurp full-path))
      (catch Exception e
        (throw (ex-info (str "Failed to read agent file: " full-path)
                        {:path full-path :error (.getMessage e)}))))))

(defn- merge-agent-defs
  "Merge child agent def onto parent.
   - Scalars: child wins if present (includes :llms — child replaces entirely)
   - :namespaces: maps are merged (child overrides parent entries)"
  [parent child]
  (let [;; Start with parent, override with non-nil child scalars
        merged (reduce (fn [m k]
                         (if (contains? child k)
                           (assoc m k (get child k))
                           m))
                       parent
                       [:name :doc :system :model :budget :recover :format :max-retries :retries
                        :thinking :reasoning-effort :verbosity :suffix-grammar? :grammar-max-chars
                        :api :llms :provider])
        ;; Merge namespaces
        merged (if (or (:namespaces parent) (:namespaces child))
                 (assoc merged :namespaces
                        (merge (:namespaces parent) (:namespaces child)))
                 merged)]
    merged))

(defn- resolve-inheritance
  "Resolve :base inheritance chain, returning fully merged agent def.
   Removes :base from result."
  [agent-def base-dir]
  (if-let [base-path (:base agent-def)]
    (let [base-path-str (str base-path)
          base-file (java.io.File. (if (str/starts-with? base-path-str "/")
                                     base-path-str
                                     (str base-dir "/" base-path-str)))
          base-dir' (.getParent base-file)
          base-def (read-agent-edn base-path-str base-dir)
          resolved-base (resolve-inheritance base-def base-dir')]
      ;; Merge child onto resolved base, remove :base key
      (dissoc (merge-agent-defs resolved-base agent-def) :base))
    ;; No base, return as-is
    agent-def))

;; =============================================================================
;; LLMs auto-discovery
;; =============================================================================

(defn- agent-name-from-file
  "Derive agent name symbol from filename: \"opus.agent.edn\" → 'opus"
  [filename]
  (symbol (str/replace filename #"\.agent\.edn$" "")))

(defn- discover-sibling-agents
  "Find all .agent.edn files in base-dir. Returns {name-sym filename, ...}."
  [base-dir]
  (when base-dir
    (let [dir (java.io.File. base-dir)
          files (.listFiles dir)]
      (when files
        (into {}
              (comp
                (filter #(.isFile %))
                (map #(.getName %))
                (filter #(str/ends-with? % ".agent.edn"))
                (map (fn [f] [(agent-name-from-file f) (symbol f)])))
              files)))))

(defn- normalize-llms-config
  "Normalize raw :llms value into a map for resolve-llms, or nil.
   - ::not-set + base-dir → discover siblings
   - ::not-set + nil base-dir → nil
   - [] → nil (opt-out)
   - vector of symbols → {(agent-name sym) sym, ...}
   - map → pass through"
  [raw-llms base-dir]
  (cond
    (= raw-llms ::not-set)
    (discover-sibling-agents base-dir)

    (and (vector? raw-llms) (empty? raw-llms))
    nil

    (vector? raw-llms)
    (into {} (map (fn [s] [(agent-name-from-file (str s)) s]) raw-llms))

    (map? raw-llms)
    raw-llms

    :else nil))

;; =============================================================================
;; LLMs namespace resolution
;; =============================================================================

(defn- resolve-llm-spec
  "Resolve an llm spec value into a plain agent spec map.
   - symbol ending in .agent.edn → load agent file, return its spec
   - map → treat as inline spec (mini agent spec)"
  [value base-dir]
  (cond
    ;; Symbol referencing a .agent.edn file
    (and (symbol? value) (str/ends-with? (str value) ".agent.edn"))
    (let [path (str value)
          full-path (if (and base-dir (not (str/starts-with? path "/")))
                      (str base-dir "/" path)
                      path)
          file (java.io.File. full-path)
          file-base-dir (.getParent file)
          raw-def (read-agent-edn full-path nil)]
      (assoc (resolve-inheritance raw-def file-base-dir) :base-dir file-base-dir))

    ;; Inline map spec
    (map? value)
    (cond-> value
      base-dir (assoc :base-dir base-dir))

    :else
    (throw (ex-info (str "Invalid llm spec: " value ". Expected .agent.edn symbol or inline map.")
                    {:value value}))))

(defn- compile-runtime-agent-from-resolved-spec
  "Compile a resolved agent spec after model/provider/system/namespaces
   have already been determined."
  [spec compile-runtime-agent-fn {:keys [model provider system namespaces]}]
  (let [base-agent (compile-runtime-agent-fn
                    (cond-> {}
                      (seq namespaces) (assoc :namespaces namespaces)
                      model (assoc :model model)
                      system (assoc :system system)
                      provider (assoc :provider provider)
                      (some? (:recover spec)) (assoc :recover (:recover spec))
                      (:format spec) (assoc :format (:format spec))
                      (some? (:prefill? spec)) (assoc :prefill? (:prefill? spec))
                      (some? (:thinking spec)) (assoc :thinking (:thinking spec))
                      (:reasoning-effort spec) (assoc :reasoning-effort (:reasoning-effort spec))
                      (:verbosity spec) (assoc :verbosity (:verbosity spec))
                      (some? (:suffix-grammar? spec)) (assoc :suffix-grammar? (:suffix-grammar? spec))
                      (:grammar-max-chars spec) (assoc :grammar-max-chars (:grammar-max-chars spec))
                      (some? (:temperature spec)) (assoc :temperature (:temperature spec))))]
    (if (:format spec)
      (format/wrap-with-format base-agent {:format (:format spec)
                                           :eval? true
                                           :max-retries (or (:max-retries spec) 3)})
      base-agent)))

(defn- build-compiled-agent-from-spec
  "Build a compiled spawn-agent function from a resolved agent spec.
   Used for llms/ entries, which inherit parent model/provider and share a
   common llms namespace for circular references."
  [spec compile-runtime-agent-fn compile-agent-fn model parent-provider extra-namespaces base-dir]
  (let [spec-base-dir (or (:base-dir spec) base-dir)
        spec-model (or (:model spec) model)
        spec-provider (if (contains? spec :provider)
                        (provider/resolve-provider (:provider spec) spec-base-dir)
                        parent-provider)
        system (resolve-system-prompt (:system spec) spec-base-dir)
        spec-namespaces (when (:namespaces spec)
                          (resolve-namespaces (:namespaces spec) spec-base-dir compile-agent-fn))
        all-namespaces (merge extra-namespaces spec-namespaces)]
    (compile-runtime-agent-from-resolved-spec spec compile-runtime-agent-fn
                                              {:model spec-model
                                               :provider spec-provider
                                               :system system
                                               :namespaces all-namespaces})))

(defn resolve-llms
  "Resolve :llms map into an effect namespace of compiled spawn-agent functions.
   Uses atom-based lazy init for circular references (A can refer to B, B can refer to A).
   Returns namespace map with :docs and compiled agent functions, or nil if no llms."
  [llms-map compile-runtime-agent-fn compile-agent-fn model parent-provider base-dir]
  (when (seq llms-map)
    ;; Phase 1: create atoms and proxy namespace
    (let [agent-atoms (into {} (map (fn [[k _]] [k (atom nil)]) llms-map))
          docs (into {} (map (fn [[k v]]
                               (let [spec (resolve-llm-spec v base-dir)
                                     doc (or (:doc spec) (str "Sub-agent: " (name k)))]
                                 [(keyword k) doc]))
                             llms-map))
          llms-ns (merge {:docs docs}
                         (into {} (map (fn [[k _]]
                                         [(keyword k)
                                          (with-meta
                                            (fn [prompt handle]
                                              (@(get agent-atoms k) prompt handle))
                                            {:spell/compiled-agent true})])
                                       llms-map)))]
      ;; Phase 2: resolve each spec and fill atoms
      (doseq [[k v] llms-map]
        (let [spec (resolve-llm-spec v base-dir)
              agent-fn (build-compiled-agent-from-spec spec compile-runtime-agent-fn compile-agent-fn
                                                       model parent-provider {'llms llms-ns} base-dir)]
          (reset! (get agent-atoms k) agent-fn)))
      llms-ns)))

;; =============================================================================
;; Main loader
;; =============================================================================

(defn load-agent-spec
  "Load an agent definition from a .agent.edn file into a plain data spec map.
   Supports inheritance via :base - child agents can extend parent agents."
  ([path] (load-agent-spec path nil))
  ([path base-dir]
   (let [full-path (if (and base-dir (not (str/starts-with? path "/")))
                     (str base-dir "/" path)
                     path)
         file (java.io.File. full-path)
         base-dir' (.getParent file)
         raw-def (read-agent-edn full-path nil)
         agent-def (resolve-inheritance raw-def base-dir')]
     (assoc agent-def :base-dir base-dir'))))

(def ^:private future-only-namespaces
  "Namespaces injected by the evaluator rather than agent :namespaces config."
  #{'blocking})

(defn- available-namespace-names
  [namespaces]
  (into future-only-namespaces
        (concat (keys llm/core-namespaces)
                (keys (or namespaces {})))))

(defn- validate-pattern-dependencies!
  "Fail fast when an agent exposes patterns whose declared namespace
   dependencies are not available in that agent profile."
  [namespaces]
  (when-let [patterns-ns (get namespaces 'patterns)]
    (let [available (available-namespace-names namespaces)]
      (doseq [[pattern-name pattern-fn] patterns-ns
              :let [required (set (:requires pattern-fn))
                    missing (set/difference required available)]
              :when (seq missing)]
        (throw (ex-info (str "Pattern " (name pattern-name)
                             " requires namespaces " (sort required)
                             " but agent is missing " (sort missing)
                             ". Add the missing namespace(s) to :namespaces in the agent's .agent.edn.")
                        {:pattern pattern-name
                         :requires (sort required)
                         :missing (sort missing)
                         :available (sort available)}))))))

(defn compile-agent-spec
  "Compile a plain data agent spec into a runnable spawn-agent function."
  [agent-spec]
  (let [{:keys [base-dir system model budget recover namespaces format max-retries retries
                prefill? thinking reasoning-effort verbosity suffix-grammar? grammar-max-chars
                api provider]} agent-spec
        resolved-system (resolve-system-prompt system base-dir)
        resolved-provider (when provider
                            (provider/resolve-provider provider base-dir))
        compile-agent-fn compile-agent-spec
        resolved-namespaces (when namespaces
                              (resolve-namespaces namespaces base-dir compile-agent-fn))
        _ (validate-pattern-dependencies! resolved-namespaces)
        raw-llms (get agent-spec :llms ::not-set)
        llms (normalize-llms-config raw-llms base-dir)
        llms-ns (when (seq llms)
                  (resolve-llms llms llm/compile-agent compile-agent-fn model resolved-provider base-dir))
        all-namespaces (cond-> (or resolved-namespaces {})
                         llms-ns (assoc 'llms llms-ns))]
    (compile-runtime-agent-from-resolved-spec agent-spec llm/compile-agent
                                              {:model model
                                               :provider resolved-provider
                                               :system resolved-system
                                               :namespaces all-namespaces})))
