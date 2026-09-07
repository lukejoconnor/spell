# Context/store/board benchmark audit

Status: COMPLETE. The final on-disk module compiles and passes eight tiny namespace-only correctness cases, clearable-fixture assertions, and an injected-contention-failure cleanup check. Final command exit 0 with empty stderr. The earlier deps.edn blocker is resolved. No harness or performance measurements were run by this worker.

## Owned files
- `perf/spell/perf/context.clj` — namespace `spell.perf.context`, five scenario maps.
- `perf/audit-context.md` — implementation evidence and limitations.

## Design and selected parameters
1. `:context-render-edit`: 20/100 operations × 64/8192-character payload; contribution serialization, parsing, retrieval, and source pruning in one timed workload. The same payload object is reused deliberately.
2. `:context-pruned-storage`: 20/100 unique 8192-character payloads; one context; prune all source references while asserting each retrieval and final store count.
3. `:globals-contention`: 4/20 host futures with equal total work (2000 updates), a common start gate, a shared 10-second join deadline, cancellation/joins in finally, exact final counter.
4. `:board-post-digest`: 20 posts/retention 8/page 3 or 60/20/7; 1024-character bodies; actual native board interpreter adapter, duplicate read-only digest check, explicit retention gap, all IDs, exact token acknowledgements.
5. `:board-captured-snapshot`: paired board/receipt variants at 20/60 posts, retention 8, page 3, 8000-character unique bodies. Each cycle updates an audit marker and serializes either the returned board or a small receipt. Expected context entries are posts versus zero; reachable distinct evidence bodies are posts versus min(posts, retention).

## Isolation and retention discipline
Every measured board/store is created by host Clojure `g/new-store`, bound only within setup/measure; no measured fixture uses the live :audit board. Setup initializes its own board exactly once and clears on failure. Context values, global state, payload holders, and future holders are clearable mutable references. Harness is expected to invoke cleanup in finally after independent wall/live-memory samples. Returned evidence contains only scalars and small maps, never stores, strings, snapshots, futures, or payload vectors. Temporary retrievals and source forms are discarded before live memory sampling.

## Measurement limitations
- Parent owns all pilot/full timings, allocation, RSS, and GC sampling. This worker will only compile its namespace and execute tiny correctness probes, never load the harness.
- Current-calling-thread allocation excludes allocations in contention futures. These are host workers, not LLM agents. Equal total work avoids conflating 4/20 workers with operation count.
- Source pruning does not delete context-store entries; retained values demonstrate live per-run storage, not cross-run leakage.
- Repeated render IDs can share the same payload object; unique render/prune cycles are the distinct-payload retention experiment.
- Snapshot capture is the intentional old anti-pattern; current compact-receipt guidance does not require full-board capture. Persistent snapshots share structures and board source code. Character totals are evidence, not byte estimates.
- Explicit GC/RSS observations are noisy. Live fixture retention and post-cleanup behavior must be labelled separately.

## Source/API provenance
Checked source packet for HEAD `67b7cf7ba919a9dca9c78a5b9e9e0ed5116d5c1f`: context APIs at `src/spell/context.clj:10–35,154–209`; edit/eval APIs at `src/spell/eval.clj:737–771,1046`; parse API at `src/spell/parse.clj:142`; globals at `src/spell/globals.clj:17–35`; native board adapter from `test/spell/mailing_list_test.clj:13–35`; board implementation `config/spl-lib/patterns.spl:1290–1460`. No source reads were needed for the first draft.

## Historical handoff and subsequent reviewer status
At this worker's handoff, parent review and pilot/full measurements were still pending. Parent subsequently completed the 14-case pilot and 80/80-case baseline; the outer reporting run then failed. The separate report-only fresh review is recorded in fresh-review.md. The command below remains this worker's own bounded verification; no later harness measurements or review are retroactively attributed to this worker.

## Final executed verification

The command below ran against the final source through `io/sh` with `{:timeout 120}`. Result: exit 0, empty stderr. It requires only `spell.perf.context`, never the harness. Eight tiny cases cover both render sizes, unique pruned storage, both contention worker counts, board pagination/retention, and both capture variants. A ninth check injects a contention update failure and verifies fixture cleanup. The final completion-barrier implementation was present for this successful run.

```sh
clojure -Sdeps '{:paths ["src" "resources" "config" "test" "perf"]}' -M -e "(require '[spell.perf.context :as c]) (println :context-compiled) (doseq [s c/scenarios p (case (:id s) :context-render-edit [{:operations 2 :payload-chars 64} {:operations 2 :payload-chars 512}] :context-pruned-storage [{:cycles 3 :payload-chars 512}] :globals-contention [{:workers 4 :total-updates 40} {:workers 20 :total-updates 40}] :board-post-digest [{:posts 5 :retention 3 :page-size 2 :body-chars 64}] :board-captured-snapshot [{:capture :board :posts 5 :retention 3 :page-size 2 :body-chars 512} {:capture :receipt :posts 5 :retention 3 :page-size 2 :body-chars 512}])] (let [fixture ((:setup s) p)] (try (println (:id s) p ((:measure s) p fixture)) (finally ((:cleanup s) fixture) (when-let [context (:context fixture)] (assert (empty? @(:values context)))) (when-let [store (:store fixture)] (assert (empty? @store))) (when-let [payload (:payload fixture)] (assert (nil? @payload))) (when-let [futures (:futures fixture)] (assert (empty? @futures))))))) (println :tiny-cases-and-cleanup-passed) (let [s (first (filter #(= :globals-contention (:id %)) c/scenarios)) p {:workers 20 :total-updates 40} fixture ((:setup s) p)] (try (assert (try (with-redefs [spell.globals/update-val (fn [& _] (throw (ex-info \"Injected update failure\" {})))] ((:measure s) p fixture)) false (catch java.util.concurrent.ExecutionException _ true))) (assert (empty? @(:futures fixture))) (finally ((:cleanup s) fixture) (assert (empty? @(:store fixture))) (assert (empty? @(:futures fixture)))))) (println :injected-contention-failure-cleaned) (System/exit 0)"
```

Exact successful stdout:

```text
:context-compiled
:context-render-edit {:operations 2, :payload-chars 64} {:render-chars 148, :max-render-chars 74, :operations 2, :payload-chars 64, :contexts 1, :stored-count 0, :same-payload-object true, :edited-forms-per-operation 1}
:context-render-edit {:operations 2, :payload-chars 512} {:render-chars 110, :max-render-chars 55, :operations 2, :payload-chars 512, :contexts 1, :stored-count 2, :same-payload-object true, :edited-forms-per-operation 1}
:context-pruned-storage {:cycles 3, :payload-chars 512} {:cycles 3, :contexts 1, :stored-count 3, :retained-payload-chars 1536, :surviving-stored-references 0}
:globals-contention {:workers 4, :total-updates 40} {:workers 4, :updates-per-worker 10, :counter 40, :stores 1, :equal-total-work true, :allocation-excludes-workers true}
:globals-contention {:workers 20, :total-updates 40} {:workers 20, :updates-per-worker 2, :counter 40, :stores 1, :equal-total-work true, :allocation-excludes-workers true}
:board-post-digest {:posts 5, :retention 3, :page-size 2, :body-chars 64} {:read-only-digest true, :retained-body-chars 192, :digest-messages 3, :evidence-bodies 3, :nonempty-pages 2, :stores 1, :high-water 5, :posts 5, :retained-messages 3, :gap-count 2}
:board-captured-snapshot {:capture :board, :posts 5, :retention 3, :page-size 2, :body-chars 512} {:read-only-digest true, :stored-count 5, :digest-messages 3, :evidence-bodies 5, :nonempty-pages 2, :stores 1, :high-water 5, :reachable-body-chars 2560, :capture :board, :contexts 1, :posts 5, :retained-messages 3, :gap-count 2}
:board-captured-snapshot {:capture :receipt, :posts 5, :retention 3, :page-size 2, :body-chars 512} {:read-only-digest true, :stored-count 0, :digest-messages 3, :evidence-bodies 3, :nonempty-pages 2, :stores 1, :high-water 5, :reachable-body-chars 1536, :capture :receipt, :contexts 1, :posts 5, :retained-messages 3, :gap-count 2}
:tiny-cases-and-cleanup-passed
:injected-contention-failure-cleaned
```

## Concrete findings

- Two small renders add zero stored entries; two oversized renders of the same payload add two IDs, not two independently allocated payload objects.
- Three unique 512-character oversized values remain in the context store after their source references are pruned: three stored values, 1536 payload characters, zero surviving source references.
- Four and twenty host workers both reach counter 40 for equal total tiny work.
- Five posts with retention three yield retained IDs 3–5 and a visible gap covering IDs 1–2. Two nonempty digest pages are read and acknowledged with their actual tokens; duplicate reads do not advance the cursor.
- With identical five-post workloads, full-board capture retains five context entries and five distinct evidence bodies (2560 body characters). Compact receipts retain zero context entries and only the three current evidence bodies (1536 body characters).
- These are correctness/structural-retention observations, not measured heap deltas or performance conclusions.

## Failure-path and fixture discipline

Final contention teardown tracks each task's pending/running state and a completion promise. Cancelling a pending task explicitly establishes that its body cannot start; running tasks signal completion in finally. This avoids treating cancelled-Future status as proof that its body terminated. Normal joins use a shared 10-second deadline; teardown uses a shared two-second completion deadline and throws on timeout. Holders are cleared and stores are cleared by fixture cleanup even on failure. The injected-update-failure check passed. Timeout and uninterruptible-worker behavior were not injected or measured. Scenario code never calls shutdown-agents.

## Development failures and resolution

1. Initial source had an extra closing parenthesis in a snapshot assertion; fixed before the successful compile.
2. Initial compact-receipt assertion compared parsed source with a map. A targeted executable probe showed serialization emits `(quote {:customized :digest})`; the final assertion evaluates that source with `ev/spell-eval`, checks `ev/ok?`, and compares its value. Compact serialization adds zero context entries. This was an assertion assumption, not a demonstrated runtime defect.
3. Final verification was temporarily blocked before namespace loading by `Error building classpath. Error reading edn. Invalid number: 1: (.../deps.edn)`. This worker did not modify the non-owned dependency file. Core's audit ID 11 reported root's repair and an actual successful rerun; the final context command above then independently succeeded. Earlier blocked handoff reports/posts are superseded by this final evidence.

## Coordination and dogfooding notes

Audit summaries through ID 11 were processed with no observed gap; the exact latest digest token was acknowledged, returning cursor 11. Incoming completion notifications superseded several proposed actions. Targeted owned-file reads and actual digest cursors established nonexecution before retry; proposed sent flags were not treated as receipts. Prior blocker and handoff posts have actual receipts. The final tracked lifecycle result supplies the successful resolution.

A feedback/log :friction entry records that post! also notified its author and that notifications can supersede proposed ack/edit/probe chains. This is ergonomic friction, not a demonstrated correctness bug. The feedback tool reported its configured destination outside the worktree: `/Users/loconnor/Dropbox/GitHub/spell/notebook/results/spell-runs/2026-09-07-runtime-audit-002/feedback.edn`. This worker did not intentionally select that destination; the tool-mediated outside-worktree write is disclosed here. Implementation/report edits were confined to the two owned files; no Git mutations, runtime optimizations, provider/model/effort changes, delegated review, or harness runs were performed.

## Remaining limits

Only tiny standalone correctness cases were executed by this worker. The listed 20/60/100-cycle and 2000-update benchmark parameters remain for parent-owned pilots/full measurements and fresh review. No heap/RSS/allocation/timing claims are made from these checks. The successful command explicitly exits its standalone process after all checks; scenario functions themselves do not terminate the process or global agent pools.
