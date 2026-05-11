# Examples Directory Guide

This directory contains runnable `.spl` examples. Each example is either a natural-language prompt that the model completes as a Spell program or a pre-built Spell form that demonstrates a specific orchestration pattern.

## Running

```bash
bin/spell examples/hello-world.spl
bin/spell -e hello-world
bin/spell -v -m openai-tc:gpt-5.4 examples/auction.spl
```

Most examples make live provider calls. Use an explicit `-m` model/provider spec when you want predictable provider selection.

## Public Example Set

The public release keeps the example surface intentionally small. The primary examples are:

| File | What it demonstrates |
| --- | --- |
| `hello-world.spl` | Minimal self-call with `!llm-self`. |
| `coin-flip.spl` | Recursive self-calls with programmatic branching. |
| `twenty-questions.spl` | Worker/checker loop with recursive rounds. |
| `auction.spl` | Parallel agents, notifications, and result collection. |
| `chat.spl` | Interactive user conversation through agent communication. |

`telephone.spl` is planned for this release but has not been added yet.

## Companion Files

- `.md` writeups explain selected examples and expected behavior.
- `buggy/calculator.py` is the intentionally broken file used by `fix-bug.spl`.
- `fix-bug.setup.sh` resets the `fix-bug` example.
- `data/`, `names.txt`, and `demo_data/` contain small local files read by examples.

Some examples in this directory are development or advanced demonstrations and may leave the public surface before the v0.2.0 release. Treat `examples/README.md` as the canonical user-facing list.

## Key Patterns

- Delegation: `(!llm-self "task")` calls the model again and evaluates the returned continuation.
- Data-returning children: a child can return structured data that the parent program uses.
- Agent communication: `agents/spawn`, `agents/send`, and `agents/!ask` support multi-agent dialogue.
- Context management: `think`, `rethink`, `prune`, `persist`, and `!extend` reshape what later model calls see.
