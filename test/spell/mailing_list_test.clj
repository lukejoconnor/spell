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
    (if (contains? r :ok)
      (:ok r)
      (throw (ex-info (str (:err r)) r)))))
(defn eval-pattern [form]
  (locking coordinator/*coordinator*
    (when-not (coordinator/agent *handle*) (coordinator/register! *handle*)))
  (binding [runtime/*current-handle* *handle*
            eval/*spell-env* {'eval eval-source
                            'globals globals/globals-namespace
                            'patterns stdlib/patterns
                            'agents {:current-handle (fn [] *handle*)
                                     :send (fn [h msg] (*deliver* h msg))}}]
    (eval-source form)))
(defn call-pattern [op & args]
  (eval-pattern (list* (symbol "patterns" (name op))
                       (map #(list 'quote %) args))))
(defn init!
  ([] (init! {}))
  ([opts]
   (call-pattern :install :mailing-list)
   (call-pattern :call :mailing-list :init opts)))
(defn mail [op args]
  (call-pattern :call :mailing-list op args))
(defn create! [] (mail :create {:list :research :description "Research evidence"}))
(defn post! [s] (mail :post {:list :research :summary s :body {:evidence "full"}}))

(deftest basic-shared-source
  (globals/set-val :unrelated {:keep true})
  (is (= {:owner :main :global :mailing-list :ready true :lists [:general]
          :subscriptions [{:list :general :agent :main :epoch 1 :cursor 0}]} (init!)))
  (let [info (mail :info {})]
    (is (= #{:init :info :lists :create :subscribe :subscribe-many :unsubscribe
                :post :post! :notify :message :digest :ack :transact :change :digest-page :deliver} (set (:module-functions info))))
    (is (not (contains? info :operations))))
  (create!)
  (mail :subscribe {:list :research :from :earliest})
  (is (= 1 (:id (post! "First"))))
  (is (= "First" (-> (mail :digest {:list :research}) :messages first :summary)))
  (is (= {:evidence "full"} (get-in (mail :message {:list :research :id 1}) [:message :body])))
  (let [before (globals/get-val :mailing-list)]
    (is (thrown? Throwable (init!)))
  (is (= {:keep true} (globals/get-val :unrelated)))
    (is (= before (globals/get-val :mailing-list))))
  (eval-pattern
    '(patterns/update :mailing-list
       (fn [definition]
         (assoc-in definition [:functions :digest-page :source]
                   '(fn [b a] {:custom (:agent a)})))))
  (binding [*handle* :fresh]
    (is (= {:custom :fresh} (mail :digest {})))))

(deftest digest-diagnostics-preserve-the-entire-board
  (init! {:page-size 1})
  (create!)
  (binding [*handle* :creator]
    (mail :create {:list :unsubscribed :description "Existing but not subscribed"}))
  (mail :subscribe {:list :research :from :earliest})
  (binding [*handle* :other-reader]
    (mail :subscribe {:list :research :from :earliest}))
  (post! "First")
  (post! "Second")
  (mail :ack {:token (:token (mail :digest {:list :research}))})
  (let [before (globals/get-val :mailing-list)
        subscriptions (into {} (map (fn [[k ls]] [k (:subscriptions ls)]) (:lists before)))
        example "(patterns/call :mailing-list :digest {:list :research})"
        keyword-error (re-pattern (java.util.regex.Pattern/quote
                                   (str "mail: :digest requires a keyword :list, e.g. " example)))
        plural-error (re-pattern (java.util.regex.Pattern/quote
                                  (str "mail: :digest accepts singular :list, not :lists; call once per list, e.g. " example)))
        unknown-error (re-pattern (java.util.regex.Pattern/quote
                                   "mail: :digest unknown list :missing; choose an existing list with (patterns/call :mailing-list :lists {}) or create it with (patterns/call :mailing-list :create {:list :missing :description \"Purpose\"}); then call (patterns/call :mailing-list :digest {:list :missing})"))
        subscription-error (re-pattern (java.util.regex.Pattern/quote
                                        "mail: :digest subscribe before reading a digest for :unsubscribed; call (patterns/call :mailing-list :subscribe {:list :unsubscribed}), then (patterns/call :mailing-list :digest {:list :unsubscribed})"))
        cases (concat
                (map #(vector % #"mail: :digest arguments must be a map")
                     [nil false 42 "research" [] [:research] '(:research)])
                (map #(vector % keyword-error)
                     [{} {:list nil} {:list "research"} {:list [:research]} {:list 42}])
                (map #(vector % plural-error)
                     [{:lists [:research]} {:lists nil}
                      {:list :research :lists [:research]}
                      {:list :missing :lists [:research]}])
                [[{:list :missing} unknown-error]
                 [{:list :missing :limit 0} unknown-error]
                 [{:list :unsubscribed} subscription-error]
                 [{:list :unsubscribed :limit 0} subscription-error]
                 [{:list :research :limit 0} #"mail: :digest :limit must be 1-100"]])]
    ;; Exercise both public dispatch (before assoc) and the editable pure helper.
    (doseq [[args diagnostic] cases
            route [:dispatch :helper]]
      (testing (str route " " (pr-str args))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo diagnostic
              (if (= :dispatch route)
                (mail :digest args)
                (call-pattern :call :mailing-list :digest-page before
                              (if (map? args) (assoc args :agent :main) args)))))
        (is (= before (globals/get-val :mailing-list))
            "All board data, owner, counters, retention and subscriptions are unchanged")
        (is (= subscriptions
               (into {} (map (fn [[k ls]] [k (:subscriptions ls)])
                             (:lists (globals/get-val :mailing-list)))))
            "No subscription was added, removed, advanced or re-epoched")))
    (let [page (mail :digest {:list :research})]
      (is (= 1 (:cursor page)))
      (is (= [2] (mapv :id (:messages page))))
      (is (= before (globals/get-val :mailing-list))))))

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
        (is (= 4 (count (:deliveries r))))
        (is (= #{:main :a :c} (set (map first @sent))))
        (is (= [:bad] (mapv :agent (filter #(false? (:sent %)) (:deliveries r)))))
      (is (= 4 (count (:deliveries (mail :notify {:list :research}))))))))

)

(deftest per-run-store-isolation
  (init!) (create!) (post! "Run A")
  (let [first-store globals/*store*]
    (binding [globals/*store* (globals/new-store)]
      (is (thrown? Throwable (mail :info {})))
      (init!)
      (is (= [:general] (mapv :list (mail :lists {})))))
    (is (= 2 (get-in @first-store [:mailing-list :lists :research :next])))))

(deftest validation-and-atomic-multi-subscribe
  (doseq [opts [{:retention 0} {:page-size 101} {:max-lists 129}
               {:max-subscribers 65} {:max-message-chars 65537} {:unknown true}]]
    (is (thrown? Throwable (init! opts)))
    (is (nil? (globals/get-val :mailing-list))))
  (init! {:lists [:research] :max-lists 1 :max-subscribers 1 :max-message-chars 200})
  (is (thrown? Throwable (mail :create {:list :other :description "other"})))
  (is (thrown? Throwable (mail :subscribe-many {:lists [:research :missing]})))
  (is (= #{:main} (set (keys (get-in (globals/get-val :mailing-list) [:lists :research :subscriptions])))))
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
    (is (= #{:main :a :b} (set @sent)) "Delivery uses post-commit subscriber snapshot"))
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
      (is (= 3 (count (filter :sent (:deliveries r)))))
      (is (= [:missing] (mapv :agent (remove :sent (:deliveries r)))))
      (doseq [h [:reader-a :reader-b]]
        (is (= [{:message {:from :main :body {:kind :mailing-list :list :research :through 2}}}]
               (get-in (coordinator/snapshot) [:agents h :mailbox]))))))
  (is (empty? (:edges (coordinator/snapshot))) "Notifications create no blocking edges"))

(deftest public-api-run-isolation
  (let [run (fn [label]
              (api/run
                {:init (str "(quine completion (eval (do '(do (patterns/install :mailing-list) (patterns/call :mailing-list :init {}) "
                            "(patterns/call :mailing-list :create {:list :research :description " (pr-str label) "}) "
                            "(patterns/call :mailing-list :lists {})))))")
                 :model-profile (provider/test-provider {:response "unused"})
                 :agent-profile "config/agent-profiles/cli.agent.edn"}))
        results (mapv #(future (run %)) ["run-A" "run-B"])]
    (is (= #{"run-A" "run-B"}
           (set (map #(-> (deref % 20000 {}) :result second :description) results))))
    (is (nil? (globals/get-val :mailing-list)) "API stores do not leak to enclosing run")))

;; Test-local composition: a caller-selected compiled child plus ordinary tracked
;; spawn-ask and a small startup program. No board-owned spawn/lifecycle helper.
(defn run-configured-board-child [response lists recovery?]
  (let [namespaces {'patterns stdlib/patterns 'globals globals/globals-namespace
                    'agents runtime/agents-namespace}
        child (th/make-test-agent {:response-fn response}
                                 :recover recovery? :prefill? false :namespaces namespaces)
        parent (th/make-test-agent
                 {:response-fn (fn [p]
                                 (str "'(audit/finish " (last (re-seq #"msg-[0-9]+" p)) ")))"))}
                 :recover false :prefill? false
                 :namespaces (assoc namespaces 'fixture {:child (fn [] child)}
                                    'audit {:finish (fn [v] (coordinator/close!) v)}))
        startup (str "(quine completion (eval (do '(let [failure (try (do "
                     "(patterns/install :mailing-list) "
                     "(patterns/call :mailing-list :subscribe-many {:lists " (pr-str lists) " :from :earliest}) nil) "
                     "(catch e {:spell/child-failure true :phase :onboarding :handle :board-worker :error (str e)}))] "
                     "(if failure failure (!llm-self (wrap-cat \"Subscriptions installed. Do NOT call patterns/call :mailing-list :init. Perform the assigned task.\") {:receive? true}))))))")]
    (th/run-agent-init parent
      (str "(quine completion (eval (do '(do (agents/spawn-ask (fixture/child) "
           (pr-str startup) " :board-worker) (agents/!wait)))))"))))

(deftest configured-child-onboarding-preserves-edited-source
  (init! {:lists [:research]})
  (eval-pattern '(patterns/update :mailing-list
                   (fn [definition]
                     (assoc-in definition [:functions :digest-page :source]
                               '(fn [board args] {:custom true :agent (:agent args)
                                                  :count (count (get-in board [:lists :research :messages]))})))))
  (let [calls (atom 0)
        value (run-configured-board-child
                (fn [p]
                  (swap! calls inc)
                  (is (= :board-worker runtime/*current-handle*) "Explicit configured child selected")
                  (is (some? (get-in (globals/get-val :mailing-list)
                                    [:lists :research :subscriptions :board-worker]))
                      "Subscription exists before the first child model generation")
                  (is (.contains ^String p "Do NOT call patterns/call :mailing-list :init"))
                  "'(do (patterns/call :mailing-list :post {:list :research :summary \"Worker evidence\"}) (patterns/call :mailing-list :digest {:list :research}))))")
                [:research] false)]
    (is (= {:custom true :agent :board-worker :count 1} (:body value)))
    (is (integer? (:edge-id value)) "Tracked result collection delivers an actual edge ID")
    (is (= 1 @calls))
    (is (= :board-worker (get-in (globals/get-val :mailing-list) [:lists :research :messages 0 :author])))))

(deftest configured-child-onboarding-failure-is-tracked-and-atomic
  (doseq [recovery? [true false]]
    (testing (str "onboarding failure with recovery=" recovery?)
      (th/with-test-run
        (fn []
          (init! {:lists [:research :second] :max-subscribers 1})
          ;; First list has capacity; the second remains full. A failed transaction
          ;; must not leave the first child subscription behind.
          (mail :unsubscribe {:list :research})
          (let [calls (atom 0)
                before (globals/get-val :mailing-list)
                value (run-configured-board-child
                        (fn [_] (swap! calls inc) "':should-not-generate)))")
                        [:research :second] recovery?)]
            (is (true? (get-in value [:body :spell/child-failure])))
            (is (= :onboarding (get-in value [:body :phase])))
            (is (= :board-worker (get-in value [:body :handle])))
            (is (integer? (:edge-id value)))
            (is (re-find #":max-subscribers limit 1 reached for :second" (get-in value [:body :error])))
            (is (zero? @calls))
            (is (= before (globals/get-val :mailing-list)))))))))

(deftest configured-child-task-recovery-remains-enabled-after-onboarding
  (init! {:lists [:research]})
  (let [calls (atom 0)
        value (run-configured-board-child
                (fn [_]
                  (is (some? (get-in (globals/get-val :mailing-list)
                                    [:lists :research :subscriptions :board-worker])))
                  (if (= 1 (swap! calls inc))
                    "'(throw \"Task error after successful onboarding\")))"
                    "':task-recovered)))"))
                [:research] true)]
    (is (= :task-recovered (:body value)))
    (is (= 2 @calls))))

(deftest direct-catalog-is-inert-and-has-no-dispatch-or-spawn-aliases
  (let [consumers #{:init :info :lists :create :subscribe :subscribe-many :unsubscribe
                    :post :post! :notify :message :digest :ack}
        helpers #{:transact :change :digest-page :deliver}
        catalog (call-pattern :catalog :mailing-list)]
    (is (nil? (globals/get-val :mailing-list)))
    (is (= (into consumers helpers) (set (keys (:functions catalog)))))
    (doseq [entry consumers]
      (is (= 1 (count (get-in catalog [:functions entry :params]))))
      (is (re-find #"Example:" (get-in catalog [:functions entry :doc])))
      (is (nil? (get-in catalog [:functions entry :source]))))
    (doseq [entry helpers]
      (is (re-find #"^Internal" (get-in catalog [:functions entry :doc]))))
    (call-pattern :install :mailing-list)
    (call-pattern :install :mailing-list)
    (is (nil? (globals/get-val :mailing-list)) "Install/reinstall never initializes or subscribes")
    (doseq [entry [:call :spawn]]
      (is (nil? (call-pattern :source :mailing-list entry)))
      (is (thrown? Throwable (call-pattern :call :mailing-list entry {}))))
    (doseq [entry (disj consumers :init)]
      (is (thrown-with-msg? Throwable #"board not initialized" (mail entry {}))))
    (is (nil? (globals/get-val :mailing-list)))))

(deftest named-initialization-and-create-return-actual-atomic-subscriptions
  (let [receipt (init! {:lists [:design :implementation] :retention 7 :page-size 2})
        board (globals/get-val :mailing-list)]
    (is (= [:design :implementation] (:lists receipt)))
    (is (= [{:list :design :agent :main :epoch 1 :cursor 0}
            {:list :implementation :agent :main :epoch 2 :cursor 0}]
           (:subscriptions receipt)))
    (is (= #{:design :implementation} (set (keys (:lists board)))))
    (is (= 7 (get-in board [:config :retention])))
    (is (= receipt (:last-result board)))
    (doseq [sub (:subscriptions receipt)]
      (is (= (select-keys sub [:epoch :cursor])
             (get-in board [:lists (:list sub) :subscriptions (:agent sub)])))))
  (let [receipt (binding [*handle* :creator] (mail :create {:list :research :description "Evidence"}))]
    (is (= {:list :research :created true
            :subscription {:list :research :agent :creator :epoch 3 :cursor 0}} receipt))
    (is (= {:epoch 3 :cursor 0}
           (get-in (globals/get-val :mailing-list) [:lists :research :subscriptions :creator])))
    (is (= (:subscription receipt)
           (mail :subscribe {:list :research :agent :creator :from :latest}))))
  (let [before (globals/get-val :mailing-list)]
    (is (thrown-with-msg? Throwable #":init already initialized" (init! {:lists [:replacement]})))
    (is (= before (globals/get-val :mailing-list)))
    (is (thrown-with-msg? Throwable #":create :description" (mail :create {:list :invalid :description nil})))
    (is (= before (globals/get-val :mailing-list)) "Failed creator subscription cannot leave a list behind")))

(deftest initialization-options-and-list-vectors-have-visible-limits
  (doseq [[options diagnostic]
          [[nil #":init arguments must be a map"]
           [false #":init arguments must be a map"]
           [{:lists nil} #":init :lists.*nonempty distinct keyword vector.*32"]
           [{:lists []} #":init :lists.*nonempty distinct keyword vector"]
           [{:lists '(:design)} #":init :lists"]
           [{:lists [:design :design]} #":init :lists"]
           [{:lists [:design "implementation"]} #":init :lists"]
           [{:lists [:design :implementation] :max-lists 1} #":init :lists.*1"]
           [{:lists [(keyword (apply str (repeat 129 "x")))]} #":init :list.*128"]
           [{:retention 2001} #":init :retention.*1-2000"]
           [{:page-size 0} #":init :page-size.*1-100"]
           [{:max-lists 0} #":init :max-lists.*1-128"]
           [{:max-subscribers 0} #":init :max-subscribers.*1-64"]
           [{:max-message-chars 0} #":init :max-message-chars.*1-65536"]
           [{:extra true} #":init unknown option fields.*:extra"]]]
    (is (thrown-with-msg? Throwable diagnostic (init! options)) (pr-str options))
    (is (nil? (globals/get-val :mailing-list))))
  (init! {:lists [:design :implementation]})
  (let [before (globals/get-val :mailing-list)]
    (doseq [lists [nil [] '(:design) [:design :design] [:design "implementation"]]]
      (is (thrown-with-msg? Throwable #":subscribe-many :lists"
                           (mail :subscribe-many {:lists lists :agent :reader})))
      (is (= before (globals/get-val :mailing-list))))
    (is (thrown-with-msg? Throwable #":subscribe-many :list.*128"
                         (mail :subscribe-many {:lists [:design (keyword (apply str (repeat 129 "x")))] :agent :reader})))
    (is (= before (globals/get-val :mailing-list)))
    (is (thrown-with-msg? Throwable #":subscribe-many unknown :list :missing"
                         (mail :subscribe-many {:lists [:design :missing] :agent :reader})))
    (is (= before (globals/get-val :mailing-list)))))

(deftest independent-list-cursors-and-snapshot-ack
  (init! {:lists [:design :implementation] :page-size 1})
  (doseq [k [:design :implementation] n [1 2]]
    (mail :post {:list k :summary (str k n)}))
  (let [design (mail :digest {:list :design})]
    (is (= 1 (count (:messages design))))
    (mail :subscribe {:list :implementation :agent :another-reader})
    (is (= 1 (:cursor (mail :ack {:token (:token design)}))))
    (is (= 0 (:cursor (mail :digest {:list :implementation}))))
    (is (= [2] (mapv :id (:messages (mail :digest {:list :design})))))
    (let [resubscribed (mail :subscribe-many {:lists [:design :implementation] :from :earliest})]
      (is (= [1 0] (mapv :cursor (:subscriptions resubscribed)))))
    (mail :post {:list :design :summary "Arrived after the read"})
    (is (= 1 (:cursor (mail :ack {:token (:token design)}))))
    (is (= [2 3] (mapv :id (:messages (mail :digest {:list :design :limit 100})))))))

(deftest selected-change-source-does-not-resolve-new-change-during-multi-subscribe
  (init! {:lists [:design :implementation]})
  (let [selected (:source (call-pattern :source :mailing-list :change))
        before (globals/get-val :mailing-list)]
    (eval-pattern '(patterns/update :mailing-list
                     (fn [definition]
                       (assoc-in definition [:functions :change :source]
                                 '(fn [board operation args] (throw "incompatible latest transition"))))))
    ;; Model the entry snapshot already selected by an in-flight call: replacing
    ;; the registry before evaluation must not skew the second list to new source.
    (let [[after receipt]
          (eval-pattern (list selected (list 'quote before) :subscribe-many
                              (list 'quote {:lists [:design :implementation] :agent :reader :from :earliest})))]
      (is (= [:design :implementation] (mapv :list (:subscriptions receipt))))
      (is (= [3 4] (mapv :epoch (:subscriptions receipt))))
      (is (= [0 0] (mapv :cursor (:subscriptions receipt))))
      (is (= {:epoch 3 :cursor 0} (get-in after [:lists :design :subscriptions :reader])))
      (is (= {:epoch 4 :cursor 0} (get-in after [:lists :implementation :subscriptions :reader])))
      (is (= before (globals/get-val :mailing-list)) "Pure selected source does not mutate shared state"))
    (is (thrown-with-msg? Throwable #"incompatible latest transition"
                         (mail :subscribe-many {:lists [:design :implementation] :agent :reader})))))

(deftest concurrent-create-receipts-match-committed-creator-subscriptions
  (init!)
  (let [gate (promise)
        results (mapv (fn [n]
                        (future @gate
                                (binding [*handle* (keyword (str "creator-" n))]
                                  (mail :create {:list (keyword (str "list-" n)) :description "Concurrent"}))))
                      (range 12))]
    (deliver gate true)
    (let [receipts (mapv #(deref % 20000 :timeout) results)
          board (globals/get-val :mailing-list)]
      (is (every? map? receipts))
      (is (= (set (range 2 14)) (set (map #(get-in % [:subscription :epoch]) receipts))))
      (doseq [{:keys [list subscription]} receipts]
        (is (= list (:list subscription)))
        (is (= (select-keys subscription [:epoch :cursor])
               (get-in board [:lists list :subscriptions (:agent subscription)]))))
      (is (= 13 (count (:lists board)))))))

(deftest create-subscribes-actual-creator-not-supplied-agent
  (init!)
  (let [receipt (binding [*handle* :creator]
                  (mail :create {:list :research :description "Evidence" :agent :bystander}))
        subscriptions (get-in (globals/get-val :mailing-list) [:lists :research :subscriptions])]
    (is (= :creator (get-in receipt [:subscription :agent])))
    (is (= #{:creator} (set (keys subscriptions))))
    (is (= {:epoch 2 :cursor 0} (:creator subscriptions)))))
