---
name: run-benchmark
description: "Run and analyze Spell benchmarks. Use when the user asks to: run a benchmark, test for regressions, compare Spell vs Claude Code, re-run after changes, analyze benchmark results, investigate failures, or any task involving GSM8K, MATH, AIME, BABILong, Omni-MATH, Exercism, SWE-bench, or the orchestration benchmark. Also triggers for 'check if X broke anything', 'how does Spell compare to CC', or 'run the exercism suite'."
---

# Run Benchmark

**Announce that you are using this skill when it triggers.**

Run Spell benchmarks, compare against baselines, and investigate results. Four harnesses exist — see [references/harness-inventory.md](references/harness-inventory.md) for exact CLI flags and dataset options.

## Workflow

### 1. Check notebook for prior runs

Before starting, search `notebook/INDEX.md` for previous runs on the same benchmark. Remind the user of the most recent results, including date, accuracy, cost, and any notes. This provides context for whether results represent improvement, regression, or stability.

### 2. Decide scope

Infer from context or clarify with the user:
- **Which benchmark?** Math (GSM8K, MATH, AIME, Omni-MATH), long-context (BABILong), coding (Exercism, SWE-bench), or orchestration
- **Why?** Regression test after changes, new benchmark, comparison run, or re-investigation
- **Comparison?** Typical comparison pairs:
  - **Claude:** CC Opus 4.6 vs Spell / toolcall transport / Opus 4.6
  - **Codex:** Codex gpt5.3-codex vs Spell / toolcall transport / gpt5.3-codex
- **Pilot needed?** Pilot (5-10 items) for new or unfamiliar benchmarks. For established benchmarks with known-working harnesses, or cheap benchmarks (GSM8K, MATH Easy), skip straight to the full run.

For regression tests after language changes, run the benchmark suite that exercises the changed feature. Common pairings:
- Prompt/system prompt changes → GSM8K + MATH (fast, sensitive to prompt)
- Parser/eval changes → BABILong + Exercism (exercise code generation paths)
- New builtins/macros → re-run the benchmark where the gap was identified
- Broad changes → GSM8K + MATH Easy + BABILong 16k (the "smoke test" trio)

### 3. Create notebook entry and dispatch

Create a notebook entry at dispatch time (not after completion). Name: `YYYY-MM-DD-{benchmark}-{context}`. Record the benchmark, dataset, model, conditions, and scope. Update this entry as results come in.

### 4. Run in batches with real-time scoring

Run items in parallel batches of 5-8 at a time. Don't wait for every item in a batch to finish — when most items in a batch are complete (e.g., 4/5 done), start the next batch and begin scoring completed items immediately.

Score and report results in real time as batches complete. This gives the user a running picture of accuracy and catches systematic failures early.

```bash
# Example: math run
cd benchmarking && uv run run_benchmark.py --dataset gsm8k --condition spell --n 30

# Example: exercism run
clj -M:dev -m exercism-bench run --difficulty 4-5 --limit 8
```

### 5. Cost management

- Always use `--trace` when available to capture traces and track costs + latency.
- After a pilot or first batch, estimate total cost for the full run.
- **If estimated total cost exceeds $50, consult the user before continuing.**
- Report running cost as batches complete.

### 6. Handle Claude Code / Codex environment

When running CC or Codex baselines, the user's `claude.md` / `agents.md` affect results. This is fine for pilots and quick comparisons, but for reported results, disable custom config:

- **Claude Code:** `claude --setting-sources "" -p "your prompt"` — skips all custom settings/CLAUDE.md.
- **Codex:** `./scripts/codex-no-custom.sh` (interactive) or `./scripts/codex-no-custom.sh exec "prompt"` (non-interactive).

Record in the results file whether custom config was disabled.

### 7. Investigate results by priority

After the run completes (or as results stream in), investigate in this order:

| Priority | Condition | Why |
|----------|-----------|-----|
| **P1** | Spell errors (crashes, timeouts, parse failures) | Bugs in the language — always fixable |
| **P2** | Spell wrong, Claude Code right | Spell's approach failed where iterative tool-use succeeded — reveals capability gaps |
| **P3** | Spell right, Claude Code wrong | Spell's approach worked where CC didn't — evidence of Spell's value |
| **P4** | Both wrong | Hard problems — useful for understanding difficulty frontier |
| **P5** | Both right | Lowest priority — confirms things work, check cost/latency differences |

For each investigated item:
- Read the verbose/trace log to understand what Spell actually did
- For errors: identify root cause category (parse error, unbound symbol, timeout, API failure, semantic)
- For wrong answers: was the approach sound but execution failed, or was the approach itself wrong?
- For Spell-vs-CC differences: what did CC's iterative tool-use do that Spell's code-generation didn't (or vice versa)?
- **For Spell wins (P3) especially:** look for evidence of interesting Spell features in the trace — orchestration (spawn/ask), context pruning (!compact, rethink), inline math, control flow composition, or toolcall composition. These are the most compelling examples of Spell's value and worth highlighting in results.

### 8. Scoring issues

If scoring fails due to output format issues (correct answer but wrong format), score manually — use sub-agents if there are many items. Don't count format issues as wrong answers; fix the scorer or override the score. Note any manual overrides in the results.

### 9. Report results

Use the standard format. Denominator is always total items — errors count as wrong:

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

### 10. Fix and re-run (selectively)

**Harness/scoring issues:** If the root cause is clear and the fix is obvious (broken scorer, config typo, harness bug), fix it and re-run the affected items.

**Model performance issues:** Diagnose the failure pattern and consult with the user before making changes. Don't speculatively modify prompts or agents to fix benchmark performance — present the diagnosis and let the user decide on the approach.

### 11. Update notebook entry

Update the notebook entry created at dispatch with final results:
- Results table with accuracy, cost, latency
- Error categorization
- Comparison table (if applicable)
- What changed since last run (if regression test)
- Findings and any fixes applied
- Whether `claude.md`/`agents.md` were cleared (for CC/Codex runs)

## Harness Quick Reference

| Benchmark | Harness | Invoke | Datasets |
|-----------|---------|--------|----------|
| GSM8K, MATH, AIME, Omni-MATH | Python | `cd benchmarking && uv run run_benchmark.py` | `gsm8k`, `math_easy`, `math_hard`, `aime_2025`, `omni_math` |
| BABILong | Python | `cd benchmarking && uv run run_benchmark.py` | `babilong` (auto-selects io agent) |
| SWE-bench | Python | `cd benchmarking && uv run run_swebench.py` | `mini` (50), `lite` (300), `verified` (500) |
| Exercism | Clojure | `clj -M:dev -m exercism-bench run` | Exercism Python (129 exercises, d1-d9) |
| Orchestration | Clojure | `clj -M:dev -m benchmark run` | 9 orchestration prompts |

See [references/harness-inventory.md](references/harness-inventory.md) for full flag reference and example invocations.

## Best Practices

- **Use `--dry-run`** (Python harness) to verify config before committing to a run.
- **Always save traces.** Use `--trace` wherever available. Per-item logs are essential for post-hoc investigation.
- **Track cost and latency.** Report total cost, cost-per-item, and median latency. Budget flags prevent runaway spending.
- **Compare apples to apples.** Same model, same items, same retry policy. Use `--items` to re-run exact subsets.
- **Errors are wrong answers.** The denominator is always total items attempted, never "items that ran without errors."
- **Use `uv` for all Python work.** Always use `uv run` (not `python3`) to invoke Python scripts and `uv pip` for package management.
