# Recovery continuation: bounded independent finding

> Historical finding: the quoted result-storage guidance below describes the earlier implementation, not the current API. Automatic retrieval was removed by [bounded snapshots](./bounded-results.md); see [current recovery guidance](./error-recovery.md). Original evidence and quotes are preserved.

## Scope and status

Investigation only on `codex/dogfood-reliability`. The initial investigation made no production edits or provider calls. Main subsequently approved only the recovery guidance correction below; no limit increase, progress heuristic, or accounting changes. This report is separate from the module work. Main accepted documented DEFER of recovery accounting and approved the exact retained-value/checkpoint/no-replay paragraph via request edge 8. Recovery semantics remain unchanged.

## Established source evidence

- `src/spell/llm.clj:48-67`: maximum recovery attempts is two; dynamic `*recovery-depth*` is shared by reader and evaluation recovery.
- `src/spell/llm.clj:174-201,204-238`: both recovery paths bind incremented depth around evaluation of the entire recovery continuation. Later self-calls run before that binding unwinds. Consequently distinct later mistakes can exhaust the budget even after useful effects and intervening self-calls. This is nested scope, not counter corruption.
- `src/spell/llm.clj:382-403`: the dangerous evaluator evaluates arbitrary trailing source synchronously. Entering `eval` alone cannot certify repair: the tail may immediately fail or make another failing self-call.
- Sibling run001 `failure-review.md`: oversized summary, unsupported `:read`, and bare `describe` consumed the shared nested limit. Successful intermediate operations do not imply the synchronous recovery continuation returned.

## Deterministic reproduction (verified)

Dedicated `test/spell/recovery_continuation_test.clj` scripts only TestProvider responses: fail A; commit receipt A and self-call; fail B; commit receipt B and self-call; fail C. Record provider-call depths and committed effects. Verified current behavior exhausts at depth two after five calls, with depths `[0 1 1 2 2]` and exactly two receipts, not replay prior effects. Also characterize uninterrupted evaluation/reader failures and mixed-phase exhaustion with three calls.

## Recommendation

Defer a production reset until an explicit recovery-episode boundary is specified and approved. There is no demonstrated safe implicit boundary here: parsing is not evaluation success; entering a tail or self-call is not repair; an arbitrary successful effect is not proof of progress. Resetting any of these can turn unresolved recursive retries into unlimited attempts. Reset after a recovery continuation actually returns is safe but does not address this report's nested continuation case. A more useful boundary would need an explicit source-directed episode completion contract, not a hidden host progress policy. Any proposal must preserve shared reader/eval budgeting while unresolved, canonical/inert recovery, dynamic scope, actual checkpoints, and no replay.

Proposed acceptance tests for an approved design: distinct acknowledged episodes can each recover; immediate eval-only, reader-only and mixed retries remain bounded at two; a retry that merely calls itself cannot evade the bound; received continuations preserve the same rule; effects execute once; literal checkpoints and stored IDs survive the chosen boundary; non-receiving helper calls do not silently certify repair.

## Approved guidance-only correction

`src/spell/llm.clj:69-72` previously said to restore long snippets by re-reading files. Main approved replacing that instruction independently of recovery accounting with this exact paragraph:

> Preserve the exact evidence, checkpoints, pending obligations, and actual effect receipts needed next. Prefer `(stored "ID")` with an ID actually observed in the previous program. Page a prior local value only after its needed pure binding has been re-established in the current program. Use `subs` for strings, `subvec` for line vectors, or the documented text/vector field for maps. Keep pages within the existing contribution cap and carry IDs plus next offsets as literal data. The previous program is inert context: its local bindings are not active. Reconstruct only the needed pure bindings or use retained stored references. Do not rerun an effect just to recover its output. Read the file again only when fresh contents are actually required, and identify the result as fresh evidence rather than the original receipt. If the original value cannot be retrieved, report missing evidence rather than claiming inspection or blindly refetching. Emit Spell code only.

This paragraph is the approved guidance-only production edit. The focused test file asserts the exact template and delivery through both reader and inert evaluation recovery. The initial characterization receipt is retained below; post-edit verification follows the before/after block.

## Initial characterization verification receipt (before guidance edit)

Command, from `/private/tmp/spell-dogfood-reliability`:

```sh
clojure -M:test -n spell.recovery-continuation-test
```

Exit **0**; **3 tests, 15 assertions, 0 failures, 0 errors**. TestProvider only; no paid subprocess/model runs and no full suites.

| Scenario | Observed provider-call recovery depths | Outcome / actual effects |
| --- | --- | --- |
| Three distinct later mistakes | `[0 1 1 2 2]` | Eval recovery exhausted at two; exactly `{:receipt :a}` and `{:receipt :b}` recorded |
| Successful recovery continuation | `[0 1 1]` | Returned `{:receipt :a}` checkpoint; that effect recorded exactly once |
| Unresolved eval-only errors | `[0 1 2]` | Eval exhaustion; no effects |
| Unresolved reader-only errors | `[0 1 2]` | Reader exhaustion; no effects |
| Reader then eval errors | `[0 1 2]` | Shared eval exhaustion; no effects |

These are characterization tests of current semantics, not tests of an implemented reset. The dedicated fixture initially had an extra closing parenthesis, then a symbol rather than keyword namespace function key; both fixture mistakes were corrected before the passing run. No production files were changed. Stored-ID survival and receiving/non-receiving boundary cases remain proposed acceptance coverage, not claims established by this test.

**Decision accepted by main:** defer recovery accounting in this module change; apply the separate prompt correction only. A reset after the entire synchronous recovery continuation returns preserves current safety but cannot help later calls still nested inside it; a useful earlier reset requires an approved source-visible episode boundary rather than an inferred host progress rule.

## Exact model-facing before/after

Only the long-snippet advice changes; the task/context reconstruction and avoid-repeat instructions remain. These are the actual string contents (not Clojure escaping).

### Before

```text
The previous Spell program threw an error. The previous program is visible during this recovery turn, but it will be pruned afterward, such that you will not see it on your next turn.

Emit a `(quine task "...")` form describing the original task, followed by a (quine context-summary "...") form describing history, progress, and any context which should be retained on your next turn. If there are long file snippets which should be retained, restore these by re-reading from those files in your trailing expression. Emit Spell code only, not prose. Avoid repeating your previous error.
```

### After

```text
The previous Spell program threw an error. The previous program is visible during this recovery turn, but it will be pruned afterward, such that you will not see it on your next turn.

Emit a `(quine task "...")` form describing the original task, followed by a (quine context-summary "...") form describing history, progress, and any context which should be retained on your next turn. Preserve the exact evidence, checkpoints, pending obligations, and actual effect receipts needed next. Prefer `(stored "ID")` with an ID actually observed in the previous program. Page a prior local value only after its needed pure binding has been re-established in the current program. Use `subs` for strings, `subvec` for line vectors, or the documented text/vector field for maps. Keep pages within the existing contribution cap and carry IDs plus next offsets as literal data. The previous program is inert context: its local bindings are not active. Reconstruct only the needed pure bindings or use retained stored references. Do not rerun an effect just to recover its output. Read the file again only when fresh contents are actually required, and identify the result as fresh evidence rather than the original receipt. If the original value cannot be retrieved, report missing evidence rather than claiming inspection or blindly refetching. Emit Spell code only. Avoid repeating your previous error.
```

## Post-edit verification

Fresh-JVM command from `/private/tmp/spell-dogfood-reliability`:

```sh
clojure -M:test -n spell.recovery-continuation-test
```

Exit **0**; **5 tests, 40 assertions, 0 failures, 0 errors**. TestProvider only. The exact prompt template and its delivery through reader and inert evaluation recovery are asserted; all earlier accounting/checkpoint/no-replay characterizations still pass unchanged. Initial delivery assertions captured the prefill provider's user-message stub, so those two cases were corrected to non-prefill transport before this passing run.

`git diff --check -- src/spell/llm.clj test/spell/recovery_continuation_test.clj docs/recovery-continuation-finding.md` also exited **0**. Inspected production diff is exactly one replaced string line at `src/spell/llm.clj:72` (one insertion, one deletion). No depth, limit, reader/eval control flow, or other production source was changed. No full suites, paid calls, or commits were performed.

Approval refinement: main relayed Fable edge 9 approval before any patch executed. The applied paragraph prefers observed stored IDs and restricts local-value paging to pure bindings re-established in the current program. The pruned-afterward preamble and task/context-summary quine scaffolding are preserved verbatim.
