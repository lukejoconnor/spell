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
  #{"ollama" "codex-msg" "codex-tc" "openclaw" "openai"
    "anthropic-pf" "anthropic-tc" "kimi" "moonshot" "test"})

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
  {"anthropic-pf"  "config/providers/anthropic-pf.provider.edn"
   "anthropic-tc"  "config/providers/anthropic-tc.provider.edn"
   "codex-msg"     "config/providers/codex-msg.provider.edn"
   "codex-tc"      "config/providers/codex-tc.provider.edn"
   "openai"        "config/providers/openai.provider.edn"
   "ollama"        "config/providers/ollama.provider.edn"
   "openclaw"      "config/providers/openclaw.provider.edn"
   "kimi"          "config/providers/kimi.provider.edn"
   "moonshot"      "config/providers/kimi.provider.edn"})

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

(defn- parse-format-key
  "Parse a JSON-provided key spec into a Clojure key for format validation.
   Strings prefixed with ':' become keywords; other strings become symbols."
  [k]
  (cond
    (or (keyword? k) (symbol? k)) k
    (string? k) (if (str/starts-with? k ":")
                  (keyword (subs k 1))
                  (symbol k))
    :else k))

(defn- normalize-format-spec
  "Normalize :format values from JSON input.
   Converts string key specs in :required/:optional into keywords or symbols."
  [format-spec]
  (if (map? format-spec)
    (cond-> format-spec
      (:required format-spec) (update :required #(mapv parse-format-key %))
      (:optional format-spec) (update :optional #(mapv parse-format-key %)))
    format-spec))

(defn- unwrap-result-value
  "If a run result is wrapped as a map containing result, unwrap it."
  [v]
  (if (map? v)
    (cond
      (contains? v :result) (get v :result)
      (contains? v 'result) (get v 'result)
      (contains? v "result") (get v "result")
      :else v)
    v))

(defn- make-provider [{:keys [model max-tokens responses-api] :as _req}]
  (when-not model
    (throw (ex-info "model is required in benchmark request" {:field "model"})))
  (let [model-spec model
        {:keys [provider model]} (parse-model-spec model-spec)
        resolved-model (resolve-model model)
        resolved-model (if (and (contains? #{"codex-msg" "codex-tc"} provider)
                                (= resolved-model "gpt-5.3"))
                         "gpt-5.3-codex"
                         resolved-model)
        base-opts (cond-> {:costs provider/default-costs}
                    resolved-model (assoc :model resolved-model)
                    max-tokens (assoc :max-tokens max-tokens))]
    (case provider
      "ollama"
      (provider/ollama-provider base-opts)

      "codex-msg"
      (provider/codex-msg-provider base-opts)

      "codex-tc"
      (provider/codex-tc-provider base-opts)

      "openai"
      (provider/openai-provider (cond-> base-opts
                                  responses-api (assoc :use-responses-api true)))

      ("kimi" "moonshot")
      (provider/kimi-provider base-opts)

      "openclaw"
      (provider/load-provider "config/providers/openclaw.provider.edn")

      "test"
      (provider/test-provider {:response "\"hello world\""})

      ("anthropic-pf" nil)
      (provider/anthropic-pf-provider base-opts)

      "anthropic-tc"
      (provider/anthropic-tc-provider base-opts)

      (throw (ex-info (str "Unknown provider prefix: " provider)
                      {:provider provider :model-spec model-spec})))))

(defn- default-agent-from-request
  "Resolve default agent path from provider .edn for this request."
  [{:keys [model responses-api]}]
  (when-not model
    (throw (ex-info "model is required to resolve default agent" {:field "model"})))
  (let [{:keys [provider]} (parse-model-spec model)
        provider-prefix (or provider "anthropic-pf")
        provider-edn (cond
                       (and (= provider-prefix "openai") responses-api)
                       "config/providers/openai-responses.provider.edn"
                       :else
                       (get provider-edn-by-prefix provider-prefix))]
    (or (when provider-edn
          (provider/provider-edn-default-agent provider-edn))
        ;; Test mode doesn't have a provider file; use message transport base.
        (when (= provider-prefix "test")
          "config/agents/base-msg.agent.edn"))))

(defn- response-ok [mode start-ns result-map]
  (let [latency-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
        usage-atom (:usage result-map)
        usage (when usage-atom (provider/usage-summary usage-atom))]
    (cond-> {:ok true
             :mode mode
             :latency_ms latency-ms
             :usage usage}
      (contains? result-map :result) (assoc :result (str (unwrap-result-value (:result result-map))))
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

(defn- default-trace-dir []
  (let [fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH-mm-ss")]
    (str "traces/" (.format fmt (java.util.Date.)))))

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

(defn- run-spell [{:keys [prompt init agent budget depth trace trace-dir prefill
                          thinking reasoning-effort verbosity suffix-grammar
                          grammar-max-chars retries format] :as req}]
  (let [provider-inst (make-provider req)
        resolved-agent (or agent (default-agent-from-request req))
        normalized-format (normalize-format-spec format)
        resolved-trace-dir (when trace (or trace-dir (default-trace-dir)))
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
                                     :trace-dir resolved-trace-dir
                                     :retries retries
                                     :thinking thinking
                                     :reasoning-effort reasoning-effort
                                     :verbosity verbosity
                                     :prefill? effective-prefill
                                     :suffix-grammar? suffix-grammar
                                     :format normalized-format}
                              grammar-max-chars (assoc :grammar-max-chars grammar-max-chars)
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
           :trace_dir (or (:trace-dir result) resolved-trace-dir)}
          (response-ok "spell" start-ns result)))
      (catch Exception e
        (cond-> (response-error "spell" start-ns e)
          resolved-trace-dir (assoc :trace_dir resolved-trace-dir))))))

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
