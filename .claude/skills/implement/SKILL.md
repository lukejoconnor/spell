---
name: implement
description: "Implement a plan from a previous session in an isolated worktree. Only use when the user explicitly invokes /implement. Do NOT auto-trigger."
---

# Implement Plan

Implement a plan (typically drafted in a prior session) in a git worktree, then open a PR.

## Pre-flight

1. **Dirty branch check.** Run `git status --porcelain`. If there are uncommitted changes, show them and ask the user whether to stash, commit, or abort before proceeding.

2. **Locate the plan.** Ask the user to paste it or point to a file/URL.

3. **Notebook entry.** Spawn a memory sub-agent (background, Sonnet) to create a notebook entry before starting work. To get the prompt template:
   ```
   CONFIG_REPO=$(readlink ~/.claude/CLAUDE.md | xargs dirname | xargs dirname)
   ```
   Read `$CONFIG_REPO/templates/memory-agent-prompt.md` and use it as the agent prompt. The entry should record the plan being implemented and will be updated at completion.

4. **Enter worktree.** Use the EnterWorktree tool.

## Implementation

### Phase 1: Gather context

Use Explore subagents (in parallel where independent) to understand the files and patterns the plan touches. In addition to plan-tagged files, have explorers search for other code paths that could plausibly be affected and report unexpected interactions or hidden dependencies back to the main agent. Read key files yourself for anything central to the change.

### Phase 2: Critique

Review the plan against actual code. Identify:
- Steps that are wrong, outdated, or under-specified
- Improvements you'd make (better APIs, simpler approaches, missed edge cases)
- Any code snippets in the plan that should be rewritten (plan snippets come from a weaker model)
- High-level conceptual simplifications: confirm the plan's end goal and whether there is a simpler overall approach that leaves the post-change codebase structurally simpler (not just a smaller local diff)

Discuss **major** deviations with the user. If you identify a simpler high-level path to the same goal, discuss that alternative with the user before implementation. Minor improvements (better naming, simpler logic, obvious fixes) can proceed without asking.

### Phase 3: Implement

- Use subagents for tasks that can be separated (e.g., independent file changes, test writing).
- Treat plan code snippets as suggestions — rewrite them when you can improve them.
- Run tests after each logical group of changes.

### Phase 4: Documentation check

Even if the plan didn't mention it, check whether these need updating:
- `CLAUDE.md` (project instructions)
- Prompt files in `config/prompts/`
- Agent/provider `.edn` configs
- Namespace `:docs` / `:short-docs` metadata
- `notebook/writeup/` documents

### Phase 5: PR

Create a PR using `gh pr create`. Include:
- Summary of what changed and why
- Any deviations from the original plan
- Test plan

### Phase 6: Report

After the PR is created:
1. Spawn memory sub-agent again (background, Sonnet) to update the notebook entry with final results.
2. **Check the to-do list.** Read `notebook/TODO.md` and check whether the completed work corresponds to any to-do item. If so, move that item to `notebook/DONE.md` (with a completion date and reference to the PR).
3. Report to the user:
   - Changes made (files touched, key decisions)
   - Hurdles discovered during implementation
   - Any choices made that weren't in the plan
   - 1-2 suggested next steps
