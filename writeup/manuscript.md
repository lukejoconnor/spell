# Spell: A Self-Prompting Execution Language for LLMs

## Overview

This document describes Spell, a domain-specific language for LLM self-orchestration. In contrast to standard agent architectures where an external harness controls the execution loop, Spell allows the LLM to write its own execution graph—including recursive calls, parallel branches, function definitions, and context management. The key innovation is that recursion is moved *inside* the LLM's output. Instead of calling the LLM in a loop, the harness calls it just once and interprets its output, calling it again if instructed to do so by the LLM itself.

Spell is implemented as a Lisp dialect. The LLM produces S-expressions that the interpreter evaluates.

## Data and Expressions

In Spell, the only data type is a string. Objects in the language are expressions. Expressions can be evaluated, producing other expressions. In particular, they produce literals, which are defined as expressions that are equivalent to their value (see below for "equivalent").

Expressions in Spell can be named via bindings. A binding is akin to variable assignment in other languages, but instead of the value of the expression being assigned when bound to a name, the expression itself is bound to the name. When we wish to evaluate an expression, we look up the variable; we are also allowed to *quote* an expression.

```clojure
;; Binding an expression (not yet evaluated)
(setq thought '(llm "analyze this"))

;; Evaluating: looks up and evaluates
thought  ; => calls the LLM

;; Quoting: returns the expression itself
'thought  ; => the symbol 'thought
'(llm "analyze this")  ; => the list (llm "analyze this")
```

## Quotation and Expansion

Quotation and expansion are separate operations in Spell:
- **Quote** (`'expr`): Returns the expression unevaluated
- **Expand** (`(expand expr)`): Substitutes free variables while preserving internal bindings

We define *equivalence* between expressions: two expressions are equivalent if they have the same expansion.

The primary reason for expansion is that it allows an LLM to pass items from its context window to a child LLM without repeating them and burning tokens. If it wishes its child call to see its original prompt, then it can expand a quoted reference, and the harness will substitute values before passing it to the child.

## Expansion Semantics

When expanding an expression, the interpreter substitutes *free variables* (those defined outside the expression) while preserving *internal bindings* (those defined inside).

For example:

```clojure
(progn
  (def hi "Hello")
  (def x '(progn
            (def earth "World!")
            (concat hi earth))))
```

The expansion of `x` is:

```clojure
'(progn
   (def earth "World!")
   (concat "Hello" earth))
```

`hi` has been substituted with its value `"Hello"` because `hi` is bound outside of `x`. But `earth` is preserved as a symbol because `earth` is bound inside `x`.

## Canonical Expansion

This rule reflects an important feature of Spell: the *environment* of a program is almost exactly the program itself. All quotations and evaluations refer to bindings defined inside of the program, with the exception of the `llm` function itself (the `llm` function cannot be quoted, but it can be evaluated).

Every expression has a canonical expansion: the smallest equivalent expression with only internal quotations. The canonical expansion is what gets passed to child LLM calls.

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
    (setq instruction "Print 'world' and terminate.")
    (return (concat "hello " (llm instruction)))))
```

Here is an equivalent program that uses quotation:

```clojure
(completion
  (prefix "Print 'hello' and invoke another LLM to print 'world'.")
  (response
    (return (concat "hello " (llm '@completion)))))
```

Here, `@completion` quotes the entire completion. In the child `llm` call, the expanded completion becomes the prefix:

```clojure
(completion
  (prefix
    (completion
      (prefix "Print 'hello' and invoke another LLM to print 'world'.")
      (response
        (return (concat "hello " (llm '@completion))))))
  (response
    (return "world")))
```

The completion of the parent call is the prefix of the child call. The child LLM thus has the context it needs to understand its assignment: the user has asked the first LLM to print 'hello' and the second to print 'world', the first has already printed 'hello', and it now must print 'world'.

If the parent LLM attempted to pass `$completion` (evaluate instead of quote), this would be an error, because an expression must be defined before it is evaluated, and the completion is not fully defined until after its closing form.
