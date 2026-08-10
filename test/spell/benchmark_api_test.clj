(ns spell.benchmark-api-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.runtime :as runtime]
            [spell.benchmark-api :as benchmark-api]
            [spell.provider :as provider]))

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
          out ((var benchmark-api/response-ok) "spell" start {:usage-tracker usage-atom})]
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
          out ((var benchmark-api/response-ok) "spell" start {:usage-tracker usage-atom})]
      (is (nil? (get-in out [:usage :by-model "unknown-model" :cost])))
      (is (nil? (get-in out [:usage :total :cost])))
      (is (string? (json/write-str out))))))

(deftest fireworks-model-spec-and-default-agent-profile-test
  (testing "parse-model-spec accepts fireworks prefix"
    (is (= {:provider "fireworks" :model "glm-5"}
           ((var benchmark-api/parse-model-spec) "fireworks:glm-5"))))

  (testing "default-agent-profile resolution uses the fireworks provider config"
    (is (= "config/model-profiles/fireworks.edn"
           (get benchmark-api/model-profile-edn-by-prefix "fireworks")))))

(deftest fireworks-tc-model-spec-and-default-agent-profile-test
  (testing "parse-model-spec accepts fireworks-tc prefix"
    (is (= {:provider "fireworks-tc" :model "kimi-k2p6"}
           ((var benchmark-api/parse-model-spec) "fireworks-tc:kimi-k2p6"))))

  (testing "default-agent-profile resolution uses the fireworks-tc provider config"
    (is (= "config/model-profiles/fireworks-tc.edn"
           (get benchmark-api/model-profile-edn-by-prefix "fireworks-tc")))
    (is (str/ends-with? ((var benchmark-api/default-agent-profile-from-request)
                         {:model "fireworks-tc:kimi-k2p6"})
                        "config/model-profiles/../agent-profiles/base-tc.agent.edn"))))

(deftest openai-tc-model-spec-and-default-agent-profile-test
  (testing "parse-model-spec accepts openai-tc prefix"
    (is (= {:provider "openai-tc" :model "gpt-5.4"}
           ((var benchmark-api/parse-model-spec) "openai-tc:gpt-5.4"))))

  (testing "default-agent-profile resolution uses the openai-tc provider config"
    (is (= "config/model-profiles/openai-tc.edn"
           (get benchmark-api/model-profile-edn-by-prefix "openai-tc")))
    (is (str/ends-with? ((var benchmark-api/default-agent-profile-from-request)
                         {:model "openai-tc:gpt-5.4"})
                        "config/model-profiles/../agent-profiles/base-tc.agent.edn"))))

(deftest make-provider-resolves-shared-model-aliases-test
  (testing "bare open-weight aliases route to Fireworks tool-call transport"
    (doseq [[alias expected] [["glm" "glm-5p2"]
                             ["kimi" "kimi-k2p7-code"]
                             ["qwen" "qwen3p7-plus"]
                             ["glm51" "glm-5p1"]
                             ["kimi26" "kimi-k2p6"]
                             ["qwen36p" "qwen3p6-plus"]]]
      (let [captured (atom nil)]
        (with-redefs [provider/fireworks-tc-provider
                      (fn [opts]
                        (reset! captured opts)
                        {:provider :fireworks-tc :opts opts})]
          (is (= :fireworks-tc
                 (:provider ((var benchmark-api/make-provider) {:model alias}))))
          (is (= expected (:model @captured)))))))

  (testing "bare gpt alias routes to OpenAI tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/openai-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :openai-tc :opts opts})]
        (is (= :openai-tc
               (:provider ((var benchmark-api/make-provider) {:model "gpt"}))))
        (is (= "gpt-5.6-sol" (:model @captured)))
        (is (:use-responses-api @captured))
        (is (:force-tool-call @captured))))))

(deftest run-spell-trace-default-test
  (testing "trace defaults to an absolute temp dir when no trace-dir override is provided"
    (let [result (with-redefs [benchmark-api/make-provider
                               (fn [_] (provider/test-provider {:response "(def x 42))"}))
                               benchmark-api/default-agent-profile-from-request
                               (fn [_] "config/agent-profiles/base-msg.agent.edn")]
                   ((var benchmark-api/run-spell) {:prompt "Return 42"
                                                   :model "test:dummy"
                                                   :trace true}))]
      (is (:ok result))
      (is (.isAbsolute (java.io.File. (:trace_dir result))))
      (is (= (.getAbsolutePath
               (java.io.File. (System/getProperty "java.io.tmpdir")
                              "spell-traces"))
             (.getAbsolutePath (.getParentFile (java.io.File. (:trace_dir result))))))))

  (testing "explicit trace-dir override is preserved exactly"
    (let [trace-dir (.toString (java.nio.file.Files/createTempDirectory
                                 "spell-benchmark-trace-"
                                 (make-array java.nio.file.attribute.FileAttribute 0)))
          result (with-redefs [benchmark-api/make-provider
                               (fn [_] (provider/test-provider {:response "(def x 42))"}))
                               benchmark-api/default-agent-profile-from-request
                               (fn [_] "config/agent-profiles/base-msg.agent.edn")]
                   ((var benchmark-api/run-spell) {:prompt "Return 42"
                                                   :model "test:dummy"
                                                   :trace true
                                                   :trace-dir trace-dir}))]
      (is (:ok result))
      (is (= trace-dir (:trace_dir result))))))
