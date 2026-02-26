(ns spell.cli
  "Command-line interface for Spell."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [spell.api :as api]
            [spell.provider :as provider])
  (:gen-class))

(def model-aliases
  {"haiku"   "claude-haiku-4-5-20251001"
   "sonnet"  "claude-sonnet-4-5-20250929"
   "opus"    "claude-opus-4-5-20251101"
   "opus46"  "claude-opus-4-6"
   "o3"      "o3"
   "o4-mini" "o4-mini"
   "gpt52"   "gpt-5.2"
   "gpt53"   "gpt-5.3"})

(def provider-prefixes
  #{"ollama" "chatgpt" "codex" "openclaw" "openai" "anthropic" "kimi" "moonshot"})

(defn parse-model-spec
  "Parse 'provider:model' into {:provider str :model str}.
   If no known provider prefix, returns {:provider nil :model input}.
   Examples:
     ollama:smollm2:135m  -> {:provider \"ollama\" :model \"smollm2:135m\"}
     chatgpt:gpt-5.3-codex -> {:provider \"chatgpt\" :model \"gpt-5.3-codex\"}
     haiku                -> {:provider nil :model \"haiku\"}"
  [s]
  (if-let [idx (str/index-of s ":")]
    (let [prefix (subs s 0 idx)
          rest   (subs s (inc idx))]
      (if (contains? provider-prefixes prefix)
        {:provider prefix :model rest}
        {:provider nil :model s}))
    {:provider nil :model s}))

(defn resolve-model [model]
  (get model-aliases model model))

(defn find-examples-dir
  "Find the examples directory relative to the project root."
  []
  (let [candidates ["examples"
                    (str (System/getProperty "user.dir") "/examples")]]
    (first (filter #(.isDirectory (io/file %)) candidates))))

(defn list-examples
  "List available .spl example files."
  []
  (when-let [dir (find-examples-dir)]
    (->> (.listFiles (io/file dir))
         (filter #(str/ends-with? (.getName %) ".spl"))
         (map #(str/replace (.getName %) ".spl" ""))
         sort)))

(defn load-example
  "Load a .spl example file by name. Returns {:prompt str :setup str? :cleanup str?} or nil."
  [name]
  (when-let [dir (find-examples-dir)]
    (let [spl-file (io/file dir (str name ".spl"))
          setup-file (io/file dir (str name ".setup.sh"))
          cleanup-file (io/file dir (str name ".cleanup.sh"))]
      (when (.exists spl-file)
        {:prompt (str/trim (slurp spl-file))
         :setup (when (.exists setup-file) (str/trim (slurp setup-file)))
         :cleanup (when (.exists cleanup-file) (str/trim (slurp cleanup-file)))}))))

(defn load-file-prompt
  "Load a .spl file from a path. Returns prompt string or nil."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (str/trim (slurp f)))))

(def cli-options
  [["-t" "--test" "Use dummy LLM provider (returns 'hello world')"]
   ["-e" "--example NAME" "Run a named example from examples/"]
   ["-a" "--agent FILE" "Use agent definition from .agent.edn file"]
   ["-m" "--model MODEL" "Model spec: haiku, sonnet, opus, ollama:<model>, codex:<model>, chatgpt:<model>, openai:<model>, openclaw:<model>, user (default: codex:gpt-5.3)"]
   ["-d" "--depth DEPTH" "Max recursion depth (default: unlimited, 0 = unlimited)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be non-negative"]]
   ["-b" "--budget DOLLARS" "Max spend in dollars (default: $1.00, 0 = unlimited)"
    :parse-fn #(Double/parseDouble %)
    :validate [#(>= % 0) "Must be non-negative"]]
   ["-M" "--max-tokens TOKENS" "Max tokens per LLM response (default: 16384)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]
   ["-K" "--thinking BUDGET" "Enable Anthropic adaptive thinking (budget_tokens, e.g. 10000)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]
   ["-R" "--reasoning-effort EFFORT" "OpenAI reasoning effort (low, medium, high)"
    :validate [#(contains? #{"low" "medium" "high"} %) "Must be low, medium, or high"]]
   [nil "--verbosity LEVEL" "OpenAI verbosity (low, auto)"
    :validate [#(contains? #{"low" "auto"} %) "Must be low or auto"]]
   [nil "--suffix-grammar" "Enable prefix-aware OpenAI suffix grammar constraints"]
   [nil "--grammar-max-chars CHARS" "Max generated grammar chars before fallback (default: 2000)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]
   [nil "--responses-api" "Force OpenAI Responses API instead of Chat Completions"]
   ["-T" "--trace" "Record execution trace to traces/"]
   ["-l" "--log FILE" "Log verbose output to FILE (implies -v)"]
   ["-v" "--verbose" "Show raw LLM response"]
   ["-S" "--setup CMD" "Shell command to run before spell execution"]
   ["-C" "--cleanup CMD" "Shell command to run after spell execution"]
   ["-h" "--help" "Show this help"]])

(defn spl-file? [arg]
  (str/ends-with? arg ".spl"))

(defn usage [options-summary]
  (->> (concat
         ["Spell - A Lisp for LLM self-orchestration"
          ""
          "Usage: spell [options] <prompt>"
          "       spell [options] <file.spl>"
          "       spell -a <agent.edn> <prompt>"
          "       spell -e <example>"
          ""
          "Options:"
          options-summary
          ""
          "Examples:"
          "  spell 'Return 42'"
          "  spell -t 'Test prompt'"
          "  spell -m haiku 'Add 1 and 2'"
          "  spell -m ollama:llama3.2 'Return 42'"
          "  spell -m chatgpt:gpt-5.3-codex 'Return 42'"
          "  spell -m openai:gpt-4o 'Return 42'"
          "  spell examples/hello-world.spl"
          "  spell -e hello-world"
          "  spell -e twenty-questions -m opus -d 40"
          "  spell -a config/agents/coder.agent.edn 'Fix the bug'"]
         (when-let [examples (seq (list-examples))]
           [""
            "Available examples:"
            (str "  " (str/join ", " examples))]))
       (str/join \newline)))

(defn error-msg [errors]
  (str "Error:\n" (str/join \newline errors)))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors) :ok? false}

      (:example options)
      (if-let [{:keys [prompt setup cleanup]} (load-example (:example options))]
        {:prompt prompt
         :options (cond-> options
                    (and setup (not (:setup options))) (assoc :setup setup)
                    (and cleanup (not (:cleanup options))) (assoc :cleanup cleanup))}
        {:exit-message (str "Unknown example: " (:example options)
                            (when-let [examples (seq (list-examples))]
                              (str "\nAvailable: " (str/join ", " examples))))
         :ok? false})

      (and (= 1 (count arguments)) (spl-file? (first arguments)))
      (if-let [prompt (load-file-prompt (first arguments))]
        {:prompt prompt :options options}
        {:exit-message (str "File not found: " (first arguments))
         :ok? false})

      (= 1 (count arguments))
      {:prompt (first arguments) :options options}

      :else
      {:exit-message (usage summary) :ok? false})))

(defn- make-provider [{:keys [test model max-tokens responses-api]}]
  (cond
    test
    (provider/test-provider {:response "\"hello world\""})

    (= model "user")
    (provider/user-provider)

    :else
    (let [{:keys [provider model]} (if model
                                     (parse-model-spec model)
                                     {:provider "codex" :model "gpt-5.3"})
          resolved-model (when model (resolve-model model))
          ;; ChatGPT/Codex backend exposes gpt-5.3 as gpt-5.3-codex.
          resolved-model (if (and (#{"chatgpt" "codex"} provider)
                                  (= resolved-model "gpt-5.3"))
                           "gpt-5.3-codex"
                           resolved-model)
          base-opts (cond-> {:costs provider/default-costs}
                      resolved-model (assoc :model resolved-model)
                      max-tokens (assoc :max-tokens max-tokens))]
      (case provider
        "ollama"
        (provider/ollama-provider base-opts)

        "chatgpt"
        (provider/chatgpt-codex-provider base-opts)

        "codex"
        (provider/chatgpt-codex-toolcall-provider base-opts)

        "openai"
        (provider/openai-provider (cond-> base-opts
                                    responses-api (assoc :use-responses-api true)))

        ("kimi" "moonshot")
        (provider/kimi-provider base-opts)

        "openclaw"
        (provider/load-provider "config/providers/openclaw.provider.edn")

        ;; anthropic (explicit or default)
        ("anthropic" nil)
        (provider/anthropic-provider base-opts)))))

(defn run-prompt
  [prompt {:keys [depth verbose log budget trace agent model thinking reasoning-effort verbosity
                  suffix-grammar grammar-max-chars]
           :as opts}
   usage-atom]
  (let [max-depth (cond
                    (nil? depth) nil    ; default: no depth limit
                    (zero? depth) nil   ; 0 also means unlimited
                    :else depth)
        prov (make-provider opts)
        prefill? (and (provider/supports-prefill prov) (not thinking))
        resolved-agent (or agent "config/agents/cli.agent.edn")
        log-writer (when log (io/writer (io/file log) :append true))]
    (api/run {:prompt prompt
              :provider prov
              :agent resolved-agent
              :user? (some? (. System console))
              :verbose (or verbose (some? log))
              :log-writer log-writer
              :budget (cond
                        (nil? budget) nil  ; api/run handles agent/default fallback
                        (zero? budget) 0   ; api/run maps 0 -> nil (unlimited)
                        :else budget)
              :depth max-depth
              :trace trace
              :prefill? prefill?
              :thinking thinking
              :reasoning-effort reasoning-effort
              :verbosity verbosity
              :suffix-grammar? suffix-grammar
              :grammar-max-chars grammar-max-chars
              :usage usage-atom})))

(defn- format-cache-stats [stats]
  (let [cache-write (:cache_creation_input_tokens stats 0)
        cache-read (:cache_read_input_tokens stats 0)]
    (when (pos? (+ cache-write cache-read))
      (format " [cache: %,d write, %,d read]" cache-write cache-read))))

(defn- format-reasoning-stats [stats]
  (when-let [r (:reasoning_tokens stats)]
    (when (pos? r)
      (format " [reasoning: %,d]" r))))

(defn- print-usage [usage-atom]
  (let [{:keys [by-model total]} (provider/usage-summary usage-atom)]
    (when (pos? (:calls total 0))
      (println)
      (println "=== Token Usage ===")
      (when (> (count by-model) 1)
        (doseq [[model stats] (sort-by key by-model)]
          (println (format "  %s: %,d in / %,d out (%d calls)%s%s%s"
                     model
                     (:input_tokens stats 0)
                     (:output_tokens stats 0)
                     (:calls stats 0)
                     (if-let [c (:cost stats)] (format " $%.4f" c) "")
                     (or (format-cache-stats stats) "")
                     (or (format-reasoning-stats stats) "")))))
      (println (format "  Total: %,d in / %,d out (%d calls)%s%s%s"
                 (:input_tokens total 0)
                 (:output_tokens total 0)
                 (:calls total 0)
                 (if-let [c (:cost total)] (format " $%.4f" c) "")
                 (or (format-cache-stats total) "")
                 (or (format-reasoning-stats total) ""))))))

(defn- run-shell [cmd]
  (when cmd
    (let [pb (ProcessBuilder. ["bash" "-c" cmd])
          proc (.start pb)]
      (.waitFor proc)
      (.exitValue proc))))

(defn -main [& args]
  (let [{:keys [prompt options exit-message ok?]} (validate-args args)]
    (if exit-message
      (do
        (println exit-message)
        (System/exit (if ok? 0 1)))
      (let [usage-atom (atom {:by-model {}})
            cost-printed? (atom false)
            shutdown-hook (Thread.
                            (fn []
                              (when-not @cost-printed?
                                (let [{:keys [total]} (provider/usage-summary usage-atom)]
                                  (when-let [c (:cost total)]
                                    (binding [*out* *err*]
                                      (println (format "\nCost: $%.4f" c))))))))]
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
        (run-shell (:setup options))
        (let [{:keys [result error error-data usage trace-dir]} (run-prompt prompt options usage-atom)]
          (run-shell (:cleanup options))
          (when trace-dir
            (binding [*out* *err*]
              (println (str "Trace: " trace-dir))))
          (when usage
            (if (:verbose options)
              (print-usage usage)
              ;; Always print cost to stderr
              (let [{:keys [total]} (provider/usage-summary usage)]
                (when-let [c (:cost total)]
                  (binding [*out* *err*]
                    (println (format "Cost: $%.4f" c)))))))
          (reset! cost-printed? true)
          (if error
            (do
              (when (and (= :budget-exceeded (:type error-data))
                         (not (:verbose options))
                         usage)
                (print-usage usage))
              (binding [*out* *err*]
                (println "Error:" error))
              (System/exit 1))
            (do
              (println result)
              (System/exit 0))))))))
