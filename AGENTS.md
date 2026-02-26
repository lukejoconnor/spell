# spell

A domain-specific language for LLM self-orchestration, implemented as a Lisp dialect in Clojure.

## Core Idea

Instead of an external harness controlling an agent loop, the LLM writes and extends its own execution graph.

## Key Semantic Concepts

### Environment Threading
`spell-eval` takes env in and returns env out. Environment state is explicit in program structure.

### Dynamic Scoping
`fn` and `defn` return source-form function data, not closures. Function bodies evaluate in caller env merged with parameter bindings.

### Expansion
`llm` auto-expands thunk prompts so free variables are replaced with quoted values from the current env. `expand` is available for explicit use.

### Quine (Self-Referential Code)
`(quine name body)` binds `name` to the full quine form as data, then evaluates `body`.

### Prompt-as-Prefix Semantics
Prompt text is used as both user message and assistant prefix. The model response is appended and the full text is parsed/evaluated as code.

### Completion Wrapper and Trailing Expression
Natural-language prompting is wrapped as `(quine completion (eval (do ...)))`. The last expression in the `do` block is evaluated again by outer `eval`, so quoted trailing forms like `'(extend)` or `'(agents/ask ...)` execute.

### Think / Rethink / Extend
`think` records a reasoning step, `rethink` marks previous sibling expressions for pruning, and `extend` continues with pruned context via `prune-and-reopen`.

### Namespaces
Spell has two namespace categories:
- Core namespaces (`strings`, `math`, `builtins`) are always available.
- Effect namespaces (`io`, `globals`, `agents`, `futures`, `patterns`, `llms`) are available in trailing-expression evaluation via `eval`.

Namespace maps use `:short-docs`, `:docs`, and optional `:detail`; `describe` surfaces this metadata in extensions.

## LLM Calls and Concurrency

### LLM Call Modes
- `llm-self`: serial self-calls on the same handle and execution tree.
- `agents/spawn`: asynchronous agent creation with a new handle.

### Concurrency Models
- Deterministic computation concurrency: `future`, `await`, `plet`, `futures/pmap`.
- Agent concurrency: `agents/spawn` plus coordination via `agents/ask` and `globals/*`.

These are intentionally separate: use futures for deterministic compute, and spawned agents for LLM-driven parallelism.

### Communication
`agents/ask` supports request/reply (`target msg`, poke-only `target`, and multi-target `[a b c]`). `agents/send` is fire-and-forget. Communication works by composing an inbox function that transforms the recipient's completion before box evaluation.

## Language Features

- 13 special forms and 26 spell macros (`defspellmacro`), including user-defined macros via `defmacro`.
- Vector destructuring (`&` rest, `:as`), `loop/recur` (including fn-level), `try/catch/throw`, `quine`, `compact`.
- Prompt-aware orchestration forms including `think`, `rethink`, `extend`, `call-now`, and `describe`.
- Inter-agent messaging (`spawn`, `ask`, `send`, reply variants), keyword handles, and message preemption semantics.

## Providers

Primary providers in day-to-day use:
- Anthropic (`anthropic-provider`)
- ChatGPT Codex messages path (`chatgpt-codex-provider`)
- ChatGPT Codex tool-call path (`chatgpt-codex-toolcall-provider`)

Other implemented providers:
- OpenAI, Ollama, Kimi, Test (plus provider-file loading via `.provider.edn`)

Each `.provider.edn` file includes a `:default-agent` key pointing to the transport-appropriate base agent.

## Agents

Three base agents (one per transport mode, no effect namespaces):
- `config/agents/base-prefill.agent.edn` — Anthropic (prefill mode)
- `config/agents/base-message.agent.edn` — message providers (no prefill)
- `config/agents/base-toolcall.agent.edn` — tool-call providers

Specialized agents inherit from a base and add namespaces:
- `config/agents/cli.agent.edn` — CLI default (base-toolcall + io, futures, patterns, agents, globals)
- `config/agents/bench/*.agent.edn` — benchmark agents (base-message or base-toolcall + io)

## CLI and API

- CLI: use `-h` or `--help` for current flags and usage. Default agent: `config/agents/cli.agent.edn`.
- Programmatic entry point: `spell.api/run` with `:prompt` or `:init`. Requires explicit `:agent` argument.

## Key Files

| Path | Description |
|------|-------------|
| `writeup/language-design.md` | Main language design writeup. |
| `writeup/paper.md` | Current paper draft. |
| `src/spell/eval.clj` | Core evaluator and special forms. |
| `src/spell/macros.clj` | Spell macro registry and macro implementations. |
| `src/spell/runtime.clj` | Box runtime, registry, ask/spawn/send, notifier flow. |
| `src/spell/llm.clj` | LLM wiring (`make-llm`, inbox pipeline, init builder). |
| `src/spell/prompt.clj` | System prompt composition from namespace metadata. |
| `src/spell/provider.clj` | Provider implementations and usage/cost tracking. |
| `src/spell/agent.clj` | Agent definition loading and llm factory wiring. |
| `src/spell/api.clj` | Public `run` entry point. |
| `src/spell/cli.clj` | CLI implementation. |
| `src/spell/benchmark_api.clj` | JSON benchmark API bridge. |
| `config/prompts/minimal.txt`, `config/prompts/minimal-no-prefill.txt`, `config/prompts/minimal-no-prefill-toolcall.txt` | Base prompt variants; provider-agnostic behavior changes should be applied consistently to all three unless intentionally variant-specific. |
| `config/agents/*.agent.edn` | Agent specs. |
| `config/providers/*.provider.edn` | Declarative provider specs. |
| `test/spell/*_test.clj` | Interpreter/runtime/provider tests. |
| `benchmarking/AGENTS.md` | Benchmark workflow and reporting guidance (in nested benchmarking repo). |
| `notebook/TODO.md`, `notebook/DONE.md`, `notebook/INDEX.md` | Active tasks, completed tasks, and notebook index. |

## Architecture (Condensed)

Current model (kept intentionally short here):
1. `spell-eval`: pure evaluator.
2. `eval` builtin in llm pipeline: effectful second-pass evaluation for trailing expression.
3. `runtime/box`: executes completion, drains inbox transforms, manages lifecycle/notifications.
4. LLM call layer: provider invocation and delivery into runtime.
5. `api/run`: top-level wiring for prompt/init + agent/provider config.

Follow-up is tracked in TODO #132 to reassess whether this section should be removed once `writeup/language-design.md` fully covers implementation architecture.

## Benchmarking

`benchmarking/` is a separate nested git repository (`benchmarking/.git`).
See `benchmarking/AGENTS.md` for benchmark commands, datasets, and reporting expectations.
Use `uv run` for Python benchmark tooling.
