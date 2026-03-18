# Spell Examples

Example `.spl` files demonstrating Spell's self-orchestration capabilities. Each is a natural-language prompt (or a pre-built quine) that the LLM completes as a Spell program.

## Running

```bash
spell examples/hello-world.spl        # run a .spl file
spell -e hello-world                   # run by name
spell -v -m opus examples/auction.spl  # verbose + model override
```

## Inventory

| File | What it demonstrates | Model |
|------|----------------------|-------|
| `hello-world.spl` | Minimal 2-step delegation (`!llm-self`) | Haiku |
| `coin-flip.spl` | Recursive self-calls with programmatic branching | Sonnet |
| `famous-greeting.spl` | File I/O (`io/read-file`) + delegation | Sonnet |
| `fix-bug.spl` | Multi-step: run tests, delegate analysis, apply fix, verify | Sonnet |
| `plet-basic.spl` | Parallel evaluation with `plet` | Sonnet |
| `think-rethink.spl` | CoT pruning: `think` / `rethink` / `!extend` / `!compact` | Sonnet |
| `test-compact.spl` | `=compact:N=` tag for selective context pruning | Sonnet |
| `twenty-questions.spl` | Worker/checker loop (Ralph pattern) with `defn` recursion | Opus |
| `explain-spell.spl` | Self-reflection + multi-child orchestration | Opus |
| `comm-spawn-basic.spl` | Fire-and-forget `agents/spawn` | Sonnet |
| `comm-handle.spl` | Return own agent handle | Sonnet |
| `comm-ask.spl` | `agents/spawn` + `agents/!ask` for request/reply | Sonnet |
| `negotiate.spl` | Multi-turn negotiation via `!ask` / `!reply-ask` / `!reply` | Opus |
| `auction.spl` | Sealed-bid auction: parallel spawns + `agents/send` notifications, `agents/!ask` collection | Opus |
| `globals-basic.spl` | `globals/set!` and `globals/get` for shared state | Sonnet |
| `globals-roles.spl` | Role registration in globals + `spawn-ask` | Opus |
| `chat.spl` | Interactive user conversation via comms | Sonnet |

## Companion files

- `.md` writeups (e.g. `hello-world.md`, `fix-bug.md`) contain expected output and key-concept explanations.
- `buggy/calculator.py` — intentionally broken file used by `fix-bug.spl`. Reset with `fix-bug.setup.sh`.
- `data/name.txt`, `names.txt`, `demo_data/` — data files read by examples.

## Key patterns

- **Delegation**: `(!llm-self "task")` spawns a child LLM call and returns its result.
- **Data-returning children**: child returns a map (`{:old "..." :new "..."}`), parent acts on it (see `fix-bug`).
- **Ralph loop**: orchestrator + worker + checker with recursive rounds (see `twenty-questions`).
- **Agent comms**: `agents/spawn` + `agents/!ask` / `!reply-ask` for structured multi-agent dialogue.
- **Context management**: `think`/`rethink`/`!extend`/`!compact` to prune and reshape context between self-calls.
