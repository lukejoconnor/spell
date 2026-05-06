# Spell Source Guide For Review Agents

This file is a public orientation guide for AI agents and human reviewers reading the Spell source release. It intentionally avoids project-private notebook, benchmark-operations, and submission-management context.

## Project Summary

Spell is a Lisp dialect for LLM self-orchestration. A Spell completion is itself a program: the evaluator runs the program, and the program can call back into an LLM, spawn sub-agents, manage context, and use configured namespaces such as `io`, `web`, `agents`, `globals`, and `patterns`.

Start with `README.md` for setup and CLI usage, then use this file as a map of the codebase.

## Top-Level Layout

| Path | Purpose |
| --- | --- |
| `bin/spell` | Shell wrapper around `clj -M:run`; this is the reviewer-facing CLI. |
| `deps.edn` | Clojure dependencies and aliases, including `:run`, `:test-fast`, and `:test-slow`. |
| `src/spell/` | Main Spell implementation. |
| `config/` | Runtime agent, provider, prompt, and Spell library configuration. |
| `examples/` | Runnable `.spl` examples plus short writeups for selected examples. |
| `test/` | Unit and integration tests. |
| `data/pricing.edn` | Model pricing table used for usage/cost reporting. |
| `spell_benchmark_client.py` | JSON bridge client used by external benchmark harnesses. |
| `benchmarks.md` | Historical benchmark notes. |

## Core Runtime Files

| Path | Purpose |
| --- | --- |
| `src/spell/cli.clj` | CLI argument parsing, model/provider selection, trace/log options, and execution dispatch. |
| `src/spell/api.clj` | Programmatic `spell.api/run` entry point. Callers pass an explicit `:agent`. |
| `src/spell/eval.clj` | Main evaluator, special forms, environment threading, futures, self-calls, context-management forms, and namespace lookup. |
| `src/spell/parse.clj` | Reader/parser entry points for Spell forms. |
| `src/spell/grammar.clj` | Parenthesis and delimiter grammar checks. |
| `src/spell/format.clj` | Formatting helpers for Spell forms. |
| `src/spell/macros.clj` | Macro registry and macro expansion. |
| `src/spell/runtime.clj` | Agent boxes, registry, `spawn`, `ask`, `send`, notifier flow, and completion coordination. |
| `src/spell/llm.clj` | LLM request construction, prompt prefix handling, suffix cleanup, and inbox pipeline. |
| `src/spell/provider.clj` | Provider implementations for Anthropic, OpenAI, Codex tool-call, Fireworks, Ollama, user, and test modes. |
| `src/spell/agent.clj` | Agent definition loading, inheritance, namespace resolution, and provider default wiring. |
| `src/spell/prompt.clj` | System prompt composition from namespace metadata. |
| `src/spell/recovery.clj` | Recovery prompts and retry helpers for malformed completions. |
| `src/spell/trace.clj` | Execution trace recording. |
| `src/spell/trace_tool.clj` | CLI utilities for inspecting recorded traces. |
| `src/spell/benchmark_api.clj` | JSON API used by benchmark runners. |

## Standard Namespaces

| Path | Exposes |
| --- | --- |
| `src/spell/stdlib.clj` | Core `strings`, `math`, and built-in helper namespaces. |
| `src/spell/io.clj` | Filesystem and shell helpers exposed as `io/*` when the selected agent enables I/O. |
| `src/spell/web.clj` | Search and fetch helpers exposed as `web/*`. |
| `src/spell/globals.clj` | Shared global store exposed as `globals/*`. |
| `src/spell/patterns.clj` | Loader for reusable Spell patterns. |
| `src/spell/react.clj` | Hidden ReAct-style helper loop used by the `react` profile. |
| `src/spell/user.clj` | Manual user-provider implementation for `-m user`. |
| `src/spell/inbox.clj` | Message inbox helpers. |

## Configuration

| Path | Purpose |
| --- | --- |
| `config/agents/base-pf.agent.edn` | Base prefill-transport agent. |
| `config/agents/base-msg.agent.edn` | Base message-transport agent. |
| `config/agents/base-tc.agent.edn` | Base tool-call-transport agent. |
| `config/agents/cli.agent.edn` | Default CLI agent; enables `io`, `web`, `patterns`, `agents`, and `globals`. |
| `config/agents/io-pf.agent.edn` | I/O-capable prefill profile. |
| `config/agents/io-msg.agent.edn` | I/O-capable message profile. |
| `config/agents/io-tc.agent.edn` | I/O-capable tool-call profile. |
| `config/agents/explore.agent.edn` | Read-oriented exploration profile. |
| `config/agents/react.agent.edn` | Profile for hidden ReAct helper execution. |
| `config/prompts/sysprompt-prefill.txt` | System prompt for prefill-style providers. |
| `config/prompts/sysprompt-message.txt` | System prompt for message-style providers. |
| `config/prompts/sysprompt-toolcall.txt` | System prompt for mandatory tool-call providers. |
| `config/providers/*.provider.edn` | Declarative provider defaults and routing metadata. |
| `config/spl-lib/patterns.spl` | Reusable Spell pattern library. |
| `config/spl-lib/react.spl` | Spell library for hidden ReAct behavior. |
| `config/web.edn` | Web/search configuration. |

## Examples

Start with:

- `examples/hello-world.spl`: small self-call.
- `examples/coin-flip.spl`: recursion.
- `examples/twenty-questions.spl`: multi-agent loop.
- `examples/negotiate.spl`: ask/reply communication.
- `examples/auction.spl`: parallel agent pattern.
- `examples/fix-bug.spl`: I/O-backed coding example with setup script.
- `examples/chat.spl`: simple interactive communication.

`examples/README.md` lists all examples and recommended model tiers. Some examples have companion `.md` files with expected behavior and explanation.

## Running And Testing

```bash
bin/spell -h
bin/spell -t "Return a greeting"
bin/spell -e hello-world
bin/spell -m sonnet "Explain the examples directory."
clj -M:test-fast
clj -M:test-slow
```

Use `-T` to record traces and `--log FILE` or `-v` to inspect model responses.

## Reading Order

For evaluator semantics:

1. `src/spell/parse.clj`
2. `src/spell/eval.clj`
3. `src/spell/llm.clj`
4. `src/spell/runtime.clj`
5. `config/prompts/sysprompt-*.txt`

For CLI and provider behavior:

1. `bin/spell`
2. `src/spell/cli.clj`
3. `src/spell/provider.clj`
4. `config/providers/*.provider.edn`
5. `config/agents/*.agent.edn`

For examples:

1. `examples/README.md`
2. `examples/hello-world.spl`
3. `examples/twenty-questions.spl`
4. `src/spell/runtime.clj`

## Notes For Review Agents

- Prefer the checked-out code over old comments when behavior differs.
- Run `bin/spell -h` for the authoritative CLI options in this revision.
- `spell.api/run` requires an explicit `:agent`; the CLI supplies `config/agents/cli.agent.edn` by default.
- Agent files can inherit from other `.agent.edn` files with `:base`; resolve relative paths from the current agent file.
- Provider-prefixed model specs are parsed in `src/spell/cli.clj`.
- The nested `benchmarking/` directory, if present in a local checkout, is a separate repository and is not required to run the core Spell examples.
