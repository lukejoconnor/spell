(ns spell.benchmark-api
  "Machine-oriented JSON API for benchmark runners.
   Accepts one request object and returns one response object."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [spell.api :as api]
            [spell.model-spec :as model-spec]
            [spell.provider :as provider]
            [spell.runtime :as runtime]
            [spell.trace :as trace])
  (:import [java.util.concurrent TimeUnit])
  (:gen-class))

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
  (model-spec/parse-model-spec s))

(def model-profile-edn-by-prefix
  {"anthropic-pf"  "config/model-profiles/anthropic-pf.edn"
   "anthropic-tc"  "config/model-profiles/anthropic-tc.edn"
   "codex-tc"      "config/model-profiles/codex-tc.edn"
   "fireworks"     "config/model-profiles/fireworks.edn"
   "fireworks-tc"  "config/model-profiles/fireworks-tc.edn"
   "openai-tc"     "config/model-profiles/openai-tc.edn"
   "ollama"        "config/model-profiles/ollama.edn"})

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
   Plain strings become keywords (Clojure convention). Strings prefixed with
   \"'\" become symbols for the rare case when a symbol key is actually wanted.
   A leading ':' is accepted as an explicit keyword marker for backward compat."
  [k]
  (cond
    (or (keyword? k) (symbol? k)) k
    (string? k) (cond
                  (str/starts-with? k "'") (symbol (subs k 1))
                  (str/starts-with? k ":") (keyword (subs k 1))
                  :else (keyword k))
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
        {:keys [provider model]} (model-spec/resolve-model-spec model-spec)
        resolved-model model
        base-opts (cond-> {:costs provider/default-costs}
                    resolved-model (assoc :model resolved-model)
                    max-tokens (assoc :max-tokens max-tokens))]
    (case provider
      "ollama"
      (provider/ollama-provider base-opts)

      "codex-tc"
      (provider/codex-tc-provider base-opts)

      "openai-tc"
      (provider/openai-provider (assoc base-opts
                                       :use-responses-api true
                                       :force-tool-call true))

      "fireworks"
      (provider/fireworks-provider base-opts)

      "fireworks-tc"
      (provider/fireworks-tc-provider base-opts)

      "test"
      (provider/test-provider {:response "\"hello world\""})

      ("anthropic-pf" nil)
      (provider/anthropic-pf-provider base-opts)

      "anthropic-tc"
      (provider/anthropic-tc-provider base-opts)

      (throw (ex-info (str "Unknown provider prefix: " provider)
                      {:provider provider :model-spec model-spec})))))

(defn- default-agent-profile-from-request
  "Resolve default agent profile path from model profile for this request."
  [{:keys [model responses-api]}]
  (when-not model
    (throw (ex-info "model is required to resolve default agent profile" {:field "model"})))
  (let [{:keys [provider]} (model-spec/resolve-model-spec model)
        provider-prefix (or provider "anthropic-pf")
        profile-edn (get model-profile-edn-by-prefix provider-prefix)]
    (or (when profile-edn
          (provider/model-profile-default-agent-profile profile-edn))
        ;; Test mode doesn't have a provider file; use message transport base.
        (when (= provider-prefix "test")
          "config/agent-profiles/base-msg.agent.edn"))))

(defn- response-ok [mode start-ns result-map]
  (let [latency-ms (/ (double (- (System/nanoTime) start-ns)) 1000000.0)
        usage-atom (or (:usage-tracker result-map) (:usage result-map))
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

(defn- run-one-shot [{:keys [prompt reasoning-effort verbosity thinking budget retries] :as req}]
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
          (response-ok "one_shot" start-ns {:result text :usage usage-atom})))
      (catch Exception e
        (response-error "one_shot" start-ns e)))))

(defn- run-spell [{:keys [prompt init agent-profile budget depth trace trace-dir prefill
                          thinking reasoning-effort verbosity suffix-grammar
                          grammar-max-chars retries format] :as req}]
  (let [provider-inst (make-provider req)
        resolved-agent-profile (or agent-profile (default-agent-profile-from-request req))
        normalized-format (normalize-format-spec format)
        resolved-trace-dir (when trace (or trace-dir (trace/default-trace-dir)))
        effective-prefill (if (contains? req :prefill)
                            prefill
                            (and (provider/supports-prefill provider-inst)
                                 (not thinking)))
        start-ns (System/nanoTime)]
    (try
      (let [result (api/run-internal (cond-> {:model-profile provider-inst
                                              :agent-profile resolved-agent-profile
                                              :budget budget
                                              :depth depth
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
           :usage (when-let [u (:usage-tracker result)] (provider/usage-summary u))
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

      (= effective-mode "one_shot")
      (if (nil? prompt)
        {:ok false :mode effective-mode :error "one_shot mode requires prompt" :error_type "invalid_request"}
        (run-one-shot req))

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
