(ns spell.llm
  "LLM orchestration engine for Spell.

   Core loop: call LLM, concatenate prefix+response, parse, apply hooks, eval."
  (:require [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.parse :as parse]
            [spell.prompt :as prompt]
            [spell.provider :as provider]
            [spell.trace :as trace]))

;; ---------------------------------------------------------------------------
;; Describe builtin (defined here to avoid circular deps with core)
;; ---------------------------------------------------------------------------

(defn describe
  "Get documentation from a namespace.
   (describe ns) — all docs
   (describe ns :key) — doc for specific item"
  ([ns] (:docs ns))
  ([ns key] (get-in ns [:docs key])))

;; ---------------------------------------------------------------------------
;; Helper for Spell function invocation
;; ---------------------------------------------------------------------------

(defn- invoke-fn
  "Invoke f with args. Handles both spell-fns and Clojure fns.
   Uses *spell-env* for spell-fn body evaluation."
  [f args]
  (if (eval/spell-fn? f)
    (let [local-env (into eval/*spell-env* (map vector (:params f) args))]
      (first (eval/spell-eval (cons 'do (:body f)) local-env)))
    (apply f args)))

;; ---------------------------------------------------------------------------
;; Error Recovery
;; ---------------------------------------------------------------------------

(def ^:private recovery-system-prompt
  "You are fixing a Spell program error. Return ONLY the fixed Spell s-expression.
No explanation, no markdown code blocks, just the raw s-expression.
Use (memo N) to reference cached values from prior evaluation - this avoids
re-executing side effects like bash commands or file writes.")

(defn format-error-for-recovery
  "Format an error result for the recovery LLM.
   Shows the full program, failing expression, error message, and memo."
  [{:keys [err expr memo program]}]
  (str "The following Spell program failed:\n\n"
       (pr-str program)
       "\n\nError at expression:\n"
       (pr-str expr)
       "\n\nError message: " err
       "\n\nMemo (cached values - use (memo N) to reference):\n"
       (if (empty? memo)
         "(empty - no prior values cached)"
         (with-out-str
           (doseq [[i entry] (map-indexed vector memo)]
             (println (str i ": " (pr-str (:expr entry))
                           " => " (pr-str (:value entry)))))))))

;; ---------------------------------------------------------------------------
;; LLM Engine
;; ---------------------------------------------------------------------------

(defn- -llm
  "Core llm: call LLM, concat prefix+response, parse, apply hooks, eval.
   Uses memo-based evaluation for potential error recovery."
  [{:keys [call-fn builtins recover-fn recovery-call-fn]} prompt hooks]
  (when (and eval/*max-llm-depth* (>= eval/*llm-depth* eval/*max-llm-depth*))
    (throw (ex-info "LLM recursion limit exceeded"
                    {:depth eval/*llm-depth* :limit eval/*max-llm-depth*})))
  (let [indent    (apply str (repeat eval/*llm-depth* "  "))
        is-thunk  (or (seq? prompt) (list? prompt))
        prompt-str (if is-thunk (pr-str prompt) (str prompt))
        node-id   (when trace/*trace*
                    (trace/begin-node! trace/*trace-node-id*
                                       eval/*llm-depth* :default prompt-str))
        _         (when eval/*verbose*
                    (println (str indent "=== LLM Call (depth " eval/*llm-depth* ") ==="))
                    (println (str indent "Prompt: " (pr-str prompt))))
        response  (call-fn prompt-str)
        _         (when eval/*verbose*
                    (println (str indent "Response: " response)))
        raw       (str prompt-str response)
        balanced  (parse/balance-parens raw)
        forms     (parse/read-all balanced)
        program   (if (> (count (vec forms)) 1)
                    (list* 'do forms)
                    (first forms))
        program'  (if (empty? hooks)
                    program
                    (hooks/apply-hooks hooks program))
        _         (when (and eval/*verbose* (seq hooks))
                    (println (str indent "Program (after hooks): " (pr-str program'))))

        ;; Evaluate with memo tracking using 4-arg form
        result    (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                            trace/*trace-node-id* node-id]
                    (eval/spell-eval program' {} [] 0))

        ;; Handle error recovery if configured (and not already recovering)
        final-result
        (if (and (eval/err? result) recover-fn (not eval/*in-recovery*))
          (let [_        (when eval/*verbose*
                           (println (str indent "=== Error Recovery ==="))
                           (println (str indent "Error: " (:err result)))
                           (println (str indent "Memo entries: " (count (:memo result)))))
                ;; Add program to result for recovery context
                result-with-program (assoc result :program program')
                fix-expr (recover-fn result-with-program recovery-call-fn)
                _        (when eval/*verbose*
                           (println (str indent "Recovery expression: " (pr-str fix-expr))))
                ;; Run fix-expr with memo available for (memo N) lookups.
                ;; Start idx at end of memo so we don't auto-match (fix-expr has different structure).
                ;; Bind *in-recovery* to prevent recursive recovery attempts.
                retry    (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                                   eval/*in-recovery*    true
                                   trace/*trace-node-id* node-id]
                           (eval/spell-eval fix-expr (:env result) (:memo result) (count (:memo result))))]
            (when (and eval/*verbose* (eval/err? retry))
              (println (str indent "Recovery failed: " (:err retry))))
            retry)
          result)

        value (when (eval/ok? final-result) (:ok final-result))
        err   (when (eval/err? final-result)
                (ex-info (:err final-result) {:result final-result}))

        _  (when node-id
             (trace/complete-node! node-id
               {:response response
                :raw-text raw
                :program  program
                :hooked   (when (seq hooks) program')
                :value    value
                :error    err
                :memo     (:memo final-result)}))]
    (if err (throw err) value)))

(defn- default-recover-fn
  "Default recovery function: calls recovery LLM, parses response as s-expression."
  [result recovery-call-fn]
  (let [prompt (format-error-for-recovery result)
        response (recovery-call-fn prompt)]
    (first (parse/read-all response))))

(defn make-llm
  "Factory: create an llm function with namespaces.

   Options:
   - :namespaces - map of {symbol -> namespace-map}. Each namespace has :docs and items.
                   Namespaces are bound under their symbol in the builtins.
   - :model      - optional model name override (nil uses provider default)
   - :llm-var    - optional var ref to bind as 'llm for self-recursion (e.g., #'llm)
   - :recover    - error recovery setting (default: true = enabled).
                   - true: use default LLM-based recovery
                   - false: disable recovery (errors propagate immediately)
                   - fn: custom recovery function (result-map, call-fn) -> fixed-expr

   Returns a function with the same signature as llm:
   (f prompt) or (f prompt hooks).

   The returned function is automatically available as 'llm-self in Spell code,
   providing self-recursion without needing to wire up var refs."
  [{:keys [namespaces model llm-var recover]
    :or {namespaces {} model nil recover true}}]
  (let [self-ref (atom nil)
        self-fn (fn llm-self
                  ([prompt] (@self-ref prompt))
                  ([prompt hooks] (@self-ref prompt hooks)))
        ;; Build namespace builtins: each namespace bound under its symbol
        ns-builtins (into {} (map (fn [[sym ns-map]] [sym ns-map]) namespaces))
        hook-builtins {'prepend-hooks-to-llm #'hooks/prepend-hooks-to-llm
                       'recurse #'hooks/recurse
                       'prefix-prompt #'hooks/prefix-prompt
                       'with-env hooks/with-env
                       'with-env-hints hooks/with-env-hints}
        variant-builtins (merge eval/core-builtins
                                hook-builtins
                                {'llm-self self-fn
                                 'describe describe}
                                ns-builtins
                                (when llm-var {'llm llm-var}))
        sys-prompt (prompt/generate-system-prompt namespaces)
        call-fn  (fn [prompt-str]
                   (provider/llm-call prompt-str
                     (cond-> {:system sys-prompt :prefix prompt-str}
                       model (assoc :model model))))
        ;; Recovery call fn: text in, text out, no prefix semantics
        recovery-call-fn (fn [prompt-str]
                           (provider/llm-call prompt-str
                             (cond-> {:system recovery-system-prompt}
                               model (assoc :model model))))
        ;; Resolve recovery setting
        recover-fn (cond
                     (false? recover) nil
                     (fn? recover) recover
                     :else default-recover-fn)
        config   {:call-fn call-fn
                  :builtins variant-builtins
                  :recover-fn recover-fn
                  :recovery-call-fn recovery-call-fn}
        wrap-nl  (fn [p]
                   (let [s (if (or (seq? p) (list? p)) (pr-str p) (str p))]
                     (if (.startsWith (.trim ^String s) "(")
                       p
                       (str "(quine completion (spell-eval (do "
                            "(def prompt \"" (parse/escape-string s) "\") "))))
        the-llm  (fn the-llm
                   ([prompt] (the-llm prompt []))
                   ([prompt hooks]
                    (let [prompt' (if (or (seq? prompt) (list? prompt))
                                   (eval/expand-expr prompt (or eval/*spell-env* {}))
                                   prompt)]
                      (binding [eval/*builtins* variant-builtins]
                        (-llm config (wrap-nl prompt') hooks)))))]
    (reset! self-ref the-llm)
    the-llm))

;; ---------------------------------------------------------------------------
;; Leaf LLM
;; ---------------------------------------------------------------------------

(defn make-form-llm
  "Factory: create a validated text LLM function.
   Retries if output fails validation.

   Options:
   - :system      - system prompt string (default: generic assistant)
   - :model       - optional model name override
   - :validate    - validation function (string -> truthy/falsy), can be Spell fn or Clojure fn
   - :format-doc  - description of expected format (shown on retry)
   - :max-retries - max retry attempts (default: 3)

   Returns (fn [prompt] response-string).
   Throws if validation fails after max retries.

   For Spell functions, the source is shown in retry messages automatically."
  [{:keys [system model validate format-doc max-retries]
    :or {system "You are a helpful assistant."
         max-retries 3}}]
  (let [;; Format validator source for retry message (Spell fns show source)
        validate-src (when (eval/spell-fn? validate)
                       (pr-str (list* 'fn (:params validate) (:body validate))))]
    (fn [prompt]
      (let [prompt-str (str prompt)
            indent     (apply str (repeat eval/*llm-depth* "  "))]
        (loop [attempt 1
               last-response nil]
          (let [retry-suffix (when last-response
                               (str "\n\n[Your previous response:\n" last-response
                                    "\n\nThis did not match the expected format."
                                    (when format-doc (str " Expected: " format-doc))
                                    (when validate-src (str " Validator: " validate-src))
                                    " Please try again.]"))
                current-prompt (str prompt-str retry-suffix)
                node-id  (when trace/*trace*
                           (trace/begin-node! trace/*trace-node-id*
                                              eval/*llm-depth* :form current-prompt))
                _        (when eval/*verbose*
                           (println (str indent "=== Form LLM Call (depth " eval/*llm-depth*
                                          ", attempt " attempt ") ==="))
                           (println (str indent "Prompt: " (pr-str current-prompt))))
                response (provider/llm-call current-prompt
                           (cond-> {:system system}
                             model (assoc :model model)))
                _        (when eval/*verbose*
                           (println (str indent "Response: " response)))]
            (if (invoke-fn validate [response])
              (do
                (when node-id
                  (trace/complete-node! node-id
                    {:response response :raw-text response :value response}))
                response)
              (do
                (when node-id
                  (trace/complete-node! node-id
                    {:response response :raw-text response
                     :error (ex-info "Validation failed" {:attempt attempt})}))
                (if (>= attempt max-retries)
                  (throw (ex-info "Form LLM validation failed after max retries"
                                  {:attempts attempt :last-response response}))
                  (recur (inc attempt) response))))))))))

(defn make-leaf-llm
  "Factory: create a plain text-in/text-out LLM function.
   No Spell parsing, evaluation, tools, or sub-agents.

   Options:
   - :system - system prompt string (default: generic assistant)
   - :model  - optional model name override (nil uses provider default)

   Returns (fn [prompt] response-string)."
  ([] (make-leaf-llm {}))
  ([{:keys [system model]
     :or {system "You are a helpful assistant. Respond concisely."}}]
   (fn [prompt]
     (let [prompt-str (str prompt)
           node-id  (when trace/*trace*
                      (trace/begin-node! trace/*trace-node-id*
                                         eval/*llm-depth* :leaf prompt-str))
           indent   (apply str (repeat eval/*llm-depth* "  "))
           _        (when eval/*verbose*
                      (println (str indent "=== Leaf LLM Call (depth " eval/*llm-depth* ") ==="))
                      (println (str indent "Prompt: " (pr-str prompt))))
           response (provider/llm-call prompt-str
                      (cond-> {:system system}
                        model (assoc :model model)))
           _        (when eval/*verbose*
                      (println (str indent "Response: " response)))
           _        (when node-id
                      (trace/complete-node! node-id
                        {:response response :raw-text response :value response}))]
       response))))
