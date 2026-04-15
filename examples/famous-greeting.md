# Famous Person Greeting

Demonstrates tool use combined with LLM delegation: read a name from a file, then delegate to a child LLM with context.

## Setup

The file `examples/data/name.txt` contains a first name (e.g., "Ada").

## Prompt

```
Read a first name from examples/data/name.txt, then greet a famous person with that first name.
```

## Expected Behavior

1. **LLM 1** reads "Ada" from examples/data/name.txt using `!call-now` + `io/read-file`
2. **LLM 1** delegates with context: `(!llm-self (wrap-cat prompt " Name: " name))`
3. **LLM 2** receives the prompt with the name appended
4. **LLM 2** identifies Ada Lovelace and returns a greeting

## Key Concepts

- **Tool use**: `(!call-now name (io/read-file "examples/data/name.txt"))` reads from a file as an extension
- **Context passing**: `(wrap-cat prompt " Name: " name)` builds a child prompt
- **Two-step reasoning**: Parent reads data, child does creative work

## Test Names

| Input | Expected Famous Person |
|-------|----------------------|
| Ada | Ada Lovelace |
| Albert | Albert Einstein |
| Isaac | Isaac Newton |
| Marie | Marie Curie |
| Nikola | Nikola Tesla |

## Notes

- Recommended model: Sonnet (Haiku struggles with this pattern)
