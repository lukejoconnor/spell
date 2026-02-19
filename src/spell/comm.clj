(ns spell.comm
  "Inter-agent communication: ask/send/spawn primitives.

   box is the universal execution primitive: it waits for a function (from
   an inbox) and applies it to a raw completion string. ask sends a message
   and blocks for reply. spawn-ask spawns an agent and blocks for its
   message. send-msg-fn is low-level fire-and-forget. Every wait wakes the target,
   preventing deadlocks."
  (:refer-clojure :exclude [send])
  (:require [spell.eval :as eval]
            [spell.parse :as parse]))

;; =============================================================================
;; Registry
;; =============================================================================

(def registry
  "Global registry: handle -> {:inbox (atom nil), :signal (atom (promise)),
                                :has-box (atom false), :default-inbox-fn fn,
                                :waiters (atom #{}), :collector (atom nil)}"
  (atom {}))

(defn register!
  "Register a handle with its default inbox fn.
   Optional parent-handle records the spawning agent."
  ([handle default-inbox-fn] (register! handle default-inbox-fn nil))
  ([handle default-inbox-fn parent-handle]
   (when (contains? @registry handle)
     (throw (ex-info "Handle already registered" {:handle handle})))
   (swap! registry assoc handle
          {:inbox             (atom nil)
           :signal            (atom (promise))
           :has-box           (atom false)
           :default-inbox-fn  default-inbox-fn
           :parent-handle     parent-handle
           :waiters           (atom #{})
           :collector         (atom nil)})))

;; =============================================================================
;; Dynamic vars
;; =============================================================================

(def ^:dynamic *current-handle*
  "Handle for the currently executing agent (set inside box)."
  nil)

(def ^:dynamic *current-raw*
  "Raw completion string for the currently executing agent (set inside box)."
  nil)

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare box notify-waiters! orphan-box!)

;; =============================================================================
;; Sleep
;; =============================================================================

(defn- make-sleep-fn
  "Create an inbox function that blocks on signal then re-enters box.
   When woken, re-enters box as non-root (parent=self).
   Loops on spurious wakes: if signal fires but inbox is still nil
   (e.g. from a -send! that was later overwritten), go back to sleep."
  [handle]
  (fn [_raw]
    (loop []
      (let [{:keys [signal]} (get @registry handle)]
        (deref @signal)
        (reset! signal (promise))
        (if @(:inbox (get @registry handle))
          (let [p (promise)]
            (deliver p *current-raw*)
            (box handle handle p))
          (recur))))))

;; =============================================================================
;; Box
;; =============================================================================

(defn box
  "Core execution primitive. Awaits completion, drains inbox, applies inbox-fn.
   Takes handle, parent-handle, and a promise that delivers the raw completion.
   Inbox functions take [raw] and return a value.
   Root detection: (not= parent-handle handle).
   Callers must seed inbox before calling box."
  [handle parent-handle completion-promise]
  (let [{:keys [inbox has-box]} (get @registry handle)]
    (when-not inbox
      (throw (ex-info "Handle not registered" {:handle handle})))
    (let [root?    (not= parent-handle handle)
          raw-or-ex (deref completion-promise)]
      (when (instance? Exception raw-or-ex)
        (when root?
          (notify-waiters! handle nil)
          (orphan-box! handle ""))
        (throw raw-or-ex))
      (let [raw (parse/balance-parens raw-or-ex)]
        (when-not (compare-and-set! has-box false true)
          (throw (ex-info "Box already active for handle" {:handle handle})))
        (let [[f _] (reset-vals! inbox nil)]
          (when-not f
            (reset! has-box false)
            (throw (ex-info "Box entered with empty inbox" {:handle handle})))
          (reset! has-box false)
          (let [result (try
                         (binding [*current-handle* handle
                                   *current-raw*    raw]
                           (f raw))
                         (catch Exception e
                           (when root?
                             (notify-waiters! handle nil)
                             (orphan-box! handle raw))
                           (throw e)))]
            (when root?
              (notify-waiters! handle result)
              (orphan-box! handle raw))
            result))))))

;; =============================================================================
;; Send
;; =============================================================================

(defn -send!
  "Low-level send: swap inbox with transform-fn, then deliver signal."
  [handle transform-fn]
  (let [{:keys [inbox signal]} (get @registry handle)]
    (when-not inbox
      (throw (ex-info "Handle not registered" {:handle handle})))
    (swap! inbox transform-fn)
    (deliver @signal :wake)))

(defn send-msg-fn
  "Send function f to agent at handle.
   f takes a raw completion string and returns a modified raw string.
   The result is then passed through the agent's eval pipeline.
   Returns nil."
  [f handle]
  ;; Remove recipient from sender's waiters — they got their message
  (when *current-handle*
    (when-let [{:keys [waiters]} (get @registry *current-handle*)]
      (swap! waiters disj handle)))
  (if-let [{:keys [pending raw done]}
           (some-> (get @registry handle) :collector deref)]
    ;; Multi-ask collector active: accumulate transform in collector raw.
    ;; Each transform expects a properly-closed raw (3 trailing parens).
    ;; We add ))) before applying f, and f's reopen strips them back off.
    (do
      (swap! raw (fn [r] (f (str r ")))"))))
      (when (and *current-handle* (contains? @pending *current-handle*))
        (swap! pending disj *current-handle*)
        (when (empty? @pending)
          (deliver done true))))
    ;; Normal send: compose transform with inbox-fn (which takes [raw]).
    ;; When inbox is nil, resolve default-inbox-fn lazily (at call time,
    ;; not composition time) so the-llm can update it after spawn.
    (-send! handle
      (fn [current]
        (if current
          (fn [raw] (current (f raw)))
          (fn [raw]
            (let [base (:default-inbox-fn (get @registry handle))]
              (base (f raw))))))))
  nil)

;; =============================================================================
;; Create-msg helper
;; =============================================================================

(defn- reopen
  "Strip the 3 trailing parens of a standard completion wrapper."
  [s]
  (parse/strip-trailing-parens 3 s))

(defn- create-msg
  "Create a function that reopens a completion, appends (def name value),
   and appends an llm-self extension so the recipient continues thinking.
   Internal plumbing for signaling (waiting-for, spawn-result)."
  [name value]
  (fn [raw]
    (str (reopen raw) "(def " name " " (eval/serialize-for-continuation value) ") '(llm-self (reopen completion)) ")))

(defn send
  "Send a message to target with auto-tagged sender handle.
   Injects (def <gensym> {:from sender :value val}) into recipient's completion.
   The recipient sees the def binding with the message map."
  [value target]
  (let [name (symbol (gensym "msg-"))
        from *current-handle*]
    (send-msg-fn (create-msg name {:from from :value value}) target)))

(defn event-send
  "Run blocking event-fn in background future. When it returns {:ok val},
   send val to handle with from-tag as sender. Returns nil immediately."
  [event-fn handle from-tag]
  (future
    (binding [*current-handle* from-tag]
      (let [result (event-fn)]
        (when (:ok result)
          (send (:ok result) handle)))))
  nil)

(defn- msg-from
  "Extract :from handle from a message map.
   msg is bound to {:from <handle> :value <val>}."
  [msg]
  (:from msg))

(defn reply
  "Reply to a message (fire-and-forget).
   Extracts sender from the message map and sends value back."
  [msg value]
  (send value (msg-from msg)))

;; =============================================================================
;; Block-for-message (internal)
;; =============================================================================

(defn block-for-message
  "Seed inbox with sleep-fn and re-enter box as non-root.
   Must be called from within an agent context (inside box).
   has-box is already false (box releases before calling inbox fn)."
  []
  (let [{:keys [inbox]} (get @registry *current-handle*)]
    (compare-and-set! inbox nil (make-sleep-fn *current-handle*))
    (let [p (promise)]
      (deliver p *current-raw*)
      (box *current-handle* *current-handle* p))))

(defn reply-ask
  "Reply to a message and block for response.
   Extracts sender from the message map, sends value, then blocks."
  [msg value]
  (when-not *current-handle*
    (throw (ex-info "reply-ask: not inside an agent context" {})))
  (when-not *current-raw*
    (throw (ex-info "reply-ask: no raw completion available" {})))
  (let [target (msg-from msg)]
    (when-let [{:keys [waiters]} (get @registry target)]
      (swap! waiters conj *current-handle*))
    (send value target)
    (block-for-message)))

;; =============================================================================
;; Ask
;; =============================================================================

(defn- ask-multi
  "Multi-target ask: poke all targets, block until all have sent.
   Collects messages into a single raw string, then triggers one extension
   with all quine bindings available. Collector is activated BEFORE poking
   so even fast-responding targets route through the collector."
  [targets]
  (when-not *current-handle*
    (throw (ex-info "ask: not inside an agent context" {})))
  (when-not *current-raw*
    (throw (ex-info "ask: no raw completion available" {})))
  (when (empty? targets)
    (throw (ex-info "ask: empty target list" {})))
  (let [target-set      (set targets)
        collector-state  {:pending (atom target-set)
                          :raw     (atom (reopen *current-raw*))
                          :done    (promise)}
        handle           *current-handle*
        entry            (get @registry handle)]
    ;; Activate collector BEFORE poking targets
    (reset! (:collector entry) collector-state)
    ;; Register as waiter and poke sleeping targets
    (doseq [target targets]
      (when-let [{:keys [waiters]} (get @registry target)]
        (swap! waiters conj handle))
      (let [{:keys [inbox]} (get @registry target)]
        (when (nil? @inbox)
          (send-msg-fn (create-msg 'waiting-for handle) target))))
    ;; Wait for all targets to respond
    @(:done collector-state)
    ;; Deactivate collector
    (reset! (:collector entry) nil)
    ;; Re-enter box with accumulated raw (closed with 3 parens)
    (let [final-raw (str @(:raw collector-state) ")))")]
      (reset! (:inbox entry) (:default-inbox-fn entry))
      (let [p (promise)]
        (deliver p final-raw)
        (box handle handle p)))))

(defn ask-builtin
  "Request-reply communication primitive.
   (ask target msg) — send msg to target and wait for reply. The message
     includes the sender's handle so the target knows who to reply to.
   (ask target) — poke target (wake it) and wait for a message. Use when
     woken by the wrong agent and you need to go back to sleep for a specific one.
   (ask [targets]) — multi-target ask. Poke all targets and block until all
     have sent. Triggers a single extension with all quine bindings.
   Every form of ask wakes the target, preventing deadlocks."
  ([target]
   (if (sequential? target)
     (ask-multi target)
     (do
       (when-not *current-handle*
         (throw (ex-info "ask: not inside an agent context" {})))
       (when-not *current-raw*
         (throw (ex-info "ask: no raw completion available" {})))
       ;; Register as waiting for target's result (for auto-notification on return)
       (when-let [{:keys [waiters]} (get @registry target)]
         (swap! waiters conj *current-handle*))
       ;; Only poke if inbox is empty (agent is sleeping).
       ;; Non-empty inbox means agent already has pending work — poke unnecessary.
       (let [{:keys [inbox]} (get @registry target)]
         (when (nil? @inbox)
           (send-msg-fn (create-msg 'waiting-for *current-handle*) target)))
       (block-for-message))))
  ([target msg]
   (when-not *current-handle*
     (throw (ex-info "ask: not inside an agent context" {})))
   (when-not *current-raw*
     (throw (ex-info "ask: no raw completion available" {})))
   ;; Register as waiting for target's result (for auto-notification on return)
   (when-let [{:keys [waiters]} (get @registry target)]
     (swap! waiters conj *current-handle*))
   (send msg target)
   (block-for-message)))


;; =============================================================================
;; Waiter notification
;; =============================================================================

(defn notify-waiters!
  "Send return value to agents still waiting on this handle.
   Called when root box completes. Agents that already received an
   explicit send were removed from :waiters, so only genuinely blocked
   agents get the fallback notification.
   Binds *current-handle* to the completing handle so multi-ask
   collectors can identify the sender."
  [handle result]
  (when-let [{:keys [waiters]} (get @registry handle)]
    (doseq [waiter @waiters]
      (when (contains? @registry waiter)
        (binding [*current-handle* handle]
          (send-msg-fn (create-msg 'spawn-result result) waiter))))
    (reset! waiters #{})))

;; =============================================================================
;; Orphan box
;; =============================================================================

(defn orphan-box!
  "If no box is active for handle, seed inbox with sleep-fn and start a
   box in a future. Root detection uses stored parent-handle: agents with
   a parent get root boxes (enabling self-sustaining sleep cycles), while
   the root agent (:root) gets non-root boxes.
   No-op if box is already active. Best-effort: races are acceptable."
  [handle raw]
  (let [{:keys [has-box inbox parent-handle]} (get @registry handle)]
    (when (and has-box (not @has-box))
      (compare-and-set! inbox nil (make-sleep-fn handle))
      (let [p (promise)]
        (deliver p raw)
        (future (box handle (or parent-handle handle) p))))))

;; =============================================================================
;; Handle queries
;; =============================================================================

(defn handle?
  "Returns true if h is a registered handle."
  [h]
  (contains? @registry h))

;; =============================================================================
;; Start box helper
;; =============================================================================

(defn start-box
  "Register handle and go to sleep with initial completion as stored context.
   Returns handle. Used by register-agent for dormant agents.
   No initial evaluation — agent wakes on first message."
  [handle default-inbox-fn initial-completion]
  (register! handle default-inbox-fn)
  (orphan-box! handle initial-completion)
  handle)

;; =============================================================================
;; Spawn
;; =============================================================================

(defn spawn
  "Start an agent in a background future. Returns its handle immediately.
   The handle is addressable. The child must explicitly send
   its result if needed; use ask-based patterns to collect spawn results.
   llm-fn must accept (prompt handle) — 2-arity.
   Stores parent handle in registry so the child can find its spawner.
   Registers synchronously so the handle is live before spawn returns.
   Optional handle-name (keyword) sets a fixed handle instead of auto-generating."
  ([llm-fn prompt] (spawn llm-fn prompt nil))
  ([llm-fn prompt handle-name]
   (let [handle (or handle-name (keyword (gensym "spawn-")))
         parent *current-handle*]
     ;; Register synchronously — handle is live before future starts
     (register! handle (make-sleep-fn handle) parent)
     (future
       ((bound-fn []
          (llm-fn prompt handle))))
     handle)))

(defn spawn-ask
  "Spawn a child agent and block until it sends back a result.
   Combines spawn + block for safe use as a quoted trailing expression:
     '(spawn-ask llm-self \"do X and send result to (parent-handle)\")
   The child must send its result via (send value (parent-handle)).
   Registers parent as waiter on child so child's death wakes the parent."
  ([llm-fn prompt] (spawn-ask llm-fn prompt nil))
  ([llm-fn prompt handle-name]
   (let [child (spawn llm-fn prompt handle-name)]
     (when-let [{:keys [waiters]} (get @registry child)]
       (swap! waiters conj *current-handle*))
     (block-for-message))))

;; =============================================================================
;; Namespace maps
;; =============================================================================

(def agents-namespace
  "Agent communication namespace — effect-guarded (trailing expression only)."
  {:guide "AGENTS — Inter-agent communication (effect namespace).

  (agents/ask target message)     — send message to target, block for reply
  (agents/ask [a b c])            — multi-target: poke all, block until all reply
  (agents/reply-ask msg value)    — reply to msg, block for next message
  (agents/reply msg value)        — reply to msg (fire-and-forget, ends conversation)
  (agents/send value target)      — send value to target with auto-tagged :from
  (agents/spawn llm-fn prompt :name) — start background agent, returns handle
  (agents/spawn-ask llm-fn prompt :name) — spawn agent, block until it sends back
  (agents/current-handle)         — your handle (:root, :spawn-N, or named keyword)
  (agents/parent-handle)          — handle of agent that spawned you (nil if root)
  (agents/send-msg-fn f handle)   — low-level: send raw transform function to handle

Message preemption: if another agent sends you a message while your response
is in flight, the message is appended as an extension and your trailing
expression becomes inert. You get a new turn with the incoming message in scope.

All agents/ calls are effect functions — quote them in the trailing expression.
Messages arrive as def bindings: (def msg-N {:from sender :value val}).
Use (describe agents :fn-name) for detailed docs on any function."
   :docs {:ask "(agents/ask target message) — send message, block for reply; (agents/ask [a b c]) for multi-target"
          :reply-ask "(agents/reply-ask msg value) — reply to msg, block for next message"
          :reply "(agents/reply msg value) — reply to msg, fire-and-forget"
          :send "(agents/send value target) — send value with auto-tagged :from"
          :spawn "(agents/spawn llm-fn prompt :name) — start background agent, returns handle"
          :spawn-ask "(agents/spawn-ask llm-fn prompt :name) — spawn agent, block until it sends back"
          :current-handle "(agents/current-handle) — your handle (:root, :spawn-N, or named keyword)"
          :parent-handle "(agents/parent-handle) — handle of spawning agent (nil if root)"
          :send-msg-fn "(agents/send-msg-fn f handle) — low-level: send raw transform function"}
   :detail
   {:ask
    "Request-reply communication primitive. Three forms:

(agents/ask target msg) — send msg to target, block for reply.
  target: keyword handle (:seller, :spawn-42, :root)
  msg: any value
  Recipient sees (def msg-N {:from your-handle :value msg}).
  Your next turn receives the reply as a def binding.

(agents/ask target) — poke target (wake it) and block.
  Sends a wake signal but no user-level message value.
  Use to wait for a specific agent to respond.

(agents/ask [a b c]) — multi-target ask.
  Pokes all targets, blocks until all have sent.
  Triggers a single extension with all message bindings.
  Reduces N round-trips to 1 for fan-out/fan-in.

Every form wakes the target, preventing deadlocks.
Code after ask is dead code — ask blocks and triggers a new turn.

Example — multi-turn conversation:
  '(do (agents/spawn llm-self \"You are a seller.\" :seller)
       (agents/ask :seller 100))
  ;; next turn: (def msg-0 {:from :seller :value 250})
  '(agents/ask :seller 150)
  ;; ...until one side uses reply to end

Example — fan-out/fan-in:
  '(do (def a (agents/spawn llm-self \"compute X, send to parent-handle\"))
       (def b (agents/spawn llm-self \"compute Y, send to parent-handle\"))
       (agents/ask [a b]))
  ;; next turn: both results as def bindings

Message preemption: if another agent sends you a message while your response
is in flight, the message is appended as an extension. Your trailing expression
(e.g. this ask) becomes inert — it does not fire. You get a new turn with the
incoming message in scope. Re-evaluate and re-issue if still appropriate.

  ...▌
  '(agents/ask :B \"hello\")
  ;; agent C sends a message before your ask fires; your completion becomes:
  ...'(agents/ask :B \"hello\") (def msg-0 {:from :C :value \"urgent\"})
  '(llm-self (reopen completion))  ;; ask became inert data — it did not fire"

    :reply-ask
    "Reply to a received message and block for the next response.
Keeps the conversation open — sender gets your reply, you wait for theirs.

(agents/reply-ask msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

The sender's next turn sees (def msg-N {:from your-handle :value value}).
Your next turn receives the sender's next message as a new def binding.

Example (from a spawned agent's perspective):
  ;; received (def msg-0 {:from :root :value 100})
  '(agents/reply-ask msg-0 250)
  ;; sends 250 back to :root, blocks for next message
  ;; next turn: (def msg-1 {:from :root :value 150})"

    :reply
    "Reply to a received message (fire-and-forget). Ends the conversation from your side.

(agents/reply msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

Does not block. Use as the final message in a conversation.
Use reply-ask instead when you want to continue back-and-forth.

Example:
  ;; received (def msg-0 {:from :root :value \"final offer: 200\"})
  '(agents/reply msg-0 \"accepted\")
  ;; :root's next turn sees (def msg-N {:from :seller :value \"accepted\"})"

    :send
    "Send a value to a target handle with auto-tagged sender.
The recipient sees (def msg-N {:from your-handle :value val}).

(agents/send value target)
  value: any value
  target: keyword handle

Low-level primitive. Prefer ask/reply-ask/reply for conversations.
Primary use: spawned child sending its result back to the parent.

Example (from a spawned child):
  '(agents/send 42 (agents/parent-handle))"

    :spawn
    "Start an agent in a background future. Returns its handle immediately.

(agents/spawn llm-fn prompt)
(agents/spawn llm-fn prompt :name)
  llm-fn: usually llm-self
  prompt: string prompt for the child agent
  :name: optional keyword handle (e.g. :seller). Default: auto-generated :spawn-N.

The child runs independently with its own handle and communicates via messages.
Use named handles for multi-turn conversations — keywords are self-evaluating
and persist across extensions.

Message timing: messages sent to a spawned agent arrive after its LLM call
completes. Everything the child needs must be in the prompt.

The prompt must be a string literal or wrap-cat expression so the child gets
the completion wrapper. A bare quine lacks the wrapper and the child will
fail with 'unbound symbol' on effect functions:
  (quine p \"Do X\") '(agents/spawn llm-self p)           ; WRONG
  (quine p \"Do X\") '(agents/spawn llm-self (wrap-cat p)) ; correct
  '(agents/spawn llm-self \"Do X.\")                       ; correct

Example:
  '(do (agents/spawn llm-self \"You negotiate prices.\" :seller)
       (agents/ask :seller 100))"

    :spawn-ask
    "Spawn a child agent and block until it sends back a result.
Combines spawn + block. One-shot delegation pattern.

(agents/spawn-ask llm-fn prompt :name)
  llm-fn: usually llm-self
  prompt: string or wrap-cat — must instruct child to send to (agents/parent-handle)
  :name: optional keyword handle (like agents/spawn)
  (see agents/spawn docs for why the prompt must not be a bare quine)

Your next turn sees (def msg-N {:from child-handle :value result}).

Example:
  '(agents/spawn-ask llm-self \"Compute 6*7 and (agents/send result (agents/parent-handle))\")
  ;; next turn: (def msg-0 {:from :spawn-42 :value 42})"

    :current-handle
    "Returns your handle as a keyword.

(agents/current-handle)

:root for the root agent, :spawn-N for auto-named spawned agents,
or the keyword you specified when spawned (e.g. :seller)."

    :parent-handle
    "Returns the handle of the agent that spawned you, or nil if root.

(agents/parent-handle)

Use in spawned agents to send results back to the parent:
  '(agents/send result (agents/parent-handle))"

    :send-msg-fn
    "Low-level fire-and-forget send. Most agents should use send, ask, or reply-* instead.

(agents/send-msg-fn f handle)
  f: function taking a raw completion string, returning a modified string
  handle: target keyword handle

Internal plumbing for the communication layer."}
   :send send
   :reply reply
   :reply-ask reply-ask
   :ask ask-builtin
   :spawn (fn
            ([llm-fn prompt] (spawn llm-fn prompt))
            ([llm-fn prompt handle-name] (spawn llm-fn prompt handle-name)))
   :spawn-ask (fn
               ([llm-fn prompt] (spawn-ask llm-fn prompt))
               ([llm-fn prompt handle-name] (spawn-ask llm-fn prompt handle-name)))
   :current-handle (fn [] *current-handle*)
   :parent-handle (fn [] (:parent-handle (get @registry *current-handle*)))
   :send-msg-fn send-msg-fn})

(def futures-namespace
  "Parallel computation namespace — effect-guarded (trailing expression only)."
  {:guide "FUTURES

future/await/plet for deterministic parallel computation. These are for pure computation only — never use them for LLM calls (they'd share the parent handle and contend over the box).

  (future expr)          — run expr in background, returns a future
  (await f)              — block until future f completes, returns value
  (futures/await-all [f1 f2 ...])  — await multiple futures, returns vector of results
  (plet [a expr1 b expr2] body)   — parallel let: compute bindings concurrently
  (futures/pmap f coll)            — parallel map: applies f to each element concurrently

Note: future, await, and plet are core builtins (no namespace prefix needed).
Only await-all and pmap are in the futures/ namespace."
   :docs {:await-all "(futures/await-all [f1 f2 ...]) — await multiple futures, returns vector of results"
          :pmap "(futures/pmap f coll) — parallel map, applies f to each element concurrently"}
   :await-all (fn [futures]
                (when-not (sequential? futures)
                  (throw (ex-info "await-all: argument must be a collection" {:got futures})))
                (mapv (fn [f]
                        (when-not (eval/spell-future? f)
                          (throw (ex-info "await-all: all elements must be futures" {:got f})))
                        (deref (:ref f)))
                      futures))
   :pmap (fn [f coll]
           (let [futures (mapv (fn [item]
                                 {:spell/future true
                                  :ref (clojure.core/future ((bound-fn [] (eval/invoke-fn f [item]))))})
                               coll)]
             (mapv #(deref (:ref %)) futures)))})
