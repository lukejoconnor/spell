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
  {:short-docs "Reusable orchestration patterns: check-result, clean-prompt, explore, fix-loop."
   :docs {:guide "PATTERNS - Reusable orchestration patterns (effect namespace).

  (patterns/check-result prompt answer)  - verify answer with leaf-llm
  (patterns/clean-prompt raw-text)       - clean up messy text, then execute it
  (patterns/explore question)            - one-shot codebase exploration agent
  (patterns/fix-loop opts)               - test-driven code fixing loop with reflector

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

fix-loop: Test-driven code fixing loop. Runs tests, spawns a worker to fix code, uses a reflector
to diagnose failures and decide whether to keep or discard changes. Loops until tests pass or retries exhausted.
  '(!call-now result (patterns/fix-loop {:test \"pytest tests/ -x\" :issue issue :reflector llms/reflector}))
  Returns {:pass true} or {:fail \"reason\"}

All patterns/ calls are effect functions - quote them in the trailing expression.

Common mistakes:

1. calling check-result outside the trailing expression: must be quoted like all effect calls
2. forgetting !call-now with explore: '(patterns/explore \"...\") runs the agent but you lose the return value; use '(!call-now findings (patterns/explore \"...\"))
3. using explore for simple tasks: explore spawns a child agent - overkill for a quick io/read-file or io/sh

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
    :fix-loop "(patterns/fix-loop opts) - test-driven code fixing loop.
opts map:
  :test       - shell command to run tests (required)
  :issue      - description of the problem to fix (required)
  :reflector  - reflector LLM function, e.g. llms/reflector (required)
  :worker     - worker LLM function (default: !llm-self)
  :max-retries - max fix attempts (default: 5)

Creates a git branch, runs tests, and enters a retry loop:
1. Worker agent edits files to fix the issue
2. Tests are run
3. If tests pass: commits and returns {:pass true}
4. If tests fail: reflector analyzes diff + test output, returns diagnosis
   - :keep-changes true: commit partial progress, continue with same changes
   - :keep-changes false: hard reset working branch to last commit, try fresh
   - :panic true: give up, return {:fail diagnosis}

The reflector must return {:diagnosis str :keep-changes bool :panic bool}.
Worker and reflector run as nested LLM calls (not spawned agents).

Example:
  '(!call-now result (patterns/fix-loop
    {:test \"python -m pytest tests/test_foo.py -x\"
     :issue issue-description
     :reflector llms/reflector
     :max-retries 3}))"}})

(defn- defn-form?
  [form]
  (and (seq? form)
       (= 'defn (first form))))

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
  (let [file (io/file patterns-spl-path)]
    (when-not (.exists file)
      (throw (ex-info "patterns.spl file not found"
                      {:path (.getPath file)})))
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
