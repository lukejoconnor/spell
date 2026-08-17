(ns spell.runtime
  "Agent runtime: box execution primitive, registry, message passing, spawn/ask.

   Single-drain model: box waits for a completion source and calls an inside-fn.
   Inbox drain + signal reset happen once per wake cycle in make-awake-fn
   (phase 3 entry). ask sends a message and blocks for reply. !spawn-ask spawns
   an agent and blocks for its message. send-msg-fn is low-level fire-and-forget.
   Every wait wakes its targets, and edge ordering prevents all-asleep cycles
   introduced by the communication topology."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.inbox :as inbox]
            [spell.parse :as parse]
            [spell.trace :as trace]))

;; =============================================================================
;; Registry
;; =============================================================================

(def registry
  "Global registry: handle -> {:state (atom {:inbox-macros [], :signal (promise)}),
                                :has-box (atom false),
                                :completed (atom (promise)),
                                :last-raw (atom nil),
                                :parent-handle kw-or-nil,
                                :epoch runtime-run-id}"
  (atom {}))

(declare wait-graph swap-current-graph! swap-vals-current-graph! inspection-graph)

(def runtime-epoch
  "Monotonic identity for the currently active runtime run. Public API runs
   advance this before replacing process-global runtime state so detached work
   from an earlier run can recognize that its lifecycle is stale."
  (atom 0))

(def ^:dynamic *runtime-epoch*
  "Epoch owned by the currently executing lifecycle. Nil outside a lifecycle."
  nil)

(defn- operation-epoch []
  (or *runtime-epoch* @runtime-epoch))

(defn- active-epoch?
  [epoch]
  (= epoch @runtime-epoch))

(defn- entry-for-epoch
  [handle epoch]
  (let [entry (get @registry handle)]
    (when (= epoch (:epoch entry))
      entry)))

(defn- require-active-epoch!
  [caller]
  (let [epoch (operation-epoch)]
    (when-not (active-epoch? epoch)
      (throw (ex-info (str caller ": lifecycle belongs to an earlier runtime run")
                      {:type :stale-runtime-epoch
                       :lifecycle-epoch epoch
                       :runtime-epoch @runtime-epoch})))
    epoch))

(defn register!
  "Register a handle in the registry.
   Optional parent-handle records the spawning agent."
  ([handle] (register! handle nil))
  ([handle parent-handle]
   (let [epoch (require-active-epoch! "register!")
         entry {:state         (atom {:inbox-macros [], :signal (promise)})
                :has-box       (atom false)
                :parent-handle parent-handle
                :completed     (atom (promise))
                :last-raw      (atom nil)
                :epoch         epoch}
         [old new] (swap-vals! registry
                               (fn [entries]
                                 (if (or (not (active-epoch? epoch))
                                         (contains? entries handle))
                                   entries
                                   (assoc entries handle entry))))]
     (when (identical? old new)
       (if (active-epoch? epoch)
         (throw (ex-info "Handle already registered" {:handle handle}))
         (throw (ex-info "register!: lifecycle belongs to an earlier runtime run"
                         {:type :stale-runtime-epoch
                          :lifecycle-epoch epoch
                          :runtime-epoch @runtime-epoch}))))
     ;; A registered handle is initially able to run. start-box changes truly
     ;; dormant handles to :finished after registration; spawned and root handles
     ;; remain :awake until their lifecycle returns.
     (swap-current-graph! epoch
                          #(assoc-in % [:nodes handle]
                                     {:status :awake :generation 1})))))

;; =============================================================================
;; Dynamic vars
;; =============================================================================

(def ^:dynamic *current-handle*
  "Handle for the currently executing agent (set inside box)."
  nil)

(def ^:dynamic *current-raw*
  "Raw completion string for the currently executing agent (set inside box)."
  nil)

(def ^:dynamic *current-eval-fn*
  "Eval function for the currently executing agent (set by make-awake-fn).
   Used by block-for-message and spawn to break circular dependencies."
  nil)

(def ^:dynamic *default-spawn-agent*
  "Default compiled agent function used by prompt-only spawn/spawn-ask forms.
   Bound by eval to the current agent."
  nil)

(defn- inbox-aware-eval-fn?
  [eval-fn]
  (true? (:spell/inbox-aware (meta eval-fn))))

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare box ask-builtin ask-one finish-agent! fill-slot!)

(defn- default-spawn-agent
  "Resolve default agent for prompt-only spawn/spawn-ask forms."
  [caller]
  (or *default-spawn-agent*
      (throw (ex-info (str caller ": no default agent available")
                      {:caller caller}))))

(defn compiled-agent?
  "Return true when value is a compiled spawn-agent function."
  [value]
  (and (fn? value)
       (true? (:spell/compiled-agent (meta value)))))

(defn- resolve-completion-source
  "Resolve completion source (promise/future/raw) to a raw value.
   Throws if the resolved value is a Throwable."
  [completion-source]
  (let [raw-or-ex (if (instance? clojure.lang.IDeref completion-source)
                    (deref completion-source)
                    completion-source)]
    (when (instance? Throwable raw-or-ex)
      (throw raw-or-ex))
    raw-or-ex))

(defn- completion-token
  "Wrap a completion source as a Spell await token."
  [completion-source]
  {:spell/future true
   :ref completion-source})

(defn- current-completion
  "Return handle's current completion promise, or throw a useful handle error."
  [handle]
  (let [epoch (operation-epoch)
        entry (entry-for-epoch handle epoch)]
    (when-not entry
      (throw (ex-info "Handle not registered in this runtime run"
                      {:handle handle :runtime-epoch epoch})))
    @(:completed entry)))

(defn- realize-completion!
  "Atomically rotate handle's completion promise, then deliver the old cycle.
   No-op if another lifecycle already rotated expected-completion."
  [handle expected-completion result]
  (when-let [completed-atom (:completed (entry-for-epoch handle (operation-epoch)))]
    (let [next-completed (promise)]
      (when (compare-and-set! completed-atom expected-completion next-completed)
        (deliver expected-completion result)
        true))))

(defn- retire-terminal-completion!
  "Deliver a prepared spawn's fallback result and retire a handle that never
   established a root lifecycle. Existing waiters retain expected-completion;
   no ownerless next lifecycle or addressable ghost handle is left behind."
  [handle expected-completion result]
  (let [epoch (operation-epoch)
        [old-registry new-registry]
        (swap-vals! registry
                    (fn [entries]
                      (let [entry (get entries handle)]
                        (if (and entry
                                 (= epoch (:epoch entry))
                                 (identical? expected-completion @(:completed entry)))
                          (dissoc entries handle)
                          entries))))]
    (when (and (contains? old-registry handle)
               (not (contains? new-registry handle)))
      (finish-agent! handle result)
      (swap-current-graph! epoch #(update % :nodes dissoc handle))
      (deliver expected-completion result)
      true)))

(declare throwable->completion-exception)

(def ^:private completion-failure-max-depth 8)
(def ^:private completion-failure-max-items 100)

(defn- reader-round-trippable?
  [value]
  (try
    (= [value] (vec (parse/read-all (pr-str value))))
    (catch Throwable _ false)))

(defn- continuation-safe-value
  "Convert host values to reader-safe plain data for completion messages."
  ([value]
   (continuation-safe-value value 0))
  ([value depth]
   (cond
     (or (nil? value)
         (string? value)
         (boolean? value)
         (number? value)
         (char? value))
     value

     (or (keyword? value) (symbol? value))
     (if (reader-round-trippable? value)
       value
       {:class (.getName (class value))
        :value (str value)})

     (>= depth completion-failure-max-depth)
     {:spell/truncated true
      :class (.getName (class value))}

     (instance? Throwable value)
     (throwable->completion-exception value (inc depth))

     (map? value)
     (into {}
           (map (fn [[k v]] [(continuation-safe-value k (inc depth))
                              (continuation-safe-value v (inc depth))]))
           (take completion-failure-max-items value))

     (vector? value)
     (mapv #(continuation-safe-value % (inc depth))
           (take completion-failure-max-items value))

     (set? value)
     (set (map #(continuation-safe-value % (inc depth))
               (take completion-failure-max-items value)))

     (list? value)
     (apply list (map #(continuation-safe-value % (inc depth))
                      (take completion-failure-max-items value)))

     (sequential? value)
     (mapv #(continuation-safe-value % (inc depth))
           (take completion-failure-max-items value))

     :else
     {:class (.getName (class value))
      :value (try
               (str value)
               (catch Throwable _ "<unprintable>"))})))

(defn- throwable->completion-exception
  "Convert a host Throwable to Spell exception data safe for continuations."
  ([^Throwable throwable]
   (throwable->completion-exception throwable 0))
  ([^Throwable throwable depth]
   (cond-> {:spell/exception true
            :class (.getName (class throwable))
            :message (or (ex-message throwable) (str throwable))
            :data (continuation-safe-value (ex-data throwable) (inc depth))}
     (and (< depth completion-failure-max-depth)
          (some? (ex-cause throwable)))
     (assoc :cause (throwable->completion-exception
                     (ex-cause throwable) (inc depth))))))

(defn- child-failure
  "Build the explicit plain-data value delivered when a child lifecycle fails."
  [handle phase throwable]
  (try
    {:spell/child-failure true
     :handle handle
     :phase phase
     :exception (throwable->completion-exception throwable)}
    (catch Throwable _
      {:spell/child-failure true
       :handle handle
       :phase phase
       :exception {:spell/exception true
                   :class (.getName (class throwable))
                   :message (try
                              (or (ex-message throwable) (str throwable))
                              (catch Throwable _ "Child lifecycle failed"))
                   :data {:normalization-failed true}}})))

;; =============================================================================
;; Wait graph (directed hypermultigraph of waiting relationships)
;; =============================================================================

(def wait-graph
  "Global wait graph: {:epoch runtime-run-id
                        :next-edge-id n
                        :next-seq n
                        :edges {edge-id {:id n :source handle :targets [handle ...]
                                         :slots {handle {:status :pending|:filled
                                                         :value v
                                                         :generation n-or-nil}}
                                         :created-seq n :status :pending}}
                        :nodes {handle {:status :awake|:asleep|:finished :generation n}}}"
  (atom {:epoch 0 :next-edge-id 1 :next-seq 1 :edges {} :nodes {}}))

(defn- empty-wait-graph
  [epoch]
  {:epoch epoch :next-edge-id 1 :next-seq 1 :edges {} :nodes {}})

(defn- swap-current-graph!
  ([f] (swap-current-graph! (operation-epoch) f))
  ([epoch f]
   (swap! wait-graph #(if (= epoch (:epoch %)) (f %) %))))

(defn- swap-vals-current-graph!
  ([f] (swap-vals-current-graph! (operation-epoch) f))
  ([epoch f]
   (swap-vals! wait-graph #(if (= epoch (:epoch %)) (f %) %))))

(defn reset-wait-graph!
  "Reset all wait topology and node lifecycle bookkeeping."
  []
  (reset! wait-graph (empty-wait-graph @runtime-epoch)))

(defn begin-run!
  "Begin an independent runtime run and return its epoch.

   The epoch advances before global state is replaced. Any detached lifecycle
   that resumes during or after the reset therefore fails its epoch check and
   cannot mutate the new registry or wait graph. This operation never waits for
   old lifecycles to drain."
  []
  (let [epoch (swap! runtime-epoch inc)]
    (reset! registry {})
    (reset! wait-graph (empty-wait-graph epoch))
    epoch))

(defn- ensure-node
  [graph handle]
  (if (get-in graph [:nodes handle])
    graph
    (assoc-in graph [:nodes handle] {:status :awake :generation 1})))

(defn- wake-node
  "Mark handle awake in graph. Bumps :generation when waking a finished handle."
  [graph handle]
  (let [graph (ensure-node graph handle)
        node (get-in graph [:nodes handle])]
    (if (= :finished (:status node))
      (assoc-in graph [:nodes handle]
                {:status :awake :generation (inc (:generation node 1))})
      (assoc-in graph [:nodes handle :status] :awake))))

(defn mark-awake!
  "Mark handle awake in the wait graph (bookkeeping only; no signal)."
  [handle]
  (swap-current-graph! #(wake-node % handle))
  nil)

(defn create-edge!
  "Atomically create an outgoing wait edge from source to targets,
   mark every target awake and the source asleep in the wait graph.
   Targets must be distinct. Returns the new edge id.
   Does not deliver wake signals; callers wake targets through messaging."
  [source targets]
  (let [epoch (require-active-epoch! "create-edge!")
        targets (vec targets)]
    (when (empty? targets)
      (throw (ex-info "create-edge!: edge requires at least one target" {:source source})))
    (when (not= (count targets) (count (distinct targets)))
      (throw (ex-info "create-edge!: targets must be distinct" {:source source :targets targets})))
    (when (some #{source} targets)
      (throw (ex-info "create-edge!: source cannot also be a target"
                      {:source source :targets targets})))
    (when-let [unknown (seq (remove #(entry-for-epoch % epoch) targets))]
      (throw (ex-info "create-edge!: target not registered"
                      {:source source :targets targets :unknown-targets (vec unknown)})))
    (let [new-graph
          (swap-current-graph!
            epoch
            (fn [g]
              (let [edge-id (:next-edge-id g)
                    seq-n (:next-seq g)
                    edge {:id edge-id
                          :source source
                          :targets targets
                          ;; A pending slot is unowned until its request is
                          ;; consumed by a target lifecycle. Spawn-ask
                          ;; claims its prepared lifecycle before launch.
                          :slots (into {} (map (fn [t] [t {:status :pending
                                                           :value nil
                                                           :generation nil}])
                                               targets))
                          :created-seq seq-n
                          :status :pending}
                    g (reduce wake-node g targets)
                    g (assoc-in (ensure-node g source) [:nodes source :status] :asleep)]
                (-> g
                    (assoc :next-edge-id (inc edge-id))
                    (assoc :next-seq (inc seq-n))
                    (assoc-in [:edges edge-id] edge)))))]
      (dec (:next-edge-id new-graph)))))

(defn- fill-slot-op!
  "Fill target's result slot in edge-id if pending. When the fill completes
   the edge, remove the edge and mark its source awake.
   Returns {:filled? bool :completed? bool :edge edge-with-fill} where :edge
   reflects the state after this fill (nil when the fill did not apply)."
  [edge-id target value]
  (let [epoch (operation-epoch)
        [old new]
        (swap-vals-current-graph!
          epoch
          (fn [g]
            (let [edge (get-in g [:edges edge-id])
                  slot (get-in edge [:slots target])]
              (if (and edge
                       (= :pending (:status edge))
                       (= :pending (:status slot)))
                (let [edge' (-> edge
                                (assoc-in [:slots target :status] :filled)
                                (assoc-in [:slots target :value] value))
                      completed? (every? #(= :filled (:status %)) (vals (:slots edge')))]
                  (if completed?
                    (-> g
                        (update :edges dissoc edge-id)
                        (wake-node (:source edge')))
                    (assoc-in g [:edges edge-id] edge')))
                g))))
        old-edge (get-in old [:edges edge-id])
        old-slot (get-in old-edge [:slots target])
        filled? (and (= epoch (:epoch old))
                     old-edge
                     (= :pending (:status old-edge))
                     (= :pending (:status old-slot)))
        edge' (when filled?
                (-> old-edge
                    (assoc-in [:slots target :status] :filled)
                    (assoc-in [:slots target :value] value)))
        completed? (and filled?
                        (every? #(= :filled (:status %)) (vals (:slots edge'))))]
    {:filled? (boolean filled?)
     :completed? (boolean completed?)
     :edge edge'}))

(defn- claim-slot!
  "Associate a pending result slot with the target lifecycle that consumed its
   request. Claims are idempotent for the same generation and cannot transfer
   ownership from one lifecycle to another."
  [edge-id target generation]
  (let [epoch (operation-epoch)
        [old new]
        (swap-vals-current-graph!
          epoch
          (fn [g]
            (let [slot (get-in g [:edges edge-id :slots target])]
              (if (and (= :pending (:status slot))
                       (nil? (:generation slot)))
                (assoc-in g [:edges edge-id :slots target :generation]
                          generation)
                g))))
        old-slot (get-in old [:edges edge-id :slots target])
        new-slot (get-in new [:edges edge-id :slots target])]
    (and (= epoch (:epoch old))
         (or (and (= :pending (:status old-slot))
                  (= generation (:generation old-slot)))
             (and (= :pending (:status new-slot))
                  (= generation (:generation new-slot)))))))

(defn- node-generation
  [handle]
  (let [[_ graph] (inspection-graph "lifecycle generation")]
    (get-in graph [:nodes handle :generation])))

(defn- cancel-edge-op!
  "Cancel edge-id when caller is its source and the edge is pending.
   Returns the cancelled edge map, or nil when no change was made."
  [edge-id caller]
  (let [epoch (operation-epoch)
        [old _]
        (swap-vals-current-graph!
          epoch
          (fn [g]
            (let [edge (get-in g [:edges edge-id])]
              (if (and edge
                       (= caller (:source edge))
                       (= :pending (:status edge)))
                (update g :edges dissoc edge-id)
                g))))
        edge (get-in old [:edges edge-id])]
    (when (and (= epoch (:epoch old))
               edge (= caller (:source edge)) (= :pending (:status edge)))
      edge)))

(defn- pending-out-edges
  [graph handle]
  (->> (vals (:edges graph))
       (filter #(and (= handle (:source %)) (= :pending (:status %))))
       (sort-by :created-seq)
       vec))

(defn- pending-in-edges
  "Ordering-only view: edges in which handle holds a pending result slot."
  [graph handle]
  (->> (vals (:edges graph))
       (filter #(and (= :pending (:status %))
                     (= :pending (get-in % [:slots handle :status]))))
       (sort-by :created-seq)
       vec))

(defn- live-in-edges
  "Inspection view: every live edge containing a slot for handle, including
   slots already filled while other targets remain pending."
  [graph handle]
  (->> (vals (:edges graph))
       (filter #(and (= :pending (:status %))
                     (contains? (:slots %) handle)))
       (sort-by :created-seq)
       vec))

(defn sleep-allowed?
  "True when handle has a pending outgoing edge created strictly after its
   newest pending incoming edge (only pending incoming slots participate)."
  ([handle]
   (let [[_ graph] (inspection-graph "sleep eligibility")]
     (sleep-allowed? graph handle)))
  ([graph handle]
   (let [newest-out (last (map :created-seq (pending-out-edges graph handle)))
         newest-in (last (map :created-seq (pending-in-edges graph handle)))]
     (boolean (and newest-out
                   (or (nil? newest-in) (> newest-out newest-in)))))))

(defn- mark-asleep-if-allowed!
  "Atomically validate the strict edge-order rule and mark handle asleep.
   Returns true on transition and false without changing the graph."
  [handle]
  (let [epoch (operation-epoch)
        [old new]
        (swap-vals-current-graph!
          epoch
          (fn [g]
            (if (and (= :awake (get-in g [:nodes handle :status]))
                     (sleep-allowed? g handle))
              (assoc-in g [:nodes handle :status] :asleep)
              g)))]
    (not (identical? old new))))

(defn- edge-summary
  [edge]
  {:id (:id edge)
   :source (:source edge)
   :targets (:targets edge)
   :created-seq (:created-seq edge)
   :status (:status edge)
   :slots (into {} (map (fn [[t slot]] [t (select-keys slot [:status :value :generation])])
                        (:slots edge)))})

(defn- claim-drained-request-slots!
  "Claim request slots carried by the exact inbox batch this lifecycle drained."
  [handle generation inbox-macros]
  (doseq [msg-macro inbox-macros
          :let [{:keys [edge-id target]} (::wait-slot msg-macro)]
          :when (and edge-id (= handle target))]
    (claim-slot! edge-id target generation)))
;; =============================================================================
;; Inside-fn constructors
;; =============================================================================

(defn make-awake-fn
  "Create an inside-fn that drains inbox, resets signal, and calls eval-fn.
   This is the single drain point per wake cycle (phase 3 entry).
   Drain and signal reset happen atomically via a single reset-vals! on
   the combined :state atom — no race window between the two operations."
  [handle eval-fn]
  (fn [raw]
    ;; send/create-edge normally performs this transition before signaling.
    ;; Reassert it at actual lifecycle entry so a send that raced with the
    ;; previous lifecycle's finish cannot leave graph state stale.
    (mark-awake! handle)
    (let [epoch (operation-epoch)
          entry (entry-for-epoch handle epoch)
          _ (when-not entry
              (throw (ex-info "Agent lifecycle belongs to an earlier runtime run"
                              {:type :stale-runtime-epoch
                               :handle handle
                               :lifecycle-epoch epoch
                               :runtime-epoch @runtime-epoch})))
          generation (node-generation handle)
          state (:state entry)
          [{:keys [inbox-macros]} _] (reset-vals! state {:inbox-macros [], :signal (promise)})]
      ;; Ownership follows consumption, not enqueueing: only requests in this
      ;; atomically drained batch may be satisfied by this lifecycle's return.
      (claim-drained-request-slots! handle generation inbox-macros)
      (let [transformed-raw (if (and (seq inbox-macros) (not (inbox-aware-eval-fn? eval-fn)))
                              (inbox/materialize-inbox-raw raw inbox-macros {:builtins eval/core-builtins})
                              raw)]
        (when-let [last-raw (:last-raw entry)]
          (reset! last-raw transformed-raw))
        (binding [*current-eval-fn* eval-fn]
          (if (inbox-aware-eval-fn? eval-fn)
            (eval-fn raw inbox-macros)
            (eval-fn transformed-raw)))))))

(defn- make-asleep-fn
  "Create an inside-fn that blocks on signal, then re-enters box awake.
   No drain or signal reset here — that happens in make-awake-fn (phase 3).
   Uses the raw parameter (not *current-raw*) so that transforms applied
   by the enclosing box before sleep are preserved on fast-reply paths."
  [handle eval-fn]
  (fn [raw]
    (let [epoch (operation-epoch)
          state (:state (entry-for-epoch handle epoch))]
      (when-not state
        (throw (ex-info "Agent lifecycle belongs to an earlier runtime run"
                        {:type :stale-runtime-epoch
                         :handle handle
                         :lifecycle-epoch epoch
                         :runtime-epoch @runtime-epoch})))
      (deref (:signal @state))
      (box handle raw (make-awake-fn handle eval-fn)))))

(defn- make-root-fn
  "Wrap an inside-fn with root lifecycle: completed delivery + orphan creation.
   After inside-fn returns (or throws), delivers completed and starts an
   asleep orphan box for the next lifecycle round.
   Reads :last-raw from registry (not *current-raw*) so the orphan captures
   the innermost extension's raw — dynamic binding reverts on stack unwind,
   but the atom retains the deepest box's value.
   No signal reset needed — make-awake-fn resets signal at phase 3 entry,
   and the orphan's asleep-fn will block on the signal created there."
  [handle eval-fn inside-fn]
  (let [epoch (operation-epoch)
        entry (entry-for-epoch handle epoch)
        completed (current-completion handle)]
    (fn [raw]
      (let [[result failure]
            (try
              [(inside-fn raw) nil]
              (catch Throwable e
                [(child-failure handle :execution e) e]))]
        ;; Finish is one wait-graph transition; completion rotation is bound
        ;; to the exact lifecycle promise captured when this root was built.
        (binding [*runtime-epoch* epoch]
          (finish-agent! handle result)
          ;; Only the lifecycle that still owns this run's registry entry may
          ;; rotate completion or create the next orphan. A detached lifecycle
          ;; from an older API run returns to its caller without touching the
          ;; replacement handle.
          (when (and (realize-completion! handle completed result)
                     (active-epoch? epoch))
            (let [orphan-raw @(:last-raw entry)]
              (future (binding [*runtime-epoch* epoch]
                        (box handle orphan-raw
                          (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn))))))))
        (if failure
          (throw failure)
          result)))))

(defn run-root-box
  "Public entry point for root lifecycle.
   Separates failure domains:
   - completion-source failures (before inside-fn ran) are handled here
   - inside-fn failures are handled by make-root-fn."
  [handle completion-source inside-fn eval-fn]
  (let [epoch (operation-epoch)
        completed (current-completion handle)
        root-fn (make-root-fn handle eval-fn inside-fn)]
    (binding [*runtime-epoch* epoch]
      (try
        (box handle (resolve-completion-source completion-source) root-fn)
        (catch Throwable e
          ;; make-root-fn already rotated completion for execution failures.
          ;; A successful CAS means failure happened before inside-fn took over.
          (let [failure (child-failure handle :initialization e)
                entry (entry-for-epoch handle epoch)]
            (when (and entry (identical? completed @(:completed entry)))
              (finish-agent! handle failure))
            (when (and (realize-completion! handle completed failure)
                       (active-epoch? epoch))
              (future (binding [*runtime-epoch* epoch]
                        (box handle ""
                          (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn)))))))
          (throw e))))))

;; =============================================================================
;; Box
;; =============================================================================

(defn box
  "Core execution primitive. Awaits completion, CAS has-box, and calls
   inside-fn with the raw string. Inbox drain happens in make-awake-fn
   (single-drain model: one drain per wake cycle, at the start of phase 3).
   Takes handle, a completion source (promise, future, or raw string),
   and an inside-fn that processes the raw string.
   Updates :last-raw in registry so make-root-fn can read the innermost
   raw for orphan box creation (dynamic binding reverts on unwind)."
  [handle completion-source inside-fn]
  (let [epoch (operation-epoch)
        {:keys [has-box last-raw]} (entry-for-epoch handle epoch)]
    (when-not has-box
      (throw (ex-info "Handle not registered in this runtime run"
                      {:handle handle
                       :type (when-not (active-epoch? epoch) :stale-runtime-epoch)
                       :lifecycle-epoch epoch
                       :runtime-epoch @runtime-epoch})))
    (let [raw (parse/balance-parens (resolve-completion-source completion-source))]
      (when-not (compare-and-set! has-box false true)
        (throw (ex-info "Box already active for handle" {:handle handle})))
      (reset! has-box false)
      (reset! last-raw raw)
      (binding [*runtime-epoch* epoch
                *current-handle* handle
                *current-raw*    raw]
        (inside-fn raw)))))

;; =============================================================================
;; Send
;; =============================================================================

(defn -send!
  "Low-level send: queue msg-macro into the inbox with FIFO ordering,
   then deliver signal. Both operations happen atomically via swap-vals!
   on the combined :state atom. A delivery owned by a stale runtime epoch is
   ignored so a late completion cannot address a reused handle."
  [handle msg-macro]
  (let [epoch (operation-epoch)
        state (:state (entry-for-epoch handle epoch))]
    (cond
      (not (active-epoch? epoch)) nil
      (not state)
      (throw (ex-info "Handle not registered in this runtime run"
                      {:handle handle :runtime-epoch epoch}))
      :else
      (let [[old new] (swap-vals! state
                        (fn [{:keys [inbox-macros] :as s}]
                          (if (active-epoch? epoch)
                            (assoc s :inbox-macros (conj inbox-macros msg-macro))
                            s)))]
        (when-not (identical? old new)
          (deliver (:signal old) :wake))))))

(defn send-msg-fn
  "Queue a Spell macro value to run against the target's parsed completion.
   Most callers should prefer send/ask/reply over this low-level primitive.
   Returns nil."
  [msg-macro handle]
  (-send! handle msg-macro)
  nil)

(defn deliver-msg-fn
  "Like send-msg-fn but delivers to a specific signal promise.
   No-op if the signal has been replaced OR already delivered (agent woke
   from something else). Uses swap-vals! so staleness/realization check and
   inbox composition happen in one atomic state transition."
  [handle captured-signal msg-macro]
  (let [epoch (operation-epoch)]
    (when-let [state (:state (entry-for-epoch handle epoch))]
      (when (active-epoch? epoch)
        (let [[old new] (swap-vals! state
                          (fn [{:keys [inbox-macros signal] :as s}]
                            (if (and (active-epoch? epoch)
                                     (identical? signal captured-signal)
                                     (not (realized? signal)))
                              (assoc s :inbox-macros (conj inbox-macros msg-macro))
                              s)))]
          (when-not (identical? old new)
            (deliver captured-signal :wake)))))))

;; =============================================================================
;; Create-msg helper
;; =============================================================================

(defn- identity-msg-macro
  []
  (eval/compose-macros []))

(defn- append-forms-macro
  [forms]
  {:spell/macro true
   :expander {:spell/fn true
              :params ['q]
              :body [(list* 'reopen 'q forms)]}})

(defn- create-msg
  "Create a Spell macro that reopens a parsed completion, appends (def name value),
   and appends an !extend continuation so the recipient continues thinking.
   Injects a think annotation so the agent knows the message preempted its
   trailing expression (if active) or awakened it (if sleeping).
   Internal plumbing for signaling (waiting-for, spawn-result)."
  [name value]
  (let [value-form (parse/read-first (eval/serialize-for-continuation value))]
    (append-forms-macro
      [(list 'think (str "[preempted or awakened by " name "]"))
       (list 'def name value-form)
       (list 'quote (list '!extend))])))

(defn- create-request-msg
  "Create an ask request macro carrying private slot-claim metadata. The
   metadata is consumed by make-awake-fn and is not exposed to Spell code."
  [name value edge-id target]
  (assoc (create-msg name value)
         ::wait-slot {:edge-id edge-id :target target}))

(defn- actionable-request?
  "True only for a live-request-shaped message, not an edge completion report."
  [msg]
  (true? (:expects-response msg)))

(defn actionable-request-live?
  "True when msg names a pending request slot owned by the current agent.
   This is an internal host-side helper for interactive routing; it is not
   exposed in agents-namespace."
  [msg]
  (when (and *current-handle* (actionable-request? msg) (:edge-id msg))
    (let [[_ graph] (inspection-graph "actionable request")
          edge (get-in graph [:edges (:edge-id msg)])]
      (boolean
        (and (= :pending (:status edge))
             (= (:from msg) (:source edge))
             (= :pending (get-in edge [:slots *current-handle* :status])))))))

(defn- reply-target
  "Return a message's singleton sender or reject an aggregate completion report."
  [caller msg]
  (let [target (:from msg)]
    (when (sequential? target)
      (throw (ex-info (str caller ": cannot reply to a multi-target completion report; "
                           "reply to a specific target or start a new request")
                      {:from target :edge-id (:edge-id msg)})))
    (when-not target
      (throw (ex-info (str caller ": message has no :from sender") {:message msg})))
    target))

(defn send
  "Send a message to target with auto-tagged sender handle.
   Injects (def <gensym> {:from sender :body val}) into recipient's completion.
   The recipient sees the def binding with the message map."
  [target value]
  (let [epoch (require-active-epoch! "send")]
    (when-not (entry-for-epoch target epoch)
    (throw (ex-info "send: target handle not registered" {:target target})))
    (let [name (symbol (gensym "msg-"))
          from *current-handle*
          msg-macro (create-msg name {:from from :body value})]
      (mark-awake! target)
      (send-msg-fn msg-macro target))))

(defn- install-notifier
  "Watch target's :completed promise. When delivered, call
   (signal-fn handle result). signal-fn determines stale vs persistent."
  [signal-fn target]
  (let [epoch (require-active-epoch! "install-notifier")
        completed-p @(:completed (entry-for-epoch target epoch))
        handle *current-handle*]
    (future
      (binding [*runtime-epoch* epoch]
        (let [result @completed-p]
          (signal-fn handle result))))))

(defn- install-completion-notifier
  "Install stale notifier: sends target's completion result to self.
   Captures current :signal at install time; no-ops if self wakes first."
  [target]
  (let [my-signal (:signal @(:state (entry-for-epoch *current-handle* (operation-epoch))))]
    (install-notifier
      (fn [handle result]
        (deliver-msg-fn handle my-signal
          (create-msg (symbol (gensym "msg-")) {:from target :body result})))
      target)))

(defn- install-persistent-notifier
  "Install persistent notifier: sends target's completion result to self
   regardless of whether self has already woken. Used by event-based
   patterns where the notification should always arrive."
  [target]
  (install-notifier
    (fn [handle result]
      (send-msg-fn (create-msg (symbol (gensym "msg-")) {:from target :body result})
                   handle))
    target))

(defn reply
  "Reply to a message (fire-and-forget).
   Actionable requests (:expects-response true) fill their edge slot. Stale,
   duplicate, or cancelled actionable requests are no-ops. A singleton edge
   completion report is an ordinary message and replies by plain send."
  [msg value]
  (if (actionable-request? msg)
    ;; An edge-bearing request has exactly one result position. A stale,
    ;; duplicate, cancelled, or foreign reply is a no-op; it must never turn
    ;; into an unrelated plain message and create a second notification.
    (if-let [edge-id (:edge-id msg)]
      (do (fill-slot! edge-id *current-handle* value)
          nil)
      (throw (ex-info "agents/reply: actionable request has no :edge-id"
                      {:message msg})))
    ;; Completion reports and legacy/plain messages have no live incoming
    ;; obligation. A singleton sender therefore gets a normal plain reply.
    (send (reply-target "agents/reply" msg) value)))

;; =============================================================================
;; Wait graph messaging integration
;; =============================================================================

(defn- deliver-edge-completion!
  "Deliver a completed edge's report to its source as a persistent message.
   Single-target edges deliver {:from target :body value :edge-id id};
   multi-target edges deliver {:from targets :body [{:from t :body v} ...] :edge-id id}."
  [edge]
  (let [source (:source edge)
        targets (:targets edge)
        msg (if (= 1 (count targets))
              (let [t (first targets)]
                {:from t
                 :body (get-in edge [:slots t :value])
                 :edge-id (:id edge)})
              {:from targets
               :body (mapv (fn [t] {:from t :body (get-in edge [:slots t :value])}) targets)
               :edge-id (:id edge)})]
    (send-msg-fn (create-msg (symbol (gensym "msg-")) msg) source)))

(defn fill-slot!
  "Fill target's result slot in edge-id with value. Fills at most once.
   When the fill completes the edge, remove it from the wait graph and wake
   the source with the edge's completion report. Returns the fill-slot-op!
   result map."
  [edge-id target value]
  (let [epoch (operation-epoch)]
    (binding [*runtime-epoch* epoch]
      (let [result (fill-slot-op! edge-id target (continuation-safe-value value))]
        (when (:completed? result)
          (deliver-edge-completion! (:edge result)))
        result))))

(defn- finish-transition
  "Pure atomic transition for one lifecycle return."
  [graph handle result]
  (let [generation (get-in graph [:nodes handle :generation])
        [graph completed]
        (reduce
          (fn [[g completed] edge]
            (if (and (= :pending (get-in edge [:slots handle :status]))
                     (= generation (get-in edge [:slots handle :generation])))
              (let [edge' (-> edge
                              (assoc-in [:slots handle :status] :filled)
                              (assoc-in [:slots handle :value] result))]
                (if (every? #(= :filled (:status %)) (vals (:slots edge')))
                  [(-> g
                       (update :edges dissoc (:id edge'))
                       (wake-node (:source edge')))
                   (conj completed edge')]
                  [(assoc-in g [:edges (:id edge')] edge') completed]))
              [g completed]))
          [graph []]
          (sort-by :created-seq (vals (:edges graph))))
        cancelled (pending-out-edges graph handle)
        graph (update graph :edges #(apply dissoc % (map :id cancelled)))
        graph (assoc-in (ensure-node graph handle) [:nodes handle :status] :finished)]
    {:graph graph
     :completed completed
     :cancelled cancelled}))

(defn finish-agent!
  "Record a lifecycle return in the wait graph:
   1. fill every pending incoming result slot held by handle with result;
   2. cancel handle's pending outgoing edges (trace warning when any);
   3. mark handle finished."
  [handle result]
  (let [epoch (operation-epoch)
        slot-result (continuation-safe-value result)
        [old _]
        (swap-vals-current-graph!
          epoch
          #(-> (finish-transition % handle slot-result) :graph))
        applied? (= epoch (:epoch old))
        {:keys [completed cancelled]} (if applied?
                                        (finish-transition old handle slot-result)
                                        {:completed [] :cancelled []})]
    (binding [*runtime-epoch* epoch]
      (when (seq cancelled)
        (trace/record-warning!
          (str "Agent " handle " finished with " (count cancelled)
               " unfinished outgoing edge(s); automatic result collection abandoned.")
          {:handle handle
           :detached-edges (mapv (fn [e] {:id (:id e) :targets (:targets e)}) cancelled)}))
      (doseq [edge completed]
        (deliver-edge-completion! edge)))
    nil))

(defn cancel-edge
  "Cancel one of the caller's pending outgoing edges. Does not stop or
   interrupt its targets. Returns a summary of the cancelled edge, or
   throws when the edge does not exist, is not pending, or the caller is
   not its source."
  [edge-id]
  (let [caller *current-handle*
        edge (cancel-edge-op! edge-id caller)]
    (when-not edge
      (throw (ex-info (str "agents/cancel: no pending outgoing edge " edge-id
                           " owned by " caller)
                      {:edge-id edge-id :caller caller})))
    (assoc (edge-summary edge) :status :cancelled)))

(defn- inspection-graph
  [caller]
  (let [epoch (require-active-epoch! caller)
        graph @wait-graph]
    (when-not (= epoch (:epoch graph))
      (throw (ex-info (str caller ": runtime run changed during inspection")
                      {:type :stale-runtime-epoch
                       :lifecycle-epoch epoch
                       :runtime-epoch @runtime-epoch})))
    [epoch graph]))

(defn agent-status
  "Zero arity: inspect the caller's state plus concise incoming/outgoing edge
  summaries. One arity: inspect another handle's state and lifecycle generation."
  ([]
   (let [[_ graph] (inspection-graph "agents/status")
         handle *current-handle*
         node (get-in graph [:nodes handle] {:status :awake :generation 1})]
     {:handle handle
      :status (:status node)
      :generation (:generation node)
      :out-edges (mapv edge-summary (pending-out-edges graph handle))
      :in-edges (mapv (fn [e]
                        (assoc (edge-summary e)
                               :my-slot (get-in e [:slots handle :status])))
                      (live-in-edges graph handle))}))
  ([handle]
   (let [[epoch graph] (inspection-graph "agents/status")
         node (get-in graph [:nodes handle])]
     (if node
       {:handle handle :status (:status node) :generation (:generation node)}
       (if (entry-for-epoch handle epoch)
         {:handle handle :status :awake :generation 1}
         (throw (ex-info (str "agents/status: unknown handle " handle) {:handle handle})))))))

(defn graph-snapshot
  "Read-only snapshot of the wait graph: nodes and pending hyperedges."
  []
  (let [[_ graph] (inspection-graph "agents/graph")]
    {:nodes (into {} (map (fn [[h n]] [h (select-keys n [:status :generation])])
                          (:nodes graph)))
     :edges (mapv edge-summary (sort-by :created-seq (vals (:edges graph))))}))

(defn out-edges
  "Inspect the caller's pending outgoing edges, target slots, and collected
   outcomes."
  []
  (let [[_ graph] (inspection-graph "agents/out-edges")]
    (mapv edge-summary (pending-out-edges graph *current-handle*))))

(defn in-edges
  "Inspect every live edge in which the caller has a target slot, including
  filled slots on multi-target edges that remain live."
  []
  (let [[_ graph] (inspection-graph "agents/in-edges")
        handle *current-handle*]
    (mapv (fn [e]
            (assoc (edge-summary e)
                   :my-slot (get-in e [:slots handle :status])))
          (live-in-edges graph handle))))

;; =============================================================================
;; Block-for-message (internal)
;; =============================================================================

(defn block-for-message
  "Re-enter box with asleep inside-fn. Must be called from within an agent
   context (inside box). Uses *current-eval-fn* to construct the asleep fn."
  []
  (box *current-handle* *current-raw*
    (make-asleep-fn *current-handle* *current-eval-fn*)))

(defn- assert-agent-context!
  "Throw if not inside an agent context (box execution)."
  [caller]
  (when-not *current-handle*
    (throw (ex-info (str caller ": not inside an agent context. "
                         "This function requires an active agent box (spawn or runtime lifecycle).")
                    {})))
  (when-not *current-raw*
    (throw (ex-info (str caller ": no raw completion available") {}))))

(defn completion-promise
  "Return await token for handle's current completion promise.
   Used by the future-gated blocking/ namespace."
  [handle]
  (let [epoch (require-active-epoch! "completion-promise")
        entry (entry-for-epoch handle epoch)]
    (when-not entry
      (throw (ex-info "completion-promise: handle not registered" {:handle handle})))
    (completion-token @(:completed entry))))

(defn blocking-await
  "Await helper for Spell futures (exposed via future-gated blocking/ namespace)."
  [fut]
  (if (eval/spell-future? fut)
    (deref (:ref fut))
    (throw (ex-info "blocking/await requires a future" {:value fut}))))

(defn blocking-await-all
  "Await a collection of Spell futures (exposed via future-gated blocking/ namespace)."
  [futures]
  (when-not (sequential? futures)
    (throw (ex-info "blocking/await-all: argument must be a collection" {:got futures})))
  (mapv (fn [f]
          (when-not (eval/spell-future? f)
            (throw (ex-info "blocking/await-all: all elements must be futures" {:got f})))
          (deref (:ref f)))
        futures))

(defn blocking-pmap
  "Parallel map over Spell futures (exposed via future-gated blocking/ namespace)."
  [f coll]
  (let [futures (mapv (fn [item]
                        (completion-token
                          (clojure.core/future
                            ((bound-fn [] (eval/invoke-fn f [item])))))
                        )
                      coll)]
    (blocking-await-all futures)))

(defn send-await
  "Capture completion token, send message, await completion (via blocking/ namespace)."
  [handle msg]
  (let [token (completion-promise handle)]
    (send handle msg)
    (blocking-await token)))

(defn sleep!
  "Go asleep on retained outgoing edges without sending any message or
   creating any edge. Allowed only when the caller has a pending outgoing
   edge created strictly after its newest pending incoming edge; otherwise
   throws without changing the wait graph."
  []
  (assert-agent-context! "!sleep")
  (let [me *current-handle*]
    (when-not (mark-asleep-if-allowed! me)
      (let [[_ graph] (inspection-graph "agents/!sleep")]
        (throw (ex-info (str "agents/!sleep: " me " has no pending outgoing edge "
                             "newer than its newest pending incoming edge")
                        {:handle me
                         :out-edges (mapv edge-summary (pending-out-edges graph me))
                         :in-edges (mapv edge-summary (pending-in-edges graph me))}))))
    (block-for-message)))

(defn reply-ask
  "Reply with value as a new reverse request, then go asleep until the sender
   responds. For an actionable singleton request, retire its old slot without
   also delivering a stale completion report. For a singleton completion report,
   directly create the reverse request. In both cases the sender sees one
   actionable message carrying value and the new live edge id. Multi-target
   request completion remains all-target; aggregate reports are rejected."
  [msg value]
  (assert-agent-context! "reply-ask")
  (let [target (reply-target "agents/!reply-ask" msg)]
    (when (actionable-request? msg)
      (when-not (:edge-id msg)
        (throw (ex-info "agents/!reply-ask: actionable request has no :edge-id"
                        {:message msg})))
      (let [result (fill-slot-op! (:edge-id msg) *current-handle*
                                (continuation-safe-value value))]
        (when-not (:filled? result)
          (throw (ex-info "agents/!reply-ask: incoming edge slot is no longer pending"
                          {:edge-id (:edge-id msg)
                           :handle *current-handle*})))
        ;; A completed singleton is superseded by the reverse request below. A
        ;; multi-target edge retains its all-results completion report.
        (when (and (:completed? result)
                   (> (count (get-in result [:edge :targets])) 1))
          (deliver-edge-completion! (:edge result)))))
    ;; For a completion report there is no old live slot to fill: directly
    ;; continue the conversation with one actionable reverse request.
    (ask-one target true value)))

;; =============================================================================
;; Ask
;; =============================================================================

(defn- wait-for-target-completions
  "Install a single stale notifier that waits for all target completions
   and delivers one combined message to self."
  [targets]
  ;; Install a single notifier that waits for all targets to complete
  (let [epoch (require-active-epoch! "wait-for-target-completions")
        handle *current-handle*
        my-signal (:signal @(:state (entry-for-epoch handle epoch)))
        completed-promises (mapv #(-> (entry-for-epoch % epoch) :completed deref) targets)]
    (future
      (binding [*runtime-epoch* epoch]
        (let [results (mapv (fn [target cp] {:from target :body @cp})
                            targets completed-promises)]
          (deliver-msg-fn handle my-signal
            (create-msg (symbol (gensym "msg-")) {:from targets :body results})))))))

(defn- ask-multi
  "Multi-target ask: create one all-targets edge, poke all targets, and go
   asleep. The edge completes when every target's result slot is filled by
   a reply or lifecycle return."
  [targets]
  (let [me *current-handle*
        edge-id (create-edge! me (vec targets))]
    (doseq [target targets]
      (let [name (symbol (gensym "msg-"))
            ask-msg {:from me :expects-response true :edge-id edge-id}]
        (send-msg-fn (create-request-msg name ask-msg edge-id target) target)))
    (block-for-message)))

(defn- ask-one
  "Create and send one request edge. include-body? distinguishes a bodyless poke
   from an explicit nil body."
  [target include-body? msg]
  (assert-agent-context! "ask")
  (let [me *current-handle*
        edge-id (create-edge! me [target])
        name (symbol (gensym "msg-"))
        ask-msg (cond-> {:from me :expects-response true :edge-id edge-id}
                  include-body? (assoc :body msg))]
    (send-msg-fn (create-request-msg name ask-msg edge-id target) target)
    (block-for-message)))

(defn ask-builtin
  "Request-reply communication primitive.
   (ask target msg) — send msg to target and wait for reply. The message
     includes the sender's handle so the target knows who to reply to.
   (ask target) — poke target (wake it) and wait for a message. Use when
     woken by the wrong agent and you need to go back to sleep for a specific one.
   (ask [targets]) — multi-target ask. Poke all targets, wake when all complete.
   Every form wakes its targets. The wait-edge ordering rule prevents an
   all-asleep cycle introduced by the communication topology."
  ([target]
   (if (sequential? target)
     (do
       (assert-agent-context! "ask")
       (when (empty? target)
         (throw (ex-info "ask: empty target list" {})))
       (ask-multi target))
     (ask-one target false nil)))
  ([target msg]
   (ask-one target true msg)))


;; =============================================================================
;; Handle queries
;; =============================================================================

(defn handle?
  "Returns true if h is registered in the caller's runtime run."
  [h]
  (boolean (entry-for-epoch h (operation-epoch))))

;; =============================================================================
;; Start box helper
;; =============================================================================

(defn start-box
  "Register handle and start a root box that sleeps until first message.
   Returns handle. Used by register-agent for dormant agents.
   No initial evaluation — agent wakes on first message."
  ([handle eval-fn initial-completion]
   (start-box handle eval-fn initial-completion nil))
  ([handle eval-fn initial-completion parent-handle]
   (register! handle parent-handle)
   (let [epoch (operation-epoch)]
     ;; This persistent handle has no active lifecycle until its first message.
     ;; Represent that dormant physical waiter as :finished, not :asleep: the
     ;; graph invariant reserves :asleep for nodes with outgoing wait edges.
     (swap-current-graph! epoch #(assoc-in % [:nodes handle :status] :finished))
     (future (binding [*runtime-epoch* epoch]
               (run-root-box handle initial-completion
                 (make-asleep-fn handle eval-fn) eval-fn)))
     handle)))

;; =============================================================================
;; Spawn
;; =============================================================================

(defn- validate-spawn-agent!
  [agent handle-name]
  (when (:spell/leaf (meta agent))
    (throw (ex-info "leaf-llm cannot be used with agents/spawn (no agent lifecycle) — use !llm-self instead"
                    {:handle handle-name})))
  (when-not (compiled-agent? agent)
    (throw (ex-info "agents/spawn requires a compiled agent"
                    {:value agent
                     :handle handle-name})))
  agent)

(defn- prepare-spawn*
  "Register a child and capture its exact lifecycle completion promise without
   launching it. The returned :start! function is one-shot."
  [agent prompt handle-name]
  (validate-spawn-agent! agent handle-name)
  (let [handle (or handle-name (keyword (gensym "spawn-")))
        parent *current-handle*]
    (register! handle parent)
    (let [epoch (operation-epoch)
          initial-completed (current-completion handle)
          started? (atom false)
          settle-direct!
          (fn [result]
            ;; Normal compiled agents rotate initial-completed in run-root-box.
            ;; A direct return/throw has no persistent orphan lifecycle, so
            ;; retire that handle after satisfying its existing observers.
            (retire-terminal-completion! handle initial-completed result))
          start!
          (fn []
            (when (compare-and-set! started? false true)
              (future
                ((bound-fn []
                   (binding [*runtime-epoch* epoch]
                     (try
                       (let [result (agent prompt handle)]
                         (settle-direct! result)
                         result)
                       (catch Throwable e
                         (settle-direct! (child-failure handle :initialization e))
                         (throw e)))))))))]
      {:handle handle
       :completed initial-completed
       :start! start!})))

(defn- start-prepared-spawn!
  [prepared]
  ((:start! prepared))
  prepared)

(defn- spawn*
  "Internal spawn primitive. Registers and immediately launches a child."
  [agent prompt handle-name]
  (start-prepared-spawn! (prepare-spawn* agent prompt handle-name)))

(defn spawn
  "Start an agent in a background future. Returns its handle immediately.
   The handle is addressable. The child must explicitly send
   its result if needed; use ask-based patterns to collect spawn results.
   1-arity and prompt-first forms default the compiled agent to the current agent.
   agent must be a compiled spawn-agent function; leaf-llm is not compatible.
   Stores parent handle in registry so the child can find its spawner.
   Registers synchronously so the handle is live before spawn returns.
   Optional handle-name (keyword) sets a fixed handle instead of auto-generating."
  ([prompt]
   (spawn (default-spawn-agent "spawn") prompt nil))
  ([a b]
   (if (compiled-agent? a)
     (spawn a b nil)
     (spawn (default-spawn-agent "spawn") a b)))
  ([agent prompt handle-name]
   (:handle (spawn* agent prompt handle-name))))

(defn- normalize-spawn-from-multi-spec
  "Validate and normalize a multi-spawn-ask entry without registering it.
   Supports explicit entries:
     [agent prompt]
     [agent prompt handle-name]
   and default-agent entries:
     prompt
     [prompt handle-name]"
  [spec]
  (if (vector? spec)
    (case (count spec)
      2 (let [[a b] spec]
          (if (compiled-agent? a)
            {:agent (validate-spawn-agent! a nil) :prompt b :handle-name nil}
            (let [agent (default-spawn-agent "spawn-ask")]
              {:agent (validate-spawn-agent! agent b) :prompt a :handle-name b})))
      3 (let [[a b c] spec]
          (if (compiled-agent? a)
            {:agent (validate-spawn-agent! a c) :prompt b :handle-name c}
            (throw (ex-info "spawn-ask: explicit 3-item entries must be [compiled-agent prompt handle-name]"
                            {:spec spec}))))
      (throw (ex-info "spawn-ask: each vector entry must be [compiled-agent prompt], [compiled-agent prompt handle-name], or [prompt handle-name]"
                      {:spec spec})))
    (let [agent (default-spawn-agent "spawn-ask")]
      {:agent (validate-spawn-agent! agent nil) :prompt spec :handle-name nil})))

(defn- discard-unstarted-spawn!
  "Remove a prepared child that has not been launched. Identity-check its
   lifecycle promise so rollback cannot remove a replacement registration."
  [{:keys [handle completed]}]
  (let [epoch (operation-epoch)
        [old new]
        (swap-vals! registry
                    (fn [entries]
                      (let [entry (get entries handle)]
                        (if (and entry
                                 (= epoch (:epoch entry))
                                 (identical? completed @(:completed entry)))
                          (dissoc entries handle)
                          entries))))]
    (when (and (contains? old handle) (not (contains? new handle)))
      (swap-current-graph! epoch #(update % :nodes dissoc handle)))))

(defn- prepare-multi-spawns!
  "Register every normalized child, rolling back the whole batch if any
   registration fails. No child is launched until this returns successfully."
  [normalized]
  (let [epoch (require-active-epoch! "spawn-ask")
        explicit-handles (vec (keep :handle-name normalized))]
    (when-not (= (count explicit-handles) (count (distinct explicit-handles)))
      (throw (ex-info "spawn-ask: child handles must be distinct"
                      {:handles explicit-handles})))
    (when-let [registered (seq (filter #(entry-for-epoch % epoch) explicit-handles))]
      (throw (ex-info "spawn-ask: child handle already registered"
                      {:handles (vec registered)})))
    (let [prepared (atom [])]
      (try
        (doseq [{:keys [agent prompt handle-name]} normalized]
          (swap! prepared conj (prepare-spawn* agent prompt handle-name)))
        @prepared
        (catch Throwable e
          (doseq [child (rseq (vec @prepared))]
            (discard-unstarted-spawn! child))
          (throw e))))))

(defn spawn-ask
  "Spawn child agent(s), create a completion edge, and go asleep until the
   edge completes. Prompt-only forms default the compiled agent to the
   current agent. Vector form spawns multiple children and creates one
   all-targets edge; the parent wakes once with the combined report when
   every child has returned.
   Combines spawn + block for safe use as a quoted trailing expression:
     '(agents/!spawn-ask \"do X and return the result\")
   A child's lifecycle return fills its result slot; children do not need
   to send the same result separately."
  ([arg]
   (assert-agent-context! "spawn-ask")
   (if (vector? arg)
     (do
       (when (empty? arg)
         (throw (ex-info "spawn-ask: empty spawn spec list" {})))
       (let [me *current-handle*
             ;; Validate the complete batch before registration. If a registry
             ;; race still makes preparation fail, prepare-multi-spawns! rolls
             ;; back every child registered by this attempt.
             normalized (mapv normalize-spawn-from-multi-spec arg)
             prepared (prepare-multi-spawns! normalized)
             children (mapv :handle prepared)
             edge-id (create-edge! me children)]
         ;; Prepared spawn slots belong to the registered initial lifecycle;
         ;; claim them before any child can run or return.
         (doseq [{:keys [handle]} prepared]
           (claim-slot! edge-id handle (node-generation handle)))
         (doseq [child prepared]
           (start-prepared-spawn! child))
         (block-for-message)))
     (spawn-ask (default-spawn-agent "spawn-ask") arg nil)))
  ([a b]
   (if (compiled-agent? a)
     (spawn-ask a b nil)
     (spawn-ask (default-spawn-agent "spawn-ask") a b)))
  ([agent prompt handle-name]
   (assert-agent-context! "spawn-ask")
   (let [me *current-handle*
         {:keys [handle] :as prepared}
         (prepare-spawn* agent prompt handle-name)
         edge-id (create-edge! me [handle])]
     (claim-slot! edge-id handle (node-generation handle))
     (start-prepared-spawn! prepared)
     (block-for-message))))

;; =============================================================================
;; Namespace maps
;; =============================================================================

(def blocking-namespace
  "Future-only blocking namespace.
   Injected into env by future*; unavailable outside futures."
  {:short-docs "Future-only blocking helpers: await, await-all, pmap, completion-promise, send-await."
   :docs {:guide "BLOCKING — Future-only blocking primitives.

  (blocking/await fut)                 — await a Spell future token (future-only)
  (blocking/await-all [f1 f2 ...])     — await multiple Spell futures (future-only)
  (blocking/pmap f coll)               — parallel map with blocking join (future-only)
  (blocking/plet [a expr1 b expr2] body) — macro; parallel let with blocking/await
  (blocking/completion-promise handle) — await token for handle completion (future-only)
  (blocking/send-await handle msg)     — capture completion, send, await (future-only)

Use from inside (future ...) orchestration code."
          }
   :detail
   {:await "(blocking/await fut) — await a Spell future token. Exposed via future-only blocking/."
    :await-all "(blocking/await-all [f1 f2 ...]) — future-only await-many helper."
    :pmap "(blocking/pmap f coll) — future-only parallel map with blocking join."
    :plet "(blocking/plet [bindings] body...) — macro; parallel let using blocking/await."
    :completion-promise "(blocking/completion-promise handle) — future-only completion token capture. Lifecycle failures resolve to tagged :spell/child-failure data; nil is a successful nil result."
    :send-await "(blocking/send-await handle msg) — future-only capture->send->await helper. Lifecycle failures resolve to tagged :spell/child-failure data."}
   :await blocking-await
   :await-all blocking-await-all
   :pmap blocking-pmap
   :completion-promise completion-promise
   :send-await send-await})

(def agents-namespace
  "Agent communication namespace — effect-guarded (trailing expression only)."
  {:short-docs "Inter-agent communication: spawn, !ask, send, reply."
   :docs {:guide "AGENTS — Inter-agent communication (effect namespace).

  (agents/spawn prompt)         — start background agent using the current agent
  (agents/spawn prompt :handle-name) — same, with explicit handle name
  (agents/spawn agent prompt)   — start background agent with explicit compiled agent
  (agents/spawn agent prompt :handle-name) — explicit compiled agent + explicit handle name
  (agents/send target message)     — send message (usually a string) to target
  (agents/reply msg-map message)   — reply to msg-map, which must contain :from
  (agents/!ask target message)     — send message to target, block for reply
  (agents/!ask target)             — poke target without message, block for reply
  (agents/!ask [a b c])            — multi-target: poke all, wake when all complete
  (agents/!reply-ask msg-map message)   — reply to msg-map, block for next message
  (agents/!spawn-ask prompt) — spawn with the current agent, block until completion
  (agents/!spawn-ask prompt :handle-name) — same, with explicit handle name
  (agents/!spawn-ask agent prompt) — spawn with explicit compiled agent, block until completion
  (agents/!spawn-ask [[agent prompt] [agent prompt :name] ...]) — spawn many, wait for all completions (no ask wakeup poke)
  (agents/!spawn-ask [prompt-a prompt-b ...]) — spawn many with the current agent, wait for all completions (no ask wakeup poke)
  (agents/!sleep)                  — go back asleep on retained outgoing edges
  (agents/cancel edge-id)          — cancel one of your outgoing edges (targets keep running)
  (agents/status)                  — your state plus incoming/outgoing edge summary
  (agents/status handle)           — another handle's awake/asleep/finished state
  (agents/graph)                   — read-only snapshot of nodes and hyperedges
  (agents/out-edges)               — your outgoing edges, target slots, collected outcomes
  (agents/in-edges)                — edges in which you hold a (possibly pending) result slot
  (agents/current-handle)          — your handle
  (agents/parent-handle)           — handle of agent that spawned you (nil if you are main)
  (agents/send-msg-fn f handle)    — low-level / not recommended

Waiting model: each !ask/!spawn-ask creates one outgoing wait edge with a
result slot per target. An edge completes when every slot is filled by a
reply or lifecycle return; completion wakes the asker once with the report.
A plain send wakes its recipient but never creates or answers an edge. If an
unrelated message wakes you while you still wait on earlier edges, handle it
and call (agents/!sleep) to keep waiting on the retained edges.

Use (!describe agents :fn-name) for detailed docs on any function.

Special handles:
  :main — the initial agent (entry point). Always present.
  :user — the human operator (only present in interactive terminal sessions).
  Check (globals/get :roles) to see if :user is available before asking.

Messages arrive as def bindings: (def msg-N {:from sender :body val}).
Reply using Spell code, not raw natural language.
Agents other than :main persist after returning; a later message can wake them for another turn.

Message preemption: if another agent sends you a message while your response
is in flight, the message is appended as an extension and your trailing
expression becomes inert. A (think \"[preempted or awakened by msg-N]\")
annotation precedes the message def. 'Preempted' means your trailing expression
did not fire; 'awakened' means you were sleeping and the message woke you.
You get a new turn with the incoming message in scope.
You may then re-run the trailing expression from your previous turn.

All agents/ calls are effect functions — quote them in the trailing expression.
Check (globals/get :roles) to discover available agents.

Common mistakes:

1. agents/send and passing turn when expecting a reply: this ends conversation, instead use agents/!ask
2. agents/reply and passing turn: same problem
3. agents/!ask followed by additional expressions: these do not evaluate, instead put them first
4. hallucinating handles: use (agents/parent-handle), :user, :main, or look up (!print (globals/get :roles)) (if globals/ available)
5. calling agents/* outside the quoted trailing expression (for example: (def h (agents/current-handle))); effect calls must run in trailing expression code
6. agents/send argument order: it is (agents/send target message), consistent with (agents/!ask target message).

In examples, ▌ marks cursor position in a completion. It is doc-only; do not type it into code.

Multi-part example:

1. Main: spawn a summarizer, keep working, then block with !ask.
  ;; turn 1: start child + continue your own CoT
  ...▌'(do (agents/spawn
         \"You are a summarizer. Read long-file.txt, then reply to my actionable request with the summary.\"
         :summarizer)
       (!extend))
  ;; next turn:
  ... ▌(think \"...\")(think \"Ok, I'll wait for summarizer now\")'(agents/!ask :summarizer)
  ;; main blocks until child responds

2. Summarizer child: reply to the request that created the wait edge.
  ...(quine prompt \"You are a summarizer. Read long-file.txt, then reply to my actionable request with the summary.\")
  ▌'(!call-now file-contents (io/read-lines \"long-file.txt\"))
  ;; next turn
  ...(def file-contents \"...\")
  (def msg-0 {:from :main :expects-response true})
  ▌(def summary \"...\")
  '(agents/reply msg-0 summary)
  ;; reply fills the request slot; the child turn then ends

3. Main: use !reply-ask to clarify and keep the conversation open.
  ...'(agents/!ask :summarizer)
  (def msg-0 {:from :summarizer :body {...}})
  (think \"I have a question about the summary.\")
  ▌'(agents/!reply-ask msg-0 \"What is the...\")
  ;; child awakens; main blocks for child's response

"
          }
   :detail
   {:!ask
    "Request-reply communication primitive. Three forms:

(agents/!ask target msg) — send msg to target, block for reply.
  target: keyword handle (:seller, :spawn-42, :main)
  msg: any value
  Recipient sees (def msg-N {:from your-handle :body msg :expects-response true}).
  Your next turn receives the reply as a def binding.

(agents/!ask target) — poke target (wake it) and block.
  Sends (def msg-N {:from your-handle :expects-response true}) — no :body.
  Use to wait for a specific agent to respond.

(agents/!ask [a b c]) — multi-target ask.
  Pokes all targets, wakes when all have completed.
  Use for fan-out where you need all results.

Every form wakes its targets. The wait-edge ordering rule prevents an
all-asleep cycle introduced by the communication topology.
Code after ask is dead code — ask blocks and triggers a new turn.

Example — multi-turn conversation:
  '(do (agents/spawn \"You are a seller.\" :seller)
       (agents/!ask :seller 100))
  ;; next turn: (def msg-0 {:from :seller :body 250})
  '(agents/!ask :seller 150)
  ;; ...until one side uses reply to end

Example — fan-out, wait for all:
  '(do (def a (agents/spawn \"compute X\"))
       (def b (agents/spawn \"compute Y\"))
       (agents/!ask [a b]))
  ;; next turn: msg with :body [{:from a :body result-a} {:from b :body result-b}]

Message preemption: if another agent sends you a message while your response
is in flight, the message is appended as an extension. Your trailing expression
(e.g. this ask) becomes inert — it does not fire. A think annotation marks
the event. You get a new turn with the incoming message in scope.
Re-evaluate and re-issue if still appropriate.

  ...▌
  '(agents/!ask :B \"hello\")
  ;; agent C sends a message before your ask fires; your completion becomes:
  ...'(agents/!ask :B \"hello\") (think \"[preempted or awakened by msg-0]\")
  (def msg-0 {:from :C :body \"urgent\"})
  '(!llm-self (edit-reopen completion))  ;; ask became inert data — it did not fire"

    :!reply-ask
    "Reply to a received message and block for the next response.
Keeps the conversation open by turning your reply into a new reverse request.

(agents/!reply-ask msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

The sender's next turn sees one actionable message:
(def msg-N {:from your-handle :body value :expects-response true :edge-id ...}).
They should answer that exact message with (agents/reply msg-N response).
Your next turn receives their response as a new def binding.

If msg is already a singleton completion report from an earlier !ask, there
is no old live slot to fill; !reply-ask directly creates the new reverse
request. A multi-target completion report has several senders and must not be
passed to !reply-ask—choose one target and start a new !ask instead.

Example (from a spawned agent's perspective):
  ;; received (def msg-0 {:from :main :body 100 :expects-response true})
  '(agents/!reply-ask msg-0 250)
  ;; :main receives msg-1 carrying 250 and a live reverse edge
  ;; :main: '(agents/reply msg-1 150)
  ;; your next turn receives (def msg-2 {:from :main :body 150 ...})"

    :reply
    "Reply to a received message (fire-and-forget). Ends the conversation from your side.

(agents/reply msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

Does not block. When msg has :expects-response true (as !ask requests do),
reply fills your result slot in its edge; the asker wakes when the edge
completes (all slots filled). A stale, duplicate, or cancelled request is a
no-op. A singleton completion report has an old :edge-id but no
:expects-response marker, so reply treats it as a normal message and sends a
plain reply to :from. Multi-target completion reports cannot be replied to as
one message; choose a specific target.
Use !reply-ask instead when you want to continue back-and-forth.

Example:
  ;; received (def msg-0 {:from :main :body \"final offer: 200\" :expects-response true})
  '(agents/reply msg-0 \"accepted\")
  ;; :main's next turn sees (def msg-N {:from :seller :body \"accepted\"})"

    :send
    "Send a value to a target handle with auto-tagged sender.
The recipient sees (def msg-N {:from your-handle :body val}).

(agents/send target value)
  target: keyword handle
  value: any value

If send is your trailing expression, the message is sent and your turn ends.
To continue after sending, use a trailing do with extend:
  '(do (agents/send target value) (!extend))
For request-reply conversations, a more common pattern is:
  '(agents/!ask target value)

Example (from a spawned child):
  '(agents/send (agents/parent-handle) 42)"

    :spawn
    "Start an agent in a background future. Returns its handle immediately.

(agents/spawn prompt)
(agents/spawn prompt :name)
(agents/spawn agent prompt)
(agents/spawn agent prompt :name)
  agent: explicit compiled agent (for example workers/helper)
  prompt: string prompt for the child agent
  :name: optional keyword handle (e.g. :seller). Default: auto-generated :spawn-N.
Include instructions to the child LLM in its prompt, usually not by sending a message.
Natural-language prompts are wrapped into an init program automatically.
Strings that already start with '(' are treated as init programs directly.
If you have an explicit compiled agent, pass it as the first argument:
  '(agents/spawn workers/helper \"Do X.\")
  '(agents/spawn \"Do X.\")                  ; uses current agent

The child runs independently with its own handle and can send messages to you or other agents.

Example:
  '(do (agents/spawn \"You negotiate prices.\" :seller)
       (agents/!ask :seller 100))"

    :!spawn-ask
    "Spawn child agent(s), create a completion edge, and block until it completes.
Combines spawn + block. One-shot delegation pattern.
The child's lifecycle return fills its result slot; the child does not
need to send the same result separately.
Lifecycle failures fill the slot with reader-safe tagged
{:spell/child-failure true ...} data; a successful nil result remains nil.

(agents/!spawn-ask prompt)
(agents/!spawn-ask prompt :name)
(agents/!spawn-ask agent prompt)
(agents/!spawn-ask agent prompt :name)
  agent: explicit compiled agent (for example workers/helper)
  prompt: string or wrap-cat
  :name: optional keyword handle (like agents/spawn)

Your next turn sees (def msg-N {:from child-handle :body result}).

(agents/!spawn-ask [[agent-a prompt-a] [agent-b prompt-b :b] ...])
  Each entry mirrors agents/spawn arities: [agent prompt], [agent prompt :name], or [prompt :name].
  Non-vector entries are treated as prompt-only entries with the current agent:
  (agents/!spawn-ask [prompt-a prompt-b prompt-c])
  Spawns all children concurrently, then waits for all completions.
  Unlike (agents/!ask [targets]), this does NOT send wakeup/poke messages to targets.
  Your next turn sees :body as a vector of {:from child-handle :body result}.

Example:
	  '(agents/!spawn-ask \"Compute 6*7 and return the result.\")
	  ;; next turn: (def msg-0 {:from :spawn-42 :body 42 :edge-id 3})"

    :!sleep "(agents/!sleep) — go asleep on retained outgoing edges.

No message is sent and no new edge is created. Allowed only when the caller
has a pending outgoing edge created strictly after its newest pending
incoming edge; otherwise throws without changing the wait graph.
Use after being awakened by an unrelated message while still waiting on
earlier !ask/!spawn-ask edges."
    :cancel "(agents/cancel edge-id) — cancel one of the caller's pending outgoing edges.

Detaches the waiting relationship but does not stop or interrupt the edge's
targets. Returns a summary of the cancelled edge; throws when the edge does
not exist, is not pending, or the caller is not its source."
    :status "(agents/status) — inspect the caller's state and concise incoming/outgoing edge summaries.
(agents/status handle) — inspect another handle's awake/asleep/finished state and lifecycle generation."
    :graph "(agents/graph) — read-only snapshot of the wait graph: nodes with state/generation and pending hyperedges with slot status."
    :out-edges "(agents/out-edges) — inspect the caller's pending outgoing edges, target slots, and collected outcomes."
    :in-edges "(agents/in-edges) — inspect all live edges containing the caller's target slot, including slots already filled while other targets remain pending."
    :current-handle
    "Returns your handle as a keyword.

(agents/current-handle)

:main for the initial agent, :spawn-N for auto-named spawned agents,
or the keyword you specified when spawned (e.g. :seller)."

    :parent-handle
    "Returns the handle of the agent that spawned you, or nil if main.

(agents/parent-handle)

Use in spawned agents to send results back to the parent:
  '(agents/send (agents/parent-handle) result)"

    :send-msg-fn
    "Low-level fire-and-forget send. Most agents should use send, !ask, or !reply-ask instead.

(agents/send-msg-fn f handle)
  f: function taking a raw completion string, returning a modified string
  handle: target keyword handle

Internal plumbing for the communication layer."}
   :send send
   :reply reply
   :!reply-ask reply-ask
   :!ask ask-builtin
   :spawn spawn
   :!spawn-ask spawn-ask
   :!sleep sleep!
   :cancel cancel-edge
   :status agent-status
   :graph graph-snapshot
   :out-edges out-edges
   :in-edges in-edges
   :current-handle (fn [] *current-handle*)
   :parent-handle (fn []
                    (let [epoch (require-active-epoch! "agents/parent-handle")]
                      (:parent-handle (entry-for-epoch *current-handle* epoch))))
   :send-msg-fn send-msg-fn})
