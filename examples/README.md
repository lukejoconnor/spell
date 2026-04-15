# Spell Examples

Example prompts demonstrating Spell's LLM self-orchestration capabilities.

## Running Examples

```bash
# Set your API key
export ANTHROPIC_API_KEY=sk-...

# Run with default model (GPT-5.2)
spell "Your prompt here"

# Run with a specific model
spell -m opus "Your prompt here"
spell -m haiku "Your prompt here"
spell -m ollama:llama3.2 "Your prompt here"

# Run a .spl file directly
spell examples/hello-world.spl

# Run a named example
spell -e hello-world

# Verbose mode shows LLM calls
spell -v -m opus "Your prompt here"
```

## Examples

| File | Description | Recommended Model |
|------|-------------|-------------------|
| `hello-world.spl` | Simple 2-step delegation | Haiku |
| `coin-flip.spl` | Recursive coin flipping | Sonnet |
| `famous-greeting.spl` | Tool use + delegation | Sonnet |
| `fix-bug.spl` | Multi-step bug fixing with delegation | Sonnet |
| `twenty-questions.spl` | Worker/checker loop (Ralph pattern) | Opus |
| `explain-spell.spl` | Self-reflection + multi-agent orchestration | Opus |
| `plet-basic.spl` | Parallel evaluation with plet | Sonnet |
| `think-rethink.spl` | CoT pruning with think/rethink/extend | Sonnet |
| `comm-handle.spl` | Return your own agent handle | Sonnet |
| `comm-ask.spl` | Spawn child + ask for result | Sonnet |
| `comm-spawn-basic.spl` | Fire-and-forget spawn | Sonnet |
| `negotiate.spl` | Multi-turn negotiation with ask/reply | Opus |
| `auction.spl` | Sealed-bid auction with parallel bidders | Opus |
| `globals-basic.spl` | Store/read global shared state | Sonnet |
| `globals-roles.spl` | Role registration + spawn-ask | Opus |

Some examples have companion `.md` files with detailed writeups and expected output.
