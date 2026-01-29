(ns spell.eval
  "Spell evaluator: spell-eval, expand, free variable analysis, builtins."
  (:require [spell.parse :as parse]
            [clojure.set :as set]))

;; =============================================================================
;; Dynamic vars
;; =============================================================================

(def ^:dynamic *verbose*
  "When true, print LLM prompts and responses."
  false)

(def ^:dynamic *llm-depth*
  "Current depth of nested LLM calls (for indentation)."
  0)

(def ^:dynamic *max-llm-depth*
  "Maximum allowed LLM recursion depth. Set to nil to disable limit."
  8)

(def ^:dynamic *quote-env*
  "Maps symbols to their quoted definition expressions during evaluation.
   Used by uneval to retrieve the source code of a binding while it's being evaluated."
  {})

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

(defn format-llm-error
  "Format an error message for a failed llm call."
  [response error]
  (str error-prefix
       "Response: " response "\n"
       "Error: " (ex-message error)))

;; =============================================================================
;; Builtins
;; =============================================================================

(def core-builtins
  "Language primitives - always available in every llm variant."
  {'+ +, '- -, '* *, '/ /, '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=,
   'str str, 'pr-str pr-str, 'list list, 'vector vector, 'first first, 'rest rest,
   'cons cons, 'conj conj, 'get get, 'assoc assoc, 'not not, 'count count,
   'inc inc, 'dec dec, 'nil? nil?, 'empty? empty?, 'rand rand,
   'cat (fn [& args] (apply str args)),
   'strip parse/strip-trailing-parens,
   'spell-error? spell-error?})

(def ^:dynamic *builtins*
  "Active builtins map. Rebound by each llm variant during evaluation.
   Root binding set by spell.core after all definitions exist."
  nil)

;; =============================================================================
;; Free variable analysis
;; =============================================================================

(def special-forms
  "Special forms that are not free variables."
  #{'quote 'def 'do 'do-eval-last 'if 'let 'fn 'defn 'cond 'and 'or 'uneval 'expand})

(defn find-free-vars
  "Find symbols in expr that aren't bound locally or builtins.
   Returns a set of free variable symbols."
  ([expr] (find-free-vars expr #{}))
  ([expr bound]
   (cond
     (symbol? expr)
     (if (or (contains? bound expr)
             (contains? (or *builtins* core-builtins) expr)
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

       ;; do / do-eval-last: process sequentially, each def binds for subsequent exprs
       (do do-eval-last) (first
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

(defn quote-value
  "Wrap non-self-evaluating values in (quote ...) for safe embedding in generated code."
  [v]
  (if (or (nil? v) (number? v) (string? v) (boolean? v) (keyword? v))
    v
    (list 'quote v)))

(defn- expand-expr
  "Walk expr substituting outer-env values for free symbols not in inner (locally defined).
   Mirrors spell-eval's structure but returns transformed data instead of evaluating."
  [expr outer-env inner]
  (cond
    ;; Self-evaluating
    (or (nil? expr) (string? expr) (number? expr) (boolean? expr) (keyword? expr))
    expr

    ;; Symbol: inner (locally defined) -> leave; outer-env -> substitute; else -> leave
    (symbol? expr)
    (cond
      (contains? inner expr) expr
      (contains? (or *builtins* core-builtins) expr) expr
      (contains? special-forms expr) expr
      (contains? outer-env expr) (quote-value (get outer-env expr))
      :else expr)

    ;; Vector
    (vector? expr)
    (mapv #(expand-expr % outer-env inner) expr)

    ;; Map
    (map? expr)
    (into {} (map (fn [[k v]] [k (expand-expr v outer-env inner)]) expr))

    ;; List
    (seq? expr)
    (case (first expr)
      nil   expr
      quote expr

      def (let [sym (second expr)
                val-expanded (expand-expr (nth expr 2) outer-env inner)]
            (list 'def sym val-expanded))

      (do do-eval-last)
         (let [[forms _]
               (reduce (fn [[acc i] sub-expr]
                         (let [expanded (expand-expr sub-expr outer-env i)
                               new-inner (if (and (seq? sub-expr) (= 'def (first sub-expr)))
                                           (conj i (second sub-expr))
                                           i)]
                           [(conj acc expanded) new-inner]))
                       [[] inner]
                       (rest expr))]
           (list* (first expr) forms))

      if (list* 'if (map #(expand-expr % outer-env inner) (rest expr)))

      let (let [pairs (partition 2 (second expr))
                [expanded-bindings final-inner]
                (reduce (fn [[acc i] [sym val-expr]]
                          [(conj acc sym (expand-expr val-expr outer-env i))
                           (conj i sym)])
                        [[] inner] pairs)
                expanded-body (map #(expand-expr % outer-env final-inner) (drop 2 expr))]
            (list* 'let (vec expanded-bindings) expanded-body))

      fn (let [params (set (second expr))
               body-inner (into inner params)]
           (list* 'fn (second expr) (map #(expand-expr % outer-env body-inner) (drop 2 expr))))

      defn (let [name-sym (second expr)
                 params (set (nth expr 2))
                 body-inner (into inner (conj params name-sym))]
             (list* 'defn name-sym (nth expr 2) (map #(expand-expr % outer-env body-inner) (drop 3 expr))))

      (cond and or) (list* (first expr) (map #(expand-expr % outer-env inner) (rest expr)))

      ;; Default: recurse into all sub-expressions
      (apply list (map #(expand-expr % outer-env inner) expr)))

    :else expr))

;; =============================================================================
;; Evaluator
;; =============================================================================

(declare spell-eval)

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

    ;; Symbol: lookup in env, fallback to *builtins*
    (symbol? expr)
    (if-let [entry (or (find env expr) (find (or *builtins* core-builtins) expr))]
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
      def   (let [sym (second expr)
                  val-expr (nth expr 2)
                  ;; Bind sym -> quoted val-expr in *quote-env* during evaluation
                  [v e'] (binding [*quote-env* (assoc *quote-env* sym (list 'quote val-expr))]
                           (spell-eval val-expr env))]
              [v (assoc e' sym v)])
      do    (eval-seq (rest expr) env)
      do-eval-last (let [[last-val final-env] (eval-seq (rest expr) env)
                         [result result-env] (spell-eval last-val final-env)]
                     [result result-env])
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

      ;; uneval: (uneval 'sym) - get the quoted source of a binding during its evaluation
      ;; Enables self-referential programs (quines) by looking up in *quote-env*
      uneval (let [[sym-v _] (spell-eval (second expr) env)]
               (when-not (symbol? sym-v)
                 (throw (ex-info "uneval: argument must evaluate to a symbol"
                                {:got sym-v :type (type sym-v)})))
               (if-let [quoted (get *quote-env* sym-v)]
                 [quoted env]
                 (throw (ex-info "uneval: symbol not found in quote environment"
                                {:symbol sym-v
                                 :available (keys *quote-env*)}))))

      ;; expand: (expand expr) - single-pass walk mirroring spell-eval
      ;; Substitutes free variables from env, returns data (not evaluated)
      expand (let [[quoted-expr e'] (spell-eval (second expr) env)]
               [(expand-expr quoted-expr e' #{}) e'])

      ;; Function application: evaluate all, apply first to rest
      (let [[vals e'] (reduce (fn [[acc e] x] (let [[v e'] (spell-eval x e)] [(conj acc v) e']))
                              [[] env] expr)]
        [(apply (first vals) (rest vals)) e']))

    :else (throw (ex-info "Unknown expression type" {:expr expr}))))

(defn run-spell
  "Run a spell program, returning just the value."
  [program]
  (first (spell-eval program {})))
