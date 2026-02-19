(ns spell.agent
  "Agent definition file loader.

   Loads .agent.edn files and resolves them into make-llm configs.

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
   - file.agent.edn     → load agent definition → llm fn
   - {:file f :items m} → submap of vars from file
   - {:file f}          → slurp file as string"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [spell.comm :as comm]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.stdlib :as stdlib]
            [spell.io :as io]))

;; =============================================================================
;; Stdlib resolution
;; =============================================================================

(def stdlib-namespaces
  "All stdlib namespaces available via stdlib/ prefix.
   Note: io/ is opt-in (not in default agent) for safety.
   Seqs, fns, and bit- ops are in core-builtins (matching Clojure)."
  {'io io/io-namespace
   'globals globals/globals-namespace
   'agents comm/agents-namespace
   'futures comm/futures-namespace
   'builtins stdlib/builtins-namespace
   'strings stdlib/strings
   'math stdlib/math
   'patterns stdlib/patterns})

(def default-agent-def
  "Built-in default agent definition.
   Core namespaces (strings, math, builtins) are always available via make-llm.
   Only effect namespaces need to be listed here."
  {:name 'default
   :doc "Default agent with standard tools"
   :namespaces {'io 'stdlib/io
                'globals 'stdlib/globals
                'agents 'stdlib/agents
                'futures 'stdlib/futures
                'patterns 'stdlib/patterns}})

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
;; Forward declaration for recursive agent loading
;; =============================================================================

(declare load-agent)

;; =============================================================================
;; Value resolution
;; =============================================================================

(defn- resolve-namespace-value
  "Resolve a single namespace value according to pattern rules.

   Patterns:
   - stdlib/X or stdlib/X/Y  → stdlib namespace/item
   - file.clj/var            → var from Clojure file
   - file.agent.edn          → load agent → llm fn
   - {:file f :items {...}}  → submap of vars from file
   - {:file f}               → file content as string"
  [value base-dir clj-cache make-llm-fn]
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
        (load-agent s base-dir make-llm-fn)

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
  [namespaces base-dir make-llm-fn]
  (let [clj-cache (atom {})]
    (into {}
          (map (fn [[k v]]
                 [(symbol k) (resolve-namespace-value v base-dir clj-cache make-llm-fn)])
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
                       [:name :doc :system :model :budget :recover :eval :format :max-retries :retries
                        :thinking :reasoning-effort :verbosity :api :llms])
        ;; Merge namespaces
        merged (if (or (:namespaces parent) (:namespaces child))
                 (assoc merged :namespaces
                        (merge (:namespaces parent) (:namespaces child)))
                 merged)]
    merged))

(defn- resolve-inheritance
  "Resolve :base inheritance chain, returning fully merged agent def.
   Removes :base from result.

   Special base paths:
   - spell:default → built-in default agent"
  [agent-def base-dir]
  (if-let [base-path (:base agent-def)]
    (let [base-path-str (str base-path)]
      (if (= base-path-str "spell:default")
        ;; Use built-in default
        (dissoc (merge-agent-defs default-agent-def agent-def) :base)
        ;; Load from file
        (let [base-file (java.io.File. (if (str/starts-with? base-path-str "/")
                                         base-path-str
                                         (str base-dir "/" base-path-str)))
              base-dir' (.getParent base-file)
              base-def (read-agent-edn base-path-str base-dir)
              resolved-base (resolve-inheritance base-def base-dir')]
          ;; Merge child onto resolved base, remove :base key
          (dissoc (merge-agent-defs resolved-base agent-def) :base))))
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
  "Resolve an llm spec value into an agent def map.
   - symbol ending in .agent.edn → load agent file, return its def
   - map → treat as inline spec (mini agent def)"
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
      (resolve-inheritance raw-def file-base-dir))

    ;; Inline map spec
    (map? value)
    value

    :else
    (throw (ex-info (str "Invalid llm spec: " value ". Expected .agent.edn symbol or inline map.")
                    {:value value}))))

(defn- build-llm-from-spec
  "Build a callable LLM function from a resolved agent spec.
   make-llm-fn: factory for eval=true agents
   model: parent model (inherited when spec omits :model)
   extra-namespaces: additional namespaces to merge (e.g. shared llms/ namespace)"
  [spec make-llm-fn model extra-namespaces base-dir]
  (let [eval? (if (some? (:eval spec)) (:eval spec) true)
        spec-model (or (:model spec) model)
        system (resolve-system-prompt (:system spec) base-dir)
        ;; Resolve spec's own namespaces if present
        spec-namespaces (when (and eval? (:namespaces spec))
                          (resolve-namespaces (:namespaces spec) base-dir make-llm-fn))
        ;; Merge extra namespaces (e.g. llms/) with spec's own
        all-namespaces (merge extra-namespaces spec-namespaces)
        ;; Build the LLM function
        base-llm (if eval?
                   (make-llm-fn (cond-> {}
                                  (seq all-namespaces) (assoc :namespaces all-namespaces)
                                  spec-model (assoc :model spec-model)
                                  system (assoc :system system)
                                  (some? (:recover spec)) (assoc :recover (:recover spec))
                                  (:format spec) (assoc :format (:format spec))
                                  (some? (:thinking spec)) (assoc :thinking (:thinking spec))
                                  (:reasoning-effort spec) (assoc :reasoning-effort (:reasoning-effort spec))
                                  (:verbosity spec) (assoc :verbosity (:verbosity spec))))
                   (llm/make-leaf-llm (cond-> {}
                                        system (assoc :system system)
                                        spec-model (assoc :model spec-model))))]
    ;; Wrap with format validation if specified
    (if (:format spec)
      (llm/wrap-with-format base-llm {:format (:format spec)
                                       :eval? eval?
                                       :max-retries (or (:max-retries spec) 3)})
      base-llm)))

(defn resolve-llms
  "Resolve :llms map into an effect namespace.
   Uses atom-based lazy init for circular references (A can call B, B can call A).
   Returns namespace map with :docs and callable functions, or nil if no llms."
  [llms-map make-llm-fn model base-dir]
  (when (seq llms-map)
    ;; Phase 1: create atoms and proxy namespace
    (let [fn-atoms (into {} (map (fn [[k _]] [k (atom nil)]) llms-map))
          docs (into {} (map (fn [[k v]]
                               (let [spec (resolve-llm-spec v base-dir)
                                     doc (or (:doc spec) (str "Sub-agent: " (name k)))]
                                 [(keyword k) doc]))
                             llms-map))
          llms-ns (merge {:docs docs}
                         (into {} (map (fn [[k _]]
                                         [(keyword k)
                                          (fn [& args] (apply @(get fn-atoms k) args))])
                                       llms-map)))]
      ;; Phase 2: resolve each spec and fill atoms
      (doseq [[k v] llms-map]
        (let [spec (resolve-llm-spec v base-dir)
              llm-fn (build-llm-from-spec spec make-llm-fn model {'llms llms-ns} base-dir)]
          (reset! (get fn-atoms k) llm-fn)))
      llms-ns)))

;; =============================================================================
;; Main loader
;; =============================================================================

(defn load-agent
  "Load an agent definition from a .agent.edn file.
   Returns an llm function created via make-llm.

   Supports inheritance via :base - child agents can extend parent agents.
   The make-llm-fn parameter allows injection of the actual make-llm
   to avoid circular dependencies."
  ([path] (load-agent path nil nil))
  ([path base-dir] (load-agent path base-dir nil))
  ([path base-dir make-llm-fn]
   (let [;; Determine base directory
         full-path (if (and base-dir (not (str/starts-with? path "/")))
                     (str base-dir "/" path)
                     path)
         file (java.io.File. full-path)
         base-dir' (.getParent file)

         ;; Parse EDN and resolve inheritance
         raw-def (read-agent-edn full-path nil)
         agent-def (resolve-inheritance raw-def base-dir')

         ;; Extract fields (from merged def)
         {:keys [name doc system model budget recover namespaces]} agent-def

         ;; Resolve system prompt
         resolved-system (resolve-system-prompt system base-dir')

         ;; Resolve namespaces
         resolved-namespaces (when namespaces
                               (resolve-namespaces namespaces base-dir' make-llm-fn))

         ;; Build make-llm config
         config (cond-> {}
                  resolved-namespaces (assoc :namespaces resolved-namespaces)
                  model (assoc :model model)
                  (some? recover) (assoc :recover recover))]

     ;; Create the llm function
     (if make-llm-fn
       (make-llm-fn config)
       ;; Return config if no make-llm-fn provided (for testing)
       {:agent-def agent-def
        :config config
        :system resolved-system}))))

(defn load-agent-config
  "Load an agent definition and return a config map suitable for make-llm.
   Does not create the llm function - caller must pass to make-llm.

   Supports inheritance via :base - child agents can extend parent agents.

   Returns:
   {:namespaces {...}
    :model \"...\"
    :recover ...
    :system \"...\"    ; resolved system prompt (nil if not specified)
    :budget ...
    :eval ...          ; true (default) = Spell evaluation, false = plain text
    :format ...        ; optional format spec {:required [...] :optional [...]}
    :max-retries ...   ; optional retry count for format validation}"
  [path]
  (let [file (java.io.File. path)
        base-dir (.getParent file)

        ;; Parse and resolve inheritance
        raw-def (read-agent-edn path nil)
        agent-def (resolve-inheritance raw-def base-dir)

        {:keys [name doc system model budget recover namespaces eval format max-retries retries
                thinking reasoning-effort verbosity api]} agent-def

        ;; Normalize :llms — use ::not-set sentinel to distinguish absent from nil
        raw-llms (get agent-def :llms ::not-set)
        llms (normalize-llms-config raw-llms base-dir)

        ;; We need make-llm to resolve sub-agents, but we don't have it yet.
        ;; For now, return a thunk that resolves namespaces when called with make-llm-fn.
        resolve-fn (fn [make-llm-fn]
                     (resolve-namespaces namespaces base-dir make-llm-fn))
        resolve-llms-fn' (when (seq llms)
                           (fn [make-llm-fn parent-model]
                             (resolve-llms llms make-llm-fn parent-model base-dir)))]

    {:name name
     :doc doc
     :system (resolve-system-prompt system base-dir)
     :model model
     :budget budget
     :recover recover
     :eval eval           ; nil means default (true)
     :format format
     :max-retries max-retries
     :retries retries     ; API retry sleep durations, e.g. [0 10]
     :thinking thinking
     :reasoning-effort reasoning-effort  ; OpenAI reasoning effort ("low", "medium", "high")
     :verbosity verbosity               ; OpenAI verbosity ("low", "auto")
     :api api                           ; :responses or :chat (default: auto-detect)
     :resolve-namespaces-fn resolve-fn
     :resolve-llms-fn resolve-llms-fn'}))

(defn- try-slurp
  "Slurp file, returning nil if not found."
  [path]
  (try (slurp path) (catch Exception _ nil)))

(defn default-agent-config
  "Return config for the built-in default agent.
   Used by CLI when no -a flag is specified."
  []
  (let [agent-def default-agent-def
        {:keys [name doc namespaces format max-retries]} agent-def
        resolve-fn (fn [make-llm-fn]
                     (resolve-namespaces namespaces nil make-llm-fn))]
    {:name name
     :doc doc
     :system (try-slurp "prompts/minimal.txt")
     :model nil
     :budget nil
     :recover nil
     :eval nil           ; nil means default (true)
     :format format
     :max-retries max-retries
     :resolve-namespaces-fn resolve-fn}))
