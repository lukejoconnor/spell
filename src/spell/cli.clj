(ns spell.cli
  "Command-line interface for Spell."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [spell.api :as api]
            [spell.mcp.cli :as mcp-cli]
            [spell.model-spec :as model-spec]
            [spell.provider :as provider]
            [spell.trace :as spell-trace])
  (:gen-class))

(defn parse-model-spec
  "Parse 'provider:model' into {:provider str :model str}."
  [s]
  (model-spec/parse-model-spec s))

(defn resolve-model [model]
  (model-spec/resolve-model-alias model))

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
   ["-i" "--init PROGRAM" "Run a complete Spell program string directly instead of wrapping a natural-language prompt"]
   ["-I" "--init-file FILE" "Run a complete Spell program file directly instead of wrapping it as a natural-language prompt"]
   ["-a" "--agent-profile FILE" "Use agent profile from .agent.edn file"]
   ["-m" "--model MODEL" "Model/provider spec: codex-tc:<model>, openai-tc:<model>, anthropic-pf:<model>, anthropic-tc:<model>, fireworks:<model>, fireworks-tc:<model>, ollama:<model>, user (default: codex-tc:gpt-5.3)"]
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
   ["-R" "--reasoning-effort EFFORT" "OpenAI reasoning effort (none, low, medium, high, xhigh, max)"
    :validate [#(contains? #{"none" "low" "medium" "high" "xhigh" "max"} %)
               "Must be none, low, medium, high, xhigh, or max"]]
   [nil "--verbosity LEVEL" "OpenAI verbosity (low, auto)"
    :validate [#(contains? #{"low" "auto"} %) "Must be low or auto"]]
   [nil "--suffix-grammar" "Enable prefix-aware OpenAI suffix grammar constraints"]
   [nil "--grammar-max-chars CHARS" "Max generated grammar chars before fallback (default: 2000)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]
   [nil "--responses-api" "Force OpenAI Responses API instead of Chat Completions"]
   ["-T" "--trace" "Record execution trace to a temp dir under java.io.tmpdir/spell-traces/"]
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
          "       spell [options] <file.spl>          # natural-language prompt file"
          "       spell --init '<program>'            # complete Spell program"
          "       spell --init-file <file.spl>        # complete Spell program file"
          "       spell -a <agent-profile.edn> <prompt>"
          "       spell -e <example>"
          ""
          "Options:"
          options-summary
          ""
          "Examples:"
          "  spell 'Return 42'"
          "  spell -t 'Test prompt'"
          "  spell -m codex-tc:gpt-5.3 'Return 42'"
          "  spell -m openai-tc:gpt-5.6-sol 'Return 42'"
          "  spell -m anthropic-tc:claude-opus-4-8 'Return 42'"
          "  spell -m fireworks:glm-5p2 'Return 42'"
          "  spell -m fireworks-tc:kimi-k2p7-code 'Return 42'"
          "  spell examples/hello-world.spl"
          "  spell -t --init '(do (+ 20 22))'"
          "  spell --init-file scratch/my-program.spl"
          "  spell -e hello-world"
          "  spell -e twenty-questions -d 40"
          "  spell -a config/agent-profiles/io-tc.agent.edn 'Fix the bug'"]
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

      (and (:example options) (or (:init options) (:init-file options)))
      {:exit-message "Cannot combine --example with --init or --init-file"
       :ok? false}

      (and (:init options) (:init-file options))
      {:exit-message "Specify only one of --init or --init-file"
       :ok? false}

      (and (:init options) (seq arguments))
      {:exit-message "--init does not accept a positional prompt or file"
       :ok? false}

      (and (:init-file options) (seq arguments))
      {:exit-message "--init-file does not accept a positional prompt or file"
       :ok? false}

      (:init options)
      {:init (:init options) :options options}

      (:init-file options)
      (if-let [init (load-file-prompt (:init-file options))]
        {:init init :options options}
        {:exit-message (str "File not found: " (:init-file options))
         :ok? false})

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
                                     (model-spec/resolve-model-spec model)
                                     {:provider "codex-tc" :model "gpt-5.3-codex"})
          resolved-model model
          base-opts (cond-> {:costs provider/default-costs}
                      resolved-model (assoc :model resolved-model)
                      max-tokens (assoc :max-tokens max-tokens))]
      (case provider
        "ollama"
        (provider/ollama-provider base-opts)

        "codex-tc"
        (provider/codex-tc-provider base-opts)

        "openai-tc"
        (provider/openai-provider (assoc base-opts
                                         :use-responses-api true
                                         :force-tool-call true))

        "fireworks"
        (provider/fireworks-provider base-opts)

        "fireworks-tc"
        (provider/fireworks-tc-provider base-opts)

        ;; anthropic-tc is the default for bare model names
        ("anthropic-tc" nil)
        (provider/anthropic-tc-provider base-opts)

        "anthropic-pf"
        (provider/anthropic-pf-provider base-opts)))))

(defn run-input
  [{:keys [prompt init]}
   {:keys [depth verbose log budget trace agent-profile model thinking reasoning-effort verbosity
           suffix-grammar grammar-max-chars]
    :as opts}
   usage-atom]
  (let [max-depth (cond
                    (nil? depth) nil    ; default: no depth limit
                    (zero? depth) nil   ; 0 also means unlimited
                    :else depth)
        resolved-model (some-> model model-spec/resolve-model-spec :model)
        opus? (and resolved-model (str/includes? resolved-model "opus"))
        thinking (or thinking (when opus? 16384))
        prov (make-provider opts)
        prefill? (and (provider/supports-prefill prov) (not thinking))
        resolved-agent-profile (or agent-profile "config/agent-profiles/cli.agent.edn")
        log-writer (when log (io/writer (io/file log) :append true))]
    (try
      (api/run-internal (cond-> {:model-profile prov
                                 :agent-profile resolved-agent-profile
                                 :log-writer (when (or verbose log) (or log-writer *out*))
                                 :budget (cond
                                           (nil? budget) nil
                                           (zero? budget) 0
                                           :else budget)
                                 :depth max-depth
                                 :prefill? prefill?
                                 :thinking thinking
                                 :reasoning-effort reasoning-effort
                                 :verbosity verbosity
                                 :suffix-grammar? suffix-grammar
                                 :grammar-max-chars grammar-max-chars
                                 :usage-tracker usage-atom}
                          prompt (assoc :prompt prompt)
                          init (assoc :init init)
                          trace (assoc :trace-dir (spell-trace/default-trace-dir))
                          (and (some? (. System console)) (not= model "user"))
                          (assoc :user-reader (io/reader System/in))))
      (finally
        (when log-writer
          (.close ^java.io.Writer log-writer))))))

(defn- format-cache-stats [stats]
  (let [cache-write (:cache_write_input_tokens stats 0)
        cache-read (:cached_input_tokens stats 0)]
    (when (pos? (+ cache-write cache-read))
      (format " [cache: %,d write, %,d read]" cache-write cache-read))))

(defn- format-reasoning-stats [stats]
  (when-let [r (:reasoning_output_tokens stats)]
    (when (pos? r)
      (format " [reasoning: %,d]" r))))

(defn- total-input-tokens [stats]
  (+ (:uncached_input_tokens stats 0)
     (:cached_input_tokens stats 0)
     (:cache_write_input_tokens stats 0)))

(defn- total-output-tokens [stats]
  (+ (:visible_output_tokens stats 0)
     (:reasoning_output_tokens stats 0)))

(defn- format-token-stat [n]
  (when (some? n)
    (let [value (double n)]
      (if (== value (Math/rint value))
        (format "%,d" (long (Math/round value)))
        (format "%,.1f" value)))))

(defn- format-context-stats [stats]
  (when (and (contains? stats :mean_total_tokens)
             (contains? stats :max_total_tokens))
    (format " [context: mean %s / max %s]"
            (format-token-stat (:mean_total_tokens stats))
            (format-token-stat (:max_total_tokens stats)))))

(defn- print-usage [usage-atom]
  (let [{:keys [by-model total]} (provider/usage-summary usage-atom)]
    (when (pos? (:calls total 0))
      (println)
      (println "=== Token Usage ===")
      (when (> (count by-model) 1)
        (doseq [[model stats] (sort-by key by-model)]
          (println (format "  %s: %,d in / %,d out (%d calls)%s%s%s%s"
                     model
                     (total-input-tokens stats)
                     (total-output-tokens stats)
                     (:calls stats 0)
                     (if-let [c (:cost stats)] (format " $%.4f" c) "")
                     (or (format-context-stats stats) "")
                     (or (format-cache-stats stats) "")
                     (or (format-reasoning-stats stats) "")))))
      (println (format "  Total: %,d in / %,d out (%d calls)%s%s%s%s"
                 (total-input-tokens total)
                 (total-output-tokens total)
                 (:calls total 0)
                 (if-let [c (:cost total)] (format " $%.4f" c) "")
                 (or (format-context-stats total) "")
                 (or (format-cache-stats total) "")
                 (or (format-reasoning-stats total) ""))))))

(defn- run-shell [cmd]
  (when cmd
    (let [pb (ProcessBuilder. ["bash" "-c" cmd])
          proc (.start pb)]
      (.waitFor proc)
      (.exitValue proc))))

(defn -main [& args]
  (if (= "mcp" (first args))
    (let [{:keys [status out err]} (mcp-cli/execute (rest args))]
      (when-not (str/blank? out) (println (str/trim-newline out)))
      (when err (binding [*out* *err*] (println "Error:" err)))
      (System/exit status))
    (let [{:keys [prompt init options exit-message ok?]} (validate-args args)]
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
        (let [{:keys [result error error-data usage-tracker trace-dir]} (run-input {:prompt prompt :init init} options usage-atom)
              usage usage-tracker]
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
              (System/exit 0)))))))))
