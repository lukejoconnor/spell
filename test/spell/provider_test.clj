(ns spell.provider-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [spell.provider :as provider])
  (:import [java.io ByteArrayOutputStream]
           [java.nio ByteBuffer]
           [java.util.concurrent CompletableFuture Flow$Subscriber Flow$Subscription]))

(defn- request-json-body
  [request]
  (let [publisher (.get (.bodyPublisher request))
        out (ByteArrayOutputStream.)
        done (promise)]
    (.subscribe publisher
                (reify Flow$Subscriber
                  (onSubscribe [_ subscription]
                    (.request ^Flow$Subscription subscription Long/MAX_VALUE))
                  (onNext [_ item]
                    (let [buf ^ByteBuffer item
                          bytes (byte-array (.remaining buf))]
                      (.get buf bytes)
                      (.write out bytes 0 (alength bytes))))
                  (onError [_ throwable]
                    (deliver done throwable))
                  (onComplete [_]
                    (deliver done true))))
    (let [result (deref done 1000 ::timeout)]
      (cond
        (instance? Throwable result) (throw result)
        (= ::timeout result) (throw (ex-info "Timed out reading request body" {}))
        :else (json/read-str (.toString out "UTF-8") :key-fn keyword)))))

(defn- request-timeout-seconds
  [request]
  (some-> request .timeout (.orElse nil) .getSeconds))

;; =============================================================================
;; Anthropic PF response parsing
;; =============================================================================

(deftest anthropic-pf-parse-response-test
  (testing "parses successful response with text content"
    (let [body (json/write-str {:content [{:type "text" :text "(def x 42)"}]
                                :usage {:input_tokens 100
                                        :output_tokens 30
                                        :cache_creation_input_tokens 50
                                        :cache_read_input_tokens 20}})
          result (#'provider/parse-anthropic-pf-response body)]
      (is (= "(def x 42)" (:text result)))
      (is (= 100 (get-in result [:usage :input_tokens])))
      (is (= 30 (get-in result [:usage :output_tokens])))
      (is (= 50 (get-in result [:usage :cache_creation_input_tokens])))
      (is (= 20 (get-in result [:usage :cache_read_input_tokens])))))

  (testing "joins multiple text blocks with newline"
    (let [body (json/write-str {:content [{:type "text" :text "first"}
                                          {:type "text" :text "second"}]
                                :usage {:input_tokens 10 :output_tokens 5}})
          result (#'provider/parse-anthropic-pf-response body)]
      (is (= "first\nsecond" (:text result)))))

  (testing "throws on error response"
    (let [body (json/write-str {:error {:type "invalid_request_error"
                                        :message "bad request"}})]
      (is (thrown-with-msg? Exception #"Anthropic API error"
            (#'provider/parse-anthropic-pf-response body)))))

  (testing "handles missing cache usage gracefully"
    (let [body (json/write-str {:content [{:type "text" :text "ok"}]
                                :usage {:input_tokens 10 :output_tokens 5}})
          result (#'provider/parse-anthropic-pf-response body)]
      (is (= "ok" (:text result)))
      (is (= 0 (get-in result [:usage :cache_creation_input_tokens])))
      (is (= 0 (get-in result [:usage :cache_read_input_tokens]))))))

(deftest anthropic-pf-parse-stream-test
  (testing "accumulates text from content_block_delta events"
    (let [sse (str "event: message_start\n"
                   "data: "
                   (json/write-str {:type "message_start"
                                    :message {:usage {:input_tokens 50
                                                      :cache_creation_input_tokens 10
                                                      :cache_read_input_tokens 5}}})
                   "\n\n"
                   "event: content_block_delta\n"
                   "data: "
                   (json/write-str {:type "content_block_delta"
                                    :delta {:text "(def x "}})
                   "\n\n"
                   "event: content_block_delta\n"
                   "data: "
                   (json/write-str {:type "content_block_delta"
                                    :delta {:text "42)"}})
                   "\n\n"
                   "event: message_delta\n"
                   "data: "
                   (json/write-str {:type "message_delta"
                                    :usage {:output_tokens 12}})
                   "\n\n"
                   "data: [DONE]\n\n")
          result (#'provider/parse-anthropic-pf-stream sse)]
      (is (= "(def x 42)" (:text result)))
      (is (= 50 (get-in result [:usage :input_tokens])))
      (is (= 12 (get-in result [:usage :output_tokens])))
      (is (= 10 (get-in result [:usage :cache_creation_input_tokens])))
      (is (= 5 (get-in result [:usage :cache_read_input_tokens])))))

  (testing "ignores unknown event types gracefully"
    (let [sse (str "data: " (json/write-str {:type "message_start"
                                              :message {:usage {:input_tokens 1}}})
                   "\n\n"
                   "data: " (json/write-str {:type "ping"})
                   "\n\n"
                   "data: " (json/write-str {:type "content_block_stop"})
                   "\n\n"
                   "data: " (json/write-str {:type "content_block_delta"
                                              :delta {:text "ok"}})
                   "\n\n"
                   "data: " (json/write-str {:type "message_delta"
                                              :usage {:output_tokens 1}})
                   "\n\n")
          result (#'provider/parse-anthropic-pf-stream sse)]
      (is (= "ok" (:text result)))))

  (testing "returns empty text when no content_block_delta events"
    (let [sse (str "data: " (json/write-str {:type "message_start"
                                              :message {:usage {:input_tokens 5}}})
                   "\n\n"
                   "data: " (json/write-str {:type "message_delta"
                                              :usage {:output_tokens 0}})
                   "\n\n")
          result (#'provider/parse-anthropic-pf-stream sse)]
      (is (= "" (:text result))))))

;; =============================================================================
;; Cost lookup
;; =============================================================================

(deftest lookup-cost-test
  (testing "exact match returns normalized cost"
    (let [result (#'provider/lookup-cost "claude-sonnet-4" {"claude-sonnet-4" [3.00 15.00]})]
      (is (= 3.00 (:input result)))
      (is (= 15.00 (:output result)))))

  (testing "longest prefix match wins over shorter prefix"
    (let [table {"gpt-5.4" [2.50 15.00]
                 "gpt-5.4-pro" [30.00 180.00]}
          result (#'provider/lookup-cost "gpt-5.4-pro-20250101" table)]
      (is (= 30.00 (:input result)))
      (is (= 180.00 (:output result)))))

  (testing "normalizes vector cost spec with derived cache prices"
    (let [result (#'provider/lookup-cost "model-x" {"model-x" [3.00 15.00]})]
      (is (= 3.75 (:cache-write-input result)) "cache-write = 1.25x input")
      (is (< (abs (- 0.30 (:cache-read-input result))) 0.001) "cache-read = 0.10x input (default ratio)")))

  (testing "normalizes map cost spec with explicit cache prices"
    (let [result (#'provider/lookup-cost "model-y"
                   {"model-y" {:input 1.00 :output 3.20 :cache-read-input 0.20}})]
      (is (= 0.20 (:cache-read-input result)) "explicit cache-read preserved")
      (is (= 1.25 (:cache-write-input result)) "cache-write derived when absent")))

  (testing "custom cache-read-ratio overrides default 0.10"
    (let [result (#'provider/lookup-cost "model-z"
                   {"model-z" [4.00 20.00]
                    :cache-read-ratio 0.25})]
      (is (= 1.00 (:cache-read-input result)) "4.00 * 0.25 = 1.00")))

  (testing "returns nil for unknown model"
    (is (nil? (#'provider/lookup-cost "nonexistent" {"other" [1.0 2.0]})))))

;; =============================================================================
;; strip-code-fences
;; =============================================================================

(deftest strip-code-fences-test
  (testing "strips backtick-lang fences"
    (is (= "(def x 1)" (provider/strip-code-fences "```clojure\n(def x 1)\n```"))))

  (testing "strips plain backtick fences"
    (is (= "content" (provider/strip-code-fences "```\ncontent\n```"))))

  (testing "passes through non-fenced content unchanged"
    (is (= "(def x 1)" (provider/strip-code-fences "(def x 1)"))))

  (testing "passes through empty string"
    (is (= "" (provider/strip-code-fences ""))))

  (testing "handles fences with trailing whitespace"
    (is (= "code" (provider/strip-code-fences "```\ncode\n```  ")))))

;; =============================================================================
;; cache-min-chars
;; =============================================================================

(deftest cache-min-chars-test
  (testing "opus-4-5 returns 16000"
    (is (= 16000 (#'provider/cache-min-chars "claude-opus-4-5-20250901"))))

  (testing "opus-4-6 returns 16000"
    (is (= 16000 (#'provider/cache-min-chars "claude-opus-4-6-20250301"))))

  (testing "haiku-4-5 returns 16000"
    (is (= 16000 (#'provider/cache-min-chars "claude-haiku-4-5-20251001"))))

  (testing "haiku-3 returns 8000"
    (is (= 8000 (#'provider/cache-min-chars "claude-haiku-3-20240307"))))

  (testing "haiku-3-5 returns 8000"
    (is (= 8000 (#'provider/cache-min-chars "claude-haiku-3-5-20241022"))))

  (testing "sonnet model returns 4000 default"
    (is (= 4000 (#'provider/cache-min-chars "claude-sonnet-4-20250514"))))

  (testing "unknown model returns 4000 default"
    (is (= 4000 (#'provider/cache-min-chars "some-random-model")))))

;; =============================================================================
;; TestProvider matching strategies
;; =============================================================================

(deftest test-provider-matching-test
  (testing "exact match in :responses map"
    (let [p (provider/test-provider {:responses {"hello" "world"}})]
      (is (= "world" (provider/call-llm p "hello")))))

  (testing "response-fn fallback when no exact match"
    (let [p (provider/test-provider {:response-fn (fn [prompt]
                                                    (when (str/includes? prompt "foo")
                                                      "bar"))})]
      (is (= "bar" (provider/call-llm p "foo baz")))))

  (testing "response-rules substring matching"
    (let [p (provider/test-provider {:response-rules [{:includes ["alpha" "beta"]
                                                       :response "matched"}]})]
      (is (= "matched" (provider/call-llm p "contains alpha and beta here")))))

  (testing "response-rules excludes blocks match"
    (let [p (provider/test-provider {:response-rules [{:includes ["alpha"]
                                                       :excludes ["gamma"]
                                                       :response "matched"}]})]
      (is (thrown-with-msg? Exception #"no response"
            (provider/call-llm p "alpha and gamma")))))

  (testing "latency simulation delays response"
    (let [p (provider/test-provider {:responses {"p" {:response "r" :latency 50}}})
          start (System/currentTimeMillis)
          result (provider/call-llm p "p")
          elapsed (- (System/currentTimeMillis) start)]
      (is (= "r" result))
      (is (>= elapsed 45) "should have slept ~50ms")))

  (testing "throws when no match found"
    (let [p (provider/test-provider {})]
      (is (thrown-with-msg? Exception #"no response"
            (provider/call-llm p "anything")))))

  (testing "resolution order: exact match wins over response-fn and rules"
    (let [p (provider/test-provider {:responses {"prompt" "exact"}
                                     :response-fn (constantly "fn-result")
                                     :response-rules [{:includes ["prompt"]
                                                       :response "rule-result"}]})]
      (is (= "exact" (provider/call-llm p "prompt"))))))

;; =============================================================================
;; resolve-provider edge cases
;; =============================================================================

(deftest resolve-provider-test
  (testing "passes through existing LLMProvider instance"
    (let [p (provider/ollama-provider)]
      (is (identical? p (provider/resolve-provider p nil)))))

  (testing "resolves inline :test type map"
    (let [p (provider/resolve-provider {:type :test :response "42)"} nil)]
      (is (instance? spell.provider.TestProvider p))
      (is (= "42)" (provider/call-llm p "any prompt")))))

  (testing "resolves :file key from map spec"
    (let [tmp (java.io.File/createTempFile "resolve-provider-" ".provider.edn")]
      (try
        (spit tmp (pr-str {:type :ollama :model "phi3"}))
        (let [p (provider/resolve-provider {:file (.getName tmp)} (.getParent tmp))]
          (is (instance? spell.provider.OllamaProvider p))
          (is (= "phi3" (:model p))))
        (finally
          (.delete tmp)))))

  (testing "throws on invalid spec"
    (is (thrown-with-msg? Exception #"Invalid provider spec"
          (provider/resolve-provider 42 nil)))))

;; =============================================================================
;; provider-edn-default-agent
;; =============================================================================

(deftest provider-edn-default-agent-test
  (testing "returns resolved path when :default-agent is present"
    (let [tmp (java.io.File/createTempFile "provider-da-" ".provider.edn")]
      (try
        (spit tmp (pr-str {:type :ollama :default-agent "agents/chat.agent.edn"}))
        (let [result (provider/provider-edn-default-agent (.getAbsolutePath tmp))]
          (is (= (str (.getParent tmp) "/agents/chat.agent.edn") result)))
        (finally
          (.delete tmp)))))

  (testing "returns nil when :default-agent is absent"
    (let [tmp (java.io.File/createTempFile "provider-noda-" ".provider.edn")]
      (try
        (spit tmp (pr-str {:type :ollama}))
        (is (nil? (provider/provider-edn-default-agent (.getAbsolutePath tmp))))
        (finally
          (.delete tmp))))))

;; =============================================================================
;; Fireworks chat template detection / resolution
;; =============================================================================

(deftest fireworks-chat-template-detection-test
  (testing "detect-chat-template returns :glm-4 for GLM models"
    (is (= :glm-4 (#'provider/detect-chat-template "accounts/fireworks/models/glm-5"))))

  (testing "detect-chat-template returns :deepseek-v3 for deepseek models"
    (is (= :deepseek-v3 (#'provider/detect-chat-template "accounts/fireworks/models/deepseek-v3p1"))))

  (testing "detect-chat-template returns :chatml as default"
    (is (= :chatml (#'provider/detect-chat-template "some-other-model"))))

  (testing "resolve-chat-template uses keyword to lookup"
    (let [result (#'provider/resolve-chat-template :glm-4 "anything")]
      (is (= (:glm-4 provider/fireworks-chat-templates) result))))

  (testing "resolve-chat-template passes through map directly"
    (let [custom {:custom "template"}
          result (#'provider/resolve-chat-template custom "anything")]
      (is (= custom result))))

  (testing "resolve-chat-template auto-detects when nil"
    (let [result (#'provider/resolve-chat-template nil "glm-5")]
      (is (= (:glm-4 provider/fireworks-chat-templates) result))))

  (testing "resolve-chat-template throws on unknown keyword"
    (is (thrown-with-msg? Exception #"Unknown Fireworks chat template"
          (#'provider/resolve-chat-template :nonexistent "x")))))

;; =============================================================================
;; Anthropic PF supports-prefill opus-4-6 exclusion
;; =============================================================================

(deftest anthropic-pf-supports-prefill-test
  (testing "opus-4-6 model returns false"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-opus-4-6-20250301"})]
      (is (false? (provider/supports-prefill p)))))

  (testing "opus-4-7 model returns false"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-opus-4-7-20250416"})]
      (is (false? (provider/supports-prefill p)))))

  (testing "sonnet model returns true"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-sonnet-4-20250514"})]
      (is (true? (provider/supports-prefill p)))))

  (testing "opus-4-5 model returns true"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-opus-4-5-20250901"})]
      (is (true? (provider/supports-prefill p))))))

(deftest anthropic-opus47-thinking-request-test
  (testing "tool-call path treats reasoning-effort as adaptive thinking on opus-4-7"
    (let [request (#'provider/anthropic-tc-request "test" "claude-opus-4-7-20250416"
                                                   "prompt" "system" nil false nil
                                                   "medium" nil 600)
          body (request-json-body request)]
      (is (= 32768 (:max_tokens body)))
      (is (= {:type "auto"} (:tool_choice body)))
      (is (= {:type "adaptive"} (:thinking body)))
      (is (= {:effort "medium"} (:output_config body)))
      (is (= 600 (request-timeout-seconds request)))))

  (testing "plain-text path uses adaptive thinking and drops assistant prefill on opus-4-7"
    (let [request (#'provider/anthropic-pf-request "test" "claude-opus-4-7-20250416"
                                                   "prompt" "system" "prefill" nil false nil
                                                   "high" nil 600)
          body (request-json-body request)]
      (is (= 32768 (:max_tokens body)))
      (is (= [{:role "user" :content "prompt"}] (:messages body)))
      (is (= {:type "adaptive"} (:thinking body)))
      (is (= {:effort "high"} (:output_config body)))
      (is (= 600 (request-timeout-seconds request))))))

(deftest make-http-client-connect-timeout-test
  (testing "applies connect timeout when requested"
    (let [client (#'provider/make-http-client {:connect-timeout-sec 17})]
      (is (= 17 (some-> client .connectTimeout (.orElse nil) .getSeconds)))))

  (testing "omits connect timeout when not requested"
    (let [client (#'provider/make-http-client)]
      (is (nil? (some-> client .connectTimeout (.orElse nil)))))))

(deftest await-http-response-test
  (testing "returns completed async response"
    (let [future (doto (CompletableFuture.) (.complete :ok))]
      (is (= :ok (#'provider/await-http-response future 1)))))

  (testing "times out and cancels unfinished responses"
    (let [future (CompletableFuture.)]
      (is (thrown-with-msg? java.net.http.HttpTimeoutException #"timed out"
            (#'provider/await-http-response future 0)))
      (is (.isCancelled future))))

  (testing "unwraps execution exceptions"
    (let [future (doto (CompletableFuture.)
                   (.completeExceptionally (ex-info "boom" {:status 500})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
            (#'provider/await-http-response future 1))))))

;; =============================================================================
;; call-with-retries exhaustion
;; =============================================================================

(deftest call-with-retries-throws-last-error-test
  (testing "throws the last error when retries exhausted"
    (let [call-count (atom 0)
          errors [(ex-info "error-1" {:status 500})
                  (ex-info "error-2" {:status 500})
                  (ex-info "error-3" {:status 500})]]
      (is (thrown-with-msg? Exception #"error-3"
            (provider/call-with-retries
              (fn [_]
                (let [n @call-count]
                  (swap! call-count inc)
                  (throw (nth errors n))))
              [0 0]))
          "should rethrow the last error when retries are exhausted")
      (is (= 3 @call-count) "should have attempted 1 initial + 2 retries"))))
