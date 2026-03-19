(ns spell.core
  "Spell — wiring layer.
   Assembles components (eval, llm, stdlib) into the default configuration.
   Re-exports key vars for public API."
  (:require [spell.runtime :as runtime]
            [spell.globals :as globals]
            [spell.grammar :as grammar]
            [spell.llm :as llm-engine]
            [spell.eval :as eval]
            [spell.io :as io]
            [spell.web :as web]
            [spell.stdlib :as stdlib]))

;; Re-export from spell.eval
(def spell-eval eval/spell-eval)
(def run-spell eval/run-spell)
;; Result map helpers
(def ok? eval/ok?)
(def err? eval/err?)
(def result-value eval/result-value)
;; Re-export from spell.llm
(def compile-agent llm-engine/compile-agent)
(def make-leaf-llm llm-engine/make-leaf-llm)
;; Re-export from spell.grammar
(def suffix-lark-grammar grammar/suffix-lark-grammar)
(def suffix-lark-grammar-stats grammar/suffix-lark-grammar-stats)
(def openai-suffix-grammar-format grammar/openai-suffix-grammar-format)
;; Re-export from spell.stdlib
(def describe stdlib/describe)

;; =============================================================================
;; All namespaces (for system prompt generation)
;; =============================================================================

;; Add core namespaces and describe-fn to default builtins.
;; These are always available (not gated behind eval's second pass).
(alter-var-root #'eval/*builtins*
  (fn [builtins]
    (merge builtins
           {'describe-fn stdlib/describe}
           llm-engine/core-namespaces)))

(def all-namespaces
  "Default effect namespaces for compiled agents.
   Core namespaces (strings, math, builtins) are always available via compile-agent
   and don't need to be listed here."
  {'io io/io-namespace
   'web web/web-namespace
   'globals globals/globals-namespace
   'agents runtime/agents-namespace
   'patterns stdlib/patterns})
