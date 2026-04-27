# Claude Code Max OAuth-Only Harness Plan

## Scope And Goal

Configure Claude Code benchmark harness infrastructure to use Claude Max subscription OAuth only. Claude Code runs should not accept or forward `ANTHROPIC_API_KEY`, and GCP benchmark VMs should get Claude Code auth from the `CLAUDE_CODE_OAUTH_TOKEN` Secret Manager secret.

AFK assumption: "Max sub only" means the supported Claude Code auth path is `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token`; legacy `CLAUDE_JSON_B64` / `~/.claude.json` support should be removed from the Claude Code benchmark harness instead of retained as a fallback.

## Likely Files

- `benchmarking/src/docker_agents.py`
  - Remove Claude Code use of `ANTHROPIC_API_KEY`.
  - Remove `CLAUDE_JSON_B64` / `~/.claude.json` loading for Claude Code containers.
  - Require `CLAUDE_CODE_OAUTH_TOKEN` for `claude_code` container env setup and surface a clear auth error before install/run.
- `benchmarking/terminal_bench/cc_agent.py`
  - Drop local `CLAUDE_JSON_B64` loading and the warning that `ANTHROPIC_API_KEY` is acceptable.
  - Make Terminal-Bench Claude Code auth check require `CLAUDE_CODE_OAUTH_TOKEN`.
- `benchmarking/src/unified_adapters.py`
  - Ensure local `ClaudeCodeAdapter` subprocess env removes `ANTHROPIC_API_KEY` and requires `CLAUDE_CODE_OAUTH_TOKEN`.
- `scripts/gcp-startup.sh`
  - Stop fetching/materializing `CLAUDE_JSON_B64` for Claude Code.
  - Fetch/export `CLAUDE_CODE_OAUTH_TOKEN`; warn or fail clearly when the secret is unavailable and a Claude Code run is attempted.
- `scripts/gcp-benchmark.sh`
  - Remove `--claude-auth-secret` / `SPELL_GCP_CLAUDE_AUTH_SECRET` plumbing.
  - Keep `--cc-oauth-secret`, defaulting to `CLAUDE_CODE_OAUTH_TOKEN`, and document it as required for Claude Code Max subscription runs.
- `AGENTS.md`, `benchmarking/AGENTS.md`, and `benchmarking/README.md`
  - Update docs to say Claude Code uses Max subscription OAuth only and never falls back to Anthropic API billing.
- Focused tests under `benchmarking/tests/`
  - Cover auth env construction, absence of `ANTHROPIC_API_KEY`, required OAuth token behavior, and GCP metadata/help changes if practical via shell/static assertions.

## Validation

- `cd benchmarking && uv run pytest tests/test_docker_agents.py tests/test_terminalbench_base.py tests/test_unified_adapters_commands.py`
- Add or update focused tests for the new OAuth-only behavior.
- Run a dry-run CLI path if available without needing live credentials, e.g. relevant `bench.py ... --dry-run` commands.
- Do not run paid/live Claude Code benchmarks in this shipping pass.

## Documentation

Update current benchmark/GCP setup docs to instruct:

1. Run `claude setup-token` locally.
2. Store the token in GCP Secret Manager as `CLAUDE_CODE_OAUTH_TOKEN`.
3. Grant the VM service account access.
4. Use `--cc-oauth-secret` only if the secret has a non-default name.

Remove or revise docs that say Claude Code can use `ANTHROPIC_API_KEY` or `CLAUDE_JSON_B64`.

## Notebook Entry

Warranted. This changes benchmark authentication semantics and GCP setup. Record the final branch/PR, exact auth decision, validation run, and any required user-side GCP secret action.

## Critique And Alternatives

The old mixed auth model made it too easy for a Claude Code comparator to silently bill the Anthropic API instead of the Max subscription. Keeping `CLAUDE_JSON_B64` as a second path would preserve ambiguity, so the plan replaces it with one explicit token path. Anthropic API keys should remain available for Spell Anthropic provider runs, but Claude Code harness code must not pass them into Claude Code subprocesses or containers.
