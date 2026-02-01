# spell

A domain-specific language for LLM self-orchestration, implemented as a Lisp dialect in Clojure.

## Core Idea

Instead of an external harness controlling an agent's execution loop, the LLM writes its own execution graph. The model becomes a metaprogrammer of its own execution—deciding what recursive calls to make, how to branch, and what context to pass forward.

## Key Semantic Concepts

### Environment Threading
`spell-eval` takes env in, returns env out. The LLM has perfect knowledge of its evaluation environment because **the environment of a program is almost exactly the program itself**.

### Dynamic Scoping
Functions use dynamic scoping: `fn`/`defn` return source-form data (`{:spell/fn true :params [...] :body (...)}`), not opaque closures. At call time, the body is evaluated in the caller's environment merged with parameter bindings. This means functions are portable data — `expand` can reconstruct them as `(fn ...)` source, and they serialize naturally across LLM boundaries.

### Expansion
`llm` auto-expands thunks passed as prompts: free variables are substituted with their quoted values from the current environment. This ensures arguments to `llm` are always closed expressions (no unresolved names). The `expand` special form is also available for explicit use, but `llm` handles it automatically.

Expansion and dynamic scoping work together: `expand` substitutes function values as their source forms, so `(expand '(f 3))` where `f` was `(defn f [x] (* x x))` produces `((fn [x] (* x x)) 3)` — fully portable code.

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
`(make-llm {:tools [...] :llms {...} :model "..."})` creates llm functions with configurable tool sets, agent access, and model override. System prompt is auto-generated from the same metadata that configures runtime builtins. Single source of truth — no documentation drift. Every variant automatically provides `llm-self` for self-recursion without needing explicit var wiring.

### Prelude (Code Library Layer)
`(make-llm {:prelude [...]})` accepts a vector of Spell forms prepended in an outer `do` block before the caller's program. Prelude definitions are real Spell code — they land in the environment, compose with `expand`, and propagate via `completion`. Used for reusable patterns like error-handling wrappers. See `spl-lib/patterns.spl` for examples.

### Concurrency (Future/Await)
`(future expr)` evaluates `expr` in a new thread, capturing the current env. `(await f)` blocks until the future completes and returns its value. Dynamic bindings (`*usage*`, `*builtins*`, etc.) are conveyed via `bound-fn`. Futures are isolated: env updates don't leak back to the parent. Enables parallel LLM calls.

### Implicit Returns
Programs return the value of their last expression (standard Lisp semantics). No explicit `(def return ...)` needed.

## Key Files

| Path | Description |
|------|-------------|
| `writeup/language-design.md` | Main writeup (title: "Agent self-orchestration with Spell") |
| `writeup/spell-literature-review.md` | Literature review positioning Spell |
| `src/spell/eval.clj` | Evaluator (`spell-eval`, `expand`, builtins, dynamic scoping, `future`) |
| `src/spell/core.clj` | Top-level wiring (default `llm`, root builtin registration, re-exports) |
| `src/spell/prompt.clj` | System prompt (preamble/postamble + metadata-driven generation) |
| `src/spell/provider.clj` | LLM providers (Anthropic, OpenAI, Ollama, Dummy), `*provider*`, `llm-call`, token/cost/budget tracking |
| `src/spell/llm.clj` | LLM engine (llm-impl, eval-forms, make-call-now, build-fresh-prefix, make-llm) |
| `src/spell/parse.clj` | S-expression parser (read-all for multi-form input) |
| `src/spell/hooks.clj` | Hooks system (apply-hooks, prepend-hooks-to-llm, recurse) |
| `src/spell/tools.clj` | Tool implementations (bash, read-name, read-file, write-file, str-replace) + `default-tools` |
| `src/spell/cli.clj` | CLI with `-t`, `-m provider:model`, `-v`, `-d`, `-b` flags; accepts `.spl` files |
| `test/spell/*_test.clj` | 6 test files (core, eval, hooks, llm, parse, tools) |
| `dev/benchmark.clj` | Orchestration benchmark harness |
| `spl-lib/patterns.spl` | Reusable Spell patterns (try-bash, safe-llm, retry-llm) for `:prelude` |
| `docs/orchestration-benchmark.md` | Benchmark results writeup |
| `deps.edn` | Clojure project config |

## Current Status

Core interpreter and tooling complete (86 tests / 424 assertions across 6 test files):
- `spell-eval` with environment threading
- Special forms: `def`, `do`, `if`, `let`, `fn`, `defn`, `cond`, `and`, `or`, `quote`, `uneval`, `expand`, `call-now`, `future`
- `llm` with auto-expansion, hooks, prefix/parent-code binding, and assistant prefill
- `call-now` for tool-use continuations with KV cache preservation
- `make-llm` factory for configurable agents (tools, sub-agents, model override, prelude)
- `llm-self` for automatic self-recursion (atom-based forward ref, available in all `make-llm` variants)
- Prelude mechanism for reusable Spell code libraries via `:prelude` in `make-llm`
- `future`/`await` for concurrent evaluation with env capture and dynamic binding conveyance
- Hooks system: `apply-hooks`, `prepend-hooks-to-llm`, `recurse`
- Tools: `bash`, `read-name`, `read-file`, `write-file`, `str-replace`
- Three LLM providers: Anthropic, OpenAI, Ollama — unified `-m provider:model` CLI syntax
- CLI with `-t` (test), `-m provider:model`, `-v` (verbose), `-d` (depth limit), `-b` (budget) flags; accepts `.spl` files
- `completion` binding via `uneval` (self-referential program text)
- Implicit return values (last expression)
- Token/cost tracking via `*usage*` dynamic var (accumulated across recursive calls, printed with `-v`)
- Budget limit via `*budget*` dynamic var (halts execution when cumulative cost exceeds threshold)
- Pattern library (`spl-lib/patterns.spl`): try-bash, safe-llm, retry-llm
- Orchestration benchmark harness (`dev/benchmark.clj`) with pilot results in `docs/`

**Next priorities** (see `notebook/TODO.md`):
- SWE-bench harness (#14)
- Expand orchestration benchmark with forcing prompts (#32)
- MCP support (#30)
- Globals mechanism for large data sharing (#29)

**Key insight:** Fresh `spell-eval` calls have no access to parent env. The `llm` function auto-expands free variables in prompts and binds `parent-code`. For tool use within a single generation, use `call-now`. For behavior propagation across generations, use recursive hooks.

**Architecture:** The LLM engine has a layered design: `eval-forms` is the shared eval path (hooks, call-now, spell-eval) used by both fresh calls (`llm-impl`) and continuations (`make-call-now`). `build-fresh-prefix` constructs the `(def interior (do ...))` scaffolding; continuations extend the existing completion prefix directly. Both paths converge on `eval-forms`.

## System Prompt Best Practices

When editing `src/spell/prompt.clj`:
- Use positive instructions ("the value of your program is the last expression") over negative ("don't use println")
- Show examples of correct behavior rather than warning against mistakes
- Avoid emphatic language (IMPORTANT, DO NOT, NEVER, MUST)
- State facts plainly; trust the model to follow clear instructions

When adding a feature the AI can use (builtins, tools, etc.), always update the system prompt to document it. The LLM can only use what it knows about. The system prompt is now metadata-driven via `generate-system-prompt` — tool docs come from `:doc` fields in tool metadata maps.

## Notebook

See `notebook/INDEX.md` for work log.
