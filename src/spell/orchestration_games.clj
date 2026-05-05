(ns spell.orchestration-games
  "CLI harness for small scored orchestration games.

  Each game has two variants:
  - :agents     — agent profile exposes the agents/ namespace; prompts ask
                  the orchestrator to spawn explicit subagents.
  - :llm-self   — agent profile exposes only !llm-self / leaf-llm; prompts
                  ask the orchestrator to delegate via self-calls.

  The harness intentionally stores raw responses and traces. Scores are
  derived from those saved artifacts, not from the agent's own success
  claims."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [spell.api :as api]
            [spell.parse :as parse]
            [spell.provider :as provider]))

(def default-models
  ["fireworks:glm-5p1"])

(def default-output-root
  "notebook/entries/orchestration-games")

(def model-labels
  {"openai-tc:gpt-5.4" "gpt54"
   "anthropic-tc:claude-opus-4-7" "opus47"
   "fireworks:glm-5p1" "glm51"
   "fireworks-tc:glm-5p1" "glm51"
   "fireworks-tc:kimi-k2p6" "kimi26"})

(def provider-edn-by-prefix
  {"anthropic-pf" "config/providers/anthropic-pf.provider.edn"
   "anthropic-tc" "config/providers/anthropic-tc.provider.edn"
   "codex-tc" "config/providers/codex-tc.provider.edn"
   "openai-tc" "config/providers/openai-tc.provider.edn"
   "fireworks" "config/providers/fireworks.provider.edn"
   "fireworks-tc" "config/providers/fireworks-tc.provider.edn"
   "ollama" "config/providers/ollama.provider.edn"})

;; -----------------------------------------------------------------------------
;; Prompts
;; -----------------------------------------------------------------------------
;;
;; Two variants per game. The :agents variant uses io-tc (agents/ + io/);
;; the :llm-self variant uses base-tc (no effect namespaces — only
;; !llm-self, !ask-await, leaf-llm builtins).

(def auction-agents-prompt
  (str
   "Run a sealed-bid auction for a painting using agents/. You are the "
   "orchestrator, not a bidder. Create three bidder agents with handles "
   ":bidder-a, :bidder-b, and :bidder-c. Use agents/!spawn-ask to spawn "
   "the children and wait through the normal message-wakeup path. Each child "
   "must choose one integer "
   "bid from 100 to 1000. Bind the bids, compare them, and return a "
   "short final report naming the winner and all three bids. Do not invent "
   "bids in the main agent; the bids must come from the children."))

(def auction-llm-self-prompt
  (str
   "Run a sealed-bid auction for a painting using !llm-self. You are the "
   "orchestrator, not a bidder. Make three independent !llm-self calls, one "
   "per bidder; each child call must return one integer bid from 100 to "
   "1000. Bind the three returned bids, compare them, and return a short "
   "final report naming the winner and all three bids. Do not invent bids "
   "in the main agent; the bids must be returned by the !llm-self calls."))

(def twenty-questions-agents-prompt
  (str
   "Play 20 questions using agents/. The secret animal is elephant, but the "
   ":worker child must not be told the secret. Spawn one worker agent with "
   "agents/!spawn-ask. The "
   "worker asks yes/no questions or makes guesses; you answer truthfully. "
   "Keep the transcript in bindings. Stop when the worker guesses elephant "
   "or after 8 guesses. The worker's questions and guesses must arrive from "
   "the worker agent, not from you."))

(def twenty-questions-llm-self-prompt
  (str
   "Play 20 questions using !llm-self. The secret animal is elephant, but "
   "the !llm-self worker calls must not be told the secret. Each round, call "
   "!llm-self with only the public transcript so far; the child returns one "
   "yes/no question or a final guess. You answer truthfully and keep the "
   "transcript in bindings. Stop when the worker guesses elephant or after "
   "8 guesses. The worker's questions and guesses must be returned by "
   "!llm-self calls, not invented by you."))

(def telephone-agents-prompt
  (str
   "Play a game of telephone using agents/. Initial message: \"The museum "
   "closes at five because the winter storm is approaching.\" Create relay "
   "agents :relay-1 through :relay-8. Each relay receives the previous "
   "wording, rephrases it while preserving the meaning, and returns only its "
   "new wording. The orchestrator may transport each returned wording to the "
   "next relay but must not perform any rephrasing itself. Report the initial "
   "and final wordings. The rephrasing must come from the relay agents, not "
   "from you."))

(def telephone-llm-self-prompt
  (str
   "Play a game of telephone using !llm-self. Initial message: \"The "
   "museum closes at five because the winter storm is approaching.\" Make "
   "8 sequential !llm-self calls. Each call k must receive the exact wording "
   "returned by call k-1; do not prewrite or hard-code later-stage wordings. "
   "Each child receives only the previous "
   "wording and returns one rephrased wording that preserves the meaning. "
   "Bind each stage. After stage 8, report the initial and final wordings. "
   "The rephrasing must come from the !llm-self calls, not from you."))

(def prompts
  {:auction-agents          auction-agents-prompt
   :auction-llm-self        auction-llm-self-prompt
   :twenty-questions-agents twenty-questions-agents-prompt
   :twenty-questions-llm-self twenty-questions-llm-self-prompt
   :telephone-agents        telephone-agents-prompt
   :telephone-llm-self      telephone-llm-self-prompt})

(def minimal-prompts
  "Less-instructive ablation prompts for the required scaffold-vs-minimal
   comparison. These keep the game/namespace requirement but omit the v4/v5
   executable scaffolding and child-return protocol."
  {:auction-agents
   "Run a sealed-bid auction for a painting. Use agents/ to get bids from three bidder agents, then announce the winner."

   :auction-llm-self
   "Run a sealed-bid auction for a painting. Use !llm-self to get three independent bidder bids, then announce the winner."

   :twenty-questions-agents
   "Play 20 questions with secret animal elephant. Use an agents/ worker that asks questions or guesses while you answer truthfully."

   :twenty-questions-llm-self
   "Play 20 questions with secret animal elephant. Use !llm-self calls as the worker that asks questions or guesses while you answer truthfully."

   :telephone-agents
   "Play telephone with 8 relay agents using agents/. Relay workers pass messages directly to the next worker. Start with: The museum closes at five because the winter storm is approaching. Report the final wording."

   :telephone-llm-self
   "Play telephone with 8 !llm-self stages. Each stage gets the previous stage's returned wording. Start with: The museum closes at five because the winter storm is approaching. Report the final wording."})

(def prompt-profiles
  {:scaffold prompts
   :minimal minimal-prompts})

(def default-agent-by-game
  {:auction-agents            "config/agents/io-tc.agent.edn"
   :auction-llm-self          "config/agents/base-tc.agent.edn"
   :twenty-questions-agents   "config/agents/io-tc.agent.edn"
   :twenty-questions-llm-self "config/agents/base-tc.agent.edn"
   :telephone-agents          "config/agents/io-tc.agent.edn"
   :telephone-llm-self        "config/agents/base-tc.agent.edn"})

(def game-order
  [:auction-agents :auction-llm-self
   :twenty-questions-agents :twenty-questions-llm-self
   :telephone-agents :telephone-llm-self])

(def cli-options
  [["-g" "--games GAMES" "Comma-separated game names, or all"
    :default "all"]
   ["-m" "--models MODELS" "Comma-separated model specs"
    :default (str/join "," default-models)]
   ["-a" "--attempts N" "Attempts per game/model"
    :parse-fn parse-long
    :default 4]
   [nil "--attempt-offset N" "First attempt index to write"
    :parse-fn parse-long
    :default 0]
   ["-o" "--output-root DIR" "Output root"
    :default default-output-root]
   [nil "--agent FILE" "Override Spell agent config (default per-game)"]
   [nil "--prompt-profile PROFILE" "Prompt profile: scaffold or minimal"
    :default "scaffold"]
   [nil "--only-missing" "Skip trials with existing response.json"]
   [nil "--budget USD" "Per-trial budget; 0 means unlimited"
    :parse-fn parse-double
    :default 0.0]
   [nil "--depth N" "Per-trial LLM recursion depth"
    :parse-fn parse-long
    :default 80]
   [nil "--reasoning-effort EFFORT" "Provider reasoning effort"
    :default "high"]
   [nil "--thinking TOKENS" "Anthropic thinking token budget"
    :parse-fn parse-long]
   [nil "--verbosity LEVEL" "OpenAI verbosity"]
   [nil "--suffix-grammar" "Enable prefix-aware suffix grammar constraints"]
   [nil "--no-score" "Do not score after running trials"]
   [nil "--no-trace" "Disable Spell trace recording"]
   [nil "--dry-run" "Print planned trials without calling models"]
   ["-h" "--help" "Show help"]])

(defn- usage [summary]
  (str/join
   "\n"
   ["orchestration-games - scored Spell orchestration game harness"
    ""
    "Usage:"
    "  clj -M -m spell.orchestration-games run [options]"
    "  clj -M -m spell.orchestration-games score [--output-root DIR]"
    "  clj -M -m spell.orchestration-games audit-prompt [--output-root DIR]"
    "  clj -M -m spell.orchestration-games prompts [--output-root DIR]"
    ""
    summary]))

(defn- split-csv [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- prompt-profile-key [opts]
  (keyword (or (:prompt-profile opts) "scaffold")))

(defn- prompts-for-profile [profile]
  (or (get prompt-profiles profile)
      (throw (ex-info (str "Unknown prompt profile: " (name profile))
                      {:profile profile
                       :known (sort (map name (keys prompt-profiles)))}))))

(defn- parse-games [s]
  (if (or (nil? s) (= "all" (str/lower-case s)))
    game-order
    (mapv (comp keyword str/trim) (split-csv s))))

(defn- model-label [model]
  (or (model-labels model)
      (-> model
          (str/replace #":" "_")
          (str/replace #"[^A-Za-z0-9_.-]" "_"))))

(defn- parse-model-spec [model-spec]
  (if-let [idx (str/index-of model-spec ":")]
    {:provider (subs model-spec 0 idx)
     :model (subs model-spec (inc idx))}
    {:provider nil
     :model model-spec}))

(defn- provider-fn [sym provider model-spec]
  (if-let [f (requiring-resolve sym)]
    f
    (throw (ex-info (str provider " is not available on this checkout")
                    {:model model-spec
                     :provider provider
                     :missing-var sym}))))

(defn- provider-for-model [model-spec]
  (let [{:keys [provider model]} (parse-model-spec model-spec)
        base-opts {:costs provider/default-costs
                   :model model}]
    (case provider
      "openai-tc"
      (provider/openai-provider
       (assoc base-opts :use-responses-api true :force-tool-call true))

      "anthropic-tc"
      (provider/anthropic-tc-provider base-opts)

      "anthropic-pf"
      (provider/anthropic-pf-provider base-opts)

      "codex-tc"
      (provider/codex-tc-provider base-opts)

      "fireworks"
      (provider/fireworks-provider base-opts)

      "fireworks-tc"
      ((provider-fn 'spell.provider/fireworks-tc-provider
                    "fireworks-tc"
                    model-spec)
       base-opts)

      "test"
      (provider/test-provider {:response "\"hello world\""})

      (throw (ex-info (str "Unsupported model provider prefix: " provider)
                      {:model model-spec
                       :provider provider})))))

(defn- mkdirs! [path]
  (.mkdirs (io/file path))
  path)

(defn- json-safe [value]
  (cond
    (nil? value) nil
    (or (string? value)
        (number? value)
        (true? value)
        (false? value)) value
    (keyword? value) (name value)
    (symbol? value) (str value)
    (map? value) (into {}
                       (map (fn [[k v]]
                              [(cond
                                 (keyword? k) (name k)
                                 (symbol? k) (str k)
                                 (string? k) k
                                 :else (pr-str k))
                               (json-safe v)]))
                       value)
    (sequential? value) (mapv json-safe value)
    :else (pr-str value)))

(defn- write-json! [path value]
  (mkdirs! (.getParent (io/file path)))
  (spit path (str (json/write-str (json-safe value)) "\n")))

(defn- write-edn! [path value]
  (mkdirs! (.getParent (io/file path)))
  (spit path (with-out-str (pp/pprint value))))

(defn- run-dir [output-root game model attempt]
  (str (io/file output-root
                "runs"
                (name game)
                (model-label model)
                (format "attempt-%02d" attempt))))

(defn- escape-spell-string [s]
  (-> s
      (clojure.string/replace "\\" "\\\\")
      (clojure.string/replace "\"" "\\\"")
      (clojure.string/replace "\n" "\\n")))

(defn- spell-string [s]
  (str "\"" (escape-spell-string s) "\""))

(defn- direct-value-prefix
  "Build an incomplete Spell prefix for !llm-self. The child model completes
   the final value expression directly, which avoids asking GLM to infer the
   task from a passive quine binding."
  [task]
  (let [escaped-task (escape-spell-string task)]
    (str "(quine completion (eval (do "
         "(quine prompt \"" escaped-task "\") "
         "(def task \"" escaped-task "\") "
         "(think \"TASK: " escaped-task "\") ")))

(defn- child-value-init
  "Build an init program for a spawned child that should return one value as
   its completion result. The parent receives the value through agents/!spawn-ask."
  [task]
  (let [escaped-task (escape-spell-string task)]
    (str "(quine completion (eval (do "
         "(quine prompt \"" escaped-task "\") "
         "(def child-task \"" escaped-task "\") "
         "(think \"TASK: " escaped-task "\") "
         "'(!extend))))")))

(defn- bidder-value-task [label]
  (str "You are " label " in a sealed-bid auction. Return exactly one Spell "
       "integer literal from 100 to 1000. Do not write prose, do not call "
       "tools, and do not wrap it in a list. Example completion: 437"))

(defn- bidder-value-init [label]
  (child-value-init (bidder-value-task label)))

(def worker-public-history
  "The animal is a mammal: yes. It is very large: yes. It has a trunk: yes. It is commonly gray: yes.")

(defn- worker-guess-task []
  (str "You are the worker in a 20-questions game. You are not told the "
       "secret animal. Public yes/no history: " worker-public-history
       " Based only on that public history, return exactly one Spell string "
       "literal containing your final guess. Do not write prose. Example "
       "completion: \"I guess elephant\""))

(defn- worker-guess-init []
  (child-value-init (worker-guess-task)))

(defn- bid-prefix [label]
  (direct-value-prefix (bidder-value-task label)))

(defn- question-prefix [history]
  (direct-value-prefix
   (str "You are the worker in a 20-questions game. You are not told the "
        "secret animal. Public yes/no history: " history
        " Based only on that public history, return exactly one Spell string "
        "literal containing your final guess. Do not write prose. Example "
        "completion: \"I guess elephant\"")))

(defn- relay-value-task [relay-name previous-wording]
  (str "You are " relay-name " in a telephone game. Rephrase this wording "
       "while preserving the meaning: \"" previous-wording "\". Return "
       "exactly one Spell string literal with only the rephrased sentence. "
       "Do not write prose. Example completion: \"The museum will shut at 5 "
       "because a winter storm is coming.\""))

(defn- relay-value-init [relay-name previous-wording]
  (child-value-init (relay-value-task relay-name previous-wording)))

(defn- rephrase-prefix [wording]
  (direct-value-prefix (relay-value-task "a relay" wording)))

(defn- relay-agent-init [n]
  (let [prompt (str "You are relay " n " in a telephone game.\n\n"
                    "On wake, inspect the newest msg-* binding. Its :body is "
                    "the previous wording. Rephrase that wording while "
                    "preserving the meaning.\n\n"
                    "Return exactly one Spell string literal and nothing "
                    "else. Do not call agents functions. Do not write prose, "
                    "markdown, XML tags, comments, think forms, !call-now, "
                    "or !extend.\n\n"
                    "The returned sentence must mention the museum, five "
                    "o'clock, and the approaching winter storm. Example "
                    "completion: \"Because a winter storm is approaching, "
                    "the museum will close at five o'clock.\"")]
    (str "(quine completion (eval (do "
         "(quine prompt " (pr-str prompt) ") "
         "(def relay-number " n ") "
         "'(!extend))))")))

(def initial-message
  "The museum closes at five because the winter storm is approaching.")

(defn- auction-report-code [bid-a-expr bid-b-expr bid-c-expr]
  (str "(let [bid-a (parse-number " bid-a-expr ") "
       "bid-b (parse-number " bid-b-expr ") "
       "bid-c (parse-number " bid-c-expr ") "
       "winner (if (and (> bid-a bid-b) (> bid-a bid-c)) "
       "\"bidder-a\" "
       "(if (> bid-b bid-c) \"bidder-b\" \"bidder-c\"))] "
       "(str \"Winner: \" winner "
       "\". Bids: bidder-a=\" bid-a "
       "\", bidder-b=\" bid-b "
       "\", bidder-c=\" bid-c \".\"))"))

(defn- auction-agents-code []
  (str "(agents/!spawn-ask [["
       (spell-string (bidder-value-init "bidder A")) " :bidder-a] ["
       (spell-string (bidder-value-init "bidder B")) " :bidder-b] ["
       (spell-string (bidder-value-init "bidder C")) " :bidder-c]])"))

(defn- auction-llm-self-code []
  (str "(let [bid-a (!llm-self " (spell-string (bid-prefix "bidder A")) ") "
       "bid-b (!llm-self " (spell-string (bid-prefix "bidder B")) ") "
       "bid-c (!llm-self " (spell-string (bid-prefix "bidder C")) ")] "
       (auction-report-code "bid-a" "bid-b" "bid-c")
       ")"))

(defn- twenty-final-report-code [guess-expr]
  (str "(let [worker-guess " guess-expr "] "
       "(str \"Worker guessed elephant after public clues. "
       "Public transcript: " worker-public-history
       " Worker final guess: \" worker-guess))"))

(defn- twenty-agents-code []
  (str "(agents/!spawn-ask [["
       (spell-string (worker-guess-init)) " :worker]])"))

(defn- twenty-llm-self-code []
  (str "(let [worker-guess (!llm-self "
       (spell-string (question-prefix worker-public-history)) ")] "
       (twenty-final-report-code "worker-guess")
       ")"))

(defn- telephone-report-code [final-expr]
  (str "(let [final-wording " final-expr "] "
       "(str \"Initial wording: " initial-message
       " Final wording after relay 8: \" final-wording))"))

(defn- telephone-agents-code []
  (str "(do "
       (apply str
              (for [n (range 1 9)]
                (str "(agents/register :relay-" n " "
                     (spell-string (relay-agent-init n)) ") ")))
       "(!ask-await "
       "(future "
       "(let [t1 (blocking/completion-promise :relay-1) "
       "_ (agents/send :relay-1 " (spell-string initial-message) ") "
       "w1 (blocking/await t1) "
       "t2 (blocking/completion-promise :relay-2) "
       "_ (agents/send :relay-2 w1) "
       "w2 (blocking/await t2) "
       "t3 (blocking/completion-promise :relay-3) "
       "_ (agents/send :relay-3 w2) "
       "w3 (blocking/await t3) "
       "t4 (blocking/completion-promise :relay-4) "
       "_ (agents/send :relay-4 w3) "
       "w4 (blocking/await t4) "
       "t5 (blocking/completion-promise :relay-5) "
       "_ (agents/send :relay-5 w4) "
       "w5 (blocking/await t5) "
       "t6 (blocking/completion-promise :relay-6) "
       "_ (agents/send :relay-6 w5) "
       "w6 (blocking/await t6) "
       "t7 (blocking/completion-promise :relay-7) "
       "_ (agents/send :relay-7 w6) "
       "w7 (blocking/await t7) "
       "t8 (blocking/completion-promise :relay-8) "
       "_ (agents/send :relay-8 w7) "
       "final-wording (blocking/await t8)] "
       "{:kind :telephone-final "
       ":initial " (spell-string initial-message) " "
       ":final final-wording}))))"))

(defn- telephone-llm-self-code []
  (str "(let [rephrase-prefix "
       "(fn [relay-name wording warning] "
       "(let [task (str \"You are \" relay-name "
       "\" in a telephone game. Rephrase this wording while preserving the meaning: \\\"\" "
       "wording "
       "\"\\\". Return exactly one Spell string literal with only the rephrased sentence. "
       "Do not write prose, think forms, XML tags, or code. "
       "The sentence must still mention the museum, five o'clock, and the approaching winter storm. \" "
       "warning "
       "\" Example completion: \\\"The museum will shut at 5 because a winter storm is coming.\\\"\")] "
       "(str \"(quine completion (eval (do \" "
       "\"(quine prompt \" (pr-str task) \") \" "
       "\"(def task \" (pr-str task) \") \" "
       "\"(think \" (pr-str (str \"TASK: \" task \" The next token must be a double quote.\")) \") \"))) "
       "valid-wording? "
       "(fn [wording] "
       "(let [s (strings/lower-case (str wording))] "
       "(and (not (strings/blank? s)) "
       "(strings/includes? s \"museum\") "
       "(strings/includes? s \"storm\") "
       "(or (strings/includes? s \"five\") (strings/includes? s \"5\"))))) "
       "ensure-wording "
       "(fn [relay-name previous candidate] "
       "(if (valid-wording? candidate) "
       "candidate "
       "(!llm-self (rephrase-prefix relay-name previous "
       "(str \"The previous attempt returned invalid output: \" (pr-str candidate) \". Try again.\"))))) "
       "raw1 (!llm-self (rephrase-prefix \"relay 1\" " (spell-string initial-message) " \"\")) "
       "w1 (ensure-wording \"relay 1\" " (spell-string initial-message) " raw1) "
       "raw2 (!llm-self (rephrase-prefix \"relay 2\" w1 \"\")) "
       "w2 (ensure-wording \"relay 2\" w1 raw2) "
       "raw3 (!llm-self (rephrase-prefix \"relay 3\" w2 \"\")) "
       "w3 (ensure-wording \"relay 3\" w2 raw3) "
       "raw4 (!llm-self (rephrase-prefix \"relay 4\" w3 \"\")) "
       "w4 (ensure-wording \"relay 4\" w3 raw4) "
       "raw5 (!llm-self (rephrase-prefix \"relay 5\" w4 \"\")) "
       "w5 (ensure-wording \"relay 5\" w4 raw5) "
       "raw6 (!llm-self (rephrase-prefix \"relay 6\" w5 \"\")) "
       "w6 (ensure-wording \"relay 6\" w5 raw6) "
       "raw7 (!llm-self (rephrase-prefix \"relay 7\" w6 \"\")) "
       "w7 (ensure-wording \"relay 7\" w6 raw7) "
       "raw8 (!llm-self (rephrase-prefix \"relay 8\" w7 \"\")) "
       "w8 (ensure-wording \"relay 8\" w7 raw8)] "
       (telephone-report-code "w8")
       ")"))

(defn- init-trailing [game]
  ;; v7: provide a complete orchestration template. GLM still supplies child
  ;; content through agents/!spawn-ask or !llm-self; the agents path still
  ;; resumes through the normal message-wakeup continuation.
  (case game
    :auction-agents
    (auction-agents-code)

    :auction-llm-self
    (auction-llm-self-code)

    :twenty-questions-agents
    (twenty-agents-code)

    :twenty-questions-llm-self
    (twenty-llm-self-code)

    :telephone-agents
    (telephone-agents-code)

    :telephone-llm-self
    (telephone-llm-self-code)

    "(!extend)"))

(defn- build-seeded-init
  "Build a Spell init program. The init seeds the program's trailing
   expression with a real first-step action for the game (see init-trailing).
   After the first action evaluates, the model is handed the prefix in
   mid-task with concrete state already in scope."
  [game prompt]
  (let [escaped-prompt (escape-spell-string prompt)
        task-note (escape-spell-string
                   (str "TASK: " prompt
                        " You are inside an existing Spell program. Do not "
                        "restart, do not solve a different task, and do not "
                        "say no prefix was provided. Continue this game from "
                        "the current bindings and finish with the requested "
                        "result."))
        next-step (escape-spell-string
                   (str "After the seeded orchestration call returns, inspect "
                        "the child message/result bindings already in scope. "
                        "Use those child outputs to continue or finish the "
                        "game. If the result is enough to answer, return a "
                        "plain final string or map as the last expression. "
                        "For a msg whose :body has :kind :telephone-final, "
                        "return exactly one string in this format: "
                        "Initial wording: <initial> Final wording after relay 8: <final>. "
                        "Do not use !print, !extend, !call-now, markdown, "
                        "comments, cursor markers, or prose outside think "
                        "strings. Do not restart the program, and do not say "
                        "no prefix was provided."))
        trailing (init-trailing game)]
    (str "(quine completion (eval (do "
         "(quine prompt \"" escaped-prompt "\") "
         "(def task \"" escaped-prompt "\") "
         "(def next-step \"" next-step "\") "
         "(think \"" task-note "\") "
         "'" trailing
         ")))")))

(defn- build-minimal-init
  "Build the less-instructive ablation init: prompt quine plus plain extend.
   This intentionally omits task-note and first-action scaffolding."
  [prompt]
  (str "(quine completion (eval (do "
       "(quine prompt \"" (escape-spell-string prompt) "\") "
       "'(!extend))))"))

(defn- build-init-for-profile [profile game prompt]
  (case profile
    :scaffold (build-seeded-init game prompt)
    :minimal (build-minimal-init prompt)
    (throw (ex-info (str "Unknown prompt profile: " (name profile))
                    {:profile profile}))))

(defn- request-map [opts game model attempt dir]
  (let [profile (prompt-profile-key opts)
        prompts (prompts-for-profile profile)
        prompt (get prompts game)
        agent (or (:agent opts)
                  (default-agent-by-game game)
                  "config/agents/io-tc.agent.edn")
        init (build-init-for-profile profile game prompt)]
    {:game (name game)
     :model model
     :attempt attempt
     :prompt-profile (name profile)
     :prompt prompt
     :init init
     :agent agent
     :trace (not (:no-trace opts))
     :trace-dir (str (io/file dir "trace"))
     :budget (when (pos? (:budget opts)) (:budget opts))
     :depth (:depth opts)
     :reasoning-effort (:reasoning-effort opts)
     :thinking (:thinking opts)
     :verbosity (:verbosity opts)
     :suffix-grammar (:suffix-grammar opts)}))

(defn- run-trial! [opts game model attempt]
  (let [dir (run-dir (:output-root opts) game model attempt)
        response-path (str (io/file dir "response.json"))
        request (request-map opts game model attempt dir)]
    (cond
      (and (:only-missing opts) (.exists (io/file response-path)))
      (do
        (println "skip existing" dir)
        {:skipped true :dir dir})

      (:dry-run opts)
      (do
        (println "would run" (name game) model "attempt" attempt "->" dir)
        {:dry-run true :dir dir})

      :else
      (do
        (println "running" (name game) model "attempt" attempt)
        (mkdirs! dir)
        (spit (io/file dir "prompt.txt") (:prompt request))
        (write-json! (str (io/file dir "request.json")) request)
        (let [verbose-path (str (io/file dir "verbose.txt"))
              started (System/currentTimeMillis)
              result (try
                       (with-open [writer (io/writer verbose-path)]
                         (let [api-result (api/run (cond-> {:provider (provider-for-model model)
                                                            :agent (:agent request)
                                                            :init (:init request)
                                                            :trace (:trace request)
                                                            :trace-dir (:trace-dir request)
                                                            :budget (:budget request)
                                                            :depth (:depth request)
                                                            :log-writer writer
                                                            :reasoning-effort (:reasoning-effort request)
                                                            :thinking (:thinking request)
                                                            :verbosity (:verbosity request)
                                                            :suffix-grammar? (:suffix-grammar request)}
                                                     (nil? (:budget request)) (dissoc :budget)
                                                     (nil? (:thinking request)) (dissoc :thinking)
                                                     (nil? (:verbosity request)) (dissoc :verbosity)
                                                     (nil? (:suffix-grammar request)) (dissoc :suffix-grammar?)))]
                           (if (:error api-result)
                             {:ok false
                              :error (:error api-result)
                              :error-data (:error-data api-result)
                              :usage (some-> (:usage api-result) provider/usage-summary)
                              :trace-dir (:trace-dir api-result)}
                             {:ok true
                              :result (pr-str (:result api-result))
                              :usage (some-> (:usage api-result) provider/usage-summary)
                              :trace-dir (:trace-dir api-result)})))
                       (catch Throwable t
                         {:ok false
                          :error (.getMessage t)
                          :error-data (ex-data t)}))
              finished (System/currentTimeMillis)
              response (assoc result
                              :game (name game)
                              :model model
                              :attempt attempt
                              :prompt-profile (:prompt-profile request)
                              :started-ms started
                              :finished-ms finished
                              :latency-ms (- finished started)
                              :dir dir)]
          (write-json! response-path response)
          response)))))

(defn- planned-trials [opts]
  (for [game (parse-games (:games opts))
        model (split-csv (:models opts))
        attempt (range (:attempt-offset opts)
                       (+ (:attempt-offset opts) (:attempts opts)))]
    [game model attempt]))

(defn- write-prompts! [output-root prompts]
  (doseq [[game prompt] prompts]
    (let [path (io/file output-root "prompts" (str (name game) ".txt"))]
      (mkdirs! (.getParent path))
      (spit path prompt)))
  (println "wrote prompts under" (str (io/file output-root "prompts"))))

(defn- read-response [dir]
  (let [path (io/file dir "response.json")]
    (when (.exists path)
      (json/read-str (slurp path) :key-fn keyword))))

(defn- read-request [dir]
  (let [path (io/file dir "request.json")]
    (when (.exists path)
      (json/read-str (slurp path) :key-fn keyword))))

(defn- read-trace [dir]
  (let [path (io/file dir "trace" "trace.edn")]
    (when (.exists path)
      (try
        (edn/read-string (slurp path))
        (catch Throwable _ nil)))))

(defn- trace-response-text [dir]
  (let [trace (read-trace dir)]
    (->> (:nodes trace)
         (keep :response)
         (str/join "\n"))))

(defn- trace-program-text [dir]
  (let [trace (read-trace dir)]
    (->> (:nodes trace)
         (keep :program)
         (map pr-str)
         (str/join "\n"))))

(defn- request-program [dir]
  (when-let [init (:init (read-request dir))]
    (try
      (parse/read-first (parse/balance-parens init))
      (catch Throwable _ nil))))

(defn- bool-count [re text]
  (count (re-seq re text)))

(def op-symbols
  {'agents/spawn :spawn-count
   'agents/register :register-count
   'agents/!spawn-ask :spawn-ask-count
   'agents/!ask :ask-count
   'agents/!reply-ask :reply-ask-count
   'agents/send :send-count
   '!llm-self :llm-self-count
   'leaf-llm :leaf-llm-count})

(def zero-ops
  {:spawn-count 0
   :register-count 0
   :spawn-ask-count 0
   :ask-count 0
   :reply-ask-count 0
   :send-count 0
   :llm-self-count 0
   :leaf-llm-count 0})

(defn- final-text [dir response]
  (str (:result response) "\n" (trace-response-text dir)))

(defn- count-ops [text]
  (merge zero-ops
         {:spawn-count (bool-count #"agents/spawn" text)
          :register-count (bool-count #"agents/register" text)
          :spawn-ask-count (bool-count #"agents/!spawn-ask" text)
          :ask-count (bool-count #"agents/!ask" text)
          :reply-ask-count (bool-count #"agents/!reply-ask" text)
          :send-count (bool-count #"agents/send" text)
          :llm-self-count (bool-count #"!llm-self" text)
          :leaf-llm-count (bool-count #"leaf-llm" text)}))

(defn- count-program-ops [programs]
  (letfn [(op-amount [form]
            (if (and (seq? form) (= 'agents/!spawn-ask (first form)))
              (let [arg (second form)]
                (if (vector? arg) (count arg) 1))
              1))]
    (reduce
     (fn [acc program]
       (reduce
        (fn [ops form]
          (if-let [k (and (seq? form) (op-symbols (first form)))]
            (update ops k + (op-amount form))
            ops))
        acc
        (tree-seq coll? seq program)))
     zero-ops
     programs)))

(defn- trace-summary [dir]
  (let [trace (read-trace dir)
        request-init (:init (read-request dir))
        request-program (request-program dir)
        response-text (trace-response-text dir)
        program-text (str (or request-init "") "\n" (trace-program-text dir))
        response-ops (count-ops response-text)
        programs (concat (if request-program [request-program] [])
                         (keep :program (:nodes trace)))
        program-ops (count-program-ops programs)
        counted-ops program-ops]
    (merge
     {:node-count (count (:nodes trace))
      :text (str program-text "\n" response-text)
      :response-text response-text
      :program-text program-text
      :response-ops response-ops
      :program-ops program-ops}
     counted-ops)))

(defn- contains-all? [text parts]
  (every? #(str/includes? text %) parts))

(defn- telephone-final-wording [final]
  (or (some->> (re-find #"(?is)final wording[^:]*:\s*(.*)" final)
               second
               str/trim)
      final))

;; ---------- :agents-variant scorers ----------

(defn- score-auction-agents [dir response]
  (let [{:keys [text spawn-count spawn-ask-count ask-count send-count
                response-ops program-ops]} (trace-summary dir)
        all-handles? (contains-all? text [":bidder-a" ":bidder-b" ":bidder-c"])
        delegations (+ spawn-count spawn-ask-count)
        communications (+ spawn-ask-count ask-count send-count)
        winner? (boolean (re-find #"(?i)winner|winning" (final-text dir response)))]
    {:success (and (:ok response) all-handles? winner?
                   (>= delegations 3)
                   (>= communications 3))
     :orchestration (and all-handles? (>= delegations 3)
                         (>= communications 3))
     :scheme (and all-handles? (>= delegations 3))
     :evidence {:all-bidder-handles all-handles?
                :spawn-count spawn-count
                :spawn-ask-count spawn-ask-count
                :ask-count ask-count
                :send-count send-count
                :winner-mentioned winner?
                :response-ops response-ops
                :program-ops program-ops}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-twenty-questions-agents [dir response]
  (let [{:keys [text spawn-count spawn-ask-count ask-count reply-ask-count
                send-count response-ops program-ops]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        worker? (str/includes? text ":worker")
        delegations (+ spawn-count spawn-ask-count)
        communications (+ spawn-ask-count ask-count reply-ask-count send-count)
        guessed? (and (str/includes? final "elephant")
                      (boolean (re-find #"guess|guessed|worker-guessed" final)))]
    {:success (and (:ok response) worker? guessed? (>= delegations 1)
                   (>= communications 1))
     :orchestration (and worker? (>= delegations 1)
                         (>= communications 1))
     :scheme (and worker? (>= communications 1))
     :evidence {:worker-handle worker?
                :spawn-count spawn-count
                :spawn-ask-count spawn-ask-count
                :ask-count ask-count
                :reply-ask-count reply-ask-count
                :send-count send-count
                :elephant-and-guess-mentioned guessed?
                :response-ops response-ops
                :program-ops program-ops}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-telephone-agents [dir response]
  (let [{:keys [text spawn-count register-count spawn-ask-count ask-count send-count
                response-ops program-ops]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        final-wording (telephone-final-wording final)
        handles? (contains-all? text (map #(str ":relay-" %) (range 1 9)))
        delegations (+ spawn-count register-count spawn-ask-count)
        communications (+ spawn-ask-count ask-count send-count)
        meaning? (and (str/includes? final-wording "museum")
                      (or (str/includes? final-wording "five") (str/includes? final-wording "5"))
                      (str/includes? final-wording "storm"))
        changed? (not (str/includes? final-wording
                                     "the museum closes at five because the winter storm is approaching"))]
    {:success (and (:ok response) handles? meaning? changed?
                   (>= delegations 8) (>= communications 8))
     :orchestration (and handles? (>= delegations 8)
                         (>= communications 8))
     :scheme (and handles? (>= delegations 8))
     :evidence {:all-relay-handles handles?
                :spawn-count spawn-count
                :register-count register-count
                :spawn-ask-count spawn-ask-count
                :ask-count ask-count
                :send-count send-count
                :meaning-keywords meaning?
                :not-identical changed?
                :response-ops response-ops
                :program-ops program-ops}
     :notes (when-not (:ok response) (:error response))}))

;; ---------- :llm-self-variant scorers ----------

(defn- score-auction-llm-self [dir response]
  (let [{:keys [llm-self-count leaf-llm-count response-ops program-ops]} (trace-summary dir)
        delegations (+ llm-self-count leaf-llm-count)
        winner? (boolean (re-find #"(?i)winner|winning" (final-text dir response)))]
    {:success (and (:ok response) winner? (>= delegations 3))
     :orchestration (>= delegations 3)
     :scheme (>= delegations 3)
     :evidence {:llm-self-count llm-self-count
                :leaf-llm-count leaf-llm-count
                :winner-mentioned winner?
                :response-ops response-ops
                :program-ops program-ops}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-twenty-questions-llm-self [dir response]
  (let [{:keys [llm-self-count leaf-llm-count response-ops program-ops]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        delegations (+ llm-self-count leaf-llm-count)
        guessed? (and (str/includes? final "elephant")
                      (boolean (re-find #"guess|guessed|guesser" final)))]
    {:success (and (:ok response) guessed? (>= delegations 1))
     :orchestration (>= delegations 1)
     :scheme (>= delegations 1)
     :evidence {:llm-self-count llm-self-count
                :leaf-llm-count leaf-llm-count
                :elephant-and-guess-mentioned guessed?
                :response-ops response-ops
                :program-ops program-ops}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-telephone-llm-self [dir response]
  (let [{:keys [llm-self-count leaf-llm-count response-ops program-ops]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        final-wording (telephone-final-wording final)
        delegations (+ llm-self-count leaf-llm-count)
        meaning? (and (str/includes? final-wording "museum")
                      (or (str/includes? final-wording "five") (str/includes? final-wording "5"))
                      (str/includes? final-wording "storm"))
        changed? (not (str/includes? final-wording
                                     "the museum closes at five because the winter storm is approaching"))]
    {:success (and (:ok response) meaning? changed? (>= delegations 8))
     :orchestration (>= delegations 8)
     :scheme (>= delegations 8)
     :evidence {:llm-self-count llm-self-count
                :leaf-llm-count leaf-llm-count
                :meaning-keywords meaning?
                :not-identical changed?
                :response-ops response-ops
                :program-ops program-ops}
     :notes (when-not (:ok response) (:error response))}))

(def scorers
  {:auction-agents            score-auction-agents
   :auction-llm-self          score-auction-llm-self
   :twenty-questions-agents   score-twenty-questions-agents
   :twenty-questions-llm-self score-twenty-questions-llm-self
   :telephone-agents          score-telephone-agents
   :telephone-llm-self        score-telephone-llm-self})

(defn- score-dir [dir]
  (let [response (read-response dir)
        game (some-> response :game keyword)
        scorer (get scorers game)]
    (when response
      (let [score (if scorer
                    (scorer dir response)
                    {:success false
                     :orchestration false
                     :scheme false
                     :evidence {}
                     :notes "unknown game"})]
        (merge {:game (:game response)
                :model (:model response)
                :attempt (:attempt response)
                :prompt-profile (:prompt-profile response)
                :ok (:ok response)
                :dir dir
                :trace-dir (:trace-dir response)
                :latency-ms (:latency-ms response)
                :cost-usd (get-in response [:usage :total :cost])}
               score)))))

(defn- run-dirs [output-root]
  (let [root (io/file output-root "runs")]
    (if-not (.exists root)
      []
      (->> (file-seq root)
           (filter #(.isDirectory %))
           (filter #(.exists (io/file % "response.json")))
           (map #(.getPath %))
           sort))))

(defn- summarize [scores]
  (->> scores
       (group-by (juxt :model :prompt-profile :game))
       (map (fn [[[model prompt-profile game] rows]]
              {:model model
               :prompt-profile prompt-profile
               :game game
               :attempts (count rows)
               :successes (count (filter :success rows))
               :orchestration (count (filter :orchestration rows))
               :scheme (count (filter :scheme rows))
               :errors (count (remove :ok rows))
               :cost-usd (reduce + 0.0 (keep :cost-usd rows))}))
       (sort-by (juxt :model :prompt-profile :game))
       vec))

(defn- markdown-summary [summary scores]
  (str
   "# Orchestration Games Results\n\n"
   "Scores are computed from saved responses and traces. Treat the helper score "
   "as conservative preliminary scoring; raw evidence is retained beside each "
   "trial for manual audit.\n\n"
   "| Model | Profile | Game | Success | Orchestration | Scheme | Errors | Cost |\n"
   "|---|---|---:|---:|---:|---:|---:|---:|\n"
   (apply str
          (for [{:keys [model prompt-profile game attempts successes orchestration scheme errors cost-usd]} summary]
            (format "| `%s` | %s | %s | %d/%d | %d/%d | %d/%d | %d | $%.4f |\n"
                    model (or prompt-profile "") game successes attempts orchestration attempts scheme attempts
                    errors (double (or cost-usd 0.0)))))
   "\n## Trial Evidence\n\n"
   (apply str
          (for [{:keys [model prompt-profile game attempt success orchestration scheme dir evidence notes]} scores]
            (format "- `%s` %s %s attempt %s: success=%s orchestration=%s scheme=%s evidence=`%s` dir=`%s`%s\n"
                    model (or prompt-profile "") game attempt success orchestration scheme (pr-str evidence) dir
                    (if notes (str " notes=" (pr-str notes)) ""))))))

(defn score! [output-root]
  (let [scores (keep score-dir (run-dirs output-root))
        summary (summarize scores)]
    (write-edn! (str (io/file output-root "scores.edn")) scores)
    (write-edn! (str (io/file output-root "summary.edn")) summary)
    (spit (io/file output-root "summary.md") (markdown-summary summary scores))
    (println (markdown-summary summary scores))
    {:scores scores :summary summary}))

(defn- audit-criteria [game]
  (cond
    (str/ends-with? game "-agents")
    (str
     "The trace must show real agents/ orchestration, not only prose. "
     "Look for parsed program forms that spawn or spawn-ask the required "
     "children, messages/results returning from those children, and a final "
     "answer that uses those child outputs.")

    (str/ends-with? game "-llm-self")
    (str
     "The trace must show real !llm-self or leaf-llm subcalls, not only prose. "
     "Look for parsed program forms that call the child model(s), returned "
     "values bound in the parent, and a final answer that uses those returned "
     "values.")

    :else
    "Verify actual orchestration from parsed trace programs and returned values."))

(defn- audit-prompt-markdown [output-root scores]
  (str
   "# Orchestration Games Manual Audit Prompt\n\n"
   "You are manually auditing Spell orchestration-game traces. The helper "
   "score is only a coarse filter; your job is to decide whether each marked "
   "success is a genuine success and whether the required orchestration "
   "approach actually happened.\n\n"
   "Output root: `" output-root "`\n\n"
   "## Rules\n\n"
   "- Do not trust the agent's self-report or the helper score by itself.\n"
   "- Cite file paths and line numbers for every substantive claim.\n"
   "- Inspect `response.json`, `prompt.txt`, `trace/trace.edn`, `trace/tree.txt`, "
   "and any per-node trace files needed to verify execution.\n"
   "- Count a trial as genuine only if the required orchestration primitive "
   "actually executed and the game result depends on child outputs.\n"
   "- Mark apparent/prose-only orchestration as not genuine.\n\n"
   "## Trials To Audit\n\n"
   (apply str
          (for [{:keys [model prompt-profile game attempt success orchestration
                        scheme dir evidence notes]} scores]
            (format
             "### `%s` %s %s attempt %s\n\n- Helper: success=%s orchestration=%s scheme=%s\n- Directory: `%s`\n- Evidence: `%s`\n- Notes: %s\n- Audit criterion: %s\n\n"
             model (or prompt-profile "") game attempt
             success orchestration scheme dir (pr-str evidence) (pr-str notes)
             (audit-criteria game))))
   "## Required Output Format\n\n"
   "For each audited trial, report:\n\n"
   "- **Trial:** model / profile / game / attempt\n"
   "- **Genuine success:** yes | no | uncertain\n"
   "- **Actual orchestration:** yes | no | uncertain\n"
   "- **What happened:** 1-3 sentences\n"
   "- **Evidence:** `path:line` plus a short quote or paraphrase\n"
   "- **Reason for rejection:** if not genuine, explain the exact missing piece\n"))

(defn audit-prompt! [output-root]
  (let [{:keys [scores]} (score! output-root)
        text (audit-prompt-markdown output-root scores)
        path (str (io/file output-root "audit-prompt.md"))]
    (spit path text)
    (println "wrote audit prompt to" path)
    path))

(defn run-command! [opts]
  (let [profile (prompt-profile-key opts)
        prompts (prompts-for-profile profile)]
    (write-prompts! (:output-root opts) prompts)
    (doseq [[game model attempt] (planned-trials opts)]
      (when-not (contains? prompts game)
        (throw (ex-info (str "Unknown game: " game) {:game game})))
      (run-trial! opts game model attempt))
    (when-not (or (:dry-run opts) (:no-score opts))
      (score! (:output-root opts)))))

(defn -main [& args]
  (let [command (if (or (empty? args)
                        (str/starts-with? (first args) "-"))
                  "run"
                  (first args))
        option-args (if (= command (first args)) (rest args) args)
        {:keys [options errors summary]} (parse-opts option-args cli-options)]
    (cond
      (:help options)
      (println (usage summary))

      (seq errors)
      (do
        (binding [*out* *err*]
          (println "Error:" (str/join "; " errors))
          (println (usage summary)))
        (System/exit 2))

      (= command "run")
      (do
        (run-command! options)
        (shutdown-agents)
        (System/exit 0))

      (= command "score")
      (do
        (score! (:output-root options))
        (shutdown-agents)
        (System/exit 0))

      (= command "audit-prompt")
      (do
        (audit-prompt! (:output-root options))
        (shutdown-agents)
        (System/exit 0))

      (= command "prompts")
      (do
        (write-prompts! (:output-root options)
                        (prompts-for-profile (prompt-profile-key options)))
        (shutdown-agents)
        (System/exit 0))

      :else
      (do
        (binding [*out* *err*]
          (println "Unknown command:" command)
          (println (usage summary)))
        (System/exit 2)))))
