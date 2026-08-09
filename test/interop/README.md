# MCP interoperability fixtures

This optional fixture is outside Spell's runtime and normal test paths.

`uv.lock` pins the official Python MCP SDK at `mcp==2.0.0`. Run both stdio and Streamable HTTP checks with:

```bash
clojure -M:test-mcp-interop
```

The official conformance repository was checked at its latest stable tag, `v0.1.16` (`21a9a2f`), while this integration was implemented. That tag's declared spec-version list stops before `2026-07-28`, so Spell does not claim or pin a misleading conformance result from it. Add a separate optional conformance alias once a stable tagged runner contains applicable stateless-client scenarios. Normal Spell installation must not acquire Node, Python, or an MCP SDK dependency.
