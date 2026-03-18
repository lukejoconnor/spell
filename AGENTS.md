# Spell

Self programmed execution language for LMs: a domain-specific language for agentic self-orchestration and own-context management, implemented as a Lisp dialect in Clojure.

## Self-programmed execution

Instead of an external harness controlling an agent loop, the LLM programs its own execution graph. Its entire completion is a program, the language of which is Spell. The harness is purely an execution layer: it evaluates the program. The program can re-invoke the LLM, and it controls exactly what context is passed in.

## Language Overview

See `writeup/language-design-v2.md` for full semantics. Key concepts:

- **Self-calls**: `(!llm-self prefix)` makes an LLM call. `!call-now`, `!print`, and `!extend` wrap or expand to `!llm-self`.
- **Quine**: `(quine name body)` binds `name` to its own source form as data, enabling self-referential LLM calls.
- **Environment threading**: `spell-eval` takes env in, returns env out. State is explicit in program structure.
- **Dynamic scoping**: `fn`/`defn` return source forms, not closures; bodies evaluate in caller env.
- **Prompt-as-prefix**: prompt text is both user message and assistant prefix; response is appended and eval'd.
- **Double evaluation**: the outer `eval` in the completion wrapper makes effect namespaces available to the trailing expression. Without it, only core namespaces are in scope.
- **Completion wrapper**: NL prompts wrapped as `(quine completion (eval (do ...)))` with double evaluation of trailing expression.
- **Think/rethink/extend**: context management. `rethink` prunes sibling expressions; `!extend` continues with pruned context.
- **Namespaces**: core (`strings`, `math`, `builtins`) always available. Effect (`io`, `web`, `globals`, `agents`, `patterns`) available via trailing-expression `eval`. Future threads also get `blocking/` (`await`, `await-all`, `pmap`, `completion-promise`, `send-await`).
- **Concurrency**: `!llm-self` for serial self-calls; `agents/spawn` for async agents; `future`/`blocking/await` for deterministic compute. These are intentionally separate.
- **Communication**: `agents/!ask` (request/reply, poke-only, multi-target), `agents/send` (fire-and-forget), keyword handles. `!ask-await` bridges main-thread agent waits with future waits.

## Providers and Agents

Primary providers: Anthropic tool-call (`anthropic-tc`) and Codex tool-call (`codex-tc`). Test provider for unit tests. See `config/providers/` for all `.provider.edn` files and `config/CLAUDE.md` for loading semantics.

Three base agents (prefill, message, tool-call) in `config/agents/base-*.agent.edn`, plus `base-glm` for GLM-5 (experimental; currently unreliable — see `glm5-failure-modes`). Specialized agents inherit and add namespaces. See `config/agents/` for full listing and `config/CLAUDE.md` for inheritance rules.

Agents with `:llms` in their `.agent.edn` get a dynamically generated `llms/` namespace with named sub-LLM variants.

## CLI and API

- CLI: use `-h` or `--help` for current flags and usage. Default agent: `config/agents/cli.agent.edn`.
- Programmatic entry point: `spell.api/run` with `:prompt` or `:init`. Requires explicit `:agent` argument.

## Key Files

| Path | Description |
|------|-------------|
| `writeup/language-design-v2.md` | Main language design writeup. |
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

## Rules

- When the user asks for a plan, always enter plan mode (using the EnterPlanMode tool). After the plan is created, tell the user the filesystem path where the plan file is located.
- When planning any change, consider whether this doc (CLAUDE.md) should be updated; if so, add that to the plan.
- When planning, consider whether a notebook entry is warranted; if so, add entry creation to the plan (almost always yes).
- For benchmarking analyses, always use the run-benchmark skill.
- Whenever possible, delegate to subagents or use task lists, preserving the main context window for iteration and discussion.

## Benchmarking

`benchmarking/` is a separate nested git repository (`benchmarking/.git`).
See `benchmarking/AGENTS.md` for benchmark commands, datasets, and reporting expectations.
Use `uv run` for Python benchmark tooling.
