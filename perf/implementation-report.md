# Runtime audit implementation and user handoff

## Final status

**Fresh Spell review: PASS for the bounded benchmark implementation/evidence and report handoff.** See [fresh-review.md](fresh-review.md) for exact scope and limitations. This verdict is not an all-runtime correctness guarantee or Codex PR approval.

Historical facts remain distinct: audit001 was aborted (exit 143, 125 responses, $15.5493); audit002 measured **80/80 passing cases in 14 families, 608.930953 seconds**, then failed outer reporting (exit 1, $22.1132). This separate fresh-context reporting pass completes the handoff without reimplementing or optimizing the audit.

## Files

Existing implementation reviewed, not edited in this pass:

- `deps.edn`: optional `:perf` classpath/main/JVM alias.
- `perf/run.py`, `perf/test_run.py`: bounded launcher, atomic receipts, subprocess checks.
- `perf/spell/perf/harness.clj`, `harness_test.clj`: independent wall/memory passes, case checkpoints, correctness/persistence tests.
- `perf/spell/perf/core.clj`: five evaluator/parser/self-call/trace/provider-I/O families.
- `perf/spell/perf/lifecycle.clj`: four coordinator/spawn/dormant/API families.
- `perf/spell/perf/context.clj`: five context/store/board families.

Report deliverables:

- `perf/README.md`: portable POSIX commands, targeted bounded launcher example, inventory and measurement/provenance limits.
- `perf/findings-and-targets.md`: all 14 families, named baseline comparisons, metric-specific goals/guardrails and validation criteria.
- `perf/dogfood-report.md`: trace-backed board handles/messages/acknowledgements, tracked handoffs, errors and customization distinction.
- `perf/implementation-report.md`: this handoff and next-agent brief.
- `perf/fresh-review.md`: independent scoped verdict and checks.
- `perf/audit-core.md`, `audit-lifecycle.md`, `audit-context.md`: preserved worker-authored exact commands/results. Report-only clarifications reconcile historical next steps and selected-line reachability; they do not confer new worker verification.

Retained evidence: `perf/results/baseline.json`, `.log`, `baseline-receipt.json`, `baseline-provenance.json`, pilot artifacts, `board-evidence.json`, `worker-receipts.edn`, `context-worker-receipt.edn`. Source packets, worker task files, progress and proposed customization file remain historical artifacts, not evidence of an installed audit002 wrapper. No baseline artifact was rewritten.

## Checks and review resolutions

| Check/finding | Evidence and resolution | Ownership |
|---|---|---|
| Worker correctness | Core 11 tiny cases + 20-call/padding/injected cleanup; lifecycle 11 cases; context 8 tiny cases + injected update failure; exact commands/output in worker reports | Historical worker claims in received returns |
| Pilot/full measurements | 14-case pilot exit 0 in 26.036854 s; 80-case baseline exit 0 in 608.930953 s, all cases ok | Historical parent measurements; raw artifacts independently inspected |
| Cleanup and checkpoint tests | 6 Clojure tests / 34 assertions; run-cases interruption, complete/failed states, partial write preservation, exact-once cleanup | Supplied independent supervisor validation; not rerun here |
| Launcher tests | 6 Python subprocess tests; timeout preserving new partial result, stale success invalidation, launch failure, zero-exit incomplete rejection, check-without-result, atomic receipt failure | Supplied independent supervisor validation; not rerun here |
| Independent integrity check | Python read-only computation exited 0: baseline hash matches provenance; 80 ok rows, 14 families, every row 5 timings/3 memory samples; 24 wall CVs >0.2 | This fresh reviewer |
| Source provenance comparison | Comparing current files against baseline source hash map found exactly the four documented changed perf Clojure files; no other recorded source mismatches | This fresh reviewer |
| Verbose cost mechanism | API log-writer enables verbose; llm self-call path sleeps rand-int 500 ms. Clearly identified as proposed pacing/logging separation, not achieved optimization | This fresh review's targeted source grep |
| Selected-line retention | read-lines slurps/splits all lines and returns a SubVector. Label corrected: ten returned lines retain the entire backing vector | Supervisor fix checked against source here |
| Quiet-board send claim | Stub throws instead of sending, but does not count attempts; report does not claim zero attempted notifications | Snapshot review |
| Reporting/board error diagnosis | Missing `{}` to info/lists, unavailable Spell builtins and source-shape errors remain caller errors; observed partial effects/preemption are not dispatch corruption | Supplied exact reproduction and traces, reviewed here |

The checkpoint implementation serializes first, writes a same-directory temporary file, and atomically replaces the target outside timed functions. The launcher pairs a fresh receipt/log with a freshly invalidated result name. Together they protect the last published completed-case evidence against the tested failures, not every in-flight case, power loss or arbitrary concurrent name reuse. Supervisor validation reports that all pre-checkpoint harness functions are text-identical to measured code; fresh review independently verified placement outside wall/memory timing.

## Deviations and boundaries

No full baseline rerun, new workers, delegated reviewer, runtime code edit, speculative optimization, live-board initialization, Git mutation or external write occurred in this report pass. Two targeted exploratory turns were used, followed by report writes. No new independent Clojure or subprocess test execution is claimed; integrity/source checks are the independent additions. The fresh context itself provides the requested independent Spell review, with Codex acceptance still separate.

Audit002's proposed customization wrapper was not installed, and its final baseline board post/dump did not execute successfully. Audit001's installed shared customization and feature tests/demo establish the customization history instead. Historical incidental external error/feedback writes and both outer-run failures are preserved in the dogfood/worker reports.

Only local deterministic costs were measured: not model/network latency, whole-process allocation, native/RSS peaks or a genuine 48-hour soak. Five timings/three memory passes in one JVM are provisional evidence; 24/80 wall CVs exceed 0.2. Current-thread allocation excludes futures; fixtures deliberately retain live objects, then clear references. Stored-value retention after pruning is in-run retrievability, not established across-run leakage.

## Compact NEXT-agent optimization brief

### Exact starting commands

From repository root, with new names and no concurrent benchmark process:

```sh
python3 -B perf/run.py check --timeout 120 --name opt-check-01
python3 -B perf/test_run.py
clojure -J-Dclojure.main.report=stderr -M:perf --list true
python3 -B perf/run.py pilot --timeout 180 --name opt-pilot-01
```

Then use the bounded targeted Python command in README, initially `core-trace-log` index 10 (20-call verbose, padding 0), with a unique output name per fresh JVM. Paired indices 2/6/10/14 select off/trace/verbose/both for the same workload. Increase warmup/repetitions only as a documented variance study with comparable control/candidate settings. Once focused work and semantic checks converge, run:

```sh
python3 -B perf/run.py baseline --timeout 900 --name opt-full-01
```

These are commands for the NEXT run, not commands executed by this report pass. Do not overwrite `baseline`, `pilot` or historical check evidence.

### Priorities and success criteria

1. **Pacing versus file logging:** baseline verbose/both 20-call unpadded medians 4816.8311/5635.7410 ms. Provisional ≥90% wall reduction by separating intentional sleep from logging, preserving complete logs and writer lifecycle. Report wait removal, not fictional CPU savings.
2. **Evaluator and trace allocation:** evaluator iterations=10000/evals=20 is 3346.5684 ms / 1260.881904 MB caller allocation; seek 20% reductions. Trace-only calls=20/padding=1024 is 105.6546 ms / 205.191664 MB; seek 20% reductions without discarding evidence.
3. **Read-lines retained backing:** lines=100000 returns ten lines yet retains 6,134,992 B live; seek ≥90% live reduction with exact metadata/range behavior, retaining original file setup/check/cleanup boundaries.
4. **Coordinator/API/context:** use the named 15–20% provisional goals in findings, after profiling. Re-measure noisy contention/small lifecycle cases before optimizing. Keep receipt capture's already-achieved zero stored entries/eight current bodies and 67,160 B live baseline as a guardrail, not a new feature to implement.

Retain non-deadlock, dynamic scoping, non-tail returns, recovery, explicit receipt and resumable context, FIFO/ordered fanin, exactly-once obligation handling, full stored-value retrievability, trace completeness, finite deadlines and fresh per-run stores. Never set retained-memory goals below full payload floors without separately reviewed compression/release semantics.

Provenance: run `2026-09-07-runtime-audit-002`, base `67b7cf7ba919a9dca9c78a5b9e9e0ed5116d5c1f`; raw baseline SHA-256 `54754377fd62516b5971715bcd334237733b07370e24fc83fa514d98a08837ce`. Hashes/dirty status identify the actual measured code; current checkpoint/label corrections are post-measurement, not a future-commit baseline.

Success requires reproduced same-machine fresh-JVM controls, variance/warmup checks, metric-specific improvements clearly beyond noise, all semantic assertions and all 80 cases passing, honest partial/failure receipts, no new sustained cleanup growth, and a fresh independent review of the candidate changes. A guardrail or 're-measure noise first' outcome is acceptable where a speedup is unjustified. Do not expand this handoff into a hidden optimization project.
