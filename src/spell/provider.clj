(ns spell.provider
  "LLM provider abstraction and token tracking.

   Providers implement the LLMProvider protocol. Built-in providers:
   - anthropic-pf-provider: Calls Claude API (prefill transport)
   - anthropic-tc-provider: Anthropic Messages API with mandatory spell_suffix tool output
   - openai-provider: Calls OpenAI API
   - codex-tc-provider: ChatGPT Codex Responses with mandatory custom tool output
   - fireworks-provider: Calls Fireworks Completions API with raw prompt templates
   - ollama-provider: Calls local Ollama API
   - test-provider: Declarative test provider with flexible response matching"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol LLMProvider
  "Protocol for LLM API providers."
  (call-llm [this prompt] [this prompt opts]
    "Send prompt to LLM, return response string.
     prompt is a string. opts may include :system for system prompt.
     Returns the assistant's text response.")
  (plain-text-provider [this]
    "Return the provider instance that should back leaf-llm.
     Must be a genuine plain-text/no-tools transport.")
  (supports-prefill [this]
    "Returns true if this provider supports assistant prefill."))

;; ---------------------------------------------------------------------------
;; Token usage tracking
;; ---------------------------------------------------------------------------

(def ^:dynamic *usage*
  "When bound to an atom, accumulates token usage from API calls.
   Atom value is a map: {:records [{:model \"model\" :usage {...} :cost F}]
                         :by-model {\"model\" {:uncached_input_tokens N
                                               :cached_input_tokens N
                                               :cache_write_input_tokens N
                                               :visible_output_tokens N
                                               :reasoning_output_tokens N
                                               :calls N}}}"
  nil)

(def ^:dynamic *budget*
  "When set to a number, throws if cumulative cost exceeds this amount (in dollars).
   Default $1.00. Override with -b flag or by binding directly."
  1.00)

(def ^:dynamic *retries*
  "Vector of sleep durations (seconds) for API-level retries on transient failures.
   Each element is one retry attempt; the value is how long to sleep before retrying.
   Default [0 10] = instant retry, then retry after 10s. nil or [] = no retries."
  [0 10])

(defn- repo-root []
  (if-let [resource (io/resource "spell/provider.clj")]
    (-> resource .toURI java.io.File. .getParentFile .getParentFile .getParentFile)
    (io/file (System/getProperty "user.dir"))))

(defn- load-shared-costs []
  (-> (io/file (repo-root) "data" "pricing.edn")
      slurp
      edn/read-string))

(def default-costs
  "Shared pricing table loaded from data/pricing.edn."
  (load-shared-costs))

(defn- normalize-cost-spec
  [costs cache-read-ratio]
  (cond
    (and (vector? costs) (= 2 (count costs)))
    (let [[input-cost output-cost] costs]
      {:input input-cost
       :output output-cost
       :cache-write-input (* input-cost 1.25)
       :cache-read-input (* input-cost cache-read-ratio)})

    (map? costs)
    (let [input-cost (or (:input costs) (get costs "input"))
          output-cost (or (:output costs) (get costs "output"))
          cache-write (or (:cache-write-input costs) (get costs "cache-write-input"))
          cache-read (or (:cache-read-input costs) (get costs "cache-read-input"))]
      (when (and input-cost output-cost)
        {:input input-cost
         :output output-cost
         :cache-write-input (or cache-write (* input-cost 1.25))
         :cache-read-input (or cache-read (* input-cost cache-read-ratio))}))

    :else nil))

(defn- canonical-usage
  "Normalize legacy provider usage keys into the canonical 5-field record."
  [usage]
  (let [reasoning (or (:reasoning_output_tokens usage)
                      (:reasoning_tokens usage)
                      0)
        raw-output (or (:visible_output_tokens usage)
                       (:output_tokens usage)
                       0)
        visible-output (if (:visible_output_tokens usage)
                         raw-output
                         (max 0 (- raw-output reasoning)))]
    {:uncached_input_tokens (or (:uncached_input_tokens usage)
                                (:input_tokens usage)
                                0)
     :cached_input_tokens (or (:cached_input_tokens usage)
                              (:cache_read_input_tokens usage)
                              0)
     :cache_write_input_tokens (or (:cache_write_input_tokens usage)
                                   (:cache_creation_input_tokens usage)
                                   0)
     :visible_output_tokens visible-output
     :reasoning_output_tokens reasoning}))

(defn- canonical-stats [stats]
  (merge (canonical-usage stats)
         (select-keys stats [:calls :max_total_tokens :cost])))

(defn- with-legacy-usage-keys [usage]
  (cond-> (assoc usage
                 :input_tokens (:uncached_input_tokens usage 0)
                 :output_tokens (:visible_output_tokens usage 0)
                 :cache_creation_input_tokens (:cache_write_input_tokens usage 0)
                 :cache_read_input_tokens (:cached_input_tokens usage 0))
    (pos? (:reasoning_output_tokens usage 0))
    (assoc :reasoning_tokens (:reasoning_output_tokens usage 0))))

(defn- usage-total-tokens [usage]
  (+ (:uncached_input_tokens usage 0)
     (:cached_input_tokens usage 0)
     (:cache_write_input_tokens usage 0)
     (:visible_output_tokens usage 0)
     (:reasoning_output_tokens usage 0)))

(defn- lookup-cost
  "Find cost for a model ID by longest-prefix matching in a cost table."
  [model-id cost-table]
  (let [cache-read-ratio (or (:cache-read-ratio cost-table) 0.10)]
    (when-let [[_ costs]
                 (reduce (fn [best [prefix costs]]
                           (if (and (string? prefix)
                                    (.startsWith ^String model-id prefix)
                                    (or (nil? best)
                                        (> (count prefix) (count (first best)))))
                             [prefix costs]
                             best))
                         nil
                         cost-table)]
      (normalize-cost-spec costs cache-read-ratio))))

(defn- usage-cost
  "Compute the dollar cost for one usage record from a cost table."
  [model usage cost-table]
  (if-let [{:keys [input output cache-write-input cache-read-input]}
           (lookup-cost model (or cost-table default-costs))]
    (let [base-input (* (:uncached_input_tokens usage 0) (/ input 1000000.0))
          cache-write (* (:cache_write_input_tokens usage 0)
                         (/ cache-write-input 1000000.0))
          cache-read (* (:cached_input_tokens usage 0)
                        (/ cache-read-input 1000000.0))
          output (* (+ (:visible_output_tokens usage 0)
                       (:reasoning_output_tokens usage 0))
                    (/ output 1000000.0))]
      (+ base-input cache-write cache-read output))
    nil))

(defn- bucket-cost
  "Return the stored cost for a bucket, or derive it from pricing when the bucket predates frozen costs."
  [model stats cost-table]
  (if (contains? stats :cost)
    (:cost stats)
    (usage-cost model stats cost-table)))

(defn current-cost
  "Compute total cost in dollars from accumulated usage data.
   Returns nil if no models have known pricing.
   Expects per-call costs to already be frozen into each model bucket."
  [usage-atom]
  (let [{:keys [by-model cost-table]} @usage-atom
        costs (keep (fn [[model stats]]
                      (bucket-cost model stats cost-table))
                    by-model)]
    (when (seq costs)
      (reduce + 0.0 costs))))

(defn track-usage!
  "Add usage data to the *usage* atom if bound.
   Optional cost-table is used to freeze the per-call cost at tracking time.
   Throws ex-info with {:type :budget-exceeded} if *budget* is set and cumulative cost exceeds it."
  ([model usage] (track-usage! model usage nil))
  ([model usage cost-table]
   (when (and *usage* usage)
     (let [usage (canonical-usage usage)
           turn-total-tokens (usage-total-tokens usage)
           turn-cost (usage-cost model usage cost-table)]
       (swap! *usage*
              (fn [u]
                (let [existing (canonical-stats (get-in u [:by-model model] {}))
                      merged-stats {:uncached_input_tokens (+ (:uncached_input_tokens existing 0)
                                                              (:uncached_input_tokens usage 0))
                                    :cached_input_tokens (+ (:cached_input_tokens existing 0)
                                                            (:cached_input_tokens usage 0))
                                    :cache_write_input_tokens (+ (:cache_write_input_tokens existing 0)
                                                                 (:cache_write_input_tokens usage 0))
                                    :visible_output_tokens (+ (:visible_output_tokens existing 0)
                                                              (:visible_output_tokens usage 0))
                                    :reasoning_output_tokens (+ (:reasoning_output_tokens existing 0)
                                                                (:reasoning_output_tokens usage 0))
                                    :calls (inc (:calls existing 0))
                                    :max_total_tokens (max (:max_total_tokens existing 0)
                                                           turn-total-tokens)}
                      merged-cost (cond
                                    (contains? existing :cost)
                                    (let [existing-cost (:cost existing)]
                                      (when (number? existing-cost)
                                        (+ existing-cost (or turn-cost 0.0))))

                                    :else
                                    (usage-cost model merged-stats (or cost-table (:cost-table u))))]
                  (cond-> (-> u
                              (update :records (fnil conj [])
                                      {:model model :usage usage :cost turn-cost})
                              (assoc-in [:by-model model]
                                        (assoc (with-legacy-usage-keys merged-stats) :cost merged-cost)))
                    (and cost-table (nil? (:cost-table u)))
                    (assoc :cost-table cost-table))))))
     (when *budget*
       (when-let [cost (current-cost *usage*)]
         (when (> cost *budget*)
           (throw (ex-info (format "Budget exceeded: $%.4f spent (limit $%.4f)" cost *budget*)
                           {:type :budget-exceeded :cost cost :budget *budget*}))))))))

(defn usage-summary
  "Compute a summary from accumulated usage data.
   Returns {:by-model {model {:uncached_input_tokens N :cached_input_tokens N
                              :cache_write_input_tokens N :visible_output_tokens N
                              :reasoning_output_tokens N :calls N :cost F
                              :mean_total_tokens F :max_total_tokens N
                              }}
            :total {:uncached_input_tokens N :cached_input_tokens N
                    :cache_write_input_tokens N :visible_output_tokens N
                    :reasoning_output_tokens N :calls N :cost F
                    :mean_total_tokens F :max_total_tokens N
                    }}"
  [usage-atom]
  (let [{:keys [by-model cost-table]} @usage-atom
        canonical-by-model (into {}
                                (map (fn [[model stats]]
                                       [model (canonical-stats stats)]))
                                by-model)
        summarize-context (fn [stats]
                            (let [input-tokens (:uncached_input_tokens stats 0)
                                  cached-input-tokens (:cached_input_tokens stats 0)
                                  cache-write-tokens (:cache_write_input_tokens stats 0)
                                  visible-output-tokens (:visible_output_tokens stats 0)
                                  reasoning-output-tokens (:reasoning_output_tokens stats 0)
                                  calls (:calls stats 0)
                                  total-tokens (+ input-tokens
                                                  cached-input-tokens
                                                  cache-write-tokens
                                                  visible-output-tokens
                                                  reasoning-output-tokens)]
                              (cond-> stats
                                (pos? calls)
                                (assoc :mean_total_tokens (double (/ total-tokens calls))
                                       :max_total_tokens (:max_total_tokens stats 0)))))
        with-costs (into {}
                     (map (fn [[model stats]]
                            [model (assoc (with-legacy-usage-keys (summarize-context stats))
                                          :cost (bucket-cost model stats cost-table))])
                          canonical-by-model))
        reasoning-total (reduce + 0 (map #(:reasoning_output_tokens % 0) (vals canonical-by-model)))
        total-uncached-input (reduce + 0 (map #(:uncached_input_tokens % 0) (vals canonical-by-model)))
        total-cached-input (reduce + 0 (map #(:cached_input_tokens % 0) (vals canonical-by-model)))
        total-cache-write (reduce + 0 (map #(:cache_write_input_tokens % 0) (vals canonical-by-model)))
        total-visible-output (reduce + 0 (map #(:visible_output_tokens % 0) (vals canonical-by-model)))
        total-calls (reduce + 0 (map #(:calls % 0) (vals canonical-by-model)))
        total (cond-> {:uncached_input_tokens total-uncached-input
                       :cached_input_tokens total-cached-input
                       :cache_write_input_tokens total-cache-write
                       :visible_output_tokens total-visible-output
                       :reasoning_output_tokens reasoning-total
                       :calls total-calls
                       :cost (let [costs (keep :cost (vals with-costs))]
                               (when (seq costs) (reduce + 0.0 costs)))}
                (pos? total-calls) (assoc :mean_total_tokens (double (/ (+ total-uncached-input
                                                                          total-cached-input
                                                                          total-cache-write
                                                                          total-visible-output
                                                                          reasoning-total)
                                                                       total-calls))
                                          :max_total_tokens (reduce max 0 (map #(:max_total_tokens % 0)
                                                                               (vals with-costs))))
                (zero? reasoning-total) (dissoc :reasoning_output_tokens))
        records (:records @usage-atom)]
    (cond-> {:by-model with-costs :total (with-legacy-usage-keys total)}
      (seq records) (assoc :per-call
                           (mapv (fn [{:keys [model usage cost]}]
                                   (let [u (canonical-usage usage)]
                                     (cond-> {:model model
                                              :uncached_input_tokens (:uncached_input_tokens u 0)
                                              :cached_input_tokens (:cached_input_tokens u 0)
                                              :cache_write_input_tokens (:cache_write_input_tokens u 0)
                                              :visible_output_tokens (:visible_output_tokens u 0)
                                              :reasoning_output_tokens (:reasoning_output_tokens u 0)}
                                       cost (assoc :cost cost))))
                                 records)))))

;; ---------------------------------------------------------------------------
;; Anthropic Provider
;; ---------------------------------------------------------------------------

(defn- make-http-client
  ([] (make-http-client nil))
  ([{:keys [http-version]}]
   (let [builder (HttpClient/newBuilder)]
     (when http-version
       (.version builder http-version))
     (.build builder))))

(defn- cache-min-chars
  "Minimum character count for caching to be worthwhile on a given model.
   Based on Anthropic's minimum cacheable token thresholds (~4 chars/token):
   - Opus 4.x, Sonnet, Haiku 4.5: 1024 tokens (~4K chars)
   - Haiku 3.x: 2048 tokens (~8K chars)"
  [model]
  (cond
    (or (str/includes? model "haiku-3")
        (str/includes? model "haiku-3-5")) 8000
    :else 4000))

(defn- common-prefix-length
  [a b]
  (let [limit (min (count a) (count b))]
    (loop [idx 0]
      (if (and (< idx limit)
               (= (.charAt ^String a idx) (.charAt ^String b idx)))
        (recur (inc idx))
        idx))))

(defn- anthropic-cacheable-user-content
  [model prompt cache-prefix]
  (let [min-chars (cache-min-chars model)]
    (if (and cache-prefix
             (not (str/blank? cache-prefix)))
      (let [shared-length (common-prefix-length prompt cache-prefix)]
        (if (>= shared-length min-chars)
          (let [shared (subs prompt 0 shared-length)
                tail (subs prompt shared-length)]
            (if (str/blank? tail)
              [{:type "text" :text shared :cache_control {:type "ephemeral"}}]
              [{:type "text" :text shared :cache_control {:type "ephemeral"}}
               {:type "text" :text tail :cache_control {:type "ephemeral"}}]))
          prompt))
      prompt)))

(declare anthropic-adaptive-thinking-model?
         anthropic-output-effort
         anthropic-thinking-enabled?)

(defn- anthropic-pf-request
  [api-key model prompt system-prompt prefix max-tokens stream? thinking reasoning-effort cache-prefix]
  (let [adaptive-only? (anthropic-adaptive-thinking-model? model)
        output-effort (anthropic-output-effort reasoning-effort)
        thinking-enabled? (anthropic-thinking-enabled? model thinking reasoning-effort)
        ;; When thinking is active, don't use assistant prefill (incompatible)
        effective-prefix (when-not thinking-enabled? prefix)
        ;; Only apply cache_control when the shared user-content prefix exceeds
        ;; the model's minimum cacheable threshold.
        min-chars (cache-min-chars model)
        user-content (anthropic-cacheable-user-content model prompt cache-prefix)
        messages (cond-> [{:role "user" :content user-content}]
                   effective-prefix (conj {:role "assistant" :content (str/trimr effective-prefix)}))
        ;; Use cache_control for system prompt when it exceeds model's minimum threshold
        cached-system (when system-prompt
                        [(cond-> {:type "text" :text system-prompt}
                           (>= (count system-prompt) min-chars)
                           (assoc :cache_control {:type "ephemeral"}))])
        body (cond-> {:model model
                      :max_tokens (if thinking-enabled?
                                    (or max-tokens 32768)
                                    (or max-tokens 16384))
                      :messages messages}
               cached-system (assoc :system cached-system)
               stream? (assoc :stream true)
               (and adaptive-only? thinking-enabled?) (assoc :thinking {:type "adaptive"})
               (and (not adaptive-only?) thinking-enabled?)
               (assoc :thinking (if (number? thinking)
                                  {:type "enabled" :budget_tokens thinking}
                                  {:type "enabled" :budget_tokens 10000}))
               (and adaptive-only? output-effort)
               (assoc :output_config {:effort output-effort}))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "https://api.anthropic.com/v1/messages"))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" api-key)
                    (.header "anthropic-version" "2023-06-01")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- parse-anthropic-pf-response [response-body]
  (let [parsed (json/read-str response-body :key-fn keyword)]
    (if-let [error (:error parsed)]
      (throw (ex-info "Anthropic API error" {:error error}))
      (let [usage (:usage parsed)]
        {:text (->> (:content parsed)
                    (filter #(= (:type %) "text"))
                    (map :text)
                    (clojure.string/join "\n"))
         :usage (with-legacy-usage-keys
                  {:uncached_input_tokens (:input_tokens usage 0)
                   :visible_output_tokens (:output_tokens usage 0)
                   :cache_write_input_tokens (:cache_creation_input_tokens usage 0)
                   :cached_input_tokens (:cache_read_input_tokens usage 0)})}))))

(defn- parse-anthropic-pf-stream
  "Parse an Anthropic SSE stream, accumulating text and usage."
  [response-body]
  (let [text (StringBuilder.)
        usage (atom {:uncached_input_tokens 0 :visible_output_tokens 0
                     :cache_write_input_tokens 0 :cached_input_tokens 0})]
    (doseq [line (str/split-lines response-body)]
      (when (str/starts-with? line "data: ")
        (let [data (subs line 6)]
          (when (not= data "[DONE]")
            (try
              (let [parsed (json/read-str data :key-fn keyword)]
                (case (:type parsed)
                  "message_start"
                  (let [u (get-in parsed [:message :usage])]
                    (swap! usage merge
                           {:uncached_input_tokens (:input_tokens u 0)
                            :cache_write_input_tokens (:cache_creation_input_tokens u 0)
                            :cached_input_tokens (:cache_read_input_tokens u 0)}))

                  "content_block_delta"
                  (when-let [t (get-in parsed [:delta :text])]
                    (.append text t))

                  "message_delta"
                  (when-let [u (:usage parsed)]
                    (swap! usage assoc :visible_output_tokens (:output_tokens u 0)))

                  nil)) ; ignore other event types
              (catch Exception _ nil))))))
    {:text (.toString text) :usage (with-legacy-usage-keys @usage)}))

(defn- anthropic-adaptive-thinking-model?
  [model]
  (str/includes? (str model) "opus-4-7"))

(defn- anthropic-output-effort
  [reasoning-effort]
  (when (and reasoning-effort
             (not= reasoning-effort "none"))
    reasoning-effort))

(defn- anthropic-thinking-enabled?
  [model thinking reasoning-effort]
  (or thinking
      (and (anthropic-adaptive-thinking-model? model)
           (anthropic-output-effort reasoning-effort))))

(defrecord AnthropicPfProvider [api-key model max-tokens http-client costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          thinking (:thinking opts)
          reasoning-effort (:reasoning-effort opts)
          thinking-enabled? (anthropic-thinking-enabled? effective-model thinking reasoning-effort)
          effective-max-tokens (or max-tokens (if thinking-enabled? 32768 16384))
          ;; Use streaming for large max_tokens (API requires it for >16384) or thinking
          stream? (or thinking-enabled? (> effective-max-tokens 16384))
          cache-prefix (:cache-prefix opts)
          request (anthropic-pf-request api-key effective-model prompt (:system opts) (:prefix opts)
                                       effective-max-tokens stream? thinking reasoning-effort cache-prefix)
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (if stream?
                                     (parse-anthropic-pf-stream (.body response))
                                     (parse-anthropic-pf-response (.body response)))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "Anthropic API request failed"
                        {:status status :body (.body response)})))))
  (plain-text-provider [this] this)
  (supports-prefill [_]
    ;; Opus 4.6+ does not support assistant prefill (returns 400 error)
    (not (or (str/includes? (str model) "opus-4-6")
             (str/includes? (str model) "opus-4-7")))))

(defn anthropic-pf-provider
  "Create an Anthropic provider.

   Options:
   - :api-key - API key (default: ANTHROPIC_API_KEY env var)
   - :model - Model name (default: claude-sonnet-4-20250514)
   - :max-tokens - Max tokens per response (default: 16384)
   - :costs - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (anthropic-pf-provider {}))
  ([{:keys [api-key model max-tokens costs]
     :or {model "claude-sonnet-4-5-20250929"}}]
   (let [key (or api-key (System/getenv "ANTHROPIC_API_KEY"))]
     (when-not key
       (throw (ex-info "No API key provided. Set ANTHROPIC_API_KEY or pass :api-key"
                       {:env "ANTHROPIC_API_KEY"})))
     (->AnthropicPfProvider key model max-tokens (make-http-client) costs))))

(defn- anthropic-tc-request
  [api-key model prompt system-prompt max-tokens stream? thinking reasoning-effort cache-prefix]
  (let [min-chars (cache-min-chars model)
        adaptive-only? (anthropic-adaptive-thinking-model? model)
        output-effort (anthropic-output-effort reasoning-effort)
        thinking-enabled? (anthropic-thinking-enabled? model thinking reasoning-effort)
        ;; Only apply cache_control when the shared user-content prefix exceeds
        ;; the model's minimum cacheable threshold.
        user-content (anthropic-cacheable-user-content model prompt cache-prefix)
        cached-system (when system-prompt
                        [(cond-> {:type "text" :text system-prompt}
                           (>= (count system-prompt) min-chars)
                           (assoc :cache_control {:type "ephemeral"}))])
        body (cond-> {:model model
                      :max_tokens (if thinking-enabled?
                                    (or max-tokens 32768)
                                    (or max-tokens 16384))
                      :messages [{:role "user" :content user-content}]
                      :tools [{:name "spell_suffix"
                               :description "Return the full Spell suffix in input.suffix"
                               :input_schema {:type "object"
                                              :properties {:suffix {:type "string"}}
                                              :required ["suffix"]
                                              :additionalProperties false}}]
                      ;; thinking forbids forced tool use; use "auto" instead of "any"
                      :tool_choice {:type (if thinking-enabled? "auto" "any")}}
               cached-system (assoc :system cached-system)
               stream? (assoc :stream true)
               ;; Opus 4.7 requires adaptive thinking; budget_tokens is rejected.
               (and adaptive-only? thinking-enabled?) (assoc :thinking {:type "adaptive"})
               (and (not adaptive-only?) thinking-enabled?)
               (assoc :thinking (if (number? thinking)
                                  {:type "enabled" :budget_tokens thinking}
                                  {:type "enabled" :budget_tokens 10000}))
               (and adaptive-only? output-effort)
               (assoc :output_config {:effort output-effort}))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "https://api.anthropic.com/v1/messages"))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" api-key)
                    (.header "anthropic-version" "2023-06-01")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- tool-use->suffix
  "Extract Spell suffix text from an Anthropic tool_use block."
  [tool-use]
  (let [input (:input tool-use)
        suffix (cond
                 (string? input) input
                 (map? input) (:suffix input)
                 :else nil)]
    (when (string? suffix)
      suffix)))

(defn- parse-anthropic-tc-response
  [response-body]
  (let [parsed (json/read-str response-body :key-fn keyword)]
    (if-let [error (:error parsed)]
      (throw (ex-info "Anthropic API error" {:error error}))
      (let [usage (:usage parsed)
            tool-use (some (fn [block]
                             (when (and (= "tool_use" (:type block))
                                        (= "spell_suffix" (:name block)))
                               block))
                           (:content parsed))
            suffix (tool-use->suffix tool-use)]
        (when (nil? suffix)
          (throw (ex-info "Anthropic mandatory tool-call response missing spell_suffix tool_use"
                          {:type :missing-tool-call
                           :provider :anthropic-tc
                           :content (:content parsed)})))
        {:text suffix
         :usage (with-legacy-usage-keys
                  {:uncached_input_tokens (:input_tokens usage 0)
                   :visible_output_tokens (:output_tokens usage 0)
                   :cache_write_input_tokens (:cache_creation_input_tokens usage 0)
                   :cached_input_tokens (:cache_read_input_tokens usage 0)})}))))

(defn- parse-anthropic-tc-stream
  "Parse an Anthropic SSE stream for tool-call mode, extracting spell_suffix input."
  [response-body]
  (let [usage (atom {:uncached_input_tokens 0 :visible_output_tokens 0
                     :cache_write_input_tokens 0 :cached_input_tokens 0})
        tool-blocks (atom {})]
    (doseq [line (str/split-lines response-body)]
      (when (str/starts-with? line "data: ")
        (let [data (subs line 6)]
          (when (not= data "[DONE]")
            (try
              (let [parsed (json/read-str data :key-fn keyword)]
                (case (:type parsed)
                  "message_start"
                  (let [u (get-in parsed [:message :usage])]
                    (swap! usage merge
                           {:uncached_input_tokens (:input_tokens u 0)
                            :cache_write_input_tokens (:cache_creation_input_tokens u 0)
                            :cached_input_tokens (:cache_read_input_tokens u 0)}))

                  "content_block_start"
                  (let [idx (:index parsed)
                        block (:content_block parsed)]
                    (swap! tool-blocks assoc idx {:name (:name block)
                                                  :input (:input block)
                                                  :partial (StringBuilder.)}))

                  "content_block_delta"
                  (when (= "input_json_delta" (get-in parsed [:delta :type]))
                    (let [idx (:index parsed)
                          partial-json (get-in parsed [:delta :partial_json])]
                      (when (string? partial-json)
                        (when-let [sb (:partial (get @tool-blocks idx))]
                          (.append ^StringBuilder sb partial-json)))))

                  "message_delta"
                  (when-let [u (:usage parsed)]
                    (swap! usage assoc :visible_output_tokens (:output_tokens u 0)))

                  nil))
              (catch Exception _ nil))))))
    (let [tool-use (some (fn [[_ {:keys [name input partial]}]]
                           (when (= "spell_suffix" name)
                             (let [partial-json (when partial (.toString ^StringBuilder partial))
                                   parsed-input (when (and partial-json (not (str/blank? partial-json)))
                                                  (json/read-str partial-json :key-fn keyword))
                                   effective-input (if (and (map? input) (map? parsed-input))
                                                     (merge input parsed-input)
                                                     (or parsed-input input))]
                               {:input effective-input})))
                         @tool-blocks)
          suffix (tool-use->suffix tool-use)]
      (when (nil? suffix)
        (throw (ex-info "Anthropic mandatory tool-call stream missing spell_suffix tool_use"
                        {:type :missing-tool-call
                         :provider :anthropic-tc
                         :body (subs response-body 0 (min 1000 (count response-body)))})))
      {:text suffix :usage (with-legacy-usage-keys @usage)})))

(defrecord AnthropicTcProvider [api-key model max-tokens http-client costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          thinking (:thinking opts)
          reasoning-effort (:reasoning-effort opts)
          thinking-enabled? (anthropic-thinking-enabled? effective-model thinking reasoning-effort)
          effective-max-tokens (or max-tokens (if thinking-enabled? 32768 16384))
          ;; Use streaming for large max_tokens (API requires it for >16384) or thinking
          stream? (or thinking-enabled? (> effective-max-tokens 16384))
          cache-prefix (:cache-prefix opts)
          request (anthropic-tc-request api-key effective-model prompt (:system opts)
                                        effective-max-tokens stream? thinking
                                        reasoning-effort cache-prefix)
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (if stream?
                                     (parse-anthropic-tc-stream (.body response))
                                     (parse-anthropic-tc-response (.body response)))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "Anthropic mandatory tool-call request failed"
                        {:status status :body (.body response)})))))
  (plain-text-provider [_]
    (->AnthropicPfProvider api-key model max-tokens http-client costs))
  (supports-prefill [_] false))

(defn anthropic-tc-provider
  "Create an Anthropic provider with mandatory spell_suffix tool output.

   Options:
   - :api-key - API key (default: ANTHROPIC_API_KEY env var)
   - :model - Model name (default: claude-sonnet-4-5-20250929)
   - :max-tokens - Max tokens per response (default: 16384)
   - :costs - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (anthropic-tc-provider {}))
  ([{:keys [api-key model max-tokens costs]
     :or {model "claude-sonnet-4-5-20250929"}}]
   (let [key (or api-key (System/getenv "ANTHROPIC_API_KEY"))]
     (when-not key
       (throw (ex-info "No API key provided. Set ANTHROPIC_API_KEY or pass :api-key"
                       {:env "ANTHROPIC_API_KEY"})))
     (->AnthropicTcProvider key model max-tokens (make-http-client) costs))))

;; ---------------------------------------------------------------------------
;; Ollama Provider
;; ---------------------------------------------------------------------------

(defn- ollama-request [base-url model prompt system-prompt prefix]
  (let [messages (cond-> []
                   system-prompt (conj {:role "system" :content system-prompt})
                   true (conj {:role "user" :content prompt})
                   prefix (conj {:role "assistant" :content prefix}))
        body {:model model
              :messages messages
              :stream false}
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/api/chat")))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- parse-ollama-response [response-body]
  (let [parsed (json/read-str response-body :key-fn keyword)]
    (if-let [error (:error parsed)]
      (throw (ex-info "Ollama API error" {:error error}))
      {:text (get-in parsed [:message :content] "")
       :usage (with-legacy-usage-keys
                {:uncached_input_tokens (:prompt_eval_count parsed 0)
                 :visible_output_tokens (:eval_count parsed 0)})})))

(defrecord OllamaProvider [base-url model http-client costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (ollama-request base-url effective-model prompt (:system opts) (:prefix opts))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-ollama-response (.body response))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "Ollama API request failed"
                        {:status status :body (.body response)})))))
  (plain-text-provider [this] this)
  (supports-prefill [_] true))

(defn ollama-provider
  "Create an Ollama provider for local models.

   Options:
   - :base-url - Ollama API URL (default: OLLAMA_HOST env var or http://localhost:11434)
   - :model - Model name (default: llama3.2)
   - :costs - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (ollama-provider {}))
  ([{:keys [base-url model costs]
     :or {model "llama3.2"}}]
   (let [url (or base-url
                 (System/getenv "OLLAMA_HOST")
                 "http://localhost:11434")
         ;; Strip trailing slash if present
         url (str/replace url #"/$" "")]
     (->OllamaProvider url model (make-http-client) costs))))

;; ---------------------------------------------------------------------------
;; OpenAI Provider
;; ---------------------------------------------------------------------------

(defn- responses-model?
  "Does this model require the OpenAI Responses API instead of Chat Completions?"
  [model]
  (some #(str/includes? model %) ["codex"]))

(defn- parse-openai-responses-usage
  "Normalize OpenAI Responses-style usage, splitting cached tokens out of input."
  [usage]
  (let [cached-tokens (get-in usage [:input_tokens_details :cached_tokens] 0)
        reasoning-tokens (get-in usage [:output_tokens_details :reasoning_tokens] 0)
        output-tokens (:output_tokens usage 0)]
    (with-legacy-usage-keys
      {:uncached_input_tokens (max 0 (- (:input_tokens usage 0) cached-tokens))
       :cached_input_tokens cached-tokens
       :visible_output_tokens (max 0 (- output-tokens reasoning-tokens))
       :reasoning_output_tokens reasoning-tokens})))

(defn- openai-responses-request
  [api-key base-url model prompt system-prompt max-tokens reasoning-effort verbosity
   grammar-format force-tool-call prompt-cache-key request-timeout-sec]
  (let [reasoning (when reasoning-effort
                    {:effort reasoning-effort})
        force-tool-instructions
        (when force-tool-call
          (str "\n\nCustom tool call output mode:\n"
               "Return the full Spell suffix as the input of exactly one custom tool call named spell_suffix.\n"
               "Do not send assistant message text, markdown, or wrapper JSON."))
        instructions (cond
                       force-tool-call
                       (str (if (str/blank? system-prompt)
                              "You are a helpful assistant."
                              system-prompt)
                            force-tool-instructions)

                       (str/blank? system-prompt) nil
                       :else system-prompt)
        tool-mode? (or force-tool-call grammar-format)
        tool (cond-> {:type "custom"
                      :name "spell_suffix"
                      :description (if grammar-format
                                     "Spell suffix constrained by grammar"
                                     "Spell suffix emitted as custom tool input")}
               grammar-format (assoc :format grammar-format))
        body (cond-> {:model model
                      :input prompt}
               instructions (assoc :instructions instructions)
               max-tokens (assoc :max_output_tokens max-tokens)
               reasoning (assoc :reasoning reasoning)
               verbosity (assoc :verbosity verbosity)
               prompt-cache-key (assoc :prompt_cache_key prompt-cache-key)
               tool-mode? (assoc :tools [tool]
                                 :tool_choice "required"))
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/responses")))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body))))
        builder (if request-timeout-sec
                  (.timeout builder (Duration/ofSeconds (long request-timeout-sec)))
                  builder)]
    (.build builder)))

(defn- parse-openai-responses-response
  ([response-body] (parse-openai-responses-response response-body false))
  ([response-body require-tool-call]
   (let [parsed (json/read-str response-body :key-fn keyword)]
     (if-let [error (:error parsed)]
       (throw (ex-info "OpenAI Responses API error" {:error error}))
       (let [status (:status parsed)
             incomplete-details (:incomplete_details parsed)
             usage (parse-openai-responses-usage (:usage parsed))
             output-text (:output_text parsed "")
             message-text (->> (:output parsed)
                               (filter #(= "message" (:type %)))
                               (mapcat :content)
                               (filter #(= "output_text" (:type %)))
                               (map :text)
                               (str/join ""))
             tool-input (some (fn [item]
                                (when (and (= "custom_tool_call" (:type item))
                                           (= "spell_suffix" (:name item)))
                                  (:input item)))
                              (:output parsed))
             text (if require-tool-call
                    (do
                      (when (= "incomplete" status)
                        (throw (ex-info "OpenAI Responses API returned incomplete response"
                                        {:type :missing-tool-call
                                         :provider :openai-tc
                                         :status status
                                         :incomplete_details incomplete-details
                                         :output (:output parsed)
                                         :usage usage})))
                      (when-not tool-input
                        (throw (ex-info "OpenAI mandatory tool-call response missing custom_tool_call"
                                        {:type :missing-tool-call
                                         :provider :openai-tc
                                         :output (:output parsed)
                                         :usage usage})))
                      tool-input)
                    (or (not-empty output-text)
                        (not-empty message-text)
                        (not-empty tool-input)
                        ""))]
         {:text text
          :usage usage})))))

(defn- openai-request [api-key base-url model prompt system-prompt _prefix max-tokens reasoning-effort verbosity
                       prompt-cache-key request-timeout-sec]
  (let [messages (cond-> []
                   system-prompt (conj {:role "system" :content system-prompt})
                   true (conj {:role "user" :content prompt}))
        body (cond-> {:model model
                      :messages messages
                      :max_completion_tokens (or max-tokens 16384)}
               reasoning-effort (assoc :reasoning_effort reasoning-effort)
               verbosity (assoc :verbosity verbosity)
               prompt-cache-key (assoc :prompt_cache_key prompt-cache-key))
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/chat/completions")))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body))))
        builder (if request-timeout-sec
                  (.timeout builder (Duration/ofSeconds (long request-timeout-sec)))
                  builder)]
    (.build builder)))

(defn- parse-openai-response [response-body]
  (let [parsed (json/read-str response-body :key-fn keyword)]
    (if-let [error (:error parsed)]
      (throw (ex-info "OpenAI API error" {:error error}))
      (let [usage (:usage parsed)
            reasoning-tokens (get-in usage [:completion_tokens_details :reasoning_tokens] 0)
            output-tokens (:completion_tokens usage 0)]
        {:text (get-in parsed [:choices 0 :message :content] "")
         :usage (with-legacy-usage-keys
                  {:uncached_input_tokens (:prompt_tokens usage 0)
                   :visible_output_tokens (max 0 (- output-tokens reasoning-tokens))
                   :reasoning_output_tokens reasoning-tokens})}))))

(defrecord OpenAIProvider [api-key base-url model max-tokens http-client use-responses-api force-tool-call
                           prompt-cache-key request-timeout-sec costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          grammar-format (:grammar-format opts)
          responses? (or use-responses-api force-tool-call
                         (responses-model? effective-model) grammar-format)
          reasoning-effort (:reasoning-effort opts)
          verbosity (:verbosity opts)
          cache-prefix (:cache-prefix opts)
          effective-cache-key (when cache-prefix prompt-cache-key)
          request (if responses?
                    (openai-responses-request api-key base-url effective-model prompt (:system opts)
                                             max-tokens reasoning-effort verbosity
                                             grammar-format force-tool-call
                                             effective-cache-key request-timeout-sec)
                    (openai-request api-key base-url effective-model prompt (:system opts) (:prefix opts)
                                   max-tokens reasoning-effort verbosity
                                   effective-cache-key request-timeout-sec))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (if responses?
                                     (try
                                       (parse-openai-responses-response (.body response) force-tool-call)
                                       (catch clojure.lang.ExceptionInfo e
                                         (when-let [usage (:usage (ex-data e))]
                                           (track-usage! effective-model usage costs))
                                         (throw e)))
                                     (parse-openai-response (.body response)))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "OpenAI API request failed"
                        {:status status :body (.body response)})))))
  (plain-text-provider [this]
    (if force-tool-call
      (->OpenAIProvider api-key base-url model max-tokens http-client use-responses-api false
                        prompt-cache-key request-timeout-sec costs)
      this))
  (supports-prefill [_] false))

(defn openai-provider
  "Create an OpenAI provider.

   Options:
   - :api-key              - API key (default: OPENAI_API_KEY env var)
   - :base-url             - API base URL (default: https://api.openai.com/v1)
   - :model                - Model name (default: gpt-4o)
   - :max-tokens           - Max tokens per response (default: 16384)
   - :use-responses-api    - Force Responses API instead of Chat Completions (default: false)
   - :force-tool-call      - Require spell_suffix custom tool output via Responses API
   - :request-timeout-sec  - Per-HTTP-call timeout in seconds (default: 600). Protects
                             against OpenAI API hangs that would otherwise burn the
                             full harness budget on a single stalled call.
   - :prompt-cache-key     - Explicit prompt_cache_key value (default: random UUID
                             generated at provider construction, reused across calls
                             with :cache-prefix opt).
   - :costs                - Cost table {model-prefix [input-per-M output-per-M]}

   Call opts supported by this provider:
   - :grammar-format       - OpenAI custom-tool grammar format map
                             {:type \"grammar\" :syntax \"lark\" :definition \"...\"}
                             When present, request is routed to Responses API with
                             tool_choice \"required\" and custom_tool_call output parsing.
   - :cache-prefix         - When set (non-nil), the provider's prompt_cache_key is
                             sent with the request so repeated calls with the same
                             provider instance land on the same cache partition."
  ([] (openai-provider {}))
  ([{:keys [api-key base-url model max-tokens use-responses-api force-tool-call
            prompt-cache-key request-timeout-sec costs]
     :or {model "gpt-4o"
          base-url "https://api.openai.com/v1"
          request-timeout-sec 600}}]
   (let [key (or api-key (System/getenv "OPENAI_API_KEY"))
         url (str/replace (or base-url "https://api.openai.com/v1") #"/$" "")]
       (when-not key
       (throw (ex-info "No API key provided. Set OPENAI_API_KEY or pass :api-key"
                       {:env "OPENAI_API_KEY"})))
     (let [local? (or (str/starts-with? url "http://127.0.0.1")
                      (str/starts-with? url "http://localhost"))
           client (if local?
                    (make-http-client {:http-version HttpClient$Version/HTTP_1_1})
                    (make-http-client))
           cache-key (or prompt-cache-key (str (java.util.UUID/randomUUID)))]
       (->OpenAIProvider key url model max-tokens client use-responses-api
                         force-tool-call cache-key request-timeout-sec costs)))))

;; ---------------------------------------------------------------------------
;; ChatGPT Codex Provider (subscription-backed Responses API)
;; ---------------------------------------------------------------------------

(defn- expand-home [path]
  (if (and path (str/starts-with? path "~"))
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- load-chatgpt-auth
  "Load ChatGPT access token/account id from Codex auth.json."
  [auth-file]
  (let [path (expand-home auth-file)]
    (try
      (let [parsed (json/read-str (slurp path) :key-fn keyword)
            token (get-in parsed [:tokens :access_token])
            account-id (get-in parsed [:tokens :account_id])]
        (when (str/blank? token)
          (throw (ex-info "Missing tokens.access_token" {:path path})))
        {:token token :account-id account-id})
      (catch Exception e
        (throw (ex-info (str "Failed to load ChatGPT auth from " path)
                        {:path path}
                        e))))))

(defn- codex-msg-request
  [api-key account-id base-url model prompt system-prompt max-tokens reasoning-effort verbosity grammar-format]
  (let [reasoning (when reasoning-effort
                    {:effort reasoning-effort})
        text-controls (when (= verbosity "low")
                        {:verbosity "low"})
        tools (when grammar-format
                [{:type "custom"
                  :name "spell_suffix"
                  :description "Spell suffix constrained by grammar"
                  :format grammar-format}])
        body (cond-> {:model model
                      :instructions (if (str/blank? system-prompt)
                                      "You are a helpful assistant."
                                      system-prompt)
                      :input [{:type "message"
                               :role "user"
                               :content [{:type "input_text"
                                          :text prompt}]}]
                      :tools (or tools [])
                      :tool_choice (if tools "required" "auto")
                      :parallel_tool_calls true
                      :store false
                      :stream true
                      :include []}
               reasoning (assoc :reasoning reasoning)
               text-controls (assoc :text text-controls))
        request-builder (cond-> (-> (HttpRequest/newBuilder)
                                    (.uri (URI/create (str base-url "/responses")))
                                    (.header "Content-Type" "application/json")
                                    (.header "Accept" "text/event-stream")
                                    (.header "Authorization" (str "Bearer " api-key)))
                          (not (str/blank? account-id))
                          (.header "ChatGPT-Account-ID" account-id))
        request (-> request-builder
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- codex-tc-request-body
  [model prompt system-prompt prompt-cache-key reasoning-effort verbosity grammar-format]
  (let [reasoning (when reasoning-effort
                    {:effort reasoning-effort})
        text-controls (when (= verbosity "low")
                        {:verbosity "low"})
        instructions (str (if (str/blank? system-prompt)
                            "You are a helpful assistant."
                            system-prompt)
                          "\n\nCustom tool call output mode:\n"
                          "Return the full Spell suffix as the input of exactly one custom tool call named spell_suffix.\n"
                          "Do not send assistant message text, markdown, or wrapper JSON.")
        tool (cond-> {:type "custom"
                      :name "spell_suffix"
                      :description "Spell suffix emitted as custom tool input"}
               grammar-format (assoc :format grammar-format))
        body (cond-> {:model model
                      :instructions instructions
                      :input [{:type "message"
                               :role "user"
                               :content [{:type "input_text"
                                          :text prompt}]}]
                      :tools [tool]
                      :tool_choice "required"
                      :parallel_tool_calls true
                      :store false
                      :stream true
                      :include []}
               prompt-cache-key (assoc :prompt_cache_key prompt-cache-key)
               reasoning (assoc :reasoning reasoning)
               text-controls (assoc :text text-controls))]
    body))

(defn- codex-tc-request
  [api-key account-id base-url model prompt system-prompt prompt-cache-key max-tokens reasoning-effort verbosity grammar-format]
  (let [body (codex-tc-request-body model prompt system-prompt prompt-cache-key
                                    reasoning-effort verbosity grammar-format)
        request-builder (cond-> (-> (HttpRequest/newBuilder)
                                    (.uri (URI/create (str base-url "/responses")))
                                    (.header "Content-Type" "application/json")
                                    (.header "Accept" "text/event-stream")
                                    (.header "Authorization" (str "Bearer " api-key)))
                          (not (str/blank? account-id))
                          (.header "ChatGPT-Account-ID" account-id))
        request (-> request-builder
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- parse-codex-msg-stream
  "Parse ChatGPT Codex Responses SSE stream, returning {:text :usage}."
  [response-body]
  (let [failed (atom nil)
        completed (atom nil)]
    (doseq [line (str/split-lines response-body)]
      (when (str/starts-with? line "data: ")
        (let [data (subs line 6)]
          (when (and (not (str/blank? data))
                     (not= data "[DONE]"))
            (try
              (let [parsed (json/read-str data :key-fn keyword)]
                (case (:type parsed)
                  "response.failed"
                  (reset! failed (or (get-in parsed [:response :error]) (:error parsed)))

                  "response.completed"
                  (reset! completed (:response parsed))

                  nil))
              (catch Exception _ nil))))))
    (when @failed
      (throw (ex-info "ChatGPT Codex Responses API error" {:error @failed})))
    (when-not @completed
      (throw (ex-info "ChatGPT Codex Responses stream missing response.completed"
                      {:body (subs response-body 0 (min 1000 (count response-body)))})))
    (parse-openai-responses-response (json/write-str @completed))))

(defn- codex-custom-tool-input
  [items]
  (some (fn [item]
          (when (and (= "custom_tool_call" (:type item))
                     (= "spell_suffix" (:name item)))
            (:input item)))
        items))

(defn- parse-codex-tc-response
  "Parse a completed ChatGPT Codex response.
   Prefer custom_tool_call input; ignore assistant message text when no tool call is present."
  ([completed] (parse-codex-tc-response completed nil))
  ([completed stream-tool-input]
   (let [usage (:usage completed)
         tool-input (or stream-tool-input
                        (codex-custom-tool-input (:output completed)))
         reasoning-tokens (get-in usage [:output_tokens_details :reasoning_tokens])]
     (when-not tool-input
       (throw (ex-info "Codex toolcall response missing custom_tool_call"
                       {:type :missing-tool-call
                        :provider :codex-tc
                        :output (:output completed)})))
     {:text tool-input
      :usage (cond-> (parse-openai-responses-usage usage)
               reasoning-tokens (assoc :reasoning_tokens reasoning-tokens))})))

(defn- merge-codex-stream-input
  [current fragment]
  (cond
    (str/blank? fragment) current
    (nil? current) fragment
    (str/starts-with? fragment current) fragment
    (str/starts-with? current fragment) current
    :else (str current fragment)))


(defn- parse-codex-tc-stream
  "Parse ChatGPT Codex Responses SSE stream for tool-call mode.
   Throws if no custom_tool_call is present (protocol violation)."
  [response-body]
  (let [failed (atom nil)
        completed (atom nil)
        tool-items (atom {})
        partial-inputs (atom {})]
    (doseq [line (str/split-lines response-body)]
      (when (str/starts-with? line "data: ")
        (let [data (subs line 6)]
          (when (and (not (str/blank? data))
                     (not= data "[DONE]"))
            (try
              (let [parsed (json/read-str data :key-fn keyword)]
                (case (:type parsed)
                  "response.failed"
                  (reset! failed (or (get-in parsed [:response :error]) (:error parsed)))

                  "response.completed"
                  (reset! completed (:response parsed))

                  "response.output_item.added"
                  (when-let [item (:item parsed)]
                    (when (and (= "custom_tool_call" (:type item))
                               (= "spell_suffix" (:name item)))
                      (swap! tool-items assoc (:id item) item)))

                  "response.output_item.done"
                  (when-let [item (:item parsed)]
                    (when (and (= "custom_tool_call" (:type item))
                               (= "spell_suffix" (:name item)))
                      (swap! tool-items assoc (:id item) item)))

                  "response.custom_tool_call_input.delta"
                  (let [item-id (:item_id parsed)
                        delta (:delta parsed)]
                    (when (and (string? item-id) (string? delta))
                      (swap! partial-inputs update item-id merge-codex-stream-input delta)))

                  "response.custom_tool_call_input.done"
                  (let [item-id (:item_id parsed)
                        input (or (:input parsed)
                                  (:arguments parsed)
                                  (get-in parsed [:item :input])
                                  (get-in parsed [:item :arguments])
                                  (:delta parsed))]
                    (when (and (string? item-id) (string? input))
                      (swap! partial-inputs update item-id merge-codex-stream-input input)))

                  nil))
              (catch Exception _ nil))))))
    (when @failed
      (throw (ex-info "ChatGPT Codex Responses API error" {:error @failed})))
    (when-not @completed
      (throw (ex-info "ChatGPT Codex Responses stream missing response.completed"
                      {:body (subs response-body 0 (min 1000 (count response-body)))})))
    (let [stream-tool-input
          (some (fn [[item-id item]]
                  (when (and (= "custom_tool_call" (:type item))
                             (= "spell_suffix" (:name item)))
                    (or (not-empty (:input item))
                        (not-empty (get @partial-inputs item-id)))))
                @tool-items)
          stream-tool-input (or stream-tool-input
                                (when (= 1 (count @partial-inputs))
                                  (some-> @partial-inputs vals first not-empty)))]
      (parse-codex-tc-response @completed stream-tool-input))))

(defrecord CodexMsgProvider [api-key account-id base-url model max-tokens http-client costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          grammar-format (:grammar-format opts)
          reasoning-effort (:reasoning-effort opts)
          verbosity (:verbosity opts)
          request (codex-msg-request api-key account-id base-url effective-model prompt
                                    (:system opts) max-tokens reasoning-effort verbosity grammar-format)
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-codex-msg-stream (.body response))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "ChatGPT Codex Responses request failed"
                        {:status status :body (.body response)})))))
  (plain-text-provider [this] this)
  (supports-prefill [_] false))

(defrecord CodexTcProvider [api-key account-id base-url model max-tokens prompt-cache-key http-client costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          grammar-format (:grammar-format opts)
          reasoning-effort (:reasoning-effort opts)
          verbosity (:verbosity opts)
          cache-prefix (:cache-prefix opts)
          request (codex-tc-request api-key account-id base-url effective-model prompt
                                    (:system opts)
                                    (when cache-prefix prompt-cache-key)
                                    max-tokens reasoning-effort verbosity grammar-format)
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-codex-tc-stream (.body response))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "ChatGPT Codex mandatory tool-call request failed"
                        {:status status :body (.body response)})))))
  (plain-text-provider [_]
    (->CodexMsgProvider api-key account-id base-url model max-tokens http-client costs))
  (supports-prefill [_] false))

(defn codex-msg-provider
  "Create a ChatGPT subscription-backed Codex provider (message transport).

   Options:
   - :api-key     - bearer token override (default: read from :auth-file)
   - :account-id  - ChatGPT account id header override (default: read from :auth-file)
   - :auth-file   - path to Codex auth.json (default: ~/.codex/auth.json)
   - :base-url    - API base URL (default: https://chatgpt.com/backend-api/codex)
   - :model       - Model name (default: gpt-5.3-codex)
   - :max-tokens  - Max output tokens
   - :costs       - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (codex-msg-provider {}))
  ([{:keys [api-key account-id auth-file base-url model max-tokens costs]
     :or {auth-file "~/.codex/auth.json"
          base-url "https://chatgpt.com/backend-api/codex"
          model "gpt-5.3-codex"}}]
   (let [{file-token :token file-account-id :account-id}
         (when (str/blank? api-key)
           (load-chatgpt-auth auth-file))
         token (or api-key file-token)
         effective-account-id (or account-id file-account-id)
         url (str/replace (or base-url "https://chatgpt.com/backend-api/codex") #"/$" "")]
     (when (str/blank? token)
       (throw (ex-info "No ChatGPT token available. Log in with codex or pass :api-key"
                       {:auth-file (expand-home auth-file)})))
     (->CodexMsgProvider token effective-account-id url model max-tokens (make-http-client) costs))))

(defn codex-tc-provider
  "Create a ChatGPT subscription-backed Codex provider with mandatory custom tool output.

   Options:
   - :api-key     - bearer token override (default: read from :auth-file)
   - :account-id  - ChatGPT account id header override (default: read from :auth-file)
   - :auth-file   - path to Codex auth.json (default: ~/.codex/auth.json)
   - :base-url    - API base URL (default: https://chatgpt.com/backend-api/codex)
   - :model       - Model name (default: gpt-5.3-codex)
   - :max-tokens  - Max output tokens
   - :costs       - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (codex-tc-provider {}))
  ([{:keys [api-key account-id auth-file base-url model max-tokens costs]
     :or {auth-file "~/.codex/auth.json"
          base-url "https://chatgpt.com/backend-api/codex"
          model "gpt-5.3-codex"}}]
   (let [{file-token :token file-account-id :account-id}
         (when (str/blank? api-key)
           (load-chatgpt-auth auth-file))
         token (or api-key file-token)
         effective-account-id (or account-id file-account-id)
         url (str/replace (or base-url "https://chatgpt.com/backend-api/codex") #"/$" "")]
     (when (str/blank? token)
       (throw (ex-info "No ChatGPT token available. Log in with codex or pass :api-key"
                       {:auth-file (expand-home auth-file)})))
     (->CodexTcProvider token effective-account-id url model max-tokens
                        (str (java.util.UUID/randomUUID))
                        (make-http-client) costs))))

;; ---------------------------------------------------------------------------
;; Fireworks Provider (Completions API with true prefill)
;; ---------------------------------------------------------------------------

(def fireworks-chat-templates
  {:chatml
   {:system-start "<|im_start|>system\n"
    :system-end "<|im_end|>\n"
    :user-start "<|im_start|>user\n"
    :user-end "<|im_end|>\n"
    :assistant-start "<|im_start|>assistant\n"
    :stop-sequences ["<|im_end|>"]}

   :deepseek-v3
   {:bos "<|begin▁of▁sentence|>"
    :system-start "<|System|>"
    :system-end ""
    :user-start "<|User|>"
    :user-end ""
    :assistant-start "<|Assistant|>"
    :stop-sequences ["<|end▁of▁sentence|>" "<|User|>"]}

   :glm-4
   {:bos "[gMASK]<sop>"
    :system-start "<|system|>\n"
    :system-end ""
    :user-start "<|user|>\n"
    :user-end ""
    :assistant-start "<|assistant|>\n"
    :stop-sequences ["<|user|>" "<|observation|>"]}})

(defn- detect-chat-template [model-id]
  (let [model-id (str/lower-case (or model-id ""))]
    (cond
      (str/includes? model-id "glm") :glm-4
      (str/includes? model-id "deepseek") :deepseek-v3
      :else :chatml)))

(defn- resolve-chat-template [chat-template model-id]
  (let [template-spec (or chat-template (detect-chat-template model-id))]
    (cond
      (map? template-spec) template-spec
      (keyword? template-spec)
      (or (get fireworks-chat-templates template-spec)
          (throw (ex-info (str "Unknown Fireworks chat template: " template-spec)
                          {:chat-template template-spec})))
      :else
      (throw (ex-info "Fireworks chat template must be a keyword or map"
                      {:chat-template template-spec})))))

(defn- format-completions-prompt [template system-prompt user-message prefix]
  (let [{:keys [bos system-start system-end user-start user-end assistant-start]} template]
    (str (or bos "")
         (when-not (str/blank? system-prompt)
           (str system-start system-prompt system-end))
         (or user-start "")
         user-message
         (or user-end "")
         (or assistant-start "")
         (or prefix ""))))

(defn- convert-think-tags [text]
  (if-let [[match think-text] (re-find #"(?s)^\s*<think>(.*?)</think>\s*" (or text ""))]
    (let [rest-text (subs text (count match))
          think-form (str "(think " (pr-str think-text) ")")]
      (if (str/blank? rest-text)
        think-form
        (str think-form " " rest-text)))
    text))

(defn- fireworks-completions-request
  [api-key base-url model prompt system-prompt prefix max-tokens chat-template thinking]
  (let [template (resolve-chat-template chat-template model)
        body (cond-> {:model model
                      :prompt (format-completions-prompt template system-prompt prompt prefix)
                      :max_tokens (or max-tokens 16384)
                      :stream true
                      :stream_options {:include_usage true}
                      :echo false}
               (seq (:stop-sequences template)) (assoc :stop (:stop-sequences template))
               thinking (assoc :thinking thinking))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/completions")))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- parse-fireworks-sse-stream
  "Parse an SSE stream response, accumulating text chunks and extracting final usage."
  [response-body]
  (let [lines (str/split-lines response-body)
        text-sb (StringBuilder.)
        usage (atom nil)]
    (doseq [line lines]
      (when (str/starts-with? line "data: ")
        (let [data (subs line 6)]
          (when-not (= data "[DONE]")
            (let [parsed (json/read-str data :key-fn keyword)]
              (when-let [error (:error parsed)]
                (throw (ex-info "Fireworks API error" {:error error})))
              (when-let [text (get-in parsed [:choices 0 :text])]
                (.append text-sb text))
              (when-let [u (:usage parsed)]
                (reset! usage u)))))))
    (let [u @usage
          cached-tokens (or (get-in u [:prompt_tokens_details :cached_tokens])
                            (:cached_tokens u)
                            0)
          prompt-tokens (:prompt_tokens u 0)]
      {:text (str text-sb)
       :usage (with-legacy-usage-keys
                {:uncached_input_tokens (max 0 (- prompt-tokens cached-tokens))
                 :visible_output_tokens (:completion_tokens u 0)
                 :cached_input_tokens cached-tokens})})))

(defrecord FireworksProvider [api-key base-url model max-tokens http-client costs chat-template convert-think?]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (fireworks-completions-request api-key
                                                 base-url
                                                 effective-model
                                                 prompt
                                                 (:system opts)
                                                 (:prefix opts)
                                                 max-tokens
                                                 chat-template
                                                 (:thinking opts))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-fireworks-sse-stream (.body response))
              text (cond-> text convert-think? convert-think-tags)]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "Fireworks completions request failed"
                        {:status status :body (.body response)})))))
  (plain-text-provider [this] this)
  (supports-prefill [_] true))

(defn fireworks-provider
  "Create a Fireworks provider using the completions API for true prefill.

   Options:
   - :api-key        - API key (default: FIREWORKS_API_KEY env var)
   - :base-url       - API base URL (default: https://api.fireworks.ai/inference/v1)
   - :model          - Model name or Fireworks account path (default: glm-5)
   - :max-tokens     - Max tokens per response
   - :chat-template  - Keyword in `fireworks-chat-templates` or explicit template map
   - :convert-think? - Convert leading <think>...</think> to Spell `(think ...)`
   - :costs          - Cost table {model-prefix price-spec}"
  ([] (fireworks-provider {}))
  ([{:keys [api-key base-url model max-tokens costs chat-template convert-think?]
     :or {model "glm-5"
          base-url "https://api.fireworks.ai/inference/v1"
          convert-think? true}}]
   (let [key (or api-key (System/getenv "FIREWORKS_API_KEY"))
         effective-model (if (str/starts-with? model "accounts/")
                           model
                           (str "accounts/fireworks/models/" model))
         url (str/replace (or base-url "https://api.fireworks.ai/inference/v1") #"/$" "")]
     (when-not key
       (throw (ex-info "No API key provided. Set FIREWORKS_API_KEY or pass :api-key"
                       {:env "FIREWORKS_API_KEY"})))
     (->FireworksProvider key url effective-model max-tokens (make-http-client) costs
                          chat-template convert-think?))))

;; ---------------------------------------------------------------------------
;; Test Provider (declarative testing)
;; ---------------------------------------------------------------------------

(defn- match-rule
  "Check if a prompt matches a response rule.
   Rule format: {:includes [strs...] :excludes [strs...] :response str-or-map}"
  [prompt {:keys [includes excludes]}]
  (and (every? #(str/includes? prompt %) includes)
       (not-any? #(str/includes? prompt %) (or excludes []))))

(defrecord TestProvider [responses response-fn response-rules prefill?]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt _opts]
    (let [entry (or (get responses prompt)
                    (when response-fn (response-fn prompt))
                    (some (fn [rule]
                            (when (match-rule prompt rule)
                              (when-not (:response rule)
                                (throw (ex-info "TestProvider: matched rule has no :response key"
                                                {:prompt prompt :rule rule})))
                              (:response rule)))
                          response-rules))]
      (when-not entry
        (throw (ex-info (str "TestProvider: no response for prompt")
                        {:prompt prompt
                         :available-keys (vec (keys responses))})))
      (let [{:keys [response latency]} (if (string? entry)
                                         {:response entry}
                                         entry)]
        (when (and latency (pos? latency))
          (Thread/sleep (long latency)))
        response)))
  (plain-text-provider [this] this)
  (supports-prefill [_] (if (some? prefill?) prefill? true)))

(defn test-provider
  "Create a declarative test provider.

   Options:
   - :response — static response string (catch-all fallback for any prompt).
   - :responses — map of prompt-string to response.
     Values are either a plain string or {:response str :latency ms}.
     When a prompt doesn't match any key, throws with the full prompt
     text (copy-paste into the map to build test fixtures).
   - :response-fn — optional fallback (fn [prompt] -> response-or-nil).
     Tried when :responses has no exact match. Useful for prompts
     containing gensym'd names (multi-agent scenarios).
   - :response-rules — vector of rules checked in order when exact match
     and response-fn both miss. Each rule:
       {:includes [\"sub1\" \"sub2\"]  ;; all must be present in prompt
        :excludes [\"exc\"]            ;; none may be present (optional)
        :response \"the response\"}    ;; string or {:response str :latency ms}
   - :prefill? — whether this provider supports prefill (default: true).

   Usage:
     (test-provider {:response \"42)\"})
     (test-provider {:responses {\"(quine completion (eval (do \" \"42)))\"}})
     (test-provider {:response-fn (fn [p] (when (.contains p \"foo\") \"bar\"))})
     (test-provider {:response-rules [{:includes [\"hello\"] :response \"world\"}]})"
  [{:keys [responses response-fn response-rules response prefill?]}]
  (->TestProvider (or responses {})
                  (or response-fn (when response (constantly response)))
                  (or response-rules [])
                  prefill?))

;; ---------------------------------------------------------------------------
;; User Provider (interactive simulation)
;; ---------------------------------------------------------------------------

(defn- unescape-for-display
  "Unescape \\n and \\t for readable display, preserving \\\\ as \\."
  [s]
  (-> s
      (str/replace "\\\\" "\u0000")
      (str/replace "\\n" "\n")
      (str/replace "\\t" "\t")
      (str/replace "\u0000" "\\")))

(defrecord UserProvider []
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (plain-text-provider [this] this)
  (supports-prefill [_] true)
  (call-llm [_ prompt opts]
    (let [system (:system opts)
          prefix (:prefix opts)]
      ;; Display context on stderr (keeps stdout clean for program output)
      (binding [*out* *err*]
        (println "\n════════════════════════════════════════")
        (when system
          (println "=== SYSTEM PROMPT ===")
          (println system)
          (println))
        (if prefix
          (do
            (println "=== PREFILL PREFIX ===")
            (println (unescape-for-display prefix))
            (println)
            (println "=== USER MESSAGE ===")
            (println (unescape-for-display prompt)))
          (do
            (println "=== PROMPT ===")
            (println (unescape-for-display prompt))))
        (println)
        (println "=== YOUR COMPLETION (Ctrl-D to submit) ===")
        (flush))
      ;; Read completion from stdin
      (let [sb (StringBuilder.)]
        (loop []
          (let [line (read-line)]
            (if (nil? line)
              (str/trimr (.toString sb))
              (do (.append sb line)
                  (.append sb "\n")
                  (recur)))))))))
(defn user-provider
  "Create an interactive user provider for simulation/debugging.
   Displays the full LLM context (system prompt, user message, prefix)
   and reads completions from stdin. Use with -m user."
  []
  (->UserProvider))

(defn strip-code-fences
  "Strip markdown code fences from LLM responses.
   Handles ```lang\\n...\\n``` wrapping that some models produce."
  [s]
  (let [trimmed (str/trim s)]
    (if (str/starts-with? trimmed "```")
      (-> trimmed
          (str/replace-first #"^```\w*\r?\n?" "")
          (str/replace #"\r?\n?```\s*$" ""))
      s)))

(defn retryable?
  "Returns true if the exception looks like a transient API failure worth retrying.
   Rate limits (429), server errors (5xx), network errors, and missing tool-call
   responses (empty/truncated/incomplete) are retryable.
   HttpTimeoutException (request-level timeout, typically 600s) is NOT retryable —
   retrying a 600s timeout consumes 1800s and exhausts the harness budget."
  [ex]
  (let [data (ex-data ex)
        status (:status data)]
    (or (= status 429)
        (and status (>= status 500))
        (= (:type data) :missing-tool-call)
        (instance? java.net.ConnectException ex)
        (instance? java.net.http.HttpConnectTimeoutException ex)
        (instance? java.io.IOException ex))))

(defn call-with-retries
  "Call f, retrying on transient failures according to retries-seq.
   retries-seq is a sequence of sleep durations in seconds.
   f takes one argument: the previous exception (nil on first call)."
  [f retries-seq]
  (loop [retries-left (seq retries-seq) last-err nil]
    (let [result (try
                   {:ok (f last-err)}
                   (catch Exception e
                     (if (and retries-left (retryable? e))
                       {:retry e :sleep (first retries-left) :rest (next retries-left)}
                       (throw e))))]
      (if (:ok result)
        (:ok result)
        (do
          (when (pos? (:sleep result))
            (Thread/sleep (* 1000 (long (:sleep result)))))
          (recur (:rest result) (:retry result)))))))

;; ---------------------------------------------------------------------------
;; Provider loading from .provider.edn files
;; ---------------------------------------------------------------------------

(defn load-provider
  "Load provider from a .provider.edn file path."
  [path]
  (let [{:keys [type api-key-env base-url model max-tokens costs use-responses-api auth-file account-id
                responses response-rules response prefill? chat-template convert-think?
                force-tool-call cache-read-ratio prompt-cache-key request-timeout-sec]}
        (edn/read-string (slurp path))
        api-key (when api-key-env (System/getenv api-key-env))
        opts (cond-> {:costs (cond-> (merge default-costs (or costs {}))
                               cache-read-ratio (assoc :cache-read-ratio cache-read-ratio))}
               api-key (assoc :api-key api-key)
               base-url (assoc :base-url base-url)
               model (assoc :model model)
               max-tokens (assoc :max-tokens max-tokens)
               use-responses-api (assoc :use-responses-api true)
               force-tool-call (assoc :force-tool-call true)
               prompt-cache-key (assoc :prompt-cache-key prompt-cache-key)
               auth-file (assoc :auth-file auth-file)
               account-id (assoc :account-id account-id)
               chat-template (assoc :chat-template chat-template)
               request-timeout-sec (assoc :request-timeout-sec request-timeout-sec)
               (some? convert-think?) (assoc :convert-think? convert-think?))]
    (case type
      :anthropic-pf (anthropic-pf-provider opts)
      :anthropic-tc (anthropic-tc-provider opts)
      :openai    (openai-provider opts)
      :codex-tc  (codex-tc-provider opts)
      :fireworks (fireworks-provider opts)
      :ollama    (ollama-provider opts)
      :test      (test-provider {:responses responses :response-rules response-rules
                                 :response response :prefill? prefill?})
      (throw (ex-info (str "Unknown provider type: " type) {:type type})))))

(defn provider-edn-default-agent
  "Read :default-agent from a .provider.edn file. Returns path string or nil.
   The path is relative to the provider file's directory."
  [path]
  (let [edn (edn/read-string (slurp path))
        rel-path (:default-agent edn)]
    (when rel-path
      (let [base-dir (.getParent (java.io.File. path))]
        (str base-dir "/" rel-path)))))

(defn- load-provider-from-map
  "Create a provider from an inline config map (same keys as .provider.edn)."
  [{:keys [type api-key-env base-url model max-tokens costs use-responses-api auth-file account-id
           responses response-rules response prefill? chat-template convert-think?
           force-tool-call cache-read-ratio prompt-cache-key request-timeout-sec] :as spec}]
  (let [api-key (when api-key-env (System/getenv api-key-env))
        opts (cond-> {:costs (cond-> (merge default-costs (or costs {}))
                               cache-read-ratio (assoc :cache-read-ratio cache-read-ratio))}
               api-key (assoc :api-key api-key)
               base-url (assoc :base-url base-url)
               model (assoc :model model)
               max-tokens (assoc :max-tokens max-tokens)
               use-responses-api (assoc :use-responses-api true)
               force-tool-call (assoc :force-tool-call true)
               prompt-cache-key (assoc :prompt-cache-key prompt-cache-key)
               auth-file (assoc :auth-file auth-file)
               account-id (assoc :account-id account-id)
               chat-template (assoc :chat-template chat-template)
               request-timeout-sec (assoc :request-timeout-sec request-timeout-sec)
               (some? convert-think?) (assoc :convert-think? convert-think?))]
    (case type
      :anthropic-pf (anthropic-pf-provider opts)
      :anthropic-tc (anthropic-tc-provider opts)
      :openai    (openai-provider opts)
      :codex-tc  (codex-tc-provider opts)
      :fireworks (fireworks-provider opts)
      :ollama    (ollama-provider opts)
      :test      (test-provider {:responses responses :response-rules response-rules
                                 :response response :prefill? prefill?})
      (throw (ex-info (str "Unknown provider type: " type) {:type type :spec spec})))))

(defn- resolve-path
  "Resolve a relative path against a base directory."
  [path base-dir]
  (if (or (nil? base-dir) (str/starts-with? path "/"))
    path
    (str base-dir "/" path)))

(defn resolve-provider
  "Resolve provider from path string, inline map, or existing instance."
  [spec base-dir]
  (cond
    (satisfies? LLMProvider spec) spec
    (string? spec) (load-provider (resolve-path spec base-dir))
    (and (map? spec) (:file spec)) (load-provider (resolve-path (:file spec) base-dir))
    (map? spec) (load-provider-from-map spec)
    :else (throw (ex-info "Invalid provider spec" {:spec spec}))))
