# Recursive Coin Flip

Demonstrates recursive self-orchestration: each LLM flips a coin and conditionally delegates to a child.

## Prompt

```
Flip a coin programmatically. If it lands heads, print an h and pass this exact prompt onto a child LLM, using its binding, so that they can do the same thing. If it lands tails, print a t.
```

## Expected Behavior

1. LLM generates a random value with `(rand)`
2. If heads (rand > 0.5): return "h" + delegate same task to child
3. If tails (rand <= 0.5): return "t" and stop

The recursion continues until someone flips tails, producing output like: `hhht` or `t` or `hhhhhht`

## Example Solution

```clojure
(do
  (def thought "flip coin, recurse on heads")
  (def flip (rand))
  (def return
    (if (> flip 0.5)
      (cat "h" (llm prefix))  ; heads: recurse with same prompt
      "t")))                   ; tails: terminate
```

## Key Concepts

- **Randomness**: `(rand)` returns 0.0-1.0
- **Recursion**: `(llm prefix)` passes the same prompt to child
- **Conditional delegation**: Only recurse on certain conditions
- **Termination**: Base case (tails) stops the recursion

## Notes

- Output length follows geometric distribution
- Average depth: 2 calls (p=0.5 for tails)
- Recommended model: Sonnet (handles recursion better)
