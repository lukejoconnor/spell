# Explain Spell (Self-Reflection + Multi-Agent Debate)

Demonstrates an LLM explaining its own understanding of Spell, then proving it by running a multi-agent debate with synthesis. Tests self-awareness of language capabilities and multi-child orchestration.

## Prompt

```
I am the creator of Spell, and I'm asking you to explain your own understanding of
the language and what you can do in it. I will use this information to improve your
system prompt and the language itself. Think freely and give a comprehensive answer.
Then, show that you can execute some of the capabilities that you understand by
designing and running a nontrivial self-orchestration task in Spell.
```

## Solution (Opus)

Opus produces a multi-agent debate: three child LLMs argue from different perspectives, then a synthesizer combines their views.

```clojure
(do
  (def explanation "Spell is a Lisp dialect designed for LLM self-orchestration...")

  (def question "Should programming languages prioritize simplicity or expressiveness?")

  (def advocate-simplicity
    (llm (cat "You are an advocate for SIMPLICITY in programming languages. "
              "Argue your position in 2-3 sentences: " question)))

  (def advocate-expressiveness
    (llm (cat "You are an advocate for EXPRESSIVENESS in programming languages. "
              "Argue your position in 2-3 sentences: " question)))

  (def synthesis
    (llm (cat "You are a wise synthesizer. Two advocates have debated. "
              "Simplicity says: " advocate-simplicity " --- "
              "Expressiveness says: " advocate-expressiveness " --- "
              "Synthesize their views into a balanced 2-3 sentence conclusion.")))

  (cat "=== SPELL SELF-ORCHESTRATION DEMONSTRATION ===\n\n"
       "QUESTION: " question "\n\n"
       "--- SIMPLICITY ADVOCATE ---\n" advocate-simplicity "\n\n"
       "--- EXPRESSIVENESS ADVOCATE ---\n" advocate-expressiveness "\n\n"
       "--- SYNTHESIS ---\n" synthesis "\n\n"
       "=== END ===\n\n"
       explanation))
```

## Solution (Sonnet)

Sonnet takes a different approach: uses `expand`, hooks (`with-env`, `with-env-hints`), and chained delegation — an orchestrator analyzes a self-referential program, then a second child assesses the results.

```clojure
(do
  (def explanation "Spell is a fascinating meta-programming language...")

  (def analyzer-code '(do
    (def my-structure (uneval 'analyzer-code))
    (def analysis (llm (cat "Analyze this code structure: " (pr-str my-structure))))
    (def tool-demo (bash "echo 'System info:' && date && whoami"))
    (cat "Self-analysis: " analysis " | Tool result: " (:out tool-demo))))

  (def expanded-analyzer (expand analyzer-code))

  (def orchestrator
    (llm "You are a code orchestrator. Execute this self-analyzing program."
         [(with-env {:program expanded-analyzer})]))

  (def final-result
    (llm (cat "Based on this orchestration result: " orchestrator
              " - create a final assessment.")
         [(with-env-hints {:context [orchestrator "The orchestration result"]})]))

  (cat explanation "\n\nDEMONSTRATION:\n" final-result))
```

## Sample Run (Opus)

```
=== LLM Call (depth 0) ===
Prompt: "I am the creator of Spell..."
Response: (do (def explanation "...") (def question "Should programming languages prioritize simplicity or expressiveness?") (def advocate-simplicity (llm ...)) ...)

  === LLM Call (depth 1) ===
  Prompt: "You are an advocate for SIMPLICITY..."
  Response: "Simplicity should be the primary goal. A language that can be held
  entirely in one's head enables confident reasoning about code..."

  === LLM Call (depth 1) ===
  Prompt: "You are an advocate for EXPRESSIVENESS..."
  Response: "Programming languages should prioritize expressiveness, because
  simplicity without expressiveness merely shifts complexity from the language
  into the programmer's mind..."

  === LLM Call (depth 1) ===
  Prompt: "You are a wise synthesizer. Two advocates have debated..."
  Response: "The false dichotomy dissolves when we recognize that true simplicity
  and true expressiveness converge: a language with a small set of orthogonal,
  composable primitives is both easy to hold in one's head AND capable of
  expressing complex ideas directly..."
```

## Self-Reported Understanding

Both models identified these Spell capabilities (unprompted):

| Capability | Opus | Sonnet |
|------------|------|--------|
| Delegation via `llm` | Yes | Yes |
| Self-reference via `uneval` | Yes | Yes |
| Expansion via `expand` | Yes | Yes |
| Hooks (`with-env`, `with-env-hints`, `recurse`) | Yes | Yes |
| Tool use via `bash` / `call-now` | Yes | Yes |
| Thunk passing / `parent-code` | Yes | Yes |
| Error handling via `spell-error?` | Yes | Yes |
| Homoiconicity / code-as-data | Yes | Implied |

Both models noted limitations: no explicit loops (use recursion), limited string manipulation, limited file I/O.

## Key Concepts

- **Multi-child orchestration**: Root spawns multiple independent children, then a final child synthesizes their outputs
- **Role assignment via prompt**: Each child gets a different persona through its prompt string
- **Self-reflection**: The LLM can describe its own capabilities and then demonstrate them
- **Hooks (Sonnet path)**: `with-env` injects bindings into child environment; `with-env-hints` provides advisory context
- **Expand (Sonnet path)**: `(expand expr)` substitutes current variable values into a quoted expression, making it portable

## Notes

- Recommended model: Opus (produces the cleaner debate pattern; Sonnet uses more advanced features but the orchestration is less focused)
- Both models correctly identified the core mechanic: output completes a `(def response ...)` expression
- Opus chose a breadth-first pattern (3 parallel-ish children); Sonnet chose depth-first (chained children with hooks)
- The debate topic is meta — programming language design — which the models engage with substantively
