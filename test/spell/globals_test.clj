(ns spell.globals-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.globals :as globals]
            [spell.core :as spell]
            [spell.provider :as provider]))

;; Reset globals between tests
(use-fixtures :each
  (fn [f]
    (globals/reset-globals!)
    (f)
    (globals/reset-globals!)))

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
;; Integration tests (with DummyProvider)
;; =============================================================================

(deftest globals-accessible-from-spell-test
  (testing "globals/get and globals/set work from Spell code"
    (provider/with-provider
      (provider/dummy-provider {:response "(globals/set :x 42)(globals/get :x))"})
      (is (= 42 (spell/llm "(do "))))))

(deftest globals-update-from-spell-test
  (testing "globals/update works from Spell code"
    (provider/with-provider
      (provider/dummy-provider
        {:response "(globals/update :roles (fn [m] (assoc m :h1 \"worker\")))(globals/get :roles))"})
      (is (= {:h1 "worker"} (spell/llm "(do "))))))

(deftest globals-pop-from-spell-test
  (testing "globals/pop works from Spell code"
    (globals/set-val :tasks [{:id 1} {:id 2}])
    (provider/with-provider
      (provider/dummy-provider {:response "(globals/pop :tasks))"})
      (is (= {:id 1} (spell/llm "(do "))))))

(deftest globals-persist-across-llm-calls-test
  (testing "globals set in one llm call are visible in the next"
    (let [call-count (atom 0)
          responses ["(globals/set :shared-val 99)(llm-self \"(do \")"
                     "(globals/get :shared-val))"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= 99 (spell/llm "(do ")))))))
