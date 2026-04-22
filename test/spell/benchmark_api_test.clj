(ns spell.benchmark-api-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.runtime :as runtime]
            [spell.benchmark-api :as benchmark-api]))

(deftest normalize-format-spec-test
  (testing "plain strings default to keywords (Clojure convention)"
    (let [normalized ((var benchmark-api/normalize-format-spec)
                      {:required ["result"]
                       :optional ["confidence"]})]
      (is (= [:result] (:required normalized)))
      (is (= [:confidence] (:optional normalized)))))
  (testing "leading ':' is accepted as explicit keyword marker"
    (let [normalized ((var benchmark-api/normalize-format-spec)
                      {:required [":answer"]})]
      (is (= [:answer] (:required normalized)))))
  (testing "leading apostrophe marks strings as symbols"
    (let [normalized ((var benchmark-api/normalize-format-spec)
                      {:required ["'raw-symbol"]})]
      (is (= ['raw-symbol] (:required normalized))))))

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
                                                  :cost 0.123
                                                  :calls 2
                                                  :max_total_tokens 260}}})
          out ((var benchmark-api/response-ok) "spell" start {:usage usage-atom})]
      (is (== 225.0 (get-in out [:usage :by-model "model-a" :mean_total_tokens])))
      (is (= 260 (get-in out [:usage :by-model "model-a" :max_total_tokens])))
      (is (= 0.123 (get-in out [:usage :by-model "model-a" :cost])))
      (is (== 225.0 (get-in out [:usage :total :mean_total_tokens])))
      (is (= 260 (get-in out [:usage :total :max_total_tokens])))
      (is (= 0.123 (get-in out [:usage :total :cost])))))

  (testing "unpriced usage serializes with nil cost instead of NaN"
    (let [start (System/nanoTime)
          usage-atom (atom {:by-model {"unknown-model" {:input_tokens 300
                                                        :output_tokens 120
                                                        :calls 1
                                                        :cost nil}}})
          out ((var benchmark-api/response-ok) "spell" start {:usage usage-atom})]
      (is (nil? (get-in out [:usage :by-model "unknown-model" :cost])))
      (is (nil? (get-in out [:usage :total :cost])))
      (is (string? (json/write-str out))))))

(deftest killed-response-reflects-partial-work-test
  (try
    (runtime/register! :main)
    (reset! (:last-raw (get @runtime/registry :main)) "(def x 1)")
    (with-redefs [benchmark-api/patch-on-disk? (constantly true)]
      (let [start (System/nanoTime)
            out ((var benchmark-api/killed-response) {:trace-dir "traces/demo"} "spell" start)]
        (is (false? (:ok out)))
        (is (= "killed" (:error_type out)))
        (is (true? (:patch_on_disk out)))
        (is (true? (get-in out [:error_data "patch_on_disk"])))
        (is (true? (get-in out [:error_data "partial_work"])))
        (is (= "traces/demo" (:trace_dir out)))))
    (finally
      (reset! runtime/registry {}))))

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
