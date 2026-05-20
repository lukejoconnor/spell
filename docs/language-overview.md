# Spell Language Overview

This guide is for readers who want to understand what Spell completions are doing when they inspect examples, traces, prompts, or source code. 

## The Completion Is The Program

A Spell model call receives the beginning of a program as its prompt. The model returns the remaining text. Spell concatenates the prompt and model suffix, parses the result, evaluates it, and returns the program's value.

The primitive that makes this recursive is `!llm-self`. Conceptually:

```clojure
(!llm-self prefix)
```

calls the selected language model with `prefix`, treats the returned suffix as the rest of a Spell program, runs the completed program, and returns its value.

Several turn-producing convenience functions internally call `!llm-self`; these are indicated by a leading `!` (for example, `!call-now` is a common way to call a tool and produce a new turn with the tool call result). 

## String literals

Often, the majority of characters in a Spell program will belong to string literals containing prompts, reasoning traces, and tool call results. The point of putting all of this text into the program is so that it can be manipulated programmatically. String literals can be bare value expressions, `def` forms, or comment-like `think` forms: `(think "...")` evaluates to nil.

## Quines And Self-Reference

`quine` is the special form that binds its own source code as data:

```clojure
(quine name (pr-str name)) ; => "(quine name (pr-str name))"
```

This first binds to `name` the entire expression as data. Then, it evaluates `(pr-str name)` and returns its value.

## Effect boundary and `eval`

Spell programs are pure except for expressions which are explicitly evaluated by an explicit call to `eval`. This evaluator is identical to the default evaluator (`spell-eval`), except that effect functions are added to its environment. In particular, turn-producing expressions must be evaluated this way. The point of this mechanism is to avoid accidental re-evaluation of effectful expressions. A typical Spell program computes an effectful expression as data and then evaluates it; the logic that computes the effectful expression can later be kept as context, while the expression itself is left as inert data.
 
## The Spell Wrapper

Most live Spell turns use the same outer shape:

```clojure
(quine completion          ; 1. bind the current completion as data
  (eval                    ; 4. evaluate the trailing expression
    (do
      ...                  ; 2. ordinary local computation
      '(effectful-expression)))) ; 3. return quoted trailing expression
```

First, `quine` binds `completion` to the source of the whole form. That gives the program a structured handle on its own current completion. Second, the `do` block performs ordinary local computation; this might often include defining string literals with a prompt and a reasoning trace or some previous tool call results. Third, the last expression of the `do` block is a quoted trailing expression. The tic `'` denotes the expression as data; it is not evaluated unless passed explicitly to `eval`. Fourth, this expression is evaluated by `eval`.

## Edit markers

A common pattern is that a Spell program produces an edited copy of itself as the prefix of the subsequent turn. Spell applies edit markers when turn-producing helpers prepare that next prefix. This phase is edit time. Spell currently has three edit markers:

- `(prune k)` marks `k` previous sibling expressions, in addition to itself, for removal; think of this as pressing the backspace key:

 ```clojure
 (edit-reopen '(quine completion (eval (do (+ 1 2) (prune) (+ 2 2)))))
 ;; => (quine completion (eval (do (+ 2 2))))
 ```

 A `prune` form has no effect at runtime and evaluates to `nil`.

- `rethink` prunes previous sibling expressions, then leaves behind a `think` marker.

- `persist` is used to persist a computed value across turns when the value from which it is computed is pruned or otherwise dropped. At runtime, it is identical to `def`. At edit time, Spell replaces `expr` in `(persist name expr)` with whatever value is bound to `name`. A common pattern is to read a large file and prune the result while persisting the relevant slice.

## Synchronous Self-Calls And Spawned Agents

Spell supports two broad delegation styles.

The simplest is a synchronous self-call. A parent program calls `!llm-self` or a helper built on it, waits for the child completion to evaluate, and uses the returned value. This is the pattern behind small examples like asking a child call for a word, answer, or transformed message.

The second style uses asynchronous agents. The `agents/` namespace provides functions which spawn agents and support coordination between them via messages and blocking; a key feature is that it supports synchronous communication (i.e., blocking for a response) between asynchronous agents without ever causing deadlock. 

The `globals/` namespace allows synchronous or asynchronous agents to coordinate via globally available bindings. Like in Clojure, objects in Spell are immutable; whereas a `globals/` binding name can change what value it refers to, the underlying value never changes once fetched.
