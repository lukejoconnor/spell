# Architecture Review: comm.clj and llm.clj

Date: 2026-02-21

## Summary

Analysis of whether `box` and related code is in the right place, and how `comm.clj` and `llm.clj` relate architecturally.

## The Four Components

The CLAUDE.md describes a 4-component architecture:

1. **`spell-eval`** — pure evaluator (in `eval.clj`)
2. **`eval` builtin** — per-agent effectful evaluator via `make-inbox-fn` (in `llm.clj`)
3. **`box`** — universal execution primitive (in `comm.clj`)
4. **`-llm`** — thin API call wrapper (in `llm.clj`)

The call chain crosses files: `llm/-llm` → `comm/box` → (inbox-fn from `llm/make-inbox-fn`).

Dependency layering: `parse` → `eval` → `comm` → `llm`. Clean, no cycles.

## comm.clj (658 lines): Five Concerns

| Concern | Lines | Key functions |
|---------|-------|---------------|
| Registry + dynamic vars | 17-49 | `registry`, `register!`, `*current-handle*`, `*current-raw*` |
| Box + lifecycle | 61-382 | `box`, `make-sleep-fn`, `notify-waiters!`, `orphan-box!`, `start-box` |
| Message passing | 125-322 | `-send!`, `send-msg-fn`, `send`, `ask-builtin`, `block-for-message` |
| Spawn | 388-418 | `spawn`, `spawn-ask` |
| Namespace maps | 424-657 | `agents-namespace`, `futures-namespace` |

### Verdict: box is in the right place

Box, registry, sleep, send, and ask are deeply coupled through coordinated reads/writes to the same atoms (`{:inbox, :signal, :has-box, :waiters, :collector}`). Splitting box from send/ask would split code that coordinates on the same state — a recipe for bugs.

Box's actual dependencies are minimal: `parse/balance-parens` (one call) and nothing from `eval`. The `eval` dependency in `comm.clj` is only used by `create-msg` (`serialize-for-continuation`) and `futures-namespace` (`invoke-fn`, `spell-future?`) — not by box itself.

### Extraction candidates in comm.clj

1. **`futures-namespace`** (lines 628-657): Nothing to do with communication. It's `await-all` and `pmap` — parallel computation utilities. Belongs in `stdlib.clj` alongside `strings` and `math`. Its only dependency on `eval` (`spell-future?`, `invoke-fn`) matches what `stdlib.clj` already imports.

2. **`event-send`** (lines 193-202): Only consumer is `io/watch-send`. Thin wrapper over `send`. Could move to `io.clj`.

3. **~190 lines of `:detail` docstrings** in `agents-namespace` (lines 456-613): Documentation, not code. Could be externalized but proximity to implementation aids maintenance. Judgment call; not urgent.

## llm.clj (497 lines): Mixed Concerns

| Concern | Lines | Key functions |
|---------|-------|---------------|
| Describe builtin | 21-28 | `!describe` |
| Prefix echo handling | 47-78 | `strip-code-fences`, `strip-prefix-echo` |
| Eval pipeline | 84-131 | `make-inbox-fn` (recovery loop) |
| Agent registration | 133-143 | `register-agent` |
| LLM engine | 145-188 | `-llm` (API call + box delivery) |
| Eval builtin factory | 190-202 | `make-eval` |
| System prompt composition | 208-261 | `compose-system-prompt`, `namespaces-section` |
| LLM factory | 263-393 | `make-llm` (the big one) |
| Leaf LLM | 399-428 | `make-leaf-llm` |
| Format validation | 434-497 | `validate-format`, `wrap-with-format` |

### Observations

**`make-llm` (130 lines) does too much.** It wires together: namespace resolution, system prompt generation, provider call construction, recovery strategy resolution, self-reference atom setup, effect-builtin assembly, eval-builtin creation, agent registration wiring, NL prompt wrapping, handle registration/inbox seeding, and the actual `the-llm` closure. This is the factory for the entire agent runtime — it's the only function that knows about all the pieces.

The complexity is somewhat inherent (it *is* the wiring function), but several chunks could be extracted:

1. **NL wrapping** (`wrap-nl`, lines 359-364): Pure string transformation. Could be a named top-level function for testability.

2. **Handle registration + inbox seeding** (lines 380-390 inside `the-llm`): The `the-llm` closure directly pokes `comm/registry` atoms. This is the tightest coupling between `llm.clj` and `comm.clj` internals — `the-llm` uses `comm/register!`, `comm/handle?`, and directly swaps `comm/registry` atoms via `assoc-in` and `compare-and-set!`. A `comm/prepare-for-call` or similar function could encapsulate this, keeping registry manipulation inside `comm.clj`.

3. **Format validation** (`validate-format`, `wrap-with-format`, lines 434-497): Self-contained, no dependency on anything else in `llm.clj`. Could be its own file or live in `eval.clj` (it's about validating program output).

4. **System prompt composition** (`compose-system-prompt`, `namespaces-section`, `format-section`, `generate-system-prompt`, lines 208-261): Pure string functions with no dependency on LLM machinery. Could be extracted if the file grows further.

5. **`!describe` macro support function** (lines 21-28): Defined here "to avoid circular deps with core." It's a pure map lookup with no LLM dependency. Currently it needs to be importable by both `core.clj` and the `!describe` macro in `macros.clj`. If `stdlib.clj` re-exported it, the circular dep concern might resolve more cleanly.

### The `the-llm` / comm coupling

The most actionable concern: `the-llm` (lines 365-391) directly manipulates `comm/registry` internals:

```clojure
;; line 385: direct atom manipulation of registry internals
(swap! comm/registry assoc-in [handle :default-inbox-fn] default-inbox)
;; line 389: CAS on internal atom
(compare-and-set! (:inbox (get @comm/registry handle)) nil inbox-fn)
;; line 390: reset on internal atom
(reset! (:inbox (get @comm/registry handle)) inbox-fn)
```

This breaks `comm.clj`'s encapsulation. If the registry schema changes, `llm.clj` breaks. A function like `comm/seed-for-call [handle inbox-fn default-inbox-fn parent?]` would keep registry manipulation inside `comm.clj`.

## Recommendations (prioritized)

1. **Extract `futures-namespace` to `stdlib.clj`** — Clearest win. Wrong file, no structural reason to be in comm.
2. **Encapsulate registry manipulation** — Add a `comm/seed-for-call` (or similar) so `the-llm` doesn't reach into registry atoms directly. Reduces cross-module coupling.
3. **Extract `wrap-with-format`** — Self-contained, independently testable, no dependency on LLM internals.
4. **Extract `wrap-nl` as top-level function** — Small but aids testability of NL-to-code wrapping.
5. **Leave `make-llm` as-is otherwise** — It's the wiring function. The complexity is inherent. After extractions 2-4, it shrinks to ~80 lines of genuine wiring.
6. **Leave box where it is** — Correct placement. The coupling to registry/send/ask is real and structural.
