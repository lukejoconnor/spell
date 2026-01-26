# spell

A domain-specific language for LLM self-orchestration, implemented as a Lisp dialect in Clojure.

## Core Idea

Instead of an external harness controlling an agent's execution loop, the LLM writes its own execution graph. The model becomes a metaprogrammer of its own execution—deciding what recursive calls to make, how to branch, and what context to pass forward.

## Key Semantic Concepts

### Environment Threading
`spell-eval` takes env in, returns env out. The LLM has perfect knowledge of its evaluation environment because **the environment of a program is almost exactly the program itself**.

### Extraction and Expansion
- **Extract:** Retrieves values from nested thunks via a path (list of symbols)
- **Expand:** Substitutes free variables so thunks can be passed to children
- Thunks passed to children must be backtick-quoted; `expand` inserts `,` (unquote) for external symbols
- `llm` function auto-expands thunks via macro

### Hooks
Three types:
1. **Undisclosed context hooks** - prepend bindings child can't read but can pass on (progressive disclosure, closures)
2. **Return hooks** - intercept/transform child return value (checker pattern, format validation)
3. **Recursive hooks** - apply to child and all descendants (global constants, tool interception)

See `semantics-clarification` notebook entry for details.

## Key Files

| Path | Description |
|------|-------------|
| `writeup/manuscript.md` | Main writeup (title: "Agent self-orchestration with Spell") |
| `writeup/spec.md` | Language specification |
| `writeup/spell-literature-review.md` | Literature review positioning Spell |
| `src/spell/core.clj` | Core interpreter implementation |
| `test/spell/core_test.clj` | Test suite (217 assertions) |
| `deps.edn` | Clojure project config |

## Current Status

Core interpreter complete (21 tests, 217 assertions):
- `spell-eval` with environment threading
- Special forms: `def`, `do`, `if`, `let`, `fn`, `defn`, `cond`, `and`, `or`, `quote`, `extract`
- `extract` retrieves bindings from thunks; `expand` substitutes free variables
- `llm` primitive for recursive calls, binds `parent-code` when passed a thunk
- CLI with `-t` (test), `-m` (model), `-v` (verbose) flags

**Replicating function pattern verified:** Root defines function in minimal thunk, children extract with `(extract [parent-code fn-name])` and call it. See `examples/coin-flip.md` for detailed example. Note: `completion` is bound to full code, but extracting from full completions re-runs logic; use minimal thunks instead.

**Next priorities:**
- KV cache section in manuscript (incomplete)
- Hooks implementation (undisclosed context, return hooks, recursive hooks)

**Key insight:** Fresh `spell-eval` calls have no access to parent env. For code reuse across LLM calls, pass thunks and use `extract`. The `llm` function auto-binds `parent-code` when given a thunk.

## System Prompt Best Practices

When editing `src/spell/prompt.clj`:
- Use positive instructions ("bind to return") over negative ("don't use println")
- Show examples of correct behavior rather than warning against mistakes
- Avoid emphatic language (IMPORTANT, DO NOT, NEVER, MUST)
- State facts plainly; trust the model to follow clear instructions

When adding a feature the AI can use (builtins, tools, etc.), always update the system prompt to document it. The LLM can only use what it knows about.

## Notebook

See `notebook/INDEX.md` for work log.
