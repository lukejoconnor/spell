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
    (def data {:ok true :exit 0 :out "src/main.py\nsrc/util.py" :err nil :truncated false})
    (prune 2)
    ;; start of turn 2 suffix
    ;; data is still in scope here
    (persist glob-receipt (select-keys data [:ok :exit :err :truncated]))
    (persist targets
      (if (= 0 (:exit data))
        (take 5 (strings/split-lines (:out data)))
        nil))
    ;; Nonzero :exit is a failed search, not evidence of no matches.
    '(!extend)
    ;; next turn: the !peek call and data are pruned; targets survive as literals

## Work with bounded snapshots and explicit reads

Context tokens are scarce. Carry forward only the evidence and actual receipts needed next.

FULL COMPUTATION, BOUNDED SNAPSHOT

Raw effects return full ordinary values unless you explicitly request a range or limit. At !call-now, !peek, !print, explicit serialize, and incoming communication bodies, Spell inserts an ordinary bounded snapshot. The next-turn binding IS that snapshot, not a hidden full original. Omission strings/entries are ordinary data and missing evidence. There is no automatic result store, retrieval ID, or invisible backing value. Compute counts, reductions, and field selection inside the effect expression before insertion:
    '(!call-now summary
       (let [r (io/read-lines "src/server.py")]
         (if (:ok r) {:ok true :line-count (count (:out r))} r)))

The default target is 10000 reader-rendered UTF-16 code units PER OUTPUT, not a shared multi-binding budget. A leading {:max-chars N} overrides it for !call-now, !peek, and !print; (serialize value N) explicitly chooses a view. N is an integer >=128; nil uses the run default; negative/noninteger/smaller values are invalid. An override may exceed the default. Outputs up to 120% of the target remain whole; larger ones shorten toward the target including escaping and omission markers. Structural/envelope minimum overhead may exceed small targets. Independent siblings do not share one cap. Exact lifecycle/request/ack metadata remains outside bounded message bodies.

Strings retain surrogate-safe head/tail where feasible. Collections retain ordinary kinds with omission data; map keys are not shortened into collisions. Lazy realization and depth are bounded; infinite sequences are not counted/exhausted for a tail. Opaque host values (including live futures) become truthful diagnostics, not retrievable handles. Retained snapshots render unchanged across later continuations, recursive containers and persist; explicit serialization may choose a new view.

EXTERNAL RESULT SHAPES

Operational IO/web/MCP results use {:ok boolean :out payload :err text-or-nil :truncated boolean}, plus :exit for processes and :status for HTTP when known. Clipping sets :truncated true without changing :ok/:exit/:status. Third-party structured payloads stay under :out. Inspect failure/truncation before treating output as complete. Process stdout/stderr preserve whitespace; empty stderr is nil. grep exit 1 with empty stderr is valid no-match. Launch failures have :exit nil; existing timeouts retain :exit -1 and :truncated true for incomplete output.

io/read-file returns plain text in :out; io/read-lines returns string rows with source positions in :out; io/ls returns its entry vector in :out. IO writes/edits return path receipts in :out. web/search returns a result vector, web/fetch full text by default, and web/config effective config, all under :out. MCP operational results retain full attributed structured payloads there. Narrow exceptions keep existing raw/control values: io/exists?, io/directory?, io/cwd, io/env; executable io/sh-test thunks; asynchronous watchers/listeners; cached MCP servers/resources/resource-templates/prompts listings. Check namespace docs for exact signatures and error exceptions.

READ AND RETAIN DELIBERATELY

Use !peek for disposable reads/tests; use !call-now for receipts or short critical results needed later. !call-now and !peek take name/expression pairs; Prefer one value expression for !print (a map/vector can combine fields); it also accepts multiple value expressions for compatibility, never name/expression pairs. For a result map, select its documented :out field before subs/subvec.

io/read-file and io/read-lines support [path], [path start end], [path opts], and [path start end opts]. Source lines are ONE-BASED HALF-OPEN [start,end); subvec indices are LOCAL ZERO-BASED HALF-OPEN. Past EOF is empty. Range selection alone is not truncation. Request a focused range rather than assuming a broad read's snapshot still contains every line:
    '(!peek page (io/read-lines "src/server.py" 181 191))
    ;; next turn, after checking :ok and :truncated:
    (persist receipt (select-keys page [:ok :err :truncated]))
    (persist focus (subvec (:out page) 0 (min 2 (count (:out page)))))
    '(!extend)
    ;; page is pruned; focus keeps the inspected rows and supported source positions.

Numbered values remain vectors of strings. first-line can encode start/vector pairs and nil/gap-string rows; omission gaps have no invented line coordinate. subvec remaps supported positions, including nested and persisted vectors. Individual long lines may be clipped too. For giant lines, request per-line zero-based half-open UTF-16 character windows:
    '(!peek page (io/read-lines "data.txt" 37 38 {:char-start 0 :char-end 900}))
    '(!peek next-page (io/read-lines "data.txt" 37 38 {:char-start 900 :char-end 1800}))
Character omissions set :truncated true. Surrogate-splitting boundaries move inward. read-file preserves original terminators; read-lines strips them while retaining selected string rows. Each later file read is fresh evidence of CURRENT contents, not retrieval of the earlier value.

Never replay an effect merely to recover omitted output. Save exact values deliberately to a file or an exposed globals entry if later access is required; omitted content is otherwise unavailable. Preserve inspected excerpts, paths, source coordinates, next offsets, literal checkpoints, pending obligations and actual effect receipts before !peek pruning or compaction. Proposed calls/plans/sent flags do not prove execution. Do not claim inspection of omitted content or repeat broad reads just because context was pruned.

Example: preserve a test receipt while discarding verbose output
    '(!peek test-out (io/sh "uv run pytest tests/test_api.py -x -q"))
    ;; next turn: inspect :ok, :exit, :err, :out and :truncated first
    (persist test-receipt (select-keys test-out [:ok :exit :err :truncated]))
    (think "Record the actual failing test and next patch, not an assumed success.")
    '(!extend)

When a known next action exists, produce the artifact or report a specific blocker before overlapping rereads. Use rethink for a concise evidence-backed checkpoint, persist for exact useful excerpts, and !compact when context grows large. A fresh wrap-cat prompt must explicitly carry the task, constraints, checkpoint and unresolved receipt obligations.

When running a disposable verification command, keep it inside !peek:
    '(!peek verify (io/sh "python /tmp/verify.py"))
    ;; end of turn 1 completion (illustrative successful result)
    (def verify {:ok true :exit 0 :out "Both edge cases passed." :err nil :truncated false})
    (prune 2)
    ;; start of turn 2 suffix
    (persist verification-receipt
      {:command "python /tmp/verify.py" :exit (:exit verify)
       :out (:out verify) :err (:err verify)
       :ok (:ok verify) :truncated (:truncated verify)})
    ;; This illustrative output is short. For a large result, retain the actual
    ;; test-count/failure summary and an explicitly saved log path, not an invented success.
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

- Before reading, name one unresolved question and the decision or edit it will enable. Choose exact paths, symbols, line ranges, and a model-chosen aggregate read budget up front (independent per-output host targets are not a shared packet cap) (for example, roughly 3,000 rendered characters across the packet, including syntax and escaping, not a guaranteed inline threshold). Prefer exact `io/read-lines` ranges or a targeted `io/grep` with documented `:include`, `:max-count`, and small `:context` options over whole reports or broad documentation dumps. `:max-count` is per file, not a total output cap; narrow paths as well.
- Use `!peek` for the packet. Before its pruning takes effect, preserve exact useful slices with `persist` and write a compact action checkpoint: a literal `(def checkpoint {...})` after the packet's prune marker, or `(persist checkpoint expression)` for computed values. Include source path/range and revision if known, finding, decision, remaining uncertainty, named next artifact/edit, and verification command. A `think` alone or a `def` referring to soon-pruned bindings is not portable. Keep the record explicitly through subsequent edits. Preserve evidence of executed effects separately from proposed actions; a plan or board post is not an execution receipt for a proposed edit or test.
- Once the next action is known, produce the named artifact (patch, prototype, test, or report) or report a specific blocker before overlapping rereads. A blocker identifies the missing contract/evidence and the smallest request or experiment needed. Do not repeat a read merely because raw output was pruned. Reopen a source only for a named new question, changed source, failed check, or unavailable evidence; state what the bounded packet adds.
- An omission is missing evidence, not a hidden retrievable body. Use the snapshot already present for inspected facts; request a focused current-file range only for a named new question. Preserve exact earlier values explicitly if required. Never replay an effect merely to recover omitted output.
- When compacting or building a fresh `wrap-cat` prompt, explicitly carry the literal checkpoint, exact next action, constraints, and outstanding receipt obligations as source/data. Use fresh self-call locals; do not depend on hidden reasoning, stale bindings, or an external coordinator to reconstruct progress, and do not replay effects to recover a record. This guidance does not change coordinator invariants or receipt semantics.
- Shell examples in this skill assume the current agent exposes `io/sh`. Check available namespace documentation; use an exposed equivalent or report a concrete verification blocker if shell access is unavailable.

Example record after a disposable packet (replace these illustrative pointers with observed evidence):

```clojure
(persist focus (subvec (:out source-lines) 20 32))
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
