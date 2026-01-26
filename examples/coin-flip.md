# Recursive Coin Flip

Demonstrates recursive self-orchestration: each LLM flips a coin and conditionally delegates to a child.

## Simple Version (using prefix)

### Prompt

```
Flip a coin programmatically. If it lands heads, print an h and pass this exact prompt onto a child LLM, using its binding, so that they can do the same thing. If it lands tails, print a t.
```

### Solution

```clojure
(do
  (def thought "flip coin, recurse on heads")
  (def flip (rand))
  (def return
    (if (> flip 0.5)
      (cat "h" (llm prefix))  ; heads: recurse with same prompt
      "t")))                   ; tails: terminate
```

Each LLM instance redefines the logic from scratch using the same prompt.

## Extract Version (code reuse)

### Prompt

```
Flip a coin using rand. If heads (>0.5), print h and recurse. If tails, print t and stop.

Create a thunk with flip-fn that takes pc as parameter. Pass thunk to child. Child extracts flip-fn from parent-code and calls (flip-fn parent-code).
```

### Root Solution

```clojure
(do
  (def thought "Create thunk with flip-fn that takes pc parameter for recursion")
  (def flip-thunk '(do
                     (defn flip-fn [pc]
                       (if (> (rand) 0.5)
                         (cat "h" (llm pc))
                         "t"))))
  (def return (llm flip-thunk)))
```

### Child Solution

```clojure
(do
  (def flip-fn (extract [parent-code flip-fn]))
  (def return (flip-fn parent-code)))
```

Children extract the function from the parent's thunk rather than redefining it.

## Key Concepts

- **Randomness**: `(rand)` returns 0.0-1.0
- **Recursion via prefix**: `(llm prefix)` passes the same prompt to child (each child rewrites code)
- **Recursion via extract**: `(llm thunk)` passes code; child extracts `(extract [parent-code fn-name])`
- **Termination**: Base case (tails) stops the recursion

## Extract Pattern Details

When root passes a thunk to `llm`, the child receives `parent-code` bound to that thunk:

1. Root: `(def thunk '(do (defn f [pc] ...))) (llm thunk)`
2. Child env: `{parent-code: <thunk>}`
3. Child: `(extract [parent-code f])` returns the function
4. Child calls `(f parent-code)` to recurse with same thunk

This pattern enables code reuse across LLM calls - define once, extract many times.

## Notes

- Output length follows geometric distribution
- Average depth: 2 calls (p=0.5 for tails)
- Recommended model: Sonnet (handles recursion and extract pattern well)
