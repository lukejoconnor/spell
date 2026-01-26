# Hello World (2-Step Delegation)

The classic "Hello World" implemented with LLM delegation.

## Prompt

```
Return Hello concatenated with child returning World
```

## Expected Behavior

1. **LLM 1** receives the task and delegates "Return World" to a child
2. **LLM 2** returns "World"
3. **LLM 1** concatenates "Hello" + "World" = "HelloWorld"

## Example Output

```
=== LLM Call (depth 0) ===
Prompt: (do (def prefix "Return Hello concatenated with child returning World") (def response
Response: (do (def thought "delegate World to child") (def return (cat "Hello" (llm "Return World"))))

  === LLM Call (depth 1) ===
  Prompt: "Return World"
  Response: (do (def thought "return World") (def return "World"))

Result: HelloWorld
```

## Key Concepts

- **Delegation**: `(llm "task")` calls a child LLM
- **Concatenation**: `(cat str1 str2)` joins strings
- **Return value**: Child's `return` becomes the value of `(llm ...)`
