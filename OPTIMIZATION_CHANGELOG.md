# Runtime Optimization Changelog

Run: `2026-09-07-runtime-optimization-001`. Base: `4abaa1d696413503ee4dd9a296e1e3df953ceb5f`; branch: `codex/dogfood-reliability`. Lead approval is subject to final integrated validation and fresh complementary review. Historical baseline remains immutable.

## Selected-line retention — approved

- Replace the returned `SubVector` with an independent vector; preserve selected strings, metadata, range clamping, full-file arity and error maps. No eviction or changes to retrievable context.
- Three fresh control/candidate process pairs, identical generated 200,000-line fixture and 256 MiB heap: held-minus-released heap **29,884,320 → 15,136 bytes** in each pair. Structural backing: whole 200,000-line vector → independent 100-line selection.
- Caller allocation **121,682,264 → 121,683,240 bytes** (+976 bytes). This is a retained-data reduction, NOT a transient-allocation or speed claim. The small candidate heap delta is near GC measurement noise.
- Evidence: `perf/selected_lines_probe.clj`, `perf/results/optimization-selected-lines-q_aloiij/`. Forced GC is outside read timing; generated data only; probe root released and temporary file deleted.
- Validation: focused I/O suite **95 tests / 265 assertions**, no failures/errors; six probe processes exit 0 with contents/metadata assertions. Fable actual review returned APPROVE on tracked edge 4; lead inspected source, tests, probe and command manifest and approves this bounded change.
- Complexity/rollback: O(selected-lines) copy; reverting restores parent-vector retention while avoiding the small copy allocation. No compatibility path.

## Streamed trace export — measured, final consumer validation pending

- Whole-map streamed readable printing replaces pretty-printing through an intermediate full string. Source/program files/tree and complete cleaned trace data are retained.
- Three fresh process pairs: median export **9,463.012 → 168.512 ms**; caller allocation approximately **21.203 GB → 130.145 MB**. Candidate wall range 122.116–179.603 ms is noisy; no private-trace extrapolation.
- Evidence: `perf/trace-export-results-002.json`, probe and comparison script. Interrupted comparison 001 is diagnostic only, excluded from the successful comparison.
- Fable actual patch review returned APPROVE on tracked edge 5; consumer integration and final tests still pending.

## Ongoing experiments and validation

- Bounded parse-sanitizer attribution is authorized; no parser source change approved yet.
- Python benchmark-runner validation executed: 6 tests, all passed.
- Full 80-case suite, complete Clojure suites, final fresh Fable verdict, commit index and final optimization report remain pending. No claim of full memory profiling or long-run reliability.
