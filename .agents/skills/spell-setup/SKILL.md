---
name: spell-setup
description: Set up Spell from a fresh checkout and run the first smoke tests or examples. Use when a user asks to install Spell, get started, verify the CLI, configure provider credentials, choose a first example, or try Spell for the first time.
---

# Spell Setup

Use this skill to make Spell usable from a fresh checkout. Prefer running commands directly and reporting the result.

## Preconditions

Spell needs Java 11+, the Clojure CLI, and either a live provider credential or the test provider.

Check:

```bash
java -version
clojure -Sdescribe
bin/spell -h
command -v spell || true
```

If Clojure is missing on macOS, recommend:

```bash
brew install clojure/tools/clojure
```

If `spell` does not already resolve to this checkout's `bin/spell`, ask the user
whether they want to add this checkout's `bin/` directory to their shell `PATH`.
Do not edit shell startup files without asking. If they say yes, prefer appending
an absolute path entry to the appropriate shell profile and then verify with
`command -v spell` in a fresh shell.

## Provider Setup

For a no-cost smoke test, use the built-in test provider:

```bash
bin/spell -t "Return a short greeting"
```

Before asking the user which live provider to configure, check the current auth
state without printing secret values:

```bash
test -n "${OPENAI_API_KEY:-}" && echo "OPENAI_API_KEY is set" || echo "OPENAI_API_KEY is not set"
test -n "${ANTHROPIC_API_KEY:-}" && echo "ANTHROPIC_API_KEY is set" || echo "ANTHROPIC_API_KEY is not set"
test -n "${FIREWORKS_API_KEY:-}" && echo "FIREWORKS_API_KEY is set" || echo "FIREWORKS_API_KEY is not set"
test -f "$HOME/.codex/auth.json" && echo "Codex auth exists" || echo "Codex auth not found"
```

Report which authorization options were found. If at least one is present, say
which corresponding provider paths are ready to try and ask whether the user
wants to configure any additional provider. If none are present, ask whether the
user wants to configure one now and list these options:

- Codex CLI: install Codex and run `codex` once so `~/.codex/auth.json` exists.
- OpenAI API: set `OPENAI_API_KEY`; the default is `openai-tc:gpt-6-astra`, and explicit older exact model IDs remain selectable.
- Anthropic API: set `ANTHROPIC_API_KEY`; use `anthropic-pf:<model>` or `anthropic-tc:<model>`.
- Fireworks API: set `FIREWORKS_API_KEY`; use `fireworks:<model>` or `fireworks-tc:<model>`.

Model names must be exact. Do not guess model IDs; check provider docs before adding or recommending new model strings.

## First Run

Start with examples that are small and easy to inspect:

```bash
bin/spell -e hello-world
bin/spell -v -e hello-world
bin/spell -e coin-flip
```

Use `-b` for a dollar budget and `-d` for maximum recursive LLM depth when running live examples:

```bash
bin/spell -b 0.25 -d 6 -e hello-world
```

Use `-T` to record a trace when the user wants to inspect execution:

```bash
bin/spell -T -v -e hello-world
```

## References

- `README.md`: human-facing overview and language introduction.
- `AGENTS.md`: source guide, setup commands, and reading order.
- `examples/README.md`: canonical public example list.
- `examples/AGENTS.md`: example-specific agent guidance.
- `config/AGENTS.md`: provider, model profile, and agent profile guide.
