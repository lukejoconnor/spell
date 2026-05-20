---
name: spell-custom-agents
description: Configure custom Spell agent profiles. Use when creating or modifying .agent.edn files, choosing namespaces, changing system prompts, or wiring sub-agents.
---

# Custom Agents

## Where To Work

Agent profiles live in `config/agent-profiles/` and are loaded by `src/spell/agent.clj`.

Start from the closest existing profile:

- `base-pf.agent.edn`: prefill transport base.
- `base-msg.agent.edn`: message transport base.
- `base-tc.agent.edn`: tool-call transport base.
- `cli.agent.edn`: CLI default with `io`, `web`, `patterns`, `agents`, and `globals`.
- `io-*.agent.edn`: I/O-capable profiles without `web` by default.

## Minimal Pattern

Use `:base` for inheritance and add only the differences:

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

Paths in `:base`, `:system-prompt {:file ...}`, and `:default-model-profile` are resolved relative to the file that declares them.

## Namespace Guidance

- Add `io` only when file or shell access is intended.
- Add `web` only when search/fetch access is intended.
- Keep transport variants aligned unless the transport requires a difference.
- Avoid inheritance cycles.

## Validate

Run a small task with the custom agent:

```bash
bin/spell -a config/agent-profiles/my-agent.agent.edn -t "Return a short greeting"
```

Then run one live task with the intended provider:

```bash
bin/spell -a config/agent-profiles/my-agent.agent.edn "Return the number 42."
```

See `config/AGENTS.md` for the current directory map and gotchas.
