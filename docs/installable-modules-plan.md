# Installable modules: approved disposition and validation plan

Status: **approved design; implementation and verification tracked separately**. Design-board messages 8 and 9 settled the contract. The documentation worker owns public docs/skills/examples and the root live artifact/companion; the validation worker owns tests/deps aliases. Production host and bundle code belong to their assigned workers. Main owns commits. Preparing artifacts is not execution evidence.

## Approved contract

Exactly five public verbs: `patterns/install`, `patterns/catalog`, `patterns/source`, `patterns/update`, `patterns/call`. See the [complete API and migration guide](./installable-modules.md) for signatures, receipt shapes, and editable source examples.

Definitions have the schema `{:doc string :functions {:key {:doc string :requires [namespace-symbol ...] :source (fn [params] body...)}}}` and live in run-local globals `:modules`, separate from mutable application state. Install is atomically only-if-absent; repeat and concurrent installs preserve edits and state. Update is a pure transform to the **next whole definition**, not `[definition result]`. Source returns the complete installed definition or complete function entry. Catalog includes docs/derived parameters/requirements, never bodies. Call snapshots the selected source and applies it in caller dynamic scope; nested calls resolve latest source independently. Namespace requirements grant no capabilities. No compatibility wrappers or production recovery-policy change.

## Final family disposition

This table supersedes the earlier proposal to delete team/fix-loop. **Only clean-prompt is deleted.**

| Family | Decision | Utility rationale | New public use |
| --- | --- | --- | --- |
| check-result | Keep as opt-in bundle | Explicit judge for checking an answer against its task. | install `:check-result`; call `:run` |
| ralph | Keep as opt-in bundle | Persistent retry worker with editable retry policy. | install `:ralph`; call `:run` |
| team | Keep as opt-in bundle | Editable decomposition, dependency scheduling, and review orchestration. | install `:team`; call `:run` |
| fix-loop | Keep as opt-in bundle | Executable test-feedback repair loop. | install `:fix-loop`; call `:run` |
| relay | Keep as opt-in bundle | Context-separated reasoning and independent verification. | install `:relay`; call `:run` |
| mailing-list/mail | Keep as one opt-in bundle | Quiet shared evidence and explicit coordination without waking every worker. | `:mailing-list` exports `:init/:call/:change/:digest/:deliver` |
| clean-prompt | Delete | Avoid a low-value implicit cleanup-and-execution wrapper; make those steps deliberate. | No shim; use explicit task-appropriate cleanup/execution |

Retained bundles are source-programmed, opt-in policies—not host rules. Their utility does not require privileged host entry points; callers can inspect and edit their actual Spell source.

Board state remains in `:mailing-list`; there is no private executable `:code` registry. Every agent may install/reuse the module. Exactly one administrator explicitly initializes board state; duplicate initialization still errors. Ordinary operations use `(patterns/call :mailing-list :call operation args)`.

## Validation responsibilities

- Host checks: five-verb discovery, schema validation, compact catalogs/receipts, complete executable source, custom modules, atomic install/update, caller dynamic scope, arity/recur, capability gates, opaque/lazy arguments, in-flight snapshot and latest nested call semantics.
- Bundle checks: six loadable definitions and documented exports, preserved orchestration behavior, shared-state separation, exact successful board transaction results, duplicate explicit init error, tracked onboarding/notifications, no private code table or removed wrappers.
- Documentation checks: executable Spell syntax, one-indexed half-open `io/read-lines` examples with local zero-indexed `subvec`, no active old API instructions, preserved predecessor pruning-evidence antipattern, navigable before/after model-facing inventory.
- Deterministic focused command: `clojure -M:test -n spell.installable-modules-test`. Broader aliases and actual test receipts belong to the validation worker; do not infer a pass from this command's presence.
- Live acceptance: root [artifact and companion](../INSTALLABLE_MODULES_LIVE_ACCEPTANCE.md), prepared for a **fresh runtime** with two actual agents. No paid subprocess is authorized during preparation. Parse/static checks are not live acceptance.

## Review evidence

The [MODEL-FACING CHANGE INVENTORY](./installable-modules-change-inventory.md) links complete editable changed text, generated catalog/onboarding source, and the committed [recovery continuation finding](./recovery-continuation-finding.md). Preserve exact evidence/receipts before pruning; do not prune evidence and rediscover it. The old JVM used for coordination still has the previous pattern API and cannot certify the new one.
