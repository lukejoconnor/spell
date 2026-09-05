(ns spell.composable-waits-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [spell.coordinator :as coordinator]
            [spell.runtime :as runtime]
            [spell.api :as api]
            [spell.provider :as provider]
            [spell.test-helpers :as th]))

(use-fixtures :each
  (fn [f]
    (binding [coordinator/*coordinator* (coordinator/new-coordinator)]
      (try (f) (finally (coordinator/close!))))))

(defn- register-all [& handles]
  (doseq [handle handles] (coordinator/register! handle)))

(deftest immediate-request-orders-effects-test
  (register-all :parent :a :b)
  (let [first-edge (coordinator/request! :parent [:a] true :first)]
    (is (= :awake (:status (coordinator/agent :parent))))
    (is (= :first (get-in (coordinator/agent :a) [:mailbox 0 :message :body])))
    ;; An intervening coordinator interaction sees the first request now.
    (let [second-edge (coordinator/request! :a [:b] true first-edge)]
      (is (< first-edge second-edge))
      (is (= first-edge (get-in (coordinator/agent :b) [:mailbox 0 :message :body])))
      (is (= #{first-edge second-edge} (set (keys (:edges (coordinator/snapshot)))))))))

(deftest fast-completion-before-wait-test
  (register-all :parent :worker)
  (let [edge (coordinator/request! :parent [:worker] false nil)]
    (coordinator/drain! :worker)
    (coordinator/fill! edge :worker nil)
    (is (empty? (:edges (coordinator/snapshot))))
    (is (= :ready (:status (coordinator/wait! :parent))))
    (let [messages (coordinator/drain! :parent)]
      (is (= 1 (count messages)))
      (is (contains? (:message (first messages)) :body))
      (is (nil? (get-in messages [0 :message :body]))))
    ;; The completion can already have been consumed by a nested self-call.
    (is (= :idle (:status (coordinator/wait! :parent))))
    (is (= :awake (:status (coordinator/agent :parent))))))

(deftest retained-waits-and-newer-incoming-test
  (register-all :parent :a :b :requester)
  (let [a (coordinator/request! :parent [:a] false nil)
        b (coordinator/request! :parent [:b] false nil)]
    (is (= :waiting (:status (coordinator/wait! :parent))))
    (coordinator/fill! a :a :done)
    (is (= :ready (:status (coordinator/wait! :parent))))
    (coordinator/drain! :parent)
    (is (= [b] (mapv :id (coordinator/outgoing (coordinator/snapshot) :parent))))
    (is (= :waiting (:status (coordinator/wait! :parent))))
    (let [incoming (coordinator/request! :requester [:parent] true :question)]
      (coordinator/drain! :parent)
      (let [before (coordinator/snapshot)
            failure (try (coordinator/wait! :parent) nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :sleep-refused (:type failure)))
        (is (= before (coordinator/snapshot))))
      (coordinator/fill! incoming :parent :answer)
      (is (= :waiting (:status (coordinator/wait! :parent)))))))

(deftest capacity-counts-hyperedges-and-releases-test
  (binding [coordinator/*coordinator* (coordinator/new-coordinator {:max-edges 1})]
    (register-all :parent :a :b)
    (let [edge (coordinator/request! :parent [:a :b] false nil)
          before (coordinator/snapshot)
          failure (try (coordinator/request! :parent [:a] true :rejected) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :coordinator-capacity (:type failure)))
      (is (= before (coordinator/snapshot)))
      (coordinator/fill! edge :a :partial)
      (is (thrown? clojure.lang.ExceptionInfo
                   (coordinator/request! :parent [:b] false nil)))
      (coordinator/fill! edge :b :complete)
      (let [next-edge (coordinator/request! :parent [:a] false nil)]
        (is (> next-edge edge))
        (coordinator/cancel! :parent next-edge)
        (is (integer? (coordinator/request! :parent [:b] false nil)))))))

(deftest capacity-admission-race-test
  (binding [coordinator/*coordinator* (coordinator/new-coordinator {:max-edges 1})]
    (register-all :parent :a :b)
    (let [start (promise)
          attempt (fn [target]
                    (future @start
                            (try {:edge (coordinator/request! :parent [target] true target)}
                                 (catch clojure.lang.ExceptionInfo e
                                   {:error (:type (ex-data e))}))))
          a (attempt :a)
          b (attempt :b)]
      (deliver start true)
      (let [results [(deref a 5000 :timeout) (deref b 5000 :timeout)]]
        (is (= 1 (count (filter :edge results))))
        (is (= 1 (count (filter #(= :coordinator-capacity (:error %)) results))))
        (is (= 1 (count (:edges (coordinator/snapshot)))))
        (is (= 1 (+ (count (:mailbox (coordinator/agent :a)))
                    (count (:mailbox (coordinator/agent :b))))))))))

(deftest spawn-capacity-rejection-rolls-back-entire-batch-test
  (binding [coordinator/*coordinator* (coordinator/new-coordinator {:max-edges 1})]
    (register-all :parent :existing)
    (coordinator/request! :parent [:existing] false nil)
    (let [before (coordinator/snapshot)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (coordinator/spawn-request! :parent
                     [{:handle :a :parent-handle :parent}
                      {:handle :b :parent-handle :parent}])))
      (is (= before (coordinator/snapshot)))
      (is (nil? (coordinator/agent :a)))
      (is (nil? (coordinator/agent :b))))))

(deftest capacity-options-validated-test
  (is (= 10000 (get-in @(coordinator/new-coordinator) [:options :max-edges])))
  (doseq [invalid [{:max-edges 0} {:max-edges -1} {:max-edges 1.5}
                   {:max-edges nil} {:max-edges "10"} {:max-slots 100}]]
    (is (thrown? clojure.lang.ExceptionInfo (coordinator/new-coordinator invalid)))))

(deftest public-nonblocking-request-and-wait-test
  (register-all :parent :worker)
  (binding [runtime/*current-handle* :parent
            runtime/*current-raw* "(quine completion (eval (do )))"
            runtime/*current-eval-fn* identity]
    (let [edge ((:ask runtime/agents-namespace) :worker :question)]
      (is (integer? edge))
      (is (= :awake (:status (coordinator/agent :parent))))
      (coordinator/fill! edge :worker :fast-result)
      (let [raw ((:!wait runtime/agents-namespace))]
        (is (string? raw))
        (is (.contains ^String raw ":fast-result")))
      (is (nil? ((:!wait runtime/agents-namespace))))
      (is (nil? ((:!sleep runtime/agents-namespace)))))))

(deftest public-spawn-registers-before-launch-and-returns-edge-test
  (register-all :parent)
  (let [started (promise)
        finish (promise)
        child (th/compiled-agent-fn
                (fn [_ handle]
                  (deliver started {:handle handle
                                    :incoming (coordinator/incoming (coordinator/snapshot) handle)})
                  @finish))]
    (try
      (binding [runtime/*current-handle* :parent
                runtime/*current-raw* "(quine completion (eval (do )))"]
        (let [edge ((:spawn-ask runtime/agents-namespace) child :prompt :child)
              observed (deref started 5000 :timeout)]
          (is (integer? edge))
          (is (= :awake (:status (coordinator/agent :parent))))
          (is (= edge (get-in observed [:incoming 0 :id])))
          (is (= 1 (get-in observed [:incoming 0 :slots :child :generation])))))
      (finally (deliver finish :done)))))

(deftest public-multi-spawn-rejection-never-launches-test
  (binding [coordinator/*coordinator* (coordinator/new-coordinator {:max-edges 1})]
    (register-all :parent :existing)
    (coordinator/request! :parent [:existing] false nil)
    (let [started (atom 0)
          child (th/compiled-agent-fn (fn [_ _] (swap! started inc)))
          before (coordinator/snapshot)]
      (binding [runtime/*current-handle* :parent
                runtime/*current-raw* "(quine completion (eval (do )))"]
        (is (thrown? clojure.lang.ExceptionInfo
                     ((:spawn-ask runtime/agents-namespace)
                      [[child :prompt :a] [child :prompt :b]]))))
      (is (= before (coordinator/snapshot)))
      (is (zero? @started)))))

(deftest lifecycle-return-releases-capacity-test
  (binding [coordinator/*coordinator* (coordinator/new-coordinator {:max-edges 1})]
    (register-all :parent :worker)
    (coordinator/request! :parent [:worker] false nil)
    (coordinator/finish! :parent (:completed (coordinator/agent :parent)) :done)
    (is (empty? (:edges (coordinator/snapshot))))
    (is (integer? (coordinator/request! :worker [:parent] false nil)))))

(deftest public-api-coordinator-capacity-test
  (let [observed (atom nil)
        provider (reify provider/LLMProvider
                   (call-llm [this prompt] (provider/call-llm this prompt {}))
                   (call-llm [_ _ _]
                     (reset! observed (get-in (coordinator/snapshot) [:options :max-edges]))
                     "(def answer 42))")
                   (plain-text-provider [this] this)
                   (supports-prefill [_] true))
        opts {:prompt "Return 42"
              :model-profile provider
              :agent-profile "config/agent-profiles/base-msg.agent.edn"}]
    (is (= 42 (:result (api/run (assoc opts :coordinator {:max-edges 7})))))
    (is (= 7 @observed))
    (is (thrown? clojure.lang.ExceptionInfo
                 (api/run (assoc opts :coordinator {:max-edges 0}))))))

(deftest nested-self-call-between-registration-and-wait-test
  (register-all :worker)
  (let [calls (atom [])
        agent (th/make-test-agent
                {:response-fn
                 (fn [prefix]
                   (swap! calls conj prefix)
                   (case (count @calls)
                     1 (let [edge (first (coordinator/outgoing (coordinator/snapshot) :main))]
                         ;; The request is committed before the nested model call.
                         (is (some? edge))
                         (is (= :question (get-in (coordinator/agent :worker)
                                                 [:mailbox 0 :message :body])))
                         (coordinator/fill! (:id edge) :worker :fast-result)
                         "'41)))")
                     2 (do
                         ;; Existing receipt timing consumes the fast result in
                         ;; this nested invocation and requests its continuation.
                         (is (empty? (:mailbox (coordinator/agent :main))))
                         "'42)))")
                     (throw (ex-info "Unexpected model call" {:calls (count @calls)}))))}
                :recover false)
        result (future
                 (th/run-agent-init
                   agent
                   "(eval (do '(do (def edge (agents/ask :worker :question)) (def nested (!llm-self \"(quine completion (eval (do \")) (agents/!wait) :continued)))"))]
    (is (= :continued (deref result 5000 :timeout)))
    (is (= 2 (count @calls)))
    (is (empty? (:edges (coordinator/snapshot))))))

(deftest reverse-request-capacity-is-atomic-test
  (binding [coordinator/*coordinator* (coordinator/new-coordinator {:max-edges 1})]
    (register-all :parent :a :b)
    (let [edge (coordinator/request! :parent [:a :b] true :question)
          request (get-in (first (coordinator/drain! :a)) [:message])
          before (coordinator/snapshot)]
      ;; Filling only A would leave the old edge live, so its reverse request
      ;; needs another unit. Rejection must also roll back the old slot fill.
      (is (thrown? clojure.lang.ExceptionInfo
                   (coordinator/reply-request! :a request :answer)))
      (is (= before (coordinator/snapshot)))
      (coordinator/fill! edge :b :other-answer)
      ;; A's answer now completes the old edge, freeing capacity for reversal.
      (is (integer? (coordinator/reply-request! :a request :answer)))
      (is (= 1 (count (:edges (coordinator/snapshot)))))
      (let [messages (mapv :message (coordinator/drain! :parent))]
        (is (= 2 (count messages)))
        (is (= [:answer :other-answer] (mapv :body (:body (first messages)))))
        (is (true? (:expects-response (second messages))))))))
