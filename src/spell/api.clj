(ns spell.api
  "Single entry point for running Spell agents.
   Pure wiring — no CLI concerns, no provider construction."
  (:require [spell.agent :as agent]
            [spell.comm :as comm]
            [spell.eval :as eval]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.trace :as trace]
            [spell.user :as user]))

(defn run
  "Run a Spell agent. Returns {:result val :usage atom} or {:error msg ...}.

   Required:
     :provider — LLM provider instance

   Prompt (mutually exclusive):
     :prompt — NL prompt (auto-wrapped into init program)
     :init   — complete Spell program string

   Optional:
     :agent    — path to .agent.edn (default: built-in)
     :user?    — register :user handle (default: false)
     :user-reader — BufferedReader for user input (default: System/in)
     :verbose  — show raw LLM response (default: false)
     :log-writer — java.io.Writer for verbose output
     :budget   — max spend in dollars (nil = unlimited)
     :depth    — max LLM recursion depth (nil = unlimited)
     :trace    — when true, record execution trace
     :prefill? — override provider prefill support
     :thinking — Anthropic adaptive thinking budget
     :reasoning-effort — OpenAI reasoning effort
     :verbosity — OpenAI verbosity
     :retries  — API retry sleep durations"
  [{:keys [prompt init agent provider user? user-reader
           verbose log-writer budget depth trace
           prefill? thinking reasoning-effort verbosity retries]}]
  ;; Validate inputs
  (when (and prompt init)
    (throw (ex-info "Specify exactly one of :prompt or :init, not both" {})))
  (when-not (or prompt init)
    (throw (ex-info "Must specify :prompt or :init" {})))
  (when-not provider
    (throw (ex-info "Must specify :provider" {})))
  (let [;; Load agent config
        agent-config (cond-> (if agent
                               (agent/load-agent-config agent)
                               (agent/default-agent-config))
                       ;; Inject provider into agent config (flows to make-llm closure)
                       provider (assoc :provider provider)
                       (some? prefill?) (assoc :prefill? prefill?)
                       thinking (assoc :thinking thinking)
                       reasoning-effort (assoc :reasoning-effort reasoning-effort)
                       verbosity (assoc :verbosity verbosity))
        ;; Build llm+run from agent config
        llm-map (agent/make-agent-llm agent-config)
        _ (when-not (:run llm-map)
            (throw (ex-info "Agent has :eval false — cannot run init program" {})))
        ;; Build init program
        init-program (or init (llm/build-init prompt (:init agent-config)))
        ;; Budget: explicit > agent config > dynamic var default
        effective-budget (cond
                           (nil? budget) (or (:budget agent-config) provider/*budget*)
                           (zero? budget) nil
                           :else budget)
        effective-verbose (or verbose (some? log-writer))
        trace-atom (when trace (trace/new-trace))
        usage-atom (atom {:by-model {}})]
    ;; Reset comm registry and globals for fresh run
    (reset! comm/registry {})
    (globals/reset-globals!)
    ;; Register user agent if requested
    (when user?
      (if user-reader
        (user/register-user-agent! user-reader)
        (user/register-user-agent!)))
    (try
      (binding [eval/*verbose* effective-verbose
                eval/*log-writer* log-writer
                eval/*max-llm-depth* depth
                provider/*usage* usage-atom
                provider/*budget* effective-budget
                provider/*retries* (or retries (:retries agent-config) provider/*retries*)
                trace/*trace* trace-atom]
        (let [result (try
                       {:result ((:run llm-map) init-program)
                        :usage usage-atom}
                       (catch Exception e
                         {:error (.getMessage e)
                          :error-data (ex-data e)
                          :usage usage-atom}))]
          (if trace-atom
            (let [dir (trace/write-trace! @trace-atom
                        (let [fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH-mm-ss")]
                          (str "traces/" (.format fmt (java.util.Date.)))))]
              (assoc result :trace-dir dir))
            result)))
      (finally
        (when log-writer
          (.close ^java.io.Writer log-writer))))))
