(ns spell.receipt-checkpoint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [spell.context :as context]
            [spell.coordinator :as coordinator]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.parse :as parse]
            [spell.provider :as provider]
            [spell.runtime :as runtime]))

(def ^:private deadline-ms 10000)

(defn- await! [source label]
  (let [value (deref source deadline-ms ::timeout)]
    (when (= ::timeout value)
      (throw (ex-info (str "Timed out: " label) {:label label})))
    (when (instance? Throwable value)
      (throw (ex-info (str "Failed before " label) {:label label} value)))
    value))

(use-fixtures :each
  (fn [f]
    (binding [context/*context* (context/new-context)
              coordinator/*coordinator* (coordinator/new-coordinator)
              globals/*store* (globals/new-store)]
      (try (f) (finally (coordinator/close!))))))

(def ^:private parent-prefix
  "(quine completion (eval (do (def checkpoint-marker :P) ")
(def ^:private child-prefix
  "(quine completion (eval (do (def checkpoint-marker :C) ")
(def ^:private helper-prefix "(eval '(do ")

(defn- canonical-suffix [tail-action]
  (str (pr-str (list 'quote tail-action)) ")))"))

(defn- helper-suffix [forms]
  (str (str/join " " (map pr-str forms)) "))"))

(defn- initial-program []
  (pr-str
    (list 'quine 'completion
      (list 'eval
        (list 'do
          (list 'quote
            (list '!llm-self parent-prefix {:receive? true})))))))

(defn- make-agent [response-fn marks extra-probe-builtins]
  (llm/compile-agent
    {:provider (provider/test-provider {:prefill? false :response-fn response-fn})
     :prefill? false
     :recover false
     :namespaces {'agents runtime/agents-namespace
                  'probe (merge {:mark (fn [value] (swap! marks conj value) nil)}
                                extra-probe-builtins)}}))

(defn- record-prompt! [prompts prompt]
  (count (swap! prompts conj prompt)))

(defn- assert-wake-prefix [prompt checkpoint message]
  (is (str/includes? prompt (str "(def checkpoint-marker " checkpoint ")"))
      "The model must resume the newest receiving checkpoint")
  (is (not (str/starts-with? prompt helper-prefix))
      "A helper's noncanonical raw must never become the resume prefix")
  (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote (str message))) prompt)))
      "The receipt must materialize the queued message once"))

(defn- run-descendant-checkpoint-case! [mode]
  (let [marks (atom [])
        prompts (atom [])
        parent-ready (promise)
        root-result (atom nil)
        parent-action
        (list 'do
          '(probe/mark :p-before)
          (list 'def 'helper-value (list '!llm-self helper-prefix))
          '(probe/mark :p-after)
          '(probe/parent-ready helper-value)
          (if (= :wait mode) '(agents/!wait) :parent-ok))
        helper-forms
        ['(probe/mark :h-before)
         (list '!llm-self child-prefix {:receive? true})
         '(probe/mark :h-after)
         :helper-ok]
        message (if (= :wait mode) :wake-after-helper :wake-after-return)
        agent
        (make-agent
          (fn [prompt]
            (case (record-prompt! prompts prompt)
              1 (canonical-suffix parent-action)
              2 (helper-suffix helper-forms)
              3 (canonical-suffix '(do (probe/mark :c-ran) :child-ok))
              4 (canonical-suffix '(do (probe/mark :wake) checkpoint-marker))
              (throw (ex-info "Unexpected extra model call" {:prompt prompt :mode mode}))))
          marks
          {:parent-ready
           (fn [value]
             ;; Make the upcoming !wait admissible before notifying the
             ;; observer. Otherwise an empty wait can return :idle before
             ;; the observer sends, accidentally testing dormant wakeup.
             (when (= :wait mode)
               (coordinator/request! :main [:checkpoint-worker] true :hold-wait))
             (deliver parent-ready value))})]
    (try
      ;; This registered handle has no runner. Its pending collection retains
      ;; the parent wait and is cancelled by ordinary lifecycle completion.
      (when (= :wait mode) (coordinator/register! :checkpoint-worker))
      (reset! root-result
              (future
                (try
                  (agent (llm/direct-init (initial-program)) :main)
                  (catch Throwable e
                    (deliver parent-ready e)
                    (throw e)))))
      (is (= :helper-ok (await! parent-ready "parent after raw helper returned")))
      (let [completion
            (if (= :wait mode)
              @root-result
              (do
                (is (= :parent-ok (await! @root-result "first root lifecycle")))
                (:completed (coordinator/agent :main))))]
        ;; At :wait the parent now owns an unresolved outgoing collection, so
        ;; sending before or after wait admission must resume this lifecycle.
        ;; At :dormant the full parent/helper call stack has already unwound.
        (coordinator/send! :main {:message {:from :observer :body message}})
        (is (= :C (await! completion "resumed checkpoint value")))
        (is (= 4 (count @prompts)))
        (assert-wake-prefix (nth @prompts 3) :C message)
        (is (not (str/includes? (nth @prompts 3) "(def checkpoint-marker :P)"))
            "A valid older parent frame must not supersede its receiving descendant")
        (is (= [:p-before :h-before :c-ran :h-after :p-after :wake] @marks)
            "All parent/helper/child effects run once, in their actual stack order")
        (is (empty? (:mailbox (coordinator/agent :main)))))
      (finally
        (coordinator/close!)
        (when-let [runner @root-result]
          (when-not (realized? runner) (future-cancel runner)))))))

(deftest receiving-descendant-survives-return-to-parent-then-explicit-wait
  (testing "receiving P -> raw H -> receiving C -> H returns -> P waits"
    (run-descendant-checkpoint-case! :wait)))

(deftest receiving-descendant-survives-whole-stack-unwind-and-dormant-wake
  (testing "receiving P -> raw H -> receiving C -> H/P return -> dormant wake"
    (run-descendant-checkpoint-case! :dormant)))

(deftest agent-registered-by-raw-helper-keeps-its-starting-context
  (let [prompts (atom [])
        saved "(quine completion (eval (do (def checkpoint-marker :registered-context) 'nil)))"
        agent (make-agent
                (fn [prompt]
                  (case (record-prompt! prompts prompt)
                    1 (canonical-suffix (list '!llm-self helper-prefix))
                    2 (helper-suffix [(list 'agents/register :registered saved)])
                    3 (canonical-suffix 'checkpoint-marker)
                    (throw (ex-info "Unexpected model call" {:prompt prompt}))))
                (atom []) {})]
    (is (= :registered (agent (llm/direct-init (initial-program)) :main)))
    (is (= saved (:last-raw @(:execution (coordinator/agent :registered)))))
    (let [completion (:completed (coordinator/agent :registered))]
      (coordinator/send! :registered {:message {:from :observer :body :registered-wake}})
      (is (= :registered-context (await! completion "registered agent wake")))
      (assert-wake-prefix (nth @prompts 2) :registered-context :registered-wake))))

(deftest explicit-wait-from-within-noncanonical-raw-helper
  (let [marks (atom [])
        prompts (atom [])
        helper-generating (promise)
        release-helper (promise)
        root-result (atom nil)
        parent-action
        (list 'do
          '(probe/mark :p-before)
          (list 'def 'helper-value (list '!llm-self helper-prefix))
          '(probe/mark :p-after)
          'helper-value)
        helper-forms
        ['(probe/mark :h-before)
         '(def waited (agents/!wait))
         '(probe/mark :h-after)
         'waited]
        agent
        (make-agent
          (fn [prompt]
            (case (record-prompt! prompts prompt)
              1 (canonical-suffix parent-action)
              2 (do
                  (deliver helper-generating true)
                  (await! release-helper "release noncanonical helper generation")
                  (helper-suffix helper-forms))
              3 (canonical-suffix '(do (probe/mark :wake) checkpoint-marker))
              (throw (ex-info "Unexpected extra model call" {:prompt prompt}))))
          marks {})]
    (try
      (reset! root-result
              (future
                (try
                  (agent (llm/direct-init (initial-program)) :main)
                  (catch Throwable e
                    (deliver helper-generating e)
                    (throw e)))))
      (is (= true (await! helper-generating "raw helper generation begins")))
      ;; Receiving P must not implicitly make nested raw H receive. Leave this
      ;; message queued throughout H generation so H's explicit wait consumes it.
      (coordinator/send! :main {:message {:from :observer :body :wake-inside-helper}})
      (deliver release-helper true)
      (is (= :P (await! @root-result "wait invoked inside noncanonical helper")))
      (is (= 3 (count @prompts)))
      (assert-wake-prefix (nth @prompts 2) :P :wake-inside-helper)
      (is (= [:p-before :h-before :wake :h-after :p-after] @marks)
          "The raw helper executes before receiving; resumption must not replay the parent")
      (is (empty? (:mailbox (coordinator/agent :main))))
      (finally
        (deliver release-helper true)
        (coordinator/close!)
        (when-let [runner @root-result]
          (when-not (realized? runner) (future-cancel runner)))))))
