# Error recovery

LM-written programs will sometimes contain errors. Spell includes error recovery mechanisms which allow the LM to recover after an initial error.

## Result-map errors

`spell-eval` returns result maps rather than throwing host-language exceptions directly:
```clojure
Success: {:ok value :env env'}
Error:   {:err message :env env :expr failing-expression :trace [...]}
```
The `:trace` field records the Spell-level call path through which the error propagated. For host-function errors, Spell rewrites the message so it is expressed in terms of Spell-facing names rather than internal Clojure machinery.

## Deterministic namespace fixup

A frequent LM mistake is to use a symbol without the required namespace qualification—for example, `trim` instead of `strings/trim`. Spell first attempts a deterministic repair by searching the available namespaces. If there is exactly one matching resolution, the system substitutes it and re-evaluates.

## Trailing expression error recovery

When the program is successfully parsed and an error is proven to come from the quoted trailing expression inside the standard `(quine completion (eval (do ...)))` wrapper, it is possible to rescue the program by superseding that one expression. Spell appends an `_error` binding and a new self-call after the failing trailing expression inside the same `do` block, then re-invokes the model. The previous trailing expression becomes inert because it is no longer last. Earlier expressions in the same `do` block are re-evaluated first, and their bindings remain available if re-evaluation still succeeds.

## Other evaluation error recovery

When another expression throws an error, the mechanism described above would fail because appending new expressions inside the `do` block could still allow the error to be re-triggered. Instead, three arguments are appended inside the top-level `quine` form: an inert recovery-context `do` containing the recovery prompt and error, a `(prune 2)` marker, and a new `(eval (do ...))` block which marks the beginning of the next model-written program. The error-causing program and recovery context are visible for exactly one turn, after which the `prune` marker causes their disappearance. The recovery prompt instructs the agent to retain whatever context it needs to continue its task.

For example, suppose the failing program contains an effect call outside the trailing expression:
```clojure
(quine completion (eval (do
  (quine prompt "List the files.")
  (def files (io/ls "."))         ;; throws: io/ is effect-only here
  '(!call-now n (count files)))))
```
The runtime catches the error and appends three arguments to the top-level `(quine completion ...)`: an inert recovery-context block containing the prompt and error, `(prune 2)`, and a fresh `(eval (do ...))` block. It then re-evaluates:

```clojure
(quine completion
  (eval (do                       ;; inert: not the last form of the quine
    (quine prompt "List the files.")
    (def files (io/ls "."))
    '(!call-now n (count files))))
  (do                             ;; inert recovery context
    (def _recovery_prompt
      "The previous Spell program threw an error. ...")
    (def _error
      {:error "io/ls: io/ is an effect namespace - use it in the trailing expression via eval"
       :in '(def files (io/ls "."))}))
  (prune 2)                       ;; drops the failed program and recovery context
  (eval (do                       ;; the new program begins here
    '(!llm-self (reopen completion) {:receive? true}))))
```
This works because `quine` with arity greater than two evaluates only the last form; appending a recovery form to the error-producing form avoids re-raising the error. The error-causing form, recovery prompt, and error message are visible to the model for only one turn. On the following extension, `(prune 2)` removes both inert arguments, leaving the new program without stale recovery context. The recovery prompt is:
```tex
The previous Spell program threw an error. The previous program is visible during this recovery turn, but it will be pruned afterward, such that you will not see it on your next turn.

Emit a `(quine task "...")` form describing the original task, followed by a (quine context-summary "...") form describing history, progress, and any context which should be retained on your next turn. If there are long file snippets which should be retained, restore these by re-reading from those files in your trailing expression. Emit Spell code only, not prose. Avoid repeating your previous error.
```

## Reader recovery

If the completion cannot be parsed at all—for example because of unbalanced parentheses—Spell cannot embed it as normal code. In that case, the raw text is wrapped into a fresh recovery quine as an inert string, followed by the inert recovery-context block, `(prune 2)`, and a fresh program. The LM sees the raw program and a separate `_error` value, including the reader error, and gets another chance to produce a valid continuation. On the following extension, the raw program, recovery prompt, and error are pruned while the task and context-summary forms requested by the recovery prompt remain. Compared with the more common evaluation recovery path, this path can be expensive because when the error-producing program is wrapped as a string literal, it misses the KV cache.

Reader and evaluation recovery share one limit of two recovery re-prompts. Each reader or evaluation retry consumes one attempt.

Recovery self-calls explicitly retain the failing call's receipt choice: raw calls use `{:receive? false}`, and receiving calls use `{:receive? true}` as illustrated above. An inbox batch already consumed before a reader error is carried into the recovery program once.
