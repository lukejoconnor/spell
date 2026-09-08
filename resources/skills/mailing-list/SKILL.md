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
'(!call-now board (patterns/call :mailing-list :init {:lists [:research] :retention 200 :page-size 20}))
'(!call-now posted (patterns/call :mailing-list :post {:list :research :summary "E7 refutes H2" :thread "H2" :provenance {:path "experiments/E7.md"}}))
'(!call-now page (patterns/call :mailing-list :digest {:list :research :limit 10}))
;; Retrieve needed full bodies before claiming evidence was inspected:
'(!call-now evidence (patterns/call :mailing-list :message {:list :research :id 1}))
;; Process summaries/bodies and explicitly account for :gap before acknowledging:
'(!call-now ack (patterns/call :mailing-list :ack {:token (:token page)}))
```

Omitted init `:lists` creates `[:general]`; an explicit nonempty distinct keyword vector replaces it. Initialization atomically subscribes the initializer. `:create {:list k :description "..."}` atomically subscribes the creator and returns `:subscription`. Existing workers subscribe to the chosen lists themselves. Every participant can safely install/reuse the module; repeat/concurrent install preserves edits and state. Existing workers **never initialize the board again**. Duplicate explicit `:init`, including concurrent attempts, errors without replacing it. `:info` and `:lists` discover the board. `:subscribe` defaults to the current handle and `:latest`; `:earliest` reads retained history and reports earlier loss. Repeat subscription preserves the cursor. `:subscribe-many` atomically subscribes to existing lists; `:unsubscribe` removes a subscription.

## Research workflow

- Post one claim/result per short summary. Use `:thread`, same-list `:reply-to`, `:tags`, and `:provenance` for experiment IDs, file/revision links and source locations. Fetch full evidence only when needed with `(patterns/call :mailing-list :message {:list k :id n})`; expired evidence is explicit.
- Digests require an arguments map with one keyword `:list`. Call separately for each list, e.g. `(patterns/call :mailing-list :digest {:list :research})`; `:lists` is not supported (including alongside `:list`) and is not an alias for `:subscribe-many`. Invalid arguments, unknown lists, and missing subscriptions have distinct diagnostics and do not change the board or subscriptions.
- Digests are read-only pages. Retain conclusions and actual tokens before pruning. Acknowledge the observed token, never a guessed head ID. Older acknowledgements cannot regress a cursor; unsubscribe/resubscribe invalidates old tokens. Coordinate overlapping readers sharing a handle.
- Retention may evict unread messages. Treat `:gap` as evidence loss to investigate or explicitly accept, not success. Keep durable source evidence/checkpoints in task files.
- Ordinary `:post` does not notify. Reserve `:post!` for urgent findings; it awakens every subscriber, including the author if subscribed. `:notify` also includes a subscribed caller. Inspect each `:deliveries` entry. Accepted send is not proof of processing. `:notify` explicitly re-notifies without another post; no automatic retries or deduplication are promised.

## Launch workers

Use a caller-selected configured compiled agent, here `workers/researcher` (the caller's profile must expose that symbol, and the child must have `patterns`, `globals`, and `agents`). No profile is created by this example. A task string alone does not guarantee pre-generation subscriptions; supply a complete startup program deliberately:

```clojure
(def child-program
  '(quine completion
     (eval
       (do
         '(let [failure
                (try
                  (do (patterns/install :mailing-list)
                      (patterns/call :mailing-list :subscribe-many
                        {:lists [:research] :from :earliest})
                      nil)
                  (catch e {:spell/child-failure true :phase :onboarding
                            :handle :researcher-1 :error (str e)}))]
            (if failure failure
              (!llm-self
                (wrap-cat "Subscriptions installed. Never reinitialize the board. Read an explicit :research digest, retrieve needed :message bodies, process gaps/evidence and ack the exact token. Post quiet summaries <=500 chars. Urgent post!/notify wakes every subscriber including a subscribed author. Return evidence and actual receipts; delivery or ack is not comprehension.")
                {:receive? true})))))))
'(!call-now launched-edge
   (agents/spawn-ask workers/researcher (pr-str child-program) :researcher-1))
;; On the next turn the edge ID is actual dispatch evidence, not readiness.
;; Do other work; wait only while a required collection remains:
'(agents/!wait)
;; On waking inspect the actual msg-N with that :edge-id and its :body.
;; A :spell/child-failure/:phase :onboarding report is visible failure.
```

Only install/subscription is inside the setup catch. It precedes the child's first model generation; task generation/recovery remains outside that catch. The board must already exist and selected lists must have capacity. Atomic multi-list failure leaves no partial subscriptions. Normal tracked lifecycle returns, including nil and tagged failures, are collected through the coordinator; a handle or proposed dispatch is not proof of onboarding/completion. Do not reuse an existing handle.

Incoming messages may supersede proposed actions. Establish actual captures/edges/subscriptions before dependent work; do not poll globals or recreate an uncertain dispatch. Subscriptions intentionally survive normal task completion because a handle can awaken later. Unsubscribe retired/unused workers explicitly; no automatic cleanup is implied.

## Customize progressively

Use `(patterns/catalog :mailing-list)` for compact docs/parameters/requirements without bodies. Inspect a complete editable entry only when necessary: `(patterns/source :mailing-list :digest)`. Direct consumers are `:init`, `:info`, `:lists`, `:create`, `:subscribe`, `:subscribe-many`, `:unsubscribe`, `:post`, `:post!`, `:notify`, `:message`, `:digest`, `:ack`; internal entries are `:transact`, `:change`, `:digest-page`, `:deliver`. No `:call`/`:spawn` compatibility entries remain; there is **no private board code registry**. Use pure `(patterns/update :mailing-list transform & args)` to change the whole definition atomically; use `assoc-in` to replace an entry/source while preserving the rest. The transform returns the next definition, not `[definition result]`.

Spell functions have dynamic scope, not lexical closures. Selected calls retain their entry snapshot; nested `patterns/call` resolves latest source. The board's separate pure `:change` returns `[next-board result]` and must remain effect-free; sends/spawns happen outside retryable state transactions. Coordinate incompatible schema changes.

The module owner is the registered agent that wins first install, not the board state's administrator/owner field. Repeat installs preserve that immutable owner; workers never reinitialize existing board state. Discover the module owner with `patterns/catalog` and send edit requests to that handle. Default `patterns/update` is owner-only; a deliberate nonowner edit must use `(patterns/update :mailing-list {:owner recorded-owner} transform & args)`. Naming the owner is acknowledgment, not evidence of approval; the journal separately records the actual editor. Install before updating, never use update to create from nil, and keep the options map before the transform. Ownership metadata is outside the editable definition. Direct globals remains trusted coordination, not a security boundary.

The first installer receives bounded guidance in its next model generation even when install's return is discarded. No extra model call or mailbox drainage occurs; overflow is retained for later generations. Provider failure after dequeue or returning without another generation can prevent guidance from being seen. Under `--dogfood`, install baselines and changed definitions are journaled exactly; board state mutations are not. An `EDIT COMMITTED / RECORDING FAILED` receipt means code is already live: preserve the sequence and do not replay the transform. See `docs/installable-modules.md` and `docs/mailing-list.md` for complete contracts.

## Preserve evidence before pruning

**Antipattern: prune supporting evidence, then rediscover it.** Before a `!peek` result disappears, `persist` exact needed snippets, observed stored IDs/offsets, a compact checkpoint, and actual operation receipts. A proposed action or sent flag is not execution evidence. Retrieve retained/stored output before repeating its original effect; an opaque preview is not proof that the hidden body was inspected. `io/read-lines` uses one-indexed half-open ranges; `subvec` uses local zero-indexed half-open offsets. Record concrete dogfood failures with `feedback/log` when available.

## Caller convention and trust

Pre-generation subscription ordering is ordinary caller composition, not runtime-enforced onboarding. Author attribution is cooperative and unauthenticated; subscriptions are cursor/delivery configuration, not a privacy or access-control boundary. Use the complete `examples/mailing-list-startup.spl` startup with an already configured compiled child; do not create profiles or add a board-specific spawn wrapper.
