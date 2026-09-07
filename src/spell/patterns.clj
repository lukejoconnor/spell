(ns spell.patterns
  "Installable run-local Spell modules. Bundles are source data, not startup code."
  (:refer-clojure :exclude [update])
  (:require [clojure.java.io :as io]
            [spell.parse :as parse]))

(def ^:private bundled-modules
  [:check-result :fix-loop :mailing-list :ralph :relay :team])

(defn- store []
  (or @(requiring-resolve 'spell.globals/*store*)
      (throw (ex-info "patterns requires a run-local globals store" {}))))

(defn- module-key! [module-key]
  (when-not (keyword? module-key)
    (throw (ex-info "Module name must be a keyword" {:module module-key})))
  module-key)

(defn- definition! [module-key definition]
  (when-not (and (map? definition) (string? (:doc definition))
                 (map? (:functions definition)))
    (throw (ex-info "Module definition requires :doc string and :functions map"
                    {:module module-key})))
  (doseq [[function-key entry] (:functions definition)]
    (when-not (and (keyword? function-key) (map? entry)
                   (string? (:doc entry)) (vector? (:requires entry))
                   (every? #(and (symbol? %) (nil? (namespace %))) (:requires entry))
                   (seq? (:source entry)) (= 'fn (first (:source entry)))
                   (vector? (second (:source entry))) (seq (nnext (:source entry))))
      (throw (ex-info "Module function requires a keyword, :doc string, :requires namespace-symbol vector, and :source (fn [params] body...)"
                      {:module module-key :function function-key}))))
  definition)

(defn- bundle-resource [module-key]
  (let [path (str "modules/" (name module-key) ".spl")]
    (or (io/resource path)
        (throw (ex-info "Module bundle not found" {:module module-key :path path})))))

(defn- bundled-definition [module-key]
  (when-not (some #{module-key} bundled-modules)
    (throw (ex-info "Unknown bundled module; supply a custom definition to install"
                    {:module module-key})))
  (let [forms (binding [*read-eval* false]
                (parse/read-all (slurp (bundle-resource module-key))))]
    (when-not (= 1 (count forms))
      (throw (ex-info "Module bundle must contain one unquoted definition map"
                      {:module module-key})))
    (definition! module-key (first forms))))

(defn- function-keys [definition]
  (vec (sort (keys (:functions definition)))))

(defn- summary [module-key definition installed?]
  {:module module-key :installed? installed? :doc (:doc definition)
   :functions (into (sorted-map)
                    (map (fn [[k entry]]
                           [k {:doc (:doc entry) :requires (:requires entry)
                               :params (second (:source entry))}]))
                    (:functions definition))})

(defn install
  "Install a bundle or custom definition only if absent; never initialize state."
  ([module-key]
   (module-key! module-key)
   (if-let [definition (get-in @(store) [:modules module-key])]
     {:module module-key :installed? false :fns (function-keys definition)}
     (install module-key (bundled-definition module-key))))
  ([module-key definition]
   (module-key! module-key)
   (let [[before after]
         (swap-vals! (store)
                     (fn [state]
                       (if (contains? (:modules state) module-key)
                         state
                         (assoc-in state [:modules module-key]
                                   (definition! module-key definition)))))]
     {:module module-key
      :installed? (not (contains? (:modules before) module-key))
      :fns (function-keys (get-in after [:modules module-key]))})))

(defn catalog
  "Compact body-free metadata for bundled and installed custom modules."
  ([]
   (let [installed (:modules @(store))]
     (mapv (fn [k]
             (if (contains? installed k)
               (summary k (get installed k) true)
               (summary k (bundled-definition k) false)))
           (sort (into (set bundled-modules) (keys installed))))))
  ([module-key]
   (module-key! module-key)
   (let [installed (:modules @(store))]
     (cond
       (contains? installed module-key) (summary module-key (get installed module-key) true)
       (some #{module-key} bundled-modules) (summary module-key (bundled-definition module-key) false)
       :else nil))))

(defn source
  "Return the complete installed definition or entry, including executable source."
  ([module-key]
   (module-key! module-key)
   (get-in @(store) [:modules module-key]))
  ([module-key function-key]
   (get-in (source module-key) [:functions function-key])))

(defn- evaluated! [expression env]
  (let [result ((requiring-resolve 'spell.eval/spell-eval) expression env)]
    (if (contains? result :ok)
      (:ok result)
      (throw (ex-info (:err result)
                      (cond-> {:result result}
                        (contains? result :thrown) (assoc :spell/thrown (:thrown result))))))))

(defn- apply-value [f args env]
  ;; Only traverse the finite argument list, never the argument values. Symbols
  ;; resolve opaque/lazy values directly instead of evaluating or quoting them.
  (let [f-name (gensym "module-fn-")
        arg-names (mapv (fn [_] (gensym "module-arg-")) args)
        local-env (into (assoc env f-name f) (map vector arg-names args))]
    (evaluated! (cons f-name arg-names) local-env)))

(defn update
  "Apply a pure, retryable transform to current-or-nil and commit its next definition."
  [module-key transform & args]
  (module-key! module-key)
  (let [env @(requiring-resolve 'spell.eval/*spell-env*)
        committed (swap! (store)
                         (fn [state]
                           (let [next-definition
                                 (apply-value transform
                                              (cons (get-in state [:modules module-key]) args)
                                              env)]
                             (assoc-in state [:modules module-key]
                                       (definition! module-key next-definition)))))]
    {:module module-key :fns (function-keys (get-in committed [:modules module-key]))}))

(defn call
  "Select an installed entry once; nested calls resolve the latest registry snapshot."
  [module-key function-key & args]
  (module-key! module-key)
  (let [entry (get-in @(store) [:modules module-key :functions function-key])
        env @(requiring-resolve 'spell.eval/*spell-env*)]
    (when-not entry
      (throw (ex-info "Module function is not installed"
                      {:module module-key :function function-key})))
    ;; Requirements describe capabilities, not grants. Resolve through the normal
    ;; caller environment so unavailable effect namespaces remain unavailable.
    (doseq [required (:requires entry)]
      (let [result ((requiring-resolve 'spell.eval/spell-eval) required env)]
        (when-not (and (contains? result :ok) (map? (:ok result)))
          (throw (ex-info "Module function requires an unavailable namespace"
                          {:module module-key :function function-key :missing required})))))
    (apply-value (evaluated! (:source entry) env) args env)))

(def patterns
  "The five public effect verbs for installable modules."
  {:short-docs "Installable run-local modules: install, catalog, source, update, call."
   :docs
   {:guide "PATTERNS — Installable modules (effect namespace).

  (patterns/install module)             — install bundled source only if absent
  (patterns/install module definition)  — install custom source only if absent
  (patterns/catalog)                    — compact bundled/custom discovery
  (patterns/catalog module)             — compact metadata or nil
  (patterns/source module)              — complete installed definition or nil
  (patterns/source module function)     — complete installed entry or nil
  (patterns/update module transform & args) — pure transform -> next definition
  (patterns/call module function & args) — execute the selected installed source

Definition: {:doc string :functions {:key {:doc string :requires [namespace-symbol ...]
                                         :source (fn [params] body...)}}}.
Pass definitions as quoted data; each :source is a single-arity fn form.
Registry: globals :modules, separate from module state. Install never resets edits or initializes state.
Pass update an already evaluated fn or builtin value, not quoted fn source.
Transforms may retry; purity is the programmer's contract, not an enforced effect barrier.
Requirements precheck namespace availability, not function completeness, and never grant capabilities.
Calls retain caller dynamic scope and normal arity/recur.
Nested calls see latest definitions; an in-flight call keeps its selected body.

Bundles: :check-result, :ralph, :team, :fix-loop, :relay export :run.
:mailing-list exports :init, :call, :change, :digest, :deliver.
Install :mailing-list, then explicitly call :init once; installation alone does not create a board.
Context discipline: avoid pruning evidence and then rediscovering it. Before prune/!peek removes results,
retain exact needed source snippets, actual effect receipts, stored IDs/offsets, and a literal checkpoint.
A plan or sent flag is not execution evidence. Retrieve retained/stored output rather than repeating effects.
Carry this evidence through compaction; report unavailable evidence instead of claiming inspection.
No former pattern wrappers or clean-prompt remain. Quote effect calls in the trailing expression."
    :install "(patterns/install module) or (patterns/install module definition). Atomic if-absent insertion. Returns {:module k :installed? boolean :fns sorted-vector}; true only for the winning insertion. Leaves all state and existing edits untouched."
    :catalog "(patterns/catalog) or (patterns/catalog module). Summaries contain :module, :installed?, :doc, and :functions entries with :doc/:params/:requires only. Discovers uninstalled bundles and installed custom modules without source bodies. Unknown module returns nil."
    :source "(patterns/source module) returns the complete installed definition; (patterns/source module function) returns the complete entry, including :doc/:requires/:source. Missing values return nil. This is actual executable source data, not documentation text."
    :update "(patterns/update module transform & args). Pass an already evaluated fn or builtin value, not quoted fn source. The conventional transform of current-or-nil and args returns the whole next definition. Validation precedes atomic commit; transforms may retry. Purity is the programmer's contract; effects are not blocked inside transforms. Returns {:module k :fns sorted-vector} from that exact commit, never a later registry read."
    :call "(patterns/call module function & args). Resolves an installed entry once, prechecks namespace availability, evaluates its single-arity fn source, and uses ordinary evaluator application with caller dynamic scope. Requirements neither grant capabilities nor guarantee that particular functions exist. Opaque arguments are not traversed. Nested calls resolve current registry; selected in-flight source remains unchanged."}
   :install install :catalog catalog :source source :update update :call call})
