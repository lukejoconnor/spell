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
asleep under fair scheduling, provided external model, tool, and evaluator work
eventually yields, finishes, or fails. This is freedom from coordinator-wait
deadlock; arbitrary programs may still diverge. Ordinary future cycles, blocking
host tools, or dependencies hidden by polling globals/files require their own
external progress or must use tracked requests. An opaque computation awaited
through `!ask-await` cannot justify sleeping past a newer incoming edge.

The ordering condition is conservative. A refusal raises a recoverable error
without suspending the agent or changing its obligations. A Spell error handler
or the normal evaluation-recovery path can inspect current obligations through
`agents/status` and revise the program. Reply to a newer request or otherwise
change the obligations before trying again. Fatal run controls remain terminal.

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

## Writing programs that communicate

Give ordinary child assignments as strings. Use `def` to name task text:

```clojure
(def review-task (str "Review docs/api.md for " topic ". Return findings."))
'(!call-now review-edge (agents/spawn-ask review-task)
            examples-edge (agents/spawn-ask "Review the examples. Return findings."))
```

The next continuation retains the actual edge IDs as injected bindings. A
`quine` binding holds source, and `wrap-cat` constructs a program prefix; use
those when deliberately building a valid completion prefix. They do not name
an ordinary instruction string.

Use `!call-now` to retain local results needed after a continuation, too. A
`def` inside an old quoted action is not a persistent binding. After dispatch
and any local work, put `'(agents/!wait)` at the end of the turn. Read received
`msg-N` bindings in the resumed continuation. A wait eventually returns the
value of that whole resumed computation; capturing it as the next message or
wrapping it in an extra function call misinterprets that value. Capturing a
synchronous `!llm-self` result remains available.

### Recognizing what executed

A message arriving during generation can supersede the proposed quoted action
before execution. The action's source remains visible, and preceding ordinary
definitions can evaluate. A local `sent` flag therefore need not correspond to
an executed send. The `[preempted or awakened by msg-N]` annotation is also used
when an executed wait awakens, so the annotation alone does not classify the
previous action.

On waking, establish which prerequisite actions actually ran before continuing
dependent work. Receiving a peer request does not establish that your own
request was dispatched. Complete an interrupted prerequisite before a dependent
reply or wait. If execution is uncertain, inspect the current state first:

```clojure
'(!call-now current-obligations (agents/status))
```

Use actual captures, received completion reports, and pending edge records. An
empty outgoing set alone does not exclude a completed or cancelled request.
When confirmed dispatch matters, capture an immediate `ask`, then wait in a
later turn; the convenience `!ask` returns the resumed computation's value.

Capture immediate interactions with fresh operation-specific names:

```clojure
'(!call-now clarification-edge (agents/ask :reviewer question))
;; A newly injected clarification-edge binding contains the returned edge ID.
```

A newly injected result establishes that the call returned. For `reply`, the
result is `nil` both after filling a live slot and after a stale no-op. Reusing
a name can leave an older value visible after the new action was superseded.
An effect may also execute before a later expression in a batched call fails,
preventing its result binding from being rendered. When execution is uncertain,
inspect pending edges and obligations before retrying.

Explicitly reply to any request whose answer differs from your final return
value. The lifecycle return supplies the same value to every remaining claimed
slot.
Before waiting, inspect uncertain obligations and establish that work remains
to collect. A refused wait is a recoverable error and leaves the agent awake.
Spell `try/catch` can handle it, and normal evaluation recovery can revise the
program. Inspect `agents/status` during recovery, then respond, return, or wait
according to the current obligations. Repeating the refused wait does not
resolve them. With recovery disabled and no handler, the lifecycle fails.
Normal return preserves the handle for later requests; startup failure retires
an unusable handle.

### Retaining a computation future

These are successive turns, using an ordinary string assignment:

```clojure
'(!call-now worker-handle
   (agents/spawn "Answer incoming arithmetic requests with integers." :worker))

'(!call-now task-future
   (future (blocking/await
             (blocking/request worker-handle "Multiply 23 by 41."))))

'(!ask-await task-future)
```

Create the future once in the quoted trailing expression and retain its value
through `!call-now`. An unrelated message can interrupt the join; after handling
it, join the same `task-future` again. A generated `stored` reference retains the
actual future object. Creating a future in ordinary retained source can repeat
its request on later turns, while a `def` inside a quoted `do` does not preserve
the binding for a later rejoin.

`future` accepts one expression; use `do` for multiple forms. `blocking/request`
creates a token and `blocking/await` collects it inside the future. The enclosing
`!ask-await` resumes with messages, including the eventual future result.
`blocking/send-await` creates and collects a new request; an existing token is
collected with `blocking/await`.

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
