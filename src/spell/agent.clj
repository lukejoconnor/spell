(ns spell.agent
  "Agent profile loader and compiler.

   Loads .agent.edn files into plain profile specs, then compiles those specs
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
   - file.agent.edn     → load agent profile spec → compile spawn-agent fn
   - {:file f :items m} → submap of vars from file
   - {:file f}          → slurp file as string"
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [spell.runtime :as runtime]
            [spell.format :as format]
            [spell.globals :as globals]
            [spell.feedback :as feedback]
            [spell.llm :as llm]
            [spell.mcp.namespace :as mcp]
            [spell.provider :as provider]
            [spell.stdlib :as stdlib]
            [spell.io :as io]
            [spell.web :as web]))

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
   'feedback feedback/feedback-namespace
   'agents runtime/agents-namespace
   'builtins stdlib/builtins-namespace
   'reminders stdlib/reminders-namespace
   'strings stdlib/strings
   'math stdlib/math
   'patterns stdlib/patterns})

(defn close-compiled-agent!
  "Release resources owned by a compiled agent, including MCP stdio processes."
  [compiled-agent]
  (when-let [close! (:spell/close! (meta compiled-agent))]
    (close!))
  nil)

(defn- attach-close [compiled-agent close-fns]
  (let [close-fns (vec (remove nil? close-fns))]
    (if (seq close-fns)
      (with-meta compiled-agent
        (assoc (meta compiled-agent) :spell/close!
               (fn [] (doseq [close! close-fns] (close!)))))
      compiled-agent)))

(defn- owned-close-fns [values]
  (keep (comp :spell/close! meta) values))

(defn- namespace-close-fns [namespaces]
  (owned-close-fns (vals (or namespaces {}))))

(defn- close-owned-values! [values]
  (doseq [close! (owned-close-fns values)]
    (close!)))

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
   - file.agent.edn          → load agent profile spec → compiled spawn-agent fn
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
    (let [resolved-ref (atom [])]
      (try
        (doseq [item value]
          (swap! resolved-ref conj
                 (resolve-namespace-value item base-dir clj-cache compile-agent-fn)))
        (let [resolved @resolved-ref]
          (when-not (every? map? resolved)
            (throw (ex-info "Vector namespace values must resolve to namespace maps"
                            {:value value
                             :resolved-types (mapv type resolved)})))
          (apply merge-namespace-maps resolved))
        (catch Throwable e
          (close-owned-values! @resolved-ref)
          (throw e))))

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
  "Resolve all namespace entries in the agent profile definition."
  [namespaces base-dir compile-agent-fn]
  (let [normalized-entries
        (reduce (fn [{:keys [seen entries] :as state} [key value]]
                  (let [normalized-key (symbol key)]
                    (when (contains? seen normalized-key)
                      (throw (ex-info
                              (str "Duplicate agent namespace after normalization: " normalized-key)
                              {:type :duplicate-agent-namespace
                               :namespace normalized-key})))
                    (assoc state
                           :seen (conj seen normalized-key)
                           :entries (conj entries [normalized-key value]))))
                {:seen #{} :entries []}
                namespaces)
        clj-cache (atom {})
        resolved-ref (atom {})]
    (try
      (doseq [[k v] (:entries normalized-entries)]
        (swap! resolved-ref assoc
               k
               (resolve-namespace-value v base-dir clj-cache compile-agent-fn)))
      @resolved-ref
      (catch Throwable e
        (close-owned-values! (vals @resolved-ref))
        (throw e)))))

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
        (throw (ex-info (str "Failed to read agent profile file: " full-path)
                        {:path full-path :error (.getMessage e)}))))))

(defn- normalize-agent-def
  "Normalize public agent profile keys into the internal runtime keys."
  [agent-def]
  (cond-> agent-def
    (contains? agent-def :agent-name)
    (-> (assoc :name (:agent-name agent-def))
        (dissoc :agent-name))

    (contains? agent-def :agent-description)
    (-> (assoc :doc (:agent-description agent-def))
        (dissoc :agent-description))

    (contains? agent-def :system-prompt)
    (-> (assoc :system (:system-prompt agent-def))
        (dissoc :system-prompt))

    (contains? agent-def :default-model-profile)
    (-> (assoc :provider (:default-model-profile agent-def))
        (dissoc :default-model-profile))

    (contains? agent-def :default-budget)
    (-> (assoc :budget (:default-budget agent-def))
        (dissoc :default-budget))

    (contains? agent-def :format-retries)
    (-> (assoc :max-retries (:format-retries agent-def))
        (dissoc :format-retries))

    (contains? agent-def :available-agents)
    (-> (assoc :workers (:available-agents agent-def))
        (dissoc :available-agents))))

(defn- merge-agent-defs
  "Merge child agent profile def onto parent.
   - Scalars: child wins if present (includes :workers — child replaces entirely)
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
                        :api :workers :provider])
        ;; Merge namespaces
        merged (if (or (:namespaces parent) (:namespaces child))
                 (assoc merged :namespaces
                        (merge (:namespaces parent) (:namespaces child)))
                 merged)
        merged (if (or (:mcp-servers parent) (:mcp-servers child))
                 (assoc merged :mcp-servers
                        (merge (:mcp-servers parent) (:mcp-servers child)))
                 merged)]
    merged))

(defn- resolve-inheritance
  "Resolve :base inheritance chain, returning fully merged agent profile def.
   Removes :base from result."
  [agent-def base-dir]
  (let [agent-def (normalize-agent-def agent-def)
        agent-def (if (:mcp-servers agent-def)
                    (update agent-def :mcp-servers
                            (fn [servers]
                              (into {}
                                    (map (fn [[alias entry]]
                                           [alias (if (map? entry)
                                                    (assoc entry :spell.mcp/base-dir base-dir)
                                                    entry)]))
                                    servers)))
                    agent-def)]
    (if-let [base-path (:base agent-def)]
      (let [base-path-str (str base-path)
            base-file (java.io.File. (if (str/starts-with? base-path-str "/")
                                       base-path-str
                                       (str base-dir "/" base-path-str)))
            base-dir' (.getParent base-file)
            base-def (normalize-agent-def (read-agent-edn base-path-str base-dir))
            resolved-base (resolve-inheritance base-def base-dir')]
        ;; Merge child onto resolved base, remove :base key
        (dissoc (merge-agent-defs resolved-base agent-def) :base))
      ;; No base, return as-is
      agent-def)))

;; =============================================================================
;; Workers config normalization
;; =============================================================================

(defn- agent-name-from-file
  "Derive agent name symbol from filename: \"opus.agent.edn\" → 'opus"
  [filename]
  (symbol (str/replace filename #"\.agent\.edn$" "")))

(defn- normalize-workers-config
  "Normalize raw :workers value into a map for resolve-workers, or nil.
   - ::not-set → nil
   - [] → nil (opt-out)
   - vector of symbols → {(agent-name sym) sym, ...}
   - map → pass through"
  [raw-workers _base-dir]
  (cond
    (= raw-workers ::not-set)
    nil

    (and (vector? raw-workers) (empty? raw-workers))
    nil

    (vector? raw-workers)
    (into {} (map (fn [s] [(agent-name-from-file (str s)) s]) raw-workers))

    (map? raw-workers)
    raw-workers

    :else nil))

;; =============================================================================
;; Workers namespace resolution
;; =============================================================================

(defn- resolve-worker-spec
  "Resolve a worker spec value into a plain agent profile spec map.
   - symbol ending in .agent.edn → load agent profile file, return its spec
   - map → treat as inline spec (mini agent profile spec)"
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
    (cond-> (normalize-agent-def value)
      base-dir (assoc :base-dir base-dir))

    :else
    (throw (ex-info (str "Invalid worker spec: " value ". Expected .agent.edn symbol or inline map.")
                    {:value value}))))

(defn- compile-runtime-agent-from-resolved-spec
  "Compile a resolved agent profile spec after model/provider/system/namespaces
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
                      (:grammar-max-chars spec) (assoc :grammar-max-chars (:grammar-max-chars spec))))]
    (if (:format spec)
      (format/wrap-with-format base-agent {:format (:format spec)
                                           :eval? true
                                           :max-retries (or (:max-retries spec) 3)})
      base-agent)))

(defn- build-compiled-agent-from-spec
  "Build a compiled spawn-agent function from a resolved agent profile spec.
   Used for workers/ entries, which inherit parent model/provider and share a
   common workers namespace for circular references."
  [spec compile-runtime-agent-fn compile-agent-fn model parent-provider
   extra-namespaces namespace-overrides base-dir]
  (let [spec-base-dir (or (:base-dir spec) base-dir)
        spec-has-provider? (contains? spec :provider)
        spec-model (or (:model spec)
                       (when-not spec-has-provider? model))
        spec-provider (if (contains? spec :provider)
                        (provider/resolve-provider (:provider spec) spec-base-dir)
                        parent-provider)
        system (resolve-system-prompt (:system spec) spec-base-dir)
        spec-namespaces-ref (atom nil)
        mcp-bundle-ref (atom nil)]
    (try
      (let [spec-namespaces (when (:namespaces spec)
                              (resolve-namespaces (:namespaces spec) spec-base-dir compile-agent-fn))
            _ (reset! spec-namespaces-ref spec-namespaces)
            mcp-bundle (when (seq (:mcp-servers spec))
                         (mcp/compile-servers (:mcp-servers spec) spec-base-dir))
            _ (reset! mcp-bundle-ref mcp-bundle)
            mcp-namespaces (:namespaces mcp-bundle)
            occupied (set (concat (keys spec-namespaces)
                                  (keys extra-namespaces)
                                  (keys namespace-overrides)
                                  (conj (set (keys llm/core-namespaces)) 'skills)))
            collisions (set/intersection occupied (set (keys mcp-namespaces)))
            _ (when (seq collisions)
                (throw (ex-info (str "MCP server aliases collide with configured namespaces: "
                                     (sort collisions))
                                {:type :mcp-namespace-collision :namespaces (sort collisions)})))
            all-namespaces (merge extra-namespaces spec-namespaces
                                  mcp-namespaces namespace-overrides)
            compiled (compile-runtime-agent-from-resolved-spec spec compile-runtime-agent-fn
                                                               {:model spec-model
                                                                :provider spec-provider
                                                                :system system
                                                                :namespaces all-namespaces})]
        (attach-close compiled (cons (:close! mcp-bundle)
                                     (namespace-close-fns spec-namespaces))))
      (catch Throwable e
        (when-let [close! (:close! @mcp-bundle-ref)] (close!))
        (close-owned-values! (vals (or @spec-namespaces-ref {})))
        (throw e)))))

(defn resolve-workers
  "Resolve :workers map into an effect namespace of compiled spawn-agent functions.
   Uses atom-based lazy init for circular references (A can refer to B, B can refer to A).
   Returns namespace map with :docs and compiled agent functions, or nil if no workers."
  ([workers-map compile-runtime-agent-fn compile-agent-fn model parent-provider base-dir]
   (resolve-workers workers-map compile-runtime-agent-fn compile-agent-fn
                    model parent-provider base-dir {}))
  ([workers-map compile-runtime-agent-fn compile-agent-fn model parent-provider base-dir inherited-namespaces]
   (when (seq workers-map)
     ;; Phase 1: create atoms and proxy namespace
     (let [agent-atoms (into {} (map (fn [[k _]] [k (atom nil)]) workers-map))
           docs (into {} (map (fn [[k v]]
                                (let [spec (resolve-worker-spec v base-dir)
                                      doc (or (:doc spec) (str "Sub-agent: " (name k)))]
                                  [(keyword k) doc]))
                              workers-map))
           workers-ns (merge {:docs docs}
                          (into {} (map (fn [[k _]]
                                          [(keyword k)
                                           (with-meta
                                             (fn
                                               ([prompt] (@(get agent-atoms k) prompt (keyword (gensym (str (name k) "-")))))
                                               ([prompt handle] (@(get agent-atoms k) prompt handle)))
                                             {:spell/compiled-agent true
                                              :spell/close!
                                              (fn []
                                                (when-let [compiled @(get agent-atoms k)]
                                                  (close-compiled-agent! compiled)))})])
                                        workers-map)))]
       ;; Phase 2: resolve each spec and fill atoms
       (try
         (doseq [[k v] workers-map]
           (let [spec (resolve-worker-spec v base-dir)
                 agent-fn (build-compiled-agent-from-spec spec compile-runtime-agent-fn compile-agent-fn
                                                          model parent-provider {'workers workers-ns}
                                                          inherited-namespaces base-dir)]
             (reset! (get agent-atoms k) agent-fn)))
         workers-ns
         (catch Throwable e
           (doseq [[_ compiled] agent-atoms]
             (when-let [compiled @compiled]
               (close-compiled-agent! compiled)))
           (throw e)))))))

;; =============================================================================
;; Main loader
;; =============================================================================

(defn load-agent-spec
  "Load an agent profile definition from a .agent.edn file into a plain data spec map.
   Supports inheritance via :base - child agents can extend parent agents."
  ([path] (load-agent-spec path nil))
  ([path base-dir]
   (let [full-path (if (and base-dir (not (str/starts-with? path "/")))
                     (str base-dir "/" path)
                     path)
         file (java.io.File. full-path)
         base-dir' (.getParent file)
         raw-def (normalize-agent-def (read-agent-edn full-path nil))
         agent-def (resolve-inheritance raw-def base-dir')]
     (assoc agent-def :base-dir base-dir'))))

(def ^:private future-only-namespaces
  "Namespaces injected by the evaluator rather than agent :namespaces config."
  #{'blocking})

(defn- available-namespace-names
  [namespaces]
  (into future-only-namespaces
        (concat (conj (set (keys llm/core-namespaces)) 'skills)
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
  "Compile a plain data agent profile spec into a runnable spawn-agent function."
  [agent-spec]
  (let [{:keys [base-dir system model budget recover namespaces namespace-overrides
                mcp-servers format max-retries retries
                prefill? thinking reasoning-effort verbosity suffix-grammar? grammar-max-chars
                api provider]} agent-spec
        resolved-system (resolve-system-prompt system base-dir)
        resolved-provider (when provider
                            (provider/resolve-provider provider base-dir))
        compile-agent-fn compile-agent-spec
        resolved-namespaces-ref (atom nil)
        resolved-overrides-ref (atom nil)
        mcp-bundle-ref (atom nil)
        workers-ns-ref (atom nil)]
    (try
      (let [resolved-namespaces (when namespaces
                                  (resolve-namespaces namespaces base-dir compile-agent-fn))
            _ (reset! resolved-namespaces-ref resolved-namespaces)
            resolved-overrides (when namespace-overrides
                                 (resolve-namespaces namespace-overrides base-dir compile-agent-fn))
            _ (reset! resolved-overrides-ref resolved-overrides)
            profile-namespaces (merge resolved-namespaces resolved-overrides)
            mcp-bundle (when (seq mcp-servers)
                         (mcp/compile-servers mcp-servers
                                              (or base-dir (System/getProperty "user.dir"))))
            _ (reset! mcp-bundle-ref mcp-bundle)
            mcp-namespaces (:namespaces mcp-bundle)
            collisions (set/intersection (set (concat (keys profile-namespaces)
                                                      (conj (set (keys llm/core-namespaces)) 'skills)))
                                         (set (keys mcp-namespaces)))
            _ (when (seq collisions)
                (throw (ex-info (str "MCP server aliases collide with configured namespaces: "
                                     (sort collisions))
                                {:type :mcp-namespace-collision :namespaces (sort collisions)})))
            runtime-namespaces (merge profile-namespaces mcp-namespaces)
            _ (validate-pattern-dependencies! runtime-namespaces)
            raw-workers (get agent-spec :workers ::not-set)
            workers (normalize-workers-config raw-workers base-dir)
            workers-ns (when (seq workers)
                         (resolve-workers workers llm/compile-agent compile-agent-fn
                                          model resolved-provider base-dir resolved-overrides))
            _ (reset! workers-ns-ref workers-ns)
            all-namespaces (cond-> runtime-namespaces
                             workers-ns (assoc 'workers workers-ns))
            worker-close-fns (keep (comp :spell/close! meta val) workers-ns)
            compiled (compile-runtime-agent-from-resolved-spec agent-spec llm/compile-agent
                                                               {:model model
                                                                :provider resolved-provider
                                                               :system resolved-system
                                                               :namespaces all-namespaces})]
        (attach-close compiled (concat [(:close! mcp-bundle)]
                                       worker-close-fns
                                       (namespace-close-fns resolved-namespaces)
                                       (namespace-close-fns resolved-overrides))))
      (catch Throwable e
        (when-let [close! (:close! @mcp-bundle-ref)] (close!))
        (doseq [close! (keep (comp :spell/close! meta val) @workers-ns-ref)]
          (close!))
        (close-owned-values! (vals (or @resolved-overrides-ref {})))
        (close-owned-values! (vals (or @resolved-namespaces-ref {})))
        (throw e)))))
