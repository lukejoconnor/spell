(ns spell.hooks-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.hooks :as hooks :refer [prepend-hooks-to-llm recurse
                                           with-env with-env-hints prefix-prompt]]
            [spell.eval :refer [spell-eval run-spell]]
            [clojure.string :as str]))

;; =============================================================================
;; prepend-hooks-to-llm tests
;; =============================================================================

(deftest prepend-hooks-to-llm-test
  (testing "adds hooks to llm call without existing hooks"
    (let [hooks ['hook1 'hook2]
          code '(llm "prompt")
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(llm "prompt" [hook1 hook2]) result))))

  (testing "prepends to existing hooks"
    (let [hooks ['new-hook]
          code '(llm "prompt" [existing-hook])
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(llm "prompt" [new-hook existing-hook]) result))))

  (testing "processes nested llm calls"
    (let [hooks ['h]
          code '(do (def x (llm "outer")) (llm "inner"))
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(do (def x (llm "outer" [h])) (llm "inner" [h])) result))))

  (testing "does not descend into quotes"
    (let [hooks ['h]
          code '(quote (llm "quoted"))
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(quote (llm "quoted")) result))))

  (testing "processes vectors"
    (let [hooks ['h]
          code '[(llm "a") (llm "b")]
          result (prepend-hooks-to-llm hooks code)]
      (is (= '[(llm "a" [h]) (llm "b" [h])] result))))

  (testing "processes maps"
    (let [hooks ['h]
          code '{:x (llm "val")}
          result (prepend-hooks-to-llm hooks code)]
      (is (= '{:x (llm "val" [h])} result))))

  (testing "leaves non-llm code unchanged"
    (let [hooks ['h]
          code '(+ 1 2)]
      (is (= '(+ 1 2) (prepend-hooks-to-llm hooks code)))))

  (testing "processes prompt recursively"
    (let [hooks ['h]
          code '(llm (do (llm "inner")))
          result (prepend-hooks-to-llm hooks code)]
      ;; Inner llm gets hooks, outer llm gets hooks, prompt is processed
      (is (= '(llm (do (llm "inner" [h])) [h]) result)))))

;; =============================================================================
;; recurse tests
;; =============================================================================

(deftest recurse-test
  (testing "recurse returns a function form"
    (let [hook '(fn [code] code)
          result (recurse hook)]
      (is (seq? result))
      (is (= 'fn (first result)))))

  (testing "recurse hook applies inner hook"
    ;; The recursive hook should first apply the inner hook
    (let [;; Inner hook adds a binding
          inner-hook '(fn [code] (list 'do '(def injected 1) code))
          recursive-hook (recurse inner-hook)
          ;; Evaluate the recursive hook to get a function
          [hook-fn _] (spell-eval recursive-hook {})
          ;; Apply it to some code via spell-eval (spell-fn, not Clojure fn)
          input '(do (def x 10))
          [result _] (spell-eval (list hook-fn (list 'quote input)) {})]
      ;; Should have injected binding
      (is (some #(= '(def injected 1) %) (tree-seq coll? seq result)))))

  (testing "recurse hook adds hooks to llm calls"
    (let [inner-hook '(fn [code] code)  ; identity hook
          recursive-hook (recurse inner-hook)
          [hook-fn _] (spell-eval recursive-hook {})
          ;; Input has an llm call
          input '(do (llm "test"))
          [result _] (spell-eval (list hook-fn (list 'quote input)) {})]
      ;; result is (do (llm "test" [hook1 hook2]))
      (let [llm-form (second result)  ; (llm "test" [hooks])
            hooks (nth llm-form 2)]
        (is (vector? hooks))
        (is (= 2 (count hooks)))
        ;; First hook is the inner hook (quoted)
        (is (= '(fn [code] code) (first hooks))))))

  (testing "recurse hook preserves existing llm hooks"
    (let [inner-hook '(fn [code] code)
          recursive-hook (recurse inner-hook)
          [hook-fn _] (spell-eval recursive-hook {})
          ;; Input has llm with existing hook
          input '(do (llm "test" [existing-hook]))
          [result _] (spell-eval (list hook-fn (list 'quote input)) {})]
      ;; result is (do (llm "test" [inner-hook recursive-hook existing-hook]))
      (let [llm-form (second result)
            hooks (nth llm-form 2)]
        (is (= 3 (count hooks)))
        (is (= 'existing-hook (nth hooks 2)))))))

;; =============================================================================
;; prefix-prompt tests
;; =============================================================================

(deftest prefix-prompt-test
  (testing "string prompt gets docs prepended"
    (let [result (prefix-prompt "DOCS" "task")]
      (is (string? result))
      (is (str/starts-with? result "DOCS"))
      (is (str/ends-with? result "task"))))

  (testing "thunk prompt gets env-hints binding"
    (let [result (prefix-prompt "DOCS" '(do (def return 1)))]
      (is (seq? result))
      (is (= 'do (first result)))
      ;; Should contain (def env-hints "DOCS")
      (is (= '(def env-hints "DOCS") (second result)))
      ;; Original thunk is third element
      (is (= '(do (def return 1)) (nth (seq result) 2)))))

  (testing "non-string non-thunk returned unchanged"
    (is (= 42 (prefix-prompt "DOCS" 42)))
    (is (= nil (prefix-prompt "DOCS" nil)))))

;; =============================================================================
;; inject-docs-into-llm-prompts tests
;; =============================================================================

(deftest inject-docs-into-llm-prompts-test
  (testing "rewrites llm call prompt"
    (let [inject @#'hooks/inject-docs-into-llm-prompts
          result (inject "DOCS" '(llm "task"))]
      (is (= 'llm (first result)))
      ;; prompt should be (prefix-prompt "DOCS" "task")
      (is (= '(prefix-prompt "DOCS" "task") (second result)))))

  (testing "handles llm with hooks"
    (let [inject @#'hooks/inject-docs-into-llm-prompts
          result (inject "DOCS" '(llm "task" [hook1]))]
      (is (= 'llm (first result)))
      (is (= '(prefix-prompt "DOCS" "task") (second result)))
      (is (= '[hook1] (nth (seq result) 2)))))

  (testing "skips quoted forms"
    (let [inject @#'hooks/inject-docs-into-llm-prompts
          result (inject "DOCS" '(quote (llm "task")))]
      (is (= '(quote (llm "task")) result))))

  (testing "recurses into nested structures"
    (let [inject @#'hooks/inject-docs-into-llm-prompts
          result (inject "DOCS" '(do (def x (llm "inner"))))]
      ;; The llm call inside should be rewritten
      (is (= 'do (first result)))
      (let [def-form (second result)
            llm-call (nth def-form 2)]
        (is (= 'llm (first llm-call)))
        (is (= '(prefix-prompt "DOCS" "inner") (second llm-call)))))))

;; =============================================================================
;; with-env tests
;; =============================================================================

(deftest with-env-test
  (testing "basic binding injection"
    (let [hook (with-env {:x 42})
          code '(+ x 1)
          result (hook code)]
      ;; Result should be (do (def x 42) (+ x 1))
      (is (= 43 (run-spell result)))))

  (testing "multiple bindings"
    (let [hook (with-env {:x 10 :y 20})
          result (hook '(+ x y))]
      (is (= 30 (run-spell result)))))

  (testing "error on non-keyword keys"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"keys must be keywords"
                          (with-env {'x 42}))))

  (testing "quoted values survive re-evaluation"
    ;; A symbol value needs quoting to not be re-evaluated
    (let [hook (with-env {:s 'hello})
          result (hook '(list s))]
      (is (= '(hello) (run-spell result)))))

  (testing "list values survive re-evaluation"
    (let [hook (with-env {:xs '(1 2 3)})
          result (hook '(first xs))]
      (is (= 1 (run-spell result))))))

;; =============================================================================
;; with-env-hints tests
;; =============================================================================

(deftest with-env-hints-test
  (testing "binding injection works"
    (let [hook (with-env-hints {:x [42 "the answer"]})
          code '(+ x 1)
          result (hook code)]
      ;; Should evaluate correctly with x bound
      (is (= 43 (run-spell result)))))

  (testing "multiple bindings with docs"
    (let [hook (with-env-hints {:x [10 "first number"] :y [20 "second number"]})
          result (hook '(+ x y))]
      (is (= 30 (run-spell result)))))

  (testing "llm calls get rewritten with prefix-prompt"
    (let [hook (with-env-hints {:x [42 "the answer"]})
          code '(llm "task")
          result (hook code)]
      ;; The result should contain a prefix-prompt wrapping the llm prompt
      (let [llm-forms (filter #(and (seq? %) (= 'llm (first %)))
                              (tree-seq coll? seq result))]
        (is (seq llm-forms))
        ;; Each llm's prompt arg should be a (prefix-prompt ...) call
        (doseq [llm-form llm-forms]
          (let [prompt-arg (second llm-form)]
            (is (and (seq? prompt-arg) (= 'prefix-prompt (first prompt-arg)))))))))

  (testing "documentation includes all binding descriptions"
    (let [hook (with-env-hints {:api-key ["sk-123" "API key for service"]
                                :timeout [30 "Timeout in seconds"]})
          result (hook '(llm "task"))
          ;; Find the docs string in the prefix-prompt call
          llm-form (first (filter #(and (seq? %) (= 'llm (first %)))
                                  (tree-seq coll? seq result)))
          prefix-call (second llm-form)
          docs-str (second prefix-call)]
      (is (str/includes? docs-str "api-key"))
      (is (str/includes? docs-str "API key for service"))
      (is (str/includes? docs-str "timeout"))
      (is (str/includes? docs-str "Timeout in seconds"))))

  (testing "error on non-keyword keys"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"keys must be keywords"
                          (with-env-hints {'x [42 "doc"]})))))
