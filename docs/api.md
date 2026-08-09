# Spell API And Configuration

This document describes the public API and configuration surface for running Spell from another program.

A run supplies task input, chooses a model profile, chooses an agent profile, and may override the small set of model and agent defaults. Model profiles describe how Spell calls an LLM service. Agent profiles describe the Spell runtime profile exposed to the model. In this document, provider means the API provider or backend implementation, such as OpenAI, Anthropic, Fireworks, Codex, Ollama, or the test provider.

## Clojure Entry Point

```clojure
(require '[spell.api :as spell])

(spell/run {:prompt "Answer with the number 42."
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/cli.agent.edn"})
```

`spell.api/run` accepts one map and returns one map.

## Run Inputs

Exactly one of `:prompt` or `:init` is required.

| Option | Required | Description |
|---|---:|---|
| `:prompt` | one of `:prompt`, `:init` | Natural-language task. Spell wraps this in the standard completion program before evaluation. |
| `:init` | one of `:prompt`, `:init` | Complete Spell program string. The program is evaluated directly. |
| `:model-profile` | yes | Model profile selection. Accepts a model profile path, an inline model profile map, or an already constructed low-level provider instance. |
| `:agent-profile` | yes | Agent profile selection. Accepts an `.agent.edn` path. |

Model profile and agent profile are required at the API boundary. Config files may still name defaults, but those defaults are exposed as `:default-*` fields and resolved by higher-level helpers or CLIs rather than making `run` ambiguous.

## Run Overrides And Controls

These options are scoped to one invocation of `run`.

| Option | Default | Description |
|---|---:|---|
| `:model` | model profile `:default-model` | Model override for this run. This is exposed because model selection is likely to vary during normal experimentation. |
| `:reasoning-effort` | model profile `:default-reasoning-effort` | Reasoning-effort override for this run. This is exposed because users often sweep cost/quality tradeoffs. |
| `:budget` | agent profile `:default-budget` or runtime default | Maximum spend in dollars for the run. `nil` means use the configured default. `0` means unlimited. |
| `:depth` | unlimited | Maximum recursive LLM depth for this run. |
| `:trace-dir` | none | When non-nil, record a Spell execution trace in this directory. There is no separate `:trace` boolean. |
| `:usage-tracker` | fresh atom | Existing usage atom to accumulate token and cost accounting into. |
| `:user-reader` | none | When non-nil, register the interactive `:user` handle and read from this reader. There is no separate `:user?` boolean. |
| `:log-writer` | none | Writer for raw LLM debugging output. Pass `*out*` or another writer for logging. There is no separate `:verbose` boolean. The caller owns the writer; the API flushes it but does not close it. |

The API rejects unknown or removed options with a clear validation error. Silent ignore would make it too easy to believe a run used a specific model, format, or reasoning setting when it actually used a configured default.

## Return Shape

On success:

```clojure
{:result value
 :usage-tracker usage-atom
 :trace-dir "/path/to/trace"} ; only when :trace-dir was supplied
```

On failure:

```clojure
{:error "message"
 :error-data {...}
 :usage-tracker usage-atom
 :trace-dir "/path/to/trace"} ; only when :trace-dir was supplied
```

The API catches evaluation and provider exceptions and returns them as data. Input validation errors, such as supplying both `:prompt` and `:init`, throw.

At the end of a run, the API closes resources owned by the compiled agent, including MCP subscription streams and stdio subprocesses. Embedders that call `spell.agent/compile-agent-spec` directly should call `spell.agent/close-compiled-agent!` when they are finished with the compiled function.

## Examples

Run a natural-language task:

```clojure
(spell/run {:prompt "Flip a fair coin and return :heads or :tails."
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/base-tc.agent.edn"})
```

Run a complete Spell program:

```clojure
(spell/run {:init "(do (+ 20 22))"
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/base-tc.agent.edn"})
```

Run with common overrides:

```clojure
(spell/run {:prompt "Solve the task, using tools if needed."
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/cli.agent.edn"
            :model "gpt-5.4"
            :reasoning-effort "medium"
            :budget 2.00
            :depth 20
            :trace-dir "traces/run-001"})
```

Log raw LLM output to stdout:

```clojure
(spell/run {:prompt "Return 42."
            :model-profile "config/model-profiles/openai-tc.edn"
            :agent-profile "config/agent-profiles/base-tc.agent.edn"
            :log-writer *out*})
```

Use an inline model profile map:

```clojure
(spell/run {:prompt "Return 42."
            :model-profile {:provider :openai
                         :model-profile-name "openai-tc"
                         :default-model "gpt-5.4"
                         :force-tool-call true
                         :use-responses-api true}
            :agent-profile "config/agent-profiles/base-tc.agent.edn"})
```

## Configuration Split

Model profiles describe how to call an LLM service. They own endpoint, authentication, model-call policy, transport behavior, retry behavior, timeouts, pricing, and provider-specific request features.

Agent profiles describe the Spell runtime profile exposed to the model. They own the system prompt, available Spell namespaces, sub-agent topology, output contract, and recovery behavior.

The test is:

- If changing the option changes the HTTP/API request, model transport, provider call controls, cost accounting, or retry policy, it belongs in the model profile.
- If changing the option changes what Spell capabilities or instructions the model sees, or how Spell validates/evaluates the model's program, it belongs in the agent profile.
- If changing the option only affects this invocation's task input, lifecycle, logging, tracing, interactive input, or accounting destination, it belongs in `spell.api/run`.

This means model-call policy lives in model-profile defaults, while implementation leftovers are kept out of the public API/config surface.

## Model Profiles

Model profile files live under `config/model-profiles/` and use EDN maps.

```clojure
{:provider :openai
 :model-profile-name openai-tc
 :api-key-env "OPENAI_API_KEY"
 :default-model "gpt-5.4"
 :default-reasoning-effort "medium"
 :use-responses-api true
 :force-tool-call true
 :max-tokens 32768
 :default-agent-profile "../agent-profiles/base-tc.agent.edn"}
```

### Model Profile Options

| Option | Applies to | Decision | Description |
|---|---|---|---|
| `:provider` | all | rename from `:type` | API provider implementation. Supported values are `:openai`, `:anthropic-pf`, `:anthropic-tc`, `:codex-tc`, `:fireworks`, `:fireworks-tc`, `:ollama`, and `:test`. |
| `:model-profile-name` | all | add model profile | Human-readable model profile name. Defaults to the profile filename without `.edn`. |
| `:api-key-env` | `:openai`, `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | keep model profile | Environment variable that contains the API key. Public config files prefer this over literal secrets. |
| `:base-url` | `:openai`, `:codex-tc`, `:fireworks`, `:fireworks-tc`, `:ollama` | keep model profile | API base URL. |
| `:model` | all model-backed providers | rename to `:default-model` | Model profile default model. `spell.api/run :model` may override it for a run. |
| `:default-reasoning-effort` | `:openai`, `:codex-tc`, `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | add model profile | Provider-neutral reasoning setting. The provider maps it to its native request shape, including Anthropic thinking budgets. `spell.api/run :reasoning-effort` may override it for a run. |
| `:max-tokens` | all hosted model providers | keep model profile | Maximum response tokens requested from the provider. |
| `:retries` | all hosted model providers | move to model profile | Provider retry schedule for transient API failures. Values are sleep durations in seconds. |
| `:request-timeout-sec` | `:openai`, `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | keep model profile | Per-request timeout in seconds. |
| `:sse-idle-timeout-sec` | `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | keep model profile | Streaming timeout in seconds with no received bytes. |
| `:sse-completion-timeout-sec` | `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | keep model profile | Total streaming response timeout in seconds. |
| `:costs` | all | keep model profile | Pricing overrides merged into `data/pricing.edn`. |
| `:cache-read-ratio` | all | keep model profile | Cost-table helper used when deriving cache-read prices from base input prices. |
| `:default-agent-profile` | all | keep model profile | Agent profile that higher-level helpers may use when no agent profile is supplied. `spell.api/run` still requires `:agent-profile`. |
| `:use-responses-api` | `:openai` | keep model profile | Force OpenAI Responses API instead of Chat Completions. |
| `:force-tool-call` | `:openai` | keep model profile | Require a `spell_suffix` custom tool call. |
| `:prompt-cache-key` | `:openai`, `:codex-tc` | keep model profile | Stable prompt cache key reused across model calls when cache-prefixing is enabled. |
| `:verbosity` | `:openai`, `:codex-tc` | keep model profile | Provider-facing verbosity control. |
| `:auth-file` | `:codex-tc` | keep model profile | Path to Codex/ChatGPT auth JSON. |
| `:account-id` | `:codex-tc` | keep model profile | ChatGPT account id header override. |
| `:chat-template` | `:fireworks` | keep model profile | Fireworks completions chat template keyword or explicit template map. |
| `:convert-think?` | `:fireworks` | delete from public spec | Current provider code defaults this behavior on for the Fireworks completions provider, and the historical checked-in EDN occurrence was the Fireworks provider profile. Paper benchmark runs through `spell.benchmark-api` construct Fireworks providers directly and do not need this exposed as a public profile switch. |
| `:responses` | `:test` | keep model profile test-only | Declarative response sequence for tests. |
| `:response-rules` | `:test` | keep model profile test-only | Prompt-matching response rules for tests. |
| `:response` | `:test` | keep model profile test-only | Single fixed test response. |
| `:supports-prefill?` | `:test` | rename model profile test-only | Whether the test provider reports prefill support. |

The public model profile does not expose separate `:thinking` and `:reasoning-effort` options. Spell exposes one provider-neutral `:default-reasoning-effort` key and maps it to each provider's native mechanism. Anthropic can translate reasoning effort into thinking budgets; explicit low-level `:thinking` remains an internal constructor escape hatch if needed, but is not part of the public config spec.

The public model profile also does not expose `:prefill?`. Prefill is a transport capability and prompt style implied by the provider plus the selected agent prompt profile. The provider reports whether it supports assistant prefill, and higher-level defaults can pair prefill-capable providers with `base-pf.agent.edn`; users do not need a separate boolean.

The suffix grammar options, `:suffix-grammar?` and `:grammar-max-chars`, are omitted from the public config spec. They exist in code and tests, and there was an old suffix-grammar pilot notebook entry, but they are not used in current checked-in config files or the paper-facing run configurations found in the repo.

Low-level provider constructor functions may accept direct `:api-key` values for programmatic use. Public model profile files use `:api-key-env` instead, so secrets do not land in the repository.

## Agent Profiles

Agent profile files live under `config/agent-profiles/` and use EDN maps.

```clojure
{:base base-tc.agent.edn
 :agent-name cli
 :agent-description "CLI default: full capabilities for interactive use"
 :default-model-profile "../model-profiles/openai-tc.edn"
 :default-budget 1.00
 :available-agents {explore explore.agent.edn}
 :namespaces
 {io       stdlib/io
  web      stdlib/web
  patterns stdlib/patterns
  agents   stdlib/agents
  globals  stdlib/globals}}
```

### Agent Options

| Option | Decision | Description |
|---|---|---|
| `:base` | keep agent profile | Parent `.agent.edn` file. Child scalar options override parent scalar options; `:namespaces` merge by key. |
| `:name` | rename to `:agent-name` | Human-readable agent name, usually a symbol. Defaults to the agent filename without `.agent.edn` or `.edn`. |
| `:doc` | rename to `:agent-description` | Short description used by generated docs and `workers/` descriptions. |
| `:system` | rename to `:system-prompt` | System prompt, either an inline string or `{:file "relative/path.txt"}`. |
| `:model` | move to model profile as `:default-model` | Model choice is part of the LLM call policy. If a sub-agent needs a different model, give it a different `:default-model-profile`. |
| `:provider` | rename to `:default-model-profile` | Default model profile for this agent. The name makes clear that `spell.api/run :model-profile` overrides it. |
| `:budget` | rename to `:default-budget` | Default dollar budget for runs using this agent profile. `spell.api/run :budget` overrides it. |
| `:recover` | keep agent | Recovery behavior used when evaluating model output fails. |
| `:format` | keep agent | Structured output contract for this agent. This changes how Spell validates and repairs model output, not how the provider endpoint is called. |
| `:max-retries` | rename to `:format-retries` | Maximum format-repair attempts when `:format` is configured. The current name is too easy to confuse with provider API retries. |
| `:retries` | move to model profile | API retry schedule is provider-call behavior. |
| `:prefill?` | delete | Prefill is implied by provider capability and prompt profile, not configured on the agent. |
| `:thinking` | move to provider-neutral reasoning effort | Public config uses `:default-reasoning-effort` on the model profile instead. |
| `:reasoning-effort` | move to model profile as `:default-reasoning-effort` | Reasoning effort changes provider request parameters and is also exposed as a run override. |
| `:verbosity` | move to model profile | Verbosity changes provider request parameters. |
| `:suffix-grammar?` | delete from public spec | Not used by current configs or paper-facing run configurations. |
| `:grammar-max-chars` | delete from public spec | Only relevant to omitted suffix grammar support. |
| `:available-agents` | keep agent | Explicit sub-agent set exposed through the `workers/` namespace. Omit it for no additional workers beyond inherited base profile settings. |
| `:namespaces` | keep agent | Spell namespaces exposed to the agent. |
| `:mcp-servers` | keep agent | Configured stateless MCP servers and per-capability permissions. Child profiles merge this map by server alias. See [MCP Client](mcp.md). |
| `:api` | delete | Loaded and inherited by older configs but unused by the runtime. It is not part of the public config surface. |

### Namespace Values

The `:namespaces` map accepts several reference forms:

| Form | Meaning |
|---|---|
| `stdlib/io` | Built-in stdlib namespace. |
| `stdlib/reminders/coding` | Nested item from a stdlib namespace. |
| `[stdlib/io stdlib/patterns]` | Merge multiple namespace maps. |
| `some_file.clj/some-var` | Load a Clojure file and resolve a public var. |
| `{:file "path/to/file"}` | Read file content as a string. |
| `{:file "path/to/file.clj" :items {name var}}` | Load selected vars from a Clojure file into a namespace map. |

The current loader can also compile a `.agent.edn` file directly as a namespace value, but this is not part of the public spec. It overlaps with `:available-agents`, which is the clearer mechanism for exposing sub-agents.

### Sub-Agent Values

The `:available-agents` option accepts:

| Form | Meaning |
|---|---|
| omitted | Inherit the base profile's workers if inherited; otherwise expose no `workers/` namespace. |
| `[]` | Disable `workers/`, overriding any base profile workers. |
| vector of symbols | Expose only the listed `.agent.edn` files from the profile directory. |
| map | Explicit mapping from `workers/` names to agent profile files or inline mini agent profile specs. |

Inline mini specs can use agent profile options such as `:agent-description`, `:system-prompt`, `:default-model-profile`, `:format`, and `:namespaces`.

Sub-agent resolution happens when the parent agent is compiled. Each `:available-agents` entry is resolved to an agent profile spec and compiled into a runnable function exposed in the `workers/` namespace. If the sub-agent profile spec has its own `:default-model-profile`, that profile is used. Otherwise the sub-agent inherits the parent agent's resolved model profile. Model differences should usually be represented by choosing a different `:default-model-profile`; otherwise the sub-agent uses the inherited profile's `:default-model`.

## Override Policy

Config fields that are directly overridable from the API should be named `:default-*` in config files:

| Concept | Config field | API override |
|---|---|---|
| Model profile selected by an agent profile | agent profile `:default-model-profile` | `run :model-profile` |
| Agent profile selected by model profile helpers | model profile `:default-agent-profile` | `run :agent-profile` |
| Model | model profile `:default-model` | `run :model` |
| Reasoning effort | model profile `:default-reasoning-effort` | `run :reasoning-effort` |
| Budget | agent profile `:default-budget` | `run :budget` |

The public API avoids adding new overlapping options unless they are commonly varied run to run. Model and reasoning effort pass that test. Most transport, timeout, retry, formatting, and namespace choices do not.

## Python Adapter Direction

The Python adapter should mirror this API shape rather than preserve benchmark-specific names:

```python
spell.run(
    prompt="Return 42.",
    model_profile="config/model-profiles/openai-tc.edn",
    agent_profile="config/agent-profiles/base-tc.agent.edn",
    model="gpt-5.4",
    reasoning_effort="medium",
    budget=1.0,
    trace_dir="traces/run-001",
)
```

The adapter can still call a Clojure subprocess internally. Its public vocabulary is `run`, `prompt`, `init`, `model_profile`, `agent_profile`, `model`, `reasoning_effort`, `budget`, `depth`, `trace_dir`, `usage_tracker`, `user_reader`, and `log_writer`, not benchmark-specific request and response names.
