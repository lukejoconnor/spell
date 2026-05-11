# Config Directory Guide

This directory contains runtime configuration used by Spell execution.

## Scope

- `agents/`: `.agent.edn` agent definitions.
- `prompts/`: system prompt text variants.
- `providers/`: declarative provider specs (`.provider.edn`).
- `spl-lib/`: reusable Spell library files.
- `web.edn`: optional defaults for web search and fetch behavior.

## Providers

First-class public provider paths are:

- OpenAI tool-call mode, configured by `providers/openai-tc.provider.edn` and `OPENAI_API_KEY`.
- Anthropic tool-call mode, configured by `providers/anthropic-tc.provider.edn` and `ANTHROPIC_API_KEY`.
- Fireworks, configured by `providers/fireworks.provider.edn` or `providers/fireworks-tc.provider.edn` and `FIREWORKS_API_KEY`.
- Codex CLI, configured by `providers/codex-tc.provider.edn` and local Codex authentication. Treat this path as experimental.

The `:test` provider is used by the test suite. Other provider files may exist for local development, but they are not the primary public path for this release.

## Agent Files

Agent files are loaded by `src/spell/agent.clj`.

Base agents define transport-level behavior and do not add effect namespaces:

- `base-pf.agent.edn`: prefill providers, using `prompts/sysprompt-prefill.txt`.
- `base-msg.agent.edn`: message providers, using `prompts/sysprompt-message.txt`.
- `base-tc.agent.edn`: tool-call providers, using `prompts/sysprompt-toolcall.txt`.

Public runtime profiles inherit from a base agent and add namespaces:

- `cli.agent.edn`: CLI default; enables `io`, `web`, `patterns`, `agents`, and `globals`.
- `io-pf.agent.edn`: I/O-capable prefill profile with `io`, `patterns`, `agents`, and `globals`; web is disabled by default.
- `io-msg.agent.edn`: I/O-capable message profile with `io`, `patterns`, `agents`, and `globals`; web is disabled by default.
- `io-tc.agent.edn`: I/O-capable tool-call profile with `io`, `patterns`, `agents`, and `globals`; web is disabled by default.

Key semantics:

- `:base` supports file-based inheritance; paths are resolved relative to the current agent file.
- `:system {:file ...}` and `:provider {:file ...}` paths are resolved relative to the current agent file.
- `:namespaces` values support `stdlib/X`, `stdlib/X/Y`, `file.clj/var`, `file.agent.edn`, `{:file f}`, and `{:file f :items {...}}`.

Rules:

- Keep relative paths valid from the file that references them.
- Keep transport variants aligned unless the transport requires a difference.
- Avoid inheritance cycles.

## Provider Files

Provider files are loaded by `spell.provider/load-provider`.

Each `.provider.edn` file includes a `:default-agent` key pointing to the transport-appropriate base agent. Supported `:type` values include:

- `:anthropic-pf`
- `:anthropic-tc`
- `:openai`
- `:codex-tc`
- `:fireworks`
- `:fireworks-tc`
- `:ollama`
- `:test`

Rules:

- Keep model names and cost keys in sync with provider routing and `data/pricing.edn`.
- Keep explicit `:cache-read-input` values aligned with providers that expose cached prompt-token pricing.
- Keep API key environment variable names accurate.
- `:fireworks` is the completions/prefill transport. Use explicit `fireworks-tc:<model>` or `:fireworks-tc` for Fireworks Anthropic-compatible Messages requests with mandatory `spell_suffix` tool output.
- Use a tool-call provider only where mandatory tool output is intended.
- OpenAI tool-call configs still use `:type :openai`; set `:force-tool-call true` and a tool-call base agent rather than adding a separate provider type.

## Prompt Files

Current variants:

- `sysprompt-prefill.txt`
- `sysprompt-message.txt`
- `sysprompt-toolcall.txt`

The main system prompt is intentionally single-track. Variation should be transport-specific only: prefill, message, or tool-call. Provider-agnostic behavior changes should normally be reflected across all three files.

## Pattern Library

`spl-lib/patterns.spl` contains reusable Spell patterns loaded through namespace wiring.

Rules:

- Keep patterns pure unless a side effect is required by design.
- Document expected return shape in comments when downstream agents depend on it.

## Web Configuration

`web.edn` configures the `web` namespace.

- Search defaults to Serper when `SERPER_API_KEY` is present; otherwise it falls back to DuckDuckGo.
- Fetch defaults to the configured fetch backend and character limit.
- `SERPER_API_KEY` is web/search configuration only. It is not required for examples that do not use the `web` namespace.

## Important Gotchas

- CLI default agent is `config/agents/cli.agent.edn`.
- Agent and provider paths are mostly relative to the file that declares them, not the process working directory.
- Prompt behavior is transport-sensitive; update all prompt variants when changing provider-agnostic model instructions.
