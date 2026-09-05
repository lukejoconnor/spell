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
(defn sleep! []
  (assert-agent-context! "!sleep")
  (let [outcome (coordinator/wait! *current-handle*)]
    (when-not (= :idle (:status outcome)) (block-for-message))))
(defn reply-ask [msg value]
  (assert-agent-context! "!reply-ask")
  (reply-target "!reply-ask" msg)
  (coordinator/reply-request! *current-handle* msg value)
  (sleep!))
(defn ask-builtin
  ([targets] (ask-builtin targets nil false))
  ([targets value] (ask-builtin targets value true))
  ([targets value supplied?]
   (assert-agent-context! "!ask")
   (coordinator/request! *current-handle* (if (sequential? targets) (vec targets) [targets]) supplied? value)
   (sleep!)))
(defn request-token
  "Create a tracked agent request and return a token for its one result."
  ([handle] (request-token handle nil false))
  ([handle msg] (request-token handle msg true))
  ([handle msg supplied?]
   (when-not *current-handle*
     (throw (ex-info "blocking/request requires a source agent" {})))
   (let [result (promise)
         id (coordinator/request! *current-handle* [handle] supplied? msg result)]
     (assoc (completion-token result) :edge-id id))))

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
  (blocking-await (request-token handle msg)))
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
        (let [value (binding [*computation-future?* false *current-handle* nil]
                      (agent prompt handle))]
          (finish-agent! handle completion value) value)
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
  ([arg]
   (assert-agent-context! "!spawn-ask")
   (if (vector? arg)
     (do (prepare-spawns! (mapv normalize-spawn-from-multi-spec arg)) (sleep!))
     (spawn-ask (default-spawn-agent "spawn-ask") arg nil)))
  ([a b] (if (compiled-agent? a) (spawn-ask a b nil) (spawn-ask (default-spawn-agent "spawn-ask") a b)))
  ([agent prompt handle]
   (assert-agent-context! "!spawn-ask")
   (prepare-spawns! [{:agent agent :prompt prompt :handle-name handle}])
   (sleep!)))
(def blocking-namespace
  "Future-only blocking namespace.
   Injected into env by future*; unavailable outside futures."
  {:short-docs "Future-only blocking helpers: await, await-all, pmap, request, send-await."
   :docs {:guide "BLOCKING — Future-only blocking primitives.

  (blocking/await fut)                 — await a Spell future token (future-only)
  (blocking/await-all [f1 f2 ...])     — await multiple Spell futures (future-only)
  (blocking/pmap f coll)               — parallel map with blocking join (future-only)
  (blocking/plet [a expr1 b expr2] body) — macro; parallel let with blocking/await
  (blocking/request handle) — send a tracked poke and return its result token (future-only)
  (blocking/send-await handle msg)     — send a tracked request, await its result (future-only)

Use from inside (future ...) orchestration code."
          }
   :detail
   {:await "(blocking/await fut) — await a Spell future token. Exposed via future-only blocking/."
    :await-all "(blocking/await-all [f1 f2 ...]) — future-only await-many helper."
    :pmap "(blocking/pmap f coll) — future-only parallel map with blocking join."
    :plet "(blocking/plet [bindings] body...) — macro; parallel let using blocking/await."
    :request "(blocking/request handle) — future-only tracked request token. Lifecycle failures resolve to tagged :spell/child-failure data; nil is a successful nil result."
    :send-await "(blocking/send-await handle msg) — future-only request->await helper. Lifecycle failures resolve to tagged :spell/child-failure data."}
   :await blocking-await
   :await-all blocking-await-all
   :pmap blocking-pmap
   :request request-token
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
                    (:parent-handle (coordinator/agent *current-handle*)))
   :send-msg-fn send-msg-fn})
