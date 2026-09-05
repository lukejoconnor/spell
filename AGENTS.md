# Spell Source Guide

This file is a public orientation guide for agents, readers, and contributors working through the Spell source tree. Start with `README.md` for the human-facing project overview, CLI usage, and core language ideas, then use this file for installation checks, source-map lookup, tests, and implementation orientation.

Current release state: `v0.3.0` is unreleased. The public Clojure API and configuration surface is documented in `docs/api.md`.

Spell is a Lisp dialect for LLM self-orchestration. A Spell completion is itself a program: the evaluator runs the program, and the program can call back into an LLM, spawn sub-agents, manage context, and use configured namespaces such as `io`, `web`, `agents`, `globals`, and `patterns`.

## Repo Skills

This repo includes Spell-specific skills under `.agents/skills/`. Use them as the first stop for task-oriented work:

- `spell-setup`: install/check prerequisites, configure provider credentials, and run the first smoke tests or examples.
- `spell-agent-config`: create or modify model profiles, agent profiles, and MCP server profiles; use `docs/api.md` as the canonical API/config reference.
- `spell-developer`: navigate and modify the Spell source code, choose relevant files, and run focused checks.

## Terminology

- Edit marker: a source form, such as `prune`, `rethink`, or `persist`, that affects how `apply-edits` rewrites a completion for a later turn.
- Edit time: the phase when `apply-edits` applies edit markers to a completion before it is used as a model prefix.
- MCP server profile: a reusable `.mcp.edn` connection and environment-backed authentication definition.
- Server alias: the agent-profile name that identifies an MCP server and becomes its generated Spell namespace.

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
| `docs/CHANGELOG.md` | Release notes; `v0.3.0` is currently unreleased. |
| `LICENSE` | MIT license text. |

## Agent Quick Start

Prerequisites:

- Java 11+
- Clojure CLI (`clojure`)
- At least one LLM provider credential or local provider

On macOS with Homebrew:

```bash
brew install clojure/tools/clojure
```

Provider setup:

- OpenAI API, the GPT-6 Astra CLI default: set `OPENAI_API_KEY`.
- Codex tool-call provider: install the OpenAI Codex CLI and run `codex` once so `~/.codex/auth.json` exists. This path is experimental and must be selected explicitly.
- Anthropic API: set `ANTHROPIC_API_KEY`.
- OpenAI API: set `OPENAI_API_KEY`.
- Fireworks API: set `FIREWORKS_API_KEY`.
- Ollama: run a local Ollama server and pass an `ollama:<model>` model spec.

Install from a fresh checkout:

```bash
git clone https://github.com/lukejoconnor/spell.git
cd spell
```

No build step is required for normal CLI use. The `bin/spell` wrapper runs the Clojure CLI entry point with `clojure -M:run`.

Smoke test without making an LLM API call:

```bash
bin/spell -h
bin/spell -t "Return a short greeting"
```

The `-t` flag uses the test provider and is useful for checking Java, Clojure, dependencies, and CLI wiring.

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

The public API/configuration reference is `docs/api.md`. In `v0.3.0`, `spell.api/run` requires `:model-profile` and `:agent-profile`, and rejects old public run keys such as `:provider`.

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
| `config/agent-profiles/base-pf.agent.edn` | Base prefill-transport agent. |
| `config/agent-profiles/base-msg.agent.edn` | Base message-transport agent. |
| `config/agent-profiles/base-tc.agent.edn` | Base tool-call-transport agent. |
| `config/agent-profiles/cli.agent.edn` | Default CLI agent; enables `io`, `web`, `patterns`, `agents`, and `globals`. |
| `config/agent-profiles/io-pf.agent.edn` | I/O-capable prefill profile. |
| `config/agent-profiles/io-msg.agent.edn` | I/O-capable message profile. |
| `config/agent-profiles/io-tc.agent.edn` | I/O-capable tool-call profile. |
| `config/prompts/sysprompt-prefill.txt` | System prompt for prefill-style providers. |
| `config/prompts/sysprompt-message.txt` | System prompt for message-style providers. |
| `config/prompts/sysprompt-toolcall.txt` | System prompt for mandatory tool-call providers. |
| `config/model-profiles/*.edn` | Declarative model provider defaults and routing metadata. |
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
clojure -M:test-fast
clojure -M:test-slow
```

The fast suite covers parser, evaluator, provider, agent, web, API, trace, macro, and prompt-facing behavior. The slow suite covers concurrency, I/O, runtime, globals, and user-provider behavior. `deps.edn` is the authoritative list of test aliases and included namespaces.

Use `-T` to record an execution trace under the temporary Spell trace directory, or `--trace-dir DIR` to write it to an explicit durable location. Use `--dogfood` to expose the feedback namespace to the main agent and its workers. Use `--agents-md` to prepend the current working directory's `AGENTS.md`, capped at 32 KiB, to a natural-language task. The trace tool can inspect a trace directory directly, for example:

```bash
clojure -M -m spell.trace-tool --trace-dir DIR --summary
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
4. `config/model-profiles/*.edn`
5. `config/agent-profiles/*.agent.edn`

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
- Agent profile files can inherit from other `.agent.edn` files with `:base`; resolve relative paths from the current agent profile file.
- Provider-prefixed model specs are parsed in `src/spell/cli.clj`.
- `SERPER_API_KEY` is only needed when using the `web` namespace with Serper-backed search.
- License is MIT; the full text is in `LICENSE`.
