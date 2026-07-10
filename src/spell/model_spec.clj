(ns spell.model-spec
  "Shared model/provider spec parsing and alias resolution."
  (:require [clojure.string :as str]))

(def provider-prefixes
  #{"ollama" "codex-tc" "openai-tc"
    "anthropic-pf" "anthropic-tc" "fireworks" "fireworks-tc" "test"})

(def model-aliases
  {"haiku"    "claude-haiku-4-5-20251001"
   "sonnet"   "anthropic-tc:claude-sonnet-5"
   "sonnet5"  "anthropic-tc:claude-sonnet-5"
   "sonnet46" "anthropic-tc:claude-sonnet-4-6"
   "fable"    "anthropic-tc:claude-fable-5"
   "fable5"   "anthropic-tc:claude-fable-5"
   "opus"     "anthropic-tc:claude-opus-4-8"
   "opus48"   "anthropic-tc:claude-opus-4-8"
   "opus46"   "anthropic-tc:claude-opus-4-6"
   "opus45"   "anthropic-tc:claude-opus-4-5-20251101"
   "o3"       "o3"
   "o4-mini"  "o4-mini"
   "gpt"      "openai-tc:gpt-5.5"
   "gpt52"    "gpt-5.2"
   "gpt53"    "gpt-5.3"
   "gpt54"    "openai-tc:gpt-5.4"
   "gpt55"    "openai-tc:gpt-5.5"
   "gpt56sol" "openai-tc:gpt-5.6-sol"
   "glm"      "fireworks-tc:glm-5p2"
   "glm52"    "fireworks-tc:glm-5p2"
   "glm51"    "fireworks-tc:glm-5p1"
   "kimi"     "fireworks-tc:kimi-k2p7-code"
   "kimi27"   "fireworks-tc:kimi-k2p7-code"
   "kimi27code" "fireworks-tc:kimi-k2p7-code"
   "kimi26"   "fireworks-tc:kimi-k2p6"
   "qwen"     "fireworks-tc:qwen3p7-plus"
   "qwen37p"  "fireworks-tc:qwen3p7-plus"
   "qwen36p"  "fireworks-tc:qwen3p6-plus"})

(defn parse-model-spec
  "Parse 'provider:model' into {:provider str :model str}.
   If no colon, returns {:provider nil :model input}.
   Throws on unrecognized provider prefix."
  [s]
  (if-let [idx (str/index-of s ":")]
    (let [prefix (subs s 0 idx)
          rest (subs s (inc idx))]
      (if (contains? provider-prefixes prefix)
        {:provider prefix :model rest}
        (throw (ex-info (str "Unknown provider prefix: " (pr-str prefix)
                             ". Known prefixes: " (str/join ", " (sort provider-prefixes)))
                        {:prefix prefix :model-spec s}))))
    {:provider nil :model s}))

(defn resolve-model-alias [model]
  (get model-aliases model model))

(defn- normalize-codex-model [{:keys [provider model] :as spec}]
  (if (and (= "codex-tc" provider)
           (= "gpt-5.3" model))
    (assoc spec :model "gpt-5.3-codex")
    spec))

(defn resolve-model-spec
  "Resolve aliases and normalize a model/provider spec.

   Bare aliases may resolve either to a model name, preserving the default
   provider, or to a full provider-prefixed model spec. Explicit provider
   prefixes are preserved; only the model portion is alias-resolved."
  [s]
  (let [{:keys [provider model]} (parse-model-spec s)
        resolved (resolve-model-alias model)
        parsed (parse-model-spec resolved)
        spec (if provider
               {:provider provider :model (:model parsed)}
               parsed)]
    (normalize-codex-model spec)))
