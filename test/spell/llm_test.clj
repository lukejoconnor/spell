(ns spell.llm-test
  (:require [clojure.test :refer [deftest testing is]]
            [spell.core :as spell]
            [spell.llm :as llm]
            [clojure.java.io :as io]))

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
