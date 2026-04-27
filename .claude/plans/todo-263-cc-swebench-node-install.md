# TODO #263: Fix CC SWE-bench Node/npm Install

**Date:** 2026-04-25
**Mode:** `$ship` autonomous (`afk`)

## Goal

Fix the Claude Code SWE-bench install failure where official Python instance images report `npm: command not found` during `cc_install_script`, even though the preceding `install_nvm_node(container)` call returned success. The immediate requirement is to make the benchmark harness fail closed unless a follow-up shell can actually resolve `npm`, then rerun the affected CC/Opus-4.7/medium 32-item SWE-bench Lite subset from run-group `sbl-op47-med-0425b`.

## Context

- TODO: `notebook/TODO.md` #263.
- Run entry: `notebook/entries/2026-04-25-sbl-op47-med-spell-vs-cc.md`.
- Nested repo: `benchmarking/`.
- Failure site: `benchmarking/src/docker_agents.py`.
- Current dirty state:
  - main repo has untracked `.claude/plans/` and `workspace/`.
  - `benchmarking/` had no dirty files at planning time.

The suspected false positive is in `install_nvm_node_exec`: it returns `True` when `NVM_NODE_INSTALL` exits zero, but the later install shell invokes `_claude_runtime_shell_setup()` separately and may not have `npm` on `PATH`. The package-manager fallback can also exit zero without proving both `node` and `npm` are available.

## Scope

Likely files to change:

- `benchmarking/src/docker_agents.py`
  - Add an explicit post-install verification helper around `command -v node` and `command -v npm`.
  - Make `install_nvm_node_exec` return `False` unless verification succeeds in a fresh shell using the same runtime environment.
  - Prefer source-compatible verification through existing runtime setup, not a broad redesign of the Claude/Codex runtime layout.
  - Preserve retry behavior for transient apt/curl failures.
- `benchmarking/tests/test_docker_agents.py`
  - Add regression coverage for a successful NVM script followed by missing `npm`.
  - Add coverage that fallback success is also verified.
  - Update existing call-count assertions to account for verification calls.
- Notebook entry
  - Warranted because this is a multi-step harness fix plus benchmark relaunch context for TODO #263.

Docs/config:

- No top-level `CLAUDE.md` update expected unless implementation discovers a new durable harness convention worth documenting.
- `benchmarking/AGENTS.md` likely does not need a change; this is an internal adapter contract fix.

## Validation

Focused local validation:

```bash
cd benchmarking && uv run python -m pytest tests/test_docker_agents.py -k "nvm_node or cc_install_script or cc_run_command"
```

Broader local validation if focused tests pass:

```bash
cd benchmarking && uv run python -m pytest tests/test_docker_agents.py
```

Benchmark validation:

1. Run a small CC SWE-bench Lite pilot on GCP or locally if feasible, using the same official instance-image path and Claude Code model path.
2. If the install path is healthy, relaunch CC/Opus-4.7/medium on the same 32 items (`--seed 2026 --n 32`) to complete the comparison from `sbl-op47-med-0425b`.
3. Report results from actual JSONL artifacts only; do not infer CC accuracy from self-report.

## Implementation Notes

The cleanest approach is a small contract change:

- `install_nvm_node_exec` should install, then run a fresh verification command with the same env.
- The verification command should source NVM/setup through the same shell snippets used by the downstream install/run commands, then require both `node` and `npm`.
- If NVM install exits zero but verification fails, try the package-manager fallback.
- If fallback exits zero but verification still fails, return `False` so `cc_install_script` is not attempted and the log points at Node/npm installation.

Avoid adding compatibility shims or multiple runtime modes. Replace the optimistic success condition with a verified one.

## Risks

- `npm` may exist only after `_nvm_source_with_tmp_npm_prefix()` but not after `_claude_runtime_shell_setup()` due to `NPM_CONFIG_PREFIX` interactions. The test should make this visible.
- Official SWE-bench images may lack `curl`, `apt` metadata, or package candidates. Existing retry/fallback behavior should stay intact, but verification must catch silent no-ops.
- Full GCP rerun can consume meaningful time and budget; because the user marked AFK and the rerun is explicitly in TODO #263, proceed after a pilot unless the pilot exposes a new systematic failure.

## PR / Review Plan

1. Delegate implementation to a fresh worktree in the nested `benchmarking` repo.
2. Open a PR from the implementation branch.
3. Run two fresh-context reviews:
   - correctness / install-contract review
   - regression and test-coverage review
4. Patch straightforward findings in the PR branch.
5. Do not merge automatically.
