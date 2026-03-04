(ns spell.patterns-loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.parse :as parse]
            [spell.patterns :as patterns]))

(def ^:private doc-keys
  #{:short-docs :docs :detail})

(defn- defn-keys-from-spl
  []
  (->> (parse/read-all (slurp "config/spl-lib/patterns.spl"))
       (keep (fn [form]
               (when (and (seq? form)
                          (= 'defn (first form))
                          (symbol? (second form)))
                 (keyword (name (second form))))))
       set))

(deftest patterns-loader-sync-test
  (testing "patterns namespace exports every top-level defn in patterns.spl"
    (let [expected (defn-keys-from-spl)
          actual   (->> (keys patterns/patterns)
                        (remove doc-keys)
                        set)]
      (is (= expected actual))
      (doseq [k expected]
        (is (= true (get-in patterns/patterns [k :spell/fn]))
            (str k " should be a spell/fn"))
        (is (vector? (get-in patterns/patterns [k :params]))
            (str k " should have vector params"))
        (is (seq (get-in patterns/patterns [k :body]))
            (str k " should have non-empty body"))))))
