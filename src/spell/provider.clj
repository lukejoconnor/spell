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
   Atom value is a map: {:by-model {\"model\" {:input_tokens N :output_tokens N :calls N
                                               :cache_creation_input_tokens N :cache_read_input_tokens N}}}"
  nil)

(def ^:dynamic *budget*
  "When set to a number, throws if cumulative cost exceeds this amount (in dollars)."
  nil)

(def ^:private model-costs
  "Cost per million tokens: {model-prefix [input-cost output-cost]}"
  {"claude-3-5-haiku"  [0.80 4.00]
   "claude-haiku-4-5"  [1.00 5.00]
   "claude-sonnet-5"   [3.00 15.00]
   "claude-sonnet-4"   [3.00 15.00]
   "claude-opus-4-5"   [15.00 75.00]
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
   "gpt-5.2"           [1.75 14.00]})

(defn- lookup-cost
  "Find cost for a model ID by prefix matching."
  [model-id]
  (some (fn [[prefix costs]]
          (when (.startsWith ^String model-id prefix) costs))
        model-costs))

(defn current-cost
  "Compute total cost in dollars from accumulated usage data.
   Returns nil if no models have known pricing.
   Accounts for cache pricing: cache writes 1.25x, cache reads 0.1x normal input."
  [usage-atom]
  (let [by-model (:by-model @usage-atom)
        costs (keep (fn [[model stats]]
                      (when-let [[in-cost out-cost] (lookup-cost model)]
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
   Throws ex-info with {:type :budget-exceeded} if *budget* is set and cumulative cost exceeds it."
  [model usage]
  (when (and *usage* usage)
    (swap! *usage* update-in [:by-model model]
           (fn [existing]
             {:input_tokens (+ (:input_tokens existing 0) (:input_tokens usage 0))
              :output_tokens (+ (:output_tokens existing 0) (:output_tokens usage 0))
              :cache_creation_input_tokens (+ (:cache_creation_input_tokens existing 0)
                                              (:cache_creation_input_tokens usage 0))
              :cache_read_input_tokens (+ (:cache_read_input_tokens existing 0)
                                          (:cache_read_input_tokens usage 0))
              :calls (inc (:calls existing 0))}))
    (when *budget*
      (when-let [cost (current-cost *usage*)]
        (when (> cost *budget*)
          (throw (ex-info (format "Budget exceeded: $%.4f spent (limit $%.4f)" cost *budget*)
                          {:type :budget-exceeded :cost cost :budget *budget*})))))))

(defn usage-summary
  "Compute a summary from accumulated usage data.
   Returns {:by-model {model {:input_tokens N :output_tokens N :calls N :cost F
                              :cache_creation_input_tokens N :cache_read_input_tokens N}}
            :total {:input_tokens N :output_tokens N :calls N :cost F
                    :cache_creation_input_tokens N :cache_read_input_tokens N}}"
  [usage-atom]
  (let [by-model (:by-model @usage-atom)
        with-costs (into {}
                     (map (fn [[model stats]]
                            (let [[in-cost out-cost] (lookup-cost model)
                                  cost (when (and in-cost out-cost)
                                         (let [base-input (* (:input_tokens stats 0) (/ in-cost 1000000.0))
                                               cache-write (* (:cache_creation_input_tokens stats 0) (/ in-cost 1000000.0) 1.25)
                                               cache-read (* (:cache_read_input_tokens stats 0) (/ in-cost 1000000.0) 0.1)
                                               output (* (:output_tokens stats 0) (/ out-cost 1000000.0))]
                                           (+ base-input cache-write cache-read output)))]
                              [model (cond-> stats cost (assoc :cost cost))]))
                          by-model))
        total {:input_tokens (reduce + 0 (map #(:input_tokens % 0) (vals by-model)))
               :output_tokens (reduce + 0 (map #(:output_tokens % 0) (vals by-model)))
               :cache_creation_input_tokens (reduce + 0 (map #(:cache_creation_input_tokens % 0) (vals by-model)))
               :cache_read_input_tokens (reduce + 0 (map #(:cache_read_input_tokens % 0) (vals by-model)))
               :calls (reduce + 0 (map #(:calls % 0) (vals by-model)))
               :cost (let [costs (keep :cost (vals with-costs))]
                       (when (seq costs) (reduce + 0.0 costs)))}]
    {:by-model with-costs :total total}))

;; ---------------------------------------------------------------------------
;; Anthropic Provider
;; ---------------------------------------------------------------------------

(defn- make-http-client []
  (-> (HttpClient/newBuilder)
      (.build)))

(defn- anthropic-request [api-key model prompt system-prompt prefix max-tokens]
  (let [messages (cond-> [{:role "user" :content prompt}]
                   prefix (conj {:role "assistant" :content (str/trimr prefix)}))
        ;; Use cache_control for system prompt to enable prompt caching
        ;; First request pays +25% write cost, subsequent requests get -90% read discount
        cached-system (when system-prompt
                        [{:type "text"
                          :text system-prompt
                          :cache_control {:type "ephemeral"}}])
        body (cond-> {:model model
                      :max_tokens (or max-tokens 16384)
                      :messages messages}
               cached-system (assoc :system cached-system))
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

(defrecord AnthropicProvider [api-key model max-tokens http-client]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (anthropic-request api-key effective-model prompt (:system opts) (:prefix opts) max-tokens)
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
   - :model - Model name (default: claude-sonnet-4-20250514)
   - :max-tokens - Max tokens per response (default: 16384)"
  ([] (anthropic-provider {}))
  ([{:keys [api-key model max-tokens]
     :or {model "claude-sonnet-4-5-20250929"}}]
   (let [key (or api-key (System/getenv "ANTHROPIC_API_KEY"))]
     (when-not key
       (throw (ex-info "No API key provided. Set ANTHROPIC_API_KEY or pass :api-key"
                       {:env "ANTHROPIC_API_KEY"})))
     (->AnthropicProvider key model max-tokens (make-http-client)))))

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

(defn- responses-model?
  "Does this model require the OpenAI Responses API instead of Chat Completions?"
  [model]
  (some #(str/includes? model %) ["codex"]))

(defn- openai-responses-request [api-key base-url model prompt system-prompt max-tokens]
  (let [body (cond-> {:model model
                      :input prompt}
               system-prompt (assoc :instructions system-prompt)
               max-tokens (assoc :max_output_tokens max-tokens))
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
      {:text (:output_text parsed "")
       :usage {:input_tokens (get-in parsed [:usage :input_tokens] 0)
               :output_tokens (get-in parsed [:usage :output_tokens] 0)}})))

(defn- openai-request [api-key base-url model prompt system-prompt _prefix max-tokens]
  (let [messages (cond-> []
                   system-prompt (conj {:role "system" :content system-prompt})
                   true (conj {:role "user" :content prompt}))
        body {:model model
              :messages messages
              :max_completion_tokens (or max-tokens 16384)}
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

(defrecord OpenAIProvider [api-key base-url model max-tokens http-client]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          responses? (responses-model? effective-model)
          request (if responses?
                    (openai-responses-request api-key base-url effective-model prompt (:system opts) max-tokens)
                    (openai-request api-key base-url effective-model prompt (:system opts) (:prefix opts) max-tokens))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (let [{:keys [text usage]} (if responses?
                                     (parse-openai-responses-response (.body response))
                                     (parse-openai-response (.body response)))]
          (track-usage! effective-model usage)
          text)
        (throw (ex-info "OpenAI API request failed"
                        {:status status :body (.body response)}))))))

(defn openai-provider
  "Create an OpenAI provider.

   Options:
   - :api-key    - API key (default: OPENAI_API_KEY env var)
   - :base-url   - API base URL (default: https://api.openai.com/v1)
   - :model      - Model name (default: gpt-4o)
   - :max-tokens - Max tokens per response (default: 16384)"
  ([] (openai-provider {}))
  ([{:keys [api-key base-url model max-tokens]
     :or {model "gpt-4o"
          base-url "https://api.openai.com/v1"}}]
   (let [key (or api-key (System/getenv "OPENAI_API_KEY"))
         url (str/replace (or base-url "https://api.openai.com/v1") #"/$" "")]
     (when-not key
       (throw (ex-info "No API key provided. Set OPENAI_API_KEY or pass :api-key"
                       {:env "OPENAI_API_KEY"})))
     (->OpenAIProvider key url model max-tokens (make-http-client)))))

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
;; User Provider (interactive simulation)
;; ---------------------------------------------------------------------------

(defrecord UserProvider []
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
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
        (println prompt)
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
