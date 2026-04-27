# Ship Plan: Kimi K2.6 Fireworks Support And 32+32 Benchmark

## Goal

Add first-class Spell support for Fireworks `kimi-k2p6`, then run Kimi K2.6 on the same 32 Terminal-Bench and 32 SWE-bench Lite items used for the prior Kimi K2.5 weak-model evaluation. Report whether Kimi K2.6 improves over Kimi K2.5 on accuracy, fatal Spell errors, cost, and latency.

## Current Context

- Prior entry: `notebook/entries/2026-04-20-weak-model-eval-kimi-glm.md`.
- Prior Kimi K2.5 model spec: `fireworks:kimi-k2p5`.
- Prior TB result file: `benchmarking/results/gcp/tb-kimi-full-v2/terminal-bench/terminalbench_20260422_010124_556323b7.jsonl`.
- Prior SWE result file: `benchmarking/results/gcp/sb-kimi-full-v2/unified/swebench_20260422_010032_8ca81ecf.jsonl`.
- Prior K2.5 headline from those JSONLs:
  - Terminal-Bench: 0.0% (0/32), 27 error rows, $2.14 recorded cost.
  - SWE-bench Lite: 0.0% (0/32), 19 error rows by JSONL status and 27 fatal Spell errors per notebook classification, $3.79 recorded cost.
- Main repo dirty state before planning: untracked `.claude/plans/` and `workspace/` only. No tracked overlapping code changes observed.
- Environment note: current sandbox is `workspace-write` with restricted network. The ship workflow can plan normally, but implementation worktree setup, PR creation, Fireworks smoke calls, `gh`, `gcloud`, and GCP benchmark dispatch may require approvals/escalation.

## Model Evidence

Primary Fireworks sources currently show:

- Fireworks model page: `fireworks/kimi-k2p6`, full API model path `accounts/fireworks/models/kimi-k2p6`, state Ready, serverless supported, created 2026-04-20, context length 262.1k tokens, function calling and image input supported.
- Fireworks pricing on the model page: `$0.95 / $0.16 / $4.00` per 1M tokens for input / cached input / output.
- Fireworks Turbo docs also mention `accounts/fireworks/routers/kimi-k2p6-turbo`, but this plan targets the standard model unless the user explicitly asks for Turbo.
- Kimi's own K2.6 page says K2.6 is the latest open-source model and claims stronger coding, long-horizon execution, and agent workflow reliability than prior Kimi models.

Sources:
- https://fireworks.ai/models/fireworks/kimi-k2p6
- https://docs.fireworks.ai/guides/serverless-products
- https://www.kimi.com/ai-models/kimi-k2-6
- https://www.kimi.com/resources/kimi-k2-6-pricing

## Implementation Scope

Likely Spell repo changes:

- `data/pricing.edn`
  - Add `"accounts/fireworks/models/kimi-k2p6" {"input" 0.95 "cache-read-input" 0.16 "cache-write-input" 1.1875 "output" 4.00}`.
  - `cache-write-input` follows existing repo convention of 1.25x uncached input when Fireworks does not separately list write pricing.
- Tests, likely `test/spell/llm_test.clj` or `test/spell/provider_test.clj`
  - Add a focused pricing/cost lookup or provider construction assertion if there is an established nearby pattern.
- No expected provider code change:
  - Fireworks provider already expands short IDs to `accounts/fireworks/models/<model>`.
  - `detect-chat-template` defaults non-GLM/non-DeepSeek models to ChatML, same as Kimi K2.5.
  - Fireworks K2.5 already works via the completions API and the K2.6 page advertises normal serverless availability.

Potential contingency:

- If a pilot request to `fireworks:kimi-k2p6` fails specifically because Fireworks exposes K2.6 only through chat completions for this account/model, then implement a Fireworks chat-completions fallback or a separate provider path. Do not add this preemptively.
- If the standard model rate-limits or is unavailable but Turbo works, pause before switching to `kimi-k2p6-turbo`, since it changes both cost and latency comparability.

## Prior Item Slices To Reuse

Terminal-Bench 32:

`blind-maze-explorer-5x5`, `blind-maze-explorer-algorithm`, `blind-maze-explorer-algorithm.easy`, `blind-maze-explorer-algorithm.hard`, `build-linux-kernel-qemu`, `configure-git-webserver`, `count-dataset-tokens`, `crack-7z-hash`, `crack-7z-hash.easy`, `create-bucket`, `download-youtube`, `eval-mteb`, `extract-safely`, `fibonacci-server`, `gpt2-codegolf`, `grid-pattern-transform`, `intrusion-detection`, `nginx-request-logging`, `openssl-selfsigned-cert`, `path-tracing-reverse`, `play-zork`, `polyglot-rust-c`, `processing-pipeline`, `pytorch-model-cli.easy`, `qemu-startup`, `run-pdp11-code`, `sanitize-git-repo`, `sqlite-db-truncate`, `swe-bench-astropy-1`, `swe-bench-astropy-2`, `swe-bench-langcodes`, `train-fasttext`.

SWE-bench Lite 32:

`astropy__astropy-12907`, `astropy__astropy-14182`, `astropy__astropy-14365`, `astropy__astropy-14995`, `astropy__astropy-6938`, `astropy__astropy-7746`, `django__django-10914`, `django__django-10924`, `django__django-11001`, `django__django-11019`, `django__django-11039`, `django__django-11049`, `django__django-11099`, `django__django-11133`, `django__django-11179`, `django__django-11283`, `django__django-11422`, `django__django-11564`, `django__django-11583`, `django__django-11620`, `django__django-11630`, `django__django-11742`, `django__django-11797`, `django__django-11815`, `django__django-11848`, `django__django-11905`, `django__django-11910`, `django__django-11964`, `django__django-11999`, `django__django-12113`, `django__django-12125`, `django__django-12184`.

## Validation Before Benchmarks

1. Focused unit validation:
   - `clojure -M:test -n spell.llm-test -n spell.provider-test`
   - `clojure -M:test -n spell.benchmark-api-test`
   - `git diff --check`
2. Fireworks smoke:
   - Run a tiny Spell API/CLI request with `fireworks:kimi-k2p6`.
   - Confirm the usage model key is exactly `accounts/fireworks/models/kimi-k2p6`.
   - Confirm no chat-template or completions-endpoint failure.
3. Benchmark dry runs:
   - `cd benchmarking && uv run python bench.py terminalbench --condition spell --model fireworks:kimi-k2p6 --name tb-kimi-k2p6-pilot -t hello-world --dry-run`
   - `cd benchmarking && uv run python bench.py swebench --dataset lite --condition spell --model fireworks:kimi-k2p6 --items django__django-11019 --name sb-kimi-k2p6-pilot --dry-run`

## Benchmark Plan

Run a pilot first:

- Terminal-Bench: `hello-world` plus one prior Kimi K2.5 failure with a useful trace, likely `swe-bench-langcodes` or `grid-pattern-transform`.
- SWE-bench Lite: `django__django-11019`, because the prior entry has detailed Kimi K2.5 silent-collapse forensics.

Proceed to full 32+32 only if:

- Fireworks calls succeed.
- Results are not dominated by a new harness/config failure.
- Projected cost is safely under the run-benchmark skill's confirmation threshold. K2.6 pricing is about 1.3-1.6x K2.5 depending on cached-token mix, so the old ~$5.92 total would roughly project to low double digits, well below $50 unless latency/depth behavior worsens badly.

Preferred GCP dispatch, using a new run-group such as `kimi-k2p6-2026-04-25`:

- `./scripts/gcp-benchmark.sh run --name tb-kimi-k2p6 --run-group kimi-k2p6-2026-04-25 --command "cd benchmarking && uv run python bench.py terminalbench --condition spell --model fireworks:kimi-k2p6 --name tb-kimi-k2p6-32 -t ...32 TB items..."`
- `./scripts/gcp-benchmark.sh run --name sb-kimi-k2p6 --run-group kimi-k2p6-2026-04-25 --command "cd benchmarking && uv run python bench.py swebench --dataset lite --condition spell --model fireworks:kimi-k2p6 --name sb-kimi-k2p6-32 --items ...32 SWE items..."`
- `./scripts/gcp-benchmark.sh wait --run-group kimi-k2p6-2026-04-25 --finish`

Use the same condition shape as the prior K2.5 runs:

- Spell only.
- Fireworks prefill provider.
- Default Fireworks agent/policy unless benchmark harness defaults have changed; record resolved agent file in report.
- No added `:coding` trailing prompt unless the old K2.5 run is found to have used one. Current evidence says prior K2.5 used default Fireworks prefill/no coding prompt.

## Reporting Plan

Report with denominators fixed at 32 per benchmark:

- Accuracy: `X% (correct/32)`.
- Error rows by harness status.
- Fatal Spell errors by trace/raw error classification, matching the prior notebook definition: trace ended on an error turn after recovery, not merely "had any mid-trace error".
- Cost and median/total latency when available.
- Paired improvement summary vs K2.5:
  - K2.6 wins: K2.6 correct, K2.5 incorrect.
  - K2.6 losses: K2.6 incorrect, K2.5 correct.
  - Both wrong / both correct.
- Error breakdown: depth-exceeded, recovery-exhausted, timeout, unknown agent/harness errors, no_patch/unresolved.
- Trace forensics on at least:
  - any K2.6 correct item, if any;
  - any new non-K2.5 failure mode;
  - `django__django-11019` or another directly comparable silent-collapse case.

## Docs / Instructions

- `CLAUDE.md` likely does not need an update for a pricing/model option only.
- If implementation discovers that K2.6 requires a new Fireworks provider behavior, update `config/AGENTS.md` or relevant provider docs only if that behavior changes operational guidance.
- Benchmarking docs probably do not need an update unless a new Fireworks invocation caveat is found.

## Notebook Entry

Yes, create/update a notebook entry at dispatch time. This is a substantial benchmark run with a new model and future sessions will need run IDs, trace roots, result files, and comparison notes.

Proposed entry name: `kimi-k2p6-weak-model-eval`.

## Ship Workflow

This is standard `$ship`, not `/ship-auto`, so stop after this plan for user approval.

After approval:

1. Spawn a fresh implementation worker in a dedicated worktree.
2. Worker implements the model option/pricing, validates, smoke-tests, opens a PR, and reports the PR URL.
3. Main thread starts two fresh-context review agents after PR creation.
4. Main thread patches straightforward review findings on the PR branch.
5. After code is ready, run the benchmark plan and report results. Do not merge unless explicitly asked.
