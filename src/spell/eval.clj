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

(def ^:dynamic *spell-env*
  "Current spell-eval environment during function application.
   Allows Clojure builtins (like apply) to access the current env for spell-fn support."
  {})

(def ^:private error-prefix
  "Prefix for error strings from failed llm calls."
  "[SPELL-ERROR] ")

(defn spell-error?
  "Returns true if value is an error string from a failed llm call."
  [v]
  (and (string? v) (.startsWith ^String v error-prefix)))

(defn spell-future?
  "Returns true if v is a Spell future handle."
  [v]
  (and (map? v) (:spell/future v)))

(defn format-llm-error
  "Format an error message for a failed llm call."
  [response error]
  (str error-prefix
       "Response: " response "\n"
       "Error: " (ex-message error)))

;; =============================================================================
;; Builtins
;; =============================================================================

(declare spell-eval expand-expr)

(defn spell-fn?
  "Returns true if v is a Spell function (dynamic-scoping function map)."
  [v]
  (and (map? v) (:spell/fn v)))

(def core-builtins
  "Language primitives - always available in every llm variant."
  {;; Math
   '+ +, '- -, '* *, '/ /, 'inc inc, 'dec dec, 'rand rand,
   ;; Comparison
   '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=,
   ;; Logic
   'not not, 'nil? nil?, 'empty? empty?,
   ;; Strings
   'str str, 'pr-str pr-str,
   'cat (fn [& args] (apply str args)),
   'subs (fn
           ([s start] (subs s start))
           ([s start end] (subs s start end))),
   'starts-with? (fn [s prefix] (.startsWith ^String (str s) (str prefix))),
   'includes? (fn [s substr] (.contains ^String (str s) (str substr))),
   'trim (fn [s] (clojure.string/trim (str s))),
   'replace (fn [s match replacement] (clojure.string/replace (str s) (str match) (str replacement))),
   'split (fn [s pattern] (clojure.string/split (str s) (re-pattern pattern))),
   'join (fn
           ([coll] (clojure.string/join coll))
           ([sep coll] (clojure.string/join sep coll))),
   'lower-case (fn [s] (clojure.string/lower-case (str s))),
   'upper-case (fn [s] (clojure.string/upper-case (str s))),
   ;; Regex
   're-find (fn [pattern s] (re-find (re-pattern pattern) s)),
   're-matches (fn [pattern s] (re-matches (re-pattern pattern) s)),
   ;; Type predicates
   'string? string?, 'number? number?, 'list? list?, 'seq? seq?, 'vector? vector?,
   'map? (fn [v] (and (map? v) (not (spell-fn? v)) (not (spell-future? v)))),
   'fn? (fn [v] (or (fn? v) (spell-fn? v))),
   ;; Collections
   'list list, 'vector vector, 'first first, 'rest rest, 'last last,
   'cons cons, 'conj conj, 'get get, 'assoc assoc, 'count count,
   'nth (fn
          ([coll idx] (nth coll idx))
          ([coll idx not-found] (nth coll idx not-found))),
   'keys keys, 'vals vals,
   'into (fn [to from] (into to from)),
   'concat concat, 'reverse reverse,
   'sort (fn [coll] (sort coll)),
   'range (fn
            ([end] (range end))
            ([start end] (range start end))
            ([start end step] (range start end step))),
   'repeat (fn [n x] (repeat n x)),
   'apply (fn [f & args]
            (let [all-args (concat (butlast args) (last args))]
              (if (spell-fn? f)
                (let [local-env (into *spell-env* (map vector (:params f) all-args))]
                  (first (spell-eval (cons 'do (:body f)) local-env)))
                (clojure.core/apply f all-args)))),
   ;; Strip / Reopen
   'strip-parens parse/strip-trailing-parens,
   'reopen (fn [s] (parse/strip-trailing-parens 3 s)),
   ;; Error handling
   'spell-error? spell-error?,
   ;; Eval — auto-expands free vars from caller's env, then evaluates in fresh env
   'spell-eval (fn [expr] (first (spell-eval (expand-expr expr *spell-env*) {}))),
   ;; Concurrency
   'await (fn [future-val]
            (when-not (spell-future? future-val)
              (throw (ex-info "await: argument must be a future" {:got future-val})))
            (deref (:ref future-val)))})

(def ^:dynamic *builtins*
  "Active builtins map. Rebound by each llm variant during evaluation.
   Root binding set by spell.core after all definitions exist."
  nil)

;; =============================================================================
;; Free variable analysis
;; =============================================================================

(def special-forms
  "Special forms that are not free variables."
  #{'quote 'def 'do 'if 'let 'fn 'defn 'cond 'and 'or 'uneval 'expand 'future 'quine})

(defn quote-value
  "Wrap non-self-evaluating values in (quote ...) for safe embedding in generated code."
  [v]
  (cond
    (or (nil? v) (number? v) (string? v) (boolean? v) (keyword? v)) v
    (spell-fn? v) (list* 'fn (:params v) (:body v))
    :else (list 'quote v)))

(defn- -expand-expr
  "Walk expr substituting outer-env values for free symbols not in inner (locally defined).
   Returns [expanded-expr updated-inner]. Mirrors spell-eval's structure but returns
   transformed data instead of evaluating."
  [expr outer-env inner]
  (cond
    ;; Self-evaluating
    (or (nil? expr) (string? expr) (number? expr) (boolean? expr) (keyword? expr))
    [expr inner]

    ;; Symbol: inner (locally defined) -> leave; outer-env -> substitute; else -> leave
    (symbol? expr)
    [(cond
       (contains? inner expr) expr
       (contains? (or *builtins* core-builtins) expr) expr
       (contains? special-forms expr) expr
       (contains? outer-env expr) (quote-value (get outer-env expr))
       :else expr)
     inner]

    ;; Vector
    (vector? expr)
    [(mapv #(first (-expand-expr % outer-env inner)) expr) inner]

    ;; Map
    (map? expr)
    [(into {} (map (fn [[k v]] [k (first (-expand-expr v outer-env inner))]) expr)) inner]

    ;; List
    (seq? expr)
    (let [expand1 #(first (-expand-expr % outer-env inner))]
      (case (first expr)
        nil   [expr inner]
        quote [expr inner]

        def (let [sym (second expr)
                  [val-expanded _] (-expand-expr (nth expr 2) outer-env inner)]
              [(list 'def sym val-expanded) (conj inner sym)])

        do (let [[forms final-inner]
                 (reduce (fn [[acc i] sub-expr]
                           (let [[expanded new-i] (-expand-expr sub-expr outer-env i)]
                             [(conj acc expanded) new-i]))
                         [[] inner]
                         (rest expr))]
             [(list* 'do forms) final-inner])

        if [(list* 'if (map expand1 (rest expr))) inner]

        let (let [pairs (partition 2 (second expr))
                  [expanded-bindings final-inner]
                  (reduce (fn [[acc i] [sym val-expr]]
                            [(conj acc sym (first (-expand-expr val-expr outer-env i)))
                             (conj i sym)])
                          [[] inner] pairs)
                  expanded-body (map #(first (-expand-expr % outer-env final-inner)) (drop 2 expr))]
              [(list* 'let (vec expanded-bindings) expanded-body) inner])

        fn (let [params (set (second expr))
                 body-inner (into inner params)]
             [(list* 'fn (second expr) (map #(first (-expand-expr % outer-env body-inner)) (drop 2 expr))) inner])

        defn (let [name-sym (second expr)
                   params (set (nth expr 2))
                   body-inner (into inner (conj params name-sym))]
               [(list* 'defn name-sym (nth expr 2) (map #(first (-expand-expr % outer-env body-inner)) (drop 3 expr)))
                (conj inner name-sym)])

        future [(list 'future (expand1 (second expr))) inner]

        quine (let [name-sym (second expr)
                    [body-expanded _] (-expand-expr (nth expr 2) outer-env (conj inner name-sym))]
                [(list 'quine name-sym body-expanded) (conj inner name-sym)])

        (cond and or) [(list* (first expr) (map expand1 (rest expr))) inner]

        ;; Default: recurse into all sub-expressions
        [(apply list (map expand1 expr)) inner]))

    :else [expr inner]))

(defn expand-expr
  "Expand expr, substituting free variables from outer-env. Returns expanded expression."
  [expr outer-env]
  (first (-expand-expr expr outer-env #{})))

;; =============================================================================
;; Evaluator
;; =============================================================================

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

    ;; Map: spell-fn maps are self-evaluating; otherwise evaluate values
    (map? expr)
    (if (spell-fn? expr)
      [expr env]
      (reduce (fn [[acc e] [k v]] (let [[v' e'] (spell-eval v e)] [(assoc acc k v') e']))
              [{} env] expr))

    ;; List: special forms or function application
    (seq? expr)
    (case (first expr)
      nil   [nil env]
      quote [(second expr) env]
      def   (let [sym (second expr)
                  val-expr (nth expr 2)
                  ;; Bind sym -> val-expr (the raw source form) in *quote-env* during evaluation
                  [v e'] (binding [*quote-env* (assoc *quote-env* sym val-expr)]
                           (spell-eval val-expr env))]
              [v (assoc e' sym v)])
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

      ;; fn: (fn [params...] body...) - dynamic scoping, returns source form
      fn    [{:spell/fn true :params (second expr) :body (drop 2 expr)}
             env]

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
               [(expand-expr quoted-expr e') e'])

      ;; quine: (quine name body) — bind name to the source form (= expr), eval body
      ;; Enables self-referential programs: name evaluates to '(quine name body)
      quine (let [name-sym (second expr)
                  body (nth expr 2)
                  env' (assoc env name-sym expr)]
              (spell-eval body env'))

      ;; future: (future expr) - evaluate expr in a new thread, return future handle
      ;; Captures env at creation time (immutable map, safe to share).
      ;; Conveys dynamic bindings via bound-fn. Env updates inside future don't leak.
      future (let [body (second expr)
                   captured-env env
                   f (bound-fn [] (first (spell-eval body captured-env)))]
               [{:spell/future true :ref (clojure.core/future (f))} env])

      ;; Function application: evaluate all, apply first to rest
      (let [[vals e'] (reduce (fn [[acc e] x] (let [[v e'] (spell-eval x e)] [(conj acc v) e']))
                              [[] env] expr)
            f (first vals)
            args (rest vals)]
        (if (spell-fn? f)
          (let [local-env (into e' (map vector (:params f) args))
                [result _] (eval-seq (:body f) local-env)]
            [result e'])
          [(binding [*spell-env* e'] (apply f args)) e'])))

    :else (throw (ex-info "Unknown expression type" {:expr expr}))))

(defn run-spell
  "Run a spell program, returning just the value."
  [program]
  (first (spell-eval program {})))
