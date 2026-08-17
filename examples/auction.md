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
- Main must spawn and collect all three named bidders with one multi-agent agents/!spawn-ask.
- Each bidder returns its bid as its lifecycle result; the completion report collects all three bids.
- Do not fabricate bids in the main agent; bids are valid only if returned by spawned bidders.
- Bidder agents must not communicate with each other.
```

## What To Expect

The result should list three bids and identify the highest bidder, or report a tie if bids match.

## Concepts

- Multi-agent `agents/!spawn-ask` creates the three independent bidders and
  wakes the parent once all three lifecycle results are available.
- The parent agent can coordinate children without inventing their outputs.
