# spell

A domain-specific language for LLM self-orchestration.

## Core Idea

Instead of an external harness controlling an agent's execution loop, the LLM writes its own execution graph. The model becomes a metaprogrammer of its own execution—deciding what recursive calls to make, how to branch, and what context to pass forward.

## Language Concepts (Simplified Initial Version)

The initial implementation omits functions and patterns. Core elements:

| Concept | Syntax | Description |
|---------|--------|-------------|
| Completion | `<completion>...</completion>` | Fundamental unit: prefix + response |
| Prefix | `<prefix>...</prefix>` | Input: system-prompt, interpreter-prompt, caller-prompt |
| Response | `<response>...</response>` | Output: bindings + return expression |
| Binding | `<name>Expr</name>` | Named intermediate result |
| Evaluate | `$ref` | Get the *value* of a binding |
| Quote | `@ref` | Get the *literal text* of a binding |
| Tag navigation | `$prefix.caller-prompt` | Dot notation for nested access |
| Return | `<return>Expr</return>` | Final expression (exactly one per response) |
| LLM call | `llm(caller-prompt)` | Spawn a child agent |

## Key Insight: Evaluate vs Quote

- `$ref` — evaluates the expression, returns its computed value
- `@ref` — returns the raw text between the tags (for passing structure to children)

## Example

```xml
<response>
  <search-result>search($prefix.caller-prompt.query)</search-result>
  <return>llm(Based on: $search-result, answer the question.)</return>
</response>
```

## Current Status

Starting implementation. Building interpreter for the simplified language (no functions, no patterns).

## Key Files

| Path | Description |
|------|-------------|
| `writeup/spec.md` | Full language specification |

## Notebook

This project uses a separate notebook repository for analysis logs. See `notebook/INDEX.md` for a summary of past work.
