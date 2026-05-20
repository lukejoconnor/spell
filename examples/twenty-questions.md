# Twenty Questions

`twenty-questions.spl` asks the top-level model to orchestrate a bounded guessing game. The host keeps the secret animal, while a worker asks questions or makes guesses from the public transcript.

## Run It

```bash
bin/spell -e twenty-questions -d 40
bin/spell -v -e twenty-questions -d 40
```

## Prompt

```text
Play '20 questions'. You are the orchestrator who picks a secret animal. Create a WORKER
who does NOT know the secret and must guess by asking questions. Start
by telling it that the answer is an animal and wait for its first guess.
Then, communicate using !ask or !reply-ask.
Loop until the worker guesses correctly or runs out of guesses.
Use a guess limit of 8, not 20.
```

## What To Expect

The returned transcript should show a hidden animal, worker questions or guesses, host answers, and a stop condition after a correct guess or eight turns.

## Concepts

- Separate prompts can give different model calls different information.
- Agent communication supports multi-turn interaction between roles.
- A bounded loop keeps an open-ended game from running indefinitely.
