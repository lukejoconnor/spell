"""Cheap subprocess checks for interruption and reuse of benchmark output names."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('perf_runner', Path(__file__).with_name('run.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='spell-perf-runner-')
        self.addCleanup(self.temporary.cleanup)
        self.out = Path(self.temporary.name)
        self.result = self.out / 'retry.json'

    def run_fake(self, source, timeout=5, expects_result=True):
        return runner.run_command([sys.executable, '-c', source], self.out, 'retry',
                                  timeout, expects_result)

    def writer(self, status, completed):
        data = {'run-status': status, 'planned-cases': 2, 'completed-cases': completed,
                'results': [{'id': 'first', 'status': 'ok'}] * completed}
        return ('from pathlib import Path; import json, os, time; '
                f'p = Path({str(self.result)!r}); t = p.with_suffix(".tmp"); '
                f't.write_text({json.dumps(data)!r}); os.replace(t, p); ')

    def test_timeout_keeps_partial_checkpoint_from_new_run(self):
        code, _ = self.run_fake(self.writer('complete', 2))
        self.assertEqual(0, code)
        code, receipt = self.run_fake(self.writer('incomplete', 1) + 'time.sleep(30)', timeout=1)
        self.assertEqual(124, code)
        self.assertEqual('timed_out', receipt['status'])
        self.assertTrue(receipt['timed_out'])
        self.assertEqual('incomplete', receipt['result_status'])
        self.assertEqual(str(self.result), receipt['result'])
        self.assertEqual(1, json.loads(self.result.read_text())['completed-cases'])
        self.assertEqual(receipt, json.loads((self.out / 'retry-receipt.json').read_text()))

    def test_early_failure_invalidates_prior_success(self):
        self.run_fake(self.writer('complete', 2))
        code, receipt = self.run_fake('raise SystemExit(3)')
        self.assertEqual(3, code)
        self.assertEqual('failed', receipt['status'])
        self.assertEqual('missing', receipt['result_status'])
        self.assertIsNone(receipt['result'])
        self.assertFalse(self.result.exists())

    def test_launch_failure_replaces_old_receipt_and_result(self):
        self.run_fake(self.writer('complete', 2))
        code, receipt = runner.run_command([str(self.out / 'missing-executable')],
                                           self.out, 'retry', 1)
        self.assertEqual(1, code)
        self.assertEqual('launch_failed', receipt['status'])
        self.assertIsNone(receipt['exit'])
        self.assertEqual('missing', receipt['result_status'])
        self.assertFalse(self.result.exists())

    def test_zero_exit_requires_complete_result(self):
        for source in ['pass', self.writer('incomplete', 1), self.writer('failed', 2)]:
            with self.subTest(source=source):
                code, receipt = self.run_fake(source)
                self.assertEqual(1, code)
                self.assertEqual(0, receipt['exit'])
                self.assertEqual('failed', receipt['status'])

    def test_checks_succeed_without_measurement_artifact(self):
        code, receipt = self.run_fake('pass', expects_result=False)
        self.assertEqual(0, code)
        self.assertEqual('ok', receipt['status'])
        self.assertEqual('not_expected', receipt['result_status'])

    def test_atomic_receipt_failure_preserves_previous_file(self):
        path = self.out / 'receipt.json'
        runner.write_json(path, {'status': 'running'})

        def fail_mid_write(value, stream, **kwargs):
            stream.write('partial')
            raise OSError('interrupted write')

        with mock.patch.object(runner.json, 'dump', fail_mid_write):
            with self.assertRaises(OSError):
                runner.write_json(path, {'status': 'ok'})
        self.assertEqual({'status': 'running'}, json.loads(path.read_text()))
        self.assertEqual([path], list(self.out.iterdir()))


if __name__ == '__main__':
    unittest.main()
