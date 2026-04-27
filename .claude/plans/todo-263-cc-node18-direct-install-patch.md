# TODO #263 Follow-up: Install Node >=18 For Claude Code

**Date:** 2026-04-25
**Mode:** `$ship` autonomous (`afk`)

## Goal

Patch `spell-benchmarking` PR #56 after the GCP pilot showed the first fix is necessary but insufficient. The harness now finds `npm`, but the package-manager fallback installs Debian/Ubuntu Node `12.22.9`, and current Claude Code `@anthropic-ai/claude-code@2.1.119` requires Node `>=18.0.0`.

The goal is to make the Claude Code runtime install path provide and verify a Node runtime new enough for current Claude Code in SWE-bench official Python images.

## Evidence

PR comment: https://github.com/lukejoconnor/spell-benchmarking/pull/56#issuecomment-4320786667

Local pilot artifacts:

- `benchmarking/logs/gcp/cc-sbl-op47-263-pilot/spell-benchmark-run.log`
- `benchmarking/results/gcp/cc-sbl-op47-263-pilot/unified/swebench_20260425_224942_85c7820b.jsonl`

Observed failure:

- `NVM/Node install failed, falling back to package manager`
- package manager installs `node v12.22.9` / `npm 8.5.1`
- Claude Code install fails with `EBADENGINE`, requiring Node `>=18.0.0`

The first PR proved useful because `npm` is now on PATH, but the fallback can no longer be considered acceptable unless the Node major version is high enough.

## Scope

Likely files:

- `benchmarking/src/docker_agents.py`
  - Update Node verification to require `node >=18`, not just `command -v node`.
  - Add a direct official Node binary tarball install step, targeted at the runtime prefix, before the distro package-manager fallback.
  - Preserve NVM as the first attempt if desired, but do not rely on distro packages for Claude Code unless they pass the same `>=18` verification.
  - Improve logging if cheap, especially around tail excerpts for install failures.
- `benchmarking/tests/test_docker_agents.py`
  - Add tests that Node 12 verification fails.
  - Add tests that NVM failure tries direct tarball install before package-manager fallback.
  - Update fallback tests so a package-manager success with Node 12 still fails verification.
  - Preserve existing retry behavior tests.
- Notebook entry/update
  - Warranted because this is a continuation of TODO #263 with pilot evidence and an implementation correction.

## Proposed Design

Use a three-step install contract:

1. Try `NVM_NODE_INSTALL`.
2. Verify in a fresh Claude runtime shell:
   - `command -v node`
   - `command -v npm`
   - `node --version` major `>=18`
3. If NVM fails or verification fails, install an official Node 22 Linux tarball directly into `$NPM_CONFIG_PREFIX` and verify again.
4. Only then try the package-manager fallback; it must pass the same verification. If it installs Node 12, return `False` before Claude Code install is attempted.

Why direct tarball before apt:

- The pilot shows apt can produce an unusable runtime.
- The NVM failure tail is hidden by current logging, and direct extraction is simpler than debugging NVM internals in every SWE-bench image.
- Installing into `$NPM_CONFIG_PREFIX` aligns with existing runtime path management.

## Validation

Local:

```bash
cd benchmarking/.worktrees/todo-263-cc-node-install-verify
uv run python -m pytest tests/test_docker_agents.py -k "nvm_node or nodejs or cc_install_script or cc_run_command or install_agent_runtime"
uv run python -m pytest tests/test_docker_agents.py
```

Pilot:

- After code review, run a 1-3 item CC SWE-bench Lite pilot on the same item set if credentials/resources are available.
- Because the user marked AFK and specifically asked for a new patch, implementation and PR updates can proceed; pause before any large rerun if the pilot still fails systematically.

## PR / Review

Patch existing PR #56 rather than opening a separate PR, unless the implementation worker finds the branch is unavailable.

Run two fresh reviews:

- correctness/runtime reviewer
- regression/test-coverage reviewer

Patch straightforward findings in the PR branch.
