# Spell Source Guide

This file is a public orientation guide for readers and contributors working through the Spell source tree. Start with `README.md` for setup and CLI usage, read `docs/language-overview.md` for the core language ideas, then use this file as a map of the implementation, configuration, examples, and tests.

Spell is a Lisp dialect for LLM self-orchestration. A Spell completion is itself a program: the evaluator runs the program, and the program can call back into an LLM, spawn sub-agents, manage context, and use configured namespaces such as `io`, `web`, `agents`, `globals`, and `patterns`.

## Top-Level Layout

| Path | Purpose |
| --- | --- |
| `bin/spell` | Shell wrapper around the Clojure CLI entry point. |
| `deps.edn` | Clojure dependencies and aliases, including `:run`, `:test-fast`, and `:test-slow`. |
| `src/spell/` | Main Spell implementation. |
| `config/` | Runtime agent, provider, prompt, web, and Spell library configuration. |
| `examples/` | Runnable `.spl` examples plus short writeups for selected examples. |
| `test/` | Unit and integration tests. |
| `data/pricing.edn` | Model pricing table used for usage and cost reporting. |
| `docs/` | Public documentation for the release. |
| `docs/language-overview.md` | Short conceptual guide to completions, self-calls, quines, context management, and agents. |

## Core Runtime Files

| Path | Purpose |
| --- | --- |
| `src/spell/cli.clj` | CLI argument parsing, model/provider selection, trace/log options, and execution dispatch. |
| `src/spell/eval.clj` | Main evaluator, special forms, environment threading, futures, self-calls, context-management forms, and namespace lookup. |
| `src/spell/parse.clj` | Reader/parser entry points for Spell forms. |
| `src/spell/grammar.clj` | Parenthesis and delimiter grammar checks. |
| `src/spell/format.clj` | Formatting helpers for Spell forms. |
| `src/spell/macros.clj` | Macro registry and macro expansion. |
| `src/spell/runtime.clj` | Agent boxes, registry, `spawn`, `ask`, `send`, notifier flow, and completion coordination. |
| `src/spell/llm.clj` | LLM request construction, prompt prefix handling, suffix cleanup, and inbox pipeline. |
| `src/spell/provider.clj` | Provider implementations for Anthropic, OpenAI, Codex CLI, Fireworks, Ollama, user, and test modes. |
| `src/spell/agent.clj` | Agent definition loading, inheritance, namespace resolution, and provider default wiring. |
| `src/spell/prompt.clj` | System prompt composition from namespace metadata. |
| `src/spell/recovery.clj` | Recovery prompts and retry helpers for malformed completions. |
| `src/spell/trace.clj` | Execution trace recording. |
| `src/spell/trace_tool.clj` | Developer tooling for inspecting recorded traces. |
| `src/spell/api.clj` | Programmatic entry point used by library callers; API details are documented separately. |

## Standard Namespaces

| Path | Exposes |
| --- | --- |
| `src/spell/stdlib.clj` | Core `strings`, `math`, and built-in helper namespaces. |
| `src/spell/io.clj` | Filesystem and shell helpers exposed as `io/*` when the selected agent enables I/O. |
| `src/spell/web.clj` | Search and fetch helpers exposed as `web/*`. |
| `src/spell/globals.clj` | Shared global store exposed as `globals/*`. |
| `src/spell/patterns.clj` | Loader for reusable Spell patterns. |
| `src/spell/user.clj` | Manual user-provider implementation for `-m user`. |
| `src/spell/inbox.clj` | Message inbox helpers. |

## Configuration

See `config/AGENTS.md` for a directory-specific guide.

| Path | Purpose |
| --- | --- |
| `config/agents/base-pf.agent.edn` | Base prefill-transport agent. |
| `config/agents/base-msg.agent.edn` | Base message-transport agent. |
| `config/agents/base-tc.agent.edn` | Base tool-call-transport agent. |
| `config/agents/cli.agent.edn` | Default CLI agent; enables `io`, `web`, `patterns`, `agents`, and `globals`. |
| `config/agents/io-pf.agent.edn` | I/O-capable prefill profile. |
| `config/agents/io-msg.agent.edn` | I/O-capable message profile. |
| `config/agents/io-tc.agent.edn` | I/O-capable tool-call profile. |
| `config/prompts/sysprompt-prefill.txt` | System prompt for prefill-style providers. |
| `config/prompts/sysprompt-message.txt` | System prompt for message-style providers. |
| `config/prompts/sysprompt-toolcall.txt` | System prompt for mandatory tool-call providers. |
| `config/providers/*.provider.edn` | Declarative provider defaults and routing metadata. |
| `config/spl-lib/patterns.spl` | Reusable Spell pattern library. |
| `config/web.edn` | Web/search configuration. |

First-class public provider paths are OpenAI, Anthropic, Fireworks, and Codex CLI. The Codex CLI path uses local Codex authentication and should be treated as experimental.

## Examples

See `examples/AGENTS.md` for a directory-specific guide and `examples/README.md` for the user-facing example list.

Start with:

- `examples/hello-world.spl`: small self-call.
- `examples/coin-flip.spl`: recursion and branching.
- `examples/twenty-questions.spl`: multi-agent loop.
- `examples/telephone.spl`: sequential relay loop.
- `examples/auction.spl`: parallel agent pattern.
- `examples/chat.spl`: interactive communication.

Each public example has a companion `.md` file with expected behavior and explanation.

## Running And Testing

```bash
bin/spell -h
bin/spell -t "Return a greeting"
bin/spell -e hello-world
bin/spell "Explain the examples directory."
clj -M:test-fast
clj -M:test-slow
```

Use `-T` to record an execution trace under the temporary Spell trace directory. The trace tool can inspect a trace directory directly, for example:

```bash
clj -M -m spell.trace-tool --trace-dir DIR --summary
```

Use `--log FILE` or `-v` when you need to inspect model responses during development.

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
3. `examples/coin-flip.spl`
4. `examples/twenty-questions.spl`
5. `examples/telephone.spl`
6. `src/spell/runtime.clj`

## Notes For Readers

- Prefer the checked-out code over old comments when behavior differs.
- Run `bin/spell -h` for the authoritative CLI options in this revision.
- Agent files can inherit from other `.agent.edn` files with `:base`; resolve relative paths from the current agent file.
- Provider-prefixed model specs are parsed in `src/spell/cli.clj`.
- `SERPER_API_KEY` is only needed when using the `web` namespace with Serper-backed search.
