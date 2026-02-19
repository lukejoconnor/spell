(ns spell.cli
  "Command-line interface for Spell."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [spell.agent :as agent]
            [spell.eval :as eval]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.trace :as trace]
            [spell.user :as user])
  (:gen-class))

(def model-aliases
  {"haiku"   "claude-haiku-4-5-20251001"
   "sonnet"  "claude-sonnet-4-5-20250929"
   "opus"    "claude-opus-4-5-20251101"
   "opus46"  "claude-opus-4-6"
   "o3"      "o3"
   "o4-mini" "o4-mini"
   "gpt52"   "gpt-5.2"})

(def provider-prefixes
  #{"ollama" "chatgpt" "openai" "anthropic" "kimi" "moonshot"})

(defn parse-model-spec
  "Parse 'provider:model' into {:provider str :model str}.
   If no known provider prefix, returns {:provider nil :model input}.
   Examples:
     ollama:smollm2:135m  -> {:provider \"ollama\" :model \"smollm2:135m\"}
     chatgpt:gpt-4o       -> {:provider \"chatgpt\" :model \"gpt-4o\"}
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
   ["-m" "--model MODEL" "Model spec: haiku, sonnet, opus, ollama:<model>, openai:<model>, user (default: openai:gpt-5.2)"]
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
          "  spell -m openai:gpt-4o 'Return 42'"
          "  spell examples/hello-world.spl"
          "  spell -e hello-world"
          "  spell -e twenty-questions -m opus -d 40"
          "  spell -a agents/coder.agent.edn 'Fix the bug'"]
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
    (provider/dummy-provider {:response "\"hello world\""})

    (= model "user")
    (provider/user-provider)

    :else
    (let [{:keys [provider model]} (if model
                                     (parse-model-spec model)
                                     {:provider "openai" :model "gpt-5.2"})
          resolved-model (when model (resolve-model model))
          base-opts (cond-> {}
                      resolved-model (assoc :model resolved-model)
                      max-tokens (assoc :max-tokens max-tokens))]
      (case provider
        "ollama"
        (provider/ollama-provider base-opts)

        ("chatgpt" "openai")
        (provider/openai-provider (cond-> base-opts
                                    responses-api (assoc :use-responses-api true)))

        ("kimi" "moonshot")
        (provider/kimi-provider base-opts)

        ;; anthropic (explicit or default)
        ("anthropic" nil)
        (provider/anthropic-provider base-opts)))))

(defn- trace-dir-name []
  (let [fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH-mm-ss")]
    (str "traces/" (.format fmt (java.util.Date.)))))

(defn- make-agent-llm
  "Create an llm function from an agent config.

   Supports :eval and :format options:
   - :eval true (default): Spell evaluation with namespaces
   - :eval false: plain text LLM (no Spell parsing/eval)
   - :format: optional format spec for output validation"
  [agent-config]
  (let [{:keys [system model budget recover resolve-namespaces-fn resolve-llms-fn eval format max-retries
                prefill? thinking reasoning-effort verbosity]} agent-config
        ;; :eval defaults to true if not specified
        eval? (if (nil? eval) true eval)
        ;; Resolve namespaces with make-llm available for sub-agents
        namespaces (when (and eval? resolve-namespaces-fn)
                     (resolve-namespaces-fn llm/make-llm))
        ;; Resolve llms/ namespace if specified
        llms-ns (when (and eval? resolve-llms-fn)
                  (resolve-llms-fn llm/make-llm model))
        all-namespaces (cond-> (or namespaces {})
                         llms-ns (assoc 'llms llms-ns))
        ;; Create base LLM function based on :eval setting
        base-llm (if eval?
                   ;; Spell evaluation mode
                   (let [config (cond-> {}
                                  (seq all-namespaces) (assoc :namespaces all-namespaces)
                                  model (assoc :model model)
                                  system (assoc :system system)
                                  (some? recover) (assoc :recover recover)
                                  format (assoc :format format)
                                  (some? prefill?) (assoc :prefill? prefill?)
                                  thinking (assoc :thinking thinking)
                                  reasoning-effort (assoc :reasoning-effort reasoning-effort)
                                  verbosity (assoc :verbosity verbosity))]
                     (llm/make-llm config))
                   ;; Leaf mode (no eval)
                   (llm/make-leaf-llm (cond-> {}
                                        system (assoc :system system)
                                        model (assoc :model model))))]
    ;; Wrap with format validation if specified
    (if format
      (llm/wrap-with-format base-llm {:format format
                                       :eval? eval?
                                       :max-retries (or max-retries 3)})
      base-llm)))

(defn run-prompt [prompt {:keys [depth verbose log budget trace agent thinking reasoning-effort verbosity] :as opts}]
  (let [max-depth (cond
                    (nil? depth) nil    ; default: no depth limit
                    (zero? depth) nil   ; 0 also means unlimited
                    :else depth)
        provider (make-provider opts)
        usage-atom (atom {:by-model {}})
        trace-atom (when trace (trace/new-trace))
        ;; Determine prefill support: provider capability minus thinking override
        prefill? (and (provider/supports-prefill provider) (not thinking))
        ;; Load agent if specified, otherwise use default agent
        ;; CLI flags override agent config values
        agent-config (cond-> (if agent
                               (agent/load-agent-config agent)
                               (agent/default-agent-config))
                       (some? prefill?) (assoc :prefill? prefill?)
                       thinking (assoc :thinking thinking)
                       reasoning-effort (assoc :reasoning-effort reasoning-effort)
                       verbosity (assoc :verbosity verbosity))
        llm-fn (make-agent-llm agent-config)
        ;; Budget: CLI flag > agent config > dynamic var default
        ;; -b 0 means unlimited (nil)
        effective-budget (cond
                           (nil? budget) (or (:budget agent-config)
                                             provider/*budget*)
                           (zero? budget) nil
                           :else budget)
        ;; --log implies -v
        effective-verbose (or verbose (some? log))
        log-writer (when log (io/writer (io/file log) :append true))]
    ;; Register :user agent for interactive CLI (terminal stdin only)
    (when (. System console)
      (user/register-user-agent!))
    (try
      (provider/with-provider provider
        (binding [eval/*verbose* effective-verbose
                  eval/*log-writer* log-writer
                  eval/*max-llm-depth* max-depth
                  provider/*usage* usage-atom
                  provider/*budget* effective-budget
                  provider/*retries* (or (:retries agent-config) provider/*retries*)
                  trace/*trace* trace-atom]
          (let [result (try
                         {:result (llm-fn prompt) :usage usage-atom}
                         (catch Exception e
                           {:error (.getMessage e)
                            :error-data (ex-data e)
                            :usage usage-atom}))]
            (if trace-atom
              (let [dir (trace/write-trace! @trace-atom (trace-dir-name))]
                (assoc result :trace-dir dir))
              result))))
      (finally
        (when log-writer
          (.close ^java.io.Writer log-writer))))))

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
      (do
        (run-shell (:setup options))
        (let [{:keys [result error error-data usage trace-dir]} (run-prompt prompt options)]
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
