# Test Directory Guide

This directory contains the Clojure test suite for Spell.

## TestProvider

Most tests use `TestProvider` from `spell.provider`. There is no separate dummy or mock provider. `TestProvider` supports multiple response-matching strategies:

- `:response`: static catch-all response.
- `:responses`: exact prompt-string map, `{prompt-string response}`.
- `:response-fn`: fallback function, `(fn [prompt] response-or-nil)`.
- `:response-rules`: ordered substring-matching rules.
- `:prefill?`: controls whether the provider supports prefill semantics; default is `true`.

Resolution order is exact match in `:responses`, then `:response-fn`, then `:response-rules`. If nothing matches, the provider throws with the full prompt text, which is useful when building fixtures.

## Test Helpers

`test/spell/test_helpers.clj` contains helpers that remove most fixture boilerplate:

```clojure
(th/make-test-agent {:response "42)"})
(th/make-test-agent {:response-fn (fn [p] ...)} :namespaces ns-map :prefill? false)

(th/make-test-runner {:response "42)"})

(th/make-test-leaf-llm "static response")
(th/make-test-leaf-llm {:response-fn f} :system "system prompt")
```

`make-test-agent` accepts either a string or a `TestProvider` option map. Keyword arguments control the compiled-agent layer: `:namespaces`, `:prefill?`, `:recover`, and `:model`.

`make-test-runner` is useful when a test wants a host-side prefix runner while still flowing through a compiled agent and `!llm-self`.

## Response Strings And Prompt-As-Prefix

Spell uses prompt-as-prefix semantics: the prompt is the beginning of the program, and the model response continues it. Test responses must complete the program that the prompt started.

For a prompt `"(do "`, the response `"42)"` produces `(do 42)`.

For a prompt `"(eval (do '"`, the response `"(io/sh \"ls\")))"` produces `(eval (do '(io/sh "ls")))`. The quote plus double-eval pattern gives access to effect namespaces.

Common prompt/response patterns:

| Prompt | Response | Full program | Notes |
| --- | --- | --- | --- |
| `(do ` | `42)` | `(do 42)` | Simplest case. |
| `(eval (do '` | `(io/sh "ls")))` | `(eval (do '(io/sh "ls")))` | Effect namespace access. |
| `(eval '(do ` | `(globals/get :x))` | `(eval '(do (globals/get :x)))` | Effect via eval. |
| `(quine completion (eval (do ` | `42))` | `(quine completion (eval (do 42)))` | With quine wrapper. |

## Common Patterns

Multi-turn tests often use `:response-fn` with an atom counter:

```clojure
(let [call-count (atom 0)
      responses ["first-response)" "second-response)"]]
  (th/make-test-agent
    {:response-fn (fn [_]
                    (let [r (nth responses @call-count)]
                      (swap! call-count inc)
                      r))}))
```

Most test files reset `runtime/registry` between tests:

```clojure
(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))
```

Effect namespaces and builtins, such as `io/`, `agents/`, `globals/`, `patterns/`, and `!ask-await`, are only available through eval's double evaluation. Test prompts must use the `(eval (do '...))` or `(eval '(do ...))` pattern.
