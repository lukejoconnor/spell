# Coin Flip

`coin-flip.spl` demonstrates recursive self-calls. Each model call flips a coin. Heads delegates the same prompt to a child and prepends `h`; tails returns `t` and stops the chain.

## Run It

```bash
bin/spell -e coin-flip -d 20
bin/spell -v -e coin-flip -d 20
```

## Prompt

```text
Flip a coin programmatically. If it lands heads, pass this exact prompt onto a child LLM, using its binding, so that they can do the same thing; prepend "h" to the child's answer. If it lands tails, return "t".
```

## What To Expect

A typical result is a string such as `t`, `ht`, or `hhht`. Longer strings mean more heads before the first tails. The depth cap keeps accidental long recursive runs bounded.

## Concepts

- `rand-int` can drive ordinary program control flow.
- `!llm-self` can recurse by passing the current prompt to another model call.
- Depth limits are useful for recursive examples.
