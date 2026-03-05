(ns spell.eval
  "Spell evaluator: spell-eval, expand, free variable analysis, builtins.

   spell-eval takes (expr, env) and returns a result map:
   - On success: {:ok value :env env'}
   - On error: {:err msg :env env :expr failing-expr}"
  (:require [spell.macros :as macros]
            [spell.parse :as parse]
            [clojure.string :as str]
            [clojure.set :as set]))

;; =============================================================================
;; Error message prefixes (shared with recovery.clj)
;; =============================================================================

(def unbound-symbol-prefix "Unbound symbol: ")
(def namespace-lookup-prefix "Namespace lookup failed: ")
(def fn-call-prefix "Function call failed: ")

;; =============================================================================
;; Dynamic vars
;; =============================================================================

(def ^:dynamic *verbose*
  "When true, print LLM prompts and responses."
  false)

(def ^:dynamic *log-writer*
  "Writer for verbose log output. Defaults to *err*."
  nil)

(defn vlog
  "Print verbose log message when *verbose* is true.
   Writes to *log-writer* if set, otherwise *err*."
  [& args]
  (when *verbose*
    (let [w (or *log-writer* *err*)]
      (locking w
        (binding [*out* w]
          (apply println args))))))

(def ^:dynamic *llm-depth*
  "Current depth of nested LLM calls (for indentation)."
  0)

(def ^:dynamic *max-llm-depth*
  "Maximum allowed LLM recursion depth. Set to nil to disable limit."
  nil)

(def ^:dynamic *spell-env*
  "Current spell-eval environment during function application.
   Allows Clojure builtins (like apply) to access the current env for spell-fn support."
  {})

(def ^:dynamic *future-env*
  "Env entries injected when evaluating inside a Spell future.
   Used for future-only namespaces like blocking/."
  {})

(def ^:dynamic *raw-text*
  "Balanced raw text of the current completion being evaluated.
   Set by the eval pipeline; used by reopen to preserve original formatting
   for KV cache compatibility (avoids pr-str round-trip divergence)."
  nil)

(def future-context-key
  "Env marker key set by future* for future-only runtime primitives."
  ::in-future)

(defn in-future-context?
  "True when current evaluation is running inside a Spell future."
  []
  (true? (get *spell-env* future-context-key)))

(defn spell-future?
  "Returns true if v is a Spell future handle."
  [v]
  (and (map? v) (:spell/future v)))

;; =============================================================================
;; Call-now value store (out-of-band storage for large values)
;; =============================================================================

(def call-now-store
  "Global store for large values that shouldn't be inlined in continuations.
   Maps string IDs to values. Used by !call-now to avoid embedding huge strings
   (like file contents) directly in the code the LLM sees."
  (atom {}))

(def call-now-inline-limit
  "Default max character count of pr-str output before truncation/storage.
   Used by serialize-for-continuation when no explicit limit is provided."
  10000)

(defn store-value!
  "Store a value in the !call-now store, return its ID."
  [value]
  (let [id (str (gensym "ref-"))]
    (swap! call-now-store assoc id value)
    id))

(defn stored
  "Retrieve a value from the !call-now store."
  [id]
  (let [v (get @call-now-store id ::not-found)]
    (if (= v ::not-found)
      (throw (ex-info (str "No stored value: " id) {:id id}))
      v)))

(defn- truncate-string
  "Truncate a string to fit within limit chars when serialized.
   Appends a note showing original length."
  [s limit]
  (let [note (format "\n... [truncated, %d chars total]" (count s))
        max-chars (max 100 (- limit (count (pr-str note)) 50))
        truncated (str (subs s 0 (min (count s) max-chars)) note)]
    (pr-str truncated)))

(defn- deep-truncate
  "Recursively truncate string values within maps and sequences.
   Returns a new value where all strings exceeding limit are truncated.
   Non-string leaves are unchanged."
  [value limit]
  (cond
    (string? value)
    (if (<= (count (pr-str value)) limit)
      value
      (let [note (format "\n... [truncated, %d chars total]" (count value))
            max-chars (max 100 (- limit (count (pr-str note)) 50))]
        (str (subs value 0 (min (count value) max-chars)) note)))

    (map? value)
    (into {} (map (fn [[k v]] [k (deep-truncate v limit)]) value))

    (sequential? value)
    (mapv #(deep-truncate % limit) value)

    :else value))

(defn- format-line-offset-vector
  "Serialize a vector with :spell/line-offset metadata as a vector literal
   where each entry has an inline ; line-number comment.
   Returns nil if the vector doesn't have line-offset metadata."
  [value]
  (when-let [offset (:spell/line-offset (meta value))]
    (if (empty? value)
      "[]"
      (let [last-line (+ offset (dec (count value)))
            width (count (str last-line))
            rows (map-indexed (fn [i line]
                                (str " " (pr-str line)
                                     " ; "
                                     (format (str "%" width "d") (+ offset i))))
                              value)]
        (str "[\n" (str/join "\n" rows) "\n]")))))

(defn serialize-for-continuation
  "Serialize a value for embedding in a !call-now continuation.
   Small values are inlined via pr-str. Large strings are truncated with a note.
   Large non-strings are deep-truncated (string values within maps/seqs are
   individually truncated) then inlined. Only stored out-of-band if still too large.
   Vectors with :spell/line-offset metadata produce a vector literal with
   inline line-number comments.
   limit: max pr-str chars before truncation/storage. Negative means always inline."
  ([value] (serialize-for-continuation value call-now-inline-limit))
  ([value limit]
   ;; Check for line-offset metadata first
   (if-let [formatted (and (vector? value) (meta value) (format-line-offset-vector value))]
     (if (or (neg? limit) (<= (count formatted) (* (max 1 limit) 2)))
       formatted
       ;; Too large — fall through to normal serialization (truncation/storage)
       (let [serialized (pr-str value)]
         (if (<= (count serialized) limit)
           serialized
           (let [id (store-value! value)]
             (str "(stored " (pr-str id) ")")))))
     (if (neg? limit)
       (pr-str value)
       (let [serialized (pr-str value)]
         (if (<= (count serialized) limit)
           serialized
           (if (string? value)
             (truncate-string value limit)
             ;; Non-string: deep-truncate string values within, then try to inline
             (let [truncated (deep-truncate value limit)
                   truncated-str (pr-str truncated)]
               (if (<= (count truncated-str) (* limit 2))
                 ;; Fits after truncation (allow 2x limit since map structure has overhead)
                 truncated-str
                 ;; Still too large — store out-of-band
                 (let [id (store-value! value)]
                   (str "(stored " (pr-str id) ")")))))))))))


;; =============================================================================
;; Result helpers
;; =============================================================================

(defn ok
  "Create a success result map."
  [value env]
  {:ok value :env env})

(defn err
  "Create an error result map."
  [msg env expr]
  {:err msg :env env :expr expr})

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

;; =============================================================================
;; Builtins
;; =============================================================================

(declare spell-eval expand-expr)

(defn spell-fn?
  "Returns true if v is a Spell function (dynamic-scoping function map)."
  [v]
  (and (map? v) (:spell/fn v)))

(defn spell-macro?
  "Returns true if v is a Spell macro (user-defined, env-based)."
  [v]
  (and (map? v) (:spell/macro v)))

(defn destructure-bind
  "Given a param pattern and a value, return a flat sequence of [symbol value] pairs.
   If param is a symbol, returns [[param value]].
   If param is a vector, recursively destructures positionally.
   Supports & rest and :as whole."
  [param value]
  (cond
    (symbol? param) [[param value]]
    (vector? param)
    (let [;; Check for :as
          as-idx (.indexOf ^java.util.List param :as)
          [core-params as-sym] (if (>= as-idx 0)
                                 [(subvec param 0 as-idx) (get param (inc as-idx))]
                                 [param nil])
          ;; Check for &
          amp-idx (.indexOf ^java.util.List core-params '&)
          [positional rest-sym] (if (>= amp-idx 0)
                                  [(subvec core-params 0 amp-idx) (get core-params (inc amp-idx))]
                                  [core-params nil])
          ;; Coerce value to sequential for nth access
          val-seq (if (sequential? value) (vec value) [value])
          ;; Bind positional params
          bindings (vec (mapcat (fn [i p] (destructure-bind p (nth val-seq i nil)))
                                (range) positional))
          ;; Bind rest if present
          bindings (if rest-sym
                     (into bindings (destructure-bind rest-sym (vec (drop (count positional) val-seq))))
                     bindings)
          ;; Bind :as if present
          bindings (if as-sym
                     (conj bindings [as-sym value])
                     bindings)]
      bindings)
    (map? param)
    (let [special-keys #{:keys :strs :syms :or :as}
          ;; Separate direct bindings from special keys (symbol or vector patterns mapped to keys)
          direct (into {} (filter (fn [[k _]] (and (not (special-keys k)) (or (symbol? k) (vector? k)))) param))
          keys-syms (:keys param)
          strs-syms (:strs param)
          syms-syms (:syms param)
          defaults (:or param)
          as-sym (:as param)
          ;; Build bindings from :keys
          key-bindings (mapcat (fn [s] (destructure-bind s (get value (keyword (name s))))) keys-syms)
          ;; Build bindings from :strs
          str-bindings (mapcat (fn [s] (destructure-bind s (get value (str (name s))))) strs-syms)
          ;; Build bindings from :syms
          sym-bindings (mapcat (fn [s] (destructure-bind s (get value (symbol (name s))))) syms-syms)
          ;; Build bindings from direct {pattern key} entries
          direct-bindings (mapcat (fn [[sym k]] (destructure-bind sym (get value k))) direct)
          ;; Combine all bindings
          bindings (vec (concat key-bindings str-bindings sym-bindings direct-bindings))
          ;; Apply :or defaults (replace nil values for keys that have defaults)
          bindings (if defaults
                     (mapv (fn [[s v]]
                             (if (and (nil? v) (contains? defaults s))
                               [s (get defaults s)]
                               [s v]))
                           bindings)
                     bindings)
          ;; Add :as binding
          bindings (if as-sym
                     (conj bindings [as-sym value])
                     bindings)]
      bindings)

    :else (throw (ex-info (str "Invalid destructuring pattern: " (pr-str param)) {:param param}))))

(defn bind-params
  "Bind fn params to args, supporting destructuring. Returns a flat seq of [sym val] pairs."
  [params args]
  (mapcat destructure-bind params args))

(defn param-symbols
  "Extract all binding symbols from a destructuring param pattern.
   Used by expand-expr to track locally-bound names."
  [param]
  (cond
    (symbol? param) [param]
    (vector? param)
    (loop [rem (seq param)
           syms []]
      (if (empty? rem)
        syms
        (let [item (first rem)]
          (cond
            (= item :as) (recur (drop 2 rem) (conj syms (second rem)))
            (= item '&) (recur (drop 2 rem) (into syms (param-symbols (second rem))))
            :else (recur (rest rem) (into syms (param-symbols item)))))))
    (map? param)
    (let [keys-syms (vec (mapcat param-symbols (:keys param)))
          strs-syms (vec (mapcat param-symbols (:strs param)))
          syms-syms (vec (mapcat param-symbols (:syms param)))
          direct-syms (vec (mapcat (fn [[k _]] (when (or (symbol? k) (vector? k)) (param-symbols k)))
                                   (dissoc param :keys :strs :syms :or :as)))
          as-sym (when-let [s (:as param)] [s])]
      (into [] (concat keys-syms strs-syms syms-syms direct-syms as-sym)))

    :else []))

(defn invoke-fn
  "Invoke f with args. Handles both spell-fns and Clojure fns.
   Uses *spell-env* for spell-fn body evaluation."
  [f args]
  (if (spell-fn? f)
    (let [local-env (into *spell-env* (bind-params (:params f) args))
          r (spell-eval (cons 'do (:body f)) local-env)]
      (if (ok? r) (:ok r) (throw (ex-info (:err r) {:result r}))))
    (apply f args)))

(def core-builtins
  "Language primitives - always available in every llm variant.
   Extended functions are in stdlib registries (strings, seqs, fns)."
  {;; Math
   '+ +, '- -, '* *, '/ /, 'inc inc, 'dec dec,
   'int int, 'long long, 'float float, 'double double, 'bigdec bigdec, 'rationalize rationalize,
   'quot quot, 'mod mod, 'max max, 'min min, 'max-key max-key, 'min-key min-key, 'rem rem,
   'abs abs, 'floor (fn [x] (long (Math/floor x))), 'ceil (fn [x] (long (Math/ceil x))),
   'rand rand, 'rand-int (fn [n] (rand-int n)),
   '+' +', '-' -', '*' *', 'inc' inc', 'dec' dec',
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
   'subs (fn ([s start] (subs s start)) ([s start end] (subs s start end))),
   'cat (fn [& args] (apply str args)),
   'read-string (fn [s] (first (parse/read-all (str s)))),
   're-find (fn [pattern s] (re-find (re-pattern pattern) s)),
   're-matches (fn [pattern s] (re-matches (re-pattern pattern) s)),
   're-seq (fn [pattern s] (vec (re-seq (re-pattern pattern) s))),
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
                (let [local-env (into *spell-env* (bind-params (:params f) all-args))
                      r (spell-eval (cons 'do (:body f)) local-env)]
                  (if (ok? r) (:ok r) (throw (ex-info (:err r) {:result r}))))
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
   ;; Prune rethinks and reopen as prefix string
   'prune-and-reopen (fn [quine-form]
                       (let [elements (vec (seq quine-form))
                             ;; [quine, name, arg1, ..., argN]
                             inert-args (subvec elements 2 (max 2 (dec (count elements))))
                             last-arg (last elements)
                             pruned-last (macros/prune-rethinks last-arg)
                             [_ do-form] (seq pruned-last)
                             body-forms (rest do-form)
                             env (or *spell-env* {})
                             ;; Re-materialize free vars in persisted defs before serializing
                             ;; back to source. This makes (def y x)-style persistence survive
                             ;; when an earlier sibling binding is pruned, while preserving
                             ;; source markers such as think/rethink.
                             expanded-body-forms
                             (map (fn [form]
                                    (if (and (seq? form)
                                             (= 'def (first form))
                                             (symbol? (second form))
                                             (>= (count form) 3))
                                      (list 'def (second form) (expand-expr (nth form 2) env))
                                      form))
                                  body-forms)]
                         (str "(quine completion "
                              (when (seq inert-args)
                                (str (str/join " " (map pr-str inert-args)) " "))
                              "(eval (do "
                              (str/join " " (map pr-str expanded-body-forms))
                              " "))),
   ;; Value store (for !call-now out-of-band large values)
   'stored stored,
   'serialize (fn
               ([value] (serialize-for-continuation value))
               ([value limit] (serialize-for-continuation value limit))),
   'deep-truncate (fn [value limit] (deep-truncate value (int limit))),
   ;; Eval — auto-expands free vars from caller's env, then evaluates in fresh env
   'spell-eval (fn [expr]
                 (let [r (spell-eval (expand-expr expr *spell-env*) {})]
                   (if (ok? r) (:ok r) (throw (ex-info (:err r) {:result r}))))),
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
   'bit-test bit-test,
   'bit-and-not bit-and-not,
   ;; Additional builtins (from verified clojure.core audit)
   'any? (fn [_] true),
   'boolean boolean,
   'boolean? boolean?,
   'dedupe (fn [coll] (vec (dedupe coll))),
   'distinct? (fn [& args] (apply distinct? args)),
   'drop-last (fn
                ([coll] (vec (drop-last coll)))
                ([n coll] (vec (drop-last n coll)))),
   'ffirst (fn [x] (first (first x))),
   'find find,
   'format (fn [fmt & args] (apply format fmt args)),
   'keep-indexed (fn [f coll] (vec (keep-indexed #(invoke-fn f [%1 %2]) coll))),
   'list* (fn [& args] (apply list* args)),
   'memoize (fn [f]
              (let [cache (atom {})]
                (fn [& args]
                  (if-let [e (find @cache args)]
                    (val e)
                    (let [ret (invoke-fn f args)]
                      (swap! cache assoc args ret)
                      ret))))),
   'namespace (fn [x] (namespace x)),
   'next (fn [coll] (next coll)),
   'not-every? (fn [pred coll] (not (every? #(invoke-fn pred [%]) coll))),
   'parse-boolean (fn [s] (case s "true" true "false" false nil)),
   'partition-by (fn [f coll] (vec (map vec (partition-by #(invoke-fn f [%]) coll)))),
   'rand-nth rand-nth,
   'random-sample (fn [prob coll] (vec (random-sample prob coll))),
   'random-uuid (fn [] (str (java.util.UUID/randomUUID))),
   'reduced reduced,
   'reductions (fn
                 ([f coll] (vec (reductions #(invoke-fn f [%1 %2]) coll)))
                 ([f init coll] (vec (reductions #(invoke-fn f [%1 %2]) init coll)))),
   'seq (fn [coll] (seq coll)),
   'shuffle (fn [coll] (vec (shuffle coll))),
   'split-with (fn [pred coll]
                 [(vec (take-while #(invoke-fn pred [%]) coll))
                  (vec (drop-while #(invoke-fn pred [%]) coll))]),
   'take-nth (fn [n coll] (vec (take-nth n coll))),
   'tree-seq (fn [branch? children root]
               (vec (tree-seq #(invoke-fn branch? [%])
                              #(invoke-fn children [%])
                              root))),
   'type (fn [x]
           (cond
             (nil? x) "nil"
             (string? x) "string"
             (number? x) "number"
             (boolean? x) "boolean"
             (keyword? x) "keyword"
             (symbol? x) "symbol"
             (vector? x) "vector"
             (map? x) (if (spell-fn? x) "function" "map")
             (set? x) "set"
             (seq? x) "list"
             (fn? x) "function"
             :else (str (clojure.core/type x)))),
   'update-keys (fn [m f] (into {} (map (fn [[k v]] [(invoke-fn f [k]) v]) m))),
   'update-vals (fn [m f] (into {} (map (fn [[k v]] [k (invoke-fn f [v])]) m))),
   ;; Gensym — generate unique symbols (for macro hygiene)
   'gensym (fn
             ([] (gensym))
             ([prefix] (gensym prefix))),
   ;; Throw — raise a catchable error (caught by try/catch and fn-application handler)
   'throw (fn [v]
            (throw (ex-info (if (string? v) v (pr-str v))
                            {:spell/thrown v}))),
   ;; future* — run a thunk in a new thread, return a future handle
   'future* (fn [thunk]
              (let [f (bound-fn []
                        (binding [*spell-env* (merge *spell-env*
                                                     *future-env*
                                                     {future-context-key true})]
                          (invoke-fn thunk [])))]
                {:spell/future true :ref (clojure.core/future (f))}))})

(def ^:dynamic *builtins*
  "Active builtins map. Rebound by each llm variant during evaluation.
   Default is core-builtins; effect builtins added by make-llm pipeline."
  core-builtins)

;; =============================================================================
;; Free variable analysis
;; =============================================================================

(def special-forms
  "Special forms that are not free variables."
  #{'quote 'def 'do 'if 'let 'fn 'fn* 'expand 'quine 'loop 'recur 'for 'try})

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
    (if (and (symbol? (first expr)) (get @macros/spell-macros (first expr)))
      ;; Clojure-side macro: expand first, then continue expanding the result
      (-expand-expr (macros/spell-macroexpand-1 expr) outer-env inner)
      ;; Check for user-defined macros in outer-env
      (let [head (first expr)
            head-val (when (and (symbol? head)
                                (not (contains? inner head))
                                (not (special-forms head)))
                       (get outer-env head))]
        (if (spell-macro? head-val)
          ;; User macro: invoke expander, then continue expanding result
          (let [expander (:expander head-val)
                macro-env (into outer-env (destructure-bind (:params expander) (vec (rest expr))))
                r (spell-eval (cons 'do (:body expander)) macro-env)]
            (if (ok? r)
              (-expand-expr (:ok r) outer-env inner)
              (throw (ex-info (str "Macro expansion failed during expand: " (:err r))
                              {:form expr}))))
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
                  (reduce (fn [[acc i] [pattern val-expr]]
                            [(conj acc pattern (first (-expand-expr val-expr outer-env i)))
                             (into i (param-symbols pattern))])
                          [[] inner] pairs)
                  expanded-body (map #(first (-expand-expr % outer-env final-inner)) (drop 2 expr))]
              [(list* 'let (vec expanded-bindings) expanded-body) inner])

        (fn fn*) (let [all-syms (set (mapcat param-symbols (second expr)))
                       body-inner (into inner all-syms)]
                   [(list* 'fn (second expr) (map #(first (-expand-expr % outer-env body-inner)) (drop 2 expr))) inner])



        quine (let [name-sym (second expr)
                    inner' (conj inner name-sym)
                    args (drop 2 expr)
                    expanded-args (map #(first (-expand-expr % outer-env inner')) args)]
                [(apply list 'quine name-sym expanded-args) inner'])

        loop (let [pairs (partition 2 (second expr))
                   [expanded-bindings final-inner]
                   (reduce (fn [[acc i] [pattern val-expr]]
                             [(conj acc pattern (first (-expand-expr val-expr outer-env i)))
                              (into i (param-symbols pattern))])
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
                                (reduce (fn [[lacc lbound] [pattern val-expr]]
                                          [(conj lacc pattern (first (-expand-expr val-expr outer-env lbound)))
                                           (into lbound (param-symbols pattern))])
                                        [[] bound] let-pairs)]
                            (recur (drop 2 remaining)
                                   (conj acc :let (vec let-expanded))
                                   let-bound))
                          ;; Normal binding: pattern coll
                          :else
                          (let [pattern item
                                coll-expr (second remaining)
                                [coll-expanded _] (-expand-expr coll-expr outer-env bound)]
                            (recur (drop 2 remaining)
                                   (conj acc pattern coll-expanded)
                                   (into bound (param-symbols pattern))))))))]
              [(list 'for (vec expanded-bindings) (first (-expand-expr body outer-env final-inner))) inner])

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

        ;; Default: recurse into all sub-expressions
        [(apply list (map expand1 expr)) inner])))))

    :else [expr inner]))

(defn expand-expr
  "Expand expr, substituting free variables from outer-env. Returns expanded expression."
  [expr outer-env]
  (first (-expand-expr expr outer-env #{})))

;; =============================================================================
;; Evaluator
;; =============================================================================

(defn- eval-seq
  "Evaluate a sequence of expressions, threading env.
   Returns result map with last value.
   On error, annotates result with :containing-form (the do-body expression that failed)."
  [exprs env]
  (if (empty? exprs)
    (ok nil env)
    (loop [remaining exprs
           result (ok nil env)]
      (if (empty? remaining)
        result
        (if (err? result)
          result
          (let [form (first remaining)
                r (spell-eval form (:env result))]
            (recur (rest remaining)
                   (if (err? r)
                     (assoc r :containing-form form)
                     r))))))))

(defn spell-eval
  "Evaluate expr in env. Returns result map:
   - Success: {:ok value :env env'}
   - Error: {:err msg :env env :expr failing-expr}"
  [expr env]
  (cond
    ;; Self-evaluating: nil, strings, numbers, booleans, keywords, regex patterns, sets
    (or (nil? expr) (string? expr) (number? expr) (boolean? expr) (keyword? expr)
        (instance? java.util.regex.Pattern expr) (set? expr))
    (ok expr env)

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
              root-result (spell-eval root-sym env)]
          (if (err? root-result)
            root-result
            (let [root-val (:ok root-result)
                  result (reduce #(get %1 (keyword %2)) root-val (rest parts))]
              (if (nil? result)
                (err (str namespace-lookup-prefix expr) env expr)
                (ok result (:env root-result))))))
        ;; Unqualified: lookup in env, fallback to *builtins*
        (if-let [entry (or (find env expr) (find (or *builtins* core-builtins) expr))]
          (ok (val entry) env)
          (err (str unbound-symbol-prefix expr) env expr))))

    ;; Vector: evaluate each element, threading env
    (vector? expr)
    (loop [remaining expr
           acc []
           e env]
      (if (empty? remaining)
        (ok acc e)
        (let [result (spell-eval (first remaining) e)]
          (if (err? result)
            result
            (recur (rest remaining)
                   (conj acc (:ok result))
                   (:env result))))))

    ;; Map: spell-fn maps are self-evaluating; otherwise evaluate values
    (map? expr)
    (if (spell-fn? expr)
      (ok expr env)
      (loop [remaining (seq expr)
             acc {}
             e env]
        (if (empty? remaining)
          (ok acc e)
          (let [[k v] (first remaining)
                result (spell-eval v e)]
            (if (err? result)
              result
              (recur (rest remaining)
                     (assoc acc k (:ok result))
                     (:env result)))))))

    ;; List: macros, special forms, or function application
    (seq? expr)
    ;; Check for macro expansion first
    (if (and (symbol? (first expr)) (get @macros/spell-macros (first expr)))
      (spell-eval (macros/spell-macroexpand-1 expr) env)
      ;; Check for user-defined macros in env
      (let [head (first expr)
            head-val (when (and (symbol? head) (not (special-forms head)))
                       (or (get env head) (get (or *builtins* core-builtins) head)))]
        (if (spell-macro? head-val)
          (let [expander (:expander head-val)
                macro-env (into env (destructure-bind (:params expander) (vec (rest expr))))
                r (spell-eval (cons 'do (:body expander)) macro-env)]
            (if (ok? r)
              (spell-eval (:ok r) env)
              (err (str "Macro expansion of " head " failed: " (:err r)) env expr)))
      (case (first expr)
      nil   (ok nil env)
      quote (ok (second expr) env)

      def   (let [sym (second expr)
                  val-result (spell-eval (nth expr 2) env)]
              (if (err? val-result)
                val-result
                (ok (:ok val-result)
                    (assoc (:env val-result) sym (:ok val-result)))))

      do    (eval-seq (rest expr) env)

      if    (let [test-result (spell-eval (second expr) env)]
              (if (err? test-result)
                test-result
                (spell-eval (nth expr (if (:ok test-result) 2 3) nil)
                            (:env test-result))))


      ;; let: (let [bindings...] body...) - local bindings (supports destructuring)
      let   (let [bindings (partition 2 (second expr))
                  body (drop 2 expr)]
              (loop [remaining bindings
                     local-env env]
                (if (empty? remaining)
                  (let [body-result (eval-seq body local-env)]
                    (if (err? body-result)
                      body-result
                      ;; Let bindings don't escape
                      (ok (:ok body-result) env)))
                  (let [[pattern val-expr] (first remaining)
                        val-result (spell-eval val-expr local-env)]
                    (if (err? val-result)
                      val-result
                      (recur (rest remaining)
                             (into local-env (destructure-bind pattern (:ok val-result)))))))))

      ;; fn/fn*: (fn [params...] body...) - dynamic scoping, returns source form
      ;; fn* is Clojure's internal form produced by #() reader macro
      (fn fn*)
            (ok {:spell/fn true :params (second expr) :body (drop 2 expr)} env)



      ;; expand: (expand expr) - single-pass walk mirroring spell-eval
      expand (let [quoted-result (spell-eval (second expr) env)]
               (if (err? quoted-result)
                 quoted-result
                 (ok (expand-expr (:ok quoted-result) (:env quoted-result))
                     (:env quoted-result))))

      ;; quine: (quine name body...) — bind name to the source form, eval last arg
      ;; Multi-arg: (quine name arg1 arg2) evaluates only arg2; earlier args are inert context.
      quine (let [name-sym (second expr)
                  body (last expr)
                  env' (assoc env name-sym expr)]
              (spell-eval body env'))

      ;; loop: (loop [bindings...] body...) - establishes a recursion point
      loop (let [bindings (partition 2 (second expr))
                 body (drop 2 expr)
                 binding-patterns (mapv first bindings)
                 init-exprs (mapv second bindings)]
             ;; Evaluate initial values
             (loop [remaining init-exprs
                    init-vals []
                    e env]
               (if (empty? remaining)
                 ;; All initial values evaluated, now run the loop body
                 (loop [current-vals init-vals]
                   (let [local-env (into e (mapcat destructure-bind binding-patterns current-vals))
                         body-result (eval-seq body local-env)]
                     (if (err? body-result)
                       body-result
                       (if (and (map? (:ok body-result)) (:spell/recur (:ok body-result)))
                         (recur (:vals (:ok body-result)))
                         ;; Normal return - restore outer env
                         (ok (:ok body-result) e)))))
                 ;; Still evaluating initial values
                 (let [result (spell-eval (first remaining) e)]
                   (if (err? result)
                     result
                     (recur (rest remaining)
                            (conj init-vals (:ok result))
                            (:env result)))))))

      ;; recur: (recur exprs...) - jump back to loop with new values
      recur (loop [remaining (rest expr)
                   vals []
                   e env]
              (if (empty? remaining)
                (ok {:spell/recur true :vals vals} e)
                (let [result (spell-eval (first remaining) e)]
                  (if (err? result)
                    result
                    (recur (rest remaining)
                           (conj vals (:ok result))
                           (:env result))))))

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
                (letfn [(eval-for [segments local-env]
                          (if (empty? segments)
                            ;; Evaluate body and return single-element result
                            (let [body-result (spell-eval body local-env)]
                              (if (err? body-result)
                                body-result
                                (ok [(:ok body-result)] (:env body-result))))
                            ;; Process next segment
                            (let [seg (first segments)
                                  rest-segs (rest segments)]
                              (case (:type seg)
                                :iter
                                (let [coll-result (spell-eval (:coll seg) local-env)]
                                  (if (err? coll-result)
                                    coll-result
                                    (loop [items (seq (:ok coll-result))
                                           acc []]
                                      (if (empty? items)
                                        (ok acc local-env)
                                        (let [item-env (into local-env (destructure-bind (:sym seg) (first items)))
                                              sub-result (eval-for rest-segs item-env)]
                                          (if (err? sub-result)
                                            sub-result
                                            (recur (rest items)
                                                   (into acc (:ok sub-result)))))))))
                                :when
                                (let [pred-result (spell-eval (:pred seg) local-env)]
                                  (if (err? pred-result)
                                    pred-result
                                    (if (:ok pred-result)
                                      (eval-for rest-segs local-env)
                                      (ok [] local-env))))
                                :let
                                (loop [let-bindings (:bindings seg)
                                       let-env local-env]
                                  (if (empty? let-bindings)
                                    (eval-for rest-segs let-env)
                                    (let [[pattern val-expr] (first let-bindings)
                                          val-result (spell-eval val-expr let-env)]
                                      (if (err? val-result)
                                        val-result
                                        (recur (rest let-bindings)
                                               (into let-env (destructure-bind pattern (:ok val-result))))))))))))]
                  (let [result (eval-for parsed env)]
                    (if (err? result)
                      result
                      ;; Return env unchanged (for bindings don't escape)
                      (ok (:ok result) env)))))))

      ;; try: (try body... (catch e handler...))
      try (let [forms (rest expr)
                catch-form (when (and (seq forms) (seq? (last forms))
                                      (= 'catch (first (last forms))))
                             (last forms))
                body-forms (if catch-form (butlast forms) forms)
                body-result (eval-seq body-forms env)]
            (if (and (err? body-result) catch-form)
              (let [catch-sym (second catch-form)
                    catch-body (drop 2 catch-form)
                    error-val (if (contains? body-result :thrown)
                                (:thrown body-result)
                                {:message (:err body-result) :expr (:expr body-result)})
                    catch-env (assoc (:env body-result) catch-sym error-val)
                    catch-result (eval-seq catch-body catch-env)]
                (if (err? catch-result)
                  catch-result
                  (ok (:ok catch-result)
                      (dissoc (:env catch-result) catch-sym))))
              body-result))



      ;; Function application: evaluate all, apply first to rest
      (loop [remaining expr
             vals []
             e env]
        (if (empty? remaining)
          (let [f (first vals)
                args (rest vals)]
            (if (spell-fn? f)
              ;; Spell fn: loop to support fn-level recur
              (loop [current-args args]
                (let [local-env (into e (bind-params (:params f) current-args))
                      body-result (eval-seq (:body f) local-env)]
                  (if (err? body-result)
                    (update body-result :trace (fnil conj []) (first expr))
                    (if (and (map? (:ok body-result)) (:spell/recur (:ok body-result)))
                      ;; recur: rebind params, re-enter function body
                      (recur (:vals (:ok body-result)))
                      ;; normal return
                      (ok (:ok body-result) e)))))
              ;; Call Clojure function - wrap in try/catch for error handling
              (try
                (ok (binding [*spell-env* e] (apply f args)) e)
                (catch Exception ex
                  (let [thrown (get (ex-data ex) :spell/thrown)
                        ex-type (get (ex-data ex) :type)]
                    (cond
                      ;; Typed exceptions — re-throw, not recoverable
                      ex-type (throw ex)
                      ;; Spell throw — preserve thrown value for try/catch
                      thrown {:err (ex-message ex) :thrown thrown :env e :expr expr}
                      ;; Other errors — wrap as eval error with Spell name + ex-data
                      :else (let [data (not-empty (dissoc (ex-data ex) :spell/thrown :result))
                                  msg (str fn-call-prefix (first expr) ": " (ex-message ex)
                                           (when data (str " " (pr-str data))))]
                              (err msg e expr))))))))
          (let [result (spell-eval (first remaining) e)]
            (if (err? result)
              result
              (recur (rest remaining)
                     (conj vals (:ok result))
                     (:env result)))))))))) ;; end case + if-spell-macro + let + if-clj-macro

    :else (err (str "Unknown expression type: " (type expr)) env expr)))

(defn run-spell
  "Run a spell program, returning just the value."
  [program]
  (let [result (spell-eval program {})]
    (if (ok? result)
      (:ok result)
      (throw (ex-info (:err result) {:result result})))))
