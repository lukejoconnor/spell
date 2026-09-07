# Changelog

## Unreleased

- Changed the CLI default provider to `codex-tc`, using local Codex authentication. The default model remains GPT-6 Astra with medium reasoning.

## v0.4.0 - 2026-09-06

### Communication and context

- Each run now owns an atomic coordinator for agent lifecycles, mailboxes, requests, and wakeups. Concurrent runs keep their coordination state separate.
- Added immediate `agents/ask` and `agents/spawn-ask`: request results from one or more agents, continue working, then use `agents/!wait` or `agents/!sleep`. Multi-target requests collect one result per target. Replies identify their request, and unrelated messages can awaken a caller while its collections remain pending. See [multi-agent communication](multi-agent.md).
- Enforced an ordering rule for communication waits to prevent coordinator-wait deadlock, assuming fair scheduling and eventual external progress. Refused waits raise recoverable errors so agents can handle outstanding requests. Added atomic capacity admission through `:coordinator {:max-edges 10000}`.
- Added tracked terminal requests through `/ask`, `/requests`, and `/cancel`. Users can collect results from several agents while continuing to exchange messages and answer clarification requests.
- Made message receipt explicit for raw `!llm-self` calls: messages remain queued unless `{:receive? true}` is supplied. Convenience wrappers receive automatically. Added `(receive completion)` to accept messages into a completed program without evaluating it. Waits and wakeups retain the latest resumable context, and recovery preserves the failing call's receipt policy.
- Replaced the future-only `blocking/completion-promise` helper with atomic `blocking/request` tokens. Requests from futures participate in the coordinator's dependency tracking.
- Unified tool, agent-message, and MCP result rendering under `:context-max-chars`, defaulting to 10,000 characters per contribution. Oversized values remain complete in run-local storage and can be inspected in smaller pieces. Negative local display limits now use the run cap.
- Improved agent guidance for retaining continuations, checking which communication actions executed, recovering from refused waits, and answering outstanding requests before returning.

### Models and providers

- Changed the CLI, OpenAI, and Codex model-profile defaults to GPT-6 Astra with medium reasoning. Added Astra aliases, model limits, and standard-tier cost tracking. Higher pricing above 272K input tokens is documented but not applied automatically.
- Fixed Codex streaming responses that deliver completed tool or message items before an empty final output list, while still rejecting failed or incomplete responses.
- Added opt-in Kimi K3 aliases and Fireworks pricing.
- Fixed retries for incomplete OpenAI Responses output when a response status is a string, preserving reported usage.

### Documentation

- Added the VitePress documentation site, with guides, API reference, local search, and a production-build check. The README links to the documentation, and both landing pages introduce the communication model.

## v0.3.0

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
