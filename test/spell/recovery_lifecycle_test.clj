(ns spell.recovery-lifecycle-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [spell.agent :as agent]
            [spell.api :as api]
            [spell.coordinator :as coordinator]
            [spell.eval :as eval]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.runtime :as runtime]
            [spell.stdlib :as stdlib]
            [spell.test-helpers :as th]))

(use-fixtures :each th/with-test-run)

(def prefix "(quine completion (eval (do ")
(defn- streak [handle]
  (:consecutive-errors @(:execution (coordinator/agent handle))))
(defn- outcome [f]
  (try {:value (f)} (catch Exception e {:exception e :data (ex-data e)})))
(defn- scripted [responses options]
  (let [calls (atom [])
        p (provider/test-provider
            {:prefill? false
             :response-fn (fn [_]
                            (let [i (count @calls)]
                              (swap! calls conj (streak runtime/*current-handle*))
                              (if (< i (count responses)) (nth responses i)
                                (throw (ex-info "Script exhausted" {:type :test-script-exhausted})))))} )
        a (llm/compile-agent (merge {:provider p :prefill? false :namespaces {}} options))]
    (assoc (outcome #(th/run-agent-prefix a prefix)) :calls @calls :streak (streak :main))))

(deftest configured-nth-own-failure-is-terminal-once
  (doseq [limit [1 2 3 5]
          responses [(repeat limit "missing")
                     (repeat limit "\\invalidchar")
                     (take limit (cycle ["\\invalidchar" "missing"]))]]
    (th/with-test-run
      (fn []
        (let [r (scripted (vec responses) {:max-consecutive-errors limit})]
          (is (= :recovery-exhausted (get-in r [:data :type])))
          (is (= limit (get-in r [:data :consecutive-errors])))
          (is (= limit (get-in r [:data :limit])))
          (is (= limit (:streak r)) "Ancestor unwind cannot increment the terminal failure")
          (is (= (vec (range limit)) (:calls r)) "Nth failure dispatches no repair call"))))))

(deftest many-distinct-repaired-errors-reset-at-valid-handoff
  (let [responses (vec (concat (mapcat (fn [_] ["'(missing)" "'(!llm-self (reopen completion))"])
                                                    (range 7)) ["42"]))
        r (scripted responses {})]
    (is (= 42 (:value r)))
    (is (= (vec (concat (mapcat (fn [_] [0 1]) (range 7)) [0])) (:calls r)))
    (is (= 0 (:streak r)))))

(deftest malformed-repairs-do-not-reset
  (let [r (scripted ["'(missing)" "\\invalidchar" "\\invalidchar"] {})]
    (is (= :reader (get-in r [:data :phase])))
    (is (= :recovery-exhausted (get-in r [:data :type])))
    (is (= [0 1 2] (:calls r)))))

(deftest synthetic-namespace-success-is-not-model-success
  (let [r (scripted ["missing"] {:recover (constantly 17)})]
    (is (= 17 (:value r)))
    (is (= 1 (:streak r)))
    (is (= [0] (:calls r)))))

(deftest parents-own-later-error-counts-without-saved-streak-rollback
  (let [r (scripted [(str "'(do (!llm-self " (pr-str prefix) ") (missing-parent))")
                     "missing-child"]
                    {:recover (constantly 17)})]
    (is (= 17 (:value r)))
    (is (= [0 0] (:calls r)))
    (is (= 2 (:streak r)) "Child synthetic repair leaves one error; parent's own later failure adds one")))

(deftest successful-parent-unwind-does-not-erase-child-failure
  (let [r (scripted [(str "'(do (!llm-self " (pr-str prefix) ") 42)")
                     "missing-child"]
                    {:recover (constantly 17)})]
    (is (= 42 (:value r)))
    (is (= [0 0] (:calls r)))
    (is (= 1 (:streak r)) "The parent already succeeded at handoff; its return cannot reset again")))

(deftest nested-terminal-child-is-never-recounted-by-parent
  (let [r (scripted [(str "'(!llm-self " (pr-str prefix) ")")
                     "missing-child" "missing-repair"]
                    {:max-consecutive-errors 2})]
    (is (= :recovery-exhausted (get-in r [:data :type])))
    (is (= 2 (get-in r [:data :consecutive-errors])))
    (is (= 2 (:streak r)))
    (is (= [0 0 1] (:calls r)))))

(deftest propagated-disabled-recovery-error-is-not-ancestors-own
  (let [r (scripted [(str "'(!llm-self " (pr-str prefix) ")") "missing-child"]
                    {:recover false})]
    (is (some? (:exception r)))
    (is (nil? (get-in r [:data :type])))
    (is (= 1 (:streak r)))
    (is (= [0 0] (:calls r)))))

(defn- inbox [options]
  (llm/make-inbox-fn
    (merge {:variant-builtins {} :eval-builtin (llm/make-eval {} {}) :recover-fn nil} options) nil))

(deftest successful-model-evaluation-resets-reader-and-eval-failures
  (let [f (inbox {})]
    (coordinator/register! :main)
    (binding [runtime/*current-handle* :main]
      (is (:exception (outcome #(f "missing"))))
      (is (= 1 (streak :main)))
      (is (:exception (outcome #(f "\\invalidchar"))))
      (is (= 2 (streak :main)))
      (is (= 42 (f "42")))
      (is (= 0 (streak :main)))
      (is (:exception (outcome #(f "missing"))))
      (is (= 1 (streak :main))))))

(deftest state-is-isolated-by-agent-run-and-revived-generation
  (let [f (inbox {})]
    (doseq [h [:a :b]] (coordinator/register! h))
    (doseq [h [:a :a :b]]
      (binding [runtime/*current-handle* h] (outcome #(f "missing"))))
    (is (= 2 (streak :a)))
    (is (= 1 (streak :b)))
    (coordinator/finish! :a (:completed (coordinator/agent :a)) :done)
    (coordinator/send! :a {:message {:from :b :body :wake}})
    (is (= 2 (:generation (coordinator/agent :a))))
    (binding [runtime/*current-handle* :a] (outcome #(f "missing")))
    (is (= 1 (streak :a)) "Revived generation starts fresh")
    (th/with-test-run
      (fn []
        (coordinator/register! :a)
        (binding [runtime/*current-handle* :a] (outcome #(f "missing")))
        (is (= 1 (streak :a)) "Same inbox closure cannot leak state across runs")))
    (is (= 1 (streak :b)))))

(deftest accepted-wait-resets-before-suspension-and-idle-does-not
  (let [f (inbox {:eval-builtin (llm/make-eval {} {'agents runtime/agents-namespace})})]
    (doseq [h [:main :peer]] (coordinator/register! h))
    (binding [runtime/*current-handle* :main]
      (outcome #(f "missing"))
      (coordinator/request! :main [:peer] true :question)
      (with-redefs [runtime/block-for-message (fn []
                                              (is (= 0 (streak :main)))
                                              :accepted)]
        (is (= :accepted (f "(eval '(agents/!wait))")))))
    (coordinator/register! :idle)
    (let [called (atom 0)]
      (binding [runtime/*current-handle* :idle runtime/*current-raw* "42"
                eval/*completion-handoff* #(swap! called inc)]
        (is (nil? (runtime/wait!)))
        (is (zero? @called))))))


(deftest external-wait-resets-only-after-admission
  (let [f (inbox {:eval-builtin
                  (llm/make-eval {} {'!ask-await stdlib/ask-await-builtin
                                        'audit {:pending (fn [] {:spell/future true :ref (promise)})}})})
        launches (atom 0)]
    (coordinator/register! :main)
    (binding [runtime/*current-handle* :main]
      (outcome #(f "missing"))
      (is (= 1 (streak :main)))
      (with-redefs [clojure.core/future-call
                    (fn [_]
                      (swap! launches inc)
                      (is (= 0 (streak :main)) "Reset precedes monitor launch")
                      (delay nil))
                    runtime/block-for-message
                    (fn []
                      (is (= 0 (streak :main)) "Reset precedes suspension")
                      :accepted)]
        (is (= :accepted (f "(eval '(!ask-await (audit/pending)))")))))
    (is (= 1 @launches))))

(deftest ready-wait-resets-before-continuation-and-refusal-does-not
  (let [resets (atom 0) blocks (atom 0)]
    (doseq [h [:main :peer]] (coordinator/register! h))
    (binding [runtime/*current-handle* :main runtime/*current-raw* "active"
              eval/*completion-handoff* #(swap! resets inc)]
      (coordinator/send! :main {:message {:from :peer :body :ready}})
      (with-redefs [runtime/block-for-message
                    (fn []
                      (is (= 1 @resets) "Ready messages are a validated handoff")
                      (swap! blocks inc)
                      :accepted)]
        (is (= :accepted (runtime/wait!)))
        (coordinator/drain! :main)
        (coordinator/request! :peer [:main] true :answer-me)
        (coordinator/drain! :main)
        (is (= :sleep-refused (:type (:data (outcome runtime/wait!)))))
        (is (= 1 @resets) "Refusal throws before recording a handoff")
        (is (= 1 @blocks))))))

(deftest invalid-inactive-and-refused-external-waits-do-not-reset
  (let [resets (atom 0) launches (atom 0)
        pending {:spell/future true :ref (promise)}]
    (doseq [h [:main :peer]] (coordinator/register! h))
    (binding [eval/*completion-handoff* #(swap! resets inc)]
      (is (:exception (outcome #(stdlib/ask-await-builtin pending)))))
    (binding [runtime/*current-handle* :main runtime/*current-raw* "active"
              eval/*completion-handoff* #(swap! resets inc)]
      (is (:exception (outcome #(stdlib/ask-await-builtin :invalid))))
      (coordinator/request! :peer [:main] true :answer-me)
      (coordinator/drain! :main)
      (with-redefs [clojure.core/future-call (fn [_] (swap! launches inc) (delay nil))]
        (is (= :sleep-refused (:type (:data (outcome #(stdlib/ask-await-builtin pending))))))))
    (is (zero? @resets))
    (is (zero? @launches))))


(deftest provider-failures-do-not-count-or-trigger-model-repair
  (doseq [first-model-error? [false true]]
    (th/with-test-run
      (fn []
        (let [calls (atom 0)
              p (provider/test-provider
                  {:prefill? false
                   :response-fn (fn [_]
                                  (let [n (swap! calls inc)]
                                    (if (and first-model-error? (= 1 n)) "missing"
                                      (throw (ex-info "Provider unavailable" {})))))} )
              a (llm/compile-agent {:provider p :prefill? false :namespaces {}})
              r (outcome #(th/run-agent-prefix a prefix))]
          (is (some? (:exception r)))
          (is (= (if first-model-error? 2 1) @calls))
          (is (= (if first-model-error? 1 0) (streak :main))))))))

(deftest provider-failure-after-model-repair-handoff-keeps-reset
  (let [calls (atom [])
        p (provider/test-provider
            {:prefill? false
             :response-fn
             (fn [_]
               (swap! calls conj (streak runtime/*current-handle*))
               (case (count @calls)
                 1 "missing"
                 2 "'(!llm-self (reopen completion))"
                 (throw (ex-info "Provider unavailable" {}))))})
        a (llm/compile-agent {:provider p :prefill? false :namespaces {}})
        r (outcome #(th/run-agent-prefix a prefix))]
    (is (:exception r))
    (is (= [0 1 0] @calls))
    (is (= 0 (streak :main)) "A provider failure is not a failing model-authored completion")))

(deftest invalid-self-call-options-do-not-reset-before-validation
  (let [r (scripted ["missing"
                     "'(!llm-self (reopen completion) {:receive? :invalid})"
                     "missing"] {})]
    (is (= [0 1 2] (:calls r)))
    (is (= :recovery-exhausted (get-in r [:data :type])))
    (is (= 3 (:streak r)))))

(deftest positive-integer-validation-and-profile-inheritance
  (doseq [n [nil false 0 -1 1.5 "3"]]
    (let [r (outcome #(llm/compile-agent {:max-consecutive-errors n}))]
      (is (= :invalid-max-consecutive-errors (get-in r [:data :type])))
      (is (= n (get-in r [:data :value])))))
  (is (= 3 llm/default-max-consecutive-errors))
  (is (= 5 (:max-consecutive-errors (#'agent/merge-agent-defs {:max-consecutive-errors 5} {}))))
  (is (= 2 (:max-consecutive-errors (#'agent/merge-agent-defs {:max-consecutive-errors 5}
                                                           {:max-consecutive-errors 2}))))
  (let [seen (atom nil)]
    (#'agent/compile-runtime-agent-from-resolved-spec
      {:max-consecutive-errors 4} #(do (reset! seen %) identity) {})
    (is (= 4 (:max-consecutive-errors @seen)))))

(deftest api-override-and-separate-runs
  (let [calls (atom 0)
        p (provider/test-provider {:response-fn (fn [_] (swap! calls inc) "missing")})
        opts {:prompt "Fail deterministically" :model-profile p
              :agent-profile "config/agent-profiles/base-msg.agent.edn"
              :max-consecutive-errors 2}]
    (doseq [_ (range 2)]
      (let [r (api/run opts)]
        (is (= :recovery-exhausted (get-in r [:error-data :type])))
        (is (= 2 (get-in r [:error-data :consecutive-errors])))))
    (is (= 4 @calls))))

(deftest inherited-profile-and-public-override-determine-real-failure-limit
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "spell-recovery-profile-" (make-array java.nio.file.attribute.FileAttribute 0)))
        base (java.io.File. dir "base.agent.edn")
        child (java.io.File. dir "child.agent.edn")
        calls (atom 0)
        p (provider/test-provider {:response-fn (fn [_] (swap! calls inc) "missing")})]
    (try
      (spit base (pr-str {:system-prompt "" :available-agents [] :max-consecutive-errors 4}))
      (doseq [[child-options override expected]
              [[{} {} 4]
               [{:max-consecutive-errors 2} {} 2]
               [{:max-consecutive-errors 2} {:max-consecutive-errors 1} 1]]]
        (spit child (pr-str (merge {:base "base.agent.edn"} child-options)))
        (reset! calls 0)
        (let [r (api/run (merge {:prompt "Fail deterministically" :model-profile p
                                :agent-profile (.getAbsolutePath child)} override))]
          (is (= :recovery-exhausted (get-in r [:error-data :type])))
          (is (= expected (get-in r [:error-data :limit])))
          (is (= expected @calls))))
      (spit child (pr-str {:base "base.agent.edn" :max-consecutive-errors 0}))
      (reset! calls 0)
      (let [r (outcome #(api/run {:prompt "Must not call the provider" :model-profile p
                                :agent-profile (.getAbsolutePath child)}))]
        (is (= :invalid-max-consecutive-errors
               (or (get-in r [:data :type]) (get-in r [:value :error-data :type]))))
        (is (zero? @calls)))
      (finally
        (.delete child)
        (.delete base)
        (.delete dir)))))
