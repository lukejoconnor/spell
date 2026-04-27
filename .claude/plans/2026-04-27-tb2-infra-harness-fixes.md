# TB2 Infra/Harness Fixes

**Date:** 2026-04-27
**Author:** Codex
**User:** Luke Jen O'Connor

## Goal

Fix or verify the Terminal-Bench 2.0 failures that are attributable to our benchmark infrastructure, Codex/TB integration, or GCP operations, while explicitly leaving upstream TB2 task defects out of scope.

The recent TB2 entry identifies six shared deterministic task failures. This plan treats them as upstream unless targeted reproduction shows the failure is caused by our local Docker/GCP configuration.

## Current Evidence

From `notebook/entries/2026-04-26-tb-2-c54-spell-codex-med.md`:

- Shared deterministic failures on both Spell and Codex: `broken-networking`, `cron-broken-network`, `fix-pandas-version`, `build-linux-kernel-qemu`, `feal-linear-cryptanalysis`, `intrusion-detection`.
- Codex-only infra/harness failures: `extract-safely` and `pytorch-model-recovery`.
- Operational issue: one high-effort shard lost per-item JSONL/traces during `finish-all` because the SSH tar stream was truncated.
- Accounting issue: timeout-killed Spell runs can write zero-token usage in wrapper records even after model work occurred.
- Runtime follow-up: `heterogeneous-dates` hit a Spell `StackOverflowError` in `prune-substitute` but still resolved; this is real runtime work, not a TB2 task bug.

Local dirty state at planning time:

- Main repo dirty: `examples/AGENTS.md`, `examples/README.md`, `scripts/gcp-startup.sh`.
- Main repo untracked: `.claude/plans/`, `examples/telephone.spl`, `workspace/`.
- Nested `benchmarking/` repo clean.

Implementation should avoid the example files and must inspect the existing `scripts/gcp-startup.sh` edits before touching startup behavior.

## Scope

### In Scope

1. Fix Codex uv bootstrap install target for tmpfs-constrained TB tasks.
   - `extract-safely` mounts a small tmpfs at `/root`.
   - Current shared uv bootstrap defaults `UV_UNMANAGED_INSTALL` to `$HOME/.local/bin`, which becomes `/root/.local/bin`.
   - Change the default for benchmark-managed agent bootstraps to a non-`$HOME` runtime path such as `/tmp/spell-agent-runtime/uv-bin`, while still respecting an explicit `UV_UNMANAGED_INSTALL`.

2. Reverify the `pytorch-model-recovery` Codex CLI failure before changing code.
   - The entry reports `codex exec -` was rejected.
   - Current `benchmarking/src/docker_agents.py::codex_run_command` passes the prompt as a quoted argument, not `-`.
   - Add/confirm a regression test that `codex_run_command` never emits `codex exec -`, then run a focused single-task dry run or targeted command check against the current Codex CLI path. Patch only if the failure reproduces.

3. Separate Docker-build failures caused by our Docker mode from upstream task bugs.
   - Test `build-linux-kernel-qemu`, `feal-linear-cryptanalysis`, and `intrusion-detection` under the current Docker mode and with `DOCKER_BUILDKIT=1`.
   - If BuildKit fixes `feal-linear-cryptanalysis` or `intrusion-detection`, enable BuildKit for Terminal-Bench runs in our harness/GCP environment.
   - Do not add task-specific Dockerfile shims for broken TB2 tasks.

4. Make GCP artifact pulls robust to large traces.
   - Replace or wrap raw `ssh tar | local tar` extraction with a retryable path.
   - Candidate design: create a remote tarball under `/tmp`, copy it with `gcloud compute scp` or another retryable copy path, verify non-empty local artifact, then extract locally.
   - Ensure `finish-all` does not delete a terminal VM if any required artifact pull fails.

5. Improve timeout usage/accounting reporting if low-risk.
   - For killed Spell benchmark responses, preserve any available trace directory and distinguish "usage unavailable due kill" from true zero tokens.
   - Avoid inventing token counts. This is reporting hygiene, not score correction.

### Out of Scope

- Patching upstream TB2 task definitions for `broken-networking`, `cron-broken-network`, `fix-pandas-version`, or `build-linux-kernel-qemu`.
- Adding compatibility shims that silently modify TB2 tasks locally.
- Treating genuine Spell timeouts, recovery exhaustion, or model wrong answers as infra bugs.
- Fixing the `heterogeneous-dates` `prune-substitute` stack overflow in this PR unless it is tiny and isolated; otherwise file/record it as a follow-up.

## Likely Files

Nested `benchmarking/` repo:

- `src/docker_agents.py`
- `terminal_bench/base.py`
- `terminal_bench/codex_agent.py`
- `src/terminalbench_runner.py`
- `tests/test_docker_agents.py`
- `tests/test_terminalbench_base.py`
- `tests/test_terminalbench_runner.py`

Main `spell` repo:

- `scripts/gcp-benchmark.sh`
- Possibly `scripts/gcp-startup.sh` if BuildKit must be enabled at VM bootstrap; inspect existing dirty edits first.
- Possibly `src/spell/benchmark_api.clj` for killed-response reporting.

## Implementation Plan

1. Create isolated worktrees.
   - Use a clean worktree for the main `spell` repo.
   - Use a separate clean worktree or branch for the nested `benchmarking/` repo if benchmarking changes are needed.
   - Preserve all unrelated local edits in the current checkout.

2. Patch the uv bootstrap.
   - Introduce a benchmark runtime uv bin directory outside `$HOME`.
   - Update `UV_BOOTSTRAP_COMMAND` to default `UV_UNMANAGED_INSTALL` there.
   - Ensure the generated `$HOME/.local/bin/env` still adds the resolved uv directory to `PATH`.
   - Add tests simulating tiny or redirected `$HOME` and confirming uv installs outside `$HOME` by default.

3. Verify Codex CLI prompt invocation.
   - Add or update tests around `codex_run_command`.
   - Run a focused reproduction for `pytorch-model-recovery` if possible without launching a full fleet.
   - If current code is already correct, document that this failure was version skew or prior-ref behavior and avoid unnecessary changes.

4. Test and optionally enable BuildKit for TB2 Docker builds.
   - Use focused TB2 dry runs or direct `tb`/Docker build probes on the three pre-agent Docker-build failures.
   - If `DOCKER_BUILDKIT=1` resolves BuildKit-only syntax, wire that env into Terminal-Bench execution and GCP benchmark shells.
   - Add tests for env propagation where practical.

5. Harden GCP artifact pull.
   - Make each remote directory pull atomic from the local perspective.
   - Retry transient copy/extract failures.
   - Return nonzero on failed required pulls so `finish-all` skips deletion.
   - Add shell syntax validation and, if feasible, a small stubbed shell test for pull failure behavior.

6. Address timeout usage reporting only if it stays small.
   - Prefer metadata flags like `usage_unavailable_reason: killed` over estimated numbers.
   - Add focused tests for killed responses or Terminal-Bench record conversion.

7. Documentation and notebook.
   - Update `benchmarking/AGENTS.md` only if user-facing TB2 run behavior changes, such as BuildKit requirements or pull semantics.
   - Update main `AGENTS.md`/project doc only if GCP workflow behavior changes materially.
   - Create a notebook entry because this is multi-repo benchmark infra work with future-run implications.

8. Rerun affected recent-run items after patching.
   - Re-run only the TB2 items whose observed failures should change because of the patches, not the whole 94-task sweep.
   - Minimum rerun set:
     - `extract-safely` under Codex after the uv install-target fix.
     - `pytorch-model-recovery` under Codex if reproduction confirms the current path still has a Codex CLI invocation bug or if a regression guard is added for that path.
     - `feal-linear-cryptanalysis` and `intrusion-detection` if BuildKit/env propagation is changed.
     - Any task used to validate GCP artifact pull robustness if the pull fix is exercised on a live VM.
   - Compare the rerun records against the recent TB2 entry and explicitly report whether each patched failure mode disappeared, persisted, or was not reproducible on the current refs.

## Validation

Benchmarking repo:

- `uv run pytest tests/test_docker_agents.py`
- `uv run pytest tests/test_terminalbench_base.py tests/test_terminalbench_runner.py`
- Focused `bench.py terminalbench --dataset 2.0` dry runs for affected tasks.
- Focused post-patch reruns of affected recent-run items, with results summarized by task and condition.
- If credentials/Docker are available, run single-task smoke checks for:
  - `extract-safely` with Codex install bootstrap
  - `pytorch-model-recovery` with Codex
  - `feal-linear-cryptanalysis` and `intrusion-detection` with BuildKit enabled

Main repo:

- `bash -n scripts/gcp-benchmark.sh`
- `bash -n scripts/gcp-startup.sh` if touched
- Focused Clojure tests if `src/spell/benchmark_api.clj` changes.

End-to-end:

- Prefer a two- or three-task TB2 pilot before any larger rerun.
- Do not launch a full TB2 sweep without a separate cost/runtime confirmation.

## PR Shape

This likely needs two PRs:

- `spell-benchmarking`: uv bootstrap, Codex invocation verification, BuildKit env propagation, Terminal-Bench harness tests.
- `spell`: GCP artifact pull robustness and optional killed-response metadata.

If only one repo needs actual code changes after verification, open only that PR.

## Permission Note

The current sandbox is `workspace-write` with restricted network access. The `$ship` implementation/review flow can proceed, but worktree setup, dependency sync, Docker/TB smoke tests, GCP checks, and PR creation may require approvals or already-authenticated local tools.
