# MCP Everything

`mcp-everything.spl` asks a real model to discover and use every MCP capability that Spell supports. The accompanying server uses the official Python MCP SDK 2.0.0 and implements the exact stateless `2026-07-28` protocol over persistent stdio.

[Simon Willison's MCP TIL](https://til.simonwillison.net/llms/mcp-in-claude-and-chatgpt) connects to a live Datasette MCP endpoint rather than a mock server. This example provides a harmless local equivalent of MCP's [Everything reference server](https://github.com/modelcontextprotocol/servers/tree/main/src/everything), narrowed to the capabilities implemented by Spell.

The official `@modelcontextprotocol/server-everything` package is not used because its current `2026.7.4` release predates the stateless protocol and answers `server/discover` with `Method not found`.

## Run It

Install [`uv`](https://docs.astral.sh/uv/) and configure `OPENAI_API_KEY`, then run:

```bash
bin/spell -e mcp-everything \
  -a examples/mcp-everything.agent.edn \
  -m openai-tc:gpt-5.6-sol \
  -R medium \
  -b 2
```

The first run downloads the pinned `mcp==2.0.0` Python SDK into uv's cache. Python and the SDK are example-only dependencies; Spell itself remains a Clojure/JVM program.

## What To Expect

A successful run reports:

- the server-provided description or instructions;
- `42` from the `add` tool and `7` from `set-counter`;
- the static description resource and templated value `item:alpha`;
- a strict review prompt and completion suggestions `formal` and `friendly`;
- a `notifications/resources/updated` event for `demo://counter`.

The example intentionally requires both protocol interoperability and model usability: the model must understand the generated namespace documentation, select each MCP operation, and compose the results into one value.

## Concepts

- `everything/add` and `everything/set-counter` are permissioned tools generated from server schemas.
- `mcp/info`, `mcp/resources`, `mcp/read-resource`, `mcp/prompts`, `mcp/get-prompt`, and `mcp/complete` expose non-tool protocol capabilities.
- `mcp/listen-send` runs a subscription in the background and sends granted notifications to an agent handle.
- Server descriptions, instructions, and schemas are attributed, untrusted data rather than privileged system instructions.
