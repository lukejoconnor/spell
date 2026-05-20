# Spell Language Overview

Spell is a small Lisp-like language for self-programmed execution. The central idea is that a language model does not merely choose the next action in a fixed agent loop. Instead, the model writes the program that decides what to do next.

This guide is for readers who want to understand what Spell completions are doing when they inspect examples, traces, prompts, or source code. It is not a full language manual, and it is not meant to teach large hand-written Spell programs.

## The Completion Is The Program

A Spell model call receives the beginning of a program as its prompt. The model returns the remaining text. Spell concatenates the prompt and model suffix, parses the result, evaluates it, and returns the program's value.

The primitive that makes this recursive is `!llm-self`. Conceptually:

```clojure
(!llm-self prefix)
```

calls the selected language model with `prefix`, treats the returned suffix as the rest of a Spell program, evaluates the completed program, and returns its result.

Because `!llm-self` is a Spell form, a model-written program can call it conditionally, recursively, inside loops, or as part of higher-level orchestration helpers. The model is writing control flow, not just filling an action slot.

## The Standard Wrapper

Most live Spell turns use the same outer shape:

```clojure
(quine completion
  (eval
    (do
      ;; model-written body
      '(!extend))))
```

The wrapper has three jobs.

First, `quine` binds `completion` to the source of the whole form. That gives the program a structured handle on its own current completion.

Second, the `do` block lets the model write ordinary local computation before deciding what happens next.

Third, `eval` creates the boundary for effectful work. In the ordinary evaluator, effectful forms such as `!llm-self`, I/O, and agent communication are not directly available. They run only when the trailing expression of the `do` block returns quoted code for `eval` to execute.

That is why Spell examples often end with a quoted form:

```clojure
'(!call-now files (io/ls "."))
```

The quote makes the expression data during the first evaluation. The wrapper's `eval` then evaluates that data as the effectful boundary for the turn.

## Prompt As Prefix

Spell uses prompt-as-prefix execution. A prompt is not merely an instruction string near the program; it is the start of the program itself.

For example, the model may see an open prefix like:

```clojure
(quine completion (eval (do (quine prompt "Inspect the project root.")
```

The suffix it returns is appended directly to that prefix. A valid suffix must therefore complete the program.

This design matters for multi-turn execution. Suppose the current trailing expression is:

```clojure
'(!extend)
```

When Spell extends the turn, the next model call receives the previous completion reopened before the final closing delimiters. If the model appends a new trailing expression, the old one is no longer last in the `do` block, so it is inert context rather than a repeated effect.

## Quines And Self-Reference

`quine` is the special form that makes source-level self-reference explicit:

```clojure
(quine self (pr-str self))
```

Inside the body, `self` is bound to the source of the entire quine form. The body still evaluates normally; `quine` is not the same as `quote`.

Spell uses this to let a completion preserve, reopen, transform, and continue itself. In the standard wrapper, `completion` names the current program as data, so forms like `!extend` and `!call-now` can build the next model prefix from the current one.

## One Effectful Boundary Per Turn

The wrapper's double evaluation gives Spell a simple discipline: ordinary body expressions are local computation, and the quoted trailing expression is the effectful boundary.

This helps avoid accidental repeated side effects. Earlier expressions remain visible to later model calls as context, but they do not automatically run again. A previous `!extend`, file read, or agent message becomes part of the program history unless the model explicitly makes a new effectful trailing expression.

The common turn-producing forms all build on `!llm-self`:

- `!extend` continues with the current completion reopened.
- `!call-now` runs one or more calls, binds their results into the next completion, and continues.
- `!peek` is like a short-lived read: the result is visible for one turn and then pruned unless kept.
- `!describe` adds documentation for namespaces or functions to the next turn.

## Context Management

Spell completions grow as they extend. The language gives the model a few ways to keep useful context and discard the rest.

`!extend` carries the completion forward unchanged except for reopening it. This is simple, but it can accumulate stale reasoning, large tool outputs, or exploratory dead ends.

`prune` marks previous expressions for removal when the completion is transformed for a future model call. It is inert when evaluated; it matters when Spell prepares the next prefix.

`persist` keeps a computed value across pruning by replacing the expression with its current literal value in the transformed completion. A common pattern is to read a large file, persist the relevant slice or summary, then prune the full result.

```clojure
'(!peek lines (io/read-lines "src/spell/eval.clj"))

;; On the next turn, lines is bound and can be summarized or sliced.
(persist focus (subvec lines 120 150))
'(!extend)
```

`think` records model reasoning as an ordinary expression in the program. `rethink` combines replacement reasoning with pruning, letting the model compress or correct an earlier chain of thought before continuing.

## Synchronous Self-Calls And Spawned Agents

Spell supports two broad delegation styles.

The simplest is a synchronous self-call. A parent program calls `!llm-self` or a helper built on it, waits for the child completion to evaluate, and uses the returned value. This is the pattern behind small examples like asking a child call for a word, answer, or transformed message.

The second style uses spawned agents. Agent forms create separate handles with their own completions and inboxes. Spawned agents can run concurrently, receive messages, answer synchronous asks, and preserve their own context while sleeping. This supports examples where several model-written programs work independently and later report results back to a coordinator.

For a first read, the important distinction is:

- `!llm-self` creates another completion in the same synchronous line of work.
- `agents/*` forms create or communicate with separate agent handles.

## Reading Spell Artifacts

When reading a Spell example or trace, look for four things:

1. The outer wrapper, usually a `quine completion` around `eval` and `do`.
2. Local computation in the body, such as `def`, `let`, `loop`, strings, and helper functions.
3. The quoted trailing expression, which is the effectful boundary for that turn.
4. Context-management forms such as `think`, `rethink`, `prune`, and `persist`, which explain why later turns contain or omit earlier material.

This is enough to follow most public examples without needing the full evaluator implementation.
