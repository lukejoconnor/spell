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

(defn reset-globals!
  "Reset globals to initial state. For CLI/test use."
  []
  (clojure.core/reset! store {:roles {} :tasks []}))

;; ---------------------------------------------------------------------------
;; Namespace map (for make-llm integration)
;; ---------------------------------------------------------------------------

(def globals-namespace
  {:docs {:get   "Get global value by key: (globals/get :roles)"
          :set   "Set global value: (globals/set :roles {}) — returns value"
          :update "Atomic read-modify-write: (globals/update :tasks (fn [t] (conj t item))) — returns new value"
          :pop   "Atomic remove-and-return first element: (globals/pop :tasks) — returns claimed item"
          :keys  "List all global keys: (globals/keys)"
          :all   "Return entire globals map: (globals/all)"}
   :get    get-val
   :set    set-val
   :update update-val
   :pop    pop-val
   :keys   list-keys
   :all    get-all})
