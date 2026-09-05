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
(def ^:dynamic *computation-future?* false)
(def ^:dynamic *computation-owner* nil)
(defn computation-owner []
  (if *computation-future?* *computation-owner*
    (when *current-handle*
      {:handle *current-handle* :completion (:completed (coordinator/agent *current-handle*))})))
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
  (append-forms-macro
    (eval/context-forms
      [{:form (list 'think (str "[preempted or awakened by " name "]"))}
       {:name name :value value}
       {:form (list 'quote (list '!extend))}])))


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

(defn box
  ([handle completion-source inside-fn]
   (box handle completion-source inside-fn (:completed (coordinator/agent handle))))
  ([handle completion-source inside-fn completion]
  (let [raw (parse/balance-parens (resolve-completion-source completion-source))
        runner (Thread/currentThread)]
    (coordinator/acquire! handle runner completion)
    (try
      (record-last-raw! handle raw)
      (binding [*current-handle* handle *current-raw* raw]
        (inside-fn raw))
      (finally (coordinator/release! handle runner))))))

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
    (let [a (coordinator/agent handle)
          completion (:completed a)
          raw (:last-raw @(:execution a))]
      (try
        (future
          (try
            ;; Wait outside the root box: the earlier lifecycle has unwound.
            (await-message! handle)
            (run-root-box handle (or raw "") (make-awake-fn handle eval-fn) eval-fn completion)
            (catch Throwable e
              (when-not (= :coordinator-closed (:type (ex-data e)))
                (coordinator/retire! handle completion (child-failure handle :startup e))
                (throw e)))))
        (catch Throwable e
          ;; Submission can fail after the preceding lifecycle rotated its
          ;; completion. Retire the unstarted next lifecycle, not the old one.
          (coordinator/retire! handle completion (child-failure handle :startup e))
          (throw e))))))

(defn run-root-box
  ([handle completion-source inside-fn eval-fn]
   (run-root-box handle completion-source inside-fn eval-fn (:completed (coordinator/agent handle))))
  ([handle completion-source inside-fn eval-fn completion]
  (let [entered? (atom false)]
    (try
      (let [resolved (resolve-completion-source completion-source)
            value (box handle resolved (fn [raw] (reset! entered? true) (inside-fn raw)) completion)]
        (when (finish-agent! handle completion value) (start-orphan! handle eval-fn))
        value)
      (catch Throwable e
        (let [failure (child-failure handle (if @entered? :lifecycle :completion-source) e)]
          (if (instance? Error e)
            (coordinator/retire! handle completion failure)
            (when (finish-agent! handle completion failure) (start-orphan! handle eval-fn))))
        (throw e))))))

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
(defn- edge-summary [edge] (dissoc edge :result-promise))
(defn out-edges [] (mapv edge-summary (coordinator/outgoing (coordinator/snapshot) *current-handle*)))
(defn in-edges []
  (->> (vals (:edges (coordinator/snapshot)))
       (filter #(contains? (:slots %) *current-handle*))
       (sort-by :created-seq) (mapv edge-summary)))
(defn agent-status
  ([] (assoc (agent-status *current-handle*) :out-edges (out-edges) :in-edges (in-edges)))
  ([handle]
   (if-let [a (coordinator/agent handle)]
     (assoc (select-keys a [:status :generation]) :handle handle)
     (throw (ex-info "Handle not registered" {:handle handle})))))
(defn graph-snapshot []
  (let [s (coordinator/snapshot)]
    {:nodes (into {} (map (fn [[h a]] [h (select-keys a [:status :generation])]) (:agents s)))
     :edges (into {} (map (fn [[id edge]] [id (edge-summary edge)]) (:edges s)))}))
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
(defn- request-edge [targets value supplied?]
  (assert-agent-context! "ask")
  (coordinator/request! *current-handle*
                        (if (sequential? targets) (vec targets) [targets])
                        supplied? value))

(defn ask
  "Immediately register and deliver a request, returning its edge ID."
  ([targets] (request-edge targets nil false))
  ([targets value] (request-edge targets value true)))

(defn ask-builtin
  "Convenience wrapper: request now, then wait on current coordination state."
  ([targets] (ask targets) (wait!))
  ([targets value] (ask targets value) (wait!)))
(defn- request-result-token [handle msg supplied?]
  (when-not *current-handle*
    (throw (ex-info "blocking/request requires a source agent" {})))
  (let [result (promise)
        id (coordinator/request! *current-handle* [handle] supplied? msg result
                                 (when *computation-future?* (:completion *computation-owner*)))]
    (assoc (completion-token result) :edge-id id :request-result true)))

(defn request-token
  "Create a tracked agent request and return a token for its one result."
  ([handle] (request-result-token handle nil false))
  ([handle msg] (request-result-token handle msg true)))

(defn- assert-computation-wait! [caller]
  ;; Function values can escape a future's namespace into an agent program.
  ;; Test the live runner rather than trusting namespace visibility or bindings
  ;; inherited by a host future, whose thread does not own that runner.
  (when (and *current-handle*
             (identical? (Thread/currentThread) (:runner (coordinator/agent *current-handle*))))
    (throw (ex-info (str caller " cannot block an agent runner; use !ask-await")
                    {:type :agent-blocking-call :handle *current-handle*}))))

(defn future-value
  "Resolve a computation or request token. Request outcomes wrap successful
   values so cancellation cannot be confused with a caller's ordinary map."
  [fut]
  (when-not (eval/spell-future? fut)
    (throw (ex-info "Await requires a future" {:value fut})))
  (let [result (deref (:ref fut))]
    (if (:request-result fut)
      (case (:status result)
        :completed (:value result)
        :cancelled (throw (ex-info "Agent request was cancelled"
                                   {:type :request-cancelled :edge-id (:edge-id result)}))
        :closed (throw (ex-info "Coordinator is closed" {:type :coordinator-closed})))
      result)))

(defn blocking-await
  "Await helper for Spell futures (exposed via future-gated blocking/ namespace)."
  [fut]
  (assert-computation-wait! "blocking/await")
  (when-not (eval/spell-future? fut)
    (throw (ex-info "blocking/await requires a future" {:value fut})))
  (future-value fut))

(defn blocking-await-all
  "Await a collection of Spell futures (exposed via future-gated blocking/ namespace)."
  [futures]
  (assert-computation-wait! "blocking/await-all")
  (when-not (sequential? futures)
    (throw (ex-info "blocking/await-all: argument must be a collection" {:got futures})))
  (mapv (fn [f]
          (when-not (eval/spell-future? f)
            (throw (ex-info "blocking/await-all: all elements must be futures" {:got f})))
          (future-value f))
        futures))

(defn blocking-pmap
  "Parallel map over Spell futures (exposed via future-gated blocking/ namespace)."
  [f coll]
  (assert-computation-wait! "blocking/pmap")
  (let [owner (computation-owner)
        futures (mapv (fn [item]
                        (completion-token
                          (clojure.core/future
                            (binding [*computation-future?* true *computation-owner* owner
                                      *current-raw* nil]
                              (eval/invoke-fn f [item])))))
                      coll)]
    (blocking-await-all futures)))


(defn send-await [handle msg]
  (assert-computation-wait! "blocking/send-await")
  (blocking-await (request-token handle msg)))
(defn start-box
  ([handle eval-fn initial] (start-box handle eval-fn initial nil))
  ([handle eval-fn initial parent]
   (register! handle parent :finished)
   (record-last-raw! handle initial)
   (start-orphan! handle eval-fn)
   handle))
(defn- validate-spawn-agent! [agent handle]
  (when-not (compiled-agent? agent)
    (throw (ex-info "agents/spawn requires a compiled agent (leaf-llm has no lifecycle)" {:handle handle})))
  agent)
(defn- launch-spawn! [{:keys [agent prompt handle completion]}]
  (let [completion (or completion (:completed (coordinator/agent handle)))]
    (try
      (future
        (try
          (let [value (binding [*computation-future?* false *current-handle* nil]
                        (agent prompt handle))]
            ;; A normal compiled agent already finished and rotated completion.
            ;; A direct return has no persistent runner, so retire that handle.
            (coordinator/retire! handle completion value)
            value)
          (catch Throwable e
            (coordinator/retire! handle completion (child-failure handle :startup e))
            (throw e))))
      handle
      (catch Throwable e
        (coordinator/retire! handle completion (child-failure handle :startup e))
        (throw e)))))
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
        id (coordinator/spawn-request! *current-handle* specs)
        prepared (mapv #(assoc % :completion (:completed (coordinator/agent (:handle %)))) specs)]
    (doseq [[index spec] (map-indexed vector prepared)]
      (try
        (launch-spawn! spec)
        (catch Throwable e
          ;; Already-launched children retain their owners. Every registration
          ;; that cannot launch receives a terminal result and is removed.
          (doseq [{:keys [handle completion]} (subvec prepared index)]
            (coordinator/retire! handle completion (child-failure handle :startup e)))
          (throw e))))
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
  {:short-docs "Future-only blocking helpers: await, await-all, pmap, request, send-await."
   :docs {:guide "BLOCKING — Future-only blocking primitives.

  (blocking/await fut)                 — await a Spell future token (future-only)
  (blocking/await-all [f1 f2 ...])     — await multiple Spell futures (future-only)
  (blocking/pmap f coll)               — parallel map with blocking join (future-only)
  (blocking/plet [a expr1 b expr2] body) — macro; parallel let with blocking/await
  (blocking/request handle) — send a bodyless tracked poke and return its result token
  (blocking/request handle msg) — send a tracked request body (including explicit nil), return its token
  (blocking/send-await handle msg)     — send a tracked request, await its result (future-only)

Use from inside (future ...) orchestration code."
          }
   :detail
   {:await "(blocking/await fut) — await a Spell future token. Exposed via future-only blocking/."
    :await-all "(blocking/await-all [f1 f2 ...]) — future-only await-many helper."
    :pmap "(blocking/pmap f coll) — future-only parallel map with blocking join."
    :plet "(blocking/plet [bindings] body...) — macro; parallel let using blocking/await."
    :request "(blocking/request handle), (blocking/request handle msg) — future-only tracked request token. One argument sends a bodyless poke; two arguments send the supplied body, including explicit nil. Lifecycle failures resolve to tagged :spell/child-failure data; nil is a successful nil result."
    :send-await "(blocking/send-await handle msg) — future-only request->await helper. Lifecycle failures resolve to tagged :spell/child-failure data."}
   :await blocking-await
   :await-all blocking-await-all
   :pmap blocking-pmap
   :request request-token
   :send-await send-await})

(def agents-namespace
  "Effect namespace for immediate communication and explicit waiting."
  {:short-docs "Agents: spawn, ask, spawn-ask, !wait, send, reply, cancel, inspection."
   :docs
   {:child-prompts "For ordinary child tasks, pass a string literal or a def-bound string to spawn/spawn-ask. A quine binding holds source; wrap-cat builds a program prefix. Use those when deliberately constructing a program, rather than naming task text."
    :waiting "For message handling, put !wait/!sleep/!ask/!spawn-ask/!reply-ask or !ask-await last in the quoted trailing expression. Read received msg-N bindings in the resumed turn. A wait returns the whole resumed computation's value, so capturing it as a message or adding parentheses, ((agents/!wait)), misuses that value. Synchronous !llm-self result capture remains available."
    :receipts "On waking, establish which required actions executed before continuing dependent work. An incoming request can supersede your own proposed request while its source and local definitions remain. When dispatch must precede another step, capture immediate ask with a fresh name, e.g. '(!call-now question-edge (agents/ask :reviewer question)), then wait separately. Check actual captures, received reports, and out-edges/status before dependent replies, waits, or return. A proposed sent flag is not execution evidence. Resolve uncertain execution before retrying; complete an interrupted prerequisite first. See (!describe agents) for examples."
    :returning "Returning fills all still-unanswered claimed request slots with the same value and abandons unfinished outgoing collections; targets keep running. Explicitly reply to any request whose answer differs from your final return value. After a wake and before returning, inspect your pending incoming slots and send any such reply that has not executed. Receiving a peer's answer does not establish that your own reply to that peer ran. Before waiting, establish that work remains to collect and inspect uncertain obligations. A refused wait is an error: recover by inspecting current state and revising the program. Return when done."
    :futures "Create a communication future once in a quoted trailing expression and retain it with !call-now for later joins. Inside it, blocking/request creates a token and blocking/await collects it; !ask-await resumes the enclosing agent with messages. (!describe agents) shows the complete pattern."
    :guide "AGENTS — Communication controlled by your program.

Use agents/ operations in the quoted trailing expression. Each operation takes
effect immediately, including between nested self-calls.

Starting work and retaining results

Ordinary child assignments are strings:
  (def review-task (str \"Review docs/api.md for \" topic \". Return findings.\"))
  '(!call-now review-edge (agents/spawn-ask review-task)
              examples-edge (agents/spawn-ask \"Review the examples. Return findings.\"))
The injected result bindings retain the actual edge IDs on the next turn.
A quine binding holds its source form; wrap-cat constructs a program prefix.
Use ordinary strings when you mean task text. Deliberate program prefixes must
have the completion-wrapper structure described by the core language guide.

(agents/ask target value) creates a request and returns its edge ID.
(agents/ask target) and (agents/ask [:reviewer :tester]) send bodyless requests.
spawn starts a child without collecting its initial result; spawn-ask reserves
its result slot before launch. Prompt-only forms use your compiled agent.
Explicit forms accept a configured compiled agent; multi-spawn supports a vector
of entries, e.g. [[task-a :a] [task-b :b]], with each entry following a supported
prompt/handle or agent/prompt/handle form. Use !describe agents :spawn
or :spawn-ask for complete signatures.

One edge collects ALL target results; across separate edges, ANY completed edge
can awaken you. Other collections remain pending. Requests and plain messages
can also awaken you. To wait after doing other work:
  '(agents/!wait)
The continuation receives msg-N bindings. A single-target completion report has
:from, :edge-id and :body. A multi-target report's :body is a vector of
{:from target :body result} maps, in target order. Consume those actual values
and return the final task result when all required work is done. Capture local
calculations with !call-now if their values must survive a later continuation;
a def inside an old quoted action is not a persistent result binding.

!ask, !spawn-ask and !reply-ask perform their interaction and then wait.
When later steps depend on confirmed dispatch, capture the immediate ask with
!call-now, then wait in a later turn. The convenience !ask returns the resumed
computation's value, so it does not retain an edge ID for this purpose.
!sleep uses the same waiting primitive as !wait. For message handling, place a
wait last in the quoted trailing expression. It resumes a whole computation:
its eventual return value is that computation's result, not the next envelope.
Thus use received msg-N bindings, rather than (!call-now msg (agents/!wait)) or
((agents/!wait)). Synchronous (!call-now result (!llm-self ...)) remains useful
when you intend to capture a self-call's result.

Requests and lifecycle completion

An actionable request has :from, :expects-response true, :edge-id and optional
:body. Pass that exact message to (agents/reply msg-N answer). A plain send does
not fill a request slot. Replies to answered or cancelled requests are no-ops;
reply returns nil in those cases and after successfully filling a live slot.
!reply-ask atomically replies, creates a reverse request, then waits; a stale
request is refused without changing the coordinator.

A lifecycle return fills every remaining claimed incoming slot with the SAME
return value and cancels its unfinished outgoing collections. Explicitly reply to any request whose answer differs
from your final return value. Receiving an answer from a peer does not establish
that your own reply to that peer executed. After a wake and before returning a
different final value, inspect that incoming request. If your entry in :slots
has :status :pending, send its required reply first. A filled slot needs no
further reply even if another target keeps the edge pending; a completed or
cancelled edge is absent. Requests not yet
consumed belong to a later lifecycle. Successful
nil is a result; terminal failures carry :spell/child-failure true. Normal
return preserves the handle for later requests; startup failure retires it.
Cancelling a collection abandons its results while targets continue running.

agents/!wait and agents/!sleep observe current messages and obligations atomically. Pending results
cannot be missed. With no messages or obligations it returns nil immediately.
To suspend after consuming current messages, these communication waits require
an outgoing edge newer than every unanswered incoming edge. An external-computation wait through !ask-await uses this rule
when it has incoming obligations; with none, it may wait for external work
without an outgoing edge.
Pending-edge summaries identify requests under :id; received request messages
use :edge-id. Inspect out-edges/in-edges/status before waiting when obligations
are uncertain, and answer requests as needed. Return if the task is
complete; wait for remaining work only when the ordering permits it. A refused
wait is a recoverable error, with no suspension. Spell try/catch can handle it;
the normal evaluation-recovery path can revise the program. Inspect current
status during recovery rather than repeating the refused wait. With recovery
disabled and no handler, the lifecycle fails. Filled slots can still appear
in in-edges while another target keeps the edge pending.

Receipt and execution evidence

A message arriving during generation can replace the proposed quoted action
with a continuation before the action executes. Its old source stays visible;
preceding ordinary local definitions can still evaluate. A bare (def sent true)
therefore says nothing about whether the following send or reply executed.
The annotation [preempted or awakened by msg-N] is also used after a real wait
awakens; the annotation or old source alone does not identify what executed.

On waking, establish whether each prerequisite actually ran before continuing
operations that depend on it. Receiving a peer request does not establish that
your own request was dispatched. Complete a required interrupted request before
a dependent reply, wait, or return. When execution is uncertain, inspect first:
  '(!call-now current-obligations (agents/status))
Use actual result captures, received completion reports, and pending edge records.
An empty outgoing set alone does not exclude a completed or cancelled request.

Capture immediate operations with a fresh name for each operation:
  '(!call-now clarification-edge (agents/ask :reviewer question))
A newly injected clarification-edge binding records the returned edge ID.
  '(!call-now clarification-reply-result (agents/reply msg-2 answer))
A newly injected nil binding records that reply returned; it does not distinguish
filling a live slot from a stale no-op. If an incoming message clearly replaced
an action before it ran, reconsider that action after handling the message.
After an error or uncertain execution, inspect pending edges before issuing it
again: an effect may have run before a later batched expression failed, leaving
no result binding. Reusing a name can leave an older binding visible after the
new action was superseded. Keep fresh captures or inspect the coordinator.

Requests collected in computation futures

Create and capture the future in the quoted trailing expression so later turns
reuse the same computation. These are successive turns:
  '(!call-now worker-handle (agents/spawn \"Answer incoming arithmetic requests with integers.\" :worker))
  '(!call-now task-future (future (blocking/await (blocking/request worker-handle \"Multiply 23 by 41.\"))))
  '(!ask-await task-future)
future takes one expression; wrap multiple body forms in do. blocking/request
creates a tracked result token; blocking/await collects it inside the future.
The enclosing !ask-await resumes with a msg-N whose :from is :future and :body
is the computed value. A body with :future-await/error reports a computation
error. An unrelated message can arrive first: handle it, then
join the same captured task-future again. A future stored through a stored
reference keeps its identity. Do not recreate it to resume waiting.
Creating a future in ordinary retained source can rerun its request on later
turns. A local def inside a quoted do is not retained for a later rejoin;
!call-now captures the future for that purpose. blocking/send-await creates
and collects a NEW request; use blocking/await for an existing token.

Use (!describe agents :function) for signatures. Discover current/parent handles
and registered roles; :user exists only when the run configured user input.
"}
   :detail
   {:spawn "(agents/spawn prompt), (agents/spawn prompt handle), (agents/spawn agent prompt), (agents/spawn agent prompt handle): start without a collection; return the registered handle. Ordinary task prompts are strings."
    :ask "(agents/ask target value), (agents/ask target), (agents/ask [targets]): immediately create and deliver a request edge; return its ID, keep running. Explicit nil body differs from a bodyless request. Capacity rejection sends nothing."
    :spawn-ask "(agents/spawn-ask prompt), (agents/spawn-ask prompt handle), (agents/spawn-ask agent prompt), (agents/spawn-ask agent prompt handle), (agents/spawn-ask [specs]): register one all-target result edge before child launch; return its ID. Specs are prompt, [prompt handle], [agent prompt], or [agent prompt handle]. Rejection registers/launches no children."
    :!ask "Same arguments as ask. Register immediately, then !wait; other already-pending messages or completed collections can awaken you."
    :!spawn-ask "Same arguments as spawn-ask. Register and launch immediately, then !wait. Children return their results; no extra send is needed."
    :!wait "(agents/!wait): handle queued messages or wait on retained edges if strict ordering permits. Empty wait is a no-op. A resumed wait returns the whole continuation value; for message handling keep it in tail position and consume msg-N bindings. Refused ordering never creates a hidden passive wait."
    :!sleep "(agents/!sleep): same primitive as !wait; resume retained collections after an unrelated wakeup."
    :send "(agents/send target value): send a plain message and awaken target. Does not fill result slots."
    :reply "(agents/reply message value): answer your slot of an actionable request exactly once. Stale/duplicate/cancelled requests are no-ops. A singleton completion report replies by plain send; aggregate reports require choosing a target."
    :!reply-ask "(agents/!reply-ask message value): atomically answer and create a reverse request, then wait. Requires a singleton sender; a stale actionable request is refused without coordinator changes."
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
