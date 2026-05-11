# Spell Configuration Proposal

Spell has two public configuration surfaces:

- Provider configs describe how to call an LLM service.
- Agent configs describe the Spell runtime profile exposed to the model.

Run-scoped controls such as `:prompt`, `:init`, `:budget`, `:depth`, `:trace`, and `:user?` belong in `spell.api/run`, not in these config files.

## Provider Configs

Provider files live under `config/providers/` and use EDN maps.

```clojure
{:type :openai
 :api-key-env "OPENAI_API_KEY"
 :model "gpt-5.4"
 :use-responses-api true
 :force-tool-call true
 :max-tokens 32768
 :default-agent "../agents/base-tc.agent.edn"}
```

### Common Provider Options

| Option | Description |
|---|---|
| `:type` | Provider implementation. Supported values are `:openai`, `:anthropic-pf`, `:anthropic-tc`, `:codex-tc`, `:fireworks`, `:fireworks-tc`, `:ollama`, and `:test`. |
| `:api-key-env` | Environment variable that contains the API key. Public config files should prefer this over literal secrets. |
| `:base-url` | API base URL. Used for hosted providers, local OpenAI-compatible endpoints, Ollama, and Codex subscription endpoints. |
| `:model` | Provider default model. Agent configs may override this per agent when needed. |
| `:max-tokens` | Maximum response tokens requested from the provider. |
| `:costs` | Pricing overrides merged into `data/pricing.edn`. Values are used for usage and budget accounting. |
| `:cache-read-ratio` | Cost-table helper used when deriving cache-read prices from base input prices. |
| `:default-agent` | Agent profile that should be used when a caller selects this provider but does not explicitly select an agent. The path is relative to the provider file. |
| `:request-timeout-sec` | Per-request timeout in seconds. |
| `:sse-idle-timeout-sec` | Streaming timeout in seconds with no received bytes. |
| `:sse-completion-timeout-sec` | Total streaming response timeout in seconds. |

### Provider-Specific Options

| Option | Applies to | Description |
|---|---|---|
| `:use-responses-api` | `:openai` | Force OpenAI Responses API instead of Chat Completions. |
| `:force-tool-call` | `:openai` | Require a `spell_suffix` custom tool call. This is the OpenAI tool-call transport used by `openai-tc` style profiles. |
| `:prompt-cache-key` | `:openai`, `:codex-tc` | Stable prompt cache key reused across model calls when the compiled agent supplies a cache prefix. |
| `:auth-file` | `:codex-tc` | Path to Codex/ChatGPT auth JSON. Defaults to `~/.codex/auth.json`. |
| `:account-id` | `:codex-tc` | ChatGPT account id header override. Usually read from `:auth-file`. |
| `:chat-template` | `:fireworks` | Fireworks completions chat template keyword or explicit template map. |
| `:convert-think?` | `:fireworks` | Convert leading `<think>...</think>` output into Spell `(think ...)` forms. |
| `:responses` | `:test` | Declarative response sequence for tests. |
| `:response-rules` | `:test` | Prompt-matching response rules for tests. |
| `:response` | `:test` | Single fixed test response. |
| `:prefill?` | `:test` | Whether the test provider reports prefill support. |

Provider constructor functions may accept direct `:api-key` values for programmatic use. Public provider config files should use `:api-key-env` instead, so secrets do not land in the repository.

### Provider Boundary

Provider configs own connection and transport details:

- authentication source
- API URL
- model default
- maximum output tokens
- transport mode
- timeout policy
- pricing overrides
- provider default agent

Provider configs should not contain run input, tracing, user interaction settings, or per-run budget overrides.

## Agent Configs

Agent files live under `config/agents/` and use EDN maps.

```clojure
{:base base-tc.agent.edn
 :name cli
 :doc "CLI default: full capabilities for interactive use"
 :llms {explore explore.agent.edn}
 :namespaces
 {io       stdlib/io
  web      stdlib/web
  patterns stdlib/patterns
  agents   stdlib/agents
  globals  stdlib/globals}}
```

### Agent Options

| Option | Description |
|---|---|
| `:base` | Parent `.agent.edn` file. Child scalar options override parent scalar options; `:namespaces` merge by key. |
| `:name` | Human-readable agent name, usually a symbol. |
| `:doc` | Short description used by generated docs and `llms/` descriptions. |
| `:system` | System prompt, either an inline string or `{:file "relative/path.txt"}`. |
| `:model` | Agent-specific model override. If absent, the provider default model is used. |
| `:provider` | Provider spec for this agent. Accepts a provider file path, inline provider config map, or already constructed provider instance. |
| `:budget` | Default dollar budget for runs using this agent when the caller does not supply `:budget`. |
| `:recover` | Recovery behavior used when evaluating model output fails. |
| `:format` | Structured output contract for this agent. When present, the compiled agent wraps output validation and repair. |
| `:max-retries` | Maximum format-repair attempts when `:format` is configured. |
| `:retries` | Provider retry schedule for transient API failures. Values are sleep durations in seconds. |
| `:prefill?` | Override whether the compiled agent uses assistant-prefill prompting with the selected provider. |
| `:thinking` | Provider-facing thinking configuration, mainly for Anthropic and compatible transports. |
| `:reasoning-effort` | Provider-facing reasoning effort. Used by OpenAI, Codex, Anthropic, and Fireworks transports when supported. |
| `:verbosity` | Provider-facing verbosity control for models that support it. |
| `:suffix-grammar?` | Enable prefix-aware suffix grammar constraints for transports that support grammar-constrained tool output. |
| `:grammar-max-chars` | Maximum generated grammar size before falling back to unconstrained output. |
| `:llms` | Sub-agent namespace exposed as `llms/`. A map names sub-agents; `[]` disables sibling auto-discovery. |
| `:namespaces` | Spell namespaces exposed to the agent. Values can reference stdlib namespaces, Clojure vars, nested agent files, file contents, or merged namespace vectors. |

### Namespace Values

The `:namespaces` map accepts several reference forms:

| Form | Meaning |
|---|---|
| `stdlib/io` | Built-in stdlib namespace. |
| `stdlib/reminders/coding` | Nested item from a stdlib namespace. |
| `[stdlib/io stdlib/patterns]` | Merge multiple namespace maps. |
| `some_file.clj/some-var` | Load a Clojure file and resolve a public var. |
| `some_agent.agent.edn` | Load and compile another agent. |
| `{:file "path/to/file"}` | Read file content as a string. |
| `{:file "path/to/file.clj" :items {name var}}` | Load selected vars from a Clojure file into a namespace map. |

### Sub-Agent Values

The `:llms` option accepts:

| Form | Meaning |
|---|---|
| omitted | Discover sibling `.agent.edn` files in the same directory. |
| `[]` | Disable `llms/` discovery. |
| vector of symbols | Expose only the listed `.agent.edn` files. |
| map | Explicit mapping from `llms/` names to agent files or inline mini agent specs. |

Inline mini specs can use most agent options, including `:doc`, `:system`, `:model`, `:provider`, `:format`, and `:namespaces`.

### Loaded But Not Proposed Public

| Option | Description |
|---|---|
| `:api` | Currently loaded and inherited but not used by the runtime. This should be removed from the public config surface unless it gets a concrete purpose before release. |

### Agent Boundary

Agent configs own model-facing behavior:

- system prompt
- available Spell namespaces
- sub-agent topology
- output format
- recovery behavior
- model override
- reasoning, thinking, verbosity, prefill, and grammar behavior
- default run budget and retry schedule

Agent configs should not contain a concrete user prompt, init program, trace destination, interactive user reader, or usage atom.

## Override Policy

The public API should have one clear precedence rule:

1. Run options control only the lifecycle of the current run.
2. Agent config controls model-facing behavior for that agent.
3. Provider config controls service connection, transport, and provider defaults.

When the same concept appears in two places, the more specific profile wins:

| Concept | Precedence |
|---|---|
| Provider selection | `run :provider` over agent `:provider` |
| Agent selection | `run :agent` over provider `:default-agent` |
| Model | agent `:model` over provider `:model` |
| Budget | `run :budget` over agent `:budget` over dynamic default |
| Retry schedule | agent `:retries` over provider/runtime default |

The public API should avoid adding new overlapping options. If a new option is not clearly run-scoped, it should be added to either provider config or agent config instead of to `spell.api/run`.
