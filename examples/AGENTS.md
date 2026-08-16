# Examples Directory Guide

This directory contains the public runnable `.spl` examples for the v0.3 release. Each example is a natural-language prompt that the model completes as a Spell program.

## Running

```bash
bin/spell examples/hello-world.spl
bin/spell -e hello-world
bin/spell -v examples/auction.spl
```

Most examples make live provider calls. Use `-b` and `-d` when you want bounded cost and recursion, and use an explicit `-m` provider-prefixed model spec when you want predictable provider selection.

## Public Example Set

The public release keeps the example surface intentionally small. The primary examples are:

| File | What it demonstrates |
| --- | --- |
| `hello-world.spl` | Minimal self-call with `!llm-self`. |
| `coin-flip.spl` | Recursive self-calls with programmatic branching. |
| `twenty-questions.spl` | Worker/checker loop with recursive rounds. |
| `telephone.spl` | Sequential relay loop with fresh self-calls. |
| `auction.spl` | Parallel agents, notifications, and result collection. |
| `chat.spl` | Interactive user conversation through agent communication. |
| `mcp-everything.spl` | Real-model exercise of tools, resources, prompts, completion, and subscriptions from an MCP server. |

## Companion Files

- Each public example has a matching `.md` writeup with the prompt, run command, expected behavior, and core concepts.
- Treat `examples/README.md` as the canonical user-facing list.

## Key Patterns

- Delegation: `(!llm-self "task")` calls the model again and evaluates the returned continuation.
- Data-returning children: a child can return structured data that the parent program uses.
- Agent communication: `agents/spawn`, `agents/send`, and `agents/!ask` support multi-agent dialogue.
- Sequential binding: a parent can bind each child result and feed it into the next call.
