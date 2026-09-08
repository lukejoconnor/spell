# Synthetic trace-export probe

No providers, private traces, or large checked-in fixtures. `trace_export_probe.clj` generates repeated nested Spell source and complete trace records in memory, writes to an owned temporary directory, verifies the exported data/program files/tree, and deletes that directory in `finally`.

## Reproduce

Requirements: Java 11+, Clojure CLI/dependencies, Python 3.8+ and POSIX process groups for the bounded comparison launcher. Run only with the exclusive benchmark JVM slot. From the candidate checkout:

```sh
# Small one-process smoke measurement (36 nodes, 24 source forms).
clojure -J-Xms512m -J-Xmx512m -Sdeps '{:paths ["src" "perf"]}' \
  -M -m trace-export-probe

# CONTROL_CHECKOUT must contain unmodified detached 4abaa1d source.
# NEW_RESULT_NAME must not already exist under perf/results.
python3 -B perf/trace_export_compare.py \
  --control CONTROL_CHECKOUT --name NEW_RESULT_NAME

# Focused correctness, including the public trace consumer.
clojure -J-Xmx512m -M:test \
  -n spell.trace-test -n spell.trace-export-test -n spell.trace-tool-test
```

The comparison runs control/candidate, candidate/control, control/candidate in six fresh JVMs. Each process uses 144 nodes, 48 forms, one warmup, one measurement, and `-Xms512m -Xmx512m`. Both sides load the identical shared probe; the selected trace source and probe hashes are recorded. Each subprocess has a 120-second process-group timeout. An enclosing Spell shell call must allow the complete sequence: `(io/sh command {:timeout 750})` uses **seconds**, not `:timeout-ms`.

The launcher refuses an existing result directory and never changes preserved baseline evidence. Raw EDN output, exact expanded commands, exit receipts, and the manifest remain under the new result directory. `perf/results` is ignored by Git; `trace-export-results-002.json` is the compact, portable six-run evidence selected for review.

## Measurement scopes and correctness

- `export-*`: `write-trace!` only, including program files, trace data and tree output.
- Unprefixed wall/CPU/allocation: temporary-directory creation, export, full readback/size checks, and cleanup. **Fixture generation, JVM startup and warmup are outside both measured intervals.**
- CPU and allocation are for the calling thread via `ThreadMXBean`; they are not process CPU, retained heap, peak RSS, or whole-agent memory. Unsupported meters report nil, not zero.
- Every warmup and measurement asserts one safely read Clojure form plus EOF, equality with every cleaned trace record and top-level field, every program file, tree output, and return path. Safe Clojure reading binds `*read-eval* false` on both sides: the old pretty printer abbreviates quote/deref forms that strict EDN cannot read.
- Focused export tests separately check strict EDN for supported fixture values, UTF-8 program bytes, hostile caller print settings, the actual `trace-tool/load-trace` consumer, overwrite, exceptions and writer closure. Arbitrary custom host objects are **not** promised to be EDN-readable.

## Results

The first corrected current-HEAD smoke measurement (36x24, one warmup, 512 MiB heap) passed all readback checks: 531,476 trace bytes, export wall 1,354.442 ms, calling-thread CPU 1,326.922 ms, and 2,793,025,816 allocated bytes. Its raw evidence is `results/trace-export-first-baseline-4abaa1d.edn`. The initial strict-EDN attempt failed on the old printer's reader abbreviations; it produced no valid timing.

The complete comparison is `trace-export-results-002.json`, backed by `results/trace-export-matched-002/`: all six subprocesses exited 0 with readback assertions passing, then process inspection found no remaining comparison/probe process. Control revision: `4abaa1d696413503ee4dd9a296e1e3df953ceb5f`; Java 11.0.23, Clojure 1.12.4.

| Export metric | Control median | Candidate median | Control/candidate |
| --- | ---: | ---: | ---: |
| Wall ms | 9,463.012 | 168.512 | 56.156x |
| Calling-thread CPU ms | 9,404.599 | 164.570 | 57.15x |
| Calling-thread allocated bytes | 21,202,831,016 | 130,144,592 | 162.917x |
| Trace bytes | 4,108,274 | 3,684,224 | 1.115x |

Including verification and cleanup, median wall was 9,648.247 versus 305.294 ms (31.603x). Candidate export wall ranged 122.116–179.603 ms; control ranged 9,308.295–9,497.912 ms. Three processes and one warmup are limited evidence, not a precise prediction. Allocation churn falls much more than output size; no extrapolation is made to the private 63.6 MB trace or full agent runtime/memory.

`trace-export-matched-001` is preserved but excluded: an incorrect enclosing shell timeout key killed the launcher after three successful receipts, briefly leaving an orphan JVM without a completed receipt. The worker verified that process was gone before releasing the slot. The complete 002 sequence used the correct outer timeout and independent fresh processes.

The measured candidate source hash precedes a documentation-only addition to `write-trace!`; its printer implementation was not changed after measurement. Subsequent changes add consumer/failure assertions, the `:test-slow` entry, and a reader comment; they do not change the measured export path.

## Accepted failure contract

Successful exports preserve complete record data, program files, tree content, naming and the returned directory. Pretty whitespace is not an invariant. The writer closes on success or failure and exceptions propagate.

This diagnostic export is **not atomic**. Direct streaming opens/truncates `trace.edn` before printing; a printer failure can leave partial data or truncate a previous trace. The old `with-out-str` rendered before opening that file, so this is a deliberate failure-artifact difference, explicitly accepted by the lead and documented/tested. No staging, rename abstraction or compatibility reader was added. Failure aborts before subsequent tree output; already-written program files are not rolled back.
