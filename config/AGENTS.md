# Config Directory Guide

This directory contains runtime configuration used by Spell execution and benchmark harnesses.

## Scope

- `agents/`: `.agent.edn` agent definitions (base + capability profiles).
- `prompts/`: system prompt text variants.
- `providers/`: declarative provider specs (`.provider.edn`).
- `spl-lib/`: reusable Spell library files (`patterns.spl`, `react.spl`).

## Providers

Primary providers: Anthropic tool-call (`anthropic-tc`) and Codex tool-call (`codex-tc`). Test provider for unit tests. See `config/providers/` for all `.provider.edn` files.

## How Config Is Loaded

### Agent Files (`config/agents/*.agent.edn`)

Loaded by `src/spell/agent.clj`.

**Base agents** (one per transport mode, no effect namespaces):
- `base-pf.agent.edn` — prefill providers, uses `sysprompt-prefill.txt`
- `base-msg.agent.edn` — message providers (no prefill), uses `sysprompt-message.txt`
- `base-tc.agent.edn` — tool-call providers, uses `sysprompt-toolcall.txt`
- `base-glm.agent.edn` — GLM-5 / Fireworks prefill profile, uses `sysprompt-prefill.txt`

**Specialized agents** inherit from a base and add namespaces:
- `cli.agent.edn` — CLI default (base-tc + io, web, patterns, agents, globals)
- `cli-glm.agent.edn` — GLM-5 CLI (base-glm + io, web, patterns, agents, globals)
- `io-pf.agent.edn` — benchmark/runtime prefill profile with io, patterns, agents, globals (web disabled by default)
- `io-msg.agent.edn` — benchmark/runtime message profile with io, patterns, agents, globals (web disabled by default)
- `io-tc.agent.edn` — benchmark/runtime toolcall profile with io, patterns, agents, globals (web disabled by default)
- `explore.agent.edn` — read-only codebase exploration (io-read namespace only)
- `react.agent.edn` — hidden ReAct loop profile (`react` + `io-exec`)
- `math-tc.agent.edn`, `math-pf.agent.edn`, `math-msg.agent.edn`, `math-compute-tc.agent.edn` — math benchmark agents (no web namespace; `math-compute-tc` uses a computation-first tool-call prompt)

Key semantics:
- `:base` supports file-based inheritance; paths resolved relative to the current agent file.
- `:system {:file ...}` and `:provider {:file ...}` paths are resolved relative to the current agent file.
- `:namespaces` values support:
  - `stdlib/X` and `stdlib/X/Y`
  - `file.clj/var`
  - `file.agent.edn` (sub-agent)
  - `{:file f}` and `{:file f :items {...}}`.

Rules:
- Keep all relative paths valid from the file that references them.
- Keep message/toolcall pairs aligned (same base behavior, different base agent).
- Avoid inheritance cycles.

### Provider Files (`config/providers/*.provider.edn`)

Loaded by `spell.provider/load-provider`.

Each provider .edn file includes a `:default-agent` key pointing to the transport-appropriate base agent. This is used by `spell.provider/provider-edn-default-agent` for API-level default resolution.

Supported `:type` values:
- `:anthropic-pf`
- `:anthropic-tc`
- `:openai`
- `:codex-msg`
- `:codex-tc`
- `:fireworks`
- `:ollama`
- `:kimi`
- `:test`

Rules:
- Keep model names and cost keys in sync with current provider routing.
- Keep explicit `:cache-read-input` values aligned with providers that expose cached prompt token pricing.
- Keep API key env var names accurate (`:api-key-env`).
- Use toolcall provider only where mandatory tool output is intended.
- OpenAI toolcall configs still use `:type :openai`; set `:force-tool-call true` and a tc base agent instead of inventing a separate provider type.

### Prompt Files (`config/prompts/*.txt`)

Current variants:
- `sysprompt-prefill.txt`
- `sysprompt-message.txt`
- `sysprompt-toolcall.txt`
- `math-compute-toolcall.txt`

Rules:
- The main system prompt is single-track. Variation should be transport-specific only (prefill, message, tool-call).
- Provider-agnostic behavior changes should normally be reflected across the three transport files.
- Intentional divergences should be transport-motivated and explicit in file comments or nearby docs.

### Pattern Library (`config/spl-lib/patterns.spl`)

Reusable Spell patterns loaded through namespace wiring (`stdlib/patterns`).

Rules:
- Keep patterns pure unless a side effect is required by design.
- Document expected return shape in comments for downstream agent usage.

### React Library (`config/spl-lib/react.spl`)

Hidden ReAct loop loaded through namespace wiring (`stdlib/react`).

Rules:
- Keep the inner model prompt plain-text only; do not expose Spell syntax inside leaf-llm prompts, and preserve the genuine plain-text leaf transport contract.
- Preserve the public entrypoint as `react/run` from an `:init` trailing expression.

## Benchmarking Config Coupling

Benchmark default agent selection is centralized in:
- `benchmarking/src/agent_policy.py` (`DEFAULT_AGENT_POLICY` + provider/transport resolution).

The policy selects from generic I/O profiles in `config/agents/` based on model/provider transport.

When changing benchmark agent config:
1. Update `config/agents/io-*.agent.edn` files if profile capabilities change.
2. Update `benchmarking/src/agent_policy.py` defaults if paths or policy change.
3. Keep transport variants aligned unless intentionally different.
4. Verify both `bench.py` dry-run output and actual run resolution.

## Important Gotchas

- CLI default agent is `config/agents/cli.agent.edn` (inherits from `base-tc.agent.edn`).
- `spell.api/run` requires an explicit `:agent` argument — there is no built-in fallback.
- `benchmarking/` is a separate nested git repo (`benchmarking/.git`), so config changes affecting benchmark defaults may require coordinated commits in both repos.
