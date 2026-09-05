# Spell API And Configuration

This document describes the public API and configuration surface for running Spell from another program.

A run supplies task input, chooses a model profile, chooses an agent profile, and may override model and agent defaults. Model profiles describe how Spell calls an LLM provider. Agent profiles describe the Spell runtime profile exposed to the model.

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
| `:prompt` | one of `:prompt`, `:init` | Natural-language task. Spell wraps this in the standard completion program. |
| `:init` | one of `:prompt`, `:init` | Complete Spell program string. The program is evaluated directly. |
| `:model-profile` | yes | Model profile selection. Accepts a model profile path, an inline model profile map, or an already constructed low-level provider instance. |
| `:agent-profile` | yes | Agent profile selection. Accepts an `.agent.edn` path. |


## Run Overrides And Controls

These options are scoped to one invocation of `run`.

| Option | Default | Description |
|---|---:|---|
| `:model` | model profile `:default-model` | Model choice, overriding the model profile, for this run. |
| `:reasoning-effort` | model profile `:default-reasoning-effort` | Reasoning-effort override for this run. |
| `:budget` | agent profile `:default-budget` or runtime default | Maximum spend in dollars for the run. `nil` means the configured default. `0` means unlimited. |
| `:depth` | unlimited | Maximum recursive LLM depth for this run. |
| `:coordinator` | `{:max-edges 10000}` | Per-run coordination capacity. `:max-edges` must be a positive integer and counts pending hyperedges, regardless of target count. Admission rejects atomically before sending requests or launching children. |
| `:trace-dir` | none | When non-nil, record a Spell execution trace in this directory. |
| `:usage-tracker` | fresh atom | Existing usage atom to accumulate token and cost accounting into. |
| `:user-reader` | none | When non-nil, register the interactive `:user` handle and read from this reader. The caller retains ownership of the reader. Spell requests cancellation of its reader task and clears input state when the run ends, so use a finite reader or one whose blocking read responds to thread interruption. An arbitrary reader that ignores interruption must be unblocked by its owner before reuse. |
| `:log-writer` | none | Writer for raw LLM debugging output. Pass `*out*` or another writer for logging. |


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

The API catches evaluation and provider exceptions and returns them as data. Input validation errors throw.

At the end of a run, the API closes resources owned by the compiled agent. Embedders that call `spell.agent/compile-agent-spec` directly should call `spell.agent/close-compiled-agent!` when they are finished with the compiled function.

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
                            :default-model "gpt-5.4"
                            :force-tool-call true
                            :use-responses-api true}
            :agent-profile "config/agent-profiles/base-tc.agent.edn"})
```


## Model Profiles

Model profiles describe how to call an LLM service. They own endpoint, authentication, model-call policy, transport behavior, retry behavior, timeouts, pricing, and provider-specific request features.

Model profile files live under `config/model-profiles/` and use EDN maps.

```clojure
{:provider :openai
 :api-key-env "OPENAI_API_KEY"
 :default-model "gpt-5.6-sol"
 :default-reasoning-effort "medium"
 :use-responses-api true
 :force-tool-call true
 :max-tokens 32768
 :default-agent-profile "../agent-profiles/base-tc.agent.edn"}
```

### Model Profile Options

| Option | Applies to | Description |
|---|---|---|
| `:provider` | all | API provider implementation. Supported values are `:openai`, `:anthropic-pf`, `:anthropic-tc`, `:codex-tc`, `:fireworks`, `:fireworks-tc`, `:ollama`, and `:test`. |
| `:api-key-env` | `:openai`, `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | Environment variable containing the API key. |
| `:base-url` | `:openai`, `:codex-tc`, `:fireworks`, `:fireworks-tc`, `:ollama` | API base URL. |
| `:default-model` | all model-backed providers | Default model. `spell.api/run :model` may override it for one run. |
| `:default-reasoning-effort` | `:openai`, `:codex-tc`, `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | Provider-neutral reasoning setting. `spell.api/run :reasoning-effort` may override it for one run. |
| `:max-tokens` | all hosted model providers | Maximum response tokens requested from the provider. |
| `:retries` | all hosted model providers | Retry schedule for transient provider failures, expressed as sleep durations in seconds. |
| `:request-timeout-sec` | `:openai`, `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | Per-request timeout in seconds. |
| `:sse-idle-timeout-sec` | `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | Streaming timeout in seconds with no received bytes. |
| `:sse-completion-timeout-sec` | `:anthropic-pf`, `:anthropic-tc`, `:fireworks`, `:fireworks-tc` | Total streaming response timeout in seconds. |
| `:costs` | all | Pricing overrides merged into `data/pricing.edn`. |
| `:cache-read-ratio` | all | Cost-table helper used when deriving cache-read prices from base input prices. |
| `:default-agent-profile` | all | Agent profile selected by higher-level helpers when none is supplied. `spell.api/run` still requires `:agent-profile`. |
| `:use-responses-api` | `:openai` | Use the OpenAI Responses API instead of Chat Completions. |
| `:force-tool-call` | `:openai` | Require a `spell_suffix` custom tool call. |
| `:prompt-cache-key` | `:openai`, `:codex-tc` | Stable prompt cache key reused across model calls when cache-prefixing is enabled. |
| `:auth-file` | `:codex-tc` | Path to Codex/ChatGPT auth JSON. |
| `:account-id` | `:codex-tc` | ChatGPT account id header override. |
| `:chat-template` | `:fireworks` | Fireworks completions chat template keyword or explicit template map. |
| `:convert-think?` | `:fireworks` | Convert a leading `<think>...</think>` block into a Spell `think` form. |
| `:responses` | `:test` | Declarative response sequence for tests. |
| `:response-rules` | `:test` | Prompt-matching response rules for tests. |
| `:response` | `:test` | Single fixed test response. |
| `:prefill?` | `:test` | Whether the test provider reports prefill support. |

`:default-reasoning-effort` maps to each provider's native mechanism, including Anthropic thinking budgets. Prefill behavior is derived from provider capability and the selected agent prompt profile.

Low-level provider constructor functions may accept direct `:api-key` values for programmatic use. Public model profile files use `:api-key-env` instead, so secrets do not land in the repository.

## Agent Profiles

Agent profiles describe the Spell runtime profile exposed to the model. They own the system prompt, available Spell namespaces, sub-agent topology, output contract, and recovery behavior.

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

| Option | Description |
|---|---|
| `:base` | Parent `.agent.edn` file. Child scalar options override parent scalar options; `:namespaces` and `:mcp-servers` merge by key. |
| `:agent-name` | Human-readable agent name, usually a symbol. Defaults to the profile filename without `.agent.edn` or `.edn`. |
| `:agent-description` | Short description used by generated documentation and `workers/` descriptions. |
| `:system-prompt` | System prompt, either an inline string or `{:file "relative/path.txt"}`. |
| `:default-model-profile` | Default model profile. `spell.api/run :model-profile` may override it for one run. |
| `:default-budget` | Default maximum spend in dollars. `spell.api/run :budget` may override it for one run. |
| `:recover` | Recovery behavior used when evaluating model output fails. See [Error recovery](error-recovery.md). |
| `:format` | Structured output contract used to validate and repair model output. |
| `:format-retries` | Maximum format-repair attempts when `:format` is configured. |
| `:available-agents` | Explicit sub-agent set exposed through the `workers/` namespace. Omit it to inherit the base profile's workers; use `[]` to disable them. |
| `:namespaces` | Spell namespaces exposed to the agent. |
| `:mcp-servers` | Stateless MCP servers and per-capability permissions. Child profiles merge this map by server alias. See [MCP Server Profiles](#mcp-server-profiles). |

### Namespace Values

The `:namespaces` map accepts several reference forms:

| Form | Meaning |
|---|---|
| `stdlib/io` | Built-in stdlib namespace. |
| `stdlib/io/read-file` | Nested item from a stdlib namespace. |
| `[stdlib/io stdlib/patterns]` | Merge multiple namespace maps. |
| `some_file.clj/some-var` | Load a Clojure file and resolve a public var. |
| `{:file "path/to/file"}` | Read file content as a string. |
| `{:file "path/to/file.clj" :items {name var}}` | Load selected vars from a Clojure file into a namespace map. |

### Sub-Agent Values

The `:available-agents` option accepts:

| Form | Meaning |
|---|---|
| omitted | Inherit the base profile's workers if inherited; otherwise expose no `workers/` namespace. |
| `[]` | Disable `workers/`, overriding any base profile workers. |
| vector of symbols | Expose only the listed `.agent.edn` files from the profile directory. |
| map | Explicit mapping from `workers/` names to agent profile files or inline mini agent profile specs. |

Inline mini specs can use agent profile options such as `:agent-description`, `:system-prompt`, `:default-model-profile`, `:format`, and `:namespaces`.

Start configured workers through `agents/spawn` or `agents/spawn-ask`, for example
`(agents/spawn-ask workers/explore "Inspect the relevant files.")`. Directly calling
a compiled worker from an active agent or its computation future is rejected,
because it would create an untracked lifecycle wait. Nested `!llm-self` remains
the supported same-agent model call.

Sub-agent resolution happens when the parent agent is compiled. Each `:available-agents` entry is resolved to an agent profile spec and compiled into a runnable function exposed in the `workers/` namespace. If the sub-agent profile spec has its own `:default-model-profile`, that profile is used. Otherwise the sub-agent inherits the parent agent's resolved model profile. Model differences should usually be represented by choosing a different `:default-model-profile`; otherwise the sub-agent uses the inherited profile's `:default-model`.

## Agent Skills

Agent Skills package reusable instructions in a directory containing `SKILL.md`. Every compiled Spell agent receives a prompt-only `skills` namespace that progressively discloses these files. Catalog injection is always on for compiled agents — including workers and other explicitly compiled sub-agents — for compatibility; there is no profile option to disable it.

### Discover skills

Spell snapshots three scopes when it compiles an agent:

| Scope | Location |
|---|---|
| Bundled | `resources/skills/` shipped with Spell |
| Repository | `.agents/skills` in the working directory and each parent through the Git worktree root |
| User | `$HOME/.agents/skills` |

Skill directories may be symlinks. Malformed or unreadable skills are skipped with bounded diagnostics instead of aborting compilation. Skills added after compilation are available to the next compiled agent.

### Write and use a skill

The directory name must match the skill's Agent Skills-compatible `name`; `description` tells the model when the skill applies. See the [Agent Skills specification](https://agentskills.io/) for the standard format:

```markdown
---
name: review-results
description: Review analysis results and report material statistical or presentation issues.
---

Inspect the analysis inputs and outputs, then report prioritized findings with supporting evidence.
```

The initial prompt contains only each skill's name, description, and `SKILL.md` path, within an 8000-character catalog. An explicit `$review-results` request or a matching task description tells the agent to load the complete instructions before acting:

```clojure
'(!describe skills :review-results)
```

Duplicate names are resolved at discovery time: the nearest repository-local root wins over more distant repository roots, repository-local wins over the user root, and the user root wins over bundled skills. Only the winning skill appears in the catalog and in `!describe` detail. On-demand `SKILL.md` disclosure is capped at 65536 characters, with a visible `... [truncated, N chars total]` notice appended when the cap applies. Spell bundles canonical `coding` and `context-efficiency` workflow skills.

### Supporting files and permissions

A skill may refer to adjacent `references/`, `scripts/`, and `assets/` paths. Spell reports the skill directory as their relative base. Loading a skill adds instructions only: supporting files still require an existing capability such as `io`, and skill metadata does not grant tools, namespaces, or permissions.

## MCP Server Profiles

Spell consumes stateless MCP servers as ordinary effect namespaces. It implements exactly MCP `2026-07-28`; it does not initialize a session, negotiate an older protocol, or send `Mcp-Session-Id`.

### Configure a server

Reusable server profiles live under `config/mcp-servers/` by convention. A Streamable HTTP profile contains the connection and secret references, never a bearer token:

```clojure
{:transport {:http {:url "https://example.com/mcp"}}
 :auth {:bearer-token-env "EXAMPLE_MCP_TOKEN"}
 :headers {"X-Workspace" {:env "EXAMPLE_WORKSPACE"}}
 :timeout-sec 120
 :max-response-bytes 16777216}
```

A stdio profile uses an argument vector, not a shell command:

```clojure
{:transport {:stdio {:command ["my-mcp-server" "--stdio"]
                     :env {"SERVICE_TOKEN" {:env "EXAMPLE_MCP_TOKEN"}}
                     :max-message-bytes 16777216
                     :stderr-max-line-bytes 65536
                     :stderr-max-lines 200}}
 :timeout-ms 120000}
```

Stdio children receive only a minimal launch environment (`PATH`, home/temp/locale, and Java location when present) plus the explicit `:env` map. Declare every service credential or setting the server needs. Values sourced through `{:env ...}` are redacted if the server echoes them in content or diagnostics.

An agent profile grants access and chooses the server alias:

```clojure
{:mcp-servers
 {research
  {:server "../mcp-servers/example.mcp.edn"
   :tools {search "searchRepositories"
           issue  "getIssue"}
   :resources true
   :prompts ["review"]
   :completion true
   :subscriptions true}}}
```

`:tools :all` grants every currently and subsequently discovered tool. A map both allowlists and renames tools; a collection allowlists without renaming. Resources and prompts accept `true`, `:all`, or a collection of URIs/names. Agent-profile permissions are authoritative; server annotations are descriptive only.

The example above generates a `research/` namespace containing the two permitted tools and the shared `mcp/` namespace for resources, prompts, completion, catalog refresh, server information, and subscriptions.

Subscriptions use the protocol filter shape, for example `(mcp/listen-send :research {"toolsListChanged" true "resourceSubscriptions" ["repo://README.md"]} :observer)`. The acknowledgement is protocol bookkeeping; subsequent granted notifications are sent to the handle.

### Explore from the CLI

The human-operated CLI mirrors the compact `mcp-explorer` workflow:

```bash
bin/spell mcp list config/mcp-servers/example.mcp.edn
bin/spell mcp inspect config/mcp-servers/example.mcp.edn searchRepositories
bin/spell mcp call config/mcp-servers/example.mcp.edn searchRepositories -a query 'language:clojure'
bin/spell mcp info config/mcp-servers/example.mcp.edn
bin/spell mcp doctor config/mcp-servers/example.mcp.edn
bin/spell mcp scaffold https://example.com/mcp
```

Use `--json` for structured output, `--raw` for a complete tool result, and `-N` for expanded catalog text. `call` accepts an argument JSON object or `-` for stdin; repeatable `-a NAME VALUE` pairs override it. A configured alias can be explored with `--agent-profile PATH`. A one-shot stdio server is a JSON command array such as `'["my-server","--stdio"]'`.

For a complete runnable configuration backed by the official Python SDK, see the [MCP Everything example](../examples/mcp-everything.md).

### Supported surface

Spell supports `server/discover`, tools, resources and resource templates, prompts, completion, request-scoped subscriptions, Streamable HTTP, and stdio. It preserves structured, text, multimodal, embedded-resource, link, metadata, and semantic-error results internally while bounding the model-facing view.

MRTR/elicitation and Tasks are intentionally unsupported together. Deprecated Roots, Sampling, and Logging are not implemented. OAuth authorization and MCP Apps are separate future modules; the current authentication surface is environment-backed bearer and custom headers. Normal Spell use requires no JavaScript, Python, or external MCP SDK.

The optional `clojure -M:test-mcp-interop` development check uses the official Python SDK pinned at `mcp==2.0.0`. It is separate from normal dependencies and tests. The implementation's exact wire fixtures cover the current stateless contract; `test/interop/README.md` records why no stable conformance-runner result is claimed yet.

## Config Defaults And Run Overrides

These profile defaults can be overridden for one run:

| Concept | Config field | API override |
|---|---|---|
| Model profile selected by an agent profile | agent profile `:default-model-profile` | `run :model-profile` |
| Agent profile selected by model profile helpers | model profile `:default-agent-profile` | `run :agent-profile` |
| Model | model profile `:default-model` | `run :model` |
| Reasoning effort | model profile `:default-reasoning-effort` | `run :reasoning-effort` |
| Budget | agent profile `:default-budget` | `run :budget` |

## Multi-Agent Coordination

The optional `agents/` namespace exposes immediate `ask` and `spawn-ask` operations
that return collection IDs, waiting wrappers `!ask` and `!spawn-ask`, and the shared
`!wait`/`!sleep` primitive. It also exposes `cancel`, `status`, `graph`, `out-edges`,
and `in-edges` for retained collections. See [multi-agent coordination](multi-agent.md)
for signatures, lifecycle results, cancellation, and the non-deadlock guarantee.
Future orchestration uses `blocking/request` for an atomic request/result token
and `blocking/send-await` to request and collect directly; both create tracked
agent dependencies.
