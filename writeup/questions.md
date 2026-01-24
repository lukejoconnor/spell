# Design Questions

Questions and choices identified during spec refactoring.

## 1. Data Model [RESOLVED]

**Decision:** Full S-expressions. Symbols, lists, strings, numbers are all valid values.

---

## 2. Completion Structure in Lisp

The XML spec has `<completion><prefix>...<response>...</response></completion>` with specific sub-structure (system-prompt, interpreter-prompt, caller-prompt).

**Question:** What's the Lisp equivalent? Options:

```clojure
;; A) Named forms
(completion
  (prefix
    (system-prompt "...")
    (interpreter-prompt "...")
    (caller-prompt "..."))
  (response
    (setq x ...)
    (return ...)))

;; B) Flat progn with conventions
(progn
  ;; prefix is just prior context
  (setq result (llm "prompt"))
  result)  ; implicit return

;; C) Something else?
```

---

## 3. The @ Operator [RESOLVED]

**Decision:** Not using `@` syntax. Use `expand` as an explicit function.

`expand` is completely different from `quote`:
- `quote` returns the expression unevaluated
- `expand` substitutes free variables (defined outside) but preserves internal bindings

```clojure
(def y 41)
(def x '(+ 1 y))
(expand x)  ;; => '(+ 1 41) - y is free, substituted

(def x '(progn (def y 41) (+ 1 y)))
(expand x)  ;; => '(progn (def y 41) (+ 1 y)) - y is internal, preserved
```

---

## 4. Patterns [DEFERRED]

**Decision:** Defer. Not needed for Phase 1.

---

## 5. Tool Integration

**Question:** How do external tools integrate?

From the notes, `spell-apply` has a whitelist. But:
- Are tools defined at interpreter startup?
- Can the LLM discover available tools?
- How do tool errors propagate?

---

## 6. Parallel Execution [DEFERRED]

**Decision:** Defer. Sequential execution for Phase 1.

---

## 7. Return Statement

The XML spec requires exactly one `<return>` per response.

**Question:** In Lisp, is return explicit or implicit (last expression)?

```clojure
;; Explicit
(response
  (setq x ...)
  (return (llm x)))

;; Implicit
(response
  (setq x ...)
  (llm x))  ; last expression is return value
```

---

## 8. Functions

The XML spec allows user-defined functions in the response:

```xml
<fn name=summarize><args>doc</args>...</fn>
```

**Question:** In Lisp, use `defun`? Or a special form?

```clojure
;; Standard defun
(defun summarize (doc) ...)

;; Special form for Spell
(spell-fn summarize (doc) ...)
```

Concern: `defun` in Clojure is `defn` and creates global vars. We want local functions.

---

## 9. Error Handling

**Question:** What happens on:
- LLM call failure (API error)?
- Pattern match failure?
- Undefined variable reference?

Options: retry, propagate, default value, ask LLM to fix?

---

## 10. The `llm` Function Signature

**Question:** What arguments does `llm` take?

```clojure
;; A) Just the prompt
(llm "do something")

;; B) Prompt + context
(llm "do something" context)

;; C) Prompt + options map
(llm "do something" {:temperature 0.7 :max-tokens 1000})
```
