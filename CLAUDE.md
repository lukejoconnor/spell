# spell

A domain-specific language for LLM self-orchestration, implemented as a Lisp dialect in Clojure.

## Core Idea

Instead of an external harness controlling an agent's execution loop, the LLM writes its own execution graph. The model becomes a metaprogrammer of its own execution—deciding what recursive calls to make, how to branch, and what context to pass forward.

## Implementation Approach

Building on Lisp rather than custom XML syntax. The mapping:

| Original Concept | Lisp Form |
|------------------|-----------|
| `<id>body</id>` | `(setq id body)` |
| `$ref` (evaluate) | Variable lookup / `eval` |
| `@ref` (quote) | `quote` / `'` |
| Tag nesting | Nested lists |

**Implementation language:** Clojure (immutability, JVM ecosystem, built-in concurrency).

## Key Semantic Concept: Expansion

Spell's core differentiator from standard Lisp is how scope/environment works.

**Problem:** When LLM1 passes a quoted expression to LLM2, the expression may reference bindings in LLM1's scope that won't exist when LLM2 runs.

**Solution:** Before passing a thunk to a child LLM, substitute values for *free variables* (those not bound inside the thunk itself).

**Canonical expansion:** The smallest equivalent expression with only internal quotations. This is implementation-critical.

See `lisp-foundation-decision` notebook entry for details.

## Key Files

| Path | Description |
|------|-------------|
| `writeup/spec.md` | Language specification (semantics section updated) |
| `writeup/spell-lisp-notes.md` | Design notes for Lisp implementation |

## Current Status

Design phase. Key decisions made:
- Lisp foundation (not XML from scratch)
- Clojure as implementation language
- Expansion semantics formalized

Next: Implementation of `spell-eval` interpreter with explicit environment threading.

## Notebook

See `notebook/INDEX.md` for work log.
