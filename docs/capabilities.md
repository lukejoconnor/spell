# Capabilities, configuration, and API

Spell configuration lives in two places. A **model profile** describes how Spell calls a model. An **agent profile** is the agent's **capabilities specification**: it describes the prompt, namespaces, workers, MCP servers, defaults, and other behavior available to that agent.

This page gives a user-facing overview. [Spell API and configuration](./api.md) is the exact, stable reference for public Clojure API inputs and profile fields.

## Invoking Spell

Spell can be invoked from the CLI or from Clojure. Both routes select a model profile and an agent profile.

### CLI

The CLI can run a natural-language task, a complete Spell program, or a bundled example:

```bash
bin/spell "Return 42"
bin/spell -m anthropic-tc:claude-opus-4-8 "Return 42"
bin/spell -a config/agent-profiles/io-tc.agent.edn "Inspect this checkout"
bin/spell --init '(do (+ 20 22))'
bin/spell -e hello-world
```

Run `bin/spell -h` for the options in the current checkout.

### Clojure API

Library callers use `spell.api/run` with exactly one of `:prompt` or `:init`, together with `:model-profile` and `:agent-profile`:

```clojure
(require '[spell.api :as spell])

(spell/run {:prompt "Answer with the number 42."
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/base-tc.agent.edn"})
```

`:init` is an initial complete Spell program, not a natural-language prompt. Spell evaluates it directly:

```clojure
(spell/run {:init "(do (def answer (+ 20 22)) answer)"
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/base-tc.agent.edn"})
;; => {:result 42, ...}
```

See the [Clojure entry point](./api.md#clojure-entry-point) and [run inputs](./api.md#run-inputs) for the full contract.

## Model profiles

A model profile describes how Spell calls a model: the provider, endpoint, credential environment variable, default model and reasoning effort, transport behavior, retries, timeouts, and related options. It provides a reusable model configuration for the CLI and Clojure API.

[Model profile options →](./api.md#model-profiles)

## Agent profiles

An agent profile is the agent's capabilities specification. It can provide:

- a base profile and system prompt;
- available namespaces;
- named sub-agents;
- default model profile, budget, and recovery behavior;
- structured-output settings;
- access to MCP servers.

Profiles can inherit from another profile with `:base`. The checked-in profiles under `config/agent-profiles/` provide examples for the supported transports and common namespace combinations.

[Agent profile options →](./api.md#agent-profiles)

## Discovering namespaces

Exact model-facing signatures and examples are available through `!describe`:

```clojure
'(!describe io agents)
'(!describe io :read-lines)
```

Default language help includes `strings/`, `math/`, and the documentation-only `builtins/` namespace. The generated `skills/` catalog lets the model discover Agent Skills and read their instructions on demand. Repository skills under `.agents/skills/` provide setup, configuration, and development guidance. See [Agent Skills](./api.md#agent-skills) for discovery and authoring.

Optional effect namespaces depend on the selected agent profile:

| Namespace | Use |
| --- | --- |
| `io/` | Full filesystem, shell-command, process-execution, test, and file-watching helpers |
| `io-read/` | Read-only filesystem inspection, codebase exploration, and environment lookup |
| `io-write/` | Filesystem mutation and file editing |
| `io-exec/` | Shell commands, direct process execution, shell-backed tests, and file watching |
| `web/` | Search and fetch public web content |
| `agents/` | Spawn agents, exchange messages, wait for replies, and inspect handles |
| `globals/` | Share values and perform atomic updates across agents |
| `patterns/` | Use reusable Spell orchestration patterns |
| `workers/` | Named compiled workers for agent spawn operations |
| `blocking/` | Wait for concurrent activity from inside a `future` |

The overlap among `io/`, `io-read/`, `io-write/`, and `io-exec/` is intentional. `io/` is the complete namespace; the other three expose focused subsets that profiles can use separately or together. Prefer structured helpers such as `read-lines`, `grep`, and `replace-lines` to shell equivalents when they fit the task.

Effect functions are evaluated through the quoted trailing expression in the Spell wrapper. A namespace being present in an agent profile does not change that language rule.

## Agents, workers, and patterns

The `agents/` namespace supports persistent named agents, messages, and tracked requests. `spawn` returns a handle; `send` delivers a message; `ask` returns a request ID immediately. `!ask` combines requesting and waiting. Incoming messages and completion reports become `msg-N` bindings; inspect their sender and request ID to determine what arrived. An unrelated message can awaken the caller while its requests remain pending; use `!wait` or `!sleep` to continue waiting on them. See [multi-agent communication](./multi-agent.md) for receipt, replies, and terminal commands.

The `globals/` namespace provides shared values when a common registry, queue, or result map is more convenient than messages. The `workers/` namespace exposes named compiled workers declared by the selected profile; pass them to `agents/spawn` or `agents/spawn-ask` to start their lifecycles. `patterns/` exposes five verbs (`install`, `catalog`, `source`, `update`, `call`) for opt-in [editable Spell modules](./installable-modules.md). Definitions and mutable state are separate. Module `:requires` entries do not grant capabilities: each caller must already have the required namespaces.

Use `!describe` for the functions available in a particular run.

## MCP server profiles

Agent profiles can include access to MCP servers. Each connection is configured by an MCP server profile, which describes its transport, endpoint or command, response limits, and authentication settings. The agent profile gives the server an alias and selects the MCP capabilities to expose; the alias becomes the generated Spell namespace.

Spell supports Streamable HTTP and stdio MCP connections. See [MCP server profiles](./api.md#mcp-server-profiles) for exact fields and current protocol support.
