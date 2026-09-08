# Installable pattern modules

Pattern policy is editable Spell source, installed explicitly into the current run. The public host API has exactly five verbs: `patterns/install`, `patterns/catalog`, `patterns/source`, `patterns/update`, and `patterns/call`. There are no compatibility wrappers for the previous named pattern functions.

## Disposition

The bundled library contains **relay** and **mailing-list**. `check-result`, `ralph`, `fix-loop`, and `team` are retired, not aliases or hidden compatibility modules. Their policy-specific tests and generated teaching are removed; independent evaluator/runtime, shell-test, ownership/journal, and reliability regressions remain. `clean-prompt` remains deleted.

Relay provides fresh-context reasoning rounds, structured handoffs, and a separate verifier; it is not a correctness guarantee. See [the runnable example](https://github.com/lukejoconnor/spell/blob/main/examples/relay.md). For repository work, compose ordinary shell steps and configured tracked agents explicitly; see [caller-owned work](https://github.com/lukejoconnor/spell/blob/main/examples/caller-owned-work.md). There is no replacement generic git framework or automatic cleanup.

## Discover, install, call

These are successive quoted trailing expressions in the normal completion wrapper, not one multi-action completion:

```clojure
'(!call-now available (patterns/catalog))
'(!call-now installed (patterns/install :relay))
'(!call-now contract (patterns/catalog :relay))
'(!call-now verdict (patterns/call :relay :run {:problem "What is 6 * 9? Explain and check." :max-rounds 2}))
```

Discovery selects `<worktree-root>/.spell/modules/<name>.spl`, then `$HOME/.spell/modules/<name>.spl`, then the bundle. The worktree root (including linked worktrees) is captured once per run; outside git it is the startup cwd. Children share that root. File names are flat lowercase-kebab names; programmatic keyword IDs are not restricted to that filename grammar. The selected origin is `{:kind :project|:user|:bundle :path canonical-path-or-resource-URL}`; explicit custom definitions have nil origin. An invalid selected file reports that path and errors **without fallback**.

Bundled definitions are classpath resources under `modules/`, sourced from `config/spl-lib` and included in packaged builds. Each file contains one ordinary unquoted definition map with inert `(fn ...)` source data. Loading/cataloging/installing does not execute its function bodies. Installation copies the selected definition into the run-local registry; repeat install preserves that source, its immutable owner/origin, revisions, and separate state, rather than reloading disk.

To keep an edit across runs, deliberately save the complete installed definition as ordinary source; there is no autosave or watched reload. Enable `io` and choose the destination yourself:

```clojure
;; Successive actions; use an absolute path if cwd is not the selected worktree root.
'(!call-now directory (io/mkdirs ".spell/modules"))
'(!call-now saved (io/write-file ".spell/modules/greeting.spl"
                    (str (pr-str (patterns/source :greeting)) "\n")))
```

A **fresh run** discovers/installs the saved file. Saving code does not persist board state, coordinator identities, pending requests, module ownership metadata, or a live journal; the next run has a new installer/owner and install baseline. See [user-module source and reload](https://github.com/lukejoconnor/spell/blob/main/examples/user-modules.md).

`catalog` lists compact discovered/installed metadata, including selected `:origin`; a module-specific catalog includes docs, derived `:params`, and `:requires`, never function bodies. Installed status distinguishes a bundled definition available for installation from one already in this run. Discovery and ordinary calls do not require inserting the executable body into the next model prefix.

| Operation | Contract |
| --- | --- |
| `(patterns/install module-key)` | Install selected project/user/bundle definition only if absent. |
| `(patterns/install module-key definition)` | Install a custom definition only if absent. |
| `(patterns/catalog)` / `(patterns/catalog module-key)` | Compact discovery; unknown module-specific lookup returns nil. |
| `(patterns/source module-key)` | Complete installed module definition, or nil. |
| `(patterns/source module-key function-key)` | Complete installed function entry (doc, requires, executable source), or nil. |
| `(patterns/update module-key pure-transform & args)` | Require installation and caller ownership; atomically validate/store the next whole definition. |
| `(patterns/update module-key {:owner recorded-owner} pure-transform & args)` | Deliberately acknowledge the immutable recorded owner; actual editor remains the caller. |
| `(patterns/call module-key function-key & args)` | Execute the selected current source in the caller's dynamic scope and capabilities. |

Install receipts include `:module`, `:installed?`, `:owner` and sorted `:fns`; catalog exposes the installed module's actual `:owner` too. Repeated or concurrent install preserves the winning definition, subsequent edits, and separate state. Update receipts include `:module`, recorded `:owner`, actual `:editor`, revision/sequence and sorted `:fns` from the successful commit; the transform returns the next definition, **not** `[definition result]`. Dogfood journal status is additive; see below.

## Ownership and deliberate edits

Every installed module has one immutable owner: the real registered agent that wins its first successful installation, using `agents/current-handle` identity. There is no installer option and no transfer operation; delegate installation if another agent should own the module. Install without a registered current handle fails before touching the store. Repeat/concurrent installs expose the winner's owner and preserve its definition, edits, metadata and separate application state.

Ownership/revision metadata lives outside the transformable definition, beside it in run-local state. Adding an `:owner` field to source data cannot transfer ownership. The default `(patterns/update module transform & args)` expects the actual caller to be the recorded owner. For a deliberate nonowner edit, use `(patterns/update module {:owner recorded-owner} transform & args)`. This acknowledges the recorded owner; it **does not claim the owner approved**. The actual editor remains the caller. Message the owner to coordinate changes rather than guessing ownership; `patterns/catalog` exposes the recorded handle.

The options map is exactly `{:owner keyword}`. Nil, false, missing/extra keys and wrong owners do not bypass checks. Options belong before the transform, not at the end; ordinary transform arguments remain ordinary arguments. Update requires prior installation and a registered caller. On every atomic retry, the host checks caller identity, installation and owner expectation against that retry's snapshot **before invoking the transform**. A rejected update never invokes it. The transform receives the whole current definition and returns the next whole definition, which is validated before commit. It may run more than once, so remain pure. Receipt metadata comes from the exact successful commit, not a later registry read.

### First-installer guidance

The winning installer receives automatic bounded guidance in its **next model generation**, even if a helper discards the install receipt: it owns the module, should expect edit requests and coordinate changes, and can use `patterns/update`. This is inert prefix context, not an extra model call, mailbox message, receive operation or replacement of the current effect receipts. Repeat/losing installs do not onboard another agent. A compact page names exact module identifiers; overflow reports how many remain queued and leaves them for later generations. Pages are limited to eight items and 2048 characters. If even one identifier cannot fit, the notice consumes that item using `[identifier exceeds notice limit]` and directs the reader to `patterns/catalog` and retained edit receipts for its full identifier; the bounded notice is not an exact-name channel in this exceptional case. Notices are consumed atomically once at prefix construction, with revision/kind deduplication.

Guidance is best-effort per generation: an agent that returns without another generation never sees it. If the provider fails after dequeue, that page is lost; it is not requeued (avoiding duplicates). Module metadata and notice queues are run-local coordination, **not security**. Direct globals writes bypass these conventions and are not journaled.

## Automatic dogfood module-edit journal

Existing CLI `--dogfood` and public API `spell.api/run` option `:dogfood true` enable local structured append-only module-edit records as well as human feedback. They reuse `.spell/feedback.edn`, or the existing `SPELL_FEEDBACK_PATH` override. Automatic entries have `:kind :module-edit`, distinct from human feedback; `:before`/`:after` are lossless executable-definition strings (nil before an install), and `:functions` lists `:added`, `:removed` and `:modified` keys. Children share the run's mode, destination and unique identity; separate API runs get distinct identities even in one process and destination. Journal entries are distinct from human `feedback/log` entries. Dogfood off performs no journal disk setup/writes and does not change ordinary module calls.

The journal covers **successful PATTERNS API installation baselines and unequal-definition updates only**. A custom install includes its exact executable baseline. Reinstall, equal-definition update (no-op), invalid/owner-rejected updates, speculative retries and direct globals mutations produce no code-change record. Each successful change records exact lossless before/after definitions, module/function changes, recorded owner, actual editor, explicit-owner acknowledgment (not approval), run identity, timestamp/trace node when available, and revision/global sequence from the same successful commit. Executable source is not truncated or automatically inserted into model context. Ordinary calls neither scan the registry nor fetch source bodies.

Appending occurs outside retryable transforms using the exact committed snapshots. Concurrent physical append order need not be commit order: reconstruct by run identity and global sequence; timestamps are advisory. This is not a crash-atomic persistence guarantee or a general audit framework. There are no globals watches, unrelated-state inspections, remote/MCP logging, or replay promises.

Journal status is additive receipt metadata: dogfood off omits `:journal`; successful recording adds `{:status :ok :sequence s}`. **A failed append does not roll back the live edit and does not throw an ordinary edit failure.** The normal edit receipt instead includes `:journal {:status :failed :message "EDIT COMMITTED / RECORDING FAILED" :module k :revision n :sequence s :error error-details}` and a bounded next-generation warning is queued for the actual editor. Preserve this receipt; **do not retry the transform to repair recording**. A failed journal append means the edit is live but the audit trail has a gap identified by sequence; the receipt is the only record unless the warning is read. Failure warnings share the bounded, deduplicated, best-effort notice lifecycle above.

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

## Mailing-list consumers

Every participant installs/reuses code. Exactly one administrator initializes state; omitted `:lists` creates `[:general]`, while an explicit nonempty distinct keyword vector replaces that default. The initializer is subscribed to all initial lists atomically. `:create` atomically creates a list and subscribes its creator, returning the actual `:subscription`.

```clojure
'(!call-now installed (patterns/install :mailing-list))
;; Administrator only, once per run:
'(!call-now board (patterns/call :mailing-list :init
                    {:lists [:design :implementation] :retention 200 :page-size 20}))
;; Existing workers never initialize again:
'(!call-now subscribed (patterns/call :mailing-list :subscribe-many
                         {:lists [:design :implementation] :from :earliest}))
'(!call-now page (patterns/call :mailing-list :digest {:list :design}))
;; Fetch needed :message bodies, process evidence and :gap, retain the token, then:
'(!call-now ack (patterns/call :mailing-list :ack {:token (:token page)}))
```

Direct consumers are `:init`, `:info`, `:lists`, `:create`, `:subscribe`, `:subscribe-many`, `:unsubscribe`, `:post`, `:post!`, `:notify`, `:message`, `:digest`, and `:ack`. There is no `:call` dispatcher or board-specialized `:spawn`. Internal editable entries are `:transact`, `:change`, `:digest-page`, and `:deliver`; the board has no private code registry. Duplicate init errors without replacing state. Configured `agents/spawn-ask` plus a child startup program installs/subscribes **before first generation**; see [complete onboarding and receipt guidance](./mailing-list.md).

## Evidence and bounded context

Preserve the predecessor pruning-evidence rule: **do not prune supporting evidence and then rediscover it**. Before `!peek` results disappear, `persist` the exact needed source slice, observed stored ID/offset, checkpoint, and actual effect receipts. Plans, source forms, and sent flags are not execution receipts. Incoming messages may replace an unexecuted trailing action.

`io/read-lines` ranges are one-indexed and half-open; `subvec` indexes are local, zero-indexed and half-open. For example, reading file lines `[181,221)` yields 40 lines. `(subvec lines 0 10)` keeps original lines 181–190, not lines 0–9 of the file. Retrieve a retained/stored value before repeating the original effect. An opaque marker is not evidence that its hidden body was inspected.

For model-facing recovery/continuation guidance, see the committed [recovery continuation finding](./recovery-continuation-finding.md). This migration does not introduce a new production recovery policy.

## Review and acceptance

The [module-library changelog](https://github.com/lukejoconnor/spell/blob/main/MODULE_LIBRARY_CHANGELOG.md) records the changes and validation. Earlier plans and run reports describe their historical revision. A running JVM retains the implementation loaded at startup; use a fresh runtime to exercise repository source changes.
