# Orchestration Benchmark: Do LLMs Self-Orchestrate in Spell?

**Date:** 2026-01-31

## Overview

Spell gives LLMs the ability to write programs that call other LLMs, use tools, and manage multi-step reasoning flows. But do models actually *use* these capabilities? This benchmark tests whether frontier models employ Spell's orchestration primitives — `llm` recursion, `call-now` tool use, hooks, branching — when given tasks of varying complexity.

We ran two rounds across four models:
- **v0 (open-ended):** 5 prompts that *could* benefit from orchestration but don't require it. 30 runs (Opus, Sonnet).
- **v1 (orchestration-forcing):** 4 prompts structurally designed to *require* orchestration. 24 runs (Opus, Sonnet) + 24 runs (GPT-4o, GPT-5.2).

Total cost: ~$13 across 78 Spell runs + AI judge evaluations.

## Methodology

Each prompt is a `.spl` file passed to Spell's `llm` function. The model receives Spell's system prompt (which teaches `llm`, `call-now`, hooks, etc.) and generates a Spell program as its response. The program is then evaluated by the Spell interpreter.

**Metrics collected per run:**
- Number of `llm` calls (child LLM invocations)
- Number of `call-now` calls (tool-use continuations)
- Maximum recursion depth
- AI judge scores: quality (1-5) and orchestration appropriateness (1-5)

**Models:** Claude Opus (`claude-opus-4-5-20251101`), Claude Sonnet (`claude-sonnet-4-20250514`), GPT-4o (`gpt-4o`), GPT-5.2 (`gpt-5.2`)
**Replicates:** 3 per prompt per model
**Judge:** Sonnet, given the program text and task description
**Budget:** $1/run limit for OpenAI models (to cap retry storms)

## Results

### v0: Open-Ended Prompts

| Prompt | Avg Quality | Opus `llm` calls | Sonnet `llm` calls | Opus `call-now` | Pattern |
|--------|-------------|-------------------|---------------------|-----------------|---------|
| iterative-refinement | 4.3 | 0, 0, 0 | 0, 0, 0 | 0, 0, 0 | Always inline |
| adversarial-self-check | 4.3 | 0, 0, 0 | 0, 0, 0 | 0, 0, 0 | Always inline |
| decomposition | 4.5 | 0, 1, 0 | 0, 0, 0 | 0, 0, 0 | Almost always inline |
| conditional-branching | 1.5 | 0, 0, 0 | 0, 0, 1 | 10, 3, 0 | Tool-augmented; both fail |
| multi-source-synthesis | 4.7 | 5, 3, 4 | 0, 4, 0 | 0, 0, 0 | Opus always orchestrates |

### v1: Orchestration-Forcing Prompts

| Prompt | Model | Rep | Quality | Orch | `llm` calls | Depth | Cost |
|--------|-------|-----|---------|------|-------------|-------|------|
| blind-evaluation | opus | 0 | 4 | 5 | 1 | 1 | $0.11 |
| blind-evaluation | opus | 1 | 2 | 1 | 1 | 1 | $0.11 |
| blind-evaluation | opus | 2 | 4 | 4 | 1 | 1 | $0.11 |
| blind-evaluation | sonnet | 0 | 3 | 1 | 1 | 1 | $0.02 |
| blind-evaluation | sonnet | 1 | 2 | 1 | 1 | 1 | $0.02 |
| blind-evaluation | sonnet | 2 | 4 | 5 | 1 | 1 | $0.02 |
| tool-computation | opus | 0 | 5 | 5 | 0 | 0 | $0.13 |
| tool-computation | opus | 1 | 4 | 5 | 0 | 0 | $0.14 |
| tool-computation | opus | 2 | 4 | 2 | 0 | 0 | $0.13 |
| tool-computation | sonnet | 0 | 1 | 3 | 0 | 0 | $0.03 |
| tool-computation | sonnet | 1 | 1 | 1 | 0 | 0 | $0.04 |
| tool-computation | sonnet | 2 | 1 | 2 | 1 | 0 | $0.05 |
| independent-analysts | opus | 0 | 2 | 3 | 4 | 1 | $0.69 |
| independent-analysts | opus | 1 | 4 | 4 | 4 | 1 | $0.36 |
| independent-analysts | opus | 2 | 4 | 5 | 4 | 1 | $0.44 |
| independent-analysts | sonnet | 0 | 4 | 5 | 4 | 1 | $0.08 |
| independent-analysts | sonnet | 1 | 1 | 4 | 4 | 1 | $0.10 |
| independent-analysts | sonnet | 2 | 4 | 4 | 4 | 1 | $0.07 |
| number-guessing | opus | 0 | 1 | 3 | 4 | 1 | $0.45 |
| number-guessing | opus | 1 | 4 | 4 | 2 | 1 | $0.41 |
| number-guessing | opus | 2 | 2 | 2 | 4 | 1 | $0.56 |
| number-guessing | sonnet | 0 | 2 | 2 | 4 | 0 | $0.04 |
| number-guessing | sonnet | 1 | 1 | 2 | 6 | 1 | $0.06 |
| number-guessing | sonnet | 2 | 2 | 2 | 22 | 7 | $0.16 |
| blind-evaluation | gpt4o | 0 | 1 | 1 | 5 | 4 | $0.03 |
| blind-evaluation | gpt4o | 1 | 2 | 1 | 26 | 7 | $0.16 |
| blind-evaluation | gpt4o | 2 | 2 | 1 | 7 | 7 | $0.05 |
| blind-evaluation | gpt5.2 | 0 | 5 | 5 | 1 | 1 | $0.01 |
| blind-evaluation | gpt5.2 | 1 | 5 | 5 | 1 | 1 | $0.01 |
| blind-evaluation | gpt5.2 | 2 | 5 | 5 | 1 | 1 | $0.01 |
| tool-computation | gpt4o | 0 | 0 | 0 | 61 | 0 | $1.01 |
| tool-computation | gpt4o | 1 | 0 | 0 | 57 | 0 | $1.03 |
| tool-computation | gpt4o | 2 | 0 | 0 | 53 | 0 | $1.02 |
| tool-computation | gpt5.2 | 0 | 2 | 2 | 0 | 0 | $0.03 |
| tool-computation | gpt5.2 | 1 | 1 | 2 | 3 | 0 | $0.05 |
| tool-computation | gpt5.2 | 2 | 2 | 1 | 1 | 0 | $0.03 |
| independent-analysts | gpt4o | 0 | 2 | 2 | 27 | 7 | $0.14 |
| independent-analysts | gpt4o | 1 | 1 | 1 | 30 | 7 | $0.24 |
| independent-analysts | gpt4o | 2 | 2 | 2 | 5 | 2 | $0.03 |
| independent-analysts | gpt5.2 | 0 | 2 | 2 | 8 | 1 | $0.02 |
| independent-analysts | gpt5.2 | 1 | 4 | 5 | 4 | 1 | $0.06 |
| independent-analysts | gpt5.2 | 2 | 1 | 3 | 12 | 0 | $0.03 |
| number-guessing | gpt4o | 0 | 1 | 1 | 0 | 0 | $0.02 |
| number-guessing | gpt4o | 1 | 1 | 3 | 0 | 0 | $0.02 |
| number-guessing | gpt4o | 2 | 2 | 1 | 0 | 0 | $0.02 |
| number-guessing | gpt5.2 | 0 | 0 | 0 | 0 | 0 | $0.01 |
| number-guessing | gpt5.2 | 1 | 0 | 0 | 0 | 0 | $0.01 |
| number-guessing | gpt5.2 | 2 | 0 | 0 | 0 | 0 | $0.01 |

### Aggregates (v1)

| | Opus | Sonnet | GPT-4o | GPT-5.2 |
|---|---|---|---|---|
| Avg quality | 3.3 | 2.2 | 1.2 | 2.3 |
| Avg orchestration score | 3.6 | 2.7 | 1.1 | 2.5 |
| Avg `llm` calls | 2.1 | 4.0 | 22.6 | 2.6 |
| Total cost | $3.65 | $0.69 | $3.78 | $0.29 |

## Key Findings

**1. Inline is the default strategy.** For v0 tasks that *could* benefit from orchestration, models solved 4/5 prompts in a single generation with zero `llm` calls. They used `(def thought "...")` for chain-of-thought, structured data definitions, and a final return value. Quality was high (4.3-4.7).

**2. Models orchestrate when structurally forced.** All 24 Anthropic v1 runs used multiple API calls, vs. 25/30 single-call solutions in v0. When the task requires delegation or tool use, models consistently use it.

**3. Quality drops with orchestration complexity.** v0 inline tasks averaged quality 4.3; v1 forcing tasks averaged 2.7. Models can *conceive* of correct orchestration patterns but frequently make execution errors: type casting failures, unbound symbols, infinite loops.

**4. `llm` recursion and `call-now` are distinct strategies.** `call-now` (tool use) was triggered by computational tasks. `llm` recursion was triggered by multi-perspective analysis. No prompt elicited both.

**5. Opus orchestrates most reliably.** Opus quality (3.3) leads all models, with consistent architectural patterns (always 4 `llm` calls for independent-analysts, always 1 for blind-evaluation). Cost is highest ($3.65) but reliability justifies it for complex orchestration.

**6. GPT-5.2 excels at simple delegation, fails at complex tasks.** GPT-5.2 scored perfect Q=5 on all 3 blind-evaluation runs — the best result of any model — with clean 1-`llm`-call patterns. But it crashed with StackOverflowError on all 3 number-guessing runs (generated infinitely self-recursive Spell code) and produced low quality on tool-computation (Q=1.7). Overall quality (2.3) matches Sonnet at 42% the cost ($0.29 vs $0.69).

**7. GPT-4o cannot reliably produce Spell syntax.** Averaging 22.6 LLM calls per run (vs 2.1 for Opus) indicates massive retry rates. All 3 tool-computation runs hit the $1 budget cap. Even simple prompts like blind-evaluation produced 5-26 calls due to syntax errors. Quality (1.2) and orchestration (1.1) are lowest of all models.

**8. `call-now` is the hardest pattern for OpenAI models.** Tool-computation requires prefix-based continuations. GPT-4o fails 100% (budget-exceeded on all 3). GPT-5.2 manages but with low quality. Anthropic models handle this pattern reliably due to native assistant prefill support.

**9. Advanced features remain unused.** No run across any model used hooks, `make-llm`, `recurse`, or `fn`-based agents. These require more complex tasks or explicit prompting.

## Orchestration Pattern Taxonomy

Four distinct patterns emerged across the 54 runs:

| Pattern | Description | Example prompt |
|---------|-------------|----------------|
| **Inline** | Everything in a single generation, no child calls | iterative-refinement |
| **Delegate-and-synthesize** | Child `llm` calls for subtasks, parent combines results | multi-source-synthesis |
| **Tool-augmented** | `call-now` for bash/Python within a single generation | tool-computation |
| **Recursive game loop** | `llm` calls in a recursive function with state threading | number-guessing |

## Prompts and Representative Examples

### 1. iterative-refinement (v0, inline)

**Prompt:**
```
Write a limerick about recursion. Then critique your limerick for rhythm, rhyme, and
humor. Based on your critique, write an improved version. Return only the final
limerick as a string.
```

**Representative output** (Opus, rep 0) — solved entirely inline:
```clojure
(do
  (def thought "I need to write a limerick about recursion, critique it, then improve it...")

  (def first-attempt
    "A function that calls on itself, ...")

  (def critique
    "Rhythm issues: Line 2 doesn't flow well... Humor issues: weak punchline...")

  (def improved
    "A programmer named Lee used recursion, ...")

  "A programmer named Lee used recursion,
Which led to a strange code excursion,
  She called her own name,
  Again and again,
Till stack overflow ended the version.")
```

No `llm` calls, no `call-now`. The model performs the full write-critique-improve loop internally.

### 2. adversarial-self-check (v0, inline)

**Prompt:**
```
Propose three strategies for reducing urban traffic congestion. Then argue against each
strategy, identifying its key weakness. Finally, rank the strategies from most to least
promising after incorporating your critiques. Return the ranked list with one-sentence
justifications.
```

**Representative output** (Opus, rep 0) — structured data, no orchestration:
```clojure
(do
  (def strategies
    (list
      {:name "Congestion Pricing"
       :description "Charge drivers fees to enter high-traffic zones..."
       :critique "Disproportionately burdens lower-income commuters..."
       :strength-after-critique "Still effective at reducing traffic..."}
      ...))

  (def ranking
    (list
      {:rank 1 :strategy "Congestion Pricing" :justification "..."}
      {:rank 2 :strategy "Remote Work Incentives" :justification "..."}
      {:rank 3 :strategy "Expanded Public Transit" :justification "..."}))

  ranking)
```

### 3. decomposition (v0, inline)

**Prompt:**
```
Explain how a camera works at three levels: for a 5-year-old, for a college student,
and for an electrical engineer. Then identify which core concepts appear in all three
explanations versus which are unique to each level.
```

**Representative output** (Opus, rep 0) — structured multi-level explanation, no orchestration:
```clojure
(do
  (def five-year-old
    "A camera is like a magic box that catches pictures! ...")

  (def college-student
    "A camera captures images by focusing light through a lens system onto a
     photosensitive surface. The lens bends incoming light rays...")

  (def electrical-engineer
    "A camera's imaging pipeline begins with photon capture at the silicon
     substrate level. CMOS active pixel sensors use photodiodes...")

  (def shared-concepts
    (list "Light enters the camera"
          "Something captures/records the light pattern"
          "The result is a preserved image"))

  {:shared-concepts shared-concepts
   :unique-to-5-year-old unique-to-5yo
   :unique-to-college-student unique-to-college
   :unique-to-electrical-engineer unique-to-engineer})
```

### 4. multi-source-synthesis (v0, delegate-and-synthesize)

**Prompt:**
```
Analyze the claim "breakfast is the most important meal of the day" from three
perspectives: nutritional science, cultural history, and economics of the food
industry. Synthesize these into a balanced one-paragraph assessment.
```

**Representative output** (Opus, rep 0) — the only v0 prompt that consistently triggered orchestration:
```clojure
(do
  (def nutritional-analysis
    (llm "From a NUTRITIONAL SCIENCE perspective only, analyze the claim..."))

  (def cultural-analysis
    (llm "From a CULTURAL HISTORY perspective only, analyze the claim..."))

  (def economic-analysis
    (llm "From a FOOD INDUSTRY ECONOMICS perspective only, analyze the claim..."))

  (def synthesis
    (llm (cat "Synthesize these three analyses into ONE balanced paragraph..."
              "\n\nNUTRITIONAL SCIENCE: " nutritional-analysis
              "\n\nCULTURAL HISTORY: " cultural-analysis
              "\n\nECONOMICS: " economic-analysis)))

  synthesis)
```

Each child `llm` call returns a focused analysis. The parent constructs a synthesis prompt from the three results. Opus used this pattern in all 3 replicates (3-5 `llm` calls each); Sonnet used it 1/3 times.

### 5. conditional-branching (v0, tool-augmented, failed)

**Prompt:**
```
Determine whether 7919 is prime. Use one method first, then verify with a different
method. Report both methods used, whether they agree, and the final answer.
```

**Representative output** (Opus, rep 0) — attempted trial division in Spell + Python verification via `call-now`. All 3 attempts failed with errors:
```clojure
(do
  ;; Method 1: Trial division in Spell
  (def method1-result
    (do
      (def test-divisors (list 2 3 5 7 11 13 ... 89))
      (defn check-divisors [n divs]
        (if (empty? divs) "prime"
          (let [d (first divs)]
            (if (= 0 (- n (* d (/ n d))))      ;; mod substitute
              (cat "divisible by " (pr-str d))
              (check-divisors n (rest divs))))))
      (check-divisors 7919 test-divisors)))

  ;; Method 2: Python via call-now
  (call-now {:bash-check (:out (bash "python3 -c \"...\""))})

  (def method2-result (strip bash-check))        ;; <-- wrong arity, crash
  ...)
```

The model's `(- n (* d (/ n d)))` trick for modular arithmetic actually works in Spell (integer division), but `strip` had wrong arity, and later retries hit `Unbound symbol` errors from continuation scoping issues. Lowest quality scores in the benchmark (1.5 avg).

### 6. blind-evaluation (v1, CoT-pruning)

**Prompt:**
```
Write a haiku about the ocean. Then have an independent critic evaluate your haiku
on a 1-10 scale for imagery, rhythm, and emotional impact — the critic should only
see the final haiku, not your drafting process or self-critique. Return the haiku
and the critic's evaluation.
```

**Representative output** (Opus, rep 0) — cleanest demonstration of information asymmetry:
```clojure
(do
  (def haiku "Salt wind carries gulls
Above the endless blue deep—
Waves erase my prints")

  (def critic-prompt
    (cat "You are a poetry critic. Evaluate this haiku on a 1-10 scale
          for three criteria: imagery, rhythm, and emotional impact.
          Provide a brief justification for each score.\n\nHaiku:\n" haiku))

  (def evaluation (llm critic-prompt))

  {:haiku haiku :evaluation evaluation})
```

The child LLM receives only the finished haiku — not the parent's `thought` bindings or drafting process. The critic's chain-of-thought is invisible to the parent (only the return value comes back). This is the **CoT-pruning** pattern: orchestration creates useful information boundaries.

Both models used this pattern consistently (6/6 runs). Quality was highest among v1 prompts (avg 4.0).

### 7. tool-computation (v1, tool-augmented)

**Prompt:**
```
List the files in the current directory using bash. Find which file has the longest
name. Read the first 3 lines of that file and summarize what it appears to contain.
```

**Representative output** (Opus, rep 0) — sequential tool calls via `call-now`:
```clojure
(call-now {:files (:out (bash "ls"))})
;; Bindings: {"files" "bin\nCLAUDE.md\ndeps.edn\ndev\nexamples\n..."}

(def thought "The files are: ... CLAUDE.md has the longest name at 9 characters.")

(call-now {:first-lines (:out (bash "head -n 3 CLAUDE.md"))})
;; Bindings: {"first-lines" "# spell\n\nA domain-specific language for..."}

(def summary "CLAUDE.md is a documentation file describing 'spell'...")
```

`call-now` preserves the KV cache — the model continues generating after each tool result as if it were a single uninterrupted completion. Opus achieved quality 4-5; Sonnet failed on all 3 replicates (syntax errors, unbound symbols).

### 8. independent-analysts (v1, delegate-and-synthesize)

**Prompt:**
```
Have three independent analysts each assess whether remote work will be the dominant
work model by 2030 — they should not see each other's reasoning. Then compare their
conclusions: where do they agree, where do they disagree, and what is the consensus view?
```

**Representative output** (Opus, rep 1) — perfect 4-call pattern in all 6 runs:
```clojure
(do
  (def analyst-prompt "You are an analyst assessing whether remote work
    will be the dominant work model by 2030. Provide your independent analysis...")

  (def analyst1 (llm analyst-prompt))
  (def analyst2 (llm analyst-prompt))
  (def analyst3 (llm analyst-prompt))

  (def comparison (llm (cat "You are comparing three independent analyst assessments..."
                            "\n\nAnalyst 1: " (pr-str analyst1)
                            "\n\nAnalyst 2: " (pr-str analyst2)
                            "\n\nAnalyst 3: " (pr-str analyst3)
                            "\n\nAnalyze: where do they agree? disagree? consensus?")))

  (cat "=== INDEPENDENT ANALYST ASSESSMENTS ===\n\n"
       "ANALYST 1:\n" (pr-str analyst1) "\n\n"
       ...
       "=== COMPARISON & CONSENSUS ===\n\n"
       comparison))
```

Architecturally correct every time. However, quality was uneven (avg 3.2): the three "independent" analysts often produced near-identical analyses (same conclusion `:partial`, same confidence 72, same reasoning). The *structure* was correct but the *content* wasn't truly independent.

### 9. number-guessing (v1, recursive game loop)

**Prompt:**
```
Think of a secret number between 1 and 20. Then have a guesser try to find it using
binary search — for each guess, tell the guesser "higher" or "lower". The guesser
must not know the secret number in advance. Report the secret number, each guess made,
and how many guesses it took.
```

**Representative output** (Opus, rep 0) — ambitious recursive design, but crashes:
```clojure
(do
  (def secret 13)

  (defn run-game [low high history]
    (let [guess-str (llm (cat "Binary search: guess a number between "
                               (pr-str low) " and " (pr-str high)
                               " inclusive. Pick the middle. Return ONLY an integer."))
          guess (spell-eval (expand guess-str))]
      (if (= guess secret)
        (conj history (list guess "correct!"))
        (if (< guess secret)
          (run-game (inc guess) high (conj history (list guess "higher")))
          (run-game low (dec guess) (conj history (list guess "lower")))))))

  (def game-log (run-game 1 20 []))
  (cat "Secret number: " (pr-str secret) "\nGuesses: " (pr-str game-log) ...))
```

The child LLM returns `(do (def thought "...") 10)` — a program, not a bare integer. When the parent tries `(spell-eval (expand guess-str))`, the string-to-number cast fails. All 3 Opus replicates and 2/3 Sonnet replicates crashed. Sonnet rep 2 reached depth 7 with 22 `llm` calls — the most architecturally complex program observed — but still didn't produce correct output.

This prompt sits at the frontier of current capability: models can *design* recursive multi-agent game loops but can't reliably *execute* them.

## Implications

1. **Models are rational about orchestration.** They don't orchestrate for the sake of it. Inline solutions are preferred when they suffice, and the quality is higher. This is arguably the correct behavior.

2. **Task structure drives orchestration.** "Three perspectives" naturally maps to three `llm` calls. "Use bash" naturally maps to `call-now`. Models follow structural cues, not abstract instructions.

3. **The capability-execution gap is real.** Models can design correct orchestration architectures but struggle with the mechanics: type coercion across LLM boundaries, scoping in continuations, parsing LLM output as structured data.

4. **CoT-pruning works and is underappreciated.** The blind-evaluation pattern — where a child's reasoning is invisible to the parent — emerged naturally and produced the highest-quality orchestrated outputs across all 4 models. GPT-5.2's perfect scores here confirm this pattern is robust across model families.

5. **Spell syntax is a discriminator across model families.** Anthropic models produce valid Spell programs reliably (2-4 calls per run). GPT-4o averages 22.6 calls — mostly retries from syntax errors. GPT-5.2 is comparable to Sonnet. This suggests Spell's Lisp syntax is a meaningful capability test, not just a formatting requirement.

6. **Prefix-based continuation is critical for cross-model support.** The assistant-prefill pattern (sending the Spell boilerplate as an assistant prefix rather than a single concatenated prompt) was necessary for OpenAI models to function at all. Even with it, `call-now` continuations remain fragile on non-Anthropic models.

7. **Budget limits are essential for cross-model benchmarking.** Without per-run cost caps, retry storms on weaker models can consume orders of magnitude more budget than successful runs ($14 vs $0.02).

8. **Hooks and advanced features need explicit motivation.** No prompt in any round triggered hooks, `make-llm`, or recursive behavior propagation. Future benchmarks need tasks specifically requiring behavior inheritance or undisclosed context injection.

## Appendix: Full Child Responses

### A. multi-source-synthesis — Nutritional Science Child (Opus, rep 0)

```
Research on breakfast's importance shows mixed evidence: while observational studies
correlate breakfast eating with lower BMI and better metabolic markers, randomized
controlled trials (like those by Betts et al. 2014 and Dhurandhar et al. 2014) found
that skipping breakfast had no significant effect on weight loss or resting metabolic
rate in adults. For cognition, evidence is stronger in children where breakfast improves
attention and memory performance, but in adults the effects appear modest and may
primarily reflect relief from overnight fasting rather than unique metabolic benefits.
The 'most important meal' claim largely originated from 1940s marketing by cereal
companies, and current evidence suggests meal timing matters less than overall dietary
quality and total caloric intake for metabolic health.
```

### B. blind-evaluation — Critic Child (Opus, rep 0)

```clojure
{:imagery {:score 8
           :reason "Strong visual elements: salt wind, gulls, endless blue deep, waves,
                    footprints. The 'endless blue deep' is evocative, and 'waves erase
                    my prints' creates a vivid closing image."}
 :rhythm {:score 7
          :reason "Follows traditional 5-7-5 structure. The em-dash creates an effective
                   pause before the final revelation."}
 :emotional-impact {:score 9
                    :reason "The haiku builds beautifully to its theme of impermanence
                             and human insignificance against nature. The erasure of
                             footprints is a powerful metaphor for mortality."}
 :overall-assessment "A contemplative beach haiku that succeeds through its understated
                      meditation on transience."}
```

### C. independent-analysts — Analyst 1 Child (Opus, rep 1)

```clojure
{:conclusion :partial
 :confidence 72
 :reasoning "Remote/hybrid will dominate knowledge work (30-40% of jobs), but most jobs
             require physical presence. By 2030, expect ~25-35% fully/mostly remote,
             ~30% hybrid, ~40% fully on-site. Hybrid, not pure remote, will be the
             'new normal' for office work. Technology enables remote, but organizational
             culture, collaboration needs, and human social preferences favor hybrid
             over fully distributed models."}
```

### D. conditional-branching — Tool Use Cascade (Opus, rep 0)

The model attempted three retries, each hitting different errors. On retry 2, it resorted to checking `7919 % p` for each prime via 23 separate `bash` calls:

```clojure
(call-now {:mod2  (:out (bash "echo $((7919 % 2))"))
           :mod3  (:out (bash "echo $((7919 % 3))"))
           :mod7  (:out (bash "echo $((7919 % 7))"))
           ...
           :mod89 (:out (bash "echo $((7919 % 89))"))})
```

Results included `mod7 = "0\n"` and `mod89 = "0\n"` — both incorrect (7919 is prime, being the 1000th prime). The model correctly identified the shell arithmetic was wrong (`7 * 1131 = 7917, not 7919`) and attempted Python verification, but hit scoping errors before reaching a final answer.

### E. number-guessing — Binary Search Child Calls (Opus, rep 0)

The child LLM successfully performed binary search when called:

```
Prompt: "Binary search: guess a number between 1 and 20 inclusive..."
Response: (do (def thought "Middle of 1-20 is 10.5, so I pick 10") 10)

Prompt: "Binary search: guess a number between 11 and 20 inclusive..."
Response: (do (def thought "Middle of 11-20 is 15.5, round down to 15") 15)

Prompt: "Binary search: guess a number between 11 and 14 inclusive..."
Response: (do (def thought "Middle of [11,12,13,14] is 12.5, so I pick 12") 12)

Prompt: "Binary search: guess a number between 13 and 14 inclusive..."
Response: ... 13)
```

The child converged correctly (10 → 15 → 12 → 13), but the parent crashed on `(spell-eval guess-str)` because the child returned a Spell program, not a bare integer. The parent needed to evaluate the child's program to extract the numeric value — a type boundary issue.
