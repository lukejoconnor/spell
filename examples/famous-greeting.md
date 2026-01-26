# Famous Person Greeting

Demonstrates tool use combined with LLM delegation: read a name from a file, then delegate to a child LLM with context.

## Setup

Create `name.txt` with a first name:
```bash
echo "Ada" > name.txt
```

## Prompt

```
Greet a famous person with the first name from name.txt. Use (llm (cat prefix " Name: " (read-name))) to delegate.
```

## Expected Behavior

1. **LLM 1** reads "Ada" from name.txt using `(read-name)`
2. **LLM 1** delegates with context: `(llm (cat prefix " Name: " (read-name)))`
3. **LLM 2** receives: "Greet a famous person... Name: Ada"
4. **LLM 2** identifies Ada Lovelace and returns a greeting

## Example Output

```
=== LLM Call (depth 0) ===
Prompt: (do (def prefix "Greet a famous person...") (def response
Response: (do (def thought "delegate with name context")
              (def return (llm (cat prefix " Name: " (read-name)))))

  === LLM Call (depth 1) ===
  Prompt: "Greet a famous person... Name: Ada"
  Response: (do (def thought "Ada Lovelace - first programmer")
                (def return "Hello, Ada Lovelace! It's an honor to meet the world's first computer programmer."))

Result: Hello, Ada Lovelace! It's an honor to meet the world's first computer programmer.
```

## Test Names

| Input | Expected Famous Person |
|-------|----------------------|
| Ada | Ada Lovelace |
| Albert | Albert Einstein |
| Isaac | Isaac Newton |
| Marie | Marie Curie |
| Nikola | Nikola Tesla |

## Key Concepts

- **Tool use**: `(read-name)` reads from name.txt
- **Context passing**: `(cat prefix " Name: " (read-name))` builds child prompt
- **Two-step reasoning**: Parent reads data, child does creative work

## Notes

- Recommended model: Sonnet (Haiku struggles with this pattern)
- The `(llm (cat prefix ...))` pattern passes parent's task + extra context to child
