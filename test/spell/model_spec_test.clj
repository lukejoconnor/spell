(ns spell.model-spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.model-spec :as model-spec]))

(deftest parse-model-spec-test
  (testing "bare models preserve nil provider"
    (is (= {:provider nil :model "haiku"}
           (model-spec/parse-model-spec "haiku")))
    (is (= {:provider nil :model "claude-sonnet-4-20250514"}
           (model-spec/parse-model-spec "claude-sonnet-4-20250514"))))

  (testing "provider prefixes split only on the first colon"
    (is (= {:provider "ollama" :model "smollm2:135m"}
           (model-spec/parse-model-spec "ollama:smollm2:135m")))
    (is (= {:provider "fireworks-tc" :model "kimi-k2p6"}
           (model-spec/parse-model-spec "fireworks-tc:kimi-k2p6"))))

  (testing "unknown provider prefixes throw"
    (is (thrown-with-msg? Exception #"Unknown provider prefix"
          (model-spec/parse-model-spec "custom:some-model")))))

(deftest resolve-model-spec-test
  (testing "model-only aliases preserve default provider"
    (is (= {:provider nil :model "claude-haiku-4-5-20251001"}
           (model-spec/resolve-model-spec "haiku")))
    (is (= {:provider nil :model "gpt-5.2"}
           (model-spec/resolve-model-spec "gpt52"))))

  (testing "full-spec aliases select provider and model"
    (is (= {:provider "anthropic-tc" :model "claude-sonnet-5-5"}
           (model-spec/resolve-model-spec "sonnet")))
    (is (= {:provider "anthropic-tc" :model "claude-opus-5-5"}
           (model-spec/resolve-model-spec "opus")))
    (is (= {:provider "anthropic-tc" :model "claude-fable-5-1"}
           (model-spec/resolve-model-spec "fable")))
    (is (= {:provider "anthropic-tc" :model "claude-fable-5-1"}
           (model-spec/resolve-model-spec "fable51")))
    (is (= {:provider "anthropic-tc" :model "claude-fable-5"}
           (model-spec/resolve-model-spec "fable5")))
    (is (= {:provider "openai-tc" :model "gpt-6-astra"}
           (model-spec/resolve-model-spec "gpt")))
    (doseq [alias ["astra" "gpt6" "gpt6astra"]]
      (is (= {:provider "openai-tc" :model "gpt-6-astra"}
             (model-spec/resolve-model-spec alias))))
    (is (= {:provider "openai-tc" :model "gpt-5.5"}
           (model-spec/resolve-model-spec "gpt55")))
    (is (= {:provider "openai-tc" :model "gpt-5.6-sol"}
           (model-spec/resolve-model-spec "gpt56sol")))
    (is (= {:provider "fireworks-tc" :model "glm-5p2"}
           (model-spec/resolve-model-spec "glm")))
    (is (= {:provider "fireworks-tc" :model "kimi-k2p7-code"}
           (model-spec/resolve-model-spec "kimi")))
    (is (= {:provider "fireworks-tc" :model "qwen3p7-plus"}
           (model-spec/resolve-model-spec "qwen")))
    (is (= {:provider "fireworks-tc" :model "glm-5p1"}
           (model-spec/resolve-model-spec "glm51")))
    (is (= {:provider "fireworks-tc" :model "kimi-k2p6"}
           (model-spec/resolve-model-spec "kimi26")))
    (is (= {:provider "fireworks-tc" :model "kimi-k3"}
           (model-spec/resolve-model-spec "kimi3")))
    (is (= {:provider "fireworks-tc" :model "kimi-k3"}
           (model-spec/resolve-model-spec "kimik3")))
    (is (= {:provider "fireworks-tc" :model "qwen3p6-plus"}
           (model-spec/resolve-model-spec "qwen36p"))))

  (testing "explicit provider prefixes are preserved"
    (is (= {:provider "fireworks-tc" :model "glm-5p1"}
           (model-spec/resolve-model-spec "fireworks-tc:glm-5p1")))
    (is (= {:provider "anthropic-pf" :model "claude-opus-5-5"}
           (model-spec/resolve-model-spec "anthropic-pf:opus"))))

  (testing "codex-tc gpt-5.3 normalizes to Codex model id"
    (is (= {:provider "codex-tc" :model "gpt-5.3-codex"}
           (model-spec/resolve-model-spec "codex-tc:gpt53")))))

(deftest current-model-aliases-and-comparators
  (doseq [[model aliases] {"gpt-6.1-sol" ["sol" "sol61" "gpt61sol"]
                          "gpt-6-sol" ["sol6" "gpt6sol"]
                          "gpt-6-luna" ["luna" "luna6" "gpt6luna"]}
          alias aliases]
    (is (= {:provider "openai-tc" :model model} (model-spec/resolve-model-spec alias)))
    (is (= {:provider "codex-tc" :model model}
           (model-spec/resolve-model-spec (str "codex-tc:" alias)))))
  (doseq [[alias model] {"sonnet55" "claude-sonnet-5-5" "opus55" "claude-opus-5-5"
                         "sonnet5" "claude-sonnet-5" "sonnet46" "claude-sonnet-4-6"
                         "opus48" "claude-opus-4-8" "opus46" "claude-opus-4-6"
                         "opus45" "claude-opus-4-5-20251101"}]
    (is (= {:provider "anthropic-tc" :model model} (model-spec/resolve-model-spec alias)))
    (is (= {:provider "anthropic-pf" :model model}
           (model-spec/resolve-model-spec (str "anthropic-pf:" alias))))))
