(ns spell.comm
  "Inter-agent communication: box/send/recv primitives.

   box is the universal execution primitive: it waits for a function (from
   an inbox) and applies it to a raw completion string. send delivers a
   function to an agent's inbox. recv lets an agent block until someone
   sends to it."
  (:refer-clojure :exclude [send])
  (:require [spell.parse :as parse]))

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
         {:inbox   (atom nil)
          :signal  (atom (promise))
          :has-box (atom false)
          :eval-fn eval-fn}))

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
  (-send! handle
    (fn [current]
      (let [base (or current (:eval-fn (get @registry handle)))]
        (comp base f))))
  nil)

;; =============================================================================
;; Recv
;; =============================================================================

(defn recv-builtin
  "Block until someone sends to the current agent's handle.
   Returns the result of the sent function applied to the current raw completion.
   Must be called from within an agent context (inside box).
   Releases the current box claim before re-entering box."
  []
  (when-not *current-handle*
    (throw (ex-info "recv: not inside an agent context" {})))
  (when-not *current-raw*
    (throw (ex-info "recv: no raw completion available" {})))
  (let [{:keys [has-box]} (get @registry *current-handle*)]
    ;; Release current box claim so box can re-acquire
    (reset! has-box false)
    (box *current-raw* *current-handle*)))

;; =============================================================================
;; Create-msg helper
;; =============================================================================

(defn- reopen
  "Strip the 3 trailing parens of a standard completion wrapper."
  [s]
  (parse/strip-trailing-parens 3 s))

(defn create-msg
  "Create a function that reopens a completion and appends (quine name value).
   Useful for injecting data into another agent's completion."
  [name value]
  (fn [raw]
    (str (reopen raw) "(quine " name " " (pr-str value) ") ")))

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
   its result if needed; use recv-based patterns to collect spawn results.
   llm-fn must accept (prompt hooks handle) — 3-arity.
   Sets *parent-handle* so the child can find its spawner."
  [llm-fn prompt]
  (let [handle (keyword (gensym "spawn-"))
        parent *current-handle*]
    (register! handle identity) ;; placeholder eval-fn; -llm seeds real one
    (future
      ((bound-fn []
         (try
           (binding [*parent-handle* parent]
             (llm-fn prompt [] handle))
           (finally
             (unregister! handle))))))
    handle))
