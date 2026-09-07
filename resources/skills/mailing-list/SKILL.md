---
name: mailing-list
description: Coordinate research agents through a shared in-run Spell message board with quiet posts, subscriptions, bounded digests, acknowledgements, notifications, and worker onboarding.
---

# Mailing-list board

Use for multi-agent research or experiments where every intermediate result should not wake every agent. Designate **exactly one loader/administrator**. Require `patterns`, `globals`, and `agents` on every participant. The board is per API run, not durable storage.

## Start small

Successive quoted trailing actions:

```clojure
'(!call-now board (patterns/mailing-list {:retention 200 :page-size 20}))
'(!call-now created (patterns/mail :create {:list :research :description "Evidence and experiments"}))
'(!call-now subscribed (patterns/mail :subscribe {:list :research :from :earliest}))
'(!call-now posted (patterns/mail :post {:list :research :summary "E7 refutes H2" :thread "H2" :provenance {:path "experiments/E7.md"}}))
'(!call-now page (patterns/mail :digest {:list :research :limit 10}))
;; Process summaries and explicitly account for :gap before acknowledging:
'(!call-now ack (patterns/mail :ack {:token (:token page)}))
```

Existing workers **never initialize again**. `:info` and `:lists` discover the board. Duplicate loads, including concurrent ones, error without replacing it. `:subscribe` defaults to the current handle and `:latest`; `:earliest` reads retained history and reports earlier loss. Repeat subscription preserves the cursor. `:unsubscribe` removes it.

## Research workflow

- Post one claim/result per short summary. Use `:thread`, same-list `:reply-to`, `:tags`, and `:provenance` for experiment IDs, file/revision links and source locations. Fetch full `:body`/provenance with `:message {:list k :id n}` only when needed. Expired evidence is explicitly reported.
- Digests are read-only pages. Retain conclusions/tokens before pruning large results. Acknowledge the actual token, never a guessed head ID. Older acknowledgements cannot regress a cursor; unsubscribe/resubscribe invalidates old tokens. Coordinate overlapping readers sharing a handle.
- Retention is bounded and may evict unread messages. Treat `:gap` as evidence loss to investigate or explicitly accept, not success. Keep source evidence/checkpoints in durable task files.
- Ordinary `:post` does not notify. Reserve `:post!` for urgent findings; it awakens every subscriber. Check each `:deliveries` entry. Accepted send is not proof of processing. `:notify` explicitly re-notifies without creating another post; no automatic retries or deduplication are promised.

## Launch workers

```clojure
'(!call-now launched (patterns/mail :spawn {:task "Investigate H2 and return evidence" :handle :h2-worker :lists [:research]}))
;; A real tracked lifecycle edge was created; wait later only if work remains.
'(agents/!wait)
```

Bootstrap subscribes atomically before ordinary task generation and teaches the worker this API. `:onboarding :pending` is not a readiness claim; inspect the actual received result/failure. Preserve normal receipt semantics: incoming messages may supersede actions. Establish captures/subscriptions before dependent work. Use supported agents requests/futures, not polling or untracked waits. Administer unused subscriptions explicitly; normal return does not retire a handle.

## Customize progressively

See `(!describe patterns :mailing-list)` and `(!describe patterns :mail)` for the compact API; `docs/mailing-list.md` explains races, bounds and source contracts. Shared executable functions are under `(get-in (globals/get :mailing-list) [:code operation])`: `:dispatch`, `:change`, `:digest`, `:deliver`. Read one entry, not the entire board. They actually govern subsequent operations for fresh agents. Replace source using pure `globals/update`; explicit parameters only, no assumed lexical closures. `:change` runs inside a retryable transaction and MUST remain pure. Put sends/spawns outside it. All agents trust each other; owner is a coordination convention, not security. Coordinate schema changes, keep summaries compact, and record concrete dogfood failures with feedback/log when available.
