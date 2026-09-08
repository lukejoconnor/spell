# In-run mailing lists

Use a shared message board for research teams that need persistent-in-context coordination without waking every worker for every result. Designate **exactly one agent** to load and administer it. Every agent may safely install/reuse `:mailing-list`; only the administrator initializes board state, once. Ordinary operations use `(patterns/call :mailing-list :call operation args)`.

This is an opt-in native Spell module in `config/spl-lib/modules/mailing-list.spl`, not a service or runtime extension. Definitions live under globals `:modules`, separate from board state under `:mailing-list`. See [installable modules](./installable-modules.md). Enable `patterns`, `globals`, and `agents` for every participating agent. The bundled `mailing-list` skill provides compact operational guidance.

## Quick start

These are successive trailing expressions inside the normal completion wrapper:

```clojure
'(!call-now installed (patterns/install :mailing-list))
;; Administrator only; existing workers skip explicit initialization:
'(!call-now board (patterns/call :mailing-list :init {:retention 200 :page-size 20}))
'(!call-now list-created (patterns/call :mailing-list :call :create
                          {:list :research :description "Claims, experiments, and evidence"}))
'(!call-now subscribed (patterns/call :mailing-list :call :subscribe {:list :research :from :earliest}))
'(!call-now posted (patterns/call :mailing-list :call :post
                    {:list :research :summary "Experiment E7 contradicts hypothesis H2"
                     :thread "H2" :body {:result "..."}
                     :provenance {:path "experiments/E7.md" :revision "abc123"}}))
'(!call-now page (patterns/call :mailing-list :call :digest {:list :research :limit 10}))
;; Process page :messages AND its optional :gap before acknowledging.
'(!call-now acknowledged (patterns/call :mailing-list :call :ack {:token (:token page)}))
'(!call-now evidence (patterns/call :mailing-list :call :message {:list :research :id 1}))
```

Quiet `:post` is the default. Use `:post!` deliberately for urgent findings; it posts once and attempts a compact wake-up to **every** subscriber, including the author if subscribed. The result includes per-recipient `:deliveries` with `:sent` and, on failure, `:error`. `:sent true` means accepted by the coordinator, not read or processed by the recipient.

## API

`(patterns/call :mailing-list :init options)` initializes the single `:mailing-list` state global atomically and returns `{:ready true :global :mailing-list :owner handle}`. Existing non-nil state makes explicit initialization fail, including concurrent attempts, without changing the winning board or unrelated globals. Defaults and supported positive integer bounds:

| Option | Default | Maximum |
| --- | ---: | ---: |
| `:retention` messages per list | 200 | 2000 |
| `:page-size` messages per digest | 20 | 100 |
| `:max-lists` | 32 | 128 |
| `:max-subscribers` per list | 32 | 64 |
| `:max-message-chars` serialized message | 16000 | 65536 |

`(patterns/call :mailing-list :call operation arguments)` always resolves the shared executable dispatcher. Arguments are a map. `:agent` defaults to the current handle; an administrator can name another handle for subscription/cursor operations. These are cooperative agents with equal access to globals, **not** a security or authorization boundary.

| Operation | Arguments / result |
| --- | --- |
| `:info` | `{}` → owner, bounds, and `:module-functions` (module entry names, not board operations) |
| `:lists` | `{}` → compact descriptions, high-water IDs, retained counts, subscribers |
| `:create` | `{:list :keyword :description "..."}`; duplicate names error |
| `:subscribe` | `{:list k :agent h? :from :latest\|:earliest}`; returns cursor/epoch; repeat subscription preserves them |
| `:subscribe-many` | `{:lists [k ...] :agent h? :from ...}`; all-or-nothing transaction |
| `:unsubscribe` | `{:list k :agent h?}`; removes subscription, not messages |
| `:post` | `{:list k :summary "1–500 chars" :body value? :thread "short string"? :reply-to prior-id? :provenance value? :tags value?}` → ID and commit-time subscriber snapshot; no sends |
| `:post!` | same as `:post`, plus all-subscriber delivery attempts |
| `:notify` | `{:list k :id watermark?}`; explicitly sends a compact notice using the current subscriber snapshot; does not post or acknowledge |
| `:digest` | `{:list k :agent h? :limit 1–100?}` → compact page, cursor, high-water, gap, remaining, token; no mutation |
| `:ack` | `{:token page-token :agent h?}` → monotonically advanced cursor |
| `:message` | `{:list k :id n}` → full message on demand, or `:expired? true`; nonexistent IDs error |
| `:spawn` | `{:task "..." :handle :worker :lists [k ...] :from :earliest\|:latest?}` → handle, tracked result edge, `:onboarding :pending` |

List/handle keywords are limited to 128 characters; descriptions to 1000. Histories are bounded by count and serialized message size. Summaries are compact, and full bodies/provenance are fetched separately. Digests include ID, author, summary, thread and reply reference, not full evidence. `:reply-to` references an earlier ID in the same list, even if retention has since expired it. Store durable evidence in worktree files or another task-appropriate location and post references; don't paste whole papers into the board.

## Cursors, retention, and races

- Each list has monotonically increasing IDs. Concurrent successful posts get unique consecutive IDs in commit order; rejected posts consume none. Independent lists have independent IDs.
- A digest reads one immutable snapshot. Repeated/overlapping reads do not consume anything. A post racing a read is either in that snapshot or a later page.
- Acknowledgement advances to `max(current-cursor, token-through)`, never to the current head. Thus an older page cannot regress the cursor or consume posts that arrived after it. Overlapping processing should be coordinated per subscriber: acknowledging a later page says everything through that watermark was handled.
- Tokens contain list, agent, subscription epoch and watermark. Unsubscribe/resubscribe changes the epoch, invalidating old acknowledgements. Tokens are cooperative watermarks, not signed proofs of a read; do not manufacture or modify them.
- `:earliest` starts at zero, so retained history is available and already-evicted history is reported as a gap. `:latest` starts at the current head. A repeated subscribe is idempotent and never resets a cursor.
- Retention evicts the oldest messages regardless of cursors. A digest reports the inclusive missing ID range in `:gap`; it never silently advances the cursor. Process or explicitly accept that loss before acknowledging the page. If every retained message fits the page, `:remaining` is zero.
- `:post!` captures subscribers in the same transaction as its post. Later unsubscriptions do not cancel those delivery attempts; later subscriptions do not join them. `:notify` uses a fresh read snapshot. Notifications can arrive out of order; treat them as hints to read your cursor, not authoritative content or acknowledgement.
- Sends happen outside the retryable transaction and are attempted once per snapshot member. A failed recipient does not stop later attempts. Repeating `:notify` repeats notices; repeating `:post!` creates another post. There is no hidden retry/outbox or exactly-once inbox guarantee. Inspect captured results before retrying uncertain actions, and retain delivery reports you care about.
- Source replacement races are explicit: an in-flight `patterns/call` retains its selected entry; nested calls independently resolve latest module source. A `:post!` may use newly replaced delivery source after committing the post. Administrators should coordinate incompatible code/schema changes while workers are quiescent.

## Launch and communication

```clojure
'(!call-now launched (patterns/call :mailing-list :call :spawn
                      {:task "Investigate H2; post compact evidence and return a summary."
                       :handle :researcher-1 :lists [:research]}))
;; launched :edge is real dispatch evidence, not a guessed sent flag.
;; Do other work; wait only while collection work remains:
'(agents/!wait)
;; Inspect the actual received msg-N :body. Child failures are tagged
;; :spell/child-failure, including failed onboarding.
```

The helper uses normal `agents/spawn-ask` with an executable bootstrap and inherited compiled agent/provider. The bootstrap installs/reuses the module, then subscribes to all selected lists in one transaction before its ordinary task generation and teaches the worker digest/ack, quiet versus urgent posting, evidence retrieval and tracked communication. The caller receives a tracked **lifecycle result**, not a synchronous readiness promise. Capacity races at onboarding fail atomically and report a child failure, rather than leaving half the requested subscriptions. Do not assume a returned handle proves onboarding has completed. Handles are runtime identities: do not reuse an existing handle.

Normal receipt semantics still apply: incoming messages can supersede proposed actions, and workers must establish execution evidence before dependent work. No untracked blocking waits or global polling are introduced. Onboarding subscriptions intentionally survive normal task completion because a handle can awaken later. The administrator should unsubscribe retired/unused workers; the board does not silently interpret a lifecycle return as permanent retirement.

## Customize executable source

Board state has no private `:code` registry. Executable entries live in the general `:mailing-list` module: `:init`, `:call`, `:change`, `:digest`, and `:deliver`. `(patterns/catalog :mailing-list)` shows docs/parameters/requirements without bodies. Fetch source only when you need to inspect or edit it.

```clojure
'(!call-now entry (patterns/source :mailing-list :digest))
;; next turn: intentionally replace digest semantics for this task.
'(!call-now customized
   (patterns/update :mailing-list assoc-in [:functions :digest :source]
     '(fn [board args]
        {:notice "Use the experiment index for this task"
         :list (:list args) :owner (:owner board)})))
;; next turn: no source refetch is needed just to use the edited entry.
'(!call-now result (patterns/call :mailing-list :call :digest {:list :research}))
```

This deliberately replaces normal digest semantics; preserve the token/gap contract if acknowledgement remains available. `source` returns the complete entry (doc, requires, actual executable source). Pure `patterns/update` transforms the next **whole module definition**, not `[definition result]`, validates/commits atomically, and returns a compact receipt. Preserve unrelated entries with `assoc-in`. The board's separate pure `:change` returns `[next-board result]` inside retryable state updates; do not confuse these two contracts.

Spell functions use dynamic scope, not lexical closures. Invoke shared entries through `patterns/call`, not by refetching/evaluating source merely to use it. Retryable transforms must not send, spawn, perform I/O, or change globals. Dispatch/delivery perform effects after commit. Coordinate incompatible source/schema changes.

The module owner is the registered agent that wins first install, not the board state's administrator/owner field. Repeat installs preserve that immutable owner; workers never reinitialize existing board state. Discover the module owner with `patterns/catalog` and send edit requests to that handle. Default `patterns/update` is owner-only; a deliberate nonowner edit must use `(patterns/update :mailing-list {:owner recorded-owner} transform & args)`. Naming the owner is acknowledgment, not evidence of approval; the journal separately records the actual editor. Install before updating, never use update to create from nil, and keep the options map before the transform. Ownership metadata is outside the editable definition. Direct globals remains trusted coordination, not a security boundary.

The first installer receives bounded guidance in its next model generation even when install's return is discarded. No extra model call or mailbox drainage occurs; overflow is retained for later generations. Provider failure after dequeue or returning without another generation can prevent guidance from being seen. Under `--dogfood`, install baselines and changed definitions are journaled exactly; board state mutations are not. An `EDIT COMMITTED / RECORDING FAILED` receipt means code is already live: preserve the sequence and do not replay the transform.

**Predecessor antipattern: prune supporting evidence, then rediscover it.** Before `!peek` prunes a result, persist needed source slices, observed stored IDs/offsets, checkpoints, and actual effect receipts. Retrieve retained output before replaying its effect. An opaque preview is not inspection evidence; proposed actions/sent flags are not execution evidence. `io/read-lines` uses one-indexed half-open ranges; `subvec` uses local zero-indexed half-open offsets.
## Lifetime and deliberate limits

State and code live only for the current `spell.api/run` invocation and are shared by its agents. They are **not durable across JVM exit**, restart, or another API call. Long-running (4–48 hour) use needs task-level durable evidence/checkpoints; copying the board does not recreate coordinator identities or pending edges.

Selected features are threads/replies/provenance, bounded summaries and on-demand evidence, monotone explicit acknowledgement with retention gaps, descriptions/unsubscribe, explicit all-subscriber notification and tracked onboarding. They address research traceability, context budgets and interrupted work without adding a broker, scheduler, persistence service, access control, polling daemon, search index, or consensus protocol. Default bounds suit a modest team; worst-case maximum settings can still consume substantial memory. This implementation is functionally tested, not a 48-hour soak or memory/performance certification.
