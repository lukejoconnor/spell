# Simulation Results Curation

**Date:** 2026-03-25
**Prepared from:** notebook result entries, notebook index, and `benchmarking/AGENTS.md`

## Scope

This document is an organization pass over benchmark and simulation-style result entries. It is intentionally non-interpretive.

Included here:
- what was run
- benchmark families, methods, and Spell variants
- sample sizes / denominators
- recorded numerical outcomes
- unresolved methodological issues
- observed Spell feature usage, especially context management and in-language computation

Excluded here:
- new scientific conclusions
- cross-entry narrative synthesis beyond simple grouping

## 1. Relevant Entries

### Core result entries reviewed directly

#### Math / reasoning / long-context
- `gaia-benchmark`
- `omni-math-rerun`
- `omni-math-codex-recheck`
- `aime-2026-full`
- `aime-2026-sonnet`
- `mathconstruct-40-four-method`
- `project-euler-40-fourway`
- `project-euler-scorer-fix`
- `longbench-four-way-rerun`
- `longbench-acm-rerun`
- `omni-math-hard-relay-describe`
- `omni-math-hard-gpt54`

#### Coding benchmarks
- `featurebench-worktree-refactor`
- `swebench-lite-16-random`
- `swebench-codex-comparison`
- `swebench-pro-20`
- `swebench-pro-opus`
- `swebench-pro-fix-loop-pilot`
- `swebench-pro-fix-loop-expanded`
- `swebench-fixloop-12-rerun`
- `swebench-pro-fixloop-v2-codex`
- `swebench-pro-4hr-rerun`
- `swebench-lite-gpt54-acm-rerun`
- `swebench-lite-sonnet46-expanded`
- `swebench-lite-sonnet46-describe-io`
- `swebench-lite-gpt54-describe-io`
- `swebench-pro-acm-prompt`
- `swebench-pro-gpt54-b2`

#### Terminal / orchestration
- `terminal-bench-rerun`
- `terminal-bench-set2-trace-investigation`
- `orchestration-benchmark-pilot`

### Support / methodology / feature-usage entries reviewed directly

- `fixloop-trace-deep-analysis`
- `context-management-trace-tooling`

### Additional indexed references surfaced but not reopened in detail here

These were noted from `benchmarking/AGENTS.md` or the notebook index but not re-read in full for this pass:
- `babilong-qa2-opus45`
- `exercism-d5-d9-benchmark`
- `exercism-benchmark-pilot`
- `scienceagentbench-pilot`
- `terminal-bench-pilot`
- `longbench-regression-v2`
- `omni-math-manual-scoring`
- `post-refactor-regression-test`

## 2. Review Tasks

1. Build a provenance map of result-bearing entries by benchmark family.
2. Normalize methods and Spell variants so the same condition names are comparable across entries.
3. Extract sample sizes, denominators, costs, and head-to-head outcomes.
4. Separate benchmark outcomes from methodology problems and infrastructure artifacts.
5. Extract explicit evidence about Spell feature usage:
   - orchestration / delegation
   - context management
   - in-language computation
   - structured `io/` usage
6. Record supersession relationships:
   - later reruns vs earlier pilots
   - rescored or corrected runs vs original auto-scored results

## 3. Benchmark and Method Inventory

### Spell method variants observed

| Variant | Typical trailing / setup | Where it appears |
|---|---|---|
| Generic Spell | `'(!extend)` | SWE-bench Pro generic runs, many baseline benchmarks |
| Fix-loop | `'(patterns/fix-loop prompt)` with `cli.agent.edn` | SWE-bench Lite / Pro |
| ACM prompt | `io-tc-acm.agent.edn` / ACM prompt text | LongBench ACM, SWE-bench Lite GPT-5.4 ACM, SWE-bench Pro ACM |
| `!describe io` init | `'(do (!describe io) (!extend))` or equivalent | SWE-bench Lite Sonnet / GPT-5.4 |
| `!describe math` | default math trailing / describe-math | Omni-MATH hard comparisons |
| Relay | `'(patterns/relay prompt)` with `cli.agent.edn` | Omni-MATH hard relay runs |
| Math agent no-web | `math-tc.agent.edn` | AIME, Omni-MATH, MathConstruct, Project Euler |

### Benchmark families and reviewed result sets

| Family | Result sets reviewed | Main methods compared | Sample sizes seen |
|---|---|---|---|
| GAIA | pilot + 36-item full | Spell/Codex, Spell/Opus, CC/Opus, Codex CLI | 10, 36 |
| Omni-MATH | 60-item four-way, 10-item Codex recheck, 60-item relay pilot context, 16-item GPT-5.4 subset | Spell/Codex, Spell/Opus, CC/Opus, Codex CLI, relay, describe-math | 10, 16, 60 |
| AIME 2026 | four-way full, Sonnet comparison | Spell/Opus, Spell/Codex, CC/Opus, Codex CLI, Spell/Sonnet, CC/Sonnet | 30 |
| MathConstruct | four-way | Spell/Opus, Spell/Codex, CC/Opus, Codex CLI | 40 |
| Project Euler | pilot, four-way, rescored outputs | Spell/Opus, Spell/Codex, CC/Opus, Codex CLI | 2, 40 |
| LongBench | four-way rerun, ACM rerun | Spell/Opus, Spell/Codex, CC/Opus, Codex CLI, Spell/ACM | 48, 91 |
| FeatureBench | pilot | Claude Code, Codex CLI, Spell | 5 |
| SWE-bench Lite | Opus vs CC, Codex four-way, fix-loop rerun, Sonnet 32, Sonnet `!describe io`, GPT-5.4 ACM, GPT-5.4 io-adoption study | Spell generic, fix-loop, ACM, `!describe io`, CC, Codex CLI | 12, 24, 32 |
| SWE-bench Pro | 20-item Codex/Opus comparisons, 3-item fix-loop pilot, 6-item fix-loop expansion, 4-item codex pilot, 4-item 4hr rerun, 4-item ACM pilot, 4-item GPT-5.4 batch 2 | Spell generic, fix-loop, ACM, CC, Codex CLI | 3, 4, 6, 20 |
| Terminal-Bench | 25-task rerun, 16-failure investigation | Spell/Opus, Spell/Codex, CC/Opus, Codex CLI | 25 |
| Orchestration benchmark | v0 open-ended + v1 forcing prompts | Opus, Sonnet | 30 runs, then 24 runs |

## 4. Numerical Results by Benchmark Family

### Math / reasoning / long-context

| Benchmark | Entry | Conditions / methods | Numerical result |
|---|---|---|---|
| GAIA pilot + full run | `gaia-benchmark` | Spell/Opus pilot on L2/L3, then 36-item full run across 4 conditions | pilot: 40% (4/10); full raw: Codex CLI 72% (26/36), Spell/Codex 64% (23/36), Spell/Opus 58% (21/36), CC/Opus 56% (20/36); adjusted for contamination: 64%, 61%, 56%, 56% |
| Omni-MATH four-way | `omni-math-rerun` | Spell/Opus, CC/Opus, Spell/Codex, Codex CLI | manual: Spell/Codex 56.7% (34/60), Codex CLI 53.3% (32/60), CC/Opus 40.0% (24/60), Spell/Opus 33.3% (20/60) |
| Omni-MATH Codex recheck | `omni-math-codex-recheck` | Spell/Codex vs Codex CLI | manual: 70% (7/10) vs 50% (5/10) |
| Omni-MATH hard relay pilot | `omni-math-hard-relay-describe` | relay vs describe-math vs Codex CLI | pilot/manual on items 0,5-9 subset: relay 5/6 manual, describe-math 4/6 manual; initial 0-9 pilot summary: relay 83% vs describe-math 67% |
| Omni-MATH hard GPT-5.4 | `omni-math-hard-gpt54` | Spell/describe-math vs Codex CLI | manual: both 75.0% (12/16); Spell cost $2.90 vs Codex CLI $10.21 |
| AIME 2026 four-way | `aime-2026-full` | Spell/Opus, Spell/Codex, CC/Opus, Codex CLI | corrected: Codex CLI 100% (30/30), Spell/Codex 96.7% (29/30), CC/Opus 96.7% (29/30), Spell/Opus 93.3% (28/30) |
| AIME 2026 Sonnet | `aime-2026-sonnet` | Spell/Sonnet vs CC/Sonnet with prior Opus comparison | CC/Sonnet 100% (30/30 corrected), Spell/Sonnet 93.3% (28/30) |
| MathConstruct | `mathconstruct-40-four-method` | Spell/Opus, CC/Opus, Spell/Codex, Codex CLI | 72.5% (29/40), 72.5% (29/40), 82.5% (33/40), 95.0% (38/40) |
| Project Euler original | `project-euler-40-fourway` | Spell/Opus, Spell/Codex, CC/Opus, Codex CLI | original raw: 25.0% (10/40), 50.0% (20/40), 37.5% (15/40), 50.0% (20/40) |
| Project Euler rescored | `project-euler-scorer-fix` | rescored existing outputs | corrected: Spell/Opus 11/39 scored, Spell/Codex 21/40, CC/Opus 16/40, Codex/Codex 22/40 |
| LongBench four-way | `longbench-four-way-rerun` | Spell/Opus4.6, CC/Opus4.6, Spell/Codex, Codex/Codex | 66.7% (32/48), 70.8% (34/48), 39.6% (19/48), 70.8% (34/48) |
| LongBench ACM rerun | `longbench-acm-rerun` | Spell/Opus4.6 + ACM vs CC/Opus4.6 | raw: 47/91 (51.6%) vs 51/91 (56.0%); with 1 gold fix: Spell 48/91 (52.7%), CC 49/91 (53.8%) |

### Coding benchmarks

| Benchmark | Entry | Conditions / methods | Numerical result |
|---|---|---|---|
| FeatureBench pilot | `featurebench-worktree-refactor` | Claude Code, Codex CLI, Spell | 80% (4/5), 40% (2/5), Spell 0% (blocked by bug) |
| SWE-bench Lite 24-item | `swebench-lite-16-random` | Spell/Opus vs CC/Opus | 12/24 evaluable; both 83.3% (10/12) |
| SWE-bench Lite 12-item four-way | `swebench-codex-comparison` | Spell/Opus, CC/Opus, Spell/Codex, Codex CLI | 83.3% (10/12), 75.0% with flake caveat, 83.3% (10/12), 91.7% (11/12) |
| SWE-bench Lite 12-item fix-loop | `swebench-fixloop-12-rerun` | Spell/fix-loop/Opus vs plain Opus vs plain Codex | fix-loop 75% (9/12), plain Opus 66.7% (8/12), plain Codex 83.3% (10/12); fix-loop +1/-1 relative to plain Opus |
| SWE-bench Lite 32-item Sonnet | `swebench-lite-sonnet46-expanded` | Spell/Sonnet vs CC/Sonnet | overall with container errors counted as wrong: 21.9% (7/32) vs 31.3% (10/32); head-to-head on 9 shared evaluable: 44% (4/9) vs 78% (7/9) |
| SWE-bench Lite 32-item Sonnet + `!describe io` | `swebench-lite-sonnet46-describe-io` | Spell/Sonnet baseline vs broken `!describe` vs working `!describe` | baseline 9/18 (50%); broken V1 13/22 (59%); working V2 15/23 (65%), and 15/22 vs 13/22 on shared V2/V1 evaluable set |
| SWE-bench Lite GPT-5.4 ACM | `swebench-lite-gpt54-acm-rerun` | Spell/ACM vs Codex CLI | 13 shared evaluable items: 53.8% (7/13) vs 61.5% (8/13) |
| SWE-bench Lite GPT-5.4 io-adoption study | `swebench-lite-gpt54-describe-io` | stock docs vs docs with concrete examples | adoption only: stock docs 2/6 adopted `io/str-replace`; concrete examples 6/6 adopted structured functions |
| SWE-bench Pro 20 Codex | `swebench-pro-20` | Spell/Codex vs Codex CLI | generation only: 15/20 patches vs 16/20 |
| SWE-bench Pro 20 Opus + combined scoreboard | `swebench-pro-opus` | adds Spell/Opus and CC/Opus | Spell/Codex 35% (7/20), Codex CLI 40% (8/20), Spell/Opus 25% (5/20), CC/Opus 35% (7/20) |
| SWE-bench Pro fix-loop pilot | `swebench-pro-fix-loop-pilot` | fix-loop/Opus on 3 items | 33.3% (1/3); openlibrary PASS, element-web PASS-by-trace/FAIL-by-eval, ansible FAIL |
| SWE-bench Pro fix-loop expanded | `swebench-pro-fix-loop-expanded` | Opus FL on 6, Codex FL on 3, CC/Opus on 6 | Opus FL 16.7% (1/6), Codex FL medium 33.3% (1/3), CC/Opus 33.3% (2/6) |
| SWE-bench Pro Codex pilot v2 | `swebench-pro-fixloop-v2-codex` | Codex CLI vs Spell/generic vs Spell/fix-loop | 75% (3/4), 50% (2/4), 50% (2/4) |
| SWE-bench Pro 4hr rerun | `swebench-pro-4hr-rerun` | CC/Opus, Spell/generic-4hr, Spell/fix-loop-4hr, Spell/fix-loop-opus | 75% (3/4), 100% (4/4), 100% (4/4), 75% (3/4) |
| SWE-bench Pro ACM prompt pilot | `swebench-pro-acm-prompt` | Codex baseline, Codex ACM, Spell/gpt-5.4 ACM v2/v3, Codex CLI/gpt-5.4, Opus ACM/baseline | Codex baseline 100% (4/4); Codex ACM 75% (3/4); Spell/gpt-5.4 ACM v3 75% (3/4); Codex CLI/gpt-5.4 75% (3/4); Spell/gpt-5.4 ACM v2 0/4; Opus ACM v2 25% (1/4); Opus baseline v2 25% (1/4) |
| SWE-bench Pro GPT-5.4 ACM batch 2 | `swebench-pro-gpt54-b2` | Spell/gpt-5.4 ACM v3 on 4 new items | 0% (0/4), combined pilot+batch2 37.5% (3/8) |

### Terminal / orchestration

| Benchmark | Entry | Conditions / methods | Numerical result |
|---|---|---|---|
| Terminal-Bench rerun | `terminal-bench-rerun` | Spell/Opus, Codex CLI, CC/Opus, Spell/Codex | raw: 64%, 56%, 48%, 40%; adjusted partial-credit: 81%, 62%, 55%, 47% |
| Terminal-Bench set-2 failure investigation | `terminal-bench-set2-trace-investigation` | reclassification of 16 failed tasks | moderate adjustment suggests Spell ~18/25 (72%) and CC ~18/25 (72%) |
| Orchestration benchmark v0 | `orchestration-benchmark-pilot` | 5 prompts x 2 models x 3 reps | 30 runs total; v0 mostly single-call inline, multi-source-synthesis triggered 3-5 llm calls for Opus |
| Orchestration benchmark v1 | `orchestration-benchmark-pilot` | 4 forcing prompts x 2 models x 3 reps | 24 runs total; all 24 used multiple API calls; average quality 2.7 vs 4.3 in v0 |

## 5. Unresolved Methodological Issues

### Scoring / gold / evaluation quality

| Issue | Evidence |
|---|---|
| Omni-MATH auto-scorer low recall | `omni-math-rerun`: manual scoring flipped 56 items; Unicode/text/set-equivalence failures |
| Project Euler scorer defects | `project-euler-40-fourway`, `project-euler-scorer-fix`: scientific notation, text answers, blank golds |
| CC markdown false negatives | `aime-2026-full`, `aime-2026-sonnet`: `**396**` style outputs mis-scored |
| LongBench gold quality noise | `longbench-acm-rerun`: 29 items where both conditions matched each other but disagreed with gold; 1 confirmed gold error |
| GAIA contamination / leakage | `gaia-benchmark`: local HF cache + web-indexed solutions contaminated some answers |
| Terminal-Bench harness bugs | `terminal-bench-rerun`, `terminal-bench-set2-trace-investigation`: outdated tests, brittle precision/format checks, residual 0-token artifacts |

### Hardware / container / infrastructure

| Issue | Evidence |
|---|---|
| ARM / x86 Docker evaluation gaps on SWE-bench Lite | `swebench-lite-16-random`: only 12/24 items evaluable; many x86 image build failures on ARM Mac |
| ARM64/QEMU instability on SWE-bench Pro | `swebench-pro-4hr-rerun`: vuls / teleport compiler crashes under QEMU |
| Element-web eval flakiness | `swebench-pro-fix-loop-pilot`, `swebench-pro-fix-loop-expanded`, `swebench-pro-4hr-rerun`: trace-correct patches sometimes fail eval due to suite-level flake / ARM emulation |
| Trace-file patch pollution | `swebench-pro-20`, `swebench-pro-gpt54-b2`: `.spl` / `.spell-trace` artifacts inflated or broke patches; later fixed |
| Host-shell `!` escaping | `swebench-pro-4hr-rerun`, `swebench-lite-sonnet46-describe-io`, `swebench-lite-gpt54-describe-io`: `!extend` / `!describe` mangled to `\!` |
| Broken auth/symlink / environment setup | `longbench-four-way-rerun`, `omni-math-hard-relay-describe`: Codex CLI auth symlink issue |

### Transport / provider / runtime issues

| Issue | Evidence |
|---|---|
| `missing-tool-call` on Anthropic toolcall | `omni-math-rerun`, `aime-2026-full`, `swebench-pro-opus`: severe on Omni-MATH, still present elsewhere |
| RST_STREAM disruptions | `swebench-pro-acm-prompt`, `omni-math-hard-gpt54`: some runs recovered only after retry patch |
| `llms/explore` wrong-arity bug | `swebench-lite-sonnet46-expanded`: 5/17 Spell traces affected |
| `io/bash` docs/example bug | `swebench-pro-acm-prompt`: invalid example appeared across multiple prompt files |
| Codex CLI web-search availability confound | several entries addendum: Codex CLI 0.111.0 may have had `web_search` enabled by default |

### Benchmark-design / task-design issues

| Issue | Evidence |
|---|---|
| LongBench run comparability | `longbench-acm-rerun`: 91-item run not directly comparable to prior 48-item run |
| Terminal-Bench unstated requirements | `terminal-bench-set2-trace-investigation`: several failures due to missing evaluation details in prompt |
| Fix-loop reflector strictness vs budget | `swebench-pro-fixloop-v2-codex`, `swebench-pro-4hr-rerun`: stricter reflector catches issues but can consume whole budget |

## 6. Spell Feature Usage

### Context management

| Pattern | Evidence |
|---|---|
| Manual context management is often rare in baseline runs | `omni-math-rerun`, `omni-math-hard-gpt54`, `swebench-lite-sonnet46-expanded`, `terminal-bench-rerun` |
| `!peek` + prune loop appears strongly with GPT-5.4 ACM on SWE-bench Pro | `swebench-pro-acm-prompt`: NodeBB v3 had 38 `!peek`, 4 rethink, 1 persist |
| `!peek` auto-prune works in LongBench ACM, but manual prune/rethink remained unused | `longbench-acm-rerun` |
| Fix-loop uses mostly structural context resets; explicit rethink usually system-injected | `fixloop-trace-deep-analysis`, `swebench-pro-fix-loop-pilot` |
| On SWE-bench Lite fix-loop, rethink/persist were used strategically | `swebench-fixloop-12-rerun` |
| Sonnet on SWE-bench used no explicit context primitives before `!describe io`, then mostly `!llm-self` resets rather than prune/rethink | `swebench-lite-sonnet46-expanded`, `swebench-lite-sonnet46-describe-io` |
| `!compact` was largely absent in reviewed benchmark traces | `fixloop-trace-deep-analysis`, `terminal-bench-rerun`, `swebench-fixloop-12-rerun`, `swebench-lite-gpt54-acm-rerun` |

### In-language computation

| Pattern | Evidence |
|---|---|
| Rare but high-value computation on Omni-MATH with Opus | `omni-math-rerun`: items 16, 18, 44 |
| Spell/Codex on Omni-MATH mostly think-only | `omni-math-rerun`, `omni-math-codex-recheck` |
| AIME Sonnet uses substantial computation helpers without context management | `aime-2026-sonnet`: `defn/fn` in 19/30 traces, `!call-now` in 10/30 |
| GPT-5.4 on Omni-MATH uses essentially zero Spell computation | `omni-math-hard-gpt54` |
| Terminal-Bench tasks mostly use shell tools, not in-language computation | `terminal-bench-rerun` |
| Math-tc agent lacking `io/sh` can block empirical checking on some math items | `omni-math-hard-gpt54` item 0 note |

### Orchestration / delegation

| Pattern | Evidence |
|---|---|
| Open-ended orchestration benchmark: inline dominates | `orchestration-benchmark-pilot` v0 |
| Forcing prompts can make models orchestrate reliably, but quality drops | `orchestration-benchmark-pilot` v1 |
| Multi-source synthesis is the clearest natural delegation case | `orchestration-benchmark-pilot` |
| Real benchmark traces seldom use `agents/spawn` directly | `fixloop-trace-deep-analysis`, `terminal-bench-rerun`, `swebench-lite-sonnet46-expanded` |
| Fix-loop provides orchestration structurally, but traces show the model itself still writes imperative sequences | `fixloop-trace-deep-analysis` |

### Structured `io/` adoption

| Pattern | Evidence |
|---|---|
| Baseline SWE-bench runs often default to `io/sh` + heredocs | `swebench-pro-20`, `swebench-lite-sonnet46-expanded`, `swebench-lite-gpt54-acm-rerun` |
| GPT-5.4 and Sonnet can adopt structured io when concrete examples are shown | `swebench-lite-gpt54-describe-io`, `swebench-lite-sonnet46-describe-io` |
| Abstract `!describe io` docs help less than concrete worked examples | `swebench-lite-gpt54-describe-io` |
| Fix-loop naturally increases structured `io/` use versus generic shell loops | `swebench-pro-4hr-rerun` |

## 7. Source Notes by Benchmark Family

### Primary benchmark result references

- `notebook/entries/2026-03-01-gaia-benchmark.md`
- `notebook/entries/2026-03-01-omni-math-rerun.md`
- `notebook/entries/2026-03-02-omni-math-codex-recheck.md`
- `notebook/entries/2026-03-02-aime-2026-full.md`
- `notebook/entries/2026-03-18-aime-2026-sonnet.md`
- `notebook/entries/2026-03-02-mathconstruct-40-four-method.md`
- `notebook/entries/2026-03-02-project-euler-40-fourway.md`
- `notebook/entries/2026-03-02-project-euler-scorer-fix.md`
- `notebook/entries/2026-03-08-longbench-four-way-rerun.md`
- `notebook/entries/2026-03-19-longbench-acm-rerun.md`
- `notebook/entries/2026-03-11-omni-math-hard-relay-describe.md`
- `notebook/entries/2026-03-23-omni-math-hard-gpt54.md`
- `notebook/entries/2026-02-26-featurebench-worktree-refactor.md`
- `notebook/entries/2026-03-02-swebench-lite-16-random.md`
- `notebook/entries/2026-03-02-swebench-codex-comparison.md`
- `notebook/entries/2026-03-03-swebench-pro-opus.md`
- `notebook/entries/2026-03-02-swebench-pro-20.md`
- `notebook/entries/2026-03-05-swebench-pro-fix-loop-pilot.md`
- `notebook/entries/2026-03-05-swebench-pro-fix-loop-expanded.md`
- `notebook/entries/2026-03-07-swebench-fixloop-12-rerun.md`
- `notebook/entries/2026-03-12-swebench-pro-fixloop-v2-codex.md`
- `notebook/entries/2026-03-12-swebench-pro-4hr-rerun.md`
- `notebook/entries/2026-03-20-swebench-lite-gpt54-acm-rerun.md`
- `notebook/entries/2026-03-22-swebench-lite-sonnet46-expanded.md`
- `notebook/entries/2026-03-23-swebench-lite-sonnet46-describe-io.md`
- `notebook/entries/2026-03-24-swebench-lite-gpt54-describe-io.md`
- `notebook/entries/2026-03-19-swebench-pro-acm-prompt.md`
- `notebook/entries/2026-03-24-swebench-pro-gpt54-b2.md`
- `notebook/entries/2026-03-08-terminal-bench-rerun.md`
- `notebook/entries/2026-03-09-terminal-bench-set2-trace-investigation.md`
- `notebook/entries/2026-01-31-orchestration-benchmark-pilot.md`

### Primary feature-usage / methodology references

- `notebook/entries/2026-03-05-fixloop-trace-deep-analysis.md`
- `notebook/entries/2026-03-06-context-management-trace-tooling.md`
- `benchmarking/AGENTS.md`
