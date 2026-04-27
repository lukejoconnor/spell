# TODO 260 Plan: Move Default Spell Traces Out of the Workspace

## Goal

Fix TODO #260 by ensuring Spell's default trace output no longer writes `traces/` into the current working directory, which can pollute benchmark task workspaces and break editable installs such as `pip install -e .[test]`.

## Current Context

- Relevant notebook context:
  - `notebook/entries/2026-04-23-prompt-011-eval-writeup.md`
  - `notebook/TODO.md` item `#260`
- Current dirty state in the main repo: only untracked `.claude/plans/`, so there is no overlapping code change to avoid.
- The concrete bug is in `src/spell/benchmark_api.clj`, but the same default-path pattern also exists in `src/spell/api.clj`.

## Approach

Prefer one shared default-path policy over a narrow benchmark-only patch.

Why:
- A benchmark-only fix would leave the same cwd-pollution behavior alive in the general API path.
- The project prefers replacement over accumulating parallel behaviors.
- The bug mechanism is not benchmark-specific; benchmarks only exposed it first.

Planned implementation direction:
1. Introduce a shared helper for default trace directories.
2. Make the default root live outside the task workspace, likely under the system temp directory (for example `${java.io.tmpdir}/spell-traces/<timestamp>`).
3. Update both `spell.api/run` and `spell.benchmark-api/run-spell` to use that shared helper when `:trace true` and no explicit `:trace-dir` is provided.
4. Preserve explicit `:trace-dir` overrides exactly as they work today.

## Likely Files

- `src/spell/api.clj`
- `src/spell/benchmark_api.clj`
- Possibly `src/spell/trace.clj` if the shared helper belongs there
- `test/spell/api_test.clj`
- Any benchmark/API tests covering default trace-dir behavior, if present
- `src/spell/cli.clj` help text, if the user-facing default path description becomes inaccurate

## Validation

Run focused validation first:

- `clojure -M:test --focus spell.api-test`
- Any benchmark-api-focused tests if they already exist
- `git diff --check`

If the implementation touches CLI help text or shared trace helpers, expand validation to the nearest related tests.

## Design Notes / Risks

- The main tradeoff is discoverability versus safety. Relative `traces/` is convenient for local manual use, but it is unsafe in repos that use flat-layout package discovery. I think safety should win for the default path, while explicit `:trace-dir` remains available for local workflows that want a repo-local directory.
- If there is existing tooling that assumes implicit relative trace paths, we should update that tooling rather than preserving the old default alongside the new one.

## Docs / Instructions

- Check whether `src/spell/cli.clj` still accurately describes the default trace location.
- Check whether project docs or notebook references need a brief update after the fix lands.
- `CLAUDE.md` likely does not need an update unless the trace-path policy is documented there.

## Notebook Entry

Yes, a notebook entry is warranted if we implement this:
- the bug is non-obvious,
- it affects benchmark correctness,
- and the fix likely changes a cross-entry-point runtime default.

## Ship Workflow Notes

- This repo is currently in `workspace-write` with restricted network access. That is workable for planning, but implementation/worktree setup/PR creation are more reliable with fuller permissions if we hit sandbox limits.
- Standard `ship` mode means stopping after this plan for approval before delegation.
