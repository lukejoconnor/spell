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
            [spell.provider :as provider]))

(def default-models
  ["fireworks-tc:glm-5p1"])

(def default-output-root
  "notebook/entries/orchestration-games")

(def model-labels
  {"openai-tc:gpt-5.4" "gpt54"
   "anthropic-tc:claude-opus-4-7" "opus47"
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
   ":bidder-a, :bidder-b, and :bidder-c. Each child must choose one integer "
   "bid from 100 to 1000 and send it to you with agents/send. After you "
   "receive all three messages, bind the bids, compare them, and return a "
   "short final report naming the winner and all three bids. Do not invent "
   "bids in the main agent; the bids must arrive from the children."))

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
   ":worker child must not be told the secret. Spawn one worker agent. The "
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
   "wording, rephrases it while preserving the meaning, and sends the new "
   "wording back to you. You then pass that wording to the next relay. "
   "After :relay-8, report the initial and final wordings. The rephrasing "
   "must come from the relay agents, not from you."))

(def telephone-llm-self-prompt
  (str
   "Play a game of telephone using !llm-self. Initial message: \"The "
   "museum closes at five because the winter storm is approaching.\" Make "
   "8 sequential !llm-self calls. Each child receives only the previous "
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
    "  clj -M -m spell.orchestration-games prompts [--output-root DIR]"
    ""
    summary]))

(defn- split-csv [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       vec))

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
  (str "(quine completion (eval (do "
       "(think \"" (escape-spell-string task) "\") "))

(defn- child-send-init
  "Build an explicit init program for spawned children. Natural-language child
   prompts get wrapped as a passive `(quine prompt ...)`; GLM often ignores
   that. This makes the child task visible as executable context and tells the
   child exactly how to return a value."
  [task]
  (str "(quine completion (eval (do "
       "(think \"" (escape-spell-string task) "\") "
       "'(!extend))))"))

(defn- bidder-child-init [label]
  (child-send-init
   (str "You are " label " in a sealed-bid auction. Choose one integer bid "
        "from 100 to 1000. Complete this Spell program with exactly one "
        "quoted trailing expression that sends the integer to your parent: "
        "'(agents/send (agents/parent-handle) 437). Replace 437 with your "
        "chosen bid. Do not write prose.")))

(defn- worker-child-init []
  (child-send-init
   (str "You are the worker in a 20-questions game. The secret is an animal, "
        "but you are not told which animal. Complete this Spell program with "
        "exactly one quoted trailing expression that sends one yes/no question "
        "or one final guess to your parent, for example: "
        "'(agents/send (agents/parent-handle) \"Is it a mammal?\"). Do not "
        "write prose.")))

(defn- relay-child-init [relay-name previous-wording]
  (child-send-init
   (str "You are " relay-name " in a telephone game. Rephrase this wording "
        "while preserving the meaning: \"" previous-wording "\". Complete "
        "this Spell program with exactly one quoted trailing expression that "
        "sends only the rephrased sentence to your parent, for example: "
        "'(agents/send (agents/parent-handle) \"The museum will shut at 5 "
        "because a winter storm is coming.\"). Do not write prose.")))

(defn- bid-prefix [label]
  (direct-value-prefix
   (str "You are " label " in a sealed-bid auction. Return exactly one Spell "
        "integer literal from 100 to 1000. Do not write prose, do not call "
        "tools, and do not wrap it in a list. Example completion: 437")))

(defn- question-prefix [history]
  (direct-value-prefix
   (str "You are the worker in a 20-questions game. The answer is an animal, "
        "but you do not know which animal. Public history so far: " history
        ". Return exactly one Spell string literal containing your next "
        "yes/no question or final guess. Example completion: \"Is it a "
        "mammal?\"")))

(defn- rephrase-prefix [wording]
  (direct-value-prefix
   (str "Rephrase this sentence while preserving the meaning: \"" wording
        "\". Return exactly one Spell string literal with only the rephrased "
        "sentence. Example completion: \"The museum will shut at 5 because "
        "a winter storm is coming.\"")))

(def initial-message
  "The museum closes at five because the winter storm is approaching.")

(defn- init-trailing [game]
  ;; v4: seed a concrete orchestration step and make child tasks explicit
  ;; enough that GLM can execute the protocol instead of treating the prompt
  ;; quine as inert context.
  (case game
    :auction-agents
    (str "(agents/!spawn-ask [["
         (spell-string (bidder-child-init "bidder A")) " :bidder-a] ["
         (spell-string (bidder-child-init "bidder B")) " :bidder-b] ["
         (spell-string (bidder-child-init "bidder C")) " :bidder-c]])")

    :auction-llm-self
    (str "(!call-now "
         "bid-a (!llm-self " (spell-string (bid-prefix "bidder A")) ") "
         "bid-b (!llm-self " (spell-string (bid-prefix "bidder B")) ") "
         "bid-c (!llm-self " (spell-string (bid-prefix "bidder C")) "))")

    :twenty-questions-agents
    (str "(agents/!spawn-ask "
         (spell-string (worker-child-init)) " :worker)")

    :twenty-questions-llm-self
    (str "(!call-now first-question "
         "(!llm-self " (spell-string (question-prefix "none")) "))")

    :telephone-agents
    (str "(agents/!spawn-ask "
         (spell-string (relay-child-init "relay 1" initial-message)) " :relay-1)")

    :telephone-llm-self
    (str "(!call-now wording-1 "
         "(!llm-self " (spell-string (rephrase-prefix initial-message)) "))")

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
        trailing (init-trailing game)]
    (str "(quine completion (eval (do "
         "(quine prompt \"" escaped-prompt "\") "
         "(think \"" task-note "\") "
         "'" trailing
         ")))")))

(defn- request-map [opts game model attempt dir]
  (let [prompt (get prompts game)
        agent (or (:agent opts)
                  (default-agent-by-game game)
                  "config/agents/io-tc.agent.edn")
        init (build-seeded-init game prompt)]
    {:game (name game)
     :model model
     :attempt attempt
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

(defn- write-prompts! [output-root]
  (doseq [[game prompt] prompts]
    (let [path (io/file output-root "prompts" (str (name game) ".txt"))]
      (mkdirs! (.getParent path))
      (spit path prompt)))
  (println "wrote prompts under" (str (io/file output-root "prompts"))))

(defn- read-response [dir]
  (let [path (io/file dir "response.json")]
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

(defn- bool-count [re text]
  (count (re-seq re text)))

(def op-symbols
  {'agents/spawn :spawn-count
   'agents/!spawn-ask :spawn-ask-count
   'agents/!ask :ask-count
   'agents/!reply-ask :reply-ask-count
   'agents/send :send-count
   '!llm-self :llm-self-count
   'leaf-llm :leaf-llm-count})

(def zero-ops
  {:spawn-count 0
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
          :spawn-ask-count (bool-count #"agents/!spawn-ask" text)
          :ask-count (bool-count #"agents/!ask" text)
          :reply-ask-count (bool-count #"agents/!reply-ask" text)
          :send-count (bool-count #"agents/send" text)
          :llm-self-count (bool-count #"!llm-self" text)
          :leaf-llm-count (bool-count #"leaf-llm" text)}))

(defn- count-program-ops [programs]
  (reduce
   (fn [acc program]
     (reduce
      (fn [ops form]
        (if-let [k (and (symbol? form) (op-symbols form))]
          (update ops k inc)
          ops))
      acc
      (tree-seq coll? seq program)))
   zero-ops
   programs))

(defn- merge-ops [& ops-colls]
  (apply merge-with + zero-ops ops-colls))

(defn- trace-summary [dir]
  (let [trace (read-trace dir)
        response-text (trace-response-text dir)
        program-text (trace-program-text dir)
        response-ops (count-ops response-text)
        programs (keep :program (:nodes trace))
        program-ops (count-program-ops programs)
        combined-ops (merge-ops response-ops program-ops)]
    (merge
     {:node-count (count (:nodes trace))
      :text (str program-text "\n" response-text)
      :response-text response-text
      :program-text program-text
      :response-ops response-ops
      :program-ops program-ops}
     combined-ops)))

(defn- contains-all? [text parts]
  (every? #(str/includes? text %) parts))

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
  (let [{:keys [text spawn-count spawn-ask-count ask-count send-count
                response-ops program-ops]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        handles? (contains-all? text (map #(str ":relay-" %) (range 1 9)))
        delegations (+ spawn-count spawn-ask-count)
        communications (+ spawn-ask-count ask-count send-count)
        meaning? (and (str/includes? final "museum")
                      (or (str/includes? final "five") (str/includes? final "5"))
                      (str/includes? final "storm"))
        changed? (not (str/includes? final
                                     "the museum closes at five because the winter storm is approaching"))]
    {:success (and (:ok response) handles? meaning? changed?
                   (>= delegations 8) (>= communications 8))
     :orchestration (and handles? (>= delegations 8)
                         (>= communications 8))
     :scheme (and handles? (>= delegations 8))
     :evidence {:all-relay-handles handles?
                :spawn-count spawn-count
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
        delegations (+ llm-self-count leaf-llm-count)
        meaning? (and (str/includes? final "museum")
                      (or (str/includes? final "five") (str/includes? final "5"))
                      (str/includes? final "storm"))
        changed? (not (str/includes? final
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
       (group-by (juxt :model :game))
       (map (fn [[[model game] rows]]
              {:model model
               :game game
               :attempts (count rows)
               :successes (count (filter :success rows))
               :orchestration (count (filter :orchestration rows))
               :scheme (count (filter :scheme rows))
               :errors (count (remove :ok rows))
               :cost-usd (reduce + 0.0 (keep :cost-usd rows))}))
       (sort-by (juxt :model :game))
       vec))

(defn- markdown-summary [summary scores]
  (str
   "# Orchestration Games Results\n\n"
   "Scores are computed from saved responses and traces. Treat the helper score "
   "as conservative preliminary scoring; raw evidence is retained beside each "
   "trial for manual audit.\n\n"
   "| Model | Game | Success | Orchestration | Scheme | Errors | Cost |\n"
   "|---|---:|---:|---:|---:|---:|---:|\n"
   (apply str
          (for [{:keys [model game attempts successes orchestration scheme errors cost-usd]} summary]
            (format "| `%s` | %s | %d/%d | %d/%d | %d/%d | %d | $%.4f |\n"
                    model game successes attempts orchestration attempts scheme attempts
                    errors (double (or cost-usd 0.0)))))
   "\n## Trial Evidence\n\n"
   (apply str
          (for [{:keys [model game attempt success orchestration scheme dir evidence notes]} scores]
            (format "- `%s` %s attempt %s: success=%s orchestration=%s scheme=%s evidence=`%s` dir=`%s`%s\n"
                    model game attempt success orchestration scheme (pr-str evidence) dir
                    (if notes (str " notes=" (pr-str notes)) ""))))))

(defn score! [output-root]
  (let [scores (keep score-dir (run-dirs output-root))
        summary (summarize scores)]
    (write-edn! (str (io/file output-root "scores.edn")) scores)
    (write-edn! (str (io/file output-root "summary.edn")) summary)
    (spit (io/file output-root "summary.md") (markdown-summary summary scores))
    (println (markdown-summary summary scores))
    {:scores scores :summary summary}))

(defn run-command! [opts]
  (write-prompts! (:output-root opts))
  (doseq [[game model attempt] (planned-trials opts)]
    (when-not (contains? prompts game)
      (throw (ex-info (str "Unknown game: " game) {:game game})))
    (run-trial! opts game model attempt))
  (when-not (or (:dry-run opts) (:no-score opts))
    (score! (:output-root opts))))

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

      (= command "prompts")
      (do
        (write-prompts! (:output-root options))
        (shutdown-agents)
        (System/exit 0))

      :else
      (do
        (binding [*out* *err*]
          (println "Unknown command:" command)
          (println (usage summary)))
        (System/exit 2)))))
