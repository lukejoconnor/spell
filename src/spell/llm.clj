(ns spell.llm
  "LLM provider abstraction for Spell.

   Providers implement the LLMProvider protocol. Two built-in:
   - anthropic-provider: Calls Claude API
   - dummy-provider: Returns canned responses for testing"
  (:require [clojure.data.json :as json])
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
;; Anthropic Provider
;; ---------------------------------------------------------------------------

(defn- make-http-client []
  (-> (HttpClient/newBuilder)
      (.build)))

(defn- anthropic-request [api-key model prompt system-prompt]
  (let [body (cond-> {:model model
                      :max_tokens 4096
                      :messages [{:role "user" :content prompt}]}
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
      ;; Extract text from content blocks
      (->> (:content parsed)
           (filter #(= (:type %) "text"))
           (map :text)
           (clojure.string/join "\n")))))

(defrecord AnthropicProvider [api-key model http-client]
  LLMProvider
  (call-llm [this prompt] (call-llm this prompt {}))
  (call-llm [_ prompt opts]
    (let [effective-model (or (:model opts) model)
          request (anthropic-request api-key effective-model prompt (:system opts))
          response (.send http-client request (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)]
      (if (<= 200 status 299)
        (parse-anthropic-response (.body response))
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

(defn llm-call
  "Call the current provider with prompt. Uses *provider* or throws if unset.
   opts may include :system for system prompt."
  ([prompt] (llm-call prompt {}))
  ([prompt opts]
   (if *provider*
     (call-llm *provider* prompt opts)
     (throw (ex-info "No LLM provider set. Use set-provider! or with-provider."
                     {})))))

;; ---------------------------------------------------------------------------
;; Convenience
;; ---------------------------------------------------------------------------

(comment
  ;; Testing with dummy provider
  (with-provider (dummy-provider)
    (llm-call "What is 2+2?"))
  ;; => "Hello, world!"

  ;; Custom dummy responses
  (with-provider (dummy-provider {:response-fn (fn [p] (str "Echo: " p))})
    (llm-call "test"))
  ;; => "Echo: test"

  ;; Anthropic (requires API key)
  (set-provider! (anthropic-provider))
  (llm-call "Say hello in exactly 3 words.")
  )
