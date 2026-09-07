import json, pathlib, subprocess, sys
out = pathlib.Path(__file__).resolve().parent
candidate = pathlib.Path('/private/tmp/spell-dogfood-reliability')
control = pathlib.Path('/private/tmp/spell-optimization-control-4abaa1d')
old = (control / 'src/spell/llm.clj').read_text()
new = (candidate / 'src/spell/llm.clj').read_text()
expected = ''.join(line for line in old.splitlines(keepends=True) if line.strip() != '(Thread/sleep (rand-int 500))')
assert new == expected, 'Candidate llm.clj must be exact control minus two whole wait lines'
cmd = ['clojure', '-J-Xms256m', '-J-Xmx1g', '-Sdeps', '{:aliases {:wait-probe {:extra-paths ["test"]}}}', '-M:wait-probe', str(out / 'wait_probe.clj')]
for pair in map(int, sys.argv[1:]):
    order = [('control', control), ('candidate', candidate)] if pair % 2 else [('candidate', candidate), ('control', control)]
    for name, root in order:
        label = f'pair-{pair}-{name}'
        assert not (out / (label + '.receipt.json')).exists(), 'Never overwrite receipts'
        result = subprocess.run([sys.executable, str(out / 'run_bounded.py'), str(root), label, '30', *cmd], timeout=35)
        assert result.returncode == 0, f'{label} exit {result.returncode}'
    a = json.loads((out / f'pair-{pair}-control.stdout').read_text())
    b = json.loads((out / f'pair-{pair}-candidate.stdout').read_text())
    assert a['source_waits'] == 2 and b['source_waits'] == 0
    assert a['assertions'] == b['assertions']
    for ac, bc in zip(a['cases'], b['cases']):
        assert {k:v for k,v in ac.items() if k not in ('elapsed_ms','wait_calls','intentional_wait_ms')} == {k:v for k,v in bc.items() if k not in ('elapsed_ms','wait_calls','intentional_wait_ms')}, 'Control/candidate semantics differ'
    print(json.dumps({'pair': pair, 'matched_semantics': True, 'assertions_per_process': a['assertions'], 'control_wait_ms': sum(x['intentional_wait_ms'] for x in a['cases']), 'candidate_wait_ms': sum(x['intentional_wait_ms'] for x in b['cases'])}), flush=True)
