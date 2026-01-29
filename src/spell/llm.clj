(ns spell.llm
  "LLM provider abstraction and orchestration engine for Spell.

   Providers implement the LLMProvider protocol. Two built-in:
   - anthropic-provider: Calls Claude API
   - dummy-provider: Returns canned responses for testing

   Engine layer: llm-impl, make-call-now, make-llm."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.parse :as parse]
            [spell.prompt :as prompt])
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

(defn track-usage!
  "Add usage data to the *usage* atom if bound."
  [model usage]
  (when (and *usage* usage)
    (swap! *usage* update-in [:by-model model]
           (fn [existing]
             {:input_tokens (+ (:input_tokens existing 0) (:input_tokens usage 0))
              :output_tokens (+ (:output_tokens existing 0) (:output_tokens usage 0))
              :calls (inc (:calls existing 0))}))))

(def ^:private model-costs
  "Cost per million tokens: {model-prefix [input-cost output-cost]}"
  {"claude-3-5-haiku"  [0.80 4.00]
   "claude-sonnet-4"   [3.00 15.00]
   "claude-opus-4"     [15.00 75.00]})

(defn- lookup-cost
  "Find cost for a model ID by prefix matching."
  [model-id]
  (some (fn [[prefix costs]]
          (when (.startsWith ^String model-id prefix) costs))
        model-costs))

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
          request (anthropic-request api-key effective-model prompt (:system opts))
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

;; ---------------------------------------------------------------------------
;; LLM Engine: implementation, call-now, factory
;; ---------------------------------------------------------------------------

(def ^:private max-retries
  "Number of times to retry a failed llm call before returning error."
  2)

(defn- make-call-now
  "Create a call-now closure for continuing an LLM generation with tool results.
   completion-str: the raw completion string from the current LLM call.
   hooks: hooks to apply to continuation code.
   sys-prompt: system prompt for continuation LLM calls.
   model-override: optional model name."
  [completion-str hooks sys-prompt model-override]
  (fn [bindings-map]
    (when-not (map? bindings-map)
      (throw (ex-info "call-now: argument must be a map" {:got bindings-map})))
    (let [indent (apply str (repeat eval/*llm-depth* "  "))
          ;; Format bindings as def forms
          def-strs (map (fn [[k v]]
                          (str "(def " (name k) " " (pr-str (eval/quote-value v)) ")"))
                        bindings-map)
          result-text (str/join " " def-strs)
          ;; Extend the completion prefix
          new-prefix (str completion-str "\n" result-text "\n")
          _ (when eval/*verbose*
              (println (str indent "=== call-now ==="))
              (println (str indent "Bindings: " (pr-str (into {} (map (fn [[k v]] [(name k) v]) bindings-map)))))
              (println (str indent "Continuation prefix length: " (count new-prefix))))
          ;; Call LLM to continue
          call-opts (cond-> {:system sys-prompt}
                      model-override (assoc :model model-override))
          continuation (llm-call new-prefix call-opts)
          _ (when eval/*verbose*
              (println (str indent "Continuation: " continuation)))
          ;; Parse continuation text (tool result defs + model's continuation)
          new-text (str result-text "\n" continuation)
          balanced (parse/balance-parens new-text)
          forms (parse/read-all balanced)
          ;; Create new call-now for recursive use
          extended-completion (str new-prefix continuation)
          new-call-now (make-call-now extended-completion hooks sys-prompt model-override)
          ;; Build program with completion as proper binding (not injected into env)
          ;; Prepend (def completion "...") so it's a real def like in initial call
          completion-def (list 'def 'completion extended-completion)
          all-forms (cons completion-def forms)
          program (list* 'do all-forms)
          program' (if (empty? hooks)
                     program
                     (hooks/apply-hooks hooks program))
          _ (when (and eval/*verbose* (seq hooks))
              (println (str indent "Continuation program (after hooks): " (pr-str program'))))
          ;; Build env with call-now + tool result bindings (completion now via def, not injection)
          eval-env (reduce (fn [e [k v]]
                             (assoc e (symbol (name k)) v))
                           {'call-now new-call-now}
                           bindings-map)
          ;; Evaluate continuation
          [value _] (binding [eval/*llm-depth* (inc eval/*llm-depth*)]
                      (eval/spell-eval program' eval-env))]
      value)))

(defn- llm-impl
  "Core llm implementation. Assumes eval/*builtins* is already bound by the caller.
   sys-prompt: the system prompt string for this llm variant.
   model-override: optional model name (nil to use provider default)."
  [prompt hooks sys-prompt model-override]
  (when (and eval/*max-llm-depth* (>= eval/*llm-depth* eval/*max-llm-depth*))
    (throw (ex-info "LLM recursion limit exceeded"
                    {:depth eval/*llm-depth* :limit eval/*max-llm-depth*})))
  (let [indent (apply str (repeat eval/*llm-depth* "  "))
        is-thunk (or (seq? prompt) (list? prompt))
        prompt-str (if is-thunk (pr-str prompt) (str prompt))
        parent-code-binding (when is-thunk
                              (str "(def parent-code '" (pr-str prompt) ") "))
        ;; Structure: (def interior (do (def completion ...) (def prefix ...) (def response ...)))
        ;; completion is naturally bound via uneval, no special injection needed
        wrapped-prompt (str "(def interior (do "
                           "(def completion (cat \"(def interior \" (pr-str (uneval 'interior)) \")\")) "
                           "(def prefix \"" (parse/escape-string prompt-str) "\") "
                           (or parent-code-binding "")
                           "(def response ")]
    (when eval/*verbose*
      (println (str indent "=== LLM Call (depth " eval/*llm-depth* ") ==="))
      (println (str indent "Prompt: " (pr-str prompt))))
    ;; Retry loop: attempt up to (1 + max-retries) times
    (loop [attempt 0
           last-response nil
           last-error nil]
      (if (> attempt max-retries)
        ;; All attempts exhausted, return error string
        (do
          (when eval/*verbose*
            (println (str indent "All " (inc max-retries) " attempts failed, returning error")))
          (eval/format-llm-error last-response last-error))
        ;; Try LLM call + eval
        (let [call-opts (cond-> {:system sys-prompt}
                          model-override (assoc :model model-override))
              response (llm-call wrapped-prompt call-opts)]
          (when eval/*verbose*
            (when (pos? attempt)
              (println (str indent "Retry attempt " attempt)))
            (println (str indent "Response: " response)))
          (let [result (try
                         (let [raw-completion (str wrapped-prompt response)
                               completion (parse/balance-parens raw-completion)
                               _ (when (and eval/*verbose* (not= completion raw-completion))
                                   (println (str indent "(auto-balanced parens)")))
                               parsed (read-string completion)
                               ;; Apply hooks to transform completion into program
                               program (if (empty? hooks)
                                         parsed
                                         (hooks/apply-hooks hooks parsed))
                               _ (when (and eval/*verbose* (seq hooks))
                                   (println (str indent "Program (after hooks): " (pr-str program))))
                               call-now-fn (make-call-now raw-completion hooks sys-prompt model-override)
                               ;; completion is now bound via (def interior ...) using uneval
                               ;; only call-now needs to be injected
                               initial-env {'call-now call-now-fn}
                               [value _] (binding [eval/*llm-depth* (inc eval/*llm-depth*)]
                                           (eval/spell-eval program initial-env))]
                           {:success true :value value})
                         (catch Exception e
                           (when eval/*verbose*
                             (println (str indent "Error: " (ex-message e))))
                           {:success false :response response :error e}))]
            (if (:success result)
              (:value result)
              (recur (inc attempt) (:response result) (:error result)))))))))

(defn make-llm
  "Factory: create an llm function with specific tools and agent access.

   Options:
   - :tools - vector of tool maps {:name sym, :fn f, :doc str}
   - :llms  - map of {symbol fn-or-var} for available agent functions.
              Use 'llm with a var ref for self-recursion: {'llm #'my-var}
              Values can also be maps with :fn and :doc for prompt generation.
   - :model - optional model name override (nil uses provider default)

   Returns a function with the same signature as llm:
   (f prompt) or (f prompt hooks)."
  [{:keys [tools llms model]
    :or {tools [] llms {}}}]
  (let [tool-builtins (into {} (map (fn [{:keys [name fn]}] [name fn]) tools))
        ;; Extract fns from llm entries (support both bare fns and {:fn f :doc d} maps)
        llm-builtins (into {} (map (fn [[sym v]]
                                     [sym (if (map? v) (:fn v) v)])
                                   llms))
        variant-builtins (merge eval/core-builtins
                                {'prepend-hooks-to-llm #'hooks/prepend-hooks-to-llm
                                 'recurse #'hooks/recurse
                                 'prefix-prompt #'hooks/prefix-prompt
                                 'with-env hooks/with-env
                                 'with-env-hints hooks/with-env-hints}
                                tool-builtins
                                llm-builtins)
        ;; For prompt generation, normalize llm entries to include :doc
        llms-for-prompt (into {} (map (fn [[sym v]]
                                        [sym (if (map? v) v {:fn v})])
                                      llms))
        sys-prompt (prompt/generate-system-prompt tools llms-for-prompt)]
    (fn the-llm
      ([prompt] (the-llm prompt []))
      ([prompt hooks]
       (binding [eval/*builtins* variant-builtins]
         (llm-impl prompt hooks sys-prompt model))))))
