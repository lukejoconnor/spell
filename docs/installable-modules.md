# Installable pattern modules

Pattern policy is editable Spell source, installed explicitly into the current run. The public host API has exactly five verbs: `patterns/install`, `patterns/catalog`, `patterns/source`, `patterns/update`, and `patterns/call`. There are no compatibility wrappers for the previous named pattern functions.

## Disposition

| Previous entry point | Disposition | Installed module / function |
| --- | --- | --- |
| `patterns/check-result` | Retained as an opt-in bundle | `:check-result :run` |
| `patterns/ralph` | Retained as an opt-in bundle | `:ralph :run` |
| `patterns/team` | Retained as an opt-in bundle | `:team :run` |
| `patterns/fix-loop` | Retained as an opt-in bundle | `:fix-loop :run` |
| `patterns/relay` | Retained as an opt-in bundle | `:relay :run` |
| `patterns/mailing-list`, `patterns/mail` | Retained as one opt-in bundle | `:mailing-list` exports `:init`, `:call`, `:change`, `:digest`, `:deliver` |
| `patterns/clean-prompt` | Deleted, not renamed or shimmed | Write deliberate cleanup/execution steps if needed |

## Discover, install, call

These are successive quoted trailing expressions in the normal completion wrapper, not one multi-action completion:

```clojure
'(!call-now available (patterns/catalog))
'(!call-now installed (patterns/install :check-result))
'(!call-now contract (patterns/catalog :check-result))
'(!call-now verdict (patterns/call :check-result :run "What is 6 * 9?" 54))
```

Bundled definitions are classpath resources under `modules/`, sourced from `config/spl-lib` in a checkout and included in packaged builds. The same loader works from a JAR outside the checkout. Installation copies the selected definition into the run-local registry.

`catalog` lists compact bundled/custom metadata; a module-specific catalog includes docs, derived `:params`, and `:requires`, never function bodies. Installed status distinguishes a bundled definition available for installation from one already in this run. Discovery and ordinary calls do not require inserting the executable body into the next model prefix.

| Operation | Contract |
| --- | --- |
| `(patterns/install module-key)` | Install bundled definition only if absent. |
| `(patterns/install module-key definition)` | Install a custom definition only if absent. |
| `(patterns/catalog)` / `(patterns/catalog module-key)` | Compact discovery; unknown module-specific lookup returns nil. |
| `(patterns/source module-key)` | Complete installed module definition, or nil. |
| `(patterns/source module-key function-key)` | Complete installed function entry (doc, requires, executable source), or nil. |
| `(patterns/update module-key pure-transform & args)` | Atomically validate/store the transform's next whole definition. A transform can create a valid definition from nil. |
| `(patterns/call module-key function-key & args)` | Execute the selected current source in the caller's dynamic scope and capabilities. |

Install receipts are `{:module k :installed? boolean :fns sorted-vector}`. Repeated or concurrent install preserves the winning definition, subsequent edits, and separate state. Update receipts are `{:module k :fns sorted-vector}` from the successful commit; the transform returns the next definition, **not** `[definition result]`.

## Source is editable data

Module definitions live in run-local globals under `:modules`. Mutable application state lives separately; the mailing-list board uses `:mailing-list`. Installing code is not initializing that state.

```clojure
(def greeting-module
  '{:doc "A small shared greeting module."
    :functions
    {:run {:doc "Greet one name." :requires []
           :source (fn [name] (str "Hello, " name))}}})
'(!call-now installed (patterns/install :greeting greeting-module))
;; next turn:
'(!call-now before (patterns/call :greeting :run "Ada"))
;; Fetch source because we intend to EDIT it, not merely to invoke it:
'(!call-now entry (patterns/source :greeting :run))
;; next turn: transform the entire definition atomically, preserving other entries.
'(!call-now edited
   (patterns/update :greeting assoc-in [:functions :run :source]
                    '(fn [name] (str "Welcome, " name))))
;; next turn:
'(!call-now after (patterns/call :greeting :run "Ada"))
```

The schema is `{:doc string :functions {:key {:doc string :requires [namespace-symbol ...] :source (fn [params] body...)}}}`. Quote definitions/source so they remain executable source data rather than prematurely evaluated function objects. `source` returns the actual complete stored definition/entry, not a summary or independent documentation copy. Use `assoc-in`, `update-in`, and `dissoc` in pure transforms to add, edit, or remove entries. Pass an evaluated function/builtin value as the update transform, e.g. `(fn [definition] ...)` or `assoc-in`; unlike entry `:source`, do not quote the transform. The entry schema accepts single-arity `(fn [params] body...)` source, with normal evaluator support for variadic parameters and destructuring. A retryable update transform must not send messages, spawn agents, call providers, or mutate application state. Purity is a caller obligation, not an effect sandbox enforced by the module host.

Functions retain Spell's **dynamic scope**, not lexical closures. An in-flight call snapshots its selected body; a nested `patterns/call` independently selects the latest entry. `:requires` prechecks listed namespace availability; it neither grants capabilities nor proves that the list contains every dependency. Enable needed namespaces in each participating agent profile; `patterns` itself never grants `io`, `agents`, or other permissions.

## Mailing-list migration

Every participant may safely install/reuse the module. Exactly one administrator explicitly initializes the board:

```clojure
'(!call-now installed (patterns/install :mailing-list))
;; Administrator only, once per run:
'(!call-now board (patterns/call :mailing-list :init {:retention 200 :page-size 20}))
;; Existing workers never initialize again:
'(!call-now subscribed
   (patterns/call :mailing-list :call :subscribe-many
                  {:lists [:design :implementation] :from :earliest}))
```

Lists must already have been created by the administrator. Replace `(patterns/mail operation args)` with `(patterns/call :mailing-list :call operation args)`. The board has no private `:code` registry. Inspect/edit its general module entries through `source`/`update`; do not put executable source back into board state. Duplicate explicit `:init` remains an error. See [mailing-list operations](./mailing-list.md) and the skill (`resources/skills/mailing-list/SKILL.md`).

## Evidence and bounded context

Preserve the predecessor pruning-evidence rule: **do not prune supporting evidence and then rediscover it**. Before `!peek` results disappear, `persist` the exact needed source slice, observed stored ID/offset, checkpoint, and actual effect receipts. Plans, source forms, and sent flags are not execution receipts. Incoming messages may replace an unexecuted trailing action.

`io/read-lines` ranges are one-indexed and half-open; `subvec` indexes are local, zero-indexed and half-open. For example, reading file lines `[181,221)` yields 40 lines. `(subvec lines 0 10)` keeps original lines 181–190, not lines 0–9 of the file. Retrieve a retained/stored value before repeating the original effect. An opaque marker is not evidence that its hidden body was inspected.

For model-facing recovery/continuation guidance, see the committed [recovery continuation finding](./recovery-continuation-finding.md). This migration does not introduce a new production recovery policy.

## Review and acceptance

See the [model-facing change inventory](./installable-modules-change-inventory.md), [approved disposition/validation plan](./installable-modules-plan.md), and root live acceptance companion (`INSTALLABLE_MODULES_LIVE_ACCEPTANCE.md`). A running JVM compiled before the migration still exposes the old API; only a fresh runtime can validate the new implementation. Preparing or parsing the live artifact is not a paid-run success claim.
