# Qwen 3.6 Fireworks Support And 32+32 Benchmark

**Date:** 2026-04-26
**Mode:** `$ship` with AFK continuation

## Goal

Add a Spell model option for the newest Qwen model available through Fireworks, then run the same 32 Terminal-Bench plus 32 SWE-bench Lite items used for prior weak/open model comparisons and report whether it improves over the previous open-model runs.

## Important Availability Finding

The user asked for "qwen-2.6 max"; public sources indicate the likely intended model is **Qwen3.6-Max-Preview**, released April 20, 2026. Fireworks' public model library currently shows **Qwen3.6 Plus** as available serverless with model slug `fireworks/qwen3p6-plus`, pricing `$0.50 / $0.10 / $3.00` per 1M input/cached-input/output tokens, and state `Ready`.

I did **not** find a Fireworks model-library page or search result for Qwen3.6-Max-Preview. Because `CLAUDE.md` requires exact model names, the implementation must not invent a `qwen3p6-max` or `qwen3p6-max-preview` ID. If a Fireworks API model listing confirms a Max endpoint later, use that exact ID and pricing; otherwise support and benchmark `qwen3p6-plus` only, clearly reporting that Max was not available on Fireworks.

References:
- Fireworks Qwen3.6 Plus page: `https://fireworks.ai/models/fireworks/qwen3p6-plus`
- Fireworks model library search result lists Qwen3.6 Plus, not Max.
- CnTechPost Qwen3.6-Max-Preview release report: `https://cntechpost.com/2026/04/20/alibaba-releases-qwen3-6-max-preview-stronger-instruction-following-capabilities/`

## Scope

Likely code/config changes:
- `data/pricing.edn`: add exact Fireworks pricing for the available Qwen model.
- Provider/model resolution: inspect `src/spell/provider.clj`, `src/spell/agent.clj`, and config agent files; add explicit handling only if the current `fireworks:<slug>` path does not already resolve to `accounts/fireworks/models/<slug>`.
- Benchmarking repo tests/config only if needed for model preservation or price lookup; `benchmarking/` is a nested git repo and should be treated separately.
- Notebook entry is warranted because this is a benchmark dispatch/result analysis and because there is a non-obvious model-availability correction.
- `CLAUDE.md` probably does not need a durable update unless the run reveals a reusable Qwen/Fireworks gotcha.

## Validation

Implementation validation:
- Run focused model/pricing tests if existing.
- At minimum run a Clojure pricing/provider smoke test or a focused test namespace covering pricing lookup.
- In `benchmarking/`, run focused tests for Fireworks model pass-through if touched.

Benchmark validation:
- Dry-run Terminal-Bench and SWE-bench commands before dispatch.
- Pilot one cheap Terminal-Bench item before scaling if this is the first live call for the model.
- If projected cost for the full 64 items exceeds about `$50`, pause for user confirmation despite AFK.
- Use GCP launcher for Docker-heavy runs, preserving run-group labels and pulling artifacts through `wait --finish`.

## Benchmark Spec

Use the prior weak-model/open-model setup unless direct code inspection contradicts it:
- Conditions: `spell`
- Model: `fireworks:qwen3p6-plus` unless exact Fireworks Max availability is confirmed
- Agent family: Fireworks prefill (`base-pf`) via existing provider classification
- No custom `:coding` trailing prompt, matching Kimi K2.5 / GLM-5.1 weak-model baseline
- Same 32 Terminal-Bench and 32 SWE-bench Lite subsets from `2026-04-20-weak-model-eval-kimi-glm`
- Compare against Kimi K2.5, GLM-5.1, Opus 4.7, and GPT-5.4 where same-subset numbers exist

## Ship Workflow

1. Create implementation branch/worktree.
2. Verify exact available Fireworks model path from local config and, if possible, a model-list command/API.
3. Add the available Qwen model option and pricing without compatibility aliases.
4. Run focused validation.
5. Open a PR with the availability caveat in the description.
6. Run two fresh-context reviews.
7. Patch review findings in the PR branch.
8. Dispatch benchmark pilot/full run only for an exact confirmed Fireworks model ID; do not benchmark a guessed Max endpoint.
9. Report accuracy, fatal Spell errors, cost, latency, run IDs, result files, and whether it improves.
