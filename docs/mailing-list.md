# In-run mailing lists

Use a shared message board for research teams that need persistent-in-context coordination without waking every worker for every result. Designate **exactly one agent** to load and administer it. Other agents use `patterns/mail`; they never call the loader again.

This is native Spell source in `config/spl-lib/patterns.spl`, not a service or runtime extension. Enable `patterns`, `globals`, and `agents` for every participating agent. The bundled `mailing-list` skill provides compact operational guidance.

## Quick start

These are successive trailing expressions inside the normal completion wrapper:

```clojure
'(!call-now board (patterns/mailing-list {:retention 200 :page-size 20}))
'(!call-now list-created (patterns/mail :create
                          {:list :research :description "Claims, experiments, and evidence"}))
'(!call-now subscribed (patterns/mail :subscribe {:list :research :from :earliest}))
'(!call-now posted (patterns/mail :post
                    {:list :research :summary "Experiment E7 contradicts hypothesis H2"
                     :thread "H2" :body {:result "..."}
                     :provenance {:path "experiments/E7.md" :revision "abc123"}}))
'(!call-now page (patterns/mail :digest {:list :research :limit 10}))
;; Process page :messages AND its optional :gap before acknowledging.
'(!call-now acknowledged (patterns/mail :ack {:token (:token page)}))
'(!call-now evidence (patterns/mail :message {:list :research :id 1}))
```

Quiet `:post` is the default. Use `:post!` deliberately for urgent findings; it posts once and attempts a compact wake-up to **every** subscriber, including the author if subscribed. The result includes per-recipient `:deliveries` with `:sent` and, on failure, `:error`. `:sent true` means accepted by the coordinator, not read or processed by the recipient.

## API

`(patterns/mailing-list options)` initializes the single `:mailing-list` global atomically and returns `{:ready true :global :mailing-list :owner handle}`. Existing non-nil state makes loading fail, including concurrent loads, without changing the winning board or unrelated globals. Defaults and supported positive integer bounds:

| Option | Default | Maximum |
| --- | ---: | ---: |
| `:retention` messages per list | 200 | 2000 |
| `:page-size` messages per digest | 20 | 100 |
| `:max-lists` | 32 | 128 |
| `:max-subscribers` per list | 32 | 64 |
| `:max-message-chars` serialized message | 16000 | 65536 |

`(patterns/mail operation arguments)` always resolves the shared executable dispatcher. Arguments are a map. `:agent` defaults to the current handle; an administrator can name another handle for subscription/cursor operations. These are cooperative agents with equal access to globals, **not** a security or authorization boundary.

| Operation | Arguments / result |
| --- | --- |
| `:info` | `{}` → owner, bounds, stored code entry names |
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
- Source replacement races are explicit: read operations use their entry snapshot; atomic mutations resolve `:change` from each current retry snapshot. A `:post!` may use a newly replaced delivery function after committing the post. Administrators should coordinate incompatible code/schema changes while workers are quiescent.

## Launch and communication

```clojure
'(!call-now launched (patterns/mail :spawn
                      {:task "Investigate H2; post compact evidence and return a summary."
                       :handle :researcher-1 :lists [:research]}))
;; launched :edge is real dispatch evidence, not a guessed sent flag.
;; Do other work; wait only while collection work remains:
'(agents/!wait)
;; Inspect the actual received msg-N :body. Child failures are tagged
;; :spell/child-failure, including failed onboarding.
```

The helper uses normal `agents/spawn-ask` with an executable bootstrap and inherited compiled agent/provider. The bootstrap subscribes to all selected lists in one transaction before its ordinary task generation and teaches the worker digest/ack, quiet versus urgent posting, evidence retrieval and tracked communication. The caller receives a tracked **lifecycle result**, not a synchronous readiness promise. Capacity races at onboarding fail atomically and report a child failure, rather than leaving half the requested subscriptions. Do not assume a returned handle proves onboarding has completed. Handles are runtime identities: do not reuse an existing handle.

Normal receipt semantics still apply: incoming messages can supersede proposed actions, and workers must establish execution evidence before dependent work. No untracked blocking waits or global polling are introduced. Onboarding subscriptions intentionally survive normal task completion because a handle can awaken later. The administrator should unsubscribe retired/unused workers; the board does not silently interpret a lifecycle return as permanent retirement.

## Customize executable source

The shared board contains `:code {:dispatch '(fn ...) :change '(fn ...) :digest '(fn ...) :deliver '(fn ...)}` alongside state. `patterns/mail` is only a lookup/eval bridge. Operations **actually execute these source forms**; there is no separate hidden implementation or decorative copy.

For example, change what every agent's next digest produces, without changing their prompts:

```clojure
'(!call-now customized
  (globals/update :mailing-list
    (fn [b]
      (assoc-in b [:code :digest]
        '(fn [board args]
           {:notice "Use the experiment index for this task"
            :list (:list args) :owner (:owner board)})))))
'(!call-now result (patterns/mail :digest {:list :research}))
```

This deliberately replaces normal digest semantics; production customizations should preserve the token/gap contract if they still offer acknowledgement. Inspect a **single** source entry when needed, not the whole board: `(!call-now source (get-in (globals/get :mailing-list) [:code :digest]))`. Use bounded slices if rendering stores an oversized result.

Spell functions use dynamic scope. Each stored function receives explicit arguments; custom functions must not depend on loader/caller lexical closures. To invoke source directly, use `((eval source) arguments...)`, not `(apply source ...)` on a quoted list. Pure `:change` returns `[next-board result]`; it runs inside retryable `globals/update`, so it must not send, spawn, perform I/O, or change globals. Dispatch/delivery perform effects after commit. Do not reset globals or overwrite unrelated keys. Owner is a coordination convention, not an access restriction. Keep task customizations in the shared code rather than private worker variants.

## Lifetime and deliberate limits

State and code live only for the current `spell.api/run` invocation and are shared by its agents. They are **not durable across JVM exit**, restart, or another API call. Long-running (4–48 hour) use needs task-level durable evidence/checkpoints; copying the board does not recreate coordinator identities or pending edges.

Selected features are threads/replies/provenance, bounded summaries and on-demand evidence, monotone explicit acknowledgement with retention gaps, descriptions/unsubscribe, explicit all-subscriber notification and tracked onboarding. They address research traceability, context budgets and interrupted work without adding a broker, scheduler, persistence service, access control, polling daemon, search index, or consensus protocol. Default bounds suit a modest team; worst-case maximum settings can still consume substantial memory. This implementation is functionally tested, not a 48-hour soak or memory/performance certification.
