# Reliability review and workflow receipt

Run: `2026-09-07-dogfood-reliability-003`, completing preserved run002 drafts; base `ac6d8da`.

## Executed review obligations

- Initial configured Fable design review: tracked edge 3. Conditional approval required provider-owned effective-model capability and matching TC request evidence; skill checkpoint suggestions remained ordinary program guidance.
- Fresh configured `workers/explore` / Fable 5.1 high, handle `:final-review003`: tracked edge 7 returned **APPROVE, no blocking defects**, after inspecting all 788 cumulative diff lines and `run.py`, `run.clj`, `README.md`.
- Final actual-delta re-review by the same fresh reviewer: tracked edge 11 returned **APPROVE, no blockers; all prior suggestions resolved or withdrawn**.
- Review was read-only/offline; test results were report evidence, not independently executed by Fable. Lead owns final approval and commits.

## Disposition of concrete findings

| Finding | Resolution |
| --- | --- |
| Capability ignored per-call model override | Provider-owned optional capability query now uses effective model; legacy protocol fallback retained; both failing-before override directions pass. |
| Analogous Anthropic TC mismatch risk | Actual-record request capture verifies both overrides; existing TC behavior needed no production change. |
| Preserve inherited false / reject incompatible true | Central compilation and inheritance corrected; serialized request capture covers root/worker policy and task content. |
| Early workflow failure cleanup | Existing `test/spell/test_helpers.clj:10-16` fixture unconditionally closes coordinator in `finally`; registration verified, suggestion withdrawn. |
| Test hid exception diagnostics | Acceptance test now allows unexpected compilation exceptions to propagate; final 8/26 pass. |
| Portable report leaked absolute paths | Relative command/artifact paths; raw subprocess/exception text omitted to avoid symlink/temp-path leakage. |
| Missing baseline should yield receipt | Guarded baseline reads emit path-free failed receipt and nonzero exit; sandbox verification passed without JVM launch. |
| Child-return order check | Exact sender set plus count two, completed bodies and distinct positive edge IDs; no ordering assumption. |
| Redundant-looking arity guards | Intentionally retained for separate catchable evaluator call/recur and callback/apply binding boundaries; reviewer accepted. |

## Final verification receipts

Lead ran these after the final followups:

- `clojure -M:test-fast`: **508 tests / 3105 assertions**, zero failures/errors.
- `clojure -M:test-slow`: **238 tests / 877 assertions**, zero failures/errors.
- `clojure -M:test -n spell.callable-arity-test -n spell.prefill-policy-test`: **14 tests / 152 assertions**, zero failures/errors.
- `python3 dev/dogfood-reliability-run/run.py`: exit 0 in a fresh JVM; **3 tests / 40 assertions**, zero failures/errors. `report.json`, `tests.edn` and `workflow.edn` are the portable execution receipts.

The workflow records actual pre-generation subscriptions, bounded digest with its real acknowledgement token, two distinct completed tracked child returns, retained source/local evidence and one executed self-call effect. It persists and reads back the completed report; test-only coordinator cleanup is explicit. Failure tests cover setup failure without task generation, task recovery outside setup catch, duplicate initialization, gaps and stale acknowledgements. The runner has nonzero discovery and complete-return gates, bounded timeout cleanup and no automatic retries/rescue.

An intermediate provider source-envelope corruption produced a misleading zero-test run. That result is excluded. The worker restored an executable namespace and subsequently ran 8 tests / 26 assertions successfully; the lead's final registered broad suites include them. Passing evidence always refers to an actual tested artifact revision, not a proposed edit.

## Live coordination record and limits

The lead initialized one in-run mailing-list board with `:design` and `:implementation`; configured workers subscribed before generation via setup-only catch. Substantive quiet posts were consumed with bounded digests and actual-page-token acknowledgements. Final review judgments were published as design messages 2, 3 and 5. Tracked completed requests include initial implementation, skill/test registration, portable artifacts, followups and final review (edges 1 through 11). Incoming messages sometimes superseded proposed effects; actual outgoing edges and disk receipts resolved uncertainty before retry. These are in-run receipts, not manufactured historical board state.

Baseline SHA-256: `54754377fd62516b5971715bcd334237733b07370e24fc83fa514d98a08837ce`; runner compares bytes before/after. Offline correctness and subprocess wall time do not establish live API latency, CPU, sampled heap/RSS or allocation performance. No host profiling result is claimed. See `DOGFOOD_CHANGELOG.md` for shutdown follow-up scope and dogfooding limitations.
