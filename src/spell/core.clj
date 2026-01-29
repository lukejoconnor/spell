(ns spell.core
  "Spell — wiring layer for Spell.
   Assembles components (eval, hooks, llm, parse) into the default configuration.
   Re-exports key vars for backwards compatibility."
  (:require [spell.llm :as llm-provider]
            [spell.prompt :as prompt]
            [spell.parse :as parse]
            [spell.eval :as eval]
            [spell.hooks :as hooks])
  (:import [java.util.concurrent TimeUnit]))

(declare llm)

;; Re-export from spell.eval for backwards compatibility
(def spell-eval eval/spell-eval)
(def run-spell eval/run-spell)
(def spell-error? eval/spell-error?)

(def ^:dynamic *bash-timeout*
  "Timeout in seconds for bash commands. Set to nil to disable."
  30)

(defn- read-name
  "Read the name from name.txt file."
  []
  (try
    (clojure.string/trim (slurp "name.txt"))
    (catch java.io.FileNotFoundException _
      (throw (ex-info "name.txt not found" {:file "name.txt"})))))

(defn- run-bash
  "Execute a bash command string. Returns {:exit N :out \"...\" :err \"...\"}."
  [command]
  (let [pb (ProcessBuilder. ["bash" "-c" command])
        process (.start pb)
        out-future (future (slurp (.getInputStream process)))
        err-future (future (slurp (.getErrorStream process)))
        timed-out? (if *bash-timeout*
                     (not (.waitFor process (long *bash-timeout*) TimeUnit/SECONDS))
                     (do (.waitFor process) false))]
    (if timed-out?
      (do (.destroyForcibly process)
          {:exit -1
           :out ""
           :err (str "Command timed out after " *bash-timeout* " seconds")})
      {:exit (.exitValue process)
       :out (clojure.string/trim @out-future)
       :err (clojure.string/trim @err-future)})))

;; =============================================================================
;; Tool definitions
;; =============================================================================

(def read-name-tool
  "Tool metadata for read-name."
  {:name 'read-name
   :fn   read-name
   :doc  "Returns the name from name.txt. Takes no arguments. Use (read-name) to get the name."})

(def bash-tool
  "Tool metadata for bash."
  {:name 'bash
   :fn   run-bash
   :doc  "Execute a shell command. Takes a command string, returns a map with :exit (integer), :out (stdout string), :err (stderr string).
(bash \"ls -la\")       ; => {:exit 0 :out \"...\" :err \"\"}
(:out (bash \"pwd\"))   ; => \"/current/dir\"
(:exit (bash \"false\")) ; => 1"})

(def default-tools
  "Default tool set for the standard llm function."
  [read-name-tool bash-tool])

;; Delegated to spell.parse
(def paren-balance parse/paren-balance)
(def balance-parens parse/balance-parens)
(def read-all parse/read-all)

;; Re-export from spell.hooks for backwards compatibility
(def prepend-hooks-to-llm hooks/prepend-hooks-to-llm)
(def recurse hooks/recurse)
(def with-env hooks/with-env)
(def prefix-prompt hooks/prefix-prompt)
(def with-env-hints hooks/with-env-hints)

;; Re-export from spell.llm for backwards compatibility
(def make-llm llm-provider/make-llm)

;; =============================================================================
;; Default llm function
;; =============================================================================

(def llm
  "The default llm function with all standard tools and self-recursion."
  (make-llm {:tools default-tools
             :llms  {'llm #'llm}}))

;; Set root binding for eval/*builtins* — used by direct spell-eval/run-spell calls
;; (tests, REPL) that don't go through an llm function.
(alter-var-root #'eval/*builtins*
  (constantly (merge eval/core-builtins
                     {'llm #'llm
                      'prepend-hooks-to-llm #'prepend-hooks-to-llm
                      'recurse #'recurse
                      'prefix-prompt #'prefix-prompt
                      'with-env with-env
                      'with-env-hints with-env-hints
                      'read-name read-name
                      'bash run-bash})))

;; Set the default system prompt for backwards compatibility
(alter-var-root #'prompt/system-prompt
  (constantly (prompt/generate-system-prompt default-tools {'llm #'llm})))

(comment
  ;; REPL testing
  (spell-eval '(+ 1 2) {})
  (spell-eval '(do (setq x 1) (+ x 2)) {})
  (run-spell '[1 (+ 2 3)])
  (run-spell '{:a (+ 1 2)})
  )
