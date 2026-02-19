(ns spell.core
  "Spell — wiring layer.
   Assembles components (eval, llm, stdlib) into the default configuration.
   Re-exports key vars for public API."
  (:require [spell.comm :as comm]
            [spell.globals :as globals]
            [spell.llm :as llm-engine]
            [spell.eval :as eval]
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
  "Default effect namespaces for the root llm.
   Core namespaces (strings, math, builtins) are always available via make-llm
   and don't need to be listed here."
  {'io io/io-namespace
   'globals globals/globals-namespace
   'agents comm/agents-namespace
   'futures comm/futures-namespace
   'patterns stdlib/patterns})

;; =============================================================================
;; Default llm function
;; =============================================================================

(def llm
  "The default llm function with all standard tools via namespaces."
  (make-llm {:namespaces all-namespaces
             :llm-var #'llm}))

;; Effect builtins map - exposed for testing
(def effect-builtins
  "Effect namespaces and functions (io, globals, agents, futures, patterns, llm, leaf-llm).
   Used by the eval builtin to merge effects in the second pass."
  {'llm #'llm
   'leaf-llm leaf-llm
   'io io/io-namespace
   'globals globals/globals-namespace
   'agents comm/agents-namespace
   'futures comm/futures-namespace
   'patterns stdlib/patterns})

;; Set root binding for eval/*builtins* — used by direct spell-eval/run-spell calls
;; (tests, REPL) that don't go through an llm function.
;; Note: seqs, fns, and bit- ops are in core-builtins (matching Clojure).
(let [pure-builtins (merge eval/core-builtins
                           {;; Core namespaces (always available)
                            'strings stdlib/strings
                            'math stdlib/math
                            'builtins stdlib/builtins-namespace
                            'describe-fn describe})
      ;; Create eval builtin using make-eval (requires llm-engine loaded)
      eval-builtin (llm-engine/make-eval pure-builtins effect-builtins)
      full-builtins (assoc pure-builtins 'eval eval-builtin)]
  (alter-var-root #'eval/*builtins* (constantly full-builtins)))
