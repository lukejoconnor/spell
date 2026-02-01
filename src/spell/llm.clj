(ns spell.llm
  "LLM orchestration engine for Spell.

   Engine layer: llm-impl, eval-forms, make-llm."
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.parse :as parse]
            [spell.prompt :as prompt]
            [spell.provider :as provider]))

;; ---------------------------------------------------------------------------
;; LLM Engine
;; ---------------------------------------------------------------------------

(def ^:private max-retries
  "Number of times to retry a failed llm call before returning error."
  2)

(defn- eval-forms
  "Apply hooks, inject call-now, evaluate program.
   call-now is a closure that extends the completion prefix and recurses."
  [program hooks raw-completion sys-prompt model-override prelude prompt-str extra-env]
  (let [indent (apply str (repeat eval/*llm-depth* "  "))
        program' (if (empty? hooks)
                   program
                   (hooks/apply-hooks hooks program))
        _ (when (and eval/*verbose* (seq hooks))
            (println (str indent "Program (after hooks): " (pr-str program'))))
        call-now-fn
        (fn [bindings-map]
          (when-not (map? bindings-map)
            (throw (ex-info "call-now: argument must be a map" {:got bindings-map})))
          (let [def-strs (map (fn [[k v]]
                                (str "(def " (name k) " " (pr-str (eval/quote-value v)) ")"))
                              bindings-map)
                result-text (str/join " " def-strs)
                new-prefix (str raw-completion "\n" result-text "\n")
                _ (when eval/*verbose*
                    (println (str indent "=== call-now ==="))
                    (println (str indent "Bindings: " (pr-str (into {} (map (fn [[k v]] [(name k) v]) bindings-map)))))
                    (println (str indent "Continuation prefix length: " (count new-prefix))))
                call-opts (cond-> {:system sys-prompt :prefix new-prefix}
                            model-override (assoc :model model-override))
                continuation (provider/llm-call prompt-str call-opts)
                _ (when eval/*verbose*
                    (println (str indent "Continuation: " continuation)))
                new-text (str result-text "\n" continuation)
                balanced (parse/balance-parens new-text)
                forms (parse/read-all balanced)
                extended-completion (str new-prefix continuation)
                completion-def (list 'def 'completion extended-completion)
                all-forms (cons completion-def forms)
                cont-program (list* 'do (concat prelude all-forms))
                cont-extra-env (reduce (fn [e [k v]]
                                         (assoc e (symbol (name k)) v))
                                       {} bindings-map)]
            (eval-forms cont-program hooks extended-completion sys-prompt
                        model-override prelude prompt-str cont-extra-env)))
        env (merge {'call-now call-now-fn} extra-env)
        [value _] (binding [eval/*llm-depth* (inc eval/*llm-depth*)]
                    (eval/spell-eval program' env))]
    value))

(defn- build-fresh-prefix
  "Build the assistant-turn prefix for a fresh llm call.
   Returns the prefix string that the LLM continues from."
  [prompt-str is-thunk prelude]
  (let [parent-code-binding (when is-thunk
                              (str "(def parent-code '" (pr-str (read-string prompt-str)) ") "))
        prelude-str (when (seq prelude)
                      (str (str/join " " (map pr-str prelude)) " "))]
    (str (when (seq prelude) "(do ")
         (or prelude-str "")
         "(def interior (do "
         "(def completion (cat \"(def interior \" (pr-str (uneval 'interior)) \")\")) "
         "(def prefix \"" (parse/escape-string prompt-str) "\") "
         (or parent-code-binding ""))))

(defn- llm-impl
  "Core llm implementation. Assumes eval/*builtins* is already bound by the caller.
   sys-prompt: the system prompt string for this llm variant.
   model-override: optional model name (nil to use provider default).
   prelude: vector of Spell forms prepended in an outer do block."
  [prompt hooks sys-prompt model-override prelude]
  (when (and eval/*max-llm-depth* (>= eval/*llm-depth* eval/*max-llm-depth*))
    (throw (ex-info "LLM recursion limit exceeded"
                    {:depth eval/*llm-depth* :limit eval/*max-llm-depth*})))
  (let [indent (apply str (repeat eval/*llm-depth* "  "))
        is-thunk (or (seq? prompt) (list? prompt))
        prompt-str (if is-thunk (pr-str prompt) (str prompt))
        wrapped-prompt (build-fresh-prefix prompt-str is-thunk prelude)]
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
        (let [call-opts (cond-> {:system sys-prompt :prefix wrapped-prompt}
                          model-override (assoc :model model-override))
              response (provider/llm-call prompt-str call-opts)]
          (when eval/*verbose*
            (when (pos? attempt)
              (println (str indent "Retry attempt " attempt)))
            (println (str indent "Response: " response)))
          (let [result (try
                         (let [raw-completion (str wrapped-prompt response)
                               balanced (parse/balance-parens raw-completion)
                               _ (when (and eval/*verbose* (not= balanced raw-completion))
                                   (println (str indent "(auto-balanced parens)")))
                               parsed (read-string balanced)]
                           {:success true
                            :value (eval-forms parsed hooks raw-completion sys-prompt
                                               model-override prelude prompt-str {})})
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
   - :tools   - vector of tool maps {:name sym, :fn f, :doc str}
   - :llms    - map of {symbol fn-or-var} for available agent functions.
                Use 'llm with a var ref for self-recursion: {'llm #'my-var}
                Values can also be maps with :fn and :doc for prompt generation.
   - :model   - optional model name override (nil uses provider default)
   - :prelude - vector of Spell forms prepended as library definitions.
                Wrapped in an outer (do ...) block before the program body.

   Returns a function with the same signature as llm:
   (f prompt) or (f prompt hooks).

   The returned function is automatically available as 'llm-self in Spell code,
   providing self-recursion without needing to wire up var refs."
  [{:keys [tools llms model prelude]
    :or {tools [] llms {} prelude []}}]
  (let [self-ref (atom nil)
        self-fn (fn llm-self
                  ([prompt] (@self-ref prompt))
                  ([prompt hooks] (@self-ref prompt hooks)))
        tool-builtins (into {} (map (fn [{:keys [name fn]}] [name fn]) tools))
        ;; Extract fns from llm entries (support both bare fns and {:fn f :doc d} maps)
        llm-builtins (into {} (map (fn [[sym v]]
                                     [sym (if (map? v) (:fn v) v)])
                                   llms))
        variant-builtins (merge eval/core-builtins
                                {'prepend-hooks-to-llm #'hooks/prepend-hooks-to-llm
                                 'recurse #'hooks/recurse
                                 'prefix-prompt #'hooks/prefix-prompt
                                 'with-env hooks/with-env
                                 'with-env-hints hooks/with-env-hints
                                 'llm-self self-fn}
                                tool-builtins
                                llm-builtins)
        ;; For prompt generation, normalize llm entries to include :doc
        llms-for-prompt (into {} (map (fn [[sym v]]
                                        [sym (if (map? v) v {:fn v})])
                                      llms))
        sys-prompt (prompt/generate-system-prompt tools llms-for-prompt)
        the-llm (fn the-llm
                  ([prompt] (the-llm prompt []))
                  ([prompt hooks]
                   (binding [eval/*builtins* variant-builtins]
                     (llm-impl prompt hooks sys-prompt model prelude))))]
    (reset! self-ref the-llm)
    the-llm))
