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
   - :hooks: concatenated (parent first, then child)

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
            [spell.prompt :as prompt]
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
   'builtins prompt/builtins-namespace
   'strings stdlib/strings
   'math stdlib/math
   'patterns stdlib/patterns})

(def default-agent-def
  "Built-in default agent definition (equivalent to agents/with-io-minimal.agent.edn).
   Available as :base spell:default or via load-default-agent-config."
  {:name 'default
   :doc "Default agent with standard library and I/O"
   :namespaces {'io 'stdlib/io
                'globals 'stdlib/globals
                'agents 'stdlib/agents
                'futures 'stdlib/futures
                'builtins 'stdlib/builtins
                'strings 'stdlib/strings
                'math 'stdlib/math
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
   - Scalars: child wins if present
   - :namespaces: merge maps (child overrides parent entries)
   - :hooks: concatenate (parent first, then child)"
  [parent child]
  (let [;; Start with parent, override with non-nil child scalars
        merged (reduce (fn [m k]
                         (if (contains? child k)
                           (assoc m k (get child k))
                           m))
                       parent
                       [:name :doc :system :model :budget :recover :eval :format :max-retries :retries :thinking])
        ;; Merge namespaces
        merged (if (or (:namespaces parent) (:namespaces child))
                 (assoc merged :namespaces
                        (merge (:namespaces parent) (:namespaces child)))
                 merged)
        ;; Concatenate hooks
        merged (if (or (:hooks parent) (:hooks child))
                 (assoc merged :hooks
                        (vec (concat (:hooks parent) (:hooks child))))
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
         {:keys [name doc system model budget recover namespaces hooks]} agent-def

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
    :max-retries ...   ; optional retry count for format validation
    :hooks [...]}      ; quoted hook expressions"
  [path]
  (let [file (java.io.File. path)
        base-dir (.getParent file)

        ;; Parse and resolve inheritance
        raw-def (read-agent-edn path nil)
        agent-def (resolve-inheritance raw-def base-dir)

        {:keys [name doc system model budget recover namespaces hooks eval format max-retries retries thinking]} agent-def

        ;; We need make-llm to resolve sub-agents, but we don't have it yet.
        ;; For now, return a thunk that resolves namespaces when called with make-llm-fn.
        resolve-fn (fn [make-llm-fn]
                     (resolve-namespaces namespaces base-dir make-llm-fn))]

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
     :resolve-namespaces-fn resolve-fn
     :hooks hooks}))

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
     :resolve-namespaces-fn resolve-fn
     :hooks nil}))
