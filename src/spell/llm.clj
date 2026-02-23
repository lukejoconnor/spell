(ns spell.llm
  "LLM orchestration engine for Spell.

   Core loop: call LLM, concatenate prefix+response, parse, eval."
  (:require [clojure.string :as str]
            [spell.runtime :as runtime]
            [spell.eval :as eval]
            [spell.grammar :as grammar]
            [spell.parse :as parse]
            [spell.prompt :as prompt]
            [spell.provider :as provider]
            [spell.recovery :as recovery]
            [spell.stdlib :as stdlib]
            [spell.trace :as trace]))

(declare make-leaf-llm)

;; ---------------------------------------------------------------------------
;; Core namespaces — always available, never need to be configured
;; ---------------------------------------------------------------------------

(def core-namespaces
  "Namespaces always merged into variant-builtins (available everywhere)."
  {'strings stdlib/strings
   'math stdlib/math
   'builtins stdlib/builtins-namespace})

(def ^:private max-recovery-attempts
  "Maximum number of recovery retries before failing."
  2)

(def ^:dynamic *reader-recovery-depth*
  "Current depth of nested reader-error recovery retries."
  0)

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
   Returns eval result (ok or err). Throws on non-quine or retry limit.
   If program doesn't start with (quine completion ...), wraps it first and recurses."
  [program result variant-builtins eval-builtin]
  (if-not (and (seq? program)
               (= 'quine (first program))
               (= 'completion (second program)))
    ;; Not a (quine completion ...) form — wrap and recurse
    (let [indent (apply str (repeat eval/*llm-depth* "  "))
          wrapped (list 'quine 'completion program)]
      (eval/vlog (str indent "Wrapping in quine completion for recovery"))
      (try-quine-recovery wrapped result variant-builtins eval-builtin))
    ;; Normal path: program is already (quine completion ...)
    (let [quine-arg-count (- (count (seq program)) 2)]
      (if (< quine-arg-count (+ 1 max-recovery-attempts))
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
        ;; Retry limit reached — propagate error
        (throw (ex-info (:err result) {:result result}))))))

(defn- try-reader-recovery
  "Attempt recovery from a reader/parse error by embedding the raw text
   as a string in a fresh recovery quine. The LLM sees its broken output
   and the error message, then gets a fresh chance via extend."
  [raw parse-error variant-builtins eval-builtin]
  (let [error-msg (or (.getMessage parse-error) "Unknown reader error")
        indent    (apply str (repeat eval/*llm-depth* "  "))
        _         (when (>= *reader-recovery-depth* max-recovery-attempts)
                    (throw (ex-info (str "Reader error recovery limit exceeded: " max-recovery-attempts)
                                    {:type :reader-recovery-exhausted
                                     :attempts *reader-recovery-depth*
                                     :limit max-recovery-attempts
                                     :parse-error error-msg})))
        _         (eval/vlog (str indent "=== Reader Error Recovery ==="))
        _         (eval/vlog (str indent "Recovery attempt: "
                                  (inc *reader-recovery-depth*) "/" max-recovery-attempts))
        _         (eval/vlog (str indent "Parse error: " error-msg))
        error-map {:error (str "Reader error: " error-msg) :raw raw}
        recovery-quine (list 'quine 'completion
                         (list 'eval
                           (list 'do
                             (list 'def '_error error-map)
                             (list 'quote (list 'extend 'completion)))))
        result    (binding [eval/*llm-depth*           (inc eval/*llm-depth*)
                            eval/*raw-text*            nil
                            eval/*builtins*            variant-builtins
                            *reader-recovery-depth*    (inc *reader-recovery-depth*)]
                    (eval/spell-eval recovery-quine {'eval eval-builtin}))]
    (if (eval/ok? result)
      (:ok result)
      (throw (ex-info (or (:err result)
                          (str "Reader error (unrecoverable): " error-msg))
                      {:parse-error error-msg :result result})))))

(defn make-inbox-fn
  "Create inbox function: [raw] -> value.
   Closes over eval-builtin from config. Calls balance-parens because
   send transforms can produce unbalanced strings (reopen strips parens).
   trace-data-atom, when non-nil, receives {:program} for tracing."
  [{:keys [variant-builtins eval-builtin recover-fn]} trace-data-atom]
  (fn [raw]
    (let [raw       (parse/balance-parens raw)
          [forms parse-err] (try [(parse/read-all raw) nil]
                                 (catch Exception e [nil e]))]
      (if parse-err
        ;; Reader error: embed raw text as string in recovery quine
        (if recover-fn
          (try-reader-recovery raw parse-err variant-builtins eval-builtin)
          (throw parse-err))
        ;; Normal path: eval and recovery
        (let [program   (if (> (count (vec forms)) 1) (list* 'do forms) (first forms))
              indent    (apply str (repeat eval/*llm-depth* "  "))
              result    (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                                 eval/*raw-text*       raw
                                 eval/*builtins*       variant-builtins]
                          (eval/spell-eval program {'eval eval-builtin}))
              final-result
              (if (and (eval/err? result) recover-fn)
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
            (throw (ex-info (:err final-result) {:result final-result}))))))))

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

(defn- wrap-nl
  "Wrap a prompt value for LLM consumption.
   If it starts with '(' it's already code — pass through as string.
   Otherwise, wrap in the standard NL completion prefix."
  [p]
  (let [s (if (or (seq? p) (list? p)) (pr-str p) (str p))]
    (if (.startsWith (.trim ^String s) "(")
      (str p)
      (str "(quine completion (eval (do "
           "(quine prompt \"" (parse/escape-string s) "\") "))))

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
   - :suffix-grammar?  - Generate prefix-aware OpenAI grammar constraints per call (default: false).
                         Adds :grammar-format to provider opts. If generated grammar exceeds
                         :grammar-max-chars, grammar constraints are skipped for that call.
   - :grammar-max-chars - Max grammar size before skipping constraints (default: 2000).

   Returns a map {:llm fn, :run fn}.

   The returned function is automatically available as 'llm-self in Spell code,
   providing self-recursion without needing to wire up var refs."
  [{:keys [namespaces provider model system llm-var recover format prefill? thinking reasoning-effort verbosity
           suffix-grammar? grammar-max-chars]
    :or {namespaces {} model nil recover true prefill? true suffix-grammar? false grammar-max-chars 2000}}]
  (let [;; Core namespaces are always available; everything in :namespaces is effect
        core-ns-names (set (keys core-namespaces))
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
        call-fn  (fn [prompt-str]
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
                         response (provider/call-with-retries
                                    #(provider/strip-code-fences
                                       (provider/call-llm provider prompt-str opts))
                                    provider/*retries*)]
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
                   (when-not (runtime/handle? handle)
                     (throw (ex-info "Explicit handle requires spawn context" {:handle handle})))
                   (@self-ref prompt handle)))
        ;; Create effect-builtins (closes over llm-self)
        effect-builtins (merge {'llm-self self-fn
                               'leaf-llm (make-leaf-llm (cond-> {}
                                                          provider (assoc :provider provider)
                                                          model (assoc :model model)))}
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
        the-llm  (fn the-llm
                   ([prompt] (the-llm prompt nil))
                   ([prompt handle]
                    (let [handle     (or handle runtime/*current-handle* :main)
                          parent     (or runtime/*current-handle*  ;; llm-self (inherited)
                                       (:parent-handle (get @runtime/registry handle))  ;; spawn child
                                       )
                          root?      (not= parent handle)
                          prompt'    (if (or (seq? prompt) (list? prompt))
                                       (eval/expand-expr prompt (or eval/*spell-env* {}))
                                       prompt)
                          prompt-str (wrap-nl prompt')
                          trace-data (atom nil)
                          inbox-fn   (make-inbox-fn config' trace-data)
                          awake-fn   (runtime/make-awake-fn inbox-fn)]
                      (when-not (runtime/handle? handle)
                        (runtime/register! handle))
                      (-llm config' handle awake-fn (when root? inbox-fn) prompt-str trace-data))))]
    (reset! self-ref the-llm)
    {:llm the-llm
     :run (fn run-init [init-string]
            (let [handle   :main
                  inbox-fn (make-inbox-fn config' (atom nil))
                  awake-fn (runtime/make-awake-fn inbox-fn)]
              (when-not (runtime/handle? handle)
                (runtime/register! handle))
              (runtime/run-root-box handle init-string awake-fn inbox-fn)))}))

(defn build-init
  "Build a balanced init program from a prompt and optional preamble.
   preamble: optional string of Spell expressions spliced before trailing expr."
  ([prompt] (build-init prompt nil))
  ([prompt preamble]
   (str "(quine completion (eval (do "
        "(quine prompt \"" (parse/escape-string (str prompt)) "\") "
        (when preamble (str preamble " "))
        "'(extend))))")))

;; ---------------------------------------------------------------------------
;; Leaf LLM
;; ---------------------------------------------------------------------------

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
             response (provider/call-with-retries
                        #(provider/strip-code-fences
                           (provider/call-llm provider prompt-str opts))
                        provider/*retries*)
             _        (eval/vlog (str indent "Response: " response))
             _        (when node-id
                        (trace/complete-node! node-id
                          {:response response :raw-text response :value response}))]
         response))
     {:spell/leaf true})))
