(ns spell.coordinator
  "Run-local communication state. Transitions commit obligations, mailboxes and
   pending notifications together. Model calls and program evaluation live outside."
  (:refer-clojure :exclude [send agent]))

(defn new-coordinator
  ([] (new-coordinator {}))
  ([options]
   (when-not (and (map? options) (every? #{:max-edges} (keys options))
                  (integer? (:max-edges options 10000)) (pos? (:max-edges options 10000)))
     (throw (ex-info "Coordinator options require positive integer :max-edges" {:options (merge {:max-edges 10000} options)})))
   (atom {:agents {} :edges {} :next-edge-id 1 :next-seq 1
          :notifications {} :next-notification-id 1 :closed? false :options (merge {:max-edges 10000} options)})))

(def ^:dynamic *coordinator* nil)

(defn snapshot []
  (when-not *coordinator*
    (throw (ex-info "No coordinator is bound; start an API run or bind a coordinator" {:type :no-coordinator})))
  @*coordinator*)
(defn agent [handle] (get-in (snapshot) [:agents handle]))
(defn open? [] (not (:closed? (snapshot))))

(defn- require-open [state]
  (when (:closed? state)
    (throw (ex-info "Coordinator is closed" {:type :coordinator-closed}))))

(defn- require-agent [state handle]
  (or (get-in state [:agents handle])
      (throw (ex-info "Handle not registered" {:handle handle}))))

(defn- notify [state p value]
  (let [id (:next-notification-id state)]
    (-> state
        (update :next-notification-id inc)
        (assoc-in [:notifications id] [p value]))))

(defn dispatch!
  "Deliver committed notifications. Concurrent dispatch is safe: promises are
   idempotent, and a notification stays recorded until delivery has happened."
  []
  (doseq [[id [p value]] (:notifications (snapshot))]
    (deliver p value)
    (swap! *coordinator* update :notifications dissoc id)))

(defn transact!
  "Apply a pure state -> [state result] transition, then dispatch notifications.
   Transition functions may be retried and must not evaluate programs or signal."
  [transition]
  (loop []
    (let [before @*coordinator*
          [after result] (transition before)]
      (if (compare-and-set! *coordinator* before after)
        (do (dispatch!) result)
        (recur)))))

(defn- entry [parent]
  {:parent-handle parent :generation 1 :status :awake :mailbox []
   :signal (promise) :completed (promise) :runner nil :runner-depth 0
   :execution (atom {:last-raw nil})})

(defn- register-transition [state handle new-entry]
  (require-open state)
  (when (get-in state [:agents handle])
    (throw (ex-info "Handle already registered" {:handle handle})))
  (assoc-in state [:agents handle] new-entry))

(defn register!
  ([handle] (register! handle nil))
  ([handle parent]
   (let [new-entry (entry parent)]
     (transact! #(vector (register-transition % handle new-entry) handle)))))

(defn- wake [state handle]
  (let [a (require-agent state handle)]
    (cond-> (assoc-in state [:agents handle :status] :awake)
      (= :finished (:status a)) (update-in [:agents handle :generation] inc))))

(defn- enqueue [state handle envelope]
  (let [state (wake state handle)]
    (-> state
        (update-in [:agents handle :mailbox] conj envelope)
        (notify (get-in state [:agents handle :signal]) :wake))))

(defn send! [handle envelope]
  (transact! (fn [state]
               (require-open state)
               [(enqueue state handle envelope) nil])))

(defn drain!
  "Drain exactly one inbox batch, rotate its signal, and claim its pending request
   slots in the same transition. A concurrent send targets the new signal."
  [handle]
  (let [signal (promise)]
    (transact!
      (fn [state]
        (require-open state)
        (let [{:keys [mailbox generation]} (require-agent state handle)
              state (reduce
                      (fn [s {:keys [request-edge]}]
                        (let [path [:edges request-edge :slots handle]
                              slot (get-in s path)]
                          (if (and (= :pending (:status slot)) (nil? (:generation slot)))
                            (assoc-in s (conj path :generation) generation) s)))
                      state mailbox)]
          [(-> state
               (assoc-in [:agents handle :mailbox] [])
               (assoc-in [:agents handle :signal] signal)) mailbox])))))

(defn outgoing [state handle]
  (->> (vals (:edges state)) (filter #(= handle (:source %)))
       (sort-by :created-seq) vec))

(defn incoming [state handle]
  (->> (vals (:edges state))
       (filter #(= :pending (get-in % [:slots handle :status])))
       (sort-by :created-seq) vec))

(defn sleep-allowed? [state handle]
  (let [out (:created-seq (last (outgoing state handle)))
        in (:created-seq (last (incoming state handle)))]
    (boolean (and out (or (nil? in) (> out in))))))

(defn wait! [handle]
  (transact!
    (fn [state]
      (require-open state)
      (let [{:keys [mailbox signal]} (require-agent state handle)]
        (cond
          (seq mailbox) [state {:status :ready :signal signal}]
          (and (empty? (outgoing state handle)) (empty? (incoming state handle)))
          [state {:status :idle}]
          (sleep-allowed? state handle)
          [(assoc-in state [:agents handle :status] :asleep)
           {:status :waiting :signal signal}]
          :else (throw (ex-info "Cannot sleep without an outgoing edge newer than every pending incoming edge"
                                {:type :sleep-refused :handle handle
                                 :out-edges (outgoing state handle)
                                 :in-edges (incoming state handle)})))))))

(defn- add-edge [state source targets]
  (require-open state)
  (require-agent state source)
  (when (or (empty? targets) (not= (count targets) (count (distinct targets)))
            (some #{source} targets))
    (throw (ex-info "Wait targets must be nonempty, distinct, and exclude their source"
                    {:source source :targets targets})))
  (doseq [target targets] (require-agent state target))
  (when-let [limit (get-in state [:options :max-edges])]
    (when (>= (count (:edges state)) limit)
      (throw (ex-info "Coordinator edge capacity exceeded"
                      {:type :coordinator-capacity :max-edges limit :active-edges (count (:edges state))}))))
  (let [id (:next-edge-id state)
        edge {:id id :source source :targets (vec targets) :status :pending
              :created-seq (:next-seq state)
              :slots (into {} (map #(vector % {:status :pending :generation nil}) targets))}]
    [(-> state (update :next-edge-id inc) (update :next-seq inc)
         (assoc-in [:edges id] edge)) id]))

(defn request!
  "Create an obligation and deliver every request atomically. Source stays awake."
  [source targets supplied? value]
  (transact!
    (fn [state]
      (let [[state id] (add-edge state source targets)]
        [(reduce (fn [s target]
                   (enqueue s target
                            {:message (cond-> {:from source :expects-response true :edge-id id}
                                        supplied? (assoc :body value))
                             :request-edge id}))
                 state targets) id]))))

(defn spawn-request!
  "Register a complete child batch and claim its initial lifecycles before any
   child is launched. specs are {:handle keyword :parent-handle keyword}."
  [source specs]
  (let [entries (mapv #(entry (:parent-handle %)) specs)]
    (transact!
      (fn [state]
        (let [state (reduce (fn [s [spec a]] (register-transition s (:handle spec) a))
                            state (map vector specs entries))
              targets (mapv :handle specs)
              [state id] (add-edge state source targets)]
          [(reduce #(assoc-in %1 [:edges id :slots %2 :generation] 1) state targets) id])))))

(defn- report [edge]
  (let [targets (:targets edge)]
    (if (= 1 (count targets))
      {:from (first targets) :edge-id (:id edge)
       :body (get-in edge [:slots (first targets) :value])}
      {:from targets :edge-id (:id edge)
       :body (mapv #(hash-map :from % :body (get-in edge [:slots % :value])) targets)})))

(defn- fill [state id target value suppress-singleton?]
  (let [edge (get-in state [:edges id])
        slot (get-in edge [:slots target])]
    (if (= :pending (:status slot))
      (let [edge (assoc-in edge [:slots target] (assoc slot :status :filled :value value))
            complete? (every? #(= :filled (:status %)) (vals (:slots edge)))
            state (if complete?
                    (cond-> (update state :edges dissoc id)
                      (not (and suppress-singleton? (= 1 (count (:targets edge)))))
                      (enqueue (:source edge) {:message (report edge)}))
                    (assoc-in state [:edges id] edge))]
        [state {:filled? true :completed? complete? :edge edge}])
      [state {:filled? false :completed? false}])))

(defn fill! [id target value]
  (transact! (fn [state]
               (require-open state)
               (fill state id target value false))))

(defn reply-request!
  "Retire an incoming reply slot and establish the reverse request in one
   transition. A singleton's report is replaced by the actionable reverse request."
  [source msg value]
  (transact!
    (fn [state]
      (require-open state)
      (let [target (:from msg)
            [state filled] (if (:expects-response msg)
                             (fill state (:edge-id msg) source value true)
                             [state {:filled? true}])]
        (when-not (:filled? filled)
          (throw (ex-info "Incoming request slot is no longer pending" {:edge-id (:edge-id msg)})))
        (let [[state id] (add-edge state source [target])]
          [(enqueue state target {:message {:from source :expects-response true :edge-id id :body value}
                                  :request-edge id}) id])))))

(defn finish!
  "Complete exactly the captured lifecycle. Fill its claimed slots, cancel its
   outgoing collections, rotate completion, and preserve undrained requests."
  [handle completion value]
  (let [next-completion (promise)]
    (transact!
      (fn [state]
        (let [a (get-in state [:agents handle])]
          (if (or (:closed? state) (not (identical? completion (:completed a))))
            [state nil]
            (let [state (reduce
                          (fn [s edge]
                            (if (= (:generation a) (get-in edge [:slots handle :generation]))
                              (first (fill s (:id edge) handle value false)) s))
                          state (incoming state handle))
                  cancelled (outgoing state handle)
                  state (-> state
                            (update :edges #(apply dissoc % (map :id cancelled)))
                            (assoc-in [:agents handle :status] :finished)
                            (assoc-in [:agents handle :completed] next-completion)
                            (notify completion value))
                  state (if (seq (get-in state [:agents handle :mailbox])) (wake state handle) state)]
              [state {:cancelled cancelled}])))))))

(defn cancel! [handle id]
  (transact!
    (fn [state]
      (require-open state)
      (let [edge (get-in state [:edges id])]
        (when-not (= handle (:source edge))
          (throw (ex-info "No outgoing edge owned by caller" {:handle handle :edge-id id})))
        [(cond-> (update state :edges dissoc id)
           (= :asleep (get-in state [:agents handle :status]))
           (enqueue handle {:message {:edge-id id :from (:targets edge) :cancelled true}}))
         (assoc edge :status :cancelled)]))))

(defn dormant! [handle]
  (transact! (fn [state]
               (require-open state)
               (require-agent state handle)
               [(assoc-in state [:agents handle :status] :finished) nil])))

(defn acquire! [handle runner]
  (transact!
    (fn [state]
      (require-open state)
      (let [a (require-agent state handle)]
        (when (and (:runner a) (not (identical? runner (:runner a))))
          (throw (ex-info "Box already active for handle" {:handle handle})))
        [(-> state (assoc-in [:agents handle :runner] runner)
             (update-in [:agents handle :runner-depth] inc)) nil]))))

(defn release! [handle runner]
  (transact!
    (fn [state]
      (if (identical? runner (get-in state [:agents handle :runner]))
        (let [state (update-in state [:agents handle :runner-depth] dec)]
          [(cond-> state (zero? (get-in state [:agents handle :runner-depth]))
             (assoc-in [:agents handle :runner] nil)) nil])
        [state nil]))))

(defn close!
  "Close this run without joining its execution. Wake parked runners and resolve
   completion tokens so they can unwind; subsequent interactions are rejected."
  []
  (transact!
    (fn [state]
      (if (:closed? state) [state nil]
        [(reduce (fn [s [_ {:keys [signal completed]}]]
                   (-> s (notify signal :closed)
                       (notify completed {:spell/run-closed true})))
                 (assoc state :closed? true :edges {}) (:agents state)) nil]))))
