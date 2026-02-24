(ns spell.globals
  "Global shared state for inter-agent communication.

   All agents can read and write globals via the globals/ namespace.
   Backed by a single atom. Pre-initialized with :roles and :tasks."
  (:require [spell.eval :as eval]))

(def ^:private store
  "Global shared state atom. Keys are keywords, values are arbitrary."
  (atom {:roles {}
         :tasks []}))

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
  (clojure.core/reset! store {:roles {} :tasks []}))

;; ---------------------------------------------------------------------------
;; Namespace map (for make-llm integration)
;; ---------------------------------------------------------------------------

(def globals-namespace
  {:guide "GLOBALS — Shared state visible to all agents (effect namespace).

  (globals/get key)              — read a global by key
  (globals/set key value)        — write a global (returns the value)
  (globals/update key f)         — atomic read-modify-write (returns new value)
  (globals/pop key)              — atomic remove-and-return first element
  (globals/keys)                 — list all global keys
  (globals/all)                  — return entire globals map
  (globals/wait-until pred)      — block until pred on globals map is true

Pre-initialized with :roles {} and :tasks [].
All globals/ calls are effect functions — quote them in the trailing expression.
Use (describe globals :fn-name) for detailed docs on any function."
   :docs {:get   "Get global value by key: (globals/get :roles)"
          :set   "Set global value: (globals/set :roles {}) — returns value"
          :update "Atomic read-modify-write: (globals/update :tasks (fn [t] (conj t item))) — returns new value"
          :pop   "Atomic remove-and-return first element: (globals/pop :tasks) — returns claimed item"
          :keys  "List all global keys: (globals/keys)"
          :all   "Return entire globals map: (globals/all)"
          :wait-until "Block until predicate on globals map is true: (globals/wait-until (fn [state] (= 3 (count (:results state)))))"}
   :detail
   {:get
    "Read a global value by key. Returns nil if key doesn't exist.

(globals/get key)
  key: keyword

Example:
  '(!call-now roles (globals/get :roles))
  ;; next turn: roles is bound to the current roles map"

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
