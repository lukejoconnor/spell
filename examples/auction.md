# Auction

`auction.spl` asks Spell to run a sealed-bid auction with three independent bidder agents. The parent coordinates the bidders, collects their bids, and reports the winner.

## Run It

```bash
bin/spell -e auction -d 20
bin/spell -v -e auction -d 20
```

## Prompt

```text
Run a sealed-bid auction for a painting.

You must orchestrate this with subagents, not local simulation.

Requirements:
- Spawn exactly 3 bidder agents with handles :bidder-a, :bidder-b, :bidder-c.
- Each bidder independently chooses one integer bid (for example 100-1000).
- Each bidder returns its bid as its lifecycle result.
- Main agent must spawn and collect all three bidders in one agents/!spawn-ask call, then announce the winner.
- Do not fabricate bids in the main agent; bids are valid only if returned by spawned bidders.
- Bidder agents must not communicate with each other.
```

## What To Expect

The result should list three bids and identify the highest bidder, or report a tie if bids match.

## Concepts

- `agents/!spawn-ask` starts independent agents and collects their lifecycle results.
- One multi-target edge completes when all three bidder results are available.
- The parent agent can coordinate children without inventing their outputs.
