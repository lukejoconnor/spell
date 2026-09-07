#!/usr/bin/env python3
"""Bounded deterministic benchmark launcher. All artifacts stay in perf/results."""
import argparse
import json
import os
import pathlib
import signal
import subprocess
import tempfile
import time


def write_json(path, value):
    """Publish one complete receipt; readers never see half-written JSON."""
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as f:
            temporary = pathlib.Path(f.name)
            json.dump(value, f, indent=2)
            f.write('\n')
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def result_state(path):
    if not path.exists():
        return 'missing'
    try:
        result = json.loads(path.read_text())
        status = result.get('run-status')
        if status in ('incomplete', 'complete', 'failed'):
            return status
    except (OSError, ValueError, AttributeError):
        pass
    return 'invalid'


def stop_process(proc):
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        return proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        return proc.wait()


def run_command(cmd, out, name, timeout, expects_result=True):
    """Run one command, invalidating older artifacts before subprocess startup."""
    out.mkdir(parents=True, exist_ok=True)
    result = out / (name + '.json')
    log_path = out / (name + '.log')
    receipt_path = out / (name + '-receipt.json')
    result.unlink(missing_ok=True)
    start = time.monotonic()
    receipt = dict(command=cmd, status='running', exit=None, timed_out=False,
                   elapsed_seconds=0, log=str(log_path), result=None,
                   result_status='missing' if expects_result else 'not_expected')
    write_json(receipt_path, receipt)
    runner_exit = 1
    with log_path.open('w') as log:
        try:
            proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=True)
            try:
                code = proc.wait(timeout=timeout)
                runner_exit = code if code >= 0 else 128 - code
                receipt.update(exit=code, status='ok' if code == 0 else 'failed')
            except subprocess.TimeoutExpired:
                receipt.update(exit=stop_process(proc), status='timed_out', timed_out=True)
                runner_exit = 124
            except KeyboardInterrupt:
                receipt.update(exit=stop_process(proc), status='interrupted')
                runner_exit = 130
        except OSError as error:
            receipt.update(status='launch_failed', error=str(error))
    if expects_result:
        state = result_state(result)
        receipt.update(result=str(result) if result.exists() else None, result_status=state)
        if receipt['status'] == 'ok' and state != 'complete':
            receipt.update(status='failed', error='Child exited without a complete result checkpoint')
            runner_exit = 1
    receipt.update(elapsed_seconds=time.monotonic() - start, runner_exit=runner_exit)
    write_json(receipt_path, receipt)
    return runner_exit, receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['check', 'pilot', 'baseline'])
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--name')
    args = parser.parse_args()
    if not 1 <= args.timeout <= 3600:
        parser.error('timeout must be 1..3600 seconds')
    root = pathlib.Path(__file__).resolve().parent.parent
    os.chdir(root)
    name = args.name or args.mode
    if not name.replace('-', '').replace('_', '').isalnum():
        parser.error('name must be alphanumeric/dash/underscore')
    out = pathlib.Path('perf/results')
    if args.mode == 'check':
        cmd = ['clojure', '-J-Dclojure.main.report=stderr', '-J-Xmx1g', '-Sdeps',
               '{:paths ["src" "resources" "perf" "test"]}', '-M', '-m', 'spell.perf.harness-test']
    else:
        cmd = ['clojure', '-J-Dclojure.main.report=stderr', '-M:perf']
        if args.mode == 'pilot':
            cmd += ['--params-index', '0', '--warmup', '1', '--reps', '2', '--memory-reps', '1']
        else:
            cmd += ['--warmup', '2', '--reps', '5', '--memory-reps', '3']
        cmd += ['--out', str(out / (name + '.json'))]
    code, receipt = run_command(cmd, out, name, args.timeout, expects_result=args.mode != 'check')
    print(json.dumps(receipt))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
