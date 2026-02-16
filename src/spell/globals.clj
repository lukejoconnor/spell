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
  {:guide "GLOBALS

globals/ is shared state visible to all agents. Pre-initialized with :roles (handle -> description) and :tasks (vector).
globals/ is an effect namespace — all globals/ calls must be inside the quoted trailing expression.

  (globals/get :roles)                          — read a global
  (globals/set :roles {})                       — write a global (returns value)
  (globals/update :roles (fn [m] (assoc m h desc))) — atomic read-modify-write (returns new value)
  (globals/pop :tasks)                          — atomic remove-and-return first element
  (globals/keys)                                — list all global keys
  (globals/wait-until (fn [state] ...))         — block until predicate on globals map is true (event-driven, no polling)

Prefer direct handles when available: agents/spawn returns the child handle, agents/parent-handle gives parent.
Use globals/roles when agents need to discover peers they were not directly given.

Pattern: role-based peer discovery (parent and child code shown separately)
  ;; parent: register self, spawn, wait for child's message (all in trailing expression)
  '(do (globals/update :roles (fn [m] (assoc m (agents/current-handle) \"orchestrator\")))
       (agents/spawn-recv llm-self \"register as worker, find orchestrator in globals, send 42\"))

  ;; child: register self, look up peer by role, send (all in trailing expression)
  '(do (globals/update :roles (fn [m] (assoc m (agents/current-handle) \"worker\")))
       (def orch (key (first (filter (fn [kv] (= \"orchestrator\" (val kv))) (globals/get :roles)))))
       (agents/send-msg 42 orch))

Pattern: stigmergic coordination (agents post results, coordinator waits)
  ;; coordinator: spawn workers, wait until all results posted
  '(do (agents/spawn llm-self \"compute X and (globals/update :results (fn [r] (conj r your-result)))\")
       (agents/spawn llm-self \"compute Y and (globals/update :results (fn [r] (conj r your-result)))\")
       (globals/wait-until (fn [state] (= 2 (count (:results state))))))
  ;; workers post results to globals; coordinator unblocks when predicate satisfied"
   :docs {:get   "Get global value by key: (globals/get :roles)"
          :set   "Set global value: (globals/set :roles {}) — returns value"
          :update "Atomic read-modify-write: (globals/update :tasks (fn [t] (conj t item))) — returns new value"
          :pop   "Atomic remove-and-return first element: (globals/pop :tasks) — returns claimed item"
          :keys  "List all global keys: (globals/keys)"
          :all   "Return entire globals map: (globals/all)"
          :wait-until "Block until predicate on globals map is true: (globals/wait-until (fn [state] (= 3 (count (:results state)))))"}
   :get    get-val
   :set    set-val
   :update update-val
   :pop    pop-val
   :keys   list-keys
   :all    get-all
   :wait-until wait-until})
