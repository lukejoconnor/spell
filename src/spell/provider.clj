(ns spell.provider
  "LLM provider abstraction and token tracking.

   Providers implement the LLMProvider protocol. Built-in providers:
   - anthropic-provider: Calls Claude API
   - openai-provider: Calls OpenAI API
   - ollama-provider: Calls local Ollama API
   - dummy-provider: Returns canned responses for testing"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol LLMProvider
  "Protocol for LLM API providers."
  (call-llm [this prompt] [this prompt opts]
    "Send prompt to LLM, return response string.
     prompt is a string. opts may include :system for system prompt.
     Returns the assistant's text response.")
  (supports-prefill [this]
    "Returns true if this provider supports assistant prefill."))

;; ---------------------------------------------------------------------------
;; Token usage tracking
;; ---------------------------------------------------------------------------

(def ^:dynamic *usage*
  "When bound to an atom, accumulates token usage from API calls.
   Atom value is a map: {:by-model {\"model\" {:input_tokens N :output_tokens N :calls N
                                               :cache_creation_input_tokens N :cache_read_input_tokens N}}}"
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

(def default-costs
  "Cost per million tokens: {model-prefix [input-cost output-cost]}"
  {"claude-3-5-haiku"  [0.80 4.00]
   "claude-haiku-4-5"  [1.00 5.00]
   "claude-sonnet-5"   [3.00 15.00]
   "claude-sonnet-4"   [3.00 15.00]
   "claude-opus-4-5"   [5.00 25.00]
   "claude-opus-4-6"   [5.00 25.00]
   ;; OpenAI models
   "gpt-4o-mini"       [0.15 0.60]
   "gpt-4o"            [2.50 10.00]
   "gpt-4.1-nano"      [0.10 0.40]
   "gpt-4.1-mini"      [0.40 1.60]
   "gpt-4.1"           [2.00 8.00]
   "o3-mini"           [1.10 4.40]
   "o4-mini"           [1.10 4.40]
   "o3"                [2.00 8.00]
   "gpt-5-mini"        [0.25 2.00]
   "gpt-5-codex"       [1.25 10.00]
   "gpt-5.1-codex"     [1.25 10.00]
   "gpt-5"             [1.25 10.00]
   "gpt-5.1"           [1.25 10.00]
   "gpt-5.2-codex"     [1.75 14.00]
   "gpt-5.2"           [1.75 14.00]
   ;; Moonshot Kimi models
   "kimi-k2.5"         [0.60 3.00]
   "kimi-k2-thinking-turbo" [1.15 8.00]
   "kimi-k2-thinking"  [0.60 2.50]
   "kimi-k2-turbo"     [1.15 8.00]
   "kimi-k2-0905"      [0.60 2.50]
   "kimi-k2-0711"      [0.60 2.50]
   "moonshot-v1-8k"    [0.20 2.00]
   "moonshot-v1-32k"   [1.00 3.00]
   "moonshot-v1-128k"  [2.00 5.00]})

(defn- lookup-cost
  "Find cost for a model ID by prefix matching in a cost table."
  [model-id cost-table]
  (some (fn [[prefix costs]]
          (when (.startsWith ^String model-id prefix) costs))
        cost-table))

(defn current-cost
  "Compute total cost in dollars from accumulated usage data.
   Returns nil if no models have known pricing.
   Accounts for cache pricing: cache writes 1.25x, cache reads 0.1x normal input."
  [usage-atom]
  (let [{:keys [by-model cost-table]} @usage-atom
        effective-costs (or cost-table default-costs)
        costs (keep (fn [[model stats]]
                      (when-let [[in-cost out-cost] (lookup-cost model effective-costs)]
                        (let [base-input (* (:input_tokens stats 0) (/ in-cost 1000000.0))
                              cache-write (* (:cache_creation_input_tokens stats 0) (/ in-cost 1000000.0) 1.25)
                              cache-read (* (:cache_read_input_tokens stats 0) (/ in-cost 1000000.0) 0.1)
                              output (* (:output_tokens stats 0) (/ out-cost 1000000.0))]
                          (+ base-input cache-write cache-read output))))
                    by-model)]
    (when (seq costs)
      (reduce + 0.0 costs))))

(defn track-usage!
  "Add usage data to the *usage* atom if bound.
   Optional cost-table merges into the atom for current-cost to use.
   Throws ex-info with {:type :budget-exceeded} if *budget* is set and cumulative cost exceeds it."
  ([model usage] (track-usage! model usage nil))
  ([model usage cost-table]
   (when (and *usage* usage)
     (swap! *usage* (fn [u]
                      (cond-> (update-in u [:by-model model]
                                (fn [existing]
                                  (cond-> {:input_tokens (+ (:input_tokens existing 0) (:input_tokens usage 0))
                                           :output_tokens (+ (:output_tokens existing 0) (:output_tokens usage 0))
                                           :cache_creation_input_tokens (+ (:cache_creation_input_tokens existing 0)
                                                                           (:cache_creation_input_tokens usage 0))
                                           :cache_read_input_tokens (+ (:cache_read_input_tokens existing 0)
                                                                       (:cache_read_input_tokens usage 0))
                                           :calls (inc (:calls existing 0))}
                                    (:reasoning_tokens usage)
                                    (assoc :reasoning_tokens (+ (:reasoning_tokens existing 0)
                                                                (:reasoning_tokens usage 0))))))
                        cost-table (update :cost-table merge cost-table))))
     (when *budget*
       (when-let [cost (current-cost *usage*)]
         (when (> cost *budget*)
           (throw (ex-info (format "Budget exceeded: $%.4f spent (limit $%.4f)" cost *budget*)
                           {:type :budget-exceeded :cost cost :budget *budget*}))))))))

(defn usage-summary
  "Compute a summary from accumulated usage data.
   Returns {:by-model {model {:input_tokens N :output_tokens N :calls N :cost F
                              :cache_creation_input_tokens N :cache_read_input_tokens N}}
            :total {:input_tokens N :output_tokens N :calls N :cost F
                    :cache_creation_input_tokens N :cache_read_input_tokens N}}"
  [usage-atom]
  (let [{:keys [by-model cost-table]} @usage-atom
        effective-costs (or cost-table default-costs)
        with-costs (into {}
                     (map (fn [[model stats]]
                            (let [[in-cost out-cost] (lookup-cost model effective-costs)
                                  cost (when (and in-cost out-cost)
                                         (let [base-input (* (:input_tokens stats 0) (/ in-cost 1000000.0))
                                               cache-write (* (:cache_creation_input_tokens stats 0) (/ in-cost 1000000.0) 1.25)
                                               cache-read (* (:cache_read_input_tokens stats 0) (/ in-cost 1000000.0) 0.1)
                                               output (* (:output_tokens stats 0) (/ out-cost 1000000.0))]
                                           (+ base-input cache-write cache-read output)))]
                              [model (cond-> stats cost (assoc :cost cost))]))
                          by-model))
        reasoning-total (reduce + 0 (keep :reasoning_tokens (vals by-model)))
        total (cond-> {:input_tokens (reduce + 0 (map #(:input_tokens % 0) (vals by-model)))
                       :output_tokens (reduce + 0 (map #(:output_tokens % 0) (vals by-model)))
                       :cache_creation_input_tokens (reduce + 0 (map #(:cache_creation_input_tokens % 0) (vals by-model)))
                       :cache_read_input_tokens (reduce + 0 (map #(:cache_read_input_tokens % 0) (vals by-model)))
                       :calls (reduce + 0 (map #(:calls % 0) (vals by-model)))
                       :cost (let [costs (keep :cost (vals with-costs))]
                               (when (seq costs) (reduce + 0.0 costs)))}
                (pos? reasoning-total) (assoc :reasoning_tokens reasoning-total))]
    {:by-model with-costs :total total}))

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
   - Opus 4.5/4.6, Haiku 4.5: 4096 tokens (~16K chars)
   - Sonnet, Opus 4.0/4.1: 1024 tokens (~4K chars)
   - Haiku 3.x: 2048 tokens (~8K chars)"
  [model]
  (cond
    (or (str/includes? model "opus-4-5")
        (str/includes? model "opus-4-6")
        (str/includes? model "haiku-4-5")) 16000
    (or (str/includes? model "haiku-3")
        (str/includes? model "haiku-3-5")) 8000
    :else 4000))

(defn- anthropic-request [api-key model prompt system-prompt prefix max-tokens stream? thinking cache-prefix]
  (let [;; When thinking is active, don't use assistant prefill (incompatible)
        effective-prefix (when-not thinking prefix)
        ;; Only apply cache_control when content exceeds model's minimum threshold
        min-chars (cache-min-chars model)
        ;; Split user message for caching: stable prefix + new content
        user-content (if (and cache-prefix
                              (not (str/blank? cache-prefix))
                              (str/starts-with? prompt cache-prefix)
                              (>= (count cache-prefix) min-chars))
                       (let [new-content (subs prompt (count cache-prefix))]
                         (if (str/blank? new-content)
                           [{:type "text" :text prompt :cache_control {:type "ephemeral"}}]
                           [{:type "text" :text cache-prefix :cache_control {:type "ephemeral"}}
                            {:type "text" :text new-content}]))
                       prompt)
        messages (cond-> [{:role "user" :content user-content}]
                   effective-prefix (conj {:role "assistant" :content (str/trimr effective-prefix)}))
        ;; Use cache_control for system prompt when it exceeds model's minimum threshold
        cached-system (when system-prompt
                        [(cond-> {:type "text" :text system-prompt}
                           (>= (count system-prompt) min-chars)
                           (assoc :cache_control {:type "ephemeral"}))])
        body (cond-> {:model model
                      :max_tokens (if thinking
                                    (or max-tokens 32768)
                                    (or max-tokens 16384))
                      :messages messages}
               cached-system (assoc :system cached-system)
               stream? (assoc :stream true)
               thinking (assoc :thinking (if (number? thinking)
                                          {:type "enabled" :budget_tokens thinking}
                                          {:type "enabled" :budget_tokens 10000})))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create "https://api.anthropic.com/v1/messages"))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" api-key)
                    (.header "anthropic-version" "2023-06-01")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- parse-anthropic-response [response-body]
  (let [parsed (json/read-str response-body :key-fn keyword)]
    (if-let [error (:error parsed)]
      (throw (ex-info "Anthropic API error" {:error error}))
      (let [usage (:usage parsed)]
        {:text (->> (:content parsed)
                    (filter #(= (:type %) "text"))
                    (map :text)
                    (clojure.string/join "\n"))
         ;; Include cache usage fields if present
         :usage {:input_tokens (:input_tokens usage 0)
                 :output_tokens (:output_tokens usage 0)
                 :cache_creation_input_tokens (:cache_creation_input_tokens usage 0)
                 :cache_read_input_tokens (:cache_read_input_tokens usage 0)}}))))

(defn- parse-anthropic-stream
  "Parse an Anthropic SSE stream, accumulating text and usage."
  [response-body]
  (let [text (StringBuilder.)
        usage (atom {:input_tokens 0 :output_tokens 0
                     :cache_creation_input_tokens 0 :cache_read_input_tokens 0})]
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
                           {:input_tokens (:input_tokens u 0)
                            :cache_creation_input_tokens (:cache_creation_input_tokens u 0)
                            :cache_read_input_tokens (:cache_read_input_tokens u 0)}))

                  "content_block_delta"
                  (when-let [t (get-in parsed [:delta :text])]
                    (.append text t))

                  "message_delta"
                  (when-let [u (:usage parsed)]
                    (swap! usage assoc :output_tokens (:output_tokens u 0)))

                  nil)) ; ignore other event types
              (catch Exception _ nil))))))
    {:text (.toString text) :usage @usage}))

(defrecord AnthropicProvider [api-key model max-tokens http-client costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          thinking (:thinking opts)
          effective-max-tokens (or max-tokens (if thinking 32768 16384))
          ;; Use streaming for large max_tokens (API requires it for >16384) or thinking
          stream? (or thinking (> effective-max-tokens 16384))
          cache-prefix (:cache-prefix opts)
          request (anthropic-request api-key effective-model prompt (:system opts) (:prefix opts)
                                    effective-max-tokens stream? thinking cache-prefix)
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (if stream?
                                     (parse-anthropic-stream (.body response))
                                     (parse-anthropic-response (.body response)))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "Anthropic API request failed"
                        {:status status :body (.body response)})))))
  (supports-prefill [_]
    ;; Opus 4.6 does not support assistant prefill (returns 400 error)
    (not (str/includes? (str model) "opus-4-6"))))

(defn anthropic-provider
  "Create an Anthropic provider.

   Options:
   - :api-key - API key (default: ANTHROPIC_API_KEY env var)
   - :model - Model name (default: claude-sonnet-4-20250514)
   - :max-tokens - Max tokens per response (default: 16384)
   - :costs - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (anthropic-provider {}))
  ([{:keys [api-key model max-tokens costs]
     :or {model "claude-sonnet-4-5-20250929"}}]
   (let [key (or api-key (System/getenv "ANTHROPIC_API_KEY"))]
     (when-not key
       (throw (ex-info "No API key provided. Set ANTHROPIC_API_KEY or pass :api-key"
                       {:env "ANTHROPIC_API_KEY"})))
     (->AnthropicProvider key model max-tokens (make-http-client) costs))))

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
       :usage {:input_tokens (:prompt_eval_count parsed 0)
               :output_tokens (:eval_count parsed 0)}})))

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

(defn- openai-responses-request [api-key base-url model prompt system-prompt max-tokens reasoning-effort verbosity]
  (let [reasoning (when reasoning-effort
                    {:effort reasoning-effort})
        body (cond-> {:model model
                      :input prompt}
               system-prompt (assoc :instructions system-prompt)
               max-tokens (assoc :max_output_tokens max-tokens)
               reasoning (assoc :reasoning reasoning)
               verbosity (assoc :verbosity verbosity))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/responses")))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- parse-openai-responses-response [response-body]
  (let [parsed (json/read-str response-body :key-fn keyword)]
    (if-let [error (:error parsed)]
      (throw (ex-info "OpenAI Responses API error" {:error error}))
      (let [usage (:usage parsed)
            reasoning-tokens (get-in usage [:output_tokens_details :reasoning_tokens])]
        {:text (:output_text parsed "")
         :usage (cond-> {:input_tokens (get-in parsed [:usage :input_tokens] 0)
                         :output_tokens (get-in parsed [:usage :output_tokens] 0)}
                  reasoning-tokens (assoc :reasoning_tokens reasoning-tokens))}))))

(defn- openai-request [api-key base-url model prompt system-prompt _prefix max-tokens reasoning-effort verbosity]
  (let [messages (cond-> []
                   system-prompt (conj {:role "system" :content system-prompt})
                   true (conj {:role "user" :content prompt}))
        body (cond-> {:model model
                      :messages messages
                      :max_completion_tokens (or max-tokens 16384)}
               reasoning-effort (assoc :reasoning_effort reasoning-effort)
               verbosity (assoc :verbosity verbosity))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/chat/completions")))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defn- parse-openai-response [response-body]
  (let [parsed (json/read-str response-body :key-fn keyword)]
    (if-let [error (:error parsed)]
      (throw (ex-info "OpenAI API error" {:error error}))
      (let [usage (:usage parsed)
            reasoning-tokens (get-in usage [:completion_tokens_details :reasoning_tokens])]
        {:text (get-in parsed [:choices 0 :message :content] "")
         :usage (cond-> {:input_tokens (:prompt_tokens usage 0)
                         :output_tokens (:completion_tokens usage 0)}
                  reasoning-tokens (assoc :reasoning_tokens reasoning-tokens))}))))

(defrecord OpenAIProvider [api-key base-url model max-tokens http-client use-responses-api costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          responses? (or use-responses-api (responses-model? effective-model))
          reasoning-effort (:reasoning-effort opts)
          verbosity (:verbosity opts)
          request (if responses?
                    (openai-responses-request api-key base-url effective-model prompt (:system opts)
                                             max-tokens reasoning-effort verbosity)
                    (openai-request api-key base-url effective-model prompt (:system opts) (:prefix opts)
                                   max-tokens reasoning-effort verbosity))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (if responses?
                                     (parse-openai-responses-response (.body response))
                                     (parse-openai-response (.body response)))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "OpenAI API request failed"
                        {:status status :body (.body response)})))))
  (supports-prefill [_] false))

(defn openai-provider
  "Create an OpenAI provider.

   Options:
   - :api-key              - API key (default: OPENAI_API_KEY env var)
   - :base-url             - API base URL (default: https://api.openai.com/v1)
   - :model                - Model name (default: gpt-4o)
   - :max-tokens           - Max tokens per response (default: 16384)
   - :use-responses-api    - Force Responses API instead of Chat Completions (default: false)
   - :costs                - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (openai-provider {}))
  ([{:keys [api-key base-url model max-tokens use-responses-api costs]
     :or {model "gpt-4o"
          base-url "https://api.openai.com/v1"}}]
   (let [key (or api-key (System/getenv "OPENAI_API_KEY"))
         url (str/replace (or base-url "https://api.openai.com/v1") #"/$" "")]
     (when-not key
       (throw (ex-info "No API key provided. Set OPENAI_API_KEY or pass :api-key"
                       {:env "OPENAI_API_KEY"})))
     (let [local? (or (str/starts-with? url "http://127.0.0.1")
                      (str/starts-with? url "http://localhost"))
           client (if local?
                    (make-http-client {:http-version HttpClient$Version/HTTP_1_1})
                    (make-http-client))]
       (->OpenAIProvider key url model max-tokens client use-responses-api costs)))))

;; ---------------------------------------------------------------------------
;; Kimi Provider (Moonshot AI)
;; ---------------------------------------------------------------------------

(defn- kimi-request [api-key base-url model prompt system-prompt prefix max-tokens]
  (let [messages (cond-> []
                   system-prompt (conj {:role "system" :content system-prompt})
                   true (conj {:role "user" :content prompt})
                   prefix (conj {:role "assistant" :content (str/trimr prefix)}))
        body (cond-> {:model model
                      :messages messages}
               max-tokens (assoc :max_tokens max-tokens))
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-url "/chat/completions")))
                    (.header "Content-Type" "application/json")
                    (.header "Authorization" (str "Bearer " api-key))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
                    (.build))]
    request))

(defrecord KimiProvider [api-key base-url model max-tokens http-client costs]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (kimi-request api-key base-url effective-model prompt
                                (:system opts) (:prefix opts) max-tokens)
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-openai-response (.body response))]
          (track-usage! effective-model usage costs)
          text)
        (throw (ex-info "Kimi API request failed"
                        {:status status :body (.body response)})))))
  (supports-prefill [_] true))

(defn kimi-provider
  "Create a Moonshot Kimi provider.

   Options:
   - :api-key    - API key (default: MOONSHOT_API_KEY env var)
   - :base-url   - API base URL (default: https://api.moonshot.ai/v1)
   - :model      - Model name (default: kimi-k2.5)
   - :max-tokens - Max tokens per response
   - :costs      - Cost table {model-prefix [input-per-M output-per-M]}"
  ([] (kimi-provider {}))
  ([{:keys [api-key base-url model max-tokens costs]
     :or {model "kimi-k2.5"
          base-url "https://api.moonshot.ai/v1"}}]
   (let [key (or api-key (System/getenv "MOONSHOT_API_KEY"))
         url (str/replace (or base-url "https://api.moonshot.ai/v1") #"/$" "")]
     (when-not key
       (throw (ex-info "No API key provided. Set MOONSHOT_API_KEY or pass :api-key"
                       {:env "MOONSHOT_API_KEY"})))
     (->KimiProvider key url model max-tokens (make-http-client) costs))))

;; ---------------------------------------------------------------------------
;; Dummy Provider (for testing)
;; ---------------------------------------------------------------------------

(defrecord DummyProvider [response-fn prefill?]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt _opts]
    (response-fn prompt))
  (supports-prefill [_] (if (some? prefill?) prefill? true)))

(defn dummy-provider
  "Create a dummy provider for testing.

   Options:
   - :response - Static response string (default: \"Hello, world!\")
   - :response-fn - Function (prompt -> response) for dynamic responses
   - :prefill? - Whether this provider supports prefill (default: true)

   If both provided, :response-fn takes precedence."
  ([] (dummy-provider {}))
  ([{:keys [response response-fn prefill?]
     :or {response "Hello, world!"}}]
   (->DummyProvider (or response-fn (constantly response)) prefill?)))

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
  (supports-prefill [_] true)
  (call-llm [_ prompt opts]
    (let [system (:system opts)
          prefix (:prefix opts)]
      ;; Display context on stderr (keeps stdout clean for program output)
      (binding [*out* *err*]
        (print "\033[2J\033[H")
        (flush)
        (when system
          (println "=== SYSTEM PROMPT ===")
          (println system)
          (println))
        (println (str "=== " (if prefix "PROMPT (prefix)" "PROMPT") " ==="))
        (println (unescape-for-display prompt))
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
   Rate limits (429), server errors (5xx), and network errors are retryable."
  [ex]
  (let [data (ex-data ex)
        status (:status data)]
    (or (= status 429)
        (and status (>= status 500))
        (instance? java.net.ConnectException ex)
        (instance? java.net.http.HttpConnectTimeoutException ex)
        (instance? java.net.http.HttpTimeoutException ex))))

(defn call-with-retries
  "Call f, retrying on transient failures according to retries-seq.
   retries-seq is a sequence of sleep durations in seconds."
  [f retries-seq]
  (loop [retries-left (seq retries-seq)]
    (let [result (try
                   {:ok (f)}
                   (catch Exception e
                     (if (and retries-left (retryable? e))
                       {:retry e :sleep (first retries-left) :rest (next retries-left)}
                       (throw e))))]
      (if (:ok result)
        (:ok result)
        (do
          (when (pos? (:sleep result))
            (Thread/sleep (* 1000 (long (:sleep result)))))
          (recur (:rest result)))))))

;; ---------------------------------------------------------------------------
;; Provider loading from .provider.edn files
;; ---------------------------------------------------------------------------

(defn load-provider
  "Load provider from a .provider.edn file path."
  [path]
  (let [{:keys [type api-key-env base-url model max-tokens costs use-responses-api]}
        (edn/read-string (slurp path))
        api-key (when api-key-env (System/getenv api-key-env))
        opts (cond-> {:costs (or costs {})}
               api-key (assoc :api-key api-key)
               base-url (assoc :base-url base-url)
               model (assoc :model model)
               max-tokens (assoc :max-tokens max-tokens)
               use-responses-api (assoc :use-responses-api true))]
    (case type
      :anthropic (anthropic-provider opts)
      :openai    (openai-provider opts)
      :ollama    (ollama-provider opts)
      :kimi      (kimi-provider opts)
      (throw (ex-info (str "Unknown provider type: " type) {:type type})))))

(defn- load-provider-from-map
  "Create a provider from an inline config map (same keys as .provider.edn)."
  [{:keys [type api-key-env base-url model max-tokens costs use-responses-api] :as spec}]
  (let [api-key (when api-key-env (System/getenv api-key-env))
        opts (cond-> {:costs (or costs {})}
               api-key (assoc :api-key api-key)
               base-url (assoc :base-url base-url)
               model (assoc :model model)
               max-tokens (assoc :max-tokens max-tokens)
               use-responses-api (assoc :use-responses-api true))]
    (case type
      :anthropic (anthropic-provider opts)
      :openai    (openai-provider opts)
      :ollama    (ollama-provider opts)
      :kimi      (kimi-provider opts)
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
    (map? spec) (load-provider-from-map spec)
    :else (throw (ex-info "Invalid provider spec" {:spec spec}))))
