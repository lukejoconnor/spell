(ns spell.context-folding
  "Context Folding namespace loader.

   Function bodies are sourced from config/spl-lib/context_folding.spl and
   converted into Spell function maps at startup.

   Based on: Context Folding (arxiv 2510.11967) / FoldAgent."
  (:require [clojure.java.io :as io]
            [spell.parse :as parse]))

(def ^:private context-folding-spl-path
  "config/spl-lib/context_folding.spl")

(def ^:private context-folding-docs
  {:short-docs "Context Folding agent: reduces LLM context via branching (arxiv 2510.11967)."
   :docs {:guide "CONTEXT-FOLDING — Context-aware branching agent.
Mirrors FoldAgent from github.com/sunnweiwei/FoldAgent.

  (context-folding/run task-or-opts)  - run Context Folding loop for a task

Use (!describe context-folding :run) for detailed docs.

run: main planning loop (pure orchestrator). Spawns branch sub-loops for each
sub-task; branch results are folded back as observations. Terminates on
Finish[answer] or max-steps.
  (context-folding/run \"Were Scott Derrickson and Ed Wood of the same nationality?\")
  (context-folding/run {:task \"...\" :max-steps 5 :max-branch-steps 7})
  Returns the final answer string.
"
          :detail
          {:run "(context-folding/run task-or-opts) - Context Folding planning loop.
opts:
  string              - task text
  :task               - task text (required if opts map)
  :max-steps          - main loop limit (default: 5)
  :max-branch-steps   - sub-loop limit per branch (default: 7)
  :verbose            - print each step (default: false)
  :temperature        - LLM temperature (default: nil = provider default; 0 = deterministic)
  :mode               - :search (default) or :bash (branches use bash tool-calling via io/sh)

Execution model (matches FoldAgent from arxiv 2510.11967):
1. Main loop: plain-text prompt completion via leaf-llm
2. Main loop actions: Branch[description | prompt] or Finish[answer]
3. Each Branch spawns run-branch sub-loop with full Search/Lookup/Finish ReAct
4. Branch result folded into main context as: [Branch result: ...]
5. Terminate on Finish[answer] or max-steps

Returns: the final answer string.

Requires agent profile with web/ support."}}})

(def ^:private context-folding-requires
  {:run                    ['strings 'web]
   :run-branch             ['strings 'web]
   :run-branch-bash        ['io 'react]
   :cf-web-search          ['web]
   :cf-web-lookup          ['strings]
   :cf-format-bash-observation []
   :cf-bash-branch-complete?   ['strings]
   :cf-extract-bash-result     ['strings]})

(defn- defn-form? [form]
  (and (seq? form) (= 'defn (first form))))

(defn- resolve-context-folding-file []
  (let [cwd-file (io/file context-folding-spl-path)
        env-file (when-let [spell-root (System/getenv "SPELL_ROOT")]
                   (io/file spell-root context-folding-spl-path))
        classpath-root (when-let [src-url (io/resource "spell/context_folding.clj")]
                         (-> src-url io/file .getParentFile .getParentFile .getParentFile))
        classpath-file (when classpath-root
                         (io/file classpath-root context-folding-spl-path))
        candidates (remove nil? [cwd-file env-file classpath-file])]
    (or (first (filter #(.exists ^java.io.File %) candidates))
        (throw (ex-info "context_folding.spl file not found"
                        {:path context-folding-spl-path
                         :cwd  (.getAbsolutePath cwd-file)})))))

(defn- form->spell-fn [form]
  (let [[_ fn-name params & body] form]
    [(keyword (clojure.core/name fn-name))
     {:spell/fn true
      :params   params
      :body     body}]))

(defn- load-context-folding-fns []
  (let [file    (resolve-context-folding-file)
        forms   (parse/read-all (slurp file))
        entries (->> forms
                     (filter defn-form?)
                     (map form->spell-fn)
                     vec)
        fns-map (into {} entries)]
    (into {}
          (map (fn [[k fn-map]]
                 [k (assoc fn-map :requires (get context-folding-requires k []))]))
          fns-map)))

(def context-folding
  "Context Folding namespace (branching orchestrator agent)."
  (merge context-folding-docs (load-context-folding-fns)))
