# Module library change log

Run: `2026-09-07-module-library-001`
Accepted base: `8ccb496fa7e7057df8821281ec9341729e8e9d16`

## Previous behavior

The bundled catalog exposed `check-result`, `ralph`, `fix-loop`, `team`, `relay` and `mailing-list`. The first four packaged task/retry/verification/team policies as defaults. Their generated planner/worker/verifier/reflector teaching and policy-oriented tests made those workflows look like the preferred reusable interface. The board exposed dispatcher aliases and specialized spawning composition. Module loading was bundle-oriented rather than ordinary editable project/HOME discovery.

## Why it was a problem

Policy bundles mixed reusable mechanisms with assumptions about how callers should plan, retry, validate or manage worktrees. Board-specific spawning made ordinary configured child composition harder to see and encouraged readiness assumptions without actual subscriptions or dispatch receipts. Dispatcher aliases obscured directly callable executable entries. Bundle-only loading made saving and reusing a task-specific module unnecessarily indirect. Documentation could imply that delivery, acknowledgment, ownership metadata or a proposed action proved more than the runtime actually guarantees.

## What changed

- Retired the four `check-result`, `ralph`, `fix-loop` and `team` bundle files and policy-specific tests/teaching. Retained the `relay` module source and independent evaluator/runtime/lifecycle, ownership, journaling and shell-thunk regressions. The tiny explicit three-request lifecycle fixture is a runtime test, not a replacement retry policy.
- Added ordinary project/HOME/bundle discovery: canonical run-start worktree root (including outside-Git use), precedence `.spell/modules` in project, then HOME, then bundled resources. The selected origin is observable. Malformed selected files fail with their path and never silently fall back. Flat lowercase-kebab filenames are discovery rules, not a restriction on arbitrary programmatic keyword IDs.
- Definitions remain one unquoted inert map. Catalog/install do not execute entry bodies. Reinstall preserves live source, immutable installer owner, origin and revision/journal continuity; it is not a reload. Explicit `io/write-file` of `pr-str(patterns/source ...)` saves ordinary definition data. A fresh run rediscovers code with a new registry/owner baseline; board/coordinator/ownership state is not persisted by that save.
- The board now exposes direct consumers: `:init`, `:info`, `:lists`, `:create`, `:subscribe`, `:subscribe-many`, `:unsubscribe`, `:post`, `:post!`, `:notify`, `:message`, `:digest`, `:ack`. Internal reusable entries remain explicit; dispatcher/spawn aliases were removed. Default initialization selects `[:general]`; explicit nonempty distinct lists replace that default. Initialization and creation atomically subscribe their actual caller and return receipts.
- Documented and tested caller-selected configured `agents/spawn-ask` with a complete child startup that installs and atomically subscribes before the first model generation. Only onboarding setup is caught; failure is a tracked `:spell/child-failure true`, `:phase :onboarding` result. Task generation/recovery is outside that catch. This is ordinary caller convention, not a runtime-enforced onboarding mechanism or new agent profile.
- Preserved read-only digest diagnostics, exact-token monotone acknowledgment, epoch invalidation, retention gaps and per-subscriber cursor semantics. Read required `:message` bodies and process evidence/gaps before acknowledging the exact observed token. Quiet posts send nothing; explicit notification attempts every subscriber, including a subscribed author/caller, with per-target receipts. Delivery and acknowledgment are not comprehension; cooperative author attribution and subscriptions are not authentication, privacy or access control.
- Updated current docs, public examples, three prompt inventories, repository AGENTS inventories, bundled mailing-list skill and the narrowly approved tracked developer-skill inventory entries. Added complete relay/configured-board startup examples, an inert greeting definition, project/HOME/save-reload teaching and caller-owned shell/agent work. No generic git worktree framework or automatic commit/reset/cleanup replacement was introduced.
- Kept independent compaction receipt, terminal cleanup and consecutive-error success-reset fixes/regressions. Historical plans, earlier acceptance reports and older logs were not rewritten.

## Design decision

Retain small transparent primitives and editable shared source; let callers deliberately compose workflow policy and select already configured agents. Keep catalog summaries separate from full executable source, call opaque shared entries without gratuitous source fetches, and preserve immutable ownership/origin/journal attribution outside editable definitions. Explicit options acknowledge a deliberate nonowner edit, not approval or a security boundary. No new persistence service, broker, scheduler, default model, access-control system or generic workflow framework is part of this change.

## Independent acceptance corrections

1. **Registered-agent startup.** Previously an agent registered inside a computation inherited the computation marker, so the retained relay failed before its worker or verifier could generate. The independent dormant-agent runner now clears that marker and its owner. Actual computation self-calls remain prohibited. New regressions exercise two request/revival cycles, the exact relay startup and the preserved guard.
2. **Prompt accuracy and packaged tests.** The three base prompts previously listed `patterns/` as always available, encouraging calls outside its configured effect scope. All three now describe it under configurable effects. The packaged-JVM test inherited personal modules and could fail a fixed bundle-count assertion on a valid installation; its child now receives an isolated temporary HOME.
3. **Documentation.** Source-only validation missed five links outside the rendered documentation root. Links now follow the existing canonical repository-source convention. Examples describe the corrected lifecycle and current usage; private run receipts and obsolete failure claims are kept in the acceptance evidence rather than public usage instructions.

## Validation and limitations

After the runtime and teaching corrections, fresh maintained suites passed: `clojure -M:test-fast` **563 tests / 5399 assertions**, `clojure -M:test-slow` **320 / 1821**, both zero failures/errors and exit 0. Focused runtime/continuation checks **83 / 354** overlap those suites. The isolated personal-module packaging reproduction now passes **1 / 3**. `npm run docs:check` passed both source validation and the production build.

The three-case offline pilot passed **33 checks**. The live Codex/Astra xhigh pilot then completed **10 model calls / $0.925734** with the same **33 checks** passing. Actual model programs consumed digest/body/token receipts, called an opaque shared module, deliberately changed it from an offset of one to two with explicit owner acknowledgement, and observed the shared edit. A fresh API run called deliberately saved source; a real registered relay worker and verifier generated and the root collected their report. Setup and filesystem saving were supplied by the host; the replacement function was supplied in the task. This proves the recorded operations and model-generated use, not autonomous discovery of a design or general reasoning correctness. Retention/notification races and exhaustive precedence remain deterministic-test coverage; this was no 48-hour soak or performance certification.

The main implementation run had reported scoped approval while its exact relay startup still failed (**4 / 29 / 5 failures**, exit 1). Baseline reproduction proved the runner defect predated this batch. Independent acceptance fixed it under the existing authorization, preserved the original failures, and verified the corrected runtime. The main raw logs, reviewer deliveries, fixture contamination/cleanup receipts and later checks remain in the durable run bundle. Four accidentally created fixture modules were archived and hash-verified before removal; their tests now create explicit Git boundaries and enforce write containment.

Fresh independent Fable review of the acceptance corrections is recorded separately from the main run's scoped reviews. The final acceptance report identifies the actual returned verdict, complete cost ledger and read/validation limits.

Rollback can follow the granular commits: discovery with origin propagation; mailing-list API with onboarding examples/tests; policy-bundle retirement with its inventory updates; dormant-runner correction with its regressions. Revert corresponding teaching when reverting an interface. No push or merge was performed.

## Exact editable teaching evidence

The final packet is `notebook/results/spell-runs/2026-09-07-module-library-001/editable-instructions.md` in the separate project notebook. It contains complete editable baseline/final source, prompt and skill pages, changed host-message forms, explicit deletions and SHA256 manifests. Dynamic message-construction expressions are preserved exactly; interpolated runtime values remain in the actual run traces. The earlier worker packets and all failed attempts are retained as historical evidence. Regeneration refuses to overwrite modified editable copies.
