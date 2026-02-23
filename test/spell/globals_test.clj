(ns spell.globals-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.comm :as comm]
            [spell.globals :as globals]
            [spell.core :as spell]
            [spell.provider :as provider]
            [spell.test-helpers :as th]))

;; Reset globals and comm registry between tests
(use-fixtures :each
  (fn [f]
    (globals/reset-globals!)
    (reset! comm/registry {})
    (f)
    (globals/reset-globals!)
    (reset! comm/registry {})))

;; =============================================================================
;; Unit tests
;; =============================================================================

(deftest initial-state-test
  (testing "globals starts with :roles and :tasks"
    (is (= {} (globals/get-val :roles)))
    (is (= [] (globals/get-val :tasks)))
    (is (= #{:roles :tasks} (set (globals/list-keys))))))

(deftest get-set-test
  (testing "set returns value, get retrieves it"
    (is (= 42 (globals/set-val :x 42)))
    (is (= 42 (globals/get-val :x))))

  (testing "set overwrites existing value"
    (globals/set-val :x 1)
    (globals/set-val :x 2)
    (is (= 2 (globals/get-val :x))))

  (testing "get returns nil for missing key"
    (is (nil? (globals/get-val :nonexistent)))))

(deftest update-test
  (testing "update applies function atomically"
    (globals/set-val :counter 0)
    (is (= 1 (globals/update-val :counter inc)))
    (is (= 1 (globals/get-val :counter))))

  (testing "update on roles map"
    (is (= {:h1 "researcher"}
           (globals/update-val :roles (fn [m] (assoc m :h1 "researcher")))))
    (is (= {:h1 "researcher"} (globals/get-val :roles)))))

(deftest pop-test
  (testing "pop returns first element and removes it"
    (globals/set-val :tasks [{:id 1} {:id 2} {:id 3}])
    (is (= {:id 1} (globals/pop-val :tasks)))
    (is (= [{:id 2} {:id 3}] (globals/get-val :tasks))))

  (testing "pop from empty returns nil"
    (globals/set-val :tasks [])
    (is (nil? (globals/pop-val :tasks))))

  (testing "pop from missing key returns nil"
    (is (nil? (globals/pop-val :nonexistent)))))

(deftest list-keys-test
  (testing "keys reflects all set keys"
    (globals/set-val :a 1)
    (globals/set-val :b 2)
    (is (= #{:roles :tasks :a :b} (set (globals/list-keys))))))

(deftest get-all-test
  (testing "all returns entire map"
    (globals/set-val :x 42)
    (let [all (globals/get-all)]
      (is (= 42 (:x all)))
      (is (= {} (:roles all))))))

(deftest reset-test
  (testing "reset restores initial state"
    (globals/set-val :x 42)
    (globals/set-val :roles {:h1 "worker"})
    (globals/reset-globals!)
    (is (nil? (globals/get-val :x)))
    (is (= {} (globals/get-val :roles)))
    (is (= [] (globals/get-val :tasks)))))

;; =============================================================================
;; Concurrency tests
;; =============================================================================

(deftest concurrent-update-test
  (testing "concurrent updates don't lose data"
    (globals/set-val :counter 0)
    (let [n 100
          futures (doall (repeatedly n #(future (globals/update-val :counter inc))))]
      (doseq [f futures] @f)
      (is (= n (globals/get-val :counter))))))

(deftest concurrent-pop-test
  (testing "concurrent pops claim each item exactly once"
    (let [items (vec (range 50))]
      (globals/set-val :work items)
      (let [results (atom [])
            futures (doall (repeatedly 50
                     #(future
                        (when-let [item (globals/pop-val :work)]
                          (swap! results conj item)))))]
        (doseq [f futures] @f)
        ;; Each item claimed at most once, all items claimed
        (is (= (set items) (set @results)))
        (is (empty? (globals/get-val :work)))))))

;; =============================================================================
;; wait-until tests
;; =============================================================================

(deftest wait-until-already-satisfied-test
  (testing "returns true immediately if predicate is already satisfied"
    (globals/set-val :x 42)
    (is (true? (globals/wait-until (fn [state] (= 42 (:x state))))))))

(deftest wait-until-blocks-then-unblocks-test
  (testing "blocks until a concurrent update satisfies predicate"
    (globals/set-val :counter 0)
    (let [result (future (globals/wait-until (fn [state] (>= (:counter state) 5))))]
      ;; Should be blocked
      (Thread/sleep 50)
      (is (not (realized? result)))
      ;; Increment counter in steps
      (dotimes [_ 5]
        (globals/update-val :counter inc))
      ;; Should unblock
      (is (true? (deref result 2000 :timeout))))))

(deftest wait-until-race-condition-test
  (testing "handles state change between check and add-watch"
    ;; State changes to satisfy predicate immediately after initial check.
    ;; The double-check after add-watch catches this.
    (globals/set-val :ready false)
    (let [result (future
                   ;; Another thread sets ready=true almost immediately
                   (future (Thread/sleep 10) (globals/set-val :ready true))
                   (globals/wait-until (fn [state] (:ready state))))]
      (is (true? (deref result 2000 :timeout))))))

(deftest wait-until-concurrent-agents-test
  (testing "multiple agents posting results, coordinator waits"
    (globals/set-val :results [])
    (let [n 10
          ;; Start wait-until in coordinator
          coordinator (future
                        (globals/wait-until
                          (fn [state] (= n (count (:results state))))))
          ;; Spawn n workers that each post a result
          workers (doall
                    (for [i (range n)]
                      (future
                        (Thread/sleep (rand-int 50))
                        (globals/update-val :results
                          (fn [r] (conj r i))))))]
      ;; Wait for all workers
      (doseq [w workers] (deref w 2000 :timeout))
      ;; Coordinator should have unblocked
      (is (true? (deref coordinator 2000 :timeout)))
      (is (= n (count (globals/get-val :results)))))))

;; =============================================================================
;; Integration tests (with TestProvider)
;; =============================================================================

(deftest globals-accessible-from-spell-test
  (testing "globals/get and globals/set work from Spell code (via eval)"
    (let [{:keys [llm]} (th/make-test-llm {:response "(globals/set :x 42)(globals/get :x))"})]
      (is (= 42 (llm "(eval '(do "))))))

(deftest globals-update-from-spell-test
  (testing "globals/update works from Spell code (via eval)"
    (let [{:keys [llm]} (th/make-test-llm
                          {:response "(globals/update :roles (fn [m] (assoc m :h1 \"worker\")))(globals/get :roles))"})]
      (is (= {:h1 "worker"} (llm "(eval '(do "))))))

(deftest globals-pop-from-spell-test
  (testing "globals/pop works from Spell code (via eval)"
    (globals/set-val :tasks [{:id 1} {:id 2}])
    (let [{:keys [llm]} (th/make-test-llm {:response "(globals/pop :tasks))"})]
      (is (= {:id 1} (llm "(eval '(do "))))))

(deftest globals-persist-across-llm-calls-test
  (testing "globals set in one llm call are visible in the next"
    (let [call-count (atom 0)
          responses ["(globals/set :shared-val 99)(llm-self \"(eval '(do \"))"
                     "(globals/get :shared-val))"]]
      (let [{:keys [llm]} (th/make-test-llm
                            {:response-fn (fn [_]
                                            (let [r (nth responses @call-count)]
                                              (swap! call-count inc)
                                              r))})]
        (is (= 99 (llm "(eval '(do ")))))))
