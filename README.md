# Spell

Self-programmed execution (SPE) is when a language model (LM) acts as a self-orchestrating agent by writing a program which the harness simply evaluates. Spell (self-programmed execution language for LMs) is a language designed for SPE. SPE and Spell are described in [this paper](https://arxiv.org/abs/2605.06898). The language is based upon and embedded within Clojure. It is currently a prototype intended for academic research.

## Contents

- [Core semantics](#core-semantics)
- [Quick start](#quick-start)
- [Spell language overview](#spell-language-overview)
  - [Relationship with Clojure](#relationship-with-clojure)
  - [The completion is the program](#the-completion-is-the-program)
  - [String literals](#string-literals)
  - [Quines and self-reference](#quines-and-self-reference)
  - [Effect boundary and `eval`](#effect-boundary-and-eval)
  - [The Spell wrapper](#the-spell-wrapper)
  - [Edit markers](#edit-markers)
  - [Namespaces](#namespaces)
  - [Multiple agents](#multiple-agents)
- [Contributing](#contributing)
- [License](#license)

## Core semantics

In SPE, the same data, a model completion, is both the content of the model's context window and the program specifying what context is passed to a subsequent turn. Typically, this prefix is constructed by editing or appending the source code of the Spell program itself. For this reason, Spell is a Lisp dialect. It supports programmatic self-reference via a `quine` form: the expression `(quine name inner-expr)` binds to `name` the entire expression, not just `inner-expr`, as data, and then evaluates `inner-expr`. It also features *edit markers*, which direct an `apply-edits` function to excize or replace certain expressions inside a `quote` or `quine` form.

A Spell program is often re-evaluated as the prefix of a newly generated program, and this is problematic if it has side effects (in particular, LM self-calls). To address this, Spell programs are pure except for expressions which are explicitly evaluated by `eval`, and they have a special structure, called the Spell wrapper:

```clojure
(quine completion          ; 1. bind the current completion as data
  (eval                    ; 4. evaluate the trailing expression
    (do
      ...                  ; 2. ordinary local computation
      '(effectful-expression)))) ; 3. return quoted trailing expression
```

The inner `do` block returns the value of its last expression, the trailing expression, which is normally a quoted expression with effects. This expression is evaluated by the outer `eval`. On a subsequent turn, if an expression is appended after the trailing expression, it becomes inert data.

## Quick start

Clone the repo and ask your agent to perform setup; it should discover the repo-local `spell-setup` skill. The prerequisites are Java, Clojure, and an OpenAI API key for the default GPT-6 Astra model. Other providers can be selected explicitly.

The most convenient way to run Spell is via the CLI:

```bash
# Run a bundled example
bin/spell -v -e hello-world

# Chat with the agent
bin/spell -e chat

# Ask the default agent to do something
bin/spell "Inspect the examples directory and suggest one example to run next."

# See options
bin/spell -h
```

To run `spell` directly instead of `bin/spell`, put this checkout's `bin/` directory on your `PATH`.

### Examples

- [Hello world](examples/hello-world.md) makes one minimal model self-call and composes its result.
- [Coin flip](examples/coin-flip.md) uses recursive self-calls with a programmatic stopping condition.
- [Chat](examples/chat.md) demonstrates an interactive conversation through agent communication.

See the [examples guide](examples/README.md) for the complete runnable set, including sequential, game-loop, and MCP examples.

## Spell language overview

This section is meant to explain key design choices and their rationale. For a practical guide to writing Spell code with examples, I recommend reading the [Spell system prompt](config/prompts/sysprompt-toolcall.txt).

## Relationship with Clojure

Spell is a dialect of Lisp embedded within Clojure. It is implemented in Clojure and copies most of Clojure's semantics. Clojure was chosen because it is a modern Lisp with a powerful concurrency model. Its concurrency features enable the multi-agent runtime. Spell resembles Clojure except when there is a reason to differ.

One deliberate difference is scoping. Clojure's `eval` reads from and writes to the global environment. That is undesirable for Spell self-calls because the language model cannot inspect a hidden global environment, and a parent binding could be overwritten by a child completion. Spell therefore runs each self-call statelessly within its own local environment; only the argument to the self-call and its return value cross the turn boundary. Because of this choice, Spell has little use for closures, which would be unable to pass through the turn boundary; therefore, functions in Spell are dynamically scoped.

Spell also adds several features which are specifically motivated by SPE, and these are described below.

## The completion is the program

A Spell model call receives the beginning of a program as its prompt. The model returns the remaining text. Spell concatenates the prompt and model suffix, parses the result, evaluates it, and returns the program's value.

The primitive that makes this recursive is `!llm-self`. Conceptually:

```clojure
(!llm-self prefix)
```

calls the selected language model with `prefix`, treats the returned suffix as the rest of a Spell program, runs the completed program, and returns its value.

Several turn-producing convenience functions internally call `!llm-self`; these are indicated by a leading `!` (for example, `!call-now` is a common way to call a tool and produce a new turn with the tool call result).

## String literals

Often, the majority of characters in a Spell program will belong to string literals containing prompts, reasoning traces, and tool call results. The point of putting all of this text into the program is so that it can be manipulated programmatically. String literals can be bare value expressions, `def` forms, or comment-like `think` forms: `(think "label" ...)` evaluates its body, then returns `nil`.

## Quines and self-reference

`quine` is the special form that binds its own source code as data:

```clojure
(quine name (pr-str name)) ; => "(quine name (pr-str name))"
```

This first binds to `name` the entire expression as data. Then, it evaluates `(pr-str name)` and returns its value.

## Effect boundary and `eval`

Spell keeps effect functions out of ordinary evaluation. They become available inside an explicit call to `eval`, which uses the same evaluator (`spell-eval`) with effect functions added to its environment. In particular, turn-producing expressions must be evaluated this way. The point of this mechanism is to avoid accidental re-evaluation of effectful expressions. A typical Spell program computes an effectful expression as data and then evaluates it; the logic that computes the effectful expression can later be kept as context, while the expression itself is left as inert data.

## The Spell wrapper

Most live Spell turns use the same outer shape:

```clojure
(quine completion          ; 1. bind the current completion as data
  (eval                    ; 4. evaluate the trailing expression
    (do
      ...                  ; 2. ordinary local computation
      '(effectful-expression)))) ; 3. return quoted trailing expression
```

First, `quine` binds `completion` to the source of the whole form. That gives the program a structured handle on its own current completion. Second, the `do` block performs ordinary local computation; this might often include defining string literals with a prompt and a reasoning trace or some previous tool call results. Third, the last expression of the `do` block is a quoted trailing expression. The quote shorthand `'` denotes the expression as data; it is not evaluated unless passed explicitly to `eval`. Fourth, this expression is evaluated by `eval`.

## Edit markers

A common pattern is that a Spell program produces an edited copy of itself as the prefix of the subsequent turn. Spell applies edit markers when turn-producing helpers prepare that next prefix. This phase is edit time. Spell currently has three forms with edit-time behavior:

- `(prune k)` marks `k` previous sibling expressions, in addition to itself, for removal; think of this as pressing the backspace key:

  ```clojure
  (edit-reopen '(do (+ 1 2) (prune) (+ 2 2)))
  ;; => (do (+ 2 2))
  ```

  The resulting `(do (+ 2 2))` expression evaluates to `4`.

  A `prune` form has no effect at runtime and evaluates to `nil`.

- `rethink` prunes previous sibling expressions, then leaves behind a `think` marker. Like `prune`, it defaults to one previous sibling unless given an explicit count.

- `persist` is used to persist a computed value across turns when the value from which it is computed is pruned or otherwise dropped. At runtime, it is identical to `def`. At edit time, Spell replaces `expr` in `(persist name expr)` with the current value bound to `name`, when one is available. A common pattern is to read a large file and prune the result while persisting the relevant slice.

## Namespaces

Spell organizes many functions into namespaces. Some namespaces are default language namespaces and are always available. Others are optional effect namespaces: the selected agent profile must expose them, and their functions are only available across the effect boundary described above. All namespaces include documentation that the model can access programmatically; some are documentation-only.

Default namespaces:

- `strings`
- `math`
- `builtins`: documentation-only namespace for functions available without a namespace prefix
- `reminders`: documentation-only namespace for Spell-specific patterns, akin to skills

Optional effect namespaces:

- `io` (also `io-read`, `io-write`, and `io-exec`; these form a partition of `io`)
- `web`
- `patterns`: library of orchestration patterns written in Spell
- `agents`: see below
- `globals`: see below
- `workers`: named child-agent entry points, when the selected agent defines them
- `blocking`: helpers for awaiting concurrent work, available only inside of `future` threads

### MCP server namespaces

Spell can also turn a configured stateless MCP `2026-07-28` server into an effect namespace. Server profiles hold connection and environment-backed authentication settings, while agent profiles select the tools, resources, prompts, completion, and subscriptions exposed to the model. See [MCP server profiles](docs/api.md#mcp-server-profiles).

## Multiple agents

Spell supports two broad multiple-agent delegation protocols.

The simplest is a synchronous self-call. A parent program calls `!llm-self`, waits for the child completion to evaluate, and uses the returned value. With this protocol there is no clear distinction between different agents versus different turns of the same agent.

The second protocol is asynchronous and operates via an optional `agents/` namespace. This provides functions like `spawn` (create an agent with a prompt), `send` (send a message asynchronously), and `!ask` (send a message and block for a response). The key design constraint of the multi-agent communication system is that it never causes deadlock: for example, if two agents simultaneously call `!ask`, then both awaken each other.

The `globals/` namespace allows synchronous or asynchronous agents to coordinate via globally available bindings. Like in Clojure, objects in Spell are immutable; whereas a `globals/` binding name can change what value it refers to, the underlying value never changes once fetched.

## Contributing

If you use Spell, please give me feedback or raise issues. AI-written issues are welcome. For AI-written PRs, please include a human-written prompt or task description in the PR message.

## License

Spell is released under the MIT License. See `LICENSE`.
