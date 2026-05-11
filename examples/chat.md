# Chat

`chat.spl` starts an interactive conversation through Spell's agent communication namespace. It is useful when you want to exercise the runtime's user-facing conversation path.

## Run It

```bash
bin/spell -e chat
bin/spell -v -e chat
```

## Prompt

```text
Start a friendly conversation with the user via the comms system (see agents/ namespace). The user might be using this conversation to test new features of Spell.
```

## What To Expect

The model should create a conversational flow that uses the agent communication tools instead of returning a single static answer.

## Concepts

- The `agents` namespace supports interactive message exchange.
- Interactive examples may wait for user input depending on the generated program and terminal environment.
