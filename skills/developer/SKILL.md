---
name: spell-developer
description: Navigate and develop the Spell source code. Use when modifying Spell internals, investigating evaluator/runtime/provider behavior, finding the right source files, updating tests, or explaining how the implementation fits together.
---

# Spell Developer

Use this skill when working inside the Spell implementation. Prefer the checked-out code over stale comments.

## Public Surface

Public API and configuration details live in `docs/api.md`. Treat that file, the checked-in `config/` profiles, and `bin/spell -h` as the authoritative release-facing surfaces.

## Core Semantics

Spell is a Lisp dialect for self-programmed execution. A model completion is a program. The evaluator runs it, and the program can call back into an LLM, spawn agents, manage context, and use configured namespaces.

Key terms:

- Self-call: `(!llm-self prefix)` makes a recursive LLM call.
- Quine: `(quine name body)` binds `name` to its own source form.
- Effect boundary: effect namespaces are available through trailing-expression `eval`.
- Edit marker: `prune`, `rethink`, and `persist` affect how `apply-edits` prepares later context.
- Prompt-as-prefix: prompt text is both user message and assistant prefix; the model suffix is appended and evaluated.

## Source Map

Core runtime:

- `src/spell/eval.clj`: evaluator, special forms, futures, self-calls, context markers, namespace lookup.
- `src/spell/runtime.clj`: boxes, registry, spawn/ask/send, notifications, completion coordination.
- `src/spell/llm.clj`: LLM request construction, prompt prefix handling, suffix cleanup, inbox pipeline.
- `src/spell/provider.clj`: Anthropic, OpenAI, Codex CLI, Fireworks, Ollama, user, and test providers.
- `src/spell/agent.clj`: agent profile loading, inheritance, namespace resolution, model default wiring.
- `src/spell/api.clj`: public `spell.api/run` entry point.
- `src/spell/cli.clj`: CLI parsing, provider/model selection, traces/logging, dispatch.

Language and support:

- `src/spell/parse.clj`: reader/parser entry points.
- `src/spell/grammar.clj`: delimiter and grammar checks.
- `src/spell/format.clj`: formatting helpers.
- `src/spell/macros.clj`: macro registry and macro expansion.
- `src/spell/prompt.clj`: system prompt composition from namespace metadata.
- `src/spell/recovery.clj`: malformed-completion recovery prompts and retry helpers.
- `src/spell/trace.clj` and `src/spell/trace_tool.clj`: trace recording and inspection.

Namespaces:

- `src/spell/stdlib.clj`: core `strings`, `math`, builtins, reminders, and namespace metadata.
- `src/spell/io.clj`: filesystem and shell helpers.
- `src/spell/web.clj`: search and fetch helpers.
- `src/spell/globals.clj`: shared global store.
- `src/spell/patterns.clj`: reusable Spell pattern loader.
- `src/spell/inbox.clj`: message inbox helpers.

Configuration:

- `config/agent-profiles/*.agent.edn`: runtime agent profiles.
- `config/model-profiles/*.edn`: provider/model-call profiles.
- `config/prompts/sysprompt-*.txt`: transport-specific system prompts.
- `config/spl-lib/patterns.spl`: reusable Spell programs.
- `data/pricing.edn`: shared model pricing table.

## Reading Order

Evaluator semantics:

1. `src/spell/parse.clj`
2. `src/spell/eval.clj`
3. `src/spell/llm.clj`
4. `src/spell/runtime.clj`
5. `config/prompts/sysprompt-*.txt`

CLI and providers:

1. `bin/spell`
2. `src/spell/cli.clj`
3. `src/spell/provider.clj`
4. `config/model-profiles/*.edn`
5. `config/agent-profiles/*.agent.edn`

Examples:

1. `examples/README.md`
2. `examples/hello-world.spl`
3. `examples/coin-flip.spl`
4. `examples/twenty-questions.spl`
5. `examples/telephone.spl`
6. `src/spell/runtime.clj`

## Tests

Use focused tests first, then broader checks:

```bash
clj -M:test-fast
clj -M:test-slow
```

The fast suite covers parser, evaluator, provider, agent, web, API, trace, macro, and prompt-facing behavior. The slow suite covers concurrency, I/O, runtime, globals, and user-provider behavior.

For trace debugging:

```bash
clj -M -m spell.trace-tool --trace-dir DIR --summary
```

Use `bin/spell -h` as the authoritative CLI option reference for the current checkout.
