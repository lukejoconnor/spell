# Self-programmed execution language for LMs

Self-programmed execution is an architecture for LM agents in which a LM-written program, not a fixed agent loop, is responsible for orchestration policy. Spell (self-programmed execution language for LMs) is a language designed for SPE. SPE and Spell are described in [this paper](https://arxiv.org/abs/2605.06898).  The language provides primitives for self-calls, program self-reference, context management, and concurrency, and multi-agent communication. It is based upon and embedded within Clojure.

## Quick Start

### Prerequisites

- Java 11+
- Clojure CLI (`clj`)

On macOS with Homebrew:

  ```bash
  brew install clojure/tools/clojure
  ```

You also need access to at least one LLM provider:

- Codex tool-call provider, the CLI default: install the OpenAI Codex CLI and run `codex` once so `~/.codex/auth.json` exists.
- Anthropic API: set `ANTHROPIC_API_KEY`.
- OpenAI API: set `OPENAI_API_KEY`.
- Fireworks API: set `FIREWORKS_API_KEY`.
- Ollama: run a local Ollama server and pass an `ollama:<model>` model spec.

### Install

```bash
git clone https://github.com/lukejoconnor/spell.git
cd spell
```

No build step is required for normal use. The `bin/spell` wrapper runs the Clojure CLI entry point with `clj -M:run`.

### Smoke Test

Run the CLI without making an LLM API call:

```bash
bin/spell -h
bin/spell -t "Return a short greeting"
```

The `-t` flag uses a dummy provider and is useful for checking that Java, Clojure, and dependencies are installed correctly.

### Run Examples

```bash
# Run a bundled example with the default provider
bin/spell -e hello-world

# Show raw LLM responses while running an example
bin/spell -v -e coin-flip

# Run a .spl file directly
bin/spell examples/twenty-questions.spl -d 40
```

More examples are listed in `examples/README.md`. Good first examples are:

- `examples/hello-world.spl`: minimal self-call example.
- `examples/coin-flip.spl`: recursive control flow.
- `examples/twenty-questions.spl`: multi-agent worker/checker loop.
- `examples/telephone.spl`: sequential relay loop.
- `examples/chat.spl`: simple interactive communication pattern.

### Talk To The Spell Agent

The default CLI agent is `config/agents/cli.agent.edn`. It exposes the core language plus `io`, `web`, `patterns`, `agents`, and `globals` namespaces.

```bash
# Default model is codex-tc:gpt-5.3
bin/spell "Inspect the examples directory and suggest one example to run next."

# Use an explicit provider-prefixed model spec
bin/spell -m openai-tc:gpt-5.4 "Explain this repository in three bullets."

# Let yourself provide the next suffix manually instead of using an LLM
bin/spell -m user "Hello me!"
```

Use `-b` to cap spend, `-d` to cap recursion depth, `-T` to record traces, and `--log FILE` to save verbose output.

## CLI Reference

```
Usage: spell [options] <prompt>
       spell [options] <file.spl>
       spell -a <agent.edn> <prompt>
       spell -e <example>

Options:
  -t, --test                     Use dummy LLM provider
  -e, --example NAME             Run a named example from examples/
  -a, --agent FILE               Use an agent definition from a .agent.edn file
  -m, --model MODEL              Model spec or alias
  -d, --depth DEPTH              Max recursion depth; 0 means unlimited
  -b, --budget DOLLARS           Max spend in dollars; 0 means unlimited
  -M, --max-tokens TOKENS        Max tokens per LLM response
  -K, --thinking BUDGET          Anthropic thinking budget
  -R, --reasoning-effort EFFORT  OpenAI reasoning effort
      --verbosity LEVEL          OpenAI verbosity
      --suffix-grammar           Enable prefix-aware OpenAI suffix grammar constraints
      --grammar-max-chars CHARS  Max generated grammar chars before fallback
      --responses-api            Force OpenAI Responses API
  -T, --trace                    Record an execution trace
  -l, --log FILE                 Log verbose output to FILE
  -v, --verbose                  Show raw LLM responses
  -S, --setup CMD                Shell command to run before Spell execution
  -C, --cleanup CMD              Shell command to run after Spell execution
  -h, --help                     Show help
```

Common model specs:

- Provider-prefixed specs: `codex-tc:<model>`, `openai-tc:<model>`, `anthropic-tc:<model>`, `anthropic-pf:<model>`, `fireworks:<model>`, `fireworks-tc:<model>`, `ollama:<model>`.

Run `bin/spell -h` for the authoritative CLI help from the checked-out code.

## Source Map

- `src/spell/cli.clj`: command-line interface.
- `src/spell/api.clj`: programmatic entry point.
- `src/spell/eval.clj`: evaluator and special forms.
- `src/spell/macros.clj`: macro registry and macro implementations.
- `src/spell/runtime.clj`: agent runtime, spawning, ask/reply, and inbox flow.
- `src/spell/llm.clj`: LLM call wiring and prompt construction.
- `src/spell/provider.clj`: provider implementations.
- `src/spell/stdlib.clj`, `io.clj`, `web.clj`, `globals.clj`, `patterns.clj`: standard namespaces exposed to Spell programs.
- `config/agents/`: agent capability profiles.
- `config/prompts/`: transport-specific system prompts.
- `config/providers/`: provider configuration.
- `examples/`: runnable `.spl` examples.
- `test/`: Clojure test suite.

`AGENTS.public.md` gives a more detailed orientation for AI agents or readers navigating the source tree.

## Tests

```bash
clj -M:test-fast
clj -M:test-slow
```

The fast suite covers parser, evaluator, provider, agent, and prompt-facing behavior. The slow suite covers concurrency, I/O, runtime, globals, and user-provider behavior.

## License

MIT
