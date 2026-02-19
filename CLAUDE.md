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

### Prompt-as-Prefix Semantics
The `llm` function uses the prompt string as both the user message and the assistant prefix. The LLM's response is concatenated with the prefix, then the full string is parsed and evaluated. This means the prompt IS the beginning of the program — the LLM continues writing code from where the prompt left off.

### Completion Wrapper and Trailing Expression Pattern
The **completion wrapper** `(quine completion (eval (do ...)))` is the standard prefix for LLM-generated programs. It creates two-level evaluation: the `do` block evaluates all expressions and returns the last value; the outer `eval` (`spell-eval`) then evaluates this returned value a second time.

The **trailing expression** is the last expression in the completion wrapper's `do` block. Because of double evaluation, the trailing expression is special — it is the only expression whose return value gets evaluated again by the outer `eval`.

The **trailing expression pattern** is the practice of quoting the trailing expression (e.g., `'(extend completion)`, `'(call-now ...)`, `'(agents/ask target)`). This works because:

- **Quoted trailing expressions execute via double evaluation**: `'(llm ...)` as the trailing expression returns a list from `do`. The outer `eval` evaluates this list, actually calling `llm`. Same for `'(recv)`, `'(call-now ...)`, etc.
- **Extensions make previous trailing expressions inert**: When a new expression is appended (by a message or extension), the previously-quoted expression is no longer last. The quote makes it return data (discarded as an intermediate value). Only the new trailing expression is double-evaluated by the outer `eval`.
- **Context window visibility**: Even discarded intermediate expressions remain in the source code. The LLM reads them in its context window (via the `completion` binding or prefix text), even without programmatic bindings. In Spell, anything in the source code is visible to the LLM regardless of environment bindings.

### Think / Rethink / Extend (Context Pruning)
`think`, `rethink`, and `extend` are macros for managing chains of thought. `(think "label" body...)` marks a reasoning step — evaluates body, returns nil. `(rethink "label" body...)` replaces the previous sibling expression at the source level (pruning it when the completion is next extended). `(rethink N "label" body...)` prunes N previous siblings. `(extend completion)` prunes all rethought expressions from the completion and calls `llm-self` to continue with clean context.

Pruning is recursive (walks the full AST) and respects list structure: only argument-position siblings are prunable, never the operator. `call-now`, `print`, and `describe` also prune rethinks automatically when extending (they use `prune-and-reopen` internally).

The `prune-and-reopen` builtin destructures a quine form, runs `prune-rethinks` on its AST, and rebuilds an open prefix string. Unlike `reopen` (which strips exactly 3 trailing parens), `prune-and-reopen` rebuilds the prefix from the pruned AST directly.

### Namespaces (Qualified Symbol Access)
Functions are organized into namespaces with two categories:

**Core namespaces** (always available, hardcoded in variant-builtins):
- `strings` — string manipulation and regex (matches clojure.string)
- `math` — mathematical functions (matches Java's Math/)
- `builtins` — docs-only reference for core builtins by category

**Effect namespaces** (optional, gated through eval's double evaluation):
- `io` — file/process I/O (bash, read-file, write-file, etc.)
- `globals` — shared state across agents
- `agents` — inter-agent communication (spawn, ask, send)
- `futures` — parallel computation (pmap, await-all)
- `patterns` — reusable orchestration patterns (check-result, clean-prompt, explore)

Core namespaces are always resolved — no configuration needed. Effect namespaces are passed via `:namespaces` in `make-llm` and are only available inside the `eval` builtin (trailing expression). `make-llm` filters core namespace names from the `:namespaces` input automatically.

Access with qualified symbols — no import needed:

```clojure
(strings/trim "  hello  ")   ; core — always available
(io/bash "ls -la")           ; effect — only in trailing expression
```

Namespace structure (simple maps with `:guide`, `:docs`, optional `:detail`, and items):
```clojure
{:guide "MYNS — terse overview for (describe myns)"
 :docs {:bash "Short one-liner for system prompt listing"}
 :detail {:bash "Detailed multi-line doc with usage examples for (describe myns :bash)"}
 :bash run-bash}
```

`describe` lookup: `(describe ns)` returns `:guide` (terse overview). `(describe ns :key)` checks `:detail` first, then `:docs`, then raw key. The `:docs` one-liners are used by `namespaces-section` for the system prompt listing; `:detail` provides expanded per-function documentation.

`make-llm` accepts a `:namespaces` map (effect namespaces only — core namespaces are automatic):
```clojure
(make-llm {:namespaces {'io io-ns 'patterns patterns-ns}
           :llm-var #'llm
           :model "..."})
```

`describe` is a Spell macro that produces an extension (like `call-now`). The child LLM sees the docs as a literal in its continuation:
```clojure
'(describe io)           ; extension: child sees terse namespace overview
'(describe io :bash)     ; extension: child sees detailed function doc
'(describe io :guide)    ; extension: child sees guide text (same as describe io)
```
`describe-fn` is the pure function underneath (available in builtins for direct access).

Qualified symbols work recursively: `outer/inner/item` looks up `:inner` in `outer`, then `:item` in that.

### Concurrency
Two concurrency patterns: **serial llm-self** (child inherits your handle, entire call tree is one logical agent) and **agents/spawn** (new handle, independent agent, communicates via `agents/ask`). `plet`/`futures/pmap`/`future` are for deterministic parallel computation only — never for LLM calls (they'd share the parent handle and contend over the box). This invariant guarantees deadlock freedom: same-handle trees are serial (can't self-deadlock), and cross-handle dependencies use `agents/ask` (which always wakes the target).

### Communication (agents/ namespace)
`agents/ask` enables request-reply message passing between concurrent agents. `(agents/ask target msg)` sends a message and blocks for reply; `(agents/ask target)` pokes target and blocks (no message); `(agents/ask [a b c])` multi-target ask — pokes all targets and blocks until all have sent, triggering a single extension with all quine bindings (reduces N turns to 1 for fan-out/fan-in). Every form of ask wakes the target, preventing deadlocks. `agents/send` sends a value to a target with auto-tagged sender. `agents/send-msg-fn` is low-level fire-and-forget. `agents/spawn` starts an agent in a background future. Handles are keywords (`:agent-42`) — self-evaluating, safe through serialization. `agents/parent-handle` lets spawned children find their parent automatically. `globals/` provides shared state visible to all agents (pre-initialized with `:roles` and `:tasks`).

### Implicit Returns
Programs return the value of their last expression (standard Lisp semantics). No explicit `(def return ...)` needed.

## Key Files

| Path | Description |
|------|-------------|
| `writeup/language-design.md` | Main writeup (title: "Agent self-orchestration with Spell") |
| `writeup/spell-literature-review.md` | Literature review positioning Spell |
| `src/spell/macros.clj` | Macro system (registry, `defspellmacro`, 26 macros incl. `defmacro` for user-defined macros, threading helpers, think/rethink pruning) |
| `src/spell/eval.clj` | Evaluator (`spell-eval`, `expand`, builtins, dynamic scoping, effect guard) |
| `src/spell/core.clj` | Top-level wiring (default `llm`, root builtin registration, re-exports) |
| `src/spell/provider.clj` | LLM providers (Anthropic, OpenAI, Ollama, Kimi, Dummy), `*provider*`, `llm-call`, token/cost/budget/retry tracking |
| `src/spell/llm.clj` | LLM engine (`-llm` API call, `make-llm` factory, `make-inbox-fn` eval pipeline, `compose-system-prompt`) |
| `prompts/minimal.txt` | Default system prompt for Spell agents |
| `src/spell/recovery.clj` | Error recovery (namespace symbol fixup, LLM-based recovery) |
| `src/spell/parse.clj` | S-expression parser (read-all for multi-form input) |
| `src/spell/comm.clj` | Communication layer (box execution primitive, registry, send/sleep/spawn, ask, agents-namespace, futures-namespace) |
| `src/spell/globals.clj` | Global shared state (globals/ namespace: get, set, update, pop) |
| `src/spell/io.clj` | I/O operations (bash, read-file, write-file, str-replace, replace-lines, sh, watch-send) |
| `src/spell/stdlib.clj` | Standard library namespaces (strings, math) and patterns definition |
| `src/spell/cli.clj` | CLI with `-t`, `-m`, `-a`, `-v`, `-d`, `-b` flags; accepts `.spl` files and `.agent.edn` agents |
| `src/spell/trace.clj` | Trace recording system for debugging LLM call trees |
| `test/spell/*_test.clj` | 8 test files (core, eval, llm, parse, stdlib, io, comm, globals, trace) |
| `dev/benchmark.clj` | Orchestration benchmark harness |
| `spl-lib/patterns.spl` | Reusable Spell patterns (check-result) |
| `src/spell/agent.clj` | Agent definition loader (.agent.edn files) |
| `agents/*.agent.edn` | Agent definition files |
| `docs/orchestration-benchmark.md` | Benchmark results writeup |
| `deps.edn` | Clojure project config |

## Current Status

Core interpreter and tooling complete (446 tests, 1713 assertions):
- `spell-eval` with environment threading
- Special forms (13): `quote`, `def`, `do`, `if`, `let`, `fn`/`fn*`, `expand`, `quine`, `loop`, `recur`, `for`, `try`
- Macros (26 via `defspellmacro`): `when`, `defn`, `and`, `or`, `cond`, `if-let`, `when-let`, `case`, `as->`, `cond->`, `cond->>`, `some->`, `some->>`, `call-now`, `print`, `describe`, `define`, `defmacro`, `compact`, `->`, `->>`, `future`, `plet`, `think`, `rethink`, `extend`
- Vector destructuring in `fn`/`defn`/`let` parameters: nested vectors, `&` rest, `:as`
- Core builtins: arithmetic, comparison, logic, list ops (`map`, `reduce`, `filter`, etc.), string ops (`cat`, `pr-str`), `spell-eval`, `llm-self`, `describe`, `throw`, `gensym`, `serialize`, `prune-and-reopen`
- Two-category namespace system: core namespaces (strings, math, builtins) always in variant-builtins; effect namespaces (io, globals, agents, futures, patterns) gated through eval's double evaluation
- Effect guard: `eval` builtin (agent-specific, not special form) merges effect namespaces and per-variant fns (`llm-self`, `llm`, `leaf-llm`) with pure builtins; effects only available in trailing expression via double evaluation
- `llm` with prompt-as-prefix semantics
- `make-llm` factory with namespace-based configuration; `compose-system-prompt` dynamically appends effect namespace docs to any system prompt (custom or default)
- Namespace system: qualified symbol access (`io/bash`, `strings/trim`) with recursive lookup
- Core namespaces: `strings` (string/regex), `math` (arithmetic/trig/number theory), `builtins` (docs-only reference)
- Effect namespaces: `io` (file/process), `agents/` (communication), `futures/` (parallel computation), `globals/` (shared state), `patterns` (orchestration patterns)
- `llm-self` for automatic self-recursion (atom-based forward ref, available in all `make-llm` variants)
- `future`/`await`/`plet` for deterministic parallel computation (core builtins); `futures/await-all`/`futures/pmap` in effect namespace
- Inter-agent communication via `agents/` namespace: `agents/spawn`, `agents/ask` (including multi-target `[a b c]`), `agents/send`, `agents/reply`, `agents/reply-ask`, `agents/spawn-ask`, `agents/register` (dormant agent with stored completion), `agents/current-handle`, `agents/parent-handle`, keyword handles
- Global shared state: `globals/` namespace (`get`, `set`, `update`, `pop`, `keys`, `wait-until`) for all-to-all coordination
- I/O tools: `bash`, `read-file`, `write-file`, `str-replace` (with `:all` flag for replace-all), `replace-lines` (supports multi-range edits), `sh`, `watch-send` (in `io` namespace, opt-in)
- LLM-based error recovery (opt-out by default): on evaluation failure, LLM generates fix re-evaluated from scratch
- Four LLM providers: Anthropic (with prompt caching), OpenAI, Ollama, Kimi (Moonshot AI) — unified `-m provider:model` CLI syntax
- No-prefill mode for OpenAI models; extended thinking support (Anthropic `extended_thinking`, OpenAI `reasoning_effort`)
- CLI with `-t` (test), `-m provider:model`, `-v` (verbose), `-d` (depth limit), `-b` (budget) flags; accepts `.spl` files and `.agent.edn` agents
- CLI auto-wraps natural-language prompts into code prefixes
- Implicit return values (last expression)
- API retry logic: `*retries*` dynamic var (default `[0 10]` — instant retry + 10s retry) for transient API failures (429, 5xx, network). Configurable per-agent via `:retries` in .agent.edn
- Token/cost tracking via `*usage*` dynamic var (accumulated across recursive calls, printed with `-v`)
- Budget limit via `*budget*` dynamic var (default $1.00, halts execution when cumulative cost exceeds threshold; `-b 0` for unlimited)
- Orchestration benchmark harness (`dev/benchmark.clj`) with pilot results in `docs/`

**Next priorities** (see `notebook/TODO.md`):
- MCP support (#30)
- Orchestration visualizations (#33)
- API-level error handling with retries (#64)
- Aider Polyglot / Exercism benchmark (#66)
- Revisit error recovery (#79)
- Consider demoting `for` to macro (#80)

**Key insight:** The `llm` function uses prompt-as-prefix semantics — the prompt string is sent as both the user message and the assistant prefix, so the response continues the prompt as code. Natural-language prompts are wrapped in the completion wrapper `(quine completion (spell-eval (do ...)))`, giving the program access to its own source as data via the `completion` binding. The `spell-eval` builtin auto-expands free variables from the caller's env before evaluating in a fresh env `{}`.

**Architecture:** 4-component design: (1) `spell-eval` — pure evaluator, (2) `eval` builtin — per-agent effectful evaluator via `make-inbox-fn` (closes over dangerous tools), (3) `box` — universal execution primitive in `comm.clj`; single point of interaction between local and global state, handles root detection via `(not= parent-handle handle)`, balance-parens, inbox drain, and lifecycle (notify-waiters, orphan-box), (4) `-llm` — thin wrapper that makes the API call and delivers to box. `make-llm` constructs the configuration and returns the `llm` function. Core namespaces (strings, math, builtins) are defined in `llm.clj` as `core-namespaces` and always merged into variant-builtins. Effect namespaces are passed via `:namespaces` and documented dynamically via `compose-system-prompt`.

## Development Principles

**No legacy compatibility code.** This is a nascent project. When changing APIs:
- Update the API directly — don't create `-legacy` variants
- Update all call sites in the same change
- Update tests to use the new API
- If external code breaks, that's acceptable — we're pre-1.0

**Avoid premature abstraction.** Don't add flexibility for hypothetical future needs. Add it when there's a concrete use case.

**Keep the codebase small.** Every line of code is a liability. Delete aggressively.

**Use `uv` for Python.** When running Python scripts (benchmarking, etc.), use `uv run` instead of `python3` directly. This handles virtual environments and dependencies automatically.

## Benchmark Reporting

When reporting benchmark accuracy, the denominator is always the total number of test items, not the number that ran without errors. Errors count as wrong answers — it doesn't matter *why* you got it wrong. Report accuracy as `correct / total`, and separately note errors and wrong answers for diagnostic purposes. Example: "50% (13/30) — 4 errors, 13 wrong" not "50% (13/26)".

## Scientific Neutrality

The user has observed a tendency toward "good news" bias in benchmark analysis — e.g., emphasizing results that favor Spell, soft-pedaling unfavorable comparisons, or framing ambiguous findings optimistically. Maintain a neutral, skeptical stance when analyzing benchmark results. Present findings as-is, including results that are unfavorable or inconclusive, without spin.

## System Prompt Best Practices

When editing `prompts/minimal.txt` or namespace docs:
- Use positive instructions ("the value of your program is the last expression") over negative ("don't use println")
- Show examples of correct behavior rather than warning against mistakes
- Avoid emphatic language (IMPORTANT, DO NOT, NEVER, MUST)
- State facts plainly; trust the model to follow clear instructions

When adding a feature the AI can use (builtins, tools, etc.), always update the system prompt to document it. The LLM can only use what it knows about. The system prompt is metadata-driven via `compose-system-prompt` in `llm.clj` — effect namespace docs are dynamically appended to the base prompt from `prompts/minimal.txt`. Tool docs come from `:docs` fields in namespace maps. The `builtins-namespace` (docs-only, in `stdlib.clj`) provides progressive disclosure for core builtins.

## Notebook

See `notebook/INDEX.md` for work log.
