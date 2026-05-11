# Telephone

`telephone.spl` demonstrates a sequential relay loop. The parent starts with one sentence, then asks eight fresh model calls to rewrite the latest wording while preserving meaning.

## Run It

```bash
bin/spell -e telephone -d 30
bin/spell -v -e telephone -d 30
```

## Prompt

```text
Return an 8-step telephone relay report. Start with: "The museum closes at five because the winter storm is approaching." Write a deterministic game loop over relay numbers 1 through 8. At iteration k, pass the message from relay k-1 to a fresh !llm-self relay k with a prompt asking it to rewrite the message while preserving meaning. Bind the returned wording as the message for the next iteration. When the loop completes, return the initial wording, each relay wording, and the final wording. Do not rewrite relay messages yourself and do not use agents/.
```

## What To Expect

The result should include the initial sentence, each relay's rewritten sentence, and the final wording after relay 8. The important behavior is that each step uses a fresh `!llm-self` call and feeds that returned wording into the next step.

## Concepts

- A parent program can bind each child result and pass it forward.
- Fresh self-calls avoid local simulation of the relay.
- A fixed loop count makes a multi-call example predictable to inspect.
