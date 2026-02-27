(ns spell.llm-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [spell.cli :as cli]
            [spell.runtime :as runtime]
            [spell.core :as spell]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.recovery]
            [spell.test-helpers :as th]
            [spell.io :as spell-io]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [spell.eval :as eval]
            [spell.prompt :as prompt]
            [spell.stdlib :as stdlib]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

(deftest llm-basic-test
  (testing "llm evaluates response and extracts return"
    ;; Prompt: "(do " -> Response: "(def return 42))"
    ;; Full completion: "(do (def return 42))"
    (let [{:keys [llm]} (th/make-test-llm {:response "(def return 42))"})]
      (is (= 42 (llm "(do "))))))

(deftest llm-with-computation-test
  (testing "llm can evaluate expressions in response"
    (let [{:keys [llm]} (th/make-test-llm {:response "(def return (+ 1 2 3)))"})]
      (is (= 6 (llm "(do "))))))

(deftest llm-nested-call-test
  (testing "llm can call llm recursively (llm is effect-only)"
    ;; llm is an effect-builtin — must go through eval's second pass
    (let [call-count (atom 0)
          responses ["'(cat \"hello \" (!llm-self \"(eval '(do \"))"
                     "\"world\"))"]]
      (let [{:keys [llm]} (th/make-test-llm
                            {:response-fn (fn [_]
                                            (let [r (nth responses @call-count)]
                                              (swap! call-count inc)
                                              r))})]
        (is (= "hello world" (llm "(eval (do ")))))))

(deftest spell-eval-with-llm-test
  (testing "spell-eval can evaluate programs containing llm calls (with effects)"
    ;; Create an LLM with provider, then use its eval pipeline
    (let [{:keys [llm]} (th/make-test-llm {:response "(def return \"from llm\"))"})
          ;; Use the llm function through the eval pipeline
          result (llm "(do ")]
      (is (= "from llm" result)))))

;; =============================================================================
;; File I/O task tests
;; =============================================================================

(deftest file-io-task-dummy-test
  (testing "file I/O task with dummy provider"
    (spit "test-greeting.txt" "Alice")
    (try
      (let [{:keys [llm]} (th/make-test-llm
                            {:response "(def thought \"read file\") (cat \"Hello, \" (:ok (io/slurp \"test-greeting.txt\")) \"!\"))"})]
        (let [result (llm "(eval '(do ")]
          (is (= "Hello, Alice!" result))))
      (finally
        (io/delete-file "test-greeting.txt")))))

;; =============================================================================
;; Token usage tracking tests
;; =============================================================================

(deftest track-usage-basic-test
  (testing "track-usage! accumulates into *usage* atom"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom]
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (is (= {:by-model {"claude-sonnet-4-20250514"
                           {:input_tokens 100 :output_tokens 50 :calls 1
                            :cache_creation_input_tokens 0 :cache_read_input_tokens 0}}}
               @usage-atom))))))

(deftest track-usage-accumulates-test
  (testing "track-usage! accumulates across multiple calls"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom]
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 200 :output_tokens 75})
        (let [stats (get-in @usage-atom [:by-model "claude-sonnet-4-20250514"])]
          (is (= 300 (:input_tokens stats)))
          (is (= 125 (:output_tokens stats)))
          (is (= 2 (:calls stats))))))))

(deftest track-usage-multi-model-test
  (testing "track-usage! tracks per-model"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom]
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (provider/track-usage! "claude-3-5-haiku-20241022"
                          {:input_tokens 200 :output_tokens 75})
        (is (= 2 (count (:by-model @usage-atom))))
        (is (= 100 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :input_tokens])))
        (is (= 200 (get-in @usage-atom [:by-model "claude-3-5-haiku-20241022" :input_tokens])))))))

(deftest track-usage-noop-when-unbound-test
  (testing "track-usage! is a no-op when *usage* is nil"
    (binding [provider/*usage* nil]
      ;; Should not throw
      (provider/track-usage! "model" {:input_tokens 100 :output_tokens 50}))))

(deftest usage-summary-test
  (testing "usage-summary computes totals and costs"
    (let [usage-atom (atom {:by-model {"claude-sonnet-4-20250514"
                                       {:input_tokens 1000000 :output_tokens 500000 :calls 3}}})]
      (let [{:keys [total by-model]} (provider/usage-summary usage-atom)]
        ;; Total tokens
        (is (= 1000000 (:input_tokens total)))
        (is (= 500000 (:output_tokens total)))
        (is (= 3 (:calls total)))
        ;; Cost: 1M * $3/MTok + 0.5M * $15/MTok = $3 + $7.5 = $10.50
        (is (< (Math/abs (- 10.5 (:cost total))) 0.001))
        ;; Per-model cost
        (is (< (Math/abs (- 10.5 (get-in by-model ["claude-sonnet-4-20250514" :cost]))) 0.001))))))

(deftest usage-summary-multi-model-test
  (testing "usage-summary with multiple models"
    (let [usage-atom (atom {:by-model {"claude-sonnet-4-20250514"
                                       {:input_tokens 1000000 :output_tokens 100000 :calls 2}
                                       "claude-3-5-haiku-20241022"
                                       {:input_tokens 2000000 :output_tokens 200000 :calls 5}}})]
      (let [{:keys [total]} (provider/usage-summary usage-atom)]
        (is (= 3000000 (:input_tokens total)))
        (is (= 300000 (:output_tokens total)))
        (is (= 7 (:calls total)))
        ;; Sonnet: 1M*3/1M + 0.1M*15/1M = 3.0 + 1.5 = 4.5
        ;; Haiku:  2M*0.8/1M + 0.2M*4/1M = 1.6 + 0.8 = 2.4
        ;; Total: 6.9
        (is (< (Math/abs (- 6.9 (:cost total))) 0.001))))))

;; =============================================================================
;; make-llm factory tests
;; =============================================================================

(deftest make-llm-test
  (testing "make-llm with custom tool via namespace (effect)"
    (let [ns-map {'tools {:docs {:my-tool "A test tool."}
                          :my-tool (fn [] "tool-result")}}
          {:keys [llm]} (th/make-test-llm {:response "(tools/my-tool)))"}
                          :namespaces ns-map)]
      (is (= "tool-result" (llm "(eval (do '")))))

  (testing "make-llm without namespaces has no tools"
    (let [{:keys [llm]} (th/make-test-llm {:response "\"no tools here\""}
                          :namespaces {})]
      (is (= "no tools here" (llm "(do ")))))

  (testing "make-llm with agent in namespace (effect)"
    (let [helper-fn (fn
                      ([prompt] "helper-result")
                      ([prompt _handle] "helper-result"))
          ns-map {'helpers {:docs {:helper "Helper agent"}
                            :helper helper-fn}}
          {:keys [llm]} (th/make-test-llm {:response "(helpers/helper \"do something\")))"}
                          :namespaces ns-map)]
      (is (= "helper-result" (llm "(eval (do '")))))

  (testing "!llm-self provides automatic self-recursion"
    ;; !llm-self is an effect-builtin: accessed via eval double-evaluation.
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [_]
                                          (let [n (swap! call-count inc)]
                                            (if (= n 1)
                                              "(eval (do '(cat \"outer-\" (!llm-self \"(do \"))))"
                                              "\"inner-result\"")))}
                          :namespaces {})]
      (is (= "outer-inner-result" (llm "(do "))))))

;; =============================================================================
;; Namespace tests
;; =============================================================================

(deftest namespace-qualified-test
  (testing "model can use qualified symbol (effect namespace)"
    (let [ns-map {'tools {:docs {:bash "run command"}
                          :bash (fn [_] {:exit 0 :out "ok" :err ""})}}
          {:keys [llm]} (th/make-test-llm {:response "(:out (tools/bash \"test\")))"}
                          :namespaces ns-map)]
      (is (= "ok" (llm "(eval (do '"))))))

(deftest namespace-describe-test
  (testing "describe-fn returns namespace docs (effect namespace)"
    (let [ns-map {'r {:docs {:a "first" :b "second"} :a identity}}
          {:keys [llm]} (th/make-test-llm {:response "(describe-fn r)))"}
                          :namespaces ns-map)]
      (is (= {:a "first" :b "second"} (llm "(eval (do '"))))))

(deftest describe-fallback-test
  (testing "describe prefers :docs :guide over raw :docs when both present"
    (let [ns-map {:docs {:guide "full guide text" :a "doc for a"} :a identity}]
      (is (= "full guide text" (stdlib/describe ns-map)))
      (is (= "doc for a" (stdlib/describe ns-map :a)))
      (is (= "full guide text" (stdlib/describe ns-map :guide)))))

  (testing "describe falls back to :docs map when no :guide entry"
    (let [ns-map {:docs {:a "doc for a"} :a identity}]
      (is (= {:a "doc for a"} (stdlib/describe ns-map)))))

  (testing "describe prefers :docs over top-level"
    (let [ns-map {:docs {:x "from docs"} :x "from top"}]
      (is (= "from docs" (stdlib/describe ns-map :x)))))

  (testing "describe returns nil for missing key"
    (let [ns-map {:docs {:a "doc"}}]
      (is (nil? (stdlib/describe ns-map :missing))))))

(deftest builtins-namespace-test
  (testing "builtins guide returns full reference string"
    (let [r (spell/spell-eval '(describe-fn builtins) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "BUILTINS"))))

  (testing "builtins category returns string"
    (let [r (spell/spell-eval '(describe-fn builtins :spell) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "spell-eval"))))

  (testing "builtins special-forms category lists all special forms"
    (let [r (spell/spell-eval '(describe-fn builtins :special-forms) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "quine")))))

(deftest namespace-guide-test
  (testing "io namespace has :guide accessible via describe"
    (let [guide (stdlib/describe spell-io/io-namespace :guide)]
      (is (string? guide))
      (is (str/includes? guide "IO"))))

  (testing "io describe :sh still returns doc (no regression)"
    (let [doc (stdlib/describe spell-io/io-namespace :sh)]
      (is (string? doc))
      (is (str/includes? doc "shell command"))))

  (testing "math namespace has :guide via spell-eval"
    (let [r (spell/spell-eval '(describe-fn math :guide) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "MATH"))))

  (testing "strings namespace has :guide via spell-eval"
    (let [r (spell/spell-eval '(describe-fn strings :guide) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "STRINGS"))))

  (testing "patterns namespace has :guide via spell-eval (effect builtin)"
    ;; Create a test llm so we have an eval builtin with effect namespaces
    (let [{:keys [llm]} (th/make-test-llm {:response "(describe-fn patterns :guide))"})]
      (let [result (llm "(eval '(do ")]
        (is (string? result))
        (is (str/includes? result "PATTERNS")))))

  (testing "agents namespace has :guide"
    (let [guide (stdlib/describe (deref (resolve 'spell.runtime/agents-namespace)) :guide)]
      (is (string? guide))
      (is (str/includes? guide "AGENTS"))))

  (testing "futures namespace has :guide"
    (let [guide (stdlib/describe (deref (resolve 'spell.stdlib/futures-namespace)) :guide)]
      (is (string? guide))
      (is (str/includes? guide "FUTURES"))))

  (testing "globals namespace has :guide"
    (let [guide (stdlib/describe (deref (resolve 'spell.globals/globals-namespace)) :guide)]
      (is (string? guide))
      (is (str/includes? guide "GLOBALS")))))

(deftest namespace-multiple-calls-test
  (testing "can use multiple items from same namespace (effect)"
    (let [ns-map {'tools {:docs {:add "add fn" :sub "sub fn"}
                          :add +
                          :sub -}}
          {:keys [llm]} (th/make-test-llm {:response "(tools/add (tools/sub 10 3) 5)))"}
                          :namespaces ns-map)]
      (is (= 12 (llm "(eval (do '"))))))

(deftest namespace-agent-test
  (testing "can call agent from namespace (effect)"
    (let [mock-agent (fn ([p] (str "result: " p))
                         ([p _] (str "result: " p)))
          ns-map {'helpers {:docs {:helper "helper agent"}
                            :helper mock-agent}}
          {:keys [llm]} (th/make-test-llm {:response "(helpers/helper \"test\")))"}
                          :namespaces ns-map)]
      (is (= "result: test" (llm "(eval (do '"))))))

;; =============================================================================
;; System prompt generation tests
;; =============================================================================

(deftest generate-system-prompt-test
  (testing "includes namespace item descriptions"
    (let [ns-map {'tools {:docs {:my-tool "Does things."}
                          :my-tool identity}}
          p (prompt/generate-system-prompt ns-map)]
      (is (str/includes? p "my-tool: Does things."))))

  (testing "includes namespace section header"
    (let [ns-map {'mytools {:docs {:a "tool a"} :a identity}}
          p (prompt/generate-system-prompt ns-map)]
      (is (str/includes? p "NAMESPACES"))
      (is (str/includes? p "## mytools"))))

  (testing "compose-system-prompt with base includes base text"
    (let [p (prompt/compose-system-prompt {:base "INTRODUCTION\nSpell is a Lisp."
                                        :namespaces {'io spell-io/io-namespace}})]
      (is (str/includes? p "INTRODUCTION"))
      (is (str/includes? p "NAMESPACES"))
      (is (str/includes? p "## io"))
      (is (str/includes? p "read-file"))
      (is (str/includes? p "sh"))))

  (testing "qualified symbol usage instructions included"
    (let [p (prompt/generate-system-prompt spell/all-namespaces)]
      (is (str/includes? p "io/sh"))))

  (testing "short-docs appear in system prompt for core namespaces"
    (let [core-ns {'strings stdlib/strings 'math stdlib/math}
          p (prompt/compose-system-prompt {:core-namespaces core-ns})]
      (is (str/includes? p "strings"))
      (is (str/includes? p "String manipulation and regex"))
      (is (str/includes? p "math"))
      (is (str/includes? p "Mathematical functions"))))

  (testing ":guide and :_ entries are filtered from per-function docs"
    (let [ns-map {'tools {:short-docs "Test tools."
                          :docs {:guide "TOOLS guide text"
                                 :_ "Meta entry"
                                 :my-tool "Does things."}
                          :my-tool identity}}
          p (prompt/generate-system-prompt ns-map)]
      (is (str/includes? p "my-tool: Does things."))
      (is (not (str/includes? p "guide: TOOLS guide text")))
      (is (not (str/includes? p "_: Meta entry"))))))


;; =============================================================================
;; Ollama provider tests
;; =============================================================================

(deftest ollama-provider-constructor-test
  (testing "default construction"
    (let [provider (provider/ollama-provider)]
      (is (instance? spell.provider.OllamaProvider provider))
      (is (= "http://localhost:11434" (:base-url provider)))
      (is (= "llama3.2" (:model provider)))))

  (testing "custom base-url and model"
    (let [provider (provider/ollama-provider {:base-url "http://myhost:9999"
                                         :model "mistral"})]
      (is (= "http://myhost:9999" (:base-url provider)))
      (is (= "mistral" (:model provider)))))

  (testing "strips trailing slash from base-url"
    (let [provider (provider/ollama-provider {:base-url "http://localhost:11434/"})]
      (is (= "http://localhost:11434" (:base-url provider))))))

(deftest provider-spec-model-resolution-test
  (testing "load-provider honors :model in .provider.edn"
    (let [tmp (java.io.File/createTempFile "provider-model-" ".provider.edn")]
      (try
        (spit tmp (pr-str {:type :ollama
                           :model "mistral:latest"}))
        (let [p (provider/load-provider (.getAbsolutePath tmp))]
          (is (= "mistral:latest" (:model p))))
        (finally
          (.delete tmp)))))

  (testing "resolve-provider honors :model in inline map"
    (let [p (provider/resolve-provider {:type :ollama
                                        :model "qwen2.5:32b"} nil)]
      (is (= "qwen2.5:32b" (:model p)))))

  (testing "load-provider supports chatgpt-codex type"
    (let [auth-file (java.io.File/createTempFile "provider-chatgpt-auth-" ".json")
          provider-file (java.io.File/createTempFile "provider-chatgpt-" ".provider.edn")]
      (try
        (spit auth-file (json/write-str {:tokens {:access_token "test-token"
                                                  :account_id "acc-1"}}))
        (spit provider-file (pr-str {:type :chatgpt-codex
                                     :auth-file (.getAbsolutePath auth-file)
                                     :model "gpt-5.3-codex"}))
        (let [p (provider/load-provider (.getAbsolutePath provider-file))]
          (is (instance? spell.provider.ChatGPTCodexProvider p))
          (is (= "gpt-5.3-codex" (:model p))))
        (finally
          (.delete auth-file)
          (.delete provider-file)))))

  (testing "load-provider supports chatgpt-codex-toolcall type"
    (let [auth-file (java.io.File/createTempFile "provider-chatgpt-toolcall-auth-" ".json")
          provider-file (java.io.File/createTempFile "provider-chatgpt-toolcall-" ".provider.edn")]
      (try
        (spit auth-file (json/write-str {:tokens {:access_token "test-token"
                                                  :account_id "acc-1"}}))
        (spit provider-file (pr-str {:type :chatgpt-codex-toolcall
                                     :auth-file (.getAbsolutePath auth-file)
                                     :model "gpt-5.3-codex"}))
        (let [p (provider/load-provider (.getAbsolutePath provider-file))]
          (is (instance? spell.provider.ChatGPTCodexToolcallProvider p))
          (is (= "gpt-5.3-codex" (:model p))))
        (finally
          (.delete auth-file)
          (.delete provider-file)))))

  (testing "load-provider supports anthropic-toolcall type"
    (let [provider-file (java.io.File/createTempFile "provider-anthropic-toolcall-" ".provider.edn")]
      (try
        (spit provider-file (pr-str {:type :anthropic-toolcall
                                     :model "claude-sonnet-4-5-20250929"}))
        (with-redefs [provider/anthropic-toolcall-provider (fn [opts]
                                                              {:provider :anthropic-toolcall
                                                               :opts opts})]
          (let [p (provider/load-provider (.getAbsolutePath provider-file))]
            (is (= :anthropic-toolcall (:provider p)))
            (is (= "claude-sonnet-4-5-20250929" (get-in p [:opts :model])))))
        (finally
          (.delete provider-file))))))

(deftest ollama-parse-response-test
  (testing "parses successful chat response"
    (let [response-body (json/write-str {:message {:role "assistant"
                                                    :content "(def return 42))"}
                                         :prompt_eval_count 150
                                         :eval_count 25})
          result (#'provider/parse-ollama-response response-body)]
      (is (= "(def return 42))" (:text result)))
      (is (= 150 (get-in result [:usage :input_tokens])))
      (is (= 25 (get-in result [:usage :output_tokens])))))

  (testing "throws on error response"
    (let [response-body (json/write-str {:error "model not found"})]
      (is (thrown-with-msg? Exception #"Ollama API error"
            (#'provider/parse-ollama-response response-body)))))

  (testing "handles missing token counts gracefully"
    (let [response-body (json/write-str {:message {:role "assistant"
                                                    :content "hello"}})
          result (#'provider/parse-ollama-response response-body)]
      (is (= "hello" (:text result)))
      (is (= 0 (get-in result [:usage :input_tokens])))
      (is (= 0 (get-in result [:usage :output_tokens]))))))

;; =============================================================================
;; OpenAI provider tests
;; =============================================================================

(deftest openai-provider-constructor-test
  (testing "constructs with explicit api-key"
    (let [provider (provider/openai-provider {:api-key "sk-test"})]
      (is (instance? spell.provider.OpenAIProvider provider))
      (is (= "https://api.openai.com/v1" (:base-url provider)))
      (is (= "gpt-4o" (:model provider)))))

  (testing "custom base-url and model"
    (let [provider (provider/openai-provider {:api-key "sk-test"
                                          :base-url "https://custom.api.com/v1"
                                          :model "gpt-4o-mini"})]
      (is (= "https://custom.api.com/v1" (:base-url provider)))
      (is (= "gpt-4o-mini" (:model provider)))))

  (testing "strips trailing slash from base-url"
    (let [provider (provider/openai-provider {:api-key "sk-test"
                                          :base-url "https://api.openai.com/v1/"})]
      (is (= "https://api.openai.com/v1" (:base-url provider))))))

(deftest openai-parse-response-test
  (testing "parses successful chat completion"
    (let [response-body (json/write-str {:choices [{:message {:content "(def return 42))"}}]
                                         :usage {:prompt_tokens 100
                                                 :completion_tokens 30}})
          result (#'provider/parse-openai-response response-body)]
      (is (= "(def return 42))" (:text result)))
      (is (= 100 (get-in result [:usage :input_tokens])))
      (is (= 30 (get-in result [:usage :output_tokens])))))

  (testing "throws on error response"
    (let [response-body (json/write-str {:error {:message "invalid api key"
                                                  :type "invalid_request_error"}})]
      (is (thrown-with-msg? Exception #"OpenAI API error"
            (#'provider/parse-openai-response response-body)))))

  (testing "handles missing usage gracefully"
    (let [response-body (json/write-str {:choices [{:message {:content "hello"}}]})
          result (#'provider/parse-openai-response response-body)]
      (is (= "hello" (:text result)))
      (is (= 0 (get-in result [:usage :input_tokens])))
      (is (= 0 (get-in result [:usage :output_tokens]))))))

(deftest openai-responses-parse-response-test
  (testing "parses standard output_text response"
    (let [response-body (json/write-str {:output_text "(def return 42))"
                                         :usage {:input_tokens 100
                                                 :output_tokens 30}})
          result (#'provider/parse-openai-responses-response response-body)]
      (is (= "(def return 42))" (:text result)))
      (is (= 100 (get-in result [:usage :input_tokens])))
      (is (= 30 (get-in result [:usage :output_tokens])))))

  (testing "falls back to custom_tool_call input when output_text is blank"
    (let [response-body (json/write-str {:output_text ""
                                         :output [{:type "custom_tool_call"
                                                   :name "spell_suffix"
                                                   :input "(def x 1)"}]
                                         :usage {:input_tokens 12
                                                 :output_tokens 7}})
          result (#'provider/parse-openai-responses-response response-body)]
      (is (= "(def x 1)" (:text result)))
      (is (= 12 (get-in result [:usage :input_tokens])))
      (is (= 7 (get-in result [:usage :output_tokens])))))

  (testing "falls back to message output_text content when output_text is absent"
    (let [response-body (json/write-str {:output [{:type "message"
                                                   :role "assistant"
                                                   :content [{:type "output_text" :text "OK"}]}]
                                         :usage {:input_tokens 3
                                                 :output_tokens 2}})
          result (#'provider/parse-openai-responses-response response-body)]
      (is (= "OK" (:text result)))
      (is (= 3 (get-in result [:usage :input_tokens])))
      (is (= 2 (get-in result [:usage :output_tokens])))))

  (testing "throws on error response"
    (let [response-body (json/write-str {:error {:message "invalid api key"
                                                  :type "invalid_request_error"}})]
      (is (thrown-with-msg? Exception #"OpenAI Responses API error"
            (#'provider/parse-openai-responses-response response-body))))))

(deftest anthropic-toolcall-provider-constructor-test
  (testing "constructs with explicit api-key"
    (let [p (provider/anthropic-toolcall-provider {:api-key "anthropic-key"
                                                    :model "claude-sonnet-4-5-20250929"})]
      (is (instance? spell.provider.AnthropicToolcallProvider p))
      (is (= "anthropic-key" (:api-key p)))
      (is (= "claude-sonnet-4-5-20250929" (:model p)))))

  (testing "uses default model when omitted"
    (let [p (provider/anthropic-toolcall-provider {:api-key "anthropic-key"})]
      (is (= "claude-sonnet-4-5-20250929" (:model p))))))

(deftest anthropic-toolcall-parse-test
  (testing "parses completed response with spell_suffix tool_use"
    (let [body (json/write-str {:content [{:type "tool_use"
                                           :name "spell_suffix"
                                           :input {:suffix "(def x 1)"}}]
                                :usage {:input_tokens 11
                                        :output_tokens 5
                                        :cache_creation_input_tokens 7
                                        :cache_read_input_tokens 3}})
          result (#'provider/parse-anthropic-toolcall-response body)]
      (is (= "(def x 1)" (:text result)))
      (is (= 11 (get-in result [:usage :input_tokens])))
      (is (= 5 (get-in result [:usage :output_tokens])))
      (is (= 7 (get-in result [:usage :cache_creation_input_tokens])))
      (is (= 3 (get-in result [:usage :cache_read_input_tokens])))))

  (testing "throws when tool_use is missing"
    (let [body (json/write-str {:content [{:type "text" :text "no tool call"}]
                                :usage {:input_tokens 1 :output_tokens 1}})]
      (is (thrown-with-msg? Exception #"missing spell_suffix tool_use"
            (#'provider/parse-anthropic-toolcall-response body)))))

  (testing "parses stream with input_json_delta"
    (let [sse (str "event: message_start\n"
                   "data: "
                   (json/write-str {:type "message_start"
                                    :message {:usage {:input_tokens 8
                                                      :cache_creation_input_tokens 2
                                                      :cache_read_input_tokens 1}}})
                   "\n\n"
                   "event: content_block_start\n"
                   "data: "
                   (json/write-str {:type "content_block_start"
                                    :index 0
                                    :content_block {:type "tool_use"
                                                    :name "spell_suffix"
                                                    :input {}}})
                   "\n\n"
                   "event: content_block_delta\n"
                   "data: "
                   (json/write-str {:type "content_block_delta"
                                    :index 0
                                    :delta {:type "input_json_delta"
                                            :partial_json "{\"suffix\":\"(def y 2)\"}"}})
                   "\n\n"
                   "event: message_delta\n"
                   "data: "
                   (json/write-str {:type "message_delta"
                                    :usage {:output_tokens 4}})
                   "\n\n")
          result (#'provider/parse-anthropic-toolcall-stream sse)]
      (is (= "(def y 2)" (:text result)))
      (is (= 8 (get-in result [:usage :input_tokens])))
      (is (= 4 (get-in result [:usage :output_tokens])))
      (is (= 2 (get-in result [:usage :cache_creation_input_tokens])))
      (is (= 1 (get-in result [:usage :cache_read_input_tokens])))))

  (testing "stream throws when spell_suffix tool_use is missing"
    (let [sse (str "event: message_start\n"
                   "data: "
                   (json/write-str {:type "message_start"
                                    :message {:usage {:input_tokens 1}}})
                   "\n\n"
                   "event: message_delta\n"
                   "data: "
                   (json/write-str {:type "message_delta"
                                    :usage {:output_tokens 1}})
                   "\n\n")]
      (is (thrown-with-msg? Exception #"missing spell_suffix tool_use"
            (#'provider/parse-anthropic-toolcall-stream sse))))))

(deftest chatgpt-codex-provider-constructor-test
  (testing "constructs with explicit token override"
    (let [p (provider/chatgpt-codex-provider {:api-key "chatgpt-token"
                                              :account-id "acc_123"})]
      (is (instance? spell.provider.ChatGPTCodexProvider p))
      (is (= "chatgpt-token" (:api-key p)))
      (is (= "acc_123" (:account-id p)))
      (is (= "https://chatgpt.com/backend-api/codex" (:base-url p)))
      (is (= "gpt-5.3-codex" (:model p)))))

  (testing "loads token and account id from auth file"
    (let [tmp (java.io.File/createTempFile "chatgpt-auth-" ".json")]
      (try
        (spit tmp (json/write-str {:tokens {:access_token "from-file-token"
                                            :account_id "from-file-account"}}))
        (let [p (provider/chatgpt-codex-provider {:auth-file (.getAbsolutePath tmp)})]
          (is (= "from-file-token" (:api-key p)))
          (is (= "from-file-account" (:account-id p))))
        (finally
          (.delete tmp)))))

  (testing "strips trailing slash from base-url"
    (let [p (provider/chatgpt-codex-provider {:api-key "chatgpt-token"
                                              :base-url "https://chatgpt.com/backend-api/codex/"})]
      (is (= "https://chatgpt.com/backend-api/codex" (:base-url p))))))

(deftest chatgpt-codex-toolcall-provider-constructor-test
  (testing "constructs with explicit token override"
    (let [p (provider/chatgpt-codex-toolcall-provider {:api-key "chatgpt-token"
                                                       :account-id "acc_123"})]
      (is (instance? spell.provider.ChatGPTCodexToolcallProvider p))
      (is (= "chatgpt-token" (:api-key p)))
      (is (= "acc_123" (:account-id p)))
      (is (= "https://chatgpt.com/backend-api/codex" (:base-url p)))
      (is (= "gpt-5.3-codex" (:model p)))))

  (testing "loads token and account id from auth file"
    (let [tmp (java.io.File/createTempFile "chatgpt-toolcall-auth-" ".json")]
      (try
        (spit tmp (json/write-str {:tokens {:access_token "from-file-token"
                                            :account_id "from-file-account"}}))
        (let [p (provider/chatgpt-codex-toolcall-provider {:auth-file (.getAbsolutePath tmp)})]
          (is (= "from-file-token" (:api-key p)))
          (is (= "from-file-account" (:account-id p))))
        (finally
          (.delete tmp)))))

  (testing "strips trailing slash from base-url"
    (let [p (provider/chatgpt-codex-toolcall-provider {:api-key "chatgpt-token"
                                                       :base-url "https://chatgpt.com/backend-api/codex/"})]
      (is (= "https://chatgpt.com/backend-api/codex" (:base-url p))))))

(deftest chatgpt-codex-stream-parse-test
  (testing "parses response.completed with assistant message output"
    (let [sse (str "event: response.completed\n"
                   "data: "
                   (json/write-str {:type "response.completed"
                                    :response {:output [{:type "message"
                                                         :role "assistant"
                                                         :content [{:type "output_text" :text "OK"}]}]
                                               :usage {:input_tokens 10
                                                       :output_tokens 4}}})
                   "\n\n")
          result (#'provider/parse-chatgpt-codex-stream sse)]
      (is (= "OK" (:text result)))
      (is (= 10 (get-in result [:usage :input_tokens])))
      (is (= 4 (get-in result [:usage :output_tokens])))))

  (testing "parses custom_tool_call grammar output"
    (let [sse (str "event: response.completed\n"
                   "data: "
                   (json/write-str {:type "response.completed"
                                    :response {:output [{:type "custom_tool_call"
                                                         :name "spell_suffix"
                                                         :input "(def x 1)"}]
                                               :usage {:input_tokens 9
                                                       :output_tokens 3}}})
                   "\n\n")
          result (#'provider/parse-chatgpt-codex-stream sse)]
      (is (= "(def x 1)" (:text result)))
      (is (= 9 (get-in result [:usage :input_tokens])))
      (is (= 3 (get-in result [:usage :output_tokens])))))

  (testing "throws on response.failed"
    (let [sse (str "event: response.failed\n"
                   "data: "
                   (json/write-str {:type "response.failed"
                                    :response {:error {:message "bad auth"}}})
                   "\n\n")]
      (is (thrown-with-msg? Exception #"ChatGPT Codex Responses API error"
            (#'provider/parse-chatgpt-codex-stream sse))))))

(deftest chatgpt-codex-toolcall-stream-parse-test
  (testing "parses custom_tool_call output"
    (let [sse (str "event: response.completed\n"
                   "data: "
                   (json/write-str {:type "response.completed"
                                    :response {:output [{:type "custom_tool_call"
                                                         :name "spell_suffix"
                                                         :input "(def x 1)"}]
                                               :usage {:input_tokens 9
                                                       :output_tokens 3}}})
                   "\n\n")
          result (#'provider/parse-chatgpt-codex-toolcall-stream sse)]
      (is (= "(def x 1)" (:text result)))
      (is (= 9 (get-in result [:usage :input_tokens])))
      (is (= 3 (get-in result [:usage :output_tokens])))))

  (testing "ignores assistant message output when no custom_tool_call is present"
    (let [sse (str "event: response.completed\n"
                   "data: "
                   (json/write-str {:type "response.completed"
                                    :response {:output [{:type "message"
                                                         :role "assistant"
                                                         :content [{:type "output_text" :text "OK"}]}]
                                               :usage {:input_tokens 10
                                                       :output_tokens 4}}})
                   "\n\n")
          result (#'provider/parse-chatgpt-codex-toolcall-stream sse)]
      (is (= "" (:text result)))
      (is (= 10 (get-in result [:usage :input_tokens])))
      (is (= 4 (get-in result [:usage :output_tokens])))))

  (testing "throws on response.failed"
    (let [sse (str "event: response.failed\n"
                   "data: "
                   (json/write-str {:type "response.failed"
                                    :response {:error {:message "bad auth"}}})
                   "\n\n")]
      (is (thrown-with-msg? Exception #"ChatGPT Codex Responses API error"
            (#'provider/parse-chatgpt-codex-toolcall-stream sse))))))

;; =============================================================================
;; CLI parse-model-spec tests
;; =============================================================================

(deftest parse-model-spec-test
  (testing "plain model name (anthropic default)"
    (is (= {:provider nil :model "haiku"} (cli/parse-model-spec "haiku")))
    (is (= {:provider nil :model "sonnet"} (cli/parse-model-spec "sonnet")))
    (is (= {:provider nil :model "claude-sonnet-4-20250514"}
           (cli/parse-model-spec "claude-sonnet-4-20250514"))))

  (testing "ollama provider prefix"
    (is (= {:provider "ollama" :model "llama3.2"}
           (cli/parse-model-spec "ollama:llama3.2")))
    (is (= {:provider "ollama" :model "smollm2:135m"}
           (cli/parse-model-spec "ollama:smollm2:135m"))))

  (testing "chatgpt provider prefix"
    (is (= {:provider "chatgpt" :model "gpt-4o"}
           (cli/parse-model-spec "chatgpt:gpt-4o")))
    (is (= {:provider "chatgpt" :model "gpt-4o-mini"}
           (cli/parse-model-spec "chatgpt:gpt-4o-mini"))))

  (testing "codex provider prefix"
    (is (= {:provider "codex" :model "gpt-5.3-codex"}
           (cli/parse-model-spec "codex:gpt-5.3-codex"))))

  (testing "openai provider prefix"
    (is (= {:provider "openai" :model "gpt-4o"}
           (cli/parse-model-spec "openai:gpt-4o"))))

  (testing "anthropic-toolcall provider prefix"
    (is (= {:provider "anthropic-toolcall" :model "claude-sonnet-4-5-20250929"}
           (cli/parse-model-spec "anthropic-toolcall:claude-sonnet-4-5-20250929"))))

  (testing "unknown provider prefix throws"
    (is (thrown-with-msg? Exception #"Unknown provider prefix"
          (cli/parse-model-spec "custom:some-model")))))

;; =============================================================================
;; Budget tests
;; =============================================================================

(deftest budget-allows-under-limit-test
  (testing "no exception when cost is under budget"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* 1.00]
        ;; sonnet: 100 in * $3/MTok + 50 out * $15/MTok = $0.00105 — well under $1
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (is (= 1 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :calls])))))))

(deftest budget-throws-when-exceeded-test
  (testing "throws ex-info with :budget-exceeded when cost exceeds budget"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* 0.01]
        ;; sonnet: 1M in * $3/MTok + 1M out * $15/MTok = $18 — way over $0.01
        (let [ex (try
                   (provider/track-usage! "claude-sonnet-4-20250514"
                                     {:input_tokens 1000000 :output_tokens 1000000})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (is (= :budget-exceeded (:type (ex-data ex))))
          (is (number? (:cost (ex-data ex))))
          (is (= 0.01 (:budget (ex-data ex)))))))))

(deftest budget-nil-means-unlimited-test
  (testing "no exception when *budget* is nil"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        ;; Large usage, but no budget set
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 10000000 :output_tokens 10000000})
        (is (= 1 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :calls])))))))

(deftest budget-unknown-model-skips-check-test
  (testing "models without known pricing don't trigger budget check"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* 0.001]
        ;; Unknown model — current-cost returns nil, so no budget check
        (provider/track-usage! "unknown-model-xyz"
                          {:input_tokens 99999999 :output_tokens 99999999})
        (is (= 1 (get-in @usage-atom [:by-model "unknown-model-xyz" :calls])))))))

;; =============================================================================
;; Error recovery tests
;; =============================================================================

(deftest make-llm-with-recovery-test
  (testing "custom recovery function is called on error"
    (let [recovery-called (atom false)
          recovery-fn (fn [result]
                        (reset! recovery-called true)
                        ;; Return a fixed expression
                        '(def return 42))
          {:keys [llm]} (th/make-test-llm
                          ;; Response has an undefined symbol that will cause an error
                          {:response "undefined-symbol)"}
                          :namespaces {} :recover recovery-fn)]
      (let [result (llm "(do ")]
        (is @recovery-called)
        (is (= 42 result)))))

  (testing "quine-extension recovery re-enters via extend"
    ;; Program is a quine with an error. Quine-extension recovery appends
    ;; a new arg with error info + (!extend completion). The second LLM call
    ;; (via extend) provides the fix.
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [_]
                                          (let [n (swap! call-count inc)]
                                            (if (= n 1)
                                              "undefined-symbol) '(!extend completion))"  ; first call fails
                                              "(def fix 42))")))
                           :prefill? true}                       ; recovery extend returns fix
                          :namespaces {})]
      ;; Use quine prefix so recovery can append
      (let [result (llm "(quine completion (eval (do ")]
        (is (= 2 @call-count))  ; original + recovery extend
        (is (= 42 result)))))

  (testing "no recovery when explicitly disabled"
    (let [{:keys [llm]} (th/make-test-llm {:response "undefined-symbol)"}
                          :namespaces {} :recover false)]
      (is (thrown? Exception (llm "(do ")))))

  (testing "non-quine program propagates error (no quine-extension)"
    ;; Plain (do ...) program can't use quine-extension recovery
    (let [{:keys [llm]} (th/make-test-llm {:response "undefined-symbol)"}
                          :namespaces {} :recover true)]
      (is (thrown? Exception (llm "(do "))))))

(deftest reader-error-recovery-test
  (testing "reader error recovery via fresh quine — LLM retries successfully"
    ;; Response #1 has \invalidchar which is an unsupported character literal.
    ;; Reader recovery embeds the raw text in a recovery quine and extends.
    ;; Response #2 (via extend) provides a valid fix.
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [_]
                                          (let [n (swap! call-count inc)]
                                            (if (= n 1)
                                              "\\invalidchar)"           ; reader error
                                              "42)")))
                           :prefill? true}                  ; recovery succeeds
                          :namespaces {})]
      (let [result (llm "(quine completion (eval (do ")]
        (is (= 2 @call-count))
        (is (= 42 result)))))

  (testing "reader error recovery disabled — throws immediately"
    (let [{:keys [llm]} (th/make-test-llm {:response "\\invalidchar)"}
                          :namespaces {} :recover false)]
      (is (thrown? Exception (llm "(quine completion (eval (do ")))))

  (testing "reader error recovery passes raw text in _error"
    ;; Verify the LLM sees the broken raw text in the recovery prompt.
    ;; The second call's prompt should contain the original raw text.
    ;; Uses prefill? false so the recovery prefix is the prompt arg (not in :prefix opt).
    (let [prompts (atom [])
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [prompt]
                                          (swap! prompts conj prompt)
                                          (let [n (count @prompts)]
                                            (if (= n 1)
                                              "\\invalidchar)"
                                              "42)")))}
                          :namespaces {} :prefill? false)]
      (llm "(quine completion (eval (do ")
      ;; The recovery prompt (second call) should mention the reader error
      (let [recovery-prompt (second @prompts)]
        (is (str/includes? recovery-prompt "Reader error"))
        (is (str/includes? recovery-prompt "\\invalidchar")))))

  (testing "reader error recovery depth limit stops runaway loops"
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [_]
                                          (swap! call-count inc)
                                          "\\invalidchar)")}
                          :namespaces {})]
      (is (thrown-with-msg? Exception #"Reader error recovery limit exceeded"
                            (llm "(quine completion (eval (do ")))
      ;; Initial call + 2 recovery retries
      (is (= 3 @call-count)))))

(deftest namespace-recovery-invoke-fn-wrapping-test
  (testing "ns-recover handles 'Function call failed: Unbound symbol: X' from invoke-fn"
    ;; When an unbound symbol occurs inside a function passed to map/reduce/filter,
    ;; invoke-fn wraps the error: "Function call failed: Unbound symbol: X".
    ;; ns-recover must unwrap this to find and fix the symbol.
    (let [math-ns {:floor (fn [x] (long (Math/floor (double x))))
                   :long long}
          {:keys [llm]} (th/make-test-llm
                          {:response "(reduce + 0 (map (fn [x] (floor (/ x 2.0))) (list 10 20 30))))"
                           :prefill? true}
                          :namespaces {'math math-ns} :recover true)]
      (let [result (llm "(do ")]
        ;; ns-recover should fix floor -> math/floor
        (is (= 30 result)))))

  (testing "ns-recover fixes bare symbol to core namespace qualified form"
    ;; sqrt is in core namespace math/ but not in core-builtins
    ;; ns-recover should fix bare sqrt -> math/sqrt
    (let [{:keys [llm]} (th/make-test-llm
                          {:response "(map (fn [x] (sqrt x)) (list 4.0 9.0)))"
                           :prefill? true}
                          :namespaces {} :recover true)]
      (let [result (llm "(do ")]
        (is (= [2.0 3.0] result))))))

(deftest clean-error-message-test
  (testing "strips 'Function call failed: ' prefix"
    (is (= "Unbound symbol: foo"
           (spell.recovery/clean-error-message "Function call failed: Unbound symbol: foo"))))
  (testing "passes through other messages unchanged"
    (is (= "Unbound symbol: foo"
           (spell.recovery/clean-error-message "Unbound symbol: foo")))))

(deftest effect-phase-recovery-gating-test
  (testing "body error triggers recovery, effects available in re-eval"
    ;; Program: body has bare `floor`, trailing expression uses effect via io/ namespace.
    ;; Namespace recovery fixes floor -> math/floor in body.
    ;; After fix, the trailing expression should execute with effects available.
    (let [effect-called (atom false)
          math-ns {:floor (fn [x] (long (Math/floor (double x))))}
          io-ns {:do-effect (fn [] (reset! effect-called true) "effect-result")}
          {:keys [llm]} (th/make-test-llm
                          {:response "(def x (floor 3.7)) '(io/do-effect)))"}
                          :namespaces {'math math-ns 'io io-ns} :recover true)]
      (let [result (llm "(eval (do ")]
        (is (= "effect-result" result))
        (is @effect-called))))

  (testing "no double-execution of side effects on recovery"
    ;; Body has a fixable error. An effect counter in io/ tracks execution.
    ;; After recovery, the effect should run exactly once.
    (let [effect-count (atom 0)
          math-ns {:floor (fn [x] (long (Math/floor (double x))))}
          io-ns {:count-effect (fn [] (swap! effect-count inc))}
          {:keys [llm]} (th/make-test-llm
                          {:response "(def x (floor 3.7)) '(io/count-effect)))"}
                          :namespaces {'math math-ns 'io io-ns} :recover true)]
      (llm "(eval (do ")
      (is (= 1 @effect-count)))))

;; =============================================================================
;; Pattern tests
;; =============================================================================

(deftest check-result-ok-test
  (testing "check-result returns {:ok answer} when leaf-llm says OK"
    ;; First call: main LLM returns check-result call; second call: leaf-llm returns "OK"
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [_]
                                          (let [n (swap! call-count inc)]
                                            (if (= n 1)
                                              "(patterns/check-result \"What is 2+2?\" 4))"
                                              "OK")))})]
      (is (= {:ok 4} (llm "(eval '(do "))))))

(deftest check-result-wrong-test
  (testing "check-result returns {:wrong msg} when leaf-llm says WRONG"
    ;; First call: main LLM returns check-result call; second call: leaf-llm returns "WRONG: ..."
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [_]
                                          (let [n (swap! call-count inc)]
                                            (if (= n 1)
                                              "(patterns/check-result \"Capital of France?\" \"London\"))"
                                              "WRONG: London is not the capital of France")))})]
      (is (= {:wrong "London is not the capital of France"}
             (llm "(eval '(do "))))))

;; =============================================================================
;; API retry logic (#64)
;; =============================================================================

(deftest llm-call-retries-transient-errors
  (testing "instant retry on 500 error succeeds on second attempt"
    (let [call-count (atom 0)
          prov (reify provider/LLMProvider
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (if (= 1 @call-count)
                     (throw (ex-info "Server error" {:status 500}))
                     "success")))]
      (binding [provider/*retries* [0]]
        (is (= "success" (provider/call-with-retries
                           #(provider/call-llm prov "test" {})
                           [0])))
        (is (= 2 @call-count)))))

  (testing "non-retryable error throws immediately"
    (let [call-count (atom 0)
          prov (reify provider/LLMProvider
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (throw (ex-info "Bad request" {:status 400}))))]
      (is (thrown-with-msg? Exception #"Bad request"
                            (provider/call-with-retries
                              #(provider/call-llm prov "test" {})
                              [0 0])))
      (is (= 1 @call-count))))

  (testing "exhausts all retries then throws"
    (let [call-count (atom 0)
          prov (reify provider/LLMProvider
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (throw (ex-info "Rate limited" {:status 429}))))]
      (is (thrown-with-msg? Exception #"Rate limited"
                            (provider/call-with-retries
                              #(provider/call-llm prov "test" {})
                              [0 0])))
      ;; 1 initial + 2 retries = 3 calls
      (is (= 3 @call-count))))

  (testing "no retries when retries-seq is nil"
    (let [call-count (atom 0)
          prov (reify provider/LLMProvider
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (throw (ex-info "Server error" {:status 500}))))]
      (is (thrown-with-msg? Exception #"Server error"
                            (provider/call-with-retries
                              #(provider/call-llm prov "test" {})
                              nil)))
      (is (= 1 @call-count)))))

;; =============================================================================
;; Kimi provider (#46)
;; =============================================================================

(deftest kimi-provider-construction
  (testing "kimi-provider requires API key"
    (is (thrown-with-msg? Exception #"MOONSHOT_API_KEY"
                          (provider/kimi-provider {:api-key nil}))))

  (testing "kimi-provider defaults"
    (let [p (provider/kimi-provider {:api-key "test-key"})]
      (is (= "kimi-k2.5" (:model p)))
      (is (= "https://api.moonshot.ai/v1" (:base-url p)))
      (is (= "test-key" (:api-key p)))))

  (testing "kimi-provider custom opts"
    (let [p (provider/kimi-provider {:api-key "k"
                                      :base-url "https://api.moonshot.cn/v1"
                                      :model "kimi-k2-thinking"
                                      :max-tokens 4096})]
      (is (= "kimi-k2-thinking" (:model p)))
      (is (= "https://api.moonshot.cn/v1" (:base-url p)))
      (is (= 4096 (:max-tokens p)))))

  (testing "kimi-provider strips trailing slash from base-url"
    (let [p (provider/kimi-provider {:api-key "k" :base-url "https://api.moonshot.ai/v1/"})]
      (is (= "https://api.moonshot.ai/v1" (:base-url p)))))

  (testing "kimi model costs are recognized"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        (provider/track-usage! "kimi-k2.5" {:input_tokens 1000000 :output_tokens 1000000})
        (let [cost (provider/current-cost usage-atom)]
          ;; kimi-k2.5: $0.60/M in + $3.00/M out = $3.60
          (is (some? cost))
          (is (< 3.5 cost 3.7)))))))

;; =============================================================================
;; supports-prefill protocol tests
;; =============================================================================

(deftest supports-prefill-test
  (testing "Anthropic provider supports prefill for Sonnet"
    (let [p (provider/anthropic-provider {:api-key "test" :model "claude-sonnet-4-5-20250929"})]
      (is (true? (provider/supports-prefill p)))))

  (testing "Anthropic provider does NOT support prefill for Opus 4.6"
    (let [p (provider/anthropic-provider {:api-key "test" :model "claude-opus-4-6"})]
      (is (false? (provider/supports-prefill p)))))

  (testing "Anthropic provider supports prefill for Opus 4.5"
    (let [p (provider/anthropic-provider {:api-key "test" :model "claude-opus-4-5-20251101"})]
      (is (true? (provider/supports-prefill p)))))

  (testing "Anthropic toolcall provider does not support prefill"
    (let [p (provider/anthropic-toolcall-provider {:api-key "test"})]
      (is (false? (provider/supports-prefill p)))))

  (testing "OpenAI provider does not support prefill"
    (let [p (provider/openai-provider {:api-key "test"})]
      (is (false? (provider/supports-prefill p)))))

  (testing "Test provider defaults to supporting prefill"
    (let [p (provider/test-provider {})]
      (is (true? (provider/supports-prefill p)))))

  (testing "Test provider can be configured as no-prefill"
    (let [p (provider/test-provider {:prefill? false})]
      (is (false? (provider/supports-prefill p)))))

  (testing "Ollama provider supports prefill"
    (let [p (provider/ollama-provider)]
      (is (true? (provider/supports-prefill p)))))

  (testing "Kimi provider supports prefill"
    (let [p (provider/kimi-provider {:api-key "test"})]
      (is (true? (provider/supports-prefill p))))))

;; =============================================================================
;; User provider display tests
;; =============================================================================

(deftest user-provider-displays-prefill-prefix-test
  (testing "user provider displays prefix content when prefill is used"
    (let [p (provider/user-provider)
          err (java.io.StringWriter.)
          response (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "suffix\n"))
                             *err* err]
                     (provider/call-llm p "Continue this Spell program."
                                        {:prefix "(quine completion (eval (do (quine prompt \"Hello me!\")))"}))]
      (is (= "suffix" response))
      (is (str/includes? (str err) "=== PREFILL PREFIX ==="))
      (is (str/includes? (str err) "Hello me!"))
      (is (str/includes? (str err) "=== USER MESSAGE ===")))))

(deftest user-provider-displays-prompt-without-prefill-test
  (testing "user provider displays prompt directly when no prefix is provided"
    (let [p (provider/user-provider)
          err (java.io.StringWriter.)
          response (binding [*in* (java.io.BufferedReader. (java.io.StringReader. "done\n"))
                             *err* err]
                     (provider/call-llm p "(eval (do " {}))]
      (is (= "done" response))
      (is (str/includes? (str err) "=== PROMPT ==="))
      (is (str/includes? (str err) "(eval (do "))
      (is (not (str/includes? (str err) "=== PREFILL PREFIX ==="))))))

;; =============================================================================
;; strip-prefix-echo tests
;; =============================================================================

(deftest strip-prefix-echo-test
  (testing "strips echoed prefix from response"
    (is (= "(def x 42))"
           (llm/strip-prefix-echo "(do " "(do (def x 42))"))))

  (testing "strips with leading whitespace in response"
    (is (= "(def x 42))"
           (llm/strip-prefix-echo "(do " "  (do (def x 42))"))))

  (testing "passes through response that doesn't echo prefix"
    (is (= "(def x 42))"
           (llm/strip-prefix-echo "(do " "(def x 42))"))))

  (testing "handles empty response"
    (is (= "" (llm/strip-prefix-echo "(do " ""))))

  (testing "handles exact prefix match with nothing after"
    (is (= "" (llm/strip-prefix-echo "(do " "(do "))))

  (testing "strips code fences from response"
    (is (= "(def x 42))"
           (llm/strip-prefix-echo "(do " "```clojure\n(def x 42))\n```"))))

  (testing "strips code fences with echoed prefix"
    (is (= "(def x 42))"
           (llm/strip-prefix-echo "(do " "```\n(do (def x 42))\n```")))))

;; =============================================================================
;; No-prefill mode integration tests
;; =============================================================================

(deftest no-prefill-mode-test
  (testing "make-llm with prefill?=false strips prefix echo"
    (let [{:keys [llm]} (th/make-test-llm {:response "(def x 42))" :prefill? false}
                          :namespaces {} :prefill? false)]
      (is (= 42 (llm "(do ")))))

  (testing "make-llm with prefill?=true (default) passes prefix normally"
    (let [{:keys [llm]} (th/make-test-llm {:response "(def x 42))" :prefill? true}
                          :namespaces {})]
      (is (= 42 (llm "(do "))))))

(deftest suffix-grammar-option-test
  (testing "make-llm passes generated grammar-format when enabled"
    (let [seen-opts (atom nil)
          prov (reify provider/LLMProvider
                 (supports-prefill [_] true)
                 (call-llm [this prompt] (provider/call-llm this prompt {}))
                 (call-llm [_ _ opts]
                   (reset! seen-opts opts)
                   "(def x 7))"))
          {:keys [llm]} (llm/make-llm {:provider prov
                                       :namespaces {}
                                       :suffix-grammar? true})]
      (is (= 7 (llm "(do ")))
      (is (= "grammar" (get-in @seen-opts [:grammar-format :type])))
      (is (= "lark" (get-in @seen-opts [:grammar-format :syntax])))
      (is (string? (get-in @seen-opts [:grammar-format :definition])))))

  (testing "skips grammar-format when generated grammar exceeds max chars"
    (let [seen-opts (atom nil)
          prov (reify provider/LLMProvider
                 (supports-prefill [_] true)
                 (call-llm [this prompt] (provider/call-llm this prompt {}))
                 (call-llm [_ _ opts]
                   (reset! seen-opts opts)
                   "(def x 9))"))
          {:keys [llm]} (llm/make-llm {:provider prov
                                       :namespaces {}
                                       :suffix-grammar? true
                                       :grammar-max-chars 10})]
      (is (= 9 (llm "(do ")))
      (is (nil? (:grammar-format @seen-opts))))))

;; =============================================================================
;; Leaf-llm async via future/plet (not spawn)
;; =============================================================================

(deftest leaf-llm-via-plet-test
  (testing "leaf-llm works in parallel via plet (the correct async pattern)"
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [prompt]
                                          (let [n (swap! call-count inc)]
                                            (cond
                                              ;; Main LLM: return code that uses plet with leaf-llm
                                              (= n 1) "'(plet [a (leaf-llm \"p1\") b (leaf-llm \"p2\")] (cat a b))))"
                                              ;; Leaf-llm calls
                                              (str/includes? prompt "p1") "hello"
                                              (str/includes? prompt "p2") "world"
                                              :else "???")))})]
      (is (= "helloworld" (llm "(eval (do "))))))

(deftest leaf-llm-via-future-await-test
  (testing "leaf-llm works with explicit future/await in trailing expression"
    (let [call-count (atom 0)
          {:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [prompt]
                                          (let [n (swap! call-count inc)]
                                            (cond
                                              ;; Main LLM: trailing expression with future/await
                                              (= n 1) "'(let [f1 (future (leaf-llm \"task-a\")) f2 (future (leaf-llm \"task-b\"))] (list (await f1) (await f2))))"
                                              (str/includes? prompt "task-a") "result-a"
                                              (str/includes? prompt "task-b") "result-b"
                                              :else "???")))})]
      (is (= ["result-a" "result-b"] (llm "(eval (do "))))))

;; =============================================================================
;; Model alias tests
;; =============================================================================

(deftest model-alias-test
  (testing "opus46 alias resolves"
    (is (= "claude-opus-4-6" (cli/resolve-model "opus46"))))

  (testing "o3 alias resolves"
    (is (= "o3" (cli/resolve-model "o3"))))

  (testing "o4-mini alias resolves"
    (is (= "o4-mini" (cli/resolve-model "o4-mini"))))

  (testing "gpt52 alias resolves"
    (is (= "gpt-5.2" (cli/resolve-model "gpt52"))))

  (testing "existing aliases still work"
    (is (= "claude-haiku-4-5-20251001" (cli/resolve-model "haiku")))
    (is (= "claude-sonnet-4-5-20250929" (cli/resolve-model "sonnet")))
    (is (= "claude-opus-4-5-20251101" (cli/resolve-model "opus")))))
