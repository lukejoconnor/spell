(ns spell.patterns
  "Pattern namespace loader.

   Pattern function bodies are sourced from config/spl-lib/patterns.spl and
   converted into Spell function maps at startup."
  (:require [clojure.java.io :as io]
            [spell.parse :as parse]))

(def ^:private patterns-spl-path
  "Filesystem path to Spell pattern definitions."
  "config/spl-lib/patterns.spl")

(def ^:private patterns-docs
  {:short-docs "Reusable orchestration patterns: check-result, clean-prompt, explore, ralph, team, fix-loop."
   :docs {:guide "PATTERNS - Reusable orchestration patterns (effect namespace).

  (patterns/check-result prompt answer)  - verify answer with leaf-llm
  (patterns/clean-prompt raw-text)       - clean up messy text, then execute it
  (patterns/explore question)            - one-shot codebase exploration agent
  (patterns/ralph opts)                  - future-based retry orchestrator
  (patterns/team goal-or-opts)           - planner + parallel worktree team orchestrator
  (patterns/fix-loop issue)              - test-driven code fixing loop (reflector + worker agents)

Use (!describe patterns :fn-name) for detailed docs on any function.

check-result: Verifies an answer using leaf-llm. Returns {:ok answer} or {:wrong msg}.
  (patterns/check-result \"What is 2+2?\" 4)            ;; => {:ok 4}
  (patterns/check-result \"Capital of France?\" \"London\") ;; => {:wrong \"London is...\"

clean-prompt: Cleans up a raw prompt (voice-to-text, quick notes) via leaf-llm, then runs it.
  '(patterns/clean-prompt \"waht is the captal of franc... like the big city\")
  leaf-llm infers intent and rewrites; !llm-self executes the cleaned prompt.
  Accepts a string or quine form (serializes non-strings automatically).

explore: One-shot delegation to a child exploration agent. Spawns a child that greps, reads, and analyzes, then returns structured findings.
  '(!call-now findings (patterns/explore \"Where is authentication handled?\"))
  Returns {:answer \"...\" :files [\"src/auth.py\" ...]}

ralph: Retry orchestrator that runs blocking completion waits inside a future, so
the caller's agent trace stays responsive. Spawns a worker, sends task/retry
messages, waits via blocking/send-await, and sends
final {:pass result} or {:fail last-result} to the caller.
  '(!call-now started (patterns/ralph \"fix failing tests\"))
  ;; later receives msg with {:pass ...} or {:fail ...}

team: Multi-task implementation orchestrator. A planner decomposes the goal,
the scheduler executes dependency waves in parallel git worktrees, and a
verifier approves merges or resolves conflicts on the integration branch.
  '(!call-now result (patterns/team \"Implement feature X\"))
  Returns {:status :completed|:partial|:failed :tasks [...] :branch \"spell-team-...\"}

fix-loop: Test-driven code fixing loop. Registers a persistent reflector agent and
a persistent worker agent for the run. The root loop coordinates both via
blocking/send-await inside a future, and the caller waits via futures/!ask-await:
reflector proposes diagnosis + test command,
worker applies edits, and the loop retries until tests pass or retries are exhausted.
  '(!call-now result (patterns/fix-loop issue))
  Returns {:pass true} or {:fail \"reason\"}

All patterns/ calls are effect functions - quote them in the trailing expression.

Common mistakes:

1. calling check-result outside the trailing expression: must be quoted like all effect calls
2. forgetting !call-now with explore: '(patterns/explore \"...\") runs the agent but you lose the return value; use '(!call-now findings (patterns/explore \"...\"))
3. using explore for simple tasks: explore spawns a child agent - overkill for a quick io/read-file or io/sh
4. using team without an io-capable agent profile: workers and verifier need io/, agents/, futures/, and blocking/

In examples, | marks cursor position in a completion. It is doc-only; do not type it into code.

Example - verify then correct:

1. Compute an answer and check it.
  ...(def answer 42)
  |'(!call-now verdict (patterns/check-result \"What is 6 * 9?\" answer))

2. Next turn: handle the verdict.
  ...(def verdict {:wrong \"6 * 9 = 54, not 42\"})
  |(def answer 54)
  '(!call-now verdict (patterns/check-result \"What is 6 * 9?\" answer))
"}
   :detail
   {:check-result "(patterns/check-result prompt answer) - verify answer with leaf-llm, returns {:ok answer} or {:wrong msg}"
    :clean-prompt "(patterns/clean-prompt raw-prompt) - clean up raw prompt via leaf-llm and execute it"
    :explore "(patterns/explore question) - one-shot exploration agent, returns {:answer \"...\" :files [...]}"
    :ralph "(patterns/ralph opts) - future-based retry orchestrator.
opts:
  string                   - task text
  :task                    - task text (required if opts map)
  :test-fn                 - predicate over worker result (default: (:ok result))
  :max-retries             - retry limit (default: 3)
  :worker-prompt           - custom worker prompt
Sends {:pass result} or {:fail last-result} to caller."
    :team "(patterns/team goal-or-opts) - planner + scheduler + worktree workers + verifier.
primary argument:
  goal-or-opts             - string goal or opts map

opts map:
  :goal                    - required goal text
  :shared-context          - optional shared instructions for all tasks
  :max-retries             - retries per task before failure (default: 2)

Execution model:
1. Commit dirty state (if any), create an integration branch
2. Planner decomposes the goal into task maps with dependency edges
3. Scheduler executes dependency waves in parallel git worktrees
4. Scheduler attempts eager merges into the integration branch
5. Verifier approves merged state or resolves conflicts/rejects for retry
6. Returns {:status :completed|:partial|:failed :tasks [...] :branch ...}

Requires agent profile with io/, agents/, futures/, and blocking/ support."
    :fix-loop "(patterns/fix-loop issue) - test-driven code fixing loop.
primary argument:
  issue                    - description of the problem to fix (required)

optional map form (advanced/internal use):
  :issue                   - description of the problem to fix (required)
  :max-retries             - max fix attempts (default: 5)

Execution model:
1. Commit dirty state (if any), create a fix branch
2. Register dormant reflector + worker agents for this run
3. Run loop in a future; use blocking/send-await for reflector/worker turns
4. Wait from caller turn with futures/!ask-await
5. Loop runs tests, wakes worker to edit code, reruns tests, and retries with
   updated diagnosis + git diff context until pass or retries exhausted

Reflector output contract:
  {:diagnosis string :test string :panic boolean}

Requires agent profile with io/ and agents/ namespaces.

Example:
  '(!call-now result (patterns/fix-loop
    issue-description))"}})

(defn- defn-form?
  [form]
  (and (seq? form)
       (= 'defn (first form))))

(defn- resolve-patterns-file
  "Resolve patterns.spl path across normal CLI and benchmark workspace CWDs.
   Lookup order:
   1) current working directory (config/spl-lib/patterns.spl)
   2) $SPELL_ROOT/config/spl-lib/patterns.spl (if SPELL_ROOT is set)
   3) classpath-derived project root from spell/patterns.clj resource"
  []
  (let [cwd-file (io/file patterns-spl-path)
        env-file (when-let [spell-root (System/getenv "SPELL_ROOT")]
                   (io/file spell-root patterns-spl-path))
        classpath-root (when-let [src-url (io/resource "spell/patterns.clj")]
                         (-> src-url io/file .getParentFile .getParentFile .getParentFile))
        classpath-file (when classpath-root
                         (io/file classpath-root patterns-spl-path))
        candidates (remove nil? [cwd-file env-file classpath-file])]
    (or (first (filter #(.exists ^java.io.File %) candidates))
        (throw (ex-info "patterns.spl file not found"
                        {:path patterns-spl-path
                         :cwd (.getAbsolutePath cwd-file)
                         :spell-root (some-> env-file .getAbsolutePath)
                         :classpath-root (some-> classpath-file .getAbsolutePath)})))))

(defn- form->spell-fn
  "Convert a top-level (defn name [params] body...) form into {:spell/fn ...}."
  [form]
  (let [[_ fn-name params & body] form]
    (when-not (symbol? fn-name)
      (throw (ex-info "patterns.spl defn name must be a symbol"
                      {:form form :name fn-name})))
    (when-not (vector? params)
      (throw (ex-info "patterns.spl defn params must be a vector"
                      {:form form :name fn-name :params params})))
    [(keyword (clojure.core/name fn-name))
     {:spell/fn true
      :params params
      :body body}]))

(defn- load-pattern-fns
  []
  (let [file (resolve-patterns-file)]
    (let [forms (parse/read-all (slurp file))
          entries (->> forms
                       (filter defn-form?)
                       (map form->spell-fn)
                       vec)
          fns-map (into {} entries)]
      (when (empty? entries)
        (throw (ex-info "patterns.spl did not contain any top-level defn forms"
                        {:path (.getPath file)})))
      (when (not= (count entries) (count fns-map))
        (throw (ex-info "patterns.spl contains duplicate defn names"
                        {:path (.getPath file)
                         :names (map first entries)})))
      fns-map)))

(def patterns
  "Reusable orchestration patterns (Spell-specific)."
  (merge patterns-docs (load-pattern-fns)))
