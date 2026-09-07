"""Focused, offline tests for atomic reliability-run receipt publication."""
import contextlib
import errno
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('reliability_runner', Path(__file__).with_name('run.py'))
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class RunnerTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.out = self.root / runner.REL_OUT
        self.out.mkdir(parents=True)
        self.baseline = self.root / 'perf/results/baseline.json'
        self.baseline.parent.mkdir(parents=True)
        self.baseline.write_bytes(b'{"baseline": true}\n')
        self.before = self.baseline.read_bytes()
        self.old_report = b'{"status": "completed", "old": true}\n'
        (self.out / 'report.json').write_bytes(self.old_report)
        for name in ('workflow.edn', 'tests.edn'):
            (self.out / name).write_text('old-' + name)
        for name, value in [('ROOT', self.root), ('OUT', self.out), ('BASELINE', self.baseline)]:
            patcher = mock.patch.object(runner, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.stdout = io.StringIO()
        redirect = contextlib.redirect_stdout(self.stdout)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)
        self.popen = mock.patch.object(runner.subprocess, 'Popen').start()
        self.addCleanup(mock.patch.stopall)
        self.popen.side_effect = self.launch

    def receipt(self):
        return json.loads((self.out / 'report.json').read_text())

    def launch(self, *args, **kwargs):
        self.assertEqual('running', self.receipt()['status'])
        for name in ('workflow.edn', 'tests.edn'):
            self.assertFalse((self.out / name).exists())
            (self.out / name).write_text('{:fresh true}')
        proc = mock.Mock(returncode=0, pid=12345)
        proc.communicate.return_value = ('Ran 2 tests containing 4 assertions.\n0 failures, 0 errors.\n', None)
        return proc

    def assert_no_temporary_files(self):
        self.assertEqual([], list(self.out.glob('.report-*.tmp')))

    def test_success_publishes_running_then_completed_atomically(self):
        replace = runner.os.replace
        publications = []

        def observe(source, destination):
            self.assertEqual(self.out, Path(source).parent)
            self.assertEqual(self.out / 'report.json', destination)
            publications.append(json.loads(Path(source).read_text())['status'])
            self.assertEqual('completed' if len(publications) == 1 else 'running', self.receipt()['status'])
            return replace(source, destination)

        with mock.patch.object(runner.os, 'replace', side_effect=observe):
            self.assertEqual(0, runner.main())
        self.assertEqual(['running', 'completed'], publications)
        report = self.receipt()
        self.assertEqual({'tests': 2, 'assertions': 4, 'failures': 0, 'errors': 0}, report['test_counts'])
        digest = hashlib.sha256(self.before).hexdigest()
        self.assertEqual(digest, report['baseline_sha256_before'])
        self.assertEqual(digest, report['baseline_sha256_after'])
        self.assertTrue(report['baseline_unchanged'])
        self.assertTrue(report['artifacts_present'])
        self.assertEqual(self.before, self.baseline.read_bytes())
        self.popen.assert_called_once_with(runner.command, cwd=self.root, stdout=subprocess.PIPE,
                                           stderr=subprocess.STDOUT, text=True,
                                           start_new_session=(runner.os.name == 'posix'))
        self.assertNotIn(str(self.root), self.stdout.getvalue())
        self.assert_no_temporary_files()

    def test_prior_success_interruption_before_old_edn_cleanup(self):
        unlink = Path.unlink

        def interrupt(path, *args, **kwargs):
            if path == self.out / 'workflow.edn':
                self.assertEqual('running', self.receipt()['status'])
                raise KeyboardInterrupt()
            return unlink(path, *args, **kwargs)

        with mock.patch.object(Path, 'unlink', interrupt):
            with self.assertRaises(KeyboardInterrupt):
                runner.main()
        self.assertEqual('running', self.receipt()['status'])
        self.assertNotIn('old', self.receipt())
        for name in ('workflow.edn', 'tests.edn'):
            self.assertEqual('old-' + name, (self.out / name).read_text())
        self.popen.assert_not_called()
        self.assert_no_temporary_files()

    def test_prior_success_interruption_after_cleanup(self):
        self.popen.side_effect = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            runner.main()
        self.assertEqual('running', self.receipt()['status'])
        for name in ('workflow.edn', 'tests.edn'):
            self.assertFalse((self.out / name).exists())
        self.assert_no_temporary_files()

    def assert_running_failure(self):
        self.assertEqual(1, runner.main())
        self.assertEqual(self.old_report, (self.out / 'report.json').read_bytes())
        for name in ('workflow.edn', 'tests.edn'):
            self.assertEqual('old-' + name, (self.out / name).read_text())
        self.assertEqual(self.before, self.baseline.read_bytes())
        self.popen.assert_not_called()
        report = json.loads(self.stdout.getvalue())
        self.assertEqual('failed', report['status'])
        self.assertEqual('running-publication', report['error']['phase'])
        self.assertNotIn(str(self.root), self.stdout.getvalue())
        self.assert_no_temporary_files()

    def test_running_tempfile_failure_does_no_destructive_work(self):
        with mock.patch.object(runner.tempfile, 'NamedTemporaryFile',
                               side_effect=PermissionError(errno.EACCES, str(self.root))):
            self.assert_running_failure()

    def test_running_replace_failure_does_no_destructive_work(self):
        with mock.patch.object(runner.os, 'replace',
                               side_effect=PermissionError(errno.EACCES, str(self.root))):
            self.assert_running_failure()

    def test_running_write_failure_does_no_destructive_work(self):
        dumps = json.dumps
        calls = 0

        def fail_once(*args, **kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise OSError(errno.ENOSPC, str(self.root))
            return dumps(*args, **kwargs)

        with mock.patch.object(runner.json, 'dumps', side_effect=fail_once):
            self.assert_running_failure()

    def test_terminal_replace_failure_leaves_running_receipt(self):
        replace = runner.os.replace

        def fail_terminal(source, destination):
            if json.loads(Path(source).read_text())['status'] != 'running':
                raise OSError(errno.ENOSPC, str(self.root))
            return replace(source, destination)

        with mock.patch.object(runner.os, 'replace', side_effect=fail_terminal):
            self.assertEqual(1, runner.main())
        self.assertEqual('running', self.receipt()['status'])
        report = json.loads(self.stdout.getvalue())
        self.assertEqual('terminal-publication', report['error']['phase'])
        self.assertEqual('failed', report['status'])
        self.assertNotIn(str(self.root), self.stdout.getvalue())
        self.assert_no_temporary_files()

    def test_nonzero_process_exit_is_failed(self):
        def launch(*args, **kwargs):
            proc = self.launch(*args, **kwargs)
            proc.returncode = 1
            return proc
        self.popen.side_effect = launch
        self.assertEqual(1, runner.main())
        self.assertEqual('failed', self.receipt()['status'])
        self.assertEqual(1, self.receipt()['exit_code'])
        self.assert_no_temporary_files()

    def test_missing_or_nonpositive_or_failing_counts_are_failed(self):
        cases = ['no summary', 'Ran 0 tests containing 4 assertions.\n0 failures, 0 errors.',
                 'Ran 2 tests containing 0 assertions.\n0 failures, 0 errors.',
                 'Ran 2 tests containing 4 assertions.\n1 failures, 0 errors.',
                 'Ran 2 tests containing 4 assertions.\n0 failures, 1 errors.']
        for output in cases:
            with self.subTest(output=output):
                def launch(*args, **kwargs):
                    proc = self.launch(*args, **kwargs)
                    proc.communicate.return_value = (output, None)
                    return proc
                self.popen.side_effect = launch
                self.assertEqual(1, runner.main())
                self.assertEqual('failed', self.receipt()['status'])

    def test_missing_artifact_is_failed(self):
        def launch(*args, **kwargs):
            proc = self.launch(*args, **kwargs)
            (self.out / 'tests.edn').unlink()
            return proc
        self.popen.side_effect = launch
        self.assertEqual(1, runner.main())
        self.assertFalse(self.receipt()['artifacts_present'])
        self.assertEqual('failed', self.receipt()['status'])

    def test_baseline_mutation_is_failed(self):
        def launch(*args, **kwargs):
            proc = self.launch(*args, **kwargs)
            self.baseline.write_bytes(b'changed')
            return proc
        self.popen.side_effect = launch
        self.assertEqual(1, runner.main())
        self.assertFalse(self.receipt()['baseline_unchanged'])
        self.assertNotEqual(self.receipt()['baseline_sha256_before'], self.receipt()['baseline_sha256_after'])
        self.assertEqual('failed', self.receipt()['status'])

    def test_baseline_before_read_failure_is_portable_and_does_not_launch(self):
        read_bytes = Path.read_bytes

        def fail_baseline(path):
            if path == self.baseline:
                raise FileNotFoundError(errno.ENOENT, str(self.root))
            return read_bytes(path)

        with mock.patch.object(Path, 'read_bytes', fail_baseline):
            self.assertEqual(1, runner.main())
        report = self.receipt()
        self.assertEqual('failed', report['status'])
        self.assertEqual({'phase': 'baseline-before', 'type': 'FileNotFoundError', 'errno': errno.ENOENT}, report['error'])
        self.assertFalse(report['baseline_unchanged'])
        self.assertIsNone(report['baseline_sha256_before'])
        self.assertIsNone(report['baseline_sha256_after'])
        self.popen.assert_not_called()
        self.assertNotIn(str(self.root), self.stdout.getvalue())
        self.assert_no_temporary_files()

    def test_baseline_after_read_failure_is_portable_and_terminal(self):
        read_bytes = Path.read_bytes
        reads = 0

        def fail_second_baseline_read(path):
            nonlocal reads
            if path == self.baseline:
                reads += 1
                if reads == 2:
                    raise PermissionError(errno.EACCES, str(self.root))
            return read_bytes(path)

        with mock.patch.object(Path, 'read_bytes', fail_second_baseline_read):
            self.assertEqual(1, runner.main())
        report = self.receipt()
        self.assertEqual('failed', report['status'])
        self.assertEqual({'phase': 'baseline-after', 'type': 'PermissionError', 'errno': errno.EACCES}, report['error'])
        self.assertFalse(report['baseline_unchanged'])
        self.assertEqual(hashlib.sha256(self.before).hexdigest(), report['baseline_sha256_before'])
        self.assertIsNone(report['baseline_sha256_after'])
        self.popen.assert_called_once()
        self.assertNotIn(str(self.root), self.stdout.getvalue())
        self.assert_no_temporary_files()

    def test_spawn_error_is_portable_and_terminal(self):
        self.popen.side_effect = FileNotFoundError(errno.ENOENT, str(self.root))
        self.assertEqual(1, runner.main())
        report = self.receipt()
        self.assertEqual({'phase': 'subprocess', 'type': 'FileNotFoundError', 'errno': errno.ENOENT}, report['error'])
        self.assertEqual('failed', report['status'])
        self.assertNotIn(str(self.root), self.stdout.getvalue())
        self.assert_no_temporary_files()

    def test_timeout_kills_and_collects_process(self):
        process = None

        def launch(*args, **kwargs):
            nonlocal process
            process = self.launch(*args, **kwargs)
            process.returncode = -9
            process.communicate.side_effect = [subprocess.TimeoutExpired(runner.command, 90),
                                               ('Ran 2 tests containing 4 assertions.\n0 failures, 0 errors.', None)]
            return process

        self.popen.side_effect = launch
        with mock.patch.object(runner.os, 'killpg', create=True) as killpg:
            self.assertEqual(1, runner.main())
        if runner.os.name == 'posix':
            killpg.assert_called_once_with(12345, runner.signal.SIGKILL)
        else:
            process.kill.assert_called_once_with()
        self.assertEqual([mock.call(timeout=90), mock.call()], process.communicate.call_args_list)
        self.assertTrue(self.receipt()['timeout'])
        self.assertEqual('failed', self.receipt()['status'])


if __name__ == '__main__':
    unittest.main()
