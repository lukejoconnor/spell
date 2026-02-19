(ns spell.llm
  "LLM orchestration engine for Spell.

   Core loop: call LLM, concatenate prefix+response, parse, eval."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [spell.comm :as comm]
            [spell.eval :as eval]
            [spell.parse :as parse]
            [spell.provider :as provider]
            [spell.recovery :as recovery]
            [spell.stdlib :as stdlib]
            [spell.trace :as trace]))

(declare make-leaf-llm)

;; ---------------------------------------------------------------------------
;; Describe builtin (defined here to avoid circular deps with core)
;; ---------------------------------------------------------------------------

(defn describe
  "Get documentation from a namespace.
   (describe ns) — guide if available, else docs map
   (describe ns :key) — detailed doc for specific item (checks :detail, then :docs)"
  ([ns] (or (:guide ns) (:docs ns)))
  ([ns key] (or (get-in ns [:detail key])
                (get-in ns [:docs key])
                (get ns key))))


;; ---------------------------------------------------------------------------
;; Core namespaces — always available, never need to be configured
;; ---------------------------------------------------------------------------

(def core-namespaces
  "Namespaces always merged into variant-builtins (available everywhere)."
  {'strings stdlib/strings
   'math stdlib/math
   'builtins stdlib/builtins-namespace})

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

(defn- try-quine-recovery
  "Attempt quine-extension recovery: append error info + extend to the quine.
   Returns eval result (ok or err). Throws on non-quine or retry limit."
  [program result variant-builtins eval-builtin]
  (let [max-recovery-args 2
        quine-arg-count (when (and (seq? program) (= 'quine (first program)))
                          (- (count (seq program)) 2))]
    (if (and quine-arg-count (< quine-arg-count (+ 1 max-recovery-args)))
      ;; Construct recovery quine: append new eval block with error info
      (let [error-map (cond-> {:error (recovery/clean-error-message (:err result))
                               :expr (list 'quote (:expr result))}
                        (:containing-form result)
                        (assoc :in (list 'quote (:containing-form result))))
            recovery-arg (list 'eval
                           (list 'do
                             (list 'def '_error error-map)
                             (list 'quote (list 'extend 'completion))))
            recovery-quine (apply list (concat (seq program) [recovery-arg]))
            indent (apply str (repeat eval/*llm-depth* "  "))
            _     (eval/vlog (str indent "Recovery quine: " (pr-str recovery-quine)))
            retry (binding [eval/*llm-depth* (inc eval/*llm-depth*)
                            eval/*raw-text*  nil
                            eval/*builtins*  variant-builtins]
                    (eval/spell-eval recovery-quine {'eval eval-builtin}))]
        (if (eval/ok? retry)
          retry
          (throw (ex-info (:err result) {:result result}))))
      ;; Not a quine or retry limit reached — propagate error
      (throw (ex-info (:err result) {:result result})))))

(defn make-inbox-fn
  "Create inbox function: [raw] -> value.
   Closes over eval-builtin from config. Calls balance-parens because
   send transforms can produce unbalanced strings (reopen strips parens).
   trace-data-atom, when non-nil, receives {:program} for tracing."
  [{:keys [variant-builtins eval-builtin recover-fn]} trace-data-atom]
  (fn [raw]
    (let [raw       (parse/balance-parens raw)
          forms     (parse/read-all raw)
          program   (if (> (count (vec forms)) 1) (list* 'do forms) (first forms))
          indent    (apply str (repeat eval/*llm-depth* "  "))
          result    (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                             eval/*raw-text*       raw
                             eval/*builtins*       variant-builtins]
                      (eval/spell-eval program {'eval eval-builtin}))
          final-result
          (if (and (eval/err? result) recover-fn (not (:effect-phase result)))
            (let [_        (do (eval/vlog (str indent "=== Error Recovery ==="))
                             (eval/vlog (str indent "Error: " (:err result))))
                  result-with-program (assoc result :program program)]
              ;; Try namespace recovery first (fast, deterministic)
              (if-let [fix-expr (recover-fn result-with-program)]
                (let [_     (eval/vlog (str indent "Namespace recovery: " (pr-str fix-expr)))
                      retry (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                                      eval/*raw-text*       nil
                                      eval/*builtins*       variant-builtins]
                              (eval/spell-eval fix-expr (merge (:env result) {'eval eval-builtin})))]
                  (if (eval/ok? retry)
                    retry
                    ;; Namespace fix didn't work, try quine-extension
                    (try-quine-recovery program result variant-builtins eval-builtin)))
                ;; No namespace fix, try quine-extension
                (try-quine-recovery program result variant-builtins eval-builtin)))
            result)]
      (when trace-data-atom
        (reset! trace-data-atom {:program program}))
      (if (eval/ok? final-result)
        (:ok final-result)
        (throw (ex-info (:err final-result) {:result final-result}))))))

(defn- register-agent
  "Register a dormant agent with stored completion as context.
   Returns handle. Agent wakes on first message (no initial LLM call).
   When woken, messages are appended to the stored completion."
  [config handle-name completion]
  (when-not (keyword? handle-name)
    (throw (ex-info "register-agent: handle must be keyword" {:got handle-name})))
  (when-not (string? completion)
    (throw (ex-info "register-agent: completion must be a string" {:got (type completion)})))
  (let [default-inbox (make-inbox-fn config (atom nil))]
    (comm/start-box handle-name default-inbox completion)))

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
                         (eval/vlog (str indent "=== LLM Call (depth " eval/*llm-depth* ") ==="))
                         (eval/vlog (str indent "Prompt: " (pr-str prompt-str))))
        response-atom  (atom nil)
        completion     (promise)]
    (future
      (try
        (let [response (call-fn prompt-str)]
          (reset! response-atom response)
          (eval/vlog (str indent "Response: " response))
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
            (let [caller-env eval/*spell-env*]
              (binding [eval/*builtins* (merge variant-builtins effect-builtins {'eval eval-builtin})]
                (let [result (eval/spell-eval expr caller-env)]
                  (if (eval/ok? result)
                    (:ok result)
                    (throw (ex-info (:err result) {:result result})))))))]
    eval-builtin))

;; ---------------------------------------------------------------------------
;; System prompt composition
;; ---------------------------------------------------------------------------

(defn- namespaces-section
  "Generate the NAMESPACES section from namespace metadata."
  [namespaces]
  (when (seq namespaces)
    (str "\nNAMESPACES\n\n"
         "Access functions with qualified symbols: namespace/item\n\n"
         (str/join "\n\n"
           (map (fn [[ns-sym ns-map]]
                  (str "## " ns-sym "\n"
                       (str/join "\n"
                         (map (fn [[k desc]]
                                (str "  " (name k) ": " desc))
                              (:docs ns-map)))))
                namespaces))
         "\n\n"
         "Usage:\n"
         "  (io/sh \"ls\")              — call function directly\n"
         "  '(describe io)              — namespace overview\n"
         "  '(describe io :sh)          — detailed doc for specific function\n"
         "  '(describe agents globals)  — multiple namespaces in one describe\n"
         "\n"
         "describe is an extension — it fires as the trailing expression for that turn.\n"
         "Use it before calling an unfamiliar namespace.\n")))

(defn- format-section
  "Generate RETURN VALUE section when a format spec is provided."
  [{:keys [required optional]}]
  (str "\nRETURN VALUE\n\n"
       "Your program's last expression must be a map with "
       (if (= 1 (count required))
         (str "key " (first required))
         (str "keys " (pr-str required)))
       ".\n"
       "Example: {:answer 42}\n"
       (when optional
         (str "Optional keys: " (pr-str optional) "\n"))))

(defn compose-system-prompt
  "Build a system prompt from a base prompt plus namespace docs and format.
   :base       — system prompt text (required; nil yields namespace docs only)
   :namespaces — map of effect namespace {symbol -> namespace-map}
   :format     — optional format spec {:required [...] :optional [...]}"
  [{:keys [base namespaces format]}]
  (str (or base "")
       (namespaces-section namespaces)
       (when format (format-section format))))

(defn generate-system-prompt
  "Build a system prompt from namespaces (convenience wrapper).
   namespaces: map of {symbol -> namespace-map}
   format: optional format spec"
  ([namespaces] (generate-system-prompt namespaces nil))
  ([namespaces format]
   (compose-system-prompt {:namespaces namespaces :format format})))

(defn make-llm
  "Factory: create an llm function with namespaces.

   Options:
   - :namespaces       - map of {symbol -> namespace-map}. Each namespace has :docs and items.
                         Namespaces are bound under their symbol in the builtins.
   - :model            - optional model name override (nil uses provider default)
   - :system           - optional system prompt string override (nil uses generated prompt)
   - :llm-var          - optional var ref to bind as 'llm for self-recursion (e.g., #'llm)
   - :recover          - error recovery setting (default: true = enabled).
                         - true: namespace recovery + quine-extension recovery
                         - false: disable recovery (errors propagate immediately)
                         - fn: custom namespace recovery function (result-map) -> fixed-expr
   - :prefill?         - whether the provider supports assistant prefill (default: true).
                         When false, prefix is sent as user message only and prefix echo is stripped.
   - :thinking         - Anthropic adaptive thinking. When truthy, passed to provider opts.
                         Number = budget_tokens, true = default (10000).
   - :reasoning-effort - OpenAI reasoning effort (\"low\", \"medium\", \"high\").
   - :verbosity        - OpenAI verbosity (\"low\", \"auto\").

   Returns a function with the same signature as llm:
   (f prompt) or (f prompt handle).

   The returned function is automatically available as 'llm-self in Spell code,
   providing self-recursion without needing to wire up var refs."
  [{:keys [namespaces model system llm-var recover format prefill? thinking reasoning-effort verbosity]
    :or {namespaces {} model nil recover true prefill? true}}]
  (let [;; Core namespaces are always available; everything in :namespaces is effect
        core-ns-names (set (keys core-namespaces))
        ns-builtins (into {} (map (fn [[sym ns-map]] [sym ns-map]) namespaces))
        effect-ns-builtins (into {} (remove #(core-ns-names (key %)) ns-builtins))
        variant-builtins (merge eval/core-builtins
                                {'describe-fn describe}
                                core-namespaces)
        sys-prompt (compose-system-prompt
                     {:base system
                      :namespaces effect-ns-builtins
                      :format format})
        prev-prompt-atom (atom nil)
        call-fn  (fn [prompt-str]
                   (let [prev-prompt @prev-prompt-atom
                         response (provider/llm-call prompt-str
                                    (cond-> {:system sys-prompt}
                                      prefill? (assoc :prefix prompt-str)
                                      model (assoc :model model)
                                      thinking (assoc :thinking thinking)
                                      reasoning-effort (assoc :reasoning-effort reasoning-effort)
                                      verbosity (assoc :verbosity verbosity)
                                      prev-prompt (assoc :cache-prefix prev-prompt)))]
                     (reset! prev-prompt-atom prompt-str)
                     (if prefill?
                       response
                       (strip-prefix-echo prompt-str response))))
        ;; Resolve recovery: namespace recovery fn (quine-extension is separate)
        ns-recover (recovery/make-namespace-recover-fn (merge core-namespaces ns-builtins))
        recover-fn (cond
                     (false? recover) nil
                     (fn? recover) recover
                     :else ns-recover)
        ;; Create a promise for the final config (to break circular dependency)
        final-config (promise)
        ;; Create llm-self that closes over api-config, gets eval dynamically
        self-ref (atom nil)
        self-fn (fn llm-self
                  ([prompt] (@self-ref prompt))
                  ([prompt handle]
                   ;; 2-arity only valid from spawn context (handle pre-registered)
                   (when-not (comm/handle? handle)
                     (throw (ex-info "Explicit handle requires spawn context" {:handle handle})))
                   (@self-ref prompt handle)))
        ;; Create effect-builtins (closes over llm-self)
        effect-builtins (merge {'llm-self self-fn
                               'leaf-llm (make-leaf-llm (cond-> {} model (assoc :model model)))}
                         effect-ns-builtins
                         (when llm-var {'llm llm-var}))
        ;; Add register-agent to agents namespace (if present)
        register-agent-fn (fn [handle-name completion] (register-agent @final-config handle-name completion))
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
                  :recover-fn recover-fn}
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
                    (let [handle     (or handle comm/*current-handle* :root)
                          parent     (or comm/*current-handle*  ;; llm-self (inherited)
                                       (:parent-handle (get @comm/registry handle))  ;; spawn child
                                       )
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
                      ;; Update default-inbox-fn so lazy send resolution uses
                      ;; the eval pipeline (not make-sleep-fn from spawn)
                      (swap! comm/registry assoc-in [handle :default-inbox-fn] default-inbox)
                      ;; Seed inbox: top-level (no parent) resets since handle
                      ;; may be reused; spawn uses CAS to preserve pending sends
                      (if parent
                        (compare-and-set! (:inbox (get @comm/registry handle)) nil inbox-fn)
                        (reset! (:inbox (get @comm/registry handle)) inbox-fn))
                      (-llm config' handle parent prompt-str trace-data))))]
    (reset! self-ref the-llm)
    the-llm))

;; ---------------------------------------------------------------------------
;; Leaf LLM
;; ---------------------------------------------------------------------------

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
                      (eval/vlog (str indent "=== Leaf LLM Call (depth " eval/*llm-depth* ") ==="))
                      (eval/vlog (str indent "Prompt: " (pr-str prompt))))
           response (provider/llm-call prompt-str
                      (cond-> {:system system}
                        model (assoc :model model)))
           _        (eval/vlog (str indent "Response: " response))
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
