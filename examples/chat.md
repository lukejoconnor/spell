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


## Terminal input

When the CLI is attached to a TTY, Spell uses JLine. Ordinary Enter submits one
logical message. Bracketed multiline paste is inserted into the current editor
buffer and remains one message; press Enter after the paste to submit it.

Use Alt+Enter to insert a newline without submitting. Spell also binds common
extended-key sequences for Shift+Enter, but many terminals send exactly the
same sequence for Shift+Enter and Enter. In those terminals Shift+Enter cannot
be detected separately; use Alt+Enter or configure the terminal to emit a
distinct Shift+Enter sequence.

Ctrl-C cancels the current input buffer. Ctrl-D ends terminal input. Agent output
that arrives while editing is printed above the active prompt and the input
buffer is redisplayed.
