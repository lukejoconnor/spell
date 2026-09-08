# Module ownership: reserved fresh live acceptance

## Status: fresh live pilot PASS; final source acceptance pending

`MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl` is a complete self-contained init program for a **fresh runtime**, using root and `:ownership-peer`. Explicit program setup supplies installation and edits; real model calls audit captured effects and collect peer completion. The parent executed pilot `2026-09-07-module-ownership-003`: **exit 0, 6 model calls, $0.63907**. Independent `check.clj` / `check.log` / `acceptance.json` in that notebook run bundle report **PASS**.

All six actual model prefixes excluded executable body markers. Automatic ownership-notice counts were `[1,0,0,0,0,0]`: root received its notice after the hidden installation return, with no duplicate or wrong-owner onboarding. The real peer completed edge **1**, received as **`msg-6625`**. Root and peer called 11 before the acknowledged peer edit and 12 afterward; the default peer edit was rejected without mutation. The explicit acknowledgment preserved owner `:main` and recorded actual editor `:ownership-peer`.

Independent journal inspection found exactly **two records**, install and update with sequences `[1,2]`, and exact source roundtrip/equality. The observed run identity was `3ec72a2b-50af-4682-9f52-21cd7193de45`. These effects occur in explicit init before traced model nodes, so journal `:trace-node-id` is legitimately absent; timestamp, run identity, sequence, and captured peer receipts provide the linkage. This pilot does not claim autonomous setup, race/failure coverage, or final accepted source commit. Final source acceptance remains pending the parent's review and commit.

## Rerun guide: parent-only paid command

From the workspace root, with the existing local Codex authentication/profile:

```bash
SPELL_FEEDBACK_PATH="$PWD/.spell/module-ownership-live/feedback.edn" \
  bin/spell --init-file MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl \
  -a config/agent-profiles/io-tc.agent.edn \
  -m codex-tc:gpt-6-astra -R xhigh \
  --dogfood --budget 8 --depth 0 --context-max-chars 10000 \
  --trace-dir "$PWD/.spell/module-ownership-live/trace"
```

The parent owns authorization, the reserved pilot budget and durable acceptance/notebook packet. **Do not launch this command as a preparation check.** Use a fresh destination for a new attempt, retain exact command/usage/exit status, and do not enable verbose model logs. `--depth 0` is unlimited depth; the dollar ceiling is modest, not unlimited. Root's compiled agent/profile is inherited by the peer; verify the actual provider and xhigh settings from the run receipt. Budget/provider failure is incomplete acceptance, not success.

Public embedding uses `spell.api/run` with `:dogfood true` and the usual `:init`, `:model-profile` and `:agent-profile`. `SPELL_FEEDBACK_PATH` overrides the existing feedback destination (default `.spell/feedback.edn`); automatic journal records and human feedback are distinct entries in that same append-only local EDN stream.

## What the explicit setup does

1. Root installs custom `:ownership-live`, discards the install receipt inside a helper expression, calls opaquely (10 → 11), then makes its next actual model generation. That generation must receive automatic owner guidance despite the hidden receipt. The audit prompt asks to identify actual runtime-added guidance; its own instructions are not evidence of such guidance.
2. Root dispatches a real tracked peer. The peer reuses the installed module, discovers its owner, calls opaquely (11), captures source only for the intended edit/equality check, and tries a default nonowner update. It captures the actual owner error and verifies the exact definition is unchanged.
3. The peer deliberately names the recorded owner in `{:owner owner}`, changes the body once (10 → 12), calls opaquely, and reinstalls without resetting. Its model audit receives receipts/results, **not executable bodies**. Actual editor must be `:ownership-peer`, distinct from immutable owner; acknowledgment is not an approval claim.
4. Root collects the actual peer edge/body, calls the changed code opaquely (12), checks reinstall preserves owner, and returns a compact audit. No model is asked to fetch source merely to invoke code. No executable module source is deliberately copied into an audit prefix.

The owner generation happens before peer dispatch so it is unambiguously the winner's next generation. Subsequent root generation must not receive duplicate onboarding; peer must not be incorrectly onboarded as module owner. Actual runtime prefix injection occurs after the program builds its input prefix, so only trace/provider-level inspection can independently establish those properties.

## Independent acceptance checklist (parent)

Keep raw receipts and actual edge IDs. A model's `:pass?` is not sufficient on its own.

- Actual owner first prefix: hidden install return is nil; a separate automatic notice identifies `:ownership-live` as owned by root, expects edit requests and coordinates `patterns/update`. Count that runtime notice, not words inside the audit instruction. Check no duplicate notice in later root prefixes and no wrong-owner notice in peer prefixes. All model prefixes must exclude executable body sentinels `OWNERSHIP_BODY_V1`, `OWNERSHIP_BODY_V2`, `OWNERSHIP_BODY_REJECTED` and complete entry source.
- Actual root/peer reports: root before 11/after 12; real peer edge completed; peer default update rejected with actual owner/caller/actionable guidance; `:unchanged? true`; successful explicit edit records owner=root, editor=`:ownership-peer`, `:explicit-owner? true`, `:journal {:status :ok :sequence s}`; reinstall false and owner preserved. Both audit stages and opacity checks must be genuine received results.
- Read the EDN journal without executing source. Filter `:kind :module-edit`, then `:module :ownership-live` and the observed `:run-id`. Require exactly one `:operation :install` baseline and one changed `:operation :update`, with consecutive sequence/revision for this isolated pilot. Failed default edit and reinstall must have no entry. Root installs; peer edits; owner is identical in both and only peer update acknowledges explicitly. Check timestamps and trace-node linkage to actual effects when present.
- Parse the lossless `:before`/`:after` executable definition strings as data (baseline before is nil). Compare entire definitions against the literal baseline and deliberate replacement in the `.spl` file, not just sentinels. Install after equals update before; update after differs only at `[:functions :run :source]`; the rejected +99 source never commits. `:functions` added/removed/modified must match. Use recorded run identity/sequence for ordering, not physical append order or a later registry read.
- Independently verify unique run identity if comparing another API/CLI run at the same destination. A failed append means `EDIT COMMITTED / RECORDING FAILED`; preserve its receipt/sequence and report an audit gap, never rerun the edit as if it failed. This pilot's happy-path success requires successful recording; deterministic failure/concurrency/isolation tests cover the wider contract.

### Preparation receipts actually observed

- Fresh JVM parse: exit 0, `PASS: one complete acceptance form; no provider run`.
- Fresh scripted TestProvider execution: exit 0, **0 paid model calls**, 5 actual provider prefixes, root 11 → 12, actual peer edge 1, hidden-return owner guidance observed by prefix assertions, no later duplicate/wrong-agent notice, and no executable body sentinels in any model prefix.
- Exact journal check: 2 records, install/update sequences `[1 2]`, owner `:main`, actual update editor `:ownership-peer`, explicit acknowledgment true, full source roundtrip/equality assertions passed. Observed run identity `cabd1eaa-c909-4b12-8c14-eeacd2af03e5`.
- Local preparation script and EDN are `.spell/module-ownership-prep/check.clj` and `.spell/module-ownership-prep/feedback.edn`. These are scripted execution evidence, **not a real-model acceptance**. The scripted root result did not independently require the peer model's audit boolean, so the parent's actual received peer report remains mandatory.

## Preparation and limitations

Only parsing, scripted TestProvider execution and source/document checks may be performed by the implementation worker. Such checks have no paid model and must be labelled as scripted, not live. The parent records their actual receipt separately from its fresh real-model pilot.

This small artifact does not force install/update races, notice overflow, provider failure after notice dequeue, disk failures, independent API isolation, or a crash. Maintained deterministic tests cover those concerns. Guidance is best-effort: returning without a generation sees none, and provider failure after dequeue loses that page. Direct globals mutations remain trusted coordination and unlogged. There is no crash-atomic journal guarantee, security boundary, hidden progress inference, or automatic replay.

Preserve the predecessor pruning-evidence rule: never prune supporting evidence and then rediscover it. Persist exact required receipts/snippets, observed stored IDs/offsets and checkpoints first. Proposed actions, source code and sent flags are not proof of execution. Preparing this artifact does not alter any of the three base prompts.
