# Context and skill reliability changelog

Baseline: `7ee774b`; branch: `codex/dogfood-reliability`. Run: `2026-09-07-context-skills-001`.

## Scope and invariants

This patch improves visible instruction access, not merely skill discovery. Oversized strings retain the original object in the existing per-run value store and gain bounded, inert inspection guidance where it fits. The complete rendered contribution, including descriptor, binding syntax, escaping and fixed continuation, uses the existing cap: default 10000 UTF-16 units, minimum 128. The cap is per contribution, not a bound on the whole accumulated context or mailbox batch. No global cap increase, arbitrary-value inspection, new inspection namespace, compatibility shim, hidden read/task policy, replay deduplication or progress enforcement was introduced.

Spell still uses source as context and completion as program, dynamic scope, fresh local evaluation and structural replay. Message acceptance still uses one atomic coordinator drain in mailbox order. Annotation metadata is validated before accepting/claiming messages. Caller-authored macro envelopes are unchanged. Receipt labels are not an action ledger and do not establish execution of earlier effects.

## Changes and decisions

| Change | Previous behavior | Why it was a problem | What changed |
|---|---|---|---|
| Stored-string disclosure | Oversized strings appeared as bare stored references. | Agents could mistake successful retrieval for missing data or never inspect hidden instructions. | Optional bounded previews, UTF-16 length and executable paging guidance use the existing contribution budget and preserve the original object. Tight caps retain the bare-reference fallback; arbitrary lazy values are not inspected. |
| Complete skill bodies | Filesystem and packaged skill bodies were truncated at 64 KiB and preceded by provenance. | Instructions beyond the cutoff were lost, and early previews emphasized metadata rather than usable instructions. | Keep complete bodies, put instructions first and winning-source provenance at the end. Existing discovery precedence is preserved. |
| Receipt labels | One generic annotation conflated pre-evaluation replacement and wakeup. | A model could infer that an action ran or was skipped from ambiguous wording. | Label the actual startup, pre-evaluation, wait, dormant, or explicit-receive site. Validate the site before mailbox drain; keep labels and binding syntax inside the existing cap. Earlier effects still require their own receipts. |
| CLI context cap | The run API exposed the contribution limit but the CLI did not. | CLI users could not select that existing limit independently of response-token limits. | Add validated --context-max-chars, preserving default 10000 and minimum 128. |
| Retrieval and evidence guidance | Examples could encourage refetching opaque results, use the wrong glob result shape, discard verification evidence, or cite a stale release. | These instructions contributed to repeated reads and unsupported claims after pruning. | Update four skill bodies and all three prompt variants with shape-correct retained-value paging, literal evidence checkpoints, actual effect receipts and current-source navigation. Keep the previously approved antipattern unchanged. |
| Acceptance correction to the coding example | The bounded read requested range [1,80), but the next example selected vector indices [200,240). | The example would throw instead of retaining evidence. | Select local indices [20,40) from the bounded read. This corrects the example without broadening the read. |

Validation and limits are recorded below. The final correction was identified by Codex while preparing exact editable blocks, after the Spell implementation run. It is a documentation-only acceptance correction, separately committed and checked by evaluating the range/slice against a temporary source fixture.

## Attachment disposition

The exact local attachment was `dev/context-skills-run/attached-suggestions.md`; it remains ignored, not force-added. Its author corrected an earlier diagnosis: existing `stored` already permits follow-up reading, and the observed problem was discoverability/misuse, not demonstrated lost data or communication corruption.

Accepted: concise existing-binding/stored retrieval recipes; shape-aware extraction before slicing; distinction between one-expression `!print` and name/expression-pair calls; bounded string-only descriptor with original identity; unchanged cap/default plus explicit CLI control; factual receipt-site text and rendered-context regression coverage. Map key summaries were not adopted because arbitrary inspection/traversal was outside the bounded string design. Deferred: a new bounded-view helper namespace, action IDs/ledger, broad diagnostics and scheduling-policy changes. These would enlarge the interface or runtime policy without being necessary for the concrete affordance defect. No claim of a preexisting lost/duplicated send was established.

## Existing API recipes and provenance

These are editable examples, not guaranteed fits for every aggregate cap or escaping pattern. Use an observed ID or a retained binding, never invent a UUID. `900` is a default-cap starting page size; reduce it (for example to `16`) when the full rendered contribution does not fit. A tight cap may not fit even fixed syntax. Inspect actual field shape before selecting fetched text.

```clojure
;; retained string, bounded initial page
'(!print (subs body 0 (min 900 (count body))))
;; observed stored string ID; same original value, no refetch
'(!print (let [body (stored result-id)] (subs body 0 (min 900 (count body)))))
;; skill provenance is at the tail, after the complete instructions
'(!print (let [body (stored result-id) n (count body)]
           (subs body (max 0 (- n 900)) n)))
;; retained vector, not a string
'(!print (subvec lines 0 (min 16 (count lines))))
```

For arbitrary UTF-16 text, avoid splitting a surrogate pair at a chosen page boundary. The generated string recipe implements a safe boundary; these simple existing-API examples require boundary choice by the caller. Preserve observed original IDs, evidence and current offsets before pruning. Reading a catalog entry or opaque marker is not equivalent to reading the instructions.

## Actual verification

All implementation validation used fresh JVMs; the coordinating live agent still runs baseline code and its skill catalog is a startup snapshot. Lead final executions on the integrated frozen checkout:

| Command | Tests | Assertions | Failures | Errors |
|---|---:|---:|---:|---:|
| `clojure -M:test-fast` | 525 | 4947 | 0 | 0 |
| `clojure -M:test-slow` | 247 | 968 | 0 | 0 |
| focused namespaces below | 315 | 2264 | 0 | 0 |

```sh
clojure -M:test -n spell.cli-test -n spell.receipt-policy-test -n spell.receipt-checkpoint-test -n spell.receive-test -n spell.runtime-test -n spell.race-condition-test -n spell.llm-test -n spell.launch-failure-test -n spell.user-test -n spell.composable-waits-test -n spell.coordinator-test -n spell.context-test -n spell.skills-test
```

Counts overlap across commands and must not be summed as unique coverage. Temporary lead logs: `/tmp/spell-context-skills-{fast,slow,focused}-final.log`. An earlier maintained fast run genuinely failed one obsolete bare-`stored` shape assertion (524 tests/4909 assertions, 1 failure); the context implementer migrated `spell.eval-test/value-store-test` to visible guidance, evaluated identity and unchanged-cap assertions. The final fast result above supersedes it.

Worker execution evidence, distinct from lead execution: context/skills 44 tests/447 assertions; evaluator 156/937; receipts plus context/skills 301/2080; final context/skills/receipt-checkpoint/API/CLI 68/674; all zero failures/errors. The real `cli/-main` subprocess regression was red with 5 failures in 1 test/21 assertions before implementation, then green. Temporary pilot shape validation was 2 tests/15 assertions green; the maintained deterministic E2E is 1 test/16 assertions green and is included in the final focused run.

Coverage includes default/minimum/raised caps, multiple descriptors/bindings, escaping and surrogate boundaries, fixed structural overhead, exact stored identity and pruning, lazy/infinite values, actual visible instruction bodies beyond 64 KiB, filesystem and packaged resource precedence/provenance; startup/generated/explicit-receive/actual wait/future/dormant sites, multiple messages, invalid-site rejection before drain/claim, earlier effects and no replay. At caps 128 and 180 the actual evaluator resumes across two nontrivial messages with effects exactly `[:earlier-effect :continued]` and never runs the replaced proposed tail. The portable E2E fetches/generates its large document once, retains its original ID, traverses ordered bounded pages across a real peer message/wait, and verifies completed artifact offsets/markers plus exact counters and matching edges. It uses a deterministic provider and host wait observer, not a live paid provider.

See `LIVE-ACCEPTANCE.md` and `LIVE-ACCEPTANCE.spl` for the post-handoff Codex NEW-runtime pilot. No paid live pilot was run here. Early/unproven wait is INCONCLUSIVE, not success inferred from a label. The separately reserved $20 belongs to that later pilot.

## Review findings and fixes

The original Fable reviewer read actual source/tests/static instructions and approved the design closure with explicit unread assertion tails; those tails are covered by subsequent integrated execution and the required new-context review recorded below. Concrete fixes: long receipt labels exceeded unchanged caps 128/180, so five compact factual labels replaced them without exempting the continuation; receipt-site validation moved before drain; obsolete skill-header and direct-stored test assumptions migrated; the initially inferred vector shape of `io/glob` was corrected from actual map-shaped output before the static edit. The user-approved baseline antipattern block must remain byte-identical exactly once in each transport; independent lead verification is recorded below.

## Known boundaries and unresolved OLD-runtime friction

The current coordinator still emits baseline opaque stored markers and the ambiguous old preemption/wakeup label; its in-memory namespaces/catalog do not reload from these edits. Large old-runtime read/describe results were recovered through the existing stored/binding API. Proposed tool actions were repeatedly superseded by incoming messages; only actual captures, disk and status/test receipts were counted. One initial worker incorrectly attempted a host-only implementation function as a Spell API; replacement fresh-JVM host validation recovered the task. A bare describe misuse and these discoverability issues were logged through feedback. Two fresh final-review attempts also failed before returning a review: one applied `subvec` to a result map; another called `io/grep` with five arguments. Both failures occurred in the OLD coordinating runtime and were logged as orchestration/tool-shape friction, not established patched-code regressions. Neither counts as review approval. A replacement genuinely fresh read-only reviewer was launched with explicit bounded `io/read-lines` recipes. No claim is made that this old process validates the patched runtime, that every audited example/credential/provider was exercised, or that all fixed syntax fits the minimum cap. No historical performance/benchmark rewrite or paid subprocess agent was used.

## Portable exact editable inventory

`CONTEXT_SKILLS_MODEL_FACING_INVENTORY.md` is the source-derived archived handoff at `07f7323`, before the separately recorded Codex acceptance correction. It contains exact baseline/handoff texts and unified diffs, a file/symbol/rationale surface index, all ten audited skill bodies, all three complete prompts, generated instruction/help/descriptor/receipt implementation sources and the complete resulting API/pilot documents. It is intentionally an editable source packet, not a prose replacement. Baseline texts are read from git object `7ee774b`, final texts from the frozen checkout. Private attachments, traces and profiles are not copied or force-added.

## Final closure and rollback index

Lead independently verified the exact baseline antipattern block: 1106 UTF-8 bytes, SHA-256 `75a94ca289b0fae40f238b99e0eb227ac5a15ff6e65a28f00ef3590e9cd093e3`, byte-identical and present exactly once in each of the three transport prompts. Fresh-JVM rendered-contribution validation passed caps 128/200/10000/50000 and all five receipt sites at 128. Bounded sanity ran 100 serializations per cap 128/10000/50000 after 20 warmups, with no capacity violation; exact observed timing receipts and complete rendered examples are in the inventory. This is not a comparative performance claim. The required genuinely fresh-context final Fable reviewer `:fable-final-safe` returned **APPROVE**, no blocking findings, on actual edge 21 after integrated green. This is distinct from the two failed review attempts and from the earlier design reviewer. The following explicit-file commits actually executed, each with rationale and the required run/notebook trailers.

### Granular rollback commit index

| Order | Commit | Scope / rationale |
|---|---|---|
| 1 | `692c9aa0e1dafb45f9702599cd364107155a5fe4` | Lossless stored-string disclosure and full skill bodies; context/skills/evaluator regressions. |
| 2 | `72f7b5054ae2d9e2d74217c35068da2945378698` | Actual receipt-site labels, validation before atomic drain, receipt/runtime/LLM/user regressions. |
| 3 | `0a61d4816b2b7ab2a5663ee169b7a68342321e96` | Four skill bodies and three transport prompts: bounded lossless inspection and durable evidence. |
| 4 | `237b090a7d49ba6164a60eac87db3718c0164bb9` | Public context-cap CLI option/regressions, canonical API text, portable pilot and deterministic real-peer E2E. |
| 5 | This documentation commit, subject **Record context-skill audit and exact model-facing inventory** | These two portable reports: audit, exact editable sources, runtime examples, validation and review receipts. Its own hash is supplied in the final handoff; a commit cannot embed its own content-derived hash. |

Resolve the documentation commit from the checkout with `git log -1 --format=%H --grep='^Record context-skill audit and exact model-facing inventory$'`. Roll back in reverse order when reverting the complete workstream. Individual groups are not promised independently test-green: context tests migrate receipt-call signatures, and the receipt/CLI acceptance tests rely on the integrated disclosure contract. Review those cross-group dependencies before a partial revert. No rollback, reset, branch switch, push, merge or PR was performed during this task.

### Fresh final review: actual reading and limits

The reviewer directly read the aggregate serializer/string-disclosure implementation (`context.clj:156-231`), full-body/provenance construction (`skills.clj:244-314`), receipt labels and validation-before-drain (`runtime.clj:165-255`), and actual assertion tails in receipt-policy, receive, wait/dormant checkpoint, skill precedence/filesystem/package >64 KiB, early context tests, and the CLI public-entrypoint/real-peer E2E. It checked the complete portable pilot and companion, changelog lines 1-71, and inventory surface index. It found no required documentation change. It confirmed the maintained E2E encodes the portable evidence conditions and does not establish a paid live pilot.

Inspection limits were explicit: serializer internals outside that range; receipt callers in `llm.clj`/`user.clj`; CLI parsing implementation; context tests beyond line 124; some skill/checkpoint/CLI test tails; changelog beyond line 71; and inventory complete source blocks, ten skill bodies, three prompts, rendered examples and hashes were not independently reread by this fresh reviewer. Those areas have earlier source/static review and/or the lead integrated test and exact-source/hash receipts documented here; they are not represented as fresh-review reads. The reviewer did not execute tests or shell commands. Its small read chunks still encountered OLD-runtime opaque storage, recorded as interface friction, not a patched-code finding. Revert in reverse dependency order; context and receipt contributions share cap semantics, so review cross-group test migrations when reverting an individual group.

## Complete per-skill audit (integrated from ignored working report)

# Static skill and prompt audit

Completed on the current checkout after `:main` approval. All seven bundled skill bodies and all three repository skill bodies were inspected in bounded reads; opaque catalog/describe markers were not counted as body inspection. Root `AGENTS.md` and config guidance were reviewed. Repository guidance identifies v0.4.0 and `docs/api.md` as the canonical public API/config reference.

## Skill dispositions and resolved paths

All paths below resolved to ordinary files under the reviewed checkout (no symlink redirects). Paths are shown repository-relative for portability. Coverage refers to the pre-edit bodies; line counts may now differ.

| Actual resolved path | Body coverage | Correctness, usefulness, redundancy; disposition |
|---|---|---|
| `resources/skills/coding/SKILL.md` | Complete, 149 lines | Keep research/implementation/verification loop and durable checkpoints. Intentional overlap links to context-efficiency rather than duplicating its full recipe. **Edited:** aggregate rendered budget explicitly includes syntax/escaping; opaque results require original-binding/stored paging before refetch, with shape-specific access and evidence/ID retention. |
| `resources/skills/context-efficiency/SKILL.md` | Complete, 80 lines | Useful bounded decision record and prune/persist lifecycle. **Edited:** real `io/glob` map shape and exit guard, evidence-first pruning, lossless retrieval recipes, one-expression `!print`, actual verification receipt retention, and a valid literal-checkpoint fresh-prefix example. Full recipes live here; coding cross-links them. |
| `resources/skills/mailing-list/SKILL.md` | Complete, 45 lines | **Keep unchanged.** Useful quiet posts, bounded digests, gap/ack semantics and receipt warnings; complements direct dispatch rather than replacing it. Actual quiet posting with `patterns/mail :post` succeeded during this task. Not every documented operation was executed. |
| `resources/skills/spell-api-and-cli/SKILL.md` | Complete, 80 lines | **Keep unchanged.** CLI/API/benchmark entrypoints and explicit `:agent-profile` align with canonical guidance; no concrete stale release statement found. CLI help remains authoritative. No claim that every CLI flag, benchmark field or sample model was exercised. |
| `resources/skills/spell-custom-agents/SKILL.md` | Complete, 110 lines | **Keep unchanged.** Useful model/agent/run separation, relative paths, namespaces, workers and transport guidance for external users. Deliberate audience-specific overlap with repository agent-config; do not merge or copy globally. Standalone alias setup and optional live-call wording could be clarified later, but no contract defect was established. |
| `resources/skills/spell-developer/SKILL.md` | Complete, 109 lines | **Keep unchanged.** Useful source-entrypoint and focused-test navigation. Suspected stale paths `src/spell/inbox.clj` and `config/spl-lib/patterns.spl` actually exist; no speculative renaming. Suite descriptions were inspected, not independently suite-tested. Repository shadow takes precedence in this run. |
| `resources/skills/spell-setup/SKILL.md` | Complete, 71 lines | **Keep unchanged.** Useful prerequisites, no-provider smoke and secret-safe auth discovery. Key/auth-file presence is not credential validation. Java minimum and checkout/install wording were not independently installation-tested; no speculative release rewrite. Repository shadow takes precedence in this run. |
| `.agents/skills/spell-agent-config/SKILL.md` | Complete, 78 lines | Active repository-only config skill. Useful canonical docs/API split, least-privilege exposure, secret handling and local examples. **Edited:** replaced broad `sed` dumps with a question-led 24-line dedicated-read packet and exact follow-up guidance. Intentional overlap with bundled custom-agents remains. |
| `.agents/skills/spell-developer/SKILL.md` | Complete, 115 lines | Active repository shadow. Useful repo/source/test navigation. **Edited:** stale “v0.3.0 is unreleased” claim replaced with checked-out `AGENTS.md`/`CHANGELOG.md` release pointers and canonical `docs/api.md`; avoids another soon-stale hardcoded version. |
| `.agents/skills/spell-setup/SKILL.md` | Complete, 93 lines | Active repository shadow. **Keep unchanged.** Useful checkout setup, free smoke path, secret-safe discovery, exact model guidance and consent before shell-profile changes. Credentials were neither printed nor validated; no user-home modifications made. |

## Exact changes

Seven skill/prompt files changed, plus this documentation-only report:

1. `resources/skills/coding/SKILL.md` — retrieval/aggregate-budget guidance and cross-link described above.
2. `resources/skills/context-efficiency/SKILL.md` — corrected glob example, bounded lossless retrieval, durable evidence/verification receipts and valid checkpoint prefix.
3. `.agents/skills/spell-agent-config/SKILL.md` — bounded dedicated-read workflow instead of broad shell file dumps.
4. `.agents/skills/spell-developer/SKILL.md` — release-independent canonical pointers.
5. `config/prompts/sysprompt-prefill.txt`
6. `config/prompts/sysprompt-message.txt`
7. `config/prompts/sysprompt-toolcall.txt`
   — all three have identical retrieval guidance near context management and clarify `!print`'s single expression versus `!call-now`/`!peek` name-expression pairs. Recipes use original retained bindings or observed stored IDs, `subs` for strings, `subvec` for vectors, documented result-map fields, aggregate syntax/escaping budgets, and evidence before pruning. Effects proposed in source are not execution receipts.
8. `dev/context-skills-run/skill-audit.md` — this audit, replacing the initial explicitly partial table.

The exact user-approved “Pruning evidence and rediscovering it” paragraph/example from `7ee774b` was neither rewritten nor duplicated. Byte-for-byte comparison of the full block through the next antipattern heading passed for each transport, once each. No global skill/prompt copying occurred.

## Approval correction and actual validation receipts

- Initial vector-shape inference for `io/glob` was wrong. Conditional vector-edit approval was not acted on. Actual documentation and calls established `{:exit N :out newline-delimited-text :err text}`. `:main` then approved the corrected map-shaped example. `(io/glob "SKILL.md" "resources/skills/context-efficiency")` returned exit 0 and the expected single path.
- Actual Spell evaluation of guarded `strings/split-lines` on `:out` returned `["src/main.py" "src/util.py"]`; the nonzero-exit example returned nil rather than falsely claiming no matches. Exit/error receipt data is retained separately.
- Actual string paging returned `"resources/skills"`. Actual stored line-vector retrieval returned the first two coding lines (`---`, `name: coding`). Empty string/vector pages returned `""` and `[]`. Oversized retained diff output was subsequently paged from its stored result map rather than refetched.
- Actual `wrap-cat` evaluation produced the standard wrapper containing a literal quoted checkpoint map. This validates prefix construction, not a paid recursive model call.
- Approved patch command exited 0. Scoped `git diff --check` exited 0. A separate invariant check confirmed identical retrieval guidance across all three transports and the exact immutable antipattern once per transport.
- Quiet implementation-board post executed as post 2; progress, correction and approval requests were actually dispatched to `:main`. No proposed dispatch was treated as a receipt.

## Runtime coordination and limits

`:context-v2` reported the approved same-cap inert stored-string descriptor: optional preview and UTF-16 length, actual observed ID, lossless unchanged original, and possible bare-reference fallback under tight caps. Static recipes depend only on existing binding/`stored`/`subs`/`subvec` access, not guaranteed descriptor fields or filesystem skill paths. Its reported runtime tests are not local test receipts from this audit worker.

This was a static documentation/prompt audit with targeted pure-example and invariant checks, not a full production/test-suite run. Other workers own production and tests. No commits, branch changes, paid agents/live calls, user-home edits, or files outside the approved scope were made by this worker. Unchanged skills were retained on the evidence available, not certified by exhaustive execution of every example.

## Approved final page-size refinement

At `:main`'s explicit request, the primary string-page examples in `resources/skills/context-efficiency/SKILL.md` and all three system prompts now use about **900 UTF-16 code units** as an illustrative starting page under the default 10k rendered context-contribution cap. This is explicitly **not a fit guarantee**. Tight caps, heavy escaping and aggregate rendered overhead require smaller pages (for example, 16 units) and one inspected value at a time. This supersedes the earlier 16-unit primary example; the lossless retrieval, evidence-retention and no-refetch guidance is unchanged.

Actual refinement receipts: the four-file patch exited 0; scoped `git diff --check` passed; each transport still contains the exact protected `7ee774b` antipattern once. Actual Spell evaluation returned lengths 900 for a 1,000-unit string and for text selected from the retained stored result map, 16 for the fallback page, `"short"` for a short string, and `""` for an empty string. Counting `"😀"` returned 2, confirming the UTF-16 unit convention used in the examples. These are expression checks, not guarantees that arbitrary escaped pages fit the rendered context-contribution cap.

The original audit remains at its approved ignored path. Main has integrated its complete disposition and validation record here; the audit worker did not force-add it, stage files or expand scope.
