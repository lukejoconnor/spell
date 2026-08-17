# Multi-Agent System

Spell agents communicate through the optional `agents/` effect namespace. An
agent can start background agents, exchange messages, wait for replies or
lifecycle results, resume an interrupted wait, cancel a wait, and inspect the
waiting relationships among agents.

All `agents/` calls are effects. Use them in the quoted trailing expression of
a Spell completion, as in:

```clojure
'(agents/!ask :reviewer "Review the proposed change.")
```

The selected agent profile must expose the `agents/` namespace. Named compiled
sub-agents, when configured, are available through `workers/`.

## Agents and waiting obligations

The runtime represents coordination as a directed hypermultigraph:

- Each node is an agent. Its lifecycle state is `:awake`, `:asleep`, or
  `:finished`.
- Each directed hyperedge is a waiting obligation with one source agent, one or
  more distinct target agents, and one result slot per target.
- Multiple edges may have the same source and targets. Edge IDs distinguish
  them for inspection and cancellation.

An awake agent may retain outgoing edges. This commonly happens when an
unrelated message awakens an agent that was waiting, or when one of several
outgoing edges completes. The remaining edges stay pending.

Within one edge, waiting has **all-target** semantics. The edge completes only
after every target slot is filled, then the source is awakened with one
completion report. Across multiple outgoing edges, wakeup has **any-edge**
semantics: whichever edge completes first awakens the source, while its other
outgoing edges remain in place.

A plain message can also awaken an agent. Sending does not create, fill, or
remove an edge. After handling an unrelated message, an agent can call
`agents/!sleep` to resume waiting on its retained outgoing edges.

`agents/spawn` starts an agent and returns its handle without creating a waiting
obligation. `agents/!spawn-ask` combines spawning and waiting: the child's
lifecycle result fills its slot automatically. Similarly, `agents/!ask` creates
a waiting obligation for an existing target and sends an actionable request.
The target can fill its slot with `agents/reply`, `agents/!reply-ask`, or by
returning from the lifecycle that consumed the request. A plain `agents/send`
does not fill the slot.

## Public functions

| Function | Purpose |
| --- | --- |
| `(agents/spawn prompt)`, `(agents/spawn prompt handle)`, `(agents/spawn agent prompt)`, `(agents/spawn agent prompt handle)` | Start a background agent and return its handle. Prompt-only forms use the current compiled agent; `agent` may be a configured `workers/` agent. |
| `(agents/!spawn-ask prompt)`, `(agents/!spawn-ask prompt handle)`, `(agents/!spawn-ask agent prompt)`, `(agents/!spawn-ask agent prompt handle)` | Spawn one agent and sleep until its lifecycle returns. |
| `(agents/!spawn-ask [spec ...])` | Spawn several agents concurrently and sleep until all return. Each spec is a prompt, `[prompt handle]`, `[agent prompt]`, or `[agent prompt handle]`. |
| `(agents/send target value)` | Send a plain message and awaken the target. No edge is created or discharged. |
| `(agents/reply message value)` | Answer an actionable request and fill the replying agent's target slot. It does not create a reverse wait. |
| `(agents/!ask target value)` | Send an actionable request, create one outgoing edge, and sleep for the result. |
| `(agents/!ask target)` | Send a bodyless actionable request to one target and sleep for the result. |
| `(agents/!ask [target ...])` | Send a bodyless request to each target, create one all-target edge, and sleep until every slot is filled. |
| `(agents/!reply-ask message value)` | Reply and create a reverse request, keeping the conversation open while the caller sleeps for the next response. |
| `(agents/!sleep)` | Sleep again on retained outgoing edges without sending a message or creating an edge. |
| `(agents/cancel edge-id)` | Cancel one pending outgoing edge owned by the caller. Targets continue running. |
| `(agents/status)`, `(agents/status handle)` | Inspect the caller with edge summaries, or inspect a node's lifecycle state and generation. |
| `(agents/graph)` | Return a read-only snapshot of nodes and pending hyperedges. |
| `(agents/out-edges)` | Inspect the caller's pending outgoing edges, slots, and collected results. |
| `(agents/in-edges)` | Inspect live edges containing a slot for the caller, including a filled slot on an otherwise pending multi-target edge. |
| `(agents/current-handle)` | Return the caller's handle. |
| `(agents/parent-handle)` | Return the handle that spawned the caller, or `nil` for the main agent. |
| `(agents/send-msg-fn macro handle)` | Low-level message-macro transport. Prefer `send`, `reply`, or the request functions in ordinary programs. |

`!ask`, `!reply-ask`, `!spawn-ask`, and `!sleep` end the current active turn by
putting the caller to sleep. Expressions placed after one of these calls in the
same trailing expression do not run before the next turn.

## Messages and replies

Messages arrive as bindings such as:

```clojure
(def msg-0 {:from :reviewer
            :body "The example is correct."})
```

An actionable request also contains `:expects-response true` and an `:edge-id`.
Pass the received message map to `agents/reply` or `agents/!reply-ask`; programs
normally do not manipulate its edge ID directly.

`agents/reply` fills the request's slot. A stale, duplicate, or cancelled
actionable request is a no-op, so it cannot create a second notification.
Calling `reply` on a singleton completion report sends an ordinary message back
to its sender. A multi-target completion report has multiple senders and cannot
be replied to as one message; choose a target and start a new request.

`agents/!reply-ask` keeps a two-way conversation open. For an actionable
request, it fills the old slot and creates one reverse edge. For a singleton
completion report, it creates the reverse request directly. In either case the
other agent receives one actionable message.

## Lifecycle completion and failure

When an agent lifecycle returns normally, its return value fills each pending
incoming slot claimed by that lifecycle. A request slot is claimed when the
target consumes the corresponding request; this prevents an older, unrelated
lifecycle return from satisfying a request it never saw. Slots created by
`!spawn-ask` are associated with the spawned lifecycle before it starts.

Reader and evaluation recovery remain internal to the child. A malformed
program can receive recovery turns without filling a result slot or notifying
the parent. If recovery succeeds, the eventual lifecycle return is the result.

If an exception escapes recovery and terminates the lifecycle, the slot is
filled with reader-safe data shaped like:

```clojure
{:spell/child-failure true
 :handle :reviewer
 :phase :execution
 :exception {:spell/exception true
             :class "clojure.lang.ExceptionInfo"
             :message "..."
             :data {...}}}
```

This makes an unrecoverable lifecycle failure distinguishable from a legitimate
`nil` return. A child that successfully returns `nil` still fills its slot with
`nil`.

Finishing also cancels the agent's unfinished outgoing edges and records the
abandoned collection in the trace. Cancellation detaches only those waiting
obligations: it does not interrupt the target agents or their descendants.
Their work can continue, but the cancelled edges no longer collect their
results or awaken the finished source. They may still use ordinary messages if
the destination handle remains available.

A persistent handle can be awakened after its current lifecycle is finished.
The new lifecycle has a new generation, keeping its results separate from
requests consumed by an earlier lifecycle.

## Waiting patterns

### Wait for all targets

Use one multi-target edge. This example starts two agents concurrently and
wakes once both lifecycle results are available:

```clojure
'(agents/!spawn-ask
   [["Review the API descriptions." :api-reviewer]
    ["Review the examples." :example-reviewer]])
```

The completion report's `:body` is a vector in target order:

```clojure
[{:from :api-reviewer :body api-result}
 {:from :example-reviewer :body example-result}]
```

For already-running agents, `(agents/!ask [:api-reviewer :example-reviewer])`
creates the same all-target shape and sends each target a bodyless request.

### Wake for whichever edge completes first

Separate edges are independent. Suppose the first request is already pending:

```clojure
'(agents/!ask :researcher-a "Investigate option A.")
```

An unrelated message can awaken the caller while that edge remains pending.
The caller can then create another edge:

```clojure
'(agents/!ask :researcher-b "Investigate option B.")
```

The caller wakes when either request completes. The other edge remains pending;
after processing the first result, the caller can keep working, cancel the
remaining edge by ID, or sleep again to await it.

### Handle an unrelated wakeup

If `msg-0` is unrelated to the result being awaited, handle it and then resume
the retained wait:

```clojure
(think "Handled msg-0; the reviewer result is still pending.")
'(agents/!sleep)
```

`!sleep` creates no edge and sends no message. It is permitted only when the
caller has a pending outgoing edge created strictly after its newest pending
incoming edge.

### Reply while keeping the conversation open

The requester starts with `!ask`:

```clojure
'(agents/!ask :reviewer "What needs changing?")
```

The reviewer answers the received request while asking a follow-up:

```clojure
'(agents/!reply-ask msg-0
   "The API example is ambiguous. Should I revise it?")
```

The requester can continue the exchange with another `!reply-ask`:

```clojure
'(agents/!reply-ask msg-1 "Yes; preserve the existing function names.")
```

Either side closes the request-response chain with `agents/reply`:

```clojure
'(agents/reply msg-2 "Revised and checked.")
```

## Deadlock avoidance

Wait-graph operations are well ordered and topologically reversible. Edges
receive a monotonically increasing creation order. Installing an edge and
sleeping is reversed as its targets fill their slots; cancelling or finishing
removes waiting obligations; sending awakens a node without deleting its
edges.

An awake agent may become asleep only when it has a pending outgoing edge
created **strictly after** its newest pending incoming edge. Creating an edge
also awakens its targets. Together, these rules ensure that waiting introduced
through the `agents/` protocol cannot leave every participant asleep solely
because of a cycle in the wait graph.

This is a communication-topology guarantee, not a guarantee that arbitrary
agent programs make progress. An agent can still fail to reply, wait for an
external event that never occurs, exhaust provider retries, or otherwise stop
doing useful work. Inspection and cancellation make those application-level
conditions visible and recoverable by the program.
