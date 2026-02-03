(ns spell.core
  "Spell — wiring layer.
   Assembles components (eval, hooks, llm, prompt, tools) into the default configuration.
   Re-exports key vars for public API."
  (:require [spell.llm :as llm-engine]
            [spell.prompt :as prompt]
            [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.registry :as registry]
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
;; Default registry
;; =============================================================================

(def leaf-llm
  "Plain text-in/text-out LLM. No Spell parsing, evaluation, tools, or sub-agents."
  (make-leaf-llm {}))

(def default-registry
  "Default registry with tools and agents."
  {:name 'tools
   :desc {:bash "Execute shell command. Returns {:exit N :out \"...\" :err \"...\"}."
          :read-file "Read file contents. Returns {:ok content} or {:error msg}."
          :write-file "Write content to file. Returns {:ok path} or {:error msg}."
          :str-replace "Replace unique string in file. Returns {:ok path} or {:error msg}."
          :read-name "Read name from name.txt."
          :leaf-llm "Plain text LLM — no code execution."}
   :items {:bash {:type :tool :fn tools/run-bash}
           :read-file {:type :tool :fn tools/read-file}
           :write-file {:type :tool :fn tools/write-file}
           :str-replace {:type :tool :fn tools/str-replace}
           :read-name {:type :tool :fn tools/read-name}
           :leaf-llm {:type :agent :fn leaf-llm}}})

;; =============================================================================
;; Default llm function
;; =============================================================================

(def llm
  "The default llm function with all standard tools via registry."
  (make-llm {:registries [default-registry]
             :llm-var #'llm}))

;; Set root binding for eval/*builtins* — used by direct spell-eval/run-spell calls
;; (tests, REPL) that don't go through an llm function.
;; Includes tools directly for backwards compatibility.
(alter-var-root #'eval/*builtins*
  (constantly (merge eval/core-builtins
                     {'llm #'llm
                      'leaf-llm leaf-llm
                      'tools default-registry
                      'describe registry/describe
                      'prepend-hooks-to-llm #'prepend-hooks-to-llm
                      'recurse #'recurse
                      'prefix-prompt #'prefix-prompt
                      'with-env with-env
                      'with-env-hints with-env-hints
                      ;; Legacy: tools available directly in builtins for REPL/test use
                      'read-name tools/read-name
                      'bash tools/run-bash
                      'read-file tools/read-file
                      'write-file tools/write-file
                      'str-replace tools/str-replace})))

;; Set the default system prompt for backwards compatibility
(alter-var-root #'prompt/system-prompt
  (constantly (prompt/generate-system-prompt [default-registry])))
