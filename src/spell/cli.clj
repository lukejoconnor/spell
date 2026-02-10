(ns spell.cli
  "Command-line interface for Spell."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [spell.agent :as agent]
            [spell.eval :as eval]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.trace :as trace])
  (:gen-class))

(def model-aliases
  {"haiku"  "claude-3-5-haiku-20241022"
   "sonnet" "claude-sonnet-4-20250514"
   "opus"   "claude-opus-4-5-20251101"})

(def provider-prefixes
  #{"ollama" "chatgpt" "openai" "anthropic"})

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
   ["-d" "--depth DEPTH" "Max recursion depth (default: 8, 0 = unlimited)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be non-negative"]]
   ["-b" "--budget DOLLARS" "Max spend in dollars (halts if exceeded)"
    :parse-fn #(Double/parseDouble %)
    :validate [pos? "Must be positive"]]
   ["-M" "--max-tokens TOKENS" "Max tokens per LLM response (default: 4096)"
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]
   ["-T" "--trace" "Record execution trace to traces/"]
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

(defn- make-provider [{:keys [test model max-tokens]}]
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
        (provider/openai-provider base-opts)

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
  (let [{:keys [system model budget recover resolve-namespaces-fn hooks eval format max-retries]} agent-config
        ;; :eval defaults to true if not specified
        eval? (if (nil? eval) true eval)
        ;; Resolve namespaces with make-llm available for sub-agents
        namespaces (when (and eval? resolve-namespaces-fn)
                     (resolve-namespaces-fn llm/make-llm))
        ;; Create base LLM function based on :eval setting
        base-llm (if eval?
                   ;; Spell evaluation mode
                   (let [config (cond-> {}
                                  namespaces (assoc :namespaces namespaces)
                                  model (assoc :model model)
                                  (some? recover) (assoc :recover recover)
                                  format (assoc :format format))]
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

(defn run-prompt [prompt {:keys [depth verbose budget trace agent] :as opts}]
  (let [max-depth (cond
                    (nil? depth) 8      ; default
                    (zero? depth) nil   ; 0 means unlimited
                    :else depth)
        provider (make-provider opts)
        usage-atom (atom {:by-model {}})
        trace-atom (when trace (trace/new-trace))
        ;; Load agent if specified, otherwise use default agent
        llm-fn (make-agent-llm (if agent
                                 (agent/load-agent-config agent)
                                 (agent/default-agent-config)))]
    (provider/with-provider provider
      (binding [eval/*verbose* verbose
                eval/*max-llm-depth* max-depth
                provider/*usage* usage-atom
                provider/*budget* budget
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
            result))))))

(defn- format-cache-stats [stats]
  (let [cache-write (:cache_creation_input_tokens stats 0)
        cache-read (:cache_read_input_tokens stats 0)]
    (when (pos? (+ cache-write cache-read))
      (format " [cache: %,d write, %,d read]" cache-write cache-read))))

(defn- print-usage [usage-atom]
  (let [{:keys [by-model total]} (provider/usage-summary usage-atom)]
    (when (pos? (:calls total 0))
      (println)
      (println "=== Token Usage ===")
      (when (> (count by-model) 1)
        (doseq [[model stats] (sort-by key by-model)]
          (println (format "  %s: %,d in / %,d out (%d calls)%s%s"
                     model
                     (:input_tokens stats 0)
                     (:output_tokens stats 0)
                     (:calls stats 0)
                     (if-let [c (:cost stats)] (format " $%.4f" c) "")
                     (or (format-cache-stats stats) "")))))
      (println (format "  Total: %,d in / %,d out (%d calls)%s%s"
                 (:input_tokens total 0)
                 (:output_tokens total 0)
                 (:calls total 0)
                 (if-let [c (:cost total)] (format " $%.4f" c) "")
                 (or (format-cache-stats total) ""))))))

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
          (when (and (:verbose options) usage)
            (print-usage usage))
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
