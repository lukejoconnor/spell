# Spell Examples

This directory contains the public examples for the v0.2 Spell release. Each `.spl` file is a natural-language prompt that asks the model to write and run a Spell program.

## Running Examples

```bash
# Smoke-test the CLI without a live provider call.
bin/spell -t "Return a short greeting"

# Run examples with the default provider.
bin/spell -e hello-world
bin/spell examples/coin-flip.spl

# Show model calls while running a live example.
bin/spell -v -e auction
```

Most examples make live provider calls, and recursive or multi-agent examples can make several calls. Use `-b` to cap spend and `-d` to cap recursion depth while experimenting.

Spell accepts provider-prefixed model specs such as `codex-tc:<model>`, `openai-tc:<model>`, `anthropic-tc:<model>`, `anthropic-pf:<model>`, `fireworks:<model>`, `fireworks-tc:<model>`, and `ollama:<model>`. The unprefixed CLI default uses the Codex CLI tool-call provider.

## Public Example Set

| Example | What it demonstrates | Try it |
| --- | --- | --- |
| `hello-world.spl` | Minimal self-call and string composition. | `bin/spell -e hello-world` |
| `coin-flip.spl` | Recursive self-calls with a programmatic stopping condition. | `bin/spell -e coin-flip -d 20` |
| `twenty-questions.spl` | A host/worker game loop with limited turns. | `bin/spell -e twenty-questions -d 40` |
| `telephone.spl` | Sequential relay loop using fresh self-calls. | `bin/spell -e telephone -d 30` |
| `auction.spl` | Parallel bidder agents and result collection. | `bin/spell -e auction -d 20` |
| `chat.spl` | Interactive conversation through the agent communication namespace. | `bin/spell -e chat` |

Each example has a companion `.md` file with a short explanation and expected behavior.
