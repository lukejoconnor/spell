(ns spell.sleep-refusal-recovery-test
  "Refused suspension is an ordinary agent error; the guard itself stays atomic."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [spell.coordinator :as coordinator]
            [spell.runtime :as runtime]
            [spell.stdlib :as stdlib]
            [spell.test-helpers :as th]))

(defn- response [form] (str (pr-str form) ")))"))
(defn- message-symbol [prefix]
  (or (some-> (last (re-seq #"\(def (msg-[0-9]+)" prefix)) second symbol)
      (throw (ex-info "Expected the original request in context" {}))))
(defn- bodies [handle]
  (mapv #(get-in % [:message :body]) (:mailbox (coordinator/agent handle))))
(defn- wait-form [kind]
  (case kind
    :ordinary '(agents/!wait)
    :external '(!ask-await (audit/pending-future))))
(defn- logical-state [edge]
  (let [state (coordinator/snapshot)]
    {:agent (select-keys (get-in state [:agents :main]) [:status :generation :completed])
     :edge (get-in state [:edges edge])
     :external-waits (:external-waits state)
     :next-edge-id (:next-edge-id state)}))
(defn- run-refusal
  "Inject one real request before the attempted suspension. On recovery the
   provider either replies to that same envelope, or attempts the refused wait
   again. Records retain the actual coordinator state before any lifecycle ends."
  [kind recover? catch? repeat?]
  (coordinator/register! :peer)
  (let [calls (atom 0) prefixes (atom []) edge (atom nil) before (atom nil)
        handled (atom []) pending {:spell/future true :ref (promise)}
        record! (fn [error]
                  (swap! handled conj {:error error :state (logical-state @edge)}) nil)
        agent (th/make-test-agent
                {:response-fn
                 (fn [prefix]
                   (swap! prefixes conj prefix)
                   (let [call (swap! calls inc)]
                     (cond
                       (= call 1)
                       (do (reset! edge (coordinator/request! :peer [:main] true :answer-me))
                           (response '(quote (!extend))))
                       (= call 2)
                       (let [attempt (list 'do '(audit/before) (wait-form kind))]
                         (response
                           (list 'quote
                                 (if catch?
                                   (list 'try attempt
                                         (list 'catch 'failure
                                               (list 'do '(audit/handled failure)
                                                     (list 'agents/reply (message-symbol prefix) 17)
                                                     :caught)))
                                   attempt))))
                       :else
                       (do (record! :model-recovery)
                           (response
                             (list 'quote
                                   (if repeat?
                                     (wait-form kind)
                                     (list 'do (list 'agents/reply (message-symbol prefix) 17)
                                           :recovered))))))))}
                :namespaces {'agents runtime/agents-namespace
                             'audit {:before #(reset! before (logical-state @edge))
                                     :handled record!
                                     :pending-future (constantly pending)}}
                :prefill? false :recover recover?)
        outcome (try {:value (th/run-agent-prefix agent "(quine completion (eval (do ")}
                     (catch Exception e {:exception e}))]
    (merge outcome {:calls @calls :prefixes @prefixes :before @before
                    :handled @handled :bodies (bodies :peer)
                    :agent (coordinator/agent :main)
                    :state (coordinator/snapshot)})))

(deftest refusal-is-atomic-at-both-suspension-apis-test
  (doseq [kind [:ordinary :external]]
    (th/with-test-run
      (fn []
        (doseq [handle [:peer :main]] (coordinator/register! handle))
        (let [edge (coordinator/request! :peer [:main] true :answer-me)
              _ (coordinator/drain! :main)
              before (coordinator/snapshot)
              monitor-starts (atom 0)
              outcome (try
                        (with-redefs [clojure.core/future-call
                                      (fn [_] (swap! monitor-starts inc)
                                        (throw (ex-info "Unexpected notification future" {})))]
                          (binding [runtime/*current-handle* :main
                                    runtime/*current-raw* "active turn"]
                            (case kind
                              :ordinary (runtime/wait!)
                              :external (stdlib/ask-await-builtin
                                          {:spell/future true :ref (promise)}))))
                        (catch Exception e (ex-data e)))]
          (is (= :sleep-refused (:type outcome)) (str kind))
          (is (= [edge] (mapv :id (:in-edges outcome))))
          (is (zero? @monitor-starts) "Refused external waits never start a notification future")
          (is (= before (coordinator/snapshot)) "Refusal commits no state transition or external wait"))))))

(deftest spell-catch-can-handle-refusal-without-model-recovery-test
  (doseq [kind [:ordinary :external] recover? [false true]]
    (th/with-test-run
      (fn []
        (let [{:keys [value calls before handled bodies state]} (run-refusal kind recover? true false)
              error (:error (first handled))]
          (is (= :caught value) (str kind " recover=" recover?))
          (is (= 2 calls) "Catch handles the error without another model call")
          (is (str/includes? (:message error) ":sleep-refused"))
          (is (str/includes? (:message error) ":in-edges"))
          (is (= [before] (mapv :state handled)) "Same pending slot, lifecycle and awake status in catch")
          (is (= [17] bodies) "Explicit reply satisfies the original request before final return")
          (is (empty? (:edges state))))))))

(deftest default-recovery-can-reply-in-the-same-lifecycle-test
  (doseq [kind [:ordinary :external]]
    (th/with-test-run
      (fn []
        (let [{:keys [value calls prefixes before handled bodies agent state]}
              (run-refusal kind true false false)]
          (is (= :recovered value) (str kind))
          (is (= 3 calls))
          (is (str/includes? (last prefixes) "_error"))
          (is (str/includes? (last prefixes)
                             (case kind :ordinary "Cannot sleep" :external "Cannot await computation")))
          (is (= :awake (get-in before [:agent :status])))
          (is (= :pending (get-in before [:edge :slots :main :status])))
          (is (= 1 (get-in before [:edge :slots :main :generation])))
          (is (= [before] (mapv :state handled)) "Recovery preserves the original claimed slot and completion")
          (is (= [17] bodies))
          (is (= 1 (:generation agent)) "Recovery did not restart the handle")
          (is (empty? (:edges state)))
          (is (empty? (:external-waits state))))))))

(deftest disabled-recovery-still-fails-the-lifecycle-test
  (doseq [kind [:ordinary :external]]
    (th/with-test-run
      (fn []
        (let [{:keys [exception calls handled bodies agent state]} (run-refusal kind false false false)]
          (is (some? exception) (str kind))
          (is (re-find #"Cannot (sleep|await computation)" (ex-message exception)))
          (is (= 2 calls))
          (is (empty? handled))
          (is (= 1 (count bodies)))
          (is (true? (:spell/child-failure (first bodies))))
          (is (= :lifecycle (:phase (first bodies))))
          (is (some? agent) "Normal failure cleanup preserves a compiled handle")
          (is (empty? (:edges state)))
          (is (empty? (:external-waits state))))))))

(deftest repeated-refusal-uses-the-shared-recovery-limit-test
  (doseq [kind [:ordinary :external]]
    (th/with-test-run
      (fn []
        (let [{:keys [exception calls before handled bodies state]} (run-refusal kind true false true)]
          (is (= :recovery-exhausted (:type (ex-data exception))) (str kind))
          (is (= 2 (:limit (ex-data exception))))
          (is (= 4 calls) "Initial request delivery, refused attempt, and two recovery calls")
          (is (= [before before] (mapv :state handled)))
          (is (true? (:spell/child-failure (first bodies))))
          (is (empty? (:external-waits state))))))))

(deftest other-typed-errors-still-bypass-catch-and-recovery-test
  (doseq [error-type [:budget-exceeded :depth-exceeded :recovery-exhausted
                     :coordinator-closed :coordinator-capacity
                     :stale-agent-lifecycle :agent-blocking-call
                     :agent-in-computation-future :unrecognized-control]
          recover? [false true]]
    (th/with-test-run
      (fn []
        (let [calls (atom 0) caught (atom false)
              agent (th/make-test-agent
                      {:response-fn (fn [_] (swap! calls inc)
                                      (response '(quote (try (audit/fail)
                                                             (catch error (audit/caught))))))}
                      :namespaces {'audit {:fail #(throw (ex-info "Typed terminal error" {:type error-type}))
                                           :caught #(reset! caught true)}}
                      :prefill? false :recover recover?)
              outcome (try (th/run-agent-prefix agent "(quine completion (eval (do ")
                           (catch Exception e e))]
          (is (= error-type (:type (ex-data outcome))) (str error-type " recover=" recover?))
          (is (= 1 @calls))
          (is (false? @caught)))))))
