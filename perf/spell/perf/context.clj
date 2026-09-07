(ns spell.perf.context
  "Bounded context/store/board workloads; no provider calls or live audit board access."
  (:require [spell.context :as ctx]
            [spell.eval :as ev]
            [spell.parse :as parse]
            [spell.globals :as g]
            [spell.stdlib :as stdlib]))

(def ^:dynamic *board-handle* :main)

(defn eval-source [form]
  (let [r (ev/spell-eval form ev/*spell-env*)]
    (if (ev/ok? r) (:ok r) (throw (ex-info (:err r) {})))))

(defn pattern-call [operation args]
  (binding [ev/*spell-env*
            {'eval eval-source 'globals g/globals-namespace
             'patterns stdlib/patterns
             'agents {:current-handle (fn [] *board-handle*)
                      :send (fn [& _]
                              (throw (ex-info "No delivery in this scenario" {})))}}]
    (ev/invoke-fn (get stdlib/patterns operation) args)))

(defn mail [operation args] (pattern-call :mail [operation args]))

(defn unique-payload [i chars]
  (let [prefix (str i ":")]
    (assert (<= (count prefix) chars))
    (str prefix (apply str (repeat (- chars (count prefix)) \x)))))

(defn stop-workers! [holder]
  (let [tasks @holder
        deadline (+ (System/nanoTime) 2000000000)]
    (try
      (doseq [{:keys [future state finished]} tasks]
        (future-cancel future)
        ;; A cancelled pending task may never execute its finally block.
        (when (compare-and-set! state :pending :cancelled)
          (deliver finished true)))
      (doseq [{:keys [finished]} tasks]
        (let [remaining-ms (max 1 (long (/ (- deadline (System/nanoTime)) 1000000)))]
          (when (= ::timeout (deref finished remaining-ms ::timeout))
            (throw (ex-info "Cancelled contention body did not finish within teardown deadline" {})))))
      (finally (reset! holder [])))))

(defn cleanup! [{:keys [context store payload futures]}]
  (try
    (when futures (stop-workers! futures))
    (finally
      (when context (reset! (:values context) {}))
      (when store (reset! store {}))
      (when payload (reset! payload nil))))
  nil)

(defn context-fixture [_]
  {:context (ctx/new-context {:max-chars 256})})

(defn render-fixture [params]
  (assoc (context-fixture params)
         :payload (atom (unique-payload 0 (:payload-chars params)))))

(defn measure-render [{:keys [operations payload-chars]} {:keys [context payload]}]
  (assert (<= 1 operations 100))
  (assert (<= 8 payload-chars 16384))
  (binding [ctx/*context* context]
    (let [value @payload
          totals
          (reduce
            (fn [{:keys [render-chars max-render-chars]} _]
              (let [text (ctx/serialize-contribution [{:name 'x :value value}])
                    forms (parse/read-all text)
                    _ (assert (= 1 (count forms)))
                    form (first forms)
                    _ (assert (= '(def x) (take 2 form)))
                    rendered-value (nth form 2)
                    resolved (if (and (seq? rendered-value)
                                      (= 'stored (first rendered-value)))
                               (ctx/stored (second rendered-value))
                               rendered-value)
                    edited (ev/apply-edits
                             (apply list 'do
                                    (concat forms [(list 'prune (count forms)) :kept]))
                             {})]
                (assert (= value resolved))
                (assert (<= (count text) (:max-chars context)))
                (assert (= '(do :kept) edited))
                {:render-chars (+ render-chars (count text))
                 :max-render-chars (max max-render-chars (count text))}))
            {:render-chars 0 :max-render-chars 0} (range operations))
          stored-count (count @(:values context))]
      (assert (if (> payload-chars 256) (pos? stored-count) (zero? stored-count)))
      (merge totals {:operations operations :payload-chars payload-chars
                     :contexts 1 :stored-count stored-count
                     :same-payload-object true :edited-forms-per-operation 1}))))

(defn measure-pruned-storage [{:keys [cycles payload-chars]} {:keys [context]}]
  (assert (<= 1 cycles 100))
  (assert (<= 512 payload-chars 16384))
  (binding [ctx/*context* context]
    (dotimes [i cycles]
      (let [payload (unique-payload i payload-chars)
            text (ctx/serialize-value payload)
            form (first (parse/read-all text))
            edited (ev/apply-edits
                     (list 'do (list 'def 'x form) '(prune 1) :kept) {})]
        (assert (= 'stored (first form)))
        (assert (= payload (ctx/stored (second form))))
        (assert (<= (count text) (:max-chars context)))
        (assert (= '(do :kept) edited))))
    (let [values @(:values context)
          stored-count (count values)
          retained-chars (reduce + 0 (map count (vals values)))]
      (assert (= cycles stored-count))
      (assert (= (* cycles payload-chars) retained-chars))
      {:cycles cycles :contexts 1 :stored-count stored-count
       :retained-payload-chars retained-chars :surviving-stored-references 0})))

(defn contention-fixture [_] {:store (g/new-store) :futures (atom [])})

(defn measure-contention [{:keys [workers total-updates]} {:keys [store futures]}]
  (assert (#{4 20} workers))
  (assert (<= 20 total-updates 20000))
  (assert (zero? (mod total-updates workers)))
  (binding [g/*store* store]
    (g/set-val :counter 0)
    (let [gate (promise)
          per-worker (quot total-updates workers)
          deadline (+ (System/nanoTime) 10000000000)]
      (try
        (dotimes [_ workers]
          (let [state (atom :pending)
                finished (promise)
                task (future
                       (when (compare-and-set! state :pending :running)
                         (try
                           @gate
                           (dotimes [_ per-worker]
                             (when (.isInterrupted (Thread/currentThread))
                               (throw (InterruptedException. "Cancelled bounded contention worker")))
                             (g/update-val :counter inc))
                           (finally (deliver finished true))))
                       nil)]
            (swap! futures conj {:future task :state state :finished finished})))
        (deliver gate true)
        (doseq [{f :future} @futures]
          (let [remaining-ms (max 1 (long (/ (- deadline (System/nanoTime)) 1000000)))]
            (when (= ::timeout (deref f remaining-ms ::timeout))
              (throw (ex-info "Contention worker exceeded shared 10-second deadline"
                              {:workers workers :total-updates total-updates})))))
        (let [counter (g/get-val :counter)]
          (assert (= total-updates counter))
          {:workers workers :updates-per-worker per-worker :counter counter
           :stores 1 :equal-total-work true :allocation-excludes-workers true})
        (finally
          (deliver gate true)
          (stop-workers! futures))))))

(defn board-fixture [{:keys [retention page-size]}]
  (assert (<= 1 retention 100))
  (assert (<= 1 page-size 100))
  (let [fixture {:context (ctx/new-context {:max-chars 256}) :store (g/new-store)}]
    (try
      (binding [g/*store* (:store fixture)]
        (pattern-call :mailing-list [{:retention retention :page-size page-size}])
        (mail :create {:list :research :description "Isolated finite benchmark evidence"})
        (mail :subscribe {:list :research :from :earliest}))
      fixture
      (catch Throwable t (cleanup! fixture) (throw t)))))

(defn check-board-and-drain! [{:keys [posts retention body-chars]}]
  (let [board (g/get-val :mailing-list)
        messages (get-in board [:lists :research :messages])
        retained (min posts retention)
        dropped (- posts retained)
        expected-ids (vec (range (inc dropped) (inc posts)))
        first-page (mail :digest {:list :research})
        again (mail :digest {:list :research})]
    (assert (= expected-ids (mapv :id messages)))
    (assert (= (unique-payload posts body-chars) (:body (last messages))))
    (assert (= (:messages first-page) (:messages again)))
    (assert (= (:gap first-page) (:gap again)))
    (assert (= (when (pos? dropped) {:from 1 :through dropped}) (:gap first-page)))
    (loop [page first-page expected expected-ids seen 0 pages 0]
      (assert (<= pages (inc posts)))
      (let [summaries (:messages page)
            ids (mapv :id summaries)
            n (count ids)]
        (assert (= ids (vec (take n expected))))
        (assert (every? #(and (not (contains? % :body))
                              (not (contains? % :provenance))) summaries))
        (when (pos? pages) (assert (nil? (:gap page))))
        ;; Acknowledge the actual read-only digest token, including the final empty page.
        (mail :ack {:token (:token page)})
        (if (zero? n)
          (do (assert (empty? expected))
              (assert (= retained seen))
              {:retained-messages retained :high-water (apply max 0 (map :id messages))
               :digest-messages seen :nonempty-pages pages :gap-count dropped
               :read-only-digest true})
          (recur (mail :digest {:list :research}) (vec (drop n expected))
                 (+ seen n) (inc pages)))))))

(defn validate-board-params! [{:keys [posts body-chars]}]
  (assert (<= 1 posts 100))
  (assert (<= 16 body-chars 8000)))

(defn measure-board [params {:keys [store]}]
  (validate-board-params! params)
  (binding [g/*store* store]
    (doseq [i (range 1 (inc (:posts params)))]
      (mail :post {:list :research :summary (str "evidence " i)
                   :body (unique-payload i (:body-chars params))}))
    (merge (check-board-and-drain! params)
           {:posts (:posts params) :stores 1 :evidence-bodies (min (:posts params) (:retention params))
            :retained-body-chars (* (min (:posts params) (:retention params)) (:body-chars params))})))

(defn measure-capture [{:keys [posts body-chars capture] :as params}
                      {:keys [store context]}]
  (validate-board-params! params)
  (assert (#{:board :receipt} capture))
  (binding [g/*store* store ctx/*context* context]
    (doseq [i (range 1 (inc posts))]
      (mail :post {:list :research :summary (str "evidence " i)
                   :body (unique-payload i body-chars)})
      (let [updated (g/update-val :mailing-list #(assoc % :audit-cycle i))
            captured (if (= capture :board) updated {:customized :digest})
            text (ctx/serialize-value captured)
            form (first (parse/read-all text))]
        (assert (<= (count text) (:max-chars context)))
        (if (= capture :board)
          (do (assert (= 'stored (first form)))
              (assert (= updated (ctx/stored (second form)))))
          (let [result (ev/spell-eval form {})]
            (assert (ev/ok? result))
            (assert (= {:customized :digest} (:ok result)))))))
    (let [board-evidence (check-board-and-drain! params)
          board (g/get-val :mailing-list)
          stored @(:values context)
          stored-count (count stored)
          bodies (count (set (map :id
                                (mapcat #(get-in % [:lists :research :messages])
                                        (cons board (vals stored))))))
          expected-bodies (if (= capture :board) posts (min posts (:retention params)))]
      (assert (= posts (:audit-cycle board)))
      (assert (= (if (= capture :board) posts 0) stored-count))
      (assert (= expected-bodies bodies))
      (merge board-evidence
             {:posts posts :capture capture :contexts 1 :stores 1 :stored-count stored-count
              :evidence-bodies bodies :reachable-body-chars (* bodies body-chars)}))))

(def scenarios
  [{:id :context-render-edit
    :description "Render one named contribution repeatedly, parse/retrieve it, and prune its source binding."
    :params [{:operations 20 :payload-chars 64} {:operations 20 :payload-chars 8192}
             {:operations 100 :payload-chars 64} {:operations 100 :payload-chars 8192}]
    :bounds "At most 100 renders/edits; 16384 payload characters; 256-character context budget."
    :notes "Combined serialization/parse/edit workload, not an isolated serializer timer. Reuses ONE payload object: stored IDs can share payload bytes. Cleanup clears both context values and payload holder."
    :setup render-fixture :measure measure-render :cleanup cleanup!}
   {:id :context-pruned-storage
    :description "Unique oversized values remain retrievable in the run store after their source references are pruned."
    :params [{:cycles 20 :payload-chars 8192} {:cycles 100 :payload-chars 8192}]
    :bounds "At most 100 cycles, each with one 512–16384 character value and one source prune."
    :notes "Live per-run store retention, NOT evidence of cross-run leakage. Only the context fixture holds the payloads after measure returns; cleanup clears its values atom. Character totals are not heap-byte estimates."
    :setup context-fixture :measure measure-pruned-storage :cleanup cleanup!}
   {:id :globals-contention
    :description "Four versus twenty host futures perform the same total number of atomic counter updates."
    :params [{:workers 4 :total-updates 2000} {:workers 20 :total-updates 2000}]
    :bounds "4/20 workers, at most 20000 total updates; shared 10-second join deadline plus 2-second completion-barrier teardown."
    :notes "Fresh store bound before future creation. Pure inc may retry. Per-task completion promises distinguish body termination from cancelled-Future status. These are host futures, not LLM agents; calling-thread allocated bytes EXCLUDE worker allocations. Wall time and exact counter are primary. No shutdown-agents."
    :setup contention-fixture :measure measure-contention :cleanup cleanup!}
   {:id :board-post-digest
    :description "Quiet posts followed by read-only paginated digests, observed retention gaps, and exact-token acknowledgements."
    :params [{:posts 20 :retention 8 :page-size 3 :body-chars 1024}
             {:posts 60 :retention 20 :page-size 7 :body-chars 1024}]
    :bounds "At most 100 posts and 101 digest pages, retention/page-size at most 100; body at most 8000 characters."
    :notes "Native board source in an isolated globals store; never touches the live audit board. The fixture performs no real sends; delivery correctness is covered by functional tests. Evidence omits bodies and stores. High-water is the maximum retained message ID."
    :setup board-fixture :measure measure-board :cleanup cleanup!}
   {:id :board-captured-snapshot
    :description "Retained-live contrast: serialize returned historical boards versus small receipts after the same updates."
    :params [{:capture :board :posts 20 :retention 8 :page-size 3 :body-chars 8000}
             {:capture :receipt :posts 20 :retention 8 :page-size 3 :body-chars 8000}
             {:capture :board :posts 60 :retention 8 :page-size 3 :body-chars 8000}
             {:capture :receipt :posts 60 :retention 8 :page-size 3 :body-chars 8000}]
    :bounds "At most 100 capture cycles; retention at most 100; body at most 8000 characters."
    :notes "Intentional OLD snapshot-capture anti-pattern, not current mandatory behavior. Paired params use identical unique posts and updates. Full boards retain historical bodies through context; receipts add zero stored values. Snapshot sharing/code/metadata affect heap size; reachable character counts are not bytes. Fixture remains strong for live GC then cleanup clears BOTH stores."
    :setup board-fixture :measure measure-capture :cleanup cleanup!}])
