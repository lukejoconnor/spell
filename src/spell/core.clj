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
;; Re-export from spell.llm
(def describe llm-engine/describe)

;; =============================================================================
;; All namespaces (for system prompt generation)
;; =============================================================================

;; Add core namespaces and describe-fn to default builtins.
;; These are always available (not gated behind eval's second pass).
(alter-var-root #'eval/*builtins*
  (fn [builtins]
    (merge builtins
           {'describe-fn llm-engine/describe}
           llm-engine/core-namespaces)))

(def all-namespaces
  "Default effect namespaces for the root llm.
   Core namespaces (strings, math, builtins) are always available via make-llm
   and don't need to be listed here."
  {'io io/io-namespace
   'globals globals/globals-namespace
   'agents comm/agents-namespace
   'futures comm/futures-namespace
   'patterns stdlib/patterns})
