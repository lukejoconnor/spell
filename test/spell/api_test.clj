(ns spell.api-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.api :as api]
            [spell.runtime :as runtime]
            [spell.globals :as globals]
            [spell.provider :as provider])
  (:import [java.io StringReader StringWriter]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

;; =============================================================================
;; Basic run tests
;; =============================================================================

(def test-agent "config/agents/base-msg.agent.edn")

(defn- observing-provider [seen-opts response]
  (reify provider/LLMProvider
    (call-llm [this prompt] (provider/call-llm this prompt {}))
    (call-llm [_ _prompt opts]
      (reset! seen-opts opts)
      response)
    (plain-text-provider [this] this)
    (supports-prefill [_] true)))

(deftest run-prompt-test
  (testing "run with :prompt triggers LLM call and returns result"
    ;; build-init wraps "Return 42" into a quine with '(extend).
    ;; extend calls !llm-self which calls the provider.
    ;; The response completes the program.
    (let [p (provider/test-provider {:response "(def x 42))"})
          result (api/run {:prompt "Return 42"
                           :lm-profile p
                           :agent test-agent})]
      (is (contains? result :result))
      (is (= 42 (:result result)))
      (is (some? (:usage-tracker result)))))

  (testing "run with :init evaluates complete program directly"
    ;; :init takes a COMPLETE Spell program (balanced, no LLM needed)
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do 42)"
                           :lm-profile p
                           :agent test-agent})]
      (is (= 42 (:result result)))))

  (testing "run with :prompt preserves prompt wrapping even when it starts with ("
    (let [call-count (atom 0)
          p (provider/test-provider
              {:response-fn (fn [_]
                              (swap! call-count inc)
                              "(def answer 42))")})
          result (api/run {:prompt "(+ 1 2)"
                           :lm-profile p
                           :agent test-agent})]
      (is (= 42 (:result result)))
      (is (= 1 @call-count))))

  (testing "run catches errors gracefully"
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do undefined-symbol)"
                           :lm-profile p
                           :agent test-agent})]
      (is (contains? result :error))
      (is (some? (:usage-tracker result))))))

;; =============================================================================
;; Validation tests
;; =============================================================================

(deftest run-validation-test
  (testing "throws when both :prompt and :init provided"
    (is (thrown-with-msg? Exception #"exactly one"
          (api/run {:prompt "hello" :init "(do )"
                    :lm-profile (provider/test-provider {:response "unused"})
                    :agent test-agent}))))

  (testing "throws when neither :prompt nor :init provided"
    (is (thrown-with-msg? Exception #"Must specify"
          (api/run {:lm-profile (provider/test-provider {:response "unused"})
                    :agent test-agent}))))

  (testing "throws when :agent missing"
    (is (thrown-with-msg? Exception #"Must specify :agent"
          (api/run {:prompt "hello"
                    :lm-profile (provider/test-provider {:response "unused"})}))))

  (testing "throws when :lm-profile missing"
    (is (thrown-with-msg? Exception #"Must specify :lm-profile"
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
                           :lm-profile p
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
                           :lm-profile p
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
                           :lm-profile p
                           :agent test-agent
                           :budget 10.0})]
      (is (= 42 (:result result)))))

  (testing "trace boolean is rejected"
    (is (thrown-with-msg? Exception #"Removed public run option"
          (api/run {:prompt "Return 42"
                    :lm-profile (provider/test-provider {:response "(def x 42))"})
                    :agent test-agent
                    :trace true}))))

  (testing "trace option respects provided trace-dir"
    (let [p (provider/test-provider {:response "(def x 42))"})
          trace-dir (.toString (java.nio.file.Files/createTempDirectory
                                 "spell-api-trace-"
                                 (make-array java.nio.file.attribute.FileAttribute 0)))
          result (api/run {:prompt "Return 42"
                           :lm-profile p
                           :agent test-agent
                           :trace-dir trace-dir})]
      (is (= 42 (:result result)))
      (is (= trace-dir (:trace-dir result)))
      (is (.exists (java.io.File. trace-dir "trace.edn")))))

  (testing "removed format option is rejected at the public API"
    (is (thrown-with-msg? Exception #"Removed public run option"
          (api/run {:prompt "Return 42"
                    :lm-profile (provider/test-provider {:response "{:result 42}))"})
                    :agent test-agent
                    :format {:required [:result]}})))))

  (testing "inline LM profile map is accepted"
    (let [result (api/run {:prompt "Return 42"
                           :lm-profile {:provider :test
                                        :response "(def x 42))"}
                           :agent test-agent})]
      (is (= 42 (:result result)))))

  (testing "LM profile path is accepted"
    (let [tmp (java.io.File/createTempFile "spell-lm-profile-" ".edn")]
      (try
        (spit tmp (pr-str {:provider :test
                           :response "(def x 42))"}))
        (let [result (api/run {:prompt "Return 42"
                               :lm-profile (.getAbsolutePath tmp)
                               :agent test-agent})]
          (is (= 42 (:result result))))
        (finally
          (.delete tmp)))))

  (testing "run-level model and reasoning effort override provider defaults"
    (let [seen (atom nil)
          result (api/run {:prompt "Return 42"
                           :lm-profile (observing-provider seen "(def x 42))")
                           :agent test-agent
                           :model "override-model"
                           :reasoning-effort "high"})]
      (is (= 42 (:result result)))
      (is (= "override-model" (:model @seen)))
      (is (= "high" (:reasoning-effort @seen)))))

  (testing "log-writer is flushed but not closed"
    (let [closed? (atom false)
          writer (proxy [StringWriter] []
                   (close [] (reset! closed? true)))
          result (api/run {:prompt "Return 42"
                           :lm-profile (provider/test-provider {:response "(def x 42))"})
                           :agent test-agent
                           :log-writer writer})]
      (is (= 42 (:result result)))
      (is (false? @closed?))))

  (testing "user-reader registers :user"
    (let [result (api/run {:init "(do 42)"
                           :lm-profile (provider/test-provider {:response "unused"})
                           :agent test-agent
                           :user-reader (StringReader. "")})]
      (is (= 42 (:result result)))
      (is (contains? (globals/get-val :roles) :user))))
