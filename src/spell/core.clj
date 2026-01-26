(ns spell.core
  "Spell - a Lisp dialect for LLM self-orchestration."
  (:require [spell.llm :as llm-provider]
            [spell.prompt :as prompt]
            [clojure.set :as set]))

(declare llm extract expand)

(def ^:dynamic *verbose*
  "When true, print LLM prompts and responses."
  false)

(def ^:dynamic *llm-depth*
  "Current depth of nested LLM calls (for indentation)."
  0)

(defn- read-name
  "Read the name from name.txt file."
  []
  (try
    (clojure.string/trim (slurp "name.txt"))
    (catch java.io.FileNotFoundException _
      (throw (ex-info "name.txt not found" {:file "name.txt"})))))

(def ^:private builtins
  "Whitelisted operations - effectively appended to env."
  {'+ +, '- -, '* *, '/ /, '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=,
   'str str, 'list list, 'vector vector, 'first first, 'rest rest,
   'cons cons, 'conj conj, 'get get, 'assoc assoc, 'not not, 'count count,
   'inc inc, 'dec dec, 'nil? nil?, 'empty? empty?, 'rand rand,
   'cat (fn [& args] (apply str args)),
   'llm #'llm,
   'expand #'expand,
   'read-name read-name})

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

(defn extract
  "Extract a value from nested thunks via path.
   Path is [thunk-sym binding-sym] or [thunk-sym inner-thunk-sym binding-sym] etc.
   All but the last symbol are thunks to navigate through; the last is the target binding.

   Example: (extract [prompt file-status] env)
   - Looks up 'prompt' in env to get a thunk
   - Evaluates the thunk
   - Returns the value of 'file-status' from the resulting environment"
  [path env]
  (let [thunk-syms (butlast path)
        target-sym (last path)
        ;; Navigate through nested thunks
        final-env (reduce
                    (fn [e sym]
                      (let [thunk (get e sym)]
                        (when (nil? thunk)
                          (throw (ex-info "Symbol not found in environment" {:symbol sym})))
                        (second (spell-eval thunk {}))))
                    env
                    thunk-syms)]
    (get final-env target-sym)))

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

(defn llm
  "The llm primitive: send prompt to LLM, evaluate response, return 'return binding.

   1. Wraps prompt in (do (def prefix \"...\") (def response form
      - If prompt is a thunk (list), also binds parent-code to the thunk
   2. Calls the LLM provider with the wrapped prompt (+ system prompt)
   3. Concatenates wrapped prompt + response into a 'completion'
   4. Auto-balances parens if LLM forgot closing parens
   5. Parses and evaluates the completion with spell-eval
   6. Returns the value bound to 'return in the resulting environment"
  [prompt]
  (let [indent (apply str (repeat *llm-depth* "  "))
        is-thunk (or (seq? prompt) (list? prompt))
        prompt-str (if is-thunk (pr-str prompt) (str prompt))
        parent-code-binding (when is-thunk
                              (str "(def parent-code '" (pr-str prompt) ") "))
        wrapped-prompt (str "(do (def prefix \"" (escape-string prompt-str) "\") "
                           (or parent-code-binding "")
                           "(def response ")
        _ (when *verbose*
            (println (str indent "=== LLM Call (depth " *llm-depth* ") ==="))
            (println (str indent "Prompt: " (pr-str prompt))))
        response (llm-provider/llm-call wrapped-prompt {:system prompt/system-prompt})
        _ (when *verbose*
            (println (str indent "Response: " response)))
        raw-completion (str wrapped-prompt response)
        completion (balance-parens raw-completion)
        _ (when (and *verbose* (not= completion raw-completion))
            (println (str indent "(auto-balanced parens)")))
        parsed (read-string completion)
        ;; Bind 'completion to the full parsed code (as a thunk) so LLM can pass itself
        initial-env {'completion (list 'quote parsed)}
        [_ env] (binding [*llm-depth* (inc *llm-depth*)]
                  (spell-eval parsed initial-env))]
    (get env 'return)))

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
