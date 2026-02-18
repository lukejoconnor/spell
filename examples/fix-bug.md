# Fix Bug Example

A realistic coding task demonstrating multi-step reasoning: the agent reads a file, runs tests, identifies the bug via delegation, and fixes it.

## Setup

The buggy file is at `examples/buggy/calculator.py`. It contains a bug in the `average` function that causes tests to fail.

## Run

```bash
spell examples/fix-bug.spl
```

## How it works

This example demonstrates **data-returning delegation**:

1. **Outer LLM** runs tests via `call-now` + `io/bash` -> sees `FAIL: average([1, 2, 3, 4, 5]) = 2.0, expected 3.0`
2. **Outer LLM** reads the file content via `call-now` + `io/read-file`
3. **Outer LLM** delegates to **child LLM** via `(llm-self (wrap-cat "Test failure: " test-output "\n\nSource:\n" file-source "\n\n..."))`
4. **Child LLM** analyzes the failure and returns a **data structure**: `{:old "..." :new "..."}`
5. **Outer LLM** extracts `:old` and `:new` from the result and calls `io/str-replace`
6. **Outer LLM** verifies the fix by running tests again

## Key pattern: Child returns data, not code

The child LLM returns a map like:
```clojure
{:old "    return total / len(numbers) - 1\n"
 :new "    return total / len(numbers)\n"}
```

The outer LLM then uses this data:
```clojure
(def old (get fix-result :old))
(def new (get fix-result :new))
'(call-now result (io/str-replace path old new))
```

This pattern is more robust than having the child return executable code because:
- The child doesn't need to know about `io/str-replace` or how to call it
- The outer LLM controls what actions are taken
- Error handling is centralized in the outer LLM

## Expected output

```
{:before "FAIL: average([1, 2, 3, 4, 5]) = 2.0, expected 3.0",
 :fix {:llm {:old "    return total / len(numbers) - 1\n", :new "    return total / len(numbers)\n"},
       :apply {:ok "examples/buggy/calculator.py"}},
 :after "All tests passed!"}
```
