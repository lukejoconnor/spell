# Spell reliability dogfood changes

Run `2026-09-07-dogfood-reliability-003` completes the seven preserved run002 drafts on base `ac6d8da`, on branch `codex/dogfood-reliability`. Production and test implementation was delegated; the lead reviewed, verified, documented and committed approved work.

## What changed and why

- **Fixed callable arity.** Validate the number of outer function parameters before binding/destructuring or executing the body, after evaluating arguments. Missing arguments no longer capture caller bindings. Direct calls, `apply`, callbacks and function `recur` produce catchable errors while valid dynamic scope/destructuring and effect ordering remain intact. The evaluator guard and `bind-params` guard protect distinct entry paths and are intentionally retained.
- **Single provider-owned prefill policy.** Agent compilation derives defaults from effective-model capability and thinking mode. Anthropic capability now honors a per-call `:model` override, just as dispatch does. An optional capability protocol preserves existing provider implementations. Explicit and inherited false remain false; incompatible explicit true fails with a useful diagnostic rather than silently downgrading or retrying. CLI/benchmark eager defaults were removed, inheritance corrected, and the compilation docstring updated.
- **Meaningful, registered regressions.** New callable-arity and prefill-policy suites run in `:test-fast`; the new deterministic multi-agent workflow runs in `:test-slow`. Provider tests exercise actual provider records and serialized request shapes before HTTP, including both override directions, thinking conflicts, retained task content, inherited false at root/worker compilation and Anthropic TC tool choice.
- **Portable board workflow.** A fresh-JVM runner verifies subscriptions before child generation, quiet posts, bounded digests, actual-token acknowledgement, two tracked completed returns, persisted report readback, source retention and effect replay safety. Failure coverage includes duplicate initialization, retention gaps, stale tokens, atomic onboarding failure and task recovery outside the setup-only catch. Runner failure gates include missing baseline/receipts, zero discovered tests, timeout and incomplete child returns; these are explicit test-program checks, not runtime rescue.
- **Action-oriented skills.** Coding/context guidance makes compact literal checkpoints, bounded evidence packets and artifact-or-blocker handoffs explicit. Opaque stored-result markers are not inspected evidence; proposed effects are not receipts. Guidance remains task-level ordinary program logic, not hidden read suppression, deduplication or retries.

## Validation

All following final checks passed with zero failures/errors:

| Check | Result |
| --- | --- |
| `clojure -M:test-fast` | 508 tests, 3105 assertions |
| `clojure -M:test-slow` | 238 tests, 877 assertions |
| `clojure -M:test -n spell.callable-arity-test -n spell.prefill-policy-test` | 14 tests, 152 assertions |
| Callable arity, independently assigned focused run | 6 tests, 126 assertions |
| Existing evaluator suite | 156 tests, 932 assertions |
| Prefill policy, repaired final namespace | 8 tests, 26 assertions |
| `python3 dev/dogfood-reliability-run/run.py` | Fresh JVM, 3 tests, 40 assertions; completed persisted workflow and both distinct tracked returns |

The two initial actual-record prefill override regressions failed before the correction (2 tests, 2 assertions, 2 failures), then passed. An intermediate source edit mistakenly serialized an I/O result envelope and produced a zero-test false green. That run is explicitly excluded: the worker restored plain Clojure source, verified namespace discovery, and reran nonzero coverage. Final broad checks and the portable runner were independently rerun by the lead after the last followups.

The runner also passed a worker-executed missing-baseline sandbox check: failed JSON receipt, exit 1, no JVM launch, no absolute paths, and no mutation of the real baseline. Raw logs, copied papers, profiles and machine-specific assignments are not included in the portable evidence.

## Fable review and resolutions

A fresh configured Fable 5.1 high reviewer (`:final-review003`) reviewed the complete 788-line cumulative implementation diff plus the portable runner and README, returning **APPROVE, no blocking defects** on tracked edge 7. After bounded followups it reviewed actual final deltas and returned **APPROVE, all prior suggestions resolved or withdrawn** on edge 11. The reviewer was read-only; reported test counts were not its independent executions.

- Effective-model prefill selection was corrected; analogous TC behavior was already correct and is now regression-tested.
- Portable receipt paths were normalized; raw subprocess/exception text was omitted from portable JSON to avoid absolute/symlink-path leakage. Failures retain phase/type and numeric status information without hidden retries.
- Unexpected prefill test exceptions now propagate instead of becoming assertion maps.
- Suggested workflow cleanup was already provided by `with-test-run`'s unconditional `try/finally` coordinator close. The reviewer verified that fixture and withdrew the suggestion; no redundant cleanup was added.
- Runner baseline reads are guarded, and completed child returns are checked by exact sender set plus count two and distinct positive edge IDs, independent of order.
- Dual arity guards were accepted as protecting separate evaluator and callback binding boundaries.

See `dev/dogfood-reliability-run/review-run003.md` and the portable workflow receipts for the bounded review/execution record.

## What this run taught us

Artifact-first delegation produced the initial tests, but generic bounded-read reminders alone did not establish completion. Expanded payload coverage and real multi-agent evidence required concrete artifact checks and narrower followups. A source-envelope corruption demonstrated why a zero exit status is insufficient without nonzero test discovery and why an old passing receipt does not validate a later edit.

Incoming messages superseded several proposed dispatch/mutation batches. The lead checked actual coordinator edges, reports and disk artifacts before retrying absent prerequisites; neither proposed actions nor quiet board posts were treated as processed receipts. One explicit ordinary-program check-in future remained outstanding while worker requests were pending. Main and workers used subscriptions, quiet substantive posts, bounded digests and actual-page-token acknowledgements. These practices preserve completion-as-program, source-as-context, fresh self-call locals, replay safety and coordinator invariants; no runtime enforcement of task policy was added. Concrete friction was logged through the configured host feedback exception.

## Performance and dogfooding limits

This is deterministic offline correctness evidence, not a live-provider reliability or performance benchmark. The PF request tests use the compiled call closure and serialization boundary without a live coordinator lifecycle; the separate workflow exercises real coordinator/child lifecycles with deterministic providers. Live provider/model accuracy, paid-call latency and broader stress coverage remain outside these tests.

`elapsed_seconds` in the portable runner is subprocess wall time including JVM startup, tests, EDN writes and shutdown; it excludes subsequent baseline comparison and JSON writing. It is not API-only latency, CPU time, sampled heap/RSS or allocation measurement. No scoped host CPU/heap/JFR result was inspected for this handoff, so none is claimed. No 80-case baseline rerun or profiler was added.

The supplied prior shutdown observation (~95 CPU-seconds pretty-printing a 31 MB trace while model threads remained active) remains a useful follow-up opportunity, not a measurement or causal conclusion of this run. A separate bounded investigation should isolate trace serialization from lifecycle completion and model activity before changing either; this patch makes no trace-performance claim.

`perf/results/baseline.json` is preserved byte-for-byte, SHA-256 `54754377fd62516b5971715bcd334237733b07370e24fc83fa514d98a08837ce`. Shared paid-run accounting was not independently available to the lead; no paid subprocesses or verbose logging were introduced. No push, PR, branch switch, merge or automatic merge is part of this work.
