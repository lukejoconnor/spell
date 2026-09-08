---
name: mailing-list
description: Coordinate research agents through a shared in-run Spell message board with quiet posts, subscriptions, bounded digests, acknowledgements, notifications, and worker onboarding.
---

# Mailing-list board

Use for multi-agent research where every intermediate result should not wake every agent. Require `patterns`, `globals`, and `agents` on every participant. Definitions live in run-local `:modules`; board state lives separately in `:mailing-list`. The board is not durable storage.

## Start small

These are successive quoted trailing actions:

```clojure
'(!call-now installed (patterns/install :mailing-list))
;; Exactly one administrator initializes state. Existing workers skip this:
'(!call-now board (patterns/call :mailing-list :init {:retention 200 :page-size 20}))
'(!call-now created (patterns/call :mailing-list :call :create {:list :research :description "Evidence and experiments"}))
'(!call-now subscribed (patterns/call :mailing-list :call :subscribe {:list :research :from :earliest}))
'(!call-now posted (patterns/call :mailing-list :call :post {:list :research :summary "E7 refutes H2" :thread "H2" :provenance {:path "experiments/E7.md"}}))
'(!call-now page (patterns/call :mailing-list :call :digest {:list :research :limit 10}))
;; Process summaries and explicitly account for :gap before acknowledging:
'(!call-now ack (patterns/call :mailing-list :call :ack {:token (:token page)}))
```

Every participant can safely install/reuse the module; repeat/concurrent install preserves edits and state. Existing workers **never initialize the board again**. Duplicate explicit `:init`, including concurrent attempts, errors without replacing it. `:info` and `:lists` discover the board. `:subscribe` defaults to the current handle and `:latest`; `:earliest` reads retained history and reports earlier loss. Repeat subscription preserves the cursor. `:subscribe-many` atomically subscribes to existing lists; `:unsubscribe` removes a subscription.

## Research workflow

- Post one claim/result per short summary. Use `:thread`, same-list `:reply-to`, `:tags`, and `:provenance` for experiment IDs, file/revision links and source locations. Fetch full evidence only when needed with `(patterns/call :mailing-list :call :message {:list k :id n})`; expired evidence is explicit.
- Digests are read-only pages. Retain conclusions and actual tokens before pruning. Acknowledge the observed token, never a guessed head ID. Older acknowledgements cannot regress a cursor; unsubscribe/resubscribe invalidates old tokens. Coordinate overlapping readers sharing a handle.
- Retention may evict unread messages. Treat `:gap` as evidence loss to investigate or explicitly accept, not success. Keep durable source evidence/checkpoints in task files.
- Ordinary `:post` does not notify. Reserve `:post!` for urgent findings; it awakens every subscriber. Inspect each `:deliveries` entry. Accepted send is not proof of processing. `:notify` explicitly re-notifies without another post; no automatic retries or deduplication are promised.

## Launch workers

```clojure
'(!call-now launched
   (patterns/call :mailing-list :call :spawn
                  {:task "Investigate H2 and return evidence"
                   :handle :h2-worker :lists [:research]}))
;; A real tracked lifecycle edge was created; wait only if work remains.
'(agents/!wait)
```

Bootstrap installs/reuses the module and subscribes atomically before ordinary task generation. `:onboarding :pending` is not readiness; inspect the received result/failure. Incoming messages may supersede proposed actions: establish actual captures/subscriptions before dependent work. Use supported requests/futures, not polling or untracked waits. Administer unused subscriptions explicitly; normal return does not retire a handle.

## Customize progressively

Use `(patterns/catalog :mailing-list)` for compact docs/parameters/requirements without bodies. Inspect a complete editable entry only when necessary: `(patterns/source :mailing-list :digest)`. General module functions are `:init`, `:call`, `:change`, `:digest`, `:deliver`; there is **no private board code registry**. Use pure `(patterns/update :mailing-list transform & args)` to change the whole definition atomically; use `assoc-in` to replace an entry/source while preserving the rest. The transform returns the next definition, not `[definition result]`.

Spell functions have dynamic scope, not lexical closures. Selected calls retain their entry snapshot; nested `patterns/call` resolves latest source. The board's separate pure `:change` returns `[next-board result]` and must remain effect-free; sends/spawns happen outside retryable state transactions. Coordinate incompatible schema changes.

The module owner is the registered agent that wins first install, not the board state's administrator/owner field. Repeat installs preserve that immutable owner; workers never reinitialize existing board state. Discover the module owner with `patterns/catalog` and send edit requests to that handle. Default `patterns/update` is owner-only; a deliberate nonowner edit must use `(patterns/update :mailing-list {:owner recorded-owner} transform & args)`. Naming the owner is acknowledgment, not evidence of approval; the journal separately records the actual editor. Install before updating, never use update to create from nil, and keep the options map before the transform. Ownership metadata is outside the editable definition. Direct globals remains trusted coordination, not a security boundary.

The first installer receives bounded guidance in its next model generation even when install's return is discarded. No extra model call or mailbox drainage occurs; overflow is retained for later generations. Provider failure after dequeue or returning without another generation can prevent guidance from being seen. Under `--dogfood`, install baselines and changed definitions are journaled exactly; board state mutations are not. An `EDIT COMMITTED / RECORDING FAILED` receipt means code is already live: preserve the sequence and do not replay the transform. See `docs/installable-modules.md` and `docs/mailing-list.md` for complete contracts.

## Preserve evidence before pruning

**Antipattern: prune supporting evidence, then rediscover it.** Before a `!peek` result disappears, `persist` exact needed snippets, observed stored IDs/offsets, a compact checkpoint, and actual operation receipts. A proposed action or sent flag is not execution evidence. Retrieve retained/stored output before repeating its original effect; an opaque preview is not proof that the hidden body was inspected. `io/read-lines` uses one-indexed half-open ranges; `subvec` uses local zero-indexed half-open offsets. Record concrete dogfood failures with `feedback/log` when available.
