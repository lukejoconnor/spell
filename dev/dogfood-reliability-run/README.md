# Deterministic reliability workflow

From the repository root:

```sh
python3 dev/dogfood-reliability-run/run.py
```

The script derives the execution root from its own location, so it also works
when invoked by path from another working directory. Requirements: Python 3,
Java, the Clojure CLI, and the checkout's dependencies already cached locally
for offline execution. No paid provider or credentials are used: tests use
`spell.test-helpers`' deterministic test provider and the real compiled-agent,
mailing-list and coordinator implementation. No production/test semantics are
modified by the runner, and there is no automatic retry or runtime rescue policy.

## Receipts

All paths below are repository-relative and overwritten on each invocation:

- `dev/dogfood-reliability-run/report.json`: portable JVM command (`command`
  is an argv vector), `cwd` of `.` meaning repository root, relative artifact
  paths, exit/status, parsed numeric test counters (no raw subprocess output), configured limits, elapsed
  subprocess wall time, and baseline SHA-256 before/after plus byte equality.
- `dev/dogfood-reliability-run/tests.edn`: Clojure test/pass/fail/error counters,
  assertion count, status and receipt checks. Success requires nonzero test
  and assertion counts, a completed workflow and both completed tracked child
  returns with exactly two messages, the exact expected sender set (order-independent),
  and distinct positive edge IDs.
- `dev/dogfood-reliability-run/workflow.edn`: actual completed child messages,
  pre-generation subscriptions, deterministic provider call counts, preserved
  self-call/effect evidence and bounded design digest.

`elapsed_seconds` is monotonic wall time measured immediately before subprocess
launch through exit or timeout termination and output collection. It includes
JVM startup, any dependency resolution, tests, EDN receipt writes and shutdown;
it excludes Python's later baseline comparison and JSON report writing. It is
not internal test time, CPU time, heap usage or allocation data. These receipts
make no performance claim; no profiler or baseline benchmark is run.

## Bounds and failures

One fresh JVM runs only `spell.reliability-workflow-test`. Output collection has
a 90-second timeout. Timeout, interruption, or output-collection failure kills the
isolated process group on POSIX (the process on other platforms), waits up to
five seconds to reap the child, and closes its output streams. The runner writes
a failed terminal receipt where possible; interruption and unexpected exceptions
are then re-raised. A cleanup failure is recorded separately without hiding the
original error. A second Ctrl-C during bounded cleanup is recorded as
`cleanup_error`; the original interruption still propagates. Launch and cleanup
overhead can exceed the 90-second limit. Provider
scripts allow at most six calls per handle per test; the successful workflow
expects two calls each for main and the two children. The design digest limit
is three. Coverage includes quiet posts and actual-token acknowledgements,
duplicate initialization, retention gaps, stale acknowledgements, setup-only
onboarding failures and ordinary task recovery. This is deterministic offline
correctness coverage, not a live-provider or performance assessment.

The runner removes old EDN receipts before launch. Test failures/errors, empty
counts, invalid/missing workflow evidence, a nonzero JVM exit, missing receipts,
timeout or changed `perf/results/baseline.json` bytes prevent success. The
baseline is read and hashed only, never rewritten or rerun. Missing or unreadable baseline files emit a failed JSON receipt with a
path-free error phase/type/errno and exit nonzero; no JVM is launched when the
initial baseline read fails. `elapsed_seconds` is null if no launch was
attempted. Writing the receipt itself still requires a writable output directory;
always check this invocation's exit code, not an old receipt alone.
Raw subprocess output and exception text are intentionally omitted from the
portable JSON receipt, so repository symlink spellings and external temporary
paths cannot leak through diagnostics. Only numeric test counters are parsed.
For local diagnostic output, run the receipt's `command` argv from the repository
root. Verbose runtime logs are not persisted. No
staging, commits or branch operations are performed.
