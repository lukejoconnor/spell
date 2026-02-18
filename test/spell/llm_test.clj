(ns spell.llm-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [spell.cli :as cli]
            [spell.comm :as comm]
            [spell.core :as spell :refer [effect-builtins]]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.io :as spell-io]
            [spell.prompt :as prompt]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [spell.eval :as eval]))

(use-fixtures :each
  (fn [f]
    (reset! comm/registry {})
    (f)
    (reset! comm/registry {})))

(deftest llm-basic-test
  (testing "llm evaluates response and extracts return"
    ;; Prompt: "(do " -> Response: "(def return 42))"
    ;; Full completion: "(do (def return 42))"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return 42))"})
      (is (= 42 (spell/llm "(do "))))))

(deftest llm-with-computation-test
  (testing "llm can evaluate expressions in response"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return (+ 1 2 3)))"})
      (is (= 6 (spell/llm "(do "))))))

(deftest llm-nested-call-test
  (testing "llm can call llm recursively (llm is effect-only)"
    ;; llm is an effect-builtin — must go through eval's second pass
    (let [call-count (atom 0)
          responses ["'(cat \"hello \" (llm \"(eval '(do \"))"
                     "\"world\"))"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= "hello world" (spell/llm "(eval (do ")))))))

(deftest spell-eval-with-llm-test
  (testing "spell-eval can evaluate programs containing llm calls (with effects)"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return \"from llm\"))"})
      ;; llm is effect-only, so merge effect builtins for direct spell-eval use
      (binding [eval/*builtins* (merge eval/*builtins* effect-builtins)]
        (let [r (spell/spell-eval '(llm "(do ") {})]
          (is (= "from llm" (:ok r))))))))

;; =============================================================================
;; File I/O task tests
;; =============================================================================

(deftest file-io-task-dummy-test
  (testing "file I/O task with dummy provider"
    (spit "test-greeting.txt" "Alice")
    (try
      (provider/with-provider
        (provider/dummy-provider
          {:response "(def thought \"read file\") (cat \"Hello, \" (:ok (io/slurp \"test-greeting.txt\")) \"!\"))"})
        (let [result (spell/llm "(eval '(do ")]
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
  (testing "make-llm with custom tool via namespace"
    (let [ns-map {'tools {:docs {:my-tool "A test tool."}
                          :my-tool (fn [] "tool-result")}}
          custom-llm (spell/make-llm {:namespaces ns-map})]
      (provider/with-provider
        (provider/dummy-provider
          {:response "(tools/my-tool))"})
        (is (= "tool-result" (custom-llm "(do "))))))

  (testing "make-llm without namespaces has no tools"
    (let [bare-llm (spell/make-llm {:namespaces {}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response "\"no tools here\""})
        (is (= "no tools here" (bare-llm "(do "))))))

  (testing "make-llm with agent in namespace"
    (let [helper-fn (fn
                      ([prompt] "helper-result")
                      ([prompt _handle] "helper-result"))
          ns-map {'helpers {:docs {:helper "Helper agent"}
                            :helper helper-fn}}
          parent-llm (spell/make-llm {:namespaces ns-map})]
      (provider/with-provider
        (provider/dummy-provider
          {:response "(helpers/helper \"do something\"))"})
        (is (= "helper-result" (parent-llm "(do "))))))

  (testing "llm-self provides automatic self-recursion"
    ;; llm-self is an effect-builtin: accessed via eval double-evaluation.
    (let [call-count (atom 0)
          custom-llm (spell/make-llm {:namespaces {}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "(eval (do '(cat \"outer-\" (llm-self \"(do \"))))"
                              "\"inner-result\"")))})
        (is (= "outer-inner-result" (custom-llm "(do ")))))))

;; =============================================================================
;; Namespace tests
;; =============================================================================

(deftest namespace-qualified-test
  (testing "model can use qualified symbol"
    (let [ns-map {'tools {:docs {:bash "run command"}
                          :bash (fn [_] {:exit 0 :out "ok" :err ""})}}
          test-llm (spell/make-llm {:namespaces ns-map})]
      (provider/with-provider
        (provider/dummy-provider {:response "(:out (tools/bash \"test\")))"})
        (is (= "ok" (test-llm "(do ")))))))

(deftest namespace-describe-test
  (testing "describe-fn returns namespace docs"
    (let [ns-map {'r {:docs {:a "first" :b "second"} :a identity}}
          test-llm (spell/make-llm {:namespaces ns-map})]
      (provider/with-provider
        (provider/dummy-provider {:response "(describe-fn r))"})
        (is (= {:a "first" :b "second"} (test-llm "(do ")))))))

(deftest describe-fallback-test
  (testing "describe prefers guide over docs when both present"
    (let [ns-map {:docs {:a "doc for a"} :a identity :guide "full guide text"}]
      (is (= "full guide text" (llm/describe ns-map)))
      (is (= "doc for a" (llm/describe ns-map :a)))
      (is (= "full guide text" (llm/describe ns-map :guide)))))

  (testing "describe prefers :docs over top-level"
    (let [ns-map {:docs {:x "from docs"} :x "from top"}]
      (is (= "from docs" (llm/describe ns-map :x)))))

  (testing "describe returns nil for missing key"
    (let [ns-map {:docs {:a "doc"}}]
      (is (nil? (llm/describe ns-map :missing))))))

(deftest builtins-namespace-test
  (testing "builtins docs returns category listing"
    (let [r (spell/spell-eval '(describe-fn builtins) {})]
      (is (eval/ok? r))
      (is (map? (:ok r)))
      (is (contains? (:ok r) :_))
      (is (str/includes? (:_ (:ok r)) "Core builtins"))))

  (testing "builtins category returns string"
    (let [r (spell/spell-eval '(describe-fn builtins :spell) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "quine"))))

  (testing "builtins agents category points to agents namespace"
    (let [r (spell/spell-eval '(describe-fn builtins :agents) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "agents/")))))

(deftest namespace-guide-test
  (testing "io namespace has :guide accessible via describe"
    (let [guide (llm/describe spell-io/io-namespace :guide)]
      (is (string? guide))
      (is (str/includes? guide "FILE EDITING"))))

  (testing "io describe :sh still returns doc (no regression)"
    (let [doc (llm/describe spell-io/io-namespace :sh)]
      (is (string? doc))
      (is (str/includes? doc "shell command"))))

  (testing "math namespace has :guide via spell-eval"
    (let [r (spell/spell-eval '(describe-fn math :guide) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "MATH NAMESPACE"))))

  (testing "strings namespace has :guide via spell-eval"
    (let [r (spell/spell-eval '(describe-fn strings :guide) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "STRINGS NAMESPACE"))))

  (testing "patterns namespace has :guide via spell-eval"
    (let [r (spell/spell-eval '(describe-fn patterns :guide) {})]
      (is (eval/ok? r))
      (is (string? (:ok r)))
      (is (str/includes? (:ok r) "PATTERNS NAMESPACE"))))

  (testing "agents namespace has :guide"
    (let [guide (llm/describe (deref (resolve 'spell.comm/agents-namespace)) :guide)]
      (is (string? guide))
      (is (str/includes? guide "AGENTS"))))

  (testing "futures namespace has :guide"
    (let [guide (llm/describe (deref (resolve 'spell.comm/futures-namespace)) :guide)]
      (is (string? guide))
      (is (str/includes? guide "FUTURES"))))

  (testing "globals namespace has :guide"
    (let [guide (llm/describe (deref (resolve 'spell.globals/globals-namespace)) :guide)]
      (is (string? guide))
      (is (str/includes? guide "GLOBALS")))))

(deftest namespace-multiple-calls-test
  (testing "can use multiple items from same namespace"
    (let [ns-map {'tools {:docs {:add "add fn" :sub "sub fn"}
                          :add +
                          :sub -}}
          test-llm (spell/make-llm {:namespaces ns-map})]
      (provider/with-provider
        (provider/dummy-provider {:response "(tools/add (tools/sub 10 3) 5))"})
        (is (= 12 (test-llm "(do ")))))))

(deftest namespace-agent-test
  (testing "can call agent from namespace"
    (let [mock-agent (fn ([p] (str "result: " p))
                         ([p _] (str "result: " p)))
          ns-map {'helpers {:docs {:helper "helper agent"}
                            :helper mock-agent}}
          test-llm (spell/make-llm {:namespaces ns-map})]
      (provider/with-provider
        (provider/dummy-provider {:response "(helpers/helper \"test\"))"})
        (is (= "result: test" (test-llm "(do ")))))))

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

  (testing "default prompt contains expected sections"
    (let [p (prompt/generate-system-prompt spell/all-namespaces)]
      (is (str/includes? p "INTRODUCTION"))
      (is (str/includes? p "NAMESPACES"))
      (is (str/includes? p "## builtins"))
      (is (str/includes? p "read-file"))
      (is (str/includes? p "sh"))
      (is (str/includes? p "CALL-NOW"))))

  (testing "qualified symbol usage instructions included"
    (let [p (prompt/generate-system-prompt spell/all-namespaces)]
      (is (str/includes? p "io/sh")))))


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

  (testing "openai provider prefix"
    (is (= {:provider "openai" :model "gpt-4o"}
           (cli/parse-model-spec "openai:gpt-4o"))))

  (testing "unknown prefix treated as plain model name"
    (is (= {:provider nil :model "custom:some-model"}
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
          recovery-fn (fn [result _recovery-call-fn]
                        (reset! recovery-called true)
                        ;; Return a fixed expression
                        '(def return 42))
          test-llm (spell/make-llm {:namespaces {}
                                    :recover recovery-fn})]
      (provider/with-provider
        ;; Response has an undefined symbol that will cause an error
        (provider/dummy-provider {:response "undefined-symbol)"})
        (let [result (test-llm "(do ")]
          (is @recovery-called)
          (is (= 42 result))))))

  (testing "default recovery uses LLM to fix errors"
    ;; Recovery is now on by default - test that it calls the recovery LLM
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:namespaces {}})]  ; recover defaults to true
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "undefined-symbol)"  ; first call fails
                              "42)")))})           ; recovery call returns fix
        (let [result (test-llm "(do ")]
          (is (= 2 @call-count))  ; original + recovery
          (is (= 42 result))))))

  (testing "no recovery when explicitly disabled"
    (let [test-llm (spell/make-llm {:namespaces {} :recover false})]
      (provider/with-provider
        (provider/dummy-provider {:response "undefined-symbol)"})
        (is (thrown? Exception (test-llm "(do ")))))))

(deftest namespace-recovery-invoke-fn-wrapping-test
  (testing "ns-recover handles 'Function call failed: Unbound symbol: X' from invoke-fn"
    ;; When an unbound symbol occurs inside a function passed to map/reduce/filter,
    ;; invoke-fn wraps the error: "Function call failed: Unbound symbol: X".
    ;; ns-recover must unwrap this to find and fix the symbol.
    (let [math-ns {:floor (fn [x] (long (Math/floor (double x))))
                   :long long}
          test-llm (spell/make-llm {:namespaces {'math math-ns}
                                    :recover true})]
      (provider/with-provider
        ;; Code uses bare `floor` inside map — invoke-fn wraps the error
        (provider/dummy-provider {:response "(reduce + 0 (map (fn [x] (floor (/ x 2.0))) (list 10 20 30))))"})
        (let [result (test-llm "(do ")]
          ;; ns-recover should fix floor -> math/floor
          (is (= 30 result))))))

  (testing "ns-recover handles wrapped Namespace lookup failed errors"
    (let [math-ns {:floor (fn [x] (long (Math/floor (double x))))}
          other-ns {:trim str/trim}
          test-llm (spell/make-llm {:namespaces {'math math-ns
                                                 'other other-ns}
                                    :recover true})]
      (provider/with-provider
        ;; Code uses wrong namespace: other/floor instead of math/floor
        (provider/dummy-provider {:response "(map (fn [x] (other/floor x)) (list 3.7 4.2)))"})
        ;; Namespace lookup for other/floor fails, ns-recover should fix to math/floor
        (let [result (test-llm "(do ")]
          (is (= [3 4] result)))))))

(deftest format-error-for-recovery-test
  (testing "formats error with program context"
    (let [result {:err "Unbound symbol: foo"
                  :expr 'foo
                  :env {'x 1}
                  :program '(do (def x 1) foo)}
          formatted (llm/format-error-for-recovery result)]
      (is (str/includes? formatted "Unbound symbol: foo"))
      (is (str/includes? formatted "foo"))
      (is (str/includes? formatted "(do (def x 1) foo)")))))

(deftest effect-phase-recovery-gating-test
  (testing "body error triggers recovery, effects available in re-eval"
    ;; Program: body has bare `floor`, trailing expression uses effect via io/ namespace.
    ;; Namespace recovery fixes floor -> math/floor in body.
    ;; After fix, the trailing expression should execute with effects available.
    (let [effect-called (atom false)
          math-ns {:floor (fn [x] (long (Math/floor (double x))))}
          io-ns {:do-effect (fn [] (reset! effect-called true) "effect-result")}
          test-llm (spell/make-llm {:namespaces {'math math-ns 'io io-ns}
                                    :recover true})]
      (provider/with-provider
        ;; Body uses bare `floor` (error), trailing expr uses io/do-effect
        (provider/dummy-provider {:response "(def x (floor 3.7)) '(io/do-effect)))"})
        (let [result (test-llm "(eval (do ")]
          (is (= "effect-result" result))
          (is @effect-called)))))

  ;; TODO: Re-enable after error recovery refactor
  ;; (testing "effect-phase error skips recovery entirely"
  ;;   ;; Error occurs inside eval's second pass — recovery should NOT trigger.
  ;;   (let [recovery-called (atom false)
  ;;         recovery-fn (fn [result _]
  ;;                       (reset! recovery-called true)
  ;;                       '(+ 1 2))
  ;;         test-llm (spell/make-llm {:namespaces {}
  ;;                                   :recover recovery-fn})]
  ;;     (provider/with-provider
  ;;       ;; eval's first pass succeeds (quoted expr is data),
  ;;       ;; second pass fails (undefined-effect is unbound).
  ;;       (provider/dummy-provider {:response "'(undefined-effect)))"})
  ;;       (is (thrown? Exception (test-llm "(eval (do "))))
  ;;     ;; Recovery should never have been called
  ;;     (is (false? @recovery-called))))


  (testing "no double-execution of side effects on recovery"
    ;; Body has a fixable error. An effect counter in io/ tracks execution.
    ;; After recovery, the effect should run exactly once.
    (let [effect-count (atom 0)
          math-ns {:floor (fn [x] (long (Math/floor (double x))))}
          io-ns {:count-effect (fn [] (swap! effect-count inc))}
          test-llm (spell/make-llm {:namespaces {'math math-ns 'io io-ns}
                                    :recover true})]
      (provider/with-provider
        (provider/dummy-provider {:response "(def x (floor 3.7)) '(io/count-effect)))"})
        (test-llm "(eval (do ")
        (is (= 1 @effect-count))))))

;; =============================================================================
;; Pattern tests
;; =============================================================================

(deftest check-result-ok-test
  (testing "check-result returns {:ok answer} when leaf-llm says OK"
    (provider/with-provider
      (provider/dummy-provider {:response "OK"})
      ;; leaf-llm is effect-only, so merge effect builtins
      (binding [eval/*builtins* (merge eval/*builtins* effect-builtins)]
        (let [r (spell/spell-eval '(patterns/check-result "What is 2+2?" 4) {})]
          (is (= {:ok 4} (:ok r))))))))

(deftest check-result-wrong-test
  (testing "check-result returns {:wrong msg} when leaf-llm says WRONG"
    (provider/with-provider
      (provider/dummy-provider {:response "WRONG: London is the capital of the UK."})
      (binding [eval/*builtins* (merge eval/*builtins* effect-builtins)]
        (let [r (spell/spell-eval '(patterns/check-result "Capital of France?" "London") {})]
          (is (= {:wrong "London is the capital of the UK."} (:ok r))))))))

;; =============================================================================
;; API retry logic (#64)
;; =============================================================================

(deftest llm-call-retries-transient-errors
  (testing "instant retry on 500 error succeeds on second attempt"
    (let [call-count (atom 0)]
      (provider/with-provider
        (reify provider/LLMProvider
          (supports-prefill [_] true)
          (call-llm [_ prompt] (provider/call-llm _ prompt {}))
          (call-llm [_ prompt opts]
            (swap! call-count inc)
            (if (= 1 @call-count)
              (throw (ex-info "Server error" {:status 500}))
              "success")))
        (binding [provider/*retries* [0]]
          (is (= "success" (provider/llm-call "test")))
          (is (= 2 @call-count))))))

  (testing "non-retryable error throws immediately"
    (let [call-count (atom 0)]
      (provider/with-provider
        (reify provider/LLMProvider
          (supports-prefill [_] true)
          (call-llm [_ prompt] (provider/call-llm _ prompt {}))
          (call-llm [_ prompt opts]
            (swap! call-count inc)
            (throw (ex-info "Bad request" {:status 400}))))
        (binding [provider/*retries* [0 0]]
          (is (thrown-with-msg? Exception #"Bad request"
                                (provider/llm-call "test")))
          (is (= 1 @call-count))))))

  (testing "exhausts all retries then throws"
    (let [call-count (atom 0)]
      (provider/with-provider
        (reify provider/LLMProvider
          (supports-prefill [_] true)
          (call-llm [_ prompt] (provider/call-llm _ prompt {}))
          (call-llm [_ prompt opts]
            (swap! call-count inc)
            (throw (ex-info "Rate limited" {:status 429}))))
        (binding [provider/*retries* [0 0]]
          (is (thrown-with-msg? Exception #"Rate limited"
                                (provider/llm-call "test")))
          ;; 1 initial + 2 retries = 3 calls
          (is (= 3 @call-count))))))

  (testing "no retries when *retries* is nil"
    (let [call-count (atom 0)]
      (provider/with-provider
        (reify provider/LLMProvider
          (supports-prefill [_] true)
          (call-llm [_ prompt] (provider/call-llm _ prompt {}))
          (call-llm [_ prompt opts]
            (swap! call-count inc)
            (throw (ex-info "Server error" {:status 500}))))
        (binding [provider/*retries* nil]
          (is (thrown-with-msg? Exception #"Server error"
                                (provider/llm-call "test")))
          (is (= 1 @call-count)))))))

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

  (testing "OpenAI provider does not support prefill"
    (let [p (provider/openai-provider {:api-key "test"})]
      (is (false? (provider/supports-prefill p)))))

  (testing "Dummy provider defaults to supporting prefill"
    (let [p (provider/dummy-provider)]
      (is (true? (provider/supports-prefill p)))))

  (testing "Dummy provider can be configured as no-prefill"
    (let [p (provider/dummy-provider {:prefill? false})]
      (is (false? (provider/supports-prefill p)))))

  (testing "Ollama provider supports prefill"
    (let [p (provider/ollama-provider)]
      (is (true? (provider/supports-prefill p)))))

  (testing "Kimi provider supports prefill"
    (let [p (provider/kimi-provider {:api-key "test"})]
      (is (true? (provider/supports-prefill p))))))

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
    (let [test-llm (spell/make-llm {:namespaces {} :prefill? false})]
      (provider/with-provider
        ;; Simulate a no-prefill model that echoes the full prefix+response
        (provider/dummy-provider {:response "(def x 42))"})
        (is (= 42 (test-llm "(do "))))))

  (testing "make-llm with prefill?=true (default) passes prefix normally"
    (let [test-llm (spell/make-llm {:namespaces {}})]
      (provider/with-provider
        (provider/dummy-provider {:response "(def x 42))"})
        (is (= 42 (test-llm "(do ")))))))

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
