---
name: spell-agent-config
description: Create, inspect, or modify Spell model profiles and agent profile files. Use when the user asks to configure providers, add or change `.agent.edn` files, expose namespaces, define sub-agents, adjust budgets, or use `spell.api/run`.
---

# Spell Agent Config

Use this skill for runtime configuration, not for changing evaluator semantics.

## Canonical Docs

Keep `docs/api.md` as the public API and configuration reference. It is the right home for stable docs because it is user-facing, linkable from the README, and useful outside agent environments. This skill should summarize workflow and point to it, not replace it.

Read before editing config:

```bash
sed -n '1,320p' docs/api.md
sed -n '1,180p' config/AGENTS.md
```

## Split Responsibilities

Model profiles live in `config/model-profiles/*.edn`. They describe how Spell calls a model provider: provider type, endpoint/auth, default model, reasoning effort, timeouts, retry policy, pricing, and transport options.

Agent profiles live in `config/agent-profiles/*.agent.edn`. They describe what the model sees and can do: system prompt, namespaces, sub-agent topology, recovery behavior, structured output, and default budget.

Run-level overrides belong in `spell.api/run` or CLI flags: task input, model override, reasoning effort override, budget, depth, trace directory, usage tracker, user reader, and log writer.

## Agent Profile Checklist

When creating or editing an agent profile:

1. Pick the transport base: `base-pf.agent.edn`, `base-msg.agent.edn`, or `base-tc.agent.edn`.
2. Use public field names: `:agent-name`, `:agent-description`, `:system-prompt`, `:default-model-profile`, `:default-budget`, `:available-agents`, `:namespaces`.
3. Keep relative paths valid from the file that declares them.
4. Expose only needed namespaces. Common namespaces are `stdlib/io`, `stdlib/web`, `stdlib/patterns`, `stdlib/agents`, and `stdlib/globals`.
5. Use `:available-agents` for worker discovery instead of exposing `.agent.edn` files as namespace values.

## Model Profile Checklist

When creating or editing a model profile:

1. Use an exact `:provider` supported by `spell.provider/resolve-model-profile`.
2. Use `:default-model` and `:default-reasoning-effort` for model-call defaults.
3. Use `:api-key-env`, not literal secrets, for public config.
4. Keep `:default-agent-profile` transport-compatible with the provider.
5. Update `data/pricing.edn` when adding a priced model.

Do not add backwards-compatibility aliases or legacy config shims unless explicitly requested.

## Validation

Run the smallest useful checks:

```bash
bin/spell -h
bin/spell -t "Return 42"
clj -M:test-fast
```

For API changes, add focused tests under `test/spell/api_test.clj`, `test/spell/agent_test.clj`, or provider-specific tests.
