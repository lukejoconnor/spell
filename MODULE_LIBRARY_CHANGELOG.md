# Module library change log

Run: `2026-09-07-module-library-001`
Accepted base: `8ccb496fa7e7057df8821281ec9341729e8e9d16`

## Previous behavior

The bundled catalog exposed `check-result`, `ralph`, `fix-loop`, `team`, `relay` and `mailing-list`. The first four packaged task/retry/verification/team policies as defaults. Their generated planner/worker/verifier/reflector teaching and policy-oriented tests made those workflows look like the preferred reusable interface. The board exposed dispatcher aliases and specialized spawning composition. Module loading was bundle-oriented rather than ordinary editable project/HOME discovery.

## Why it was a problem

Policy bundles mixed reusable mechanisms with assumptions about how callers should plan, retry, validate or manage worktrees. Board-specific spawning made ordinary configured child composition harder to see and encouraged readiness assumptions without actual subscriptions or dispatch receipts. Dispatcher aliases obscured directly callable executable entries. Bundle-only loading made saving and reusing a task-specific module unnecessarily indirect. Documentation could imply that delivery, acknowledgment, ownership metadata or a proposed action proved more than the runtime actually guarantees.

## What changed

- Retired the four `check-result`, `ralph`, `fix-loop` and `team` bundle files and policy-specific tests/teaching. Retained `relay` unchanged and retained independent evaluator/runtime/lifecycle, ownership, journaling and shell-thunk regressions. The tiny explicit three-request lifecycle fixture is a runtime test, not a replacement retry policy.
- Added ordinary project/HOME/bundle discovery: canonical run-start worktree root (including outside-Git use), precedence `.spell/modules` in project, then HOME, then bundled resources. The selected origin is observable. Malformed selected files fail with their path and never silently fall back. Flat lowercase-kebab filenames are discovery rules, not a restriction on arbitrary programmatic keyword IDs.
- Definitions remain one unquoted inert map. Catalog/install do not execute entry bodies. Reinstall preserves live source, immutable installer owner, origin and revision/journal continuity; it is not a reload. Explicit `io/write-file` of `pr-str(patterns/source ...)` saves ordinary definition data. A fresh run rediscovers code with a new registry/owner baseline; board/coordinator/ownership state is not persisted by that save.
- The board now exposes direct consumers: `:init`, `:info`, `:lists`, `:create`, `:subscribe`, `:subscribe-many`, `:unsubscribe`, `:post`, `:post!`, `:notify`, `:message`, `:digest`, `:ack`. Internal reusable entries remain explicit; dispatcher/spawn aliases were removed. Default initialization selects `[:general]`; explicit nonempty distinct lists replace that default. Initialization and creation atomically subscribe their actual caller and return receipts.
- Documented and tested caller-selected configured `agents/spawn-ask` with a complete child startup that installs and atomically subscribes before the first model generation. Only onboarding setup is caught; failure is a tracked `:spell/child-failure true`, `:phase :onboarding` result. Task generation/recovery is outside that catch. This is ordinary caller convention, not a runtime-enforced onboarding mechanism or new agent profile.
- Preserved read-only digest diagnostics, exact-token monotone acknowledgment, epoch invalidation, retention gaps and per-subscriber cursor semantics. Read required `:message` bodies and process evidence/gaps before acknowledging the exact observed token. Quiet posts send nothing; explicit notification attempts every subscriber, including a subscribed author/caller, with per-target receipts. Delivery and acknowledgment are not comprehension; cooperative author attribution and subscriptions are not authentication, privacy or access control.
- Updated current docs, public examples, three prompt inventories, repository AGENTS inventories, bundled mailing-list skill and the narrowly approved tracked developer-skill inventory entries. Added complete relay/configured-board startup examples, an inert greeting definition, project/HOME/save-reload teaching and caller-owned shell/agent work. No generic git worktree framework or automatic commit/reset/cleanup replacement was introduced.
- Kept independent compaction receipt, terminal cleanup and consecutive-error success-reset fixes/regressions. Historical plans, earlier acceptance reports and older logs were not rewritten.

## Design decision

Retain small transparent primitives and editable shared source; let callers deliberately compose workflow policy and select already configured agents. Keep catalog summaries separate from full executable source, call opaque shared entries without gratuitous source fetches, and preserve immutable ownership/origin/journal attribution outside editable definitions. Explicit options acknowledge a deliberate nonowner edit, not approval or a security boundary. No new persistence service, broker, scheduler, default model, access-control system or generic workflow framework is part of this change.

## Validation and limitations

Maintained settled validation: `clojure -M:test-fast` **563 tests / 5399 assertions**, `clojure -M:test-slow` **317 / 1801**, both raw exit 0 with zero failures/errors; `node scripts/check-docs.mjs` exit 0 (source/conflict-marker check only). Raw attempt03 logs/exits are under `.spell/module-library-001/`.

Reusable scripted acceptance: discovery **9 / 111**, board **10 / 274**, raw exit 0. It requires a caller-chosen fresh attempt name, refuses overwrite, and verifies nonempty selections, resolved vars and actual positive/exact test counts. Providers are explicitly scripted; no autonomous-model decisions or paid pilots are claimed.

**Full exact-startup acceptance is not green.** Corrected artifact attempt02 ran **4 tests / 29 assertions, 5 failures, 0 errors, exit 1**. Default board setup, exact configured-board startup and inert module checks passed. All five failures concern the retained relay: registered workers fail with `Self-calls cannot run inside computation futures` before worker/verifier provider generation. The enclosing relay returns `:solved false` and two `:stuck` reports. `src/spell/runtime.clj`, `src/spell/llm.clj` and the retained relay bundle are unchanged against the accepted base. No relay/runtime edit was authorized; the public relay guide now discloses the observed limitation. Passing retained unit tests do not establish this lifecycle path.

Initial failed tests, the interrupted maintained02 run, failed exact-startup attempts and fixture-contamination evidence remain preserved. The four confirmed fixture outputs were archived byte-for-byte and deleted only under explicit lead authorization after exact SHA256 verification; all directories and other files were preserved. See `.spell/module-library-001/ACCEPTANCE_REPORT.md` for commands, receipts and caveats.

## Exact editable teaching evidence

`.spell/module-library-001/teaching/` contains complete editable accepted-base/current source snapshots, including every changed teaching/generated-message source and all deleted bundle messages; explicit absence markers represent additions/deletions. `manifest.json` records byte counts and SHA256, `diff/` contains full diffs, and `SHA256SUMS` verifies present snapshots. Full source is deliberately a superset of teaching, not a set of truncated previews. Dynamic message-construction expressions are preserved exactly; this is not a claim to enumerate every possible interpolated runtime value. Historical user artifacts were not blanket-regenerated.
