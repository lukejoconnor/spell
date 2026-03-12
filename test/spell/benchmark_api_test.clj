(ns spell.benchmark-api-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.benchmark-api :as benchmark-api]))

(deftest normalize-format-spec-test
  (testing "parses JSON string keys into symbols/keywords for validation"
    (let [normalized ((var benchmark-api/normalize-format-spec)
                      {:required ["result" ":answer"]
                       :optional [":confidence" "source"]})]
      (is (= ['result :answer] (:required normalized)))
      (is (= [:confidence 'source] (:optional normalized))))))

(deftest response-ok-unwrap-result-test
  (testing "unwraps keyword result wrapper before stringifying"
    (let [start (System/nanoTime)
          out ((var benchmark-api/response-ok) "spell" start {:result {:result 42}})]
      (is (= "42" (:result out)))))

  (testing "unwraps symbol result wrapper before stringifying"
    (let [start (System/nanoTime)
          out ((var benchmark-api/response-ok) "spell" start {:result {'result "abc"}})]
      (is (= "abc" (:result out))))))

(deftest fireworks-model-spec-and-default-agent-test
  (testing "parse-model-spec accepts fireworks prefix"
    (is (= {:provider "fireworks" :model "glm-5"}
           ((var benchmark-api/parse-model-spec) "fireworks:glm-5"))))

  (testing "default-agent resolution uses the fireworks provider config"
    (is (= "config/providers/fireworks.provider.edn"
           (get benchmark-api/provider-edn-by-prefix "fireworks")))))
