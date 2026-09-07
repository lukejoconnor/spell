# Runtime optimization report — 2026-09-07

Run: `2026-09-07-runtime-optimization-001`  
Branch: `codex/dogfood-reliability`  
Exact control: `4abaa1d696413503ee4dd9a296e1e3df953ceb5f` in a separate detached checkout. The implementation worktree was never switched or reset. Implementation was delegated; the lead reviewed and committed explicit files. No push, merge, PR, repeated live Fable demo, or paid subprocess agent.

## Accepted changes and reversible commit index

| Commit | Mechanism | Measured benefit | Cost / rollback reason |
| --- | --- | --- | --- |
| `3e2cc1b` | Copy selected lines out of a backing SubVector | Held-minus-released heap 29,884,320 → 15,136 B; backing 200,000 → 100 lines | +976 B caller allocation, O(selection) copy. Revert if copying outweighs retention in a different workload. |
| `0405fb6` | Stream the complete cleaned trace map with readable printing | Median export 9,463.012 → 168.512 ms; ~21.203 GB → 130.145 MB caller allocation | Compact whitespace; printer failure may leave partial/truncated diagnostic output. Revert for old presentation/failure behavior, at substantial buffering cost. |
| `89a6347` | Materialize sanitizer output only at the first rewrite | Unchanged read-first caller allocation −38.2–38.5% | Rewritten read-first +3.6–3.8%; some sanitizer timings regress. Revert independently for rewrite-heavy workloads. |
| `9209365` | Remove exactly two verbose-only presentation sleeps | Four fixed 250 ms waits disappear in each offline pair; observed elapsed reduction 1,016.34–1,022.81 ms | Logs may interleave more tightly. WAIT reduction, not CPU acceleration; retries/backoff untouched. |

All four preserve completion-as-program, source-as-context, dynamic scope, fresh self-call locals, replay safety, per-run isolation, atomic receipt/non-deadlock behavior, and intentionally retrievable context. No compatibility layer, hidden orchestration policy, read suppression, deduplication, or eviction was added. Trace formatting is not a foundational invariant. Parser normalization behavior was deliberately held fixed.

## Controls, measurement scope and noise

All claimed changes have at least three comparable fresh-process control/candidate runs against exact 4abaa1d, with one measurement JVM at a time. Generated fixtures avoid private traces. Inputs, heap and assertions match within each experiment; exact commands, source/probe hashes, raw samples and process receipts are preserved. Clean timings are distinct from GC-based retained-data observations. These are local decision probes, not a comprehensive profiler or paid-workload frequency study.

### Selected-line retention

`perf/selected_lines_probe.clj`; evidence `results/optimization-selected-lines-q_aloiij/`.

| Metric | Control runs 1 / 2 / 3 | Candidate runs 1 / 2 / 3 |
| --- | --- | --- |
| Held-minus-released heap, B | 29,884,320 / 29,884,320 / 29,884,320 | 15,136 / 15,136 / 15,136 |
| Backing line count | 200,000 / 200,000 / 200,000 | 100 / 100 / 100 |
| Caller allocation, B | 121,682,264 in each run | 121,683,240 in each run |

256 MiB fixed heap; generated 200,000-line file, 100 selected lines, three warmups. The probe roots only the selection and measures held/released heap after repeated GC, while directly identifying backing-vector structure. Content, metadata and cleanup are asserted. GC is outside the read timing. Whole-file slurp/split transient allocation remains. The small candidate heap delta is noise-sensitive; the structural severing of the parent is the stronger retention evidence. No speedup or peak-heap claim.

### Trace export

Tools: `perf/trace_export_probe.clj`, `perf/trace_export_compare.py`; detailed scope: [trace-export-probe.md](trace-export-probe.md); compact data: [trace-export-results-002.json](trace-export-results-002.json). Raw successful receipts: `results/trace-export-matched-002/`.

| Export wall, ms | Process 1 | Process 2 | Process 3 | Median |
| --- | ---: | ---: | ---: | ---: |
| Control | 9,308.295 | 9,497.912 | 9,463.012 | 9,463.012 |
| Candidate | 122.116 | 179.603 | 168.512 | 168.512 |

144 generated nodes × 48 forms, repeated/nested source, fixed 512 MiB heap, one warmup and one measured export in each fresh process. Median wall ratio is 56.16×; candidate range is visibly noisy. Caller export allocation is 21.203–21.464 GB control versus 129.692–130.147 MB candidate (~163× lower median). Output is 4,108,274 → 3,684,224 bytes; the reduction is formatting, not record removal.

Fixture generation is outside timing. An export-only meter surrounds `write-trace!`; a separate inclusive meter covers temporary-directory setup, export, full readback/assertions and cleanup. Caller-thread CPU/allocation do not establish whole-process or retained-heap savings. The earlier 36-node probe produced a concrete baseline before broader investigation: 1,354.44 ms / 2.793 GB caller allocation for 531,476 serialized bytes.

Tests preserve complete cleaned map values, arbitrary keys, warnings, source, node files, raw program bytes and tree output, including real trace-tool consumer readback. Unlimited readable printer bindings avoid caller truncation. Ordinary generated data now has strict EDN readback; old pprint quote abbreviations required a safe Clojure reader. Arbitrary custom host values are not promised universal EDN support.

**Accepted failure contract:** direct streaming may truncate an existing trace.edn or leave partial output if printing fails. Exceptions propagate, the writer closes, and truncated output fails readback. This diagnostic export is not made transactional; optional staging/atomic rename was explicitly declined as unnecessary scope. No benefit is extrapolated to the motivating private 63.6 MB trace, which was not reread or exported for this run.

### Parser allocation

Tool: `perf/parse_sanitizer_probe.clj`; attribution: `results/optimization-parse-sanitizer-7ps8pufa/`; matched evidence and full timing ranges: `results/optimization-parse-sanitizer-matched-vyqze266/README.md`.

Three fresh pairs, fixed 256 MiB heap, 30 warmups and five ten-call batches. Each comparison process passes 55 semantic assertions. Independent baseline sanitizer copies, exact reader outcome comparisons and bounded seeded tests guard preservation of the state machines.

| Input | Chunks | Ordered sanitizer B/call, control → candidate | read-first B/call, control → candidate |
| --- | ---: | ---: | ---: |
| Unchanged | 64 | 79,568.8 → 4,728.8 | 195,504.8 → 120,664.8 |
| Unchanged | 256 | 306,128.8 → 7,800.8 | 748,104.8 → 462,184.8 |
| Unchanged | 1,024 | 1,212,368.8 → 20,088.8 | 2,967,352.8 → 1,824,344.8 |
| Rewritten | 64 | 34,136.8 → 34,560.8 | 96,192.8 → 99,808.8 |
| Rewritten | 256 | 113,048.8 → 113,472.8 | 352,272.8 → 365,104.8 |
| Rewritten | 1,024 | 440,984.8 → 441,408.8 | 1,384,960.8 → 1,434,656.8 |

Values are medians across three process medians. Allocation benefit is asymmetric: well-formed unchanged source benefits, while rewritten source pays a bounded cost. At 64 rewritten chunks the sanitizer wall ranges worsen from 251.98–256.32 to 276.35–312.57 µs/call; this observed regression is not discarded. Larger timings are variable and operation-order/JIT-sensitive. No unconditional speedup, runtime neutrality, retained-heap benefit, or measured paid-workload frequency is claimed. Lead accepts the local mechanism and explicit cost; the commit is independently reversible.

### Verbose presentation waits

Portable fixture, guarded test driver, supervisors, exact commands and receipts: `results/optimization-verbose-wait-20260225-b81f36a2/`. The directory label is an artifact identifier, not the run's authorization identifier.

Three fresh pairs, eight self/leaf × verbose × trace cases per process, 53 semantic checks each, all six exits 0 and reaped. The probe pins only presentation `rand-int(500)` to 250 ms. Four waits total 1,000 ms in controls and zero in candidates. Elapsed reductions: 1,020.309 / 1,022.806 / 1,016.339 ms. Values, provider-call counts, log content/order and trace lifecycle agree. No warmup or throughput study: these numbers demonstrate intentional wait removal, not compute speed or nonverbose timing neutrality. Offline providers only; paid verbose logging was not enabled.

## Convergence validation — actual executions

| Suite | Tests | Assertions | Exit | Failures / errors |
| --- | ---: | ---: | ---: | --- |
| Maintained fast | 513 | 4,564 | 0 | 0 / 0 |
| Maintained slow, including trace export | 246 | 960 | 0 | 0 / 0 |
| Explicit trace-tool | 23 | 104 | 0 | 0 / 0 |
| Guarded focused LLM | 77 | 675 | 0 | 0 / 0 |

Integrated receipts and positive-count summaries: `results/optimization-final-validation-001/`. Commands are `clojure -J-Dclojure.main.report=stderr -M:test-fast`, `-M:test-slow`, and `-M:test -n spell.trace-tool-test`. Processes were reaped. The repaired focused regression additionally hard-fails on insufficient positive test/assertion counts. Earlier focused I/O 95/265 and parser 12/1,496 also passed. Python runner validation executed six passing tests.

**Full suite:** `python3 -B perf/run.py baseline --timeout 900 --name optimization-final-80cases-001` ran once after convergence: complete **80/80**, exit 0, **343.2703038 s**, two warmups, five wall repetitions, three memory repetitions. Results/log/receipt are preserved under that unique name. Four optimized production-file hashes match the measured candidate; result revision 89a6347 predates the then-uncommitted wait change, so source hashes, not that revision alone, establish provenance.

18/80 final cases have wall CV > 0.2. The historical 608.930953 s baseline predates reliability HEAD and includes different intentional waits. This one final total is validation evidence, **not a claimed aggregate speedup**. Historical `results/baseline.json` remains unchanged, SHA256 `54754377fd62516b5971715bcd334237733b07370e24fc83fa514d98a08837ce`.

## Review, diagnostics and rejected/deferred experiments

- Initial Fable design and actual implementation reviews approved the bounded mechanisms. A **fresh independent** reviewer returned final **APPROVE, no blocking fixes**, on tracked edge 13, after inspecting integrated receipts, guarded wait validation and the code. Its large full-suite read was opaque; full80/80 status and candidate hash matching were directly verified by the lead, not independently by that reviewer. A failed predecessor reviewer is not counted as a verdict.
- Initial trace EDN warmup failed on pprint quote shorthand; preserved as diagnostic, not timing evidence. Interrupted trace comparison001 used unsupported `io/sh :timeout-ms`, causing default30s timeout and a temporarily orphaned JVM; child absence was verified before clean comparison002. Incomplete receipts are excluded, not erased.
- An exit0/zero-test wait run is explicitly **rejected**. Repaired and positively guarded77/675 runs, then integrated validation, replace it. Missing exit capture for early42/214 trace output is closed by final slow/trace-tool clean receipts. A large failed full-test snapshot remains uncommitted.
- Dogfooding exposed invalid communication targets, unsupported Spell strings/split arity and unknown shell timeout vocabulary. These were workflow failures, not reasons to weaken receipt/recovery policy. Log concrete API errors and prefer precise documented calls. Future receipt tools should embed discovered counts and reject zero tests; unknown I/O option keys should fail clearly.
- The parser probe exposed pre-existing rewriting of line-start comment markers inside multiline strings. It is preserved by differential tests, logged separately, and **not fixed** here. A separate correctness patch needs explicit handling of quoted strings, escaped quotes/backslashes and quotes inside ordinary/normalized comment bodies; a naive opening-quote toggle is insufficiently reviewed.
- No serializer schema hand-maintenance, trace dedup, cache, broad profiler framework, atomic staging, or context eviction was added. No additional benchmark-wide reruns were spent after final convergence.

## Remaining bottlenecks, next actions and stopping rationale

The final full suite still identifies `core-evaluator` at iterations=10,000/evals=20 as the largest local timed case: mean3,477.479 ms, wall CV0.008, ~1,400,091,184 caller-allocated bytes. Next justified performance work is a bounded allocation attribution of that exact fixture before choosing any evaluator change. Do not weaken dynamic scope, fresh locals or structural replay safety. Lifecycle dormant wake (20 agents/100 cycles) mean264.990 ms and API cleanup100cycles mean158.737 ms are further attribution candidates, not established optimization mechanisms.

Measure real unchanged/rewrite sanitizer frequency before further tuning; repair the known multiline-string correctness issue separately. Coordinator/globals/board worker allocation and intentionally rooted context remain unprofiled here; caller allocation alone cannot support whole-process claims. No full memory census, peak-RSS guarantee, API-wait attribution or long-run reliability claim is made.

The run continued beyond the two starting tasks into measured parser allocation and presentation-wait work. It stops after four small accepted changes, explicit rejected/failed experiments, integrated validation and fresh review—not because historical targets were hit. Further evaluator/concurrency changes need a new attribution hypothesis and risk more semantic complexity; preserving the verified result and validation reserve is preferable to speculative expansion.

## Usage and handoff

The shared authorization was **$100**, separate from prior reliability spend. Actual billed model dollars/tokens are **not exposed to this agent or its workers**; no usage total or certified remaining budget is invented. External run accounting/notebook is authoritative for the actual spend. There were no paid subprocess agents or repeated live provider demo. Portable local process durations and measurement scope are in receipts; they are not model billing metrics.

Lead approves these four commits and the documented tradeoffs. Codex final acceptance and its notebook entry remain external. All commits carry `Spell-Run: 2026-09-07-runtime-optimization-001` and `Notebook-Entry: notebook/entries/2026-09-07-runtime-optimization.md` trailers.
