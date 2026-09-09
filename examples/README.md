# Spell Examples

This directory contains the public examples for the v0.3 Spell release. Most `.spl` files are natural-language prompts that ask the model to write and run a Spell program. The installable-module section explicitly identifies complete startup programs (use `--init-file`) and inert module definitions (save/discover them; do not run as prompts).

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

## Installable modules

Current examples: [caller-configured board startup](mailing-list-startup.md) installs/subscribes before the child's first generation using an existing compiled agent; [relay](relay.md) supplies a complete runnable startup program with fresh-context structured handoffs and a separate verifier (not a correctness guarantee); [user modules](user-modules.md) shows project/HOME discovery and explicit save/fresh-run reload; [caller-owned shell work](caller-owned-work.md) composes configured tracked agents without a generic git framework or automatic cleanup.


[installable-modules.spl](installable-modules.spl) is a small natural-language task exercising the five-verb module API; its [companion](installable-modules.md) gives an explicit bounded command.
