(ns spell.receipt-policy-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [spell.context :as context]
            [spell.coordinator :as coordinator]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.parse :as parse]
            [spell.provider :as provider]))

(def ^:private deadline-ms 10000)
(def ^:private prefix "(quine completion (eval (do (def generated-marker :present) ")
(def ^:private supplied-completion
  '(quine completion (eval (do (def retained-value :retained)
                              '(do (probe/mark :must-not-evaluate) :original-tail)))))

(defn- quoted [form] (list 'quote form))
(defn- program [action]
  (pr-str (list 'quine 'completion (list 'eval (list 'do (quoted action))))))
(defn- suffix [action] (str (pr-str (quoted action)) ")))"))
(defn- await! [source label]
  (let [value (deref source deadline-ms ::timeout)]
    (when (= ::timeout value) (throw (ex-info (str "Timed out: " label) {})))
    (when (instance? Throwable value) (throw value))
    value))
(defn- make-agent [response-fn probe]
  ;; Deliberately omit agents/: receive and the convenience continuation must
  ;; remain available with only this test instrumentation namespace exposed.
  (llm/compile-agent
    {:provider (provider/test-provider {:prefill? false :response-fn response-fn})
     :prefill? false :recover false :namespaces {'probe probe}}))
(defn- launch [compiled action readiness]
  (future
    (try (compiled (llm/direct-init (program action)) :main)
         (catch Throwable e (deliver readiness e) (throw e)))))
(defn- stop! [runner gate]
  (when gate (deliver gate true))
  (coordinator/close!)
  (when (and runner (not (realized? runner))) (future-cancel runner)))
(defn- occurrences [value token]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote (str token)))
                (if (string? value) value (pr-str value)))))

(use-fixtures :each
  (fn [f]
    (binding [context/*context* (context/new-context)
              coordinator/*coordinator* (coordinator/new-coordinator)
              globals/*store* (globals/new-store)]
      (try (f) (finally (coordinator/close!))))))

(defn- arrival-case! [mode]
  (let [receiving? (= :receiving mode)
        calls (atom []) marks (atom []) observed (atom nil)
        generating (promise) release-generation (promise)
        self-call (if (= :default mode)
                    (list '!llm-self prefix)
                    (list '!llm-self prefix {:receive? receiving?}))
        action (list 'let ['value self-call]
                 '(probe/observe value)
                 {:value 'value :received '(receive completion)})
        compiled
        (make-agent
          (fn [prompt]
            (case (count (swap! calls conj prompt))
              1 (do (deliver generating true)
                    (await! release-generation "generation release")
                    (suffix '(do (probe/mark :chosen-action) :chosen-result)))
              2 (if receiving?
                  (suffix '(do (probe/mark :received-action) :received-result))
                  (throw (ex-info "Raw call unexpectedly continued for a message" {})))
              (throw (ex-info "Unexpected extra generation" {:prompt prompt}))))
          {:mark #(do (swap! marks conj %) nil)
           :observe (fn [value]
                      (reset! observed {:value value :marks @marks :calls (count @calls)
                                        :mailbox (:mailbox (coordinator/agent :main))}))})
        runner (launch compiled action generating)]
    (try
      (is (= true (await! generating "generation starts")))
      (coordinator/send! :main {:message {:from :observer :body :arrival-message}})
      (deliver release-generation true)
      (let [result (await! runner "arrival result")]
        (is (= (if receiving? :received-result :chosen-result) (:value result)))
        (is (= (if receiving? [:received-action] [:chosen-action]) @marks))
        (is (= (if receiving? 2 1) (count @calls)))
        (is (= (if receiving? [] [{:message {:from :observer :body :arrival-message}}])
               (:mailbox @observed))
            "A raw self-call leaves the message queued until explicit receipt")
        (is (= (:marks @observed) @marks)
            "Explicit receive must not execute its transformed completion")
        (is (= (:calls @observed) (count @calls))
            "Explicit receive must not generate another completion")
        (is (= (if receiving? 0 1) (occurrences (:received result) :arrival-message))
            "The later explicit boundary consumes precisely the remaining batch")
        (is (empty? (:mailbox (coordinator/agent :main))))
        (when receiving?
          (is (= 1 (occurrences (second @calls) :arrival-message))
              "The receiving continuation sees the batch exactly once")))
      (finally (stop! runner release-generation)))))

(deftest raw-call-defers-receipt (arrival-case! :default))
(deftest explicit-false-defers-receipt (arrival-case! :false))
(deftest opted-in-call-receives-after-generation (arrival-case! :receiving))

(deftest receiving-parent-does-not-make-nested-raw-call-receive
  (let [calls (atom []) marks (atom []) observed (atom nil)
        helper-generating (promise) release-helper (promise)
        outer-action
        (list 'do '(probe/mark :outer-before)
          (list 'def 'helper-value (list '!llm-self prefix))
          '(probe/mark :outer-after) '(probe/observe helper-value)
          {:value 'helper-value :received '(receive completion)})
        compiled
        (make-agent
          (fn [prompt]
            (case (count (swap! calls conj prompt))
              1 (suffix outer-action)
              2 (do (deliver helper-generating true)
                    (await! release-helper "nested helper release")
                    (suffix '(do (probe/mark :inner-action) :inner-result)))
              (throw (ex-info "Nested raw call unexpectedly received" {:prompt prompt}))))
          {:mark #(do (swap! marks conj %) nil)
           :observe (fn [value]
                      (reset! observed {:value value
                                        :mailbox (:mailbox (coordinator/agent :main))}))})
        runner (launch compiled (list '!llm-self prefix {:receive? true}) helper-generating)]
    (try
      (is (= true (await! helper-generating "nested helper generation")))
      (coordinator/send! :main {:message {:from :observer :body :nested-message}})
      (deliver release-helper true)
      (let [result (await! runner "nested result")]
        (is (= :inner-result (:value result)))
        (is (= [:outer-before :inner-action :outer-after] @marks))
        (is (= 2 (count @calls)))
        (is (= [{:message {:from :observer :body :nested-message}}] (:mailbox @observed)))
        (is (= 1 (occurrences (:received result) :nested-message)))
        (is (empty? (:mailbox (coordinator/agent :main)))))
      (finally (stop! runner release-helper)))))

(defn- no-model-provider [calls]
  (fn [_] (swap! calls inc) (suffix :unexpected-model-call)))

(defn- receipt-state [edge-id]
  (let [state (coordinator/snapshot)]
    {:mailbox (get-in state [:agents :main :mailbox])
     :signal (get-in state [:agents :main :signal])
     :claimed-generation (get-in state [:edges edge-id :slots :main :generation])
     :agent-generation (get-in state [:agents :main :generation])}))

(deftest explicit-receive-empty-and-single-batch-does-not-evaluate-or-generate
  (coordinator/register! :requester)
  (let [calls (atom 0) marks (atom []) edge (atom nil) before (atom nil) after (atom nil)
        compiled
        (make-agent (no-model-provider calls)
          {:mark #(do (swap! marks conj %) nil)
           :enqueue (fn []
                      (reset! edge (coordinator/request! :requester [:main] true :explicit-message))
                      (reset! before (receipt-state @edge)) nil)
           :observe #(do (reset! after (receipt-state @edge)) nil)})
        action
        (list 'let ['empty-before (list 'receive (quoted supplied-completion))]
          '(probe/enqueue)
          (list 'let ['received (list 'receive (quoted supplied-completion))]
            '(probe/observe)
            {:empty-before 'empty-before :received 'received
             :empty-after (list 'receive (quoted supplied-completion))}))
        result (compiled (llm/direct-init (program action)) :main)]
    (is (= supplied-completion (:empty-before result)))
    (is (= supplied-completion (:empty-after result)))
    (is (= 1 (occurrences (:received result) :explicit-message)))
    (is (zero? @calls))
    (is (empty? @marks))
    (is (= 1 (count (:mailbox @before))))
    (is (empty? (:mailbox @after)))
    (is (nil? (:claimed-generation @before)))
    (is (= (:agent-generation @after) (:claimed-generation @after)))
    (is (not (identical? (:signal @before) (:signal @after))))))

(deftest explicit-receive-preserves-the-supplied-quine-name
  (let [calls (atom [])
        supplied '(quine saved (eval (do (def retained-value :named-context) ':discarded)))
        compiled (make-agent
                   (fn [prompt]
                     (swap! calls conj prompt)
                     (suffix 'retained-value))
                   {:enqueue #(coordinator/send! :main
                                {:message {:from :observer :body :named-message}})})
        action (list 'do '(probe/enqueue)
                 (list 'let ['list 7 'second 8 'nth 9 'context-forms 10]
                   (list 'eval (list 'receive (quoted supplied)))))]
    (is (= :named-context (compiled (llm/direct-init (program action)) :main)))
    (is (= 1 (count @calls)))
    (is (str/starts-with? (first @calls) "(quine saved "))
    (is (= 1 (occurrences (first @calls) :named-message)))
    (is (empty? (:mailbox (coordinator/agent :main))))))

(defn- compaction-case! [arrival]
  (let [calls (atom []) marks (atom [])
        enqueue! #(coordinator/send! :main
                    {:message {:from :observer :body :compaction-message}})
        compiled
        (make-agent
          (fn [prompt]
            (case (count (swap! calls conj prompt))
              1 (do (when (= :first arrival) (enqueue!))
                    "'(def compacted-value :kept)")
              2 (if (= :first arrival)
                  (suffix :first-stage-received)
                  (do (when (= :second arrival) (enqueue!))
                      (suffix '(do (probe/mark :generated-tail) compacted-value))))
              3 (suffix 'compacted-value)
              (throw (ex-info "Unexpected compaction call" {:prompt prompt}))))
          {:mark #(swap! marks conj %)})
        result (compiled (llm/direct-init (program '(!compact))) :main)]
    (is (= (if (= :first arrival) :first-stage-received :kept) result))
    (is (= (if (= :second arrival) 3 2) (count @calls)))
    (is (= (if (= :none arrival) [:generated-tail] []) @marks))
    (is (str/includes? (second @calls)
                      (if (= :first arrival) "=compact=" "(def compacted-value :kept)")))
    (is (= (if (= :none arrival) 0 1)
           (occurrences (last @calls) :compaction-message)))
    (is (empty? (:mailbox (coordinator/agent :main))))))

(deftest compaction-runs-both-stages (compaction-case! :none))
(deftest compaction-receives-after-its-first-generation (compaction-case! :first))
(deftest compaction-receives-after-its-second-generation (compaction-case! :second))

(def ^:private invalid-completions
  [nil false 42 "(quine completion (eval (do )))"
   '(do :ordinary-expression) '(quine completion)
   '(quine nil (eval (do :ok))) '(quine 42 (eval (do :ok)))
   '(quine completion (do :missing-eval))
   '(quine completion (eval (do :ok) :extra-argument))])
(def ^:private invalid-options
  [nil true :not-a-map [] {:unknown true} {:receive? true :unknown false}
   {:receive? nil} {:receive? 1} {:receive? :yes}])

(defn- rejection-case! [kind invalids]
  (coordinator/register! :requester)
  (let [calls (atom 0) marks (atom []) edge (atom nil) before (atom nil)
        rejections (atom [])
        attempts
        (mapv (fn [value]
                (let [attempt (if (= :completion kind)
                                (list 'receive (quoted value))
                                (list '!llm-self prefix (quoted value)))]
                  (list 'try attempt (list 'catch 'error '(probe/rejected error)))))
              invalids)
        compiled
        (make-agent (no-model-provider calls)
          {:mark #(do (swap! marks conj %) nil)
           :enqueue (fn []
                      (reset! edge (coordinator/request! :requester [:main] true :validation-message))
                      (reset! before (receipt-state @edge)) nil)
           :rejected (fn [error]
                       (swap! rejections conj {:error error :state (receipt-state @edge)})
                       :rejected)})
        action
        (list 'do '(probe/enqueue)
          (list 'let ['results attempts]
            {:results 'results :received (list 'receive (quoted supplied-completion))}))
        result (compiled (llm/direct-init (program action)) :main)]
    (is (= (vec (repeat (count invalids) :rejected)) (:results result)))
    (is (= (count invalids) (count @rejections)))
    (doseq [{:keys [state]} @rejections]
      (is (= (:mailbox @before) (:mailbox state)) "Validation must precede dequeue")
      (is (identical? (:signal @before) (:signal state)) "Validation must not rotate the signal")
      (is (nil? (:claimed-generation state)) "Validation must not claim a request slot"))
    (is (zero? @calls) "Invalid self-call options must fail before provider work")
    (is (empty? @marks))
    (is (= 1 (occurrences (:received result) :validation-message)))
    (is (empty? (:mailbox (coordinator/agent :main))))))

(deftest invalid-receive-inputs-preserve-mailbox-and-claim-state
  (rejection-case! :completion invalid-completions))
(deftest invalid-self-call-options-fail-before-generation
  (rejection-case! :options invalid-options))
(deftest minimum-cap-generated-receipt-evaluates-a-message-batch
  (doseq [limit [128 180]]
    (binding [context/*context* (context/new-context {:max-chars limit})
              coordinator/*coordinator* (coordinator/new-coordinator)]
      (try
        (let [calls (atom []) marks (atom []) received (atom nil)
              bodies (mapv #(hash-map :index % :detail (apply str (repeat 80 "message-detail-"))) [1 2])
              proposed '(do (probe/mark :must-not-run) :proposed-result)
              agent (make-agent
                      (fn [prompt]
                        (case (count (swap! calls conj prompt))
                          1 (do (doseq [body bodies]
                                  (coordinator/send! :main {:message {:from :observer :body body}}))
                                (suffix proposed))
                          2 (let [form (first (parse/read-all (parse/balance-parens prompt)))
                                  forms (vec (rest (second (last form))))
                                  positions (vec (keep-indexed
                                                   #(when (= '(think "pre-eval: tail not run") %2) %1)
                                                   forms))
                                  message-defs (mapv #(nth forms (inc %)) positions)]
                              (is (= 2 (count positions)) "Both factual annotations remain visible at the same cap")
                              (is (some #{(quoted proposed)} forms) "Only the proposed tail becomes inert")
                              (doseq [i positions]
                                (let [contribution (subvec forms i (+ i 3))]
                                  (is (= 'def (first (second contribution))))
                                  (is (= '(quote (!extend completion)) (last contribution)))
                                  (is (<= (count (str/join " " (map pr-str contribution))) limit)
                                      "Annotation, message binding/reference and continuation share one unchanged cap")))
                              (suffix (list 'do
                                            (list* 'probe/received (map second message-defs))
                                            '(probe/mark :continued)
                                            :received-result)))
                          (throw (ex-info "Unexpected generation" {:prompt prompt}))))
                      {:mark #(do (swap! marks conj %) nil)
                       :received (fn [& messages] (reset! received (vec messages)) nil)})
              action (list 'do '(probe/mark :earlier-effect)
                           (list '!llm-self prefix {:receive? true}))]
          (is (= :received-result (agent (llm/direct-init (program action)) :main)))
          (is (= [:earlier-effect :continued] @marks)
              "The earlier effect executes exactly once; the replaced tail never executes")
          (is (= (mapv #(hash-map :from :observer :body %) bodies) @received)
              "The actual evaluator resolves both stored message values, losslessly and in order")
          (is (= 2 (count @calls)))
          (is (= limit (:max-chars context/*context*)))
          (is (empty? (:mailbox (coordinator/agent :main)))))
        (finally (coordinator/close!))))))

(defn- assert-rendered-receipts [prompt site-text bodies original-action]
  (let [form (first (parse/read-all (parse/balance-parens prompt)))
        forms (vec (rest (second (last form))))
        positions (keep-indexed #(when (and (seq? %2) (= 'think (first %2))) %1) forms)]
    (is (= 'quine (first form)))
    (is (= (count bodies) (count positions)))
    (is (some #{(quoted original-action)} forms)
        "The old proposed action remains visible as inert quoted source")
    (doseq [[i body] (map vector positions bodies)]
      (let [annotation (nth forms i)
            message-def (nth forms (inc i))
            message-value (nth message-def 2)]
        (is (= 'def (first message-def)))
        (is (= site-text (second annotation)))
        (is (= body (:body (if (and (seq? message-value) (= 'quote (first message-value)))
                            (second message-value) message-value))))
        (is (= '(quote (!extend completion)) (nth forms (+ i 2))))))))

(deftest startup-receipt-is-not-a-generated-proposal
  (let [calls (atom []) marks (atom [])
        action '(do (probe/mark :startup-tail) :not-run)
        agent (make-agent (fn [prompt] (swap! calls conj prompt) (suffix :continued))
                          {:mark #(swap! marks conj %)})]
    (coordinator/register! :main)
    (doseq [body [:startup-one :startup-two]]
      (coordinator/send! :main {:message {:from :observer :body body}}))
    (is (= :continued (agent (llm/direct-init (program action)) :main)))
    (is (empty? @marks))
    (is (= 1 (count @calls)))
    (assert-rendered-receipts
      (first @calls)
      "startup: tail not run"
      [:startup-one :startup-two] action)
    (is (not (str/includes? (first @calls) "proposed trailing expression")))))

(deftest generated-batch-receipt-replaces-only-the-proposed-tail
  (let [calls (atom []) marks (atom []) generating (promise) release-generation (promise)
        proposed '(do (probe/mark :must-not-run) :proposed-result)
        action (list 'do '(probe/mark :earlier-effect)
                     (list '!llm-self prefix {:receive? true}))
        agent (make-agent
                (fn [prompt]
                  (case (count (swap! calls conj prompt))
                    1 (do (deliver generating true)
                          (await! release-generation "release generated receipt")
                          (suffix proposed))
                    2 (suffix '(do (probe/mark :continued) :received-result))
                    (throw (ex-info "Unexpected generation" {:prompt prompt}))))
                {:mark #(do (swap! marks conj %) nil)})
        runner (launch agent action generating)]
    (try
      (is (= true (await! generating "generated receipt ready")))
      (doseq [body [:generated-one :generated-two]]
        (coordinator/send! :main {:message {:from :observer :body body}}))
      (deliver release-generation true)
      (is (= :received-result (await! runner "generated receipt result")))
      (is (= [:earlier-effect :continued] @marks)
          "Earlier effects ran once; only the new proposed tail did not execute")
      (is (= 2 (count @calls)))
      (is (empty? (:mailbox (coordinator/agent :main))))
      (assert-rendered-receipts
        (second @calls)
        "pre-eval: tail not run"
        [:generated-one :generated-two] proposed)
      (finally (stop! runner release-generation)))))
