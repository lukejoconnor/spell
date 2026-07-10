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
    (is (= {:provider "anthropic-tc" :model "claude-sonnet-5"}
           (model-spec/resolve-model-spec "sonnet")))
    (is (= {:provider "anthropic-tc" :model "claude-opus-4-8"}
           (model-spec/resolve-model-spec "opus")))
    (is (= {:provider "anthropic-tc" :model "claude-fable-5"}
           (model-spec/resolve-model-spec "fable5")))
    (is (= {:provider "openai-tc" :model "gpt-5.5"}
           (model-spec/resolve-model-spec "gpt")))
    (is (= {:provider "openai-tc" :model "gpt-5.5"}
           (model-spec/resolve-model-spec "gpt55")))
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
    (is (= {:provider "fireworks-tc" :model "qwen3p6-plus"}
           (model-spec/resolve-model-spec "qwen36p"))))

  (testing "explicit provider prefixes are preserved"
    (is (= {:provider "fireworks-tc" :model "glm-5p1"}
           (model-spec/resolve-model-spec "fireworks-tc:glm-5p1")))
    (is (= {:provider "anthropic-pf" :model "claude-opus-4-8"}
           (model-spec/resolve-model-spec "anthropic-pf:opus"))))

  (testing "codex-tc gpt-5.3 normalizes to Codex model id"
    (is (= {:provider "codex-tc" :model "gpt-5.3-codex"}
           (model-spec/resolve-model-spec "codex-tc:gpt53")))))
