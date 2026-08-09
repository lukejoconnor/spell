# MCP Client

Spell consumes stateless MCP servers as ordinary effect namespaces. It implements exactly MCP `2026-07-28`; it does not initialize a session, negotiate an older protocol, or send `Mcp-Session-Id`.

## Configure a server

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
                     :env {"SERVICE_TOKEN" {:env "EXAMPLE_MCP_TOKEN"}}}}
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

The example above generates a `research/` namespace containing the two permitted tools and the shared `mcp/` namespace for resources, prompts, completion, catalog refresh, server information, and subscriptions. These are ordinary Spell capabilities: there is no language-level distinction between operations invoked by a user participant and operations invoked by another permissioned agent.

Subscriptions use the protocol filter shape, for example `(mcp/listen-send :research {"toolsListChanged" true "resourceSubscriptions" ["repo://README.md"]} :observer)`. The acknowledgement is protocol bookkeeping; subsequent granted notifications are sent to the handle.

Catalogs are completely paginated, sorted by their protocol identity, and fixed for the generated tool namespace during a compiled run. Catalogs above 20 permitted tools use summary disclosure: the base prompt contains only the namespace summary, `!describe` shows compact signatures, and item-level `!describe` retains the full schema. `mcp/refresh` refreshes protocol catalogs without silently changing that run's callable functions. Positive server `ttlMs` values permit in-memory reuse across compiles with the same alias, endpoint, and credential context; `ttlMs: 0` disables that reuse. Subscription notifications invalidate reusable catalog entries.

Server descriptions, instructions, schemas, annotations, and extension metadata remain attributed, untrusted data. Spell does not insert server instructions into its system prompt. Tool arguments and structured outputs are validated with JSON Schema 2020-12, and external schema references are rejected rather than fetched.

## Explore from the CLI

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

## Supported surface

Spell supports `server/discover`, tools, resources and resource templates, prompts, completion, request-scoped subscriptions, Streamable HTTP, and stdio. It preserves structured, text, multimodal, embedded-resource, link, metadata, and semantic-error results internally while bounding the model-facing view.

MRTR/elicitation and Tasks are intentionally unsupported together. Deprecated Roots, Sampling, and Logging are not implemented. OAuth authorization and MCP Apps are separate future modules; the current authentication surface is environment-backed bearer and custom headers. Normal Spell use requires no JavaScript, Python, or external MCP SDK.

The optional `clojure -M:test-mcp-interop` development check uses the official Python SDK pinned at `mcp==2.0.0`. It is separate from normal dependencies and tests. The implementation's exact wire fixtures cover the current stateless contract; `test/interop/README.md` records why no stable conformance-runner result is claimed yet.
