# Multi-agent coordination

Spell programs control when to launch work, exchange messages, call a model,
wait, and return. Each API run has its own coordinator holding agent identities,
mailboxes, lifecycle results, and outstanding requests.

## Request now, wait later

`agents/ask` and `agents/spawn-ask` register work immediately and return an edge
ID. They leave the caller running. An edge collects one result from each target;
its slots exist before any request is delivered or child starts. Each call is an
independent atomic interaction with the coordinator, in evaluator order.

```clojure
'(do
   (def review (agents/spawn-ask "Review the proposed change and return findings."))
   (def examples (agents/spawn-ask "Check the examples and return findings."))
   (agents/!wait))
```

The two collections are independent. Completion of either awakens the caller,
and the other stays pending. Use one multi-target edge to collect all results in
one report:

```clojure
'(agents/!spawn-ask
   [["Review the API." :api-reviewer]
    ["Check the examples." :example-reviewer]])
```

`!ask` and `!spawn-ask` are convenience wrappers: register the request, then
execute `!wait`. `!sleep` uses the same waiting primitive to resume retained
waits. A wait rechecks pending messages and current ordering atomically. A
result that arrives before the wait remains available; the caller cannot miss
its wakeup simply because registration and waiting were separate calls.

When no messages or incoming/outgoing obligations remain, waiting is a no-op.

Programs may run other expressions, including nested `!llm-self` calls, between
registration and waiting. Coordinator operations are eager; they are never
batched across those expressions. Current inbox receipt timing remains after
model generation and before evaluation.

## Operations

| Operation | Result and behavior |
|---|---|
| `(agents/spawn prompt)`, `(agents/spawn agent prompt handle)` | Start a child and return its handle. No result collection is created. |
| `(agents/ask target value)`, `(agents/ask target)`, `(agents/ask [targets])` | Immediately create a request edge, awaken its targets, and return its ID. The one-argument forms send bodyless requests. |
| `(agents/spawn-ask prompt)`, `(agents/spawn-ask agent prompt handle)`, `(agents/spawn-ask [specs])` | Register the children and one result edge before launching, then return the edge ID. A spec is a prompt, `[prompt handle]`, `[agent prompt]`, or `[agent prompt handle]`. |
| `(agents/!ask ...)`, `(agents/!spawn-ask ...)` | Register through the corresponding nonblocking operation, then wait. |
| `(agents/!wait)`, `(agents/!sleep)` | Continue with available messages or sleep on retained edges when the ordering rule permits. |
| `(agents/send target value)` | Deliver a plain message and awaken the recipient. It does not fill a request slot. |
| `(agents/reply message value)` | Fill the caller's slot in an actionable request. |
| `(agents/!reply-ask message value)` | Reply and open a reverse request, then wait for the next response. |
| `(agents/cancel edge-id)` | Detach an outgoing collection owned by the caller. Targets continue running. |
| `(agents/status)`, `(agents/status handle)` | Inspect lifecycle status and, for the caller, edge summaries. |
| `(agents/graph)`, `(agents/out-edges)`, `(agents/in-edges)` | Inspect pending collections and result slots. |
| `(agents/current-handle)`, `(agents/parent-handle)` | Identify the caller and its parent. |

Prompt-only spawn operations use the current compiled agent. Explicit agent
arguments use a configured compiled worker. Start worker lifecycles through
spawn operations; calling a compiled worker directly from an active agent or
its future is rejected. Nested `!llm-self` calls remain available.
Waiting operations are ordinarily
the final expression of a turn because waking continues through the agent's
completion rather than returning like a host-language promise await.

## Replies and lifecycle results

An actionable request contains `:from`, `:expects-response true`, and `:edge-id`,
and optionally `:body`. Pass that message map to `agents/reply`. Each target slot
fills at most once. Replies to stale, cancelled, or already answered requests
are no-ops. An actionable request without its edge ID is invalid. `!reply-ask` requires a
live actionable request when reversing an existing request; a stale one is
refused without changing the coordinator. `agents/reply` to that stale request remains a no-op.

A single-target completion report contains `:from`, `:body`, and `:edge-id`.
For multiple targets, `:body` is a vector of `{:from target :body result}` maps
in target order. A reply to a single-target completion report sends an ordinary
message. An aggregate report has no single reply destination.

A normal lifecycle return fills its claimed incoming slots. Requests are
claimed when their mailbox batch is consumed, so an older lifecycle cannot
answer a queued request it never saw. Spawn collections are claimed before the
child starts. A successful `nil` is a result; an unrecoverable child failure is
explicit data marked `:spell/child-failure true`. Recovery attempts do not
complete a slot while recovery is still active.

Ordinary messages may wake a waiting agent while its results are still pending.
After handling such a message, call `!sleep` or `!wait` to retain the collections.
Returning ends that lifecycle and cancels its unfinished outgoing edges, with a
trace warning. Cancellation abandons collection; it does not interrupt children
or their descendants. A handle whose initialization or runner submission fails
is retired after its waiting callers receive terminal failure results. A persistent agent can be awakened for a later lifecycle.

## Non-deadlock guarantee

Edges have strictly increasing creation order. An agent may sleep only if one
of its pending outgoing edges is newer than every pending incoming edge. A
request also durably arranges wakeup of its targets. Filling, cancelling, and
finishing remove waiting obligations; completion reports awaken their source.

For any finite closed set of unfinished waits, its newest edge has an unresolved
target. That target cannot also sleep under a newer outgoing edge within the
set. Thus communication waits cannot leave every participant in such a set
asleep. Ordinary future computation, external I/O, and whether a model responds
usefully retain their own progress assumptions.

The ordering condition is conservative. Refusal explains the caller's pending
obligations; it does not silently introduce another kind of wait. Reply to a
newer request or otherwise change the obligations before trying again.

## Requests from futures

Future orchestration can use `(blocking/request target value)` to register an
immediate request and obtain its result token. `(blocking/await token)` collects
that result, and `(blocking/send-await target value)` combines both operations.
The one-argument request form is a bodyless poke. Request slots and payload
delivery commit together, so a target cannot finish between capturing a token
and receiving its assignment.

These tokens represent coordinator edges owned by the enclosing agent. A
surrounding `!ask-await` must obey the same incoming/outgoing ordering rule when
its computation depends on agents. New incoming requests wake the enclosing
agent so it can answer. `!ask-await` can also resume for already queued or
unrelated messages before its future finishes; the eventual result arrives
through a later message while the lifecycle remains active. Resumption does
not imply that the future is complete: the program can process messages and
continue, or call `!ask-await` again on the same future to wait further. Each
call registers its own completion notification. Ordinary computation futures and external I/O have
separate progress assumptions. `blocking/completion-promise` is replaced by the
atomic request operation. Awaiting a cancelled token raises `:request-cancelled`;
closing the run raises `:coordinator-closed`. A child failure remains tagged result
data, and successful `nil` or cancellation-shaped maps retain their exact values.
Future-only blocking helpers also check the actual calling thread, so passing a
helper function back into an agent turn cannot create an uninterruptible wait.

## Capacity

The API option `:coordinator {:max-edges 10000}` sets the maximum number of
simultaneously pending hyperedges. The default is 10,000; overrides must be
positive integers. Each edge counts once even if it has several target slots.
This is a collection-count limit, not a limit on agent count, slots, payload
size, or total memory.

Capacity admission is atomic. A rejected request sends no request messages; a
rejected spawn collection registers or starts no children. Rejection raises a
capacity error immediately and never waits for space. Completed, cancelled, and
abandoned edges release their capacity. Context presentation limits are
separate from coordinator admission and do not change stored result values.
