# Spell

Self programmed execution language for LMs: a domain-specific language for agentic self-orchestration and own-context management, implemented as a Lisp dialect in Clojure.

## Self-programmed execution

Instead of an external harness controlling an agent loop, the LLM programs its own execution graph. Its entire completion is a program, the language of which is Spell. The harness is purely an execution layer: it evaluates the program. The program can re-invoke the LLM, and it controls exactly what context is passed in.

## Language Overview

See `notebook/writeup/paper/language-design-v2.md` for full semantics. Key concepts:

- **Self-calls**: `(!llm-self prefix)` makes an LLM call. `!call-now`, `!print`, and `!extend` wrap or expand to `!llm-self`.
- **Quine**: `(quine name body)` binds `name` to its own source form as data, enabling self-referential LLM calls.
- **Environment threading**: `spell-eval` takes env in, returns env out. State is explicit in program structure.
- **Dynamic scoping**: `fn`/`defn` return source forms, not closures; bodies evaluate in caller env.
- **Prompt-as-prefix**: prompt text is both user message and assistant prefix; response is appended and eval'd.
- **Double evaluation**: the outer `eval` in the completion wrapper makes effect namespaces available to the trailing expression. Without it, only core namespaces are in scope.
- **Completion wrapper**: NL prompts wrapped as `(quine completion (eval (do ...)))` with double evaluation of trailing expression.
- **Think/prune/rethink/extend**: context management. `prune` removes sibling expressions, `rethink` is `prune` plus residual `think`, and `!extend` continues with pruned context.
- **Namespaces**: core (`strings`, `math`, `builtins`) always available. Effect (`io`, `web`, `globals`, `agents`, `patterns`) available via trailing-expression `eval`. Future threads also get `blocking/` (`await`, `await-all`, `pmap`, `completion-promise`, `send-await`).
- **Concurrency**: `!llm-self` for serial self-calls; `agents/spawn` for async agents; `future`/`blocking/await` for deterministic compute. These are intentionally separate.
- **Communication**: `agents/!ask` (request/reply, poke-only, multi-target), `agents/send` (fire-and-forget), keyword handles. `!ask-await` bridges main-thread agent waits with future waits.

## Providers and Agents

See `config/CLAUDE.md` for provider/agent details. Base agents: `base-pf`, `base-msg`, `base-tc`, `base-glm` (GLM-5, experimental — see `glm5-failure-modes`). Agents with `:llms` get a dynamic `llms/` namespace.

## CLI and API

- CLI: use `-h` or `--help` for current flags and usage. Default agent: `config/agents/cli.agent.edn`.
- Programmatic entry point: `spell.api/run` with `:prompt` or `:init`. Requires explicit `:agent` argument.

## Key Files

| Path | Description |
|------|-------------|
| `notebook/writeup/paper/language-design-v2.md` | Main language design writeup. |
| `notebook/writeup/paper/paper.md` | Paper draft v1. |
| `notebook/writeup/paper/paper_v2.md` | Paper draft v2 (AI-edited, being merged with v1). |
| `src/spell/eval.clj` | Core evaluator and special forms. |
| `src/spell/macros.clj` | Spell macro registry and macro implementations. |
| `src/spell/runtime.clj` | Box runtime, registry, ask/spawn/send, notifier flow. |
| `src/spell/llm.clj` | LLM wiring (`make-llm`, inbox pipeline, init builder). |
| `src/spell/prompt.clj` | System prompt composition from namespace metadata. |
| `src/spell/provider.clj` | Provider implementations and usage/cost tracking. |
| `data/pricing.edn` | Shared model pricing table used by both Spell and the benchmarking repo. |
| `src/spell/agent.clj` | Agent definition loading and llm factory wiring. |
| `src/spell/api.clj` | Public `run` entry point. |
| `src/spell/cli.clj` | CLI implementation. |
| `src/spell/benchmark_api.clj` | JSON benchmark API bridge. |
| `config/prompts/minimal.txt`, `config/prompts/minimal-no-prefill.txt`, `config/prompts/minimal-no-prefill-toolcall.txt` | Base prompt variants; provider-agnostic behavior changes should be applied consistently to all three unless intentionally variant-specific. |
| `notebook/ERROR_WATCHLIST.md` | Low-frequency observed errors not yet warranting runtime intervention. |

## Rules

- **No backwards compatibility.** This is a nascent project with effectively zero users. Do not add compatibility shims, legacy placeholders, migration paths, reopen support for prior serialized shapes, or deprecated aliases unless the user explicitly asks for them. Prefer replacing old paths outright when that simplifies the system.
- **Redesign means replace, not accumulate.** When switching from approach A to approach B, the plan must include deleting approach A. Do not leave the old approach in the codebase "for now" or "for compatibility." We want fewer options, not more; one approach, not two. If the plan doesn't explicitly include removing the old code/config/files, the plan is incomplete.
- **Model names must be exact.** Never guess or extrapolate model identifiers (e.g., don't assume `gpt-5.4-codex` exists because `gpt-5.3-codex` does). Always consult the provider's API docs or web search to confirm the exact model ID string and pricing before adding models or making API calls.
- When the user asks for a plan, always enter plan mode (using the EnterPlanMode tool). After the plan is created, tell the user the filesystem path where the plan file is located.
- When planning any change, consider whether this doc (CLAUDE.md) should be updated; if so, add that to the plan.
- When planning, consider whether a notebook entry is warranted; if so, add entry creation to the plan (almost always yes).
- For benchmarking analyses, always use the run-benchmark skill.
- Whenever possible, delegate to subagents or use task lists, preserving the main context window for iteration and discussion.

## Benchmarking

`benchmarking/` is a separate nested git repository (`benchmarking/.git`).
See `benchmarking/AGENTS.md` for benchmark commands, datasets, and reporting expectations.
Use `uv run` for Python benchmark tooling.

### GCP Benchmark VM

Use `scripts/gcp-benchmark.sh` to run long benchmark jobs on a GCP VM:

```bash
./scripts/gcp-benchmark.sh run --name <vm-name> --run-group <group> --command "<benchmark command>"
./scripts/gcp-benchmark.sh wait --run-group <group> --finish
./scripts/gcp-benchmark.sh status-all --run-group <group>
```

Recommended workflow:
- `run` is the default path for unattended Docker-backed evals. It creates the VM, waits for startup, stages a benchmark wrapper into the tmux session, and returns without attaching.
- `wait --run-group <group> --finish` is the standard way to monitor a batch. It prints a one-line status summary each polling cycle, then pulls artifacts and deletes terminal VMs before exiting. Run it in the background when you want a single completion notification.
- `status-all --run-group <group>` remains the ad-hoc fleet snapshot command. `--all` is the project-wide escape hatch when you intentionally want every Spell-managed VM.
- `pull-all --run-group <group>` is available for non-destructive syncs, and `finish-all --run-group <group>` remains the manual cleanup path.
- `start` and `ssh` remain the manual debugging path.
- `wait` now bounds SSH polling and eventually treats persistently unreachable VMs as terminal for the wait loop, so one dead VM will not hang the whole fleet. `finish-all` remains conservative and skips VMs it cannot still pull.

The launcher tags each managed VM with `managed-by=spell-benchmark` plus a run-group label for fleet discovery. The full run-group string and benchmark command are also stored in instance metadata for detailed reporting.

The VM bootstrap lives in `scripts/gcp-startup.sh`. It clones the main `spell` repo and then separately clones `spell-benchmarking` into `spell/benchmarking`, because `benchmarking/` is an ignored nested repo rather than part of the main checkout. After the first successful bootstrap, later stop/start cycles take a fast path that refreshes secrets, recreates the tmux shell session, and preserves the existing checkout and benchmark artifacts.

Pulled artifacts land directly under:
- `benchmarking/results/gcp/<vm-name>/...`
- `benchmarking/traces/gcp/<vm-name>/...`
- `benchmarking/logs/gcp/<vm-name>/...`

One-time setup on your Mac:
- Install and authenticate `gcloud` (`brew install --cask gcloud-cli`, then `gcloud init`)
- Enable `compute.googleapis.com` and `secretmanager.googleapis.com`
- Store `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, and a read-only `GITHUB_TOKEN` in Secret Manager
- For `codex-tc` model runs: store `CODEX_AUTH_JSON_B64` in Secret Manager (base64-encode your local `~/.codex/auth.json`). Without this, use `openai-tc:gpt-5.4` instead (standard OpenAI API).
- For Claude Code with Max subscription: run `claude setup-token` locally, then store the resulting token as `CLAUDE_CODE_OAUTH_TOKEN` in Secret Manager. Without this, Claude Code uses `ANTHROPIC_API_KEY` (API billing) instead of the subscription.
- Grant the default Compute Engine service account `roles/secretmanager.secretAccessor` on those secrets
- Ensure the project has a usable VPC network. If it has no `default` VPC, create one or pass `--network <name>` to the launcher.

**SWE-bench on fresh VMs:** The first SWE-bench run on a new VM requires building environment images (~10 min per unique env). These are cached for subsequent runs. A full SWE-bench Lite run touches ~20 unique envs, so initial bootstrapping can take ~3 hours. Consider pre-building images or using a persistent disk cache.

The launcher uses Compute Engine's built-in `--max-run-duration` with `--instance-termination-action=DELETE` so benchmark VMs auto-delete instead of lingering after long unattended runs.
