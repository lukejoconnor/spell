# Todo 259: Spell TB Install Regression

## Goal

Find and fix the Spell runtime regression that makes Terminal-Bench agent startup fail on a stable cluster of tasks between `v0.1.0` and `1bf5a82`, then ship the smallest replacement design that restores offline-safe installs. The clearest repro is `extract-safely`; `cron-broken-network` is the strongest signal that the failure is caused by a new network dependency during startup.

## Pre-Flight Notes

- Notebook context reviewed: `notebook/entries/2026-04-23-prompt-011-eval-writeup.md`
- Todo source: `notebook/TODO.md` `#259`
- Current repo state: clean `git status --porcelain`
- Constraint: benchmark-side install wrapper appears unchanged since `v0.1.0`; likely regression surface is the bundled Spell runtime copied into `/opt/spell`, not the Terminal-Bench harness glue.

## Likely Change Surface

- `deps.edn`
- `src/spell/benchmark_api.clj`
- `src/spell/provider.clj`
- `src/spell/llm.clj`
- `src/spell/agent.clj`
- `benchmarking/src/docker_agents.py`
- `benchmarking/terminal_bench/spell_agent.py`

Notes:
- `benchmarking/src/docker_agents.py` and `benchmarking/terminal_bench/spell_agent.py` are probably validation/containment points, not the primary source of the regression, because they do not show meaningful diffs versus `v0.1.0`.
- The current install path copies `deps.edn`, `src`, `config`, and `data` into `/opt/spell`, then smoke-checks with `clojure -Sdeps '{:deps {local/spell {:local/root \"/opt/spell\"}}}' -M -e "(require 'spell.benchmark-api)"`.

## Implementation Plan

1. Reproduce the failure on the actual install path.
   - Use a one-task Terminal-Bench repro centered on `extract-safely`, with Spell running through `terminal_bench/spell_agent.py`.
   - If the full TB repro is slow or noisy, run the same `/opt/spell` bundle + `spell_install_script()` sequence directly inside the task container to isolate install from task execution.
   - Capture the first failing command and whether the failure happens during package-manager setup, Clojure CLI install, local bundle require, provider namespace load, or benchmark API startup.

2. Bisect the runtime-side regression surface between `v0.1.0` and `1bf5a82`.
   - Start with `deps.edn` plus the runtime entry path used by the smoke check: `spell.benchmark-api` -> `spell.api` / `spell.agent` / `spell.llm` / `spell.provider`.
   - Compare the repro behavior at `v0.1.0`, midpoint commits, and `HEAD` until the first bad commit is identified.
   - Prefer a narrow manual bisect over broad speculative edits.

3. Replace the offending startup dependency instead of layering around it.
   - If the regression is a new network fetch, vendor or pre-bundle the required artifact in `/opt/spell`, or move the dependency behind a runtime path that does not execute during install smoke-check.
   - If the regression comes from eager provider initialization, make startup lazy enough that install only verifies the benchmark entrypoint and local bundle integrity.
   - Do not keep both old and new startup paths. Remove the regressed path once the replacement is validated.

4. Harden the benchmark-side smoke check only if still needed after the root-cause fix.
   - Keep the smoke check meaningful, but limit it to guarantees we actually need for TB startup.
   - If install failures remain opaque, improve stderr preservation in the Spell agent install path so future regressions are diagnosable from `agent-logs`.

5. Update docs/instructions only if the shipped behavior changes.
   - Likely no `CLAUDE.md` update unless the runtime bundle contract or benchmark install semantics materially change.
   - If the fix adds a durable benchmark-runtime constraint or debugging workflow, update `benchmarking/AGENTS.md`.

6. Record the result in the notebook.
   - This task clears a tracked regression with non-obvious root cause, so a notebook entry is warranted if implementation proceeds.

## Validation

- Re-run the minimal `extract-safely` repro on the fixed branch and confirm install succeeds.
- Re-run at least one additional previously failing task:
  - `cron-broken-network` preferred, because it exercises the offline/network-restricted hypothesis.
  - `chess-best-move` as secondary confirmation.
- Run focused benchmark-side tests covering the install/bundle path:
  - `uv run pytest benchmarking/tests/test_docker_agents.py -q`
  - `uv run pytest benchmarking/tests/test_terminalbench_base.py -q`
  - Add or update a regression test if the root cause is representable in unit/integration form.
- If code changes touch core runtime loading, run the smallest relevant Clojure test slice rather than the full suite unless the change surface demands more.

## Risks / Decisions To Recheck During Implementation

- The empty `agent-logs` symptom may mean the install failure happens before current log capture, so instrumentation might be required before the root cause is visible.
- The original “network fetch” hypothesis is plausible but still unproven; the implementation worker should challenge it quickly if the first repro points elsewhere.
- Because this is the standard `ship` flow, implementation should happen in a dedicated worktree with a fresh-context worker after approval.
