# Spell Examples

This directory contains the public examples for the v0.3 Spell release. Each `.spl` file is a natural-language prompt that asks the model to write and run a Spell program.

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

Spell accepts provider-prefixed model specs such as `codex-tc:<model>`, `openai-tc:<model>`, `anthropic-tc:<model>`, `anthropic-pf:<model>`, `fireworks:<model>`, `fireworks-tc:<model>`, and `ollama:<model>`. The CLI default is `openai-tc:gpt-6-astra` with medium reasoning. Explicit older model specs, including `openai-tc:gpt-5.6-sol`, remain available.

## Public Example Set

| Example | What it demonstrates | Try it |
| --- | --- | --- |
| [`hello-world.spl`](hello-world.md) | Minimal self-call and string composition. | `bin/spell -e hello-world` |
| [`coin-flip.spl`](coin-flip.md) | Recursive self-calls with a programmatic stopping condition. | `bin/spell -e coin-flip -d 20` |
| [`twenty-questions.spl`](twenty-questions.md) | A host/worker game loop with limited turns. | `bin/spell -e twenty-questions -d 40` |
| [`telephone.spl`](telephone.md) | Sequential relay loop using fresh self-calls. | `bin/spell -e telephone -d 30` |
| [`auction.spl`](auction.md) | Parallel bidder agents and result collection. | `bin/spell -e auction -d 20` |
| [`chat.spl`](chat.md) | Interactive conversation through the agent communication namespace. | `bin/spell -e chat` |
| [`mcp-everything.spl`](mcp-everything.md) | Real-model discovery and use of every supported MCP capability. | `bin/spell -e mcp-everything -a examples/mcp-everything.agent.edn -m openai-tc:gpt-5.6-sol -R medium` |

Each example has a companion `.md` file with a short explanation and expected behavior.
