import hashlib, json, pathlib, statistics, subprocess
out = pathlib.Path(__file__).resolve().parent
root = out.parents[2]
rows = []
for pair in (1, 2, 3):
    values = {}
    for side in ('control', 'candidate'):
        label = f'pair-{pair}-{side}'
        receipt = json.loads((out / (label + '.receipt.json')).read_text())
        data = json.loads((out / (label + '.stdout')).read_text())
        assert receipt['exit'] == 0 and receipt['child_reaped'] and not receipt['timed_out']
        assert len(data['cases']) == 8 and data['assertions'] == 53
        values[side] = {'process_wall_s': receipt['wall_s'], 'verbose_elapsed_ms': sum(c['elapsed_ms'] for c in data['cases'] if c['verbose']), 'nonverbose_elapsed_ms': sum(c['elapsed_ms'] for c in data['cases'] if not c['verbose']), 'intentional_wait_ms': sum(c['intentional_wait_ms'] for c in data['cases'])}
    rows.append({'pair': pair, **values, 'verbose_elapsed_reduction_ms': values['control']['verbose_elapsed_ms'] - values['candidate']['verbose_elapsed_ms'], 'process_wall_reduction_s': values['control']['process_wall_s'] - values['candidate']['process_wall_s']})
last = json.loads((out / 'focused-regression-repaired.receipt.json').read_text())
assert last['exit'] == 0 and last['child_reaped'] and not last['timed_out']
assert 'Ran 77 tests containing 675 assertions.' in (out / 'focused-regression-repaired.stdout').read_text()
summary = {'pairs': rows, 'assertions_per_probe_process': 53, 'probe_processes': 6, 'total_probe_assertions': 318, 'focused_tests': 77, 'focused_assertions': 675, 'all_children_reaped': True, 'wait_reduction_ms_per_pair': 1000, 'verbose_reduction_ms_median': statistics.median(r['verbose_elapsed_reduction_ms'] for r in rows), 'verbose_reduction_ms_range': [min(r['verbose_elapsed_reduction_ms'] for r in rows), max(r['verbose_elapsed_reduction_ms'] for r in rows)], 'process_reduction_s_range': [min(r['process_wall_reduction_s'] for r in rows), max(r['process_wall_reduction_s'] for r in rows)]}
(out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
diff = subprocess.check_output(['git', 'diff', '--', 'src/spell/llm.clj', 'test/spell/llm_test.clj'], cwd=root, text=True)
(out / 'change.diff').write_text(diff)
metadata = {'branch': subprocess.check_output(['git','branch','--show-current'], cwd=root, text=True).strip(), 'head': subprocess.check_output(['git','rev-parse','HEAD'], cwd=root, text=True).strip(), 'scope_diff_numstat': subprocess.check_output(['git','diff','--numstat','--','src/spell/llm.clj','test/spell/llm_test.clj'], cwd=root, text=True).strip(), 'sha256': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [root / 'src/spell/llm.clj', root / 'test/spell/llm_test.clj', out / 'wait_probe.clj', out / 'run_pairs.py', out / 'run_bounded.py']}}
(out / 'source-receipt.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(summary, indent=2))
print(json.dumps(metadata, indent=2))
print('Invalid append snapshot prefix: ' + repr((out / 'failed-append.snapshot.txt').read_text()[:160]))
