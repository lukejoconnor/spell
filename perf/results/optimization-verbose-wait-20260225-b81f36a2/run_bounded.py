import json, os, pathlib, signal, subprocess, sys, time
root, label, limit, *cmd = sys.argv[1:]
outdir = pathlib.Path(__file__).resolve().parent
start = time.monotonic()
with (outdir / (label + '.stdout')).open('w') as out, (outdir / (label + '.stderr')).open('w') as err:
    child = subprocess.Popen(cmd, cwd=root, stdout=out, stderr=err, start_new_session=True)
    timed_out = False
    try:
        code = child.wait(timeout=float(limit))
    except subprocess.TimeoutExpired:
        timed_out = True
        os.killpg(child.pid, signal.SIGKILL)
        code = child.wait()
receipt = {'cwd': root, 'command': cmd, 'timeout_s': float(limit), 'exit': code, 'timed_out': timed_out, 'child_reaped': child.poll() is not None, 'wall_s': time.monotonic() - start}
(outdir / (label + '.receipt.json')).write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt))
print((outdir / (label + '.stdout')).read_text())
print((outdir / (label + '.stderr')).read_text(), file=sys.stderr)
sys.exit(124 if timed_out else code)
