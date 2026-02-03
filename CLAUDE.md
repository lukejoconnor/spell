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

### Quine (Self-Referential Code)
`(quine name body)` binds `name` to the entire `(quine name body)` form as data, then evaluates `body`. The name binding is available inside `body`, giving the program access to its own source code. Used in the default NL prefix to provide the `completion` binding — the program's own source as data.

### Uneval (Legacy Self-Reference)
`(uneval 'sym)` returns the raw source expression of `sym`'s definition while it's being evaluated. Solves the circular reference problem where a binding needs access to its own code. Returns the expression directly (no `(quote ...)` wrapper), so `(pr-str (uneval 'x))` faithfully reconstructs the source. Largely superseded by `quine`.

### Prompt-as-Prefix Semantics
The `llm` function uses the prompt string as both the user message and the assistant prefix. The LLM's response is concatenated with the prefix, then the full string is parsed and evaluated. This means the prompt IS the beginning of the program — the LLM continues writing code from where the prompt left off.

### Hooks
Quoted macros (code→code transformers) applied to LLM completions before evaluation. Three types:
1. **Undisclosed context hooks** — prepend bindings child can't read but can pass on
2. **Return hooks** — intercept/transform child return value
3. **Recursive hooks** — `(recurse hook)` makes hooks self-propagating to all descendants

See `hooks-implementation` notebook entry for details.

### Make-LLM (Agent Factory)
`(make-llm {:tools [...] :llms {...} :model "..."})` creates llm functions with configurable tool sets, agent access, and model override. System prompt is auto-generated from the same metadata that configures runtime builtins. Single source of truth — no documentation drift. Every variant automatically provides `llm-self` for self-recursion without needing explicit var wiring.

### Prelude (Code Library Layer)
`(make-llm {:prelude [...]})` accepts a vector of Spell forms prepended before the parsed response forms. Prelude definitions are real Spell code — they land in the environment and compose with `expand`. Used for reusable patterns like error-handling wrappers. See `spl-lib/patterns.spl` for examples.

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
| `src/spell/llm.clj` | LLM engine (`-llm` core loop, `make-llm` factory) |
| `src/spell/parse.clj` | S-expression parser (read-all for multi-form input) |
| `src/spell/hooks.clj` | Hooks system (apply-hooks, prepend-hooks-to-llm, recurse) |
| `src/spell/tools.clj` | Tool implementations (bash, read-name, read-file, write-file, str-replace) + `default-tools` |
| `src/spell/cli.clj` | CLI with `-t`, `-m provider:model`, `-v`, `-d`, `-b` flags; accepts `.spl` files; auto-wraps NL prompts |
| `test/spell/*_test.clj` | 6 test files (core, eval, hooks, llm, parse, tools) |
| `dev/benchmark.clj` | Orchestration benchmark harness |
| `spl-lib/patterns.spl` | Reusable Spell patterns (try-bash, safe-llm, retry-llm) for `:prelude` |
| `docs/orchestration-benchmark.md` | Benchmark results writeup |
| `deps.edn` | Clojure project config |

## Current Status

Core interpreter and tooling complete:
- `spell-eval` with environment threading
- Special forms: `def`, `do`, `if`, `let`, `fn`, `defn`, `cond`, `and`, `or`, `quote`, `uneval`, `expand`, `future`, `quine`
- Core builtins: arithmetic, comparison, logic, list ops (`map`, `reduce`, `filter`, etc.), string ops (`subs`, `starts-with?`, `includes?`, `trim`, `cat`, `pr-str`), `spell-eval`, `llm-self`
- `llm` with prompt-as-prefix semantics, hooks, and prelude support
- `make-llm` factory for configurable agents (tools, sub-agents, model override, prelude)
- `llm-self` for automatic self-recursion (atom-based forward ref, available in all `make-llm` variants)
- Prelude mechanism for reusable Spell code libraries via `:prelude` in `make-llm`
- `future`/`await` for concurrent evaluation with env capture and dynamic binding conveyance
- Hooks system: `apply-hooks`, `prepend-hooks-to-llm`, `recurse`
- Tools: `bash`, `read-name`, `read-file`, `write-file`, `str-replace`
- Three LLM providers: Anthropic, OpenAI, Ollama — unified `-m provider:model` CLI syntax
- CLI with `-t` (test), `-m provider:model`, `-v` (verbose), `-d` (depth limit), `-b` (budget) flags; accepts `.spl` files
- CLI auto-wraps natural-language prompts into code prefixes
- Implicit return values (last expression)
- Token/cost tracking via `*usage*` dynamic var (accumulated across recursive calls, printed with `-v`)
- Budget limit via `*budget*` dynamic var (halts execution when cumulative cost exceeds threshold)
- Pattern library (`spl-lib/patterns.spl`): try-bash, safe-llm, retry-llm (TODO: redesign for exception semantics)
- Orchestration benchmark harness (`dev/benchmark.clj`) with pilot results in `docs/`

**Next priorities** (see `notebook/TODO.md`):
- SWE-bench harness (#14)
- Expand orchestration benchmark with forcing prompts (#32)
- MCP support (#30)
- Globals mechanism for large data sharing (#29)

**TODOs from scaffolding removal:**
- patterns.spl redesign: `safe-llm`/`retry-llm` need exception-handling semantics; `try-bash` needs code-prefix prompts

**Key insight:** The `llm` function uses prompt-as-prefix semantics — the prompt string is sent as both the user message and the assistant prefix, so the response continues the prompt as code. Natural-language prompts are wrapped in a `(quine completion (spell-eval (do ...)))` preamble, giving the program access to its own source as data via the `completion` binding. The `spell-eval` builtin auto-expands free variables from the caller's env before evaluating in a fresh env `{}`. For behavior propagation across generations, use recursive hooks.

**Architecture:** The LLM engine is a simple loop in `-llm`: call the LLM provider, concatenate prefix + response, parse with `read-all`, prepend prelude forms, apply hooks, then `spell-eval`. `make-llm` constructs the configuration (builtins, system prompt, call function) and returns the `llm` function.

## System Prompt Best Practices

When editing `src/spell/prompt.clj`:
- Use positive instructions ("the value of your program is the last expression") over negative ("don't use println")
- Show examples of correct behavior rather than warning against mistakes
- Avoid emphatic language (IMPORTANT, DO NOT, NEVER, MUST)
- State facts plainly; trust the model to follow clear instructions

When adding a feature the AI can use (builtins, tools, etc.), always update the system prompt to document it. The LLM can only use what it knows about. The system prompt is now metadata-driven via `generate-system-prompt` — tool docs come from `:doc` fields in tool metadata maps.

## Notebook

See `notebook/INDEX.md` for work log.
