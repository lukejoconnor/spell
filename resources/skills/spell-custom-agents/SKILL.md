---
name: spell-custom-agents
description: Configure custom Spell agent and model profiles. Use when creating or modifying .agent.edn files, choosing namespaces, changing system prompts, wiring sub-agents, selecting provider-prefixed models, or doing agent smoke tests.
---

# Custom Agents

Use this skill for runtime configuration, not evaluator changes. Keep `docs/api.md` as the stable public reference and `config/AGENTS.md` as the local directory guide.

## Mental Model

Spell separates model-call policy from the runtime profile exposed to the model:

- Model profiles live in `config/model-profiles/*.edn`. They say how to call a provider: `:provider`, credentials, `:default-model`, reasoning effort, token/time limits, retries, pricing overrides, and transport flags like OpenAI `:force-tool-call`.
- Agent profiles live in `config/agent-profiles/*.agent.edn`. They say what the model sees and can do: system prompt, exposed namespaces, worker/sub-agent topology, default model profile, budget, output format, and recovery/evaluation behavior.
- Run-level CLI/API options are task overrides: prompt/init, chosen agent profile, chosen model profile, model override, reasoning-effort override, budget, depth, traces, and logs.

If a setting changes the HTTP/provider request, put it in a model profile. If it changes prompt-visible capabilities or Spell runtime behavior, put it in an agent profile.

## Agent Profile Workflow

Start from a nearby existing profile in `config/agent-profiles/`:

- `base-pf.agent.edn`, `base-msg.agent.edn`, `base-tc.agent.edn`: transport bases with the right system prompt and no effect namespaces.
- `cli.agent.edn`: interactive default; exposes `io`, `web`, `patterns`, `agents`, and `globals`, plus `explore` as a worker.
- `io-pf.agent.edn`, `io-msg.agent.edn`, `io-tc.agent.edn`: I/O-capable profiles without `web` by default.

Use `:base` inheritance and add only the differences:

```clojure
{:base cli.agent.edn
 :agent-name my-agent
 :agent-description "Short purpose of this profile."
 :default-model-profile "../model-profiles/openai-tc.edn"
 :namespaces
 {io stdlib/io
  patterns stdlib/patterns
  agents stdlib/agents
  globals stdlib/globals}}
```

Paths in `:base`, `:system-prompt {:file ...}`, and `:default-model-profile` are resolved relative to the file that declares them. Child scalar values override parent scalar values; `:namespaces` merge by key.

Expose only the capabilities the task needs. Use `io` for file/shell work, `web` for search/fetch, `patterns` for reusable Spell programs, `agents` for agent communication, and `globals` for shared state. Use `:available-agents` to expose workers through the `workers/` namespace; do not expose `.agent.edn` files as namespace values for new public config.

Start configured workers with `(agents/spawn workers/explore prompt)` or `(agents/spawn-ask workers/explore prompt)`. Worker functions are lifecycle arguments to spawn operations; direct worker invocation from an active agent or its computation future is rejected. Use `!llm-self` for serial self-calls.

## Model Profiles And Providers

Start from `config/model-profiles/*.edn` and keep provider details there. Common public provider paths are:

```text
codex-tc:<model>
openai-tc:<model>
anthropic-tc:<model>
anthropic-pf:<model>
fireworks:<model>
fireworks-tc:<model>
ollama:<model>
```

Profile files use provider keys such as `:provider`, `:api-key-env`, `:default-model`, `:default-reasoning-effort`, `:max-tokens`, `:request-timeout-sec`, and `:default-agent-profile`. Keep model names exact and update `data/pricing.edn` when adding priced models.

Credential expectations are:

- Codex tool-call provider: install the OpenAI Codex CLI and run `codex` once so `~/.codex/auth.json` exists.
- OpenAI API: set `OPENAI_API_KEY`.
- Anthropic API: set `ANTHROPIC_API_KEY`.
- Fireworks API: set `FIREWORKS_API_KEY`.
- Ollama: run a local Ollama server and use an `ollama:<model>` model spec.

Model names must be exact. Do not guess model IDs; check provider docs before adding or recommending new model strings.

## Transport Matching

Keep model transport and agent base aligned:

- Prefill providers should use `base-pf.agent.edn` or a profile inheriting from it.
- Message providers should use `base-msg.agent.edn` or a profile inheriting from it.
- Tool-call providers should use `base-tc.agent.edn` or a profile inheriting from it.

OpenAI tool-call configs still use `:provider :openai` plus `:force-tool-call true`; do not invent a separate OpenAI provider type.

## Validate

Run the smallest useful checks:

```bash
bin/spell -h
bin/spell -t "Return a short greeting"
bin/spell -a config/agent-profiles/my-agent.agent.edn -t "Return a short greeting"
```

Then run one live task with the intended provider:

```bash
bin/spell -a config/agent-profiles/my-agent.agent.edn -m openai-tc:gpt-5.4 "Return the number 42."
```

For Clojure API validation, use explicit profiles:

```clojure
(spell/run {:prompt "Return 42."
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/my-agent.agent.edn"})
```

For debugging, add `-v`, `--log FILE`, or `-T`. Run `bin/spell -h` for the authoritative option list in the current checkout.

Run `clojure -M:test-fast` after changing loader behavior, profile semantics, or public examples.
