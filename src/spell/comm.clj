(ns spell.comm
  "Inter-agent communication: ask/recv/send primitives.

   box is the universal execution primitive: it waits for a function (from
   an inbox) and applies it to a raw completion string. ask sends a message
   and blocks for reply. recv wakes a source and blocks for its message.
   send is low-level fire-and-forget. Every wait wakes the target,
   preventing deadlocks."
  (:refer-clojure :exclude [send])
  (:require [spell.eval :as eval]
            [spell.parse :as parse]))

;; =============================================================================
;; Registry
;; =============================================================================

(def registry
  "Global registry: handle -> {:inbox (atom nil), :signal (atom (promise)),
                                :has-box (atom false), :eval-fn fn}"
  (atom {}))

(defn register!
  "Register a handle with its eval pipeline function."
  [handle eval-fn]
  (when (contains? @registry handle)
    (throw (ex-info "Handle already registered" {:handle handle})))
  (swap! registry assoc handle
         {:inbox     (atom nil)
          :signal    (atom (promise))
          :has-box   (atom false)
          :eval-fn   eval-fn
          :waiters   (atom #{})
          :collector (atom nil)}))

(defn unregister!
  "Remove a handle from the registry."
  [handle]
  (swap! registry dissoc handle))

;; =============================================================================
;; Dynamic vars
;; =============================================================================

(def ^:dynamic *current-handle*
  "Handle for the currently executing agent (set inside box)."
  nil)

(def ^:dynamic *current-raw*
  "Raw completion string for the currently executing agent (set inside box)."
  nil)

(def ^:dynamic *parent-handle*
  "Handle of the agent that spawned the current agent (set by spawn)."
  nil)

(def ^:dynamic *spawn-ready*
  "Promise delivered by -llm after registering a spawned handle.
   Lets spawn block until the handle is fully live."
  nil)

;; =============================================================================
;; Box
;; =============================================================================

(defn box
  "Core execution primitive. Drains inbox, applies fn to raw.
   If inbox is empty, blocks until someone sends to this handle."
  [raw handle]
  (let [{:keys [inbox signal has-box]} (get @registry handle)]
    (when-not inbox
      (throw (ex-info "Handle not registered" {:handle handle})))
    (when-not (compare-and-set! has-box false true)
      (throw (ex-info "Box already active for handle" {:handle handle})))
    (loop []
      (let [[f _] (reset-vals! inbox nil)]
        (if f
          (do (reset! has-box false)
              (binding [*current-handle* handle
                        *current-raw*    raw]
                (f raw)))
          (do (deref @signal) ; block until signal
              (reset! signal (promise))
              (recur)))))))

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
    ;; Normal send: compose with eval-fn and signal box
    (-send! handle
      (fn [current]
        (let [base (or current (:eval-fn (get @registry handle)))]
          (comp base f)))))
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

(defn- block-for-message
  "Release box claim and re-enter box. Blocks until inbox receives a function.
   Must be called from within an agent context (inside box)."
  []
  (let [{:keys [has-box]} (get @registry *current-handle*)]
    (reset! has-box false)
    (box *current-raw* *current-handle*)))

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
    ;; Release box claim and wait for all targets to respond
    (reset! (:has-box entry) false)
    @(:done collector-state)
    ;; Deactivate collector
    (reset! (:collector entry) nil)
    ;; Re-enter box with accumulated raw (closed with 3 parens)
    (let [final-raw (str @(:raw collector-state) ")))")]
      (reset! (:inbox entry) (:eval-fn entry))
      (box final-raw handle))))

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
   Called when root -llm completes. Agents that already received an
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
  "If no box is active for handle, spawn a one-shot future that calls box.
   Processes one message then exits. No-op if box is already active.
   Best-effort: races are acceptable since orphan is a convenience."
  [raw handle]
  (let [{:keys [has-box]} (get @registry handle)]
    (when (and has-box (not @has-box))
      (future (box raw handle)))))

;; =============================================================================
;; Handle queries
;; =============================================================================

(defn handle?
  "Returns true if h is a registered handle."
  [h]
  (contains? @registry h))

;; =============================================================================
;; Spawn
;; =============================================================================

(defn spawn
  "Start an agent in a background future. Returns its handle immediately.
   The handle is addressable (send to it). The child must explicitly send
   its result if needed; use ask-based patterns to collect spawn results.
   llm-fn must accept (prompt hooks handle) — 3-arity.
   Sets *parent-handle* so the child can find its spawner.
   Blocks until the child registers (handle is live before spawn returns).
   Optional handle-name (keyword) sets a fixed handle instead of auto-generating."
  ([llm-fn prompt] (spawn llm-fn prompt nil))
  ([llm-fn prompt handle-name]
   (let [handle (or handle-name (keyword (gensym "spawn-")))
         parent *current-handle*
         ready  (promise)]
     (future
       ((bound-fn []
          (binding [*parent-handle* parent
                    *spawn-ready*   ready]
            (llm-fn prompt [] handle)))))
     @ready
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

Agents communicate by sending messages. A message extends the recipient's completion with a def binding and triggers a new LLM turn.

  (agents/send-msg value handle)      — send a message to agent at handle (auto-tags :from with sender handle)
  (agents/reply-send msg value)       — reply to a received message (fire-and-forget)
  (agents/reply-ask msg value)        — reply to a received message, then block for their response
  (agents/spawn llm-fn prompt)        — start a background agent, returns its handle (auto-generated)
  (agents/spawn llm-fn prompt :name)  — same, but with a fixed handle name (keyword)
  (agents/current-handle)             — your handle (keyword like :agent-42); works at all levels including root
  (agents/parent-handle)              — returns the handle of the agent that spawned you (nil if not spawned)

Messages arrive as def bindings: (def msg-N {:from sender-handle :value val}).
Access the value with (:value msg-N) and the sender with (:from msg-N). reply-send and reply-ask extract the sender automatically.

Blocking primitives — these block until a message arrives, then trigger a new turn (extension) with the message binding. Code after a blocking call in the same expression is dead code; continue in the next turn instead.

  (agents/ask target msg)             — send msg to target, block for reply
  (agents/ask target)                 — poke target (wake it), block until it sends to you
  (agents/ask [a b c])                — multi-target ask: poke all, block until all have sent (one turn for N agents)
  (agents/spawn-recv llm-fn prompt)   — spawn agent, block until it sends back

Handles are keywords, so they pass safely through wrap-cat and child code without lookup errors.

Named handles for multi-turn conversations: when using send-msg+ask to exchange messages across turns, use named handles. Bindings from a quoted trailing expression (like a variable holding a spawn result) do not persist to the next turn — the previous trailing expression becomes inert data after extension. Keywords are self-evaluating, so they work in every turn.
  ;; fragile: seller binding lost after ask triggers extension
  '(do (def seller (agents/spawn llm-self \"...\"))
       (agents/send-msg 100 seller) (agents/ask seller))
  ;; robust: keyword handle works in every turn
  '(do (agents/spawn llm-self \"...\" :seller)
       (agents/send-msg 100 :seller) (agents/ask :seller))

Message timing: a message sent to a spawned agent arrives *after* the agent completes its LLM call and evaluates its code. Everything the child needs before completion must be in the prompt.

spawn-recv pattern (spawn + block — the primary delegation pattern):
  '(agents/spawn-recv llm-self \"compute 42 and send result to (agents/parent-handle)\")

  ;; child:
  '(agents/send-msg 42 (agents/parent-handle))

  ;; parent's next turn sees (def msg-N {:from child-handle :value 42}), continues
  ;; (:value msg-N) => 42

Multi-target ask — collect from all targets in a single turn:
  ;; turn 1: spawn agents, wait for all at once
  '(do (def a (agents/spawn llm-self \"compute bid and agents/send-msg to (agents/parent-handle)\"))
       (def b (agents/spawn llm-self \"compute bid and agents/send-msg to (agents/parent-handle)\"))
       (agents/ask [a b]))
  ;; turn 2: both messages arrived as def bindings with {:from ... :value ...}

ask pattern (for agents that have already completed — e.g. named agents or after receiving a message):
  ;; parent sends to worker and waits: '(agents/ask :worker \"do X next\")
  ;; worker receives the ask as (def msg-N {:from :parent-42, :value \"do X next\"})
  ;; (:value msg-N) => \"do X next\", (:from msg-N) => :parent-42
  ;; worker replies: '(agents/reply-send msg-N result-value)

Named spawn pattern (agents know each other's handles):
  '(do (agents/spawn llm-self \"You are seller. Buyer is :buyer.\" :seller)
       (agents/spawn llm-self \"You are buyer. Seller is :seller.\" :buyer))
  ;; Each agent can send directly to the other by name

Deadlock prevention: ask always wakes the target. If A asks B while B asks A, both sends cross and both unblock.

Handle inheritance: llm-self calls within an agent inherit the agent's handle.
All llm-self descendants share the same address.

Agents persist after returning (orphan box state). Sending to a returned agent wakes it for another turn.

For parallel LLM work, use agents/spawn. Each spawned agent gets its own handle and communicates via agents/ask.

  ;; spawn a worker and wait for its result:
  '(agents/spawn-recv llm-self \"compute 6 * 7 and send result to (agents/parent-handle)\")

  ;; spawn named agents that can find each other:
  '(do (agents/spawn llm-self \"You are researcher A. Send findings to :coordinator.\" :researcher-a)
       (agents/spawn llm-self \"You are researcher B. Send findings to :coordinator.\" :researcher-b))

llm-self calls are always serial — the child inherits your handle, so your entire llm-self call tree is one logical agent. For parallel LLM work, use agents/spawn (separate handles)."
   :docs {:send-msg "Send value to handle with auto-tagged :from"
          :reply-send "Reply to received message (fire-and-forget)"
          :reply-ask "Reply to received message, then block for response"
          :ask "Send msg to target and block for reply; (ask [a b c]) for multi-target"
          :spawn "Start background agent, returns handle"
          :spawn-recv "Spawn agent, block until it sends back"
          :current-handle "Your handle (keyword like :agent-42)"
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
   :parent-handle (fn [] *parent-handle*)
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
