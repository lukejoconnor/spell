# Test Infrastructure

## TestProvider

All tests use `TestProvider` from `spell.provider`. There is no separate "dummy" or "mock" provider. TestProvider supports multiple response-matching strategies:

- `:response` — static catch-all (any prompt returns this string)
- `:responses` — exact prompt-string map (`{prompt-string response}`)
- `:response-fn` — fallback function `(fn [prompt] -> response-or-nil)`
- `:response-rules` — ordered substring-matching rules `[{:includes [...] :excludes [...] :response "..."}]`
- `:prefill?` — controls `supports-prefill` (default `true`)

Resolution order: exact match in `:responses`, then `:response-fn`, then `:response-rules`. If nothing matches, throws with the full prompt text (copy-paste into `:responses` to build fixtures).

## Test Helpers (`test/spell/test_helpers.clj`)

Two factory functions eliminate boilerplate:

```clojure
;; Full LLM+eval pipeline (returns {:llm fn, :run fn})
(th/make-test-llm {:response "42)"})
(th/make-test-llm {:response-fn (fn [p] ...)} :namespaces ns-map :prefill? false)

;; Leaf LLM (returns fn, no eval pipeline)
(th/make-test-leaf-llm "static response")
(th/make-test-leaf-llm {:response-fn f} :system "system prompt")
```

`make-test-llm` accepts either a string (shorthand for `{:response str}`) or a TestProvider opts map. Keyword args control the `make-llm` layer: `:namespaces`, `:prefill?`, `:recover`, `:model`.

## Response Strings and Prompt-as-Prefix

Spell uses prompt-as-prefix semantics: the prompt IS the beginning of the program, and the LLM response continues it. Test responses must complete the program that the prompt started.

For a prompt `"(do "`, the response `"42)"` produces the full program `(do 42)`.

For a prompt `"(eval (do '"`, the response `"(io/bash \"ls\")))"`produces `(eval (do '(io/bash "ls")))` — the quote + double-eval pattern gives access to effect namespaces.

Common prompt/response patterns in tests:

| Prompt | Response | Full program | Notes |
|--------|----------|-------------|-------|
| `(do ` | `42)` | `(do 42)` | Simplest case |
| `(eval (do '` | `(io/bash "ls")))` | `(eval (do '(io/bash "ls")))` | Effect namespace access |
| `(eval '(do ` | `(globals/get :x))` | `(eval '(do (globals/get :x)))` | Effect via eval |
| `(quine completion (eval (do ` | `42))` | `(quine completion (eval (do 42)))` | With quine wrapper |

## Common Patterns

**Multi-turn tests** use `:response-fn` with an atom counter:

```clojure
(let [call-count (atom 0)
      responses ["first-response)" "second-response)"]]
  (th/make-test-llm
    {:response-fn (fn [_]
                    (let [r (nth responses @call-count)]
                      (swap! call-count inc)
                      r))}))
```

**Registry cleanup** — most test files reset `runtime/registry` between tests:

```clojure
(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))
```

**Effect testing** — effect namespaces and builtins (for example `io/`, `agents/`, `globals/`, `patterns/`, `!ask-await`) are only available through eval's double evaluation. Test prompts must use the `(eval (do '...))` or `(eval '(do ...))` pattern.
