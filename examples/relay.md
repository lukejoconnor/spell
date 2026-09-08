# Relay: fresh-context handoffs

`relay.spl` is a complete startup program, not a natural-language prompt. With an already configured provider and an agent exposing `patterns`, `agents`, and `strings`:

```sh
bin/spell --init-file examples/relay.spl -b 1 -d 20
```

This command makes model calls and can spend money. It is supplied for an authorized run, **not executed as a paid pilot** in module-library-001. A budget limit is not a forecast of cost or proof the task will finish.

The retained bundle accepts a problem string or `{:problem string :max-rounds n}`. Each round registers a fresh worker. A worker returns `{:status :progress|:solved|:stuck :report "bounded evidence/reasoning summary" :answer value?}`; `:answer` is required for `:solved`. The next worker receives the problem plus structured prior reports, not the preceding worker's full context. Report handles permit explicit clarification requests; they do not make a shared hidden reasoning history.

A claimed solution goes to a separately registered fresh verifier, which returns `{:confirmed true|false :feedback "what was checked"}`. Rejections become handoff reports for later rounds. The result is `{:solved boolean :answer value? :rounds vector}`; exhausted rounds preserve reports rather than pretending success. Separate verification reduces shared context but is **not a correctness guarantee**, proof system, independent model family, or substitute for external tests/evidence. Caller capabilities/provider configuration still apply.

The module joins a captured computation with `!ask-await`; the enclosing agent resumes with an actual `msg-N` from `:future`. Inspect that report rather than capturing a wait as if it were the next message. Keep evidence, actual edge/result receipts and observed stored IDs across compaction. Do not restart a still-running relay merely because an unrelated message arrived.

## Observed validation limitation (module-library-001)

The exact startup parses, but offline execution with an explicitly compiled scripted provider did **not** produce a successful relay. Both registered workers returned a lifecycle failure, `Self-calls cannot run inside computation futures` (`:agent-in-computation-future`), before worker/verifier provider generation. The enclosing result was `:solved false` with two `:stuck` reports. The retained relay bundle and runtime were not modified for this assignment. Maintained relay unit tests pass but do not establish this full lifecycle path. Do not treat the command above as validated successful execution. Raw attempts and diagnosis are preserved in `.spell/module-library-001/acceptance-startups-01.*`, `acceptance-startups-02.*`, and the acceptance report. No paid pilot was run.
