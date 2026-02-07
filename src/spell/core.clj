(ns spell.core
  "Spell — wiring layer.
   Assembles components (eval, hooks, llm, prompt, tools) into the default configuration.
   Re-exports key vars for public API."
  (:require [spell.llm :as llm-engine]
            [spell.prompt :as prompt]
            [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.tools :as tools]
            [spell.stdlib :as stdlib]))

(declare llm)
(declare leaf-llm)

;; Re-export from spell.eval
(def spell-eval eval/spell-eval)
(def run-spell eval/run-spell)
;; Result map helpers for memo-based error recovery
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
(def make-form-llm llm-engine/make-form-llm)
(def format-error-for-recovery llm-engine/format-error-for-recovery)

;; Re-export from spell.llm
(def describe llm-engine/describe)

;; =============================================================================
;; tools namespace
;; =============================================================================

(def leaf-llm
  "Plain text-in/text-out LLM. No Spell parsing, evaluation, tools, or sub-agents."
  (make-leaf-llm {}))

(def tools
  "Tools namespace with shell, file I/O, and LLM variants."
  {:docs {:bash "Execute shell command. Returns {:exit N :out \"...\" :err \"...\"}."
          :read-file "Read file with line numbers. Returns {line-num \"content\" ...} or {:error msg}. Optional start/end args for range."
          :write-file "Write content to file. Returns {:ok path} or {:error msg}."
          :str-replace "Replace unique string in file. Returns {:ok path} or {:error msg}."
          :replace-lines "Replace line range in file. Takes path, start, end, new-content. Returns {:ok path} or {:error msg}."
          :read-name "Read name from name.txt."
          :leaf-llm "Plain text LLM — no code execution."
          :make-form-llm "Create validated LLM. Options: :validate (fn), :format-doc (string), :max-retries (int, default 3), :system, :model. Returns fn that retries on validation failure."}
   :bash tools/run-bash
   :read-file tools/read-file
   :write-file tools/write-file
   :str-replace tools/str-replace
   :replace-lines tools/replace-lines
   :read-name tools/read-name
   :leaf-llm leaf-llm
   :make-form-llm make-form-llm})

;; =============================================================================
;; All namespaces (for system prompt generation)
;; =============================================================================

(def all-namespaces
  "All available namespaces: tools + stdlib."
  (merge {'tools tools} stdlib/all-namespaces))

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
                     {'llm #'llm
                      'leaf-llm leaf-llm
                      ;; Namespaces (strings, math, patterns - matching Clojure structure)
                      'tools tools
                      'strings stdlib/strings
                      'math stdlib/math
                      'patterns stdlib/patterns
                      'describe describe
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
                      'str-replace tools/str-replace
                      'replace-lines tools/replace-lines})))

