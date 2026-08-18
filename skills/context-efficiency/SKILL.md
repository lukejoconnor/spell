---
name: context-efficiency
description: Minimize context-window usage during long-running or tool-heavy work when context is growing by pruning disposable results, persisting essentials, rethinking conclusions, and compacting reasoning.
---

CONTEXT EFFICIENCY — Minimize total context window usage.

Context tokens are your scarcest resource. Prune aggressively to stay effective over long tasks.

Prefer !peek over !call-now for disposable tool calls (it appends pruning that removes the command and binding on the following extension):
    '(!peek data (io/glob "**/*.py"))

On the subsequent turn, persist what you need before extending:
    ;; end of turn 1 completion
    (def data "... 200 lines ...")
    (prune)
    ;; start of turn 2 suffix
    ;; data is still in scope here
    (persist targets (take 5 (strings/split-lines data)))
    '(!extend)
    ;; next turn: the !peek call and data are pruned; targets survive as literals

When running a disposable verification command, keep it inside !peek:
    '(!peek verify (io/sh "python /tmp/verify.py"))
    ;; end of turn 1 completion
    (prune)
    ;; start of turn 2 suffix
    (think "Verification passed: the fix handles both edge cases.")
    '(!extend)
    ;; next turn: both the command and result are gone

When you need to rerun a script later, write it to disk first and then call it with !call-now.

After extended reasoning, rethink to compress:
    (think "Long analysis of the bug... examining stack traces, testing hypotheses... the root cause is in parse_args line 42.")
    (rethink "The bug is in parse_args, line 42: off-by-one in the loop bound.")
    '(!extend)

When context grows large, compact:
    '(!compact)

Plan-clear pattern — reason and explore, then start fresh with a self-contained plan:
    (think "analyzing the problem..." ...)
    '(!peek files (io/ls "."))
    ;; end of turn 1 completion
    (def files [...])
    (def plan "Task: fix the calculator bug in calc.py\n1. Edit line 12: fix off-by-one\n2. Run tests")
    ;; start of turn 2 suffix
    '(!llm-self (wrap-cat plan))
    ;; next turn has only the plan as prefix — maximum working space

Each extension should carry forward only what the next step needs.
