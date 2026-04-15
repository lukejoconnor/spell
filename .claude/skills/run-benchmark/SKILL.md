---
name: run-benchmark
description: "Run and analyze Spell benchmarks. Use when the user asks to: run a benchmark, test for regressions, compare Spell vs Claude Code, re-run after changes, analyze benchmark results, investigate failures, or any task involving GSM8K, MATH, AIME, BABILong, Omni-MATH, Exercism, SWE-bench, or the orchestration benchmark. Also triggers for 'check if X broke anything', 'how does Spell compare to CC', or 'run the exercism suite'."
---

# Run Benchmark

Run Spell benchmarks, compare against baselines, and investigate results. See `benchmarking/AGENTS.md` for full CLI flags, dataset options, and known gotchas.

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

**Runs often take a long time. Do not wait for everything to complete before giving the user interpretable results.** After each batch completes, present an updated results table (using the same format from §6 — accuracy, errors, wrong, cost, median latency). The user should see a running picture of progress throughout the run, not a single dump at the end.

Run items in parallel batches of 4-8 at a time. Don't wait for every item in a batch to finish — when most items in a batch are complete (e.g., 4/5 done), **dispatch trace-investigation subagents for completed items and then start the next batch.** Trace investigation for batch N must be dispatched before batch N+1 launches. This ensures trace subagents run concurrently with the next batch and catches systematic failures early.

Guidelines:
- Always use `--trace` to capture traces and track costs + latency.
- After a pilot or first batch, estimate total cost for the full run. If estimated total cost exceeds $50, consult the user before continuing.
- Detect anomalous results and pause further analyses: for example, if a batch completes immediately with 0/5 score.
- **Disk check before each batch (Docker-backed runs):** On the GCP benchmark VM, run `docker system df` before dispatching a batch and prune if needed. Each SWE-bench item generates ~5-6 GB of Docker artifacts (instance images + containers). If reclaimable space exceeds 20 GB or free space is below 50 GB, run `docker container prune -f && docker image prune -f` to reclaim stopped containers and dangling images. Do NOT prune env images (`sweb.env.*`) — they are shared and expensive to rebuild.

**Default trace location:** `traces/YYYY-MM-DD'T'HH-mm-ss/` relative to the project root.

```bash
# Example: math run (tracing is on by default)
cd benchmarking && uv run python bench.py general --dataset gsm8k --condition spell --n 30

# Example: SWE-bench run (evaluates by default after generation)
cd benchmarking && uv run python bench.py swebench --dataset mini --condition spell
```

### 4b. Init programs and trailing expressions

When the user specifies an init program or trailing expression (e.g., "use init program with `!describe io`"), you need to pass it via the `--trailing` flag.

**How it works:** The harness wraps the benchmark prompt into a Spell init program using `build_spell_init(prompt, trailing)`. The trailing expression controls what the agent does after the prompt is loaded into context. Default trailing is `'(!extend)` (just continue with an LLM call). For math benchmarks, the default is `'(!describe math)` (load math namespace docs first).

**Common trailing patterns:**

| Trailing | Effect |
|----------|--------|
| `'(!extend)` | Default — just continue with an LLM call |
| `'(!describe math)` | Load math namespace docs, then respond |
| `'(!describe io)` | Load io namespace docs, then respond |
| `'(do (!describe io) (!extend))` | Load io docs, then continue with a fresh call |
| `'(!describe io web)` | Load multiple namespace docs |

**Shell quoting — critical:** The trailing expression contains `!` which triggers zsh history expansion inside double quotes. `set +H` does NOT reliably fix this — zsh still backslash-escapes `!` inside double quotes even with history expansion disabled.

**Claude Code Bash tool gotcha:** The Bash tool pre-escapes `!` to `\!` in command strings regardless of quoting context. Even `'(!describe io)'` in single quotes becomes `'(\!describe io)'`. The ONLY reliable pattern is `$'...'` ANSI-C quoting, which bypasses this escaping:

The correct pattern uses `$'...'` quoting to embed the leading single-quote:
```bash
cd benchmarking && uv run python bench.py swebench \
  --dataset lite --condition spell --model anthropic-tc \
  --trailing $'\'(do (!describe io) (!extend))'
```

**For GCP `--command` flag:** The trailing expression must survive nested quoting through `gcp-benchmark.sh --command "..."`. Use `$'...'` for the entire `--command` value:
```bash
./scripts/gcp-benchmark.sh run \
  --name my-vm --run-group my-group \
  --command $'uv run python bench.py swebench --dataset lite --condition spell --model codex-tc:gpt-5.4 --trailing "\'(!describe io)" --items item1,item2 --name my-run'
```

**Always dry-run first** when using a custom trailing to verify the init program is correct:
```bash
cd benchmarking && uv run python bench.py swebench \
  --dataset lite --condition spell --model anthropic-tc \
  --trailing $'\'(do (!describe io) (!extend))' \
  --items task_id_1 --dry-run --n 1
```

Check the dry-run output for `"trailing":` — it should show the expression with unescaped `!`, e.g. `"trailing": "'(do (!describe io) (!extend))"`. If you see `\!` or `'\'(...)` the quoting is wrong.

### 5. Investigate traces — 100% coverage, comprehensive detail

**The user CONSTANTLY requests more detail from trace analysis. Do not give terse summaries. Every trace must be analyzed, and every analysis must be comprehensive.**

**100% trace coverage is MANDATORY.** Dispatch a `trace-checker` subagent for EVERY item in the run — not just errors, not just interesting ones, ALL of them. P5 (both right) traces are just as important as P1 (errors) because they reveal technique differences, cost patterns, and Spell feature usage. Skipping "boring" traces means missing the full picture.

**Summary-first triage:** Before dispatching trace investigation subagents, run `spell.trace-tool --summary` on the trace root for a quick overview of tracked forms, namespace usage, errors, and investigation flags. Also run `--context-trajectory` on the trace root for per-trace character trajectories:

```bash
# Human-readable batch summary
clj -M -m spell.trace-tool --trace-root traces/2026-03-08T10-00-00 --summary

# TSV for sorting/filtering many traces
clj -M -m spell.trace-tool --trace-root traces/2026-03-08T10-00-00 --summary --tsv

# Context trajectories across all traces
clj -M -m spell.trace-tool --trace-root traces/2026-03-08T10-00-00 --context-trajectory
```

Use investigation flags and the priority table below to decide investigation order (NOT which to skip — you skip NONE).

**Dispatch `trace-checker` subagents** — one per item or small group. **Always** dispatch subagents for trace investigation, even for a single item. Pass the task parameters in free text:

```
Agent tool call:
  subagent_type: "trace-checker"
  prompt: |
    Task: django__django-16379
    Condition: spell
    Trace root: traces/2026-03-08T10-04-01
    Spell trace: traces/2026-03-08T10-04-01/spell/django__django-16379
    Harness result: fail (is_resolved: false)
    Comparison: claude_code (same model: opus)
    Focus: Spell wrong, baseline right (P2) — why did Spell fail?
```

The `trace-checker` agent (defined in `.claude/agents/trace-checker.md`) has the full investigation methodology, error classification, output format, and `spell.trace-tool` documentation baked into its system prompt.

Investigation priority (determines ORDER, not coverage):

| Priority | Condition | Why |
|----------|-----------|-----|
| **P1** | Spell errors (crashes, timeouts, parse failures) | Bugs in the language — always fixable |
| **P2** | Spell wrong, baseline right | Reveals capability gaps |
| **P3** | Spell right, baseline wrong | Evidence of Spell's value |
| **P4** | Both wrong | Understanding the difficulty frontier |
| **P5** | Both right | Cost/latency comparison, feature usage patterns |

**When to investigate comparison traces:** For P2 (Spell loss) and P3 (Spell win), include the trace root in the subagent prompt so it can locate the comparison method's trace. The subagent knows the trace directory layout and will read both traces to diagnose what differed. For P1, P4, and P5, comparison traces are not needed unless specifically requested.

Every item in your results must have been individually examined by a subagent that returned real findings to you. If you catch yourself writing investigation results without having dispatched subagents, STOP and go do the actual work.

**Note:** `spell.trace-tool` has additional modes beyond `--summary` (skeletonize nodes, count function calls, rethink reports, context trajectory). The `trace-checker` subagent has full documentation. If you need the tool directly in the main conversation, refer to `.claude/agents/trace-checker.md` for usage.

### 6. Scoring and reporting

**Source of truth for SWE-bench:** Use `same_container_resolved` from the unified JSONL as the default scoring source. This runs the harness tests inside the same container where the agent made changes, immediately after generation — no separate eval step needed. Only run standalone `bench.py swebench-eval` (fresh-container evaluation) when the user explicitly requests it, or when same-container results look suspicious (e.g., patches that modify test files). Report results from `same_container_resolved` as soon as generation completes; do not wait for or run a separate eval pass unless asked.

**Source of truth (non-SWE-bench):** Always score from the benchmark harness's own output, never from agent self-reports. Spell's `ok: true` means "runtime didn't crash" — only the harness `is_resolved: true` means "tests passed." These diverge often.

**Cost: use the unified JSONL's `cost_usd` field, NOT the `tb results.json` token fields.** The `terminal-bench/<run-id>/results.json` file written by the `tb` harness has its own `total_input_tokens` / `total_output_tokens` fields that do NOT include the cached-vs-uncached breakdown (they show `cached_input_tokens: 0` even when the real cache fraction is 90%+). Computing cost from those fields at uncached rates produces wildly inflated numbers (e.g. 5× over-estimate). Always sum `cost_usd` from the unified JSONL (`results/.../terminal-bench/<run-name>.jsonl` or `results/.../full_*.jsonl`) — each record has a Clojure-computed `cost_usd` that properly accounts for cached tokens.

**Win/loss definition:** A "Spell win" means Spell solved a task that the baseline (same model) did not. Comparisons are always same-model: Spell/Opus vs CC/Opus, or Spell/Codex vs Codex/Codex. A "Spell loss" is the reverse — baseline solved it, Spell didn't.

**Cross-validation:** After scoring a comparison run, programmatically extract `is_resolved` from both conditions' `results.json` and compute spell_wins, spell_losses, both_pass, both_fail. Do not report wins/losses based on manual inspection.

**Manual scoring.** Some benchmarks have flaky or extremely stringent automatic scoring. For these, results should be scored manually for partial credit. Present both harness-graded and manually-graded results, and explain any discrepancies.

| Benchmark | Auto-scoring | Manual scoring needed? |
|-----------|-------------|----------------------|
| **Omni-MATH** | <50% recall — format sensitivity (Unicode vs LaTeX, set ordering, equivalent expressions, text answers) | **Always.** Auto-scorer misses most correct answers. |
| **MATH (hard)** | Fragile LaTeX parsing, symbolic equivalence sometimes missed by sympy | **Yes** for non-trivial expressions. Spot-check at minimum. |
| **BABILong / LongBench** | Freeform answer extraction heuristic; ~15% affected by context truncation | **Recommended.** Last-line heuristic unreliable for multi-word answers. |
| **GSM8K** | Numeric extraction with decimal normalization | **No.** Reliable. |
| **AIME** | Integer answers 0-999, exact match | **No.** Reliable. |
| **SWE-bench** | Official harness, test execution | **No.** Reliable (note: 1 known flaky item, `django-14382`). |
| **Exercism** | pytest pass/fail | **No.** Reliable. |

When manual scoring applies, dispatch `trace-checker` subagents to verify each disputed item. Format issues (correct answer, wrong format) should not count as wrong — fix the scorer or override, and note any manual overrides.

**Results format.** Denominator is always total items — errors count as wrong:

```
X% (correct/total) — N errors, M wrong
```

**Result ordering.** Always present results in a consistent order to reduce cognitive load:
1. Spell before comparator (e.g., Spell row above CC row)
2. If multiple models, alphabetical by model name (e.g., GPT before Opus)

**Method naming.** Use `[harness]/[model]` shorthand consistently in tables and prose: e.g., Spell/Opus, CC/Opus, Spell/GPT-5.4, Codex/GPT-5.4, Codex/Codex (the last = gpt-5.3-codex).

For comparison runs, use a table:

```markdown
| Runner | Accuracy | Errors | Wrong | Cost | Median Latency |
|--------|----------|--------|-------|------|----------------|
| Spell/Opus  | 90% (27/30) | 1 | 2 | $20.14 | 31s |
| CC/Opus     | 100% (30/30) | 0 | 0 | $7.44 | 37s |
```

Include error categorization when there are failures:

```markdown
### Error Breakdown
- 2 parse errors (large string in continuation)
- 1 timeout (exceeded 120s budget)
- 1 unbound symbol (effect guard scoping)
```

**Comprehensive trace analysis report.** After all trace-checker subagents return, present a detailed analysis section. This is NOT optional — the user ALWAYS wants this level of detail:

```markdown
### Trace Analysis

#### Feature Usage Overview
Summary of Spell feature usage across ALL traces in the run:
- Which features were commonly used vs rarely used vs never used
- Patterns in how features were employed (e.g., "rethink used in 8/10 traces, always to prune tool output")
- **Prune/peek patterns:** How often was prune used, and was it paired with !peek? What was typically pruned? Total characters pruned across the run.
- **Character trajectory patterns:** Typical context growth patterns across the run (stable, growing, sawtooth). Median start/peak/final context sizes.
- **IO breakdown:** io/sh usage vs structured io/ functions (read-file, write-file, list-dir). Is the agent shelling out or using native capabilities?
- Any surprising usage patterns or absences

#### Per-Item Analysis
For EVERY item (not just errors/failures), include the trace-checker's findings:
- Narrative of what happened
- Feature inventory
- Key findings with evidence
- Error analysis where applicable (including recovery rounds, causes, outcomes)

#### Interesting Traces
Highlight 2-5 traces that are particularly interesting and explain WHY:
- Traces where Spell features demonstrably helped or hurt
- Traces with unusual error recovery patterns
- Traces that reveal capability gaps or strengths
- Explain HOW the feature was used, WHY the agent chose it, and TO WHAT EFFECT
```

Do NOT collapse trace analysis into a terse table. The table (§6 results format) provides the quantitative summary; the trace analysis section provides the qualitative depth. Both are required.

### 7. Fix, re-run, and finalize

**Harness/scoring issues:** If the root cause is clear and the fix is obvious, fix it and re-run the affected items.

**Model performance issues:** Present the diagnosis and let the user decide. Don't speculatively modify prompts or agents.

**Update the notebook entry** with final results: accuracy table, error categorization with specific error messages from traces, comparison table (if applicable), per-item trace analysis summaries, and which run directories were used for scoring.

## Trace storage

Interesting traces can be preserved in the notebook for future reference. On user request, copy traces to:

```
notebook/traces/{notebook-entry-name}/{trace-contents}
```

For example, if the notebook entry is `2026-03-02-swebench-regression`, store traces at `notebook/traces/2026-03-02-swebench-regression/`. Commit to the notebook repo.

## Harness Quick Reference

All Python harness commands use `cd benchmarking && uv run python bench.py <subcommand>`. Tracing is on by default for `general` and `swebench`.

| Benchmark | Subcommand | Datasets |
|-----------|------------|----------|
| Math/Reasoning | `general` | `gsm8k`, `math_hard`, `math_easy`, `aime_2025`, `aime_2026`, `hmmt_feb_2025`, `hmmt_nov_2025`, `omni_math`, `omni_math_hard` |
| Long-Context | `general` | `longbench_short`, `babilong_32k_qa2`, `babilong_32k`, `babilong_16k`, `babilong_8k` |
| SWE-bench | `swebench` | `mini` (50), `lite` (300), `verified` (500), `pro` (731) |
| Exercism | `exercism` | (use `--difficulty`, `--slugs`, `--n` to filter) |
| FeatureBench | `featurebench` | (all tasks by default) |
| ScienceAgentBench | `scienceagentbench` | (use `--n` to limit) |
| Orchestration | `clj -M:dev -m benchmark run` | 4 orchestration prompts |

Exercism also has a legacy Clojure harness (`clj -M:dev -m exercism-bench run`) and a unified Python path (`bench.py exercism --unified`) that runs on the shared container infrastructure.

## Plotting Results

Use `benchmarking/src/plot_results.py` for all result visualizations. It provides canonical colors, method labels, and horizontal bar charts with optional Wilson CIs and harness-vs-manual grouped bars.

```python
import sys
sys.path.insert(0, "benchmarking/src")
from plot_results import plot_benchmark, MethodResult

results = [
    MethodResult("spell_opus", 28, 50),
    MethodResult("cc_opus", 22, 50),
]
plot_benchmark(results, title="Terminal-Bench", save_path="/tmp/tb.png")
```

Method keys: `spell_opus`, `cc_opus`, `oneshot_opus`, `spell_codex`, `codex_cli`, `oneshot_codex`. Pass `manual_results=` for harness-vs-manual grouped bars, `error_bars=True` for Wilson CIs. Run via `uv run` from the benchmarking directory.

## Best Practices

- **Model names must be exact.** Always check `config/providers/*.provider.edn` for the correct model ID string before running. Common pitfall: `gpt-5.4-codex` does not exist — the correct ID is `gpt-5.4` (routed via `codex-tc:gpt-5.4`). Using an incorrect model ID causes "mandatory tool-call request failed" errors that waste the entire run.
- **Always evaluate.** Never use `--no-evaluate`. The user wants to see evaluation results as soon as possible. SWE-bench evaluates by default after generation; do not disable this.
- **Use `--trace` on every run.** Tracing is on by default for `general` and `swebench`. Without traces, post-hoc investigation is impossible.
- **Use `--dry-run`** (Python harness) to verify config before committing to a run.
- **Compare apples to apples.** Same model, same items, same retry policy. Use `--items` to re-run exact subsets.
- **Errors are wrong answers.** The denominator is always total items attempted, never "items that ran without errors."
- **Multiple runs: latest is canonical.** When a task has been run multiple times, report the result from the latest run unless explicitly directed otherwise.
- **Never fabricate.** If you haven't read a trace, you don't know what's in it. Dispatch subagents. Wait for results. Report what they found.
- **Use `uv` for all Python work.** Always use `uv run` (not `python3`) to invoke Python scripts.

## Docker-Based Evals: Use The GCP Benchmark VM

For any Docker-backed benchmark or eval run, default to the GCP launcher in the main repo instead of local Colima/Docker. This is now the preferred path for SWE-bench and other container-heavy evals unless the user explicitly asks to run locally.

Why this is the default:
- Native Linux Docker instead of ARM-hosted x86 emulation on a laptop
- Isolated disk for large instance/env images and container churn
- Persistent tmux session for long runs
- Built-in auto-delete via Compute Engine `--max-run-duration`

### Standard Workflow

For unattended Docker-backed evals, launch from the main repo root with `run` and a shared run-group:

```bash
./scripts/gcp-benchmark.sh run \
  --name <vm-name> \
  --run-group <group> \
  --spell-ref <spell-ref> \
  --benchmarking-ref <benchmarking-ref> \
  --command "<benchmark command>"
```

Monitor the batch with project-level fleet discovery:

```bash
./scripts/gcp-benchmark.sh wait --run-group <group> --finish
```

For Codex/Claude background monitoring, prefer launching that `wait --finish` command as the long-running process. It keeps the polling loop in bash instead of spending LLM turns on repeated `status-all` checks, and it returns only once artifacts are already pulled locally.

For non-destructive checkpoints, use:

```bash
./scripts/gcp-benchmark.sh pull-all --run-group <group>
```

Pulled artifacts land directly under:
- `benchmarking/results/gcp/<vm-name>/...`
- `benchmarking/traces/gcp/<vm-name>/...`
- `benchmarking/logs/gcp/<vm-name>/...`

`start` and `ssh` are still the manual debugging path when you want an interactive tmux shell on the VM.

If you need to inspect or clean up one VM directly:

```bash
./scripts/gcp-benchmark.sh status --name <vm-name>
./scripts/gcp-benchmark.sh pull --name <vm-name>
./scripts/gcp-benchmark.sh finish --name <vm-name>
```

### Notes

- Pass the same `--run-group` across related VMs; it is the primary fleet-scoping mechanism for `wait`, `status-all`, `pull-all`, and `finish-all`.
- Use `--all` only when you intentionally want every Spell-managed benchmark VM in the GCP project.
- Use `--spell-ref` and `--benchmarking-ref` to point the VM at the exact branches under test.
- The one-time GCP/Secret Manager setup lives in notebook entry `gcp-benchmark-setup-guide`. Key gotcha: each new Secret Manager secret needs an explicit `add-iam-policy-binding` for the Compute Engine service account — `gcloud secrets create` does NOT grant access automatically.
- If a startup or initial attach step fails, the launcher now deletes the VM automatically rather than leaving it running.

### GCP Model Specs

On GCP, `codex-tc:gpt-5.4` requires a `CODEX_AUTH_JSON_B64` secret in Secret Manager (base64-encoded `~/.codex/auth.json`). If this secret is not configured, use `openai-tc:gpt-5.4` instead — it routes through the standard OpenAI Responses API using `OPENAI_API_KEY`.

Codex session tokens expire. After re-logging in locally with `codex`, update the secret:
```bash
gcloud secrets versions add CODEX_AUTH_JSON_B64 \
  --project spellbenchmarking \
  --data-file=<(base64 < ~/.codex/auth.json)
```

| Local | GCP (with Codex auth) | GCP (without Codex auth) |
|-------|----------------------|--------------------------|
| `codex-tc:gpt-5.4` | `codex-tc:gpt-5.4` | `openai-tc:gpt-5.4` |
| `anthropic-tc:claude-opus-4-6` | `anthropic-tc:claude-opus-4-6` | `anthropic-tc:claude-opus-4-6` |

### SWE-bench on Fresh VMs

The first SWE-bench run on a new VM requires building environment images from scratch (~10 min per unique env). These are Docker-cached for subsequent runs on the same VM. If the harness errors with `container_error` and empty messages on a fresh VM, this is the likely cause — the env images need to be built before `build_container` can succeed.

**Always use `--prewarm-envs`** for SWE-bench runs with `--parallel > 1`. Without it, parallel env image builds race on a shared Dockerfile path and fail 20-30% of the time. The prewarm flag builds all unique env images sequentially before launching parallel agent execution.

**Over-provision VMs aggressively.** VM costs are <10% of API costs — a $46 API run on a $1 VM should never fail due to insufficient disk or RAM. Default to `--machine-type e2-standard-16 --boot-disk-size 300` for SWE-bench runs. The marginal cost (~$1 extra per run) is negligible compared to losing an entire API-cost run to OOM or disk-full.

### Monitoring Long-Running GCP Jobs

**Do NOT fire-and-forget GCP benchmark runs.** The `wait --finish` background process is necessary but not sufficient — it relies on SSH which can be flaky. For any run expected to take more than 30 minutes:

1. **Use `/loop` to set up periodic status checks** (e.g., `/loop 30m check status`). Check both the `wait` output file AND `gcloud compute instances list` directly.
2. **Check for early failures** within the first 15-20 minutes — disk full, OOM, Docker build errors, and SSH connectivity issues all manifest early. Don't assume a run will complete just because VMs launched successfully.
3. **Pull partial results** with `pull-all --run-group` if the `wait` process stalls. VMs may have completed work even if the wait script can't reach them.
4. **Check serial console** (`gcloud compute instances get-serial-port-output`) when SSH fails — it reveals OOM kills, DHCP failures, and disk-full errors that SSH-based status checks miss.
5. **Kill zombie VMs promptly** — VMs that are RUNNING but unreachable (lost DHCP, disk full) waste money. Delete them and relaunch with fixes.
