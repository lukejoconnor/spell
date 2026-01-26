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
