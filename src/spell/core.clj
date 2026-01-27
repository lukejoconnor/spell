(ns spell.core
  "Spell - a Lisp dialect for LLM self-orchestration."
  (:require [spell.llm :as llm-provider]
            [spell.prompt :as prompt]
            [clojure.set :as set])
  (:import [java.util.concurrent TimeUnit]))

(declare llm extract expand prepend-hooks-to-llm recurse)

(def ^:dynamic *verbose*
  "When true, print LLM prompts and responses."
  false)

(def ^:dynamic *llm-depth*
  "Current depth of nested LLM calls (for indentation)."
  0)

(def ^:dynamic *max-llm-depth*
  "Maximum allowed LLM recursion depth. Set to nil to disable limit."
  8)

(def ^:dynamic *bash-timeout*
  "Timeout in seconds for bash commands. Set to nil to disable."
  30)

(def ^:private error-prefix
  "Prefix for error strings from failed llm calls."
  "[SPELL-ERROR] ")

(def ^:private max-retries
  "Number of times to retry a failed llm call before returning error."
  2)

(defn spell-error?
  "Returns true if value is an error string from a failed llm call."
  [v]
  (and (string? v) (.startsWith ^String v error-prefix)))

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

(def ^:private builtins
  "Whitelisted operations - effectively appended to env."
  {'+ +, '- -, '* *, '/ /, '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=,
   'str str, 'list list, 'vector vector, 'first first, 'rest rest,
   'cons cons, 'conj conj, 'get get, 'assoc assoc, 'not not, 'count count,
   'inc inc, 'dec dec, 'nil? nil?, 'empty? empty?, 'rand rand,
   'cat (fn [& args] (apply str args)),
   'llm #'llm,
   'expand #'expand,
   'prepend-hooks-to-llm #'prepend-hooks-to-llm,
   'recurse #'recurse,
   'read-name read-name,
   'bash run-bash,
   'spell-error? spell-error?})

(declare spell-eval)

;; =============================================================================
;; Free variable analysis
;; =============================================================================

(def ^:private special-forms
  "Special forms that are not free variables."
  #{'quote 'def 'do 'if 'let 'fn 'defn 'cond 'and 'or 'extract})

(defn find-free-vars
  "Find symbols in expr that aren't bound locally or builtins.
   Returns a set of free variable symbols."
  ([expr] (find-free-vars expr #{}))
  ([expr bound]
   (cond
     (symbol? expr)
     (if (or (contains? bound expr)
             (contains? builtins expr)
             (contains? special-forms expr))
       #{}
       #{expr})

     (seq? expr)
     (case (first expr)
       ;; quoted expressions have no free vars
       quote #{}

       ;; def: only the value expr can have free vars
       def (if (>= (count expr) 3)
             (find-free-vars (nth expr 2) bound)
             #{})

       ;; do: process sequentially, each def binds for subsequent exprs
       do (first
            (reduce (fn [[fv b] sub-expr]
                      (let [sub-free (find-free-vars sub-expr b)
                            ;; If this is a def, add the symbol to bound
                            new-bound (if (and (seq? sub-expr) (= 'def (first sub-expr)))
                                        (conj b (second sub-expr))
                                        b)]
                        [(set/union fv sub-free) new-bound]))
                    [#{} bound]
                    (rest expr)))

       ;; if: just recurse into all parts
       if (apply set/union (map #(find-free-vars % bound) (rest expr)))

       ;; let: each binding extends scope for subsequent bindings and body
       let (let [pairs (partition 2 (second expr))
                 [free final-bound]
                 (reduce (fn [[fv b] [sym val-expr]]
                           [(set/union fv (find-free-vars val-expr b))
                            (conj b sym)])
                         [#{} bound] pairs)]
             (apply set/union free (map #(find-free-vars % final-bound) (drop 2 expr))))

       ;; fn: params are bound in body
       fn (let [params (set (second expr))
                new-bound (set/union bound params)]
            (apply set/union (map #(find-free-vars % new-bound) (drop 2 expr))))

       ;; defn: name and params are bound in body
       defn (let [name (second expr)
                  params (set (nth expr 2))
                  new-bound (set/union bound params #{name})]
              (apply set/union (map #(find-free-vars % new-bound) (drop 3 expr))))

       ;; cond, and, or: just recurse
       (cond and or) (apply set/union (map #(find-free-vars % bound) (rest expr)))

       ;; default: recurse into all sub-expressions
       (apply set/union (map #(find-free-vars % bound) expr)))

     (vector? expr)
     (apply set/union (map #(find-free-vars % bound) expr))

     (map? expr)
     (apply set/union (map #(find-free-vars % bound) (vals expr)))

     :else #{})))

;; =============================================================================
;; Substitution
;; =============================================================================

(defn substitute
  "Replace free symbols with their values, respecting quote boundaries.
   bindings is a map from symbol to value."
  [expr bindings]
  (cond
    (symbol? expr)
    (if (contains? bindings expr)
      (get bindings expr)
      expr)

    (seq? expr)
    (case (first expr)
      ;; Don't substitute inside quotes
      quote expr

      ;; def: substitute in value only
      def (if (>= (count expr) 3)
            (list 'def (second expr) (substitute (nth expr 2) bindings))
            expr)

      ;; let: remove substituted symbols from bindings for each binding's scope
      let (let [pairs (partition 2 (second expr))
                [new-bindings new-binding-list]
                (reduce (fn [[b acc] [sym val-expr]]
                          [(dissoc b sym)
                           (concat acc [sym (substitute val-expr b)])])
                        [bindings []] pairs)
                new-body (map #(substitute % new-bindings) (drop 2 expr))]
            (list* 'let (vec new-binding-list) new-body))

      ;; fn: remove params from bindings for body
      fn (let [params (set (second expr))
               inner-bindings (apply dissoc bindings params)]
           (list* 'fn (second expr) (map #(substitute % inner-bindings) (drop 2 expr))))

      ;; defn: remove name and params from bindings for body
      defn (let [name (second expr)
                 params (set (nth expr 2))
                 inner-bindings (apply dissoc bindings (conj params name))]
             (list* 'defn name (nth expr 2) (map #(substitute % inner-bindings) (drop 3 expr))))

      ;; default: recurse
      (apply list (map #(substitute % bindings) expr)))

    (vector? expr)
    (mapv #(substitute % bindings) expr)

    (map? expr)
    (into {} (map (fn [[k v]] [k (substitute v bindings)]) expr))

    :else expr))

;; =============================================================================
;; Extract and Expand
;; =============================================================================

(defn- contains-llm-call?
  "Check if form contains any (llm ...) calls. Used to prevent accidental
   LLM invocations during extraction."
  [form]
  (cond
    (not (coll? form)) false
    (and (seq? form) (= 'llm (first form))) true
    (and (seq? form) (= 'quote (first form))) false  ; don't descend into quotes
    :else (some contains-llm-call? form)))

(defn- def-value-contains-llm?
  "Check if a def form's value expression contains llm calls.
   Returns false for defn (function bodies aren't evaluated during extraction)."
  [form]
  (and (seq? form)
       (= 'def (first form))  ; only def, not defn
       (>= (count form) 3)
       (contains-llm-call? (nth form 2))))

(defn- extract-binding-from-ast
  "Find a def/defn binding in a form via AST traversal (no evaluation).
   Returns [form-to-eval preceding-defs] if found, nil otherwise.
   preceding-defs is a list of def/defn forms that come before the target in a do block."
  ([form target-sym] (extract-binding-from-ast form target-sym []))
  ([form target-sym preceding]
   (cond
     ;; Unwrap quote
     (and (seq? form) (= 'quote (first form)))
     (extract-binding-from-ast (second form) target-sym preceding)

     ;; defn: (defn name [params] body...) → return as fn form
     (and (seq? form) (= 'defn (first form)) (= target-sym (second form)))
     [(list* 'fn (nth form 2) (drop 3 form)) preceding]

     ;; def: (def name value) → return the value expression
     (and (seq? form) (= 'def (first form)) (= target-sym (second form)))
     [(nth form 2) preceding]

     ;; do: search sub-forms, tracking preceding def/defn forms
     (and (seq? form) (= 'do (first form)))
     (loop [remaining (rest form)
            prec preceding]
       (when (seq remaining)
         (let [sub-form (first remaining)]
           (if-let [result (extract-binding-from-ast sub-form target-sym prec)]
             result
             ;; Not found in this sub-form; if it's a def/defn, add to preceding
             (let [new-prec (if (and (seq? sub-form)
                                     (or (= 'def (first sub-form))
                                         (= 'defn (first sub-form))))
                              (conj prec sub-form)
                              prec)]
               (recur (rest remaining) new-prec))))))

     :else nil)))

(defn extract
  "Extract a value from nested thunks via path.
   Path is [sym] for direct lookup, or [thunk-sym binding-sym ...] for nested extraction.

   Uses AST traversal to find bindings without evaluating the entire thunk,
   avoiding side effects from code after the target binding.

   Example: (extract [parent-code helper] env)
   - Looks up 'parent-code' in env (a thunk)
   - Finds 'helper' binding in thunk's AST
   - Evaluates preceding definitions and the binding, returns the value"
  [path env]
  (when (empty? path)
    (throw (ex-info "extract: empty path" {})))

  (let [first-sym (first path)]
    (when-not (contains? env first-sym)
      (throw (ex-info "extract: symbol not found" {:symbol first-sym})))

    (if (= 1 (count path))
      ;; Base case: return value from env
      (get env first-sym)

      ;; Recursive case: AST-extract from thunk, then recurse
      (let [thunk (get env first-sym)
            next-sym (second path)
            result (extract-binding-from-ast thunk next-sym)]
        (when result
          (let [[form preceding] result
                ;; fn forms are safe - body isn't evaluated during extraction
                form-is-fn? (and (seq? form) (= 'fn (first form)))]
            ;; Check for llm calls in forms we're about to evaluate
            ;; (skip fn forms since their bodies aren't evaluated)
            (when (and (not form-is-fn?) (contains-llm-call? form))
              (throw (ex-info "extract: target binding contains llm call"
                             {:target next-sym :form form})))
            ;; Only check def values (not defn bodies) in preceding forms
            (when-let [bad-def (some #(when (def-value-contains-llm? %) %) preceding)]
              (throw (ex-info "extract: preceding definition contains llm call"
                             {:def bad-def})))
            ;; Evaluate preceding def/defn forms to build context
            (let [eval-env (reduce (fn [e def-form]
                                     (second (spell-eval def-form e)))
                                   {}  ; fresh env for thunk evaluation
                                   preceding)]
              (if (= 2 (count path))
                ;; Final element: evaluate the extracted form
                (first (spell-eval form eval-env))
                ;; More elements: extracted form becomes new thunk, recurse
                (extract (rest path) {next-sym form})))))))))

(defn expand
  "Substitute free variables in sub-thunk with values from closure.

   closure: a thunk that defines the context (evaluated to get bindings)
   sub-thunk: expression whose free variables should be substituted
   env: environment in which closure's symbols are defined

   Example: (expand '(do (def x 42)) '(+ x 1) {})
   - Evaluates closure to get {x 42}
   - Finds free vars in sub-thunk: #{x}
   - Returns (+ 42 1)"
  [closure sub-thunk env]
  (let [[_ closure-env] (spell-eval closure env)
        free (find-free-vars sub-thunk)
        bindings (into {} (for [v free
                                :when (contains? closure-env v)]
                            [v (get closure-env v)]))]
    (substitute sub-thunk bindings)))

(defn- eval-seq
  "Evaluate a sequence of expressions, returning [last-value final-env]."
  [exprs env]
  (reduce (fn [[_ e] x] (spell-eval x e)) [nil env] exprs))

(defn spell-eval
  "Evaluate expr in env. Returns [value updated-env]."
  [expr env]
  (cond
    ;; Self-evaluating: nil, strings, numbers, booleans, keywords
    (or (nil? expr) (string? expr) (number? expr) (boolean? expr) (keyword? expr))
    [expr env]

    ;; Symbol: lookup in env, fallback to builtins
    (symbol? expr)
    (if-let [entry (or (find env expr) (find builtins expr))]
      [(val entry) env]
      (throw (ex-info "Unbound symbol" {:symbol expr})))

    ;; Vector: evaluate each element, threading env
    (vector? expr)
    (reduce (fn [[acc e] x] (let [[v e'] (spell-eval x e)] [(conj acc v) e']))
            [[] env] expr)

    ;; Map: evaluate values, threading env
    (map? expr)
    (reduce (fn [[acc e] [k v]] (let [[v' e'] (spell-eval v e)] [(assoc acc k v') e']))
            [{} env] expr)

    ;; List: special forms or function application
    (seq? expr)
    (case (first expr)
      nil   [nil env]
      quote [(second expr) env]
      def   (let [[v e'] (spell-eval (nth expr 2) env)]
              [v (assoc e' (second expr) v)])
      do    (eval-seq (rest expr) env)
      if    (let [[test-v e'] (spell-eval (second expr) env)]
              (spell-eval (nth expr (if test-v 2 3) nil) e'))

      ;; let: (let [bindings...] body...) - local bindings
      let   (let [bindings (partition 2 (second expr))
                  body (drop 2 expr)
                  local-env (reduce (fn [le [sym val-expr]]
                                      (let [[v _] (spell-eval val-expr le)]
                                        (assoc le sym v)))
                                    env bindings)
                  [result _] (eval-seq body local-env)]
              [result env])  ; let bindings don't escape

      ;; fn: (fn [params...] body...) - creates closure
      fn    (let [params (second expr)
                  body (drop 2 expr)
                  closure-env env]
              [(fn [& args]
                 (let [local-env (into closure-env (map vector params args))
                       [result _] (eval-seq body local-env)]
                   result))
               env])

      ;; defn: (defn name [params...] body...)
      defn  (let [name (second expr)
                  params (nth expr 2)
                  body (drop 3 expr)
                  [f _] (spell-eval (list* 'fn params body) env)]
              [f (assoc env name f)])

      ;; cond: (cond test1 expr1 test2 expr2 ... :else default)
      cond  (loop [clauses (partition 2 (rest expr)), e env]
              (if (empty? clauses)
                [nil e]
                (let [[test-expr result-expr] (first clauses)]
                  (if (= test-expr :else)
                    (spell-eval result-expr e)
                    (let [[test-v e'] (spell-eval test-expr e)]
                      (if test-v
                        (spell-eval result-expr e')
                        (recur (rest clauses) e')))))))

      ;; and: short-circuit, returns last truthy or first falsy
      and   (loop [exprs (rest expr), e env, last-v true]
              (if (empty? exprs)
                [last-v e]
                (let [[v e'] (spell-eval (first exprs) e)]
                  (if v
                    (recur (rest exprs) e' v)
                    [v e']))))

      ;; or: short-circuit, returns first truthy or last falsy
      or    (loop [exprs (rest expr), e env, last-v nil]
              (if (empty? exprs)
                [last-v e]
                (let [[v e'] (spell-eval (first exprs) e)]
                  (if v
                    [v e']
                    (recur (rest exprs) e' v)))))

      ;; extract: (extract [thunk-sym binding-sym]) - get binding from thunk
      ;; Special form because it needs unevaluated path symbols and the env
      extract (let [path (second expr)]
                [(extract path env) env])

      ;; Function application: evaluate all, apply first to rest
      (let [[vals e'] (reduce (fn [[acc e] x] (let [[v e'] (spell-eval x e)] [(conj acc v) e']))
                              [[] env] expr)]
        [(apply (first vals) (rest vals)) e']))

    :else (throw (ex-info "Unknown expression type" {:expr expr}))))

(defn paren-balance
  "Count open parens minus close parens in a string."
  [s]
  (reduce (fn [n c]
            (case c
              \( (inc n)
              \) (dec n)
              n))
          0 s))

(defn balance-parens
  "Append closing parens to balance the string if needed."
  [s]
  (let [balance (paren-balance s)]
    (if (pos? balance)
      (str s (apply str (repeat balance \))))
      s)))

(defn- escape-string
  "Escape a string for embedding in Lisp code."
  [s]
  (-> s
      (clojure.string/replace "\\" "\\\\")
      (clojure.string/replace "\"" "\\\"")
      (clojure.string/replace "\n" "\\n")
      (clojure.string/replace "\t" "\\t")))

(defn prepend-hooks-to-llm
  "Walk code and prepend hooks to all llm calls.
   (llm prompt) -> (llm prompt [hooks...])
   (llm prompt [existing]) -> (llm prompt [hooks... existing...])
   Does not descend into quoted forms."
  [hooks code]
  (cond
    ;; Don't descend into quotes
    (and (seq? code) (= 'quote (first code)))
    code

    ;; llm call - prepend hooks
    (and (seq? code) (= 'llm (first code)))
    (let [prompt (second code)
          existing-hooks (if (>= (count code) 3) (nth code 2) [])
          ;; Recursively process the prompt (it might contain nested llm calls)
          processed-prompt (prepend-hooks-to-llm hooks prompt)]
      (list 'llm processed-prompt (vec (concat hooks existing-hooks))))

    ;; Sequence - recurse into elements
    (seq? code)
    (apply list (map #(prepend-hooks-to-llm hooks %) code))

    ;; Vector - recurse
    (vector? code)
    (mapv #(prepend-hooks-to-llm hooks %) code)

    ;; Map - recurse into values
    (map? code)
    (into {} (map (fn [[k v]] [k (prepend-hooks-to-llm hooks v)]) code))

    ;; Anything else - return unchanged
    :else code))

(defn recurse
  "Create a recursive hook from a quoted macro.
   Returns a quoted macro that:
   1. Applies the input macro to code
   2. Prepends [input-macro, (recurse input-macro)] to all llm calls in the result

   This makes the hook propagate to all descendant llm calls.

   Example:
     (def recursive-logger (recurse '(fn [code] (do (def log \"seen\") code))))
     ;; When used as a hook, this will inject 'log' binding into every nested llm call"
  [hook]
  ;; Build a quoted fn that:
  ;; 1. Evaluates the inner hook to get a function
  ;; 2. Applies it to code
  ;; 3. Prepends [quoted-hook, (recurse quoted-hook)] to all llm calls
  ;;
  ;; The key: hooks passed to prepend-hooks-to-llm must be quoted code (data),
  ;; not evaluated functions. So we quote the hook and call recurse on quoted hook.
  (list 'fn '[code]
        (list 'let ['inner-hook hook
                    'transformed (list 'inner-hook 'code)
                    ;; Quote the hooks so they stay as data, not functions
                    'hooks-to-add (list 'vector
                                        (list 'quote hook)
                                        (list 'recurse (list 'quote hook)))]
              (list 'prepend-hooks-to-llm 'hooks-to-add 'transformed))))

(defn- format-llm-error
  "Format an error message for a failed llm call."
  [response error]
  (str error-prefix
       "Response: " response "\n"
       "Error: " (ex-message error)))

(defn- apply-hooks
  "Apply hooks (quoted macros) to code, left-to-right.
   Each hook is either a quoted form that evaluates to a function code->code,
   or an already-evaluated function. Returns the transformed code."
  [hooks code]
  (reduce (fn [c hook]
            ;; If hook is already a function, use it directly
            ;; Otherwise evaluate it to get the transformer function
            (let [hook-fn (if (fn? hook)
                            hook
                            (first (spell-eval hook {})))]
              (hook-fn c)))
          code
          hooks))

(defn llm
  "The llm primitive: send prompt to LLM, evaluate response, return 'return binding.

   1. Wraps prompt in (do (def prefix \"...\") (def response form
      - If prompt is a thunk (list), also binds parent-code to the thunk
   2. Calls the LLM provider with the wrapped prompt (+ system prompt)
   3. Concatenates wrapped prompt + response into a 'completion'
   4. Auto-balances parens if LLM forgot closing parens
   5. Applies hooks to transform completion into program
   6. Parses and evaluates the program with spell-eval
   7. Returns the value bound to 'return in the resulting environment

   On error (syntax or evaluation), retries up to max-retries times.
   If all attempts fail, returns an error string (detectable via spell-error?).

   Hooks are quoted macros (code->code transformers). Each hook is evaluated
   to get a function, then applied to the code. Hooks compose left-to-right:
   (hook2 (hook1 completion))."
  ([prompt] (llm prompt []))
  ([prompt hooks]
   (when (and *max-llm-depth* (>= *llm-depth* *max-llm-depth*))
     (throw (ex-info "LLM recursion limit exceeded"
                     {:depth *llm-depth* :limit *max-llm-depth*})))
   (let [indent (apply str (repeat *llm-depth* "  "))
         is-thunk (or (seq? prompt) (list? prompt))
         prompt-str (if is-thunk (pr-str prompt) (str prompt))
         parent-code-binding (when is-thunk
                               (str "(def parent-code '" (pr-str prompt) ") "))
         wrapped-prompt (str "(do (def prefix \"" (escape-string prompt-str) "\") "
                            (or parent-code-binding "")
                            "(def response ")]
     (when *verbose*
       (println (str indent "=== LLM Call (depth " *llm-depth* ") ==="))
       (println (str indent "Prompt: " (pr-str prompt))))
     ;; Retry loop: attempt up to (1 + max-retries) times
     (loop [attempt 0
            last-response nil
            last-error nil]
       (if (> attempt max-retries)
         ;; All attempts exhausted, return error string
         (do
           (when *verbose*
             (println (str indent "All " (inc max-retries) " attempts failed, returning error")))
           (format-llm-error last-response last-error))
         ;; Try LLM call + eval
         (let [response (llm-provider/llm-call wrapped-prompt {:system prompt/system-prompt})]
           (when *verbose*
             (when (pos? attempt)
               (println (str indent "Retry attempt " attempt)))
             (println (str indent "Response: " response)))
           (let [result (try
                          (let [raw-completion (str wrapped-prompt response)
                                completion (balance-parens raw-completion)
                                _ (when (and *verbose* (not= completion raw-completion))
                                    (println (str indent "(auto-balanced parens)")))
                                parsed (read-string completion)
                                ;; Apply hooks to transform completion into program
                                program (if (empty? hooks)
                                          parsed
                                          (apply-hooks hooks parsed))
                                _ (when (and *verbose* (seq hooks))
                                    (println (str indent "Program (after hooks): " (pr-str program))))
                                initial-env {'completion (list 'quote parsed)}
                                [_ env] (binding [*llm-depth* (inc *llm-depth*)]
                                          (spell-eval program initial-env))]
                            {:success true :value (get env 'return)})
                          (catch Exception e
                            (when *verbose*
                              (println (str indent "Error: " (ex-message e))))
                            {:success false :response response :error e}))]
             (if (:success result)
               (:value result)
               (recur (inc attempt) (:response result) (:error result))))))))))

(defn run-spell
  "Run a spell program, returning just the value."
  [program]
  (first (spell-eval program {})))

(comment
  ;; REPL testing
  (spell-eval '(+ 1 2) {})
  (spell-eval '(do (setq x 1) (+ x 2)) {})
  (run-spell '[1 (+ 2 3)])
  (run-spell '{:a (+ 1 2)})
  )
