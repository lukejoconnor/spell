(ns spell.patterns
  "Installable run-local Spell modules. Bundles are source data, not startup code."
  (:refer-clojure :exclude [update])
  (:require [clojure.java.io :as io]
            [spell.module-notices :as notices]
            [spell.module-journal :as journal]))

(def ^:private bundled-modules
  [:mailing-list :relay])

(def ^:private file-module-name #"[a-z0-9]+(?:-[a-z0-9]+)*")

(defn discovery-context
  "Snapshot canonical project and user roots once for a run. A .git file or directory
  marks the nearest worktree; outside Git only cwd is used. No cwd mutation."
  ([] (discovery-context (io/file (System/getProperty "user.dir"))
                         (some-> (or (System/getenv "HOME")
                                     (System/getProperty "user.home")) io/file)))
  ([cwd home]
   (let [cwd (.getCanonicalFile (io/file cwd))
         project (loop [dir cwd]
                   (cond (.exists (io/file dir ".git")) dir
                         (.getParentFile dir) (recur (.getParentFile dir))
                         :else cwd))]
     {:project-root (.getCanonicalPath project)
      :user-root (some-> home io/file .getCanonicalPath)})))

(defn- store []
  (let [s (or @(requiring-resolve 'spell.globals/*store*)
              (throw (ex-info "patterns requires a run-local globals store" {})))]
    ;; api/run seeds this before launching agents. Direct host users also get one
    ;; shared snapshot, rather than re-resolving roots for each caller.
    (when-not (contains? @s :module-discovery)
      (let [context (discovery-context)]
        (swap! s #(if (contains? % :module-discovery) %
                      (assoc % :module-discovery context)))))
    s))

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
(defn- filesystem-sources [kind root]
  (if-not root
    {}
    (let [dir (io/file root ".spell" "modules")
          path (.getCanonicalPath dir)]
      (if-not (.exists dir)
        {}
        (let [files (.listFiles dir)]
          (when-not files
            (throw (ex-info (str "Cannot list module directory: " path) {:path path :kind kind})))
          (:sources
            (reduce
              (fn [{:keys [sources paths]} file]
                (let [filename (.getName file)
                      path (.getCanonicalPath file)]
                  (if-not (.endsWith filename ".spl")
                    {:sources sources :paths paths}
                    (let [basename (subs filename 0 (- (count filename) 4))]
                      (when-not (re-matches file-module-name basename)
                        (throw (ex-info (str "Invalid module filename (expected lowercase kebab-case .spl): " file)
                                        {:path (str file) :canonical-path path :kind kind})))
                      (when-let [other (get paths path)]
                        (throw (ex-info (str "Duplicate module source identity: " file " and " other)
                                        {:path (str file) :other-path other :canonical-path path :kind kind})))
                      {:sources (assoc sources (keyword basename) {:kind kind :path path})
                       :paths (assoc paths path (str file))}))))
              {:sources {} :paths {}}
              (sort-by #(.getName %) files))))))))

(defn- selected-sources [snapshot]
  (let [{:keys [project-root user-root]} (:module-discovery snapshot)]
    (merge (zipmap bundled-modules
                   (map #(hash-map :kind :bundle :resource-path (str "modules/" (name %) ".spl"))
                        bundled-modules))
           (filesystem-sources :user user-root)
           (filesystem-sources :project project-root))))

(defn- load-selected [module-key selected]
  (let [origin (if (= :bundle (:kind selected))
                 (let [path (:resource-path selected)]
                   (if-let [url (io/resource path)]
                     {:kind :bundle :path (str url)}
                     (throw (ex-info (str "Module bundle not found: " path)
                                     {:module module-key :path path :origin selected}))))
                 selected)
        path (:path origin)]
    (try
      ;; Strict reading, unlike completion-parser recovery. Never evaluate source
      ;; or invoke application-installed tagged literal readers during discovery.
      (with-open [reader (java.io.PushbackReader.
                           (io/reader (if (= :bundle (:kind origin))
                                        (java.net.URL. path) path)))]
        (binding [*read-eval* false *data-readers* {} *default-data-reader-fn* nil]
          (let [eof (Object.)
                definition (read {:eof eof} reader)
                trailing (read {:eof eof} reader)]
            (when-not (and (map? definition) (identical? eof trailing))
              (throw (ex-info "Module source must contain one unquoted definition map" {})))
            {:definition (definition! module-key definition) :origin origin})))
      (catch Exception cause
        (throw (ex-info (str "Invalid module source " path ": " (.getMessage cause))
                        {:module module-key :path path :origin origin} cause))))))


(defn- function-keys [definition]
  (vec (sort (keys (:functions definition)))))

(defn- summary [module-key definition installed? owner]
  {:module module-key :installed? installed? :owner owner :doc (:doc definition)
   :functions (into (sorted-map)
                    (map (fn [[k entry]]
                           [k {:doc (:doc entry) :requires (:requires entry)
                               :params (second (:source entry))}]))
                    (:functions definition))})

(defn- caller! [module-key]
  (let [caller @(requiring-resolve 'spell.runtime/*current-handle*)
        registered? (and caller
                         @(requiring-resolve 'spell.coordinator/*coordinator*)
                         ((requiring-resolve 'spell.coordinator/agent) caller))]
    (when-not registered?
      (throw (ex-info (str "Module " module-key " requires a registered current agent; caller " (pr-str caller))
                      {:module module-key :caller caller :type :missing-module-editor})))
    caller))

(defn- commit-definition [state module-key definition owner install?]
  (let [sequence (inc (get state :module-sequence 0))
        revision (inc (get-in state [:module-installations module-key :revision] 0))]
    (cond-> (-> state
                (assoc-in [:modules module-key] definition)
                (assoc-in [:module-installations module-key]
                          (merge (get-in state [:module-installations module-key])
                                 {:owner owner :revision revision :sequence sequence}))
                (assoc :module-sequence sequence))
      install? (notices/enqueue owner {:kind :owner :module module-key :revision revision}))))

(defn- receipt [state module-key caller explicit?]
  (merge {:module module-key :fns (function-keys (get-in state [:modules module-key]))
          :editor caller :explicit-owner? explicit?}
         (get-in state [:module-installations module-key])))
(defn- install-definition [module-key definition origin]
  (let [caller (caller! module-key)
        [before after]
        (swap-vals! (store)
          (fn [state]
            (caller! module-key)
            (if (contains? (:modules state) module-key)
              state
              (cond-> (commit-definition state module-key
                                        (definition! module-key definition) caller true)
                origin (assoc-in [:module-installations module-key :origin] origin)))))
        installed? (not (contains? (:modules before) module-key))
        result (assoc (receipt after module-key caller false) :installed? installed?)]
    (if installed?
      (journal/record-change result :install nil (get-in after [:modules module-key]))
      result)))

(defn install
  "Install selected source if absent. The actual winning installer is the immutable owner."
  ([module-key]
   (module-key! module-key)
   (caller! module-key)
   (let [snapshot @(store)]
     (if (contains? (:modules snapshot) module-key)
       (install-definition module-key nil nil)
       (if-let [selected (get (selected-sources snapshot) module-key)]
         (let [{:keys [definition origin]} (load-selected module-key selected)]
           (install-definition module-key definition origin))
         (throw (ex-info "Unknown module; supply a custom definition to install"
                         {:module module-key}))))))
  ([module-key definition]
   (module-key! module-key)
   (install-definition module-key definition nil)))

(defn- installed-summary [snapshot module-key]
  (assoc (summary module-key (get-in snapshot [:modules module-key]) true
                  (get-in snapshot [:module-installations module-key :owner]))
         :origin (get-in snapshot [:module-installations module-key :origin])))

(defn- selected-summary [module-key selected]
  (let [{:keys [definition origin]} (load-selected module-key selected)]
    (assoc (summary module-key definition false nil) :origin origin)))

(defn catalog
  "Compact body-free metadata for selected files/bundles and installed custom modules."
  ([]
   (let [snapshot @(store) installed (:modules snapshot)
         selected (selected-sources snapshot)]
     (mapv (fn [k]
             (if (contains? installed k)
               (installed-summary snapshot k)
               (selected-summary k (get selected k))))
           (sort (into (set (keys selected)) (keys installed))))))
  ([module-key]
   (module-key! module-key)
   (let [snapshot @(store)]
     (if (contains? (:modules snapshot) module-key)
       (installed-summary snapshot module-key)
       (when-let [selected (get (selected-sources snapshot) module-key)]
         (selected-summary module-key selected))))))

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

(defn- update-arguments! [module-key transform-or-options args]
  ;; Evaluated Spell functions are maps too, not options maps.
  (if (and (map? transform-or-options) (not (:spell/fn transform-or-options)))
    (do
      (when-not (and (= #{:owner} (set (keys transform-or-options)))
                     (keyword? (:owner transform-or-options)) (seq args))
        (throw (ex-info "patterns/update options must be exactly {:owner keyword}, followed by an evaluated transform"
                        {:module module-key :options transform-or-options})))
      [true (:owner transform-or-options) (first args) (rest args)])
    [false nil transform-or-options args]))

(defn update
  "Owner-checked whole-definition transform; install first. Transforms may retry."
  [module-key transform-or-options & args]
  (module-key! module-key)
  (let [caller (caller! module-key)
        [explicit? expected transform args] (update-arguments! module-key transform-or-options args)
        env @(requiring-resolve 'spell.eval/*spell-env*)
        [before after]
        (swap-vals! (store)
          (fn [state]
            (caller! module-key)
            (when-not (contains? (:modules state) module-key)
              (throw (ex-info (str "Module " module-key " is not installed; call patterns/install first (update cannot create modules)")
                              {:module module-key :caller caller :owner nil})))
            (let [owner (get-in state [:module-installations module-key :owner])
                  expected-owner (if explicit? expected caller)]
              (when-not (and owner (= owner expected-owner))
                (throw (ex-info
                         (str "Module " module-key " owner " (pr-str owner) ", caller " (pr-str caller)
                              ": owner mismatch. Message the owner to request the edit, or deliberately name the recorded owner with "
                              "(patterns/update " module-key " {:owner " (pr-str owner) "} transform & args); naming the owner is acknowledgment, not approval.")
                         {:module module-key :owner owner :caller caller :expected-owner expected-owner})))
              (when-not (or (fn? transform) (and (map? transform) (:spell/fn transform)))
                (throw (ex-info "patterns/update requires an already evaluated transform function"
                                {:module module-key})))
              (let [old-definition (get-in state [:modules module-key])
                    next-definition (definition! module-key
                                      (apply-value transform (cons old-definition args) env))]
                (if (= old-definition next-definition)
                  state
                  (commit-definition state module-key next-definition owner false))))))
        result (receipt after module-key caller explicit?)
        old-definition (get-in before [:modules module-key])
        next-definition (get-in after [:modules module-key])]
    (if (= old-definition next-definition)
      result
      (journal/record-change result :update old-definition next-definition))))

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

  (patterns/install module)             — install selected project/user/bundled source only if absent
  (patterns/install module definition)  — install custom source only if absent
  (patterns/catalog)                    — compact project/user/bundled/custom discovery
  (patterns/catalog module)             — compact metadata or nil
  (patterns/source module)              — complete installed definition or nil
  (patterns/source module function)     — complete installed entry or nil
  (patterns/update module transform & args) — pure transform -> next definition
  (patterns/call module function & args) — execute the selected installed source

Definition: {:doc string :functions {:key {:doc string :requires [namespace-symbol ...]
                                         :source (fn [params] body...)}}}.
Pass definitions as quoted data; each :source is a single-arity fn form.
Registry: globals :modules, separate from module state. Install never resets edits or initializes state.
Owner/revision metadata is separate from definitions. Actual registered installer owns the module immutably.
Update requires install; default expected owner is the caller. A strict leading {:owner keyword} deliberately
acknowledges the recorded owner, not approval; message the owner to coordinate. Missing identity is rejected.
Pass update an already evaluated fn or builtin value, not quoted fn source.
Winning installers receive bounded next-generation guidance without mailbox receipt or extra model calls.
Dogfood automatically appends exact module changes at the feedback destination; ordinary calls and direct globals writes are unlogged.
Post-commit recording failure returns EDIT COMMITTED / RECORDING FAILED in :journal: the edit is live; do not replay.
Transforms may retry; purity is the programmer's contract, not an enforced effect barrier.
Requirements precheck namespace availability, not function completeness, and never grant capabilities.
Calls retain caller dynamic scope and normal arity/recur.
Nested calls see latest definitions; an in-flight call keeps its selected body.

Bundles: :relay exports :run; :mailing-list provides board operations (see catalog).
File discovery: <run project root>/.spell/modules > HOME/.spell/modules > bundled classpath.
The canonical project root is the nearest ancestor with a .git file/directory, or cwd outside Git;
project and user roots are captured once per run and shared by children. Missing directories are normal.
Each flat lowercase-kebab filename (e.g. my-module.spl) names an unqualified module keyword.
A file contains exactly one unquoted definition map. Reading is inert; invalid selected sources fail with paths.
Invalid filenames and duplicate canonical source aliases within a layer fail rather than silently choosing.
Catalog exposes :origin {:kind :project|:user|:bundle :path canonical-file-path-or-resource-URL}, not bodies.
Installed snapshots retain their origin across updates/reinstalls; explicit programmatic definitions have nil origin.
Programmatic module identities remain arbitrary keywords. Ordinary io writes can save definitions explicitly;
start a fresh run to reload saved files. No auto writeback; reinstall never reloads or overwrites installed source.
Install :mailing-list, then explicitly call :init once; installation alone does not create a board.
Context discipline: avoid pruning evidence and then rediscovering it. Before prune/!peek removes results,
retain exact needed source snippets, actual effect receipts, source locations, and a literal checkpoint.
A plan or sent flag is not execution evidence. Reuse explicitly retained evidence before repeating effects.
Carry this evidence through compaction; report unavailable evidence instead of claiming inspection.
No former pattern wrappers or clean-prompt remain. Quote effect calls in the trailing expression."
    :install "(patterns/install module) or (patterns/install module definition). Atomic if-absent insertion by a registered agent; the winning actual installer is the immutable owner. Returns {:module :installed? :fns :owner :editor :explicit-owner? :revision :sequence :origin}; repeat/concurrent install preserves owner, edits and state. Winning installers get bounded guidance in their next model generation, even if the return is discarded. Delegate installation to choose another owner; no owner option or transfer."
    :catalog "(patterns/catalog) or (patterns/catalog module). Summaries contain :module, :installed?, :owner (nil before install), :origin, :doc, and :functions entries with :doc/:params/:requires only. Discovers selected project > user > bundled sources plus installed custom modules without source bodies. Origin is {:kind :project|:user|:bundle :path canonical-file-path-or-resource-URL}, nil for explicit custom definitions. Installed snapshots win over files; invalid selected files fail with their path. Unknown module returns nil."
    :source "(patterns/source module) returns the complete installed definition; (patterns/source module function) returns the complete entry, including :doc/:requires/:source. Missing values return nil. This is actual executable source data, not documentation text."
    :update "(patterns/update module transform & args) or (patterns/update module {:owner recorded-owner} transform & args). Install first. Default expected owner is the registered actual caller; mismatch rejects before transform. The optional second argument must be exactly {:owner keyword}; explicitly naming the recorded owner acknowledges a deliberate edit, not approval. Message the owner to coordinate. Pass an already evaluated fn or builtin, not quoted source. The pure retryable transform receives the current whole definition and args; validation precedes atomic commit. Returns {:module :fns :owner :editor :explicit-owner? :revision :sequence} from that exact commit; unchanged definitions keep revision/sequence. Dogfood records successful changes automatically; :journal is {:status :ok :sequence s} or {:status :failed :message \"EDIT COMMITTED / RECORDING FAILED\" ...}. A recording failure leaves the edit live: do not replay. Off mode omits :journal."
    :call "(patterns/call module function & args). Resolves an installed entry once, prechecks namespace availability, evaluates its single-arity fn source, and uses ordinary evaluator application with caller dynamic scope. Requirements neither grant capabilities nor guarantee that particular functions exist. Opaque arguments are not traversed. Nested calls resolve current registry; selected in-flight source remains unchanged."}
   :install install :catalog catalog :source source :update update :call call})
