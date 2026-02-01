(ns spell.provider
  "LLM provider abstraction and token tracking.

   Providers implement the LLMProvider protocol. Built-in providers:
   - anthropic-provider: Calls Claude API
   - openai-provider: Calls OpenAI API
   - ollama-provider: Calls local Ollama API
   - dummy-provider: Returns canned responses for testing"
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol LLMProvider
  "Protocol for LLM API providers."
  (call-llm [this prompt] [this prompt opts]
    "Send prompt to LLM, return response string.
     prompt is a string. opts may include :system for system prompt.
     Returns the assistant's text response."))

;; ---------------------------------------------------------------------------
;; Token usage tracking
;; ---------------------------------------------------------------------------

(def ^:dynamic *usage*
  "When bound to an atom, accumulates token usage from API calls.
   Atom value is a map: {:by-model {\"model\" {:input_tokens N :output_tokens N :calls N}}}"
  nil)

(def ^:dynamic *budget*
  "When set to a number, throws if cumulative cost exceeds this amount (in dollars)."
  nil)

(def ^:private model-costs
  "Cost per million tokens: {model-prefix [input-cost output-cost]}"
  {"claude-3-5-haiku"  [0.80 4.00]
   "claude-sonnet-4"   [3.00 15.00]
   "claude-opus-4"     [15.00 75.00]
   "gpt-4o"            [2.50 10.00]
   "gpt-5.2"           [1.75 14.00]})

(defn- lookup-cost
  "Find cost for a model ID by prefix matching."
  [model-id]
  (some (fn [[prefix costs]]
          (when (.startsWith ^String model-id prefix) costs))
        model-costs))

(defn current-cost
  "Compute total cost in dollars from accumulated usage data.
   Returns nil if no models have known pricing."
  [usage-atom]
  (let [by-model (:by-model @usage-atom)
        costs (keep (fn [[model stats]]
                      (when-let [[in-cost out-cost] (lookup-cost model)]
                        (+ (* (:input_tokens stats) (/ in-cost 1000000.0))
                           (* (:output_tokens stats) (/ out-cost 1000000.0)))))
                    by-model)]
    (when (seq costs)
      (reduce + 0.0 costs))))

(defn track-usage!
  "Add usage data to the *usage* atom if bound.
   Throws ex-info with {:type :budget-exceeded} if *budget* is set and cumulative cost exceeds it."
  [model usage]
  (when (and *usage* usage)
    (swap! *usage* update-in [:by-model model]
           (fn [existing]
             {:input_tokens (+ (:input_tokens existing 0) (:input_tokens usage 0))
              :output_tokens (+ (:output_tokens existing 0) (:output_tokens usage 0))
              :calls (inc (:calls existing 0))}))
    (when *budget*
      (when-let [cost (current-cost *usage*)]
        (when (> cost *budget*)
          (throw (ex-info (format "Budget exceeded: $%.4f spent (limit $%.4f)" cost *budget*)
                          {:type :budget-exceeded :cost cost :budget *budget*})))))))

(defn usage-summary
  "Compute a summary from accumulated usage data.
   Returns {:by-model {model {:input_tokens N :output_tokens N :calls N :cost F}}
            :total {:input_tokens N :output_tokens N :calls N :cost F}}"
  [usage-atom]
  (let [by-model (:by-model @usage-atom)
        with-costs (into {}
                     (map (fn [[model stats]]
                            (let [[in-cost out-cost] (lookup-cost model)
                                  cost (when (and in-cost out-cost)
                                         (+ (* (:input_tokens stats) (/ in-cost 1000000.0))
                                            (* (:output_tokens stats) (/ out-cost 1000000.0))))]
                              [model (cond-> stats cost (assoc :cost cost))]))
                          by-model))
        total {:input_tokens (reduce + 0 (map :input_tokens (vals by-model)))
               :output_tokens (reduce + 0 (map :output_tokens (vals by-model)))
               :calls (reduce + 0 (map :calls (vals by-model)))
               :cost (let [costs (keep :cost (vals with-costs))]
                       (when (seq costs) (reduce + 0.0 costs)))}]
    {:by-model with-costs :total total}))

;; ---------------------------------------------------------------------------
;; Anthropic Provider
;; ---------------------------------------------------------------------------

(defn- make-http-client []
  (-> (HttpClient/newBuilder)
      (.build)))

(defn- anthropic-request [api-key model prompt system-prompt prefix]
  (let [messages (cond-> [{:role "user" :content prompt}]
                   prefix (conj {:role "assistant" :content (str/trimr prefix)}))
        body (cond-> {:model model
                      :max_tokens 4096
                      :messages messages}
               system-prompt (assoc :system system-prompt))
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
      {:text (->> (:content parsed)
                  (filter #(= (:type %) "text"))
                  (map :text)
                  (clojure.string/join "\n"))
       :usage (:usage parsed)})))

(defrecord AnthropicProvider [api-key model http-client]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (anthropic-request api-key effective-model prompt (:system opts) (:prefix opts))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-anthropic-response (.body response))]
          (track-usage! effective-model usage)
          text)
        (throw (ex-info "Anthropic API request failed"
                        {:status status :body (.body response)}))))))

(defn anthropic-provider
  "Create an Anthropic provider.

   Options:
   - :api-key - API key (default: ANTHROPIC_API_KEY env var)
   - :model - Model name (default: claude-sonnet-4-20250514)"
  ([] (anthropic-provider {}))
  ([{:keys [api-key model]
     :or {model "claude-sonnet-4-20250514"}}]
   (let [key (or api-key (System/getenv "ANTHROPIC_API_KEY"))]
     (when-not key
       (throw (ex-info "No API key provided. Set ANTHROPIC_API_KEY or pass :api-key"
                       {:env "ANTHROPIC_API_KEY"})))
     (->AnthropicProvider key model (make-http-client)))))

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

(defrecord OllamaProvider [base-url model http-client]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (ollama-request base-url effective-model prompt (:system opts) (:prefix opts))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-ollama-response (.body response))]
          (track-usage! effective-model usage)
          text)
        (throw (ex-info "Ollama API request failed"
                        {:status status :body (.body response)}))))))

(defn ollama-provider
  "Create an Ollama provider for local models.

   Options:
   - :base-url - Ollama API URL (default: OLLAMA_HOST env var or http://localhost:11434)
   - :model - Model name (default: llama3.2)"
  ([] (ollama-provider {}))
  ([{:keys [base-url model]
     :or {model "llama3.2"}}]
   (let [url (or base-url
                 (System/getenv "OLLAMA_HOST")
                 "http://localhost:11434")
         ;; Strip trailing slash if present
         url (str/replace url #"/$" "")]
     (->OllamaProvider url model (make-http-client)))))

;; ---------------------------------------------------------------------------
;; OpenAI Provider
;; ---------------------------------------------------------------------------

(defn- openai-request [api-key base-url model prompt system-prompt prefix]
  (let [messages (cond-> []
                   system-prompt (conj {:role "system" :content system-prompt})
                   true (conj {:role "user" :content prompt})
                   prefix (conj {:role "assistant" :content prefix}))
        body {:model model
              :messages messages
              :max_completion_tokens 4096}
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
      {:text (get-in parsed [:choices 0 :message :content] "")
       :usage {:input_tokens (:prompt_tokens (:usage parsed) 0)
               :output_tokens (:completion_tokens (:usage parsed) 0)}})))

(defrecord OpenAIProvider [api-key base-url model http-client]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (openai-request api-key base-url effective-model prompt (:system opts) (:prefix opts))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (parse-openai-response (.body response))]
          (track-usage! effective-model usage)
          text)
        (throw (ex-info "OpenAI API request failed"
                        {:status status :body (.body response)}))))))

(defn openai-provider
  "Create an OpenAI provider.

   Options:
   - :api-key  - API key (default: OPENAI_API_KEY env var)
   - :base-url - API base URL (default: https://api.openai.com/v1)
   - :model    - Model name (default: gpt-4o)"
  ([] (openai-provider {}))
  ([{:keys [api-key base-url model]
     :or {model "gpt-4o"
          base-url "https://api.openai.com/v1"}}]
   (let [key (or api-key (System/getenv "OPENAI_API_KEY"))
         url (str/replace (or base-url "https://api.openai.com/v1") #"/$" "")]
     (when-not key
       (throw (ex-info "No API key provided. Set OPENAI_API_KEY or pass :api-key"
                       {:env "OPENAI_API_KEY"})))
     (->OpenAIProvider key url model (make-http-client)))))

;; ---------------------------------------------------------------------------
;; Dummy Provider (for testing)
;; ---------------------------------------------------------------------------

(defrecord DummyProvider [response-fn]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt _opts]
    (response-fn prompt)))

(defn dummy-provider
  "Create a dummy provider for testing.

   Options:
   - :response - Static response string (default: \"Hello, world!\")
   - :response-fn - Function (prompt -> response) for dynamic responses

   If both provided, :response-fn takes precedence."
  ([] (dummy-provider {}))
  ([{:keys [response response-fn]
     :or {response "Hello, world!"}}]
   (->DummyProvider (or response-fn (constantly response)))))

;; ---------------------------------------------------------------------------
;; Dynamic provider binding
;; ---------------------------------------------------------------------------

(def ^:dynamic *provider*
  "Current LLM provider. Bind with `with-provider` or set with `set-provider!`."
  nil)

(defn set-provider!
  "Set the global default provider."
  [provider]
  (alter-var-root #'*provider* (constantly provider)))

(defmacro with-provider
  "Execute body with a specific provider bound."
  [provider & body]
  `(binding [*provider* ~provider]
     ~@body))

(defn- strip-code-fences
  "Strip markdown code fences from LLM responses.
   Handles ```lang\\n...\\n``` wrapping that some models produce."
  [s]
  (let [trimmed (str/trim s)]
    (if (str/starts-with? trimmed "```")
      (-> trimmed
          (str/replace-first #"^```\w*\r?\n?" "")
          (str/replace #"\r?\n?```\s*$" ""))
      s)))

(defn llm-call
  "Call the current provider with prompt. Uses *provider* or throws if unset.
   opts may include :system for system prompt."
  ([prompt] (llm-call prompt {}))
  ([prompt opts]
   (if *provider*
     (strip-code-fences (call-llm *provider* prompt opts))
     (throw (ex-info "No LLM provider set. Use set-provider! or with-provider."
                     {})))))
