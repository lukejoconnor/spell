"""Focused fresh-JVM checks for the standalone runner's stale-receipt guard."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class StandaloneGuardTests(unittest.TestCase):
    def check_stale_receipt(self, stale_name):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            workflow = out / 'workflow.edn'
            tests = out / 'tests.edn'
            stale = out / stale_name
            old = b'{:status :completed :old true}\n'
            stale.write_bytes(old)
            command = ['clojure', '-Sdeps', '{:paths ["src" "resources" "test"]}', '-M',
                       'dev/dogfood-reliability-run/run.clj', str(workflow), str(tests)]
            proc = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, timeout=30)
            self.assertEqual(1, proc.returncode, proc.stdout)
            self.assertIn('Receipt precondition failed: destinations must be absent.', proc.stdout)
            self.assertNotIn('Ran ', proc.stdout)
            self.assertEqual(old, stale.read_bytes())
            other = tests if stale == workflow else workflow
            self.assertFalse(other.exists())

    def test_existing_workflow_receipt_blocks_tests(self):
        self.check_stale_receipt('workflow.edn')

    def test_existing_test_receipt_blocks_tests(self):
        self.check_stale_receipt('tests.edn')


if __name__ == '__main__':
    unittest.main()
