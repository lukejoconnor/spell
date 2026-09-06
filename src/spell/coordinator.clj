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
   (atom {:agents {} :edges {} :external-waits {} :next-edge-id 1 :next-seq 1
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
  ([handle parent] (register! handle parent :awake))
  ([handle parent status]
   (when-not (#{:awake :finished} status)
     (throw (ex-info "Initial agent status must be awake or finished" {:status status})))
   (let [new-entry (assoc (entry parent) :status status)]
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

(defn- sleep-refusal-data [state handle]
  (let [summarize #(mapv (fn [edge] (select-keys edge [:id :source :targets :created-seq])) %)]
    {:type :sleep-refused :handle handle
     :out-edges (summarize (outgoing state handle))
     :in-edges (summarize (incoming state handle))}))

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
                                (sleep-refusal-data state handle))))))))

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

(defn- wake-if-invalid-wait [state source]
  (let [status (get-in state [:agents source :status])]
    (if (and (#{:asleep :external-wait} status)
             (not (sleep-allowed? state source))
             (or (= :asleep status) (seq (incoming state source))))
      (enqueue state source {:message {:from :coordinator :body :wait-obligation-changed}})
      state)))

(defn- require-source-lifecycle [state source expected]
  (when (and expected (not (identical? expected (:completed (require-agent state source)))))
    (throw (ex-info "Computation belongs to a completed agent lifecycle"
                    {:type :stale-computation-lifecycle :handle source}))))

(defn request!
  "Create an obligation and deliver every request atomically. Source stays awake."
  ([source targets supplied? value] (request! source targets supplied? value nil))
  ([source targets supplied? value result-promise]
   (request! source targets supplied? value result-promise nil))
  ([source targets supplied? value result-promise expected-lifecycle]
  (transact!
    (fn [state]
      (require-source-lifecycle state source expected-lifecycle)
      (let [[state id] (add-edge state source targets)]
        [(reduce (fn [s target]
                   (enqueue s target
                            {:message (cond-> {:from source :expects-response true :edge-id id}
                                        supplied? (assoc :body value))
                             :request-edge id}))
                 (cond-> state result-promise (assoc-in [:edges id :result-promise] result-promise)) targets) id])))))

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
                    (let [s (update state :edges dissoc id)]
                      (if-let [p (:result-promise edge)]
                        (-> s (notify p {:status :completed :value (:body (report edge))})
                            (wake-if-invalid-wait (:source edge)))
                        (cond-> s
                          (not (and suppress-singleton? (= 1 (count (:targets edge)))))
                          (enqueue (:source edge) {:message (report edge)}))))
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
          [(enqueue state target
                    {:message (cond-> {:from source :expects-response true :edge-id id :body value}
                                ;; The terminal labels completed user collections. A
                                ;; reverse singleton request replaces the usual report,
                                ;; so preserve its exact original ID for that endpoint.
                                (and (= :user target) (:completed? filled)
                                     (= 1 (count (get-in filled [:edge :targets]))))
                                (assoc :reply-to-edge-id (:edge-id msg)))
                     :request-edge id}) id])))))

(defn- settle-lifecycle
  [state handle completion value next-completion]
  (let [a (get-in state [:agents handle])
        retire? (nil? next-completion)]
    (if (or (:closed? state) (nil? a)
            (not (identical? completion (:completed a))))
      [state nil]
      (let [state (reduce
                    (fn [s edge]
                      (if (or retire?
                              (= (:generation a) (get-in edge [:slots handle :generation])))
                        (first (fill s (:id edge) handle value false)) s))
                    state (incoming state handle))
            cancelled (outgoing state handle)
            state (reduce (fn [s edge]
                            (if-let [p (:result-promise edge)]
                              (notify s p {:status :cancelled :edge-id (:id edge)}) s))
                          state cancelled)
            state (-> state
                      (update :external-waits
                              #(into {} (remove (fn [[_ wait]] (= handle (:source wait))) %)))
                      (update :edges #(apply dissoc % (map :id cancelled)))
                      (notify completion value))
            state (if retire?
                    (-> state (update :agents dissoc handle)
                        (notify (:signal a) :retired))
                    (-> state
                        (assoc-in [:agents handle :status] :finished)
                        (assoc-in [:agents handle :completed] next-completion)))
            state (if (and (not retire?) (seq (get-in state [:agents handle :mailbox])))
                    (wake state handle) state)]
        [state {:cancelled cancelled :retired? retire?}]))))

(defn finish!
  "Complete exactly the captured lifecycle. Fill its claimed slots, cancel its
   outgoing collections, rotate completion, and preserve undrained requests."
  [handle completion value]
  (let [next-completion (promise)]
    (transact! #(settle-lifecycle % handle completion value next-completion))))

(defn retire!
  "Retire a lifecycle that has no runner able to service a subsequent request.
   Ownership is checked against its captured completion. Fill even unconsumed
   incoming requests, cancel outgoing collections, and remove the handle."
  [handle completion value]
  (transact! #(settle-lifecycle % handle completion value nil)))

(defn cancel! [handle id]
  (transact!
    (fn [state]
      (require-open state)
      (let [edge (get-in state [:edges id])]
        (when-not (= handle (:source edge))
          (throw (ex-info "No outgoing edge owned by caller" {:handle handle :edge-id id})))
        [(cond-> (update state :edges dissoc id)
           (:result-promise edge) (notify (:result-promise edge) {:status :cancelled :edge-id id})
           (#{:asleep :external-wait} (get-in state [:agents handle :status]))
           (enqueue handle {:message {:edge-id id :from (:targets edge) :cancelled true}}))
         (assoc edge :status :cancelled)]))))

(defn begin-external-wait!
  "Register an interruptible computation wait. Existing incoming agent obligations
   still require a newer real outgoing edge; this wait cannot manufacture one."
  [source]
  (let [token (Object.)]
    (transact!
      (fn [state]
        (require-open state)
        (let [a (require-agent state source)]
          (when (and (empty? (:mailbox a)) (seq (incoming state source))
                     (not (sleep-allowed? state source)))
            (throw (ex-info "Cannot await computation while newer incoming obligations require attention"
                            (sleep-refusal-data state source))))
          [(cond-> (assoc-in state [:external-waits token]
                            {:source source :generation (:generation a)})
             (empty? (:mailbox a)) (assoc-in [:agents source :status] :external-wait)) token])))))

(defn complete-external-wait! [token value]
  (transact!
    (fn [state]
      (if-let [{:keys [source]} (get-in state [:external-waits token])]
        [(enqueue (update state :external-waits dissoc token) source
                  {:message {:from :future :body value}}) true]
        [state false]))))

(defn acquire! [handle runner completion]
  (transact!
    (fn [state]
      (require-open state)
      (let [a (require-agent state handle)]
        (when-not (identical? completion (:completed a))
          (throw (ex-info "Scheduled execution belongs to a completed agent lifecycle"
                          {:type :stale-agent-lifecycle :handle handle})))
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
                 (reduce (fn [s [_ edge]]
                           (if-let [p (:result-promise edge)]
                             (notify s p {:status :closed}) s))
                         (assoc state :closed? true :edges {} :external-waits {}) (:edges state))
                 (:agents state)) nil]))))
