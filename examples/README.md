# Spell Examples

Example prompts demonstrating Spell's LLM self-orchestration capabilities.

## Running Examples

```bash
# Set your API key
export ANTHROPIC_API_KEY=sk-...

# Run with Sonnet (default)
spell "Your prompt here"

# Run with Opus (for complex multi-role examples)
spell -m opus -d 40 "Your prompt here"

# Run with Haiku (faster, for simple examples)
spell -m haiku "Your prompt here"

# Verbose mode shows LLM calls
spell -v -m opus "Your prompt here"
```

## Examples

| File | Description | Recommended Model |
|------|-------------|-------------------|
| `hello-world.md` | Simple 2-step delegation | Haiku |
| `coin-flip.md` | Recursive coin flipping | Sonnet |
| `famous-greeting.md` | Tool use + delegation | Sonnet |
| `twenty-questions.md` | Worker/checker loop (Ralph pattern) | Opus |
| `explain-spell.md` | Self-reflection + multi-agent debate | Opus |
