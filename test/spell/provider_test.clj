(ns spell.provider-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [spell.provider :as provider])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream PipedInputStream PipedOutputStream]
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

(defn- repeated-string
  [n s]
  (apply str (repeat n s)))

(defn- thrown-ex
  [f]
  (try
    (f)
    nil
    (catch Exception e
      e)))

;; =============================================================================
;; SSE body timeout reader
;; =============================================================================

(deftest read-sse-body-test
  (testing "successful SSE body is preserved"
    (let [body (str "event: message_start\n"
                    "data: {\"type\":\"message_start\"}\n\n"
                    "data: [DONE]\n\n")
          input (ByteArrayInputStream. (.getBytes body "UTF-8"))]
      (is (= body (#'provider/read-sse-body
                    input
                    {:provider :test
                     :sse-idle-timeout-sec 1
                     :sse-completion-timeout-sec 1})))))

  (testing "idle timeout fires before first event and is retryable"
    (let [input (PipedInputStream.)
          output (PipedOutputStream. input)]
      (try
        (let [ex (thrown-ex
                  #(-> input
                       (#'provider/read-sse-body
                         {:provider :test-provider
                          :sse-idle-timeout-sec 0.05
                          :sse-completion-timeout-sec 1})))]
          (is (= :sse-idle-timeout (:type (ex-data ex))))
          (is (= :test-provider (:provider (ex-data ex))))
          (is (true? (provider/retryable? ex))))
        (finally
          (.close output)))))

  (testing "idle timeout fires after a partial stream goes silent"
    (let [input (PipedInputStream.)
          output (PipedOutputStream. input)
          writer (future
                   (.write output (.getBytes "data: {\"partial\":true}\n\n" "UTF-8"))
                   (.flush output)
                   (Thread/sleep 1000))]
      (try
        (let [ex (thrown-ex
                  #(-> input
                       (#'provider/read-sse-body
                         {:provider :test-provider
                          :sse-idle-timeout-sec 0.05
                          :sse-completion-timeout-sec 1})))]
          (is (= :sse-idle-timeout (:type (ex-data ex))))
          (is (true? (provider/retryable? ex))))
        (finally
          (future-cancel writer)
          (.close output)))))

  (testing "completion timeout is a non-retryable hard cap while bytes keep arriving"
    (let [input (PipedInputStream.)
          output (PipedOutputStream. input)
          writer (future
                   (try
                     (loop []
                       (.write output (.getBytes "data: {\"ping\":true}\n\n" "UTF-8"))
                       (.flush output)
                       (Thread/sleep 10)
                       (recur))
                     (catch Exception _ nil)))]
      (try
        (let [ex (thrown-ex
                  #(-> input
                       (#'provider/read-sse-body
                         {:provider :test-provider
                          :sse-idle-timeout-sec 0.5
                          :sse-completion-timeout-sec 0.08})))]
          (is (= :sse-completion-timeout (:type (ex-data ex))))
          (is (false? (provider/retryable? ex))))
        (finally
          (future-cancel writer)
          (.close output)))))

  (testing "heartbeat comments do not reset the data-event idle timeout"
    (let [input (PipedInputStream.)
          output (PipedOutputStream. input)
          writer (future
                   (try
                     (loop []
                       (.write output (.getBytes ": ping\n\n" "UTF-8"))
                       (.flush output)
                       (Thread/sleep 10)
                       (recur))
                     (catch Exception _ nil)))]
      (try
        (let [ex (thrown-ex
                  #(-> input
                       (#'provider/read-sse-body
                         {:provider :test-provider
                          :sse-idle-timeout-sec 0.08
                          :sse-completion-timeout-sec 1})))]
          (is (= :sse-idle-timeout (:type (ex-data ex))))
          (is (true? (provider/retryable? ex))))
        (finally
          (future-cancel writer)
          (.close output)))))

  (testing "header wait timeout is classified as first-event idle timeout"
    (with-redefs [provider/send-http-request
                  (fn [& _]
                    (throw (java.net.http.HttpTimeoutException. "headers stalled")))]
      (let [ex (thrown-ex
                #(#'provider/send-sse-request
                   nil nil 600 0.05 1 :test-provider))]
        (is (= :sse-idle-timeout (:type (ex-data ex))))
        (is (= :test-provider (:provider (ex-data ex))))
        (is (true? (provider/retryable? ex)))))))

(deftest sse-timeout-config-test
  (testing "Anthropic constructors default to the current Sonnet model"
    (is (= "claude-sonnet-5-5"
           (:model (provider/anthropic-pf-provider {:api-key "test"}))))
    (is (= "claude-sonnet-5-5"
           (:model (provider/anthropic-tc-provider {:api-key "test"})))))

  (testing "streaming provider constructors install SSE timeout defaults"
    (let [anthropic-pf (provider/anthropic-pf-provider {:api-key "test"})
          anthropic-tc (provider/anthropic-tc-provider {:api-key "test"})
          fireworks (provider/fireworks-provider {:api-key "test"})
          fireworks-tc (provider/fireworks-tc-provider {:api-key "test"})]
      (doseq [p [anthropic-pf anthropic-tc fireworks fireworks-tc]]
        (is (= 100 (:sse-idle-timeout-sec p)))
        (is (= 1000 (:sse-completion-timeout-sec p))))))

  (testing "streaming provider constructors accept custom SSE timeouts"
    (let [opts {:api-key "test"
                :sse-idle-timeout-sec 7
                :sse-completion-timeout-sec 77}
          providers [(provider/anthropic-pf-provider opts)
                     (provider/anthropic-tc-provider opts)
                     (provider/fireworks-provider opts)
                     (provider/fireworks-tc-provider opts)]]
      (doseq [p providers]
        (is (= 7 (:sse-idle-timeout-sec p)))
        (is (= 77 (:sse-completion-timeout-sec p))))))

  (testing "plain-text siblings preserve SSE timeouts"
    (let [anthropic-leaf (provider/plain-text-provider
                           (provider/anthropic-tc-provider
                             {:api-key "test"
                              :sse-idle-timeout-sec 8
                              :sse-completion-timeout-sec 88}))
          fireworks-leaf (provider/plain-text-provider
                           (provider/fireworks-tc-provider
                             {:api-key "test"
                              :sse-idle-timeout-sec 9
                              :sse-completion-timeout-sec 99}))]
      (is (= 8 (:sse-idle-timeout-sec anthropic-leaf)))
      (is (= 88 (:sse-completion-timeout-sec anthropic-leaf)))
      (is (= 9 (:sse-idle-timeout-sec fireworks-leaf)))
      (is (= 99 (:sse-completion-timeout-sec fireworks-leaf)))))

  (testing "inline provider maps thread SSE timeout options"
    (with-redefs [provider/anthropic-pf-provider identity]
      (let [opts (#'provider/load-provider-from-map
                   {:type :anthropic-pf
                    :sse-idle-timeout-sec 12
                    :sse-completion-timeout-sec 120})]
        (is (= 12 (:sse-idle-timeout-sec opts)))
        (is (= 120 (:sse-completion-timeout-sec opts))))))

  (testing "provider EDN files thread SSE timeout options"
    (let [tmp (java.io.File/createTempFile "provider-sse-timeouts" ".edn")]
      (try
        (spit tmp (pr-str {:type :fireworks-tc
                           :sse-idle-timeout-sec 13
                           :sse-completion-timeout-sec 130}))
        (with-redefs [provider/fireworks-tc-provider identity]
          (let [opts (provider/load-provider (.getPath tmp))]
            (is (= 13 (:sse-idle-timeout-sec opts)))
            (is (= 130 (:sse-completion-timeout-sec opts)))))
        (finally
          (.delete tmp))))))

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
  (testing "opus-4-5 returns 4000"
    (is (= 4000 (#'provider/cache-min-chars "claude-opus-4-5-20250901"))))

  (testing "opus-4-6 returns 4000"
    (is (= 4000 (#'provider/cache-min-chars "claude-opus-4-6-20250301"))))

  (testing "opus-4-7 returns 4000"
    (is (= 4000 (#'provider/cache-min-chars "claude-opus-4-7-20250416"))))

  (testing "haiku-4-5 returns 4000"
    (is (= 4000 (#'provider/cache-min-chars "claude-haiku-4-5-20251001"))))

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
;; provider-edn-default-agent-profile
;; =============================================================================

(deftest provider-edn-default-agent-profile-test
  (testing "returns resolved path when :default-agent-profile is present"
    (let [tmp (java.io.File/createTempFile "provider-da-" ".provider.edn")]
      (try
        (spit tmp (pr-str {:type :ollama :default-agent-profile "agents/chat.agent.edn"}))
        (let [result (provider/provider-edn-default-agent-profile (.getAbsolutePath tmp))]
          (is (= (str (.getParent tmp) "/agents/chat.agent.edn") result)))
        (finally
          (.delete tmp)))))

  (testing "returns nil when :default-agent-profile is absent"
    (let [tmp (java.io.File/createTempFile "provider-noda-" ".provider.edn")]
      (try
        (spit tmp (pr-str {:type :ollama}))
        (is (nil? (provider/provider-edn-default-agent-profile (.getAbsolutePath tmp))))
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

(deftest fireworks-reasoning-effort-request-test
  (testing "emits Fireworks reasoning_effort on completions requests"
    (doseq [model ["accounts/fireworks/models/glm-5p1"
                   "accounts/fireworks/models/glm-5p2"]]
      (let [request (#'provider/fireworks-completions-request
                      "test" "https://api.fireworks.ai/inference/v1"
                      model
                      "prompt" "system" nil nil nil nil "high" 600)
            body (request-json-body request)]
        (is (= "high" (:reasoning_effort body)) model)
        (is (not (contains? body :thinking)) model))))

  (testing "positive integer budgets are emitted as JSON strings"
    (let [request (#'provider/fireworks-completions-request
                    "test" "https://api.fireworks.ai/inference/v1"
                    "accounts/fireworks/models/qwen3p6-plus"
                    "prompt" "system" nil nil nil nil "32000" 600)
          body (request-json-body request)]
      (is (= "32000" (:reasoning_effort body)))))

  (testing "DeepSeek V4 Pro is configured as a Fireworks reasoning model"
    (let [request (#'provider/fireworks-completions-request
                    "test" "https://api.fireworks.ai/inference/v1"
                    "accounts/fireworks/models/deepseek-v4-pro"
                    "prompt" "system" nil nil nil nil "high" 600)
          body (request-json-body request)]
      (is (= "high" (:reasoning_effort body)))))

  (testing "unsupported Fireworks models reject reasoning_effort"
    (is (thrown-with-msg? Exception #"only supported for configured thinking models"
          (#'provider/fireworks-completions-request
            "test" "https://api.fireworks.ai/inference/v1"
            "accounts/fireworks/models/kimi-k2p5"
            "prompt" "system" nil nil nil nil "high" 600))))

  (testing "thinking and reasoning_effort are mutually exclusive"
    (is (thrown-with-msg? Exception #"cannot include both thinking and reasoning_effort"
          (#'provider/fireworks-completions-request
            "test" "https://api.fireworks.ai/inference/v1"
            "accounts/fireworks/models/glm-5p1"
            "prompt" "system" nil nil nil {:type "enabled"} "high" 600)))))

;; =============================================================================
;; Anthropic PF supports-prefill opus-4-6 exclusion
;; =============================================================================

(deftest anthropic-pf-supports-prefill-test
  (testing "opus-4-6 model returns false"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-opus-4-6-20250301"})]
      (is (false? (provider/supports-prefill p {})))))

  (testing "opus-4-7 model returns false"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-opus-4-7-20250416"})]
      (is (false? (provider/supports-prefill p {})))))

  (testing "sonnet model returns true"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-sonnet-4-20250514"})]
      (is (true? (provider/supports-prefill p {})))))

  (testing "opus-4-5 model returns true"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-opus-4-5-20250901"})]
      (is (true? (provider/supports-prefill p {})))))

  (testing "Fable 5.1 returns false"
    (let [p (provider/anthropic-pf-provider {:api-key "test" :model "claude-fable-5-1"})]
      (is (false? (provider/supports-prefill p {}))))))

(deftest anthropic-adaptive-thinking-request-test
  (testing "tool-call path uses adaptive thinking on current model families"
    (doseq [model ["claude-opus-4-7" "claude-opus-4-8"
                   "claude-sonnet-5" "claude-fable-5" "claude-fable-5-1"]]
      (let [request (#'provider/anthropic-tc-request "test" model
                                                     "prompt" "system" nil false nil
                                                     "medium" nil 600)
            body (request-json-body request)]
        (is (= 32768 (:max_tokens body)) model)
        (is (= {:type "auto"} (:tool_choice body)) model)
        (is (= {:type "adaptive"} (:thinking body)) model)
        (is (= {:effort "medium"} (:output_config body)) model)
        (is (= 600 (request-timeout-seconds request)) model))))

  (testing "Fable 5.1 uses automatic tool choice without thinking"
    (let [request (#'provider/anthropic-tc-request "test" "claude-fable-5-1"
                                                   "prompt" "system" nil false nil
                                                   nil nil 600)
          body (request-json-body request)]
      (is (= {:type "auto"} (:tool_choice body)))
      (is (nil? (:thinking body)))
      (is (nil? (:output_config body)))))

  (testing "plain-text path uses adaptive thinking and drops assistant prefill on no-prefill families"
    (doseq [model ["claude-opus-4-7-20250416" "claude-fable-5-1"]]
      (let [request (#'provider/anthropic-pf-request "test" model
                                                     "prompt" "system" "prefill" nil false nil
                                                     "high" nil 600)
            body (request-json-body request)]
        (is (= 32768 (:max_tokens body)) model)
        (is (= [{:role "user" :content "prompt"}] (:messages body)) model)
        (is (= {:type "adaptive"} (:thinking body)) model)
        (is (= {:effort "high"} (:output_config body)) model)
        (is (= 600 (request-timeout-seconds request)) model)))))

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

(deftest anthropic-cache-prefix-request-test
  (let [shared-prefix (repeated-string 4100 "a")
        boundary-prefix (repeated-string 4000 "c")
        prompt-extends (str shared-prefix "fg")
        cache-prefix (str shared-prefix "xyz")
        short-prefix (repeated-string 3000 "b")]
    (testing "tool-call path splits on the longest shared prefix when prompt extends prior content"
      (let [request (#'provider/anthropic-tc-request "test" "claude-sonnet-4-20250514"
                                                     prompt-extends nil nil false nil
                                                     nil cache-prefix nil)
            body (request-json-body request)]
        (is (= [{:role "user"
                 :content [{:type "text" :text shared-prefix :cache_control {:type "ephemeral"}}
                           {:type "text" :text "fg" :cache_control {:type "ephemeral"}}]}]
               (:messages body)))))

    (testing "tool-call path preserves whitespace-only tails"
      (let [request (#'provider/anthropic-tc-request "test" "claude-sonnet-4-20250514"
                                                     (str shared-prefix "\n  ") nil nil false nil
                                                     nil cache-prefix nil)
            body (request-json-body request)]
        (is (= [{:role "user"
                 :content [{:type "text" :text shared-prefix :cache_control {:type "ephemeral"}}
                           {:type "text" :text "\n  " :cache_control {:type "ephemeral"}}]}]
               (:messages body)))))

    (testing "tool-call path caches at the exact 4000 character threshold"
      (let [request (#'provider/anthropic-tc-request "test" "claude-sonnet-4-20250514"
                                                     (str boundary-prefix "z") nil nil false nil
                                                     nil (str boundary-prefix "y") nil)
            body (request-json-body request)]
        (is (= [{:role "user"
                 :content [{:type "text" :text boundary-prefix :cache_control {:type "ephemeral"}}
                           {:type "text" :text "z" :cache_control {:type "ephemeral"}}]}]
               (:messages body)))))

    (testing "tool-call path keeps the prompt cached when edit markers shrink it to the shared prefix"
      (let [request (#'provider/anthropic-tc-request "test" "claude-sonnet-4-20250514"
                                                     shared-prefix nil nil false nil
                                                     nil cache-prefix nil)
            body (request-json-body request)]
        (is (= [{:role "user"
                 :content [{:type "text" :text shared-prefix :cache_control {:type "ephemeral"}}]}]
               (:messages body)))))

    (testing "plain-text path uses a cached block when the prompt exactly matches the shared prefix"
      (let [request (#'provider/anthropic-pf-request "test" "claude-sonnet-4-20250514"
                                                     shared-prefix nil nil nil false nil
                                                     nil shared-prefix nil)
            body (request-json-body request)]
        (is (= [{:role "user"
                 :content [{:type "text" :text shared-prefix :cache_control {:type "ephemeral"}}]}]
               (:messages body)))))

    (testing "common prefixes below the threshold stay a plain string"
      (let [request (#'provider/anthropic-tc-request "test" "claude-sonnet-4-20250514"
                                                     (str short-prefix "xyz") nil nil false nil
                                                     nil (str short-prefix "123") nil)
            body (request-json-body request)]
        (is (= [{:role "user" :content (str short-prefix "xyz")}]
               (:messages body)))))

    (testing "system prompt gets cache_control at the exact 4000 character threshold"
      (let [request (#'provider/anthropic-tc-request "test" "claude-sonnet-4-20250514"
                                                     "prompt" boundary-prefix nil false nil
                                                     nil nil nil)
            body (request-json-body request)]
        (is (= [{:type "text" :text boundary-prefix :cache_control {:type "ephemeral"}}]
               (:system body)))))))

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

(deftest codex-response-deadline-config
  (doseq [constructor [provider/codex-tc-provider provider/codex-msg-provider]]
    (is (= 300 (:request-timeout-sec (constructor {:api-key "test"}))))
    (is (= 37 (:request-timeout-sec (constructor {:api-key "test" :request-timeout-sec 37}))))
    (doseq [invalid [nil 0 -1 0.5 "300"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                           (constructor {:api-key "test" :request-timeout-sec invalid})))))
  (is (= 37 (:request-timeout-sec
              (provider/plain-text-provider
                (provider/codex-tc-provider {:api-key "test" :request-timeout-sec 37}))))))

(deftest codex-response-deadline-includes-body
  ;; Keep the exchange open until the client returns, rather than sleeping on
  ;; the server. The test's outer guard also bounds a regressed unbounded client.
  (doseq [constructor [provider/codex-tc-provider provider/codex-msg-provider]
          stage [:before-headers :partial-body]]
    (let [server (com.sun.net.httpserver.HttpServer/create
                   (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
          executor (java.util.concurrent.Executors/newCachedThreadPool)
          entered (promise)
          release (promise)
          requests (atom 0)]
      (.setExecutor server executor)
      (.createContext server "/responses"
        (reify com.sun.net.httpserver.HttpHandler
          (handle [_ exchange]
            (try
              (slurp (.getRequestBody exchange))
              (swap! requests inc)
              (when (= stage :partial-body)
                (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
                (.sendResponseHeaders exchange 200 0)
                (let [out (.getResponseBody exchange)]
                  (.write out (.getBytes "data: {\"type\":\"response.created\"}\n\n" "UTF-8"))
                  (.flush out)))
              (deliver entered true)
              @release
              (catch Exception _)
              (finally (.close exchange))))))
      (.start server)
      (try
        (let [p (constructor {:api-key "test" :request-timeout-sec 1
                              :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})
              task (future
                     (try (provider/call-with-retries
                            (fn [_] (provider/call-llm p "hello")) [0])
                          (catch Throwable e e)))]
          (try
            (is (= true (deref entered 3000 ::not-reached)) (str stage))
            (let [result (deref task 4000 ::hung)]
              (is (instance? java.net.http.HttpTimeoutException result) (str result))
              (when (instance? Throwable result)
                (is (not (provider/retryable? result))))
              (is (= 1 @requests) "A full-request timeout must not start another paid attempt"))
            (finally (future-cancel task))))
        (finally
          (deliver release true)
          (.stop server 0)
          (.shutdownNow executor))))))

(deftest codex-response-completes-before-deadline
  (doseq [[constructor output] [[provider/codex-tc-provider [{:type "custom_tool_call" :input "42"}]]
                                [provider/codex-msg-provider [{:type "message" :content [{:type "output_text" :text "42"}]}]]]]
    (let [server (com.sun.net.httpserver.HttpServer/create
                   (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
          body (.getBytes
                 (str "data: " (json/write-str {:type "response.completed"
                                                :response {:status "completed" :output output}}) "\n\n")
                 "UTF-8")]
      (.createContext server "/responses"
        (reify com.sun.net.httpserver.HttpHandler
          (handle [_ exchange]
            (try
              (slurp (.getRequestBody exchange))
              (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
              (.sendResponseHeaders exchange 200 (alength body))
              (.write (.getResponseBody exchange) body)
              (finally (.close exchange))))))
      (.start server)
      (try
        (is (= "42" (provider/call-llm
                       (constructor {:api-key "test" :request-timeout-sec 5
                                     :base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))})
                       "hello")))
        (finally (.stop server 0))))))

;; Current model contracts are tested offline at the HTTP request boundary.
(defn- claude-request-body [transport model thinking effort]
  (request-json-body
   (if (= transport :tc)
     (#'provider/anthropic-tc-request "test" model "prompt" "system" 16384 false
                                     thinking effort nil 600)
     (#'provider/anthropic-pf-request "test" model "prompt" "system" "prefill" 16384 false
                                     thinking effort nil 600))))

(deftest claude-55-request-contract
  (doseq [transport [:tc :pf]
          model ["claude-opus-5-5" "claude-sonnet-5-5" "claude-opus-5-5-20260921"]
          effort [nil "low" "medium" "high" "xhigh" "max"]
          thinking [nil 1024 {:type "adaptive"}]]
    (let [body (claude-request-body transport model thinking effort)]
      (is (= {:type "adaptive"} (:thinking body)))
      (is (= 16384 (:max_tokens body)))
      (is (= ["user"] (mapv :role (:messages body))))
      (is (= (when effort {:effort effort}) (:output_config body)))
      (is (not-any? #(contains? body %) [:temperature :top_p :top_k]))
      (when (= transport :tc)
        (is (= {:type "auto"} (:tool_choice body)))
        (is (= "spell_suffix" (get-in body [:tools 0 :name]))))))
  (doseq [transport [:tc :pf]
          model ["claude-opus-5-5" "claude-sonnet-5-5" "claude-fable-5-1"]
          effort ["none" "minimal" "bogus"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported reasoning effort"
          (claude-request-body transport model nil effort))))
  (doseq [transport [:tc :pf]
          model ["claude-opus-5-5" "claude-sonnet-5-5"]
          thinking [{:type "disabled"} {:type "enabled" :budget_tokens 1024}
                    {:type "adaptive" :budget_tokens 1024}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires adaptive thinking"
          (claude-request-body transport model thinking "medium")))))

(deftest sonnet-55-between-tools-contract
  (doseq [transport [:tc :pf] effort [nil "low" "medium" "high"]]
    (let [body (claude-request-body transport "claude-sonnet-5-5" {:type "between_tools"} effort)]
      (is (= {:type "between_tools"} (:thinking body)))
      (is (= (when effort {:effort effort}) (:output_config body)))
      (when (= transport :tc) (is (= {:type "auto"} (:tool_choice body))))))
  (doseq [transport [:tc :pf]
          [model thinking effort] [["claude-sonnet-5-5" {:type "between_tools"} "xhigh"]
                                    ["claude-sonnet-5-5" {:type "between_tools"} "max"]
                                    ["claude-sonnet-5-5" {:type "between_tools" :budget_tokens 1} "low"]
                                    ["claude-opus-5-5" {:type "between_tools"} "low"]
                                    ["claude-fable-5-1" {:type "between_tools"} "low"]
                                    ["claude-sonnet-5" {:type "between_tools"} "low"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"between_tools requires"
          (claude-request-body transport model thinking effort)))))

(deftest current-anthropic-defaults
  (doseq [constructor [provider/anthropic-tc-provider provider/anthropic-pf-provider]]
    (is (= "claude-sonnet-5-5" (:model (constructor {:api-key "offline"}))))))

(defn- gpt6-request [model effort force-tool?]
  (#'provider/openai-responses-request "test" "https://api.openai.com/v1" model
                                       "prompt" "system" 32768 effort nil nil force-tool? nil 600))

(deftest gpt6-effort-contract
  (doseq [model ["gpt-6-astra" "gpt-6.1-sol" "gpt-6-sol" "gpt-6-luna"]
          effort [nil "low" "medium" "high" "xhigh" "max"]]
    (let [body (request-json-body (gpt6-request model effort true))]
      (is (= (when effort {:effort effort}) (:reasoning body)))
      (is (= 32768 (:max_output_tokens body)))
      (is (= "spell_suffix" (get-in body [:tools 0 :name])))))
  (doseq [model ["gpt-6-sol" "gpt-6-luna"]]
    (is (= {:effort "none"} (:reasoning (request-json-body (gpt6-request model "none" true))))))
  (doseq [model ["gpt-6-astra" "gpt-6.1-sol" "gpt-6.1-sol-20260929"]
          effort ["none" "minimal"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported reasoning effort"
          (gpt6-request model effort true)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported reasoning effort"
          (#'provider/codex-tc-request-body model "prompt" "system" nil effort nil nil))))
  (doseq [model ["gpt-6-sol" "gpt-6-luna"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported reasoning effort"
          (gpt6-request model "minimal" true)))))

(deftest gpt6-effective-model-and-plain-text-routing
  (doseq [model ["gpt-6-astra" "gpt-6.1-sol" "gpt-6-sol" "gpt-6-luna"]
          plain? [false true]]
    (let [body (atom nil)
          p (provider/openai-provider {:api-key "offline" :model "gpt-5.6-sol"
                                       :max-tokens 32768})
          p (if plain? (provider/plain-text-provider p) p)]
      ;; Abort at serialization, before HTTP; :input proves Responses routing.
      (with-redefs [json/write-str (fn [request & _]
                                    (reset! body request)
                                    (throw (ex-info "captured" {::captured true})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"captured"
              (provider/call-llm p "prompt" {:model model :reasoning-effort "medium"}))))
      (is (= model (:model @body)))
      (is (contains? @body :input))
      (is (not (contains? @body :messages))))))

(deftest latest-model-pricing-rows
  (doseq [[model input output read write]
          [["gpt-6-astra" 10.0 50.0 1.0 12.5]
           ["gpt-6.1-sol" 2.0 10.0 0.1 2.5]
           ["gpt-6-sol" 2.0 10.0 0.2 2.5]
           ["gpt-6-luna" 0.1 0.5 0.01 0.125]
           ["claude-opus-5-5" 4.0 20.0 0.2 5.0]
           ["claude-sonnet-5-5" 2.0 10.0 0.2 2.5]
           ["claude-fable-5-1" 10.0 50.0 0.25 12.5]
           ["claude-sonnet-5" 3.0 15.0 0.3 3.75]]]
    (doseq [id [model (str model "-20261003")]]
      (let [cost (#'provider/lookup-cost id provider/default-costs)]
        (is (= {:input input :output output :cache-read-input read :cache-write-input write}
               (dissoc cost :long-context)) id)
        (is (= (str/starts-with? model "gpt-6") (contains? cost :long-context)) id)))))

(deftest request-local-long-context-pricing
  (doseq [model ["gpt-6-astra" "gpt-6.1-sol" "gpt-6-sol" "gpt-6-luna"]
          total [271999 272000 272001]]
    (let [{:keys [input output cache-read-input cache-write-input]}
          (#'provider/lookup-cost model provider/default-costs)
          usage {:uncached_input_tokens (- total 200000) :cached_input_tokens 100000
                 :cache_write_input_tokens 100000 :visible_output_tokens 200 :reasoning_output_tokens 300}
          input-cost (+ (* (- total 200000) input) (* 100000 cache-read-input) (* 100000 cache-write-input))
          expected (/ (+ (* (if (> total 272000) 2.0 1.0) input-cost)
                         (* (if (> total 272000) 1.5 1.0) 500 output)) 1000000.0)]
      (is (< (abs (- expected (#'provider/usage-cost model usage nil))) 1.0e-10))))
  (testing "frozen costs are request-local, never inferred from the accumulated context"
    (let [usage (atom {:by-model {}})]
      (binding [provider/*usage* usage]
        (provider/track-usage! "gpt-6.1-sol" {:uncached_input_tokens 200000})
        (provider/track-usage! "gpt-6.1-sol" {:uncached_input_tokens 200000}))
      (is (< (abs (- 0.8 (provider/current-cost usage))) 1.0e-10))
      (swap! usage assoc :cost-table {"gpt-6.1-sol" [900 900]})
      (is (< (abs (- 0.8 (provider/current-cost usage))) 1.0e-10))))
  (testing "legacy aggregate-only records cannot prove a long request"
    (is (< (abs (- 0.8 (provider/current-cost
                       (atom {:by-model {"gpt-6.1-sol" {:uncached_input_tokens 400000}}})))) 1.0e-10)))
  (testing "Anthropic does not get the GPT6 surcharge"
    (is (= 1.22 (#'provider/usage-cost "claude-opus-5-5"
                  {:uncached_input_tokens 300000 :visible_output_tokens 1000} nil))))
  (testing "budget enforcement uses the tiered frozen cost"
    (let [usage (atom {:by-model {}})]
      (binding [provider/*usage* usage provider/*budget* 1.0]
        (is (thrown? clojure.lang.ExceptionInfo
                     (provider/track-usage! "gpt-6.1-sol" {:uncached_input_tokens 300000}))))
      (is (= 1.2 (provider/current-cost usage))))))

(deftest long-context-cost-overrides
  (let [tier {:threshold 10 :input-multiplier 2 :output-multiplier 1.5}
        costs {"custom" {:input 1.0 :output 2.0 :long-context tier}}
        normalized (#'provider/lookup-cost "custom" costs)]
    (is (= tier (:long-context normalized)))
    (is (= 0.000026 (#'provider/usage-cost "custom" {:uncached_input_tokens 13} costs)))
    (is (nil? (:long-context (#'provider/lookup-cost "custom" {"custom" [1.0 2.0]})))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid long-context pricing tier"
        (#'provider/lookup-cost "custom" {"custom" {:input 1 :output 2 :long-context {:threshold -1}}}))))

(deftest responses-cache-write-accounting
  (doseq [[total reads writes ordinary] [[12000 0 12000 0]
                                         [15000 12000 3000 0]
                                         [20000 12000 3000 5000]]]
    (let [usage (#'provider/parse-openai-responses-usage
                  {:input_tokens total :input_tokens_details {:cached_tokens reads :cache_write_tokens writes}
                   :output_tokens 50 :output_tokens_details {:reasoning_tokens 20}})
          expected (/ (+ (* ordinary 2.0) (* reads 0.10) (* writes 2.50) (* 50 10.0)) 1000000.0)]
      (is (= ordinary (:uncached_input_tokens usage)))
      (is (= reads (:cached_input_tokens usage)))
      (is (= writes (:cache_write_input_tokens usage)))
      (is (= writes (:cache_creation_input_tokens usage)))
      (is (= 30 (:visible_output_tokens usage)))
      (is (= 20 (:reasoning_output_tokens usage)))
      (is (< (abs (- expected (#'provider/usage-cost "gpt-6.1-sol" usage nil))) 1.0e-10))))
  (is (= 0 (:cache_write_input_tokens (#'provider/parse-openai-responses-usage {:input_tokens 10})))))

(deftest responses-verbosity-schema
  (doseq [verbosity [nil "auto" "low" "medium" "high"]]
    (let [body (request-json-body
                 (#'provider/openai-responses-request "test" "https://api.openai.com/v1" "gpt-6.1-sol"
                  "prompt" "system" 32768 "medium" verbosity nil true "cache-key" 600))]
      (is (not (contains? body :verbosity)))
      (is (= (when (contains? #{"low" "medium" "high"} verbosity) {:verbosity verbosity}) (:text body)))
      (is (= {:effort "medium"} (:reasoning body)))
      (is (= 32768 (:max_output_tokens body)))
      (is (= "required" (:tool_choice body)))
      (is (= "spell_suffix" (get-in body [:tools 0 :name])))
      (is (= "cache-key" (:prompt_cache_key body))))))

(deftest unfrozen-usage-bucket-adds-request-local-cost
  (doseq [[new-input expected] [[100000 0.6] [300000 1.6]]]
    (let [usage (atom {:by-model {"gpt-6.1-sol" {:uncached_input_tokens 200000 :calls 1}}})]
      (binding [provider/*usage* usage provider/*budget* nil]
        (provider/track-usage! "gpt-6.1-sol" {:uncached_input_tokens new-input}))
      (is (< (abs (- expected (provider/current-cost usage))) 1.0e-10))
      (is (= (+ 200000 new-input) (get-in @usage [:by-model "gpt-6.1-sol" :uncached_input_tokens])))
      (is (= 2 (get-in @usage [:by-model "gpt-6.1-sol" :calls]))))))
