# Agent self-orchestration with Spell

## Overview

This document describes Spell (Self-Prompting Execution Language for LLMs), a domain-specific language embedded in Clojure for LLM self-orchestration and own-context manipulation. In contrast to standard agent architectures where an external harness calls the LLM in a loop, Spell gives the LLM the ability to call itself recursively. The user prompts the LLM once, and then the harness evaluates its outputs recursively. The LLM can call itself to emulate a simple agent loop—but it can also manipulate its own chain of thought in arbitrary and possibly unexpected ways.

As an embedded DSL, Spell uses Clojure's S-expression syntax but has its own evaluation semantics, implemented by the `spell-eval` interpreter. The key primitive is `llm`, which generates code, optionally transforms it via macros, and evaluates it. `llm` accepts a prompt, generates Spell code, optionally modifies it using macros, and evaluates it. Spell differs from Clojure in its rules related to scope and environments, motivated by the fact that the LLM called by `llm` cannot observe variables defined outside of its local context window.

## Background

Clojure is a modern dialect of Lisp. In Clojure, like in other Lisps, objects are S-expressions, and these can be evaluated, producing values. Expressions are named via bindings. A binding evaluates the expression and associates the result with a name unless it is quoted. A quoted expression, or thunk, is evaluated using `eval`.

```clojure
;; Binding evaluates immediately
(def x (+ 1 2))    ; x is bound to 3

;; To defer evaluation, quote
(def thunk '(+ 41 1))  ; thunk is bound to the list

;; Later, evaluate the thunk
(eval thunk) -> 42
```

Quotes are used in Spell so that a parent LLM can pass arbitrary source code as context to a child LLM (in Lisp, "code is data"). This "source code" will often have trivial logic yet contain important information, like a prompt (in Spell, "data is code").


## Completions and `llm`

In Spell, a *completion* is a program which comprises a prompt (called a *prefix*) and a *response*:

```clojure
'(do
   (def prefix ...)
   (def response
     (do
       ...
       (def return ...))))
```

The response is produced by an LLM, with the prefix as its prompt. Within the response, the value of `return` is the actual output (i.e., what would be viewed by a user in a chat). This is orchestrated by the `llm` primitive. A simplified implementation of `llm` is:

```clojure
(defn llm [prompt]
  (let [response (call-LLM prompt) ; generate response
        completion (str prompt response) ; concatenate prompt + response
        env (spell-eval completion {})] ; evaluate into an environment
    (get env 'return))) ; return the variable called return
```

`spell-eval` is a special evaluation function that takes as input both an expression and an environment (here, `{}`) and returns a new environment (here bound to `env`). This environment contains a binding for the symbol `return`, which is the return value of `llm`.


## Example: Passing Context to a Child


```clojure
(def completion
  '(do
     (def prefix "Print 'Hello' and call a child LLM, who should print ' World!'")
     (def response
       (def return (cat "Hello" (llm completion))))))
```

The child LLM sees the completion of its parent as its prompt, producing this completion:

```clojure
'(do
   (def prefix
     "(do
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

## Environments and evaluation
In Clojure, most functions have lexical scope: the environment when the function is evaluated is that when it was defined, not when it was called. However, the `eval` function is global: it both reads from and writes to the global environment. This behavior is undesired when `llm` evaluates a completion becuase the LLM has no way to read the global environment - it knows only the contents of its own completion. Without scoping, a binding defined by a parent LLM could be overwritten by a child LLM, or the environment could become cluttered with forgotten functions and variables. This challenge is mostly specific to the agentic setting, where the code-writing entity never sees the entire source code.

Spell solves this challenge by introducing an evaluation function, `spell-eval`, which takes an environment as input, evaluates an expression in this environment, and returns an environment as its output. This way, the LLM has perfect knowledge of the environment in which its completion will be evaluated.

This behavior reflects an important principle in Spell: the environment of a program is almost exactly the program itself. All references resolve to bindings defined inside the program, with the exception of built-in functions like `llm`, as well as bindings that are explicitly undisclosed (see below).

## Extraction from thunks

When a parent LLM passes a thunk to a child LLM, this thunk may define bindings - or even bindings to thunks that define bindings - which the LLM wishes to extract. For example, consider the following prompt:

```clojure
;; A thunk, bound to `prompt`, containing bindings for `item1` and `item2`
(def prompt
  '(do
     (def item1 "Write unit tests for the parser")
     (def item2 "Update the README with new API docs")
     (def prefix "Complete both tasks below.")
     (def response ...)))
```

The parent LLM receives this prompt and wants to delegate: pass `item1` to one child and `item2` to another. But `item1` and `item2` are bound *inside* the quoted thunk—they aren't directly accessible. The parent cannot simply write `(llm item1)` because `item1` is not bound in its environment; it's bound inside `prompt`. How can the LLM pass each to-do item to a different LLM? 

Spell implements a function `extract` which takes as input a 'path'. This path is a vector of symbols; the last symbol is the binding to be extracted, and each preceding symbol is the name of the thunk which defines the binding for its successor.

```clojure
;; Extract item1 from the prompt thunk
(extract [prompt item1])  ; => "Write unit tests for the parser"

;; The parent LLM can now delegate each item to a child
(def response
  (do
    (def result1 (llm (extract [prompt item1])))
    (def result2 (llm (extract [prompt item2])))
    (def return (cat result1 result2))))
```

For nested thunks, the path grows longer. If `prompt` contained a thunk `subtask` which itself bound `detail`, the extraction would be `(extract [prompt subtask detail])`.

Most instances of extraction are expected to follow a simple pattern similar to nested map lookup. However, some could require evaluating logic:

```clojure
;; A thunk with conditional logic
(def prompt
  '(do
     (def file-status
       (if (file-exists? "config.yaml")
         "exists"
         "does not exist"))
     (def prefix "Check if the config file exists and report.")
     (def response ...)))

;; Extraction must evaluate the `if` to determine the value
(extract [prompt file-status])  ; => "exists" or "does not exist"
```

Here, extracting `file-status` requires evaluating the `if` expression, which in turn calls a built-in function. The extraction is no longer a simple map lookup—it requires running `spell-eval` on the thunk up to the point where the target binding is defined.

## Expansion of thunks

A further challenge arises when attempting to extract a binding from a thunk whose logic depends on externally defined values. Suppose that the parent LLM produces a thunk which references a binding from outside of it, and passes that thunk to a child LLM without passing the binding:

```clojure
;; Parent LLM completion
(do
  (def x 42)
  (def thunk '(+ x 1))
  (def return (llm thunk)))
```

The child LLM receives `(+ x 1)` as its prefix, but `x` is not bound in the child's environment—it was bound in the parent. The child cannot evaluate `x` because it only knows what appears in its own completion. This is why thunks passed to children must be *expanded* if they are to be evaluated: free variables are substituted with their values before the thunk leaves the parent's scope. Spell implements a function, `expand`, which takes two thunks as input: the closure, which defines every symbol used within it (except built-ins), and a sub-thunk, whose symbols must be defined in the closure.

Together, `expand` and `extract` execute a subexpression and all of its dependencies, and they do so in a specific order. `expand` eagerly evaluates every external dependency for the thunk; `extract` lazily evaluates precisely the dependencies of the value being extracted. 

<!-- old text, keep but ignore
the child LLM may wish to evaluate either that thunk, or some expression defined inside of it. For example, it may wish to pass a subexpression to its own child, or call a function defined in the thunk. Doing so requires extraction, because the thunk is not automatically evaluated when the child's completion is evaluated.  These thunks must be backtick-quoted, for a reason related to the closure-as-environment principle.  `expand` inserts the `,` character in front of every symbol in the sub-thunk which is not internally defined, except within blocks that are protected by an ordinary `'`. Then, when the expression containing the sub-thunk is evaluated, externally-defined symbols are automatically replaced with their values. Thereafter, the `extract` function can successfully retrieve the value of any symbol defined inside of the subthunk.
-->

LLMs do not need to call `expand` themselves, as the `llm` function uses a macro to call `expand` on any thunk that is passed to a child LLM.

## LLM hooks

Spell allows an LLM, or user, to define macros which run on the closure of a child LLM before it is evaluated; they hook the `llm` call. When using hooks, output of the LLM, the closure, may differ from the input to `spell-eval`, which is called the program. These could do anything, but we consider three kinds of hooks in particular, [all of which have corresponding built-ins in Spell]:
- **Undisclosed context hooks** prepend bindings to the closure. The child LLM cannot itself read the bound value, but it can disclose this value to its own child. This hook enables the progressive disclosure pattern, where agents retrieve context only when it is needed; here, the context in question can be part of the agent's own chain of thought. It also enables closures (the child LLM can use a function without reading its source code).
- **Return hooks** intercept the return value of a child LLM and apply some function to it. For example, the return value of a worker subagent could be passed to a checker subagent (hooks can call `llm`). A return hook can also be used to ensure a structured return value. Suppose a checker subagent should return either 0 or 1; then the return hook could verify this, and pass the entire closure back to the checker with a stern reminder if its format was incorrect.
- **Recursive hooks** are applied not only to a child LLM, but also to all of its descendents. Such hooks could be used to define bindings which act like global constants, to intercept tool calls, or to blacklist certain operations. 

Hooks written by the LLM must be evaluated within the `spell-eval` sandbox, but it can be useful to enable user-defined hooks that run outside of it. For example, such hooks could increment a global token-usage counter, log outputs to a file, or poll for instructions from an external process. 

## KV cache

A potential pitfall for the self-orchestration approach 








# Old text

## Canonical Expansion

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
