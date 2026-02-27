(ns spell.globals
  "Global shared state for inter-agent communication.

   All agents can read and write globals via the globals/ namespace.
   Backed by a single atom. Pre-initialized with :roles and :tasks."
  (:require [spell.eval :as eval]))

(def ^:private default-state
  "Source of truth for globals that exist by default at startup/reset."
  {:roles {}
   :tasks []})

(def ^:private store
  "Global shared state atom. Keys are keywords, values are arbitrary."
  (atom default-state))

(defn get-val
  "Get value for key. Returns nil if key doesn't exist."
  [key]
  (get @store key))

(defn set-val
  "Set value for key. Returns value."
  [key value]
  (swap! store assoc key value)
  value)

(defn update-val
  "Atomic read-modify-write for key. Applies f to current value.
   Handles both Spell fns and Clojure fns. Returns the new value of key."
  [key f]
  (get (swap! store update key #(eval/invoke-fn f [%])) key))

(defn pop-val
  "Atomically remove and return first element from a sequential value at key.
   Returns nil if empty or key doesn't exist."
  [key]
  (let [[old _] (swap-vals! store update key rest)]
    (first (get old key))))

(defn list-keys
  "List all global keys."
  []
  (vec (keys @store)))

(defn get-all
  "Return entire globals map."
  []
  @store)

(defn wait-until
  "Block until pred returns truthy for the globals map.
   Returns true immediately if pred is already satisfied.
   Uses add-watch for event-driven notification (no polling).
   Handles both Spell fns and Clojure fns."
  [pred]
  (let [watch-key (keyword (gensym "wait-"))]
    (if (eval/invoke-fn pred [@store])
      true
      (let [done (promise)]
        (add-watch store watch-key
          (fn [_ _ _ new-state]
            (when (eval/invoke-fn pred [new-state])
              (remove-watch store watch-key)
              (deliver done true))))
        ;; Double-check after adding watch (closes race window)
        (if (eval/invoke-fn pred [@store])
          (do (remove-watch store watch-key)
              true)
          (do @done true))))))

(defn reset-globals!
  "Reset globals to initial state. For CLI/test use."
  []
  (clojure.core/reset! store default-state))

;; ---------------------------------------------------------------------------
;; Namespace map (for make-llm integration)
;; ---------------------------------------------------------------------------

(def globals-namespace
  {:short-docs "Shared state visible to all agents."
   :docs {:guide "GLOBALS — Shared state visible to all agents.

  (globals/get key)              — read a global by key
  (globals/set key value)        — write a global (returns the value)
  (globals/update key f)         — atomic read-modify-write (returns new value)
  (globals/pop key)              — atomic remove-and-return first element
  (globals/keys)                 — list all global keys
  (globals/all)                  — return entire globals map
  (globals/wait-until pred)      — block until pred on globals map is true

Use (!describe globals :fn-name) for detailed docs on any function.
All globals/ calls are effect functions — quote them in the trailing expression.

Common read patterns:
  1) Bind to a local with !call-now:
     '(!call-now roles (globals/get :roles))
     ;; next turn: roles is available as a local binding

  2) Print directly for quick inspection:
     '(!print (globals/get :roles))

Default special keys:
  :roles {}  — Agent registry for handle lookup.
               Convention: {:main \"Orchestrator\" :spawn-1 \"Worker for CLI\" :spawn-2 \"Worker for unit testing\"}
  :tasks []  — shared task queue.
               Convention: [{:id 1 :desc \"read file\"} {:id 2 :desc \"summarize\"}]

These defaults are conventions, not requirements, and are unpopulated by default. Agents may create additional keys.

Common mistakes:

1. calling globals/* outside the quoted trailing expression: (globals/get :roles) does nothing at eval time; must be quoted
2. forgetting !call-now: '(globals/get :roles) returns the value; use '(!call-now roles (globals/get :roles)) if you want to see it
3. hallucinating handles: instead, look them up in roles/ (also see agents/parent-handle and agents/current-handle)


Multi-part example — worker pool with a shared task queue:
▌ marks cursor position and is doc-only; do not type it into code.

1. Main: populate the queue and spawn workers.
  ...▌'(do (globals/set :results [])
       (globals/set :tasks [{:id 1 :desc \"summarize A\"} {:id 2 :desc \"summarize B\"}])
       (agents/spawn !llm-self \"You are a worker. Pop tasks from globals :tasks and process them.\" :w1)
       (agents/spawn !llm-self \"You are a worker. Pop tasks from globals :tasks and process them.\" :w2)
       (globals/wait-until (fn [s] (= 2 (count (:results s))))))

2. Worker w1: claim a task atomically.
  ...▌'(!call-now task (globals/pop :tasks))
  ;; next turn: task is {:id 1 :desc \"summarize A\"} (or nil if queue empty)

3. Worker w1: post result back.
  ...(def task {:id 1 :desc \"summarize A\"})
  ▌(def summary \"A is about...\")
  '(globals/update :results (fn [r] (conj (or r []) {:id 1 :summary summary})))
"
          }
   :detail
   {:get
    "Read a global value by key. Returns nil if key doesn't exist.

(globals/get key)
  key: keyword

Example (bind result to local for later reasoning):
  '(!call-now roles (globals/get :roles))
  ;; next turn: roles is bound to the current roles map

Example (quick inspection in one expression):
  '(print \"roles=\" (globals/get :roles))"

    :set
    "Set a global value. Returns the value.

(globals/set key value)
  key: keyword
  value: any value

Example:
  '(do (globals/set :status \"running\") ...)"

    :update
    "Atomic read-modify-write for a key. Applies f to the current value.
Returns the new value of the key (not the entire map).

(globals/update key f)
  key: keyword
  f: function of one argument (the current value at key)

Thread-safe — multiple agents can update the same key concurrently.

Example (register yourself in :roles):
  '(globals/update :roles (fn [m] (assoc m (agents/current-handle) \"worker\")))

Example (append to :tasks):
  '(globals/update :tasks (fn [t] (conj t {:id 1 :desc \"process data\"})))"

    :pop
    "Atomically remove and return the first element from a sequential value at key.
Returns nil if the value is empty or the key doesn't exist.

(globals/pop key)
  key: keyword

Thread-safe — use for work queues where multiple agents claim tasks.

Example (worker claims a task):
  '(!call-now task (globals/pop :tasks))
  ;; task is the first element, atomically removed from :tasks"

    :keys
    "List all global keys as a vector.

(globals/keys)

Example:
  '(!call-now ks (globals/keys))"

    :all
    "Return the entire globals map.

(globals/all)

Example:
  '(!call-now state (globals/all))"

    :wait-until
    "Block until a predicate on the globals map returns truthy.
Event-driven (uses watch, no polling). Returns true when satisfied.
Returns true immediately if the predicate is already satisfied.

(globals/wait-until pred)
  pred: function of one argument (the entire globals map)

Example (wait for all workers to post results):
  '(globals/wait-until (fn [state] (= 3 (count (:results state)))))

Example (wait for a specific key to exist):
  '(globals/wait-until (fn [state] (contains? state :done)))"}
   :get    get-val
   :set    set-val
   :update update-val
   :pop    pop-val
   :keys   list-keys
   :all    get-all
   :wait-until wait-until})
