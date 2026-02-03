(ns spell.registry
  "Registry abstraction for tools, agents, and Spell library functions.

   Registries unify three item types under one import mechanism:
   - :tool   — Clojure functions (bash, read-file, etc.)
   - :agent  — LLM functions (leaf-llm, etc.)
   - :spell  — Spell source forms (library functions)")

(defn registry?
  "True if x is a registry map."
  [x]
  (and (map? x)
       (contains? x :name)
       (contains? x :desc)
       (contains? x :items)))

(defn lookup
  "Look up an item in registry by keyword. Returns nil if not found."
  [reg key]
  (get-in reg [:items key]))

(defn describe
  "Get descriptions from registry.
   (describe reg) — all descriptions
   (describe reg key) — description for key, or sub-registry descriptions"
  ([reg]
   (:desc reg))
  ([reg key]
   (let [item (lookup reg key)]
     (if (registry? item)
       (:desc item)
       (get (:desc reg) key)))))

(defn import-item
  "Import an item from registry, returning [value name-symbol].
   For :tool/:agent, returns the :fn.
   For :spell, evaluates the :form and returns the result.
   eval-fn is (fn [form env] [value env']) for evaluating spell forms."
  [reg key eval-fn env]
  (let [item (lookup reg key)]
    (when (nil? item)
      (throw (ex-info "import: key not found in registry"
                      {:registry (:name reg)
                       :key key
                       :available (keys (:desc reg))})))
    (let [name-sym (symbol (name key))]
      (case (:type item)
        :tool [(:fn item) name-sym]
        :agent [(:fn item) name-sym]
        :spell (let [[val _] (eval-fn (:form item) env)]
                 [val name-sym])
        (throw (ex-info "import: unknown item type"
                        {:key key :type (:type item)}))))))

(defn- resolve-import-verbose-form
  "Resolve a single import-verbose form if it matches, otherwise return unchanged.
   reg-map is {name-symbol -> registry}."
  [form reg-map]
  (if (and (seq? form)
           (= 'import-verbose (first form))
           (>= (count form) 3))
    (let [reg-sym (second form)
          key (nth form 2)
          reg (get reg-map reg-sym)
          item (when reg (lookup reg key))
          name-sym (symbol (name key))]
      (if (and reg item)
        (case (:type item)
          :tool (list 'def name-sym (list 'get-in reg-sym [:items key :fn]))
          :agent (list 'def name-sym (list 'get-in reg-sym [:items key :fn]))
          :spell (list 'def name-sym (:form item))
          form)
        form))
    ;; Recurse into do blocks
    (if (and (seq? form) (= 'do (first form)))
      (list* 'do (map #(resolve-import-verbose-form % reg-map) (rest form)))
      form)))

(defn resolve-import-verbose
  "Pre-process forms, replacing (import-verbose reg :key) with (def name form).
   Operates on top-level forms and recurses into do blocks.
   reg-map is {name-symbol -> registry}."
  [forms reg-map]
  (mapv #(resolve-import-verbose-form % reg-map) forms))
