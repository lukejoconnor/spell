# Hello World

`hello-world.spl` is the smallest bundled delegation example. The parent model asks a child model for `World`, then combines that result with `Hello`.

## Run It

```bash
bin/spell -e hello-world
bin/spell -v -e hello-world
```

## Prompt

```text
Return Hello concatenated with child returning World
```

## What To Expect

The model should produce a short Spell program that calls `!llm-self` for the child task and concatenates the child result with `Hello`. The final result is usually `HelloWorld` or the same words with spacing.

## Concepts

- `!llm-self` asks a fresh model call to solve a subtask.
- `cat` joins strings.
- The final expression in a Spell form is the returned value.
