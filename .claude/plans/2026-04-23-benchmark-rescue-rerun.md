# Benchmark Final Rerun Plan

## Goal

Produce final benchmark results that are clean enough to publish as the source of truth.

That means:

- no final headline result should depend on a run known to be contaminated by harness errors
- no final headline result should depend on artifacts that were only partially preserved
- final aggregation should come from one clean rerun campaign on current `main` for both repos
- artifact durability should be enforced explicitly before any VM cleanup

There is no budget cap for this rerun. The priority is correctness, completeness, and clean final tables, not minimizing spend.

## Baseline Refs

Use current `main` for both repos and record the exact SHAs in every run-group:

- Spell `main`: `8e2bda9dd25aea84e1bec9679634e47a03d7dac8`
- `benchmarking` `main`: `15ec551b8c854ee82c76e9c3ced77860f7c3ed80`

Important caveats on those refs:

- `benchmarking` `main` already includes the TODO #259 fix: `SpellAgent` opts out of the shared `uv` bootstrap.
- Spell `main` still defaults traces via `trace/default-trace-dir`, so the rerun must pass an explicit external `:trace-dir` outside the benchmark workspace unless `main` changes again before launch.

## Historical Data Policy

Keep the April 22-23 artifacts on disk for diagnosis, comparison, and writeup context.

Do not use those historical runs as final benchmark inputs for this campaign.

In particular:

- historical GPT Terminal-Bench runs are not final because they may include harness contamination
- historical GPT SWE-bench Lite partials are not final because they are incomplete and mixed across stages
- historical Opus runs are not final because some are partial and some were lost entirely

The final tables for this rerun should aggregate only from the new current-`main` run groups.

## What Went Wrong Last Time

1. Artifact handling failed.
   - The original run lost GPT Stage C SBL data and all Opus SBL artifacts because completed work was not pulled before VM termination.
   - The current launcher now uses `STOP` instead of `DELETE`, but that only creates a recovery window. It does not replace explicit pull verification.

2. Terminal-Bench had real harness contamination.
   - The empty-log `agent_installation_failed` cluster was caused by the shared `uv` bootstrap running for `SpellAgent`.
   - That issue is fixed on `benchmarking` `main`, but any TB result produced before that fix should be treated as non-final.

3. Trace placement was unsafe.
   - The relative trace-dir default can still write inside the task workspace and affect benchmark behavior.
   - For this rerun, trace output must be forced to a path outside the task workspace for every benchmark invocation.

4. Partial-preserve logic created ambiguity.
   - Mixing preserved historical shards with new shards makes it too easy to blur “best available” with “final clean result.”
   - This plan removes that ambiguity by making the new rerun the only final source of truth.

## Plan

### Phase 0: Freeze The Execution Baseline

Before launching any new benchmark shards:

1. Run from Spell `main` at `8e2bda9` and `benchmarking` `main` at `15ec551`, or newer `main` only if intentionally refreshed and re-recorded.
2. Record both SHAs in launcher metadata and in the run log.
3. Standardize one external trace root for all runs so no benchmark writes traces into the task workspace.

### Phase 1: Artifact Safety First

Use the safer manual completion flow for every run group:

1. launch shards
2. `wait` without `--finish`
3. `pull-all --finished-only`
4. verify local JSONL, traces, and logs under `benchmarking/{results,traces,logs}/gcp/`
5. only then `finish-all`

Rules:

- a shard does not count until its local artifacts exist
- chat status messages are never a source of truth
- keep primary and retry run groups separate
- stage labels are for naming and aggregation only, not for launch sequencing

### Phase 2: Local Preflight Checks Only

Do not run another pilot benchmark phase. We already have enough pilot evidence.

Do perform the following local checks before the expensive rerun:

1. confirm the exact benchmark command lines and quoting with launcher dry-runs
2. confirm every benchmark invocation passes an explicit external trace directory
3. confirm `benchmarking` `main` still has the `SpellAgent` `_requires_uv() -> False` behavior
4. run focused tests for the affected paths if they exist and are cheap:
   - Terminal-Bench bootstrap path
   - trace-dir handling

These are preflight checks, not benchmark pilots.

### Phase 3: Final GPT-5.4 Rerun

Use exact model spec `openai-tc:gpt-5.4`.

Rerun the full GPT surface needed for the final tables. Do not preserve old GPT headline rows.

Scope:

1. Terminal-Bench low: full `80`
2. Terminal-Bench med: full `80`
3. Terminal-Bench high: full `80`
4. SWE-bench Lite high: full `300`
5. SWE-bench Lite low: full `300`

Total new GPT work: `840` benchmark items.

Stage mapping for continuity with the earlier campaign:

1. Stage A
   - GPT TB high
   - GPT SBL high `300`
2. Stage B
   - GPT TB low
   - GPT TB med
3. Stage C
   - GPT SBL low `300`

Clarification:

- there is no additional GPT TB work beyond those three full TB reruns
- the old `gptb-tb-lo` run is kept only as historical reference, not as a final input
- these GPT stage labels do not imply serial launch; all GPT run groups can be launched immediately

### Phase 4: Final Opus 4.7 Rerun Through Stage B Only

Use exact model spec `anthropic-tc:claude-opus-4-7`.

Because final rows must be clean and post-fix, do not preserve the old Opus TB med row as final input.

Scope:

1. Terminal-Bench med: full `80`
2. Terminal-Bench low: full `80`
3. Terminal-Bench high: full `80`
4. SWE-bench Lite medium-effort: full `300`

Total new Opus work: `540` benchmark items.

Stage mapping:

1. Stage A
   - Opus TB med
   - Opus SBL medium-effort `300`
2. Stage B
   - Opus TB low
   - Opus TB high

Clarifications:

- Opus SBL should run at explicit `medium` reasoning effort, not provider-default / no-effort
- Opus SBL scope is the full `300`, not a subset
- stop after Stage B; do not run Opus Stage C low/high for this campaign
- these Opus stage labels do not imply serial launch; all Opus run groups can be launched immediately

### Phase 5: Aggregation And Retry Policy

After each completed block:

1. aggregate only from the new current-`main` rerun artifacts on local disk
2. exclude April 22-23 historical artifacts from the final aggregate
3. generate a missing-work list from local JSONL only
4. retry only cleanly missing or invalid shards from the new rerun campaign

Retry triggers include:

- missing local unified JSONL
- missing traces or logs after a claimed completion
- empty-log install failures
- any trace-path regression into the benchmark workspace

### Phase 6: Writeup Rule

When writing the final tables and summary:

- new current-`main` rerun groups are the final benchmark results
- April 22-23 artifacts can be discussed only as historical context or failure analysis
- do not merge old and new rows into one headline matrix

## Recommended Execution Order

1. switch both repos to current `main`
2. record exact SHAs in the run metadata
3. configure one external trace root used everywhere
4. validate launcher commands and focused local tests
5. launch all planned GPT and Opus run groups immediately
6. monitor each run group independently with `wait` without `--finish`
7. as groups complete, `pull-all --finished-only`, verify local artifacts, then `finish-all`
8. aggregate only from verified local rerun artifacts
9. run targeted retries only for genuinely missing or invalid new shards

## Success Criteria

- every final headline row comes from the new current-`main` rerun campaign
- no final headline row depends on April 22-23 historical artifacts
- every completed shard has local JSONL, traces, and logs before cleanup
- no empty-log install failures survive into the final dataset
- no workspace-local trace pollution survives into the final dataset
- GPT-5.4 final matrix is complete: TB low/med/high and SBL low/high
- Opus 4.7 final partial matrix is complete: TB med/low/high and SBL medium-effort
