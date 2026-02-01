(ns spell.llm-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [spell.cli :as cli]
            [spell.core :as spell]
            [spell.provider :as provider]
            [spell.tools :as tools]
            [spell.prompt :as prompt]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deftest llm-basic-test
  (testing "llm evaluates response and extracts return"
    ;; Dummy provider returns a response that completes the prompt
    ;; Prompt: "(do " -> Response: "(def return 42))"
    ;; Full completion: "(do (def return 42))"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return 42))"})
      (is (= 42 (spell/llm "(do "))))))

(deftest llm-with-computation-test
  (testing "llm can evaluate expressions in response"
    ;; Response includes computation
    (provider/with-provider
      (provider/dummy-provider {:response "(def return (+ 1 2 3)))"})
      (is (= 6 (spell/llm "(do "))))))

(deftest llm-nested-call-test
  (testing "llm can call llm recursively"
    ;; Outer call gets response that calls llm
    ;; Inner call returns "world"
    (let [call-count (atom 0)
          responses ["(def return (cat \"hello \" (llm \"(do \"))))"
                     "(def return \"world\"))"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= "hello world" (spell/llm "(do ")))))))

(deftest spell-eval-with-llm-test
  (testing "spell-eval can evaluate programs containing llm calls"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return \"from llm\"))"})
      (let [[result _] (spell/spell-eval '(llm "(do ") {})]
        (is (= "from llm" result))))))

;; =============================================================================
;; Greeting task tests (read-name tool)
;; =============================================================================

(deftest greeting-task-dummy-test
  (testing "greeting task with dummy provider - expected completion"
    ;; The expected LLM response for the greeting task
    ;; Response needs net -2 to close input's 2 open parens
    ;; Opens: (do, (def return, (cat, (read-name = 4 + def thought opens/closes = 4
    ;; Need: 4 + 2 = 6 closing parens minimum
    (spit "name.txt" "Alice")
    (try
      (provider/with-provider
        (provider/dummy-provider
          {:response "(do (def thought \"use read-name tool\") (def return (cat \"Hello, \" (read-name) \"!\"))))))"})
        (let [result (spell/llm "(do (def prefix \"Read name.txt and greet the person\") (def response ")]
          (is (= "Hello, Alice!" result))))
      (finally
        (io/delete-file "name.txt")))))

;; Delegation test omitted for now - escaping is complex
;; The simple greeting test demonstrates the read-name tool works

;; =============================================================================
;; Hook tests
;; =============================================================================

(deftest llm-hooks-basic-test
  (testing "hook transforms completion before evaluation"
    ;; The completion returns 10, but our hook wraps it to add 5
    ;; Hook: adds a binding that modifies return
    (provider/with-provider
      (provider/dummy-provider {:response "(def return 10))"})
      (let [;; Hook that intercepts the program and wraps return value
            hook '(fn [code]
                    ;; code is: (do (def depth 0) (def prefix "...") (def response (def return 10)))
                    ;; We wrap it to add 5 to return
                    (list 'do code '(def return (+ return 5))))
            result (spell/llm "(do " [hook])]
        (is (= 15 result))))))

(deftest llm-hooks-multiple-test
  (testing "multiple hooks compose left-to-right"
    ;; First hook adds 10, second hook doubles
    (provider/with-provider
      (provider/dummy-provider {:response "(def return 5))"})
      (let [add-hook '(fn [code]
                        (list 'do code '(def return (+ return 10))))
            double-hook '(fn [code]
                           (list 'do code '(def return (* return 2))))
            ;; Apply add-hook first: 5+10=15, then double: 15*2=30
            result (spell/llm "(do " [add-hook double-hook])]
        (is (= 30 result))))))

(deftest llm-hooks-inject-binding-test
  (testing "hook can inject bindings into program"
    ;; LLM uses 'secret' which hook injects
    (provider/with-provider
      (provider/dummy-provider {:response "(def return (+ secret 1)))"})
      (let [inject-hook '(fn [code]
                           (list 'do '(def secret 99) code))
            result (spell/llm "(do " [inject-hook])]
        (is (= 100 result))))))

(deftest llm-hooks-no-hooks-unchanged-test
  (testing "empty hooks list doesn't change behavior"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return 42))"})
      (is (= 42 (spell/llm "(do " []))))))

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
                           {:input_tokens 100 :output_tokens 50 :calls 1}}}
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

(deftest llm-recursive-hook-test
  (testing "recursive hook propagates to nested llm calls"
    ;; Outer call returns (llm "nested")
    ;; Inner call returns 10
    ;; Recursive hook: (1) applies add-hook, (2) prepends [add-hook, recursive-hook] to llm calls
    ;;
    ;; Flow:
    ;; - Outer completion transformed by recursive-hook:
    ;;   - add-hook adds (def return (+ return 5))
    ;;   - prepends [add-hook, recursive-hook] to nested llm
    ;; - Outer evaluates:
    ;;   - Inner llm runs with [add-hook, recursive-hook]
    ;;     - LLM returns 10
    ;;     - add-hook: 10 + 5 = 15
    ;;     - recursive-hook: applies add-hook (15 + 5 = 20)
    ;;     - Inner returns 20
    ;;   - Outer return = 20
    ;;   - Outer's (def return (+ return 5)) -> 20 + 5 = 25
    (let [call-count (atom 0)
          responses ["(def return (llm \"nested\")))"  ; outer calls nested
                     "(def return 10))"]]               ; nested returns 10
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (let [;; Hook that adds 5 to return
              add-hook '(fn [code]
                          (list 'do code '(def return (+ return 5))))
              ;; Make it recursive
              recursive-hook (spell/recurse add-hook)
              result (spell/llm "(do " [recursive-hook])]
          (is (= 25 result)))))))

;; =============================================================================
;; make-llm factory tests
;; =============================================================================

(deftest make-llm-test
  (testing "make-llm with custom tool resolves during evaluation"
    (let [test-tool {:name 'my-tool
                     :fn   (fn [] "tool-result")
                     :doc  "A test tool."}
          custom-llm (spell/make-llm {:tools [test-tool]
                                :llms  {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response "(def return (my-tool))))"})
        (is (= "tool-result" (custom-llm "use tool"))))))

  (testing "make-llm without tool excludes it from evaluation"
    ;; Create an llm with NO tools - bash should be unbound
    (let [bare-llm (spell/make-llm {:llms {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response "(def return \"no tools here\")))"})
        ;; Should work for basic expressions
        (is (= "no tools here" (bare-llm "test"))))))

  (testing "make-llm with named agent function"
    (let [helper-fn (fn
                      ([prompt] "helper-result")
                      ([prompt hooks] "helper-result"))
          parent-llm (spell/make-llm {:tools []
                                :llms  {'llm #'spell/llm
                                        'helper helper-fn}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response "(def return (helper \"do something\"))))"})
        (is (= "helper-result" (parent-llm "delegate"))))))

  (testing "llm-self provides automatic self-recursion"
    ;; Create a custom llm variant with NO explicit self-reference in :llms
    ;; llm-self should still allow recursive calls
    (let [call-count (atom 0)
          custom-llm (spell/make-llm {:tools [] :llms {}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "(cat \"outer-\" (llm-self \"inner\"))"
                              "\"inner-result\"")))})
        (is (= "outer-inner-result" (custom-llm "test")))))))

;; =============================================================================
;; prelude tests
;; =============================================================================

(deftest prelude-basic-test
  (testing "prelude function is available in LLM completion"
    ;; Prelude defines (defn double [x] (* x 2))
    ;; LLM response uses it: (double 21) => 42
    (let [test-llm (spell/make-llm
                     {:tools []
                      :llms {'llm #'spell/llm}
                      :prelude ['(defn double [x] (* x 2))]})]
      (provider/with-provider
        (provider/dummy-provider {:response "(double 21))))"})
        (is (= 42 (test-llm "test")))))))

(deftest prelude-multiple-forms-test
  (testing "multiple prelude forms are all available"
    (let [test-llm (spell/make-llm
                     {:tools []
                      :llms {'llm #'spell/llm}
                      :prelude ['(defn add1 [x] (+ x 1))
                                '(defn mul2 [x] (* x 2))]})]
      (provider/with-provider
        (provider/dummy-provider {:response "(mul2 (add1 3)))))"})
        (is (= 8 (test-llm "test")))))))

(deftest prelude-empty-test
  (testing "empty prelude works identically to no prelude"
    (let [test-llm (spell/make-llm
                     {:tools []
                      :llms {'llm #'spell/llm}
                      :prelude []})]
      (provider/with-provider
        (provider/dummy-provider {:response "42))"})
        (is (= 42 (test-llm "test")))))))

(deftest prelude-with-call-now-test
  (testing "prelude functions available in call-now continuation"
    (let [call-count (atom 0)
          test-llm (spell/make-llm
                     {:tools []
                      :llms {'llm #'spell/llm}
                      :prelude ['(defn double [x] (* x 2))]})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "(def thought \"start\") (call-now {:x 21})"
                              "(double x)")))})
        (is (= 42 (test-llm "test")))))))

(deftest prelude-completion-excludes-prelude-test
  (testing "completion binding is a string that starts with (def interior"
    ;; completion captures (def interior ...) via uneval, not the outer (do <prelude> ...)
    (let [test-llm (spell/make-llm
                     {:tools []
                      :llms {'llm #'spell/llm}
                      :prelude ['(defn double [x] (* x 2))]})]
      (provider/with-provider
        (provider/dummy-provider
          {:response "(if (not (nil? completion)) (count completion) -1))))"})
        (is (pos? (test-llm "test")))))))

;; =============================================================================
;; call-now tests
;; =============================================================================

(deftest call-now-test
  (testing "basic call-now with string result"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              ;; First call: use call-now with a literal value
                              "(def thought \"thinking\") (call-now {:result \"tool-output\"})"
                              ;; Continuation: use the bound result
                              "(cat \"got: \" result)")))})
        (is (= "got: tool-output" (test-llm "test"))))))

  (testing "call-now with map result (like bash tool)"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [tools/bash-tool] :llms {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              ;; First call: bash returns a map, pass via call-now
                              "(def thought \"running\") (call-now {:output (:out (bash \"echo hello\"))})"
                              ;; Continuation: use the bound output
                              "output")))})
        (is (= "hello" (test-llm "test"))))))

  (testing "call-now with multiple bindings"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "(def thought \"plan\") (call-now {:a \"first\" :b \"second\"})"
                              "(cat a \" and \" b)")))})
        (is (= "first and second" (test-llm "test"))))))

  (testing "call-now passes completion to continuation"
    ;; The continuation should have access to the extended completion string
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "(def thought \"hi\") (call-now {:x \"val\"})"
                              ;; Verify completion is a string containing original code
                              "(if (and (not (nil? completion)) (> (count completion) 0)) \"has-completion\" \"no-completion\")")))})
        (is (= "has-completion" (test-llm "test"))))))

  (testing "recursive call-now (continuation uses call-now again)"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (case n
                              1 "(def thought \"start\") (call-now {:step1 \"one\"})"
                              2 "(call-now {:step2 (cat step1 \"-two\")})"
                              3 "(cat step2 \"-three\")")))})
        (is (= "one-two-three" (test-llm "test"))))))

  (testing "call-now with empty bindings"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "(def thought \"start\") (call-now {})"
                              "\"continued\"")))})
        (is (= "continued" (test-llm "test")))))))

;; =============================================================================
;; System prompt generation tests
;; =============================================================================

(deftest generate-system-prompt-test
  (testing "includes tool documentation"
    (let [p (prompt/generate-system-prompt
              [{:name 'my-tool :doc "Does things."}]
              {})]
      (is (str/includes? p "my-tool: Does things."))))

  (testing "includes agent documentation"
    (let [p (prompt/generate-system-prompt
              []
              {'helper {:doc "Helps with stuff."}})]
      (is (str/includes? p "(helper \"prompt\") - Helps with stuff."))))

  (testing "self-recursion listed in builtins"
    (let [p (prompt/generate-system-prompt [] {'llm #'spell/llm})]
      (is (str/includes? p "Self: llm"))))

  (testing "agents section only appears for non-self llms"
    (let [self-only (prompt/generate-system-prompt [] {'llm #'spell/llm})
          with-agent (prompt/generate-system-prompt [] {'llm #'spell/llm
                                                        'helper {:doc "Helps."}})]
      (is (not (str/includes? self-only "AGENTS")))
      (is (str/includes? with-agent "AGENTS"))))

  (testing "default prompt contains expected sections"
    (let [p (prompt/generate-system-prompt tools/default-tools {'llm #'spell/llm})]
      (is (str/includes? p "SPELL INTERPRETER"))
      (is (str/includes? p "BUILTINS"))
      (is (str/includes? p "TOOLS"))
      (is (str/includes? p "read-name"))
      (is (str/includes? p "bash"))
      (is (str/includes? p "ERROR HANDLING"))
      (is (str/includes? p "EXAMPLES")))))

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
