# Spell: A Self-Prompting Execution Language for LLMs

## Overview

This document describes Spell, a domain-specific language for LLM self-orchestration. In contrast to standard agent architectures where an external harness controls the execution loop, Spell allows the LLM to write its own execution graph—including recursive calls, parallel branches, function definitions, and context management. The key innovation is that recursion is moved *inside* the LLM's output. Instead of calling the LLM in a loop, the harness calls it just once and interprets its output, calling it again if instructed to do so by the LLM itself.

Spell is implemented as a Lisp dialect. The LLM produces S-expressions that the interpreter evaluates.

## Data and Expressions

Values in Spell are S-expressions: strings, numbers, symbols, and lists. Expressions can be evaluated, producing values.

Expressions in Spell can be named via bindings. A binding evaluates the expression and associates the result with a name—standard eager evaluation, as in most Lisps. To defer evaluation, you quote the expression.

```clojure
;; Binding evaluates immediately
(def x (+ 1 2))    ; x is bound to 3

;; To defer evaluation, quote
(def thunk '(llm "analyze this"))  ; thunk is bound to the list (llm "analyze this")

;; Later, evaluate the thunk
(eval thunk)  ; calls the LLM
```

## Quotation and Expansion

Quotation and expansion are separate operations in Spell:
- **Quote** (`'expr`): Returns the expression unevaluated
- **Expand** (`(expand expr)`): Substitutes free variables while preserving internal bindings

We define *equivalence* between expressions: two expressions are equivalent if they have the same expansion.

The primary reason for expansion is that it allows an LLM to pass items from its context window to a child LLM without repeating them and burning tokens. If it wishes its child call to see its original prompt, it can expand a quoted expression, and the interpreter will substitute values for free variables before passing it to the child.

## Expansion Semantics

When expanding an expression, the interpreter substitutes *free variables* (those defined outside the expression) while preserving *internal bindings* (those defined inside).

```clojure
(def y 41)
(def x '(+ 1 y))
(expand x)  ;; => '(+ 1 41)
;; y is free (defined outside x), so it's substituted
```

But internal bindings are preserved:

```clojure
(def x '(progn
          (def y 41)
          (+ 1 y)))
(expand x)  ;; => '(progn (def y 41) (+ 1 y))
;; y is internal (defined inside x), so it's preserved as a symbol
```

## Canonical Expansion

This rule reflects an important feature of Spell: the *environment* of a program is almost exactly the program itself. All references resolve to bindings defined inside the program, with the exception of built-in functions like `llm`.

Every expression has a canonical expansion: the smallest equivalent expression with only internal references. The canonical expansion is what gets passed to child LLM calls.

## Completions

A program in Spell is called a *completion* because it comprises a prompt (called a *prefix*) and a *response*, written by the LLM:

```clojure
(completion
  (prefix ...)
  (response ...))
```

The evaluation scope of a binding is its suffix (the entire program after the binding). The quotation scope of a binding is the entire program, including the body of the binding itself.

## Example: Passing Context to a Child

Consider this program:

```clojure
(completion
  (prefix "Print 'hello' and invoke another LLM to print 'world'.")
  (response
    (def instruction "Print 'world' and terminate.")
    (return (concat "hello " (llm instruction)))))
```

The child LLM receives just the string `"Print 'world' and terminate."` because `instruction` is evaluated before the call.

Here is a version that passes more context using expansion:

```clojure
(completion
  (prefix "Print 'hello' and invoke another LLM to print 'world'.")
  (response
    (return (concat "hello " (llm (expand 'completion))))))
```

Here, `'completion` quotes the entire completion, and `expand` substitutes any free variables. The child receives the expanded completion as its prefix:

```clojure
(completion
  (prefix
    (completion
      (prefix "Print 'hello' and invoke another LLM to print 'world'.")
      (response
        (return (concat "hello " (llm (expand 'completion)))))))
  (response
    (return "world")))
```

The completion of the parent call becomes the prefix of the child call. The child LLM thus has the context it needs to understand its assignment: the user asked the first LLM to print 'hello' and the second to print 'world', the first has already printed 'hello', and now it must print 'world'.

Note: if the parent attempted to pass `completion` without quoting (evaluate instead of quote), this would be an error—the completion is not fully defined until after its closing form.
