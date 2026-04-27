# Terminal-Bench 2.0 Dataset Support

## Goal

Add first-class support for running Terminal-Bench 2.0/current public task set while preserving the existing `terminal-bench-core==0.1.1` old-core runs used by recent local comparisons.

The current runner already accepts a raw `--dataset` string and forwards it to `tb run`, but the benchmark code and docs still treat `terminal-bench-core==0.1.1` as the only named/default surface. The Terminal-Bench CLI registry currently exposes:

- `terminal-bench-core==0.1.1`: old/core 80-task set used by our recent runs.
- `terminal-bench-core`: latest/head registry task set, the closest available `tb` registry path for current Terminal-Bench 2/public task set under the installed `terminal-bench` 0.2.18 CLI.

## Scope

Change the nested `benchmarking/` repo only unless implementation discovers a main-repo integration point.

Likely files:

- `benchmarking/src/terminalbench_runner.py`
  - Add named dataset presets/aliases, e.g. `old-core`, `core-0.1.1`, `1.1`, `2.0`, `head`.
  - Keep raw `tb` dataset strings working.
  - Preserve the old-core default unless there is a strong reason to change it.
- `benchmarking/bench.py`
  - Normalize the CLI `--dataset` value before building commands/importing records.
  - Print/report both requested and resolved dataset when they differ.
  - Ensure dry-run output makes the resolved dataset obvious.
- `benchmarking/tests/test_terminalbench_runner.py` and/or CLI tests
  - Cover alias normalization, raw passthrough, old-core default, and command construction for 2.0/head.
- `benchmarking/AGENTS.md`
  - Document how to run old-core vs current public/2.0 Terminal-Bench task sets.

## Validation

Fast validation:

- `cd benchmarking && uv run python -m pytest tests/test_terminalbench_runner.py -q`
- `cd benchmarking && uv run python bench.py terminalbench --condition spell --dataset old-core -t hello-world --dry-run`
- `cd benchmarking && uv run python bench.py terminalbench --condition spell --dataset 2.0 --n 1 --dry-run`
- `cd benchmarking && uv run python bench.py terminalbench --condition codex --dataset 2.0 --n 1 --dry-run`

Pilot validation requested by user:

- Run a few items through both `spell` and `codex`.
- Run both old-core and 2.0/head task sets.
- Keep this small because it uses paid model calls and Docker builds. Use explicit low-cost/common tasks for old-core if possible; use `--n 1` or a small explicit 2.0 task once discovered from the registry/cache.

If exact overlapping task IDs between old-core and 2.0 are not easy to discover, it is acceptable to run separate small items per dataset, since this task is harness-support validation rather than score comparison.

## Documentation And Notebook

- Update `benchmarking/AGENTS.md`; no main `CLAUDE.md` change expected unless the public/default Terminal-Bench policy changes.
- Create or update a notebook entry because this changes benchmark methodology and records paid pilot run IDs/results.

## Risks / Notes

- Public leaderboard labels this as Terminal-Bench 2.0/Harbor, while the installed `tb` registry exposes the current set as `terminal-bench-core`/`head`, not a pinned `==2.0` row. The implementation should name this clearly so future comparisons do not confuse `terminal-bench-core` head with old `terminal-bench-core==0.1.1`.
- If `tb run --dataset terminal-bench-core` fails during real execution because the registry head changed or a task is incompatible, document the exact error and keep old-core support intact.
