(ns spell.macros
  "Spell macro system: registry, expansion, and all macro definitions.

   Macros are code→code transformers registered in the spell-macros atom.
   The evaluator and internal env-closing expansion call spell-macroexpand-1.")

;; =============================================================================
;; Macro system
;; =============================================================================

(def spell-macros
  "Registry of Spell macros. Maps symbol to expansion function.
   Each function takes the arguments of the macro form (not including the macro name)
   and returns a new Spell form to evaluate."
  (atom {}))

(defn defspellmacro
  "Register a Spell macro. f takes the macro form's args and returns expanded code."
  [sym f]
  (swap! spell-macros assoc sym f))

(defn spell-macroexpand-1
  "If form is a Spell macro call, expand it once. Otherwise return form unchanged."
  [form]
  (if (and (seq? form) (symbol? (first form)))
    (if-let [macro-fn (get @spell-macros (first form))]
      (apply macro-fn (rest form))
      form)
    form))

;; =============================================================================
;; Macro definitions (following Clojure's core.clj)
;; =============================================================================

;; when: (when test body...) -> (if test (do body...))
(defspellmacro 'when
  (fn [test & body]
    (list 'if test (cons 'do body))))

;; defn: (defn name [params...] body...) -> (def name (fn [params...] body...))
(defspellmacro 'defn
  (fn [name params & body]
    (list 'def name (list* 'fn params body))))

;; and: short-circuit, returns last truthy or first falsy. (and) -> true
(defspellmacro 'and
  (fn [& args]
    (cond
      (empty? args) true
      (= 1 (count args)) (first args)
      :else (let [sym (gensym "and__")]
              (list 'let [sym (first args)]
                    (list 'if sym (cons 'and (rest args)) sym))))))

;; or: short-circuit, returns first truthy or last falsy. (or) -> nil
(defspellmacro 'or
  (fn [& args]
    (cond
      (empty? args) nil
      (= 1 (count args)) (first args)
      :else (let [sym (gensym "or__")]
              (list 'let [sym (first args)]
                    (list 'if sym sym (cons 'or (rest args))))))))

;; cond: (cond test1 expr1 test2 expr2 ...) -> nested if
(defspellmacro 'cond
  (fn [& clauses]
    (when (seq clauses)
      (list 'if (first clauses)
            (second clauses)
            (cons 'cond (nnext clauses))))))

;; if-let: (if-let [sym test] then else?) -> (let [temp test] (if temp (let [sym temp] then) else))
(defspellmacro 'if-let
  (fn
    ([bindings then] (list 'if-let bindings then nil))
    ([bindings then else]
     (let [sym (first bindings)
           tst (second bindings)
           temp (gensym "if-let__")]
       (list 'let [temp tst]
             (list 'if temp
                   (list 'let [sym temp] then)
                   else))))))

;; when-let: (when-let [sym test] body...) -> (let [temp test] (when temp (let [sym temp] body...)))
(defspellmacro 'when-let
  (fn [bindings & body]
    (let [sym (first bindings)
          tst (second bindings)
          temp (gensym "when-let__")]
      (list 'let [temp tst]
            (list 'when temp
                  (list* 'let [sym temp] body))))))

;; case: (case expr val1 result1 val2 result2 ... default?) -> nested cond + =
(defspellmacro 'case
  (fn [test-expr & clauses]
    (let [g (gensym "case__")
          pairs (partition 2 clauses)
          has-default? (odd? (count clauses))
          default-expr (if has-default?
                         (last clauses)
                         (list 'throw (list 'str "No matching clause: " g)))
          cond-clauses (mapcat (fn [[match-val result-expr]]
                                 [(list '= g match-val) result-expr])
                               pairs)]
      (list 'let [g test-expr]
            (list* 'cond (concat cond-clauses [:else default-expr]))))))

;; as->: (as-> expr name form1 form2 ...) -> nested let rebinding name
(defspellmacro 'as->
  (fn [expr name-sym & forms]
    (if (empty? forms)
      expr
      (list* 'let
             (vec (concat [name-sym expr]
                          (mapcat (fn [form] [name-sym form]) (butlast forms))))
             [(if (empty? forms) name-sym (last forms))]))))

;; cond->: (cond-> expr test1 form1 ...) -> chained let with (if test (-> g step) g)
(defspellmacro 'cond->
  (fn [expr & clauses]
    (let [g (gensym "cond->__")
          steps (map (fn [[test step]]
                       (list 'if test (list '-> g step) g))
                     (partition 2 clauses))]
      (if (empty? steps)
        (list 'let [g expr] g)
        (list* 'let
               (vec (concat [g expr]
                            (mapcat (fn [step] [g step]) (butlast steps))))
               [(last steps)])))))

;; cond->>: like cond-> but uses ->>
(defspellmacro 'cond->>
  (fn [expr & clauses]
    (let [g (gensym "cond->>__")
          steps (map (fn [[test step]]
                       (list 'if test (list '->> g step) g))
                     (partition 2 clauses))]
      (if (empty? steps)
        (list 'let [g expr] g)
        (list* 'let
               (vec (concat [g expr]
                            (mapcat (fn [step] [g step]) (butlast steps))))
               [(last steps)])))))

;; some->: (some-> expr form1 form2 ...) -> chained let with nil-checking
(defspellmacro 'some->
  (fn [expr & forms]
    (let [g (gensym "some->__")
          steps (map (fn [step]
                       (list 'if (list 'nil? g) nil (list '-> g step)))
                     forms)]
      (if (empty? steps)
        (list 'let [g expr] g)
        (list* 'let
               (vec (concat [g expr]
                            (mapcat (fn [step] [g step]) (butlast steps))))
               [(last steps)])))))

;; some->>: like some-> but uses ->>
(defspellmacro 'some->>
  (fn [expr & forms]
    (let [g (gensym "some->>__")
          steps (map (fn [step]
                       (list 'if (list 'nil? g) nil (list '->> g step)))
                     forms)]
      (if (empty? steps)
        (list 'let [g expr] g)
        (list* 'let
               (vec (concat [g expr]
                            (mapcat (fn [step] [g step]) (butlast steps))))
               [(last steps)])))))

;; !call-now: (!call-now name expr) or (!call-now name expr limit)
;;           (!call-now name1 expr1 name2 expr2 ...) — multi-binding
;; Sugar for evaluate-then-extend. No effect guard exception — respects double evaluation.
;; Optional limit controls inline threshold for serialize (default: call-now-inline-limit).
;; Negative limit means always inline (no out-of-band storage).
;; Multi-binding evaluates all exprs, then extends with all bindings in one turn.
(defn- serialized-form
  ([temp]
   (list 'read-string (list 'serialize temp)))
  ([temp limit]
   (list 'read-string (list 'serialize temp limit))))

(defn- call-now-expander
  "Shared expander for !call-now and !peek-now.
   extra-form-exprs are appended to the reopened quine."
  [macro-name args extra-form-exprs]
  (let [extra-form-exprs (or extra-form-exprs [])
        def-form-expr (fn
                        ([name-sym temp]
                         (list 'list (list 'quote 'def) (list 'quote name-sym)
                               (serialized-form temp)))
                        ([name-sym temp limit]
                         (list 'list (list 'quote 'def) (list 'quote name-sym)
                               (serialized-form temp limit))))]
    (cond
      ;; Single binding: (!call-now name expr)
      (= (count args) 2)
      (let [[name-sym val-expr] args
            temp (gensym "call-now__")]
        (list 'let [temp val-expr]
              (list '!llm-self
                    (list* 'reopen (list 'prune-and-reopen 'completion)
                           (concat [(def-form-expr name-sym temp)]
                                   extra-form-exprs)))))

      ;; Single binding with limit: (!call-now name expr limit)
      (= (count args) 3)
      (let [[name-sym val-expr limit] args
            temp (gensym "call-now__")]
        (list 'let [temp val-expr]
              (list '!llm-self
                    (list* 'reopen (list 'prune-and-reopen 'completion)
                           (concat [(def-form-expr name-sym temp limit)]
                                   extra-form-exprs)))))

      ;; Multi-binding: (!call-now name1 expr1 name2 expr2 ...)
      (and (even? (count args)) (>= (count args) 4))
      (let [pairs (partition 2 args)
            temps (map (fn [[name-sym _]] (gensym (str "call-now-" name-sym "__"))) pairs)
            let-bindings (vec (mapcat (fn [temp [_ val-expr]] [temp val-expr]) temps pairs))
            def-forms (map (fn [temp [name-sym _]]
                             (def-form-expr name-sym temp))
                           temps pairs)]
        (list 'let let-bindings
              (list '!llm-self
                    (list* 'reopen (list 'prune-and-reopen 'completion)
                           (concat def-forms extra-form-exprs)))))

      :else
      (throw (ex-info (str macro-name ": expected 2 args (name expr), 3 args (name expr limit), or even >= 4 args (name1 expr1 name2 expr2 ...)")
                      {:args-count (count args)})))))

(def ^:private peek-rethink-message
  "!peek-now binding disappears unless persisted.")

(defspellmacro '!call-now
  (fn [& args]
    (call-now-expander "!call-now" args nil)))

;; !peek-now: same as !call-now, but marks the binding as one-turn ephemeral.
;; The injected rethink prunes the peek binding on the following extension unless
;; the model persists the needed subset into a new def.
(defspellmacro '!peek-now
  (fn [& args]
    (call-now-expander "!peek-now" args
                       [(list 'list (list 'quote 'rethink) peek-rethink-message)])))

;; Short alias for !peek-now.
(defspellmacro '!peek
  (fn [& args]
    (call-now-expander "!peek" args
                       [(list 'list (list 'quote 'rethink) peek-rethink-message)])))

;; =============================================================================
;; Threading helpers (used by -> and ->> macros)
;; =============================================================================

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

;; future: (future expr) -> (future* (fn [] expr))
(defspellmacro 'future
  (fn [body]
    (list 'future* (list 'fn [] body))))

;; blocking/plet: (blocking/plet [a e1 b e2] body...)
;; -> launch futures, await all (future-only), bind, run body
(defspellmacro 'blocking/plet
  (fn [bindings & body]
    (let [pairs (partition 2 bindings)
          fut-syms (map (fn [[sym _]] (gensym (str sym "__fut__"))) pairs)
          ;; Build future bindings: [a__fut (future e1) b__fut (future e2)]
          fut-bindings (vec (mapcat (fn [fut-sym [_ expr]]
                                      [fut-sym (list 'future expr)])
                                    fut-syms pairs))
          ;; Build await bindings: [a (blocking/await a__fut) b (blocking/await b__fut)]
          await-bindings (vec (mapcat (fn [[sym _] fut-sym]
                                        [sym (list 'blocking/await fut-sym)])
                                      pairs fut-syms))]
      (list 'let fut-bindings
            (list* 'let await-bindings body)))))

;; !print: (!print expr...) — evaluate exprs, extend completion with bare serialized values.
;; Like (!call-now x x) but without creating a binding — values appear as
;; literals in the continuation so the LLM can see them.
(defn- print-expander
  [& val-exprs]
  (let [temps (mapv (fn [_] (gensym "print__")) val-exprs)
        bindings (vec (mapcat vector temps val-exprs))
        forms (map serialized-form temps)]
    (list 'let bindings
          (list '!llm-self
                (list* 'reopen (list 'prune-and-reopen 'completion) forms)))))

(defspellmacro '!print print-expander)
;; Backward-compatible alias.
(defspellmacro 'print print-expander)

;; line-offset: (line-offset n [data...]) -> quoted vector with :spell/line-offset metadata.
;; This keeps the annotation alive across pr-str/read-string round-trips.
(defspellmacro 'line-offset
  (fn [offset data-form]
    (let [vec-data (if (vector? data-form)
                     data-form
                     (throw (ex-info "line-offset expects a vector literal"
                                     {:form data-form})))]
      (list 'quote (with-meta vec-data (assoc (or (meta vec-data) {}) :spell/line-offset offset))))))

;; define: Scheme-style alias for def
(defspellmacro 'define
  (fn [name-sym val-expr]
    (list 'def name-sym val-expr)))

;; defmacro: (defmacro name [params] body...) — user-defined macro
;; Expands to (def name {:spell/macro true :expander (fn [params] body...)})
;; The fn receives unevaluated argument forms and returns a new form to evaluate.
(defspellmacro 'defmacro
  (fn [name-sym params & body]
    (list 'def name-sym {:spell/macro true :expander (list* 'fn params body)})))

;; describe: produces an extension with namespace docs
;;   (!describe ns)           — guide (or docs if no guide)
;;   (!describe ns :key)      — doc for specific item
;;   (!describe ns1 ns2 ...)  — multiple namespaces in one turn
;;   (!describe ns1 ns2 :key) — mixed: full ns1 guide + ns2 :key lookup
;; Expands to (!print ...) so the child LLM sees the docs as a literal.
(defspellmacro '!describe
  (fn [& args]
    (cond
      ;; (!describe ns)
      (= 1 (count args))
      (list '!print (list 'describe-fn (first args)))

      ;; (!describe ns :key) — keyword means key lookup
      (and (= 2 (count args)) (keyword? (second args)))
      (list '!print (list 'describe-fn (first args) (second args)))

      ;; (!describe ns1 ns2 ... ) — multi-namespace, with optional ns :key pairs
      :else
      (let [groups (loop [remaining (seq args), acc []]
                     (if-not remaining
                       acc
                       (let [sym (first remaining)
                             rst (next remaining)]
                         (if (and rst (keyword? (first rst)))
                           (recur (next rst) (conj acc [sym (first rst)]))
                           (recur rst (conj acc [sym]))))))
            parts (mapcat (fn [group]
                            (if (= 2 (count group))
                              [(str "## " (first group) " " (second group) "\n")
                               (list 'describe-fn (first group) (second group))
                               "\n\n"]
                              [(str "## " (first group) "\n")
                               (list 'describe-fn (first group))
                               "\n\n"]))
                          groups)]
        (list '!print (list* 'cat parts))))))

;; ->: (-> x (f a) (g b)) -> (g (f x a) b)
(defspellmacro '->
  (fn [x & forms]
    (thread-first x forms)))

;; ->>: (->> x (f a) (g b)) -> (g b (f a x))
(defspellmacro '->>
  (fn [x & forms]
    (thread-last x forms)))

;; =============================================================================
;; Think / Rethink / Extend — context pruning for unproductive thoughts
;; =============================================================================

(defn rethink-form?
  "Returns true if form is a (rethink ...) expression."
  [form]
  (and (seq? form) (= 'rethink (first form))))

(defn rethink-n
  "Return the number of previous siblings to prune. Default 1.
   (rethink \"reason\" body...) → 1
   (rethink 2 \"reason\" body...) → 2"
  [form]
  (if (number? (second form))
    (int (second form))
    1))

(defn rethink->think
  "Convert a rethink form to a think form, dropping the optional count.
   (rethink \"reason\" body...) → (think \"reason\" body...)
   (rethink 2 \"reason\" body...) → (think \"reason\" body...)"
  [form]
  (if (number? (second form))
    (list* 'think (drop 2 form))
    (list* 'think (rest form))))

(defn process-siblings
  "Reduce over sibling forms, pruning previous siblings on rethink."
  [forms]
  (reduce
    (fn [acc form]
      (if (rethink-form? form)
        (let [n (rethink-n form)]
          (conj (vec (drop-last n acc))
                (rethink->think form)))
        (conj acc form)))
    []
    forms))

;; think: (think label body...) → (do body... nil)
;; Evaluates body for side effects (bindings, computation), returns nil.
;; Preserved as a source marker for extend/prune-substitute.
(defspellmacro 'think
  (fn [_label & body]
    (if (seq body)
      (list* 'do (concat body [nil]))
      nil)))

;; rethink: (rethink [n] label body...) → (do body... nil)
;; At eval time, same as think. At source level, marks previous N siblings
;; for pruning by extend (default N=1).
(defspellmacro 'rethink
  (fn [& args]
    (let [body (cond
                 (number? (first args)) (drop 2 args)
                 :else (rest args))]
      (if (seq body)
        (list* 'do (concat body [nil]))
        nil))))

;; extend: (!extend completion) — prune rethinks and continue via !llm-self
(defspellmacro '!extend
  (fn
    ([] (list '!llm-self (list 'prune-and-reopen 'completion)))
    ([comp-sym] (list '!llm-self (list 'prune-and-reopen comp-sym)))))

;; compact: (!compact completion) — prune rethinks, append compaction instructions, continue via !llm-self
;; Prefix ends with '(!llm-self (wrap-cat — LLM writes quoted forms, balance-parens closes everything.
(def ^:private compact-suffix
  (str "(think \"=compact= Compact your context into the wrap-cat below. "
       "Each argument is a QUOTED form: '(def x 1) '(think \\\"label\\\" ...) etc. "
       "For large values: (list 'def 'x (deep-truncate x 500)). "
       "Preserve =compact:N= markers. Drop routine thinks; keep decisions/key defs. "
       "Just write the forms — closing parens and continuation are automatic.\" nil) "
       "'(!llm-self (wrap-cat "))

(defspellmacro '!compact
  (fn
    ([] (list '!compact 'completion))
    ([comp-sym]
     (list '!llm-self
       (list 'str
             (list 'serialize-prefix (list 'prune-and-reopen comp-sym))
             compact-suffix)))))
