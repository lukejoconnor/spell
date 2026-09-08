# Module ownership and automatic dogfood journals

Scope: run `2026-09-07-module-ownership-002`, predecessor base `0078a4c746269a215770cb8b6c821fd97ecaf249`. This records intentional behavior changes; it is not a paid live-acceptance receipt.

## Immutable installation ownership

- **Previous behavior:** Modules had no recorded installer ownership; install receipts/catalog were ownerless and any agent could update by default.
- **Why it was a problem:** Agents sharing editable orchestration policy had no reliable place to route edit requests or distinguish an owner from an editor.
- **What changed:** The real registered winner of first installation owns the module for that run. Runtime metadata is outside editable definitions; repeated/concurrent installation preserves owner/source/state. Default updates expect caller ownership; explicit `{:owner recorded-owner}` permits deliberate nonowner edits without implying approval. Missing identities and uninstalled updates fail before transforms; update-from-nil is removed.

## Automatic bounded ownership guidance

- **Previous behavior:** A discarded install receipt could leave its installer unaware of its module responsibility.
- **Why it was a problem:** Helper-installed policy could have no informed coordination point; mailbox-based onboarding would risk unrelated message drainage or effect-receipt loss.
- **What changed:** A winner-only per-handle queue prepends inert guidance to the next generation without another model call or mailbox receive. Bounded pages retain overflow; no duplicate/wrong-agent onboarding. Dequeue is best-effort: provider failure after dequeue loses that page, and agents returning immediately see none.

## Local code-change journals in dogfood mode

- **Previous behavior:** `--dogfood` exposed human feedback but did not automatically preserve successful module source changes.
- **Why it was a problem:** Later registry reads could miss exact committed policy, editor identity and concurrent order; retries or general globals watches would record speculative/unrelated state.
- **What changed:** Dogfood records exact install baselines and unequal-definition PATTERNS API updates, with run identity, commit sequence/revision, owner/editor/explicit acknowledgment and exact before/after executable data. Reinstall/no-op/failed/speculative/direct-globals changes are unlogged. Append happens outside transforms; sequence reconstructs commit order. Off mode performs no journal disk setup/writes, and ordinary calls do not scan the registry.

## Honest post-commit recording failure

- **Previous behavior:** No automatic module journal existed, so there was no post-commit journal-failure contract.
- **Why it was a problem:** Treating a failed append as an ordinary failed edit would invite replay of a transform whose code was already live.
- **What changed:** The successful edit receipt carries additive failed journal status and `EDIT COMMITTED / RECORDING FAILED`, plus a bounded warning for the actual editor. Preserve its sequence; never replay the edit to repair logging. A recording gap is possible; no crash-atomic guarantee is claimed. The recording boundary also catches controlled `Error` subclasses, preserving the same committed receipt/warning if serialization or append raises one. Focused ownership/feedback coverage passed 31 tests / 282 assertions; injected `StackOverflowError` and `AssertionError` cases each preserve the callable edit with one transform and recording attempt. Actual resource exhaustion/process termination remains outside any guarantee.

## Model-facing guidance and prepared live pilot

- **Previous behavior:** Module/board docs allowed update-from-nil, called ownership only a convention without a default check, and the predecessor pilot let a peer edit without acknowledgment.
- **Why it was a problem:** Those instructions would misdirect current agents and could not demonstrate hidden-return onboarding, rejected default peer edits or actual-editor journaling.
- **What changed:** Module and board docs/skill describe immutable ownership, strict options, owner routing, exact journal scope and honest failure caveats. A complete affected-text before/after inventory is editable separately from source. The small two-agent ownership pilot supplies explicit deterministic setup and future real-model audits; its companion separates static checks from a parent's reserved paid run. The three base prompts, including the exact predecessor pruning-evidence antipattern, are preserved unchanged.

## Lexically safe notice placement during acceptance

- **Previous behavior:** The first implementation appended a notice comment at the model prefix's unfinished cursor.
- **Why it was a problem:** At an unfinished string or token, the appended text could alter executable data instead of remaining a comment.
- **What changed:** Prepend a complete escaped comment before the source, preserving the original prefix bytes. Acceptance regression runs passed 24 tests / 226 assertions focused on ownership and 102 tests / 978 assertions in adjacent coverage; these deterministic results do not replace the paid live acceptance evidence.
