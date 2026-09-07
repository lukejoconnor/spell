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
- Use !peek for exploratory reads and disposable probes. Persist only the specific snippets, facts, or outputs you will need on later turns.

Bound research by the decision it enables (see [context-efficiency](../context-efficiency/SKILL.md#bounded-read-packets-and-a-durable-decision-record)):
- Start with one unresolved question, exact source/test pointers, and one small read packet. Bound aggregate rendered output up front, including multiple bindings (roughly 3,000 rendered characters including syntax and escaping is a starting point, not a guaranteed inline threshold). Use exact `io/read-lines` ranges or narrow `io/grep` paths with documented `:include`, `:max-count`, and small `:context` options. `:max-count` limits matches per file, not total output; many files or large context can still overflow the packet.
- Before disposable results are pruned, write an explicit compact action checkpoint as a literal `(def checkpoint {...})` after the packet's prune marker, or use `(persist checkpoint expression)` to materialize computed evidence. Include observed path/range or symbol, finding, decision, remaining uncertainty, named next artifact/edit, and verification command. A `think` alone or a `def` that depends on soon-pruned bindings is not a portable record. Retain the checkpoint explicitly through later pruning, compaction, or a fresh self-call; do not replay tool effects to reconstruct it. Keep actual execution receipts distinct from proposed actions.
- Once the next action is known, produce its named artifact (patch, prototype, test, or report) or report a specific blocker before overlapping rereads. A blocker names the missing contract/evidence and the smallest request or experiment that would resolve it; it is not another broad reading plan.
- Before another research packet, identify genuinely new evidence needed: a changed source, failing check, unanswered question, or unavailable result. State what the bounded packet adds to the checkpoint. Do not reread merely because raw output was pruned.
- An opaque stored-result marker is not inspected evidence. Retrieve bounded pages from the original binding or `(stored observed-id)` before another source read: `subs` for strings, `subvec` for line vectors, or the documented field of a result map. `!print` takes one expression, not result-name/expression pairs. See [lossless retrieval recipes](../context-efficiency/SKILL.md#retrieve-retained-output-before-another-read). Preserve the observed ID, offsets and inspected evidence before pruning; report unavailable evidence rather than guessing success or repeating the same broad describe/read batch.
- Carry checkpoint data and outstanding receipt obligations explicitly into new self-call source, using fresh locals rather than relying on stale bindings. These are optional, model-controlled task decisions expressed in ordinary Spell programs, not hidden harness progress counters, retry/deduplication rules, read suppression, or changes to coordinator semantics.

For example, after inspecting a packet (illustrative facts, not execution receipts):
```clojure
(def checkpoint {:source "src/parser.clj:21-32"
                 :finding "Empty input reaches the indexing branch."
                 :decision "Add an empty-input guard."
                 :open "Expected empty result is established by the test."
                 :next-artifact "src/parser.clj guard and one regression test"
                 :check "Run the targeted parser test."
                 :receipts []})
'(!extend)
```

Shell examples below assume `io/sh` is exposed by the current agent. Check available namespace documentation first; if unavailable, use an exposed equivalent or report the verification blocker rather than assuming shell access.

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
  '(!peek file-lines (io/read-lines "src/server.py" 1 80))
  ;; end of turn 1 completion
  (prune 2)
  ;; start of turn 2 suffix
  (persist handler-block (subvec file-lines 20 40))
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
