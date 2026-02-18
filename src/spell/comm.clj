(ns spell.comm
  "Inter-agent communication: ask/send/spawn primitives.

   box is the universal execution primitive: it waits for a function (from
   an inbox) and applies it to a raw completion string. ask sends a message
   and blocks for reply. spawn-recv spawns an agent and blocks for its
   message. send is low-level fire-and-forget. Every wait wakes the target,
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

(defn send
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

(defn send-msg
  "Send a message to target with auto-tagged sender handle.
   Injects (def <gensym> {:from sender :value val}) into recipient's completion.
   The recipient sees the def binding with the message map."
  [value target]
  (let [name (symbol (gensym "msg-"))
        from *current-handle*]
    (send (create-msg name {:from from :value value}) target)))

(defn- msg-from
  "Extract :from handle from a message map.
   msg is bound to {:from <handle> :value <val>}."
  [msg]
  (:from msg))

(defn reply-send
  "Reply to a message (fire-and-forget).
   Extracts sender from the message map and sends value back."
  [msg value]
  (send-msg value (msg-from msg)))

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
    (send-msg value target)
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
          (send (create-msg 'waiting-for handle) target))))
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
           (send (create-msg 'waiting-for *current-handle*) target)))
       (block-for-message))))
  ([target msg]
   (when-not *current-handle*
     (throw (ex-info "ask: not inside an agent context" {})))
   (when-not *current-raw*
     (throw (ex-info "ask: no raw completion available" {})))
   ;; Register as waiting for target's result (for auto-notification on return)
   (when-let [{:keys [waiters]} (get @registry target)]
     (swap! waiters conj *current-handle*))
   (send-msg msg target)
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
          (send (create-msg 'spawn-result result) waiter))))
    (reset! waiters #{})))

;; =============================================================================
;; Orphan box
;; =============================================================================

(defn orphan-box!
  "If no box is active for handle, seed inbox with sleep-fn and start a
   non-root box in a future. Processes messages then sleeps.
   No-op if box is already active. Best-effort: races are acceptable."
  [handle raw]
  (let [{:keys [has-box inbox]} (get @registry handle)]
    (when (and has-box (not @has-box))
      (compare-and-set! inbox nil (make-sleep-fn handle))
      (let [p (promise)]
        (deliver p raw)
        (future (box handle handle p))))))

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
   The handle is addressable (send to it). The child must explicitly send
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

(defn spawn-recv
  "Spawn a child agent and block until it sends back a result.
   Combines spawn + block for safe use as a quoted trailing expression:
     '(spawn-recv llm-self \"do X and send result to (parent-handle)\")
   The child must send its result via (send-msg value (parent-handle)).
   No poke is sent since the child is already running."
  [llm-fn prompt]
  (spawn llm-fn prompt)
  (block-for-message))

;; =============================================================================
;; Namespace maps
;; =============================================================================

(def agents-namespace
  "Agent communication namespace — effect-guarded (trailing expression only)."
  {:guide "AGENTS

All agents/ calls are effect functions — quote them as the trailing expression:
  '(agents/current-handle)   — correct: fires via double evaluation
  (agents/current-handle)    — WRONG: agents/ is unbound outside trailing expression

Use (describe agents :fn-name) for docs on a specific function.

PATTERNS

One-shot delegation (spawn + block for result):
  '(agents/spawn-recv llm-self \"compute 42 and send result to (agents/parent-handle)\")
  ;; child sends: '(agents/send-msg 42 (agents/parent-handle))
  ;; parent's next turn sees (def msg-N {:from child-handle :value 42})

Multi-turn conversation (spawn + ask loop):
  ;; parent spawns a named agent, then asks it with a value:
  '(do (agents/spawn llm-self \"You are a seller...\" :seller)
       (agents/ask :seller 100))
  ;; ask sends 100 to :seller and blocks until :seller replies.
  ;; :seller's next turn sees (def msg-0 {:from :root :value 100}).
  ;; :seller counters: '(agents/reply-ask msg-0 250)
  ;;   reply-ask sends 250 back to :root AND blocks for the next message.
  ;; parent's next turn sees (def msg-1 {:from :seller :value 250}).
  ;; parent sends next offer: '(agents/ask :seller 150)
  ;; ...repeat until one side uses reply-send (fire-and-forget) to end.

Fan-out / fan-in (multi-target ask):
  '(do (def a (agents/spawn llm-self \"compute bid and agents/send-msg to (agents/parent-handle)\"))
       (def b (agents/spawn llm-self \"compute bid and agents/send-msg to (agents/parent-handle)\"))
       (agents/ask [a b]))
  ;; next turn: both messages arrived as def bindings

IMPORTANT NOTES

Messages arrive as def bindings: (def msg-N {:from sender-handle :value val}).
Access with (:value msg-N) and (:from msg-N). reply-send and reply-ask extract the sender automatically.

The root agent's handle is :root. Spawned agents get auto-generated handles (:spawn-42) unless you provide a named handle. Use named handles (:seller) for multi-turn conversations — keyword handles are self-evaluating and persist across turns, while bindings from quoted trailing expressions do not.

Message timing: messages sent to a spawned agent arrive after the agent completes its LLM call. Everything the child needs must be in the prompt.

Blocking: ask, spawn-recv block until a message arrives, then trigger a new turn. Code after a blocking call is dead code.

Deadlock prevention: ask always wakes the target.

Handle inheritance: llm-self calls inherit your handle (serial). For parallel LLM work, use agents/spawn (separate handles).

Human-in-the-loop (interactive CLI only):
  '(agents/ask :user \"What file should I edit?\")
  ;; next turn sees (def msg-N {:from :user :value \"report.txt\"})"
   :docs {:send-msg "Send value to handle with auto-tagged :from"
          :reply-send "Reply to received message (fire-and-forget)"
          :reply-ask "Reply to received message, then block for response"
          :ask "Send msg to target and block for reply; (ask [a b c]) for multi-target"
          :spawn "Start background agent, returns handle"
          :spawn-recv "Spawn agent, block until it sends back"
          :register "Register dormant agent with stored completion; wakes on first message"
          :current-handle "Your handle (:root for the root agent, :spawn-N or named for spawned agents)"
          :parent-handle "Handle of agent that spawned you (nil if root)"
          :send "Low-level fire-and-forget send"}
   :send-msg send-msg
   :reply-send reply-send
   :reply-ask reply-ask
   :ask ask-builtin
   :spawn (fn
            ([llm-fn prompt] (spawn llm-fn prompt))
            ([llm-fn prompt handle-name] (spawn llm-fn prompt handle-name)))
   :spawn-recv (fn [llm-fn prompt] (spawn-recv llm-fn prompt))
   :current-handle (fn [] *current-handle*)
   :parent-handle (fn [] (:parent-handle (get @registry *current-handle*)))
   :send send})

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
   :docs {:await-all "Await multiple futures, returns vector of results"
          :pmap "Parallel map — applies fn to each element in parallel"}
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
