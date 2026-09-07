# Core runtime benchmark implementation audit

## Scope and provenance

Owned files: `perf/spell/perf/core.clj` and this report. Implementation follows the checked source packet at `67b7cf7ba919a9dca9c78a5b9e9e0ed5116d5c1f`; no runtime changes, optimization, paid calls, live-board fixtures, delegation, or harness loading. Five scenario maps export `scenarios` in `spell.perf.core`.

## Scenario coverage and finite units

- `:core-evaluator`: 4/20 evaluations of 100/1,000/10,000-iteration loops, explicit non-tail +1 continuation; generated AST + eval + assertions, not isolated parser performance.
- `:core-parse-expand`: 4/20/100/1,000/5,000 sibling forms; valid source construction + parse-first + recursive expansion + eval. Measures siblings/source length, not context nesting depth.
- `:core-serial-self-calls`: fresh deterministic test provider and counter, 4/20/100 calls; final 42 must return through non-tail +1 as 43. Includes API compile/discovery/shutdown, uses receiving `!extend`, bounded depth/context. No artificial latency/network. Do not lower failing cases without reporting them.
- `:core-trace-log`: identical 4/20-call inputs in off/trace/verbose-file/both with 0/1,024 whitespace padding per response. Includes file evidence parsing/counting. Separate `:held-trace` mode retains 4/20 trace nodes with 1k/100k-character payloads in a clearable trace atom. It is NOT equivalent to API file tracing.
- `:core-provider-local-boundaries`: JSON 10k/100k/1M chars including Unicode/quotes/backslashes/newlines, synthetic SSE 100/1,000/10,000 delta events followed by one exact finished tool output, local files 1,000/10,000/100,000 lines selecting ten via `[1,11)`. Bounded parameter checks accept smaller correctness probes.

## Retention, isolation, cleanup, interpretation

Only small counts/hashes/results escape into harness sample records. JSON deliberately retains input/body/encoded/decoded artifacts; SSE retains input and parsed output. Local line fixture retains a ten-line SubVector and, through that view, the entire split-lines backing vector; this is not ten-line-sized retention. Held-trace retains the trace atom until cleanup. These strong-reference fixtures are clearable; file mode API trace has already been closed and is not retained. Thus file mode post-API live delta is neither peak heap nor held trace size. GC requests do not guarantee collection; heap/RSS differ. Caller-thread allocation excludes futures.

All scenario-created files are under fresh `perf/.core-fixture-*` directories, not the OS temporary directory. Cleanup deletes only the owned subtree, closes caller-owned writers through `with-open`, clears the fixture atom and any held trace atom. Partial file setup catches failure and cleans up. The harness must invoke `:cleanup` in `finally` after measurement errors. No scenario shuts down the JVM agent pool. `api/run` provides run-local coordinator/global resources; the live audit board is never accessed by the benchmark namespace.

Timed fixture lifecycle includes setup/work/assertions/cleanup; memory passes retain setup fixtures around measurement. Source generation, JSON/SSE construction and file generation are intentionally included in timing. Local disk cache is uncontrolled; `sio/read-lines` slurps the entire file even when selecting ten lines. Synthetic SSE excludes buffering/socket/timeout behavior. API trace files appear after API return, not necessarily during progress.

## API couplings

- `ev/spell-eval` result must be `:ok`, not `:err`; `ev/expand-expr`, `parse/read-first` follow packet contracts.
- `provider/test-provider` callback counts invocations and does not inspect unavailable assistant prefix. API accepts model-profile/agent-profile/init/depth/context-max-chars/trace-dir/log-writer, no removed provider/verbose options.
- Private `provider/codex-tc-request-body` is called with exactly seven arguments; `provider/parse-codex-tc-stream` uses finished custom-tool output even when completed output is empty. This pinned-revision dependency is intentional and must be rechecked on upgrades. Builder model/effort strings are inert request data, not live model configuration.
- `trace/new-trace`, `begin-node!`, `complete-node!` provide held graph; file mode inspects completed `trace.edn`.

## Verification status

Runnable first draft written directly from checked packet without exploratory source reads. Namespace-only compile and eleven tiny correctness probes passed (exit 0, empty stderr). A second bounded probe also passed (exit 0, empty stderr): exact 20-call non-tail return 43; four-call padded trace+verbose mode with exact node count and node values; injected-failure cleanup for held trace, JSON, SSE and selected local lines. Cleanup is idempotent, clears fixture and nested trace atoms, and removes owned directories. Parent owns pilot/full measurements and fresh review. No performance baseline, speedup target, or long-duration extrapolation is claimed. Any target must follow metric-specific baseline/noise evidence.


## Reproducible correctness evidence

Both commands ran through io/sh with explicit timeout 120 seconds, loaded only spell.perf.core (not the harness), and placed JVM temporary reports under perf. Their shutdown-agents calls are standalone probe-process teardown, never scenario code.

### Eleven tiny cases

```sh
clojure -J-Djava.io.tmpdir=perf -Sdeps '{:aliases {:audit-core {:extra-paths ["perf" "test"]}}}' -M:audit-core -e '(require (quote spell.perf.core)) (let [ss spell.perf.core/scenarios ps [[{:iterations 8 :evals 4}] [{:forms 4}] [{:calls 4 :padding 0}] [{:mode :off :calls 4 :padding 0} {:mode :trace :calls 4 :padding 0} {:mode :verbose :calls 4 :padding 0} {:mode :both :calls 4 :padding 0} {:mode :held-trace :calls 4 :prompt-chars 16}] [{:mode :json :chars 128} {:mode :sse :events 4} {:mode :read-lines :lines 20}]] failures (atom 0)] (doseq [[s cases] (map vector ss ps) p cases] (try (println :PASS (:id s) p (if-let [run (:run s)] (run p) (let [f ((:setup s) p)] (try ((:measure s) p f) (finally ((:cleanup s) f) (assert (nil? @f))))))) (catch Throwable t (swap! failures inc) (println :FAIL (:id s) p (.getMessage t) (ex-data t))))) (println :failures @failures) (shutdown-agents) (System/exit (if (zero? @failures) 0 1)))'
```

Exit 0, empty stderr:

```text
:PASS :core-evaluator {:iterations 8, :evals 4} {:evals 4, :loop-iterations 32, :result 29}
:PASS :core-parse-expand {:forms 4} {:sibling-forms 4, :source-chars 87, :result 42}
:PASS :core-serial-self-calls {:calls 4, :padding 0} {:calls 4, :result 43, :padding-chars-per-call 0}
:PASS :core-trace-log {:mode :off, :calls 4, :padding 0} {:calls 4, :result 43, :padding-chars-per-call 0, :nodes 0, :trace-bytes 0, :log-bytes 0}
:PASS :core-trace-log {:mode :trace, :calls 4, :padding 0} {:calls 4, :result 43, :padding-chars-per-call 0, :nodes 4, :trace-bytes 2192, :log-bytes 0}
:PASS :core-trace-log {:mode :verbose, :calls 4, :padding 0} {:calls 4, :result 43, :padding-chars-per-call 0, :nodes 0, :trace-bytes 0, :log-bytes 520}
:PASS :core-trace-log {:mode :both, :calls 4, :padding 0} {:calls 4, :result 43, :padding-chars-per-call 0, :nodes 4, :trace-bytes 2192, :log-bytes 520}
:PASS :core-trace-log {:mode :held-trace, :calls 4, :prompt-chars 16} {:nodes 4, :prompt-chars-per-node 16, :result 42}
:PASS :core-provider-local-boundaries {:mode :json, :chars 128} {:prompt-chars 128, :encoded-bytes 893, :text-hash -1302989462}
:PASS :core-provider-local-boundaries {:mode :sse, :events 4} {:delta-events 4, :total-events 6, :sse-chars 713, :output-chars 9, :text-hash -1757294346}
:PASS :core-provider-local-boundaries {:mode :read-lines, :lines 20} {:file-lines 20, :file-bytes 151, :returned-lines 10, :returned-chars 61, :first-line 1}
:failures 0
```

### Twenty-call, padding and injected-failure cleanup checks

```sh
clojure -J-Djava.io.tmpdir=perf -Sdeps '{:aliases {:audit-core {:extra-paths ["perf" "test"]}}}' -M:audit-core -e '(require (quote spell.perf.core)) (let [ss (into {} (map (juxt :id identity) spell.perf.core/scenarios)) serial (:run (ss :core-serial-self-calls)) exercise (fn [id p fail?] (let [s (ss id) f ((:setup s) p) before @f failure (atom nil) evidence (atom nil)] (try (reset! evidence ((:measure s) p f)) (when fail? (throw (ex-info "injected failure after retained workload" {:injected true}))) (catch Throwable t (if (:injected (ex-data t)) (reset! failure true) (throw t))) (finally ((:cleanup s) f) ((:cleanup s) f) (assert (nil? @f)) (when-let [t (:held-trace before)] (assert (nil? @t))) (when-let [d (:dir before)] (assert (not (.exists d)))))) (assert (= fail? (boolean @failure))) (println :PASS-CLEANUP id p :injected-failure fail? @evidence)))] (println :PASS-20-CALLS (serial {:calls 20 :padding 0})) (exercise :core-trace-log {:mode :both :calls 4 :padding 1024} false) (exercise :core-trace-log {:mode :held-trace :calls 4 :prompt-chars 16} true) (exercise :core-provider-local-boundaries {:mode :json :chars 128} true) (exercise :core-provider-local-boundaries {:mode :sse :events 4} true) (exercise :core-provider-local-boundaries {:mode :read-lines :lines 20} true) (println :cleanup-checks-passed)) (shutdown-agents)'
```

Exit 0, empty stderr on the final rerun:

```text
:PASS-20-CALLS {:calls 20, :result 43, :padding-chars-per-call 0}
:PASS-CLEANUP :core-trace-log {:mode :both, :calls 4, :padding 1024} :injected-failure false {:calls 4, :result 43, :padding-chars-per-call 1024, :nodes 4, :trace-bytes 10396, :log-bytes 4616}
:PASS-CLEANUP :core-trace-log {:mode :held-trace, :calls 4, :prompt-chars 16} :injected-failure true {:nodes 4, :payload-chars-per-node 16, :result 42}
:PASS-CLEANUP :core-provider-local-boundaries {:mode :json, :chars 128} :injected-failure true {:prompt-chars 128, :encoded-bytes 893, :text-hash -1302989462}
:PASS-CLEANUP :core-provider-local-boundaries {:mode :sse, :events 4} :injected-failure true {:delta-events 4, :total-events 6, :sse-chars 713, :output-chars 9, :text-hash -1757294346}
:PASS-CLEANUP :core-provider-local-boundaries {:mode :read-lines, :lines 20} :injected-failure true {:file-lines 20, :file-bytes 151, :returned-lines 10, :returned-chars 61, :first-line 1}
:cleanup-checks-passed
```

The second command verifies the final tightened completed-trace assertions (exactly the requested node count, every node value 42). The held-trace evidence key is now :payload-chars-per-node: each prompt additionally includes its node-index prefix. Failure injection occurs after the retained workload; finally invokes cleanup twice and checks the cleared outer atom, cleared nested trace atom and deleted directory. It does not simulate every possible filesystem deletion error or failure during API internals.

## Corrections and dogfood observations

Draft-only defects corrected before completion: one unmatched init delimiter, conditional with-open trying to close nil in off/trace modes, and treating trace :nodes as a map instead of a vector. A targeted src/spell/trace.clj:29-84 read confirmed new-trace initializes :nodes [] and complete-node! records values by vector index. These were benchmark draft defects, not runtime regressions.

A later verification attempt failed before namespace loading because deps.edn contained literal numbered io/read-file presentation prefixes, producing Invalid number: 1:. Root confirmed and repaired the originating shared-file edit using joined io/read-lines; this worker never edited deps.edn. The identical bounded command then passed. The incident is resolved and retained here as provenance, not an outstanding blocker.

## Remaining limits

No harness, pilot/full measurements, 100-call chain, maximum-size performance run, network call or live-board fixture was executed by this worker. The exported largest parameters remain intact for parent-owned measurement; do not silently downscale failures. No baseline or optimization claim follows from these correctness probes. All model/effort names in provider-builder data are inert; the live model/effort configuration was unchanged.

## Supervisor retention correction

The selected ten-line result is a Clojure SubVector returned by `spell.io/read-lines`; it keeps the entire split-lines backing vector reachable. The retained-live measurement therefore includes all file lines reachable through that view, even though only ten are returned to the caller. Baseline observations (about6.13MB at100,000lines) are real; interpreting them as ten-line storage was incorrect. The workload is preserved for optimization.
