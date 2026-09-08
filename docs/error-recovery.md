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

Emit a `(quine task "...")` form describing the original task, followed by a (quine context-summary "...") form describing history, progress, and context needed next. Preserve exact inspected evidence, checkpoints, pending obligations, and actual effect receipts. The previous program is inert context: its local bindings are not active. Reconstruct needed pure bindings explicitly. Inserted results are ordinary bounded snapshots with no hidden full original; omission data is missing evidence. Use subs for strings, subvec for line vectors, and the documented :out field for result envelopes. Preserve paths, source coordinates and next offsets as literal data. Never rerun an effect merely to recover omitted output. A focused file reread is fresh evidence of current contents, not the original receipt. Exact earlier values are available only if the program deliberately saved them in explicit state. If evidence is unavailable, report it rather than claiming inspection. Emit Spell code only. Avoid repeating your previous error.
```

## Reader recovery

If the completion cannot be parsed at all—for example because of unbalanced parentheses—Spell cannot embed it as normal code. In that case, the raw text is wrapped into a fresh recovery quine as an inert string, followed by the inert recovery-context block, `(prune 2)`, and a fresh program. The LM sees the raw program and a separate `_error` value, including the reader error, and gets another chance to produce a valid continuation. On the following extension, the raw program, recovery prompt, and error are pruned while the task and context-summary forms requested by the recovery prompt remain. Compared with the more common evaluation recovery path, this path can be expensive because when the error-producing program is wrapped as a string literal, it misses the KV cache.

## Consecutive-error limit

`:max-consecutive-errors` is a positive integer (default **3**), configurable in an agent profile or as a `spell.api/run` override. An explicit `nil`, zero, negative, non-integer, or string is invalid. Child profiles inherit the setting unless they override it. This is independent of provider retry schedules, format-repair attempts, and the LLM depth limit.

The counter belongs to one agent lifecycle, not to the compiled provider, a dynamic call stack, or the run as a whole. Reader and evaluation failures share the counter. The **Nth own failed completion** throws a typed `:recovery-exhausted` exception once, before dispatching another repair. Its data includes `:phase` (`:reader` or `:eval`), `:consecutive-errors`, `:limit`, `:max-consecutive-errors`, and `:handle`. The default therefore permits two repair calls after an initial failure, but repaired, distinct later mistakes do not accumulate forever.

| Event | Accounting |
|---|---|
| The model completion evaluates successfully, including a successful model-written repair | Reset to zero. |
| A normal self-call passes argument/options validation and enters the child call | Record the caller's success before the handoff; do not reset it again on ancestor unwind. |
| Ordinary wait is accepted with messages or a pending dependency; external wait is admitted | Reset before suspension/monitor launch. |
| An own reader or evaluation failure, including a malformed repair | Increment once, shared across both phases. |
| Synthetic recovery dispatch or deterministic namespace fixup | No reset. A later model-written repair has its own accounting frame. |
| Provider arrival/failure, parsing alone, or propagated descendant failure | No additional reset or own-failure increment. |
| Invalid self-call options, invalid/inactive external wait, or refused wait | No handoff reset. An unhandled model evaluation error still increments once. |
| An idle ordinary wait | No wait-boundary reset; an otherwise successful completion still resets normally. |

A parent can hand off successfully, return from the child, and then fail in its own later expression. That new parent error counts once against the current lifecycle counter. No saved ancestor streak is restored. A descendant's already-originating error is not counted again as it unwinds through ancestors. A new run, another agent, or a revived lifecycle generation starts with independent accounting.

With `:recover false`, failures still propagate immediately; the limit does not enable repair or turn ancestor propagation into extra failed completions. Typed terminal/control exceptions continue to bypass ordinary model recovery. Recovery does not roll back effects: retain actual receipts and never replay an effect merely to recover its output.


Recovery self-calls explicitly retain the failing call's receipt choice: raw calls use `{:receive? false}`, and receiving calls use `{:receive? true}` as illustrated above. An inbox batch already consumed before a reader error is carried into the recovery program once.
