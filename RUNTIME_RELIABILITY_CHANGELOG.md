# Runtime reliability changes

Base: `2a589c58ec4e3c06d492edd5f44fc522952980ff`. This batch implements TODOs 286–289. Final review, runtime receipts, source hashes and editable teaching are retained in the notebook's `2026-09-07-runtime-reliability-001` run bundle. This document describes the change; it is not a substitute for acceptance evidence.

## Digest diagnostics — TODO288

**Previous behavior:** A missing, malformed or plural list argument could reach subscription lookup and report that an already subscribed agent needed to subscribe.

**Why it was a problem:** The error described the wrong remedy and made agents repeat subscription operations.

**What changed:** Validate the argument map and required singular keyword `:list` first. Distinguish unknown lists from absent subscriptions and include the actual list plus actionable syntax. Plural `:lists` is rejected. Both the public facade and pure helper preserve the entire board and subscriptions on errors. Bootstrap guidance and the mailing-list skill teach the actual per-list call.

**Validation and rollback:** Focused coverage exercises 42 error paths across two lists/readers and nonzero cursors. Reverting this change restores misleading diagnostics; it does not change the board architecture or acknowledgement contract.

## Consecutive-error recovery — TODO289

**Previous behavior:** Two nested recovery retries made the third error fatal, even when many successful model turns occurred between errors. The limit was hard-coded, and a provider failure could be treated as the parent's own evaluation failure.

**Why it was a problem:** Repaired mistakes accumulated across long nested executions and could terminate otherwise productive work. Users could not configure the policy per agent.

**What changed:** `:max-consecutive-errors` is a positive integer in agent configuration, default 3, with an optional root API override. The Nth consecutive own reader/evaluation failure terminates the lifecycle. A successful model completion or validated normal handoff resets before nested execution; an ancestor cannot reset again on unwind. Successful model-authored repair counts as success; synthetic host recovery dispatch and deterministic namespace repair do not. Accepted ordinary/external waits reset, refused or invalid waits do not. Provider failures and propagated descendant errors are not additional own model failures. State is isolated across agents, runs and revived lifecycle generations. Old dynamic retry-depth accounting is removed.

**Validation and rollback:** Focused tests cover consecutive reader/evaluation/mixed failures, intervening success, parent unwind, accepted/refused waits, provider failures, real profile inheritance/overrides, run isolation and preservation of completed effects. Runtime accounting, config plumbing, tests and teaching should be reverted together; restoring the old policy would restore the long-run failure demonstrated by this batch.

## Compaction receipt — TODO286

**Previous behavior:** Messages arriving during summary generation could replace the generated compacted-context handoff with a continuation of the old context.

**Why it was a problem:** The model paid for a summary yet retained the old context. The prior audit observed seven such losses.

**What changed:** Only the summary-generation call B uses `{:receive? false}`. Its compacted continuation C uses `{:receive? true}`. Ordinary turn A remains receiving/preemptible. Existing message receipt handles the queued messages after replacement; no separate scheduler is added.

**Validation and rollback:** Deterministic tests assert the exact compacted prefix and exactly-once queued request receipt, including arrivals during B/C and allowed preemption during A. The full-suite pass required updating an older expansion assertion to expect B=false while retaining C=true. Reverting this macro boundary restores the handoff-loss behavior.

## Interactive Ctrl+C — TODO287

**Previous behavior:** Ctrl+C cancelled text entry and continued the terminal. Interrupting an active request could leave the caller waiting for provider completion; pure evaluation did not observe interruption.

**Why it was a problem:** The requested exit did not happen promptly, and terminal settings or active work could remain unsettled.

**What changed:** Ctrl+C ends the interactive run with exit status 130. A typed run marker bypasses model recovery; the evaluator cooperatively checks it during pure computation. The in-flight provider future is retained and cancelled, and trace completion does not block on an undelivered result. Cleanup disarms further interrupts, restores terminal settings, and preserves the primary interruption when cleanup also fails. JNI terminal restoration preserves native settings omitted by JLine's attributes representation. Failed terminal setup closes/restores resources while retaining the originating exception.

**Validation and rollback:** Actual PTY tests cover idle input, provider wait, pure loop and function recursion with both Ctrl+C bytes and SIGINT; all eight exit promptly, restore native settings and clean up. Pure-loop failures and earlier restoration failures are retained as baseline evidence. Other terminal backends retain their existing JLine restoration path; they were not all exercised on this macOS host. Revert terminal lifecycle, cancellation and cooperative evaluation pieces together.

## Teaching and dogfooding evidence

**Previous behavior:** Error-recovery documentation retained an obsolete instruction to reread lost evidence and described the old fixed retry budget. Failed agent reports could also be mistaken for completed final review.

**Why it was a problem:** Documentation contradicted current evidence-preservation guidance, and unexecuted review/test proposals were insufficient acceptance evidence.

**What changed:** Documentation now matches the existing recovery prompt and the new counter semantics; the three base system prompts remain unchanged. The saved run audit records actual design/concrete review deliveries, seven failed child-request receipts and the lead's failed final-review lookup. Codex continues acceptance from saved source, requiring separate fresh review and live-pilot evidence before final acceptance. Each acceptance artifact distinguishes scripted setup, model-authored behavior and observed effects.
