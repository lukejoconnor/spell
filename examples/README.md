# Spell Examples

Example prompts demonstrating Spell's LLM self-orchestration capabilities.

## Running Examples

```bash
# Set your API key
export ANTHROPIC_API_KEY=sk-...

# Run with Sonnet (recommended for complex examples)
clojure -M:run -m claude-sonnet-4-20250514 "Your prompt here"

# Run with Haiku (faster, works well for simple examples)
clojure -M:run -m claude-3-5-haiku-20241022 "Your prompt here"
```

## Examples

| File | Description | Recommended Model |
|------|-------------|-------------------|
| `hello-world.md` | Simple 2-step delegation | Haiku |
| `coin-flip.md` | Recursive coin flipping | Sonnet |
| `famous-greeting.md` | Tool use + delegation | Sonnet |
