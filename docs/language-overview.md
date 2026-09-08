# Spell language overview

Self-programmed execution (SPE) is when a language model acts as a self-orchestrating agent by writing a program which the harness evaluates. Spell (self-programmed execution language for LMs) is a language designed for SPE. It is based upon and embedded within Clojure, and is currently a prototype intended for academic research.

In SPE, the same data—a model completion—is both the content of the model's context window and the program specifying what context is passed to a subsequent turn. Spell therefore makes context explicit in source code that the program can edit, extend, and evaluate.

## Relationship with Clojure

Spell is a Lisp dialect implemented in Clojure and copies most of Clojure's semantics. Clojure's data representation makes programs straightforward to manipulate as data, while its concurrency facilities support Spell's multi-agent runtime. Spell differs where self-programmed execution requires it.

In particular, each model self-call runs statelessly in a fresh local environment. Only the self-call's argument and return value cross the turn boundary. This prevents a child completion from depending on a hidden parent environment or overwriting a parent binding. Functions are dynamically scoped; ordinary closures do not persist across model turns.

## The completion is the program

A Spell model call receives the beginning of a program as its prompt. The model returns the remaining text. Spell concatenates the prompt and model suffix, parses the completed program, evaluates it, and returns its value.

The primitive that makes this recursive is:

```clojure
(!llm-self prefix)
```

`!llm-self` calls the selected language model with `prefix`, treats the returned suffix as the rest of a Spell program, runs the completed program, and returns its value. Several turn-producing convenience functions use the same operation; their names begin with `!`.

Often, prompts, reasoning, and tool results occupy string literals inside the program. Keeping this material in source makes it available to ordinary program transformations rather than placing it in hidden harness state.

### Quines and self-reference

`quine` binds its complete source form as data before evaluating its body:

```clojure
(quine q (+ 41 1))
;; q is (quine q (+ 41 1)); the form evaluates to 42
```

A completion can therefore refer to its own source and construct an edited or extended copy as the prefix of another model call. For example:

```clojure
(quine prompt "Use two turns to return hello.")
'(!llm-self (wrap-cat prompt))
```

`wrap-cat` places its arguments inside the standard completion wrapper so that the next model receives the beginning of another valid Spell program.

## Effects and the Spell wrapper

A Spell program is often re-evaluated as the prefix of a newly generated program. Re-evaluation would be problematic if it repeated file writes, tool calls, or model calls. Spell programs are therefore pure except for expressions explicitly evaluated by `eval`. A typical live completion has this structure:

```clojure
(quine completion          ; bind the current completion as data
  (eval                    ; evaluate the trailing expression
    (do
      (quine prompt "Inspect the project.")
      (think "List the root before choosing a file.")
      '(!call-now files (io/ls "."))))) ; quoted trailing expression
```

The inner `do` block performs ordinary local computation and returns the value of its last expression, called the trailing expression. This is normally a quoted expression containing effects. The outer `eval` evaluates that expression with effect functions available.

On a subsequent turn, new source is appended after the old trailing expression:

```clojure
(quine completion
  (eval
    (do
      '(!extend)          ; no longer trailing
      "Hello, world!"))) ; new trailing value
```

Because `do` returns only its last value, the earlier quoted expression is now inert data. It remains visible in the program without being evaluated again. Spell completions normally contain one quoted trailing effect expression per model response.

## Context management

Long-running programs need to keep decisions and small evidence while dropping bulky or obsolete material. Context edit markers are applied when a completion is prepared as a later prefix:

- `prune` removes preceding sibling forms and itself.
- `rethink` removes preceding forms and leaves a concise `think` replacement.
- `persist` acts like `def` at runtime, then materializes the bound value into source at edit time.
- `!peek` calls a tool, binds its result for one turn, and arranges for the call and injected binding to be pruned on the following extension.
- `!extend` reopens the edited completion for another model turn.

### Worked flow: inspect, retain, discard

First read a focused current-file range ephemerally (one-based, half-open):

```clojure
'(!peek file-lines (io/read-lines "src/spell/eval.clj" 431 433))
```

The next turn receives a generated binding and edit marker before the cursor:

```clojure
(def file-lines {:ok true :out (first-line 431 ["line a" "line b"]) :err nil :truncated false})
(prune 2)
```

This is an illustrative complete two-row result, not an assumed tool receipt. Check `:ok`, `:err`, and `:truncated` first. The binding is a bounded snapshot, not a hidden full file. Use local zero-based `subvec` indices to preserve an inspected slice; see [bounded results](bounded-results.md):

```clojure
(persist effect-section (subvec (:out file-lines) 0 2))
'(!extend)
```

When `!extend` prepares the next prefix, the injected `(prune 2)` removes both the prior `!peek` call and `file-lines` binding. `persist` replaces its computation with the current value of `effect-section`, so the selected lines survive even though the large input does not.

If the useful result is a conclusion rather than a literal slice, compress the previous forms:

```clojure
(rethink "Effect functions are added only for the outer eval.")
'(!extend)
```

The exact line range above is illustrative; choose it from the tool result. The important sequence is **peek → use or persist → extend**. `!peek` has already injected the pruning that applies while Spell prepares that next prefix; add `rethink` only when you also want to replace earlier material with a concise conclusion.

## Errors and recovery

Parsing, evaluation, provider, and output-format failures are distinct. Agent profiles can configure recovery behavior and structured output repair. Recovery asks a model to continue from the valid prefix or repair an invalid completion; it does not make arbitrary effects safe to repeat. The completion wrapper remains the replay boundary.

For diagnosis:

- use `-v` or `--log FILE` to inspect raw model output;
- use `-T` or `--trace-dir DIR` to record an execution trace;
- keep tool calls in quoted trailing expressions;
- prefer a focused retry over copying an entire failed transcript forward.

Input validation errors in the public Clojure API throw. Runtime/provider failures are returned in the API's error map. See the [exact API contract](./api.md).

## Concurrency and agents

Spell supports ordinary `future` computation and asynchronous agents. The `blocking/` namespace is injected only inside a future. An ordinary agent turn can instead end with a wakeup-based primitive such as `!ask-await` or `agents/!ask`.

The `agents/` namespace manages named handles. `spawn` returns a handle immediately. `ask` registers a request and returns its ID; `!ask` registers the request and then waits. Because an unrelated incoming message can awaken the caller while results remain pending, inspect the received messages and use `!wait` or `!sleep` to continue waiting on the existing request. Incoming messages appear on a later turn as bindings:

```clojure
(def msg-1 {:from :researcher
            :body {:answer 42 :source "..."}})
```

A compact fan-out can wait for several fresh workers:

```clojure
'(agents/!spawn-ask
   ["Summarize the parser's public behavior."
    "Summarize the evaluator's public behavior."])
```

The vector form creates one request with a result slot for every child, then waits. Once every slot is filled, the parent receives one completion report with the request's `:edge-id`. Its body is an ordered vector of child results:

```clojure
(def msg-1
  {:from [:spawn-1 :spawn-2]
   :edge-id 1
   :body [{:from :spawn-1 :body "Parser summary..."}
          {:from :spawn-2 :body "Evaluator summary..."}]})
```

Each inner `:from` identifies the child handle and each inner `:body` is that child's reply or final evaluated result. A child may also send an ordinary message before it completes; that can awaken the wait early while the collection remains pending. Inspect the received binding, then use `!wait` or `!sleep` to continue collecting. For persistent communication, retain the handle returned by `agents/spawn`, then use `agents/ask` or `agents/!ask`, or answer an actionable request with `agents/reply`.

A message may queue while model generation is in flight. When receipt is enabled, Spell accepts messages after generation and before evaluation, and can replace the proposed trailing action with a receiving continuation. Convenience wrappers such as `!extend` enable receipt; raw `!llm-self` calls leave messages queued unless passed `{:receive? true}`. Waiting agents resume from their latest retained context. See [message receipt](./multi-agent.md#message-receipt) and the [communication guide](./multi-agent.md) for the full protocol.

Use the [Auction](https://github.com/lukejoconnor/spell/blob/main/examples/auction.md) and [Chat](https://github.com/lukejoconnor/spell/blob/main/examples/chat.md) examples for complete runnable programs.
