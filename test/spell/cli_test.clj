(ns spell.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.api :as api]
            [spell.cli :as cli]
            [spell.provider :as provider]))

(deftest print-usage-context-stats-test
  (testing "verbose usage output includes mean/max total context"
    (let [usage-atom (atom {:by-model {"model-a" {:input_tokens 300
                                                  :output_tokens 120
                                                  :cache_creation_input_tokens 10
                                                  :cache_read_input_tokens 20
                                                  :calls 2
                                                  :mean_total_tokens 225
                                                  :max_total_tokens 260}
                                       "model-b" {:input_tokens 50
                                                  :output_tokens 10
                                                  :calls 1
                                                  :mean_total_tokens 60
                                                  :max_total_tokens 60}}
                            :total {:input_tokens 350
                                    :output_tokens 130
                                    :cache_creation_input_tokens 10
                                    :cache_read_input_tokens 20
                                    :calls 3
                                    :mean_total_tokens 170
                                    :max_total_tokens 260}})
          output (with-out-str ((var cli/print-usage) usage-atom))]
      (is (str/includes? output "[context: mean 225 / max 260]"))
      (is (str/includes? output "[context: mean 170 / max 260]")))))

(deftest validate-args-accepts-new-openai-reasoning-levels
  (testing "xhigh reasoning effort is accepted"
    (let [result (cli/validate-args ["--reasoning-effort" "xhigh" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "xhigh" (get-in result [:options :reasoning-effort])))))

  (testing "none reasoning effort is accepted"
    (let [result (cli/validate-args ["--reasoning-effort" "none" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "none" (get-in result [:options :reasoning-effort]))))))

(deftest trace-dir-option-test
  (testing "--trace-dir enables tracing at the requested path"
    (let [result (cli/validate-args ["--trace-dir" "notebook/results/run-1/trace"
                                     "Return 42"])]
      (is (= "notebook/results/run-1/trace"
             (get-in result [:options :trace-dir])))))

  (testing "an explicit trace directory takes precedence over -T"
    (with-redefs [api/run-internal identity]
      (let [result (cli/run-input {:prompt "Return 42"}
                                  {:test true
                                   :trace true
                                   :trace-dir "durable/trace"}
                                  (atom {:by-model {}}))]
        (is (= "durable/trace" (:trace-dir result)))))))

(deftest agents-md-option-test
  (testing "--agents-md prepends cwd instructions to a natural-language prompt"
    (let [result (with-redefs-fn
                   {#'spell.cli/load-cwd-agents-md
                    (constantly {:path "/repo/AGENTS.md"
                                 :text "Keep changes scoped."
                                 :truncated? false})}
                   #(cli/validate-args ["--agents-md" "Implement the feature"]))]
      (is (str/includes? (:prompt result) "Project instructions from /repo/AGENTS.md"))
      (is (str/includes? (:prompt result) "<agents_md>\nKeep changes scoped.\n</agents_md>"))
      (is (str/ends-with? (:prompt result) "Task:\nImplement the feature"))))

  (testing "--agents-md is rejected for direct Spell programs"
    (let [result (cli/validate-args ["--agents-md" "--init" "(do 42)"])]
      (is (false? (:ok? result)))
      (is (str/includes? (:exit-message result) "requires a natural-language prompt"))))

  (testing "AGENTS.md truncation preserves valid UTF-8 within the 32 KiB cap"
    (let [tmp (java.io.File/createTempFile "spell-agents-md-" ".md")]
      (try
        (spit tmp (str (apply str (repeat 32767 "a")) "界tail"))
        (let [{:keys [text truncated?]} (#'spell.cli/read-agents-md-file tmp)]
          (is truncated?)
          (is (= 32767
                 (alength (.getBytes text java.nio.charset.StandardCharsets/UTF_8))))
          (is (not (str/includes? text "界"))))
        (finally
          (.delete tmp))))))

(deftest validate-args-supports-direct-init-programs
  (testing "--init passes a complete Spell program instead of a prompt"
    (let [result (cli/validate-args ["--init" "(do 42)"])]
      (is (= "(do 42)" (:init result)))
      (is (nil? (:prompt result)))))

  (testing "--init-file loads a complete Spell program"
    (let [tmp (java.io.File/createTempFile "spell-cli-init-" ".spl")]
      (try
        (spit tmp "(do (+ 20 22))")
        (let [result (cli/validate-args ["--init-file" (.getAbsolutePath tmp)])]
          (is (= "(do (+ 20 22))" (:init result)))
          (is (nil? (:prompt result))))
        (finally
          (.delete tmp)))))

  (testing "prompt/file modes remain distinct from init modes"
    (is (false? (:ok? (cli/validate-args ["--init" "(do 42)" "Return 42"]))))
    (is (false? (:ok? (cli/validate-args ["--init-file" "program.spl" "Return 42"]))))
    (is (false? (:ok? (cli/validate-args ["--init" "(do 42)" "--init-file" "program.spl"]))))
    (is (false? (:ok? (cli/validate-args ["--example" "hello-world" "--init" "(do 42)"]))))))

(deftest dogfood-flag-test
  (testing "--dogfood is parsed as an explicit run-level gate"
    (let [result (cli/validate-args ["--dogfood" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (true? (get-in result [:options :dogfood])))))

  (testing "dogfood adds feedback to the selected profile without changing the profile path"
    (let [seen-opts (atom nil)
          profile "config/agent-profiles/base-msg.agent.edn"]
      (with-redefs [api/run-internal (fn [opts]
                                      (reset! seen-opts opts)
                                      {:result :ok})]
        (is (= :ok (:result (cli/run-input {:init "(do 42)"}
                                            {:test true
                                             :dogfood true
                                             :agent-profile profile}
                                            (atom {:by-model {}})))))
        (is (= profile (:agent-profile @seen-opts)))
        (is (= {'feedback 'stdlib/feedback}
               (:agent-namespace-overrides @seen-opts))))))

  (testing "ordinary runs do not receive the feedback override"
    (let [seen-opts (atom nil)]
      (with-redefs [api/run-internal (fn [opts]
                                      (reset! seen-opts opts)
                                      {:result :ok})]
        (cli/run-input {:init "(do 42)"} {:test true} (atom {:by-model {}}))
        (is (not (contains? @seen-opts :agent-namespace-overrides)))))))

(deftest run-input-evaluates-direct-init-without-llm-call
  (let [result (cli/run-input {:init "(do (+ 20 22))"} {:test true} (atom {:by-model {}}))]
    (is (= 42 (:result result))))

  (testing "atom and literal init programs are not treated as prompts"
    (doseq [[program expected] [["42" 42]
                                ["\"hello\"" "hello"]]]
      (let [result (cli/run-input {:init program} {:test true} (atom {:by-model {}}))]
        (is (= expected (:result result)))))))

(deftest make-provider-resolves-shared-model-aliases-test
  (testing "the CLI default uses GPT-6 Astra through OpenAI tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/openai-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :openai-tc :opts opts})]
        (is (= :openai-tc
               (:provider ((var cli/make-provider) {}))))
        (is (= "gpt-6-astra" (:model @captured)))
        (is (:use-responses-api @captured))
        (is (:force-tool-call @captured)))))

  (testing "bare gpt alias routes to OpenAI tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/openai-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :openai-tc :opts opts})]
        (is (= :openai-tc
               (:provider ((var cli/make-provider) {:model "gpt"}))))
        (is (= "gpt-6-astra" (:model @captured)))
        (is (:use-responses-api @captured))
        (is (:force-tool-call @captured)))))

  (testing "bare opus alias routes to Anthropic tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/anthropic-tc-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :anthropic-tc :opts opts})]
        (is (= :anthropic-tc
               (:provider ((var cli/make-provider) {:model "opus"}))))
        (is (= "claude-opus-4-8" (:model @captured))))))

  (testing "Fable aliases route to the expected Anthropic tool-call models"
    (doseq [[alias expected] [["fable" "claude-fable-5-1"]
                              ["fable51" "claude-fable-5-1"]
                              ["fable5" "claude-fable-5"]]]
      (let [captured (atom nil)]
        (with-redefs [provider/anthropic-tc-provider
                      (fn [opts]
                        (reset! captured opts)
                        {:provider :anthropic-tc :opts opts})]
          (is (= :anthropic-tc
                 (:provider ((var cli/make-provider) {:model alias}))))
          (is (= expected (:model @captured)))))))

  (testing "bare open-weight aliases route to Fireworks tool-call transport"
    (doseq [[alias expected] [["glm" "glm-5p2"]
                             ["kimi" "kimi-k2p7-code"]
                             ["qwen" "qwen3p7-plus"]
                             ["glm51" "glm-5p1"]
                             ["kimi26" "kimi-k2p6"]
                             ["qwen36p" "qwen3p6-plus"]]]
      (let [captured (atom nil)]
        (with-redefs [provider/fireworks-tc-provider
                      (fn [opts]
                        (reset! captured opts)
                        {:provider :fireworks-tc :opts opts})]
          (is (= :fireworks-tc
                 (:provider ((var cli/make-provider) {:model alias}))))
          (is (= expected (:model @captured))))))))

(deftest run-input-default-reasoning-effort-test
  (let [seen (atom nil)
        run! (fn [opts]
               (with-redefs [provider/openai-provider
                             (fn [_] (provider/test-provider {:response "unused"}))
                             api/run-internal
                             (fn [run-opts]
                               (reset! seen run-opts)
                               {:result 42})]
                 (cli/run-input {:prompt "Return 42"} opts (atom {:by-model {}}))))]
    (testing "the default model uses medium reasoning"
      (run! {})
      (is (= "medium" (:reasoning-effort @seen))))

    (testing "an explicit reasoning effort overrides the default"
      (run! {:reasoning-effort "high"})
      (is (= "high" (:reasoning-effort @seen))))

    (testing "explicit Astra specs and aliases retain medium reasoning"
      (doseq [model ["gpt" "astra" "gpt6" "gpt6astra"
                     "openai-tc:gpt-6-astra" "codex-tc:gpt-6-astra"]]
        (run! {:model model})
        (is (= "medium" (:reasoning-effort @seen)) model)))

    (testing "an explicit older model keeps its provider-specific reasoning default"
      (run! {:model "gpt55"})
      (is (nil? (:reasoning-effort @seen))))))

(deftest help-text-uses-public-provider-specs-and-curated-examples
  (let [{:keys [exit-message ok?]} (cli/validate-args ["--help"])]
    (is ok?)
    (is (str/includes? exit-message "default: openai-tc:gpt-6-astra"))
    (is (str/includes? exit-message "default: medium for the default model"))
    (is (str/includes? exit-message "token budget for extended thinking; adaptive for supported models"))
    (is (str/includes? exit-message "Reasoning effort for OpenAI and adaptive Anthropic models"))
    (is (str/includes? exit-message "codex-tc:<model>"))
    (is (str/includes? exit-message "openai-tc:gpt-6-astra"))
    (is (str/includes? exit-message "anthropic-tc:claude-opus-4-8"))
    (is (str/includes? exit-message "spell -m fable 'Use Claude Fable 5.1'"))
    (is (str/includes? exit-message "fireworks-tc:kimi-k2p7-code"))
    (is (str/includes? exit-message "--dogfood"))
    (is (str/includes? exit-message "--agents-md"))
    (is (str/includes? exit-message "spell -t --init '(do (+ 20 22))'"))
    (is (str/includes? exit-message "spell --init-file scratch/my-program.spl"))
    (is (str/includes? exit-message "--trace-dir DIR"))
    (doseq [example ["hello-world" "coin-flip" "twenty-questions"
                     "telephone" "auction" "chat"]]
      (is (str/includes? exit-message example)))
    (doseq [removed ["famous-greeting" "fix-bug" "comm-ask"
                     "globals-basic" "negotiate" "test-compact"]]
      (is (not (str/includes? exit-message removed))))))

(deftest reasoning-effort-accepts-gpt-5-6-max-test
  (testing "CLI accepts GPT-5.6's max reasoning effort"
    (let [result (cli/validate-args ["-m" "gpt56sol" "-R" "max" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "max" (get-in result [:options :reasoning-effort]))))))
