# Config Directory Guide

This directory contains runtime configuration used by Spell execution.

## Scope

- `agent-profiles/`: `.agent.edn` agent profile definitions.
- `model-profiles/`: declarative model profile specs (`.edn`).
- `mcp-servers/`: reusable stateless MCP connection profiles (`.mcp.edn`).
- `prompts/`: system prompt text variants.
- `spl-lib/`: reusable Spell library files.
- `web.edn`: optional defaults for web search and fetch behavior.

## Model Profiles

First-class public provider paths are:

- OpenAI tool-call mode, configured by `model-profiles/openai-tc.edn` and `OPENAI_API_KEY`.
- Anthropic prefill/tool-call modes, configured by `model-profiles/anthropic-pf.edn` or `model-profiles/anthropic-tc.edn` and `ANTHROPIC_API_KEY`.
- Fireworks prefill/tool-call modes, configured by `model-profiles/fireworks.edn` or `model-profiles/fireworks-tc.edn` and `FIREWORKS_API_KEY`.
- Codex CLI, configured by `model-profiles/codex-tc.edn` and local Codex authentication. Treat this path as experimental.
- Ollama, configured by `model-profiles/ollama.edn` and a local Ollama server.

The `:test` provider is used by the test suite. Other provider files may exist for local development, but they are not the primary public path for this release.

## Agent Profiles

Agent profile files are loaded by `src/spell/agent.clj`.

Base agents define transport-level behavior and do not add effect namespaces:

- `base-pf.agent.edn`: prefill providers, using `prompts/sysprompt-prefill.txt`.
- `base-msg.agent.edn`: message providers, using `prompts/sysprompt-message.txt`.
- `base-tc.agent.edn`: tool-call providers, using `prompts/sysprompt-toolcall.txt`.

Public agent profiles inherit from a base agent and add namespaces:

- `cli.agent.edn`: CLI default; enables `io`, `web`, `patterns`, `agents`, and `globals`.
- `io-pf.agent.edn`: I/O-capable prefill profile with `io`, `patterns`, `agents`, and `globals`; web is disabled by default.
- `io-msg.agent.edn`: I/O-capable message profile with `io`, `patterns`, `agents`, and `globals`; web is disabled by default.
- `io-tc.agent.edn`: I/O-capable tool-call profile with `io`, `patterns`, `agents`, and `globals`; web is disabled by default.

Key semantics:

- `:base` supports file-based inheritance; paths are resolved relative to the current agent file.
- `:system-prompt {:file ...}` and `:default-model-profile` paths are resolved relative to the current agent file.
- `:namespaces` values support `stdlib/X`, `stdlib/X/Y`, `file.clj/var`, `file.agent.edn`, `{:file f}`, and `{:file f :items {...}}`.
- `:mcp-servers` maps server aliases to inline connection profiles or `{:server "../mcp-servers/name.mcp.edn" ...}` plus explicit capability permissions.

MCP profiles store environment-variable references rather than secret values. Spell supports exactly MCP `2026-07-28`; do not add legacy lifecycle or session settings. See `docs/api.md#mcp-server-profiles` for the profile shape and permissions.

Rules:

- Keep relative paths valid from the file that references them.
- Keep transport variants aligned unless the transport requires a difference.
- Avoid inheritance cycles.

## Model Profile Files

Model profile files are loaded by `spell.provider/resolve-model-profile`.

Each model profile may include a `:default-agent-profile` key pointing to the transport-appropriate base agent profile. `spell.api/run` still requires an explicit `:agent-profile`; the default is for higher-level helpers and CLI-style selection. Supported public `:provider` values include:

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
- OpenAI tool-call configs still use `:provider :openai`; set `:force-tool-call true` and a tool-call base agent rather than adding a separate provider type.

## Prompt Files

Current variants:

- `sysprompt-prefill.txt`
- `sysprompt-message.txt`
- `sysprompt-toolcall.txt`

The main system prompt is intentionally single-track. Variation should be transport-specific only: prefill, message, or tool-call. Provider-agnostic behavior changes should normally be reflected across all three files.

## Installable Pattern Modules

`spl-lib/modules/*.spl` contains two bundled editable Spell module definitions (relay and mailing-list), plus project/HOME `.spell/modules` discovery. Namespace wiring exposes only `patterns/install`, `catalog`, `source`, `update`, and `call`; it does not install every bundle or initialize application state at startup. Install a bundle explicitly before calling its functions. Repeated installation preserves existing edits and state.

Definitions live in run-local globals `:modules`, separate from application state. For `:mailing-list`, every participant may install/reuse the module, but exactly one administrator explicitly calls `:init` to create board state. Existing workers never initialize it again.

- Keep orchestration policy in editable Spell source, not host rules. Document function behavior and return shapes in each entry's `:doc`; `catalog` derives parameters from its executable `:source` and omits bodies.
- Pass definitions and entry source as quoted data. Pass `patterns/update` an evaluated function/builtin that returns the next whole definition. Retryable transforms must be pure; purity is a caller obligation, not an enforced effect sandbox.
- List namespace dependencies in `:requires`; these precheck availability without granting capabilities. Enable required namespaces on each caller's profile.
- Preserve supporting evidence and actual effect receipts before pruning; retrieve retained output instead of rediscovering it by repeating effects.

See [the public migration guide](../docs/installable-modules.md) for the schema, five-verb signatures, and final bundle disposition.
## Web Configuration

`web.edn` configures the `web` namespace.

- Search defaults to Serper when `SERPER_API_KEY` is present; otherwise it falls back to DuckDuckGo.
- Fetch defaults to the configured fetch backend and character limit.
- `SERPER_API_KEY` is web/search configuration only. It is not required for examples that do not use the `web` namespace.

## Important Gotchas

- CLI default agent profile is `config/agent-profiles/cli.agent.edn`.
- Agent and provider paths are mostly relative to the file that declares them, not the process working directory.
- Prompt behavior is transport-sensitive; update all prompt variants when changing provider-agnostic model instructions.
