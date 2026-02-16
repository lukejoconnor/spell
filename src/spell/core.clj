(ns spell.core
  "Spell — wiring layer.
   Assembles components (eval, hooks, llm, prompt) into the default configuration.
   Re-exports key vars for public API."
  (:require [spell.comm :as comm]
            [spell.globals :as globals]
            [spell.llm :as llm-engine]
            [spell.prompt :as prompt]
            [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.io :as io]
            [spell.stdlib :as stdlib]))

(declare llm)
(declare leaf-llm)

;; Re-export from spell.eval
(def spell-eval eval/spell-eval)
(def run-spell eval/run-spell)
;; Result map helpers
(def ok? eval/ok?)
(def err? eval/err?)
(def result-value eval/result-value)
;; Re-export from spell.hooks
(def prepend-hooks-to-llm hooks/prepend-hooks-to-llm)
(def recurse hooks/recurse)
(def with-env hooks/with-env)
(def prefix-prompt hooks/prefix-prompt)
(def with-env-hints hooks/with-env-hints)

;; Re-export from spell.llm
(def make-llm llm-engine/make-llm)
(def make-leaf-llm llm-engine/make-leaf-llm)
(def format-error-for-recovery llm-engine/format-error-for-recovery)

;; Re-export from spell.llm
(def describe llm-engine/describe)

;; =============================================================================
;; LLM variants
;; =============================================================================

(def leaf-llm
  "Plain text-in/text-out LLM. No Spell parsing, evaluation, tools, or sub-agents."
  (make-leaf-llm {}))

;; =============================================================================
;; All namespaces (for system prompt generation)
;; =============================================================================

(def all-namespaces
  "All available namespaces: io + stdlib + agents + futures + builtins.
   Note: io/ is included here for the default llm (REPL/test use).
   Agent configs control which namespaces are available."
  (merge {'io io/io-namespace
          'globals globals/globals-namespace
          'agents comm/agents-namespace
          'futures comm/futures-namespace
          'builtins prompt/builtins-namespace}
         stdlib/all-namespaces))

;; =============================================================================
;; Default llm function
;; =============================================================================

(def llm
  "The default llm function with all standard tools via namespaces."
  (make-llm {:namespaces all-namespaces
             :llm-var #'llm}))

;; Set root binding for eval/*builtins* — used by direct spell-eval/run-spell calls
;; (tests, REPL) that don't go through an llm function.
;; Note: seqs, fns, and bit- ops are in core-builtins (matching Clojure).
(alter-var-root #'eval/*builtins*
  (constantly (merge eval/core-builtins
                     {;; Pure namespaces
                      'strings stdlib/strings
                      'math stdlib/math
                      'patterns stdlib/patterns
                      'builtins prompt/builtins-namespace
                      'describe-fn describe
                      'prepend-hooks-to-llm #'prepend-hooks-to-llm
                      'recurse #'recurse
                      'prefix-prompt #'prefix-prompt
                      'with-env with-env
                      'with-env-hints with-env-hints})))

;; Effect builtins — only available inside eval's second pass (double-evaluation).
;; Contains non-deterministic and side-effectful operations:
;; LLM calls, communication, concurrency, IO, globals.
(alter-var-root #'eval/*effect-builtins*
  (constantly {'llm #'llm
               'leaf-llm leaf-llm
               'io io/io-namespace
               'globals globals/globals-namespace
               'agents comm/agents-namespace
               'futures comm/futures-namespace}))
