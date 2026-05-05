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
   "Run a sealed-bid auction for a painting. Spawn three bidder agents "
   "(handles :bidder-a, :bidder-b, :bidder-c) using the agents/ namespace. "
   "Each bidder independently picks one integer bid in 100-1000 and sends "
   "it back to you. Collect the three bids and announce the winner. The "
   "bids must come from the bidder agents, not from you."))

(def auction-llm-self-prompt
  (str
   "Run a sealed-bid auction for a painting. Pick three bids by making "
   "three independent !llm-self subcalls — one per bidder. Each subcall "
   "should return an integer bid in 100-1000. Capture the three bids and "
   "announce the winner. The bids must come from the !llm-self children, "
   "not from you."))

(def twenty-questions-agents-prompt
  (str
   "Play 20 questions, with a guess limit of 8. The secret animal is "
   "elephant. Spawn one worker agent (handle :worker) using the agents/ "
   "namespace; the worker does NOT know the answer, only that it is an "
   "animal. Drive the conversation: the worker asks yes/no questions or "
   "makes a guess; you reply yes/no truthfully. Stop when the worker "
   "guesses elephant or runs out of guesses. The worker's questions and "
   "guesses must come from the worker agent, not from you."))

(def twenty-questions-llm-self-prompt
  (str
   "Play 20 questions, with a guess limit of 8. The secret animal is "
   "elephant. Use !llm-self subcalls to act as a single worker that does "
   "NOT see the answer. Each round, !llm-self with the question/answer "
   "history so far; the child returns its next yes/no question or final "
   "guess. You answer yes/no truthfully and check guesses against "
   "elephant. Stop when the worker guesses elephant or runs out of "
   "guesses. The worker's outputs must come from !llm-self children, not "
   "from you."))

(def telephone-agents-prompt
  (str
   "Play a game of telephone with 8 relay agents. Initial message: "
   "\"The museum closes at five because the winter storm is approaching.\" "
   "Spawn :relay-1 through :relay-8 using the agents/ namespace. Each "
   "relay receives the previous wording, rephrases it (preserve the "
   "meaning, change the words), and forwards to the next relay. "
   ":relay-8 sends the final wording back to you. Report the initial "
   "and final wordings. The rephrasing must come from the relay agents, "
   "not from you."))

(def telephone-llm-self-prompt
  (str
   "Play a game of telephone with 8 stages. Initial message: "
   "\"The museum closes at five because the winter storm is approaching.\" "
   "Make 8 sequential !llm-self subcalls; each takes the previous "
   "wording and returns a rephrased wording that preserves the meaning. "
   "Capture each wording in a Spell binding. Report the initial and "
   "final wordings. The rephrasing must come from the !llm-self "
   "children, not from you."))

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

(def init-trailing-by-game
  "Per-game seed for the init program's trailing expression. This kicks the
   first turn off with a real first-step action, so the model arrives mid-
   task with concrete state in scope rather than facing an empty prefix.
   Empirically the bare `(!extend)` default is too weak — GLM-5.1 (and even
   gpt-5.4) fall back to greeting/nil responses on it."
  {:auction-agents
   (str "(!call-now bid-a (agents/!spawn-ask "
        "\"Pick a single integer bid in the range 100 to 1000. "
        "Send only that integer back to the parent (no prose).\" "
        ":bidder-a))")
   :auction-llm-self
   (str "(!call-now bid-a (!llm-self (wrap-cat "
        "\"Pick a single integer bid in the range 100 to 1000. "
        "Return only that integer as the trailing expression value (no prose).\")))")
   :twenty-questions-agents
   (str "(!call-now msg-0 (agents/!spawn-ask "
        "\"You are the worker in a 20-questions game. The answer is an animal "
        "(8 guesses max). You do NOT know the answer. Ask one yes/no question, "
        "or guess the animal. Send your question or guess back to the parent.\" "
        ":worker))")
   :twenty-questions-llm-self
   (str "(!call-now first-question (!llm-self (wrap-cat "
        "\"You are the worker in a 20-questions game. The answer is an animal "
        "(8 guesses max). You do NOT know the answer. Ask one yes/no question, "
        "or guess the animal. Return only your question or guess as the "
        "trailing expression value (a string).\")))")
   :telephone-agents
   (str "(!call-now wording-1 (agents/!spawn-ask "
        "\"Rephrase this sentence (preserve meaning, change wording): "
        "'The museum closes at five because the winter storm is approaching.' "
        "Send only the rephrased sentence back to the parent.\" "
        ":relay-1))")
   :telephone-llm-self
   (str "(!call-now wording-1 (!llm-self (wrap-cat "
        "\"Rephrase this sentence (preserve meaning, change wording): "
        "'The museum closes at five because the winter storm is approaching.' "
        "Return only the rephrased sentence as the trailing expression value (a string).\")))")})

(defn- build-seeded-init
  "Build a Spell init program. The init seeds the program's trailing
   expression with a real first-step action for the game (see
   init-trailing-by-game). After the first action evaluates, the model is
   handed the prefix in mid-task with concrete state already in scope."
  [game prompt]
  (let [escaped-prompt (escape-spell-string prompt)
        trailing (or (get init-trailing-by-game game) "(!extend)")]
    (str "(quine completion (eval (do "
         "(quine prompt \"" escaped-prompt "\") "
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

(defn- bool-count [re text]
  (count (re-seq re text)))

(defn- final-text [dir response]
  (str (:result response) "\n" (trace-response-text dir)))

(defn- trace-summary [dir]
  (let [trace (read-trace dir)
        text (trace-response-text dir)]
    {:node-count (count (:nodes trace))
     :spawn-count (+ (bool-count #"agents/spawn" text)
                     (bool-count #"agents/!spawn-ask" text))
     :ask-count (bool-count #"agents/!ask" text)
     :reply-ask-count (bool-count #"agents/!reply-ask" text)
     :send-count (bool-count #"agents/send" text)
     :llm-self-count (bool-count #"!llm-self" text)
     :leaf-llm-count (bool-count #"leaf-llm" text)
     :text text}))

(defn- contains-all? [text parts]
  (every? #(str/includes? text %) parts))

;; ---------- :agents-variant scorers ----------

(defn- score-auction-agents [dir response]
  (let [{:keys [text spawn-count ask-count send-count]} (trace-summary dir)
        all-handles? (contains-all? text [":bidder-a" ":bidder-b" ":bidder-c"])
        winner? (boolean (re-find #"(?i)winner|winning" (final-text dir response)))]
    {:success (and (:ok response) all-handles? winner?
                   (>= spawn-count 3)
                   (>= (+ ask-count send-count) 3))
     :orchestration (and all-handles? (>= spawn-count 3)
                         (>= (+ ask-count send-count) 3))
     :scheme (and all-handles? (>= spawn-count 3))
     :evidence {:all-bidder-handles all-handles?
                :spawn-count spawn-count
                :ask-count ask-count
                :send-count send-count
                :winner-mentioned winner?}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-twenty-questions-agents [dir response]
  (let [{:keys [text spawn-count ask-count reply-ask-count send-count]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        worker? (str/includes? text ":worker")
        guessed? (and (str/includes? final "elephant")
                      (boolean (re-find #"guess|guessed|worker-guessed" final)))]
    {:success (and (:ok response) worker? guessed? (>= spawn-count 1)
                   (>= (+ ask-count reply-ask-count) 1))
     :orchestration (and worker? (>= spawn-count 1)
                         (>= (+ ask-count reply-ask-count send-count) 1))
     :scheme (and worker? (>= (+ ask-count reply-ask-count) 1))
     :evidence {:worker-handle worker?
                :spawn-count spawn-count
                :ask-count ask-count
                :reply-ask-count reply-ask-count
                :send-count send-count
                :elephant-and-guess-mentioned guessed?}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-telephone-agents [dir response]
  (let [{:keys [text spawn-count ask-count send-count]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        handles? (contains-all? text (map #(str ":relay-" %) (range 1 9)))
        meaning? (and (str/includes? final "museum")
                      (or (str/includes? final "five") (str/includes? final "5"))
                      (str/includes? final "storm"))
        changed? (not (str/includes? final
                                     "the museum closes at five because the winter storm is approaching"))]
    {:success (and (:ok response) handles? meaning? changed?
                   (>= spawn-count 8) (>= (+ ask-count send-count) 8))
     :orchestration (and handles? (>= spawn-count 8)
                         (>= (+ ask-count send-count) 8))
     :scheme (and handles? (>= spawn-count 8))
     :evidence {:all-relay-handles handles?
                :spawn-count spawn-count
                :ask-count ask-count
                :send-count send-count
                :meaning-keywords meaning?
                :not-identical changed?}
     :notes (when-not (:ok response) (:error response))}))

;; ---------- :llm-self-variant scorers ----------

(defn- score-auction-llm-self [dir response]
  (let [{:keys [llm-self-count leaf-llm-count]} (trace-summary dir)
        delegations (+ llm-self-count leaf-llm-count)
        winner? (boolean (re-find #"(?i)winner|winning" (final-text dir response)))]
    {:success (and (:ok response) winner? (>= delegations 3))
     :orchestration (>= delegations 3)
     :scheme (>= delegations 3)
     :evidence {:llm-self-count llm-self-count
                :leaf-llm-count leaf-llm-count
                :winner-mentioned winner?}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-twenty-questions-llm-self [dir response]
  (let [{:keys [llm-self-count leaf-llm-count]} (trace-summary dir)
        final (str/lower-case (final-text dir response))
        delegations (+ llm-self-count leaf-llm-count)
        guessed? (and (str/includes? final "elephant")
                      (boolean (re-find #"guess|guessed|guesser" final)))]
    {:success (and (:ok response) guessed? (>= delegations 1))
     :orchestration (>= delegations 1)
     :scheme (>= delegations 1)
     :evidence {:llm-self-count llm-self-count
                :leaf-llm-count leaf-llm-count
                :elephant-and-guess-mentioned guessed?}
     :notes (when-not (:ok response) (:error response))}))

(defn- score-telephone-llm-self [dir response]
  (let [{:keys [llm-self-count leaf-llm-count]} (trace-summary dir)
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
                :not-identical changed?}
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
