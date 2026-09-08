# Caller-configured board startup

[`mailing-list-startup.spl`](mailing-list-startup.spl) is a complete **startup program**, not a positional natural-language task. Run it from a fresh run with your existing parent profile that exposes the caller-selected compiled `workers/researcher` symbol; the child must expose `patterns`, `globals` and `agents`:

```bash
bin/spell --init-file examples/mailing-list-startup.spl -a /path/to/your-existing-parent.agent.edn -b 1 -d 12
```

Replace the illustrative compiled symbol and unique handle in the source to match your existing configuration; this example does not create or modify profiles. The command uses the caller's configured provider and budget.

The parent installs and initializes `[:research]`, posts explicit evidence, then captures an ordinary `agents/spawn-ask` edge. The complete child startup installs the already-shared definition and atomically subscribes before its first model generation. Only setup is caught; a tagged `:spell/child-failure true`, `:phase :onboarding` result is collected normally if setup fails. Task generation/recovery is outside that catch. The parent must establish the actual dispatched edge and collect its corresponding report rather than interpreting a handle, plan, unrelated message or empty outgoing set as proof.

This ordering is **ordinary caller convention, not runtime-enforced onboarding**. No board-specific spawn API, lifecycle wrapper or new default agent exists. Reinstall preserves edited shared source, immutable module ownership and origin. The board must not already be initialized by this fresh-run example; for an existing board, omit the parent init and select existing lists without replacing state. Child installation never reinitializes it.

Process needed message bodies and any retention gap before acknowledging the exact observed digest token. Acknowledgement is not comprehension; author attribution is cooperative, not authenticated identity. Subscriptions are cursor/delivery configuration, **not a privacy or access-control boundary**. Notifications attempt every subscriber, including subscribed author/caller; quiet posts send nothing. Subscriptions survive completion and must be explicitly removed if desired. No automatic cleanup is implied.

See [mailing-list API and invariants](../docs/mailing-list.md) for bounds, concurrency, failure and source-editing contracts.
