# Changelog

## v0.3.0 - Unreleased

- Changed the CLI and OpenAI model-profile default to GPT-5.6 Sol with medium reasoning, while retaining explicit provider and reasoning overrides.
- Removed the CLI launcher's unnecessary dependency on `rlwrap` by invoking `clojure` directly.
- Added a new-spec-only MCP `2026-07-28` client with generated permissioned namespaces, Streamable HTTP and stdio transports, tools, resources, prompts, completion, subscriptions, and an explorer-style `spell mcp` CLI.
- Added a runnable MCP Everything-style example backed by the official Python SDK, including retained GPT-5.6 Sol real-model validation.
- Integrated MCP server-profile, permission, CLI, security, and supported-surface documentation into the public API and configuration reference.

## v0.2.0

First public-facing software release.

Notable release-surface changes relative to v0.1.0:

- Added the public `spell.api/run` entry point with explicit `:model-profile` and `:agent-profile` inputs.
- Renamed checked-in runtime configuration to model profiles under `config/model-profiles/` and agent profiles under `config/agent-profiles/`.
- Curated the public examples to `hello-world`, `coin-flip`, `twenty-questions`, `telephone`, `auction`, and `chat`.
- Consolidated public documentation around `README.md`, `AGENTS.md`, and `docs/api.md`.
- Removed the experimental React namespace/profile from the public release surface.
- Added repo-local Spell skills for setup, configuration, and source navigation.
- Added `--trace-dir DIR` for durable CLI traces and automatic agent/trace context on feedback entries.
- Expanded the read-only `explore` agent profile with web and feedback access while retaining its no-write, no-exec boundary.

## v0.1.0 - 2026-05-06

Paper/reproducibility-oriented baseline release.
