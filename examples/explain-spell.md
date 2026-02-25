# Explain Spell (Self-Reflection + Multi-Agent Orchestration)

Demonstrates an LLM explaining its own understanding of Spell, then proving it by running a multi-agent task.

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
(def explanation "Spell is a Lisp dialect designed for LLM self-orchestration...")

(def question "Should programming languages prioritize simplicity or expressiveness?")

(def advocate-simplicity
  (!llm-self (wrap-cat "You are an advocate for SIMPLICITY in programming languages. "
                      "Argue your position in 2-3 sentences: " question)))

(def advocate-expressiveness
  (!llm-self (wrap-cat "You are an advocate for EXPRESSIVENESS in programming languages. "
                      "Argue your position in 2-3 sentences: " question)))

(def synthesis
  (!llm-self (wrap-cat "You are a wise synthesizer. Two advocates have debated. "
                      "Simplicity says: " advocate-simplicity " --- "
                      "Expressiveness says: " advocate-expressiveness " --- "
                      "Synthesize their views into a balanced 2-3 sentence conclusion.")))

(cat "=== SPELL SELF-ORCHESTRATION DEMONSTRATION ===\n\n"
     "QUESTION: " question "\n\n"
     "--- SIMPLICITY ADVOCATE ---\n" advocate-simplicity "\n\n"
     "--- EXPRESSIVENESS ADVOCATE ---\n" advocate-expressiveness "\n\n"
     "--- SYNTHESIS ---\n" synthesis "\n\n"
     "=== END ===\n\n"
     explanation)
```

## Key Concepts

- **Multi-child orchestration**: Root spawns multiple independent children, then a final child synthesizes their outputs
- **Role assignment via prompt**: Each child gets a different persona through its prompt string
- **Self-reflection**: The LLM can describe its own capabilities and then demonstrate them
- **Expansion**: `!llm-self` auto-expands free variables from the current environment, making quoted expressions portable

## Notes

- Recommended model: Opus (produces cleaner orchestration patterns)
- Both models correctly identified the core mechanic: output continues a prefix expression
- The debate topic is meta — programming language design — which the models engage with substantively
