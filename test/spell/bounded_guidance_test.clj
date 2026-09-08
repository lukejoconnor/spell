(ns spell.bounded-guidance-test
  "Regression controls for the bounded-snapshot teaching surfaces."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def prompt-paths
  ["config/prompts/sysprompt-message.txt"
   "config/prompts/sysprompt-prefill.txt"
   "config/prompts/sysprompt-toolcall.txt"])

(defn context-block [text]
  (second (re-find #"(?s)CONTEXT MANAGEMENT\n(.*?)RECOMMENDED PATTERNS\n" text)))

(deftest synchronized-bounded-snapshot-prompt-guidance
  (let [blocks (mapv #(context-block (slurp %)) prompt-paths)]
    (is (every? seq blocks))
    (is (apply = blocks) "Transport variants share the same context policy")
    (doseq [block blocks
            phrase ["The next-turn binding IS that snapshot"
                    "Compute counts, reductions, and field selection inside the effect expression"
                    "PER OUTPUT" "120%" "{:max-chars N}"
                    "no automatic result store" "Exact lifecycle/request/ack metadata"
                    "ONE-BASED HALF-OPEN" "LOCAL ZERO-BASED HALF-OPEN"
                    ":char-start 900 :char-end 1800"
                    "(:out page)" ":truncated true"
                    "Never replay an effect merely to recover omitted output"]]
      (is (str/includes? block phrase) phrase))))

(deftest active-guidance-does-not-teach-removed-retrieval
  (doseq [path (concat prompt-paths
                      ["resources/skills/coding/SKILL.md"
                       "resources/skills/context-efficiency/SKILL.md"
                       "resources/skills/mailing-list/SKILL.md"
                       "docs/api.md" "docs/bounded-results.md"
                       "docs/error-recovery.md" "docs/multi-agent.md"
                       "src/spell/stdlib.clj"])]
    (let [text (slurp path)]
      (doseq [obsolete ["(stored " "deep-truncate" "name still holds the full value"
                        "multi-binding call shares one" "negative limit uses the run"]]
        (is (not (str/includes? text obsolete)) (str path ": " obsolete))))))

(deftest future-guidance-uses-explicit-existing-globals
  (let [text (slurp "docs/multi-agent.md")]
    (is (str/includes? text "(globals/set :task-future"))
    (is (str/includes? text "(!ask-await (globals/get :task-future))"))
    (is (not (str/includes? text "(!call-now task-future")))
    (is (not (str/includes? text "globals/put")))))
