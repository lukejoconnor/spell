(ns spell.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest run-input-evaluates-direct-init-without-llm-call
  (let [result (cli/run-input {:init "(do (+ 20 22))"} {:test true} (atom {:by-model {}}))]
    (is (= 42 (:result result)))))

(deftest make-provider-resolves-shared-model-aliases-test
  (testing "bare gpt alias routes to OpenAI tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/openai-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :openai-tc :opts opts})]
        (is (= :openai-tc
               (:provider ((var cli/make-provider) {:model "gpt"}))))
        (is (= "gpt-5.4" (:model @captured)))
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
        (is (= "claude-opus-4-6" (:model @captured))))))

  (testing "bare open-weight aliases route to Fireworks tool-call transport"
    (doseq [[alias expected] [["glm51" "glm-5p1"]
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

(deftest help-text-uses-public-provider-specs-and-curated-examples
  (let [{:keys [exit-message ok?]} (cli/validate-args ["--help"])]
    (is ok?)
    (is (str/includes? exit-message "codex-tc:<model>"))
    (is (str/includes? exit-message "openai-tc:gpt-5.4"))
    (is (str/includes? exit-message "fireworks-tc:kimi-k2p6"))
    (is (str/includes? exit-message "spell -t --init '(do (+ 20 22))'"))
    (is (str/includes? exit-message "spell --init-file scratch/my-program.spl"))
    (doseq [example ["hello-world" "coin-flip" "twenty-questions"
                     "telephone" "auction" "chat"]]
      (is (str/includes? exit-message example)))
    (doseq [removed ["famous-greeting" "fix-bug" "comm-ask"
                     "globals-basic" "negotiate" "test-compact"]]
      (is (not (str/includes? exit-message removed))))))
