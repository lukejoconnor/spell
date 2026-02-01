(ns spell.cli
  "Command-line interface for Spell."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [spell.core :as spell]
            [spell.eval :as eval]
            [spell.provider :as provider])
  (:gen-class))

(def model-aliases
  {"haiku"  "claude-3-5-haiku-20241022"
   "sonnet" "claude-sonnet-4-20250514"
   "opus"   "claude-opus-4-5-20251101"})

(def provider-prefixes
  #{"ollama" "chatgpt" "openai"})

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
  "Load a .spl example file by name. Returns prompt string or nil."
  [name]
  (when-let [dir (find-examples-dir)]
    (let [f (io/file dir (str name ".spl"))]
      (when (.exists f)
        (str/trim (slurp f))))))

(defn load-file-prompt
  "Load a .spl file from a path. Returns prompt string or nil."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (str/trim (slurp f)))))

(def cli-options
  [["-t" "--test" "Use dummy LLM provider (returns 'hello world')"]
   ["-e" "--example NAME" "Run a named example from examples/"]
   ["-m" "--model MODEL" "Model spec: haiku, sonnet (default), opus, ollama:<model>, chatgpt:<model>"]
   ["-d" "--depth DEPTH" "Max recursion depth (default: 8, 0 = unlimited)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be non-negative"]]
   ["-b" "--budget DOLLARS" "Max spend in dollars (halts if exceeded)"
    :parse-fn #(Double/parseDouble %)
    :validate [pos? "Must be positive"]]
   ["-v" "--verbose" "Show raw LLM response"]
   ["-h" "--help" "Show this help"]])

(defn spl-file? [arg]
  (str/ends-with? arg ".spl"))

(defn usage [options-summary]
  (->> (concat
         ["Spell - A Lisp for LLM self-orchestration"
          ""
          "Usage: spell [options] <prompt>"
          "       spell [options] <file.spl>"
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
          "  spell -m chatgpt:gpt-4o 'Return 42'"
          "  spell examples/hello-world.spl"
          "  spell -e hello-world"
          "  spell -e twenty-questions -m opus -d 40"]
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
      (if-let [prompt (load-example (:example options))]
        {:prompt prompt :options options}
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

(defn- make-provider [{:keys [test model]}]
  (if test
    (provider/dummy-provider {:response "(def return \"hello world\")))"})
    (let [{:keys [provider model]} (if model
                                     (parse-model-spec model)
                                     {:provider nil :model nil})
          resolved-model (when model (resolve-model model))]
      (case provider
        "ollama"
        (provider/ollama-provider (cond-> {}
                                   resolved-model (assoc :model resolved-model)))

        ("chatgpt" "openai")
        (provider/openai-provider (cond-> {}
                                   resolved-model (assoc :model resolved-model)))

        ;; default: anthropic
        (provider/anthropic-provider (cond-> {}
                                      resolved-model (assoc :model resolved-model)))))))

(defn run-prompt [prompt {:keys [depth verbose budget] :as opts}]
  (let [max-depth (cond
                    (nil? depth) 8      ; default
                    (zero? depth) nil   ; 0 means unlimited
                    :else depth)
        provider (make-provider opts)
        usage-atom (atom {:by-model {}})]
    (provider/with-provider provider
      (binding [eval/*verbose* verbose
                eval/*max-llm-depth* max-depth
                provider/*usage* usage-atom
                provider/*budget* budget]
        (try
          {:result (spell/llm prompt) :usage usage-atom}
          (catch Exception e
            {:error (.getMessage e)
             :error-data (ex-data e)
             :usage usage-atom}))))))

(defn- print-usage [usage-atom]
  (let [{:keys [by-model total]} (provider/usage-summary usage-atom)]
    (when (pos? (:calls total 0))
      (println)
      (println "=== Token Usage ===")
      (when (> (count by-model) 1)
        (doseq [[model stats] (sort-by key by-model)]
          (println (format "  %s: %,d in / %,d out (%d calls)%s"
                     model
                     (:input_tokens stats)
                     (:output_tokens stats)
                     (:calls stats)
                     (if-let [c (:cost stats)] (format " $%.4f" c) "")))))
      (println (format "  Total: %,d in / %,d out (%d calls)%s"
                 (:input_tokens total)
                 (:output_tokens total)
                 (:calls total)
                 (if-let [c (:cost total)] (format " $%.4f" c) ""))))))

(defn -main [& args]
  (let [{:keys [prompt options exit-message ok?]} (validate-args args)]
    (if exit-message
      (do
        (println exit-message)
        (System/exit (if ok? 0 1)))
      (let [{:keys [result error error-data usage]} (run-prompt prompt options)]
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
            (System/exit 0)))))))
