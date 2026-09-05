# Changelog

## v0.3.0 - Unreleased

- Added immediate `agents/ask` and `agents/spawn-ask` with edge IDs, composable `!wait`/`!sleep`, durable all-target collections, and atomic admission through `:coordinator {:max-edges 10000}`. Waiting wrappers retain strict edge ordering; actionable replies require their request edge ID. Replaced the future-only `completion-promise` helper with atomic `blocking/request` tokens and made `send-await` dependencies coordinator-owned.

- Changed the CLI and OpenAI model-profile default to GPT-5.6 Sol with medium reasoning, while retaining explicit provider and reasoning overrides.
- Removed the CLI launcher's unnecessary dependency on `rlwrap` by invoking `clojure` directly.
- Added a new-spec-only MCP `2026-07-28` client with generated permissioned namespaces, Streamable HTTP and stdio transports, tools, resources, prompts, completion, subscriptions, and an explorer-style `spell mcp` CLI.
- Added a runnable MCP Everything-style example backed by the official Python SDK, including retained GPT-5.6 Sol real-model validation.
- Integrated MCP server-profile, permission, CLI, security, and supported-surface documentation into the public API and configuration reference.
- Added `--trace-dir DIR` for durable CLI traces and automatic agent/trace context on feedback entries.
- Added `--dogfood` to expose the feedback namespace to the main agent and its workers only for explicit self-improvement runs.
- Added `--agents-md` to prepend the current working directory's `AGENTS.md`, capped at 32 KiB, to natural-language CLI tasks.
- Added Agent Skills discovery with on-demand `SKILL.md` disclosure via a prompt-only `skills` namespace, removed `reminders`, renamed bundled skills to `spell-*` names where applicable, and added SnakeYAML for safe skill metadata parsing.
- Changed error recovery so proven trailing-expression failures reopen in place, while other evaluation and reader failures use one-turn inert recovery context that is pruned before subsequent turns; added a public recovery guide.
- Added Claude Fable 5.1 model aliases, pricing, adaptive thinking support, and automatic tool choice.

## v0.2.0

First public-facing software release.

Notable release-surface changes relative to v0.1.0:

- Added the public `spell.api/run` entry point with explicit `:model-profile` and `:agent-profile` inputs.
- Renamed checked-in runtime configuration to model profiles under `config/model-profiles/` and agent profiles under `config/agent-profiles/`.
- Curated the public examples to `hello-world`, `coin-flip`, `twenty-questions`, `telephone`, `auction`, and `chat`.
- Consolidated public documentation around `README.md`, `AGENTS.md`, and `docs/api.md`.
- Removed the experimental React namespace/profile from the public release surface.
- Added repo-local Spell skills for setup, configuration, and source navigation.

## v0.1.0 - 2026-05-06

Paper/reproducibility-oriented baseline release.
