# Spell as a Lisp Dialect: Design Notes

## Overview

These notes document the design decisions for implementing Spell (an LLM self-orchestration DSL) as a Lisp dialect, rather than using XML syntax. The core insight is that Spell's semantics are fundamentally Lisp-like, and using actual Lisp gives us formal foundations, existing tooling, and conceptual clarity.

**Implementation language**: Clojure (see Part 7 for rationale).

---

## Part 1: Lisp Fundamentals

### S-Expressions

The grammar is minimal:

```
sexpr ::= atom | (sexpr*)
atom  ::= symbol | number | string
```

Everything is either an atom or a list of S-expressions. No special syntax for function calls, operators, or control flow. This uniformity enables homoiconicity: code and data share the same representation.

### Core Primitives

McCarthy showed you need only:

| Primitive | Meaning |
|-----------|---------|
| `atom` | Is this an atom (not a list)? |
| `eq` | Are these two atoms equal? |
| `car` | First element of a list |
| `cdr` | Rest of list (after first) |
| `cons` | Prepend element to list |
| `quote` | Return expression unevaluated |
| `cond` | Conditional branching |
| `lambda` | Create a function |

From these, everything else can be built.

### List Navigation Shorthand

`car`/`cdr` chains compose:

| Shorthand | Expands to | Meaning |
|-----------|------------|---------|
| `caar` | `(car (car x))` | first of first |
| `cadr` | `(car (cdr x))` | second element |
| `caddr` | `(car (cdr (cdr x)))` | third element |
| `cadar` | `(car (cdr (car x)))` | second of first |

### Quote and Eval

Quote prevents evaluation:

```lisp
(+ 1 2)    →  3           ; evaluated
'(+ 1 2)   →  (+ 1 2)     ; literal list
```

Eval executes data as code:

```lisp
(eval '(+ 1 2))  →  3
```

This is the foundation of metaprogramming: code is data you can manipulate, then execute.

---

## Part 2: Spell's Relationship to Lisp

### The Original Spell Syntax

Spell used XML with special reference operators:

- `$ref` — evaluate binding, get result
- `@ref` — quote binding, get literal text

### Mapping to Lisp

| Spell | Lisp |
|-------|------|
| `<id>body</id>` | `(setq id body)` |
| `$ref` | Variable lookup / `eval` |
| `@ref` | `quote` / `'` |
| Tag nesting | Nested lists |

### Key Difference in Defaults

| Aspect | Lisp | Original Spell |
|--------|------|----------------|
| Default behavior | Eager evaluation | Lazy (quoted) by default |
| Opt-in mechanism | Quote to defer | `$` to evaluate |

### Resolution

Keep standard Lisp semantics. The LLM quotes when it wants to defer:

```lisp
(setq thought "literal string")       ; quotes unnecessary for literals
(setq action '(search query))         ; quote to defer evaluation
(setq result '(llm prompt))           ; quote to defer
```

This is simpler than inventing lazy-by-default semantics. Literals self-evaluate anyway, so quoting is only needed for expressions the LLM wants to pass as thunks.

Nested quotes protect inner expressions:

```lisp
(setq completion '(progn
                    (setq x (+ 1 2))      ; protected by outer quote
                    (setq y (search q)))) ; not evaluated until spell-eval
```

---

## Part 3: Scope

### Lexical vs Dynamic

| Model | Variable resolved from |
|-------|----------------------|
| Lexical | Where function was *defined* |
| Dynamic | Where function was *called* |

```lisp
(setq x 10)

(defun foo () x)

(defun bar ()
  (let ((x 99))
    (foo)))

(bar)
;; Lexical → 10 (foo sees definition-time x)
;; Dynamic → 99 (foo sees call-time x)
```

### Why Spell Needs Lexical Scope

The LLM's "lexical scope" is what appears in its prompt. It cannot see:
- Global state set by other code
- Variables bound at call time outside its context

Dynamic scope would make LLM-generated code unpredictable—it might accidentally work (or fail) due to bindings it can't see.

### The `eval` Problem

Standard Lisp `eval` evaluates in the **global environment**:

```lisp
(let ((x 99))
  (eval '(+ x 1)))  →  error or uses global x, not 99
```

This breaks our predictability requirement.

---

## Part 4: The Spell Interpreter

### Why Custom Eval

We need an interpreter that:
1. Threads an explicit environment
2. Isolates from Lisp globals
3. Matches what the LLM sees in its prompt

### Core Implementation

```lisp
(defun spell-eval (expr env)
  (cond
    ;; Atom: look up in env
    ((atom expr)
     (if (stringp expr)
         expr                           
         (lookup expr env)))
    
    ;; Quote: return literal
    ((eq (car expr) 'quote)
     (cadr expr))
    
    ;; Setq: evaluate body, extend env
    ((eq (car expr) 'setq)
     (let ((name (cadr expr))
           (val (spell-eval (caddr expr) env)))
       (extend-env name val env)
       val))
    
    ;; Sequence: eval each, return last
    ((eq (car expr) 'progn)
     (spell-eval-sequence (cdr expr) env))
    
    ;; Function call
    (t
     (spell-apply (car expr)
                  (mapcar (lambda (a) (spell-eval a env))
                          (cdr expr))
                  env))))

(defun lookup (name env)
  (let ((binding (assoc name env)))
    (if binding
        (cdr binding)
        (error "Unbound: %s" name))))

(defun extend-env (name val env)
  (cons (cons name val) env))
```

### Sandboxed Apply

```lisp
(defun spell-apply (fn args env)
  (case fn
    ;; Pure functions - safe
    ((+ - * /) (apply fn args))
    ((car cdr cons list append) (apply fn args))
    ((eq equal atom null) (apply fn args))
    
    ;; Meta - stay in sandbox
    ((eval) (spell-eval (car args) env))
    ((apply) (spell-apply (car args) (cadr args) env))
    
    ;; Spell primitives
    ((llm) (call-llm (car args)))
    
    ;; Whitelisted tools
    ((search) (safe-search (car args)))
    ((write_file) (sandboxed-write (car args) (cadr args)))
    
    ;; Everything else: denied
    (otherwise (error "Unknown function: %s" fn))))
```

---

## Part 5: Security and Predictability

### The Global State Question

| Operation | Affects current execution? | Predictable? |
|-----------|---------------------------|--------------|
| Read global | Yes | No — LLM can't see what it gets |
| Write global | No | Yes — just a side effect |

Writing to globals is harmless for correctness (the LLM won't read them back through spell-eval). But intercepting `setq` is still preferred because:
- Keeps spell env as single source of truth
- Allows GC to free memory after spell-eval returns
- Cleaner isolation

### Memory Management

If bindings go to globals:
- Global symbol table is a GC root
- Memory accumulates with each completion

If bindings stay in spell env:
- `env` is local to spell-eval call
- When spell-eval returns, env becomes unreachable
- GC frees it

This matters for long-running systems processing many completions.

### OS Access

Lisp has full OS access:

```lisp
;; Filesystem
(open "file.txt" :direction :output)
(delete-file "file.txt")

;; Shell
(run-program "rm" '("-rf" "/"))

;; Network
(usocket:socket-connect "evil.com" 80)

;; FFI
(cffi:foreign-funcall "system" :string "ls" :int)
```

### Sandbox Strategy: Whitelist, Don't Blacklist

The LLM can only call functions explicitly allowed in `spell-apply`. Dangerous functions like `open`, `run-program`, `shell-command` simply don't exist in Spell's vocabulary.

The spec's distinction matters:

| Kind | Defined where | Trusted? |
|------|---------------|----------|
| Built-in | Interpreter | Yes |
| Tool | Harness | Yes |
| User-defined | LLM response | No — sandboxed |

User-defined functions can only call whitelisted primitives.

---

## Part 6: Extracting from Nested Structures

### The Structure

XML-style nesting becomes nested `setq` forms:

```xml
<completion>
  <prefix>
    <caller-prompt>
      <thought>found me</thought>
    </caller-prompt>
  </prefix>
</completion>
```

Becomes:

```lisp
(setq completion
  '((setq prefix
      ((setq caller-prompt
         ((setq thought "found me")))))
    (setq return thought)))
```

### Recursive Extraction

```lisp
(defun extract (thunk path)
  (if (null (cdr path))
      (find-binding (car path) thunk)
      (extract (find-binding (car path) thunk)
               (cdr path))))

(defun find-binding (name thunk)
  (cond
    ((null thunk) nil)
    ((and (listp (car thunk))
          (eq (caar thunk) 'setq)
          (eq (cadar thunk) name))
     (caddar thunk))
    (t (find-binding name (cdr thunk)))))
```

Usage:

```lisp
(extract completion '(prefix caller-prompt thought))  →  "found me"
```

Nothing is evaluated during extraction—we're just navigating quoted structure.

---

## Summary of Design Decisions

1. **Use standard Lisp syntax** — S-expressions instead of XML
2. **Keep standard Lisp eager semantics** — LLM quotes to defer
3. **Custom interpreter** — `spell-eval` threads explicit environment
4. **Intercept `setq`** — bindings stay local for GC and isolation
5. **Intercept `eval` and `apply`** — prevent escape to global scope
6. **Whitelist functions** — sandbox blocks OS access by default
7. **Literals are robust** — quoting unnecessary, only matters for expressions

The result: a predictable, sandboxed Lisp dialect where code behavior is fully determined by what the LLM can see in its prompt.

---

## Part 7: Thunk Expansion

### The Problem

When LLM1 passes a quoted expression to LLM2, the expression may contain references to bindings in LLM1's scope:

```lisp
;; LLM1's execution
(progn
  (setq x 5)
  (setq thunk '(+ x 1))
  (llm thunk))           ; passes '(+ x 1) to LLM2
```

LLM2 receives `(+ x 1)` but `x` is gone—it was in LLM1's env, which no longer exists.

### The Solution: `expand`

Before passing a thunk to a child LLM, we must substitute values for any free variables (those not bound inside the thunk itself):

```lisp
(defun expand (thunk env)
  (let ((internal (collect-bindings thunk)))
    (substitute-free thunk env internal)))

(defun collect-bindings (thunk)
  ;; Find all names bound by setq inside thunk
  (cond
    ((atom thunk) nil)
    ((eq (car thunk) 'setq)
     (cons (cadr thunk) 
           (collect-bindings (caddr thunk))))
    (t (mapcan #'collect-bindings thunk))))

(defun substitute-free (expr env internal)
  (cond
    ((atom expr)
     (if (and (symbolp expr)
              (not (member expr internal))
              (assoc expr env))
         (cdr (assoc expr env))    ; substitute value
         expr))                     ; leave as-is
    ((eq (car expr) 'quote) 
     expr)                          ; don't descend into nested quotes
    ((eq (car expr) 'setq)
     (list 'setq 
           (cadr expr)
           (substitute-free (caddr expr) env internal)))
    (t 
     (mapcar (lambda (e) (substitute-free e env internal)) 
             expr))))
```

### Example

```lisp
;; env = ((x . 5) (y . 10))

(expand '(progn
           (setq z 1)
           (+ x z))
        env)

;; internal bindings: (z)
;; x is free, z is internal

→ '(progn
     (setq z 1)
     (+ 5 z))      ; x substituted, z preserved
```

### Integrating with LLM Calls

Either bake it into the `llm` primitive:

```lisp
((eq fn 'llm)
 (call-llm (expand (car args) env)))
```

Or make it explicit in Spell code:

```lisp
(llm (expand '(+ x 1)))
```

Baking it in is safer—can't forget to expand.

### Scope of `expand` Itself

`expand` needs access to the current env at call time. This feels dynamic, but we're threading env explicitly through spell-eval, so we just wire it correctly:

```lisp
;; In spell-apply:
((eq fn 'expand)
 (expand (car args) env))   ; pass current env
```

The "dynamic" feel comes from `expand` being a primitive that implicitly receives the current env, rather than a regular function that would close over its definition-time env.

---

## Part 8: Parallelism

### The Challenge

Eventually Spell needs parallel agent execution. The Lisp parts are fast (microseconds); LLM calls are slow (seconds). We need the interpreter to not block while waiting.

### Futures

Most Lisps support this pattern:

```lisp
;; Launch async, returns immediately
(setq f (future (llm prompt)))

;; Later, block only if result not ready
(force f)  →  result
```

### Parallel Branches in Spell

```lisp
(progn
  (setq branch1 (future (llm "Research A")))
  (setq branch2 (future (llm "Research B")))
  (setq branch3 (future (llm "Research C")))
  
  ;; All three running concurrently
  ;; Lisp interpreter is free
  
  (setq synthesis (llm (concat "Synthesize:"
                               (force branch1)
                               (force branch2)
                               (force branch3)))))
```

### In spell-apply

```lisp
((eq fn 'future)
 (make-future (lambda () 
                (spell-eval (car args) env))))

((eq fn 'force)
 (force-future (car args)))
```

The lambda captures `env`, so when the future runs, it has the right scope.

### What Blocks What

| Operation | Blocks? |
|-----------|---------|
| Lisp evaluation | Microseconds, doesn't matter |
| `(future ...)` | No, returns immediately |
| `(force f)` | Only if result not ready |
| LLM calls | Run concurrently in background |

### No Race Conditions

If parallel branches both call `expand`, they're reading the same env. That's fine—env is immutable (we `cons` to extend, never mutate).

Branches are isolated—they can only return values, not modify parent env.

---

## Part 9: Clojure as Implementation Language

### The Lisp Family

```
Lisp (McCarthy, 1958)
├── Common Lisp (1984) — kitchen-sink, standardized
├── Scheme (1975) — minimalist, academic
├── Emacs Lisp — embedded in Emacs
└── Clojure (2007) — modern, JVM-hosted
```

All share: S-expressions, homoiconicity, macros, car/cdr/cons (renamed in Clojure).

### What Clojure Changes

| Traditional Lisp | Clojure |
|------------------|---------|
| Mutable by default | Immutable by default |
| Lists everywhere | Vectors, maps, sets as primitives |
| `car`/`cdr` | `first`/`rest` |
| `setq`/`setf` | `def` (no mutation) |
| Cons cells | Persistent data structures |
| Own runtime | Runs on JVM (also JS, CLR) |

### Clojure Scoping

Lexically scoped by default, with opt-in dynamic scope:

```clojure
(def x 10)  ; global

(defn foo [] x)

(let [x 99]
  (foo))  →  10   ; lexical: foo sees definition-time x

;; Dynamic when explicitly declared
(def ^:dynamic *x* 10)

(binding [*x* 99]
  (foo-using-*x*))  →  99   ; dynamic lookup
```

### Clojure's eval Has the Same Problem

```clojure
(let [x 5]
  (eval '(+ x 1)))  →  error, x not found
```

Clojure compiles to JVM bytecode. Lexical bindings become JVM locals—not accessible by name at runtime. `eval` only sees namespace-level vars.

**We still need custom `spell-eval`.**

### Why Clojure for Spell

1. **Immutability** — env threading is natural, no accidental mutation
2. **JVM ecosystem** — easy HTTP clients for LLM APIs  
3. **Concurrency primitives** — futures, atoms, agents, core.async built in
4. **Modern ergonomics** — vectors, maps, destructuring
5. **Active ecosystem** — more momentum than Common Lisp today

### Popularity Context

| Metric | Position |
|--------|----------|
| TIOBE index | ~40-50th |
| Relative to Common Lisp | More active, more new projects |
| Relative to mainstream | Niche but stable |

Notable users: Nubank, Cisco, Walmart, CircleCI, Metabase.

Small but dedicated community. Good for interpreter/DSL work.

### Spell-eval in Clojure

```clojure
(defn spell-eval [expr env]
  (cond
    (symbol? expr) (get env expr)
    (string? expr) expr
    (number? expr) expr
    (= 'quote (first expr)) (second expr)
    (= 'setq (first expr)) (let [val (spell-eval (nth expr 2) env)]
                             (assoc env (second expr) val)
                             val)
    :else (spell-apply (first expr)
                       (map #(spell-eval % env) (rest expr))
                       env)))
```

Immutable maps make env handling clean:

```clojure
;; Extend env, returns new map, original unchanged
(assoc env 'x 5)
```

---

## Summary of Design Decisions

1. **Use standard Lisp syntax** — S-expressions instead of XML
2. **Keep standard Lisp eager semantics** — LLM quotes to defer
3. **Custom interpreter** — `spell-eval` threads explicit environment
4. **Intercept `setq`** — bindings stay local for GC and isolation
5. **Intercept `eval` and `apply`** — prevent escape to global scope
6. **Whitelist functions** — sandbox blocks OS access by default
7. **Literals are robust** — quoting unnecessary, only matters for expressions
8. **Expand thunks before passing to children** — substitute free variables
9. **Use Clojure** — immutability, JVM ecosystem, good concurrency

The result: a predictable, sandboxed Lisp dialect where code behavior is fully determined by what the LLM can see in its prompt.
