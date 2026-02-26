(ns spell.benchmark-api
  "Machine-oriented JSON API for benchmark runners.
   Accepts one request object and returns one response object."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [spell.api :as api]
            [spell.provider :as provider])
  (:gen-class))

(def provider-prefixes
  #{"ollama" "chatgpt" "codex" "codex-toolcall" "openclaw" "openai"
    "anthropic" "anthropic-toolcall" "kimi" "moonshot" "test"})

(def cli-options
  [["-r" "--request FILE" "Request JSON path, or '-' for stdin" :default "-"]
   ["-o" "--response FILE" "Response JSON path, or '-' for stdout" :default "-"]
   ["-h" "--help" "Show help"]])

(defn- usage [summary]
  (str/join
    "\n"
    ["spell.benchmark-api - JSON interface for benchmarks"
     ""
     "Usage: clj -M -m spell.benchmark-api [--request FILE|-] [--response FILE|-]"
     ""
     summary]))

(defn- parse-model-spec [s]
  (if-let [idx (str/index-of s ":")]
    (let [prefix (subs s 0 idx)
          rest (subs s (inc idx))]
      (if (contains? provider-prefixes prefix)
        {:provider prefix :model rest}
        (throw (ex-info (str "Unknown provider prefix: " (pr-str prefix)
                             ". Known prefixes: " (str/join ", " (sort provider-prefixes)))
                        {:prefix prefix :model-spec s}))))
    {:provider nil :model s}))

(def model-aliases
  {"haiku" "claude-haiku-4-5-20251001"
   "sonnet" "claude-sonnet-4-5-20250929"
   "opus" "claude-opus-4-5-20251101"
   "opus46" "claude-opus-4-6"
   "o3" "o3"
   "o4-mini" "o4-mini"
   "gpt52" "gpt-5.2"
   "gpt53" "gpt-5.3"})

(defn- resolve-model [model]
  (get model-aliases model model))

(def provider-edn-by-prefix
  {"anthropic" "config/providers/anthropic.provider.edn"
   "anthropic-toolcall" "config/providers/anthropic-toolcall.provider.edn"
   "chatgpt" "config/providers/chatgpt-codex.provider.edn"
   "codex" "config/providers/chatgpt-codex.provider.edn"
   "codex-toolcall" "config/providers/chatgpt-codex-toolcall.provider.edn"
   "openai" "config/providers/openai.provider.edn"
   "ollama" "config/providers/ollama.provider.edn"
   "openclaw" "config/providers/openclaw.provider.edn"
   "kimi" "config/providers/kimi.provider.edn"
   "moonshot" "config/providers/kimi.provider.edn"})

(defn- normalize-keys [v]
  (cond
    (map? v)
    (into {}
          (map (fn [[k vv]]
                 [(-> k name (str/replace "_" "-") keyword)
                  (normalize-keys vv)]))
          v)

    (vector? v)
    (mapv normalize-keys v)

    :else v))

(defn- read-json-source [path]
  (let [raw (if (= "-" path) (slurp *in*) (slurp path))]
    (-> (json/read-str raw :key-fn keyword)
        normalize-keys)))

(defn- write-json-dest [path data]
  (let [payload (json/write-str data)]
    (if (= "-" path)
      (println payload)
      (spit path (str payload "\n")))))

(defn- json-safe
  "Convert values to JSON-serializable data.
   Preserves primitive values; stringifies unknown objects."
  [v]
  (cond
    (or (nil? v) (string? v) (number? v) (boolean? v)) v
    (keyword? v) (name v)
    (symbol? v) (str v)
    (map? v) (into {}
                   (map (fn [[k vv]] [(json-safe k) (json-safe vv)]))
                   v)
    (vector? v) (mapv json-safe v)
    (set? v) (mapv json-safe v)
    (seq? v) (mapv json-safe v)
    :else (pr-str v)))

(defn- make-provider [{:keys [model max-tokens responses-api] :as _req}]
  (let [model-spec (or model "anthropic:claude-sonnet-4-5-20250929")
        {:keys [provider model]} (parse-model-spec model-spec)
        resolved-model (resolve-model model)
        resolved-model (if (and (contains? #{"chatgpt" "codex"} provider)
                                (= resolved-model "gpt-5.3"))
                         "gpt-5.3-codex"
                         resolved-model)
        base-opts (cond-> {:costs provider/default-costs}
                    resolved-model (assoc :model resolved-model)
                    max-tokens (assoc :max-tokens max-tokens))]
    (case provider
      "ollama"
      (provider/ollama-provider base-opts)

      ("chatgpt" "codex")
      (provider/chatgpt-codex-provider base-opts)

      "codex-toolcall"
      (provider/chatgpt-codex-toolcall-provider base-opts)

      "openai"
      (provider/openai-provider (cond-> base-opts
                                  responses-api (assoc :use-responses-api true)))

      ("kimi" "moonshot")
      (provider/kimi-provider base-opts)

      "openclaw"
      (provider/load-provider "config/providers/openclaw.provider.edn")

      "test"
      (provider/test-provider {:response "\"hello world\""})

      ("anthropic" nil)
      (provider/anthropic-provider base-opts)

      "anthropic-toolcall"
      (provider/anthropic-toolcall-provider base-opts)

      (throw (ex-info (str "Unknown provider prefix: " provider)
                      {:provider provider :model-spec model-spec})))))

(defn- default-agent-from-request
  "Resolve default agent path from provider .edn for this request."
  [{:keys [model responses-api]}]
  (let [model-spec (or model "anthropic:claude-sonnet-4-5-20250929")
        {:keys [provider]} (parse-model-spec model-spec)
        provider-prefix (or provider "anthropic")
        provider-edn (cond
                       (and (= provider-prefix "openai") responses-api)
                       "config/providers/openai-responses.provider.edn"
                       :else
                       (get provider-edn-by-prefix provider-prefix))]
    (or (when provider-edn
          (provider/provider-edn-default-agent provider-edn))
        ;; Test mode doesn't have a provider file; use message transport base.
        (when (= provider-prefix "test")
          "config/agents/base-message.agent.edn"))))

(defn- response-ok [mode start-ns result-map]
  (let [latency-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
        usage-atom (:usage result-map)
        usage (when usage-atom (provider/usage-summary usage-atom))]
    (cond-> {:ok true
             :mode mode
             :latency_ms latency-ms
             :usage usage}
      (contains? result-map :result) (assoc :result (str (:result result-map)))
      (:trace-dir result-map) (assoc :trace_dir (:trace-dir result-map)))))

(defn- response-error [mode start-ns e]
  (let [latency-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
        data (ex-data e)]
    {:ok false
     :mode mode
     :latency_ms latency-ms
     :error (.getMessage e)
     :error_type (some-> (:type data) name)
     :error_data (json-safe data)}))

(defn- normalize-budget [budget]
  (cond
    (nil? budget) nil
    (zero? budget) nil
    :else budget))

(defn- run-baseline [{:keys [prompt reasoning-effort verbosity thinking budget retries] :as req}]
  (let [usage-atom (atom {:by-model {}})
        provider-inst (make-provider req)
        start-ns (System/nanoTime)]
    (try
      (binding [provider/*usage* usage-atom
                provider/*budget* (normalize-budget budget)
                provider/*retries* (or retries provider/*retries*)]
        (let [text (provider/call-llm provider-inst prompt
                                      (cond-> {}
                                        reasoning-effort (assoc :reasoning-effort reasoning-effort)
                                        verbosity (assoc :verbosity verbosity)
                                        thinking (assoc :thinking thinking)))]
          (response-ok "baseline" start-ns {:result text :usage usage-atom})))
      (catch Exception e
        (response-error "baseline" start-ns e)))))

(defn- run-spell [{:keys [prompt init agent budget depth trace prefill
                          thinking reasoning-effort verbosity suffix-grammar
                          grammar-max-chars retries] :as req}]
  (let [provider-inst (make-provider req)
        resolved-agent (or agent (default-agent-from-request req))
        effective-prefill (if (contains? req :prefill)
                            prefill
                            (and (provider/supports-prefill provider-inst)
                                 (not thinking)))
        start-ns (System/nanoTime)]
    (try
      (let [result (api/run (cond-> {:provider provider-inst
                                     :agent resolved-agent
                                     :budget budget
                                     :depth depth
                                     :trace trace
                                     :retries retries
                                     :thinking thinking
                                     :reasoning-effort reasoning-effort
                                     :verbosity verbosity
                                     :prefill? effective-prefill
                                     :suffix-grammar? suffix-grammar
                              :grammar-max-chars grammar-max-chars}
                              prompt (assoc :prompt prompt)
                              init (assoc :init init)))]
        (if (:error result)
          {:ok false
           :mode "spell"
           :latency_ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
           :error (:error result)
           :error_type (some-> result :error-data :type name)
           :error_data (json-safe (:error-data result))
           :usage (when-let [u (:usage result)] (provider/usage-summary u))
           :trace_dir (:trace-dir result)}
          (response-ok "spell" start-ns result)))
      (catch Exception e
        (response-error "spell" start-ns e)))))

(defn- run-request [{:keys [mode prompt init] :as req}]
  (let [effective-mode (or mode "spell")]
    (cond
      (and (nil? prompt) (nil? init))
      {:ok false :mode effective-mode :error "Request must include prompt or init" :error_type "invalid_request"}

      (= effective-mode "baseline")
      (if (nil? prompt)
        {:ok false :mode effective-mode :error "Baseline mode requires prompt" :error_type "invalid_request"}
        (run-baseline req))

      (= effective-mode "spell")
      (run-spell req)

      :else
      {:ok false
       :mode effective-mode
       :error (str "Unsupported mode: " effective-mode)
       :error_type "invalid_mode"})))

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do
        (println (usage summary))
        (System/exit 0))

      (seq errors)
      (do
        (binding [*out* *err*]
          (println (str "Error: " (str/join "; " errors))))
        (System/exit 2))

      :else
      (let [req (read-json-source (:request options))
            response (run-request req)]
        (write-json-dest (:response options) response)
        (System/exit (if (:ok response) 0 1))))))
