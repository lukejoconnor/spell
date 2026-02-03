(ns spell.llm
  "LLM orchestration engine for Spell.

   Core loop: call LLM, concatenate prefix+response, parse, apply hooks, eval."
  (:require [spell.eval :as eval]
            [spell.hooks :as hooks]
            [spell.parse :as parse]
            [spell.prompt :as prompt]
            [spell.provider :as provider]
            [spell.trace :as trace]))

;; ---------------------------------------------------------------------------
;; LLM Engine
;; ---------------------------------------------------------------------------

(defn- -llm
  "Core llm: call LLM, concat prefix+response, parse, apply hooks, eval."
  [{:keys [call-fn builtins prelude]} prompt hooks]
  (when (and eval/*max-llm-depth* (>= eval/*llm-depth* eval/*max-llm-depth*))
    (throw (ex-info "LLM recursion limit exceeded"
                    {:depth eval/*llm-depth* :limit eval/*max-llm-depth*})))
  (let [indent    (apply str (repeat eval/*llm-depth* "  "))
        is-thunk  (or (seq? prompt) (list? prompt))
        prompt-str (if is-thunk (pr-str prompt) (str prompt))
        node-id   (when trace/*trace*
                    (trace/begin-node! trace/*trace-node-id*
                                       eval/*llm-depth* :default prompt-str))
        _         (when eval/*verbose*
                    (println (str indent "=== LLM Call (depth " eval/*llm-depth* ") ==="))
                    (println (str indent "Prompt: " (pr-str prompt))))
        response  (call-fn prompt-str)
        _         (when eval/*verbose*
                    (println (str indent "Response: " response)))
        raw       (str prompt-str response)
        balanced  (parse/balance-parens raw)
        forms     (parse/read-all balanced)
        all-forms (if (seq prelude) (concat prelude forms) forms)
        program   (if (> (count (vec all-forms)) 1)
                    (list* 'do all-forms)
                    (first all-forms))
        program'  (if (empty? hooks)
                    program
                    (hooks/apply-hooks hooks program))
        _         (when (and eval/*verbose* (seq hooks))
                    (println (str indent "Program (after hooks): " (pr-str program'))))
        [value err]
        (try
          [(first (binding [eval/*llm-depth*      (inc eval/*llm-depth*)
                            trace/*trace-node-id* node-id]
                    (eval/spell-eval program' {})))
           nil]
          (catch Exception e [nil e]))
        _  (when node-id
             (trace/complete-node! node-id
               {:response response
                :raw-text raw
                :program  program
                :hooked   (when (seq hooks) program')
                :value    value
                :error    err}))]
    (if err (throw err) value)))

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
        call-fn  (fn [prompt-str]
                   (provider/llm-call prompt-str
                     (cond-> {:system sys-prompt :prefix prompt-str}
                       model (assoc :model model))))
        config   {:call-fn call-fn :builtins variant-builtins :prelude prelude}
        wrap-nl  (fn [p]
                   (let [s (if (or (seq? p) (list? p)) (pr-str p) (str p))]
                     (if (.startsWith (.trim ^String s) "(")
                       p
                       (str "(quine completion (spell-eval (do "
                            "(def prompt \"" (parse/escape-string s) "\") "))))
        the-llm  (fn the-llm
                   ([prompt] (the-llm prompt []))
                   ([prompt hooks]
                    (let [prompt' (if (or (seq? prompt) (list? prompt))
                                   (eval/expand-expr prompt (or eval/*spell-env* {}))
                                   prompt)]
                      (binding [eval/*builtins* variant-builtins]
                        (-llm config (wrap-nl prompt') hooks)))))]
    (reset! self-ref the-llm)
    the-llm))

(defn make-leaf-llm
  "Factory: create a plain text-in/text-out LLM function.
   No Spell parsing, evaluation, tools, or sub-agents.

   Options:
   - :system - system prompt string (default: generic assistant)
   - :model  - optional model name override (nil uses provider default)

   Returns (fn [prompt] response-string)."
  ([] (make-leaf-llm {}))
  ([{:keys [system model]
     :or {system "You are a helpful assistant. Respond concisely."}}]
   (fn [prompt]
     (let [prompt-str (str prompt)
           node-id  (when trace/*trace*
                      (trace/begin-node! trace/*trace-node-id*
                                         eval/*llm-depth* :leaf prompt-str))
           indent   (apply str (repeat eval/*llm-depth* "  "))
           _        (when eval/*verbose*
                      (println (str indent "=== Leaf LLM Call (depth " eval/*llm-depth* ") ==="))
                      (println (str indent "Prompt: " (pr-str prompt))))
           response (provider/llm-call prompt-str
                      (cond-> {:system system}
                        model (assoc :model model)))
           _        (when eval/*verbose*
                      (println (str indent "Response: " response)))
           _        (when node-id
                      (trace/complete-node! node-id
                        {:response response :raw-text response :value response}))]
       response))))
