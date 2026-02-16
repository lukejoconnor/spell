(ns spell.llm
  "LLM orchestration engine for Spell.

   Core loop: call LLM, concatenate prefix+response, parse, apply hooks, eval."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [spell.comm :as comm]
            [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.parse :as parse]
            [spell.prompt :as prompt]
            [spell.provider :as provider]
            [spell.trace :as trace]))

(declare make-leaf-llm)

;; ---------------------------------------------------------------------------
;; Describe builtin (defined here to avoid circular deps with core)
;; ---------------------------------------------------------------------------

(defn describe
  "Get documentation from a namespace.
   (describe ns) — guide if available, else docs map
   (describe ns :key) — doc for specific item"
  ([ns] (or (:guide ns) (:docs ns)))
  ([ns key] (or (get-in ns [:docs key])
                (get ns key))))

;; ---------------------------------------------------------------------------
;; Error Recovery
;; ---------------------------------------------------------------------------

(def ^:private recovery-system-prompt
  "You are fixing a Spell program error. Return ONLY the fixed Spell s-expression.
No explanation, no markdown code blocks, just the raw s-expression.")

(defn format-error-for-recovery
  "Format an error result for the recovery LLM.
   Shows the full program, failing expression, and error message."
  [{:keys [err expr program]}]
  (str "The following Spell program failed:\n\n"
       (pr-str program)
       "\n\nError at expression:\n"
       (pr-str expr)
       "\n\nError message: " err))

(defn- find-in-namespaces
  "Search all namespaces for a keyword matching sym.
   Returns a list of qualified symbols, e.g. (seqs/distinct)."
  [sym namespaces]
  (let [kw (keyword sym)]
    (for [[ns-sym ns-map] namespaces
          :when (map? ns-map)
          :when (contains? ns-map kw)]
      (symbol (str ns-sym "/" sym)))))

(defn- substitute-symbol
  "Recursively replace occurrences of old-sym with new-sym in expr."
  [expr old-sym new-sym]
  (cond
    (= expr old-sym) new-sym
    (seq? expr) (apply list (map #(substitute-symbol % old-sym new-sym) expr))
    (vector? expr) (mapv #(substitute-symbol % old-sym new-sym) expr)
    (map? expr) (into {} (map (fn [[k v]] [(substitute-symbol k old-sym new-sym)
                                            (substitute-symbol v old-sym new-sym)]) expr))
    :else expr))

(defn- make-namespace-recover-fn
  "Create a recovery fn that fixes unbound/misqualified symbols by searching namespaces.
   Returns nil if no unique match found (letting the next strategy try)."
  [namespaces]
  (fn [result _recovery-call-fn]
    (let [{:keys [err expr program]} result
          ;; Unwrap "Function call failed: " prefix from invoke-fn errors
          ;; so we can match the inner error pattern.
          inner-err (if (str/starts-with? err "Function call failed: ")
                      (subs err (count "Function call failed: "))
                      err)]
      (when-let [fix
                 (cond
                   ;; Case 1: "Unbound symbol: X" — bare symbol, search all namespaces
                   (str/starts-with? inner-err "Unbound symbol: ")
                   (let [sym (symbol (subs inner-err (count "Unbound symbol: ")))
                         matches (find-in-namespaces sym namespaces)]
                     (when (= 1 (count matches))
                       (let [qualified (first matches)]
                         (when eval/*verbose*
                           (println (str "  Namespace recovery: " sym " -> " qualified)))
                         (substitute-symbol program sym qualified))))

                   ;; Case 2: "Namespace lookup failed: ns/item" — wrong namespace
                   (str/starts-with? inner-err "Namespace lookup failed: ")
                   (let [qualified-str (subs inner-err (count "Namespace lookup failed: "))
                         parts (str/split qualified-str #"/")
                         item-sym (symbol (last parts))
                         bad-qualified (symbol qualified-str)
                         matches (find-in-namespaces item-sym namespaces)]
                     (when (= 1 (count matches))
                       (let [correct (first matches)]
                         (when eval/*verbose*
                           (println (str "  Namespace recovery: " bad-qualified " -> " correct)))
                         (substitute-symbol program bad-qualified correct)))))]
        ;; Return the fixed program for re-evaluation from scratch
        ;; (safe because spell-eval is pure).
        fix))))

;; ---------------------------------------------------------------------------
;; Prefix Echo Deduplication
;; ---------------------------------------------------------------------------

(defn- strip-code-fences
  "Remove markdown code fences from response if present.
   Handles ```clojure, ```lisp, ```scheme, or bare ```."
  [response]
  (let [trimmed (str/trim response)]
    (if (str/starts-with? trimmed "```")
      (let [;; Strip opening fence line
            after-fence (subs trimmed (inc (.indexOf trimmed "\n")))
            ;; Strip closing fence
            last-fence (.lastIndexOf after-fence "```")]
        (if (pos? last-fence)
          (str/trim (subs after-fence 0 last-fence))
          (str/trim after-fence)))
      response)))

(defn strip-prefix-echo
  "Strip prefix echo from a no-prefill model's response.
   Handles code fences, then checks for prefix echo.
   Tries exact match first, then trimmed prefix."
  [prompt-str response]
  (let [cleaned (strip-code-fences response)
        trimmed (str/triml cleaned)]
    (cond
      ;; Exact prefix match (including trailing whitespace)
      (str/starts-with? trimmed prompt-str)
      (subs trimmed (count prompt-str))
      ;; Trimmed prefix match (model may drop trailing whitespace)
      (let [prefix (str/trim prompt-str)]
        (str/starts-with? trimmed prefix))
      (subs trimmed (count (str/trim prompt-str)))
      ;; No echo — return cleaned (fences stripped)
      :else cleaned)))

;; ---------------------------------------------------------------------------
;; LLM Engine
;; ---------------------------------------------------------------------------

(defn- make-eval-pipeline
  "Create closure: raw-string -> value. Captures config and hooks.
   trace-data-atom, when non-nil, receives {:program :hooked} for tracing."
  [{:keys [builtins recover-fns recovery-call-fn]} hooks trace-data-atom]
  (fn [raw]
    (let [balanced  (parse/balance-parens raw)
          forms     (parse/read-all balanced)
          program   (if (> (count (vec forms)) 1) (list* 'do forms) (first forms))
          program'  (if (empty? hooks)
                      program
                      (hooks/apply-hooks hooks program))
          indent    (apply str (repeat eval/*llm-depth* "  "))
          _         (when (and eval/*verbose* (seq hooks))
                      (println (str indent "Program (after hooks): " (pr-str program'))))
          result    (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                             eval/*raw-text*       balanced]
                      (eval/spell-eval program' {}))
          final-result
          (if (and (eval/err? result) recover-fns (not (:effect-phase result)))
            (let [_        (when eval/*verbose*
                             (println (str indent "=== Error Recovery ==="))
                             (println (str indent "Error: " (:err result))))
                  result-with-program (assoc result :program program')]
              ;; Pipeline: try each recover-fn on the current error.
              ;; If a fix is found, eval it. If eval succeeds, done.
              ;; If eval fails, continue pipeline with the new error.
              ;; Recovery only triggers for first-pass (body) errors.
              ;; Effect-phase errors (from eval's second pass) skip recovery
              ;; to prevent double-execution of side effects.
              (loop [current result-with-program
                     fns     recover-fns]
                (if (empty? fns)
                  current
                  (if-let [fix-expr ((first fns) current recovery-call-fn)]
                    (let [_     (when eval/*verbose*
                                  (println (str indent "Recovery expression: " (pr-str fix-expr))))
                          retry (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                                          eval/*raw-text*       nil]
                                  (eval/spell-eval fix-expr (:env current)))]
                      (if (eval/err? retry)
                        (if (:effect-phase retry)
                          retry ;; effects may have run; stop recovery loop
                          (recur (assoc retry :program fix-expr) (rest fns)))
                        retry))
                    (recur current (rest fns))))))
            result)]
      (when trace-data-atom
        (reset! trace-data-atom {:program program :hooked (when (seq hooks) program')}))
      (if (eval/ok? final-result)
        (:ok final-result)
        (throw (ex-info (:err final-result) {:result final-result}))))))

(defn- register-agent
  "Register a dormant agent with a minimal sleeping completion.
   Returns handle. Agent wakes on first message.
   config is the llm config map (from make-llm)."
  [config handle-name]
  (when-not (keyword? handle-name)
    (throw (ex-info "register-agent: handle must be keyword" {:got handle-name})))
  (let [eval-fn (make-eval-pipeline config [] (atom nil))
        initial-completion "(quine completion (eval (do)))"]
    (comm/start-box handle-name eval-fn initial-completion)))

(defn- -llm
  "Core llm: call LLM, concat prefix+response, parse, apply hooks, eval.
   Two cases: root (handle not yet registered) or inherited (handle exists).
   Root owns the handle lifecycle: register, orphan-box, unregister.
   Inherited just seeds the inbox and calls box."
  [{:keys [call-fn builtins recover-fns recovery-call-fn] :as config} prompt hooks handle]
  (when (and eval/*max-llm-depth* (>= eval/*llm-depth* eval/*max-llm-depth*))
    (throw (ex-info "LLM recursion limit exceeded"
                    {:type :depth-exceeded :depth eval/*llm-depth* :limit eval/*max-llm-depth*})))
  (let [handle     (or handle
                       comm/*current-handle*
                       (keyword (gensym "agent-")))
        root?      (not (comm/handle? handle))
        indent     (apply str (repeat eval/*llm-depth* "  "))
        is-thunk   (or (seq? prompt) (list? prompt))
        prompt-str (if is-thunk (pr-str prompt) (str prompt))
        trace-data (atom nil)
        eval-fn    (make-eval-pipeline config hooks trace-data)
        _          (when root? (comm/register! handle eval-fn))
        _          (let [inbox (:inbox (get @comm/registry handle))]
                     (if root?
                       (reset! inbox eval-fn)
                       ;; Inherited call: only seed if empty. Preserves any
                       ;; function -send!'d during the current turn's eval.
                       (compare-and-set! inbox nil eval-fn)))
        _          (when comm/*spawn-ready*
                     (deliver comm/*spawn-ready* true))
        node-id    (when trace/*trace*
                     (trace/begin-node! trace/*trace-node-id*
                                        eval/*llm-depth* :default prompt-str))
        _          (when eval/*verbose*
                     (Thread/sleep (rand-int 500))
                     (locking *out*
                       (println (str indent "=== LLM Call (depth " eval/*llm-depth* ") ==="))
                       (println (str indent "Prompt: " (pr-str prompt)))))
        response   (call-fn prompt-str)
        _          (when eval/*verbose*
                     (locking *out*
                       (println (str indent "Response: " response))))
        raw        (parse/balance-parens (str prompt-str response))]
    (try
      (let [result (binding [eval/*builtins*       builtins
                             trace/*trace-node-id* node-id]
                     (comm/box raw handle))]
        (when root? (comm/notify-waiters! handle result))
        (when root? (comm/orphan-box! raw handle))
        (when node-id
          (trace/complete-node! node-id
            (merge {:response response :raw-text raw :value result}
                   @trace-data)))
        result)
      (catch Exception e
        (when root? (comm/notify-waiters! handle nil))
        (when root? (comm/orphan-box! raw handle))
        (when node-id
          (trace/complete-node! node-id
            (merge {:response response :raw-text raw :error e}
                   @trace-data)))
        (throw e))
      (finally
        (when root? (comm/unregister! handle))))))

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
   - :system     - optional system prompt string override (nil uses generated prompt)
   - :llm-var    - optional var ref to bind as 'llm for self-recursion (e.g., #'llm)
   - :recover    - error recovery setting (default: true = enabled).
                   - true: use default LLM-based recovery
                   - false: disable recovery (errors propagate immediately)
                   - fn: custom recovery function (result-map, call-fn) -> fixed-expr
   - :prefill?   - whether the provider supports assistant prefill (default: true).
                   When false, prefix is sent as user message only and prefix echo is stripped.
   - :thinking   - Anthropic adaptive thinking. When truthy, passed to provider opts.
                   Number = budget_tokens, true = default (10000).

   Returns a function with the same signature as llm:
   (f prompt) or (f prompt hooks).

   The returned function is automatically available as 'llm-self in Spell code,
   providing self-recursion without needing to wire up var refs."
  [{:keys [namespaces model system llm-var recover format prefill? thinking]
    :or {namespaces {} model nil recover true prefill? true}}]
  (let [self-ref (atom nil)
        self-fn (fn llm-self
                  ([prompt] (@self-ref prompt))
                  ([prompt hooks] (@self-ref prompt hooks))
                  ([prompt hooks handle]
                   (when-not comm/*spawn-ready*
                     (throw (ex-info "Explicit handle requires spawn context" {:handle handle})))
                   (@self-ref prompt hooks handle)))
        ;; Split namespace builtins: io, globals, agents, futures are effect-only, rest are pure
        effect-ns-names #{'io 'globals 'agents 'futures}
        ns-builtins (into {} (map (fn [[sym ns-map]] [sym ns-map]) namespaces))
        pure-ns-builtins (into {} (remove #(effect-ns-names (key %)) ns-builtins))
        effect-ns-builtins (into {} (filter #(effect-ns-names (key %)) ns-builtins))
        hook-builtins {'prepend-hooks-to-llm #'hooks/prepend-hooks-to-llm
                       'recurse #'hooks/recurse
                       'prefix-prompt #'hooks/prefix-prompt
                       'with-env hooks/with-env
                       'with-env-hints hooks/with-env-hints}
        effect-builtins (merge {'llm-self self-fn
                               'leaf-llm (make-leaf-llm {})}
                         effect-ns-builtins
                         (when llm-var {'llm llm-var}))
        variant-builtins (merge eval/core-builtins
                                hook-builtins
                                {'describe-fn describe}
                                pure-ns-builtins)
        sys-prompt (or system (prompt/generate-system-prompt namespaces format))
        prev-prompt-atom (atom nil)
        call-fn  (fn [prompt-str]
                   (let [prev-prompt @prev-prompt-atom
                         response (provider/llm-call prompt-str
                                    (cond-> {:system sys-prompt}
                                      prefill? (assoc :prefix prompt-str)
                                      model (assoc :model model)
                                      thinking (assoc :thinking thinking)
                                      prev-prompt (assoc :cache-prefix prev-prompt)))]
                     (reset! prev-prompt-atom prompt-str)
                     (if prefill?
                       response
                       (strip-prefix-echo prompt-str response))))
        ;; Recovery call fn: text in, text out, no prefix semantics
        recovery-call-fn (fn [prompt-str]
                           (provider/llm-call prompt-str
                             (cond-> {:system recovery-system-prompt}
                               model (assoc :model model))))
        ;; Resolve recovery setting into a chain of strategies
        ns-recover (make-namespace-recover-fn namespaces)
        recover-fns (cond
                      (false? recover) nil
                      (fn? recover) [ns-recover recover]
                      :else [ns-recover default-recover-fn ns-recover])
        ;; Create a promise for the final config (to break circular dependency)
        final-config (promise)
        ;; Add register-agent to agents namespace (if present)
        register-agent-fn (fn [handle-name] (register-agent @final-config handle-name))
        effect-builtins' (if (contains? effect-ns-builtins 'agents)
                           (assoc effect-builtins 'agents
                                  (assoc (get effect-ns-builtins 'agents)
                                         :register register-agent-fn))
                           effect-builtins)
        ;; Create eval builtin that merges effect-builtins
        eval-builtin (fn [expr]
                       (let [expanded (eval/expand-expr expr eval/*spell-env*)]
                         (binding [eval/*builtins* (merge variant-builtins effect-builtins')]
                           (let [result (eval/spell-eval expanded {})]
                             (if (eval/ok? result)
                               (:ok result)
                               (throw (ex-info (:err result) {:result result})))))))
        ;; Full builtins includes eval
        full-builtins (assoc variant-builtins 'eval eval-builtin)
        ;; Final config with full builtins
        config'  {:call-fn call-fn
                  :builtins full-builtins
                  :recover-fns recover-fns
                  :recovery-call-fn recovery-call-fn}
        _        (deliver final-config config')
        wrap-nl  (fn [p]
                   (let [s (if (or (seq? p) (list? p)) (pr-str p) (str p))]
                     (if (.startsWith (.trim ^String s) "(")
                       p
                       (str "(quine completion (eval (do "
                            "(quine prompt \"" (parse/escape-string s) "\") "))))
        the-llm  (fn the-llm
                   ([prompt] (the-llm prompt []))
                   ([prompt hooks] (the-llm prompt hooks nil))
                   ([prompt hooks handle]
                    (let [prompt' (if (or (seq? prompt) (list? prompt))
                                   (eval/expand-expr prompt (or eval/*spell-env* {}))
                                   prompt)]
                      (binding [eval/*builtins* full-builtins]
                        (-llm config' (wrap-nl prompt') hooks handle)))))]
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
                           (Thread/sleep (rand-int 500))
                           (locking *out*
                             (println (str indent "=== Form LLM Call (depth " eval/*llm-depth*
                                            ", attempt " attempt ") ==="))
                             (println (str indent "Prompt: " (pr-str current-prompt)))))
                response (provider/llm-call current-prompt
                           (cond-> {:system system}
                             model (assoc :model model)))
                _        (when eval/*verbose*
                           (locking *out*
                             (println (str indent "Response: " response))))]
            (if (eval/invoke-fn validate [response])
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
                      (Thread/sleep (rand-int 500))
                      (locking *out*
                        (println (str indent "=== Leaf LLM Call (depth " eval/*llm-depth* ") ==="))
                        (println (str indent "Prompt: " (pr-str prompt)))))
           response (provider/llm-call prompt-str
                      (cond-> {:system system}
                        model (assoc :model model)))
           _        (when eval/*verbose*
                      (locking *out*
                        (println (str indent "Response: " response))))
           _        (when node-id
                      (trace/complete-node! node-id
                        {:response response :raw-text response :value response}))]
       response))))

;; ---------------------------------------------------------------------------
;; Format Validation
;; ---------------------------------------------------------------------------

(defn- validate-format
  "Validate value against format spec. Returns {:valid true} or {:valid false :error msg}."
  [value {:keys [required optional]}]
  (cond
    (not (map? value))
    {:valid false :error (str "Expected map, got " (type value))}

    (not-empty (remove #(contains? value %) required))
    {:valid false :error (str "Missing required keys: "
                              (vec (remove #(contains? value %) required)))}

    :else
    {:valid true}))

(defn wrap-with-format
  "Wrap an LLM function with format validation and retry.

   For eval=false (leaf LLM): parses response as EDN, validates against format.
   For eval=true (Spell LLM): validates the evaluated result against format.

   Options:
   - :format      - format spec with :required and :optional keys
   - :eval?       - true if wrapped fn returns evaluated result (vs raw string)
   - :max-retries - max retry attempts (default 3)"
  [llm-fn {:keys [format eval? max-retries] :or {max-retries 3}}]
  (fn [prompt & args]
    (loop [attempt 1
           last-response nil
           last-error nil]
      (let [;; Add retry context to prompt if retrying
            prompt' (if last-error
                      (str prompt "\n\n[Previous attempt returned:\n"
                           (pr-str last-response)
                           "\n\nError: " last-error
                           "\n\nExpected format: map with keys " (:required format)
                           (when (:optional format)
                             (str " (optional: " (:optional format) ")"))
                           "\nPlease return valid EDN matching this format.]")
                      prompt)
            ;; Call the underlying LLM
            response (apply llm-fn prompt' args)
            ;; For eval?=false, parse response as EDN
            ;; For eval?=true, response is already the evaluated result
            value (if eval?
                    response
                    (try
                      (edn/read-string response)
                      (catch Exception e
                        {:__parse-error (.getMessage e)})))
            validation (if (:__parse-error value)
                         {:valid false :error (str "Failed to parse as EDN: " (:__parse-error value))}
                         (validate-format value format))]
        (if (:valid validation)
          value  ; Return the validated value (parsed EDN or Spell result)
          (if (>= attempt max-retries)
            (throw (ex-info "Format validation failed after max retries"
                            {:attempts attempt
                             :last-response response
                             :last-value value
                             :error (:error validation)}))
            (recur (inc attempt)
                   (if eval? value response)
                   (:error validation))))))))
