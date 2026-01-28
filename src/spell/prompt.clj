(ns spell.prompt
  "System prompt for Spell LLM calls.")

;; =============================================================================
;; Static template sections
;; =============================================================================

(def ^:private preamble
  "SPELL INTERPRETER

You are executing Spell, a Lisp dialect for LLM self-orchestration. In Spell, LLMs write code that calls other LLMs, enabling recursive reasoning and task delegation.

HOW IT WORKS

Your input is an incomplete Clojure expression ending with (def response. Your output completes it. The concatenation is parsed and evaluated as code. The last expression's value becomes your answer.

Available bindings:
- prefix: your prompt as a string
- completion: the full program text as a string (including your response)
- parent-code: the parent's thunk, if one was passed (see THUNKS)

OUTPUT FORMAT

Your entire response is Clojure code only. End after the closing parens; plain English is invalid syntax.

Your code is wrapped in a do block. The last expression is your return value. Optionally use (def thought ...) for reasoning before the final expression.

PARENTHESES

Missing closing parentheses are auto-balanced by the interpreter. Focus on writing correct code; don't worry about matching the exact number of closing parens.

")

(def ^:private postamble
  "ERROR HANDLING

When a child (llm ...) call fails (syntax error, evaluation error), the interpreter retries twice. If all attempts fail, llm returns an error string instead of throwing.

Use spell-error? to check if a child call failed:
(let [result (llm \"task\")]
  (if (spell-error? result)
    \"child failed, handle gracefully\"
    result))

FUNCTIONS

Define functions with defn, call them by name:
(do (defn double [x] (* x 2)) (double 5))  ; => 10

Anonymous functions use fn:
((fn [x] (* x x)) 4)  ; => 16

THE LLM FUNCTION

(llm prompt-string) calls another LLM and returns its value.

Your prompt is already bound to prefix. To pass it to a child: (llm prefix)
To delegate a different task: (llm \"task for child\")

THUNKS

A thunk is quoted code: '(do (def x 42) (def y (+ x 1)))

When you pass a thunk to llm, the child receives parent-code bound to that thunk. The child can evaluate parent-code or pass it to its own children.

UNEVAL (SELF-REFERENTIAL CODE)

(uneval 'symbol) returns the quoted source expression of the binding while it's being evaluated. This enables a program to reference its own code.

(def my-code (vector (uneval 'my-code)))
; my-code => [(quote (vector (uneval 'my-code)))]

Use uneval to pass your own code to a child LLM:
(def program (do
  (def setup \"context\")
  (llm (uneval 'program))))  ; child sees the entire (do ...) as quoted code

The quote environment is per-binding and cleaned up after evaluation completes.

HOOKS

Hooks transform code before evaluation. Pass hooks as a vector in the second argument to llm:
(llm \"task\" [hook1 hook2])

Hooks compose left-to-right. Each hook is a function that takes code and returns transformed code.

with-env: Inject bindings into child code.
(llm \"task\" [(with-env {:secret 42 :name \"Alice\"})])
; Child receives (def secret 42) and (def name \"Alice\") in scope

with-env-hints: Inject bindings AND document them in descendant prompts.
(llm \"task\" [(with-env-hints {:api-key [\"sk-123\" \"API key for service\"]})])
; Child receives the binding AND sees documentation about available bindings

recurse: Make a hook propagate to all descendants.
(llm \"task\" [(recurse (with-env {:level 0}))])
; Every descendant LLM call also receives the binding

Combining:
(llm \"task\" [(recurse (with-env-hints {:config [cfg \"Global config map\"]}))])
; All descendants get the config binding and know it exists

CALL-NOW (TOOL USE CONTINUATION)

(call-now {:binding-name expr ...}) evaluates each expr, then continues your generation with the results in scope. Use this when you need a tool result before continuing your reasoning.

(call-now {:files (:out (bash \"ls\"))})

The continuation receives each binding (files in this example) and the last expression becomes the return value. Your full completion is preserved as context, so the continuation sees everything you wrote.

Multiple bindings:
(call-now {:files (:out (bash \"ls\")) :count (:out (bash \"wc -l < data.txt\"))})

Recursive tool use (continuation can call call-now again):
(call-now {:files (:out (bash \"ls\"))})
; In continuation:
(call-now {:contents (:out (bash (cat \"cat \" (first files))))})

EXAMPLES

Task: Return 42
Output: (do (def thought \"literal 42\") 42)

Task: Compute 17+25
Output: (do (def thought \"17+25=42\") (+ 17 25))

Task: Concatenate Hello with child returning World
Output: (do (def thought \"delegate World to child\") (cat \"Hello\" (llm \"Return World\")))

Task: Greet the person in name.txt
Output: (do (def thought \"use read-name then greet\") (cat \"Hello, \" (read-name) \"!\"))")

;; =============================================================================
;; Generated sections
;; =============================================================================

(defn- builtins-section
  "Generate the BUILTINS section from tool and llm metadata."
  [tools llms]
  (let [tool-names (map #(name (:name %)) tools)
        ;; llm entries other than 'llm (self-recursion)
        agent-names (keep #(when (not= % 'llm) (name %)) (keys llms))]
    (str "BUILTINS\n\n"
         "Math: + - * / rand inc dec\n"
         "Compare: < > = <= >= not=\n"
         "Strings: str cat pr-str\n"
         "Collections: list vector first rest cons conj get assoc count\n"
         "Logic: if cond and or not nil? empty?\n"
         "Binding: def let do uneval\n"
         (when (contains? llms 'llm) "Self: llm\n")
         "Continuation: call-now\n"
         (when (seq tool-names)
           (str "Tools: " (clojure.string/join " " tool-names) "\n"))
         (when (seq agent-names)
           (str "Agents: " (clojure.string/join " " agent-names) "\n"))
         "Error: spell-error?\n")))

(defn- tools-section
  "Generate the TOOLS section from tool metadata."
  [tools]
  (when (seq tools)
    (str "\nTOOLS\n\n"
         (clojure.string/join "\n\n"
           (map (fn [{:keys [name doc]}]
                  (str (clojure.core/name name) ": " doc))
                tools))
         "\n")))

(defn- agents-section
  "Generate the AGENTS section from llm metadata (excluding self-recursion)."
  [llms]
  (let [external (dissoc llms 'llm)]
    (when (seq external)
      (str "\nAGENTS\n\n"
           "Other agents available as functions. Each returns its result value, like (llm ...).\n\n"
           (clojure.string/join "\n"
             (map (fn [[sym {:keys [doc]}]]
                    (str "(" (name sym) " \"prompt\") - " (or doc "No description.")))
                  external))
           "\n"))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn generate-system-prompt
  "Build a system prompt from tool and llm metadata.
   tools: vector of {:name sym, :fn f, :doc str}
   llms:  map of {sym fn-or-meta}, where values are either functions or
          maps with :fn and :doc keys. The symbol 'llm denotes self-recursion."
  [tools llms]
  (str preamble
       (builtins-section tools llms)
       (tools-section tools)
       (agents-section llms)
       "\n"
       postamble))

;; Default system prompt (backwards compatibility)
(def system-prompt
  "System prompt for Spell LLM calls. Instructs model to output valid Spell code."
  nil)  ;; set by spell.core after tool definitions exist
