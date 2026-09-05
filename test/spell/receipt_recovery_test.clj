(ns spell.receipt-recovery-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [spell.context :as context]
            [spell.coordinator :as coordinator]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.runtime :as runtime]))

(use-fixtures :each
  (fn [f]
    (binding [context/*context* (context/new-context)
              coordinator/*coordinator* (coordinator/new-coordinator)
              globals/*store* (globals/new-store)]
      (try (f) (finally (coordinator/close!))))))

(defn suffix [action] (str (pr-str (list 'quote action)) ")))"))

(defn check-recovery [phase receive?]
  (let [calls (atom [])
        seen (atom nil)
        parent-prefix "(quine completion (eval (do (def parent-marker :parent) "
        helper-prefix "(quine completion (eval (do (def helper-marker :helper) "
        agent
        (llm/compile-agent
          {:provider
           (provider/test-provider
             {:prefill? false
              :response-fn
              (fn [prompt]
                (case (count (swap! calls conj prompt))
                  1 (suffix (list '!llm-self helper-prefix {:receive? receive?}))
                  2 (do
                      (coordinator/send! :main
                        {:message {:from :observer :body :recovery-envelope}})
                      (if (= phase :reader)
                        "{:odd-map})))"
                        (suffix '(/ 1 0))))
                  3 (suffix '(probe/observe))
                  (throw (ex-info "Unexpected extra model call" {:prompt prompt}))))})
           :prefill? false
           :namespaces
           {'probe
            {:observe
             (fn []
               (reset! seen (mapv :message (:mailbox (coordinator/agent :main))))
               ;; End the synthetic run after observing the recovery boundary;
               ;; queued messages must not trigger an unrelated dormant lifecycle.
               (coordinator/close!)
               :recovered)}}})
        init (pr-str
               (list 'quine 'completion
                 (list 'eval
                   (list 'do
                     (list 'quote
                       (list '!llm-self parent-prefix {:receive? true}))))))
        result (agent (llm/direct-init init) :main)]
    (is (= :recovered result))
    (is (= 3 (count @calls)))
    (is (= (if receive? [] [{:from :observer :body :recovery-envelope}]) @seen)
        "Recovery must retain the originating call's receipt choice")
    (is (= (if receive? 1 0)
           (count (re-seq #":recovery-envelope" (nth @calls 2))))
        "An already-drained batch appears once in receiving reader recovery")))

(deftest raw-reader-recovery-under-receiving-parent
  (check-recovery :reader false))
(deftest raw-evaluation-recovery-under-receiving-parent
  (check-recovery :eval false))
(deftest receiving-reader-recovery-retains-drained-batch
  (check-recovery :reader true))
