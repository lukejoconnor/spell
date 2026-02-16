(ns spell.hooks
  "Hook system for Spell: code transformers applied to LLM completions."
  (:require [spell.eval :as eval]
            [clojure.string :as str]))

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
                     (list 'def (symbol (name k)) (eval/quote-value v)))
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
                     (list 'def (symbol (name k)) (eval/quote-value v)))
                   bindings)
        docs (str "Available bindings:\n"
                  (str/join "\n"
                    (map (fn [[k [_ doc]]]
                           (str "  " (name k) " - " doc))
                         bindings)))]
    (fn [code]
      (let [with-bindings (list* 'do (concat defs [code]))]
        (inject-docs-into-llm-prompts docs with-bindings)))))

(defn apply-hooks
  "Apply hooks (quoted macros) to code, left-to-right.
   Each hook is either a quoted form that evaluates to a function code->code,
   or an already-evaluated Clojure function. Returns the transformed code."
  [hooks code]
  (reduce (fn [c hook]
            (cond
              ;; Clojure function (e.g. with-env, with-env-hints) - call directly
              (fn? hook) (hook c)
              ;; Spell function map - apply via spell-eval with quoted arg
              (eval/spell-fn? hook) (let [r (eval/spell-eval (list hook (list 'quote c)) {})]
                                      (if (eval/ok? r) (:ok r) (throw (ex-info (:err r) {:result r}))))
              ;; Quoted form - evaluate to get a function, then apply
              :else (let [r (eval/spell-eval hook {})
                          hook-fn (if (eval/ok? r) (:ok r) (throw (ex-info (:err r) {:result r})))]
                      (if (eval/spell-fn? hook-fn)
                        (let [r2 (eval/spell-eval (list hook-fn (list 'quote c)) {})]
                          (if (eval/ok? r2) (:ok r2) (throw (ex-info (:err r2) {:result r2}))))
                        (hook-fn c)))))
          code
          hooks))
