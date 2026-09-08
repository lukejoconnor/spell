# Lifecycle audit module

Owned implementation: `perf/spell/perf/lifecycle.clj` (`spell.perf.lifecycle`).

## Implementation and provenance

First draft implemented directly from the checked source packet for revision `67b7cf7ba919a9dca9c78a5b9e9e0ed5116d5c1f`, without exploratory source reads. Packet anchors: `coordinator.clj:68-236,372`, `runtime.clj:309,329,476,507-573`, `api.clj:121-149`, and `test_helpers.clj:10-64`.

Four exported scenarios:
- `:lifecycle-coordinator`: coordinator-only, one N-slot hyperedge, ordered fanin, legal wait signal, and FIFO batches. 4/20 agents, 1/10 cycles, 16/256 messages per target per cycle.
- `:lifecycle-spawn-return`: actual runtime future spawn-ask, tracked parent wait, ordered response and child retirement. Deterministic metadata-marked host callback, not evaluator/model execution. 4/20 agents, 1/10/100 cycles.
- `:lifecycle-dormant-wake`: actual persistent start-box/orphan wake/finish paths and inbox rewriting using a deterministic eval callback. 4/20 agents, 1/10/100 cycles. Live context/coordinator/globals remain strongly reachable through the clearable fixture until harness cleanup.
- `:lifecycle-api-cleanup`: 1/10/100 public API runs with deterministic TestProvider and real compiler/evaluator. Checks 42, absence of :error, closed coordinator, no edges, and adjacent-run identity changes for context/coordinator/globals.

## Method boundaries

Every host fixture allocates its own stores/coordinator; nothing initializes, resets or measures the live audit board. All returned evidence is small, realized scalar maps, never snapshots, usage atoms or raw completions. Setup failures close their fixture. Cleanup is idempotent and clears strong references. API observers hold at most adjacent runs and release references in finally before memory inspection.

All new dereference/settling waits are deadline-bounded to 5 seconds. The API itself has no total-run timeout; external process timeout is mandatory. Predicated 1ms backoff is only for state settling, not an arbitrary sleep used as readiness evidence. Wall time includes scheduling, blocking and checks, not CPU alone. Caller-thread allocation excludes worker allocation. Coordinator close does not join orphan execution or immediately empty agent entries; shared future pools retain idle threads. No arbitrary host cancellation or immediate thread-count recovery is claimed. Finished/runner-nil is a coordinator-state observation, not proof every stack frame exited. This is a finite accelerated proxy, not a 4–48 hour soak test.

## Verification

PASS: namespace-only compile/correctness probe, `io/sh` timeout 120 seconds, exit 0, empty stderr. Eleven cases cover the smallest parameters, 4-agent/two-cycle reuse, 20-agent/one-cycle paths, and one/two API runs. Every fixture cleanup was invoked twice and its strong-reference holder asserted nil. No harness or performance measurements were run; parent owns pilot/full measurements, including 10/100-cycle cases and larger mailbox batches.

Exact successful command (run from the worktree root under `io/sh` with `{:timeout 120}`):

```sh
clojure -J-Dclojure.main.report=stderr -Sdeps '{:aliases {:audit-check {:extra-paths ["perf" "test"]}}}' -M:audit-check -e '(require (quote [spell.perf.lifecycle :as l])) (try (doseq [s l/scenarios p (if (= :lifecycle-api-cleanup (:id s)) [{:cycles 1} {:cycles 2}] [(first (:params s)) (assoc (first (:params s)) :cycles 2) (assoc (first (:params s)) :agents 20)]) :let [f ((:setup s) p)]] (try (prn (:id s) ((:measure s) p f)) (finally ((:cleanup s) f) ((:cleanup s) f) (assert (nil? @f))))) (println "LIFECYCLE-FINAL-CORRECTNESS-OK") (finally (shutdown-agents)))'
```

Representative successful output:

```clojure
:lifecycle-coordinator {:agents 20, :cycles 1, :hyperedges 1, :reply-slots 20, :mailbox-messages 320, :remaining-edges 0}
:lifecycle-spawn-return {:agents 4, :cycles 2, :spawned 8, :callbacks 8, :remaining-edges 0, :remaining-children 0}
:lifecycle-dormant-wake {:agents 20, :cycles 1, :callbacks 20, :generation-increments 20, :dormant 20, :active-runners 0, :remaining-edges 0}
:lifecycle-api-cleanup {:cycles 2, :successful-runs 2, :closed-coordinators 2, :adjacent-isolation-checks 3}
LIFECYCLE-FINAL-CORRECTNESS-OK
```

One targeted post-draft source check of `src/spell/coordinator.clj:57-80` confirmed initial `:generation` 1 and increment on waking a finished identity. Dormant checks now assert each agent's generation after every cycle, not just callback counts.

Probe-command corrections: the first command incorrectly placed `:extra-paths` at top level in `-Sdeps`, so the namespace was not on the classpath; the second had an unbalanced probe expression. Both were corrected before the successful checks. These were command-construction errors, not observed Spell runtime failures. The two failed Clojure invocations automatically wrote error reports to the OS temporary directory, an incidental outside-worktree side effect; subsequent probes use `-J-Dclojure.main.report=stderr`. No intentional outside-worktree edits or Git mutations were performed. No new Spell runtime bug was established by these checks.

The standalone probe invokes `shutdown-agents` only at process exit; no scenario does. Passing correctness checks is not memory-slope, worker-allocation, thread-join, or long-duration reliability evidence.
