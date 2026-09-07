(ns spell.reliability-workflow-test
  (:require [clojure.test :refer :all]
            [spell.test-helpers :as th]
            [spell.eval :as eval]
            [spell.globals :as globals]
            [spell.stdlib :as stdlib]
            [spell.runtime :as runtime]
            [spell.coordinator :as coordinator]
            [clojure.java.io]
            [clojure.edn]))

(use-fixtures :each th/with-test-run)

(def ^:dynamic *handle* :main)
(def ^:dynamic *deliver* (fn [_ _] nil))

(defn eval-source [form]
  (let [result (eval/spell-eval form eval/*spell-env*)]
    (if (eval/ok? result)
      (:ok result)
      (throw (ex-info (:err result) {})))))

(defn invoke-pattern [op args]
  (binding [eval/*spell-env* {'eval eval-source
                            'globals globals/globals-namespace
                            'patterns stdlib/patterns
                            'agents {:current-handle (fn [] *handle*)
                                     :send (fn [h message] (*deliver* h message))}}]
    (eval-source (list* (symbol "patterns" (name op))
                        (map #(list 'quote %) args)))))

(defn mail [op args]
  (invoke-pattern :call [:mailing-list :call op args]))

(defn init-board! [opts]
  (invoke-pattern :install [:mailing-list])
  (invoke-pattern :call [:mailing-list :init opts]))

(deftest bounded-mailbox-failure-contracts
  ;; One successful initialization in this isolated run; duplicate init must
  ;; fail without replacing the board. All operations are offline and finite.
  (is (:ready (init-board! {:retention 3 :page-size 2})))
  (doseq [list-name [:design :implementation]]
    (mail :create {:list list-name :description (str list-name)})
    (mail :subscribe {:list list-name :from :earliest}))
  (let [before (globals/get-val :mailing-list)]
    (is (thrown? Throwable (init-board! {})))
    (is (= before (globals/get-val :mailing-list))))
  (let [deliveries (atom [])]
    (binding [*deliver* (fn [h message] (swap! deliveries conj [h message]))]
      (doseq [n (range 5)]
        (mail :post {:list :design :summary (str "design-" n)
                     :body {:sequence n}})))
    (is (empty? @deliveries) "Quiet posts must not send wakeups"))
  (let [small (mail :digest {:list :design :limit 2})
        page (mail :digest {:list :design :limit 3})]
    (is (= {:from 1 :through 2} (:gap page)))
    (is (= [3 4 5] (mapv :id (:messages page))))
    (is (= 1 (:remaining small)))
    (is (= page (mail :digest {:list :design :limit 3})))
    (is (= 5 (:cursor (mail :ack {:token (:token page)}))))
    (is (= 5 (:cursor (mail :ack {:token (:token small)}))))
    (is (empty? (:messages (mail :digest {:list :design :limit 3}))))
    (mail :unsubscribe {:list :design})
    (mail :subscribe {:list :design :from :earliest})
    (let [before (globals/get-val :mailing-list)]
      (is (thrown? Throwable (mail :ack {:token (:token page)})))
      (is (= before (globals/get-val :mailing-list)))))
  (is (:expired? (mail :message {:list :design :id 1}))))

;; Real deterministic provider -> compiled-agent -> coordinator workflow.
(def ^:dynamic *workflow-report-path* nil)

(defn response! [calls handle]
  (let [n (get (swap! calls update handle (fnil inc 0)) handle)]
    (when (> n 6)
      (throw (ex-info "Deterministic provider call bound exceeded" {:handle handle :calls n})))
    n))

(deftest real-workflow-completes-and-persists
  (let [calls (atom {})
        marks (atom [])
        returns (atom [])
        onboarding (atom #{})
        pages (atom [])
        report-file (if *workflow-report-path*
                      (clojure.java.io/file *workflow-report-path*)
                      (doto (java.io.File/createTempFile "spell-reliability-" ".edn")
                        (.deleteOnExit)))
        finish (fn [message]
                 (swap! returns conj message)
                 (let [report {:status :completed
                               :provider :deterministic-test
                               :child-returns @returns
                               :subscriptions-before-generation @onboarding
                               :provider-calls @calls
                               :self-call-effect-count (count @marks)
                               :design-pages @pages}]
                   (spit report-file (pr-str report))
                   (coordinator/close!)
                   report))
        agent (th/make-test-agent
                {:response-fn
                 (fn [p]
                   (let [h runtime/*current-handle*
                         n (response! calls h)]
                     (case h
                       :design-worker
                       (do
                         (when (= n 1)
                           (is (every? #(some? (get-in (globals/get-val :mailing-list)
                                                       [:lists % :subscriptions h]))
                                       [:design :implementation])
                               "Both subscriptions precede task generation")
                           (is (.contains ^String p "Do NOT call patterns/call :mailing-list :init"))
                           (swap! onboarding conj h))
                         (if (= n 1)
                           "(def local-evidence {:owner :design-worker :marker :retained}) '(do (audit/mark local-evidence) (!extend)))"
                           (do
                             (is (.contains ^String p "local-evidence") "Self-call keeps source context")
                             "'(do (patterns/call :mailing-list :call :post {:list :design :summary \"design complete\" :body local-evidence}) (audit/child local-evidence)))")))
                       :implementation-worker
                       (do
                         (when (= n 1)
                           (is (every? #(some? (get-in (globals/get-val :mailing-list)
                                                       [:lists % :subscriptions h]))
                                       [:design :implementation]))
                           (swap! onboarding conj h))
                         (if (= n 1)
                           "'(throw \"bounded task error after onboarding\")))"
                           "'(do (patterns/call :mailing-list :call :post {:list :implementation :summary \"implementation complete\" :body {:status :completed :task-recovery true}}) '{:status :completed :owner :implementation-worker :task-recovery true})))"))
                       :main
                       (let [message (last (re-seq #"msg-[0-9]+" p))]
                         (when-not message
                           (throw (ex-info (str "Main startup diagnostic: " (subs p (max 0 (- (count p) 900)))) {})))
                         (if (= n 1)
                           (str "'(do (audit/record " message ") (patterns/call :mailing-list :call :spawn {:task \"Run bounded implementation task\" :handle :implementation-worker :lists [:design :implementation]}) (agents/!wait)))")
                           (str "'(audit/finish " message ")))"))) ))) }
                :prefill? false
                :namespaces {'patterns stdlib/patterns
                             'globals globals/globals-namespace
                             'agents runtime/agents-namespace
                             'audit {:mark #(swap! marks conj %)
                                     :child #(assoc % :status :completed)
                                     :record #(swap! returns conj %)
                                     :page #(swap! pages conj %)
                                     :finish finish}})
        value (th/run-agent-init agent
                "(quine completion (eval (do '(do
                   (patterns/install :mailing-list)
                   (patterns/call :mailing-list :init {:retention 8 :page-size 3})
                   (patterns/call :mailing-list :call :create {:list :design :description (str :design)})
                   (patterns/call :mailing-list :call :create {:list :implementation :description (str :implementation)})
                   (patterns/call :mailing-list :call :subscribe {:list :design :from :earliest})
                   (patterns/call :mailing-list :call :subscribe {:list :implementation :from :earliest})
                   (patterns/call :mailing-list :call :post {:list :design :summary \"seed design\" :body {:sequence 0}})
                   (patterns/call :mailing-list :call :post {:list :design :summary \"seed design\" :body {:sequence 1}})
                   (patterns/call :mailing-list :call :post {:list :design :summary \"seed design\" :body {:sequence 2}})
                   (patterns/call :mailing-list :call :post {:list :design :summary \"seed design\" :body {:sequence 3}})
                   (let [page (patterns/call :mailing-list :call :digest {:list :design :limit 3})]
                     (audit/page page)
                     (patterns/call :mailing-list :call :ack {:token (:token page)}))
                   (patterns/call :mailing-list :call :spawn {:task \"Keep local evidence across a self-call, then return\" :handle :design-worker :lists [:design :implementation]})
                   (agents/!wait)))))")
        board (globals/get-val :mailing-list)
        persisted (clojure.edn/read-string (slurp report-file))]
    (is (= :completed (:status value)))
    (is (= value persisted) "The completed report is persisted, not merely proposed")
    (is (= [:design-worker :implementation-worker] (mapv :from @returns)))
    (is (= [:completed :completed] (mapv #(get-in % [:body :status]) @returns)))
    (is (= :retained (get-in @returns [0 :body :marker])) "Self-call local survives")
    (is (true? (get-in @returns [1 :body :task-recovery])) "Setup catch does not swallow task recovery")
    (is (= 1 (count @marks)) "Reopening source must not replay completed effects")
    (is (= #{:design-worker :implementation-worker} @onboarding))
    (is (= {:design-worker 2 :implementation-worker 2 :main 2} @calls)
        "Quiet posts must not introduce coordinator wakeup generations")
    (is (= [1 2 3] (mapv :id (:messages (first @pages)))))
    (is (= 1 (:remaining (first @pages))))
    (is (= 3 (get-in board [:lists :design :subscriptions :main :cursor])))
    (is (= 5 (count (get-in board [:lists :design :messages]))))
    (is (= 1 (count (get-in board [:lists :implementation :messages]))))))

(deftest onboarding-failure-is-bounded-and-precedes-generation
  (let [worker-calls (atom 0)
        main-calls (atom {})
        agent (th/make-test-agent
                {:response-fn
                 (fn [p]
                   (response! main-calls runtime/*current-handle*)
                   (if (= :blocked-worker runtime/*current-handle*)
                     (do (swap! worker-calls inc) "':unexpected-generation)))")
                     (str "'(audit/finish " (last (re-seq #"msg-[0-9]+" p)) ")))")))}
                :prefill? false
                :namespaces {'patterns stdlib/patterns
                             'globals globals/globals-namespace
                             'agents runtime/agents-namespace
                             'audit {:finish (fn [v] (coordinator/close!) v)}})
        value (th/run-agent-init agent
                "(quine completion (eval (do '(do
                   (patterns/install :mailing-list)
                   (patterns/call :mailing-list :init {:max-subscribers 1})
                   (patterns/call :mailing-list :call :create {:list :design :description (str :design)})
                   (patterns/call :mailing-list :call :create {:list :implementation :description (str :implementation)})
                   (patterns/call :mailing-list :call :subscribe {:list :implementation})
                   (patterns/call :mailing-list :call :spawn {:task \"Must never reach generation\" :handle :blocked-worker :lists [:design :implementation]})
                   (agents/!wait)))))")]
    (is (true? (get-in value [:body :spell/child-failure])))
    (is (= :onboarding (get-in value [:body :phase])))
    (is (= :blocked-worker (get-in value [:body :handle])))
    (is (re-find #"subscriber limit reached" (get-in value [:body :error])))
    (is (zero? @worker-calls))
    (is (= 1 (:main @main-calls)))
    (doseq [list-name [:design :implementation]]
      (is (nil? (get-in (globals/get-val :mailing-list)
                       [:lists list-name :subscriptions :blocked-worker]))
          "Failed multi-list onboarding is atomic"))))
