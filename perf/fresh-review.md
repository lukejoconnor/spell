# Fresh-context independent Spell review

## Verdict: PASS

**PASS for the implemented bounded benchmark suite, preserved evidence and report handoff within the scope below.** No new blocking correctness defect was established by this review. The existing checkpoint/receipt and explanatory corrections address the supplied actionable findings without changing measured workloads. No runtime optimization or speculative fix is requested or implemented here.

This is a fresh Spell review, not Codex PR acceptance, a full runtime audit rerun, or a claim that the failed outer audit002 reporting run succeeded. The baseline subprocess passed 80/80; the outer Spell process subsequently exited 1. Codex independently reviews the resulting PR/reports.

## Exact reviewed material

Complete supplied current source snapshots:

1. `deps.edn` including the optional perf alias.
2. `perf/run.py`.
3. `perf/test_run.py`.
4. `perf/spell/perf/harness.clj`.
5. `perf/spell/perf/harness_test.clj`.
6. `perf/spell/perf/core.clj`.
7. `perf/spell/perf/lifecycle.clj`.
8. `perf/spell/perf/context.clj`.

Supplied evidence reviewed: the full 80-row median/CV/allocation/live/cleanup summary; measured environment and provenance; corrected arity reproduction; audit001 loop analysis; audit002 board catalog, failure chain, acknowledgement bounds and saved worker returns; all three worker reports and original acceptance requirements. Supervisor's six Clojure tests/34 assertions and six Python tests are attributed independent validation, **not tests rerun by this reviewer**. No new cumulative Git diff was generated; review used the complete source/evidence snapshots and the targeted checks below.

## Independent targeted checks actually performed

Two exploratory turns, no `!peek`, board initialization, workers or benchmark rerun:

**1. Artifact inventory and source anchors.** `io/ls` confirmed `perf/` and `perf/results/` artifact names. Targeted `io/grep` in `src/spell/llm.clj` located verbose self-call sleep at 348–351 and leaf sleep at 639–642. `io/grep` in `src/spell/io.clj` located `read-lines` at 111–135 and its whole-file split/SubVector return. Both grep commands exited 0.

**2. Read-only integrity computation and logging wiring.** A `python3 -B -c` command loaded baseline/provenance/receipts, SHA-256 hashed preserved artifacts, counted cases/sample lengths/noisy CVs, and compared current files against the baseline's recorded source hash map. It exited 0 with:

- baseline JSON hash matches provenance;
- exactly **80 results, all status ok, 14 families**;
- all cases have **five timing samples and three memory samples**;
- **24/80 wall CVs >0.2**;
- baseline receipt exit 0, not timed out, elapsed **608.9309530258179 seconds**;
- pilot receipt exit 0, elapsed **26.036854028701782 seconds**;
- current-vs-measured source mismatches were exactly `perf/spell/perf/context.clj`, `core.clj`, `harness.clj`, `harness_test.clj`, the four documented post-measurement Clojure files; no other recorded source mismatches appeared.

The same turn's `io/grep` in `src/spell/api.clj` exited 0 and confirmed `effective-verbose = (some? log-writer)` at 72, dynamic verbose/writer bindings at 103–104, and writer flushing at 133–134. This directly supports pacing attribution, without pretending to have profiled file I/O.

Observed immutable artifact hashes:

| Artifact under perf/results | SHA-256 |
|---|---|
| baseline.json | `54754377fd62516b5971715bcd334237733b07370e24fc83fa514d98a08837ce` |
| baseline.log | `f5607b8657a009a68d223eb6fb2f5c1e11a296aa9d7abcfd34556b8784738311` |
| baseline-receipt.json | `eb40d46f48ab8bb66d58ac052fadab330505fb5383fbe498ee408e5deb6e63e4` |

Original artifact immutability across the supervisor fixes is supplied supervisor validation. This reviewer independently confirmed the current JSON/provenance match and recorded all three hashes, then performed report-only writes; no retrospective schema/source-hash rewriting was done. The base is `67b7cf7ba919a9dca9c78a5b9e9e0ed5116d5c1f`, not an assertion that all benchmark files were pristine committed files at that base.

## Findings and resolutions checked

- **Persistence:** `run-cases!` publishes an initial incomplete state and each finished case; `write-checkpoint!` serializes before disk and uses same-directory atomic replacement with temporary-file cleanup. These calls are outside `wall-sample`/`memory-sample`. The last published checkpoint survives the tested interruption/write-failure paths; in-flight samples are not promised. All pre-checkpoint harness functions are reported text-identical to measured code by the supervisor.
- **Fresh launcher receipts:** prior result deletion, initial running receipt and truncated log precede launch. Timeout/interrupt stops the process group; terminal status reports the fresh result state. A zero exit with missing/incomplete/failed checkpoint is rejected. This resolves stale success pairing for serial named runs. Concurrent same-name launches, power-loss durability and an abruptly killed launcher are not claimed covered; inspect a surviving running receipt rather than treating it as success.
- **Memory interpretation:** the root holds fixtures during the live pass and cleanup clears reference containers. The read-lines view retains the whole line vector; quiet-board send stubbing prevents delivery but does not count attempts. The reports no longer infer ten-line-sized retention or zero notification attempts from these fixtures.
- **Semantics:** evaluator/self-call workloads retain non-tail results; lifecycle checks cover ordered fanin, edge disposal, retirement/generation and adjacent store identity. Dormant close is not a join. Context pruning intentionally retains retrievable stored values. Snapshot capture is an optional anti-pattern contrasted with already-working compact receipts, not mandatory board behavior or a new leak fix.
- **Historical error diagnosis:** missing second maps to mail info/lists are caller arity errors with an unhelpful diagnostic, not dispatcher collisions. Partial effect commit and receiving-call preemption require execution receipts. Reporting source-shape/unavailable-builtin failures remain recorded, not hidden by this handoff.
- **Targets:** every family has named measured comparisons, source pointers, a metric-specific provisional improvement or justified guardrail/noise gate, and correctness criteria. Random verbose sleep is distinguished from logging CPU/I/O. Full-payload floors, future-thread allocation omissions and variance constrain claims.

## Remaining limitations / acceptance boundary

This reviewer did not rerun Clojure tests, Python subprocess tests, feature customization tests/demo, worker correctness probes, the pilot or the ~10-minute baseline. It did not independently reread every frozen trace node or recompute billing; detailed board/count claims come from the supplied recovered evidence. This is explicit attribution, not a claim of additional experiments.

Tests cover specific cleanup/checkpoint failures, not all filesystem faults, unsupported atomic-move filesystems, uninterruptible worker teardown or whole-runtime races. Single-JVM ordering, only two warmups/five timings/three memory passes, explicit-GC best effort, point RSS, current-thread-only allocation and small evidence roots limit causal/memory inference. Network/model latency, native/RSS peaks and a genuine 48-hour soak are absent. Same-machine fresh-JVM controls, warmup/variance studies and semantic regression checks remain mandatory for NEXT-run optimization claims.

The worker reports' original commands and authorship are preserved; historical pending parent steps are superseded by the measured artifacts and this review, not retroactively attributed to workers. Audit002's wrapper file was not installed and its proposed final board dump/post has no successful receipt. The historical outer failure remains a deviation even though this separate handoff is complete.

No extra reviewer was delegated. No implementation code, baseline artifacts, Git state or external files were modified in this report-only pass. The five handoff reports and narrow worker-report clarifications are the only intended writes.
