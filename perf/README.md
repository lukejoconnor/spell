# Spell runtime and memory benchmarks

A bounded, local benchmark suite: **14 scenario families / 80 parameter cases**, using deterministic providers and host callbacks rather than paid model calls. The preserved audit002 baseline passed **80/80** in **608.930953 seconds (about 10 min 9 sec)**. Its preceding 14-case pilot took 26.036854 seconds. The outer Spell audit subsequently failed while reporting; successful measurements do not mean that outer run succeeded.

## Run from the repository root

Requirements: Java 11+, Clojure CLI with dependencies available, Python 3.8+, and a POSIX system for the Python launcher's process-group termination. No provider credentials are needed. RSS sampling uses `ps` when available; unavailable probes are not zero usage. Filesystem support for same-directory atomic rename is required for checkpoints. Windows process control is not implemented by this launcher.

Use **new output names**. In particular, do not rerun the launcher with `--name baseline`, `--name pilot`, or another preserved evidence name: it intentionally invalidates the previous result with that name.

```sh
# Focused harness/cleanup/checkpoint tests, not performance measurements.
python3 -B perf/run.py check --timeout 120 --name check-next

# Six cheap Python launcher/receipt subprocess tests.
python3 -B perf/test_run.py

# Inventory, including the ordered parameter vectors used by --params-index.
clojure -J-Dclojure.main.report=stderr -M:perf --list true

# Fourteen smallest parameter cases: 1 warmup, 2 timings, 1 memory pass.
python3 -B perf/run.py pilot --timeout 180 --name pilot-next

# Full 80-case measurement: 2 warmups, 5 timings, 3 memory passes.
# Baseline duration was ~609 seconds on the recorded M1 Max, not a time guarantee.
python3 -B perf/run.py baseline --timeout 900 --name baseline-next
```

The optional `:perf` alias adds `perf` and `test` to the classpath and sets `-Xms256m -Xmx1g -XX:+UseG1GC`. Launcher timeouts are configurable from 1 to 3600 seconds. Run only one benchmark process at a time; do not share an output name between processes. Python tests and some Clojure tests use temporary directories; core workload files themselves live in owned `perf/.core-fixture-*` subdirectories and are removed by cleanup.

### One targeted parameter case, with process timeout and receipt

This portable POSIX/Python command selects `core-trace-log` index 10: `{:mode :verbose :calls 20 :padding 0}`. It uses the existing launcher's bounded process handling rather than an unbounded direct harness invocation. Change both `name` and the scenario/index for subsequent experiments.

```sh
python3 -B - <<'PY'
import importlib.util
import json
from pathlib import Path
spec = importlib.util.spec_from_file_location('perf_runner', 'perf/run.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
name = 'verbose-next'
out = Path('perf/results')
cmd = ['clojure', '-J-Dclojure.main.report=stderr', '-M:perf',
       '--scenario', 'core-trace-log', '--params-index', '10',
       '--warmup', '2', '--reps', '5', '--memory-reps', '3',
       '--out', str(out / (name + '.json'))]
code, receipt = runner.run_command(cmd, out, name, 180)
print(json.dumps(receipt))
raise SystemExit(code)
PY
```

For matched trace comparisons, indices 2/6/10/14 are off/trace/verbose/both at 20 calls, padding 0; 3/7/11/15 use padding 1024. Other useful current indices: evaluator 5, parse-expand 4, serial-self-calls 2, provider boundaries 2/5/8 (largest JSON/SSE/read-lines), coordinator 7, spawn-return 5, dormant-wake 5, API-cleanup 2. Always verify the inventory before relying on indices after a workload change.

### Artifacts and interruption semantics

A new measurement writes `<name>.json`, `<name>.log`, and `<name>-receipt.json` under `perf/results/`. The current harness publishes an initial `incomplete` checkpoint and atomically replaces it after each finished case. It records planned/completed counts and ends with `complete` or `failed`. Interruption preserves the last successfully published checkpoint, **not** the in-flight case or every individual timing sample. Publication and console printing are outside measured regions. Atomic rename is not a power-loss/fsync guarantee.

The launcher removes an older result, publishes a fresh running receipt, truncates the log, and records launch failure, nonzero exit, timeout, or interruption. A zero child exit without a complete result checkpoint is rejected. Inspect both the final receipt and the result; a surviving `running` receipt is not success. The historical baseline predates this persistence enhancement and has the older receipt/result layout: do not retrofit it or mistake missing new fields for corruption.

## Scenario inventory

| Family | Cases | Work and named parameters |
|---|---:|---|
| core-evaluator | 6 | `iterations` 100/1000/10000 × `evals` 4/20; non-tail continuation |
| core-parse-expand | 5 | `forms` 4/20/100/1000/5000; sibling source construction, parsing, expansion, evaluation |
| core-serial-self-calls | 3 | `calls` 4/20/100, `padding` 0; receiving extension chain and return 43 |
| core-trace-log | 20 | off/trace/verbose/both, 4/20 calls, 0/1024 padding; separate held-trace payload cases |
| core-provider-local-boundaries | 9 | JSON `chars` 10k–1M; SSE `events` 100–10k; files `lines` 1k–100k selecting ten |
| lifecycle-coordinator | 8 | `agents` 4/20, `cycles` 1/10, `mailbox-batch` 16/256; hyperedge/FIFO |
| lifecycle-spawn-return | 6 | `agents` 4/20, `cycles` 1/10/100; real future dispatch, ordered fanin, retirement |
| lifecycle-dormant-wake | 6 | `agents` 4/20, `cycles` 1/10/100; generation, settled runners, live fixture |
| lifecycle-api-cleanup | 3 | `cycles` 1/10/100; API closure and adjacent-run store isolation |
| context-render-edit | 4 | `operations` 20/100 × `payload-chars` 64/8192; shared payload, render/retrieve/prune |
| context-pruned-storage | 2 | `cycles` 20/100, unique `payload-chars` 8192; intentionally retrievable stored values |
| globals-contention | 2 | `workers` 4/20, equal `total-updates` 2000 |
| board-post-digest | 2 | `posts/retention/page-size` 20/8/3 and 60/20/7; 1024-character bodies |
| board-captured-snapshot | 4 | `capture` board/receipt, `posts` 20/60, retention 8, page 3, bodies 8000 |

## Methodology and interpretation

The baseline ran serially in **one JVM**, on an M1 Max with 10 reported processors, macOS 15.7.7/aarch64, Amazon OpenJDK 11.0.23, Clojure 1.12.4, 1 GiB maximum heap, G1. Metadata records seed 424242, but verbose logging itself uses random sleeps; the workload is deterministic in correctness, not in every timing path.

Each case has two warmup invocations, five wall samples, and three independent memory executions. Timing includes **setup + workload + correctness assertions + cleanup**; harness resource probes and explicit GC requests are excluded. Source/file/event generation and trace-file evidence parsing are deliberately included. Throughput means complete fixture invocations per second, not automatically tokens, messages, or operations per second.

Allocation is the **calling thread only**, excluding all worker/future threads. Memory passes request GC/finalization three times with 80 ms sleeps before point probes. A global fixture root deliberately keeps live objects reachable through the live probe; cleanup clears reference containers and drops that root. Small evidence/result records remain. Retained-live and post-cleanup deltas are distinct; explicit GC is best effort, negative deltas can be collection noise, and neither committed heap nor RSS equals retained live heap.

Thread peaks are process-wide since a case reset, including JVM/harness threads. Shared future pools can retain idle threads. Coordinator close and a finished/runner-nil observation are not thread joins. RSS is a point snapshot, **not peak RSS**; native allocation and native/RSS peaks are unmeasured. Disk cache, case order, JIT state and previous cases are not isolated. **24/80 wall CVs exceed 0.2**; five samples are not a robust tail-latency study.

Before optimization claims, repeat control/candidate on the same machine in fresh JVMs, investigate warmup sensitivity and variance, preserve workload/assertions, and use paired cases with named parameters. Do not compare a differently sized workload or changed setup boundary as a speedup. Network/auth/socket behavior, live model latency, malformed-recovery performance, true nesting-depth stress, whole-process allocation, and a genuine 48-hour soak remain outside this baseline.

## Evidence and reading order

- [findings-and-targets.md](findings-and-targets.md): measured comparisons and provisional goals for all 14 families.
- [dogfood-report.md](dogfood-report.md): historical four-agent board workflow and failed outer runs.
- [implementation-report.md](implementation-report.md): changes, checks, deviations, next-agent brief.
- [fresh-review.md](fresh-review.md): independent review verdict and check boundaries.
- `results/baseline.json`, `baseline.log`, `baseline-receipt.json`: immutable measured evidence.
- `results/baseline-provenance.json`: post-measurement source/persistence correction record.
- `results/board-evidence.json`, `worker-receipts.edn`, `context-worker-receipt.edn`: recovered board observations and attributed worker returns.
- `audit-core.md`, `audit-lifecycle.md`, `audit-context.md`: original workers' exact correctness commands and results, not worker-authored baseline measurements.

Measured run: `2026-09-07-runtime-audit-002`; base `67b7cf7ba919a9dca9c78a5b9e9e0ed5116d5c1f`, with actual measured file hashes/dirty status in the raw baseline. Baseline JSON SHA-256: `54754377fd62516b5971715bcd334237733b07370e24fc83fa514d98a08837ce`. Later changes corrected labels and added persistence outside timed functions; the old artifact is not represented as measured at a future commit. No runtime optimization was implemented by this audit or this report-only review.
