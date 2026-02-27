(ns spell.api-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.api :as api]
            [spell.runtime :as runtime]
            [spell.provider :as provider]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

;; =============================================================================
;; Basic run tests
;; =============================================================================

(def test-agent "config/agents/base-msg.agent.edn")

(deftest run-prompt-test
  (testing "run with :prompt triggers LLM call and returns result"
    ;; build-init wraps "Return 42" into a quine with '(extend).
    ;; extend calls !llm-self which calls the provider.
    ;; The response completes the program.
    (let [p (provider/test-provider {:response "(def x 42))"})
          result (api/run {:prompt "Return 42"
                           :provider p
                           :agent test-agent})]
      (is (contains? result :result))
      (is (= 42 (:result result)))
      (is (some? (:usage result)))))

  (testing "run with :init evaluates complete program directly"
    ;; :init takes a COMPLETE Spell program (balanced, no LLM needed)
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do 42)"
                           :provider p
                           :agent test-agent})]
      (is (= 42 (:result result)))))

  (testing "run catches errors gracefully"
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do undefined-symbol)"
                           :provider p
                           :agent test-agent})]
      (is (contains? result :error))
      (is (some? (:usage result))))))

;; =============================================================================
;; Validation tests
;; =============================================================================

(deftest run-validation-test
  (testing "throws when both :prompt and :init provided"
    (is (thrown-with-msg? Exception #"exactly one"
          (api/run {:prompt "hello" :init "(do )"
                    :provider (provider/test-provider {:response "unused"})
                    :agent test-agent}))))

  (testing "throws when neither :prompt nor :init provided"
    (is (thrown-with-msg? Exception #"Must specify"
          (api/run {:provider (provider/test-provider {:response "unused"})
                    :agent test-agent}))))

  (testing "throws when :agent missing"
    (is (thrown-with-msg? Exception #"Must specify :agent"
          (api/run {:prompt "hello"}))))

  (testing "throws when :provider missing"
    (is (thrown-with-msg? Exception #"Must specify :provider"
          (api/run {:prompt "hello"
                    :agent test-agent})))))

;; =============================================================================
;; Init program tests
;; =============================================================================

(deftest run-init-program-test
  (testing "init program evaluates without LLM call for first pass"
    (let [call-count (atom 0)
          p (provider/test-provider
              {:response-fn (fn [_]
                              (swap! call-count inc)
                              "42)")})
          result (api/run {:init "(do 42)"
                           :provider p
                           :agent test-agent})]
      ;; The init program (do 42) evaluates directly — no LLM call needed
      (is (= 42 (:result result)))
      (is (= 0 @call-count))))

  (testing "init with extend triggers LLM call"
    (let [call-count (atom 0)
          p (provider/test-provider
              {:response-fn (fn [_]
                              (swap! call-count inc)
                              "(def answer 42))")})
          result (api/run {:init "(quine completion (eval (do '(!extend))))"
                           :provider p
                           :agent test-agent})]
      (is (= 42 (:result result)))
      (is (= 1 @call-count)))))

;; =============================================================================
;; Budget and options tests
;; =============================================================================

(deftest run-options-test
  (testing "budget option is respected"
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do 42)"
                           :provider p
                           :agent test-agent
                           :budget 10.0})]
      (is (= 42 (:result result)))))

  (testing "trace option produces trace-dir"
    (let [p (provider/test-provider {:response "(def x 42))"})
          result (api/run {:prompt "Return 42"
                           :provider p
                           :agent test-agent
                           :trace true})]
      (is (= 42 (:result result)))
      (is (string? (:trace-dir result))))))
