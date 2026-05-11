# Spell API Proposal

This document describes the proposed public Clojure API for running Spell from another program.

The API is intentionally small. A run chooses an agent, chooses a provider, supplies either a natural-language prompt or a complete Spell program, and optionally sets run-scoped controls such as budget, tracing, logging, recursion depth, and user input. Model behavior, transport behavior, output formatting, retries, and provider-specific tuning live in agent and provider configuration files instead of in the run call.

## Clojure Entry Point

```clojure
(require '[spell.api :as spell])

(spell/run {:prompt "Answer with the number 42."
            :provider "config/providers/openai-tc.provider.edn"
            :agent "config/agents/cli.agent.edn"})
```

`spell.api/run` accepts one map and returns one map.

## Inputs

Exactly one of `:prompt` or `:init` is required.

| Option | Required | Description |
|---|---:|---|
| `:prompt` | one of `:prompt`, `:init` | Natural-language task. Spell wraps this in the standard completion program before evaluation. |
| `:init` | one of `:prompt`, `:init` | Complete Spell program string. The program is evaluated directly. |
| `:provider` | usually | Provider selection. Accepts a provider instance, a `.provider.edn` path, or an inline provider config map. May be omitted when the selected agent supplies `:provider`. |
| `:agent` | usually | Agent selection. Accepts a `.agent.edn` path. May be omitted only when the provider config supplies a `:default-agent` and the implementation can resolve it unambiguously. |

## Run Options

These options are scoped to one invocation of `run`. They are not agent defaults and should not be copied into agent or provider files.

| Option | Default | Description |
|---|---:|---|
| `:budget` | agent/runtime default | Maximum spend in dollars for the run. `nil` means use the configured default. `0` means unlimited. |
| `:depth` | unlimited | Maximum recursive LLM depth for this run. Use this to stop runaway self-calls without changing an agent profile. |
| `:trace` | `false` | When true, record a Spell execution trace for the run. |
| `:trace-dir` | generated temp dir | Destination directory for trace output when `:trace` is true. |
| `:usage` | fresh atom | Existing usage atom to accumulate token and cost accounting into. |
| `:user?` | `false` | Register the interactive `:user` handle for the run. |
| `:user-reader` | `System/in` | Reader used by the `:user` handle when `:user?` is true. |
| `:verbose` | `false` | Emit raw LLM responses for debugging. |
| `:log-writer` | none | Writer for verbose output. Supplying this also enables verbose output. The API closes it at the end of the run. |

## Return Shape

On success:

```clojure
{:result value
 :usage usage-atom
 :trace-dir "/path/to/trace"} ; only when tracing is enabled
```

On failure:

```clojure
{:error "message"
 :error-data {...}
 :usage usage-atom
 :trace-dir "/path/to/trace"} ; only when tracing is enabled
```

The API catches evaluation and provider exceptions and returns them as data. Input validation errors, such as supplying both `:prompt` and `:init`, should still throw.

## What Is Not In The Run API

The public run API should not expose provider or agent defaults directly. In particular, these currently exist in configuration files and should not be accepted as ordinary `run` options:

| Moved out of `run` | Configure in |
|---|---|
| `:model` | provider or agent config |
| `:max-tokens` | provider config |
| `:thinking` | agent config |
| `:reasoning-effort` | agent config |
| `:verbosity` | agent config |
| `:prefill?` | agent config |
| `:suffix-grammar?` | agent config |
| `:grammar-max-chars` | agent config |
| `:format` | agent config |
| `:retries` | agent or provider config |
| `:use-responses-api` | provider config |
| `:force-tool-call` | provider config |
| `:request-timeout-sec` | provider config |

The rule is: if an option changes how an agent talks to the model, it belongs in an agent or provider profile. If an option changes the lifecycle of this one run, it belongs in `spell.api/run`.

The implementation should reject removed run options with a clear validation error rather than silently ignoring them. Silent ignore would make it too easy to believe that a run used a specific model, format, or reasoning setting when it actually used the configured profile.

## Examples

Run a natural-language task with explicit provider and agent profiles:

```clojure
(spell/run {:prompt "Flip a fair coin and return :heads or :tails."
            :provider "config/providers/openai-tc.provider.edn"
            :agent "config/agents/base-tc.agent.edn"})
```

Run a complete Spell program:

```clojure
(spell/run {:init "(do (+ 20 22))"
            :provider "config/providers/openai-tc.provider.edn"
            :agent "config/agents/base-tc.agent.edn"})
```

Run with a budget and trace:

```clojure
(spell/run {:prompt "Solve the task, using tools if needed."
            :provider "config/providers/openai-tc.provider.edn"
            :agent "config/agents/cli.agent.edn"
            :budget 2.00
            :depth 20
            :trace true})
```

Use an already constructed provider instance:

```clojure
(require '[spell.provider :as provider])

(def p
  (provider/openai-provider {:model "gpt-5.4"
                             :force-tool-call true
                             :use-responses-api true}))

(spell/run {:prompt "Return 42."
            :provider p
            :agent "config/agents/base-tc.agent.edn"})
```

## Python Adapter Direction

The Python adapter should mirror this API shape rather than preserve benchmark-specific names. A future Python wrapper can expose the same request fields:

```python
spell.run(
    prompt="Return 42.",
    provider="config/providers/openai-tc.provider.edn",
    agent="config/agents/base-tc.agent.edn",
    budget=1.0,
    trace=True,
)
```

The adapter can still call a Clojure subprocess internally. Its public vocabulary should be `run`, `prompt`, `init`, `provider`, `agent`, `budget`, `depth`, `trace`, `trace_dir`, and `usage`, not benchmark-specific request and response names.
