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
Prompt: (quine completion (eval (do (quine prompt "Return Hello concatenated with child returning World")
Response: (def thought "delegate World to child") (cat "Hello" (llm-self "Return World"))))

  === LLM Call (depth 1) ===
  Prompt: (quine completion (eval (do (quine prompt "Return World")
  Response: "World")))

Result: HelloWorld
```

## Key Concepts

- **Delegation**: `(llm-self "task")` calls a child LLM
- **Concatenation**: `(cat str1 str2)` joins strings
- **Implicit return**: The last expression in the `do` block is the return value
