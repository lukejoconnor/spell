(ns spell.runtime
  "Stackful agent execution interacting with a run-local coordinator. Inbox
   receipt remains after generation, before evaluating the returned program."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [spell.coordinator :as coordinator]
            [spell.eval :as eval]
            [spell.inbox :as inbox]
            [spell.parse :as parse]
            [spell.trace :as trace]))

(def ^:dynamic *current-handle* nil)
(def ^:dynamic *current-raw* nil)
(def ^:dynamic *current-eval-fn* nil)
(def ^:dynamic *default-spawn-agent* nil)
(defn- inbox-aware-eval-fn? [f] (true? (:spell/inbox-aware (meta f))))
(declare box run-root-box block-for-message sleep! fill-slot! ask-builtin)

(def register! coordinator/register!)
(defn handle? [handle] (boolean (coordinator/agent handle)))
(defn record-last-raw! [handle raw]
  (when-let [execution (:execution (coordinator/agent handle))]
    (swap! execution assoc :last-raw raw))
  nil)

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

(declare throwable->completion-exception)

(def ^:private completion-failure-max-depth 8)
(def ^:private completion-failure-max-items 100)

(defn- reader-round-trippable?
  [value]
  (try
    (= [value] (vec (parse/read-all (pr-str value))))
    (catch Throwable _ false)))

(defn- diagnostic-value
  "Convert host values to reader-safe plain data for completion messages."
  ([value]
   (diagnostic-value value 0))
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
           (map (fn [[k v]] [(diagnostic-value k (inc depth))
                              (diagnostic-value v (inc depth))]))
           (take completion-failure-max-items value))

     (vector? value)
     (mapv #(diagnostic-value % (inc depth))
           (take completion-failure-max-items value))

     (set? value)
     (set (map #(diagnostic-value % (inc depth))
               (take completion-failure-max-items value)))

     (list? value)
     (apply list (map #(diagnostic-value % (inc depth))
                      (take completion-failure-max-items value)))

     (sequential? value)
     (mapv #(diagnostic-value % (inc depth))
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
            :data (diagnostic-value (ex-data throwable) (inc depth))}
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


(defn- envelope-macro [{:keys [message macro]}]
  (or macro (create-msg (symbol (gensym "msg-")) message)))

(defn make-awake-fn [handle eval-fn]
  (fn [raw]
    (let [before-awake (:spell/before-awake (meta eval-fn))
          after-awake (:spell/after-awake (meta eval-fn))]
      (when before-awake (before-awake))
      (try
        (let [macros (mapv envelope-macro (coordinator/drain! handle))
              transformed (if (and (seq macros) (not (inbox-aware-eval-fn? eval-fn)))
                            (inbox/materialize-inbox-raw raw macros {:builtins eval/core-builtins}) raw)]
          (record-last-raw! handle transformed)
          (binding [*current-eval-fn* eval-fn]
            (if (inbox-aware-eval-fn? eval-fn) (eval-fn raw macros) (eval-fn transformed))))
        (finally (when after-awake (after-awake)))))))

(defn- await-message! [handle]
  ;; The signal is a notification adapter. Mailbox and run closure are authoritative.
  (let [{:keys [mailbox signal]} (coordinator/agent handle)]
    (when (empty? mailbox) @signal)
    (when-not (coordinator/open?)
      (throw (ex-info "Coordinator is closed" {:type :coordinator-closed})))))

(defn- make-asleep-fn [handle eval-fn]
  (fn [raw]
    (await-message! handle)
    (box handle raw (make-awake-fn handle eval-fn))))

(defn box [handle completion-source inside-fn]
  (let [raw (parse/balance-parens (resolve-completion-source completion-source))
        runner (Thread/currentThread)]
    (coordinator/acquire! handle runner)
    (try
      (record-last-raw! handle raw)
      (binding [*current-handle* handle *current-raw* raw]
        (inside-fn raw))
      (finally (coordinator/release! handle runner)))))

(defn finish-agent!
  ([handle result] (finish-agent! handle (:completed (coordinator/agent handle)) result))
  ([handle completion result]
   (let [outcome (coordinator/finish! handle completion result)]
     (when (seq (:cancelled outcome))
       (trace/record-warning!
         (str "Agent " handle " finished with unfinished outgoing edges; result collection abandoned.")
         {:handle handle :detached-edges (mapv #(select-keys % [:id :targets]) (:cancelled outcome))}))
     outcome)))

(defn- start-orphan! [handle eval-fn]
  (when (coordinator/open?)
    (let [raw (:last-raw @(:execution (coordinator/agent handle)))]
      (future
        (try
          ;; Wait outside the root box: the earlier lifecycle has unwound.
          (await-message! handle)
          (run-root-box handle (or raw "") (make-awake-fn handle eval-fn) eval-fn)
          (catch Exception e
            (when-not (= :coordinator-closed (:type (ex-data e))) (throw e))))))))

(defn run-root-box [handle completion-source inside-fn eval-fn]
  (let [completion (:completed (coordinator/agent handle))
        entered? (atom false)]
    (try
      (let [resolved (resolve-completion-source completion-source)
            value (box handle resolved (fn [raw] (reset! entered? true) (inside-fn raw)))]
        (when (finish-agent! handle completion value) (start-orphan! handle eval-fn))
        value)
      (catch Exception e
        (when (finish-agent! handle completion
                            (child-failure handle (if @entered? :lifecycle :completion-source) e))
          (start-orphan! handle eval-fn))
        (throw e)))))

(defn -send! [handle macro] (coordinator/send! handle {:macro macro}))
(defn send-msg-fn [macro handle] (-send! handle macro) nil)
(defn send [target value]
  (coordinator/send! target {:message {:from *current-handle* :body value}}))
(defn actionable-request-live? [msg]
  (let [edge (get-in (coordinator/snapshot) [:edges (:edge-id msg)])]
    (boolean (and (:expects-response msg) (= (:from msg) (:source edge))
                  (= :pending (get-in edge [:slots *current-handle* :status]))))))
(defn- reply-target [caller msg]
  (let [target (:from msg)]
    (when (or (nil? target) (sequential? target))
      (throw (ex-info (str caller ": requires a singleton sender") {:message msg})))
    target))
(def fill-slot! coordinator/fill!)
(defn reply [msg value]
  (if (:expects-response msg)
    (do (when-not (:edge-id msg)
          (throw (ex-info "Actionable request has no edge-id" {:message msg})))
        (fill-slot! (:edge-id msg) *current-handle* value) nil)
    (send (reply-target "reply" msg) value)))
(defn cancel-edge [id] (coordinator/cancel! *current-handle* id))
(defn out-edges [] (coordinator/outgoing (coordinator/snapshot) *current-handle*))
(defn in-edges []
  (->> (vals (:edges (coordinator/snapshot)))
       (filter #(contains? (:slots %) *current-handle*))
       (sort-by :created-seq) vec))
(defn agent-status
  ([] (assoc (agent-status *current-handle*) :out-edges (out-edges) :in-edges (in-edges)))
  ([handle]
   (if-let [a (coordinator/agent handle)]
     (assoc (select-keys a [:status :generation]) :handle handle)
     (throw (ex-info "Handle not registered" {:handle handle})))))
(defn graph-snapshot []
  (let [s (coordinator/snapshot)]
    {:nodes (into {} (map (fn [[h a]] [h (select-keys a [:status :generation])]) (:agents s)))
     :edges (:edges s)}))
(defn sleep-allowed? [handle] (coordinator/sleep-allowed? (coordinator/snapshot) handle))
(defn block-for-message []
  (box *current-handle* *current-raw* (make-asleep-fn *current-handle* *current-eval-fn*)))
(defn- assert-agent-context! [caller]
  (when-not (and *current-handle* *current-raw*)
    (throw (ex-info (str caller ": requires an active agent context") {}))))
(defn wait!
  "Observe current coordination state and sleep only when a pending edge permits.
   Available messages continue immediately; an empty wait returns nil."
  []
  (assert-agent-context! "!wait")
  (let [outcome (coordinator/wait! *current-handle*)]
    (when-not (= :idle (:status outcome)) (block-for-message))))
(defn sleep! [] (wait!))
(defn reply-ask [msg value]
  (assert-agent-context! "!reply-ask")
  (reply-target "!reply-ask" msg)
  (coordinator/reply-request! *current-handle* msg value)
  (sleep!))
(defn ask
  "Immediately register and deliver a request, returning its edge ID."
  ([targets] (ask targets nil false))
  ([targets value] (ask targets value true))
  ([targets value supplied?]
   (assert-agent-context! "ask")
   (coordinator/request! *current-handle*
                         (if (sequential? targets) (vec targets) [targets])
                         supplied? value)))

(defn ask-builtin
  "Convenience wrapper: request now, then wait on current coordination state."
  ([targets] (ask targets) (wait!))
  ([targets value] (ask targets value) (wait!)))
(defn completion-promise [handle]
  (when-not (handle? handle) (throw (ex-info "Handle not registered" {:handle handle})))
  (assoc (completion-token (:completed (coordinator/agent handle))) :agent-handle handle))
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


(defn send-await [handle msg]
  (let [token (completion-promise handle)]
    (send handle msg)
    (blocking-await token)))
(defn start-box
  ([handle eval-fn initial] (start-box handle eval-fn initial nil))
  ([handle eval-fn initial parent]
   (register! handle parent)
   (record-last-raw! handle initial)
   (coordinator/dormant! handle)
   (start-orphan! handle eval-fn)
   handle))
(defn- validate-spawn-agent! [agent handle]
  (when-not (compiled-agent? agent)
    (throw (ex-info "agents/spawn requires a compiled agent (leaf-llm has no lifecycle)" {:handle handle})))
  agent)
(defn- launch-spawn! [{:keys [agent prompt handle]}]
  (let [completion (:completed (coordinator/agent handle))]
    (future
      (try
        (let [value (agent prompt handle)] (finish-agent! handle completion value) value)
        (catch Exception e
          (finish-agent! handle completion (child-failure handle :startup e))
          (throw e)))))
  handle)
(defn spawn
  ([prompt] (spawn (default-spawn-agent "spawn") prompt nil))
  ([a b] (if (compiled-agent? a) (spawn a b nil) (spawn (default-spawn-agent "spawn") a b)))
  ([agent prompt handle]
   (let [handle (or handle (keyword (gensym "spawn-")))]
     (validate-spawn-agent! agent handle)
     (register! handle *current-handle*)
     (launch-spawn! {:agent agent :prompt prompt :handle handle}))))
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


(defn prepare-spawns! [specs]
  (let [specs (mapv (fn [{:keys [agent handle-name] :as spec}]
                      (validate-spawn-agent! agent handle-name)
                      (assoc spec :handle (or handle-name (keyword (gensym "spawn-")))
                             :parent-handle *current-handle*)) specs)
        id (coordinator/spawn-request! *current-handle* specs)]
    (doseq [spec specs] (launch-spawn! spec))
    id))
(defn spawn-ask
  "Register children and their result edge before launching; return the edge ID."
  ([arg]
   (assert-agent-context! "spawn-ask")
   (if (vector? arg)
     (prepare-spawns! (mapv normalize-spawn-from-multi-spec arg))
     (spawn-ask (default-spawn-agent "spawn-ask") arg nil)))
  ([a b]
   (if (compiled-agent? a)
     (spawn-ask a b nil)
     (spawn-ask (default-spawn-agent "spawn-ask") a b)))
  ([agent prompt handle]
   (assert-agent-context! "spawn-ask")
   (prepare-spawns! [{:agent agent :prompt prompt :handle-name handle}])))

(defn spawn-ask-and-wait
  "Convenience wrapper: start the collection, then wait on current state."
  ([arg] (spawn-ask arg) (wait!))
  ([a b] (spawn-ask a b) (wait!))
  ([agent prompt handle] (spawn-ask agent prompt handle) (wait!)))

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
  "Effect namespace for immediate communication and explicit waiting."
  {:short-docs "Agents: spawn, ask, spawn-ask, !wait, send, reply, cancel, inspection."
   :docs
   {:guide "AGENTS — Immediate coordination controlled by your program.

All agents/ calls are effects: use them in the quoted trailing expression.

(agents/ask target value) creates and sends a request now, returning an edge ID.
(agents/ask target) or (agents/ask [targets]) sends bodyless requests.
(agents/spawn-ask prompt) starts a child with the current compiled agent and
returns its collection edge ID. Explicit forms accept agent/prompt/handle;
multi-spawn accepts [prompt ...] or [[agent prompt handle] ...].
(agents/!ask ...) and (agents/!spawn-ask ...) register, then call !wait.
(agents/!wait) and (agents/!sleep) use the same wait mechanism.

Example: '(do (def a (agents/spawn-ask \"Review the API.\"))
             (def b (agents/spawn-ask \"Check the examples.\"))
             (agents/!wait))

One edge collects ALL its target results. Across separate edges, ANY completed
edge awakens you; the others remain pending. Plain sends can also awaken you.
After handling an unrelated message, call !wait or !sleep to retain your waits.
Returning ends your lifecycle and cancels your unfinished outgoing collections;
targets continue running. Returning a child result fills its claimed slots.

Requests arrive as msg-N maps with :from, :expects-response true, :edge-id and
optional :body. Pass that exact message to (agents/reply msg-N result).
Duplicate/cancelled replies are no-ops. A single-target completion report has
:from/:body/:edge-id; multi-target :body is [{:from target :body result} ...].
Successful nil is a result. Terminal failures carry :spell/child-failure true.

A wait observes current messages and obligations atomically. Fast results cannot
be missed. With no incoming/outgoing obligations and no messages, it returns nil.
Otherwise sleep requires an outgoing edge newer than every pending incoming
edge. Refusal includes the obligations; answer newer requests before retrying.
Registration wakes targets and happens immediately, including when nested
!llm-self calls occur before waiting. There is no whole-turn batching.

Current receipt timing is after model generation, before evaluation. Messages
arriving in flight may preempt the pending trailing expression. They arrive as
new bindings on a continuation, as before. Put waiting at the end of a turn.

Use (!describe agents :function) for signatures. Discover handles through
(agents/current-handle), (agents/parent-handle), and registered roles. :user
exists only when the run configured user input. Never invent a handle."}
   :detail
   {:spawn "(agents/spawn prompt), (agents/spawn prompt handle), (agents/spawn agent prompt), (agents/spawn agent prompt handle): start without a collection; return the registered handle."
    :ask "(agents/ask target value), (agents/ask target), (agents/ask [targets]): immediately create and deliver a request edge; return its ID, keep running. Explicit nil body differs from a bodyless request. Capacity rejection sends nothing."
    :spawn-ask "(agents/spawn-ask prompt), (agents/spawn-ask prompt handle), (agents/spawn-ask agent prompt), (agents/spawn-ask agent prompt handle), (agents/spawn-ask [specs]): register one all-target result edge before child launch; return its ID. Specs are prompt, [prompt handle], [agent prompt], or [agent prompt handle]. Rejection registers/launches no children."
    :!ask "Same arguments as ask. Register immediately, then !wait; other already-pending messages or completed collections can awaken you."
    :!spawn-ask "Same arguments as spawn-ask. Register and launch immediately, then !wait. Children return their results; no extra send is needed."
    :!wait "(agents/!wait): handle queued messages or wait on retained edges if strict ordering permits. Empty wait is a no-op. Refused ordering never creates a hidden passive wait."
    :!sleep "(agents/!sleep): same primitive as !wait; resume retained collections after an unrelated wakeup."
    :send "(agents/send target value): send a plain message and awaken target. Does not fill result slots."
    :reply "(agents/reply message value): answer your slot of an actionable request exactly once. Stale/duplicate/cancelled requests are no-ops. A singleton completion report replies by plain send; aggregate reports require choosing a target."
    :!reply-ask "(agents/!reply-ask message value): atomically answer and create a reverse request, then wait. Requires a singleton sender."
    :cancel "(agents/cancel edge-id): detach your pending collection and return its cancelled summary. Does not stop targets or descendants."
    :status "(agents/status), (agents/status handle): inspect lifecycle status/generation; zero-arity includes your edge summaries."
    :graph "(agents/graph): inspect agent nodes and pending result edges."
    :out-edges "(agents/out-edges): inspect your pending outgoing edges and result slots."
    :in-edges "(agents/in-edges): inspect live edges containing your slot, including filled slots on a still-pending multi-target edge."
    :current-handle "(agents/current-handle): your registered handle."
    :parent-handle "(agents/parent-handle): spawning handle, or nil for the main agent."
    :send-msg-fn "(agents/send-msg-fn macro handle): low-level message-macro delivery. Use send/reply/request operations for ordinary communication."}
   :send send
   :reply reply
   :ask ask
   :!ask ask-builtin
   :!reply-ask reply-ask
   :spawn spawn
   :spawn-ask spawn-ask
   :!spawn-ask spawn-ask-and-wait
   :!wait wait!
   :!sleep sleep!
   :cancel cancel-edge
   :status agent-status
   :graph graph-snapshot
   :out-edges out-edges
   :in-edges in-edges
   :current-handle (fn [] *current-handle*)
   :parent-handle (fn [] (:parent-handle (coordinator/agent *current-handle*)))
   :send-msg-fn send-msg-fn})
