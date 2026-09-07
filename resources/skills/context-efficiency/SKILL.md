---
name: context-efficiency
description: Minimize context-window usage during long-running or tool-heavy work when context is growing by pruning disposable results, persisting essentials, rethinking conclusions, and compacting reasoning.
---

CONTEXT EFFICIENCY — Minimize total context window usage.

Context tokens are scarce. Preserve the evidence and receipts needed for the next action before pruning disposable context.

Prefer !peek over !call-now for disposable tool calls (it appends pruning that removes the command and binding on the following extension):
    '(!peek data (io/glob "*.py" "src" {:max-depth 2}))

On the subsequent turn, persist what you need before extending:
    ;; end of turn 1 completion
    (def data {:exit 0 :out "src/main.py\nsrc/util.py" :err ""})
    (prune 2)
    ;; start of turn 2 suffix
    ;; data is still in scope here
    (persist glob-receipt {:exit (:exit data) :err (:err data)})
    (persist targets
      (if (= 0 (:exit data))
        (take 5 (strings/split-lines (:out data)))
        nil))
    ;; Nonzero :exit is a failed search, not evidence of no matches.
    '(!extend)
    ;; next turn: the !peek call and data are pruned; targets survive as literals

## Retrieve retained output before another read

Stored output is lossless retrieval, not a reason to refetch. A stored marker or preview is not evidence that the hidden body was inspected. Use the original retained binding if available; otherwise use `(stored "ID-from-the-observed-marker")`. Keep that observed ID as literal data before pruning. Never invent an ID or repeat the original tool effect merely to recover its output.

`!print` accepts ONE expression; `!call-now` and `!peek` accept result-name/expression pairs. For an already retained string `body`, an illustrative first page under the default 10k output cap is about 900 UTF-16 code units (not a fit guarantee):
    '(!print (subs body 0 (min 900 (count body))))
For a stored string (replace the illustrative ID with the observed ID):
    (def result-id "ID-from-the-observed-marker")
    '(!print (let [body (stored result-id)]
               (subs body 0 (min 900 (count body)))))
For an `io/read-lines` vector retained as `lines`, use:
    '(!print (subvec lines 0 (min 2 (count lines))))
Advance offsets through the same original value for subsequent pages. Use `subs` for strings and `subvec` for vectors; for a result map, select the documented text/vector field first. If one line is too large, page that line as a string. Budget the aggregate rendered packet, including all bindings, labels, syntax and escaping, within the existing cap; 900 UTF-16 units or two lines is only an illustrative starting page, never a fit guarantee. For tight caps, heavy escaping or aggregate overhead, reduce the page (for example, to 16 units) and inspect one value at a time. If the value cannot be retrieved, report the missing evidence rather than claiming inspection or blindly refetching.

Before `!peek` pruning, persist the exact evidence needed for the next artifact and actual effect receipts. Carry IDs, offsets and a literal checkpoint through compaction or a fresh self-call. Proposed calls, plans and sent flags are not proof that edits, tests or dispatches executed.

When running a disposable verification command, keep it inside !peek:
    '(!peek verify (io/sh "python /tmp/verify.py"))
    ;; end of turn 1 completion (illustrative successful result)
    (def verify {:exit 0 :out "Both edge cases passed." :err ""})
    (prune 2)
    ;; start of turn 2 suffix
    (persist verification-receipt
      {:command "python /tmp/verify.py" :exit (:exit verify)
       :out (:out verify) :err (:err verify)})
    ;; This illustrative output is short. For a large result, retain the actual
    ;; test-count/failure summary and the stored ID, not an invented success.
    '(!extend)
    ;; next turn: raw command/result are gone; the observed receipt survives

When you need to rerun a script later, write it to disk first and then call it with !call-now.

After extended reasoning, rethink to compress:
    (think "Long analysis of the bug... examining stack traces, testing hypotheses... the root cause is in parse_args line 42.")
    (rethink "The bug is in parse_args, line 42: off-by-one in the loop bound.")
    '(!extend)

When context grows large, compact:
    '(!compact)

Plan-clear pattern — start fresh only with a self-contained task, evidence and plan:
    ;; Illustrative checkpoint: use facts and receipts actually observed.
    (def checkpoint {:task "Fix the calculator boundary bug."
                     :source "calc.py:12 and tests/test_calc.py:20-26"
                     :finding "The inspected test expects zero for empty input."
                     :next-artifact "Boundary guard and regression test"
                     :check "python -m pytest tests/test_calc.py -q"
                     :receipts []})
    '(!llm-self
       (wrap-cat (list 'def 'checkpoint (list 'quote checkpoint))))
    ;; The next prefix contains a literal checkpoint, not stale local references.

Each extension should carry forward only what the next step needs.

## Bounded read packets and a durable decision record

A smaller context is not progress by itself: pruning the evidence and then fetching it again can make a read loop cheaper per turn but unbounded overall. Use this task-level workflow, not a runtime retry, deduplication, or progress policy.

- Before reading, name one unresolved question and the decision or edit it will enable. Choose exact paths, symbols, line ranges, and an aggregate output budget up front (for example, roughly 3,000 rendered characters across the packet, including syntax and escaping, not a guaranteed inline threshold). Prefer exact `io/read-lines` ranges or a targeted `io/grep` with documented `:include`, `:max-count`, and small `:context` options over whole reports or broad documentation dumps. `:max-count` is per file, not a total output cap; narrow paths as well.
- Use `!peek` for the packet. Before its pruning takes effect, preserve exact useful slices with `persist` and write a compact action checkpoint: a literal `(def checkpoint {...})` after the packet's prune marker, or `(persist checkpoint expression)` for computed values. Include source path/range and revision if known, finding, decision, remaining uncertainty, named next artifact/edit, and verification command. A `think` alone or a `def` referring to soon-pruned bindings is not portable. Keep the record explicitly through subsequent edits. Preserve evidence of executed effects separately from proposed actions; a plan or board post is not an execution receipt for a proposed edit or test.
- Once the next action is known, produce the named artifact (patch, prototype, test, or report) or report a specific blocker before overlapping rereads. A blocker identifies the missing contract/evidence and the smallest request or experiment needed. Do not repeat a read merely because raw output was pruned. Reopen a source only for a named new question, changed source, failed check, or unavailable evidence; state what the bounded packet adds.
- An opaque stored-result marker is not inspected evidence. Page the original binding or `(stored observed-id)` using the retrieval recipe above before considering another source read. Preserve only inspected facts and the observed ID/offset; report unavailable evidence if retrieval fails. A summary or preview does not replace inspection of the relevant original body. Do not restart the same broad describe/read sequence.
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
