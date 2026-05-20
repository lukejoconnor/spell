---
name: spell-provider-setup-and-smoke-test
description: Configure a Spell LLM provider and run local smoke tests. Use when setting API keys, choosing provider-prefixed model specs, testing dummy mode, or checking that examples can call a model.
---

# Provider Setup And Smoke Test

## No-Provider Smoke Test

Run these first; they do not make an LLM API call:

```bash
bin/spell -h
bin/spell -t "Return a short greeting"
```

The `-t` flag uses the dummy provider and verifies Java, Clojure, dependency resolution, and the Spell CLI path.

## Provider Choices

Use one of these public provider paths:

- Codex tool-call provider: install the OpenAI Codex CLI and run `codex` once so `~/.codex/auth.json` exists.
- Anthropic API: set `ANTHROPIC_API_KEY`.
- OpenAI API: set `OPENAI_API_KEY`.
- Fireworks API: set `FIREWORKS_API_KEY`.
- Ollama: run a local Ollama server and use an `ollama:<model>` model spec.

Common model spec forms:

```text
codex-tc:<model>
openai-tc:<model>
anthropic-tc:<model>
anthropic-pf:<model>
fireworks:<model>
fireworks-tc:<model>
ollama:<model>
```

## Live Smoke Tests

Start with a bundled example:

```bash
bin/spell -e hello-world
```

Then try an explicit provider-prefixed model:

```bash
bin/spell -m openai-tc:gpt-5.4 "Return the number 42."
```

For debugging, add `-v` to show raw model responses or `--log FILE` to save verbose output.

## Troubleshooting

- Run `bin/spell -h` for the authoritative option list in the current checkout.
- Check model profile files in `config/model-profiles/` and agent profile files in `config/agent-profiles/`.
- `SERPER_API_KEY` is only needed for Serper-backed web search. It is not required for local CLI or non-web examples.
