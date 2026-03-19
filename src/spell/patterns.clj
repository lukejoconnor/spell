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
  {:short-docs "Reusable orchestration patterns: check-result, clean-prompt, ralph, team, fix-loop, relay, react."
   :docs {:guide "PATTERNS - Reusable orchestration patterns (effect namespace).

  (patterns/check-result prompt answer)  - verify answer with leaf-llm
  (patterns/clean-prompt raw-text)       - clean up messy text, then execute it
  (patterns/ralph opts)                  - future-based retry orchestrator
  (patterns/team goal-or-opts)           - planner + parallel worktree team orchestrator
  (patterns/fix-loop issue)              - test-driven code fixing loop (reflector + worker agents)
  (patterns/relay opts)                  - fresh-worker reasoning rounds with fresh verification
  (patterns/react task-or-opts)          - ReAct loop (Yao et al. 2022): web search + reasoning

Use (!describe patterns :fn-name) for detailed docs on any function.

check-result: Verifies an answer using leaf-llm. Returns {:ok answer} or {:wrong msg}.
  (patterns/check-result \"What is 2+2?\" 4)            ;; => {:ok 4}
  (patterns/check-result \"Capital of France?\" \"London\") ;; => {:wrong \"London is...\"

clean-prompt: Cleans up a raw prompt (voice-to-text, quick notes) via leaf-llm, then runs it.
  '(patterns/clean-prompt \"waht is the captal of franc... like the big city\")
  leaf-llm infers intent and rewrites; !llm-self executes the cleaned prompt.
  Accepts a string or quine form (serializes non-strings automatically).

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
blocking/send-await inside a future, and the caller waits via !ask-await:
reflector proposes diagnosis + test spec,
worker applies edits, and the loop retries until tests pass or retries are exhausted.
  '(!call-now result (patterns/fix-loop issue))
  Returns {:pass true} or {:fail \"reason\"}

relay: Reasoning relay with fresh context each round. Each round registers a new
worker, passes forward compressed prior reports, and if a worker claims :solved,
the pattern registers a fresh verifier to check the answer independently.
  '(!call-now result (patterns/relay problem))
  Returns {:solved true|false :answer any? :rounds [...]}

All patterns/ calls are effect functions - quote them in the trailing expression.

Common mistakes:

1. calling check-result outside the trailing expression: must be quoted like all effect calls
2. using team without an io-capable agent profile: workers and verifier need io/ and agents/; blocking/ is future-only and !ask-await is a builtin

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
    :ralph "(patterns/ralph opts) - future-based retry orchestrator.
opts:
  string                   - task text
  :task                    - task text (required if opts map)
  :test-fn                 - predicate over worker result (default: (:ok result))
  :max-retries             - retry limit (default: 3)
  :worker-prompt           - custom worker prompt
Sends {:pass result} or {:fail last-result} to caller.
Requires agent profile with agents/ support. Uses future-only blocking/ helpers internally."
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

Requires agent profile with io/ and agents/ support.
Uses core strings/ plus future-only blocking/ helpers internally."
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
4. Wait from caller turn with !ask-await
5. Loop runs tests, wakes worker to edit code, reruns tests, and retries with
   updated diagnosis + git diff context until pass or retries exhausted

Reflector output contract:
  {:resolved boolean
   :diagnosis string
   :test-output string
   :panic boolean
   :reset-worker boolean}

Requires agent profile with io/ and agents/ support.
Uses core strings/ plus future-only blocking/ helpers internally.

Example:
  '(!call-now result (patterns/fix-loop
    issue-description))"
    :relay "(patterns/relay opts) - fresh-worker reasoning rounds with fresh verification.
opts:
  string                   - problem statement
  :problem                 - problem statement (required if opts map)
  :max-rounds              - max worker rounds before giving up (default: 5)

Execution model:
1. Register a fresh dormant worker for each round
2. Send {:kind :solve ... :previous-reports [...]} via blocking/send-await
3. Normalize worker output into report entries tagged with :worker-handle
4. When a worker reports :solved, register a fresh verifier for that attempt
5. Verifier independently confirms or rejects the claimed answer
6. Returns {:solved true :answer ... :rounds [...]} or {:solved false :rounds [...]}

Workers and verifiers may optionally message prior workers named in accumulated
reports. Requires agent profile with agents/ support and future-only blocking/
helpers."
    :react "(patterns/react task-or-opts) - mini-swe-agent style ReAct loop (bash actions).
Mirrors DefaultAgent from github.com/SWE-agent/mini-swe-agent.

opts:
  string           - task text
  :task            - task text (required if opts map)
  :max-steps       - max steps before LimitsExceeded (default: 30, 0 = unlimited)

Execution model (mirrors DefaultAgent.run/step/query/execute_actions):
1. Build system prompt + instance template (task)
2. loop: assemble conversation string -> leaf-llm -> extract ```mswea_bash_command block
3. If no command: feed format error back (mirrors FormatError exception)
4. Execute command via io/sh (mirrors env.execute)
5. Format observation: <returncode>N</returncode><output>...</output> (truncated at 10000 chars)
6. Terminate when output contains COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT or max-steps reached

Returns: {:exit_status \"submitted\"|\"LimitsExceeded\" :submission str}

Example:
  '(patterns/react \"Fix the failing test in test_foo.py\")"
    }})

(def ^:private pattern-requires
  "Machine-readable namespace requirements for public patterns.
   Core namespaces like strings/ are always available. Future-only blocking/
   is provided by the evaluator, so it is documented here but never needs to
   appear in an agent's :namespaces map."
  {:check-result ['strings]
   :clean-prompt []
   :ralph ['agents 'blocking]
   :team ['strings 'io 'agents 'blocking]
   :fix-loop ['strings 'io 'agents 'blocking]
   :relay ['agents 'blocking]
   :react ['strings 'io]})

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

(defn- attach-pattern-requires
  [fns-map]
  (into {}
        (map (fn [[k fn-map]]
               [k (assoc fn-map :requires (get pattern-requires k []))]))
        fns-map))

(def patterns
  "Reusable orchestration patterns (Spell-specific)."
  (merge patterns-docs
         (attach-pattern-requires (load-pattern-fns))))
