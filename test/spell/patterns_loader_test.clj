(ns spell.patterns-loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.parse :as parse]
            [spell.patterns :as patterns]))

(def bundles [:check-result :ralph :team :fix-loop :relay :mailing-list])

(deftest public-namespace-has-only-module-verbs
  (is (= #{:install :catalog :source :update :call}
         (set (remove #{:short-docs :docs :detail} (keys patterns/patterns))))))

(deftest bundled-definitions-are-executable-source-data
  (doseq [module bundles]
    (testing (name module)
      (let [forms (parse/read-all
                    (slurp (str "config/spl-lib/modules/" (name module) ".spl")))
            definition (first forms)]
        (is (= 1 (count forms)))
        (is (map? definition))
        (is (string? (:doc definition)))
        (is (map? (:functions definition)))
        (is (seq (:functions definition)))
        (doseq [[k entry] (:functions definition)]
          (is (keyword? k))
          (is (string? (:doc entry)))
          (is (vector? (:requires entry)))
          (is (every? symbol? (:requires entry)))
          (is (seq? (:source entry)))
          (is (= 'fn (first (:source entry)))))))))
