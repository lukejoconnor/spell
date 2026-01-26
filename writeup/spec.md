# Spell Implementation Specification

## 1. Overview

Spell is a Lisp dialect for LLM self-orchestration. This document specifies how to implement the interpreter.

**Implementation language:** Clojure

**Key insight:** The interpreter evaluates S-expressions produced by an LLM. The LLM can invoke itself recursively via the `llm` primitive. Before passing expressions to child LLM calls, free variables are substituted with their values (expansion).

See `manuscript.md` for conceptual introduction. See `questions.md` for open design questions.

---

## 2. Core Data Structures

### 2.1 Environment

The environment maps symbols to values. Implemented as an immutable map.

```clojure
;; Environment is a map from symbols to values
(def empty-env {})

(defn extend-env [env name val]
  (assoc env name val))

(defn lookup [env name]
  (if-let [val (get env name)]
    val
    (throw (ex-info "Unbound variable" {:name name}))))
```

### 2.2 Values

Values are full S-expressions:
- Strings
- Numbers
- Symbols
- Lists (including nested lists)

---

## 3. The Interpreter: `spell-eval`

The core interpreter evaluates an expression in an environment.  Differs from `eval` in two ways:
1. It inputs an environment, looks up symbols in that environment, and returns an environment with bindings added to it
2. It whitelists certain built-ins; symbols are invalid unless they are in the environment, or whitelisted

```clojure
(defn spell-eval [expr env]
  (cond
    ;; Empty list: nil
    (empty? expr) nil
  
    ;; String literal: self-evaluating
    (string? expr) expr

    ;; Number literal: self-evaluating
    (number? expr) expr

    ;; Symbol: look up in environment
    (symbol? expr) (lookup env expr)

    ;; Lists and function calls
    (list? expr) (do
        ;; call spell-eval on each element 
        ;; call eval on the expression and update the env
        )

    :else (throw (ex-info "Unknown expression type" {:expr expr}))))

```

### 3.1 Progn with Bindings

`do` must thread environment updates from `setq`:

```clojure
(defn spell-eval-do [exprs env]
  (loop [remaining exprs
         current-env env
         last-val nil]
    (if (empty? remaining)
      last-val
      (let [expr (first remaining)]
        (if (and (list? expr) (= 'setq (first expr)))
          ;; Setq: evaluate and extend env
          (let [name (second expr)
                val (spell-eval (nth expr 2) current-env)
                new-env (extend-env current-env name val)]
            (recur (rest remaining) new-env val))
          ;; Other expression: evaluate in current env
          (let [val (spell-eval expr current-env)]
            (recur (rest remaining) current-env val)))))))
```

---

## 4. Function Application: `spell-apply`

Applies a function to evaluated arguments.

```clojure
(defn spell-apply [fn-name args env]
  (case fn-name
    ;; Arithmetic (if we support numbers)
    + (apply + args)
    - (apply - args)
    * (apply * args)
    / (apply / args)

    ;; List operations
    car (first (first args))
    cdr (rest (first args))
    cons (cons (first args) (second args))
    list args

    ;; String operations
    concat (apply str args)

    ;; Comparison
    = (apply = args)
    < (apply < args)
    > (apply > args)

    ;; The LLM primitive
    llm (call-llm (first args) env)

    ;; Expansion (substitute free variables)
    expand (expand-expr (first args) env)

    ;; Otherwise: look up user-defined function or tool
    (let [fn-def (get env fn-name)]
      (if fn-def
        (apply-user-fn fn-def args env)
        (apply-tool fn-name args)))))
```

### 4.1 Tool Functions

Tools are external functions (search, file operations, etc.). They're whitelisted.

```clojure
(def tools
  {'search (fn [query] (external-search query))
   'read-file (fn [path] (slurp path))
   ;; Add more tools here
   })

(defn apply-tool [name args]
  (if-let [tool-fn (get tools name)]
    (apply tool-fn args)
    (throw (ex-info "Unknown function" {:name name}))))
```

---

## 5. Expansion

The critical operation: substitute values for free variables in a quoted expression.

**Key distinction from quote:** `quote` returns the expression unevaluated. `expand` substitutes free variables (those defined outside the expression) while preserving internal bindings.

```clojure
;; Example:
(def y 41)
(def x '(+ 1 y))
(expand x)  ;; => '(+ 1 41)
;; y is free (defined outside x), so it's substituted

;; But:
(def x '(do (def y 41) (+ 1 y)))
(expand x)  ;; => '(do (def y 41) (+ 1 y))
;; y is internal (defined inside x), so it's preserved
```

### 5.1 Collect Internal Bindings

Find all names bound by `setq` inside an expression:

```clojure
(defn collect-bindings [expr]
  (cond
    (not (list? expr)) #{}
    (= 'setq (first expr)) (conj (collect-bindings (nth expr 2))
                                  (second expr))
    :else (apply clojure.set/union (map collect-bindings expr))))
```

### 5.2 Substitute Free Variables

```clojure
(defn expand-expr [expr env]
  (let [internal (collect-bindings expr)]
    (substitute-free expr env internal)))

(defn substitute-free [expr env internal]
  (cond
    ;; Symbol: substitute if free (not internal, but in env)
    (symbol? expr)
    (if (and (not (contains? internal expr))
             (contains? env expr))
      (get env expr)
      expr)

    ;; Not a list: return as-is
    (not (list? expr)) expr

    ;; Quote: don't descend (nested quotes are protected)
    (= 'quote (first expr)) expr

    ;; Setq: recurse into body, preserve name
    (= 'setq (first expr))
    (list 'setq
          (second expr)
          (substitute-free (nth expr 2) env internal))

    ;; Other list: recurse into all elements
    :else (map #(substitute-free % env internal) expr)))
```

### 5.3 Integration with LLM Calls

When calling `llm`, expand the argument first:

```clojure
(defn call-llm [prompt env]
  (let [expanded-prompt (expand-expr prompt env)]
    (invoke-llm-api expanded-prompt)))
```

---

## 6. The LLM Interface

### 6.1 Invoking the LLM

```clojure
(defn invoke-llm-api [prompt]
  ;; Convert prompt to string if needed
  ;; Call the actual LLM API
  ;; Parse response as S-expression
  ;; Return the result
  (let [prompt-str (pr-str prompt)  ; or format as needed
        response-str (call-anthropic-api prompt-str)
        response-expr (read-string response-str)]
    (spell-eval response-expr {})))  ; evaluate in fresh env? or inherited?
```

> **[DECISION NEEDED: Q2, Q10]** What context does the child LLM receive? What's the prompt format?

### 6.2 Completion Structure

> **[DECISION NEEDED: Q2]** Define the structure of a completion.

---

## 7. User-Defined Functions

> **[DECISION NEEDED: Q8]** How are functions defined?

Draft approach using closures:

```clojure
;; Function definition stored as a closure-like structure
{:params [doc]
 :body '(llm (concat "Summarize: " doc))
 :env captured-env}

(defn apply-user-fn [fn-def args env]
  (let [{:keys [params body closure-env]} fn-def
        extended-env (reduce (fn [e [p a]] (extend-env e p a))
                             closure-env
                             (map vector params args))]
    (spell-eval body extended-env)))
```

---

## 8. Parallel Execution

> **[DECISION NEEDED: Q6]** Parallelism model.

Draft using Clojure futures:

```clojure
;; In spell-apply:
future (future (spell-eval (first args) env))
force (deref (first args))
```

---

## 9. Entry Point

```clojure
(defn run-spell [program]
  (spell-eval program {}))

;; Example:
(run-spell '(do
              (setq greeting "hello")
              (concat greeting " world")))
;; => "hello world"
```

---

## 10. Implementation Roadmap

### Phase 1: Core Interpreter
- [ ] `spell-eval` for literals, symbols, quote, setq, do, if
- [ ] `spell-apply` for basic operations (concat, arithmetic, list ops)
- [ ] Environment threading
- [ ] REPL for testing

### Phase 2: Expansion
- [ ] `collect-bindings`
- [ ] `substitute-free`
- [ ] `expand-expr`
- [ ] Unit tests for expansion semantics

### Phase 3: LLM Integration
- [ ] `call-llm` with API integration
- [ ] Prompt formatting
- [ ] Response parsing
- [ ] Error handling for API failures

### Phase 4: Advanced Features
- [ ] User-defined functions
- [ ] Tools integration
- [ ] Parallel execution
- [ ] Recursion limits

---

## Appendix A: Examples

### A.1 Basic Evaluation

```clojure
(do
  (setq x "hello")
  (setq y "world")
  (concat x " " y))
;; => "hello world"
```

### A.2 LLM Call

```clojure
(do
  (setq query "What is 2+2?")
  (llm query))
;; => (LLM response)
```

### A.3 Expansion Example

```clojure
;; Given env = {x: 5, y: 10}
(expand-expr '(do
                (setq z 1)
                (+ x z))
             env)
;; => (do (setq z 1) (+ 5 z))
;; x substituted (free), z preserved (internal)
```

### A.4 Passing Context to Child

```clojure
(do
  (setq task "Write a poem")
  (setq draft (llm (concat "First draft: " task)))
  (llm (list 'do
             (list 'setq 'previous-draft draft)
             '(concat "Improve this: " previous-draft))))
```

---

## Appendix B: Comparison to Standard Lisp

| Aspect | Standard Lisp | Spell |
|--------|---------------|-------|
| Eval scope | Global environment | Explicit threaded env |
| Quote | `'expr` returns expr | Same, but expansion substitutes free vars |
| Side effects | Full OS access | Sandboxed to whitelisted tools |
| Special primitive | None | `llm` for recursive LLM calls |
| Memory | Global accumulation | Local to spell-eval call |
