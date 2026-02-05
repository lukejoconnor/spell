(ns spell.eval
  "Spell evaluator: spell-eval, expand, free variable analysis, builtins.

   The evaluator now uses memo-based tracking for error recovery:
   - spell-eval takes (expr, env, memo, idx) and returns a result map
   - On success: {:ok value :env env' :memo memo' :idx idx'}
   - On error: {:err msg :env env :memo memo :idx idx :expr failing-expr}
   - The memo vector records evaluated expressions and their values
   - (memo N) special form retrieves cached values by index"
  (:require [spell.parse :as parse]
            [clojure.string :as str]
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
;; Result helpers (for memo-based error recovery)
;; =============================================================================

(defn ok
  "Create a success result map."
  [value env memo idx]
  {:ok value :env env :memo memo :idx idx})

(defn err
  "Create an error result map."
  [msg env memo idx expr]
  {:err msg :env env :memo memo :idx idx :expr expr})

(defn ok?
  "Returns true if result is a success."
  [result]
  (contains? result :ok))

(defn err?
  "Returns true if result is an error."
  [result]
  (contains? result :err))

(defn result-value
  "Extract the value from a success result, or nil for error."
  [result]
  (:ok result))

(defn- record-memo
  "Record an expression and its value in the memo, incrementing idx."
  [result expr]
  (if (ok? result)
    (-> result
        (update :memo conj {:expr expr :value (:ok result)})
        (update :idx inc))
    result))

;; =============================================================================
;; Builtins
;; =============================================================================

(declare spell-eval expand-expr)

(defn spell-fn?
  "Returns true if v is a Spell function (dynamic-scoping function map)."
  [v]
  (and (map? v) (:spell/fn v)))

(defn- invoke-fn
  "Invoke f with args. Handles both spell-fns and Clojure fns.
   Uses *spell-env* for spell-fn body evaluation."
  [f args]
  (if (spell-fn? f)
    (let [local-env (into *spell-env* (map vector (:params f) args))]
      (first (spell-eval (cons 'do (:body f)) local-env)))
    (apply f args)))

(def core-builtins
  "Language primitives - always available in every llm variant.
   Extended functions are in stdlib registries (strings, seqs, fns)."
  {;; Math
   '+ +, '- -, '* *, '/ /, 'inc inc, 'dec dec,
   'int int, 'quot quot, 'mod mod, 'max max, 'min min,
   ;; Comparison
   '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=,
   ;; Logic
   'not not, 'nil? nil?, 'empty? empty?,
   ;; Strings (core only - extended in strings registry)
   'str str, 'pr-str pr-str,
   'cat (fn [& args] (apply str args)),
   ;; Type predicates
   'string? string?, 'number? number?, 'list? list?, 'seq? seq?, 'vector? vector?,
   'map? (fn [v] (and (map? v) (not (spell-fn? v)) (not (spell-future? v)))),
   'fn? (fn [v] (or (fn? v) (spell-fn? v))),
   ;; Collections (core only - extended in seqs registry)
   'list list, 'vector vector, 'first first, 'rest rest, 'last last,
   'cons cons, 'conj conj, 'get get, 'assoc assoc, 'count count,
   'nth (fn
          ([coll idx] (nth coll idx))
          ([coll idx not-found] (nth coll idx not-found))),
   'keys keys, 'vals vals,
   'into (fn [to from] (into to from)),
   'concat concat,
   'apply (fn [f & args]
            (let [all-args (concat (butlast args) (last args))]
              (if (spell-fn? f)
                (let [local-env (into *spell-env* (map vector (:params f) all-args))]
                  (first (spell-eval (cons 'do (:body f)) local-env)))
                (clojure.core/apply f all-args)))),
   ;; Core higher-order functions (spell-fn aware)
   'map (fn [f coll] (mapv #(invoke-fn f [%]) coll)),
   'filter (fn [pred coll] (filterv #(invoke-fn pred [%]) coll)),
   'reduce (fn
             ([f coll] (clojure.core/reduce #(invoke-fn f [%1 %2]) coll))
             ([f init coll] (clojure.core/reduce #(invoke-fn f [%1 %2]) init coll))),
   ;; Slicing
   'take (fn [n coll] (vec (take n coll))),
   'drop (fn [n coll] (vec (drop n coll))),
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
  #{'quote 'def 'do 'if 'let 'fn 'defn 'cond 'and 'or 'uneval 'expand 'future 'quine '-> '->> 'memo})

(defn- thread-first
  "Transform (-> x (f a) (g b)) into (g (f x a) b)."
  [initial forms]
  (reduce (fn [acc form]
            (let [form (if (seq? form) form (list form))]
              (list* (first form) acc (rest form))))
          initial forms))

(defn- thread-last
  "Transform (->> x (f a) (g b)) into (g b (f a x))."
  [initial forms]
  (reduce (fn [acc form]
            (let [form (if (seq? form) form (list form))]
              (concat form [acc])))
          initial forms))

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

    ;; Symbol: qualified (a/b) -> leave as-is (global ref);
    ;; inner (locally defined) -> leave; outer-env -> substitute; else -> leave
    (symbol? expr)
    (let [;; Use str to get full symbol including namespace
          sym-str (str expr)
          ;; Check if this is a qualified symbol (has / with content on both sides)
          qualified? (when (str/includes? sym-str "/")
                       (let [p (str/split sym-str #"/")]
                         (and (> (count p) 1)
                              (seq (first p))
                              (seq (second p)))))]
      (if qualified?
        ;; Qualified symbols are self-contained namespace references - leave intact
        [expr inner]
        [(cond
           (contains? inner expr) expr
           (contains? (or *builtins* core-builtins) expr) expr
           (contains? special-forms expr) expr
           (contains? outer-env expr) (quote-value (get outer-env expr))
           :else expr)
         inner]))

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

        ;; Threading macros: transform then expand
        -> (-expand-expr (thread-first (second expr) (drop 2 expr)) outer-env inner)
        ->> (-expand-expr (thread-last (second expr) (drop 2 expr)) outer-env inner)

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
  "Evaluate a sequence of expressions, threading env/memo/idx.
   Returns result map with last value."
  [exprs env memo idx]
  (if (empty? exprs)
    (ok nil env memo idx)
    (loop [remaining exprs
           result (ok nil env memo idx)]
      (if (empty? remaining)
        result
        (if (err? result)
          result
          (recur (rest remaining)
                 (spell-eval (first remaining) (:env result) (:memo result) (:idx result))))))))

(defn spell-eval
  "Evaluate expr in env with memo tracking. Returns result map:
   - Success: {:ok value :env env' :memo memo' :idx idx'}
   - Error: {:err msg :env env :memo memo :idx idx :expr failing-expr}

   The memo vector records evaluated expressions and values for replay.
   If idx points to an existing memo entry, return cached value (skip re-evaluation)."
  ([expr env]
   ;; Backwards-compatible 2-arg form: convert result to [value env] pair
   (let [result (spell-eval expr env [] 0)]
     (if (ok? result)
       [(:ok result) (:env result)]
       (throw (ex-info (:err result) {:result result})))))
  ([expr env memo idx]
   ;; Check memo first - if we have a cached value at this index, return it
   (if-let [cached (get memo idx)]
     (ok (:value cached) env memo (inc idx))
     ;; Normal evaluation
     (let [result
           (cond
             ;; Self-evaluating: nil, strings, numbers, booleans, keywords
             (or (nil? expr) (string? expr) (number? expr) (boolean? expr) (keyword? expr))
             (ok expr env memo idx)

             ;; Symbol: qualified (a/b/c) -> recursive namespace lookup; else env/*builtins*
             (symbol? expr)
             (let [sym-str (str expr)
                   parts (when (str/includes? sym-str "/")
                           (let [p (str/split sym-str #"/")]
                             (when (and (> (count p) 1)
                                        (seq (first p))
                                        (seq (second p)))
                               p)))]
               (if parts
                 ;; Qualified symbol: strings/trim or nested/path/item
                 (let [root-sym (symbol (first parts))
                       root-result (spell-eval root-sym env memo idx)]
                   (if (err? root-result)
                     root-result
                     (let [root-val (:ok root-result)
                           result (reduce #(get %1 (keyword %2)) root-val (rest parts))]
                       (if (nil? result)
                         (err (str "Namespace lookup failed: " expr) env memo idx expr)
                         (ok result (:env root-result) (:memo root-result) (:idx root-result))))))
                 ;; Unqualified: lookup in env, fallback to *builtins*
                 (if-let [entry (or (find env expr) (find (or *builtins* core-builtins) expr))]
                   (ok (val entry) env memo idx)
                   (err (str "Unbound symbol: " expr) env memo idx expr))))

             ;; Vector: evaluate each element, threading state
             (vector? expr)
             (loop [remaining expr
                    acc []
                    e env
                    m memo
                    i idx]
               (if (empty? remaining)
                 (ok acc e m i)
                 (let [result (spell-eval (first remaining) e m i)]
                   (if (err? result)
                     result
                     (recur (rest remaining)
                            (conj acc (:ok result))
                            (:env result)
                            (:memo result)
                            (:idx result))))))

             ;; Map: spell-fn maps are self-evaluating; otherwise evaluate values
             (map? expr)
             (if (spell-fn? expr)
               (ok expr env memo idx)
               (loop [remaining (seq expr)
                      acc {}
                      e env
                      m memo
                      i idx]
                 (if (empty? remaining)
                   (ok acc e m i)
                   (let [[k v] (first remaining)
                         result (spell-eval v e m i)]
                     (if (err? result)
                       result
                       (recur (rest remaining)
                              (assoc acc k (:ok result))
                              (:env result)
                              (:memo result)
                              (:idx result)))))))

             ;; List: special forms or function application
             (seq? expr)
             (case (first expr)
               nil   (ok nil env memo idx)
               quote (ok (second expr) env memo idx)

               def   (let [sym (second expr)
                           val-expr (nth expr 2)
                           val-result (binding [*quote-env* (assoc *quote-env* sym val-expr)]
                                        (spell-eval val-expr env memo idx))]
                       (if (err? val-result)
                         val-result
                         (ok (:ok val-result)
                             (assoc (:env val-result) sym (:ok val-result))
                             (:memo val-result)
                             (:idx val-result))))

               do    (eval-seq (rest expr) env memo idx)

               if    (let [test-result (spell-eval (second expr) env memo idx)]
                       (if (err? test-result)
                         test-result
                         (spell-eval (nth expr (if (:ok test-result) 2 3) nil)
                                     (:env test-result)
                                     (:memo test-result)
                                     (:idx test-result))))

               ;; let: (let [bindings...] body...) - local bindings
               let   (let [bindings (partition 2 (second expr))
                           body (drop 2 expr)]
                       (loop [remaining bindings
                              local-env env
                              m memo
                              i idx]
                         (if (empty? remaining)
                           (let [body-result (eval-seq body local-env m i)]
                             (if (err? body-result)
                               body-result
                               ;; Let bindings don't escape
                               (ok (:ok body-result) env (:memo body-result) (:idx body-result))))
                           (let [[sym val-expr] (first remaining)
                                 val-result (spell-eval val-expr local-env m i)]
                             (if (err? val-result)
                               val-result
                               (recur (rest remaining)
                                      (assoc local-env sym (:ok val-result))
                                      (:memo val-result)
                                      (:idx val-result)))))))

               ;; fn: (fn [params...] body...) - dynamic scoping, returns source form
               fn    (ok {:spell/fn true :params (second expr) :body (drop 2 expr)} env memo idx)

               ;; defn: (defn name [params...] body...)
               defn  (let [name (second expr)
                           params (nth expr 2)
                           body (drop 3 expr)
                           fn-result (spell-eval (list* 'fn params body) env memo idx)]
                       (if (err? fn-result)
                         fn-result
                         (ok (:ok fn-result)
                             (assoc (:env fn-result) name (:ok fn-result))
                             (:memo fn-result)
                             (:idx fn-result))))

               ;; cond: (cond test1 expr1 test2 expr2 ... :else default)
               cond  (loop [clauses (partition 2 (rest expr))
                            e env
                            m memo
                            i idx]
                       (if (empty? clauses)
                         (ok nil e m i)
                         (let [[test-expr result-expr] (first clauses)]
                           (if (= test-expr :else)
                             (spell-eval result-expr e m i)
                             (let [test-result (spell-eval test-expr e m i)]
                               (if (err? test-result)
                                 test-result
                                 (if (:ok test-result)
                                   (spell-eval result-expr (:env test-result) (:memo test-result) (:idx test-result))
                                   (recur (rest clauses) (:env test-result) (:memo test-result) (:idx test-result)))))))))

               ;; and: short-circuit, returns last truthy or first falsy
               and   (loop [exprs (rest expr)
                            e env
                            m memo
                            i idx
                            last-v true]
                       (if (empty? exprs)
                         (ok last-v e m i)
                         (let [result (spell-eval (first exprs) e m i)]
                           (if (err? result)
                             result
                             (if (:ok result)
                               (recur (rest exprs) (:env result) (:memo result) (:idx result) (:ok result))
                               (ok (:ok result) (:env result) (:memo result) (:idx result)))))))

               ;; or: short-circuit, returns first truthy or last falsy
               or    (loop [exprs (rest expr)
                            e env
                            m memo
                            i idx
                            last-v nil]
                       (if (empty? exprs)
                         (ok last-v e m i)
                         (let [result (spell-eval (first exprs) e m i)]
                           (if (err? result)
                             result
                             (if (:ok result)
                               (ok (:ok result) (:env result) (:memo result) (:idx result))
                               (recur (rest exprs) (:env result) (:memo result) (:idx result) (:ok result)))))))

               ;; uneval: (uneval 'sym) - get the quoted source of a binding during its evaluation
               uneval (let [sym-result (spell-eval (second expr) env memo idx)]
                        (if (err? sym-result)
                          sym-result
                          (let [sym-v (:ok sym-result)]
                            (if-not (symbol? sym-v)
                              (err (str "uneval: argument must evaluate to a symbol, got " (type sym-v))
                                   (:env sym-result) (:memo sym-result) (:idx sym-result) expr)
                              (if-let [quoted (get *quote-env* sym-v)]
                                (ok quoted (:env sym-result) (:memo sym-result) (:idx sym-result))
                                (err (str "uneval: symbol not found in quote environment: " sym-v)
                                     (:env sym-result) (:memo sym-result) (:idx sym-result) expr))))))

               ;; expand: (expand expr) - single-pass walk mirroring spell-eval
               expand (let [quoted-result (spell-eval (second expr) env memo idx)]
                        (if (err? quoted-result)
                          quoted-result
                          (ok (expand-expr (:ok quoted-result) (:env quoted-result))
                              (:env quoted-result) (:memo quoted-result) (:idx quoted-result))))

               ;; quine: (quine name body) — bind name to the source form (= expr), eval body
               quine (let [name-sym (second expr)
                           body (nth expr 2)
                           env' (assoc env name-sym expr)]
                       (spell-eval body env' memo idx))

               ;; memo: (memo N) - retrieve cached value at index N
               memo (let [n (second expr)]
                      (if-let [entry (get memo n)]
                        (ok (:value entry) env memo idx)
                        (err (str "No memo entry at index " n) env memo idx expr)))

               ;; future: (future expr) - evaluate expr in a new thread, return future handle
               future (let [body (second expr)
                            captured-env env
                            ;; Use 2-arg form for backwards compatibility in thread
                            f (bound-fn [] (first (spell-eval body captured-env)))]
                        (ok {:spell/future true :ref (clojure.core/future (f))} env memo idx))

               ;; ->: (-> x (f a) (g b)) - thread-first
               -> (spell-eval (thread-first (second expr) (drop 2 expr)) env memo idx)

               ;; ->>: (->> x (f a) (g b)) - thread-last
               ->> (spell-eval (thread-last (second expr) (drop 2 expr)) env memo idx)

               ;; Function application: evaluate all, apply first to rest
               (loop [remaining expr
                      vals []
                      e env
                      m memo
                      i idx]
                 (if (empty? remaining)
                   (let [f (first vals)
                         args (rest vals)]
                     (if (spell-fn? f)
                       (let [local-env (into e (map vector (:params f) args))
                             body-result (eval-seq (:body f) local-env m i)]
                         (if (err? body-result)
                           body-result
                           (ok (:ok body-result) e (:memo body-result) (:idx body-result))))
                       ;; Call Clojure function - wrap in try/catch for error handling
                       (try
                         (ok (binding [*spell-env* e] (apply f args)) e m i)
                         (catch Exception ex
                           (err (str "Function call failed: " (ex-message ex)) e m i expr)))))
                   (let [result (spell-eval (first remaining) e m i)]
                     (if (err? result)
                       result
                       (recur (rest remaining)
                              (conj vals (:ok result))
                              (:env result)
                              (:memo result)
                              (:idx result)))))))

             :else (err (str "Unknown expression type: " (type expr)) env memo idx expr))]
       ;; Record to memo after successful evaluation
       (record-memo result expr)))))

(defn run-spell
  "Run a spell program, returning just the value."
  [program]
  (let [result (spell-eval program {} [] 0)]
    (if (ok? result)
      (:ok result)
      (throw (ex-info (:err result) {:result result})))))
