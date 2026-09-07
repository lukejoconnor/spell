# Installable modules: live acceptance

## Status and scope

**Live acceptance passed on 2026-09-07.** `INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl` is a complete, self-contained init program. It uses two actual agent lifecycles (root and `:module-peer`), deterministic setup/effects, and small real-model audits. It needs no project fixture or external helper. The acceptance ran in a fresh runtime after the host/bundle migration and packaging fix.

Run `2026-09-07-installable-pattern-modules-003` completed with 8 Codex/Astra xhigh calls, observed cost $0.83954, and exit 0. Independent trace inspection verified all eight actual model prefixes omit both executable-body sentinels; root 11 → 12, peer 11 → 12 → 12, preserved edits, and the actual peer result on edge 1. The peer arrived before a wait was needed. One unsupported `hash-map` call recovered using retained receipts without repeating effects. The explicit init program supplies the deterministic installation/edit; this demonstrates cross-agent execution and model auditing, not an independently invented edit.

## Preparation checks actually completed

- Complete artifact parsed as one Spell form in a fresh JVM (exit 0).
- The artifact's parsed program executed in a fresh isolated TestProvider run with root and peer lifecycles (exit 0; three scripted provider responses). Actual outputs: root 11 → 12; peer 11 → 12 → 12; reuse receipts `:installed? false`; complete entry keys `[:doc :requires :source]`; scripted peer audit and opaque-prefix checks true. This verifies deterministic execution, **not real-model acceptance**. The checker used non-prefill TestProvider responses so it could inspect each actual audit prefix; no source refetch was used for invocation.
- Documentation source checks and production VitePress build passed during Codex acceptance after installing locked dependencies and correcting source links. The preparation attempt had stopped at missing VitePress; that historical failure is preserved in the run report.

## Exact fresh-runtime command

Run from the repository root with local Codex authentication. The user authorized this acceptance within the shared $200 module-work budget; the following run ceiling is $8.

```bash
bin/spell --init-file INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl \
  -a config/agent-profiles/io-tc.agent.edn \
  -m codex-tc:gpt-6-astra -R xhigh \
  --budget 8 --context-max-chars 10000 \
  --trace-dir /absolute/path/to/acceptance-bundle/trace
```

Pass the complete program with `--init-file`. Reasoning is xhigh; the default unlimited depth is retained. Trace output records execution for review; verbose model logging is disabled. Budget/provider failure means incomplete acceptance. The supervisor's durable run bundle contains the exact executed command and final usage.

The checked-out `config/model-profiles/codex-tc.edn` uses local Codex authentication and tool-call transport. `config/agent-profiles/io-tc.agent.edn` exposes `patterns`, `agents`, `globals`, and `io`; the peer inherits the compiled agent. Actual compiled provider/reasoning and execution receipts must be checked in the acceptance bundle.

## What the program does

1. Root installs a tiny custom module and calls it: input 10 → 11. It obtains a compact catalog and creates an actual tracked peer edge.
2. The peer reuses the same module without a replacement definition, calls it (11), fetches its complete entry **once for editing**, and atomically edits its source. It calls again (12), reinstalls without resetting the edit, and calls again (12).
3. The peer model audits only captured receipts/outputs, not the function body. Root receives the actual peer result, calls the edited shared module (12), and confirms another install reports reuse.
4. Root's fresh audit prefix is constructed from teaching plus compact catalog/receipts. A deterministic guard rejects body sentinels in that prefix; the model also inspects its actual completion. Neither agent refetches source merely to invoke the module.

The source defines orchestration explicitly. No lexical closures, polling, fake result bindings, or private code registry are assumed. The artifact retains the predecessor rule against pruning evidence and then rediscovering it in both model-facing audit prompts.

## Required receipts and result

| Evidence | Expected |
| --- | --- |
| Root install | `{:module :live-acceptance :installed? true :fns [:run]}` |
| Root catalog | `:installed? true`; function `:run` has `:doc`, `:params [n]`, `:requires []`; no body |
| Root before / after peer report | `11` / `12` |
| Actual peer edge/report | Matching returned edge ID and sender `:module-peer`, not a guessed ID |
| Peer reuse and reinstall; root reuse | `:installed? false`, `:fns [:run]` |
| Peer before / after / preserved | `11` / `12` / `12` |
| Peer inspected entry keys | `[:doc :requires :source]`, with `:source-inspected-for-edit? true` |
| Peer update | `{:module :live-acceptance :fns [:run]}` |
| Peer model audit | `{:peer-ok? true}` |
| Root final | `:pass? true`, actual supporting receipts, `:opaque-prefix? true` |

A fabricated receipt, missing child result, source refetch just for invocation, body leak, or tagged child failure invalidates acceptance even if a model says `:pass? true`. Inspect actual outputs/receipts. This small demonstration does not replace deterministic concurrency, arity/recur, capability, bundle, or mailing-list tests.

## Navigation

- [Complete editable artifact](INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl)
- [Public migration guide](docs/installable-modules.md)
- [MODEL-FACING CHANGE INVENTORY](docs/installable-modules-change-inventory.md)
- [Committed recovery/continuation finding](docs/recovery-continuation-finding.md)
