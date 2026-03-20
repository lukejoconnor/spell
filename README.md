# Spell

Spell is a Lisp dialect for LLM self-orchestration: instead of an external harness driving an agent loop, the LLM writes and executes its own program. The language provides primitives for self-calls, context management, concurrency, and multi-agent communication, all evaluated by a minimal Clojure runtime.

## Quick Start

### Prerequisites

- **Java 11+**
- **Clojure CLI** (`clj`): install via [Homebrew](https://brew.sh) on macOS:
  ```bash
  brew install clojure/tools/clojure
  ```
- **LLM access** (at least one):
  - **Codex (default):** Install [OpenAI Codex CLI](https://github.com/openai/codex) and run `codex` once to log in. This writes `~/.codex/auth.json`, which Spell reads automatically. Requires a ChatGPT Pro/Plus subscription.
  - **Anthropic:** Set `export ANTHROPIC_API_KEY=sk-...` in your shell.
  - **OpenAI API:** Set `export OPENAI_API_KEY=sk-...` in your shell.

### Install

```bash
git clone https://github.com/lukejoconnor/spell.git
cd spell
```

### Run

```bash
# Run a bundled example with verbose output
bin/spell -m opus -v -e hello-world

# Chat interactively via the comms system (log to file)
bin/spell -m opus --log /tmp/chat.log -e chat

# Run Spell code yourself as the model
bin/spell -m user "Hello me!"
```

More examples in `examples/` — try `negotiate`, `twenty-questions`, `auction`, etc.

## CLI Reference

```
Usage: bin/spell [options] <prompt>
       bin/spell [options] <file.spl>
       bin/spell -e <example>

Options:
  -m MODEL      Model: haiku, sonnet, opus, ollama:<m>, codex-tc:<m>, openai-tc:<m>, openai:<m>, ...
  -a FILE       Agent definition (.agent.edn)
  -e NAME       Run a bundled example
  -d DEPTH      Max recursion depth (0 = unlimited)
  -b DOLLARS    Budget cap in dollars (default: $1.00, 0 = unlimited)
  -M TOKENS     Max tokens per LLM response (default: 16384)
  -K BUDGET     Enable extended thinking (budget_tokens)
  -T            Record execution trace to traces/
  -v            Verbose output (show raw LLM responses)
  --log FILE    Log verbose output to file
  -h            Show help
```

## License

MIT
