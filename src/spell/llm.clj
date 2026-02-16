(ns spell.llm
  "LLM orchestration engine for Spell.

   Core loop: call LLM, concatenate prefix+response, parse, eval."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [spell.comm :as comm]
            [spell.eval :as eval]
            [spell.parse :as parse]
            [spell.prompt :as prompt]
            [spell.provider :as provider]
            [spell.recovery :as recovery]
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

;; Re-export from recovery (for core.clj)
(def format-error-for-recovery recovery/format-error-for-recovery)

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

(defn- make-inbox-fn
  "Create inbox function: [raw] -> value.
   Closes over eval-builtin from config. Box does balance-parens,
   so raw is already balanced when this is called.
   trace-data-atom, when non-nil, receives {:program} for tracing."
  [{:keys [variant-builtins eval-builtin recover-fns recovery-call-fn]} trace-data-atom]
  (fn [raw]
    (let [forms     (parse/read-all raw)
          program   (if (> (count (vec forms)) 1) (list* 'do forms) (first forms))
          indent    (apply str (repeat eval/*llm-depth* "  "))
          result    (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                             eval/*raw-text*       raw
                             eval/*builtins*       variant-builtins]
                      (eval/spell-eval program {'eval eval-builtin}))
          final-result
          (if (and (eval/err? result) recover-fns (not (:effect-phase result)))
            (let [_        (when eval/*verbose*
                             (println (str indent "=== Error Recovery ==="))
                             (println (str indent "Error: " (:err result))))
                  result-with-program (assoc result :program program)]
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
                                          eval/*raw-text*       nil
                                          eval/*builtins*       variant-builtins]
                                  (eval/spell-eval fix-expr (merge (:env current) {'eval eval-builtin})))]
                      (if (eval/err? retry)
                        (if (:effect-phase retry)
                          retry ;; effects may have run; stop recovery loop
                          (recur (assoc retry :program fix-expr) (rest fns)))
                        retry))
                    (recur current (rest fns))))))
            result)]
      (when trace-data-atom
        (reset! trace-data-atom {:program program}))
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
  (let [default-inbox (make-inbox-fn config (atom nil))
        initial-completion "(quine completion (eval (do)))"]
    (comm/start-box handle-name default-inbox initial-completion)))

(defn- -llm
  "Core llm engine: make API call, deliver to box.
   handle and parent-handle determine root behavior (handled by box).
   Does NOT handle registration or inbox seeding — caller does that."
  [{:keys [call-fn]} handle parent-handle prompt-str trace-data-atom]
  (when (and eval/*max-llm-depth* (>= eval/*llm-depth* eval/*max-llm-depth*))
    (throw (ex-info "LLM recursion limit exceeded"
                    {:type :depth-exceeded :depth eval/*llm-depth* :limit eval/*max-llm-depth*})))
  (let [indent         (apply str (repeat eval/*llm-depth* "  "))
        node-id        (when trace/*trace*
                         (trace/begin-node! trace/*trace-node-id*
                                            eval/*llm-depth* :default prompt-str))
        _              (when eval/*verbose*
                         (Thread/sleep (rand-int 500))
                         (locking *out*
                           (println (str indent "=== LLM Call (depth " eval/*llm-depth* ") ==="))
                           (println (str indent "Prompt: " (pr-str prompt-str)))))
        response-atom  (atom nil)
        completion     (promise)]
    (future
      (try
        (let [response (call-fn prompt-str)]
          (reset! response-atom response)
          (when eval/*verbose*
            (locking *out*
              (println (str indent "Response: " response))))
          (deliver completion (str prompt-str response)))
        (catch Exception e
          (deliver completion e))))
    (try
      (let [result (binding [trace/*trace-node-id* node-id]
                     (comm/box handle parent-handle completion))]
        (when node-id
          (trace/complete-node! node-id
            (merge {:response @response-atom
                    :raw-text (try @completion (catch Exception _ ""))
                    :value result}
                   @trace-data-atom)))
        result)
      (catch Exception e
        (when node-id
          (trace/complete-node! node-id
            (merge {:response (or @response-atom "")
                    :raw-text (try @completion (catch Exception _ ""))
                    :error e}
                   @trace-data-atom)))
        (throw e)))))

(defn make-eval
  "Create an eval builtin (inner/dangerous evaluator) from effect-builtins.
   Returns a function that merges variant-builtins with effect-builtins and evaluates.
   The eval builtin binds itself in eval/*builtins* to support recursive eval calls."
  [variant-builtins effect-builtins]
  (letfn [(eval-builtin [expr]
            (let [expanded (eval/expand-expr expr eval/*spell-env*)]
              (binding [eval/*builtins* (merge variant-builtins effect-builtins {'eval eval-builtin})]
                (let [result (eval/spell-eval expanded {})]
                  (if (eval/ok? result)
                    (:ok result)
                    (throw (ex-info (:err result) {:result result})))))))]
    eval-builtin))

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
   (f prompt) or (f prompt handle).

   The returned function is automatically available as 'llm-self in Spell code,
   providing self-recursion without needing to wire up var refs."
  [{:keys [namespaces model system llm-var recover format prefill? thinking]
    :or {namespaces {} model nil recover true prefill? true}}]
  (let [;; Split namespace builtins: io, globals, agents, futures are effect-only, rest are pure
        effect-ns-names #{'io 'globals 'agents 'futures}
        ns-builtins (into {} (map (fn [[sym ns-map]] [sym ns-map]) namespaces))
        pure-ns-builtins (into {} (remove #(effect-ns-names (key %)) ns-builtins))
        effect-ns-builtins (into {} (filter #(effect-ns-names (key %)) ns-builtins))
        variant-builtins (merge eval/core-builtins
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
                             (cond-> {:system recovery/recovery-system-prompt}
                               model (assoc :model model))))
        ;; Resolve recovery setting into a chain of strategies
        ns-recover (recovery/make-namespace-recover-fn namespaces)
        recover-fns (cond
                      (false? recover) nil
                      (fn? recover) [ns-recover recover]
                      :else [ns-recover recovery/default-recover-fn ns-recover])
        ;; Create a promise for the final config (to break circular dependency)
        final-config (promise)
        ;; Create llm-self that closes over api-config, gets eval dynamically
        self-ref (atom nil)
        self-fn (fn llm-self
                  ([prompt] (@self-ref prompt))
                  ([prompt handle]
                   ;; 2-arity only valid from spawn context
                   (when-not comm/*parent-handle*
                     (throw (ex-info "Explicit handle requires spawn context" {:handle handle})))
                   (@self-ref prompt handle)))
        ;; Create effect-builtins (closes over llm-self)
        effect-builtins (merge {'llm-self self-fn
                               'leaf-llm (make-leaf-llm {})}
                         effect-ns-builtins
                         (when llm-var {'llm llm-var}))
        ;; Add register-agent to agents namespace (if present)
        register-agent-fn (fn [handle-name] (register-agent @final-config handle-name))
        effect-builtins' (if (contains? effect-ns-builtins 'agents)
                           (assoc effect-builtins 'agents
                                  (assoc (get effect-ns-builtins 'agents)
                                         :register register-agent-fn))
                           effect-builtins)
        ;; Create eval builtin using make-eval
        eval-builtin (make-eval variant-builtins effect-builtins')
        ;; Config with variant-builtins and eval-builtin
        config'  {:call-fn call-fn
                  :variant-builtins variant-builtins
                  :eval-builtin eval-builtin
                  :recover-fns recover-fns
                  :recovery-call-fn recovery-call-fn}
        _        (deliver final-config config')
        wrap-nl  (fn [p]
                   (let [s (if (or (seq? p) (list? p)) (pr-str p) (str p))]
                     (if (.startsWith (.trim ^String s) "(")
                       (str p)
                       (str "(quine completion (eval (do "
                            "(quine prompt \"" (parse/escape-string s) "\") "))))
        the-llm  (fn the-llm
                   ([prompt] (the-llm prompt nil))
                   ([prompt handle]
                    (let [handle     (or handle comm/*current-handle* (keyword (gensym "agent-")))
                          parent     (cond
                                       comm/*current-handle* comm/*current-handle*  ;; llm-self (inherited)
                                       comm/*parent-handle*  comm/*parent-handle*   ;; spawn child
                                       :else                 nil)                   ;; top-level
                          root?      (not= parent handle)
                          prompt'    (if (or (seq? prompt) (list? prompt))
                                       (eval/expand-expr prompt (or eval/*spell-env* {}))
                                       prompt)
                          prompt-str (wrap-nl prompt')
                          trace-data (atom nil)
                          inbox-fn   (make-inbox-fn config' trace-data)
                          default-inbox (make-inbox-fn config' (atom nil))]
                      ;; Register if new handle
                      (when-not (comm/handle? handle)
                        (comm/register! handle default-inbox))
                      ;; Seed inbox: root resets, inherited CAS (preserve pending sends)
                      (if root?
                        (reset! (:inbox (get @comm/registry handle)) inbox-fn)
                        (compare-and-set! (:inbox (get @comm/registry handle)) nil inbox-fn))
                      (-llm config' handle parent prompt-str trace-data))))]
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
