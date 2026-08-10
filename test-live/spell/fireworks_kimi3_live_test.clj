(ns spell.fireworks-kimi3-live-test
  "Credential-gated live smoke for Kimi K3 on Fireworks' Anthropic-compatible
   Messages endpoint. This namespace is excluded from the offline test roots.

   Run explicitly with:
     SPELL_RUN_LIVE_TESTS=1 FIREWORKS_API_KEY=... clojure -M:test-live-fireworks-kimi3

   The provider's chat fallback is disabled so a pass proves that /v1/messages
   returned the mandatory spell_suffix tool call. The suffix and credential are
   never printed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.parse :as parse]
            [spell.provider :as provider]))

(defn- live-enabled? []
  (= "1" (System/getenv "SPELL_RUN_LIVE_TESTS")))

(deftest kimi3-fireworks-messages-spell-suffix-smoke
  (let [api-key (System/getenv "FIREWORKS_API_KEY")]
    (if-not (and (live-enabled?) (not (str/blank? api-key)))
      (is true "Skipped: requires SPELL_RUN_LIVE_TESTS=1 and FIREWORKS_API_KEY")
      (testing "Kimi K3 returns a usable raw suffix through mandatory spell_suffix"
        (let [prompt "(quine completion (eval (do (quine prompt \"Define answer as 42 and finish the program.\") "
              llm (provider/fireworks-tc-provider
                    {:api-key api-key
                     :model "kimi-k3"
                     :max-tokens 512
                     :chat-fallback? false
                     :request-timeout-sec 120})
              suffix (provider/call-llm llm prompt)]
          (is (string? suffix))
          (is (not (str/blank? suffix)))
          (is (seq (parse/read-all (str prompt suffix)))))))))
