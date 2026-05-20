(ns spell.api
  "Single public entry point for running Spell agents."
  (:require [clojure.set :as set]
            [spell.agent :as agent]
            [spell.runtime :as runtime]
            [spell.eval :as eval]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.trace :as trace]
            [spell.user :as user]))

(def ^:private public-run-keys
  #{:prompt :init :model-profile :agent-profile :model :reasoning-effort
    :budget :depth :trace-dir :usage-tracker :user-reader :log-writer})

(def ^:private removed-run-keys
  #{:provider :agent :lm-profile :trace :usage :user? :verbose :thinking :prefill? :format :retries
    :suffix-grammar? :suffix-grammar :grammar-max-chars :provider-constructor
    :verbosity :api-key :api-key-env :base-url :auth-file :account-id :max-tokens :costs
    :use-responses-api :force-tool-call :request-timeout-sec :sse-idle-timeout-sec
    :sse-completion-timeout-sec :prefill})

(defn- validate-public-run-opts! [opts]
  (let [unknown (seq (sort-by name (set/difference (set (keys opts)) public-run-keys)))
        removed (seq (sort-by name (set/intersection (set (keys opts)) removed-run-keys)))]
    (when removed
      (throw (ex-info (str "Removed public run option(s): " removed)
                      {:type :invalid-run-options :removed removed})))
    (when unknown
      (throw (ex-info (str "Unknown public run option(s): " unknown)
                      {:type :invalid-run-options :unknown unknown})))))

(defn- validate-required-run-opts! [{:keys [prompt init model-profile agent-profile]}]
  (when (and prompt init)
    (throw (ex-info "Specify exactly one of :prompt or :init, not both" {})))
  (when-not (or prompt init)
    (throw (ex-info "Must specify exactly one of :prompt or :init" {})))
  (when-not agent-profile
    (throw (ex-info "Must specify :agent-profile (path to .agent.edn file)" {})))
  (when-not model-profile
    (throw (ex-info "Must specify :model-profile" {}))))

(defn- execute-run
  [{:keys [prompt init model-profile agent-profile model reasoning-effort budget depth trace-dir
           usage-tracker user-reader log-writer]
    :as opts}]
  (validate-required-run-opts! opts)
  (let [profile (provider/resolve-model-profile model-profile)
        resolved-provider (:provider profile)
        agent-spec (cond-> (agent/load-agent-spec agent-profile)
                     true (assoc :provider resolved-provider)
                     (or model (:default-model profile)) (assoc :model (or model (:default-model profile)))
                     (or reasoning-effort (:default-reasoning-effort profile))
                     (assoc :reasoning-effort (or reasoning-effort (:default-reasoning-effort profile)))
                     (contains? opts :prefill?) (assoc :prefill? (:prefill? opts))
                     (:thinking opts) (assoc :thinking (:thinking opts))
                     (:verbosity opts) (assoc :verbosity (:verbosity opts))
                     (:suffix-grammar? opts) (assoc :suffix-grammar? (:suffix-grammar? opts))
                     (:grammar-max-chars opts) (assoc :grammar-max-chars (:grammar-max-chars opts))
                     (:format opts) (assoc :format (:format opts)))
        agent-fn (agent/compile-agent-spec agent-spec)
        run-input (if init (llm/direct-init init) (llm/build-init prompt))
        effective-budget (cond
                           (nil? budget) (or (:budget agent-spec) provider/*budget*)
                           (zero? budget) nil
                           :else budget)
        effective-verbose (some? log-writer)
        trace-atom (when trace-dir (trace/new-trace))
        trace-written? (atom false)
        usage-atom (or usage-tracker (atom {:by-model {}}))
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
                              (catch Exception _)))))]
    (reset! runtime/registry {})
    (globals/reset-globals!)
    (globals/set-val :roles {:main {}})
    (when user-reader
      (user/register-user-agent!
        (if (instance? java.io.BufferedReader user-reader)
          user-reader
          (java.io.BufferedReader. user-reader))))
    (try
      (when shutdown-hook
        (.addShutdownHook (Runtime/getRuntime) shutdown-hook))
      (binding [eval/*verbose* effective-verbose
                eval/*log-writer* log-writer
                eval/*max-llm-depth* depth
                provider/*usage* usage-atom
                provider/*budget* effective-budget
                provider/*retries* (or (:retries opts) (:retries profile) (:retries agent-spec) provider/*retries*)
                trace/*trace* trace-atom]
        (let [result (try
                       {:result (agent-fn run-input :main)
                        :usage-tracker usage-atom}
                       (catch Exception e
                         {:error (.getMessage e)
                          :error-data (ex-data e)
                          :usage-tracker usage-atom}))]
          (when trace-atom
            (write-trace-once! true))
          (cond-> result
            trace-dir (assoc :trace-dir trace-dir))))
      (finally
        (when shutdown-hook
          (try
            (.removeShutdownHook (Runtime/getRuntime) shutdown-hook)
            (catch IllegalStateException _)))
        (when log-writer
          (.flush ^java.io.Writer log-writer))))))

(defn run
  "Run a Spell agent with the v0.2.0 public API.

   Required:
     :model-profile — model profile path, inline profile map, or provider instance
     :agent-profile      — path to .agent.edn
     exactly one of :prompt or :init

   Returns {:result value :usage-tracker atom} or
   {:error message :error-data data :usage-tracker atom}."
  [opts]
  (validate-public-run-opts! opts)
  (execute-run opts))

(defn run-internal
  "Internal adapter for CLI and benchmark compatibility.
   Accepts non-public runtime controls while still requiring :model-profile."
  [opts]
  (execute-run opts))
