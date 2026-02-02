(ns spell.core
  "Spell — wiring layer.
   Assembles components (eval, hooks, llm, prompt, tools) into the default configuration.
   Re-exports key vars for public API."
  (:require [spell.llm :as llm-engine]
            [spell.prompt :as prompt]
            [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.tools :as tools]))

(declare llm)
(declare leaf-llm)

;; Re-export from spell.eval
(def spell-eval eval/spell-eval)
(def run-spell eval/run-spell)
(def spell-error? eval/spell-error?)

;; Re-export from spell.hooks
(def prepend-hooks-to-llm hooks/prepend-hooks-to-llm)
(def recurse hooks/recurse)
(def with-env hooks/with-env)
(def prefix-prompt hooks/prefix-prompt)
(def with-env-hints hooks/with-env-hints)

;; Re-export from spell.llm
(def make-llm llm-engine/make-llm)
(def make-leaf-llm llm-engine/make-leaf-llm)

;; =============================================================================
;; Default llm function
;; =============================================================================

(def leaf-llm
  "Plain text-in/text-out LLM. No Spell parsing, evaluation, tools, or sub-agents."
  (make-leaf-llm {}))

(def llm
  "The default llm function with all standard tools and self-recursion."
  (make-llm {:tools tools/default-tools
             :llms  {'llm      #'llm
                     'leaf-llm {:fn  leaf-llm
                                :doc "Plain text LLM — no code execution. Takes a prompt string, returns a response string."}}}))

;; Set root binding for eval/*builtins* — used by direct spell-eval/run-spell calls
;; (tests, REPL) that don't go through an llm function.
(alter-var-root #'eval/*builtins*
  (constantly (merge eval/core-builtins
                     {'llm #'llm
                      'leaf-llm leaf-llm
                      'prepend-hooks-to-llm #'prepend-hooks-to-llm
                      'recurse #'recurse
                      'prefix-prompt #'prefix-prompt
                      'with-env with-env
                      'with-env-hints with-env-hints
                      'read-name tools/read-name
                      'bash tools/run-bash
                      'read-file tools/read-file
                      'write-file tools/write-file
                      'str-replace tools/str-replace})))

;; Set the default system prompt for backwards compatibility
(alter-var-root #'prompt/system-prompt
  (constantly (prompt/generate-system-prompt tools/default-tools
                {'llm #'llm
                 'leaf-llm {:fn leaf-llm :doc "Plain text LLM — no code execution. Takes a prompt string, returns a response string."}})))
