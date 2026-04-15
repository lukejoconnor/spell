# Spell Benchmark Catalog

## Math Benchmarks

### GSM8K
- **What:** Grade-school math word problems requiring multi-step arithmetic reasoning
- **Items:** ~1,300 (typically sampled: 30–50)
- **Distinguishing feature:** Easiest math benchmark; tests whether orchestration eliminates arithmetic errors via code execution
- **Subcategories:** None

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Spell/Sonnet (50 items) | 94% (47/50) | 3 errors | — |
| Baseline one-shot (50 items) | 74% (37/50) | 13 errors | — |
| Spell/Opus 4.5 (30 items, post-refactor) | 100% (30/30) | 0 | $0.71 |

### MATH Hard (Level 5)
- **What:** Competition math (AMC/AIME difficulty), hardest tier of the MATH benchmark
- **Items:** ~500 (typically sampled: 20)
- **Distinguishing feature:** Harder than GSM8K but easier than Omni-MATH; standard competition-math difficulty
- **Subcategories:** Algebra, Number Theory, Geometry, Counting & Probability, Precalculus, Intermediate Algebra, Prealgebra

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Spell/Opus 4.5 (20 items) | 90% (18/20) | 0 | $0.92 |

### AIME 2026
- **What:** American Invitational Mathematics Examination — 30 problems from MathArena
- **Items:** 30
- **Distinguishing feature:** Real competition problems with integer answers 000–999; clean auto-scoring. Near-saturated by top models
- **Subcategories:** None

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Codex CLI | 100% (30/30) | 0 | $4.05 |
| Spell/Codex | 96.7% (29/30) | 0 | $1.28 |
| CC/Opus 4.6 | 96.7% (29/30) | 0 | $3.35 |
| Spell/Opus 4.6 | 93.3% (28/30) | 1 missing-tool-call | $3.35 |

### HMMT Feb 2025
- **What:** Harvard-MIT Math Tournament problems — harder than AIME, proof-adjacent
- **Items:** 30
- **Distinguishing feature:** Harder competition math than AIME; not yet near saturation
- **Subcategories:** None

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Spell/Codex (no grammar) | 83% (~25/30) | 3 timeouts | $1.66 |
| Spell/Opus 4.5 | 63.3% (19/30) | — | $25.62 |
| CC/Opus 4.5 | 50% (15/30) | — | $44.47 |

### Omni-MATH Hard
- **What:** Olympiad-level math problems (difficulty ≥ 6.0), sourced from international competitions
- **Items:** ~4,400 total; runs use 60-item samples
- **Distinguishing feature:** Hardest math benchmark; requires manual scoring (auto-scorer has <50% recall due to Unicode, set notation, text answers)
- **Subcategories:** Difficulty 1.0–9.5 (filtered to ≥6.0 for "Hard")

| Condition | Accuracy (manual) | Errors/Timeouts | Cost |
|-----------|-------------------|-----------------|------|
| Spell/Codex (60 items) | 56.7% (34/60) | 0 | $6.41 |
| Codex CLI (60 items) | 53.3% (32/60) | 5 timeouts | — |
| CC/Opus 4.6 (60 items) | 40.0% (24/60) | 24 timeouts | $11.41 |
| Spell/Opus 4.6 (60 items) | 33.3% (20/60) | 19 missing-tool-call | $6.18 |

### MathConstruct
- **What:** Constructive-proof math problems requiring building objects satisfying constraints
- **Items:** Varies; runs use 40-item seeded samples
- **Distinguishing feature:** Tests constructive reasoning (build an object) vs. answer-derivation in other math benchmarks
- **Subcategories:** None

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Codex CLI (40 items) | 95.0% (38/40) | 2 errors | $6.02 |
| Spell/Codex (40 items) | 82.5% (33/40) | 4 errors | $2.35 |
| Spell/Opus 4.6 (40 items) | 72.5% (29/40) | 8 errors | $4.66 |
| CC/Opus 4.6 (40 items) | 72.5% (29/40) | 9 errors | $7.08 |

### Project Euler
- **What:** Math/programming problems requiring integer answers; many need code execution
- **Items:** 913 total; runs use 40-item random samples
- **Distinguishing feature:** Requires computation (not just reasoning); tests whether agents write and execute code effectively
- **Subcategories:** None

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Codex CLI (10 items, 900s) | 80% (8/10) | 1 timeout | $11.57 |
| Spell/Codex (10 items, 900s) | 60% (6/10) | 0 | $1.64 |
| Spell/Codex (40 items, 300s) | 50% (20/40) | 17 errors | $1.55 |
| Codex CLI (40 items, 300s) | 50% (20/40) | 18 errors | $5.56 |
| CC/Opus (40 items, 300s) | 37.5% (15/40) | 22 errors | $2.03 |
| Spell/Opus (40 items, 300s) | 25% (10/40) | 25 errors | $2.62 |

---

## Coding Benchmarks

### SWE-bench Lite
- **What:** Bug-fixing in real open-source Python repos (Django, scikit-learn, etc.)
- **Items:** 300 total; 12 ARM-evaluable Django instances used
- **Distinguishing feature:** Standard software-engineering benchmark; Docker-based eval with unit tests
- **Subcategories:** By repo (Django, requests, sympy, etc.)

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Codex CLI (12 items) | 91.7% (11/12) | 0 | $10.42 |
| Spell/Codex (12 items) | 83.3% (10/12) | 0 | ~$5.08 |
| Spell/Opus (12 items) | 83.3% (10/12) | 0 | $10.88 |
| CC/Opus (12 items) | 75.0% (9/12) | flaky | $13.85 |

### SWE-bench Pro
- **What:** Harder, production-like subset of SWE-bench with more complex patches
- **Items:** ~250 total; runs use 6–20 item samples
- **Distinguishing feature:** Harder than Lite; multi-file patches, less-popular repos
- **Subcategories:** By repo

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Spell/Codex (20 items) | 75% (15/20) | — | $11.44 |
| Codex CLI (20 items) | 80% (16/20) | — | $49.30 |
| Team pattern pilot (6 items) | 16.7% (1/6) | timeouts | — |

### Exercism
- **What:** Python programming exercises with two-attempt protocol (initial + retry with error feedback)
- **Items:** ~225 total; runs use 16–30 items filtered by difficulty
- **Distinguishing feature:** Clean pytest eval; tests basic coding competence rather than complex bug-fixing
- **Subcategories:** Difficulty d1–d9

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Spell (d5–d9, 16 items) | 93.8% (15/16) | — | — |
| CC (d5–d9, 16 items) | 100% (16/16) | — | — |
| Spell (d4–d5, 30 items) | 100% (30/30) | — | — |
| CC (d4–d5, 30 items) | 100% (30/30) | — | — |

### Terminal-Bench 2.0
- **What:** Shell/terminal tasks executed in Docker containers (file manipulation, git ops, process management)
- **Items:** 25 tasks across easy/medium/hard
- **Distinguishing feature:** Tests practical terminal skills rather than code generation; Spell's strongest relative benchmark
- **Subcategories:** Easy, Medium, Hard

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Spell/Opus 4.6 (25 tasks) | 76% (19/25) | — | ~$1.20 |
| Codex CLI (25 tasks) | 56% (14/25) | — | — |
| CC/Opus (25 tasks) | 52% (13/25) | — | — |
| Spell/Codex (25 tasks) | 44% (11/25) | — | — |

### FeatureBench
- **What:** Feature implementation tasks — build new functionality from specs
- **Items:** Varies by split; 5-task pilot run
- **Distinguishing feature:** Tests feature building (vs. bug-fixing in SWE-bench); pytest scoring with partial credit
- **Subcategories:** regression, statistics, algorithms, pandas

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| CC/Opus 4.5 (5 tasks) | 80% (4/5) | 1 UTF-8 error | $4.59 |
| Spell/Opus 4.5 (5 tasks) | 40% (2/5) | 1 UTF-8 error | $21.70 |

---

## Other Benchmarks

### LongBench v2
- **What:** Long-context multiple-choice QA across 6 domains, with contexts from 52k–16M characters
- **Items:** 48 (stratified sample, 8 per domain)
- **Distinguishing feature:** Tests long-context processing; reveals `(stored ...)` truncation as Spell-specific failure mode
- **Subcategories:** Code Repo, Single-Doc QA, Multi-Doc QA, Structured Data, Long Dialogue, Short Tasks

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| CC/Opus 4.6 (48 items) | 70.8% (34/48) | 0 | $6.89 |
| Codex CLI (48 items) | 70.8% (34/48) | 0 | $24.86 |
| Spell/Opus 4.6 (48 items) | 66.7% (32/48) | 0 | $10.49 |
| Spell/Codex (48 items) | 39.6% (19/48) | 3 timeouts | $1.95 |

### BABILong QA2 (32k)
- **What:** Multi-hop QA requiring chain-tracing through 32k-token synthetic stories
- **Items:** 100
- **Distinguishing feature:** Tests retrieval in long context, not multi-hop reasoning; **abandoned** as not representative
- **Subcategories:** None

| Condition | Accuracy | Errors/Timeouts | Cost |
|-----------|----------|-----------------|------|
| Spell/Opus 4.5 (100 items) | 65% (65/100) | 6 recursion-limit | $22.35 |

### GAIA
- **What:** General AI Assistant evaluation — real-world tasks requiring web search, file reading, multi-step reasoning
- **Items:** 165 validation total; runs use 36 items (12 per level)
- **Distinguishing feature:** Only benchmark requiring web search (Serper backend); tests tool-use breadth
- **Subcategories:** Level 1 (easy), Level 2 (medium), Level 3 (hard)

| Condition | Accuracy (corrected) | Errors/Timeouts | Cost |
|-----------|----------------------|-----------------|------|
| Codex CLI (36 items) | 64% (23/36) | 0 | — |
| Spell/Codex (36 items) | 61% (22/36) | — | $4.18 |
| Spell/Opus (36 items) | 56% (20/36) | — | $10.32 |
| CC/Opus (36 items) | 56% (20/36) | — | $10.78 |

### ScienceAgentBench
- **What:** Data-driven scientific programming tasks with real datasets
- **Items:** 102 total; 5-task pilot
- **Distinguishing feature:** Domain-specific science (GIS, bioinformatics, chemistry, psychology); functional correctness eval not yet integrated
- **Subcategories:** Bioinformatics, Computational Chemistry, GIS, Psychology/Cognitive Neuroscience

| Condition | Output Produced | Errors/Timeouts | Cost |
|-----------|-----------------|-----------------|------|
| Spell/Opus (5 tasks) | 5/5 | 0 | $1.90 |
| CC/Opus (5 tasks) | 5/5 | 0 | $1.76 |

### Orchestration
- **What:** Tests whether models use Spell's orchestration primitives (spawn, llm-self, agents) vs. answering inline
- **Items:** 4–5 prompts × 2 models × 3 replicates
- **Distinguishing feature:** Meta-benchmark — measures orchestration behavior itself, not task accuracy
- **Subcategories:** v0 (optional orchestration), v1 (forced orchestration)

Results are qualitative (orchestration patterns observed, not accuracy scores). Key finding: models default to inline reasoning; forced orchestration reduces quality.
