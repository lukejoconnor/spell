(ns spell.llm-test
  (:require [clojure.test :refer [deftest testing is]]
            [spell.core :as spell]
            [spell.llm :as llm]
            [spell.prompt :as prompt]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deftest llm-basic-test
  (testing "llm evaluates response and extracts return"
    ;; Dummy provider returns a response that completes the prompt
    ;; Prompt: "(do " -> Response: "(def return 42))"
    ;; Full completion: "(do (def return 42))"
    (llm/with-provider
      (llm/dummy-provider {:response "(def return 42))"})
      (is (= 42 (spell/llm "(do "))))))

(deftest llm-with-computation-test
  (testing "llm can evaluate expressions in response"
    ;; Response includes computation
    (llm/with-provider
      (llm/dummy-provider {:response "(def return (+ 1 2 3)))"})
      (is (= 6 (spell/llm "(do "))))))

(deftest llm-nested-call-test
  (testing "llm can call llm recursively"
    ;; Outer call gets response that calls llm
    ;; Inner call returns "world"
    (let [call-count (atom 0)
          responses ["(def return (cat \"hello \" (llm \"(do \"))))"
                     "(def return \"world\"))"]]
      (llm/with-provider
        (llm/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= "hello world" (spell/llm "(do ")))))))

(deftest spell-eval-with-llm-test
  (testing "spell-eval can evaluate programs containing llm calls"
    (llm/with-provider
      (llm/dummy-provider {:response "(def return \"from llm\"))"})
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
      (llm/with-provider
        (llm/dummy-provider
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
    (llm/with-provider
      (llm/dummy-provider {:response "(def return 10))"})
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
    (llm/with-provider
      (llm/dummy-provider {:response "(def return 5))"})
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
    (llm/with-provider
      (llm/dummy-provider {:response "(def return (+ secret 1)))"})
      (let [inject-hook '(fn [code]
                           (list 'do '(def secret 99) code))
            result (spell/llm "(do " [inject-hook])]
        (is (= 100 result))))))

(deftest llm-hooks-no-hooks-unchanged-test
  (testing "empty hooks list doesn't change behavior"
    (llm/with-provider
      (llm/dummy-provider {:response "(def return 42))"})
      (is (= 42 (spell/llm "(do " []))))))

;; =============================================================================
;; Token usage tracking tests
;; =============================================================================

(deftest track-usage-basic-test
  (testing "track-usage! accumulates into *usage* atom"
    (let [usage-atom (atom {:by-model {}})]
      (binding [llm/*usage* usage-atom]
        (llm/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (is (= {:by-model {"claude-sonnet-4-20250514"
                           {:input_tokens 100 :output_tokens 50 :calls 1}}}
               @usage-atom))))))

(deftest track-usage-accumulates-test
  (testing "track-usage! accumulates across multiple calls"
    (let [usage-atom (atom {:by-model {}})]
      (binding [llm/*usage* usage-atom]
        (llm/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (llm/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 200 :output_tokens 75})
        (let [stats (get-in @usage-atom [:by-model "claude-sonnet-4-20250514"])]
          (is (= 300 (:input_tokens stats)))
          (is (= 125 (:output_tokens stats)))
          (is (= 2 (:calls stats))))))))

(deftest track-usage-multi-model-test
  (testing "track-usage! tracks per-model"
    (let [usage-atom (atom {:by-model {}})]
      (binding [llm/*usage* usage-atom]
        (llm/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (llm/track-usage! "claude-3-5-haiku-20241022"
                          {:input_tokens 200 :output_tokens 75})
        (is (= 2 (count (:by-model @usage-atom))))
        (is (= 100 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :input_tokens])))
        (is (= 200 (get-in @usage-atom [:by-model "claude-3-5-haiku-20241022" :input_tokens])))))))

(deftest track-usage-noop-when-unbound-test
  (testing "track-usage! is a no-op when *usage* is nil"
    (binding [llm/*usage* nil]
      ;; Should not throw
      (llm/track-usage! "model" {:input_tokens 100 :output_tokens 50}))))

(deftest usage-summary-test
  (testing "usage-summary computes totals and costs"
    (let [usage-atom (atom {:by-model {"claude-sonnet-4-20250514"
                                       {:input_tokens 1000000 :output_tokens 500000 :calls 3}}})]
      (let [{:keys [total by-model]} (llm/usage-summary usage-atom)]
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
      (let [{:keys [total]} (llm/usage-summary usage-atom)]
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
      (llm/with-provider
        (llm/dummy-provider
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
      (llm/with-provider
        (llm/dummy-provider
          {:response "(def return (my-tool))))"})
        (is (= "tool-result" (custom-llm "use tool"))))))

  (testing "make-llm without tool excludes it from evaluation"
    ;; Create an llm with NO tools - bash should be unbound
    (let [bare-llm (spell/make-llm {:llms {'llm #'spell/llm}})]
      (llm/with-provider
        (llm/dummy-provider
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
      (llm/with-provider
        (llm/dummy-provider
          {:response "(def return (helper \"do something\"))))"})
        (is (= "helper-result" (parent-llm "delegate")))))))

;; =============================================================================
;; call-now tests
;; =============================================================================

(deftest call-now-test
  (testing "basic call-now with string result"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (llm/with-provider
        (llm/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              ;; First call: use call-now with a literal value
                              "\"thinking\") (call-now {:result \"tool-output\"})))"
                              ;; Continuation: use the bound result
                              "(cat \"got: \" result)")))})
        (is (= "got: tool-output" (test-llm "test"))))))

  (testing "call-now with map result (like bash tool)"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [spell/bash-tool] :llms {'llm #'spell/llm}})]
      (llm/with-provider
        (llm/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              ;; First call: bash returns a map, pass via call-now
                              "\"running\") (call-now {:output (:out (bash \"echo hello\"))})))"
                              ;; Continuation: use the bound output
                              "output")))})
        (is (= "hello" (test-llm "test"))))))

  (testing "call-now with multiple bindings"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (llm/with-provider
        (llm/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "\"plan\") (call-now {:a \"first\" :b \"second\"})))"
                              "(cat a \" and \" b)")))})
        (is (= "first and second" (test-llm "test"))))))

  (testing "call-now passes completion to continuation"
    ;; The continuation should have access to the extended completion string
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (llm/with-provider
        (llm/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "\"hi\") (call-now {:x \"val\"})))"
                              ;; Verify completion is a string containing original code
                              "(if (and (not (nil? completion)) (> (count completion) 0)) \"has-completion\" \"no-completion\")")))})
        (is (= "has-completion" (test-llm "test"))))))

  (testing "recursive call-now (continuation uses call-now again)"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (llm/with-provider
        (llm/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (case n
                              1 "\"start\") (call-now {:step1 \"one\"})))"
                              2 "(call-now {:step2 (cat step1 \"-two\")})"
                              3 "(cat step2 \"-three\")")))})
        (is (= "one-two-three" (test-llm "test"))))))

  (testing "call-now with empty bindings"
    (let [call-count (atom 0)
          test-llm (spell/make-llm {:tools [] :llms {'llm #'spell/llm}})]
      (llm/with-provider
        (llm/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "\"start\") (call-now {})))"
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
    (let [p (prompt/generate-system-prompt spell/default-tools {'llm #'spell/llm})]
      (is (str/includes? p "SPELL INTERPRETER"))
      (is (str/includes? p "BUILTINS"))
      (is (str/includes? p "TOOLS"))
      (is (str/includes? p "read-name"))
      (is (str/includes? p "bash"))
      (is (str/includes? p "ERROR HANDLING"))
      (is (str/includes? p "EXAMPLES")))))
