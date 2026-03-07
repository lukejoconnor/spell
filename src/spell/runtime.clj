(ns spell.runtime
  "Agent runtime: box execution primitive, registry, message passing, spawn/ask.

   Single-drain model: box waits for a completion source and calls an inside-fn.
   Inbox drain + signal reset happen once per wake cycle in make-awake-fn
   (phase 3 entry). ask sends a message and blocks for reply. !spawn-ask spawns
   an agent and blocks for its message. send-msg-fn is low-level fire-and-forget.
   Every wait wakes the target, preventing deadlocks."
  (:refer-clojure :exclude [send])
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.parse :as parse]))

;; =============================================================================
;; Registry
;; =============================================================================

(def registry
  "Global registry: handle -> {:state (atom {:inbox-fn identity, :signal (promise)}),
                                :has-box (atom false),
                                :completed (atom (promise)),
                                :last-raw (atom nil),
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
          {:state             (atom {:inbox-fn identity, :signal (promise)})
           :has-box           (atom false)
           :parent-handle     parent-handle
           :completed         (atom (promise))
           :last-raw          (atom nil)})))

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

(def ^:dynamic *default-spawn-llm*
  "Default llm function used by prompt-only spawn/spawn-ask forms.
   Bound by eval to the current !llm-self."
  nil)

;; =============================================================================
;; Forward declarations
;; =============================================================================

(declare box ask-builtin)

(defn- default-spawn-llm
  "Resolve default llm for prompt-only spawn/spawn-ask forms."
  [caller]
  (or *default-spawn-llm*
      (throw (ex-info (str caller ": no default !llm-self available")
                      {:caller caller}))))

(defn- resolve-completion-source
  "Resolve completion source (promise/future/raw) to a raw value.
   Throws if the resolved value is an exception."
  [completion-source]
  (let [raw-or-ex (if (instance? clojure.lang.IDeref completion-source)
                    (deref completion-source)
                    completion-source)]
    (when (instance? Exception raw-or-ex)
      (throw raw-or-ex))
    raw-or-ex))

(defn- completion-token
  "Wrap a completion source as a Spell await token."
  [completion-source]
  {:spell/future true
   :ref completion-source})

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
    (let [state (:state (get @registry handle))
          [{:keys [inbox-fn]} _] (reset-vals! state {:inbox-fn identity, :signal (promise)})
          transformed (inbox-fn raw)]
      (binding [*current-eval-fn* eval-fn]
        (eval-fn transformed)))))

(defn- make-asleep-fn
  "Create an inside-fn that blocks on signal, then re-enters box awake.
   No drain or signal reset here — that happens in make-awake-fn (phase 3).
   Uses the raw parameter (not *current-raw*) so that transforms applied
   by the enclosing box before sleep are preserved on fast-reply paths."
  [handle eval-fn]
  (fn [raw]
    (let [state (:state (get @registry handle))]
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
  (fn [raw]
    (try
      (let [result (inside-fn raw)]
        (deliver @(:completed (get @registry handle)) result)
        (reset! (:completed (get @registry handle)) (promise))
        (let [orphan-raw @(:last-raw (get @registry handle))]
          (future (box handle orphan-raw
                    (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn)))))
        result)
      (catch Exception e
        (deliver @(:completed (get @registry handle)) nil)
        (reset! (:completed (get @registry handle)) (promise))
        (let [orphan-raw @(:last-raw (get @registry handle))]
          (future (box handle orphan-raw
                    (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn)))))
        (throw e)))))

(defn run-root-box
  "Public entry point for root lifecycle.
   Separates failure domains:
   - completion-source failures (before inside-fn ran) are handled here
   - inside-fn failures are handled by make-root-fn."
  [handle completion-source inside-fn eval-fn]
  (let [root-fn (make-root-fn handle eval-fn inside-fn)
        resolved (try
                   (resolve-completion-source completion-source)
                   (catch Exception e
                     ;; completion-source exception (before inside-fn ran)
                     (when-not (realized? @(:completed (get @registry handle)))
                       (deliver @(:completed (get @registry handle)) nil)
                       (reset! (:completed (get @registry handle)) (promise))
                       (future (box handle ""
                                 (make-root-fn handle eval-fn (make-asleep-fn handle eval-fn)))))
                     (throw e)))]
    (box handle resolved root-fn)))

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
  (let [{:keys [has-box last-raw]} (get @registry handle)]
    (when-not has-box
      (throw (ex-info "Handle not registered" {:handle handle})))
    (let [raw (parse/balance-parens (resolve-completion-source completion-source))]
      (when-not (compare-and-set! has-box false true)
        (throw (ex-info "Box already active for handle" {:handle handle})))
      (reset! has-box false)
      (reset! last-raw raw)
      (binding [*current-handle* handle
                *current-raw*    raw]
        (inside-fn raw)))))

;; =============================================================================
;; Send
;; =============================================================================

(defn -send!
  "Low-level send: compose transform-fn into inbox with FIFO ordering,
   then deliver signal. Both operations happen atomically via swap-vals!
   on the combined :state atom."
  [handle transform-fn]
  (let [state (:state (get @registry handle))]
    (when-not state
      (throw (ex-info "Handle not registered" {:handle handle})))
    (let [[old _] (swap-vals! state
                    (fn [{:keys [inbox-fn] :as s}]
                      (assoc s :inbox-fn (comp transform-fn inbox-fn))))]
      (deliver (:signal old) :wake))))

(defn send-msg-fn
  "Send function f to agent at handle.
   f takes a raw completion string and returns a modified raw string.
   Returns nil."
  [f handle]
  (-send! handle f)
  nil)

(defn deliver-msg-fn
  "Like send-msg-fn but delivers to a specific signal promise.
   No-op if the signal has been replaced OR already delivered (agent woke
   from something else). Uses swap-vals! so staleness/realization check and
   inbox composition happen in one atomic state transition."
  [handle captured-signal msg-fn]
  (let [state (:state (get @registry handle))
        [old new] (swap-vals! state
                    (fn [{:keys [inbox-fn signal] :as s}]
                      (if (and (identical? signal captured-signal)
                               (not (realized? signal)))
                        (assoc s :inbox-fn (comp msg-fn inbox-fn))
                        s)))]
    (when-not (identical? old new)
      (deliver captured-signal :wake))))

;; =============================================================================
;; Create-msg helper
;; =============================================================================

(defn- reopen-completion
  "Reopen a completion wrapper by parsing to AST, pruning rethink-marked
   expressions, and rebuilding an open prefix.
   If multiple top-level forms are present, reopens the LAST form and keeps
   earlier top-level forms as inert context.
   Handles excess trailing parens safely (the LLM may write too many closers).
   Falls back to string-level strip-3 for non-quine forms."
  [s]
  (let [balanced (parse/balance-parens s)
        forms    (parse/read-all balanced)
        form     (last forms)]
    (if (and (seq? form) (= 'quine (first form)))
      (let [prior-forms (butlast forms)
            processed (eval/prune-substitute form nil)]
        (str (when (seq prior-forms)
               (str (str/join " " (map pr-str prior-forms)) " "))
             (eval/reopen processed)))
      (parse/strip-trailing-parens 3 s))))

(defn- create-msg
  "Create a function that reopens a completion, appends (def name value),
   and appends an !llm-self extension so the recipient continues thinking.
   Injects a think annotation so the agent knows the message preempted its
   trailing expression (if active) or awakened it (if sleeping).
   Internal plumbing for signaling (waiting-for, spawn-result)."
  [name value]
  (fn [raw]
    (str (reopen-completion raw) "(think \"[preempted or awakened by " name "]\") (def " name " " (eval/serialize-for-continuation value) ") '(!llm-self (prune-and-reopen completion)) ")))

(defn send
  "Send a message to target with auto-tagged sender handle.
   Injects (def <gensym> {:from sender :body val}) into recipient's completion.
   The recipient sees the def binding with the message map."
  [target value]
  (let [name (symbol (gensym "msg-"))
        from *current-handle*]
    (send-msg-fn (create-msg name {:from from :body value}) target)))

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
  (let [my-signal (:signal @(:state (get @registry *current-handle*)))]
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
  (send (:from msg) value))

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

(defn- assert-future-context!
  "Throw if not running inside a Spell future."
  [caller]
  (when-not (eval/in-future-context?)
    (throw (ex-info (str caller ": must be called from within a future") {}))))

(defn completion-promise
  "Return await token for handle's current completion promise.
   Future-only primitive: must be called from a Spell future."
  [handle]
  (assert-future-context! "completion-promise")
  (let [entry (get @registry handle)]
    (when-not entry
      (throw (ex-info "completion-promise: handle not registered" {:handle handle})))
    (completion-token @(:completed entry))))

(defn blocking-await
  "Future-only await helper for the blocking/ namespace."
  [fut]
  (assert-future-context! "blocking/await")
  (if (eval/spell-future? fut)
    (deref (:ref fut))
    (throw (ex-info "blocking/await requires a future" {:value fut}))))

(defn blocking-await-all
  "Future-only helper: await a collection of Spell futures."
  [futures]
  (assert-future-context! "blocking/await-all")
  (when-not (sequential? futures)
    (throw (ex-info "blocking/await-all: argument must be a collection" {:got futures})))
  (mapv (fn [f]
          (when-not (eval/spell-future? f)
            (throw (ex-info "blocking/await-all: all elements must be futures" {:got f})))
          (deref (:ref f)))
        futures))

(defn blocking-pmap
  "Future-only parallel map. Applies f to each item concurrently and awaits results."
  [f coll]
  (assert-future-context! "blocking/pmap")
  (let [futures (mapv (fn [item]
                        (completion-token
                          (clojure.core/future
                            ((bound-fn [] (eval/invoke-fn f [item])))))
                        )
                      coll)]
    (blocking-await-all futures)))

(defn send-await
  "Future-only helper: capture completion token, send message, await completion."
  [handle msg]
  (assert-future-context! "blocking/send-await")
  (let [token (completion-promise handle)]
    (send handle msg)
    (blocking-await token)))

(defn reply-ask
  "Reply to a message and block for response.
   Extracts sender from the message map, sends value, then blocks."
  [msg value]
  (ask-builtin (:from msg) value))

;; =============================================================================
;; Ask
;; =============================================================================

(defn- wait-for-target-completions
  "Install a single stale notifier that waits for all target completions
   and delivers one combined message to self."
  [targets]
  ;; Install a single notifier that waits for all targets to complete
  (let [handle *current-handle*
        my-signal (:signal @(:state (get @registry handle)))
        completed-promises (mapv #(-> @registry (get %) :completed deref) targets)]
    (future
      (let [results (mapv (fn [target cp] {:from target :body @cp})
                          targets completed-promises)]
        (deliver-msg-fn handle my-signal
          (create-msg (symbol (gensym "msg-")) {:from targets :body results}))))))

(defn- ask-multi
  "Multi-target ask: poke all targets, wake when all have completed.
   Installs a single notifier that derefs each target's :completed promise
   in series, then delivers a combined result message."
  [targets]
  ;; Send poke messages to all targets
  (doseq [target targets]
    (let [name (symbol (gensym "msg-"))
          ask-msg {:from *current-handle* :expects-response true}]
      (send-msg-fn (create-msg name ask-msg) target)))
  (wait-for-target-completions targets)
  (block-for-message))

(defn ask-builtin
  "Request-reply communication primitive.
   (ask target msg) — send msg to target and wait for reply. The message
     includes the sender's handle so the target knows who to reply to.
   (ask target) — poke target (wake it) and wait for a message. Use when
     woken by the wrong agent and you need to go back to sleep for a specific one.
   (ask [targets]) — multi-target ask. Poke all targets, wake when all complete.
   Every form of ask wakes the target, preventing deadlocks."
  ([target]
   (if (sequential? target)
     (do
       (assert-agent-context! "ask")
       (when (empty? target)
         (throw (ex-info "ask: empty target list" {})))
       (ask-multi target))
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

(defn- spawn*
  "Internal spawn primitive that returns handle.
   Keeps completion promise lifecycle handling for fast/non-agent child returns."
  [llm-fn prompt handle-name]
  (when (:spell/leaf (meta llm-fn))
    (throw (ex-info "leaf-llm cannot be used with agents/spawn (no agent lifecycle) — use !llm-self instead"
                    {:handle handle-name})))
  (let [handle (or handle-name (keyword (gensym "spawn-")))
        parent *current-handle*]
    ;; Register synchronously — handle is live before future starts
    (register! handle parent)
    (let [initial-completed @(:completed (get @registry handle))]
      (future
        ((bound-fn []
           (try
             (let [result (llm-fn prompt handle)]
               ;; If llm-fn returned without delivering :completed (non-agent fn),
               ;; deliver now so notifiers fire and waiting agents don't deadlock.
               ;; Check that :completed atom still holds the same promise (run-root-box
               ;; resets it), so we don't interfere with the orphan lifecycle.
               (when (identical? @(:completed (get @registry handle)) initial-completed)
                 (when-not (realized? initial-completed)
                   (deliver initial-completed result)))
               result)
             (catch Exception e
               ;; Ensure :completed is delivered so notifiers fire (prevents deadlock)
               (when (identical? @(:completed (get @registry handle)) initial-completed)
                 (when-not (realized? initial-completed)
                   (deliver initial-completed nil)))
               (throw e))))))
      {:handle handle})))

(defn spawn
  "Start an agent in a background future. Returns its handle immediately.
   The handle is addressable. The child must explicitly send
   its result if needed; use ask-based patterns to collect spawn results.
   1-arity and prompt-first forms default llm-fn to !llm-self in eval context.
   llm-fn must accept (prompt handle) — 2-arity. leaf-llm is not compatible
   (it has no agent lifecycle); use !llm-self instead.
   Stores parent handle in registry so the child can find its spawner.
   Registers synchronously so the handle is live before spawn returns.
   Optional handle-name (keyword) sets a fixed handle instead of auto-generating."
  ([prompt]
   (spawn (default-spawn-llm "spawn") prompt nil))
  ([a b]
   (if (fn? a)
     (spawn a b nil)
     (spawn (default-spawn-llm "spawn") a b)))
  ([llm-fn prompt handle-name]
   (:handle (spawn* llm-fn prompt handle-name))))

(defn- spawn-from-multi-spec
  "Spawn child from a multi-spawn-ask entry.
   Supports explicit entries:
     [llm-fn prompt]
     [llm-fn prompt handle-name]
   and default-llm entries:
     prompt
     [prompt handle-name]"
  [spec]
  (if (vector? spec)
    (case (count spec)
      2 (let [[a b] spec]
          (if (fn? a)
            (spawn a b)
            (spawn (default-spawn-llm "spawn-ask") a b)))
      3 (let [[a b c] spec]
          (if (fn? a)
            (spawn a b c)
            (throw (ex-info "spawn-ask: explicit 3-item entries must be [llm-fn prompt handle-name]"
                            {:spec spec}))))
      (throw (ex-info "spawn-ask: each vector entry must be [llm-fn prompt], [llm-fn prompt handle-name], or [prompt handle-name]"
                      {:spec spec})))
    (spawn (default-spawn-llm "spawn-ask") spec)))

(defn spawn-ask
  "Spawn child agent(s) and block until completion messages arrive.
   Prompt-only forms default llm-fn to !llm-self in eval context.
   Vector form spawns multiple children, then waits for all completions
   without sending wakeup messages to those children.
   Combines spawn + block for safe use as a quoted trailing expression:
     '(agents/!spawn-ask !llm-self \"do X and send result to (parent-handle)\")
   The child must send its result via (send (parent-handle) value).
   Installs completion notifier so child's death wakes the parent."
  ([arg]
   (assert-agent-context! "spawn-ask")
   (if (vector? arg)
     (do
       (when (empty? arg)
         (throw (ex-info "spawn-ask: empty spawn spec list" {})))
       (let [children (mapv spawn-from-multi-spec arg)]
         (wait-for-target-completions children)
         (block-for-message)))
     (spawn-ask (default-spawn-llm "spawn-ask") arg nil)))
  ([a b]
   (if (fn? a)
     (spawn-ask a b nil)
     (spawn-ask (default-spawn-llm "spawn-ask") a b)))
  ([llm-fn prompt handle-name]
   (assert-agent-context! "spawn-ask")
   (let [child (spawn llm-fn prompt handle-name)]
     (install-completion-notifier child)
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
   {:await "(blocking/await fut) — future-only await. Throws outside (future ...)."
    :await-all "(blocking/await-all [f1 f2 ...]) — future-only await-many helper."
    :pmap "(blocking/pmap f coll) — future-only parallel map with blocking join."
    :plet "(blocking/plet [bindings] body...) — macro; parallel let using blocking/await."
    :completion-promise "(blocking/completion-promise handle) — future-only completion token capture."
    :send-await "(blocking/send-await handle msg) — future-only capture->send->await helper."}
   :await blocking-await
   :await-all blocking-await-all
   :pmap blocking-pmap
   :completion-promise completion-promise
   :send-await send-await})

(def agents-namespace
  "Agent communication namespace — effect-guarded (trailing expression only)."
  {:short-docs "Inter-agent communication: spawn, !ask, send, reply."
   :docs {:guide "AGENTS — Inter-agent communication (effect namespace).

  (agents/spawn prompt)         — start background agent with !llm-self
  (agents/spawn prompt :handle-name) — same, with explicit handle name
  (agents/spawn llm-fn prompt)  — start background agent with explicit llm-fn
  (agents/spawn llm-fn prompt :handle-name) — explicit llm-fn + explicit handle name
  (agents/send target message)     — send message (usually a string) to target
  (agents/reply msg-map message)   — reply to msg-map, which must contain :from
  (agents/!ask target message)     — send message to target, block for reply
  (agents/!ask target)             — poke target without message, block for reply
  (agents/!ask [a b c])            — multi-target: poke all, wake when all complete
  (agents/!reply-ask msg-map message)   — reply to msg-map, block for next message
  (agents/!spawn-ask prompt) — spawn with !llm-self, block until completion
  (agents/!spawn-ask prompt :handle-name) — same, with explicit handle name
  (agents/!spawn-ask llm-fn prompt) — spawn with explicit llm-fn, block until completion
  (agents/!spawn-ask [[llm-fn prompt] [llm-fn prompt :name] ...]) — spawn many, wait for all completions (no ask wakeup poke)
  (agents/!spawn-ask [prompt-a prompt-b ...]) — spawn many with !llm-self, wait for all completions (no ask wakeup poke)
  (agents/current-handle)          — your handle
  (agents/parent-handle)           — handle of agent that spawned you (nil if you are main)
  (agents/send-msg-fn f handle)    — low-level / not recommended

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
  ...▌'(do (agents/spawn !llm-self
         \"You are a summarizer. Read long-file.txt and send me a summary.\"
         :summarizer)
       (!extend))
  ;; next turn:
  ... ▌(think \"...\")(think \"Ok, I'll wait for summarizer now\")'(agents/!ask :summarizer)
  ;; main blocks until child responds

2. Summarizer child: use send to return result.
  ...(quine prompt \"You are a summarizer. Read long-file.txt and send me a summary.\")
  ▌'(!call-now file-contents (io/read-lines \"long-file.txt\"))
  ;; next turn
  ...(def file-contents \"...\")
  ▌(def summary \"...\")
  '(agents/send (agents/parent-handle) summary)
  ;; child turn ends after send

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

Every form wakes the target, preventing deadlocks.
Code after ask is dead code — ask blocks and triggers a new turn.

Example — multi-turn conversation:
  '(do (agents/spawn !llm-self \"You are a seller.\" :seller)
       (agents/!ask :seller 100))
  ;; next turn: (def msg-0 {:from :seller :body 250})
  '(agents/!ask :seller 150)
  ;; ...until one side uses reply to end

Example — fan-out, wait for all:
  '(do (def a (agents/spawn !llm-self \"compute X\"))
       (def b (agents/spawn !llm-self \"compute Y\"))
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
  '(!llm-self (prune-and-reopen completion))  ;; ask became inert data — it did not fire"

    :!reply-ask
    "Reply to a received message and block for the next response.
Keeps the conversation open — sender gets your reply, you wait for theirs.

(agents/!reply-ask msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

The sender's next turn sees (def msg-N {:from your-handle :body value}).
Your next turn receives the sender's next message as a new def binding.

Example (from a spawned agent's perspective):
  ;; received (def msg-0 {:from :main :body 100 :expects-response true})
  '(agents/!reply-ask msg-0 250)
  ;; sends 250 back to :main, blocks for next message
  ;; next turn: (def msg-1 {:from :main :body 150 :expects-response true})"

    :reply
    "Reply to a received message (fire-and-forget). Ends the conversation from your side.

(agents/reply msg value)
  msg: the received message map (e.g. msg-0)
  value: your reply (any value)

Does not block. Use as the final message in a conversation.
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

(agents/spawn llm-fn prompt)
(agents/spawn llm-fn prompt :name)
(agents/spawn prompt)
(agents/spawn prompt :name)
  llm-fn: !llm-self (not leaf-llm — leaf-llm has no agent lifecycle and will error)
  prompt: string prompt for the child agent
  :name: optional keyword handle (e.g. :seller). Default: auto-generated :spawn-N.
Include instructions to the child LLM in its prompt, usually not by sending a message.
The prompt must be a string literal or wrap-cat expression so the child gets
the completion wrapper. A bare quine lacks the wrapper and the child will
fail with 'unbound symbol' on effect functions:
  (quine p \"Do X\") '(agents/spawn !llm-self p)           ; WRONG
  (quine p \"Do X\") '(agents/spawn !llm-self (wrap-cat p)) ; correct
  '(agents/spawn !llm-self \"Do X.\")                       ; correct

The child runs independently with its own handle and can send messages to you or other agents.

Example:
  '(do (agents/spawn !llm-self \"You negotiate prices.\" :seller)
       (agents/!ask :seller 100))"

    :!spawn-ask
    "Spawn child agent(s) and block for result message(s).
Combines spawn + block. One-shot delegation pattern.

(agents/!spawn-ask prompt)
(agents/!spawn-ask prompt :name)
(agents/!spawn-ask llm-fn prompt :name)
  llm-fn: !llm-self (not leaf-llm — leaf-llm has no agent lifecycle and will error)
  prompt: string or wrap-cat
  :name: optional keyword handle (like agents/spawn)
  (see agents/spawn docs for why the prompt must not be a bare quine)

Your next turn sees (def msg-N {:from child-handle :body result}).

(agents/!spawn-ask [[llm-a prompt-a] [llm-b prompt-b :b] ...])
  Each entry mirrors agents/spawn arities: [llm-fn prompt], [llm-fn prompt :name], or [prompt :name].
  Non-vector entries are treated as prompt-only entries with !llm-self:
  (agents/!spawn-ask [prompt-a prompt-b prompt-c])
  Spawns all children concurrently, then waits for all completions.
  Unlike (agents/!ask [targets]), this does NOT send wakeup/poke messages to targets.
  Your next turn sees :body as a vector of {:from child-handle :body result}.

Example:
	  '(agents/!spawn-ask !llm-self \"Compute 6*7 and (agents/send (agents/parent-handle) result)\")
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
   :current-handle (fn [] *current-handle*)
   :parent-handle (fn [] (:parent-handle (get @registry *current-handle*)))
   :send-msg-fn send-msg-fn})
