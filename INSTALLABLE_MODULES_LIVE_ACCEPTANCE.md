# Installable modules: live acceptance

## Status and scope

**Prepared, not paid-run accepted.** `INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl` is a complete, self-contained init program. It uses two actual agent lifecycles (root and `:module-peer`), deterministic setup/effects, and small real-model audits. It needs no project fixture or external helper. The existing coordination JVM has the old API; start a fresh runtime after the host/bundle migration is present.

No paid subprocess was run to prepare this artifact. Static parsing/config inspection and a future live result are different evidence. Do not infer a pass from the expected receipts below.

## Preparation checks actually completed

- Complete artifact parsed as one Spell form in a fresh JVM (exit 0).
- The artifact's parsed program executed in a fresh isolated TestProvider run with root and peer lifecycles (exit 0; three scripted provider responses). Actual outputs: root 11 → 12; peer 11 → 12 → 12; reuse receipts `:installed? false`; complete entry keys `[:doc :requires :source]`; scripted peer audit and opaque-prefix checks true. This verifies deterministic execution, **not real-model acceptance**. The checker used non-prefill TestProvider responses so it could inspect each actual audit prefix; no source refetch was used for invocation.
- Documentation source checker and `git diff --check` passed. Full documentation build could not run because `vitepress` was not installed (exit 127); no rendered-site success is claimed.

## Exact fresh-runtime command

Run from the repository root, only when live spend is authorized and `OPENAI_API_KEY` is configured:

```bash
bin/spell --init-file INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl \
  -a config/agent-profiles/io-tc.agent.edn \
  -m openai-tc:gpt-6-astra -R medium \
  -b 1.00 -d 8 -M 1600 --context-max-chars 10000
```

No `-v`, `--log`, or trace flag is needed. Do not pass this complete program as a positional prompt file. Budget is $1.00 for the run (not a promise of successful completion); depth is 8 and response token cap is 1600. A budget/depth/provider failure is a failed/incomplete acceptance, not a pass.

Configuration verified by reading the checked-out files and successful `bin/spell -h` (exit 0): `config/model-profiles/openai-tc.edn` selects OpenAI Responses, mandatory tool-call transport, `OPENAI_API_KEY`, GPT-6 Astra, and medium reasoning; the explicit CLI flags above pin model/reasoning/bounds. `config/agent-profiles/io-tc.agent.edn` inherits the tool-call base prompt and exposes `patterns`, `agents`, `globals`, and `io`; the peer inherits that compiled agent. Credential validity, provider availability, actual billing, and paid runtime success are **not verified** by these checks.

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
