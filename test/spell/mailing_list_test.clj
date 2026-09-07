(ns spell.mailing-list-test
  (:require [clojure.test :refer :all]
            [spell.test-helpers :as th]
            [spell.eval :as eval]
            [spell.api :as api]
            [spell.provider :as provider]
            [spell.globals :as globals]
            [spell.runtime :as runtime]
            [spell.coordinator :as coordinator]
            [spell.stdlib :as stdlib]))

(use-fixtures :each th/with-test-run)

(def ^:dynamic *handle* :main)
(def ^:dynamic *deliver* (fn [_ _] nil))
(defn eval-source [form]
  (let [r (eval/spell-eval form eval/*spell-env*)]
    (if (eval/ok? r) (:ok r) (throw (ex-info (:err r) {})))))
(defn call-pattern [op args]
  (binding [eval/*spell-env* {'eval eval-source
                            'globals globals/globals-namespace
                            'patterns stdlib/patterns
                            'agents {:current-handle (fn [] *handle*)
                                     :send (fn [h msg] (*deliver* h msg))}}]
    (eval/invoke-fn (get stdlib/patterns op) [args])))
(defn init! ([] (init! {})) ([opts] (call-pattern :mailing-list opts)))
(defn mail [op args]
  (binding [eval/*spell-env* {'eval eval-source
                            'globals globals/globals-namespace
                            'patterns stdlib/patterns
                            'agents {:current-handle (fn [] *handle*)
                                     :send (fn [h msg] (*deliver* h msg))}}]
    (eval/invoke-fn (:mail stdlib/patterns) [op args])))
(defn create! [] (mail :create {:list :research :description "Research evidence"}))
(defn post! [s] (mail :post {:list :research :summary s :body {:evidence "full"}}))

(deftest basic-shared-source
  (globals/set-val :unrelated {:keep true})
  (is (= {:owner :main :global :mailing-list :ready true} (init!)))
  (create!)
  (mail :subscribe {:list :research :from :earliest})
  (is (= 1 (:id (post! "First"))))
  (is (= "First" (-> (mail :digest {:list :research}) :messages first :summary)))
  (is (= {:evidence "full"} (get-in (mail :message {:list :research :id 1}) [:message :body])))
  (let [before (globals/get-val :mailing-list)]
    (is (thrown? Throwable (init!)))
    (is (= before (globals/get-val :mailing-list))))
  (is (= {:keep true} (globals/get-val :unrelated)))
  (globals/update-val :mailing-list
    #(assoc-in % [:code :digest] '(fn [b a] {:custom (:agent a)})))
  (binding [*handle* :fresh]
    (is (= {:custom :fresh} (mail :digest {})))))

(deftest retention-and-monotone-ack
  (init! {:retention 3 :page-size 2}) (create!)
  (mail :subscribe {:list :research :from :earliest})
  (doseq [n (range 5)] (post! (str n)))
  (let [p1 (mail :digest {:list :research})
        p2 (mail :digest {:list :research :limit 3})]
    (is (= {:from 1 :through 2} (:gap p1)))
    (is (= [3 4] (mapv :id (:messages p1))))
    (is (= 1 (:remaining p1)))
    (is (= p1 (mail :digest {:list :research})))
    (mail :ack {:token (:token p2)})
    (is (= 5 (:cursor (mail :ack {:token (:token p1)}))))
    (is (empty? (:messages (mail :digest {:list :research}))))
    (mail :unsubscribe {:list :research})
    (mail :subscribe {:list :research :from :earliest})
    (is (thrown? Throwable (mail :ack {:token (:token p1)}))))
  (is (:expired? (mail :message {:list :research :id 1}))))

(deftest initialization-and-writer-races
  (let [gate (promise)
        results (mapv (fn [n] (future @gate
                               (binding [*handle* (keyword (str "owner-" n))]
                                 (try (init!) (catch Throwable _ :duplicate))))) (range 8))]
    (deliver gate true)
    (let [r (mapv #(deref % 20000 :timeout) results)]
      (is (= 1 (count (filter map? r))))
      (is (= 7 (count (filter #{:duplicate} r))))
      (is (= (:owner (first (filter map? r))) (:owner (globals/get-val :mailing-list))))))
  (create!)
  (let [gate (promise)
        writers (mapv (fn [n] (future @gate (post! (str n)))) (range 40))]
    (deliver gate true)
    (is (= (set (range 1 41)) (set (map #(-> (deref % 20000 {}) :id) writers))))
    (is (= (vec (range 1 41)) (mapv :id (get-in (globals/get-val :mailing-list) [:lists :research :messages]))))))

(deftest quiet-and-explicit-delivery
  (init!) (create!)
  (doseq [h [:a :bad :c]] (mail :subscribe {:list :research :agent h}))
  (let [sent (atom [])]
    (binding [*deliver* (fn [h m] (if (= h :bad) (throw (ex-info "retired" {})) (swap! sent conj [h m])))]
      (post! "Quiet")
      (is (empty? @sent))
      (let [r (mail :post! {:list :research :summary "Wake"})]
        (is (= 2 (:id r)))
        (is (= 3 (count (:deliveries r))))
        (is (= #{:a :c} (set (map first @sent))))
        (is (= [:bad] (mapv :agent (filter #(false? (:sent %)) (:deliveries r)))))
      (is (= 3 (count (:deliveries (mail :notify {:list :research}))))))))

)

(deftest per-run-store-isolation
  (init!) (create!) (post! "Run A")
  (let [first-store globals/*store*]
    (binding [globals/*store* (globals/new-store)]
      (is (thrown? Throwable (mail :info {})))
      (init!)
      (is (empty? (mail :lists {}))))
    (is (= 2 (get-in @first-store [:mailing-list :lists :research :next])))))

(deftest real-agent-spawn-onboarding-and-customization
  (let [seen (atom [])
        result (atom nil)
        agent (th/make-test-agent
                {:response-fn
                 (fn [p]
                   (swap! seen conj {:handle runtime/*current-handle* :prompt p})
                   (if (= :board-worker runtime/*current-handle*)
                     (do
                       (is (some? (get-in (globals/get-val :mailing-list)
                                         [:lists :research :subscriptions :board-worker]))
                           "Subscription exists BEFORE first model generation")
                       "'(do (patterns/mail :post {:list :research :summary \"Worker evidence\"}) (patterns/mail :digest {:list :research}))))")
                     (let [msg (last (re-seq #"msg-[0-9]+" p))]
                       (str "'(audit/finish " msg ")))"))))}
                :recover false :prefill? false
                :namespaces {'patterns stdlib/patterns
                             'globals globals/globals-namespace
                             'agents runtime/agents-namespace
                             'audit {:finish (fn [v] (reset! result v) (coordinator/close!) v)}})
        value (th/run-agent-init agent
                "(quine completion (eval (do '(do
                   (patterns/mailing-list {})
                   (patterns/mail :create {:list :research :description \"Evidence\"})
                   (globals/update :mailing-list
                     (fn [b] (assoc-in b [:code :digest]
                                      '(fn [board args] {:custom true :agent (:agent args)
                                                        :count (count (get-in board [:lists :research :messages]))}))))
                   (patterns/mail :spawn {:task \"Post evidence and return a digest\"
                                          :handle :board-worker :lists [:research]})
                   (agents/!wait)))))")]
    (is (= true (get-in value [:body :custom])))
    (is (= :board-worker (get-in value [:body :agent])))
    (is (= 1 (get-in value [:body :count])))
    (is (some #(and (= :board-worker (:handle %))
                    (.contains ^String (:prompt %) "Do NOT initialize")) @seen))
    (is (= :board-worker (get-in (globals/get-val :mailing-list)
                                [:lists :research :messages 0 :author])))))

(deftest validation-and-atomic-multi-subscribe
  (doseq [opts [{:retention 0} {:page-size 101} {:max-lists 129}
               {:max-subscribers 65} {:max-message-chars 65537} {:unknown true}]]
    (is (thrown? Throwable (init! opts)))
    (is (nil? (globals/get-val :mailing-list))))
  (init! {:max-lists 1 :max-subscribers 1 :max-message-chars 200})
  (create!)
  (is (thrown? Throwable (mail :create {:list :other :description "other"})))
  (is (thrown? Throwable (mail :subscribe-many {:lists [:research :missing]})))
  (is (empty? (get-in (globals/get-val :mailing-list) [:lists :research :subscriptions])))
  (let [s (mail :subscribe {:list :research})]
    (is (= s (mail :subscribe {:list :research :from :earliest}))))
  (is (thrown? Throwable (mail :subscribe {:list :research :agent :other})))
  (is (thrown? Throwable (post! (apply str (repeat 501 "x")))))
  (is (thrown? Throwable (mail :post {:list :research :summary "large" :body (apply str (repeat 201 "x"))})))
  (is (= 1 (:id (post! "valid"))) "Rejected writes do not consume IDs")
  (doseq [args [{:list :research :limit 0} {:list :missing}]]
    (is (thrown? Throwable (mail :digest args))))
  (let [token (:token (mail :digest {:list :research}))]
    (is (thrown? Throwable (mail :ack {:token (assoc token :through 2)})))
    (is (thrown? Throwable (mail :ack {:token token :agent :other})))))

(deftest ack-requires-wrapped-non-nil-token
  (init!) (create!)
  (mail :subscribe {:list :research :from :earliest})
  (post! "pending")
  (let [token (:token (mail :digest {:list :research}))
        before (globals/get-val :mailing-list)]
    (doseq [args [token {} {:list :research} {:token nil}
                 {:list :research :token nil}]]
      (is (thrown-with-msg? Throwable #":ack requires \{:token page-token\}"
                           (mail :ack args))
          (str "Malformed acknowledgement: " args))
      (is (= before (globals/get-val :mailing-list))
          "Malformed acknowledgements leave subscriptions and cursors unchanged"))
    (is (= (:through token) (:cursor (mail :ack {:token token}))))))

(deftest ack-wrong-agent-guard-precedes-watermark-validation
  (init!) (create!)
  (mail :subscribe {:list :research :from :earliest})
  (binding [*handle* :other]
    (mail :subscribe {:list :research :from :earliest}))
  (post! "pending")
  (let [token (:token (mail :digest {:list :research}))
        before (globals/get-val :mailing-list)]
    (binding [*handle* :other]
      (doseq [rejected-token [token (assoc token :through -1)]]
        (is (thrown-with-msg? Throwable #"stale subscription token or wrong agent"
                             (mail :ack {:token rejected-token})))
        (is (= before (globals/get-val :mailing-list))
            "Wrong-agent acknowledgements leave all subscriptions unchanged")))
    (is (= (:through token) (:cursor (mail :ack {:token token}))))))

(deftest ack-stale-epoch-guard-precedes-watermark-validation
  (init!) (create!)
  (mail :subscribe {:list :research :from :earliest})
  (post! "pending")
  (let [token (:token (mail :digest {:list :research}))]
    (mail :unsubscribe {:list :research})
    (mail :subscribe {:list :research :from :earliest})
    (let [before (globals/get-val :mailing-list)]
      (doseq [rejected-token [token (assoc token :through -1)]]
        (is (thrown-with-msg? Throwable #"stale subscription token or wrong agent"
                             (mail :ack {:token rejected-token})))
        (is (= before (globals/get-val :mailing-list))
            "Stale acknowledgements leave the replacement subscription unchanged")))
    (let [fresh-token (:token (mail :digest {:list :research}))]
      (is (not= (:epoch token) (:epoch fresh-token)))
      (is (= (:through fresh-token)
             (:cursor (mail :ack {:token fresh-token})))))))

(deftest notification-snapshot-and-ack-concurrent-post
  (init!) (create!)
  (mail :subscribe {:list :research :agent :a :from :earliest})
  (mail :subscribe {:list :research :agent :b :from :earliest})
  (let [sent (atom [])]
    (binding [*deliver* (fn [h m]
                         (swap! sent conj h)
                         (mail :unsubscribe {:list :research :agent :b})
                         (mail :subscribe {:list :research :agent :c}))]
      (mail :post! {:list :research :summary "snapshot"}))
    (is (= #{:a :b} (set @sent)) "Delivery uses post-commit subscriber snapshot"))
  (let [p (mail :digest {:list :research :agent :a})]
    (post! "concurrent new evidence")
    (mail :ack {:agent :a :token (:token p)})
    (is (= [2] (mapv :id (:messages (mail :digest {:list :research :agent :a})))))))

(deftest real-coordinator-quiet-and-notify
  (init!) (create!)
  (doseq [h [:reader-a :reader-b]]
    (coordinator/register! h)
    (mail :subscribe {:list :research :agent h}))
  (mail :subscribe {:list :research :agent :missing})
  (binding [*deliver* runtime/send runtime/*current-handle* :main]
    (post! "quiet")
    (is (every? #(empty? (get-in (coordinator/snapshot) [:agents % :mailbox]))
                [:reader-a :reader-b]))
    (let [r (mail :post! {:list :research :summary "urgent"})]
      (is (= 2 (count (filter :sent (:deliveries r)))))
      (is (= [:missing] (mapv :agent (remove :sent (:deliveries r)))))
      (doseq [h [:reader-a :reader-b]]
        (is (= [{:message {:from :main :body {:kind :mailing-list :list :research :through 2}}}]
               (get-in (coordinator/snapshot) [:agents h :mailbox]))))))
  (is (empty? (:edges (coordinator/snapshot))) "Notifications create no blocking edges"))

(deftest public-api-run-isolation
  (let [run (fn [label]
              (api/run
                {:init (str "(quine completion (eval (do '(do (patterns/mailing-list {}) "
                            "(patterns/mail :create {:list :research :description " (pr-str label) "}) "
                            "(patterns/mail :lists {})))))")
                 :model-profile (provider/test-provider {:response "unused"})
                 :agent-profile "config/agent-profiles/cli.agent.edn"}))
        results (mapv #(future (run %)) ["run-A" "run-B"])]
    (is (= #{"run-A" "run-B"}
           (set (map #(-> (deref % 20000 {}) :result first :description) results))))
    (is (nil? (globals/get-val :mailing-list)) "API stores do not leak to enclosing run")))

(deftest onboarding-failure-is-tracked-and-atomic
  (doseq [recovery? [true false]]
    (testing (str "onboarding failure with recovery=" recovery?)
      (th/with-test-run
        (fn []
          (init! {:max-subscribers 1})
          (create!)
          (mail :create {:list :second :description "Second list"})
          (mail :subscribe {:list :second :agent :full})
          (let [worker-calls (atom 0)
                agent (th/make-test-agent
                        {:response-fn
                         (fn [p]
                           (if (= :full-worker runtime/*current-handle*)
                             (do (swap! worker-calls inc) "':should-not-generate)))")
                             (str "'(audit/finish " (last (re-seq #"msg-[0-9]+" p)) ")))")))}
                        :recover recovery? :prefill? false
                        :namespaces {'patterns stdlib/patterns
                                     'globals globals/globals-namespace
                                     'agents runtime/agents-namespace
                                     'audit {:finish (fn [v] (coordinator/close!) v)}})
                value (th/run-agent-init agent
                        "(quine completion (eval (do '(do
                           (patterns/mail :spawn {:task \"No capacity\" :handle :full-worker
                                                  :lists [:research :second]})
                           (agents/!wait)))))")]
            (is (true? (get-in value [:body :spell/child-failure])))
            (is (= :onboarding (get-in value [:body :phase])))
            (is (= :full-worker (get-in value [:body :handle])))
            (is (re-find #"subscriber limit reached" (get-in value [:body :error])))
            (is (zero? @worker-calls))
            (is (nil? (get-in (globals/get-val :mailing-list)
                             [:lists :research :subscriptions :full-worker])))))))))

(deftest task-recovery-remains-enabled-after-onboarding
  (init!)
  (create!)
  (let [worker-calls (atom 0)
        agent (th/make-test-agent
                {:response-fn
                 (fn [p]
                   (if (= :recovering-worker runtime/*current-handle*)
                     (do
                       (is (some? (get-in (globals/get-val :mailing-list)
                                         [:lists :research :subscriptions :recovering-worker])))
                       (if (= 1 (swap! worker-calls inc))
                         "'(throw \"Task error after successful onboarding\")))"
                         "':task-recovered)))"))
                     (str "'(audit/finish " (last (re-seq #"msg-[0-9]+" p)) ")))")))}
                :prefill? false
                :namespaces {'patterns stdlib/patterns
                             'globals globals/globals-namespace
                             'agents runtime/agents-namespace
                             'audit {:finish (fn [v] (coordinator/close!) v)}})
        value (th/run-agent-init agent
                "(quine completion (eval (do '(do
                   (patterns/mail :spawn {:task \"Recover from a task error\"
                                          :handle :recovering-worker :lists [:research]})
                   (agents/!wait)))))")]
    (is (= :task-recovered (:body value)))
    (is (= 2 @worker-calls))))
