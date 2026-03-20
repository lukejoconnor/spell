(ns spell.react
  "React namespace loader.

   Function bodies are sourced from config/spl-lib/react.spl and
   converted into Spell function maps at startup."
  (:require [clojure.java.io :as io]
            [spell.parse :as parse]))

(def ^:private react-spl-path
  "config/spl-lib/react.spl")

(def ^:private react-docs
  {:short-docs "ReAct loop (mini-swe-agent style): run a bash-action agent loop."
   :docs {:guide "REACT - ReAct loop with bash actions (effect namespace).
Mirrors DefaultAgent from github.com/SWE-agent/mini-swe-agent.

  (react/run task-or-opts)  - run ReAct loop for a task

Use (!describe react :run) for detailed docs.

run: mini-swe-agent style bash loop. LM produces THOUGHT + ```mswea_bash_command blocks.
Commands execute via io/sh. Loop terminates on COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT or max-steps.
  (react/run \"Fix the failing test in test_foo.py\")
  (react/run {:task \"...\" :max-steps 20})
  Returns {:exit_status \"submitted\"|\"LimitsExceeded\" :submission str}
"
          :detail
          {:run "(react/run task-or-opts) - mini-swe-agent style bash agent loop.
opts:
  string           - task text
  :task            - task text (required if opts map)
  :max-steps       - max steps before LimitsExceeded (default: 30, 0 = unlimited)
  :verbose         - print each step (default: false)

Execution model (matches DefaultAgent from github.com/SWE-agent/mini-swe-agent):
1. system_template: THOUGHT + ```mswea_bash_command instructs model format
2. instance_template: task + workflow + submit instructions
3. loop: leaf-llm messages -> extract ```mswea_bash_command block
4. If no command: feed format_error_template back as next user message
5. Execute command via io/sh, format as <returncode>/<output>, loop
6. Terminate when output contains COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT or max-steps

Returns: {:exit_status \"submitted\"|\"LimitsExceeded\" :submission str}

Requires agent profile with io/ support."}}})

(def ^:private react-requires
  {:run ['strings 'io]})

(defn- defn-form? [form]
  (and (seq? form) (= 'defn (first form))))

(defn- resolve-react-file []
  (let [cwd-file (io/file react-spl-path)
        env-file (when-let [spell-root (System/getenv "SPELL_ROOT")]
                   (io/file spell-root react-spl-path))
        classpath-root (when-let [src-url (io/resource "spell/react.clj")]
                         (-> src-url io/file .getParentFile .getParentFile .getParentFile))
        classpath-file (when classpath-root
                         (io/file classpath-root react-spl-path))
        candidates (remove nil? [cwd-file env-file classpath-file])]
    (or (first (filter #(.exists ^java.io.File %) candidates))
        (throw (ex-info "react.spl file not found"
                        {:path react-spl-path
                         :cwd  (.getAbsolutePath cwd-file)})))))

(defn- form->spell-fn [form]
  (let [[_ fn-name params & body] form]
    [(keyword (clojure.core/name fn-name))
     {:spell/fn true
      :params   params
      :body     body}]))

(defn- load-react-fns []
  (let [file    (resolve-react-file)
        forms   (parse/read-all (slurp file))
        entries (->> forms
                     (filter defn-form?)
                     (map form->spell-fn)
                     vec)
        fns-map (into {} entries)]
    (into {}
          (map (fn [[k fn-map]]
                 [k (assoc fn-map :requires (get react-requires k []))]))
          fns-map)))

(def react
  "ReAct loop namespace (bash-action agent, mini-swe-agent style)."
  (merge react-docs (load-react-fns)))
