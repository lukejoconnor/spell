# Relay: fresh-context handoffs

`relay.spl` is a complete startup program, not a natural-language prompt. With an already configured provider and an agent exposing `patterns`, `agents`, and `strings`:

```sh
bin/spell --init-file examples/relay.spl -b 1 -d 20
```

The command uses your configured provider. A budget limit bounds spending; it does not guarantee that the task will finish.

The retained bundle accepts a problem string or `{:problem string :max-rounds n}`. Each round registers a fresh worker. A worker returns `{:status :progress|:solved|:stuck :report "bounded evidence/reasoning summary" :answer value?}`; `:answer` is required for `:solved`. The next worker receives the problem plus structured prior reports, not the preceding worker's full context. Report handles permit explicit clarification requests; they do not make a shared hidden reasoning history.

A claimed solution goes to a separately registered fresh verifier, which returns `{:confirmed true|false :feedback "what was checked"}`. Rejections become handoff reports for later rounds. The result is `{:solved boolean :answer value? :rounds vector}`; exhausted rounds preserve reports rather than pretending success. Separate verification reduces shared context but is **not a correctness guarantee**, proof system, independent model family, or substitute for external tests/evidence. Caller capabilities/provider configuration still apply.

The module joins a captured computation with `!ask-await`; the enclosing agent resumes with an actual `msg-N` from `:future`. Inspect that report rather than capturing a wait as if it were the next message. Keep evidence, actual edge/result receipts and observed stored IDs across compaction. Do not restart a still-running relay merely because an unrelated message arrived.

Registered workers execute in independent agent lifecycles. The computation coordinating the relay can wait for them; calling `!llm-self` directly inside that computation remains invalid.
