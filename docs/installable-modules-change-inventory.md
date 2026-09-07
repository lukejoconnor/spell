# MODEL-FACING CHANGE INVENTORY — installable pattern modules

This is a review map to **complete editable text**, not a copy of the library or a live-acceptance claim. Baseline before this migration: `cffd2933e7bae1f543a585020ceef3fa13554589`. After links point to working-tree source; line numbers are navigation hints, not immutable revision claims. The final Codex packet captures exact before/after text and source hashes; repository paths below identify editable files.

## Contract and disposition

[Complete API/schema/examples](./installable-modules.md) · [approved disposition/validation plan](./installable-modules-plan.md) · [mailing-list operations](./mailing-list.md) · live acceptance command and receipts (`INSTALLABLE_MODULES_LIVE_ACCEPTANCE.md`)

| Previous family | Disposition | After |
| --- | --- | --- |
| check-result | Keep, opt-in | `:check-result :run` |
| ralph | Keep, opt-in | `:ralph :run` |
| team | Keep, opt-in; earlier deletion proposal superseded | `:team :run` |
| fix-loop | Keep, opt-in; earlier deletion proposal superseded | `:fix-loop :run` |
| relay | Keep, opt-in | `:relay :run` |
| mailing-list/mail | Keep, one opt-in module | `:mailing-list :init/:call/:change/:digest/:deliver` |
| clean-prompt | Delete only this family | No wrapper or replacement alias |

The only public host verbs are `patterns/install`, `catalog`, `source`, `update`, and `call`. Definitions are separate from state. Updates take evaluated pure transforms to whole definitions; entry source is quoted executable data. Requirements precheck listed namespace availability, grant no permissions, and do not certify dependency completeness. Transform purity is a caller obligation, not an effect sandbox.

## Generated discovery, catalog, and host teaching

| Changed model-facing surface | Complete before | Complete editable after / block locator |
| --- | --- | --- |
| Namespace short docs, guide, per-function help and invocation examples | old host (`git show cffd293:src/spell/patterns.clj`) | host source (`src/spell/patterns.clj`), `def patterns`, `:short-docs`, `:docs`, `:guide` and the five verb docs (starts near line 162) |
| Generated catalog payload: module/function docs, derived params, requirements, installed status, no bodies | Same old host; no general module catalog existed | host source (`src/spell/patterns.clj`), `summary` and `catalog` (near lines 61–103), reading the complete bundle entries linked below |
| Namespace export/description wiring | old stdlib (`git show cffd293:src/spell/stdlib.clj`) | stdlib (`src/spell/stdlib.clj`), `patterns` namespace wiring |

These links cover **generated** teaching, not just static Markdown. The host guide includes single-arity source shape, evaluated update transforms, dynamic scope/snapshot behavior, namespace-precheck limitations, and the predecessor evidence-pruning antipattern. Ordinary calls/catalogs omit executable bodies; `source` returns complete installed entries for deliberate inspection/editing.

## Bundle prompts and onboarding

All old bundle bodies and embedded prompts are available as complete git-before source (`git show cffd293:config/spl-lib/patterns.spl`). Each after link below is the complete editable module definition, including docs/requirements/source, prompt construction, and interpolation—not a truncated prompt excerpt.

| Bundle | Editable after; changed block locators | Before → after teaching |
| --- | --- | --- |
| check-result | check-result.spl (`config/spl-lib/modules/check-result.spl`), complete `:run` source and verifier prompt | Named wrapper → install/call `:check-result :run`; retain verification behavior |
| ralph | ralph.spl (`config/spl-lib/modules/ralph.spl`), default worker prompt near line 24 | `You are a worker agent for patterns/ralph.` → `You are a worker agent for patterns/call :ralph :run.` plus evidence discipline |
| team | team.spl (`config/spl-lib/modules/team.spl`), planner/verifier/task-worker prompts near lines 414/437/467 | `... in patterns/team.` → `... in patterns/call :team :run.` for all three roles, plus evidence discipline |
| fix-loop | fix-loop.spl (`config/spl-lib/modules/fix-loop.spl`), reflector/repair-worker prompts near lines 191/232 | Reflector `... in patterns/fix-loop.` → `... in patterns/call :fix-loop :run.`; repair-worker role retained; both gain evidence discipline |
| relay | relay.spl (`config/spl-lib/modules/relay.spl`), reasoning/verification/fresh-verifier prompts near lines 148/175/185 | Role references change from `patterns/relay` to `patterns/call :relay :run`; all three blocks retain fresh-agent semantics and gain evidence discipline |
| mailing-list | mailing-list.spl (`config/spl-lib/modules/mailing-list.spl`), `:call` source `teaching` and `bootstrap` near lines 130–186 | Old worker teaching used `patterns/mail` and a private code/state registry. New bootstrap installs/reuses `:mailing-list`, calls general `:call :subscribe-many`, never initializes existing board state, and teaches general call/digest/ack/evidence/notification operations |

Exact common addition to the changed role-string blocks:

```text
Context discipline: avoid the prune-evidence-then-rediscover antipattern. Before pruning a tool result, persist the exact evidence, actual effect receipts and next-action checkpoint needed for the artifact; retrieve retained or stored output instead of repeating effects.
```

Mailing-list explicit initialization still errors on duplicate board state; repeat module installation does not reset it. Its executable entries move to general `:modules`, and board state remains under `:mailing-list`. The `:change` board contract `[next-board result]` is separate from the module-update next-definition contract. Bootstrap source and generated teaching are both covered by the complete link above.

## Public docs, skills, examples, and configuration guidance

For any baseline-tracked path below, exact before text is retrievable without guessing a previous revision:

```bash
git show cffd2933e7bae1f543a585020ceef3fa13554589:PATH
# Review the full tracked change, not only the current snippets:
git diff cffd2933e7bae1f543a585020ceef3fa13554589 -- PATH
```

For new/untracked docs, baseline absence is not a deleted-before claim. The plan supersedes an earlier uncommitted proposal, explicitly documenting the correction rather than inventing a git-before version.

| Editable after | Exact changed block / previous behavior |
| --- | --- |
| README (`README.md`) | `patterns` namespace bullet: generic library → explicit installable-source guide |
| AGENTS (`AGENTS.md`) | Terminology: combined board code/state → module definitions and separate board state/init. Active source-map rows: old pattern loader/library → five-verb host and `config/spl-lib/modules/*.spl` opt-in definitions. |
| Configuration guide (`config/AGENTS.md`) | Pattern Library section → Installable Pattern Modules: no implicit startup installation/state initialization; explicit install/reuse, separate board init, editable policy, schema/docs, pure update transforms and capability prechecks. Complete before is available with the baseline `git show` command above. |
| [API](./api.md) | In-run mailing-list section: old loader/mail wrappers → five verbs and explicit board init |
| [Capabilities](./capabilities.md) | Agents/workers/patterns paragraph: module installation and requirements do not grant permissions |
| [Changelog](./CHANGELOG.md) | Unreleased module migration entries explicitly label **Previous behavior / Why it was a problem / What changed** |
| [Mailing-list guide](./mailing-list.md) | Quick start/API/customization/onboarding/source races: wrappers/private registry → general module functions, separate state, exact receipts and evidence preservation |
| [Module guide](./installable-modules.md) | New complete public contract, schema, disposition, source-editing examples, capability and purity limits |
| [Approved plan](./installable-modules-plan.md) | Supersedes uncommitted deletion proposal: team/fix-loop retained, only clean-prompt deleted. Final family table now gives each keep/delete utility rationale and states that retained bundles are source-programmed opt-in policies, not host rules. |
| Mailing-list skill (`resources/skills/mailing-list/SKILL.md`) | Complete invocation/customization/onboarding teaching migrated; predecessor evidence antipattern explicit |
| Discovered developer skill (`.agents/skills/spell-developer/SKILL.md`) and resource developer skill (`resources/skills/spell-developer/SKILL.md`) | Source map: old `config/spl-lib/patterns.spl` → complete `config/spl-lib/modules/*.spl` definitions and host API |
| Examples index (`examples/README.md`), new prompt (`examples/installable-modules.spl`), companion (`examples/installable-modules.md`) | New actual-receipt module exercise; distinguish prompt-file use from complete init-file use |
| Live init artifact (`INSTALLABLE_MODULES_LIVE_ACCEPTANCE.spl`) and companion (`INSTALLABLE_MODULES_LIVE_ACCEPTANCE.md`) | New self-contained root+peer program, deterministic source-driven setup, two model audits, opaque audit-prefix checks; exact configured fresh-runtime command/bounds; no paid-run claim |
| [Documentation navigation](./.vitepress/config.mjs) | Add installable-modules and mailing-list guide links |

No agent/model-profile capability changes were made. Live acceptance used existing `io-tc.agent.edn` and `codex-tc.edn` configuration with Astra xhigh; actual compiled provider settings, usage and prefixes are recorded in run 003.

## LLM recovery/continuation teaching — separate committed finding

Read the committed [recovery continuation finding](./recovery-continuation-finding.md), especially **Approved guidance-only correction**, for the exact before/after recovery paragraph and deterministic evidence. Its complete editable implementation is src/spell/llm.clj (`src/spell/llm.clj`), oversized-output recovery guidance. This inventory deliberately links that report instead of duplicating it. No production recovery accounting/policy redesign was approved or performed by the documentation worker.

## Verification boundaries

The documentation worker prepared artifacts and used no paid subprocess, production/test edits, or commits. A fresh no-provider parse checked the complete live program. A separate fresh isolated TestProvider execution of the parsed artifact then passed (exit 0; three scripted responses), with actual root 11 → 12, peer 11 → 12 → 12, preserved edits/reuse receipts, complete entry keys, and body-free audit prefixes. These preparation checks are deterministic/scripted. Subsequent fresh run 003 independently passed real-agent acceptance: 8 calls, root 11 → 12, peer 11 → 12 → 12, actual child receipt, and no body sentinels in any actual model prefix. One unsupported hash-map call recovered without replaying effects. Documentation source checks and `git diff --check` passed; the initial rendered build failed for missing `vitepress` (exit 127). Codex subsequently installed locked dependencies, corrected 32 invalid source links, and passed the production build. CLI help exited 0 and actual checked-out profile fields were inspected. Production/test owners provide their own integration receipts. Before pruning any review evidence, retain exact source slices, observed stored IDs/offsets, checkpoints, and actual effect receipts; never prune evidence then rediscover it.
