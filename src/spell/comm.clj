(ns spell.comm
  "Inter-agent communication: ask/send/spawn primitives.

   box is the universal execution primitive: it waits for a completion source,
   drains inbox transforms, and passes the result to an inside-fn. ask sends a
   message and blocks for reply. spawn-ask spawns an agent and blocks for its
   message. send-msg-fn is low-level fire-and-forget. Every wait wakes the target,
   preventing deadlocks."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.parse :as parse]))

;; =============================================================================
;; Registry
;; =============================================================================

(def registry
  "Global registry: handle -> {:inbox (atom identity), :signal (atom (promise)),
                                :has-box (atom false),
                                :completed (atom (promise)),
                                :parent-handle kw-or-nil}"
  (atom {}))


(defn register!
  "Register a handle in the registry.
   Optional parent-handle records the spawning agent."
  ([handle] (register! handle nil))
  ([handle parent-handle]
   (when (contains? @registry handle)
     (throw (ex-info "Handle already registered" {:handle handle})))
   (swap! registry assoc handle
          {:inbox             (atom identity)
           :signal            (atom (promise))
           :has-box           (atom false)
           :parent-handle     parent-handle
           :completed         (atom (promise))})))

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

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare box)

;; =============================================================================
;; Inside-fn constructors
;; =============================================================================

(defn make-awake-fn
  "Create an inside-fn that binds *current-eval-fn* and calls eval-fn.
   Used when an agent is actively processing (not sleeping)."
  [eval-fn]
  (fn [raw]
    (binding [*current-eval-fn* eval-fn]
      (eval-fn raw))))

(defn- make-asleep-fn
  "Create an inside-fn that blocks on signal, then re-enters box awake.
   Uses the raw parameter (not *current-raw*) so that transforms applied
   by the enclosing box before sleep are preserved on fast-reply paths."
  [handle eval-fn]
  (fn [raw]
    (let [{:keys [signal]} (get @registry handle)]
      (deref @signal)
      (reset! signal (promise))
      (box handle raw (make-awake-fn eval-fn)))))

(defn- reset-signal-for-orphan!
  "Reset signal to a fresh promise before starting an orphan box.
   If the inbox has unconsumed messages, re-deliver so the orphan wakes.
   Without this, a stale signal (delivered during active execution but
   consumed by a concurrent box) causes the orphan to wake spuriously
   and replay the entire program — creating a zombie continuation."
  [handle]
  (let [{:keys [signal inbox]} (get @registry handle)
        new-signal (promise)]
    (reset! signal new-signal)
    (when (not= @inbox identity)
      (deliver new-signal :wake))))

(defn- make-root-fn
  "Wrap an inside-fn with root lifecycle: completed delivery + orphan creation.
   After inside-fn returns (or throws), delivers completed and starts an
   asleep orphan box for the next lifecycle round."
  [handle eval-fn inside-fn]
  (fn [raw]
    (try
      (let [result (inside-fn raw)]
        (deliver @(:completed (get @registry handle)) result)
        (reset! (:completed (get @registry handle)) (promise))
        (reset-signal-for-orphan! handle)
        (future (box handle *current-raw*
                  (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn))))
        result)
      (catch Exception e
        (deliver @(:completed (get @registry handle)) nil)
        (reset! (:completed (get @registry handle)) (promise))
        (reset-signal-for-orphan! handle)
        (future (box handle *current-raw*
                  (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn))))
        (throw e)))))

(defn run-root-box
  "Public entry point for root lifecycle. Wraps box with make-root-fn and
   handles completion-source exceptions (before inside-fn ran)."
  [handle completion-source inside-fn eval-fn]
  (let [root-fn (make-root-fn handle eval-fn inside-fn)]
    (try
      (box handle completion-source root-fn)
      (catch Exception e
        ;; completion-source exception (before inside-fn ran) — make-root-fn didn't fire
        (when-not (realized? @(:completed (get @registry handle)))
          (deliver @(:completed (get @registry handle)) nil)
          (reset! (:completed (get @registry handle)) (promise))
          (future (box handle ""
                    (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn)))))
        (throw e)))))

;; =============================================================================
;; Box
;; =============================================================================

(defn box
  "Core execution primitive. Awaits completion, drains inbox transforms,
   applies inside-fn to the (possibly transformed) raw string.
   Takes handle, a completion source (promise, future, or raw string),
   and an inside-fn that processes the raw string."
  [handle completion-source inside-fn]
  (let [{:keys [inbox has-box]} (get @registry handle)]
    (when-not inbox
      (throw (ex-info "Handle not registered" {:handle handle})))
    (let [raw-or-ex (if (instance? clojure.lang.IDeref completion-source)
                      (deref completion-source)
                      completion-source)]
      (when (instance? Exception raw-or-ex)
        (throw raw-or-ex))
      (let [raw (parse/balance-parens raw-or-ex)]
        (when-not (compare-and-set! has-box false true)
          (throw (ex-info "Box already active for handle" {:handle handle})))
        (let [[transform _] (reset-vals! inbox identity)]
          (reset! has-box false)
          (let [transformed (transform raw)]
            (binding [*current-handle* handle
                      *current-raw*    raw]
              (inside-fn transformed))))))))

;; =============================================================================
;; Send
;; =============================================================================

(defn -send!
  "Low-level send: compose transform-fn into inbox with FIFO ordering,
   then deliver signal."
  [handle transform-fn]
  (let [{:keys [inbox signal]} (get @registry handle)]
    (when-not inbox
      (throw (ex-info "Handle not registered" {:handle handle})))
    (swap! inbox (fn [cur] (comp transform-fn cur)))
    (deliver @signal :wake)))

(defn send-msg-fn
  "Send function f to agent at handle.
   f takes a raw completion string and returns a modified raw string.
   Returns nil."
  [f handle]
  (-send! handle f)
  nil)

(defn deliver-msg-fn
  "Like send-msg-fn but delivers to a specific signal promise.
   No-op if the promise was already delivered (agent woke from
   something else)."
  [handle signal-promise msg-fn]
  (when-not (realized? signal-promise)
    (let [{:keys [inbox]} (get @registry handle)]
      (swap! inbox (fn [cur] (comp msg-fn cur)))
      (deliver signal-promise :wake))))

;; =============================================================================
;; Create-msg helper
;; =============================================================================

(defn- reopen
  "Reopen a completion wrapper by parsing to AST and rebuilding an open prefix.
   Handles excess trailing parens safely (the LLM may write too many closers).
   Falls back to string-level strip-3 for non-quine forms."
  [s]
  (let [balanced (parse/balance-parens s)
        form     (first (parse/read-all balanced))]
    (if (and (seq? form) (= 'quine (first form)))
      (let [elements   (vec (seq form))
            inert-args (subvec elements 2 (max 2 (dec (count elements))))
            last-arg   (last elements)
            [_ do-form] (seq last-arg)
            body-forms (rest do-form)]
        (str "(quine completion "
             (when (seq inert-args)
               (str (str/join " " (map pr-str inert-args)) " "))
             "(eval (do "
             (str/join " " (map pr-str body-forms))
             " "))
      (parse/strip-trailing-parens 3 s))))

(defn- create-msg
  "Create a function that reopens a completion, appends (def name value),
   and appends an llm-self extension so the recipient continues thinking.
   Internal plumbing for signaling (waiting-for, spawn-result)."
  [name value]
  (fn [raw]
    (str (reopen raw) "(def " name " " (eval/serialize-for-continuation value) ") '(llm-self (reopen completion)) ")))

(defn send
  "Send a message to target with auto-tagged sender handle.
   Injects (def <gensym> {:from sender :body val}) into recipient's completion.
   The recipient sees the def binding with the message map."
  [value target]
  (let [name (symbol (gensym "msg-"))
        from *current-handle*]
    (send-msg-fn (create-msg name {:from from :body value}) target)))

(defn event-send
  "Run blocking event-fn in background future. When it returns {:ok val},
   send val to handle with from-tag as sender. When it returns {:abort ...},
   do nothing (silently discard). On exception, sends {:error msg} to handle
   and logs to stderr. Returns nil immediately."
  [event-fn handle from-tag]
  (future
    (binding [*current-handle* from-tag]
      (try
        (let [result (event-fn)]
          (cond
            (:ok result) (send (:ok result) handle)
            (:abort result) nil))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "event-send error for " handle ": " (.getMessage e))))
          (send {:error (.getMessage e)} handle)))))
  nil)

(defn- install-notifier
  "Watch target's :completed promise. When delivered, call
   (signal-fn handle result). signal-fn determines stale vs persistent."
  [signal-fn target]
  (let [completed-p @(:completed (get @registry target))
        handle *current-handle*]
    (future
      (let [result @completed-p]
        (signal-fn handle result)))))

(defn- install-completion-notifier
  "Install stale notifier: sends target's completion result to self.
   Captures current :signal at install time; no-ops if self wakes first."
  [target]
  (let [my-signal @(:signal (get @registry *current-handle*))]
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
   Extracts sender from the message map and sends value back."
  [msg value]
  (send value (:from msg)))

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
    (throw (ex-info (str caller ": not inside an agent context") {})))
  (when-not *current-raw*
    (throw (ex-info (str caller ": no raw completion available") {}))))

(defn reply-ask
  "Reply to a message and block for response.
   Extracts sender from the message map, sends value, then blocks."
  [msg value]
  (assert-agent-context! "reply-ask")
  (let [target (:from msg)]
    (send value target)
    (install-completion-notifier target)
    (block-for-message)))

;; =============================================================================
;; Ask
;; =============================================================================

(defn- ask-multi
  "Multi-target ask: poke all targets, wake on first reply.
   Installs completion notifiers for all targets — first reply wins."
  [targets]
  (assert-agent-context! "ask")
  (when (empty? targets)
    (throw (ex-info "ask: empty target list" {})))
  (doseq [target targets]
    (let [name (symbol (gensym "msg-"))
          ask-msg {:from *current-handle* :expects-response true}]
      (send-msg-fn (create-msg name ask-msg) target))
    (install-completion-notifier target))
  (block-for-message))

(defn ask-builtin
  "Request-reply communication primitive.
   (ask target msg) — send msg to target and wait for reply. The message
     includes the sender's handle so the target knows who to reply to.
   (ask target) — poke target (wake it) and wait for a message. Use when
     woken by the wrong agent and you need to go back to sleep for a specific one.
   (ask [targets]) — multi-target ask. Poke all targets, wake on first reply.
   Every form of ask wakes the target, preventing deadlocks."
  ([target]
   (if (sequential? target)
     (ask-multi target)
     (ask-builtin target nil)))
  ([target msg]
   (assert-agent-context! "ask")
   (let [name (symbol (gensym "msg-"))
         ask-msg (cond-> {:from *current-handle* :expects-response true}
                   msg (assoc :body msg))]
     (send-msg-fn (create-msg name ask-msg) target))
   (install-completion-notifier target)
   (block-for-message)))


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
  "Register handle and start a root box that sleeps until first message.
   Returns handle. Used by register-agent for dormant agents.
   No initial evaluation — agent wakes on first message."
  ([handle eval-fn initial-completion]
   (start-box handle eval-fn initial-completion nil))
  ([handle eval-fn initial-completion parent-handle]
   (register! handle parent-handle)
   (future (run-root-box handle initial-completion
             (make-asleep-fn handle eval-fn) eval-fn))
   handle))

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
     (register! handle parent)
     (future
       ((bound-fn []
          (llm-fn prompt handle))))
     handle)))

(defn spawn-ask
  "Spawn a child agent and block until it sends back a result.
   Combines spawn + block for safe use as a quoted trailing expression:
     '(spawn-ask llm-self \"do X and send result to (parent-handle)\")
   The child must send its result via (send value (parent-handle)).
   Installs completion notifier so child's death wakes the parent."
  ([llm-fn prompt] (spawn-ask llm-fn prompt nil))
  ([llm-fn prompt handle-name]
   (assert-agent-context! "spawn-ask")
   (let [child (spawn llm-fn prompt handle-name)]
     (install-completion-notifier child)
     (block-for-message))))

;; =============================================================================
;; Namespace maps
;; =============================================================================

(def agents-namespace
  "Agent communication namespace — effect-guarded (trailing expression only)."
  {:guide "AGENTS — Inter-agent communication (effect namespace).

  (agents/ask target message)     — send message to target, block for reply
  (agents/ask [a b c])            — multi-target: poke all, wake on first reply
  (agents/reply-ask msg value)    — reply to msg, block for next message
  (agents/reply msg value)        — reply to msg (fire-and-forget, ends conversation)
  (agents/send value target)      — send value to target with auto-tagged :from
  (agents/spawn llm-fn prompt :name) — start background agent, returns handle
  (agents/spawn-ask llm-fn prompt :name) — spawn agent, block until it sends back
  (agents/current-handle)         — your handle (:main, :spawn-N, or named keyword)
  (agents/parent-handle)          — handle of agent that spawned you (nil if main)
  (agents/send-msg-fn f handle)   — low-level: send raw transform function to handle

Special handles:
  :main — the initial agent (entry point). Always present.
  :user — the human operator (only present in interactive terminal sessions).
  Check (globals/get :roles) to see if :user is available before asking.

Message preemption: if another agent sends you a message while your response
is in flight, the message is appended as an extension and your trailing
expression becomes inert. You get a new turn with the incoming message in scope.

All agents/ calls are effect functions — quote them in the trailing expression.
Messages arrive as def bindings: (def msg-N {:from sender :body val}).
Check (globals/get :roles) to discover available agents.
Use (describe agents :fn-name) for detailed docs on any function."
   :docs {:ask "(agents/ask target message) — send message, block for reply; (agents/ask [a b c]) pokes all, first reply wins"
          :reply-ask "(agents/reply-ask msg value) — reply to msg, block for next message"
          :reply "(agents/reply msg value) — reply to msg, fire-and-forget"
          :send "(agents/send value target) — send value with auto-tagged :from"
          :spawn "(agents/spawn llm-fn prompt :name) — start background agent, returns handle"
          :spawn-ask "(agents/spawn-ask llm-fn prompt :name) — spawn agent, block until it sends back"
          :current-handle "(agents/current-handle) — your handle (:main, :spawn-N, or named keyword)"
          :parent-handle "(agents/parent-handle) — handle of spawning agent (nil if main)"
          :send-msg-fn "(agents/send-msg-fn f handle) — low-level: send raw transform function"}
   :detail
   {:ask
    "Request-reply communication primitive. Three forms:

(agents/ask target msg) — send msg to target, block for reply.
  target: keyword handle (:seller, :spawn-42, :main)
  msg: any value
  Recipient sees (def msg-N {:from your-handle :body msg :expects-response true}).
  Your next turn receives the reply as a def binding.

(agents/ask target) — poke target (wake it) and block.
  Sends (def msg-N {:from your-handle :expects-response true}) — no :body.
  Use to wait for a specific agent to respond.

(agents/ask [a b c]) — multi-target ask.
  Pokes all targets, wakes on first reply.
  Use for fan-out where the first response is sufficient.

Every form wakes the target, preventing deadlocks.
Code after ask is dead code — ask blocks and triggers a new turn.

Example — multi-turn conversation:
  '(do (agents/spawn llm-self \"You are a seller.\" :seller)
       (agents/ask :seller 100))
  ;; next turn: (def msg-0 {:from :seller :body 250})
  '(agents/ask :seller 150)
  ;; ...until one side uses reply to end

Example — fan-out, first reply wins:
  '(do (def a (agents/spawn llm-self \"compute X, send to parent-handle\"))
       (def b (agents/spawn llm-self \"compute Y, send to parent-handle\"))
       (agents/ask [a b]))
  ;; next turn: first reply as def binding (or completion notification)

Message preemption: if another agent sends you a message while your response
is in flight, the message is appended as an extension. Your trailing expression
(e.g. this ask) becomes inert — it does not fire. You get a new turn with the
incoming message in scope. Re-evaluate and re-issue if still appropriate.

  ...▌
  '(agents/ask :B \"hello\")
  ;; agent C sends a message before your ask fires; your completion becomes:
  ...'(agents/ask :B \"hello\") (def msg-0 {:from :C :body \"urgent\"})
  '(llm-self (reopen completion))  ;; ask became inert data — it did not fire"

    :reply-ask
    "Reply to a received message and block for the next response.
Keeps the conversation open — sender gets your reply, you wait for theirs.

(agents/reply-ask msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

The sender's next turn sees (def msg-N {:from your-handle :body value}).
Your next turn receives the sender's next message as a new def binding.

Example (from a spawned agent's perspective):
  ;; received (def msg-0 {:from :main :body 100 :expects-response true})
  '(agents/reply-ask msg-0 250)
  ;; sends 250 back to :main, blocks for next message
  ;; next turn: (def msg-1 {:from :main :body 150 :expects-response true})"

    :reply
    "Reply to a received message (fire-and-forget). Ends the conversation from your side.

(agents/reply msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

Does not block. Use as the final message in a conversation.
Use reply-ask instead when you want to continue back-and-forth.

Example:
  ;; received (def msg-0 {:from :main :body \"final offer: 200\" :expects-response true})
  '(agents/reply msg-0 \"accepted\")
  ;; :main's next turn sees (def msg-N {:from :seller :body \"accepted\"})"

    :send
    "Send a value to a target handle with auto-tagged sender.
The recipient sees (def msg-N {:from your-handle :body val}).

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

Your next turn sees (def msg-N {:from child-handle :body result}).

Example:
  '(agents/spawn-ask llm-self \"Compute 6*7 and (agents/send result (agents/parent-handle))\")
  ;; next turn: (def msg-0 {:from :spawn-42 :body 42})"

    :current-handle
    "Returns your handle as a keyword.

(agents/current-handle)

:main for the initial agent, :spawn-N for auto-named spawned agents,
or the keyword you specified when spawned (e.g. :seller)."

    :parent-handle
    "Returns the handle of the agent that spawned you, or nil if main.

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
   :spawn spawn
   :spawn-ask spawn-ask
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
