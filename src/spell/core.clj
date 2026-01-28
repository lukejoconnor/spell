(ns spell.core
  "Spell - a Lisp dialect for LLM self-orchestration."
  (:require [spell.llm :as llm-provider]
            [spell.prompt :as prompt]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(declare llm prepend-hooks-to-llm recurse make-llm prefix-prompt)

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
;; Builtins: core (always available) + dynamic (per-llm-variant)
;; =============================================================================

(def ^:private core-builtins
  "Language primitives - always available in every llm variant."
  {'+ +, '- -, '* *, '/ /, '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=,
   'str str, 'pr-str pr-str, 'list list, 'vector vector, 'first first, 'rest rest,
   'cons cons, 'conj conj, 'get get, 'assoc assoc, 'not not, 'count count,
   'inc inc, 'dec dec, 'nil? nil?, 'empty? empty?, 'rand rand,
   'cat (fn [& args] (apply str args)),
   'spell-error? spell-error?})

(def ^:dynamic *builtins*
  "Active builtins map. Rebound by each llm variant during evaluation.
   Root binding set at bottom of file after all definitions exist."
  nil)

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

(declare spell-eval)

;; =============================================================================
;; Free variable analysis
;; =============================================================================

(def ^:private special-forms
  "Special forms that are not free variables."
  #{'quote 'def 'do 'if 'let 'fn 'defn 'cond 'and 'or 'uneval 'expand})

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

(defn- quote-value
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

      do (let [[forms _]
               (reduce (fn [[acc i] sub-expr]
                         (let [expanded (expand-expr sub-expr outer-env i)
                               new-inner (if (and (seq? sub-expr) (= 'def (first sub-expr)))
                                           (conj i (second sub-expr))
                                           i)]
                           [(conj acc expanded) new-inner]))
                       [[] inner]
                       (rest expr))]
           (list* 'do forms))

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

(defn read-all
  "Read all forms from a string. Returns a vector of parsed forms."
  [s]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [forms []]
      (let [form (try (read rdr) (catch Exception _ ::eof))]
        (if (= form ::eof)
          forms
          (recur (conj forms form)))))))

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

;; =============================================================================
;; Convenience hooks: with-env, with-env-hints
;; =============================================================================

(defn with-env
  "Create a hook that injects bindings into code.
   bindings is a map of keywords to values, e.g. {:secret 42 :name \"Alice\"}.
   Returns a function code->code that wraps code with def forms."
  [bindings]
  (when-not (every? keyword? (keys bindings))
    (throw (ex-info "with-env: keys must be keywords" {:keys (keys bindings)})))
  (let [defs (mapv (fn [[k v]]
                     (list 'def (symbol (name k)) (quote-value v)))
                   bindings)]
    (fn [code]
      (list* 'do (concat defs [code])))))

(defn prefix-prompt
  "Prepend documentation string to a prompt.
   If prompt is a string, prepends docs with separator.
   If prompt is a thunk (list), wraps with env-hints binding."
  [docs prompt]
  (cond
    (string? prompt)
    (str docs "\n\n" prompt)

    (or (seq? prompt) (list? prompt))
    (list 'do (list 'def 'env-hints docs) prompt)

    :else prompt))

(defn- inject-docs-into-llm-prompts
  "Walk code and wrap llm call prompts with prefix-prompt.
   (llm prompt) -> (llm (prefix-prompt docs prompt))
   Does not descend into quotes."
  [docs code]
  (cond
    (and (seq? code) (= 'quote (first code)))
    code

    (and (seq? code) (= 'llm (first code)))
    (let [prompt (second code)
          wrapped-prompt (list 'prefix-prompt docs (inject-docs-into-llm-prompts docs prompt))
          rest-args (drop 2 code)]
      (list* 'llm wrapped-prompt rest-args))

    (seq? code)
    (apply list (map #(inject-docs-into-llm-prompts docs %) code))

    (vector? code)
    (mapv #(inject-docs-into-llm-prompts docs %) code)

    (map? code)
    (into {} (map (fn [[k v]] [k (inject-docs-into-llm-prompts docs v)]) code))

    :else code))

(defn with-env-hints
  "Create a hook that injects bindings AND documents them in descendant prompts.
   bindings is a map of keywords to [value, doc-string] pairs.
   Example: {:api-key [\"sk-123\" \"API key for external service\"]}
   Returns a function code->code."
  [bindings]
  (when-not (every? keyword? (keys bindings))
    (throw (ex-info "with-env-hints: keys must be keywords" {:keys (keys bindings)})))
  (let [defs (mapv (fn [[k [v _]]]
                     (list 'def (symbol (name k)) (quote-value v)))
                   bindings)
        docs (str "Available bindings:\n"
                  (str/join "\n"
                    (map (fn [[k [_ doc]]]
                           (str "  " (name k) " - " doc))
                         bindings)))]
    (fn [code]
      (let [with-bindings (list* 'do (concat defs [code]))]
        (inject-docs-into-llm-prompts docs with-bindings)))))

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

;; =============================================================================
;; LLM implementation and factory
;; =============================================================================

(defn- make-call-now
  "Create a call-now closure for continuing an LLM generation with tool results.
   completion-str: the raw completion string from the current LLM call.
   hooks: hooks to apply to continuation code.
   sys-prompt: system prompt for continuation LLM calls.
   model-override: optional model name."
  [completion-str hooks sys-prompt model-override]
  (fn [bindings-map]
    (when-not (map? bindings-map)
      (throw (ex-info "call-now: argument must be a map" {:got bindings-map})))
    (let [indent (apply str (repeat *llm-depth* "  "))
          ;; Format bindings as def forms
          def-strs (map (fn [[k v]]
                          (str "(def " (name k) " " (pr-str (quote-value v)) ")"))
                        bindings-map)
          result-text (str/join " " def-strs)
          ;; Extend the completion prefix
          new-prefix (str completion-str "\n" result-text "\n")
          _ (when *verbose*
              (println (str indent "=== call-now ==="))
              (println (str indent "Bindings: " (pr-str (into {} (map (fn [[k v]] [(name k) v]) bindings-map)))))
              (println (str indent "Continuation prefix length: " (count new-prefix))))
          ;; Call LLM to continue
          call-opts (cond-> {:system sys-prompt}
                      model-override (assoc :model model-override))
          continuation (llm-provider/llm-call new-prefix call-opts)
          _ (when *verbose*
              (println (str indent "Continuation: " continuation)))
          ;; Parse continuation text (tool result defs + model's continuation)
          new-text (str result-text "\n" continuation)
          balanced (balance-parens new-text)
          forms (read-all balanced)
          ;; Create new call-now for recursive use
          extended-completion (str new-prefix continuation)
          new-call-now (make-call-now extended-completion hooks sys-prompt model-override)
          ;; Build program with completion as proper binding (not injected into env)
          ;; Prepend (def completion "...") so it's a real def like in initial call
          completion-def (list 'def 'completion extended-completion)
          all-forms (cons completion-def forms)
          program (list* 'do all-forms)
          program' (if (empty? hooks)
                     program
                     (apply-hooks hooks program))
          _ (when (and *verbose* (seq hooks))
              (println (str indent "Continuation program (after hooks): " (pr-str program'))))
          ;; Build env with call-now + tool result bindings (completion now via def, not injection)
          eval-env (reduce (fn [e [k v]]
                             (assoc e (symbol (name k)) v))
                           {'call-now new-call-now}
                           bindings-map)
          ;; Evaluate continuation
          [value _] (binding [*llm-depth* (inc *llm-depth*)]
                      (spell-eval program' eval-env))]
      value)))

(defn- llm-impl
  "Core llm implementation. Assumes *builtins* is already bound by the caller.
   sys-prompt: the system prompt string for this llm variant.
   model-override: optional model name (nil to use provider default)."
  [prompt hooks sys-prompt model-override]
  (when (and *max-llm-depth* (>= *llm-depth* *max-llm-depth*))
    (throw (ex-info "LLM recursion limit exceeded"
                    {:depth *llm-depth* :limit *max-llm-depth*})))
  (let [indent (apply str (repeat *llm-depth* "  "))
        is-thunk (or (seq? prompt) (list? prompt))
        prompt-str (if is-thunk (pr-str prompt) (str prompt))
        parent-code-binding (when is-thunk
                              (str "(def parent-code '" (pr-str prompt) ") "))
        ;; Structure: (def interior (do (def completion ...) (def prefix ...) (def response ...)))
        ;; completion is naturally bound via uneval, no special injection needed
        wrapped-prompt (str "(def interior (do "
                           "(def completion (cat \"(def interior \" (pr-str (uneval 'interior)) \")\")) "
                           "(def prefix \"" (escape-string prompt-str) "\") "
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
        (let [call-opts (cond-> {:system sys-prompt}
                          model-override (assoc :model model-override))
              response (llm-provider/llm-call wrapped-prompt call-opts)]
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
                               call-now-fn (make-call-now raw-completion hooks sys-prompt model-override)
                               ;; completion is now bound via (def interior ...) using uneval
                               ;; only call-now needs to be injected
                               initial-env {'call-now call-now-fn}
                               [value _] (binding [*llm-depth* (inc *llm-depth*)]
                                           (spell-eval program initial-env))]
                           {:success true :value value})
                         (catch Exception e
                           (when *verbose*
                             (println (str indent "Error: " (ex-message e))))
                           {:success false :response response :error e}))]
            (if (:success result)
              (:value result)
              (recur (inc attempt) (:response result) (:error result)))))))))

(defn make-llm
  "Factory: create an llm function with specific tools and agent access.

   Options:
   - :tools - vector of tool maps {:name sym, :fn f, :doc str}
   - :llms  - map of {symbol fn-or-var} for available agent functions.
              Use 'llm with a var ref for self-recursion: {'llm #'my-var}
              Values can also be maps with :fn and :doc for prompt generation.
   - :model - optional model name override (nil uses provider default)

   Returns a function with the same signature as llm:
   (f prompt) or (f prompt hooks)."
  [{:keys [tools llms model]
    :or {tools [] llms {}}}]
  (let [tool-builtins (into {} (map (fn [{:keys [name fn]}] [name fn]) tools))
        ;; Extract fns from llm entries (support both bare fns and {:fn f :doc d} maps)
        llm-builtins (into {} (map (fn [[sym v]]
                                     [sym (if (map? v) (:fn v) v)])
                                   llms))
        variant-builtins (merge core-builtins
                                {'prepend-hooks-to-llm #'prepend-hooks-to-llm
                                 'recurse #'recurse
                                 'prefix-prompt #'prefix-prompt
                                 'with-env with-env
                                 'with-env-hints with-env-hints}
                                tool-builtins
                                llm-builtins)
        ;; For prompt generation, normalize llm entries to include :doc
        llms-for-prompt (into {} (map (fn [[sym v]]
                                        [sym (if (map? v) v {:fn v})])
                                      llms))
        sys-prompt (prompt/generate-system-prompt tools llms-for-prompt)]
    (fn the-llm
      ([prompt] (the-llm prompt []))
      ([prompt hooks]
       (binding [*builtins* variant-builtins]
         (llm-impl prompt hooks sys-prompt model))))))

;; =============================================================================
;; Default llm function
;; =============================================================================

(def llm
  "The default llm function with all standard tools and self-recursion."
  (make-llm {:tools default-tools
             :llms  {'llm #'llm}}))

(defn run-spell
  "Run a spell program, returning just the value."
  [program]
  (first (spell-eval program {})))

;; Set root binding for *builtins* — used by direct spell-eval/run-spell calls
;; (tests, REPL) that don't go through an llm function.
(alter-var-root #'*builtins*
  (constantly (merge core-builtins
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
