# Runtime Optimization Changelog

Run: `2026-09-07-runtime-optimization-001`; base `4abaa1d696413503ee4dd9a296e1e3df953ceb5f`; same branch `codex/dogfood-reliability`. Implementation was delegated; lead approves the explicit tradeoffs below. Historical baseline and prior evidence are preserved.

| Change | Commit | Accepted benefit and cost |
| --- | --- | --- |
| Detach selected lines | `3e2cc1b` | Three fresh pairs: 200,000-line backing becomes an independent 100-line selection; held-minus-released heap 29,884,320 → 15,136 B. Caller allocation +976 B. Retention-only claim; small GC delta is noise-sensitive. |
| Stream trace output | `0405fb6` | Three fresh pairs: median export 9,463.012 → 168.512 ms; caller allocation ~21.203 GB → 130.145 MB. Compact formatting; explicitly accepted partial/truncated diagnostic output on printer failure. Full records/program bytes/tree and real consumer checked. |
| Lazy sanitizer output | `89a6347` | Unchanged read-first caller allocation −38.2–38.5%. Rewritten read-first +3.6–3.8%; some rewrite timings regress. An asymmetric allocation tradeoff, NOT an unconditional speedup. State machines and existing behavior preserved. |
| Remove verbose presentation waits | Commit accompanying this record | Exactly two verbose-only sleeps removed; logging/order/lifecycle/retries unchanged. Three offline pairs remove four fixed 250 ms waits; observed elapsed reduction 1,016.34–1,022.81 ms. WAIT reduction only, not CPU acceleration. |

## Validation actually completed

- Maintained fast suite: **513 tests / 4,564 assertions**, exit 0.
- Maintained slow suite (including trace export): **246 tests / 960 assertions**, exit 0.
- Explicit trace-tool suite: **23 tests / 104 assertions**, exit 0.
- All have positive test counts, zero failures/errors, and reaped children. Receipts: `perf/results/optimization-final-validation-001/`.
- Full deterministic suite: **80/80 cases**, complete, exit 0, **343.270 s**, 2 warmups / 5 wall repetitions / 3 memory repetitions. `perf/results/optimization-final-80cases-001.json`. Four production source hashes match the tested candidate, including the then-uncommitted verbose deletion. This single convergence run is NOT an aggregate speedup comparison; 18/80 cases have wall CV > 0.2.
- Guarded wait regression: **77 tests / 675 assertions**, exit 0; six offline comparison processes each pass 53 semantic checks. Earlier exit-0/zero-test diagnostic is explicitly rejected and preserved.
- Focused I/O: 95/265; parser: 12/1,496 plus 55 assertions per comparison process. Earlier trace 42/214 output lost its exit capture; the integrated slow and trace-tool receipts now close that gap.
- Python benchmark-runner tests: 6 tests passed.

## Review and rollback

Original Fable design and actual patch reviews approved the mechanisms. A fresh independent reviewer returned final **APPROVE, no blocking fixes**, on tracked edge 13 after inspecting maintained-suite receipts and the guarded wait regression. The reviewer accepted full-suite completion and candidate hash matching on the lead's direct verification; the large result was opaque in the reviewer's read. Optional atomic trace staging is deliberately deferred: diagnostic partial output is documented, tested, loud, and accepted. Each source optimization is independently revertible; its focused evidence is committed alongside it.

No hidden orchestration policy, compatibility path, context eviction, replay deduplication or weakened receipt/isolation semantics was introduced. Common-path parser frequency in paid workloads is unmeasured. Caller allocation excludes worker threads; retained-data probes are not complete heap profiling. No private trace extrapolation or long-run reliability claim.

## Remaining work and known issues

Final report and review closure accompany the documentation commit. The pre-existing multiline-string comment normalization bug remains a separate correctness follow-up; changing it would invalidate the behavior-preserving parser comparison. Evaluator allocation remains the largest measured local bottleneck. Further broad runtime work is deferred pending a bounded attribution hypothesis; hitting old targets was not used as the stopping rule.
