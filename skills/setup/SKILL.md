---
name: spell-setup
description: Set up Spell from a fresh checkout and verify prerequisites, CLI smoke tests, and available model provider authorization. Use when helping a user get to a runnable checkout, configure API provider access, or try the first Spell example.
---

# Spell Setup

Spell runs from the checkout; there is no package installation step for normal use. The goal is a checkout that can run `bin/spell -h`, pass a no-provider smoke test, and identify which live model providers are ready.

## Local Prerequisites

Check Java, the Clojure CLI, and the Spell wrapper:

```bash
java -version
clj -Sdescribe
bin/spell -h
```

Spell expects Java 11+ and the Clojure CLI. On macOS, if `clj` is missing, recommend:

```bash
brew install clojure/tools/clojure
```

No build step is required for normal CLI use. `bin/spell` runs the Clojure CLI entry point with `clj -M:run`.

## No-Provider Smoke Test

Run:

```bash
bin/spell -t "Return a short greeting"
```

The `-t` flag uses the test provider and verifies Java, Clojure, dependency resolution, and the Spell CLI path without making an LLM API call.

## Provider Authorization Check

Before asking the user to configure a provider, check whether any supported live authorization is already present. Do not print secret values.

```bash
test -n "${OPENAI_API_KEY:-}" && echo "OPENAI_API_KEY is set" || echo "OPENAI_API_KEY is not set"
test -n "${ANTHROPIC_API_KEY:-}" && echo "ANTHROPIC_API_KEY is set" || echo "ANTHROPIC_API_KEY is not set"
test -n "${FIREWORKS_API_KEY:-}" && echo "FIREWORKS_API_KEY is set" || echo "FIREWORKS_API_KEY is not set"
test -f "$HOME/.codex/auth.json" && echo "Codex auth exists" || echo "Codex auth not found"
```

If one or more are configured, tell the user they are ready to use those provider paths:

- Codex auth (CLI default): `codex-tc:<model>`
- `OPENAI_API_KEY`: `openai-tc:<model>`
- `ANTHROPIC_API_KEY`: `anthropic-tc:<model>`
- `FIREWORKS_API_KEY`: `fireworks-tc:<model>`

If none are configured, ask whether the user wants to set one up now. Give concise setup instructions:

- OpenAI: export `OPENAI_API_KEY` in the shell or shell profile.
- Anthropic: export `ANTHROPIC_API_KEY` in the shell or shell profile.
- Fireworks: export `FIREWORKS_API_KEY` in the shell or shell profile.
- Codex: install the OpenAI Codex CLI and run `codex` once so `~/.codex/auth.json` exists.

Do not edit shell startup files unless the user explicitly asks. Model names must be exact; check provider docs before adding or recommending new model strings.

## First Live Run

After authorization is available, try a small live task with the matching provider:

```bash
bin/spell -m openai-tc:gpt-5.4 -e hello-world
```
