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

(defn create-msg
  "Create a function that reopens a completion, appends (def name value),
   and appends an llm-self extension so the recipient continues thinking.
   Useful for injecting data into another agent's completion."
  [name value]
  (fn [raw]
    (str (reopen raw) "(def " name " " (eval/serialize-for-continuation value) ") '(llm-self (reopen completion)) ")))

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
   (send (create-msg 'message {:from *current-handle* :body msg}) target)
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
   The child must send its result via (send (create-msg 'name val) (parent-handle)).
   No poke is sent since the child is already running."
  [llm-fn prompt]
  (spawn llm-fn prompt)
  (block-for-message))
