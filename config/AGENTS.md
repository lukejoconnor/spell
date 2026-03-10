# Config Directory Guide

This directory contains runtime configuration used by Spell execution and benchmark harnesses.

## Scope

- `agents/`: `.agent.edn` agent definitions (base + capability profiles).
- `prompts/`: system prompt text variants.
- `providers/`: declarative provider specs (`.provider.edn`).
- `spl-lib/`: reusable Spell library files (`patterns.spl`).

## How Config Is Loaded

### Agent Files (`config/agents/*.agent.edn`)

Loaded by `src/spell/agent.clj`.

**Base agents** (one per transport mode, no effect namespaces):
- `base-pf.agent.edn` — Anthropic (prefill mode), uses `minimal.txt`
- `base-msg.agent.edn` — message providers (no prefill), uses `minimal-no-prefill.txt`
- `base-tc.agent.edn` — tool-call providers, uses `minimal-no-prefill-toolcall.txt`

**Specialized agents** inherit from a base and add namespaces:
- `cli.agent.edn` — CLI default (base-tc + io, web, patterns, agents, globals)
- `io-pf.agent.edn` — benchmark/runtime I/O profile for prefill providers
- `io-msg.agent.edn` — benchmark/runtime I/O profile for message providers
- `io-tc.agent.edn` — benchmark/runtime I/O profile for mandatory toolcall providers
- `explore.agent.edn` — read-only codebase exploration (io-read namespace only)
- `math-tc.agent.edn`, `math-pf.agent.edn`, `math-msg.agent.edn` — math benchmark agents (no web namespace)

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
- `:ollama`
- `:kimi`
- `:test`

Rules:
- Keep model names and cost keys in sync with current provider routing.
- Keep API key env var names accurate (`:api-key-env`).
- Use toolcall provider only where mandatory tool output is intended.

### Prompt Files (`config/prompts/*.txt`)

Current variants:
- `minimal.txt`
- `minimal-no-prefill.txt`
- `minimal-no-prefill-toolcall.txt`

Rules:
- Provider-agnostic behavior changes should normally be reflected across all three.
- Intentional divergences (for transport/toolcall specifics) should be explicit in file comments or nearby docs.

### Pattern Library (`config/spl-lib/patterns.spl`)

Reusable Spell patterns loaded through namespace wiring (`stdlib/patterns`).

Rules:
- Keep patterns pure unless a side effect is required by design.
- Document expected return shape in comments for downstream agent usage.

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
