# Recursive Coin Flip

Demonstrates recursive self-orchestration: each LLM flips a coin and conditionally delegates to a child.

## Prompt

```
Flip a coin programmatically. If it lands heads, pass this exact prompt onto a child LLM, using its binding, so that they can do the same thing; prepend "h" to the child's answer. If it lands tails, return "t".
```

## Solution

```clojure
(def flip (math/rand-int 2))
(if (= flip 0)
  (cat "h" (!llm-self prompt))  ; heads: recurse with same prompt
  "t")                          ; tails: terminate
```

Each LLM instance rewrites the logic from scratch using the same prompt (bound via `quine` in the completion wrapper).

## Key Concepts

- **Randomness**: `(math/rand-int 2)` returns 0 or 1
- **Recursion via prompt**: `(!llm-self prompt)` passes the same prompt to a child (each child rewrites code)
- **Termination**: Base case (tails) stops the recursion
- **Implicit return**: The `if` expression's value is the return value

## Notes

- Output length follows geometric distribution
- Average depth: 2 calls (p=0.5 for tails)
- Recommended model: Sonnet (handles recursion well)
