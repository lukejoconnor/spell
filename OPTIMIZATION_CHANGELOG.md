# Runtime Optimization Changelog

Run: `2026-09-07-runtime-optimization-001`. Base: `4abaa1d696413503ee4dd9a296e1e3df953ceb5f`; branch: `codex/dogfood-reliability`. Historical baseline is immutable. Lead approvals below remain subject to final integrated validation and fresh complementary review.

## Selected-line retention — approved and committed `3e2cc1b`

- Copy only the returned selection into an independent vector; preserve strings, line metadata, clamping, full-file arity and error maps. No eviction of retrievable context.
- Three fresh control/candidate pairs, identical generated 200,000-line fixture and 256 MiB heap: held-minus-released heap **29,884,320 → 15,136 bytes** in every pair. Backing vector: 200,000 lines → 100 selected lines.
- Caller allocation **121,682,264 → 121,683,240 bytes** (+976 bytes). Retained-data reduction only, not transient-allocation or speed improvement; candidate heap delta itself is near GC noise.
- Evidence: `perf/selected_lines_probe.clj`, `perf/results/optimization-selected-lines-q_aloiij/`. Focused I/O suite: **95 tests / 265 assertions**, no failures/errors; six probe exits 0 with content/metadata assertions. Fable actual review edge 4 and lead approve.
- Cost/rollback: O(selected-lines) copy; reverting restores whole-file backing retention while avoiding a small copy allocation.

## Streamed trace export — approved and committed `0405fb6`

- Replace whole-trace pretty-printing through a full intermediate string with readable whole-map streaming. Preserve all cleaned records, arbitrary keys, warning/source data, program-file bytes and tree output; no manual schema or compatibility path.
- Three fresh process pairs at 512 MiB, generated 144-node/48-form fixture: median export **9,463.012 → 168.512 ms** (56.16×); median caller allocation approximately **21.203 GB → 130.145 MB** (~163× lower). Candidate wall range **122.116–179.603 ms** is noisy. Export bytes **4,108,274 → 3,684,224**. These are bounded synthetic export measurements, not a prediction for private traces or retained-heap measurements.
- Fixture generation is outside measurement. Export-only timing covers `write-trace!`; a separate inclusive meter covers temporary-directory setup, export, full readback/assertions and cleanup.
- Evidence: `perf/trace-export-results-002.json`, `perf/trace-export-probe.md`, generated probe, comparison supervisor and raw receipts. Interrupted comparison 001 is preserved and excluded; clean comparison 002 has six successful process receipts.
- Fable actual patch review edge 5 approved; subsequent explicit review and lead accepted the non-atomic diagnostic-output contract: printer failure may truncate/leave partial `trace.edn`. Exceptions propagate, writer closes, and truncated output fails the real consumer. No staging abstraction added.
- Final focused output: **42 tests / 214 assertions**, no failures/errors, including actual trace-tool consumer, print settings, full data/bytes/tree and failure cleanup. Process exit was verified absent, but exit-code capture was lost to orchestration error; final integrated suites must provide a clean exit receipt. Source and final test output inspected by lead.
- Cost/rollback: compact rather than pretty whitespace; partial output on printing failure. Reverting restores expensive pretty formatting and whole-string buffering. Arbitrary custom host values are not promised universal strict-EDN support.

## Ongoing experiments and final validation

- Three fresh attribution runs locate roughly 40% of unchanged `read-first` caller allocation in unconditional sanitizer copying. Bounded lazy-copy candidate and differential tests authorized; parser behavior changes are excluded from this optimization.
- Two verbose-only random sleeps are approved for a separate offline-tested wait-reduction experiment, not a CPU-speedup claim. Provider retry/backoff remains untouched.
- Discovered multiline-string comment normalization bug is recorded separately, not silently repaired in performance comparisons.
- Python benchmark-runner validation executed: **6 tests**, all passed.
- Full 80-case suite, complete Clojure suites, fresh final Fable verdict and final report/commit index remain pending. No full-memory-profiling or long-run-reliability claim.

## Lazy sanitizer output — approved as an asymmetric allocation tradeoff

- Allocate an output builder only at the first actual rewrite; unchanged strings are returned directly. Preserve both state machines, sanitizer ordering, nil/empty behavior and reader results/errors. No cache, deduplication, input suppression or parser behavior repair.
- Three fresh matched process pairs, identical 256 MiB heap, 30 warmups and five ten-call batches: unchanged `read-first` caller allocation falls **38.2–38.5%** across 64/256/1024-chunk fixtures. Ordered sanitizer allocation falls **79,568.8 → 4,728.8**, **306,128.8 → 7,800.8**, and **1,212,368.8 → 20,088.8 bytes/call**.
- Real cost: rewritten sanitizers allocate approximately **424 extra bytes/call**; rewritten `read-first` median allocation increases **3.6–3.8%**. Small rewritten sanitizer wall ranges regress from **251.98–256.32 to 276.35–312.57 µs/call**. Other timings are noisy and operation-order/JIT-sensitive. This is NOT an unconditional speedup or a runtime-neutral optimization.
- Lead accepts the common unchanged-input allocation benefit for a small local mechanism, with the recovery-input tradeoff explicit. Revert this commit independently if rewrite-heavy workloads dominate. Frequency of unchanged input in paid workloads was not measured.
- Evidence: `perf/parse_sanitizer_probe.clj` and `perf/results/optimization-parse-sanitizer-matched-vyqze266/README.md`; attribution and initially failing probe evidence retained separately. Caller-thread allocation only; no retained-heap claim.
- Validation: **12 tests / 1,496 assertions**, no failures/errors; all six comparison processes exit 0 with **55 semantic assertions each**. Differential tests use independent baseline functions and seeded inputs. Actual Fable review edge 9 approved and closed the independent escape-oracle check; lead inspected the exact final mechanism and accepts it.
- Existing multiline-string comment normalization corruption is explicitly preserved by this optimization and remains a separate correctness follow-up, not a newly introduced behavior.
