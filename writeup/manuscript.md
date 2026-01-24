# Spell: A Self-Prompting Execution Language for LLMs

## Overview

This document describes Spell, a domain-specific language for LLM self-orchestration. In contrast to standard agent architectures where an external harness controls the execution loop, Spell allows the LLM to write its own execution graph—including recursive calls, parallel branches, function definitions, and context management. The key innovation is that recursion is moved *inside* the LLM's output. Instead of calling the LLM in a loop, the harness calls it just once and interprets its output, calling it again if instructed to do so by the LLM itself.

Spell is implemented as a Lisp dialect, based closely on Clojure. The LLM produces S-expressions that the interpreter evaluates.

## Background

In Lisp (and Clojure), objects are S-expressions, and these can be evaluated, producing values. Expressions are named via bindings. A binding evaluates the expression and associates the result with a name unless it is quoted. A quoted expression, or thunk, is evaluated using `eval`.

```clojure
;; Binding evaluates immediately
(def x (+ 1 2))    ; x is bound to 3

;; To defer evaluation, quote
(def thunk '(+ 41 1))  ; thunk is bound to the list

;; Later, evaluate the thunk
(eval thunk) -> 42
```

There exist two ways to quote an expression: using the tic ', or using the backtick. When an expression is backtick-quoted, a subexpression can be evaluated using the `,` operator:

```clojure
(def x 41)
`(+ ,x 1) -> (+ 41 1)
```

Quotes and backtick quotes are used in Spell so that a parent LLM can pass arbitrary source code as context to a child LLM (in Lisp, "code is data"). This "source code" will often have trivial logic yet contain important information, like a prompt ("data is code").


## Completions and `llm`

In Spell, a *completion* is a program which comprises a prompt (called a *prefix*) and a *response*:

```clojure
'(progn
   (def prefix ...)
   (def response
     (progn
       ...
       (def return ...))))
```

The response is produced by an LLM, with the prefix as its prompt. Within the response, the value of `return` is the actual output (i.e., what would be viewed by a user in a chat). This is orchestrated by the `llm` primitive. In pseudocode:

```
(defn llm [prompt]
  (let [response (api-call prompt)
        completion (str prompt response)
        env (spell-eval completion {})]
    (get env 'return)))
```

`spell-eval` is a special evaluation function that takes as input both an expression and an environment (here, `{}`) and returns a new environment (here bound to `env`). This environment contains a binding for the symbol `return`, which is the return value of `llm`.


## Example: Passing Context to a Child


```clojure
(def completion
  '(progn
     (def prefix "Print 'Hello' and call a child LLM, who should print ' World!'")
     (def response
       (def return (cat "Hello" (llm completion))))))
```

The child LLM sees the completion of its parent as its prompt, producing this completion:

```clojure
'(progn
   (def prefix
     "(progn
        (def prefix \"Print 'Hello' and call a child LLM, who should print ' World!'\")
        (def response
          (def return (cat \"Hello\" (llm completion)))))")
   (def response
     (def return " World!")))
```

In order of operations:
1. the parent `llm` call passes the initial prompt to an API call, gets `completion`, and calls `spell-eval` on it.
2. this thunk contains a child `llm` call with `completion` as its prompt; this is evaluated.
3. the child `llm` call passes this prompt to the API, gets a new `completion` and calls `spell-eval`.
4. this completion has no further function calls to be evaluated, so `spell-eval` returns immediately.
5. the child `llm` call extracts the `return` value from its completion, which is " World!".
6. the parent `spell-eval` call returns the fully evaluated completion, which contains the binding `return -> "Hello World!"`
7. This binding is extracted and returned by the parent `llm` call.

## Environments and scope
In Clojure, 

## Expansion

A thunk 
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
