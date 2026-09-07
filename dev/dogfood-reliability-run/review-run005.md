# Run005 reliability acceptance review

Base: `9ece4a1`, branch `codex/dogfood-reliability`. Includes the eight preserved run004 sole-interface drafts. Production/test implementation was delegated; the lead owns documentation, review and commit.

## Decisions and findings

- Fable 5.1 high initial design reviewer `:spawn-6514`, request edge 4: endorsed sole opts-aware `LLMProvider`, provider-owned effective model and centralized compile-agent policy; no compatibility layer.
- Same Fable reviewer, actual cumulative source/test/runner review edge 7: **APPROVE**, no blocking source locations. Reviewed the real child `:base` root/worker request tests, all provider implementations/callers, ack guard/atomicity and both runner languages plus Python tests.
- Standalone stale EDN acceptance concern: resolved by implementer with a pre-test absence guard for both receipt destinations and two fresh-JVM regressions.
- Claimed missing `baseline_unchanged`: disproved by initialization in current runner. Failed running publication must leave old artifacts intact and launch nothing; interruption after successful publication deliberately leaves running. No destructive fallback or retry added.
- Effective-model error-data concern is diagnostic only; no demonstrated functional defect and no speculative production change.
- Fresh independent Fable 5.1 high reviewer `:spawn-7582`, request edge 8: **APPROVE, no blockers**. Reviewed the actual cumulative diff, both new Python test files, review record and portable receipts. Confirmed the sole opts-aware interface, real child `:base` false through root/worker requests, ack guards/atomicity, running-before-cleanup/launch, atomic terminal publication and both stale-receipt guards. Accepted the documented limitations below. Quiet final decision posted to design message 9. Reviewers are read-only; their source approval is not independent test execution.

## Executed evidence

The lead ran `clojure -M:test` exactly once after all implementer edits settled: exit 0, **893 tests, 5102 assertions, 0 failures, 0 errors**. Raw log is ignored at `dev/dogfood-reliability-run/run005-full-suite.log` and is not a commit artifact.

Worker focused receipts: missing child-false regression 1/12; prefill/provider/LLM namespaces 103/865; mailing-list namespace 15/108; Python runner/standalone guards 17 tests (15 mocked + 2 fresh-JVM), all passing. Actual final portable receipts inspected by lead: `report.json`, `run005-python-tests.json`, `tests.edn`, `workflow.edn`. Fresh workflow completed with 3 tests/40 assertions, two distinct tracked child returns and one self-call effect. Report elapsed 2.045 seconds is JVM subprocess wall time, not API latency.

`git diff --check` passed. Exact removed-interface-symbol search in `src` and `test` returned no matches. Baseline preserved byte-for-byte, SHA-256 `54754377fd62516b5971715bcd334237733b07370e24fc83fa514d98a08837ce`.

## Limitations and recovery

Run004 failed during documentation/board API handling; its eight drafts were preserved, not treated as accepted or reimplemented. Run005 repaired board list descriptions without reinitialization. Quiet posts, actual own-page-token acknowledgements and captured request edges coordinate ordinary programs; proposed effects are not execution evidence. No runtime retry/read suppression/dedup policy or token-validation weakening was added.

Non-POSIX process-tree cleanup is unverified. Atomic replace does not promise fsync/power-loss durability. Unexpected non-OSError exceptions leave running; process-group kill errors may skip output collection while failing the run. Existing EDN writes are not a general cross-process atomic publishing API. Request-shape tests stop before live HTTP; deterministic workflow/tests do not establish live-provider autonomy, exact API wait or complete memory profiling. Trace-export measurement is a separate followup. Lead approval is not Codex final acceptance.
