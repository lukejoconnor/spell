# Installable modules example

The [prompt](installable-modules.spl) asks an agent to install, discover, call, inspect for editing, update, and reuse a tiny custom module. No compatibility wrappers or board initialization are involved.

Run from the repository root with authorized live spend and `OPENAI_API_KEY` configured:

```bash
bin/spell examples/installable-modules.spl \
  -a config/agent-profiles/io-tc.agent.edn \
  -m openai-tc:gpt-6-astra -R medium -b 1.00 -d 12 -M 1600
```

This `.spl` is a **natural-language prompt**, unlike the root complete init program. The example is prepared, not paid-run verified. Expected actual receipts: first install has `:installed? true`; catalog has `:params [n]` and no body; calls return 11 before editing and 12 after editing/reinstall; repeat install has `:installed? false`. The update receipt names `:example-counter` and `:fns [:run]`. A model's success label without those actual results is not acceptance.

For deterministic setup with two real agent lifecycles and bounded model audits, see the root [live acceptance artifact/command](../INSTALLABLE_MODULES_LIVE_ACCEPTANCE.md). For the complete contract and disposition table, see [installable pattern modules](../docs/installable-modules.md).
