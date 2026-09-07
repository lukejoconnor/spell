#!/usr/bin/env python3
"""Deterministic reliability tests in one fresh JVM; see README.md for limits."""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
REL_OUT = Path('dev/dogfood-reliability-run')
OUT = ROOT / REL_OUT
BASELINE = ROOT / 'perf/results/baseline.json'
command = ['clojure', '-Sdeps', '{:paths ["src" "resources" "test"]}', '-M',
           (REL_OUT / 'run.clj').as_posix(),
           (REL_OUT / 'workflow.edn').as_posix(),
           (REL_OUT / 'tests.edn').as_posix()]
report = {'status': 'failed', 'command': command, 'cwd': '.',
          'path_base': 'repository root derived from run.py at execution time',
          'limits': {'wall_seconds': 90, 'provider_calls_per_handle': 6,
                     'design_digest_limit': 3, 'paid_calls': 0},
          'elapsed_seconds': None,
          'elapsed_seconds_scope': 'subprocess launch through exit/termination and output collection; includes JVM startup, tests, receipt writes and shutdown; not internal test time or CPU time',
          'baseline_sha256_before': None, 'baseline_sha256_after': None,
          'baseline_unchanged': False,
          'diagnostics_policy': 'Raw subprocess output and exception text are omitted for path portability; only parsed numeric test counters are retained.',
          'artifacts': {name: (REL_OUT / name).as_posix()
                        for name in ['workflow.edn', 'tests.edn']}}
before = None
started = None
phase = 'receipt-cleanup'
try:
    for name in ['workflow.edn', 'tests.edn']:
        (OUT / name).unlink(missing_ok=True)
    phase = 'baseline-before'
    before = BASELINE.read_bytes()
    report['baseline_sha256_before'] = hashlib.sha256(before).hexdigest()
    phase = 'subprocess'
    started = time.monotonic()
    proc = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True,
                            start_new_session=(os.name == 'posix'))
    try:
        output, _ = proc.communicate(timeout=90)
    except subprocess.TimeoutExpired:
        report['timeout'] = True
        if os.name == 'posix':
            os.killpg(proc.pid, signal.SIGKILL)
        else:
            proc.kill()
        output, _ = proc.communicate()
    finally:
        report['elapsed_seconds'] = round(time.monotonic() - started, 3)
    report['exit_code'] = proc.returncode
    summary = re.search(r'Ran (\d+) tests containing (\d+) assertions\.\s+(\d+) failures, (\d+) errors\.', output)
    if summary:
        report['test_counts'] = dict(zip(['tests', 'assertions', 'failures', 'errors'],
                                        map(int, summary.groups())))
except OSError as exc:
    if started is not None and report['elapsed_seconds'] is None:
        report['elapsed_seconds'] = round(time.monotonic() - started, 3)
    report['error'] = {'phase': phase, 'type': type(exc).__name__, 'errno': exc.errno}
if before is not None:
    try:
        after = BASELINE.read_bytes()
        report['baseline_sha256_after'] = hashlib.sha256(after).hexdigest()
        report['baseline_unchanged'] = before == after
    except OSError as exc:
        report['error'] = {'phase': 'baseline-after', 'type': type(exc).__name__,
                           'errno': exc.errno}
counts = report.get('test_counts', {})
report['artifacts_present'] = all((ROOT / p).is_file() for p in report['artifacts'].values())
if (report.get('exit_code') == 0 and not report.get('timeout')
        and 'error' not in report and report['baseline_unchanged']
        and report['artifacts_present'] and counts.get('tests', 0) > 0
        and counts.get('assertions', 0) > 0
        and counts.get('failures') == 0 and counts.get('errors') == 0):
    report['status'] = 'completed'
(OUT / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
raise SystemExit(0 if report['status'] == 'completed' else 1)
