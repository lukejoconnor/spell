(ns spell.agents-prompt-test
  "Executable controls for communication guidance exposed by agents metadata."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.coordinator :as coordinator]
            [spell.io :as spell-io]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.runtime :as runtime]
            [spell.test-helpers :as th]))

(defn- response [form] (str (pr-str form) ")))"))
(defn- message-symbol [prefix]
  (or (some-> (last (re-seq #"\(def (msg-[0-9]+)" prefix)) second symbol)
      (throw (ex-info "Expected an injected message" {:prefix prefix}))))
(defn- run-script [response-fn namespaces]
  (th/run-agent-prefix
    (th/make-test-agent {:response-fn response-fn}
                        :namespaces (merge {'agents runtime/agents-namespace} namespaces)
                        :prefill? false :recover false)
    "(quine completion (eval (do "))
(defn- interrupt! []
  (coordinator/send! :main {:message {:from :external :body :new-information}}))
(defn- bodies [handle]
  (mapv #(get-in % [:message :body]) (:mailbox (coordinator/agent handle))))

(deftest agents-guidance-reaches-provider-and-describe-test
  (let [entry-marker "AGENTS-ENTRY-SCOPE-MARKER"
        guide-marker "AGENTS-GUIDE-SCOPE-MARKER"
        agents-ns (-> runtime/agents-namespace
                      (assoc-in [:docs :entry-marker] entry-marker)
                      (update-in [:docs :guide] #(str guide-marker "\n" %)))
        actual-payload
        (fn [agents?]
          (th/with-test-run
            (fn []
              (let [requests (atom []) calls (atom 0)
                    delegate (provider/test-provider
                               {:response-fn
                                (fn [_]
                                  (if (and agents? (= 1 (swap! calls inc)))
                                    (response '(quote (!describe agents)))
                                    (response 42)))})
                    capturing (reify provider/LLMProvider
                                (call-llm [this text] (provider/call-llm this text {}))
                                (call-llm [_ text opts]
                                  (swap! requests conj {:text text :opts opts})
                                  (provider/call-llm delegate text opts))
                                (plain-text-provider [this] this)
                                (supports-prefill [_] false))
                    agent (llm/compile-agent
                            {:provider capturing :prefill? false :recover false :system "SCOPE-BASE"
                             :namespaces (cond-> (array-map 'io spell-io/io-namespace)
                                           agents? (assoc 'agents agents-ns))})]
                (is (= 42 (th/run-agent-prefix agent "(quine completion (eval (do ")))
                @requests))))
        enabled (actual-payload true) disabled (actual-payload false)
        system (get-in enabled [0 :opts :system])
        plain (get-in disabled [0 :opts :system])]
    (testing "actual first provider payload contains concise guidance; describe exposes the guide"
      (is (str/includes? system entry-marker))
      (is (not (str/includes? system guide-marker)))
      (is (str/includes? (:text (second enabled)) guide-marker))
      (doseq [k [:child-prompts :waiting :receipts :returning :futures]]
        (let [doc (get-in runtime/agents-namespace [:docs k])]
          (is (seq doc) (str "Missing entry guidance: " k))
          (is (str/includes? system doc)))))
    (testing "removing the sole agents section exactly restores the nonagents provider payload"
      (is (not (str/includes? plain entry-marker)))
      (is (= plain (str/replace system #"(?s)\n\n## agents .*?(?=\n\nUsage:)" ""))))))

(deftest request-capture-reflects-execution-across-receipt-test
  (doseq [preempt-first? [true false]]
    (th/with-test-run
      (fn []
        (coordinator/register! :peer)
        (let [calls (atom 0) prefixes (atom []) observed (atom nil) stale (atom 0)
              result
              (run-script
                (fn [prefix]
                  (swap! prefixes conj prefix)
                  (case (swap! calls inc)
                    1 (do (when preempt-first? (interrupt!))
                          (response '(quote (!call-now question-edge (agents/ask :peer :question)))))
                    2 (do (reset! observed {:edges (count (:edges (coordinator/snapshot)))
                                           :bodies (bodies :peer)})
                          (if preempt-first? (response :preempted)
                              (do (interrupt!) (response '(quote (audit/stale))))))
                    3 (response '(quote question-edge))))
                {'audit {:stale #(swap! stale inc)}})]
          (is (= (if preempt-first? :preempted 1) result))
          (is (= {:edges (if preempt-first? 0 1)
                  :bodies (if preempt-first? [] [:question])} @observed))
          (is (= (not preempt-first?)
                 (str/includes? (last @prefixes) "(def question-edge 1)")))
          (is (zero? @stale)))))))

(deftest reply-capture-and-pure-bookkeeping-test
  (doseq [[preempt? flag?] [[true false] [false false] [true true]]]
    (th/with-test-run
      (fn []
        (coordinator/register! :peer)
        (let [calls (atom 0) prefixes (atom []) edge (atom nil) observed (atom nil)
              result
              (run-script
                (fn [prefix]
                  (swap! prefixes conj prefix)
                  (case (swap! calls inc)
                    1 (do (reset! edge (coordinator/request! :peer [:main] true :private-number))
                          (response '(quote (!extend))))
                    2 (let [reply (list 'agents/reply (message-symbol prefix) 17)]
                        (when preempt? (interrupt!))
                        (if flag?
                          (str "(def replied true) " (response (list 'quote reply)))
                          (response (list 'quote (list '!call-now 'reply-result reply)))))
                    3 (do (reset! observed {:pending (contains? (:edges (coordinator/snapshot)) @edge)
                                           :bodies (bodies :peer)})
                          (if preempt? (response (if flag? '(quote replied) :preempted))
                              (do (interrupt!) (response :stale))))
                    4 (response '(quote reply-result)))) {})]
          (is (= {:pending preempt? :bodies (if preempt? [] [17])} @observed))
          (is (= (cond flag? true preempt? :preempted :else nil) result))
          (is (= (not preempt?) (str/includes? (last @prefixes) "(def reply-result nil)"))))))))

(deftest captures-have-operation-and-failure-boundaries-test
  (testing "a superseded second action can leave the first action's reused capture name"
    (th/with-test-run
      (fn []
        (coordinator/register! :peer)
        (let [calls (atom 0) seen (atom nil)
              result (run-script
                       (fn [_]
                         (case (swap! calls inc)
                           1 (response '(quote (!call-now edge (agents/ask :peer :first))))
                           2 (do (interrupt!) (response '(quote (!call-now edge (agents/ask :peer :second)))))
                           3 (do (reset! seen (bodies :peer)) (response '(quote edge))))) {})]
          (is (= 1 result))
          (is (= [:first] @seen))))))
  (testing "a later capture expression may fail after an earlier request already ran"
    (th/with-test-run
      (fn []
        (coordinator/register! :peer)
        (let [calls (atom 0) seen (atom nil)]
          (is (thrown-with-msg? Exception #"later expression failed"
                (run-script
                  (fn [_] (swap! calls inc)
                    (response '(quote (!call-now edge (agents/ask :peer :question)
                                                  later (audit/fail)))))
                  {'audit {:fail (fn [] (reset! seen (bodies :peer))
                                  (throw (ex-info "later expression failed" {})))}})))
          (is (= [:question] @seen))
          (is (= 1 @calls) "No capture continuation was generated")))))
  (testing "cancelled reply returns captured nil without filling a slot"
    (th/with-test-run
      (fn []
        (coordinator/register! :peer)
        (let [calls (atom 0) edge (atom nil) seen (atom nil) last-prefix (atom nil)
              result (run-script
                       (fn [prefix]
                         (reset! last-prefix prefix)
                         (case (swap! calls inc)
                           1 (do (reset! edge (coordinator/request! :peer [:main] true :number))
                                 (response '(quote (!extend))))
                           2 (do (coordinator/cancel! :peer @edge)
                                 (response (list 'quote (list '!call-now 'reply-result
                                                            (list 'agents/reply (message-symbol prefix) 17)))))
                           3 (do (reset! seen (bodies :peer)) (response '(quote reply-result))))) {})]
          (is (nil? result))
          (is (= [] @seen))
          (is (str/includes? @last-prefix "(def reply-result nil)")))))))

(deftest waits-return-the-resumed-continuation-test
  (doseq [mode [:terminal :captured :wrapped]]
    (th/with-test-run
      (fn []
        (coordinator/register! :peer)
        (let [calls (atom 0) received (atom nil)
              result (try
                       (run-script
                         (fn [prefix]
                           (case (swap! calls inc)
                             1 (response
                                 (case mode
                                   :terminal '(quote (do (agents/ask :peer :value) (audit/complete) (agents/!wait)))
                                   :captured '(quote (!call-now waited (do (agents/ask :peer :value) (audit/complete) (agents/!wait))))
                                   :wrapped '(quote (do (agents/ask :peer :value) (audit/complete) ((agents/!wait))))))
                             2 (do (reset! received prefix) (response '(quote {:assembled 99})))
                             3 (response '(quote {:captured waited}))))
                         {'audit {:complete (fn []
                                              (coordinator/fill! (:id (first (coordinator/outgoing (coordinator/snapshot) :main))) :peer 37)
                                              nil)}})
                       (catch Exception e {:error (.getMessage e)}))]
          (is (str/includes? @received ":body 37"))
          (case mode
            :terminal (is (= {:assembled 99} result))
            :captured (is (= {:captured {:assembled 99}} result))
            :wrapped (is (str/includes? (:error result) "Wrong number of args"))))))))

(deftest explicit-replies-and-lifecycle-return-test
  (th/with-test-run
    (fn []
      (doseq [h [:peer-a :peer-b :peer-c]] (coordinator/register! h))
      (let [calls (atom 0)
            result (run-script
                     (fn [prefix]
                       (case (swap! calls inc)
                         1 (do (doseq [h [:peer-a :peer-b :peer-c]]
                                 (coordinator/request! h [:main] true h))
                               (response '(quote (!extend))))
                         2 (response (list 'quote (list '!call-now 'individual-reply
                                                        (list 'agents/reply (message-symbol prefix) 17))))
                         3 (response 46))) {})
            all-bodies (mapv bodies [:peer-a :peer-b :peer-c])]
        (is (= 46 result))
        (is (= [[17] [46] [46]] (sort all-bodies))
            "One explicit reply has its own value; all remaining claimed slots get the final value")))))

(deftest captured-future-survives-unrelated-wake-without-resending-test
  (th/with-test-run
    (fn []
      (coordinator/register! :peer)
      (let [calls (atom 0) joins (atom 0) worker-requests (atom 0) observed (atom [])
            release-worker (promise) last-prefix (atom nil)
            original-request coordinator/request! original-begin coordinator/begin-external-wait!]
        (try
          (with-redefs [coordinator/request!
                        (fn [& args]
                          (when (= [:main [:worker]] (vec (take 2 args))) (swap! worker-requests inc))
                          (apply original-request args))
                        coordinator/begin-external-wait!
                        (fn [handle]
                          (let [token (original-begin handle)]
                            (when (= 1 (swap! joins inc))
                              (coordinator/request! :peer [:main] true :status))
                            token))]
            (let [result
                  (run-script
                    (fn [prefix]
                      (if (= :worker runtime/*current-handle*)
                        (do (when (= :timeout (deref release-worker 5000 :timeout))
                              (throw (ex-info "Worker release timeout" {})))
                            (response 385))
                        (do (reset! last-prefix prefix)
                            (case (swap! calls inc)
                              1 (response '(quote (!call-now worker-handle (agents/spawn "Return an integer for arithmetic requests." :worker))))
                              2 (response '(quote (!call-now task-future (future (blocking/await (blocking/request worker-handle "Sum squares 1 through 10."))))))
                              3 (response '(quote (do (audit/record task-future) (!ask-await task-future))))
                              4 (response (list 'quote (list '!call-now 'status-reply (list 'agents/reply (message-symbol prefix) :working))))
                              5 (response '(quote (do (audit/record task-future) (audit/release) (!ask-await task-future))))
                              6 (response (list 'quote (list :body (message-symbol prefix))))))))
                    {'audit {:record (fn [f] (swap! observed conj f) nil)
                             :release (fn [] (deliver release-worker true) nil)}})]
              (is (= 385 result))
              (is (= 1 @worker-requests))
              (is (= 2 @joins))
              (is (= 2 (count @observed)))
              (is (apply identical? @observed) "Capture preserves the same future identity across the wake")
              (is (= [:working] (bodies :peer)))
              (is (str/includes? @last-prefix ":from :future, :body 385"))))
          (finally (deliver release-worker true)))))))

;; Retained from the delegated Fable implementation; this namespace scopes
;; isolation per test body rather than through the original :each fixture.
(deftest refused-wait-lists-pending-requests-by-edge-id-test
  (th/with-test-run
    (fn []
      (testing "a refused wait exposes pending request ids under :in-edges; replying clears it"
        (doseq [h [:rf-a :rf-b]] (runtime/register! h))
        (let [edge (coordinator/request! :rf-a [:rf-b] true "status?")
              msg (:message (first (coordinator/drain! :rf-b)))
              refusal (try (coordinator/wait! :rf-b) nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= edge (:edge-id msg)))
          (is (:expects-response msg))
          (is (= :sleep-refused (:type refusal)))
          (is (= [edge] (mapv :id (:in-edges refusal))))
          (is (empty? (:out-edges refusal)))
          (binding [runtime/*current-handle* :rf-b]
            (runtime/reply msg "done"))
          (is (= :idle (:status (coordinator/wait! :rf-b)))))))))
