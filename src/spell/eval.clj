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

(def ^:dynamic *spell-env*
  "Current spell-eval environment during function application.
   Allows Clojure builtins (like apply) to access the current env for spell-fn support."
  {})

(def ^:dynamic *raw-text*
  "Balanced raw text of the current completion being evaluated.
   Set by the eval pipeline; used by reopen to preserve original formatting
   for KV cache compatibility (avoids pr-str round-trip divergence)."
  nil)

(defn spell-future?
  "Returns true if v is a Spell future handle."
  [v]
  (and (map? v) (:spell/future v)))


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

(defn invoke-fn
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
   'int int, 'quot quot, 'mod mod, 'max max, 'min min, 'max-key max-key, 'min-key min-key, 'rem rem,
   'abs abs,
   'parse-number (fn [s]
                   (let [s (str s)
                         m (re-find #"-?\d+(?:\.\d+)?" s)]
                     (when m
                       (if (re-find #"\." m) (Double/parseDouble m) (Long/parseLong m))))),
   ;; Numeric predicates
   'even? even?, 'odd? odd?, 'pos? pos?, 'neg? neg?, 'zero? zero?,
   ;; Comparison
   '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=, 'compare compare,
   ;; Logic
   'not not, 'nil? nil?, 'empty? empty?, 'some? some?, 'true? true?, 'false? false?,
   ;; Strings (core only - extended in strings registry)
   'str str, 'pr-str pr-str,
   'cat (fn [& args] (apply str args)),
   ;; Type predicates
   'string? string?, 'number? number?, 'list? list?, 'seq? seq?, 'vector? vector?, 'set? set?,
   'map? (fn [v] (and (map? v) (not (spell-fn? v)) (not (spell-future? v)))),
   'fn? (fn [v] (or (fn? v) (spell-fn? v))),
   'keyword? keyword?, 'symbol? symbol?,
   ;; Type constructors
   'name name, 'symbol symbol, 'keyword keyword,
   ;; Identity/utility
   'identity identity,
   ;; Collections (core only - extended in seqs registry)
   'list list, 'vector vector, 'set set, 'first first, 'second second, 'rest rest, 'last last,
   'cons cons, 'conj conj, 'get get, 'assoc assoc, 'count count,
   'reverse (fn [coll] (vec (reverse coll))),
   'nth (fn
          ([coll idx] (nth coll idx))
          ([coll idx not-found] (nth coll idx not-found))),
   'keys keys, 'vals vals, 'key key, 'val val,
   'bigint bigint,
   'into (fn [to from] (into to from)),
   'concat concat,
   ;; Collection access/mutation
   'peek peek, 'pop pop, 'butlast butlast,
   'subvec (fn
             ([v start] (subvec v start))
             ([v start end] (subvec v start end))),
   'vec vec, 'not-empty not-empty,
   ;; Map operations
   'merge (fn [& maps] (apply merge maps)),
   'update (fn
             ([m k f] (update m k #(invoke-fn f [%])))
             ([m k f & args] (update m k #(invoke-fn f (into [%] args))))),
   'update-in (fn
                ([m ks f] (update-in m ks #(invoke-fn f [%])))
                ([m ks f & args] (update-in m ks #(invoke-fn f (into [%] args))))),
   'get-in get-in, 'assoc-in assoc-in, 'dissoc dissoc,
   ;; Set operations
   'contains? contains?, 'disj disj,
   'apply (fn [f & args]
            (let [all-args (concat (butlast args) (last args))]
              (if (spell-fn? f)
                (let [local-env (into *spell-env* (map vector (:params f) all-args))]
                  (first (spell-eval (cons 'do (:body f)) local-env)))
                (clojure.core/apply f all-args)))),
   ;; Core higher-order functions (spell-fn aware)
   'map (fn [f coll] (mapv #(invoke-fn f [%]) coll)),
   'map-indexed (fn [f coll] (vec (map-indexed #(invoke-fn f [%1 %2]) coll))),
   'filter (fn [pred coll] (filterv #(invoke-fn pred [%]) coll)),
   'reduce (fn
             ([f coll] (clojure.core/reduce #(invoke-fn f [%1 %2]) coll))
             ([f init coll] (clojure.core/reduce #(invoke-fn f [%1 %2]) init coll))),
   ;; Slicing
   'take (fn [n coll] (vec (take n coll))),
   'drop (fn [n coll] (vec (drop n coll))),
   'take-last (fn [n coll] (vec (take-last n coll))),
   ;; Higher-order: keep (map + filter nil), some (find first truthy)
   'keep (fn [f coll] (vec (keep #(invoke-fn f [%]) coll))),
   'some (fn [pred coll] (some #(invoke-fn pred [%]) coll)),
   ;; Range
   'range (fn
            ([end] (vec (range end)))
            ([start end] (vec (range start end)))
            ([start end step] (vec (range start end step)))),
   ;; Strip / Reopen
   'strip-parens (fn [n s] (parse/strip-trailing-parens n (if (string? s) s (pr-str s)))),
   'reopen (fn [s]
              (parse/strip-trailing-parens 3
                (cond (string? s) s
                      *raw-text*  *raw-text*
                      :else       (pr-str s)))),
   ;; wrap-cat: combine quine-bound forms into an open preamble prefix
   'wrap-cat (fn [& forms]
               (str "(quine completion (eval (do "
                    (str/join " " (map pr-str forms))
                    " ")),
   ;; Eval — auto-expands free vars from caller's env, then evaluates in fresh env
   'spell-eval (fn [expr] (first (spell-eval (expand-expr expr *spell-env*) {}))),
   ;; Concurrency
   'await (fn [future-val]
            (when-not (spell-future? future-val)
              (throw (ex-info "await: argument must be a future" {:got future-val})))
            (deref (:ref future-val))),
   'await-all (fn [futures]
                (when-not (sequential? futures)
                  (throw (ex-info "await-all: argument must be a collection" {:got futures})))
                (mapv (fn [f]
                        (when-not (spell-future? f)
                          (throw (ex-info "await-all: all elements must be futures" {:got f})))
                        (deref (:ref f)))
                      futures)),
   'pmap (fn [f coll]
           (let [futures (mapv (fn [item]
                                 {:spell/future true
                                  :ref (clojure.core/future ((bound-fn [] (invoke-fn f [item]))))})
                               coll)]
             (mapv #(deref (:ref %)) futures))),
   ;; Extended sequence operations (from seqs/)
   'every? (fn [pred coll] (every? #(invoke-fn pred [%]) coll)),
   'remove (fn [pred coll] (filterv #(not (invoke-fn pred [%])) coll)),
   'mapcat (fn [f coll] (vec (mapcat #(invoke-fn f [%]) coll))),
   'take-while (fn [pred coll] (vec (take-while #(invoke-fn pred [%]) coll))),
   'drop-while (fn [pred coll] (vec (drop-while #(invoke-fn pred [%]) coll))),
   'not-any? (fn [pred coll] (not-any? #(invoke-fn pred [%]) coll)),
   'group-by (fn [f coll]
               (reduce (fn [m x]
                         (let [k (invoke-fn f [x])]
                           (clojure.core/update m k (fnil conj []) x)))
                       {} coll)),
   'sort-by (fn [keyfn coll] (vec (sort-by #(invoke-fn keyfn [%]) coll))),
   'sort (fn [coll] (vec (sort coll))),
   'repeat (fn [n x] (vec (repeat n x))),
   'repeatedly (fn [n f] (vec (repeatedly n #(invoke-fn f [])))),
   'distinct (fn [coll] (vec (distinct coll))),
   'flatten (fn [coll] (vec (flatten coll))),
   'frequencies frequencies,
   'partition (fn
                ([n coll] (vec (clojure.core/map vec (partition n coll))))
                ([n step coll] (vec (clojure.core/map vec (partition n step coll))))),
   'partition-all (fn
                    ([n coll] (vec (clojure.core/map vec (partition-all n coll))))
                    ([n step coll] (vec (clojure.core/map vec (partition-all n step coll))))),
   'interleave (fn [& colls] (vec (apply interleave colls))),
   'interpose (fn [sep coll] (vec (interpose sep coll))),
   'zipmap zipmap,
   'split-at (fn [n coll] [(vec (take n coll)) (vec (drop n coll))]),
   'merge-with (fn [f & maps]
                 (reduce (fn [acc m]
                           (reduce-kv (fn [a k v]
                                        (if (contains? a k)
                                          (assoc a k (invoke-fn f [(get a k) v]))
                                          (assoc a k v)))
                                      acc m))
                         {} maps)),
   'select-keys select-keys,
   'reduce-kv (fn [f init m]
                (reduce-kv (fn [acc k v] (invoke-fn f [acc k v])) init m)),
   'sorted-map (fn [& keyvals] (apply sorted-map keyvals)),
   'sorted-set (fn [& vals] (apply sorted-set vals)),
   'sorted-map-by (fn [comp & keyvals]
                    (apply sorted-map-by #(invoke-fn comp [%1 %2]) keyvals)),
   'sorted-set-by (fn [comp & vals]
                    (apply sorted-set-by #(invoke-fn comp [%1 %2]) vals)),
   'coll? coll?,
   'sequential? sequential?,
   'int? int?,
   'find-first (fn [pred coll] (some #(when (invoke-fn pred [%]) %) coll)),
   ;; Function combinators (from fns/)
   'comp (fn [& fns]
           (fn [x]
             (reduce (fn [v f] (invoke-fn f [v])) x (reverse fns)))),
   'partial (fn [f & args]
              (fn [& more]
                (invoke-fn f (concat args more)))),
   'juxt (fn [& fns]
           (fn [& args]
             (mapv #(invoke-fn % args) fns))),
   'complement (fn [f]
                 (fn [& args]
                   (not (invoke-fn f args)))),
   'constantly (fn [x] (fn [& _] x)),
   'every-pred (fn [& preds]
                 (fn [& args]
                   (every? #(invoke-fn % args) preds))),
   'some-fn (fn [& preds]
              (fn [& args]
                (some #(invoke-fn % args) preds))),
   'fnil (fn [f & defaults]
           (fn [& args]
             (let [fixed-args (mapv (fn [arg default]
                                      (if (nil? arg) default arg))
                                    args
                                    (concat defaults (repeat nil)))]
               (invoke-fn f fixed-args)))),
   ;; Bitwise operations (with bit- prefix like Clojure)
   'bit-and bit-and,
   'bit-or bit-or,
   'bit-xor bit-xor,
   'bit-not bit-not,
   'bit-shift-left bit-shift-left,
   'bit-shift-right bit-shift-right,
   'unsigned-bit-shift-right unsigned-bit-shift-right,
   'bit-set bit-set,
   'bit-clear bit-clear,
   'bit-flip bit-flip,
   'bit-test bit-test})

(def ^:dynamic *builtins*
  "Active builtins map. Rebound by each llm variant during evaluation.
   Root binding set by spell.core after all definitions exist."
  nil)

;; =============================================================================
;; Free variable analysis
;; =============================================================================

(def special-forms
  "Special forms that are not free variables."
  #{'quote 'def 'do 'if 'when 'let 'fn 'fn* 'defn 'cond 'and 'or 'expand 'eval 'future 'plet 'quine 'call-now '-> '->> 'memo 'loop 'recur 'for 'try 'throw})

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
    (or (nil? expr) (string? expr) (number? expr) (boolean? expr) (keyword? expr)
        (instance? java.util.regex.Pattern expr))
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

        when [(list* 'when (map expand1 (rest expr))) inner]

        let (let [pairs (partition 2 (second expr))
                  [expanded-bindings final-inner]
                  (reduce (fn [[acc i] [sym val-expr]]
                            [(conj acc sym (first (-expand-expr val-expr outer-env i)))
                             (conj i sym)])
                          [[] inner] pairs)
                  expanded-body (map #(first (-expand-expr % outer-env final-inner)) (drop 2 expr))]
              [(list* 'let (vec expanded-bindings) expanded-body) inner])

        (fn fn*) (let [params (set (second expr))
                       body-inner (into inner params)]
                   [(list* 'fn (second expr) (map #(first (-expand-expr % outer-env body-inner)) (drop 2 expr))) inner])

        defn (let [name-sym (second expr)
                   params (set (nth expr 2))
                   body-inner (into inner (conj params name-sym))]
               [(list* 'defn name-sym (nth expr 2) (map #(first (-expand-expr % outer-env body-inner)) (drop 3 expr)))
                (conj inner name-sym)])

        future [(list 'future (expand1 (second expr))) inner]

        plet (let [pairs (partition 2 (second expr))
                   [expanded-bindings final-inner]
                   (reduce (fn [[acc i] [sym val-expr]]
                             [(conj acc sym (first (-expand-expr val-expr outer-env i)))
                              (conj i sym)])
                           [[] inner] pairs)
                   expanded-body (map #(first (-expand-expr % outer-env final-inner)) (drop 2 expr))]
               [(list* 'plet (vec expanded-bindings) expanded-body) inner])

        quine (let [name-sym (second expr)
                    [body-expanded _] (-expand-expr (nth expr 2) outer-env (conj inner name-sym))]
                [(list 'quine name-sym body-expanded) (conj inner name-sym)])

        loop (let [pairs (partition 2 (second expr))
                   [expanded-bindings final-inner]
                   (reduce (fn [[acc i] [sym val-expr]]
                             [(conj acc sym (first (-expand-expr val-expr outer-env i)))
                              (conj i sym)])
                           [[] inner] pairs)
                   expanded-body (map #(first (-expand-expr % outer-env final-inner)) (drop 2 expr))]
               [(list* 'loop (vec expanded-bindings) expanded-body) inner])

        recur [(list* 'recur (map expand1 (rest expr))) inner]

        ;; for: (for [x coll :when pred :let [y expr]] body)
        for (let [bindings (second expr)
                  body (nth expr 2)
                  ;; Parse bindings into segments, tracking bound symbols
                  [expanded-bindings final-inner]
                  (loop [remaining (seq bindings)
                         acc []
                         bound inner]
                    (if (empty? remaining)
                      [acc bound]
                      (let [item (first remaining)]
                        (cond
                          ;; :when pred
                          (= item :when)
                          (let [[pred-expanded _] (-expand-expr (second remaining) outer-env bound)]
                            (recur (drop 2 remaining)
                                   (conj acc :when pred-expanded)
                                   bound))
                          ;; :let [bindings...]
                          (= item :let)
                          (let [let-pairs (partition 2 (second remaining))
                                [let-expanded let-bound]
                                (reduce (fn [[lacc lbound] [sym val-expr]]
                                          [(conj lacc sym (first (-expand-expr val-expr outer-env lbound)))
                                           (conj lbound sym)])
                                        [[] bound] let-pairs)]
                            (recur (drop 2 remaining)
                                   (conj acc :let (vec let-expanded))
                                   let-bound))
                          ;; Normal binding: sym coll
                          :else
                          (let [sym item
                                coll-expr (second remaining)
                                [coll-expanded _] (-expand-expr coll-expr outer-env bound)]
                            (recur (drop 2 remaining)
                                   (conj acc sym coll-expanded)
                                   (conj bound sym)))))))]
              [(list 'for (vec expanded-bindings) (first (-expand-expr body outer-env final-inner))) inner])

        throw [(list 'throw (expand1 (second expr))) inner]

        try (let [forms (rest expr)
                  catch-form (when (and (seq forms) (seq? (last forms))
                                        (= 'catch (first (last forms))))
                               (last forms))
                  body-forms (if catch-form (butlast forms) forms)
                  [expanded-body final-inner]
                  (reduce (fn [[acc i] sub-expr]
                            (let [[expanded new-i] (-expand-expr sub-expr outer-env i)]
                              [(conj acc expanded) new-i]))
                          [[] inner] body-forms)
                  expanded-catch (when catch-form
                                   (let [catch-sym (second catch-form)
                                         catch-inner (conj final-inner catch-sym)
                                         expanded-catch-body (map #(first (-expand-expr % outer-env catch-inner))
                                                                  (drop 2 catch-form))]
                                     (list* 'catch catch-sym expanded-catch-body)))]
              [(if expanded-catch
                 (list* 'try (concat expanded-body [expanded-catch]))
                 (list* 'try expanded-body))
               inner])

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
             ;; Self-evaluating: nil, strings, numbers, booleans, keywords, regex patterns, sets
             (or (nil? expr) (string? expr) (number? expr) (boolean? expr) (keyword? expr)
                 (instance? java.util.regex.Pattern expr) (set? expr))
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
                           val-result (spell-eval (nth expr 2) env memo idx)]
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

               ;; when: (when test body...) - evaluate body when test is truthy, else nil
               when  (spell-eval (list 'if (second expr) (cons 'do (drop 2 expr)) nil) env memo idx)

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

               ;; fn/fn*: (fn [params...] body...) - dynamic scoping, returns source form
               ;; fn* is Clojure's internal form produced by #() reader macro
               (fn fn*)
                     (ok {:spell/fn true :params (second expr) :body (drop 2 expr)} env memo idx)

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

               ;; expand: (expand expr) - single-pass walk mirroring spell-eval
               expand (let [quoted-result (spell-eval (second expr) env memo idx)]
                        (if (err? quoted-result)
                          quoted-result
                          (ok (expand-expr (:ok quoted-result) (:env quoted-result))
                              (:env quoted-result) (:memo quoted-result) (:idx quoted-result))))

               ;; eval: (eval expr) - expand and evaluate using current env (like Clojure's eval)
               eval (let [quoted-result (spell-eval (second expr) env memo idx)]
                      (if (err? quoted-result)
                        quoted-result
                        (let [expanded (expand-expr (:ok quoted-result) (:env quoted-result))]
                          (spell-eval expanded (:env quoted-result) (:memo quoted-result) (:idx quoted-result)))))

               ;; call-now: (call-now name expr) - evaluate expr, bind to name, continue in child LLM
               ;; Like def but spawns child LLM that continues with the binding in scope
               call-now
               (let [name-sym (second expr)
                     val-expr (nth expr 2)]
                 ;; Evaluate the value expression
                 (let [val-result (spell-eval val-expr env memo idx)]
                   (if (err? val-result)
                     val-result
                     (let [result-val (:ok val-result)
                           e (:env val-result)
                           m (:memo val-result)
                           i (:idx val-result)
                           ;; Get completion from env (bound by quine preamble)
                           completion (get e 'completion)
                           ;; Check if binding already exists (idempotency guard)
                           quine-str (str "(quine " name-sym " ")
                           already-bound? (and completion
                                               (.contains (pr-str completion) quine-str))]
                       (if already-bound?
                         ;; Already bound, just return the result
                         (ok result-val e m i)
                         ;; Not bound - call llm-self with extended completion
                         (let [;; Build the continuation prompt
                               reopen-fn (get *builtins* 'reopen)
                               reopened (reopen-fn completion)
                               continuation (str reopened "(quine " name-sym " " (pr-str result-val) ") ")
                               ;; Get llm-self and call it
                               llm-self-fn (get *builtins* 'llm-self)]
                           (if llm-self-fn
                             (try
                               (ok (llm-self-fn continuation) e m i)
                               (catch Exception ex
                                 (err (str "call-now failed: " (.getMessage ex)) e m i expr)))
                             (err "call-now requires llm-self (only available inside llm calls)" e m i expr))))))))

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

               ;; loop: (loop [bindings...] body...) - establishes a recursion point
               loop (let [bindings (partition 2 (second expr))
                          body (drop 2 expr)
                          binding-syms (mapv first bindings)
                          init-exprs (mapv second bindings)]
                      ;; Evaluate initial values
                      (loop [remaining init-exprs
                             init-vals []
                             e env
                             m memo
                             i idx]
                        (if (empty? remaining)
                          ;; All initial values evaluated, now run the loop body
                          (loop [current-vals init-vals
                                 loop-m m
                                 loop-i i]
                            (let [local-env (into e (map vector binding-syms current-vals))
                                  body-result (eval-seq body local-env loop-m loop-i)]
                              (if (err? body-result)
                                body-result
                                (if (and (map? (:ok body-result)) (:spell/recur (:ok body-result)))
                                  (recur (:vals (:ok body-result))
                                         (:memo body-result)
                                         (:idx body-result))
                                  ;; Normal return - restore outer env
                                  (ok (:ok body-result) e (:memo body-result) (:idx body-result))))))
                          ;; Still evaluating initial values
                          (let [result (spell-eval (first remaining) e m i)]
                            (if (err? result)
                              result
                              (recur (rest remaining)
                                     (conj init-vals (:ok result))
                                     (:env result)
                                     (:memo result)
                                     (:idx result)))))))

               ;; recur: (recur exprs...) - jump back to loop with new values
               recur (loop [remaining (rest expr)
                            vals []
                            e env
                            m memo
                            i idx]
                       (if (empty? remaining)
                         (ok {:spell/recur true :vals vals} e m i)
                         (let [result (spell-eval (first remaining) e m i)]
                           (if (err? result)
                             result
                             (recur (rest remaining)
                                    (conj vals (:ok result))
                                    (:env result)
                                    (:memo result)
                                    (:idx result))))))

               ;; for: (for [x coll :when pred :let [y expr]] body) - list comprehension
               for (let [bindings (second expr)
                         body (nth expr 2)]
                     ;; Parse bindings into structured form
                     (letfn [(parse-bindings [remaining]
                               (loop [rem (seq remaining)
                                      parsed []]
                                 (if (empty? rem)
                                   parsed
                                   (let [item (first rem)]
                                     (cond
                                       (= item :when)
                                       (recur (drop 2 rem) (conj parsed {:type :when :pred (second rem)}))
                                       (= item :let)
                                       (recur (drop 2 rem) (conj parsed {:type :let :bindings (partition 2 (second rem))}))
                                       :else
                                       (recur (drop 2 rem) (conj parsed {:type :iter :sym item :coll (second rem)})))))))]
                       (let [parsed (parse-bindings bindings)]
                         ;; Recursive evaluation of for comprehension
                         (letfn [(eval-for [segments local-env m i]
                                   (if (empty? segments)
                                     ;; Evaluate body and return single-element result
                                     (let [body-result (spell-eval body local-env m i)]
                                       (if (err? body-result)
                                         body-result
                                         (ok [(:ok body-result)] (:env body-result) (:memo body-result) (:idx body-result))))
                                     ;; Process next segment
                                     (let [seg (first segments)
                                           rest-segs (rest segments)]
                                       (case (:type seg)
                                         :iter
                                         (let [coll-result (spell-eval (:coll seg) local-env m i)]
                                           (if (err? coll-result)
                                             coll-result
                                             (loop [items (seq (:ok coll-result))
                                                    acc []
                                                    cur-m (:memo coll-result)
                                                    cur-i (:idx coll-result)]
                                               (if (empty? items)
                                                 (ok acc local-env cur-m cur-i)
                                                 (let [item-env (assoc local-env (:sym seg) (first items))
                                                       sub-result (eval-for rest-segs item-env cur-m cur-i)]
                                                   (if (err? sub-result)
                                                     sub-result
                                                     (recur (rest items)
                                                            (into acc (:ok sub-result))
                                                            (:memo sub-result)
                                                            (:idx sub-result))))))))
                                         :when
                                         (let [pred-result (spell-eval (:pred seg) local-env m i)]
                                           (if (err? pred-result)
                                             pred-result
                                             (if (:ok pred-result)
                                               (eval-for rest-segs local-env (:memo pred-result) (:idx pred-result))
                                               (ok [] local-env (:memo pred-result) (:idx pred-result)))))
                                         :let
                                         (loop [let-bindings (:bindings seg)
                                                let-env local-env
                                                let-m m
                                                let-i i]
                                           (if (empty? let-bindings)
                                             (eval-for rest-segs let-env let-m let-i)
                                             (let [[sym val-expr] (first let-bindings)
                                                   val-result (spell-eval val-expr let-env let-m let-i)]
                                               (if (err? val-result)
                                                 val-result
                                                 (recur (rest let-bindings)
                                                        (assoc let-env sym (:ok val-result))
                                                        (:memo val-result)
                                                        (:idx val-result))))))))))]
                           (let [result (eval-for parsed env memo idx)]
                             (if (err? result)
                               result
                               ;; Return env unchanged (for bindings don't escape)
                               (ok (:ok result) env (:memo result) (:idx result))))))))

               ;; try: (try body... (catch e handler...))
               try (let [forms (rest expr)
                         catch-form (when (and (seq forms) (seq? (last forms))
                                               (= 'catch (first (last forms))))
                                      (last forms))
                         body-forms (if catch-form (butlast forms) forms)
                         body-result (eval-seq body-forms env memo idx)]
                     (if (and (err? body-result) catch-form)
                       (let [catch-sym (second catch-form)
                             catch-body (drop 2 catch-form)
                             error-val (if (contains? body-result :thrown)
                                         (:thrown body-result)
                                         {:message (:err body-result) :expr (:expr body-result)})
                             catch-env (assoc (:env body-result) catch-sym error-val)
                             catch-result (eval-seq catch-body catch-env
                                                    (:memo body-result) (:idx body-result))]
                         (if (err? catch-result)
                           catch-result
                           (ok (:ok catch-result)
                               (dissoc (:env catch-result) catch-sym)
                               (:memo catch-result) (:idx catch-result))))
                       body-result))

               ;; throw: (throw value) - raise a catchable error
               throw (let [val-result (spell-eval (second expr) env memo idx)]
                       (if (err? val-result)
                         val-result
                         {:err (if (string? (:ok val-result))
                                 (:ok val-result)
                                 (pr-str (:ok val-result)))
                          :thrown (:ok val-result)
                          :env (:env val-result)
                          :memo (:memo val-result)
                          :idx (:idx val-result)
                          :expr expr}))

               ;; future: (future expr) - evaluate expr in a new thread, return future handle
               future (let [body (second expr)
                            captured-env env
                            ;; Use 2-arg form for backwards compatibility in thread
                            f (bound-fn [] (first (spell-eval body captured-env)))]
                        (ok {:spell/future true :ref (clojure.core/future (f))} env memo idx))

               ;; plet: (plet [name1 expr1 name2 expr2 ...] body...) - parallel let
               ;; Evaluates all exprs as implicit futures, awaits all, binds results, runs body
               plet (let [bindings (partition 2 (second expr))
                          body (drop 2 expr)]
                      ;; Evaluate all binding exprs sequentially to collect memo/idx,
                      ;; then wrap each result in a future
                      (let [eval-results
                            (loop [remaining bindings
                                   acc []
                                   m memo
                                   i idx]
                              (if (empty? remaining)
                                {:futures acc :memo m :idx i}
                                (let [[sym val-expr] (first remaining)
                                      captured-env env
                                      f (bound-fn [] (first (spell-eval val-expr captured-env)))
                                      fut {:spell/future true
                                           :ref (clojure.core/future (f))}]
                                  (recur (rest remaining)
                                         (conj acc [sym fut])
                                         m i))))]
                        ;; Await all futures and bind results
                        (let [local-env (reduce (fn [e [sym fut]]
                                                  (assoc e sym (deref (:ref fut))))
                                                env
                                                (:futures eval-results))
                              body-result (eval-seq body local-env
                                                    (:memo eval-results) (:idx eval-results))]
                          (if (err? body-result)
                            body-result
                            ;; Join bindings don't escape (like let)
                            (ok (:ok body-result) env (:memo body-result) (:idx body-result))))))

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
                       ;; Spell fn: loop to support fn-level recur
                       (loop [current-args args
                              fn-m m
                              fn-i i]
                         (let [local-env (into e (map vector (:params f) current-args))
                               body-result (eval-seq (:body f) local-env fn-m fn-i)]
                           (if (err? body-result)
                             body-result
                             (if (and (map? (:ok body-result)) (:spell/recur (:ok body-result)))
                               ;; recur: rebind params, re-enter function body
                               (recur (:vals (:ok body-result))
                                      (:memo body-result)
                                      (:idx body-result))
                               ;; normal return
                               (ok (:ok body-result) e (:memo body-result) (:idx body-result))))))
                       ;; Call Clojure function - wrap in try/catch for error handling
                       (try
                         (ok (binding [*spell-env* e] (apply f args)) e m i)
                         (catch Exception ex
                           (let [thrown (get (ex-data ex) :spell/thrown)]
                             (if thrown
                               {:err (ex-message ex) :thrown thrown :env e :memo m :idx i :expr expr}
                               (err (str "Function call failed: " (ex-message ex)) e m i expr)))))))
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
