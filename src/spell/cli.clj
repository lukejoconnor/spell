(ns spell.cli
  "Command-line interface for Spell."
  (:require [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [spell.core :as spell]
            [spell.llm :as llm])
  (:gen-class))

(def model-aliases
  {"haiku"  "claude-3-5-haiku-20241022"
   "sonnet" "claude-sonnet-4-20250514"
   "opus"   "claude-opus-4-5-20251101"})

(defn resolve-model [model]
  (get model-aliases model model))

(def cli-options
  [["-t" "--test" "Use dummy LLM provider (returns 'hello world')"]
   ["-m" "--model MODEL" "Model: haiku, sonnet (default), opus, or full ID"]
   ["-d" "--depth DEPTH" "Max recursion depth (default: 8, 0 = unlimited)"
    :parse-fn #(Integer/parseInt %)
    :validate [#(>= % 0) "Must be non-negative"]]
   ["-v" "--verbose" "Show raw LLM response"]
   ["-h" "--help" "Show this help"]])

(defn usage [options-summary]
  (->> ["Spell - A Lisp for LLM self-orchestration"
        ""
        "Usage: spell [options] <prompt>"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  spell 'Return 42'"
        "  spell -t 'Test prompt'"
        "  spell -m haiku 'Add 1 and 2'"]
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

      (= 1 (count arguments))
      {:prompt (first arguments) :options options}

      :else
      {:exit-message (usage summary) :ok? false})))

(defn run-prompt [prompt {:keys [test model depth verbose]}]
  (let [resolved-model (when model (resolve-model model))
        max-depth (cond
                    (nil? depth) 8      ; default
                    (zero? depth) nil   ; 0 means unlimited
                    :else depth)
        provider (if test
                   (llm/dummy-provider {:response "(def return \"hello world\")))"})
                   (llm/anthropic-provider (cond-> {}
                                             resolved-model (assoc :model resolved-model))))]
    (llm/with-provider provider
      (binding [spell/*verbose* verbose
                spell/*max-llm-depth* max-depth]
        (try
          {:result (spell/llm prompt)}
          (catch Exception e
            {:error (.getMessage e)}))))))

(defn -main [& args]
  (let [{:keys [prompt options exit-message ok?]} (validate-args args)]
    (if exit-message
      (do
        (println exit-message)
        (System/exit (if ok? 0 1)))
      (let [{:keys [result error]} (run-prompt prompt options)]
        (if error
          (do
            (binding [*out* *err*]
              (println "Error:" error))
            (System/exit 1))
          (do
            (println result)
            (System/exit 0)))))))
