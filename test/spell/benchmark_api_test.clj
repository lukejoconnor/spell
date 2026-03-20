(ns spell.benchmark-api-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest response-ok-usage-summary-test
  (testing "serializes usage-summary with mean/max context fields"
    (let [start (System/nanoTime)
          usage-atom (atom {:by-model {"model-a" {:input_tokens 300
                                                  :output_tokens 120
                                                  :cache_creation_input_tokens 10
                                                  :cache_read_input_tokens 20
                                                  :calls 2
                                                  :max_total_tokens 260}}})
          out ((var benchmark-api/response-ok) "spell" start {:usage usage-atom})]
      (is (== 225.0 (get-in out [:usage :by-model "model-a" :mean_total_tokens])))
      (is (= 260 (get-in out [:usage :by-model "model-a" :max_total_tokens])))
      (is (== 225.0 (get-in out [:usage :total :mean_total_tokens])))
      (is (= 260 (get-in out [:usage :total :max_total_tokens]))))))

(deftest fireworks-model-spec-and-default-agent-test
  (testing "parse-model-spec accepts fireworks prefix"
    (is (= {:provider "fireworks" :model "glm-5"}
           ((var benchmark-api/parse-model-spec) "fireworks:glm-5"))))

  (testing "default-agent resolution uses the fireworks provider config"
    (is (= "config/providers/fireworks.provider.edn"
           (get benchmark-api/provider-edn-by-prefix "fireworks")))))

(deftest openai-tc-model-spec-and-default-agent-test
  (testing "parse-model-spec accepts openai-tc prefix"
    (is (= {:provider "openai-tc" :model "gpt-5.4"}
           ((var benchmark-api/parse-model-spec) "openai-tc:gpt-5.4"))))

  (testing "default-agent resolution uses the openai-tc provider config"
    (is (= "config/providers/openai-tc.provider.edn"
           (get benchmark-api/provider-edn-by-prefix "openai-tc")))
    (is (str/ends-with? ((var benchmark-api/default-agent-from-request)
                         {:model "openai-tc:gpt-5.4"})
                        "config/providers/../agents/base-tc.agent.edn"))))
