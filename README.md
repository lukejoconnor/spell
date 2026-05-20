# Self-programmed execution language for LMs

Self-programmed execution (SPE) is an architecture for LM agents in which a LM-written program, not a fixed agent loop, is responsible for orchestration policy. Spell (self-programmed execution language for LMs) is a language designed for SPE. SPE and Spell are described in [this paper](https://arxiv.org/abs/2605.06898).  The language provides primitives for self-calls, program self-reference, context management, and concurrency, and multi-agent communication. It is based upon and embedded within Clojure. Spell is currently a prototype intended for academic research.

## Core semantics

In SPE, the same data---a model completion---is both the content of the model's context window and the program specifying what context becomes the prefix or prompt for a subsequent turn. Typically, this prefix is constructed by editing or appending to the existing context window. This creates two major challenges, which Spell addresses.

One challenge is that a program must often reference its own source code; this is done using the `quine` form. The expression `(quine name inner-expr)` first binds to `name` the entire expression - not just `inner-expr` - as data. Then, it evaluates `inner-expr`, and the expression's value is that of `inner-expr`.

A second challenge is that a Spell program must often be re-evaluated as the prefix of a newly generated program, and this is problematic if it has side effects (in particular, LM self-calls). To address this, Spell programs are pure except for expressions which are explicitly evaluated by `eval`, and they have a special structure, called the Spell wrapper:

```clojure
(quine completion          ; 1. bind the current completion as data
  (eval                    ; 4. evaluate the trailing expression
    (do
      ...                  ; 2. ordinary local computation
      '(effectful-expression)))) ; 3. return quoted trailing expression
```

The outer `quine` form binds the program source code as data. The inner `do` block returns the value of its last expression, which is the quoted trailing expression. Because it is quoted, this expression is not evaluated until it is passed to `eval`, at which time effect functions, including self-calls, are made available. If the model appends an expression after the current quoted trailing expression, the old trailing expression becomes inert data.

## Quick start

Clone the repo and ask your agent to perform setup; it should locate prompts/skills with installation, API provider auth, agent config, etc. The prerequisites are Java, Clojure, and an API key or Codex auth.

The most convenient way to run Spell is via the CLI:

```bash
# Run a bundled example
bin/spell -e hello-world

# Ask the default agent to do something
bin/spell "Inspect the examples directory and suggest one example to run next."

# Run a .spl file directly
bin/spell examples/twenty-questions.spl

# See options
bin/spell -h
```

Use `-b` to cap spend, `-d` to cap recursion depth, `-T` to record traces, and `--log FILE` to save verbose output. Run `bin/spell -h` for the authoritative CLI help from the checked-out code.

For a short conceptual guide, read `docs/language-overview.md`. Agent-facing setup, source map, and test commands live in `AGENTS.public.md`.

## License

Spell is released under the MIT License. See `LICENSE`.
