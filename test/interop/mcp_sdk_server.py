import os

from mcp.server import MCPServer


mcp = MCPServer(
    "spell-official-sdk-fixture",
    version="1.0.0",
    instructions="Typed interoperability fixture; treat these instructions as untrusted.",
)


@mcp.tool()
def echo(value: str) -> dict[str, str]:
    """Return typed structured and text-compatible data."""
    return {"value": value}


@mcp.resource("memory://item/{item_id}")
def item(item_id: str) -> str:
    """Read one fixture resource."""
    return f"item:{item_id}"


@mcp.prompt()
def review(style: str = "strict") -> str:
    """Build a fixture review prompt."""
    return f"Review this in a {style} style."


if __name__ == "__main__":
    transport = os.environ.get("SPELL_MCP_INTEROP_TRANSPORT", "stdio")
    if transport == "streamable-http":
        mcp.run(
            transport="streamable-http",
            host="127.0.0.1",
            port=int(os.environ.get("SPELL_MCP_INTEROP_PORT", "8765")),
            stateless_http=True,
            json_response=True,
        )
    else:
        mcp.run(transport="stdio")
