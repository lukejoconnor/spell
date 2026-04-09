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
            [spell.stdlib :as stdlib]
            [spell.parse :as parse]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

(defn- append-forms-macro
  [& forms]
  (#'runtime/append-forms-macro forms))

(deftest llm-basic-test
  (testing "llm evaluates response and extracts return"
    ;; Prompt: "(do " -> Response: "(def return 42))"
    ;; Full completion: "(do (def return 42))"
    (let [llm (th/make-test-runner {:response "(def return 42))"})]
      (is (= 42 (llm "(do "))))))

(deftest llm-with-computation-test
  (testing "llm can evaluate expressions in response"
    (let [llm (th/make-test-runner {:response "(def return (+ 1 2 3)))"})]
      (is (= 6 (llm "(do "))))))

(deftest llm-nested-call-test
  (testing "llm can call llm recursively (llm is effect-only)"
    ;; llm is an effect-builtin — must go through eval's second pass
    (let [call-count (atom 0)
          responses ["'(cat \"hello \" (!llm-self \"(eval '(do \"))"
                     "\"world\"))"]]
      (let [llm (th/make-test-runner
                 {:response-fn (fn [_]
                                 (let [r (nth responses @call-count)]
                                   (swap! call-count inc)
                                   r))})]
        (is (= "hello world" (llm "(eval (do ")))))))

(deftest peek-now-e2e-persisted-binding-test
  (testing "!peek-now supports explicit persist in the next turn and only that survives after !extend"
    (let [call-count (atom 0)
          responses ["'(!peek-now x 42)))"
                     "(persist y x) '(!extend completion)))"
                     "y)))"]
          llm (th/make-test-runner
               {:response-fn (fn [_]
                               (let [idx @call-count]
                                 (swap! call-count inc)
                                 (nth responses idx)))})]
      (is (= 42 (llm "(quine completion (eval (do ")))
      (is (= 3 @call-count)))))

(deftest spell-eval-with-llm-test
  (testing "spell-eval can evaluate programs containing llm calls (with effects)"
    ;; Create an LLM with provider, then use its eval pipeline
    (let [llm (th/make-test-runner {:response "(def return \"from llm\"))"})
          ;; Use the llm function through the eval pipeline
          result (llm "(do ")]
      (is (= "from llm" result)))))

(deftest inbox-top-level-form-gating-test
  (let [variant-builtins (merge eval/core-builtins
                                {'describe-fn stdlib/describe}
                                llm/core-namespaces)
        eval-builtin (llm/make-eval variant-builtins {})]
    (testing "default: reads first form only, ignores trailing forms"
      (let [inbox-fn (llm/make-inbox-fn {:variant-builtins variant-builtins
                                         :eval-builtin eval-builtin
                                         :recover-fn nil}
                                        (atom nil))]
        ;; First form (def x 1) evaluates to 1; second form is ignored
        (is (= 1 (inbox-fn "(def x 1) (+ x 1)")))))

    (testing "default: first form valid, garbage after — no error"
      (let [inbox-fn (llm/make-inbox-fn {:variant-builtins variant-builtins
                                         :eval-builtin eval-builtin
                                         :recover-fn nil}
                                        (atom nil))]
        (is (= 42 (inbox-fn "(+ 40 2) But wait carefully: I should reconsider...")))))

    (testing "allow-multiple-top-level: reads all forms, wraps in do"
      (let [inbox-fn (llm/make-inbox-fn {:variant-builtins variant-builtins
                                         :eval-builtin eval-builtin
                                         :allow-multiple-top-level? true
                                         :recover-fn nil}
                                        (atom nil))]
        (is (= 2 (inbox-fn "(def x 1) (+ x 1)")))))))

(deftest inbox-trims-persisted-raw-for-reopen-test
  (let [variant-builtins (merge eval/core-builtins
                                {'describe-fn stdlib/describe
                                 'capture-raw (fn [] runtime/*current-raw*)}
                                llm/core-namespaces)
        eval-builtin (llm/make-eval variant-builtins {})
        inbox-fn (llm/make-inbox-fn {:variant-builtins variant-builtins
                                     :eval-builtin eval-builtin
                                     :recover-fn nil}
                                    (atom nil))
        handle :trimmed-raw-agent
        raw "(quine completion (eval (do (capture-raw)))) But wait carefully: I should reconsider..."
        expected "(quine completion (eval (do (capture-raw))))"
        p (promise)]
    (runtime/register! handle)
    (deliver p raw)
    (is (= expected
           (runtime/run-root-box handle p (runtime/make-awake-fn handle inbox-fn) inbox-fn))
        "evaluation should see the reopenable completion, not the ignored suffix")
    (is (= expected @(:last-raw (get @runtime/registry handle)))
        "stored raw should drop ignored suffixes so later wakeups can reopen it")
    (is (= (parse/read-first expected)
           (parse/read-first @(:last-raw (get @runtime/registry handle))))
        "stored raw should remain parseable for later inbox macro application paths")))

(deftest inbox-preserves-split-top-level-raw-for-reopen-test
  (let [variant-builtins (merge eval/core-builtins
                                {'describe-fn stdlib/describe}
                                llm/core-namespaces)
        eval-builtin (llm/make-eval variant-builtins {})
        inbox-fn (llm/make-inbox-fn {:variant-builtins variant-builtins
                                     :eval-builtin eval-builtin
                                     :allow-multiple-top-level? true
                                     :recover-fn nil}
                                    (atom nil))
        handle :split-top-level-agent
        first-form '(quine completion (eval (do (def first-msg "kept") nil)))
        last-form '(quine completion (eval (do (def second-msg "open-me"))))
        raw (str (pr-str first-form) " " (pr-str last-form))
        p (promise)]
    (runtime/register! handle)
    (runtime/-send! handle (append-forms-macro '(def injected :yes)))
    (deliver p raw)
    (runtime/box handle p (runtime/make-awake-fn handle inbox-fn))
    (let [stored @(:last-raw (get @runtime/registry handle))
          forms (vec (parse/read-all stored))
          reopened-form (last forms)
          body-exprs (rest (second (last reopened-form)))]
      (is (= 2 (count forms))
          "stored raw should preserve the split top-level shape")
      (is (= 'quine (first reopened-form)))
      (is (some #(= '(def second-msg "open-me") %) body-exprs))
      (is (some #(= '(def injected :yes) %) body-exprs)))))

;; =============================================================================
;; File I/O task tests
;; =============================================================================

(deftest file-io-task-dummy-test
  (testing "file I/O task with dummy provider"
    (spit "test-greeting.txt" "Alice")
    (try
      (let [llm (th/make-test-runner
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
        (is (= 100 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :input_tokens])))
        (is (= 50 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :output_tokens])))
        (is (= 1 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :calls])))
        (is (= 150 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :max_total_tokens])))
        (is (== 0.0010500000000000002 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :cost])))))))

(deftest track-usage-accumulates-test
  (testing "track-usage! accumulates across multiple calls"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom]
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 200 :output_tokens 75
                           :cache_creation_input_tokens 10
                           :cache_read_input_tokens 5})
        (let [stats (get-in @usage-atom [:by-model "claude-sonnet-4-20250514"])]
          (is (= 300 (:input_tokens stats)))
          (is (= 125 (:output_tokens stats)))
        (is (= 10 (:cache_creation_input_tokens stats)))
        (is (= 5 (:cache_read_input_tokens stats)))
        (is (= 2 (:calls stats)))
        (is (= 290 (:max_total_tokens stats)))
        (is (== 0.0028140000000000005 (:cost stats))))))))

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
        (is (= 200 (get-in @usage-atom [:by-model "claude-3-5-haiku-20241022" :input_tokens])))
        (is (== 0.0010500000000000002 (get-in @usage-atom [:by-model "claude-sonnet-4-20250514" :cost])))
        (is (== 0.00046 (get-in @usage-atom [:by-model "claude-3-5-haiku-20241022" :cost])))))))

(deftest track-usage-noop-when-unbound-test
  (testing "track-usage! is a no-op when *usage* is nil"
    (binding [provider/*usage* nil]
      ;; Should not throw
      (provider/track-usage! "model" {:input_tokens 100 :output_tokens 50}))))

(deftest usage-summary-context-stats-test
  (testing "usage-summary reports per-model and total context mean/max"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom]
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 100 :output_tokens 50})
        (provider/track-usage! "claude-sonnet-4-20250514"
                          {:input_tokens 200 :output_tokens 75
                           :cache_creation_input_tokens 10
                           :cache_read_input_tokens 5})
        (provider/track-usage! "claude-3-5-haiku-20241022"
                          {:input_tokens 80 :output_tokens 20
                           :cache_read_input_tokens 30}))
      (let [{:keys [by-model total]} (provider/usage-summary usage-atom)
            sonnet (get by-model "claude-sonnet-4-20250514")
            haiku (get by-model "claude-3-5-haiku-20241022")]
        (is (== 220.0 (:mean_total_tokens sonnet)))
        (is (= 290 (:max_total_tokens sonnet)))
        (is (== 0.0028140000000000005 (:cost sonnet)))
        (is (== 130.0 (:mean_total_tokens haiku)))
        (is (= 130 (:max_total_tokens haiku)))
        (is (== 0.0001464 (:cost haiku)))
        (is (== 190.0 (:mean_total_tokens total)))
        (is (= 290 (:max_total_tokens total)))
        (is (== 0.0029604000000000006 (:cost total))))))

  (testing "usage-summary defaults missing max_total_tokens to zero for prepopulated atoms"
    (let [usage-atom (atom {:by-model {"legacy-model" {:input_tokens 150
                                                       :output_tokens 50
                                                       :calls 2}}})
          {:keys [by-model total]} (provider/usage-summary usage-atom)]
      (is (== 100.0 (get-in by-model ["legacy-model" :mean_total_tokens])))
      (is (= 0 (get-in by-model ["legacy-model" :max_total_tokens])))
      (is (== 100.0 (:mean_total_tokens total)))
      (is (= 0 (:max_total_tokens total)))))

  (testing "track-usage! preserves explicit prepopulated max_total_tokens"
    (let [usage-atom (atom {:by-model {"legacy-model" {:input_tokens 2000
                                                       :output_tokens 0
                                                       :calls 2
                                                       :max_total_tokens 1000}}})]
      (binding [provider/*usage* usage-atom]
        (provider/track-usage! "legacy-model" {:input_tokens 100 :output_tokens 0}))
      (let [stats (get-in @usage-atom [:by-model "legacy-model"])]
        (is (= 3 (:calls stats)))
        (is (= 1000 (:max_total_tokens stats))))))

  (testing "track-usage! derives accumulated cost when resuming a preseeded bucket"
    (let [usage-atom (atom {:by-model {"legacy-model" {:input_tokens 1000000
                                                       :output_tokens 500000
                                                       :calls 1}}
                            :cost-table {"legacy-model" {:input 1.0
                                                         :output 2.0}}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        (provider/track-usage! "legacy-model"
                               {:input_tokens 2000000
                                :output_tokens 1000000}
                               {"legacy-model" {:input 1.0
                                                :output 2.0}}))
      (let [stats (get-in @usage-atom [:by-model "legacy-model"])]
        (is (= 6.0 (:cost stats)))
        (is (= 6.0 (double (provider/current-cost usage-atom)))))
      (let [{:keys [by-model total]} (provider/usage-summary usage-atom)]
        (is (= 6.0 (get-in by-model ["legacy-model" :cost])))
        (is (= 6.0 (:cost total)))))))

(deftest current-cost-supports-explicit-cache-read-price-test
  (testing "current-cost uses model-specific cache read pricing when provided"
    (let [usage-atom (atom {:by-model {"accounts/fireworks/models/glm-5"
                                       {:input_tokens 0
                                        :output_tokens 0
                                        :cache_read_input_tokens 0
                                        :calls 0
                                        :cache_creation_input_tokens 0
                                        :max_total_tokens 0}}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        (provider/track-usage! "accounts/fireworks/models/glm-5"
                          {:input_tokens 1000000
                           :output_tokens 500000
                           :cache_read_input_tokens 200000}
                          {"accounts/fireworks/models/glm-5"
                           {:input 1.00
                            :cache-read-input 0.20
                            :output 3.20}}))
      (is (= 2.64 (double (provider/current-cost usage-atom)))))))

(deftest current-cost-prices-gpt-5-4-pro-at-pro-rate-test
  (testing "current-cost prices gpt-5.4-pro before the overlapping gpt-5.4 prefix"
    (let [usage-atom (atom {:by-model {}})]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        (provider/track-usage! "gpt-5.4-pro"
                          {:input_tokens 1000000
                           :output_tokens 0
                           :cache_creation_input_tokens 0
                           :cache_read_input_tokens 0}))
      (is (= 30.0 (double (provider/current-cost usage-atom)))))))

;; =============================================================================
;; compile-agent factory tests
;; =============================================================================

(deftest compile-agent-test
  (testing "compile-agent with custom tool via namespace (effect)"
    (let [ns-map {'tools {:docs {:my-tool "A test tool."}
                          :my-tool (fn [] "tool-result")}}
          llm (th/make-test-runner {:response "(tools/my-tool)))"}
                                   :namespaces ns-map)]
      (is (= "tool-result" (llm "(eval (do '")))))

  (testing "compile-agent without namespaces has no tools"
    (let [llm (th/make-test-runner {:response "\"no tools here\""}
                                   :namespaces {})]
      (is (= "no tools here" (llm "(do ")))))

  (testing "compile-agent with agent in namespace via agents/spawn"
    (let [helper-fn (th/compiled-agent-fn (fn [_prompt _handle] "helper-result"))
          ns-map {'helpers {:docs {:helper "Helper agent"}
                            :helper helper-fn}
                  'agents runtime/agents-namespace}
          llm (th/make-test-runner {:response "(agents/spawn helpers/helper \"do something\")))"}
                                   :namespaces ns-map)]
      (is (keyword? (llm "(eval (do '")))))

  (testing "!llm-self provides automatic self-recursion"
    ;; !llm-self is an effect-builtin: accessed via eval double-evaluation.
    (let [call-count (atom 0)
          llm (th/make-test-runner
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
          llm (th/make-test-runner {:response "(:out (tools/bash \"test\")))"}
                                   :namespaces ns-map)]
      (is (= "ok" (llm "(eval (do '"))))))

(deftest namespace-describe-test
  (testing "describe-fn returns namespace docs (effect namespace)"
    (let [ns-map {'r {:docs {:a "first" :b "second"} :a identity}}
          llm (th/make-test-runner {:response "(describe-fn r)))"}
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


(deftest namespace-multiple-calls-test
  (testing "can use multiple items from same namespace (effect)"
    (let [ns-map {'tools {:docs {:add "add fn" :sub "sub fn"}
                          :add +
                          :sub -}}
          llm (th/make-test-runner {:response "(tools/add (tools/sub 10 3) 5)))"}
                                   :namespaces ns-map)]
      (is (= 12 (llm "(eval (do '"))))))

(deftest namespace-agent-test
  (testing "compiled agent in a namespace can be passed to agents/spawn"
    (let [mock-agent (th/compiled-agent-fn (fn [p _] (str "result: " p)))
          ns-map {'helpers {:docs {:helper "helper agent"}
                            :helper mock-agent}
                  'agents runtime/agents-namespace}
          llm (th/make-test-runner {:response "(agents/spawn helpers/helper \"test\")))"}
                                   :namespaces ns-map)]
      (is (keyword? (llm "(eval (do '"))))))

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

  (testing "compose-system-prompt with base includes base text and namespace sections"
    (let [p (prompt/compose-system-prompt {:base "INTRODUCTION\nSpell is a Lisp."
                                        :namespaces {'io spell-io/io-namespace}})]
      (is (str/includes? p "INTRODUCTION"))
      (is (str/includes? p "NAMESPACES"))
      (is (str/includes? p "## io"))
      ;; io namespace content appears (don't check specific function names)
      (is (> (count p) (count "INTRODUCTION\nSpell is a Lisp.")))))

  (testing "short-docs appear in system prompt for core namespaces"
    (let [core-ns {'strings stdlib/strings 'math stdlib/math}
          p (prompt/compose-system-prompt {:core-namespaces core-ns})]
      (is (str/includes? p "strings"))
      (is (str/includes? p "math"))
      ;; each core namespace contributes its :short-docs string
      (is (str/includes? p (:short-docs stdlib/strings)))
      (is (str/includes? p (:short-docs stdlib/math)))))

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
      (is (some? (:base-url provider)))
      (is (some? (:model provider)))))

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

  (testing "load-provider supports codex-msg type"
    (let [auth-file (java.io.File/createTempFile "provider-codex-msg-auth-" ".json")
          provider-file (java.io.File/createTempFile "provider-codex-msg-" ".provider.edn")]
      (try
        (spit auth-file (json/write-str {:tokens {:access_token "test-token"
                                                  :account_id "acc-1"}}))
        (spit provider-file (pr-str {:type :codex-msg
                                     :auth-file (.getAbsolutePath auth-file)
                                     :model "gpt-5.3-codex"}))
        (let [p (provider/load-provider (.getAbsolutePath provider-file))]
          (is (instance? spell.provider.CodexMsgProvider p))
          (is (= "gpt-5.3-codex" (:model p))))
        (finally
          (.delete auth-file)
          (.delete provider-file)))))

  (testing "load-provider supports codex-tc type"
    (let [auth-file (java.io.File/createTempFile "provider-codex-tc-auth-" ".json")
          provider-file (java.io.File/createTempFile "provider-codex-tc-" ".provider.edn")]
      (try
        (spit auth-file (json/write-str {:tokens {:access_token "test-token"
                                                  :account_id "acc-1"}}))
        (spit provider-file (pr-str {:type :codex-tc
                                     :auth-file (.getAbsolutePath auth-file)
                                     :model "gpt-5.3-codex"}))
        (let [p (provider/load-provider (.getAbsolutePath provider-file))]
          (is (instance? spell.provider.CodexTcProvider p))
          (is (= "gpt-5.3-codex" (:model p))))
        (finally
          (.delete auth-file)
          (.delete provider-file)))))

  (testing "load-provider supports anthropic-tc type"
    (let [provider-file (java.io.File/createTempFile "provider-anthropic-tc-" ".provider.edn")]
      (try
        (spit provider-file (pr-str {:type :anthropic-tc
                                     :model "claude-sonnet-4-5-20250929"}))
        (with-redefs [provider/anthropic-tc-provider (fn [opts]
                                                       {:provider :anthropic-tc
                                                        :opts opts})]
          (let [p (provider/load-provider (.getAbsolutePath provider-file))]
            (is (= :anthropic-tc (:provider p)))
            (is (= "claude-sonnet-4-5-20250929" (get-in p [:opts :model])))))
        (finally
          (.delete provider-file)))))

  (testing "load-provider threads OpenAI toolcall opts and cache-read ratio"
    (let [provider-file (java.io.File/createTempFile "provider-openai-tc-" ".provider.edn")]
      (try
        (spit provider-file (pr-str {:type :openai
                                     :model "gpt-5.4"
                                     :force-tool-call true
                                     :costs {"gpt-5.4" [2.50 15.00]}
                                     :cache-read-ratio 0.25}))
        (let [p (provider/load-provider (.getAbsolutePath provider-file))
              usage-atom (atom {:by-model {}})]
          (binding [provider/*usage* usage-atom
                    provider/*budget* nil]
            (provider/track-usage! "gpt-5.4"
                              {:input_tokens 1000000
                               :output_tokens 0
                               :cache_read_input_tokens 1000000}
                              (:costs p)))
          (is (instance? spell.provider.OpenAIProvider p))
          (is (true? (:force-tool-call p)))
          (is (= 3.125 (double (provider/current-cost usage-atom)))))
        (finally
          (.delete provider-file)))))

  (testing "load-provider supports fireworks type"
    (let [provider-file (java.io.File/createTempFile "provider-fireworks-" ".provider.edn")]
      (try
        (spit provider-file (pr-str {:type :fireworks
                                     :model "glm-5"
                                     :convert-think? false}))
        (with-redefs [provider/fireworks-provider (fn [opts]
                                                    {:provider :fireworks
                                                     :opts opts})]
          (let [p (provider/load-provider (.getAbsolutePath provider-file))]
            (is (= :fireworks (:provider p)))
            (is (= "glm-5" (get-in p [:opts :model])))
            (is (false? (get-in p [:opts :convert-think?])))))
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
      (is (some? (:base-url provider)))
      (is (some? (:model provider)))))

  (testing "custom base-url and model"
    (let [provider (provider/openai-provider {:api-key "sk-test"
                                          :base-url "https://custom.api.com/v1"
                                          :model "gpt-4o-mini"})]
      (is (= "https://custom.api.com/v1" (:base-url provider)))
      (is (= "gpt-4o-mini" (:model provider)))))

  (testing "strips trailing slash from base-url"
    (let [provider (provider/openai-provider {:api-key "sk-test"
                                          :base-url "https://api.openai.com/v1/"})]
      (is (= "https://api.openai.com/v1" (:base-url provider)))))

  (testing "retains force-tool-call when requested"
    (let [provider (provider/openai-provider {:api-key "sk-test"
                                              :force-tool-call true})]
      (is (true? (:force-tool-call provider))))))

(deftest plain-text-provider-mapping-test
  (testing "anthropic tool-call resolves to prefill sibling with same model"
    (let [prov (provider/->AnthropicTcProvider "k" "claude-sonnet-4-5-20250929" 16384 nil {"claude" [1 1]})
          leaf (provider/plain-text-provider prov)]
      (is (instance? spell.provider.AnthropicPfProvider leaf))
      (is (= "claude-sonnet-4-5-20250929" (:model leaf)))
      (is (= 16384 (:max-tokens leaf)))
      (is (= {"claude" [1 1]} (:costs leaf)))))

  (testing "codex tool-call resolves to message sibling with same model"
    (let [prov (provider/->CodexTcProvider "tok" "acct" "https://chatgpt.com/backend-api/codex" "gpt-5.3-codex" 4096 "cache-key" nil nil)
          leaf (provider/plain-text-provider prov)]
      (is (instance? spell.provider.CodexMsgProvider leaf))
      (is (= "gpt-5.3-codex" (:model leaf)))
      (is (= "acct" (:account-id leaf)))
      (is (= 4096 (:max-tokens leaf)))))

  (testing "openai tool-call resolves to non-toolcall sibling with same routing fields"
    (let [prov (provider/->OpenAIProvider "sk" "https://api.openai.com/v1" "gpt-5.4" 8192 nil true true
                                          "cache-key" 600 {"gpt-5.4" [2.5 15]})
          leaf (provider/plain-text-provider prov)]
      (is (instance? spell.provider.OpenAIProvider leaf))
      (is (= "gpt-5.4" (:model leaf)))
      (is (true? (:use-responses-api leaf)))
      (is (false? (:force-tool-call leaf)))
      (is (= "cache-key" (:prompt-cache-key leaf)))
      (is (= 600 (:request-timeout-sec leaf)))
      (is (= {"gpt-5.4" [2.5 15]} (:costs leaf)))))

  (testing "openai plain-text providers stay identity-preserving"
    (let [prov (provider/->OpenAIProvider "sk" "https://api.openai.com/v1" "gpt-5.4" 8192 nil true false
                                          "cache-key" 600 {"gpt-5.4" [2.5 15]})]
      (is (identical? prov (provider/plain-text-provider prov)))))

  (testing "already plain-text providers return themselves"
    (let [prov (provider/test-provider {:response "ok"})]
      (is (identical? prov (provider/plain-text-provider prov))))))

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

  (testing "toolcall mode requires spell_suffix and separates cached tokens from input"
    (let [response-body (json/write-str {:output_text "ignored"
                                         :output [{:type "custom_tool_call"
                                                   :name "spell_suffix"
                                                   :input "(def x 1)"}]
                                         :usage {:input_tokens 20
                                                 :output_tokens 7
                                                 :input_tokens_details {:cached_tokens 8}}})
          result (#'provider/parse-openai-responses-response response-body true)]
      (is (= "(def x 1)" (:text result)))
      (is (= 12 (get-in result [:usage :input_tokens])))
      (is (= 8 (get-in result [:usage :cache_read_input_tokens])))
      (is (= 7 (get-in result [:usage :output_tokens])))))

  (testing "toolcall mode throws when spell_suffix tool call is missing"
    (let [response-body (json/write-str {:output_text "plain text"
                                         :usage {:input_tokens 3
                                                 :output_tokens 2}})]
      (is (thrown-with-msg? Exception #"missing custom_tool_call"
            (#'provider/parse-openai-responses-response response-body true)))))

  (testing "throws incomplete responses as retryable missing-tool-call errors"
    (let [response-body (json/write-str {:status "incomplete"
                                         :incomplete_details {:reason "max_output_tokens"}
                                         :output [{:type "custom_tool_call"
                                                   :name "spell_suffix"
                                                   :input "(def x"}]})
          ex (try
               (#'provider/parse-openai-responses-response response-body true)
               nil
               (catch Exception e
                 e))]
      (is (instance? Exception ex) "expected incomplete response to throw")
      (is (re-find #"incomplete response" (ex-message ex)))
      (is (= :missing-tool-call (:type (ex-data ex))))
      (is (= :openai-tc (:provider (ex-data ex))))
      (is (= "incomplete" (:status (ex-data ex))))
      (is (= "max_output_tokens"
             (get-in (ex-data ex) [:incomplete_details :reason])))))

  (testing "throws on error response"
    (let [response-body (json/write-str {:error {:message "invalid api key"
                                                  :type "invalid_request_error"}})]
      (is (thrown-with-msg? Exception #"OpenAI Responses API error"
            (#'provider/parse-openai-responses-response response-body))))))

(deftest fireworks-provider-construction-and-helpers-test
  (testing "fireworks-provider defaults and expands short model ids"
    (let [p (provider/fireworks-provider {:api-key "fw-test"})]
      (is (instance? spell.provider.FireworksProvider p))
      (is (= "https://api.fireworks.ai/inference/v1" (:base-url p)))
      (is (= "accounts/fireworks/models/glm-5" (:model p)))
      (is (:convert-think? p))))

  (testing "fireworks-provider strips trailing slash and preserves full account model ids"
    (let [p (provider/fireworks-provider {:api-key "fw-test"
                                          :base-url "https://api.fireworks.ai/inference/v1/"
                                          :model "accounts/fireworks/models/deepseek-v3p1"})]
      (is (= "https://api.fireworks.ai/inference/v1" (:base-url p)))
      (is (= "accounts/fireworks/models/deepseek-v3p1" (:model p)))))

  (testing "format-completions-prompt leaves assistant prefix open"
    (is (= "<|im_start|>system\nsys<|im_end|>\n<|im_start|>user\nuser<|im_end|>\n<|im_start|>assistant\n(prefill"
           (#'provider/format-completions-prompt
            (:chatml provider/fireworks-chat-templates)
            "sys"
            "user"
            "(prefill"))))

  (testing "convert-think-tags rewrites a leading think block into Spell"
    (is (= "(think \"reasoning\\nnext\") (def x 1)"
           (#'provider/convert-think-tags "<think>reasoning\nnext</think>\n(def x 1)")))
    (is (= "(def x 1)"
           (#'provider/convert-think-tags "(def x 1)"))))

  (testing "parse-fireworks-sse-stream accumulates chunks and extracts usage"
    (let [response-body (str "data: " (json/write-str {:choices [{:text "<think>plan"}]}) "\n\n"
                             "data: " (json/write-str {:choices [{:text "</think>\n(def x 1)"}]
                                                       :usage {:prompt_tokens 120
                                                               :completion_tokens 25
                                                               :prompt_tokens_details {:cached_tokens 20}}}) "\n\n"
                             "data: [DONE]\n\n")
          result (#'provider/parse-fireworks-sse-stream response-body)]
      (is (= "<think>plan</think>\n(def x 1)" (:text result)))
      (is (= 100 (get-in result [:usage :input_tokens])))
      (is (= 25 (get-in result [:usage :output_tokens])))
      (is (= 20 (get-in result [:usage :cache_read_input_tokens]))))))

(deftest anthropic-tc-provider-constructor-test
  (testing "constructs with explicit api-key and model"
    (let [p (provider/anthropic-tc-provider {:api-key "anthropic-key"
                                              :model "claude-sonnet-4-5-20250929"})]
      (is (instance? spell.provider.AnthropicTcProvider p))
      (is (= "anthropic-key" (:api-key p)))
      (is (= "claude-sonnet-4-5-20250929" (:model p)))))

  (testing "uses a default model when omitted"
    (let [p (provider/anthropic-tc-provider {:api-key "anthropic-key"})]
      (is (some? (:model p))))))

(deftest anthropic-tc-parse-test
  (testing "parses completed response with spell_suffix tool_use"
    (let [body (json/write-str {:content [{:type "tool_use"
                                           :name "spell_suffix"
                                           :input {:suffix "(def x 1)"}}]
                                :usage {:input_tokens 11
                                        :output_tokens 5
                                        :cache_creation_input_tokens 7
                                        :cache_read_input_tokens 3}})
          result (#'provider/parse-anthropic-tc-response body)]
      (is (= "(def x 1)" (:text result)))
      (is (= 11 (get-in result [:usage :input_tokens])))
      (is (= 5 (get-in result [:usage :output_tokens])))
      (is (= 7 (get-in result [:usage :cache_creation_input_tokens])))
      (is (= 3 (get-in result [:usage :cache_read_input_tokens])))))

  (testing "throws when tool_use is missing"
    (let [body (json/write-str {:content [{:type "text" :text "no tool call"}]
                                :usage {:input_tokens 1 :output_tokens 1}})]
      (is (thrown-with-msg? Exception #"missing spell_suffix tool_use"
            (#'provider/parse-anthropic-tc-response body)))))

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
          result (#'provider/parse-anthropic-tc-stream sse)]
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
            (#'provider/parse-anthropic-tc-stream sse))))))

(deftest codex-msg-provider-constructor-test
  (testing "constructs with explicit token override"
    (let [p (provider/codex-msg-provider {:api-key "chatgpt-token"
                                          :account-id "acc_123"})]
      (is (instance? spell.provider.CodexMsgProvider p))
      (is (= "chatgpt-token" (:api-key p)))
      (is (= "acc_123" (:account-id p)))
      (is (some? (:base-url p)))
      (is (some? (:model p)))))

  (testing "loads token and account id from auth file"
    (let [tmp (java.io.File/createTempFile "codex-msg-auth-" ".json")]
      (try
        (spit tmp (json/write-str {:tokens {:access_token "from-file-token"
                                            :account_id "from-file-account"}}))
        (let [p (provider/codex-msg-provider {:auth-file (.getAbsolutePath tmp)})]
          (is (= "from-file-token" (:api-key p)))
          (is (= "from-file-account" (:account-id p))))
        (finally
          (.delete tmp)))))

  (testing "strips trailing slash from base-url"
    (let [p (provider/codex-msg-provider {:api-key "chatgpt-token"
                                          :base-url "https://chatgpt.com/backend-api/codex/"})]
      (is (= "https://chatgpt.com/backend-api/codex" (:base-url p))))))

(deftest codex-tc-provider-constructor-test
  (testing "constructs with explicit token override"
    (let [p (provider/codex-tc-provider {:api-key "chatgpt-token"
                                          :account-id "acc_123"})]
      (is (instance? spell.provider.CodexTcProvider p))
      (is (= "chatgpt-token" (:api-key p)))
      (is (= "acc_123" (:account-id p)))
      (is (some? (:base-url p)))
      (is (some? (:model p)))))

  (testing "loads token and account id from auth file"
    (let [tmp (java.io.File/createTempFile "codex-tc-auth-" ".json")]
      (try
        (spit tmp (json/write-str {:tokens {:access_token "from-file-token"
                                            :account_id "from-file-account"}}))
        (let [p (provider/codex-tc-provider {:auth-file (.getAbsolutePath tmp)})]
          (is (= "from-file-token" (:api-key p)))
          (is (= "from-file-account" (:account-id p))))
        (finally
          (.delete tmp)))))

  (testing "strips trailing slash from base-url"
    (let [p (provider/codex-tc-provider {:api-key "chatgpt-token"
                                          :base-url "https://chatgpt.com/backend-api/codex/"})]
      (is (= "https://chatgpt.com/backend-api/codex" (:base-url p))))))

(deftest codex-tc-prompt-cache-key-test
  (testing "generates a stable prompt cache key per provider instance"
    (let [p (provider/codex-tc-provider {:api-key "chatgpt-token"})]
      (is (string? (:prompt-cache-key p)))
      (is (not (str/blank? (:prompt-cache-key p))))))

  (testing "threads the cache key into codex-tc calls when cache-prefix is present"
    (let [p (provider/codex-tc-provider {:api-key "chatgpt-token"})
          captured (atom nil)]
      (with-redefs-fn {#'provider/codex-tc-request (fn [& args]
                                                     (reset! captured args)
                                                     (throw (ex-info "stop" {})))}
        #(is (thrown? clojure.lang.ExceptionInfo
              (provider/call-llm p "prompt" {:cache-prefix "previous prompt"
                                             :system "system"}))))
      (is (= (:prompt-cache-key p)
             (nth @captured 6))))))

(deftest codex-tc-request-body-test
  (testing "includes prompt_cache_key when provided"
      (let [body (#'provider/codex-tc-request-body "gpt-5.3-codex"
                                                 "prompt"
                                                 "system"
                                                 "cache-key"
                                                 nil
                                                 nil
                                                 nil)]
      (is (= "gpt-5.3-codex" (:model body)))
      (is (= "cache-key" (:prompt_cache_key body)))
      (is (str/starts-with? (:instructions body) "system"))))

  (testing "omits prompt_cache_key when not provided"
    (let [body (#'provider/codex-tc-request-body "gpt-5.3-codex"
                                                 "prompt"
                                                 "system"
                                                 nil
                                                 nil
                                                 nil
                                                 nil)]
      (is (nil? (:prompt_cache_key body))))))

(deftest codex-msg-stream-parse-test
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
          result (#'provider/parse-codex-msg-stream sse)]
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
          result (#'provider/parse-codex-msg-stream sse)]
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
            (#'provider/parse-codex-msg-stream sse))))))

(deftest codex-tc-stream-parse-test
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
          result (#'provider/parse-codex-tc-stream sse)]
      (is (= "(def x 1)" (:text result)))
      (is (= 9 (get-in result [:usage :input_tokens])))
      (is (= 3 (get-in result [:usage :output_tokens])))))

  (testing "throws when no custom_tool_call is present"
    (let [sse (str "event: response.completed\n"
                   "data: "
                   (json/write-str {:type "response.completed"
                                    :response {:output [{:type "message"
                                                         :role "assistant"
                                                         :content [{:type "output_text" :text "OK"}]}]
                                               :usage {:input_tokens 10
                                                       :output_tokens 4}}})
                   "\n\n")]
      (is (thrown-with-msg? Exception #"missing custom_tool_call"
            (#'provider/parse-codex-tc-stream sse)))))

  (testing "throws on response.failed"
    (let [sse (str "event: response.failed\n"
                   "data: "
                   (json/write-str {:type "response.failed"
                                    :response {:error {:message "bad auth"}}})
                   "\n\n")]
      (is (thrown-with-msg? Exception #"ChatGPT Codex Responses API error"
            (#'provider/parse-codex-tc-stream sse))))))

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

  (testing "codex-msg provider prefix"
    (is (= {:provider "codex-msg" :model "gpt-5.3-codex"}
           (cli/parse-model-spec "codex-msg:gpt-5.3-codex"))))

  (testing "codex-tc provider prefix"
    (is (= {:provider "codex-tc" :model "gpt-5.3-codex"}
           (cli/parse-model-spec "codex-tc:gpt-5.3-codex"))))

  (testing "openai provider prefix"
    (is (= {:provider "openai" :model "gpt-4o"}
           (cli/parse-model-spec "openai:gpt-4o"))))

  (testing "openai-tc provider prefix"
    (is (= {:provider "openai-tc" :model "gpt-5.4"}
           (cli/parse-model-spec "openai-tc:gpt-5.4"))))

  (testing "fireworks provider prefix"
    (is (= {:provider "fireworks" :model "glm-5"}
           (cli/parse-model-spec "fireworks:glm-5"))))

  (testing "anthropic-tc provider prefix"
    (is (= {:provider "anthropic-tc" :model "claude-sonnet-4-5-20250929"}
           (cli/parse-model-spec "anthropic-tc:claude-sonnet-4-5-20250929"))))

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
        ;; Unknown model — track-usage! records nil cost, so the budget check stays quiet
        (provider/track-usage! "unknown-model-xyz"
                          {:input_tokens 99999999 :output_tokens 99999999})
        (is (= 1 (get-in @usage-atom [:by-model "unknown-model-xyz" :calls])))
        (is (contains? (get-in @usage-atom [:by-model "unknown-model-xyz"]) :cost))
        (is (nil? (get-in @usage-atom [:by-model "unknown-model-xyz" :cost])))))))

;; =============================================================================
;; Error recovery tests
;; =============================================================================

(deftest compile-agent-with-recovery-test
  (testing "custom recovery function is called on error"
    (let [recovery-called (atom false)
          recovery-fn (fn [result]
                        (reset! recovery-called true)
                        ;; Return a fixed expression
                        '(def return 42))
          llm (th/make-test-runner
               {:response "undefined-symbol)"}
               :namespaces {} :recover recovery-fn)]
      (let [result (llm "(do ")]
        (is @recovery-called)
        (is (= 42 result)))))

  (testing "quine-extension recovery re-enters through the recovery quine"
    ;; Program is a quine with an error. Quine-extension recovery appends
    ;; a rethink marker plus a new arg with error info, then reopens the
    ;; recovery quine. The second LLM call provides the fix.
    (let [call-count (atom 0)
          llm (th/make-test-runner
               {:response-fn (fn [_]
                               (let [n (swap! call-count inc)]
                                 (if (= n 1)
                                   "undefined-symbol) '(!extend completion))"
                                   "(def fix 42))")))
                :prefill? true}
               :namespaces {})]
      ;; Use quine prefix so recovery can append
      (let [result (llm "(quine completion (eval (do ")]
        (is (= 2 @call-count))
        (is (= 42 result)))))

  (testing "quine-extension recovery injects rethink and reopens without _previous_program"
    (let [prompts (atom [])]
      (let [llm (th/make-test-runner
                 {:response-fn (fn [prompt]
                                 (swap! prompts conj prompt)
                                 (if (= 1 (count @prompts))
                                   "undefined-symbol) '(!extend completion))"
                                   "(def fix 42))"))}
                 :namespaces {} :prefill? false)]
        (let [result (llm "(quine completion (eval (do ")
              recovery-prompt (second @prompts)]
          (is (= 42 result))
          (is (str/includes? recovery-prompt "(rethink \"Error recovery - see _error for details.\")"))
          (is (str/includes? recovery-prompt "!llm-self (reopen completion)"))
          (is (not (str/includes? recovery-prompt "_previous_program")))))))

  (testing "shared recovery limit stops eval-only runaway loops"
    (let [call-count (atom 0)]
      (let [llm (th/make-test-runner
                 {:response-fn (fn [_]
                                 (swap! call-count inc)
                                 "undefined-symbol)")}
                 :namespaces {})]
        (let [invoke #(llm "(quine completion (eval (do ")]
          (is (thrown-with-msg? Exception #"Recovery limit exceeded: 2 while handling eval error"
                                (invoke))))
        ;; Initial call + 2 eval recovery retries
        (is (= 3 @call-count)))))

  (testing "no recovery when explicitly disabled"
    (let [llm (th/make-test-runner {:response "undefined-symbol)"}
                                   :namespaces {} :recover false)]
      (is (thrown? Exception (llm "(do ")))))

  (testing "non-quine program propagates error (no quine-extension)"
    ;; Plain (do ...) program can't use quine-extension recovery
    (let [llm (th/make-test-runner {:response "undefined-symbol)"}
                                   :namespaces {} :recover true)]
      (is (thrown? Exception (llm "(do "))))))

(deftest reader-error-recovery-test
  (testing "reader error recovery via fresh quine — LLM retries successfully"
    ;; Response #1 has \invalidchar which is an unsupported character literal.
    ;; Reader recovery embeds the raw text in a recovery quine and extends.
    ;; Response #2 (via extend) provides a valid fix.
    (let [call-count (atom 0)
          llm (th/make-test-runner
               {:response-fn (fn [_]
                               (let [n (swap! call-count inc)]
                                 (if (= n 1)
                                   "\\invalidchar)"
                                   "42)")))
                :prefill? true}
               :namespaces {})]
      (let [result (llm "(quine completion (eval (do ")]
        (is (= 2 @call-count))
        (is (= 42 result)))))

  (testing "reader error recovery disabled — throws immediately"
    (let [llm (th/make-test-runner {:response "\\invalidchar)"}
                                   :namespaces {} :recover false)]
      (is (thrown? Exception (llm "(quine completion (eval (do ")))))

  (testing "reader error recovery passes raw text in _error"
    ;; Verify the LLM sees the broken raw text in the recovery prompt.
    ;; The second call's prompt should contain the original raw text.
    ;; Uses prefill? false so the recovery prefix is the prompt arg (not in :prefix opt).
    (let [prompts (atom [])
          llm (th/make-test-runner
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
        (is (str/includes? recovery-prompt "The previous Spell program threw an error."))
        (is (str/includes? recovery-prompt "Reader error"))
        (is (str/includes? recovery-prompt "\\invalidchar")))))

  (testing "shared recovery limit stops parse-only runaway loops"
    (let [call-count (atom 0)
          llm (th/make-test-runner
               {:response-fn (fn [_]
                               (swap! call-count inc)
                               "\\invalidchar)")}
               :namespaces {})]
      (is (thrown-with-msg? Exception #"Recovery limit exceeded: 2 while handling reader error"
                            (llm "(quine completion (eval (do ")))
      ;; Initial call + 2 recovery retries
      (is (= 3 @call-count))))

  (testing "shared recovery limit applies across reader and eval recovery"
    (let [call-count (atom 0)
          llm (th/make-test-runner
               {:response-fn (fn [_]
                               (case (swap! call-count inc)
                                 1 "\\invalidchar)"
                                 2 "undefined-symbol)"
                                 3 "undefined-symbol)"))}
               :namespaces {})]
      (is (thrown-with-msg? Exception #"Recovery limit exceeded: 2 while handling eval error"
                            (llm "(quine completion (eval (do ")))
      ;; Initial parse error + one reader recovery retry + one eval recovery retry
      (is (= 3 @call-count)))))

(deftest namespace-recovery-invoke-fn-wrapping-test
  (testing "ns-recover handles 'Function call failed: Unbound symbol: X' from invoke-fn"
    ;; When an unbound symbol occurs inside a function passed to map/reduce/filter,
    ;; invoke-fn wraps the error: "Function call failed: Unbound symbol: X".
    ;; ns-recover must unwrap this to find and fix the symbol.
    (let [math-ns {:floor (fn [x] (long (Math/floor (double x))))
                   :long long}
          llm (th/make-test-runner
               {:response "(reduce + 0 (map (fn [x] (floor (/ x 2.0))) (list 10 20 30))))"
                :prefill? true}
               :namespaces {'math math-ns} :recover true)]
      (let [result (llm "(do ")]
        ;; ns-recover should fix floor -> math/floor
        (is (= 30 result)))))

  (testing "ns-recover fixes bare symbol to core namespace qualified form"
    ;; sqrt is in core namespace math/ but not in core-builtins
    ;; ns-recover should fix bare sqrt -> math/sqrt
    (let [llm (th/make-test-runner
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
          llm (th/make-test-runner
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
          llm (th/make-test-runner
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
          llm (th/make-test-runner
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
          llm (th/make-test-runner
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
                 (plain-text-provider [this] this)
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (if (= 1 @call-count)
                     (throw (ex-info "Server error" {:status 500}))
                     "success")))]
      (binding [provider/*retries* [0]]
        (is (= "success" (provider/call-with-retries
                           (fn [_err] (provider/call-llm prov "test" {}))
                           [0])))
        (is (= 2 @call-count)))))

  (testing "non-retryable error throws immediately"
    (let [call-count (atom 0)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (throw (ex-info "Bad request" {:status 400}))))]
      (is (thrown-with-msg? Exception #"Bad request"
                            (provider/call-with-retries
                              (fn [_err] (provider/call-llm prov "test" {}))
                              [0 0])))
      (is (= 1 @call-count))))

  (testing "exhausts all retries then throws"
    (let [call-count (atom 0)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (throw (ex-info "Rate limited" {:status 429}))))]
      (is (thrown-with-msg? Exception #"Rate limited"
                            (provider/call-with-retries
                              (fn [_err] (provider/call-llm prov "test" {}))
                              [0 0])))
      ;; 1 initial + 2 retries = 3 calls
      (is (= 3 @call-count))))

  (testing "no retries when retries-seq is nil"
    (let [call-count (atom 0)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! call-count inc)
                   (throw (ex-info "Server error" {:status 500}))))]
      (is (thrown-with-msg? Exception #"Server error"
                            (provider/call-with-retries
                              (fn [_err] (provider/call-llm prov "test" {}))
                              nil)))
      (is (= 1 @call-count)))))

;; =============================================================================
;; Missing tool-call retry (#144)
;; =============================================================================

(deftest retryable-missing-tool-call
  (testing "retryable? returns true for :missing-tool-call"
    (is (provider/retryable?
          (ex-info "missing tool call" {:type :missing-tool-call}))))

  (testing "retryable? still returns true for 429 and 5xx"
    (is (provider/retryable? (ex-info "rate limit" {:status 429})))
    (is (provider/retryable? (ex-info "server error" {:status 500}))))

  (testing "retryable? returns false for other errors"
    (is (not (provider/retryable? (ex-info "bad request" {:status 400}))))
    (is (not (provider/retryable? (ex-info "generic" {}))))))

(deftest call-with-retries-passes-last-error
  (testing "f receives nil on first call and the exception on retry"
    (let [received-errs (atom [])
          call-count (atom 0)
          missing-ex (ex-info "missing tool call" {:type :missing-tool-call})]
      (provider/call-with-retries
        (fn [err]
          (swap! received-errs conj err)
          (swap! call-count inc)
          (if (= 1 @call-count)
            (throw missing-ex)
            "success"))
        [0])
      (is (= 2 @call-count))
      (is (nil? (first @received-errs)) "first call should receive nil")
      (is (= missing-ex (second @received-errs)) "retry should receive the previous exception"))))

(deftest missing-tool-call-retry-hint-in-compile-agent
  (testing "retry hint is appended to user message on missing-tool-call retry"
    (let [call-count (atom 0)
          received-prompts (atom [])
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! received-prompts conj prompt)
                   (swap! call-count inc)
                   (if (= 1 @call-count)
                     (throw (ex-info "missing tool call"
                                     {:type :missing-tool-call :provider :anthropic-tc}))
                     "(def return 42))")))
          agent-fn (llm/compile-agent {:namespaces {}
                                       :provider prov
                                       :prefill? true
                                       :recover false})]
      (binding [provider/*retries* [0]]
        (is (= 42 (th/run-agent-prefix agent-fn "(do "))))
      (is (= 2 @call-count))
      ;; First call: plain user message (prefill mode = "Continue this Spell program.")
      (is (= "Continue this Spell program." (first @received-prompts)))
      ;; Second call: user message with retry hint appended
      (is (clojure.string/includes? (second @received-prompts)
                                     "retrying")))))

(deftest missing-tool-call-retry-hint-in-leaf-llm
  (testing "retry hint is appended in leaf-llm on missing-tool-call retry"
    (let [call-count (atom 0)
          received-prompts (atom [])
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (supports-prefill [_] false)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ prompt opts]
                   (swap! received-prompts conj prompt)
                   (swap! call-count inc)
                   (if (= 1 @call-count)
                     (throw (ex-info "missing tool call"
                                     {:type :missing-tool-call :provider :codex-tc}))
                     "hello world")))]
      (let [leaf (llm/make-leaf-llm {:provider prov})]
        (binding [provider/*retries* [0]]
          (is (= "hello world" (leaf "hi")))))
      (is (= 2 @call-count))
      ;; First call: plain prompt
      (is (= "hi" (first @received-prompts)))
      ;; Second call: prompt with retry hint
      (is (clojure.string/includes? (second @received-prompts)
                                     "retrying"))))

  (testing "leaf-llm uses the provider's plain-text sibling instead of the parent transport"
    (let [main-calls (atom 0)
          leaf-calls (atom 0)
          leaf-provider (reify provider/LLMProvider
                          (plain-text-provider [this] this)
                          (supports-prefill [_] false)
                          (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                          (call-llm [_ prompt _opts]
                            (swap! leaf-calls inc)
                            (str "leaf:" prompt)))
          prov (reify provider/LLMProvider
                 (plain-text-provider [_] leaf-provider)
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ _prompt _opts]
                   (swap! main-calls inc)
                   (throw (ex-info "parent transport should not handle leaf calls" {}))))]
      (is (= "leaf:hello" ((llm/make-leaf-llm {:provider prov}) "hello")))
      (is (= 0 @main-calls))
      (is (= 1 @leaf-calls))))

  (testing "compile-agent rejects providers without a plain-text leaf transport"
    (let [prov (reify provider/LLMProvider
                 (plain-text-provider [_]
                   (throw (ex-info "no plain-text leaf transport"
                                   {:type :leaf-llm-plain-text-unsupported
                                    :provider :fake-tc})))
                 (supports-prefill [_] true)
                 (call-llm [_ prompt] (provider/call-llm _ prompt {}))
                 (call-llm [_ _prompt _opts] "(def return 1))"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"plain-text leaf transport"
                            (llm/compile-agent {:namespaces {}
                                                :provider prov
                                                :recover false}))))))

;; =============================================================================
;; Kimi provider (#46)
;; =============================================================================

(deftest kimi-provider-construction
  (testing "kimi-provider requires API key"
    (is (thrown-with-msg? Exception #"MOONSHOT_API_KEY"
                          (provider/kimi-provider {:api-key nil}))))

  (testing "kimi-provider defaults"
    (let [p (provider/kimi-provider {:api-key "test-key"})]
      (is (some? (:model p)))
      (is (some? (:base-url p)))
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

)

;; =============================================================================
;; Fireworks provider
;; =============================================================================

(deftest fireworks-provider-construction
  (testing "fireworks-provider custom opts"
    (let [p (provider/fireworks-provider {:api-key "fw"
                                          :base-url "https://api.fireworks.ai/inference/v1/"
                                          :model "deepseek-v3p1"
                                          :max-tokens 8192
                                          :chat-template :deepseek-v3
                                          :convert-think? false})]
      (is (= "accounts/fireworks/models/deepseek-v3p1" (:model p)))
      (is (= "https://api.fireworks.ai/inference/v1" (:base-url p)))
      (is (= 8192 (:max-tokens p)))
      (is (= :deepseek-v3 (:chat-template p)))
      (is (false? (:convert-think? p))))))

;; =============================================================================
;; supports-prefill protocol tests
;; =============================================================================

(deftest supports-prefill-test
  (testing "Anthropic tc provider does not support prefill"
    (let [p (provider/anthropic-tc-provider {:api-key "test"})]
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
      (is (true? (provider/supports-prefill p)))))

  (testing "Fireworks provider supports prefill"
    (let [p (provider/fireworks-provider {:api-key "test"})]
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
  (testing "compile-agent with prefill?=false strips prefix echo"
    (let [llm (th/make-test-runner {:response "(def x 42))" :prefill? false}
                                   :namespaces {} :prefill? false)]
      (is (= 42 (llm "(do ")))))

  (testing "compile-agent with prefill?=true (default) passes prefix normally"
    (let [llm (th/make-test-runner {:response "(def x 42))" :prefill? true}
                                   :namespaces {})]
      (is (= 42 (llm "(do "))))))

(deftest suffix-grammar-option-test
  (testing "compile-agent passes generated grammar-format when enabled"
    (let [seen-opts (atom nil)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (supports-prefill [_] true)
                 (call-llm [this prompt] (provider/call-llm this prompt {}))
                 (call-llm [_ _ opts]
                   (reset! seen-opts opts)
                   "(def x 7))"))
          agent-fn (llm/compile-agent {:provider prov
                                       :namespaces {}
                                       :suffix-grammar? true})]
      (is (= 7 (th/run-agent-prefix agent-fn "(do ")))
      (is (= "grammar" (get-in @seen-opts [:grammar-format :type])))
      (is (= "lark" (get-in @seen-opts [:grammar-format :syntax])))
      (is (string? (get-in @seen-opts [:grammar-format :definition])))))

  (testing "skips grammar-format when generated grammar exceeds max chars"
    (let [seen-opts (atom nil)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (supports-prefill [_] true)
                 (call-llm [this prompt] (provider/call-llm this prompt {}))
                 (call-llm [_ _ opts]
                   (reset! seen-opts opts)
                   "(def x 9))"))
          agent-fn (llm/compile-agent {:provider prov
                                       :namespaces {}
                                       :suffix-grammar? true
                                       :grammar-max-chars 10})]
      (is (= 9 (th/run-agent-prefix agent-fn "(do ")))
      (is (nil? (:grammar-format @seen-opts))))))

;; =============================================================================
;; Leaf-llm async via future/blocking (not spawn)
;; =============================================================================

(deftest leaf-llm-via-plet-test
  (testing "leaf-llm works in parallel via blocking/plet inside a future"
    (let [call-count (atom 0)
          llm (th/make-test-runner
               {:response-fn (fn [prompt]
                               (let [n (swap! call-count inc)]
                                 (cond
                                   (= n 1) "'(future (blocking/plet [a (leaf-llm \"p1\") b (leaf-llm \"p2\")] (cat a b)))"
                                   (str/includes? prompt "p1") "hello"
                                   (str/includes? prompt "p2") "world"
                                   :else "???")))})]
      (let [fut (llm "(eval (do ")]
        (is (:spell/future fut))
        (is (= "helloworld" (deref (:ref fut) 5000 :timeout)))))))

(deftest leaf-llm-via-future-await-test
  (testing "leaf-llm works with explicit future + blocking/await"
    (let [call-count (atom 0)
          llm (th/make-test-runner
               {:response-fn (fn [prompt]
                               (let [n (swap! call-count inc)]
                                 (cond
                                   (= n 1) "'(future (let [f1 (future (leaf-llm \"task-a\")) f2 (future (leaf-llm \"task-b\"))] (list (blocking/await f1) (blocking/await f2))))"
                                   (str/includes? prompt "task-a") "result-a"
                                   (str/includes? prompt "task-b") "result-b"
                                   :else "???")))})]
      (let [fut (llm "(eval (do ")]
        (is (:spell/future fut))
        (is (= ["result-a" "result-b"] (deref (:ref fut) 5000 :timeout)))))))
