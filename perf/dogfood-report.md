# Mailing-list dogfood report: runtime audit 001 and 002

## Outcome and evidence boundary

The required autonomous audit **did use the new mailing-list system substantively**: audit002 had root `:main` plus `:core-bench`, `:lifecycle-bench` and `:context-bench`, onboarded/subscribed with disjoint implementation responsibilities and tracked worker-return edges. It produced 14 benchmark families / 80 cases, all passing. This is not a claim of a successful outer audit run: baseline measurement completed, then root reporting failed and the outer process exited **1**, with supervisor-recorded cost **$22.1132**.

Audit001 was supervisor-aborted after **125 recorded response markers**, exit **143**, cost **$15.5493**. These costs are attributed to the supplied supervisor records, not independently recomputed billing. The deterministic performance cases themselves made no paid model calls. The requested orchestration configuration was Codex Astra at xhigh; this reviewer did not change providers, effort, budgets or agent configuration.

Evidence under `perf/results/`:

- `board-evidence.json`: recovered historical board observations, trace-node/binding provenance, message catalog and limitations.
- `worker-receipts.edn`, `context-worker-receipt.edn`: saved received worker results. Their test claims remain attributed to those workers.
- `pilot.json`, `pilot-receipt.json`, `baseline.json`, `baseline.log`, `baseline-receipt.json`: parent-owned measurements.

The supplied audit001 investigation and corrected arity reproduction additionally cite the frozen runs' verbose logs, traces and analysis extracts. This reviewer inspected those supplied evidence snapshots, not a running old process. **No old globals exist in this fresh process; no board was initialized or queried to manufacture historical evidence.** `board-evidence.json` is a recovered bounded export, not a successful final live-board dump by audit002 root. Full frozen trace/log bundles remain supervisor-owned run evidence.

## Audit002: substantive workflow and handoffs

Root was the sole observed owner/administrator of list `:audit`. Worker onboarding and subscriptions were followed by actual posts, read-only digest pages, acknowledgements and urgent notifications. Disjoint ownership was core/lifecycle/context modules and their worker reports; root owned harness integration, pilot and full measurement. Board updates did not substitute for tracked request completion.

The export contains **13 observed post receipts, 19 digest-page observations**, IDs **1–13**, and no gap in the observed pages. Maximum observed high-water is 13. Maximum successful ack receipt cursor by handle: `:main` **11**, `:core-bench` **9**, `:context-bench` **11**, `:lifecycle-bench` **8**. These are observed lower bounds, not an assertion of complete final live state or acknowledgement through 13.

| IDs / author | Substantive exchange | Trace-backed receipt into context |
|---|---|---|
| 1, main | Shared measurement contract: setup/checks/cleanup timed; independent live/cleanup probes; clear all strong roots; small evidence; caller allocation excludes workers | main node 17; core 20; context 27; lifecycle 38 |
| 2, core-bench | Five-family runnable first draft, non-tail self-call result, retained fixtures, exact provider outputs; compile next | core 20; context 27; lifecycle 38; main 44 |
| 3, context-bench | Five-family draft; isolated stores, board/receipt contrast, equal total contention work; no pilot/harness | context 27; lifecycle 38; main 44; core 48 |
| 4, lifecycle-bench | Four smallest-case probes passed, separation of coordinator/dispatch/dormancy/API work, repeated-cycle checks next | main 44; core 48; lifecycle 50; context 60/67 |
| 5, context-bench | Corrected map-serialization assertion: parsed quoted form must be evaluated; receipt adds zero stored entries | main 44; core 48; lifecycle 50; context 60/67 |
| 6, core-bench | Draft delimiter repaired; 11 tiny checks pass; trace nodes are a vector; 20-call and cleanup checks next | main 54; core 57; context 60/67; lifecycle 64 |
| 7, lifecycle-bench | Completed module/report and 11 bounded cases; handoff with close-not-join limitation | main 54; core 57; context 60/67; lifecycle 64 |
| 8, core-bench | URGENT shared deps.edn blocker: literal line-number prefixes produce Invalid number: 1:; worker did not edit non-owned file | lifecycle 64; context 67; main 69; core 84 |
| 9–10, context-bench | Final verification blocked before namespace load; explicit unverified completion-barrier refinement; tracked handoff must be superseded after repair | context 83/93; core 84; main 85/91 |
| 11, core-bench | Root repaired deps; final rerun passed, 20-call return 43, trace checks and injected cleanup; blocker resolved | main 91; context 93 |
| 12, main | Pilot: all 14 smallest cases PASS, exit 0, ~26.04 s; baseline next | main 103, board-final-page |
| 13, context-bench | Final source and completion-barrier refinement now verified; eight tiny cases plus injected-failure cleanup pass; supersedes blocked handoff | main 103, board-final-page |

Root received saved tracked results on **edge 1 (core), edge 2 (lifecycle), edge 3 (context)**. Core's saved claim covers eleven tiny probes, exact 20-call return 43, padded trace/log checks and idempotent/injected cleanup. Lifecycle's claim covers eleven bounded cases, retirement/generation/isolation and twice-called cleanup. Context's final saved return covers eight tiny cases plus injected contention failure and supersedes its earlier blocked report. See each original `audit-*.md` for exact executed commands, output and authorship limits. Parent subsequently ran the 14-case pilot and 80-case baseline; these were not workers' performance measurements.

Message targets and `sent:true` show dispatch/acceptance, not by themselves insertion into a model context. The digest observations above independently establish delivery into reader contexts; ack receipts establish cursor changes. No successful final baseline board post, ack-through-13 receipt or root board dump was recovered. Proposed operations after node 103 must not be promoted into historical facts.

## Audit001: successful board use, unsuccessful artifact production

Run `2026-09-07-runtime-audit-001`, measured base `67b7cf7ba919a9dca9c78a5b9e9e0ed5116d5c1f`, began 11:18:27 UTC and was terminated 11:26:02. Its first-to-last recorded call span was 442.273 seconds. The 123 partial trace nodes lack completion/end fields; they are not 123 established failed tasks. All 123 recovered prompt prefixes parsed with their missing tail closed.

The analysis counted **generated operation occurrences**, not executed effects: 84 `!peek`, 16 `!call-now`, 24 `!describe`, 257 `io/read-lines` across 28 paths, including **62 assignment reads**, 31 runtime source reads and 23 context source reads. There were 17 explicit persist markers in 13 responses, but the first source-subset persistence was not generated until response 67. No worker benchmark module/report writes and no benchmark compile/test command were generated. These facts support a stalled research/prototype workflow, not board deadlock.

Context presentation contributed materially. Of 73 trace prefixes whose latest operation was a peek, 65 contained a stored result. Of 56 observed assignment-read bindings, 47 were references. There were 130 distinct visible stored UUIDs, not 130 measured heap allocations of known size. Node 8's combined descriptions became a stored reference; node 9's 7800-character slice also became stored when batched with other results. The budget is aggregate across values and binding syntax (`src/spell/context.clj:7,157–198`); `src/spell/macros.clj:205–250` serializes batch results together and gives peek an edit-time prune marker. Stored data remains retrievable after its source binding is pruned. Some useful results were inline, so storage alone cannot explain all repeated research.

Actual board use nevertheless succeeded:

- Node 9 established owner main, list audit and main subscription at cursor 0.
- Child roots 11/12/13 identify runtime-auditor/lifecycle-bench/context-bench onboarding; node 71 listed all four subscribers; node 89 showed three pending tracked lifecycle edges.
- Node 31 returned `{:customized :dispatch :receipt :tiny}` and a successful post ID 2. This proves installation of shared executable dispatch customization followed by valid use.
- Node 34 showed lifecycle ack of ID 1 and post ID 3; runtime's node 107 digest included that peer evidence and root harness message ID 4 with no gap.
- Nodes 110/118, 112 and 119 captured runtime/main/lifecycle acknowledgements through ID 4.

Those four retained messages concerned ownership, source convention, lifecycle methodology and measurement boundaries, not performance results. The board worked as a coordination mechanism while artifact production stalled.

## Customization: distinguish implemented, installed and merely proposed

The feature tests/demo had already proven source customization; this fresh review did not rerun them. Audit001 **actually installed and used** a shared dispatch wrapper requiring E:-prefixed summaries and provenance paths, evidenced by node 31 and the valid post. That observation is not a test of every rejection branch.

Audit002 has a proposed `perf/board-customization.spl` wrapper file, but it **was NOT installed**. A file's existence, a planned wrapper, or an E:-prefixed pilot summary is not an installation receipt. Audit002 substantively used the native shared board; it should not be credited with a second customization installation. The captured-snapshot benchmark intentionally studies an optional old full-board-return anti-pattern, not required/current customization guidance. Compact receipts are already the recommended pattern.

## Failures, classifications and bounded resolutions

### 1. Caller API errors, with interface friction — highest workflow priority

**Correct arity diagnosis:** `(patterns/mail :info)` and `(patterns/mail :lists)` omit the required second map. Correct forms are `(patterns/mail :info {})` and `(patterns/mail :lists {})`.

The isolated supervisor reproduction changed only the argument vectors under the mailing-list test adapter:

```text
[:info]      -> Unbound symbol: arguments
[:lists]     -> Unbound symbol: arguments
[:info {}]   -> success, map
[:lists {}]  -> success, sequence
```

`config/spl-lib/patterns.spl:1619–1622` defines `[operation arguments]`; `docs/mailing-list.md:40` and `src/spell/patterns.clj:146` document the two-argument API. `src/spell/eval.clj:360–364,391–399` binds supplied parameters by a sequence zip; a missing argument leaves `arguments` unbound. An explicit arity diagnostic would be more helpful, but **there is no evidence here of a dispatch collision or a correctly supplied map being lost**.

Exact boundary: audit002 verbose lines 284–285 end a batch with a valid digest; lines 287–289 advance to the next model response. The milestone write at line 295 calls missing-map info/lists. Recovery line 299 reports the unbound symbol; line 303 is nested recovery of the same failure, not a second independently established digest failure. Root feedback at node 18 mislocalized the earlier batch. The reproduction's initial deps.edn parse obstacle was probe setup, not the cause of the already-running process's arity error.

Other caller mistakes: audit001 unquoted effect at log 213; vector-path grep at 487; vector passed as a single `agents/send` target at 566; out-of-bounds source subvec at 682. Returned error maps for an incorrectly grouped `io/git` command and nonexistent source path differ from throwing recoveries. Audit002 lifecycle-return suffixes called unavailable Spell `hash-map` (log 1327/1433); map literals allowed saved returns. Host Clojure supports forms such as mapv/assert that Spell does not—host benchmark source is not evidence those forms are legal in agent suffixes.

### 2. Numbered presentation written as source — repaired shared-file mistake

Audit002 root wrote `io/read-file` numbered display text into deps.edn. Message 8 and feedback node 84 identify the resulting `Invalid number: 1:` before namespace load. Root repaired the raw source; core ID 11 and context ID 13 establish actual successful final reruns. This was source/presentation confusion, not a new runtime parser regression. Use raw slurp or safe structured replacement, never a numbered presentation string as file contents. The fixture SubVector retention label is a separate issue, corrected after measurement.

### 3. Same-call partial commit and uncertain receipts — confirmed execution semantics

A multi-effect `!call-now` is not an all-or-nothing transaction. In audit001's vector-send batch, earlier ack/post effects committed before `agents/send` threw, so the batch's result bindings were not injected. Node 89 independently proved cursor 3/high-water 4 and the already-posted harness message. Retrying the entire batch would have duplicated it. A proposed feedback write after the throwing send never executed; only two feedback records actually persisted, at nodes 8 and 85.

An incoming message can also replace the generated trailing action before evaluation. Root's original harness-write proposal was followed by `msg-6690`; node 71 established `:harness? false`, and node 75 later captured the real write receipt. Receiving semantics are implemented at `src/spell/runtime.clj:236–250` and `src/spell/llm.clj:569–585`. Source proposals, sent flags and received peer replies do not establish that one's own pending effect executed.

**Practice:** short mutation batches; record immediate request edges; inspect disk/cursors/outstanding obligations when execution is uncertain; retry only missing prerequisites. Log a failure in a separate short successful operation rather than behind another risky effect. Do not weaken receipt semantics merely to avoid recovery work.

### 4. Self-notification preemption — ergonomic friction, not a proven correctness defect

Context worker's feedback records `post!` notifying its author. These notifications superseded proposed ack/edit/probe chains; the worker used owned-file state and actual digest cursors before retrying. Audit001 recorded `msg-6690`, `msg-6886`, `msg-6938`, `msg-6972` and `msg-6980`; the annotation 'preempted or awakened' is not proof that all five discarded work. Prefer quiet posts and targeted urgent notices when appropriate, separate acknowledgements from risky dependent actions, and retain tracked handoff obligations. Possible notification-policy improvements require their own review, not a claim of deadlock here.

### 5. Benchmark drafts and persistence limitations — not runtime regressions

Workers repaired draft delimiter mistakes, a conditional writer scope trying to close nil, treating trace node vectors as maps, and comparing parsed quoted receipt syntax directly with a map. Lifecycle probe classpath and parenthesis mistakes were command-construction errors. Final recorded probes passed. Context's completion promises corrected the inference that cancelled-Future state alone proves body termination; timeout/uninterruptible-worker behavior was not injected.

Supervisor review subsequently fixed result persistence: atomic per-case checkpoints, stale-result invalidation, fresh launcher receipts and rejection of zero-exit incomplete results. Six Clojure tests/34 assertions and six Python subprocess tests passed, according to independent supervisor validation. These are benchmark reliability fixes outside timed functions, not performance optimizations. The original baseline JSON/log/receipt remain unchanged.

Two explanatory corrections matter: the ten-line SubVector retains all backing file lines (about 6.13 MB at 100k lines), and the quiet-board fixture blocks real sends but does not count attempts. Neither corrected note changes the measured workload or proves notification behavior beyond its stated scope.

### 6. Audit002 final reporting failure — unresolved outer-run failure, completed here as a separate handoff

At trace node 103 / verbose line 1499, a malformed multi-binding continuation closed quoted `!call-now` before `local-lines-source`, leaving that symbol unbound. Proposed ack13, baseline board post and export were not successful receipts. Node 104 / line 1515 recovery then called unavailable Spell `mapv`; recovery limit 2 was exceeded and root exited 1. The already completed baseline survived. This report-only fresh reviewer supplies the missing handoff; it does not rewrite that exit status into success.

## What improved from audit001 to audit002

Audit002 began with compact checked source packets, exact API examples, clear ownership and prototype-first milestones. Core/context/lifecycle wrote first drafts before exploratory source reads, exchanged concrete compile/blocker/completion evidence, and root measured the suite. Audit001 repeatedly reread source/assignment without worker modules or a compile/test command.

This is a useful **descriptive before/after workflow observation, not a controlled causal experiment**. Prompt content, run history, errors, context state and other conditions differed; no numerical productivity speedup or causal effect of xhigh effort can be inferred. The actionable next-run policy is to track created artifacts and passing prototypes, bound repeated reads, preserve verified contracts, and escalate a specific blocker instead of indefinite polling or rediscovery.

## Deviations and limits

The old run's proposed final board dump/post never completed; the supervisor recovered historical evidence. Audit001 was aborted and audit002 failed reporting despite successful measurements. Earlier worker probes incidentally produced Clojure error reports outside the worktree; context's configured feedback tool also wrote to the supervisor's external run directory. These tool-mediated deviations are disclosed in the worker reports, not repeated by this reviewer. This pass wrote reports only under perf, did not use external feedback destinations, did not access a live board, spawn workers, rerun the baseline, mutate Git or optimize code.

The current fresh context fulfills the independent Spell review of the existing implementation. Codex independently reviews the resulting PR/reports; no claim of Codex approval is made here. Remaining measurement limits and next optimization criteria are in [findings-and-targets.md](findings-and-targets.md).
