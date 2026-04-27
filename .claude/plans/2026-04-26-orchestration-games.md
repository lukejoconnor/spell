# Orchestration Games Benchmark

**Date:** 2026-04-26
**Mode:** `$ship` autonomous / AFK

## Goal

Create three CLI-runnable orchestration games that test whether a Spell agent can infer and implement the appropriate multi-agent scheme when the task requires orchestration, without being told exactly how to implement the scheme in Spell.

The experimental question is:

> When a task requires orchestration, can the agent successfully set up the appropriate scheme in Spell without being told how to do so?

## Games

1. **Auction**
   - Mirrors best-of-k / independent candidate generation.
   - Based on `examples/auction.spl`, but scored as a game rather than a demo.
   - Success requires independent bidder agents, valid bid collection, and correct winner selection.

2. **Twenty questions**
   - Mirrors worker-checker.
   - Based on `examples/twenty-questions.spl`.
   - Success requires separating a secret-holder/checker from a guessing worker, multi-turn question/answer flow, and a correct final guess within the limit.

3. **Telephone**
   - Mirrors deterministic workflow / pipeline orchestration.
   - New prompt: create an 8-agent relay where each agent passes along the meaning of a short initial message while changing the wording, and the final message returns to the originator.
   - Success requires exactly the staged relay structure, eight agent handoffs, meaning preservation, and non-identical wording.

## Implementation Shape

- Add a small scored orchestration-games harness, likely under `dev/` to match the existing Clojure orchestration benchmark:
  - `dev/orchestration_games.clj`
  - prompts under `notebook/entries/orchestration-games/prompts/`
  - outputs under `notebook/entries/orchestration-games/outputs/`
- Reuse existing Spell agent/provider loading where possible, but avoid extending the older qualitative `dev/benchmark.clj` in place unless it is cleaner.
- Use exact model specs:
  - GPT: `openai-tc:gpt-5.4` or equivalent OpenAI provider path with model `gpt-5.4`.
  - Opus: `anthropic-tc:claude-opus-4-7` or equivalent Anthropic provider path with model `claude-opus-4-7`.
- Run 4 attempts per game per model, for 24 total model-game trials.
- Store raw verbose traces/results for manual inspection.

## Scoring

Use manual scoring from raw outputs/traces, not model self-report. The harness should preserve enough evidence to score:

- `success`: did the game complete its functional goal?
- `orchestration`: did the model actually use appropriate Spell orchestration primitives/agents?
- `scheme`: whether the inferred scheme matches the game pattern.
- `notes`: concise failure/success rationale.

Report success rate out of 4 attempts per game/model and a short qualitative answer to the paper question.

## Validation

- Start with a cheap dry run or one-trial pilot using the final harness on at least one model.
- If systematic errors are harness/prompt issues, patch and rerun affected trials.
- Run focused Clojure tests or at minimum compile/load the new namespace.
- Inspect raw outputs before scoring. Use subagents for independent scoring if useful, but keep final reported scores tied to saved result files.

## Documentation / Notebook

- Update `benchmarks.md` if the harness and results are intended as current benchmark catalog material.
- Add a notebook entry because this is a multi-step benchmark setup and result-producing experiment.

## PR Timing

The user asked to report results before making the eventual PR. Implement and run in an isolated worktree, report the score table and interpretation first, then pause before opening the PR.
