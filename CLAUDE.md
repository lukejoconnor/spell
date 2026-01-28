# spell

A domain-specific language for LLM self-orchestration, implemented as a Lisp dialect in Clojure.

## Core Idea

Instead of an external harness controlling an agent's execution loop, the LLM writes its own execution graph. The model becomes a metaprogrammer of its own execution—deciding what recursive calls to make, how to branch, and what context to pass forward.

## Key Semantic Concepts

### Environment Threading
`spell-eval` takes env in, returns env out. The LLM has perfect knowledge of its evaluation environment because **the environment of a program is almost exactly the program itself**.

### Expansion (Auto-Closure)
`llm` auto-expands thunks passed as prompts: free variables are substituted with their quoted values from the current environment. This ensures arguments to `llm` are always closed expressions (no unresolved names). The `expand` special form is also available for explicit use, but `llm` handles it automatically.

`extract` was removed — with call-now's flat evaluation model, code reuse comes naturally from prefix construction and hooks, not from extracting bindings out of nested thunks.

### Uneval (Self-Referential Code)
`(uneval 'sym)` returns the quoted definition of `sym` while it's being evaluated. Solves the circular reference problem where a binding needs access to its own code. Used internally to bind `completion` (the full program text) without special injection.

### Call-Now (Tool-Use Continuations)
`(call-now {:name (tool-expr)})` evaluates tool expressions, binds results, and continues the LLM's generation as a single continuous completion. Preserves KV cache by appending `(def name value)` forms after the current completion text. Enables the standard agent loop: generate code → call tool → continue with results.

### Hooks
Quoted macros (code→code transformers) applied to LLM completions before evaluation. Three types:
1. **Undisclosed context hooks** — prepend bindings child can't read but can pass on
2. **Return hooks** — intercept/transform child return value
3. **Recursive hooks** — `(recurse hook)` makes hooks self-propagating to all descendants

See `hooks-implementation` notebook entry for details.

### Make-LLM (Agent Factory)
`(make-llm {:tools [...] :llms {...} :model "..."})` creates llm functions with configurable tool sets, agent access, and model override. System prompt is auto-generated from the same metadata that configures runtime builtins. Single source of truth — no documentation drift.

### Implicit Returns
Programs return the value of their last expression (standard Lisp semantics). No explicit `(def return ...)` needed.

## Key Files

| Path | Description |
|------|-------------|
| `writeup/manuscript.md` | Main writeup (title: "Agent self-orchestration with Spell") |
| `writeup/spec.md` | Language specification |
| `writeup/spell-literature-review.md` | Literature review positioning Spell |
| `src/spell/core.clj` | Core interpreter (`spell-eval`, `llm`, `make-llm`, hooks, special forms) |
| `src/spell/prompt.clj` | System prompt (preamble/postamble + metadata-driven generation) |
| `src/spell/llm.clj` | LLM provider protocol (Anthropic, Dummy implementations) |
| `src/spell/tools.clj` | Tool implementations (read-file, write-file, str-replace) |
| `src/spell/cli.clj` | CLI with `-t` (test), `-m` (model), `-v` (verbose) flags |
| `test/spell/core_test.clj` | Core test suite |
| `test/spell/tools_test.clj` | File tool tests |
| `test/spell/llm_test.clj` | LLM integration tests (using dummy provider) |
| `deps.edn` | Clojure project config |

## Current Status

Core interpreter and tooling complete (57 deftests across 3 test files):
- `spell-eval` with environment threading
- Special forms: `def`, `do`, `if`, `let`, `fn`, `defn`, `cond`, `and`, `or`, `quote`, `uneval`, `expand`, `call-now`
- `llm` with auto-expansion, hooks, and prefix/parent-code binding
- `call-now` for tool-use continuations with KV cache preservation
- `make-llm` factory for configurable agents (tools, sub-agents, model override)
- Hooks system: `apply-hooks`, `prepend-hooks-to-llm`, `recurse`
- Tools: `bash`, `read-name`, `read-file`, `write-file`, `str-replace`
- CLI with `-t` (test), `-m` (model), `-v` (verbose), `-d` (depth limit) flags
- `completion` binding via `uneval` (self-referential program text)
- Implicit return values (last expression)

**Next priorities** (see `notebook/TODO.md`):
- File tools integration into default-tools (#18)
- Token/cost tracking (#12)
- SWE-bench harness (#14)
- Concurrent LLM calls (#16)
- Manuscript update with implementation learnings (#9)

**Key insight:** Fresh `spell-eval` calls have no access to parent env. The `llm` function auto-expands free variables in prompts and binds `parent-code`. For tool use within a single generation, use `call-now`. For behavior propagation across generations, use recursive hooks.

## System Prompt Best Practices

When editing `src/spell/prompt.clj`:
- Use positive instructions ("the value of your program is the last expression") over negative ("don't use println")
- Show examples of correct behavior rather than warning against mistakes
- Avoid emphatic language (IMPORTANT, DO NOT, NEVER, MUST)
- State facts plainly; trust the model to follow clear instructions

When adding a feature the AI can use (builtins, tools, etc.), always update the system prompt to document it. The LLM can only use what it knows about. The system prompt is now metadata-driven via `generate-system-prompt` — tool docs come from `:doc` fields in tool metadata maps.

## Notebook

See `notebook/INDEX.md` for work log.
