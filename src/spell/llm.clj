(ns spell.llm
  "LLM orchestration engine for Spell.

   Core loop: call LLM, concatenate prefix+response, parse, eval."
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.grammar :as grammar]
            [spell.inbox :as inbox]
            [spell.parse :as parse]
            [spell.prompt :as prompt]
            [spell.provider :as provider]
            [spell.recovery :as recovery]
            [spell.runtime :as runtime]
            [spell.stdlib :as stdlib]
            [spell.trace :as trace]))

(declare make-leaf-llm build-init)

;; ---------------------------------------------------------------------------
;; Core namespaces — always available, never need to be configured
;; ---------------------------------------------------------------------------

(def core-namespaces
  "Namespaces always merged into variant-builtins (available everywhere)."
  {'strings stdlib/strings
   'math stdlib/math
   'builtins stdlib/builtins-namespace
   'reminders stdlib/reminders-namespace})

(def ^:private max-recovery-attempts
  "Maximum number of recovery retries before failing."
  2)

(def ^:dynamic *recovery-depth*
  "Current depth of nested recovery retries across reader and eval recovery."
  0)

(defn- throw-if-recovery-exhausted!
  "Throw when the shared recovery budget is exhausted."
  [phase error-msg]
  (when (>= *recovery-depth* max-recovery-attempts)
    (throw (ex-info (str "Recovery limit exceeded: " max-recovery-attempts
                         " while handling " (name phase) " error")
                    (cond-> {:type :recovery-exhausted
                             :phase phase
                             :attempts *recovery-depth*
                             :limit max-recovery-attempts}
                      error-msg
                      (assoc (if (= phase :reader) :parse-error :error) error-msg))))))

(defn- recovery-prompt-text
  "Instruction shown to the model during recovery attempts."
  [error-msg]
  (str "The previous Spell program threw an error. "
       "Please recover from this error by writing a new program that fulfills "
       "the intent of the previous program while avoiding the error. "
       "The previous program is inert text; it will not be reevaluated. "
       "Reminder: Emit Spell code only, not prose. "
       "Error message: " error-msg))

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
  "Attempt quine-extension recovery: append error info to the quine and reopen it.
   Returns eval result (ok or err). Throws on non-quine or shared recovery limit.
   If program doesn't start with (quine completion ...), wraps it first and recurses."
  [program result variant-builtins eval-builtin gated-ns-hints]
  (if-not (and (seq? program)
               (= 'quine (first program))
               (= 'completion (second program)))
    ;; Not a (quine completion ...) form — wrap and recurse
    (let [indent (apply str (repeat eval/*llm-depth* "  "))
          wrapped (list 'quine 'completion program)]
      (eval/vlog (str indent "Wrapping in quine completion for recovery"))
      (try-quine-recovery wrapped result variant-builtins eval-builtin gated-ns-hints))
    ;; Normal path: program is already (quine completion ...)
    (let [error-map (cond-> {:error (recovery/clean-error-message (:err result))}
                      (:containing-form result)
                      (assoc :in (list 'quote (:containing-form result)))
                      (:trace result)
                      (assoc :trace (:trace result)))
          indent (apply str (repeat eval/*llm-depth* "  "))
          _     (throw-if-recovery-exhausted! :eval (:error error-map))
          _     (eval/vlog (str indent "Recovery attempt: "
                                (inc *recovery-depth*) "/" max-recovery-attempts))
          recovery-prompt (recovery-prompt-text (:error error-map))
          rethink-arg '(rethink "Error recovery - see _error for details.")
          recovery-arg (list 'eval
                         (list 'do
                           (list 'def '_recovery_prompt recovery-prompt)
                           (list 'def '_error error-map)
                           (list 'quote (list '!llm-self (list 'reopen 'completion)))))
          recovery-quine (apply list (concat (seq program) [rethink-arg recovery-arg]))
          _     (eval/vlog (str indent "Recovery quine: " (pr-str recovery-quine)))
          retry (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                          eval/*raw-text*       nil
                          eval/*builtins*       variant-builtins
                          eval/*gated-ns-hints* gated-ns-hints
                          *recovery-depth*      (inc *recovery-depth*)]
                  (eval/spell-eval recovery-quine {'eval eval-builtin}))]
      (if (eval/ok? retry)
        retry
        (throw (ex-info (:err result) {:result result}))))))

(defn- try-reader-recovery
  "Attempt recovery from a reader/parse error by embedding the raw text
   as a string in a fresh recovery quine. The LLM sees its broken output
   and the error message, then gets a fresh chance via !extend.
   Shares the same retry budget as eval recovery."
  [raw parse-error inbox-macros variant-builtins eval-builtin gated-ns-hints]
  (let [error-msg (or (.getMessage parse-error) "Unknown reader error")
        indent    (apply str (repeat eval/*llm-depth* "  "))
        _         (throw-if-recovery-exhausted! :reader error-msg)
        _         (eval/vlog (str indent "=== Reader Error Recovery ==="))
        _         (eval/vlog (str indent "Recovery attempt: "
                                  (inc *recovery-depth*) "/" max-recovery-attempts))
        _         (eval/vlog (str indent "Parse error: " error-msg))
        recovery-prompt (recovery-prompt-text (str "Reader error: " error-msg))
        error-map {:error (str "Reader error: " error-msg) :raw raw}
        recovery-quine (list 'quine 'completion
                         (list 'eval
                           (list 'do
                             (list 'def '_recovery_prompt recovery-prompt)
                             (list 'def '_previous_program raw)
                             (list 'def '_error error-map)
                             (list 'quote (list '!extend 'completion)))))
        recovery-program (inbox/apply-inbox-macros recovery-quine inbox-macros
                                                   {:env {'eval eval-builtin}})
        result    (binding [eval/*llm-depth*           (inc eval/*llm-depth*)
                            eval/*raw-text*            nil
                            eval/*builtins*            variant-builtins
                            eval/*gated-ns-hints*      gated-ns-hints
                            *recovery-depth*           (inc *recovery-depth*)]
                    (eval/spell-eval recovery-program {'eval eval-builtin}))]
    (if (eval/ok? result)
      (:ok result)
      (throw (ex-info (or (:err result)
                          (str "Reader error (unrecoverable): " error-msg))
                      {:parse-error error-msg :result result})))))

(defn make-inbox-fn
  "Create inbox function: [raw] -> value or [raw inbox-macros] -> value.
   Closes over eval-builtin from config. Calls balance-parens because
   completions may arrive with mismatched trailing parens.
   trace-data-atom, when non-nil, receives {:program} for tracing."
  [{:keys [variant-builtins eval-builtin recover-fn allow-multiple-top-level? gated-ns-hints]} trace-data-atom]
  (let [f
        (fn
          ([raw]
           ((make-inbox-fn {:variant-builtins variant-builtins
                            :eval-builtin eval-builtin
                            :recover-fn recover-fn
                            :allow-multiple-top-level? allow-multiple-top-level?
                            :gated-ns-hints gated-ns-hints}
                           trace-data-atom)
            raw
            []))
          ([raw inbox-macros]
           (binding [eval/*gated-ns-hints* (or gated-ns-hints {})]
             (let [raw (parse/balance-parens raw)
                   [program parse-err]
                   (if allow-multiple-top-level?
                     (try
                       (let [forms (vec (parse/read-all raw))
                             cnt   (count forms)]
                         [(if (> cnt 1) (list* 'do forms) (first forms)) nil])
                       (catch Exception e [nil e]))
                     (try
                       [(parse/read-first raw) nil]
                       (catch Exception e [nil e])))]
               (if parse-err
                 (if recover-fn
                   (try-reader-recovery raw parse-err inbox-macros variant-builtins
                                        eval-builtin eval/*gated-ns-hints*)
                   (throw parse-err))
                 (let [continuation-raw (if allow-multiple-top-level?
                                          (if (seq inbox-macros)
                                            (inbox/materialize-inbox-raw raw inbox-macros
                                                                         {:env {'eval eval-builtin}})
                                            raw)
                                          raw)
                       program' (if allow-multiple-top-level?
                                  (let [forms (vec (parse/read-all continuation-raw))
                                        cnt   (count forms)]
                                    (if (> cnt 1) (list* 'do forms) (first forms)))
                                  (inbox/apply-inbox-macros program inbox-macros
                                                            {:env {'eval eval-builtin}}))
                       continuation-raw (if allow-multiple-top-level?
                                          continuation-raw
                                          (if (some? program') (pr-str program') raw))
                       indent (apply str (repeat eval/*llm-depth* "  "))
                       _ (when-let [handle runtime/*current-handle*]
                           (when-let [last-raw (:last-raw (get @runtime/registry handle))]
                             (reset! last-raw continuation-raw)))
                       result (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                                        eval/*raw-text*       continuation-raw
                                        eval/*builtins*       variant-builtins
                                        runtime/*current-raw* continuation-raw]
                                (eval/spell-eval program' {'eval eval-builtin}))
                       final-result
                       (if (and (eval/err? result) recover-fn)
                         (let [_ (do (eval/vlog (str indent "=== Error Recovery ==="))
                                     (eval/vlog (str indent "Error: " (:err result))))
                               result-with-program (assoc result :program program')]
                           (if-let [fix-expr (recover-fn result-with-program)]
                             (let [_ (eval/vlog (str indent "Namespace recovery: " (pr-str fix-expr)))
                                   retry (binding [eval/*llm-depth* (inc eval/*llm-depth*)
                                                   eval/*raw-text* nil
                                                   eval/*builtins* variant-builtins]
                                           (eval/spell-eval fix-expr
                                                            (merge (:env result) {'eval eval-builtin})))]
                               (if (eval/ok? retry)
                                 retry
                                 (try-quine-recovery program' result variant-builtins
                                                     eval-builtin eval/*gated-ns-hints*)))
                             (try-quine-recovery program' result variant-builtins
                                                 eval-builtin eval/*gated-ns-hints*)))
                         result)]
                   (when trace-data-atom
                     (reset! trace-data-atom {:program program'
                                              :source-program program}))
                   (if (eval/ok? final-result)
                     (:ok final-result)
                     (throw (ex-info (:err final-result) {:result final-result})))))))))]
    (with-meta f {:spell/inbox-aware true})))

(defn- register-agent
  "Register a dormant agent with stored completion as context.
   Returns handle. Agent wakes on first message (no initial LLM call).
   When woken, messages are appended to the stored completion."
  [config handle-name completion]
  (when-not (keyword? handle-name)
    (throw (ex-info "register-agent: handle must be keyword" {:got handle-name})))
  (when-not (string? completion)
    (throw (ex-info "register-agent: completion must be a string" {:got (type completion)})))
  (let [eval-fn (make-inbox-fn config (atom nil))]
    (runtime/start-box handle-name eval-fn completion)))

(defn- -llm
  "Core llm engine: make API call, deliver to box.
   inside-fn processes the raw completion string.
   eval-fn, when non-nil, indicates root lifecycle (uses run-root-box)."
  [{:keys [call-fn]} handle inside-fn eval-fn prompt-str trace-data-atom]
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
                     (if eval-fn
                       (runtime/run-root-box handle completion inside-fn eval-fn)
                       (runtime/box handle completion inside-fn)))]
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
   The eval builtin binds itself in eval/*builtins* to support recursive eval calls.
   Optional future-only namespaces are injected via eval/*future-env*."
  ([variant-builtins effect-builtins]
   (make-eval variant-builtins effect-builtins {} nil))
  ([variant-builtins effect-builtins future-only-builtins]
   (make-eval variant-builtins effect-builtins future-only-builtins nil))
  ([variant-builtins effect-builtins future-only-builtins current-agent-fn]
   (letfn [(eval-builtin [expr]
             (let [caller-env eval/*spell-env*]
               (binding [eval/*builtins* (merge variant-builtins effect-builtins {'eval eval-builtin})
                         eval/*future-env* future-only-builtins
                         runtime/*default-spawn-agent* (when current-agent-fn
                                                         (current-agent-fn))]
                 (let [result (eval/spell-eval expr caller-env)]
                   (if (eval/ok? result)
                     (:ok result)
                     (throw (ex-info (:err result) {:result result})))))))]
     eval-builtin)))

(defn- wrap-nl
  "Wrap a prompt value for LLM consumption.
   If it starts with '(' it's already code — pass through as string.
   Otherwise, wrap in the standard NL completion prefix."
  [p]
  (let [s (str p)]
    (if (.startsWith (.trim ^String s) "(")
      s
      (str "(quine completion (eval (do "
           "(quine prompt \"" (parse/escape-string s) "\") "))))

(defn compile-agent
  "Factory: compile an agent runtime into a single spawn function.

   Options:
   - :namespaces       - map of {symbol -> namespace-map}. Each namespace has :docs and items.
                         Namespaces are bound under their symbol in the builtins.
   - :model            - optional model name override (nil uses provider default)
   - :system           - optional system prompt string override (nil uses generated prompt)
   - :llm-var          - optional var ref to bind as 'llm for self-recursion (e.g., #'llm)
   - :recover          - error recovery setting (default: true = enabled).
                         - true: namespace recovery + quine-extension recovery
                           (recovery quine adds error info + rethink, then reopens)
                         - false: disable recovery (errors propagate immediately)
                         - fn: custom namespace recovery function (result-map) -> fixed-expr
   - :prefill?         - whether the provider supports assistant prefill (default: true).
                         When false, prefix is sent as user message only and prefix echo is stripped.
   - :thinking         - Anthropic adaptive thinking. When truthy, passed to provider opts.
                         Number = budget_tokens, true = default (10000).
   - :reasoning-effort - OpenAI reasoning effort (\"low\", \"medium\", \"high\").
   - :verbosity        - OpenAI verbosity (\"low\", \"auto\").
   - :suffix-grammar?  - Generate prefix-aware OpenAI grammar constraints per call (default: false).
                         Adds :grammar-format to provider opts. If generated grammar exceeds
                         :grammar-max-chars, grammar constraints are skipped for that call.
   - :grammar-max-chars - Max grammar size before skipping constraints (default: 2000).

   Returns a compiled spawn function marked with {:spell/compiled-agent true}.
   The returned function is the root/new-handle startup path. Same-handle
   prefix completion remains internal via !llm-self only."
  [{:keys [namespaces provider model system llm-var recover format prefill? thinking reasoning-effort verbosity
           suffix-grammar? grammar-max-chars]
    :or {namespaces {} model nil recover true prefill? true suffix-grammar? false grammar-max-chars 2000}}]
  (let [core-ns-names (set (keys core-namespaces))
        ns-builtins (into {} (map (fn [[sym ns-map]] [sym ns-map]) namespaces))
        effect-ns-builtins (into {} (remove #(core-ns-names (key %)) ns-builtins))
        variant-builtins (merge eval/core-builtins
                                {'describe-fn stdlib/describe}
                                core-namespaces)
        sys-prompt (prompt/compose-system-prompt
                     {:base system
                      :namespaces effect-ns-builtins
                      :core-namespaces core-namespaces
                      :format format})
        prev-prompt-atom (atom nil)
        call-fn (fn [prompt-str]
                  (let [prev-prompt @prev-prompt-atom
                        grammar-format (when suffix-grammar?
                                         (let [{:keys [definition over-limit?]}
                                               (grammar/suffix-lark-grammar-stats prompt-str
                                                                                  {:max-chars grammar-max-chars})]
                                           (when-not over-limit?
                                             {:type "grammar" :syntax "lark" :definition definition})))
                        opts (cond-> {:system sys-prompt}
                               prefill? (assoc :prefix prompt-str)
                               model (assoc :model model)
                               thinking (assoc :thinking thinking)
                               reasoning-effort (assoc :reasoning-effort reasoning-effort)
                               verbosity (assoc :verbosity verbosity)
                               grammar-format (assoc :grammar-format grammar-format)
                               prev-prompt (assoc :cache-prefix prev-prompt))
                        base-user-msg (if prefill?
                                        "Continue this Spell program."
                                        prompt-str)
                        max-tok (:max-tokens provider)
                        response (provider/call-with-retries
                                   (fn [err]
                                     (let [user-msg (if (and err (= :missing-tool-call (:type (ex-data err))))
                                                      (str base-user-msg
                                                           "\n;; system: retrying — previous response was truncated or empty"
                                                           (when max-tok (str ", max output tokens " max-tok)))
                                                      base-user-msg)]
                                       (provider/strip-code-fences
                                         (provider/call-llm provider user-msg opts))))
                                   provider/*retries*)]
                    (reset! prev-prompt-atom prompt-str)
                    (if prefill?
                      response
                      (strip-prefix-echo prompt-str response))))
        ns-recover (recovery/make-namespace-recover-fn (merge core-namespaces ns-builtins))
        recover-fn (cond
                     (false? recover) nil
                     (fn? recover) recover
                     :else ns-recover)
        final-config (promise)
        current-agent-ref (atom nil)
        self-ref (atom nil)
        self-fn (fn !llm-self [prompt]
                  (@self-ref prompt))
        effect-builtins (merge {'!llm-self self-fn
                                '!ask-await stdlib/ask-await-builtin
                                'leaf-llm (make-leaf-llm (cond-> {}
                                                           provider (assoc :provider provider)
                                                           model (assoc :model model)))}
                               effect-ns-builtins
                               (when llm-var {'llm llm-var}))
        future-only-builtins {'blocking runtime/blocking-namespace}
        register-agent-fn (fn [handle-name completion] (register-agent @final-config handle-name completion))
        effect-builtins' (if (contains? effect-ns-builtins 'agents)
                           (assoc effect-builtins 'agents
                                  (assoc (get effect-ns-builtins 'agents)
                                         :register register-agent-fn))
                           effect-builtins)
        eval-builtin (make-eval variant-builtins effect-builtins' future-only-builtins
                                #(deref current-agent-ref))
        gated-ns-hints (merge
                         (into {}
                               (for [ns-sym (keys effect-ns-builtins)]
                                 [ns-sym (str (name ns-sym)
                                              "/ is an effect namespace - use it in the trailing expression via eval")]))
                         (into {}
                               (for [ns-sym (keys future-only-builtins)]
                                 [ns-sym (str (name ns-sym)
                                              "/ is only available inside (future ...) blocks")])))
        config' {:call-fn call-fn
                 :variant-builtins variant-builtins
                 :eval-builtin eval-builtin
                 :gated-ns-hints gated-ns-hints
                 :recover-fn recover-fn}
        _ (deliver final-config config')
        start-root (fn start-root [prompt handle]
                     (let [prompt' (cond
                                     (and (seq? prompt) (= 'quine (first prompt)))
                                     (eval/serialize-quine-prefix prompt)
                                     (or (seq? prompt) (list? prompt))
                                     (eval/expand-expr prompt (or eval/*spell-env* {}))
                                     :else
                                     prompt)
                           prompt-str (if (string? prompt') prompt' (str prompt'))
                           init-program (if (.startsWith (.trim ^String prompt-str) "(")
                                          prompt-str
                                          (build-init prompt-str))
                           trace-data (atom nil)
                           inbox-fn (make-inbox-fn config' trace-data)
                           awake-fn (runtime/make-awake-fn handle inbox-fn)]
                       (when-not (runtime/handle? handle)
                         (runtime/register! handle))
                       (runtime/run-root-box handle init-program awake-fn inbox-fn)))
        same-handle-llm (fn same-handle-llm [prompt]
                          (when-not runtime/*current-handle*
                            (throw (ex-info "!llm-self requires an active agent handle"
                                            {:prompt prompt})))
                          (let [prompt' (cond
                                          (and (seq? prompt) (= 'quine (first prompt)))
                                          (eval/serialize-quine-prefix prompt)
                                          (or (seq? prompt) (list? prompt))
                                          (eval/expand-expr prompt (or eval/*spell-env* {}))
                                          :else
                                          prompt)
                                prompt-str (wrap-nl prompt')
                                trace-data (atom nil)
                                inbox-fn (make-inbox-fn config' trace-data)
                                awake-fn (runtime/make-awake-fn runtime/*current-handle* inbox-fn)]
                            (-llm config' runtime/*current-handle* awake-fn nil prompt-str trace-data)))
        compiled-agent (with-meta start-root {:spell/compiled-agent true
                                              :spell/agent-spec {:model model}})]
    (reset! self-ref same-handle-llm)
    (reset! current-agent-ref compiled-agent)
    compiled-agent))

(defn build-init
  "Build a balanced init program from a prompt.
   Uses '(!extend) as the default trailing expression."
  [prompt]
  (str "(quine completion (eval (do "
       "(quine prompt \"" (parse/escape-string (str prompt)) "\") "
       "'(!extend))))"))


;; ---------------------------------------------------------------------------
;; Leaf LLM
;; ---------------------------------------------------------------------------

(defn- resolve-leaf-provider
  [provider]
  (when-not provider
    (throw (ex-info "leaf-llm requires a provider with a plain-text transport"
                    {:type :leaf-llm-provider-missing})))
  (let [leaf-provider (provider/plain-text-provider provider)]
    (when-not leaf-provider
      (throw (ex-info "leaf-llm requires a provider that can supply a plain-text transport"
                      {:type :leaf-llm-plain-text-unsupported
                       :provider-class (class provider)})))
    leaf-provider))

(defn make-leaf-llm
  "Factory: create a plain text-in/text-out LLM function.
   No Spell parsing, evaluation, tools, or sub-agents.

   Options:
   - :provider - LLM provider instance
   - :system   - system prompt string (default: generic assistant)
   - :model    - optional model name override (nil uses provider default)

   Returns (fn [prompt] response-string)."
  ([] (make-leaf-llm {}))
  ([{:keys [provider system model]
     :or {system "You are a helpful assistant. Respond concisely."}}]
   (let [leaf-provider (resolve-leaf-provider provider)]
     (with-meta
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
               opts     (cond-> {:system system}
                          model (assoc :model model))
               max-tok  (:max-tokens leaf-provider)
               response (provider/call-with-retries
                          (fn [err]
                            (let [msg (if (and err (= :missing-tool-call (:type (ex-data err))))
                                       (str prompt-str
                                            "\n;; system: retrying — previous response was truncated or empty"
                                            (when max-tok (str ", max output tokens " max-tok)))
                                       prompt-str)]
                              (provider/strip-code-fences
                                (provider/call-llm leaf-provider msg opts))))
                          provider/*retries*)
               _        (eval/vlog (str indent "Response: " response))
               _        (when node-id
                          (trace/complete-node! node-id
                            {:response response :raw-text response :value response}))]
           response))
       {:spell/leaf true}))))
