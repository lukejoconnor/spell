(ns spell.perf.lifecycle
  "Finite lifecycle correctness/performance workloads; no live provider calls."
  (:require [spell.api :as api]
            [spell.context :as context]
            [spell.coordinator :as c]
            [spell.globals :as globals]
            [spell.provider :as p]
            [spell.runtime :as r]
            [spell.test-helpers :as th]))

(def ^:private deadline-ms 5000)
(def ^:private initial-source "(quine completion (eval (do )))")

(defn- check! [ok message data]
  (when-not ok (throw (ex-info message data))))

(defn- deadline []
  (+ (System/nanoTime) (* deadline-ms 1000000)))

(defn- remaining-ms [until]
  (max 0 (long (Math/ceil (/ (double (- until (System/nanoTime))) 1000000.0)))))

(defn- bounded [x until]
  (let [v (deref x (remaining-ms until) ::timeout)]
    (check! (not= ::timeout v) "Lifecycle deadline exceeded" {:deadline-ms deadline-ms})
    v))

(defn- await-state! [pred message]
  (let [until (deadline)]
    (loop []
      (if (pred)
        true
        (do
          (check! (pos? (remaining-ms until)) message {:deadline-ms deadline-ms})
          ;; Backoff only: the predicate, not elapsed sleeping, establishes readiness.
          (Thread/sleep 1)
          (recur))))))

(defmacro ^:private with-fixture [fixture & body]
  `(let [state# (deref ~fixture)]
     (check! (some? state#) "Fixture already cleared" {})
     (binding [context/*context* (:context state#)
               c/*coordinator* (:coordinator state#)
               globals/*store* (:globals state#)]
       ~@body)))

(defn- close-fixture! [fixture]
  (when @fixture
    (try
      (with-fixture fixture (c/close!))
      (finally (reset! fixture nil)))))

(defn- handles [n]
  (mapv #(keyword (str "worker-" %)) (range n)))

(defn- new-fixture [{:keys [agents] :or {agents 0}}]
  (let [fixture (atom {:context (context/new-context)
                       :coordinator (c/new-coordinator)
                       :globals (globals/new-store)
                       :handles (handles agents)
                       :callbacks (atom 0)})]
    (try
      (with-fixture fixture (c/register! :parent))
      fixture
      (catch Throwable e
        (close-fixture! fixture)
        (throw e)))))

(defn- await-envelope! [handle]
  (let [until (deadline)]
    (loop []
      (let [{:keys [status signal]} (c/wait! handle)]
        (case status
          :ready (c/drain! handle)
          :waiting (do (bounded signal until) (recur))
          (throw (ex-info "No tracked work while awaiting response" {:handle handle :status status})))))))

(defn- coordinator-measure [{:keys [agents cycles mailbox-batch]} fixture]
  (with-fixture fixture
    (let [hs (:handles @fixture)]
      (doseq [h hs] (c/register! h))
      (dotimes [cycle cycles]
        ;; One hyperedge with N slots, not N singleton requests.
        (let [edge (c/request! :parent hs true cycle)
              waited (c/wait! :parent)]
          (check! (= :waiting (:status waited)) "Kernel parent did not wait" {})
          (doseq [h hs]
            (let [inbox (c/drain! h)]
              (check! (= 1 (count inbox)) "Wrong request count" {:handle h})
              (check! (= cycle (get-in inbox [0 :message :body])) "Wrong request body" {:handle h})
              (c/fill! edge h h)))
          (bounded (:signal waited) (deadline))
          (let [inbox (await-envelope! :parent)]
            (check! (= 1 (count inbox)) "Wrong reply count" {})
            (check! (= hs (mapv :body (get-in inbox [0 :message :body]))) "Wrong ordered fanin" {}))
          (check! (empty? (:edges (c/snapshot))) "Kernel retained completed edge" {})
          (check! (= :idle (:status (c/wait! :parent))) "Kernel did not become idle" {}))
        (doseq [h hs]
          (dotimes [i mailbox-batch]
            (c/send! h {:message {:from :parent :body i}}))
          (let [inbox (c/drain! h)]
            (check! (= mailbox-batch (count inbox)) "Wrong mailbox size" {:handle h})
            (check! (= (vec (range mailbox-batch)) (mapv #(get-in % [:message :body]) inbox))
                    "Mailbox FIFO failure" {:handle h}))
          (check! (empty? (c/drain! h)) "Mailbox not empty after drain" {:handle h})))
      {:agents agents :cycles cycles :hyperedges cycles
       :reply-slots (* agents cycles)
       :mailbox-messages (* agents cycles mailbox-batch)
       :remaining-edges (count (:edges (c/snapshot)))})))

(defn- spawn-measure [{:keys [agents cycles]} fixture]
  (with-fixture fixture
    (let [hs (:handles @fixture)
          calls (:callbacks @fixture)
          child (th/compiled-agent-fn (fn [_ h] (swap! calls inc) h))]
      (binding [r/*current-handle* :parent
                r/*current-raw* initial-source]
        (dotimes [_ cycles]
          (let [edge (r/spawn-ask (mapv (fn [h] [child "finite host callback" h]) hs))
                inbox (await-envelope! :parent)]
            (check! (integer? edge) "Spawn did not return edge" {})
            (check! (= 1 (count inbox)) "Spawn returned wrong envelope count" {})
            (check! (= hs (mapv :body (get-in inbox [0 :message :body]))) "Spawn fanin mismatch" {})
            (check! (empty? (:edges (c/snapshot))) "Spawn retained edge" {})
            ;; The reply can arrive before retirement finishes; prove retirement explicitly.
            (await-state! #(every? (fn [h] (nil? (c/agent h))) hs)
                          "Direct-return children did not retire"))))
      (check! (= (* agents cycles) @calls) "Wrong dispatch callback count" {})
      {:agents agents :cycles cycles :spawned (* agents cycles)
       :callbacks @calls :remaining-edges (count (:edges (c/snapshot)))
       :remaining-children (count (filter c/agent hs))})))

(defn- dormant-setup [params]
  (let [fixture (new-fixture params)]
    (try
      (with-fixture fixture
        (let [calls (:callbacks @fixture)]
          (doseq [h (:handles @fixture)]
            (r/start-box h (fn [_] (swap! calls inc) :done) initial-source :parent))))
      fixture
      (catch Throwable e
        (close-fixture! fixture)
        (throw e)))))

(defn- dormant-measure [{:keys [agents cycles]} fixture]
  (with-fixture fixture
    (let [hs (:handles @fixture)
          calls (:callbacks @fixture)
          initial-generations (mapv #(:generation (c/agent %)) hs)]
      (dotimes [cycle cycles]
        (let [completed (mapv #(:completed (c/agent %)) hs)
              until (deadline)]
          (binding [r/*current-handle* :parent]
            (doseq [h hs] (r/send h :ping)))
          (check! (every? #{:done} (mapv #(bounded % until) completed))
                  "Dormant wake returned wrong value" {})
          (await-state! #(every? (fn [h]
                                  (let [a (c/agent h)]
                                    (and (= :finished (:status a))
                                         (nil? (:runner a)))))
                                hs)
                        "Dormant agent failed to settle after completion")
          (check! (= (mapv #(+ % (inc cycle)) initial-generations)
                     (mapv #(:generation (c/agent %)) hs))
                  "Dormant generation mismatch" {:cycle cycle})))
      (check! (= (* agents cycles) @calls) "Wrong wake callback count" {})
      (check! (empty? (:edges (c/snapshot))) "Wake retained unexpected edges" {})
      ;; Leave actual coordinator/context/globals reachable for the harness memory pass.
      {:agents agents :cycles cycles :callbacks @calls
       :generation-increments (* agents cycles)
       :dormant (count (filter #(= :finished (:status (c/agent %))) hs))
       :active-runners (count (filter #(some? (:runner (c/agent %))) hs))
       :remaining-edges (count (:edges (c/snapshot)))})))

(defn- api-setup [_]
  ;; The API, not this fixture, owns fresh per-run stores. These observer references
  ;; are bounded to adjacent runs and explicitly released before memory inspection.
  (atom {:observed (atom nil) :previous (atom nil)}))

(defn- api-cleanup! [fixture]
  (when-let [{:keys [observed previous]} @fixture]
    (reset! observed nil)
    (reset! previous nil)
    (reset! fixture nil)))

(defn- api-measure [{:keys [cycles]} fixture]
  (let [{:keys [observed previous]} @fixture
        provider (p/test-provider
                   {:response-fn
                    (fn [_]
                      (reset! observed {:coordinator c/*coordinator*
                                        :context context/*context*
                                        :globals globals/*store*})
                      "(def answer 42))")})]
    (try
      (dotimes [cycle cycles]
        (reset! observed nil)
        (let [result (api/run {:prompt "Return 42"
                               :model-profile provider
                               :agent-profile "config/agent-profiles/base-msg.agent.edn"})
              current @observed]
          (check! (not (contains? result :error)) "Public API failed"
                  {:cycle cycle :error (:error result)})
          (check! (= 42 (:result result)) "Public API wrong result" {:cycle cycle})
          (check! (some? current) "Provider did not observe API bindings" {:cycle cycle})
          (check! (:closed? @(:coordinator current)) "API coordinator not closed" {:cycle cycle})
          (check! (empty? (:edges @(:coordinator current))) "API retained request edges" {:cycle cycle})
          (when-let [prior @previous]
            (doseq [k [:coordinator :context :globals]]
              (check! (not (identical? (get prior k) (get current k)))
                      "Adjacent API runs reused state" {:cycle cycle :store k})))
          (reset! previous current)
          (reset! observed nil)))
      {:cycles cycles :successful-runs cycles :closed-coordinators cycles
       :adjacent-isolation-checks (* 3 (max 0 (dec cycles)))}
      (finally
        (reset! observed nil)
        (reset! previous nil)))))

(def scenarios
  [{:id :lifecycle-coordinator
    :description "Coordinator-only hyperedge fanout/fanin and FIFO mailbox batches"
    :params (vec (for [n [4 20] cycles [1 10] batch [16 256]]
                   {:agents n :cycles cycles :mailbox-batch batch}))
    :bounds {:max-agents 20 :max-cycles 10 :max-mailbox-batch 256 :wait-deadline-ms deadline-ms}
    :notes "No worker futures, evaluator, or model. One N-slot edge per cycle. All callbacks include correctness checks and cleanup in harness timing; explicit waits test signals without intentional sleeping."
    :setup new-fixture :measure coordinator-measure :cleanup close-fixture!}
   {:id :lifecycle-spawn-return
    :description "Production runtime future dispatch, tracked parent wait, ordered fanin, and retirement"
    :params (vec (for [n [4 20] cycles [1 10 100]] {:agents n :cycles cycles}))
    :bounds {:max-agents 20 :max-cycles 100 :wait-deadline-ms deadline-ms}
    :notes "Real runtime spawn-ask with deterministic metadata-marked host child, not compiled Spell evaluator or model cost. Wall time includes scheduling/blocking and bounded predicate backoff; caller-thread allocation excludes future threads. Children must retire before handle reuse."
    :setup new-fixture :measure spawn-measure :cleanup close-fixture!}
   {:id :lifecycle-dormant-wake
    :description "Production persistent box wake/finish cycles with live dormant fixture retention"
    :params (vec (for [n [4 20] cycles [1 10 100]] {:agents n :cycles cycles}))
    :bounds {:max-agents 20 :max-cycles 100 :wait-deadline-ms deadline-ms}
    :notes "Actual orphan futures, inbox rewriting, and runtime ownership with deterministic eval callback, not full evaluator/model cost. Fixture holds isolated coordinator/context/globals through memory measurement, then closes and clears references. Finished/runner-nil is a state observation, not a thread join. close! wakes but does not join runners; shared future-pool idle threads may persist. Blocking latency is not CPU cost."
    :setup dormant-setup :measure dormant-measure :cleanup close-fixture!}
   {:id :lifecycle-api-cleanup
    :description "Repeated deterministic public API runs, closure and adjacent store isolation"
    :params (mapv (fn [cycles] {:cycles cycles}) [1 10 100])
    :bounds {:max-cycles 100 :process-deadline-required true}
    :notes "Public api/run with TestProvider exercises actual compiler/evaluator and API cleanup. Checks result, closed coordinator, empty edges and fresh adjacent context/coordinator/globals. Keeps at most adjacent observer references and releases all before memory inspection. No long-soak or immediate JVM thread-count claim. API has no total-run timeout; caller must bound the process."
    :setup api-setup :measure api-measure :cleanup api-cleanup!}])
