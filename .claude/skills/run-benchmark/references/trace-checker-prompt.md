# Trace Checker Subagent Prompt

**Always** dispatch Sonnet subagents for trace investigation — even for a single item. Use the template below, replacing `{TASK_DIR}`, `{TASK_NAME}`, `{CONDITION}`, `{HARNESS_RESULT}`, and `{FOCUS}` with actual values.

---

```
You are investigating a benchmark trace for task "{TASK_NAME}" ({CONDITION}).
The harness result for this task is: {HARNESS_RESULT}

Task directory: {TASK_DIR}

{FOCUS}

## Your job

Read the trace files in the task directory and report what actually happened.
You MUST read the files — do not guess, infer, or summarize without reading.

## Steps

1. List the contents of the task directory.
2. Read the primary trace files:
   - For Spell: `.spl` trace files, `stdout.txt`, `stderr.txt`, `result.json`
   - For CC/Claude Code: `agent.log`, `stream-json/`, `result.json`
   - For SWE-bench: also check `agent-logs/result.json` (but note: `ok` field only means "no runtime crash", NOT "tests passed" — only the harness `is_resolved` field is authoritative)
3. Identify what the agent did: what approach did it take? What tracked functions/features did it use?
4. If the task failed or errored: find the EXACT error. Quote the error message and the file + line where you found it.
5. If the task succeeded: note any interesting tracked functions or features used, including `defn` and `fn` when present.

## Error classification

Never classify errors by inference from symptoms. Read the actual error from the trace. Common Spell errors to distinguish:
- `missing-tool-call` (empty `input: {}`)
- `reader-recovery-exhausted`
- `depth-exceeded`
- `max_tokens` — only if you find `stop_reason: "max_tokens"` in the provider response
- Stray-delimiter parse failure (0 forms, nil program)

For CC: read `agent.log` or `stream-json/` output for the actual error.

## What to look for by investigation priority

- **Errors (P1):** Root cause category with exact error message. Is this a bug in Spell or expected behavior?
- **Spell wrong, baseline right (P2):** Was the approach sound but execution failed, or was the approach itself wrong? What did the baseline's tool-use do differently?
- **Spell right, baseline wrong (P3):** Look especially for evidence of interesting Spell features — orchestration (spawn/ask), context pruning (!compact, rethink), helper construction via `defn`/`fn`, loop/recur, futures/pmap, inline math, control flow composition, or toolcall composition. These are the most compelling examples of Spell's value.
- **Both wrong (P4):** What makes this problem hard? Did both agents fail the same way or differently?
- **Both right (P5):** Check cost/latency differences. Note any interesting technique differences.

## Output format

Return your findings in EXACTLY this format:

### {TASK_NAME} ({CONDITION})
- **Result:** pass | fail | error
- **Approach:** [1-2 sentence summary of what the agent did]
- **Key detail:** [the most important finding — error message, interesting technique, etc.]
- **Evidence:** `{filename}:{line_number}` — "{quoted text from the file}"
- **Spell features used:** [list, or "N/A" for CC traces]
- **Notes:** [anything else relevant]

## Advanced Investigation Checklist

- Use `spell.trace-tool --summary` first when available. Treat its investigation flags as a prioritization aid, not as a substitute for reading the trace.
- Check context management: rethink/peek/persist patterns, large pruned regions, and whether `!compact` appears.
- Check inter-agent communication: `agents/` usage, `spawn`/`!ask` coordination, and any `globals/` reads or writes.
- Check emergent patterns: `patterns/` usage that was not directly scaffolded by the prompt.
- Check in-language computation: nontrivial `math/` or `strings/` usage, plus helper definitions via `defn` and `fn`.
- Check concurrency: `future`, `blocking/await`, `!ask-await`, or agent-level parallel work.
- Check error recovery: whether failures were graceful, degenerate, or partially recovered, and whether later trace segments continued productively.

## Rules

- Every claim must cite a specific file and line. If you cannot find evidence, say "not found in trace" — do NOT invent it.
- Read at minimum 200 lines of the primary trace file. For long traces, read the beginning (setup), middle (main work), and end (result/error).
- If the trace is very long (>1000 lines), summarize the structure (how many LLM calls, what phases) but still quote specific evidence for your key findings.
- Do NOT report the harness result back to me as your finding — I already know the harness result. I need to know WHY it passed or failed.
- If files are missing or unreadable, report that explicitly.
```
