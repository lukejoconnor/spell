(ns spell.callable-arity-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.eval :as ev]))

;; Adapted from dev/dogfood-reliability-run/codex-evaluator-probes.clj.
;; Each probe has a fresh evaluator environment and records host-visible effects.
(defn- probe [label form expected expected-effects]
  (testing (name label)
    (let [effects (atom [])
          result (ev/spell-eval form
                               {'record (fn [x] (swap! effects conj x) x)})]
      (is (ev/ok? result) (pr-str result))
      (is (= expected (:ok result)) (pr-str result))
      (is (= expected-effects @effects)))))

(deftest seed-values-and-catchable-errors
  (doseq [[label form expected]
          [[:direct-missing '(try ((fn [x y] y) 1) (catch e (:message e))) "Wrong number of args: expected 2, got 1"]
           [:direct-extra '(try ((fn [x] x) 1 2) (catch e (:message e))) "Wrong number of args: expected 1, got 2"]
           [:zero '(do (defn zero [] :zero) (zero)) :zero]
           [:zero-extra '(try ((fn [] :body) 1) (catch e (:message e))) "Wrong number of args: expected 0, got 1"]
           [:dynamic-capture-rejected '(let [y :outer] (try ((fn [x y] y) 1) (catch e (:message e)))) "Wrong number of args: expected 2, got 1"]
           [:apply-caught '(try (apply (fn [x y] y) [1]) (catch e (re-find "expected 2, got 1" (:message e)))) "expected 2, got 1"]
           [:callback-caught '(try (map (fn [x y] y) [1]) (catch e (re-find "expected 2, got 1" (:message e)))) "expected 2, got 1"]
           [:reduce-caught '(try (reduce (fn [acc] acc) 0 [1]) (catch e (re-find "expected 1, got 2" (:message e)))) "expected 1, got 2"]
           [:update-caught '(try (update {:x 1} :x (fn [] :body)) (catch e (re-find "expected 0, got 1" (:message e)))) "expected 0, got 1"]
           [:direct-dynamic '(let [y 7] ((fn [x] (+ x y)) 1)) 8]
           [:apply-dynamic '(let [y 7] (apply (fn [x] (+ x y)) [1])) 8]
           [:callback-dynamic '(let [y 7] (map (fn [x] (+ x y)) [1 2])) [8 9]]
           [:destructuring-rest '((fn [[x & more :as all]] [x more all]) [1 2 3]) [1 [2 3] [1 2 3]]]
           [:destructuring-nil '((fn [[x y]] [x y]) [1]) [1 nil]]
           [:map-default '((fn [{:keys [x] :or {x 9}}] x) {}) 9]
           [:apply-prefix '(apply (fn [x y] [x y]) 1 [2]) [1 2]]
           [:recur-valid '((fn [x acc] (if (= x 0) acc (recur (dec x) (+ acc x)))) 3 0) 6]
           [:recur-destructure '((fn [[x acc]] (if (= x 0) acc (recur [(dec x) (+ acc x)]))) [3 0]) 6]
           [:recur-missing '(try ((fn [x y] (if x (recur false) y)) true 1) (catch e (:message e))) "Wrong number of args: expected 2, got 1"]
           [:recur-extra '(try ((fn [x] (if x (recur false 2) :done)) true) (catch e (:message e))) "Wrong number of args: expected 1, got 2"]
           [:argument-env-preserved '(try ((fn [x] :body) (do (def a 1) :one) (do (def b 2) :two)) (catch e [a b])) [1 2]]]]
    (probe label form expected [])))

(deftest seed-invalid-call-effect-ordering
  (probe :direct-effects '(try ((fn [x] (record :body)) (record :one) (record :two)) (catch e :caught)) :caught [:one :two])
  (probe :apply-effects '(try (apply (fn [x] (record :body)) [(record :one) (record :two)]) (catch e :caught)) :caught [:one :two])
  (probe :callback-effects '(try (map (fn [x y] (record :body)) [(record :one)]) (catch e :caught)) :caught [:one])
  (probe :recur-effects '(try ((fn [x] (record :body) (recur (record :next) (record :extra))) 1) (catch e :caught)) :caught [:body :next :extra]))

(deftest omitted-parameters-never-capture-caller-bindings
  (doseq [[label form expected]
          [[:apply-capture-rejected
            '(let [y :outer]
               (try (apply (fn [x y] y) [1])
                    (catch e (re-find "expected 2, got 1" (:message e)))))
            "expected 2, got 1"]
           [:callback-capture-rejected
            '(let [y :outer]
               (try (map (fn [x y] y) [1])
                    (catch e (re-find "expected 2, got 1" (:message e)))))
            "expected 2, got 1"]
           [:destructured-parameter-missing
            '(let [x :outer]
               (try ((fn [[x]] x))
                    (catch e (:message e))))
            "Wrong number of args: expected 1, got 0"]
           [:named-function-missing
            '(do (defn pair [x y] y)
                 (let [y :outer]
                   (try (pair 1) (catch e (:message e)))))
            "Wrong number of args: expected 2, got 1"]]]
    (probe label form expected [])))

(deftest valid-calls-use-call-time-dynamic-scope
  (doseq [[label form expected]
          [[:direct-call-time-binding
            '(let [y 100]
               (defn add-y [x] (+ x y))
               (let [y 7] (add-y 1)))
            8]
           [:apply-call-time-binding
            '(let [y 100]
               (defn add-y [x] (+ x y))
               (let [y 7] (apply add-y [1])))
            8]
           [:callback-call-time-binding
            '(let [y 100]
               (defn add-y [x] (+ x y))
               (let [y 7] (map add-y [1 2])))
            [8 9]]
           [:supplied-parameter-shadows-caller
            '(let [y :outer] ((fn [x y] y) 1 :supplied))
            :supplied]
           [:destructured-missing-element-shadows-caller
            '(let [y :outer] ((fn [[x y]] [x y]) [1]))
            [1 nil]]]]
    (probe label form expected [])))

(deftest successful-call-effect-ordering
  (doseq [[label form expected effects]
          [[:direct
            '((fn [x y] (record :body) [x y])
              (record :one) (record :two))
            [:one :two] [:one :two :body]]
           [:apply
            '(apply (fn [x y] (record :body) [x y])
                    (record :one) [(record :two)])
            [:one :two] [:one :two :body]]
           [:callback
            '(map (fn [x] (record x))
                  [(record :one) (record :two)])
            [:one :two] [:one :two :one :two]]
           [:reduce
            '(reduce (fn [acc x] (record :body) (+ acc x))
                     (record 0) [(record 1) (record 2)])
            3 [0 1 2 :body :body]]
           [:update
            '(update {:x (record 1)} :x
                     (fn [x y] (record :body) (+ x y)) (record 2))
            {:x 3} [1 2 :body]]]]
    (probe label form expected effects)))

(deftest invalid-calls-evaluate-arguments-but-never-enter-body
  (doseq [[label form expected effects]
          [[:missing-direct
            '(try ((fn [x y] (record :body)) (record :one))
                  (catch e :caught))
            :caught [:one]]
           [:reduce
            '(try (reduce (fn [acc] (record :body))
                          (record 0) [(record 1)])
                  (catch e :caught))
            :caught [0 1]]
           [:update
            '(try (update {:x (record 1)} :x (fn [] (record :body)))
                  (catch e :caught))
            :caught [1]]]]
    (probe label form expected effects)))
