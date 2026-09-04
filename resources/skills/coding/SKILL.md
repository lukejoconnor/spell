---
name: coding
description: Research, plan, implement, verify, and iterate on coding tasks with efficient context management.
---

CODING TASKS — Research, plan, implement, verify, iterate.

Expect early verification failures. They are normal. Use them to refine your understanding, and continue until the actual task is complete.

RESEARCH before committing to a plan or implementation:
- Identify the relevant code, tests, configs, scripts, data files, and output locations.
- Treat the real environment as the source of truth. Verify important assumptions instead of relying on the prompt, your first impression, or a guessed architecture.
- Determine what the task actually requires: what behavior, artifact, output, or test result counts as completion.
- When errors, tracebacks, or failing commands point to exact files or line numbers, inspect those exact places first, then expand outward as needed.
- Use !peek-now for exploratory reads and disposable probes. Persist only the specific snippets, facts, or outputs you will need on later turns.

Examples:

Check dependencies and environment assumptions:
  '(!peek env-check
      (io/sh "which python3 && python3 --version && python3 -m pytest --version && which rg")
      pkg-check
      (io/sh "python3 - <<'PY'
import importlib.util
mods = ['pytest', 'numpy', 'pandas']
for name in mods:
    print(f'{name}:', bool(importlib.util.find_spec(name)))
PY"))
  ;; end of turn 1 completion
  (prune 3)
  ;; start of turn 2 suffix
  (think "Summary of peek output: python3 and pytest are available; rg is installed; numpy and pandas are importable.")
  '(!call-now source-hits
      (io/grep "def handle_request|class Handler" "src" {:include "*.py" :context 8 :max-count 20}))

Search for the real implementation site before editing:
  '(!peek def-hits
      (io/grep ["def handle_request" "class Handler"] "src" {:include "*.py" :context 8 :max-count 20}))
  ;; end of turn 1 completion
  (prune 2)
  ;; start of turn 2 suffix
  (think "Summary of peek output: handle_request is defined in src/server.py and referenced from src/router.py.")
  '(!call-now impl-lines (io/read-lines "src/server.py" 201 240)
               router-lines (io/read-lines "src/router.py" 110 145))

Read exact ranges along an error trace:
  '(!peek verify
      (io/sh "cd /repo && python3 -m pytest tests/test_server.py::test_handles_empty_input -q"))
  ;; end of turn 1 completion
  (prune 2)
  ;; start of turn 2 suffix
  (persist err-summary
      "Summary of !peek output: AssertionError in test_handles_empty_input; expected empty list but got nil from handle_request.")
  '(!call-now test-lines   (io/read-lines "tests/test_server.py" 52 84)
               router-lines (io/read-lines "src/router.py" 110 145)
               impl-lines   (io/read-lines "src/server.py" 201 240))

Explore a large file ephemerally, then persist only the relevant subset:
  '(!peek file-lines (io/read-lines "src/server.py"))
  ;; end of turn 1 completion
  (prune 2)
  ;; start of turn 2 suffix
  (persist handler-block (subvec file-lines 200 240))
  '(!peek test-lines (io/read-lines "tests/test_server.py" 52 84))
  ;; end of turn 2 completion
  (prune 2)
  ;; start of turn 3 suffix

Use !peek for disposable file creation or one-off probes:
  '(!peek _
      (io/write-file "/tmp/check.py" verify-script)
      probe (io/sh "python3 /tmp/check.py"))
  ;; end of turn 1 completion
  (prune 3)
  ;; start of turn 2 suffix

Read the tests to find constraints not in the task description:
  '(!peek test-code (io/read-lines "tests/test_solution.py"))
  ;; end of turn 1 completion
  (prune 2)
  ;; start of turn 2 suffix
  (persist size-check (subvec test-code 10 16))
  (think "The test compresses output.bin with zlib and asserts the result is under 10000 bytes — I need a compact representation, not a raw dump.")

PLAN before acting:
- State what you think is going on, what parts of the system are relevant, and what you will do next.
- Identify the concrete files, commands, or artifacts involved.
- State how you will tell whether the task is complete.
- If multiple locations, layers, or output paths may matter, name them before proceeding.

Example:
  (think "Plan: inspect the parser and the failing test, update the parser behavior, then run the exact validation command and confirm the expected output/artifact.")

IMPLEMENT:
- Make changes that are supported by the evidence gathered during research.
- Prefer structured io/ tools for reading and editing files.
- Use io/sh for running programs, tests, package managers, and shell utilities.
- Keep the feedback loop intact: when you need results for later reasoning, bind them with !call-now or inspect them with !peek-now.

VERIFY:
- Use the actual validation step that matches the task: exact test, exact command, exact output check, or exact artifact check.
- Use !peek-now for io/sh verification outputs, which may be verbose.
- After a failed verification, summarize what the failure means before moving on.

Example:
  '(!peek verify
      (io/sh "cd /repo && python3 -m pytest tests/test_server.py::test_handles_empty_input -q"))
  ;; end of turn 1 completion
  (prune 2)
  ;; start of turn 2 suffix
  (def err-summary "Summary of !peek output: AssertionError in test_handles_empty_input; expected empty list but got nil from handle_request.")
  '(!call-now impl-lines (io/read-lines "src/server.py" 201 240))

ITERATE:
- If verification fails, keep going. Read the failure, update your model of the task, and try again.
- Re-check your assumptions after each surprising result. Be open to the possibility that your previous reasoning, chosen file, inferred root cause, or validation method was wrong.
- If a command fails or the environment behaves unexpectedly, inspect the actual tools, files, paths, permissions, dependencies, and outputs before concluding anything.

Example:
  (think "My earlier assumption was wrong: the failure is not in src/router.py; the traceback and test output point to src/server.py, and pytest is using a different code path than my custom repro.")

COMPLETION:
- Return concise evidence for completion: what you ran or checked, what passed, and what observable result proves the task is done.
- Do not treat diagnosis, a plausible patch, or a partial check as completion.

Example:
  (think "Validation evidence: ran `python3 -m pytest tests/test_server.py::test_handles_empty_input -q` and it passed; output file `/app/out.json` now exists and contains the expected empty list.")