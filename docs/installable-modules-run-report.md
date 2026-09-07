# Installable pattern modules — run 002

Run: `2026-09-07-installable-pattern-modules-002` · branch: `codex/dogfood-reliability` · base: `0e3a9561471afb328c953d61c5d641951ece8858`.

## Delivered design and scope

Five effect verbs replace the always-loaded wrapper library: `patterns/install`, `catalog`, `source`, `update`, and `call`. Definitions live in the shared, run-local globals `:modules` store. Install is atomic if-absent and preserves edits/state on reuse. Catalog returns body-free metadata; source returns the complete executable definition or selected entry. Update evaluates a pure transform to the next definition and returns the exact committed definition (not a `[definition result]` protocol). Call uses ordinary evaluator semantics, fresh function/argument locals, dynamic scope, arity/recur and opaque values. An in-flight call retains its selected function; a nested call sees the latest module. Requirements are prechecks, not capability grants. This is not a sandbox, policy/cache/CAS framework, or wrapper compatibility layer.

Six editable bundles replace the deleted `config/spl-lib/patterns.spl`: check-result (explicit judging), ralph (persistent retry), team (editable decomposition/dependencies/review), fix-loop (test-feedback repair), relay (context-separated reasoning/verifier), and mailing-list (quiet coordination with evidence). The first five expose `:run`; mailing-list exposes `:init`, `:call`, `:change`, `:digest`, and `:deliver`. `clean-prompt` was deliberately removed: implicit cleanup/execution did not justify a separate retained bundle. These are opt-in source policies, not additional host rules.

Mailing-list state is separate from module definitions; duplicate board initialization remains an explicit error. Pure board change returns `[next-board result]`, with exact committed `:last-result`; sends and spawns occur outside transactions. Onboarding installs/reuses and subscribes before receiving generation. Metadata uses `:module-functions`, not misleading `:operations`; no private `:code` cache remains. The already-running coordination JVM kept its old API and board throughout; source edits did not hot-reload it and the board was not reinitialized.

## Artifacts and model-facing inventory

- Host: [patterns.clj](../src/spell/patterns.clj), [stdlib.clj](../src/spell/stdlib.clj); editable source: [six module files](../config/spl-lib/modules/).
- [Design/API guide](installable-modules.md), [plan and disposition](installable-modules-plan.md), and [change inventory](installable-modules-change-inventory.md). The inventory supplies baseline/diff recipes and full editable-source links/locators for the before/after model-facing surface, including generated namespace prompts, catalog, onboarding, and recovery. It is navigation to complete source, not a claim that catalog contains bodies.
- Public README, root/config AGENTS, API/capabilities/mailing-list docs, VitePress navigation, mailing-list skill and both repository spell-developer skill copies were migrated. Predecessor evidence/pruning guidance was preserved.
- [Example program](../examples/installable-modules.spl) and [companion](../examples/installable-modules.md); [live acceptance program](../INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl) and [companion](../INSTALLABLE_MODULES_LIVE_ACCEPTANCE.md).
- Tests cover the module host, loading, retained patterns, mailing-list, and maintained agent/LLM/workflow fixtures; aliases include the new namespace. Main coordinated/reviewed and wrote this report; implementation workers owned production and test edits. Main owns commits. No library git/shell automation was added; relevant tests use temporary repositories/mocks.

## Verification receipts

| Check | Actual result |
| --- | --- |
| Recovery-continuation regression | Fresh JVM: 5 tests, 40 assertions, 0 failures/errors; diff check passed. |
| Expanded module/loader/patterns/mailing-list namespaces | `clojure -M:test -n spell.installable-modules-test -n spell.patterns-loader-test -n spell.patterns-test -n spell.mailing-list-test`: 57 tests, 511 assertions, 0 failures/errors, exit 0. Not rerun after this pass. |
| Initial maintained fast alias | 532 tests, 5,078 assertions, 4 failures, 2 errors, exit 1. Stale recovery-prompt/check-result and compile-time dependency fixtures identified; not described as passing. |
| Initial maintained slow alias | 287 tests, 1,244 assertions, 0 failures, 4 errors, exit 1. Legacy mailing-list/workflow/onboarding API fixtures identified. |
| Final affected namespaces | `clojure -M:test -n spell.llm-test -n spell.agent-test -n spell.reliability-workflow-test`: 110 tests, 819 assertions, 0 failures/errors, exit 0. Log: `/tmp/installable-modules-final-affected-fixed.log`. |
| Final fast alias | `clojure -M:test-fast`: 532 tests, 5,078 assertions, 0 failures/errors, exit 0. Log: `/tmp/installable-modules-final-fast.log`. |
| Final slow alias | `clojure -M:test-slow`: 287 tests, 1,280 assertions, 0 failures/errors, exit 0. Log: `/tmp/installable-modules-final-slow.log`. Each previously failed alias was rerun once after corrections. |
| Scripted live artifact | Fresh TestProvider execution, exit 0, three responses. Root values `[11 12]`; actual peer values `[11 12 12]`; repeat install false; source entry keys `[:doc :requires :source]`; peer audit and opaque-prefix guards true. Peer fetched source once to edit, not again merely to call. |
| Docs/source checks | Worker receipts: source check, diff check, and CLI help exit 0. Docs build exit 127: VitePress missing; not a passing build. |

Expanded focused tests positively cover invalid definition/install/update rollback; concurrent install/update receipts and state; missing functions/capabilities; fixed arity; selected executable source; opaque/lazy value identity; public API run isolation; overlapping in-flight frames with latest nested lookup; and actual-agent install/reuse/edit/call/catalog behavior. Earlier 10-test/31-assertion host checks and 15 independent probes were preliminary, not substitutes for final verification. Intermediate test-development failures (wrong prefix capture and an unsupported speculative variadic fixture) were corrected before the focused pass; retained coverage uses approved fixed-arity behavior.

Synthetic fresh-JVM sanity only: install 4.426917 ms; 10,000 warmed calls 100.615917 ms total; 1,000 repeat installs 2.99775 ms total. No baseline comparison, production latency claim, or whole-agent improvement is established.

## Review, corrections, and explicit limits

Fable design and host reviews approved the five verbs and complete host source. Integration review inspected selected evaluator/API isolation paths and the initial complete module/loader/board tests. Main read the host, complete mailing-list bundle, current inventory, expanded coverage, and migration diffs. Final independent review read the complete current 201-line host, complete 45-line live program, actual-agent test, and bounded team/relay helper sections. It approved that scope with no required changes. It did **not** review every remaining team/relay/check-result/ralph/fix-loop/mailing-list body in that final pass, run tests/shell, or perform paid live execution. A relay helper naming nit was accepted without churn.

An earlier lazy-map allegation was explicitly withdrawn after checking Spell's eager map implementation and actual mailing-list validation/delivery tests; no speculative production change followed. Two failed review retrieval attempts (map passed to `subs`, board summary exceeding 500 characters) were not counted as reviews. Validation lifecycle edge 29 exhausted the unchanged recovery limit after an out-of-range `subvec`; a subsequent actual checkpoint and worktree diff recovered which edits executed. Failure envelopes and intentions were not treated as test receipts. No concrete module-host production defect was established by the initial maintained-suite failures.

Recovery change is separately committed as `cffd293`: blanket reread advice became guidance to preserve observed stored IDs, exact evidence/checkpoints/receipts, reestablish pure bindings, and avoid effect replay. Existing preamble/quine scaffolding and pruning evidence instructions remain. **Not fixed:** recovery-depth accounting still dynamically spans the continuation, including later successes; there was no proven safe repair boundary. Shared reader/eval depth remains 2, with no limit increase or speculative progress policy. Harmless println noise/template duplication was accepted.

## Cost and handoff

Observed usage snapshot before final commits: run 002 **$162.183154** across 652 calls (Astra $143.848464; Fable $18.334690). This is a snapshot, not the final bill. Prior run 001: **$18.03084425**. Incurred aggregate at that snapshot: **$180.21399825**, leaving **$19.78600175** before further finalization. The originally separate **$20** Codex acceptance reserve is not incurred spend and no longer fits in full beneath $200: projected total with that reserve is **$200.21399825**. The nominal main cap of $171 is therefore insufficient to enforce the aggregate ceiling. Gate any later paid acceptance on final usage reconciliation and a reduced available budget; do not assume the full reserve remains. No paid acceptance was launched here.

No paid subprocess Spell run, notebook/home/global-skill edit, branch/reset/push/merge/PR was performed by this finalization. Codex retains independent fresh-runtime/live acceptance, notebook reconciliation and the final user report. Proposed paid command (not executed here):

```sh
bin/spell --init-file INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl -a config/agent-profiles/io-tc.agent.edn -m openai-tc:gpt-6-astra -R medium -b 1.00 -d 8 -M 1600 --context-max-chars 10000
```

Provider credentials, billing, and real-provider acceptance remain unverified. Do not reuse the old coordination JVM to accept the new module API. Final migration receipts, commit IDs, and final observed usage follow in the completion handoff.
