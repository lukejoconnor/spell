---
name: context-efficiency
description: Minimize context-window usage during long-running or tool-heavy work when context is growing by pruning disposable results, persisting essentials, rethinking conclusions, and compacting reasoning.
---

CONTEXT EFFICIENCY — Minimize total context window usage.

Context tokens are your scarcest resource. Prune aggressively to stay effective over long tasks.

Prefer !peek over !call-now for disposable tool calls (it appends pruning that removes the command and binding on the following extension):
    '(!peek data (io/glob "**/*.py"))

On the subsequent turn, persist what you need before extending:
    ;; end of turn 1 completion
    (def data "... 200 lines ...")
    (prune 2)
    ;; start of turn 2 suffix
    ;; data is still in scope here
    (persist targets (take 5 (strings/split-lines data)))
    '(!extend)
    ;; next turn: the !peek call and data are pruned; targets survive as literals

When running a disposable verification command, keep it inside !peek:
    '(!peek verify (io/sh "python /tmp/verify.py"))
    ;; end of turn 1 completion (illustrative successful result)
    (def verify {:exit 0 :out "Both edge cases passed." :err ""})
    (prune 2)
    ;; start of turn 2 suffix
    (think "Verification passed: the fix handles both edge cases.")
    '(!extend)
    ;; next turn: both the command and result are gone

When you need to rerun a script later, write it to disk first and then call it with !call-now.

After extended reasoning, rethink to compress:
    (think "Long analysis of the bug... examining stack traces, testing hypotheses... the root cause is in parse_args line 42.")
    (rethink "The bug is in parse_args, line 42: off-by-one in the loop bound.")
    '(!extend)

When context grows large, compact:
    '(!compact)

Plan-clear pattern — reason and explore, then start fresh with a self-contained plan:
    (think "analyzing the problem..." ...)
    '(!peek files (io/ls "."))
    ;; end of turn 1 completion
    (def files [...])
    (def plan "Task: fix the calculator bug in calc.py\n1. Edit line 12: fix off-by-one\n2. Run tests")
    ;; start of turn 2 suffix
    '(!llm-self (wrap-cat plan))
    ;; next turn has only the plan as prefix — maximum working space

Each extension should carry forward only what the next step needs.

## Bounded read packets and a durable decision record

A smaller context is not progress by itself: pruning the evidence and then fetching it again can make a read loop cheaper per turn but unbounded overall. Use this task-level workflow, not a runtime retry, deduplication, or progress policy.

- Before reading, name one unresolved question and the decision or edit it will enable. Choose exact paths, symbols, line ranges, and an aggregate output budget up front (for example, roughly 3,000 characters across the packet, not a guaranteed inline threshold). Prefer exact `io/read-lines` ranges or a targeted `io/grep` with documented `:include`, `:max-count`, and small `:context` options over whole reports or broad documentation dumps. `:max-count` is per file, not a total output cap; narrow paths as well.
- Use `!peek` for the packet. Before its pruning takes effect, preserve exact useful slices with `persist` and write a compact action checkpoint: a literal `(def checkpoint {...})` after the packet's prune marker, or `(persist checkpoint expression)` for computed values. Include source path/range and revision if known, finding, decision, remaining uncertainty, named next artifact/edit, and verification command. A `think` alone or a `def` referring to soon-pruned bindings is not portable. Keep the record explicitly through subsequent edits. Preserve evidence of executed effects separately from proposed actions; a plan or board post is not an execution receipt for a proposed edit or test.
- Once the next action is known, produce the named artifact (patch, prototype, test, or report) or report a specific blocker before overlapping rereads. A blocker identifies the missing contract/evidence and the smallest request or experiment needed. Do not repeat a read merely because raw output was pruned. Reopen a source only for a named new question, changed source, failed check, or unavailable evidence; state what the bounded packet adds.
- An opaque stored-result marker is not inspected evidence. Do not infer reviewed contents or success from it. Narrow or summarize the retained binding only if the underlying data is accessible; otherwise request a smaller exact range or report the unavailable evidence. If output is partially visible, preserve only inspected facts. Do not restart the same broad describe/read sequence.
- When compacting or building a fresh `wrap-cat` prompt, explicitly carry the literal checkpoint, exact next action, constraints, and outstanding receipt obligations as source/data. Use fresh self-call locals; do not depend on hidden reasoning, stale bindings, or an external coordinator to reconstruct progress, and do not replay effects to recover a record. This guidance does not change coordinator invariants or receipt semantics.
- Shell examples in this skill assume the current agent exposes `io/sh`. Check available namespace documentation; use an exposed equivalent or report a concrete verification blocker if shell access is unavailable.

Example record after a disposable packet (replace these illustrative pointers with observed evidence):

```clojure
(persist focus (subvec source-lines 20 32))
(def checkpoint {:source "src/parser.clj:21-32"
                 :finding "Empty input reaches the indexing branch."
                 :decision "Add an empty-input guard."
                 :open "Confirm expected empty result in the existing test."
                 :next-artifact "src/parser.clj guard and one regression test"
                 :check "Run the targeted parser test."
                 :receipts []})
'(!extend)
```

The record is visible ordinary program data. Bounds and escalation remain model-controlled task decisions, not hidden harness counters, automatic retries, or read suppression.
