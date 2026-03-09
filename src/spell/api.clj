(ns spell.api
  "Single entry point for running Spell agents.
   Pure wiring — no CLI concerns, no provider construction."
  (:require [spell.agent :as agent]
            [spell.runtime :as runtime]
            [spell.eval :as eval]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.trace :as trace]
            [spell.user :as user]))

(defn run
  "Run a Spell agent. Returns {:result val :usage atom} or {:error msg ...}.

   Required (one of):
     :provider — LLM provider instance (can be omitted if agent .edn specifies :provider)

   Prompt (mutually exclusive):
     :prompt — NL prompt (auto-wrapped into init program)
     :init   — complete Spell program string

   Required:
     :agent    — path to .agent.edn
     :user?    — register :user handle (default: false)
     :user-reader — BufferedReader for user input (default: System/in)
     :verbose  — show raw LLM response (default: false)
     :log-writer — java.io.Writer for verbose output
     :budget   — max spend in dollars (nil = unlimited)
     :depth    — max LLM recursion depth (nil = unlimited)
     :trace    — when true, record execution trace
     :trace-dir — optional destination directory for trace output
     :prefill? — override provider prefill support
     :thinking — Anthropic adaptive thinking budget
     :reasoning-effort — OpenAI reasoning effort
     :verbosity — OpenAI verbosity
     :suffix-grammar? — enable prefix-aware OpenAI suffix grammar constraints
     :grammar-max-chars — max generated grammar chars before fallback
     :format   — optional output format spec {:required [...] :optional [...]}
     :retries  — API retry sleep durations
     :usage    — pre-created usage atom (default: fresh atom)"
  [{:keys [prompt init agent provider user? user-reader
           verbose log-writer budget depth trace trace-dir
           prefill? thinking reasoning-effort verbosity
           suffix-grammar? grammar-max-chars format retries usage]}]
  ;; Validate inputs
  (when (and prompt init)
    (throw (ex-info "Specify exactly one of :prompt or :init, not both" {})))
  (when-not (or prompt init)
    (throw (ex-info "Must specify :prompt or :init" {})))
  (when-not agent
    (throw (ex-info "Must specify :agent (path to .agent.edn file)" {})))
  (let [;; Load agent config
        agent-spec (cond-> (agent/load-agent-spec agent)
                       ;; Inject provider into the plain spec before compilation
                       provider (assoc :provider provider)
                       (some? prefill?) (assoc :prefill? prefill?)
                       thinking (assoc :thinking thinking)
                       reasoning-effort (assoc :reasoning-effort reasoning-effort)
                       verbosity (assoc :verbosity verbosity)
                       suffix-grammar? (assoc :suffix-grammar? suffix-grammar?)
                       grammar-max-chars (assoc :grammar-max-chars grammar-max-chars)
                       format (assoc :format format))
        ;; Validate: provider must come from somewhere
        _ (when-not (:provider agent-spec)
            (throw (ex-info "Must specify :provider (via argument or agent .edn)" {})))
        ;; Compile runnable spawn-agent function
        agent-fn (agent/compile-agent-spec agent-spec)
        run-input (or init prompt)
        ;; Budget: explicit > agent config > dynamic var default
        effective-budget (cond
                           (nil? budget) (or (:budget agent-spec) provider/*budget*)
                           (zero? budget) nil
                           :else budget)
        effective-verbose (or verbose (some? log-writer))
        trace-atom (when trace (trace/new-trace))
        trace-dir (when trace
                    (or trace-dir
                        (let [fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH-mm-ss")]
                          (str "traces/" (.format fmt (java.util.Date.))))))
        trace-written? (atom false)
        write-trace-once!
        (fn [force?]
          (when (and trace-atom
                     trace-dir
                     (or force? (seq (:nodes @trace-atom)))
                     (compare-and-set! trace-written? false true))
            (trace/write-trace! @trace-atom trace-dir)))
        shutdown-hook (when trace-atom
                        (Thread.
                         ^Runnable
                         (fn []
                           (try
                             (write-trace-once! false)
                             (catch Exception _)))))
        usage-atom (or usage (atom {:by-model {}}))]
    ;; Reset runtime registry and globals for fresh run
    (reset! runtime/registry {})
    (globals/reset-globals!)
    ;; Register user agent if requested
    (when user?
      (if user-reader
        (user/register-user-agent! user-reader)
        (user/register-user-agent!)))
    (try
      (when shutdown-hook
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook))
        (binding [eval/*verbose* effective-verbose
                eval/*log-writer* log-writer
                eval/*max-llm-depth* depth
                provider/*usage* usage-atom
                provider/*budget* effective-budget
                provider/*retries* (or retries (:retries agent-spec) provider/*retries*)
                trace/*trace* trace-atom]
        (let [result (try
                       {:result (agent-fn run-input :main)
                        :usage usage-atom}
                       (catch Exception e
                         {:error (.getMessage e)
                          :error-data (ex-data e)
                          :usage usage-atom}))]
          (when trace-atom
            (write-trace-once! true))
          (if trace-atom
            (assoc result :trace-dir trace-dir)
            result)))
      (finally
        (when shutdown-hook
          (try
            (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
            (catch IllegalStateException _)))
        (when log-writer
          (.close ^java.io.Writer log-writer))))))
