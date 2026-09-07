# Measured findings and provisional optimization targets

## Status, units and acceptance rules

This is a **NEXT-run optimization brief**, not a speedup announcement. Audit002's baseline passed 80/80 cases across 14 families in 608.930953 seconds; its outer reporting run later exited 1. `results/baseline.json` is authoritative for all samples, parameter vectors, evidence, resource snapshots and source hashes. Environment and immutable provenance are detailed in [README.md](README.md).

Below, wall values are medians in milliseconds; CV is the sample wall standard deviation divided by its mean. Allocation values are median **calling-thread bytes**, expressed as decimal MB where indicated (1 MB = 1,000,000 bytes). Live/cleanup values are median independent-pass heap deltas in bytes, not payload character counts, RSS, or peak heap. All figures include the fixture boundaries documented in the harness.

Every percentage target is a **provisional engineering goal relative to a named measured case**, not a statistically established attainable gain. The 10–20% reductions are sized to test a specific mechanism while demanding materially more than a tiny median fluctuation; the sleep and SubVector goals reflect directly identified avoidable work/reachability. Guardrails are deliberate where evidence does not justify optimization. None overrides semantics or payload retention requirements.

Validation for every family:

1. Repeat unmodified control and candidate on the same machine, serially, in fresh JVMs. Use at least three fresh-process comparisons and identical parameters, heap/GC, assertions and timing boundaries. First reproduce the baseline direction; targeted runs have different JIT/order history from the original single-JVM full run.
2. Check warmup sensitivity (for example 2 versus 5–10 warmups) and use more timings when needed. Prefer wall CV at or below 0.10 for a timing claim; if noise remains large, report it and require an effect clearly larger than observed run-to-run spread rather than forcing a PASS. The original five timings/three memory samples alone establish no confidence interval or p99.
3. Compare wall and allocation separately. Worker allocation needs additional instrumentation before whole-process allocation claims. A reduction in blocking/sleep time is not a CPU reduction. Evaluate targets against both the historical values and the newly reproduced control; investigate disagreement rather than silently redefining success.
4. Keep all correctness checks and maximum parameter cases. Never downscale a failure or hide a failed/partial checkpoint. Preserve baseline artifacts and record candidate source hashes. After focused convergence, the NEXT optimizing run should execute all 80 cases and focused semantic tests, not repeat broad suites indefinitely.
5. Retained-live targets apply only to equally rooted fixtures. Post-cleanup deltas are a release/no-growth guardrail across repeats, not a requirement to reach exactly zero. Small retained evidence, explicit-GC limitations, and negative collection-noise deltas preclude byte-perfect cleanup claims.

## Priority order

- **P1: verbose-path pacing, evaluator allocation, tracing/serialization amplification, selected-line backing retention.** These have substantial measured costs or directly demonstrated source mechanisms.
- **P2: coordinator mailbox allocation, repeated API setup, context serialization and board interpreter work.** Profile their combined fixture paths before attributing everything to one runtime function.
- **P3: lifecycle scheduling and globals contention noise; retention guardrails.** Existing structural correctness and receipt-style capture already work. Do not manufacture a leak or optimization mandate from them.

Fixture wall times below are not a global application profile: different families do different amounts of work. The ~609-second full run includes warmups, repeated independent executions, requested GC and random verbose sleeps; it is not 609 seconds of runtime CPU.

## 1. core-evaluator — P1, stable high allocation

Source: `perf/spell/perf/core.clj:evaluator-run`; `src/spell/eval.clj:spell-eval` and evaluator dispatch/environment/non-tail continuation paths.

| Named case | Wall ms | CV | Caller allocation MB |
|---|---:|---:|---:|
| iterations=1000, evals=20 | 335.6728 | 0.0159 | 126.161904 |
| iterations=10000, evals=20 | 3346.5684 | 0.0106 | 1260.881904 |

Ten times the loop work produces approximately ten times both cost measures. The large case executes 200,000 loop iterations and allocates about 1.261 GB on the caller while retaining only 328 B live / 520 B after cleanup. This is allocation churn, not demonstrated retained leakage.

**Target:** reduce large-case wall and caller allocation by 20%: at most **2677.25 ms / 1008.71 MB**. Profile per-evaluation result/environment construction and repeated dispatch; reduce transient representations without changing dynamic scope or skipping evaluation. Validation must retain the non-tail outer +1, exact arithmetic result, all six parameter cases and absence of sustained cleanup growth. No retained-payload optimization is indicated here.

## 2. core-parse-expand — P2, roughly size-scaled combined work

Source: `core.clj:parse-run`; `src/spell/parse.clj:read-first`; `src/spell/eval.clj:expand-expr, spell-eval`.

| forms | Wall ms | CV | Caller allocation MB |
|---|---:|---:|---:|
| 1000 | 12.9697 | 0.1376 | 6.806048 |
| 5000 | 51.2751 | 0.0506 | 33.960600 |

The 4/20-form cases have CV 0.4517/0.3094 and are not evidence for micro-latency claims. The large case supports investigating repeated traversal/intermediate expansion allocations, but includes source construction and evaluation, not parser-only time.

**Target:** at forms=5000, 20% lower wall and caller allocation: **41.02 ms / 27.17 MB**. Profile construction/parse/expand/eval separately outside the unchanged comparison fixture before choosing a mechanism. Validate exact final 42 and valid macro behavior; guard small cases against reproducible regressions after re-measuring noise. No claim about nesting-depth or malformed-input recovery follows from sibling counts.

## 3. core-serial-self-calls — P2, growing-prefix allocation candidate

Source: `core.clj:serial-run`; `src/spell/llm.clj` prefix/suffix pipeline, `src/spell/macros.clj` extension construction, `src/spell/eval.clj` self-call continuation, `src/spell/api.clj` lifecycle.

| calls, padding=0 | Wall ms | CV | Caller allocation MB |
|---|---:|---:|---:|
| 20 | 9.8202 | 0.0923 | 4.351120 |
| 100 | 32.1847 | 0.0966 | 49.479808 |

Five times the calls costs about 3.28× wall but 11.37× caller allocation. Accumulating source construction/formatting is a plausible investigation, not an isolated causal measurement. API compilation/discovery and shutdown are also included; future allocations are missing.

**Target:** reduce calls=100 caller allocation by 20% to **39.58 MB**; wall guardrail **≤35.40 ms** (10% above the historical median), with a new reproducible control required. Consider avoiding redundant prefix reconstruction while retaining receiving `!extend`, exact 100 provider calls, terminal 42 returning through +1 as **43**, depth/context bounds and non-tail behavior. The 4-call CV 0.2777 requires re-measurement before a latency claim. No network/model speedup is implied.

## 4. core-trace-log — P1, artificial pacing plus trace overhead

Source: `core.clj:trace-measure, serial-run`; `src/spell/api.clj:72,103–104` makes `:log-writer` enable verbose; `src/spell/llm.clj:348–351` sleeps `rand-int 500` milliseconds on the verbose self-call path. The leaf path has the same pacing at 639–642 but is not exercised by this serial-chain fixture. Trace graph/output paths are in `src/spell/trace.clj`.

Matched **calls=20, padding=0**:

| mode | Wall ms | CV | Caller allocation MB |
|---|---:|---:|---:|
| off | 6.6804 | 0.1542 | 4.359048 |
| trace | 56.1306 | 0.0770 | 95.067112 |
| verbose | 4816.8311 | 0.1745 | 4.523960 |
| both | 5635.7410 | 0.0838 | 95.229880 |

Random sleep can contribute 0–499 ms per call; its expected total for 20 calls is about 4990 ms, consistent with the multi-second verbose results. This is directly source-supported pacing, **not evidence that writing a few kilobytes inherently takes five seconds**. Randomness also explains why both need not exceed verbose in every finite sample. No pacing change was made during the audit.

**Targets:** evaluate making presentation pacing separate from file logging, with explicit behavior review. For the named 20-call unpadded cases, seek **≥90% wall reduction**: verbose ≤481.68 ms and both ≤563.57 ms. Preserve complete logs, writer ownership/flush/close and identical provider call counts. Report the removed intentional wait separately from CPU/I/O work.

Trace-only padding=1024 takes **105.6546 ms**, CV **0.0346**, caller allocation **205.191664 MB**, compared with off **6.3252 ms / 6.458408 MB**. Trace-only targets for padding 0/1024 are **20% lower wall and allocation**: approximately **44.90/84.52 ms** and **76.05/164.15 MB**. Investigate repeated graph/source serialization and trace export rather than omitting nodes, values or evidence-file parsing from timing.

Separate held-trace mode at calls=20, prompt-chars=100000 measures **51.4694 ms**, CV **0.0829**, caller allocation **92.280784 MB**, live delta **2,007,520 B**, cleanup **472 B**. At prompt-chars=1000 its live delta is 27,520 B. Held mode bypasses API/file tracing and includes prompt creation. **Guardrail:** retain all 20 complete prompts/values with no reproducible live increase beyond 10% of 2,007,520 B; do not target below the full payload floor without compression or changed release semantics. File-mode live samples occur after API return and are not trace peak memory.

## 5. core-provider-local-boundaries — P1/P2, three distinct local boundaries

Source: `core.clj:boundary-setup, boundary-measure`; private `src/spell/provider.clj:codex-tc-request-body, parse-codex-tc-stream`; `src/spell/io.clj:111–135`.

| mode and parameter | Wall ms | CV | Caller allocation MB | Live B |
|---|---:|---:|---:|---:|
| json, chars=100000 | 8.3822 | 0.0310 | 18.425752 | 664296 |
| json, chars=1000000 | 63.3023 | 0.0727 | 184.867064 | 7343720 |
| sse, events=1000 | 3.7817 | 0.2267 | 3.530376 | 107096 |
| sse, events=10000 | 17.7726 | 0.1651 | 36.549296 | 2107936 |
| read-lines, lines=10000 | 4.6211 | 0.1790 | 4.228088 | 607056 |
| read-lines, lines=100000 | 25.5233 | 0.1173 | 42.809256 | 6134992 |

**JSON target:** at chars=1M, 20% less caller allocation (**147.89 MB**) and a 10% wall regression guardrail (**69.63 ms**). Inspect intermediate encoding/building copies, but preserve Unicode/quote/backslash/newline exactness. The fixture intentionally retains prompt, body, encoded and decoded values: its 7.34 MB live delta is not an accidental cross-run leak.

**SSE target:** at events=10000, provisionally 15% lower caller allocation (**31.07 MB**); re-measure wall noise before a latency target. Generation of the synthetic SSE string is timed too. Preserve exact finished custom-tool output when the completed response output is empty; this case tests no socket buffering, auth, timeout or real streaming latency.

**Selected-line target:** at lines=100000, reduce retained live delta by at least 90%, to **≤613,499 B**, while returning the exact ten lines and metadata. `read-lines(1,11)` slurps/splits the whole file and returns a **SubVector that keeps the entire backing line vector reachable**. The 6.13 MB is not ten-line storage. An independently materialized small result could remove this reachability; bounded streaming could additionally reduce transient allocation, but must preserve clamping, empty-file, line-ending, error and half-open range semantics. Merely assuming `vec` copies an already-vector view is unsafe. Provisional caller-allocation target is 20% lower (**34.25 MB**), wall guardrail **28.08 ms**. File creation/read/check/delete and uncontrolled local cache remain included, so this is not isolated read throughput. Cleanup median is 512 B.

## 6. lifecycle-coordinator — P2, mailbox/transition allocation

Source: `perf/spell/perf/lifecycle.clj:coordinator-measure`; `src/spell/coordinator.clj:request!, wait!, fill!, send!, drain!` (packet anchors 68–236,372).

At cycles=10, mailbox-batch=256: agents=4 takes **21.6419 ms**, CV **0.2862**, caller allocation **31.179656 MB**; agents=20 takes **76.7468 ms**, CV **0.0233**, caller allocation **170.013368 MB**. The latter covers 51,200 mailbox messages plus ten 20-slot hyperedges. Live/cleanup deltas are 14,808/544 B.

**Target:** for agents=20/cycles=10/batch=256, 20% less wall and caller allocation: **61.40 ms / 136.01 MB**. Investigate persistent collection/transition churn and compatible batching, not a presumed contention bottleneck: this is a coordinator-only serial workload. Preserve FIFO, ordered fanin, readiness signaling, exact one hyperedge per cycle, empty completed edges and idle parent. Re-measure the noisy 4-agent comparison before claiming a scaling exponent.

## 7. lifecycle-spawn-return — P3, retain correctness and establish scheduling controls

Source: `lifecycle.clj:spawn-measure`; `src/spell/runtime.clj:spawn-ask` and child completion/retirement; coordinator request/result edges.

At cycles=100: agents=4 takes **10.1929 ms**, CV **0.2088**; agents=20 takes **34.3363 ms**, CV **0.0352**, caller allocation **11.016080 MB**, live/cleanup **1344/504 B**. The latter is 2000 actual runtime-dispatched host callbacks, not 2000 LLM calls or compiled Spell programs.

**Target/guardrail:** agents=20/cycles=100 wall **≤37.77 ms** (10% historical allowance) and exact callbacks=2000, remaining children=0, remaining edges=0. Re-measure small/noisy cases before pursuing scheduler optimization. Profile dispatch/coordination only if a replicated control identifies a useful cost; do not optimize based on caller allocation as if it included futures. Retirement must be established before handle reuse; no sleep-only readiness shortcut.

## 8. lifecycle-dormant-wake — P2/P3, bounded wake cost, not leak proof

Source: `lifecycle.clj:dormant-setup, dormant-measure`; `src/spell/runtime.clj:start-box`, orphan wake/finish and receipt rewriting; coordinator generation transitions.

At cycles=100: agents=4 takes **110.3813 ms**, CV **0.0914**; agents=20 takes **296.5254 ms**, CV **0.0578**, caller allocation **13.186960 MB**, live/cleanup **3992/984 B**. Small 4-agent cases have CV 0.3989/0.4636. The 20-agent live median is 3992 B at both 10 and 100 cycles, encouraging bounded-fixture evidence, not a 48-hour result.

**Target:** provisionally 15% lower agents=20/cycles=100 wall, **252.05 ms**, only after profiling separates scheduler/backoff waits from useful receipt/state work. Preserve 2000 callbacks and generation increments, exactly 20 dormant identities, zero active runners and zero edges. Cleanup/no-growth is a guardrail, not forced immediate disappearance of JVM pool threads. `close!` wakes but does not join; finished/runner-nil is state evidence, not proof of every stack exiting.

## 9. lifecycle-api-cleanup — P2, repeated fresh-run setup

Source: `lifecycle.clj:api-measure, api-cleanup!`; `src/spell/api.clj:execute-run*` cleanup at 121–149; `src/spell/agent.clj` profile compilation/discovery.

Cycles=10: **19.3257 ms**, CV **0.1306**, caller allocation **20.212176 MB**. Cycles=100: **161.4593 ms**, CV **0.0619**, allocation **202.080488 MB**, live/cleanup **6168/1680 B**. The one-run CV is 0.3202; per-call division of that sample is not a stable startup estimate.

**Target:** cycles=100, 20% lower wall/allocation: **129.17 ms / 161.66 MB**. Inspect repeated immutable metadata/profile processing; any cache must preserve configuration freshness and resource ownership and must not share run-local coordinator, context or globals. Validate 100 successful 42 results, 100 closed coordinators, no edges and 297 adjacent identity checks. API observers release references before memory probing. The API lacks a total-run timeout, so retain the launcher deadline; no general cross-run memory-leak absence is proven by 100 runs.

## 10. context-render-edit — P2/P3, optimize allocations only after noise checks

Source: `perf/spell/perf/context.clj:measure-render`; `src/spell/context.clj:serialize-contribution, stored` (154–209); `src/spell/eval.clj:apply-edits`; parser.

At payload-chars=8192: operations=20 takes **2.4431 ms**, CV **0.2405**, allocation **1.893728 MB**, live **10952 B**; operations=100 takes **8.8770 ms**, CV **0.1494**, allocation **6.402352 MB**, live **20216 B**. At operations=100/payload-chars=64 wall is 9.8836 ms with CV 0.2894. These data do not establish that bigger payloads are faster.

**Target:** operations=100/payload-chars=8192, provisionally 15% less caller allocation (**5.44 MB**); **re-measure wall noise first**. Investigate bounded rendering and intermediate parse/edit forms while keeping the 256-character contribution budget and exact retrieved value. This fixture reuses one payload object: 100 stored IDs need not retain 100 separate payload strings. Preserve source pruning, stored retrieval and zero stored entries for the small inline case. Do not transfer the unique-value memory slope from the next family to this one.

## 11. context-pruned-storage — P2 allocation, semantic retention guardrail

Source: `context.clj:measure-pruned-storage`; `src/spell/context.clj:serialize-value, stored`, per-run values atom; evaluator edit markers.

| cycles, payload-chars=8192 | Wall ms | CV | Caller allocation MB | Live B |
|---|---:|---:|---:|---:|
| 20 | 5.9199 | 0.0224 | 16.268888 | 167272 |
| 100 | 31.4135 | 0.0607 | 81.337888 | 835256 |

At 100 cycles, all 819,200 payload characters remain intentionally retrievable despite zero surviving source references. Cleanup median is 512 B. Prompt pruning limits visible source; it is **not** automatic release from the live run store, and these data do not establish across-run leakage.

**Targets:** 20% lower allocation/wall for cycles=100 (**65.07 MB / 25.13 ms**) by investigating oversized-value serialization/preview intermediate work. Retained-live **guardrail ≤918,782 B** (10% above baseline), with all 100 stored values and full payload preserved. No target below the full-payload storage floor without explicit compression or separately reviewed release semantics. The payload character count is not a portable heap-byte formula. Longer cycle counts would be a new experiment, not retroactive evidence of an unbounded leak.

## 12. globals-contention — P3, re-measure noise first

Source: `context.clj:measure-contention, stop-workers!`; `src/spell/globals.clj:17–35` atomic updates.

Equal total-updates=2000: workers=4 takes **1.0403 ms**, CV **0.0806**; workers=20 takes **1.3695 ms**, CV **0.2318**. Caller allocations 7280/27896 B exclude the workers doing the updates; they cannot quantify CAS retry allocation. The ~1.32× wall contrast is suggestive, not a proven contention penalty given startup/scheduling and variance.

**Target:** first reproduce both controls with adequate warmup/repetitions and preferably CV≤0.10. Until then, no numerical speedup demand; use a **10% wall regression guardrail against a stable fresh-process control** and exact counter=2000. A future optimization may examine retry contention but must keep atomic update semantics, pure retryable `inc`, common gate, shared 10-second join deadline and two-second completion-barrier teardown. Cancelled-Future state alone is not body termination. Do not infer whole-process allocation or arbitrary uninterruptible-worker cleanup from the supplied injection test.

## 13. board-post-digest — P2, stable interpreter/board cost

Source: `context.clj:measure-board, check-board-and-drain!, pattern-call`; `config/spl-lib/patterns.spl` mailing-list operations (packet 1290–1460, dispatcher 1619–1622); `src/spell/globals.clj` and evaluator invocation.

| posts / retention / page-size, body-chars=1024 | Wall ms | CV | Caller allocation MB | Live B |
|---|---:|---:|---:|---:|
| 20 / 8 / 3 | 14.9051 | 0.0360 | 7.061952 | 11304 |
| 60 / 20 / 7 | 33.6238 | 0.0326 | 17.966024 | 25752 |

This is a combined setup/post/digest/ack/cleanup workload; both retention and pagination differ between rows, so the ratio is not pure per-post scaling.

**Target:** posts=60/retention=20/page-size=7/body-chars=1024, 15% lower wall/allocation (**28.58 ms / 15.27 MB**). Investigate dispatch evaluation, summary construction and repeated immutable updates without changing shared executable source/customization behavior. Validate retained IDs 41–60, gap 1–40, read-only repeated digests, exact-token acknowledgements, bounded summary pages without bodies/provenance and final empty page. Cleanup median is 728 B.

The isolated fixture's send stub prevents real sends but **does not count attempted sends**; a board operation might catch its exception. Thus it does not prove zero notification attempts or delivery correctness. Functional mailing-list tests/demo and historical audit deliveries are separate evidence; no active audit board is touched by this fixture.

## 14. board-captured-snapshot — P3, preserve an already-demonstrated usage improvement

Source: `context.clj:measure-capture`; context stored values and globals update return value; native board retention.

All rows: retention=8, page-size=3, body-chars=8000.

| posts, capture | Wall ms | CV | Live B | Stored entries / reachable bodies |
|---|---:|---:|---:|---:|
| 20, board | 24.9859 | 0.0181 | 180312 | 20 / 20 |
| 20, receipt | 24.4196 | 0.0394 | 67160 | 0 / 8 |
| 60, board | 63.8959 | 0.0120 | 538872 | 60 / 60 |
| 60, receipt | 63.6874 | 0.0259 | 67160 | 0 / 8 |

At 60 posts, capturing full returned boards retains about **8.02×** the live heap of small receipts; the receipt contrast is about **87.5% lower live delta**, already measured with existing behavior. Timing is nearly equal; caller allocation is 62.614384 versus 61.685760 MB. Do not sell this as an implemented runtime speedup or as required/current board usage.

**Guardrail:** receipt cases at both posts=20 and 60 retain zero context entries, eight distinct current bodies (64,000 characters) and live delta **≤73,876 B** (10% allowance), with no reproducible growth across those paired sizes. Both cleanup medians are 768 B. Preserve full-board mode as a diagnostic contrast with 60 stored snapshots / 60 bodies at posts=60; do not silently evict intentionally stored histories to make it match receipts. The practical mechanism is already available: perform a globals update and return a tiny receipt, rather than serializing the returned historical board. Compression/release of intentionally captured histories would require a separate semantics proposal.

## Explicit omissions and limits

- **Network/model latency and cost:** no live-provider timings, credentials, connection setup, retries, rate limits, socket backpressure or inference throughput measured. The paid agent runs are orchestration dogfood costs, not benchmark-provider costs.
- **Whole-process/native memory and peaks:** no native allocation accounting, RSS peak sampler or whole-process allocation profiler. Existing RSS snapshots, committed heap and thread peaks cannot substitute for these. Future work should collect them separately without polluting the unchanged timed region.
- **Long-duration reliability:** 100-cycle lifecycle/API proxies and bounded retention fixtures are not a genuine 4–48-hour soak. No 48-hour memory slope, thread trend or reliability claim is supported.
- **Coverage granularity:** parse siblings are not deep syntactic nesting; serial chains stop at 100; source rendering/pruning is a synthetic analogue of large tool disclosure, not end-to-end `!describe`/provider latency. Malformed recovery performance, cancellation under uninterruptible host work, broad MCP/native/process I/O and large concurrent board traffic are not measured here.
- **Attribution:** generation, assertions, cleanup, local cache and setup are timed. Profiling can identify mechanisms, but changing workload boundaries requires a new separately labelled baseline. Explicit GC and small sample evidence are imperfect isolation.

Retain coordinator non-deadlock, dynamic scope, non-tail returns, recovery, explicit receipt/resumable-context semantics, ordered messages, context retrieval and per-run isolation throughout any optimization. These are constraints, not optional trade-offs for the targets.
