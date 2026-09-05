(ns spell.composable-waits-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [spell.coordinator :as coordinator]
            [spell.runtime :as runtime]
            [spell.api :as api]
            [spell.eval :as eval]
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
