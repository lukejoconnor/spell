---
name: run-benchmark
description: "Run and analyze Spell benchmarks. Use when the user asks to: run a benchmark, test for regressions, compare Spell vs Claude Code, re-run after changes, analyze benchmark results, investigate failures, or any task involving GSM8K, MATH, AIME, BABILong, Omni-MATH, Exercism, SWE-bench, or the orchestration benchmark. Also triggers for 'check if X broke anything', 'how does Spell compare to CC', or 'run the exercism suite'."
---

# Run Benchmark

**Announce that you are using this skill when it triggers.**

Run Spell benchmarks, compare against baselines, and investigate results. See [references/harness-inventory.md](references/harness-inventory.md) for exact CLI flags and dataset options.

## CRITICAL: Never Fabricate Results

The user has caught Claude fabricating results instead of doing work. This constitutes serious misconduct and undermines trust. In particular, when instructed to inspect many long Spell traces, you must never:

- **Invent or infer trace contents** based on what "probably" happened
- **Summarize traces you haven't opened** — if you didn't read it, you don't know what's in it
- **Fabricate error categories** from symptoms or guesses instead of reading the actual error messages

Fabricating results is **scientific fraud**.

**When the task involves inspecting traces:** You MUST dispatch Sonnet subagents. See §5 below. Do not skip this for any reason — not even for a single trace. Do not summarize subagent "findings" before they return. Do not write investigation results without having dispatched and received results from subagents.

**The test is simple:** For every factual claim in your results ("task X failed with error Y", "Spell used spawn/ask in task Z", "CC timed out on task W"), can you point to the specific file and line where you read that? If not, you are fabricating.

## Workflow

### 1. Check notebook for prior runs

Before starting, search `notebook/INDEX.md` for previous runs on the same benchmark. Use this context to determine expected results and to remind yourself how to perform the analysis.

### 2. Follow instructions

Specification comprises:
- **Which benchmark?**
- **Which methods?** Typical comparison pairs:
  - **Claude:** CC Opus 4.6 vs Spell / toolcall transport / Opus 4.6
  - **Codex:** Codex gpt5.3-codex vs Spell / toolcall transport / gpt5.3-codex
- **What subset of problems?** You may be re-running a previous subset or choosing a new one. When there are multiple levels or categories, choose a representative subset.
- **Pilot needed?** Pilot (1-8 items) for new benchmarks or when harness changes have been made. When there are multiple levels or categories, choose pilot a representative selection.
- **Optionally, motivation?** If this information is provided, use it to guide your interpretation, especially when analyzing traces (see below) and reporting results to user.

### 3. Create notebook entry and dispatch

Create a notebook entry at dispatch time (not after completion). Name: `YYYY-MM-DD-{benchmark}-{context}`. Record the benchmark, dataset, model, conditions, and scope. Update this entry as results come in.

### 4. Run the analysis

Run items in parallel batches of 4-8 at a time. Don't wait for every item in a batch to finish — when most items in a batch are complete (e.g., 4/5 done), start the next batch and begin scoring completed items + checking traces immediately. This gives the user a running picture of accuracy and catches systematic failures early.

Guidelines:
- Always use `--trace` to capture traces and track costs + latency.
- After a pilot or first batch, estimate total cost for the full run. If estimated total cost exceeds $50, consult the user before continuing.
- Detect anomalous results and pause further analyses: for example, if a batch completes immediately with 0/5 score.

**Default trace location:** `traces/YYYY-MM-DD'T'HH-mm-ss/` relative to the project root.

```bash
# Example: math run with tracing
cd benchmarking && uv run run_benchmark.py --dataset gsm8k --condition spell --n 30 --trace

# Example: SWE-bench run with tracing
cd benchmarking && uv run run_swebench.py --dataset mini --condition spell --trace
```

### 5. Investigate traces

After the run completes (or as results stream in), investigate traces by dispatching **Sonnet subagents** — one per item or small group. **Always** dispatch subagents for trace investigation, even for a single item. Use the prompt template in [references/trace-checker-prompt.md](references/trace-checker-prompt.md) verbatim, filling in the placeholders.

Investigation priority:

| Priority | Condition | Why |
|----------|-----------|-----|
| **P1** | Spell errors (crashes, timeouts, parse failures) | Bugs in the language — always fixable |
| **P2** | Spell wrong, baseline right | Reveals capability gaps |
| **P3** | Spell right, baseline wrong | Evidence of Spell's value |
| **P4** | Both wrong | Understanding the difficulty frontier |
| **P5** | Both right | Cost/latency comparison |

Every item in your results must have been individually examined by a subagent that returned real findings to you. If you catch yourself writing investigation results without having dispatched subagents, STOP and go do the actual work.

### Trace Tooling (`spell.trace-tool`)

For quick local inspection before/while subagent review, use the Clojure trace helper:

```bash
# Skeletonize latest extension node in one trace
clj -M -m spell.trace-tool --trace-dir traces/2026-03-02T07-04-01

# Count specific function calls on selected node (zsh: quote ! symbols)
clj -M -m spell.trace-tool --trace-dir traces/2026-03-02T07-04-01 --fn think --fn '!print'

# Aggregate call counts across all nodes (deduped by default)
clj -M -m spell.trace-tool --trace-dir traces/2026-03-02T07-04-01 --count-all-nodes --fn think

# Rethink report: each rethink + preceding expression
clj -M -m spell.trace-tool --trace-dir traces/2026-03-02T07-04-01 --rethinks

# Rethink report across a directory of traces
clj -M -m spell.trace-tool --trace-root traces --rethinks

# Resolve latest errored benchmark row to trace_dir, then inspect
clj -M -m spell.trace-tool --results-jsonl benchmarking/results/unified/full_omni_spell_opus.jsonl
```

Notes:
- Default node selection prefers the latest `:default` node with a parsed program (usually the last extension node).
- `--string-truncate N` controls displayed string truncation (default `32`, `-1` disables truncation).
- `--count-all-nodes` is useful for whole-trace stats; selected-node mode is better for extension-chain end state.

### 6. Scoring and reporting

**Source of truth:** Always score from the benchmark harness's own output, never from agent self-reports. Spell's `ok: true` means "runtime didn't crash" — only the harness `is_resolved: true` means "tests passed." These diverge often.

**Win/loss definition:** A "Spell win" means Spell solved a task that the baseline (same model) did not. Comparisons are always same-model: Spell/Opus vs CC/Opus, or Spell/Codex vs Codex/Codex. A "Spell loss" is the reverse — baseline solved it, Spell didn't.

**Cross-validation:** After scoring a comparison run, programmatically extract `is_resolved` from both conditions' `results.json` and compute spell_wins, spell_losses, both_pass, both_fail. Do not report wins/losses based on manual inspection.

**Format issues:** If scoring fails due to output format (correct answer, wrong format), score manually with sub-agents. Don't count format issues as wrong; fix the scorer or override. Note any manual overrides.

**Results format.** Denominator is always total items — errors count as wrong:

```
X% (correct/total) — N errors, M wrong
```

For comparison runs, use a table:

```markdown
| Runner | Accuracy | Errors | Wrong | Cost | Median Latency |
|--------|----------|--------|-------|------|----------------|
| Spell  | 90% (27/30) | 1 | 2 | $20.14 | 31s |
| CC     | 100% (30/30) | 0 | 0 | $7.44 | 37s |
```

Include error categorization when there are failures:

```markdown
### Error Breakdown
- 2 parse errors (large string in continuation)
- 1 timeout (exceeded 120s budget)
- 1 unbound symbol (effect guard scoping)
```

### 7. Fix, re-run, and finalize

**Harness/scoring issues:** If the root cause is clear and the fix is obvious, fix it and re-run the affected items.

**Model performance issues:** Present the diagnosis and let the user decide. Don't speculatively modify prompts or agents.

**Update the notebook entry** with final results: accuracy table, error categorization with specific error messages from traces, comparison table (if applicable), and which run directories were used for scoring.

## Trace storage

Interesting traces can be preserved in the notebook for future reference. On user request, copy traces to:

```
notebook/traces/{notebook-entry-name}/{trace-contents}
```

For example, if the notebook entry is `2026-03-02-swebench-regression`, store traces at `notebook/traces/2026-03-02-swebench-regression/`. Commit to the notebook repo.

## Harness Quick Reference

| Benchmark | Invoke | Datasets |
|-----------|--------|----------|
| GSM8K, MATH, AIME, Omni-MATH | `cd benchmarking && uv run run_benchmark.py` | `gsm8k`, `math_easy`, `math_hard`, `aime_2025`, `omni_math` |
| BABILong | `cd benchmarking && uv run run_benchmark.py` | `babilong` (auto-selects io agent) |
| SWE-bench | `cd benchmarking && uv run run_swebench.py` | `mini` (50), `lite` (300), `verified` (500) |
| Orchestration | `clj -M:dev -m benchmark run` | 9 orchestration prompts |

See [references/harness-inventory.md](references/harness-inventory.md) for full flag reference and example invocations.

**Note:** Exercism has a separate Clojure harness (`clj -M:dev -m exercism-bench run`) that is not yet unified with the Python harness and lacks `--trace` support. Prefer other benchmarks unless specifically requested.

## Best Practices

- **Use `--trace` on every run.** Traces go to `traces/YYYY-MM-DD'T'HH-mm-ss/` by default. Without traces, post-hoc investigation is impossible.
- **Use `--dry-run`** (Python harness) to verify config before committing to a run.
- **Compare apples to apples.** Same model, same items, same retry policy. Use `--items` to re-run exact subsets.
- **Errors are wrong answers.** The denominator is always total items attempted, never "items that ran without errors."
- **Multiple runs: latest is canonical.** When a task has been run multiple times, report the result from the latest run unless explicitly directed otherwise.
- **Never fabricate.** If you haven't read a trace, you don't know what's in it. Dispatch subagents. Wait for results. Report what they found.
- **Use `uv` for all Python work.** Always use `uv run` (not `python3`) to invoke Python scripts.
