#!/usr/bin/env python3
"""Three fresh-process trace-export pairs; no providers or private trace inputs.
Run only while holding the exclusive benchmark JVM slot.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--control', type=Path, required=True)
    parser.add_argument('--name', required=True, help='New directory under perf/results')
    parser.add_argument('--nodes', type=int, default=144)
    parser.add_argument('--forms', type=int, default=48)
    parser.add_argument('--timeout', type=int, default=120)
    args = parser.parse_args()
    if (not args.name.replace('-', '').replace('_', '').isalnum()
            or args.nodes < 1 or args.forms < 1 or not 1 <= args.timeout <= 120):
        parser.error('Use a simple unique name, positive fixture sizes, timeout 1..120')
    root = Path(__file__).resolve().parent.parent
    control = args.control.resolve()
    control_head = subprocess.check_output(
        ['git', '-C', str(control), 'rev-parse', 'HEAD'], text=True).strip()
    if not control_head.startswith('4abaa1d'):
        parser.error('Control must be the detached 4abaa1d source checkout')
    if subprocess.check_output(
            ['git', '-C', str(control), 'status', '--porcelain', '--', 'src/spell/trace.clj'],
            text=True).strip():
        parser.error('Control trace source must be unmodified')
    roots = {'control': control, 'candidate': root}
    trace_hashes = {label: sha256(path / 'src/spell/trace.clj')
                    for label, path in roots.items()}
    probe_hash = sha256(root / 'perf/trace_export_probe.clj')
    opts = '{:nodes %d :forms %d :warmup 1 :runs 1}' % (args.nodes, args.forms)
    order = [('control', 1), ('candidate', 1), ('candidate', 2),
             ('control', 2), ('control', 3), ('candidate', 3)]
    out = root / 'perf/results' / args.name
    out.mkdir(parents=True, exist_ok=False)  # Never overwrite preserved evidence.
    spec = importlib.util.spec_from_file_location('perf_runner', root / 'perf/run.py')
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    manifest = {'control_head': control_head, 'trace_sha256': trace_hashes,
                'probe_sha256': probe_hash, 'options_edn': opts,
                'heap_bytes': 536870912, 'warmup': 1, 'runs_per_process': 1,
                'order': order, 'receipts': [], 'status': 'running'}
    manifest_path = out / 'manifest.json'
    runner.write_json(manifest_path, manifest)
    os.chdir(root)
    for label, number in order:
        assert sha256(roots[label] / 'src/spell/trace.clj') == trace_hashes[label], 'Source changed'
        assert sha256(root / 'perf/trace_export_probe.clj') == probe_hash, 'Probe changed'
        paths = [str(roots[label] / 'src'), str(root / 'perf')]
        deps = '{:paths [' + ' '.join(json.dumps(p) for p in paths) + ']}'
        cmd = ['clojure', '-J-Xms512m', '-J-Xmx512m', '-Sdeps', deps,
               '-M', '-m', 'trace-export-probe', opts]
        name = '%s-%d' % (label, number)
        code, receipt = runner.run_command(cmd, out, name, args.timeout, expects_result=False)
        manifest['receipts'].append({'name': name, 'status': receipt['status'],
                                     'exit': receipt['exit'], 'timed_out': receipt['timed_out']})
        manifest['status'] = 'failed' if code else 'running'
        runner.write_json(manifest_path, manifest)
        print(json.dumps({'name': name, 'exit': code, 'status': receipt['status']}), flush=True)
        if code:
            return code
    manifest['status'] = 'complete'
    runner.write_json(manifest_path, manifest)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
