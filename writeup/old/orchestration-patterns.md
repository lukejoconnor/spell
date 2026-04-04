# Novel Orchestration Patterns for a General-Purpose Spell Agent

## Introduction

This document catalogs orchestration patterns that would be useful for a general-purpose Spell agent tackling tasks like bug fixing, feature implementation, research, debugging, and data analysis. Each pattern addresses a specific failure mode of naive agent execution. Some patterns are expressible in current Spell; others identify gaps that would require new primitives.

The patterns are ordered roughly by how often a general-purpose agent would reach for them.

---

## 1. Proof-Carrying Completion

**One-line:** Every result ships with a machine-checkable verification that the orchestration layer enforces.

**Problem it solves:** Agents produce plausible but wrong results. Post-hoc verification (e.g., `check-result`) is optional and easy to skip. The agent "thinks" it's done when it isn't.

**How it works:** The agent's return type is not a bare value but a pair: `(artifact, proof)`. The proof is a function (or test suite) that the orchestration layer runs against the artifact. If the proof fails, the result is rejected and the agent must retry with the failure information. The critical property is that *the caller specifies the proof*, not the callee — so the agent can't game the verification.

```
caller defines: proof = (fn [result] (and (passes-tests? result) (no-regressions? result)))
caller delegates: (solve-with-proof task proof)
callee returns: {:artifact code-patch :proof-result (proof code-patch)}
orchestration: if (:proof-result return) → accept, else → retry with failure details
```

**Use cases:**
- Code generation (tests must pass)
- Refactoring (behavior must be preserved)
- Data transformation (invariants must hold)
- Any task where correctness is checkable even if generation is hard

**Expressibility in Spell:** Partially expressible. An agent can use `!call-now` to run tests and check results. What's missing is *structural enforcement* — a way to make verification non-optional. This could be a hook that intercepts return values and applies a proof function, rejecting failures. The `return-hook` mechanism is close but would need to trigger retries rather than just transforming values.

**Key insight:** Verification is almost always cheaper than generation. A pattern that makes verification mandatory and automatic converts an unreliable generator into a reliable one via rejection sampling.

---

## 2. Reflexion Loop

**One-line:** On failure, the agent generates a verbal diagnosis and injects it as episodic memory into the next attempt.

**Problem it solves:** When an agent fails and retries, it often makes the same mistake or a closely related one. Standard retry just re-rolls the dice. The agent has no memory of *what went wrong*.

**How it works:** After a failed attempt, a separate reflection step generates a natural-language diagnosis: "I failed because I assumed the API returns a list, but it returns a dict. Next time, check the return type first." This reflection is injected into the prefix of the retry attempt, so the retrying agent sees both the original task and the lessons from previous failures. Reflections accumulate across retries.

```
attempt 1: try task → fail (tests fail on line 42)
reflect: "The function signature changed in v2. I used the v1 signature."
attempt 2: try task with reflection injected → fail (edge case)
reflect: "Empty input causes division by zero. Need a guard."
attempt 3: try task with both reflections → succeed
```

**Use cases:**
- Bug fixing (especially when the first fix attempt is wrong)
- Code generation with test suites
- Multi-step tasks where early mistakes compound
- Any task with clear success/failure signals

**Expressibility in Spell:** Mostly expressible. The retry loop can use `!llm-self` with accumulated reflections as context. What's awkward is that Spell's error recovery (`try`/`catch` + LLM fix) currently re-evaluates from scratch rather than accumulating episodic memory across attempts. A `reflect-and-retry` macro could wrap this pattern: catch failure → call `leaf-llm` to diagnose → prepend diagnosis to prompt → retry.

**Related work:** Reflexion (Shinn et al. 2023) achieved 97% on AlfWorld vs. 75% for base ReAct, using exactly this pattern.

---

## 3. Speculative Branching

**One-line:** Fork multiple solution strategies in parallel; commit the first one that passes verification.

**Problem it solves:** The agent commits to the first approach it thinks of, which may not be the best one. Backtracking after deep investment is expensive. Sequential exploration wastes time on approaches that could have been evaluated in parallel.

**How it works:** The agent identifies 2-3 plausible strategies and spawns them simultaneously. Each strategy runs independently and produces a candidate result. A verifier evaluates candidates as they arrive. The first candidate that passes verification is committed; remaining branches are cancelled.

```
parent identifies strategies: [approach-A, approach-B, approach-C]
parent spawns: 3 agents, each pursuing one strategy
parent waits: for first verified result (not first result)
verifier: runs proof/tests against each candidate as it arrives
first pass: commit and cancel remaining branches
all fail: escalate to reflexion loop with all failure diagnoses
```

**Use cases:**
- Bug fixing (patch the symptom vs. fix the root cause vs. refactor)
- Implementation tasks with genuine architectural alternatives
- Optimization (try different algorithms, keep the fastest)
- Any task where the right approach isn't obvious upfront

**Expressibility in Spell:** Mostly expressible. `agents/spawn` + `agents/!ask` handles the forking. Cancellation is possible via `-send!`: when a winner is found, send a function to remaining agents that short-circuits evaluation (returns a sentinel or throws). This is cooperative cancellation — fires at the agent's next `box` entry (after its current LLM call completes). Still missing: a race-style primitive that returns the first result meeting a predicate, rather than waiting for all results. Currently `agents/!ask [a b c]` waits for *all* targets; a `race` variant would commit on first verified result.

**Design tension:** Speculative branching trades compute cost for latency and quality. The agent needs a way to judge when the cost is worth it — i.e., when the task is hard enough and the strategies different enough to justify parallelism.

---

## 4. Context Distillation

**One-line:** When context fills up, compress the accumulated state into a summary and continue with fresh context.

**Problem it solves:** Long tasks (multi-file edits, extended debugging sessions, large codebases) fill the context window. The agent loses access to early information and starts making inconsistent decisions. Spell's `!extend` prunes rethought expressions but doesn't compress surviving content.

**How it works:** At a natural breakpoint (or when context reaches a threshold), the agent pauses work and generates a structured summary of its current state: what's been done, what's been learned, what remains, and what key decisions have been made. It then starts a fresh agent with this summary as its initial context, plus any artifacts produced so far.

```
working agent accumulates context over N turns
threshold reached (or natural breakpoint)
distill: agent generates structured summary:
  - completed: [list of done items]
  - learned: [key findings, constraints discovered]
  - artifacts: [file paths modified, values computed]
  - remaining: [what's left to do]
  - decisions: [architectural choices made and why]
fresh agent: starts with summary + original task, continues work
```

**Use cases:**
- Multi-file refactoring (dozens of files to modify)
- Extended debugging sessions
- Research tasks that involve reading many documents
- Any task spanning more turns than the context window supports

**Expressibility in Spell:** Partially expressible via `!llm-self` with a compressed prompt. What's missing is *automatic* distillation — detecting when context is becoming a bottleneck and triggering compression without explicit agent action. Also missing: a structured protocol for what gets preserved vs. discarded. The `think`/`rethink` system handles local corrections but not global compression.

**Key insight:** The distillation itself can be an LLM call (`leaf-llm` to summarize), making the compression lossy but intelligent — the agent decides what's important to preserve.

---

## 5. Progressive Narrowing

**One-line:** Use cheap computation to filter a large candidate space, then invest expensive computation only on survivors.

**Problem it solves:** The agent treats all subproblems equally, spending the same compute on easy and hard parts. This wastes budget on trivia and underinvests in the parts that matter.

**How it works:** Structure work as a funnel with increasing compute investment at each stage. Stage 1 uses cheap tools (grep, file listing, simple heuristics, or small/fast models) to identify candidates. Stage 2 applies moderate compute (read files, parse, basic analysis) to reduce candidates further. Stage 3 applies full compute (deep analysis, LLM reasoning, test execution) only to the remaining candidates.

```
stage 1 (cheap): grep for "error" across 200 files → 15 files match
stage 2 (moderate): read each file's error-handling section → 4 files have relevant bugs
stage 3 (expensive): deep analysis of 4 files, generate and test fixes
```

**Use cases:**
- Bug hunting in large codebases
- Finding the right file/function to modify
- Literature search (scan titles → read abstracts → read papers)
- Any task where the search space is large but most candidates are irrelevant

**Expressibility in Spell:** Fully expressible. This is a natural composition of `!call-now` (for cheap tool calls), `!llm-self` (for analysis), and sequential filtering. The pattern doesn't require new primitives — it's a strategy the agent can adopt. However, an agent without explicit instruction tends to go deep immediately rather than scanning broadly first. Making this a named pattern (like a macro or library function) would make it easier to invoke.

**Design note:** The funnel shape is the key insight. Each stage should be at least 3-5x cheaper than the next, and should eliminate at least half the candidates. If a stage doesn't narrow sufficiently, it wasn't cheap enough relative to its information gain.

---

## 6. Hypothesis-Driven Exploration

**One-line:** The agent explicitly formulates hypotheses, designs experiments to test them, and updates beliefs — the scientific method as orchestration.

**Problem it solves:** Agents explore chaotically — reading files, running commands, reading more files — without a clear theory of what they're looking for. This leads to inefficient exploration that doesn't converge.

**How it works:** The agent maintains an explicit set of hypotheses about the problem. Each action is designed to discriminate between hypotheses — to rule one in or out. After each observation, the agent updates its belief state and decides which hypothesis to test next. The process terminates when one hypothesis has sufficient evidence or all have been ruled out.

```
observe: test failure on line 42, "TypeError: NoneType"
hypothesize:
  H1: function returns None on empty input
  H2: upstream caller passes None instead of default
  H3: config value is missing
experiment: check if function handles empty input → H1 confirmed
update: H1 is likely; design confirmatory test
experiment: add empty-input test case → reproduces the bug
conclude: H1 confirmed, fix = add empty-input guard
```

**Use cases:**
- Debugging (the classic hypothesis-testing domain)
- Root cause analysis
- Performance investigation
- Understanding unfamiliar code

**Expressibility in Spell:** Expressible as a discipline rather than a primitive. The agent can use `think` to record hypotheses, `!call-now` to run experiments, and `rethink` to update beliefs. What might be valuable is a `hypothesize` macro or protocol that structures this: maintain a list of hypotheses with status (active/confirmed/refuted), select the next experiment based on information gain, and terminate when confidence is sufficient. This would be a higher-level pattern built on existing primitives.

**Key insight:** The structure isn't about the individual actions (which are just tool calls) but about the *decision procedure* for choosing which action to take next. Hypothesis-driven exploration is an *information-theoretic* strategy — each action maximizes expected information gain.

---

## 7. Watchdog Agent

**One-line:** A lightweight monitor agent observes the working agent's actions in real time and can intervene (redirect, warn, or abort) when it detects problems.

**Problem it solves:** Agents go off the rails — getting stuck in loops, pursuing dead-end strategies, making unsafe changes, or losing track of the original goal. By the time the agent recognizes the problem (if it does), it has wasted significant compute.

**How it works:** A watchdog agent runs concurrently with the working agent. It receives a stream of the worker's actions (tool calls, thoughts, intermediate results) and evaluates them against criteria: staying on task, making progress, maintaining safety invariants, budget consumption. When it detects a problem, it sends an intervention message to the worker via the messaging system.

```
spawn worker with task
spawn watchdog with criteria:
  - task description (for relevance checking)
  - safety invariants (no destructive operations, stay in directory)
  - progress heuristic (should see new information every N turns)
  - budget limit
worker acts normally, each action also reported to watchdog
watchdog evaluates: "worker has run grep 4 times on the same file" → send "stuck loop" intervention
worker receives intervention → breaks out of pattern
```

**Use cases:**
- Long-running autonomous tasks
- Tasks involving destructive operations (file edits, deployments)
- Budget-constrained scenarios
- Any situation where the cost of going off-track is high

**Expressibility in Spell:** Partially expressible. One-shot observation works via `-send!`: send a message function that intercepts the raw completion, forwards it to the observer, and returns raw unchanged for normal evaluation. Persistent observation (across multiple turns) is harder — `-llm` resets the inbox to `eval-fn` at the start of each call, overwriting any re-queued observer. Workarounds: (a) async re-queue with a delay (races but works in practice since LLM calls take seconds), (b) modify `-llm` to compose with existing inbox rather than overwrite, or (c) add a registry-level `:observer` slot that survives inbox resets. The trace system (`trace/*trace*`) already records every LLM call and could serve as a read-only observation source via `globals/`. Alternatively, the worker could explicitly log actions to `globals/` and the watchdog could poll — clunkier but fully expressible today.

**Design tension:** The watchdog itself consumes compute. It should be much cheaper than the worker (use `leaf-llm` or simple heuristics for evaluation). The cost of the watchdog must be justified by the cost of uncaught mistakes.

---

## 8. Checkpoint & Backtrack

**One-line:** Save the agent's state at decision points, then backtrack and try alternatives when a path fails.

**Problem it solves:** Agents make irreversible commitments early and then sunk-cost their way through bad approaches. `think`/`rethink` handles small local corrections, but there's no way to roll back to an earlier state in a deep call tree and try a completely different path.

**How it works:** At key decision points, the agent saves a checkpoint: the current program state (or enough to reconstruct it — e.g., the completion source, environment bindings, files modified). If a downstream path fails, the agent can restore a checkpoint and explore a different branch. The simplest version saves the `completion` binding (which Spell already provides) at key moments and can re-enter from any saved checkpoint.

```
checkpoint "before-approach-selection"
try approach A:
  modify file X → tests fail
  diagnose: approach A is fundamentally wrong
backtrack to "before-approach-selection"
try approach B:
  modify file Y → tests pass
  commit approach B
```

**Use cases:**
- Debugging when the first fix doesn't work
- Refactoring with multiple valid strategies
- Multi-step tasks where early decisions constrain later options
- Any task where you might need to undo several steps of work

**Expressibility in Spell:** Partially expressible. Because Spell programs have access to their own source via `quine`/`completion`, an agent can in principle save a snapshot of its program state and re-enter from that point. The `prune-and-reopen` mechanism already manipulates the program's AST. What's missing: (1) a clean way to save and restore file system state (for tasks involving file edits), and (2) a structured protocol for managing a tree of checkpoints. For file state, git commits are the natural checkpoint mechanism — the agent could `git stash` or branch at decision points. For program state, the `completion` binding at each checkpoint needs to be stored somewhere accessible to the backtracking agent.

**Relationship to MCTS:** This pattern is the practical core of Monte Carlo Tree Search applied to agent trajectories. Full MCTS adds value estimation (which branches look promising?) and a UCB-like selection strategy. The simpler version described here is depth-first with backtracking on failure.

---

## 9. Stigmergic Task Board

**One-line:** Agents coordinate through a shared task board, claiming and completing work items without centralized assignment.

**Problem it solves:** Fan-out/fan-in requires a central coordinator that decomposes the task, assigns subtasks, and collects results. This coordinator is a bottleneck — it must understand the full task well enough to decompose it correctly, and it must wait for all workers before continuing. For tasks where the decomposition is emergent (you discover subtasks as you work), centralized assignment is awkward.

**How it works:** A shared task board (via `globals/`) holds a list of work items. Any agent can: read the board, claim an item (atomically, via `globals/pop` or compare-and-swap), do the work, and post results back to the board. Agents can also *add* new items they discover during their work. No single agent needs to understand the full task — each agent picks up whatever it can handle.

```
initial board: [{:task "fix bug in auth.py"} {:task "update tests"} {:task "update docs"}]
agent A claims "fix bug in auth.py", discovers it also needs "update config.py"
  → adds {:task "update config.py"} to board
  → completes auth.py fix, posts result
agent B claims "update tests", completes it
agent C claims "update config.py" (discovered by A), completes it
agent D claims "update docs", completes it
all items done → parent collects results
```

**Use cases:**
- Large refactoring tasks (many files to update)
- Tasks where the full scope isn't known upfront
- Heterogeneous agents with different capabilities
- Work that's naturally parallel but with emergent dependencies

**Expressibility in Spell:** Mostly expressible. `globals/pop` provides atomic task claiming. `globals/update` can post results and add new tasks. Completion detection can be done via polling: a coordinator agent periodically checks `globals/` for remaining tasks. This is inelegant but functional. A `globals/wait-until` primitive (block until a predicate on global state becomes true, using Clojure's `add-watch` internally) would make this cleaner — avoiding busy-wait in favor of event-driven notification.

**Design note:** The stigmergic pattern scales well (adding more agents doesn't require changing the coordinator) but is harder to debug (no single agent has the full picture). It's best for tasks where subtasks are loosely coupled.

---

## 10. Forward Simulation

**One-line:** Before committing to an action, the agent simulates its consequences and evaluates the predicted outcome.

**Problem it solves:** Agents take actions (especially file edits and command execution) without considering what might go wrong. By the time they see the failure, the action has already been taken and may be hard to reverse.

**How it works:** Before executing a consequential action, the agent runs a "what if" simulation. For code changes, this means mentally tracing the impact: what other code depends on this? What tests would break? For commands, this means predicting the output. The simulation is typically an LLM call (cheap, fast) that evaluates the predicted consequences. If the simulation predicts problems, the agent modifies its plan before executing.

```
plan: change function signature from f(x) to f(x, y=None)
simulate: "Which callers of f() would break?"
  → leaf-llm analyzes: "3 callers in module A, 2 in module B. All pass single arg, so default y=None keeps them working. But test_f() explicitly checks the signature — that will fail."
revise plan: also update test_f() before changing the signature
execute revised plan
```

**Use cases:**
- Code refactoring (predict ripple effects)
- Destructive operations (predict what would be deleted/overwritten)
- Configuration changes (predict system behavior change)
- Any action where consequences are nonlocal

**Expressibility in Spell:** Fully expressible. The simulation is just a `leaf-llm` call with the proposed action as context. The agent can use `think` to record the simulation result and adjust the plan. No new primitives needed — this is a strategy the agent can adopt. However, making it a named pattern (e.g., a `simulate-then-act` macro) would make it easy to invoke consistently.

**Key insight:** Simulation is cheap relative to the cost of fixing mistakes. Even an imperfect simulation (which an LLM prediction always is) catches many foreseeable problems. The pattern is especially valuable when combined with proof-carrying completion — simulate first, then verify after.

---

## 11. Cascading Model Tiers

**One-line:** Route subtasks to different capability levels — use cheap models for easy work, expensive models only when needed.

**Problem it solves:** A general-purpose agent uses the same (expensive) model for everything — simple string formatting, complex reasoning, routine tool calls, and creative problem-solving. Most subtasks don't need the most powerful model.

**How it works:** The agent classifies subtask difficulty (either explicitly or via a routing heuristic) and delegates to the appropriate tier. Tier 1 is `leaf-llm` or a fast model for simple generation/classification. Tier 2 is the standard `!llm-self` for moderate complexity. Tier 3 is a more powerful model (or extended thinking) for genuinely hard problems. If a lower tier fails or expresses low confidence, the task escalates to a higher tier.

```
task: "Fix the bug and update documentation"
decompose:
  subtask 1: "Read error log" → tier 0 (just a tool call, no LLM needed)
  subtask 2: "Identify root cause" → tier 2 (reasoning required)
  subtask 3: "Generate fix" → tier 2 (code generation)
  subtask 4: "Update docstring" → tier 1 (simple text generation)
  subtask 5: "Verify fix handles edge cases" → tier 3 (deep analysis)
```

**Use cases:**
- Budget-constrained tasks
- High-volume tasks (many subtasks, most routine)
- Tasks where parts are trivial and parts are hard
- Any scenario where model cost matters

**Expressibility in Spell:** Partially expressible. `make-llm` can create LLM functions with different models, and these can be passed via namespaces or as builtins. The agent can choose between `!llm-self`, `leaf-llm`, and custom LLM variants. What's missing: (1) a *routing* mechanism that automatically classifies difficulty, and (2) an *escalation* protocol for when a lower tier fails. Currently the agent must manually decide which tier to use. A `cascading-call` primitive could try tier 1 first and automatically escalate on failure.

**Design tension:** The routing decision itself costs compute. If routing is done by an LLM, the routing overhead may exceed the savings from using a cheaper model. Heuristic routing (based on prompt length, task type, or keyword matching) may be more practical.

---

## 12. Adversarial Contract

**One-line:** Before delegating to a child agent, the parent specifies a formal contract (preconditions, postconditions, invariants) that the child's result must satisfy.

**Problem it solves:** Delegation is informal — the parent gives a natural-language instruction, and the child returns whatever it produces. There's no structured way to express what the parent expects, and no automatic way to check whether the child delivered.

**How it works:** The parent defines a contract as a set of executable predicates:
- **Preconditions:** what the child can assume about its inputs
- **Postconditions:** what properties the child's output must have
- **Invariants:** what must remain true throughout execution

The orchestration layer wraps the child's execution: verify preconditions before starting, verify postconditions on completion, verify invariants at each step. Failed postconditions trigger retry with the violation as context. Failed invariants trigger immediate abort.

```
contract:
  pre: (file-exists? "main.py")
  post: (and (tests-pass?) (= (line-count "main.py") original-count ± 5))
  invariant: (not (modifies? "config.py"))
delegate: (solve-under-contract task contract)
child works, modifies main.py
postcondition check: tests pass, line count OK → accept
```

**Use cases:**
- Safety-critical delegations (don't modify these files)
- Quality-controlled pipelines (output must match schema)
- Multi-agent workflows where agents don't trust each other
- Any delegation where the parent knows what "correct" looks like

**Expressibility in Spell:** Mostly expressible. Preconditions can be checked before spawning. Postconditions can be enforced via return hooks — a hook that validates the result and, on failure, sends a retry message (with the violation as context) back to the agent via `-send!`. The `orphan-box` mechanism ensures the agent can be woken for retry even after it has returned. Invariants during execution are harder — they'd require the watchdog pattern (see above) or a hook that intercepts each turn's evaluation, not just the final return.

**Relationship to proof-carrying completion:** Contracts are a generalization. Proof-carrying completion is the special case where the only contract term is "the proof passes." Full contracts add preconditions, invariants, and structured failure handling.

---

## 13. Ensemble with Engineered Diversity

**One-line:** Run the same task through multiple agents with deliberately different perspectives, then synthesize a result that's better than any individual.

**Problem it solves:** Running the same prompt multiple times gives superficially different but structurally similar outputs. True diversity — genuinely different approaches, different assumptions, different failure modes — doesn't emerge from identical prompts.

**How it works:** The parent creates agents with engineered differences:
- Different system prompts (e.g., "you are a security expert" vs. "you are a performance engineer")
- Different initial information (each agent sees a different subset of the context)
- Different instructions ("use a recursive approach" vs. "use an iterative approach")
- Different models (when multiple are available)

After all agents produce results, a synthesis step combines their outputs — not by majority vote, but by extracting the unique contributions of each and resolving conflicts.

```
task: "Review this code for issues"
agent A (system: security focus): finds SQL injection, missing auth check
agent B (system: performance focus): finds N+1 query, unnecessary allocation
agent C (system: maintainability focus): finds duplicated logic, unclear naming
synthesizer: merges all findings, removes duplicates, ranks by severity
result: comprehensive review covering all three dimensions
```

**Use cases:**
- Code review (different dimensions of quality)
- Design decisions (different stakeholder perspectives)
- Risk assessment (different threat models)
- Any task where multiple perspectives improve the outcome

**Expressibility in Spell:** Expressible. Each agent can be spawned with different prompts or even different `make-llm` configurations. The synthesis step is a standard fan-in via `agents/!ask [a b c]`. What's not built-in is the *diversity engineering* — the parent must explicitly construct diverse perspectives. A library of "lenses" (reusable perspective-shifting prompts) would make this pattern easier to invoke.

**Key insight:** The value is in the diversity, not the quantity. Three genuinely different perspectives are worth more than ten copies of the same one. The hard part is engineering diversity that maps onto real, independent failure modes.

---

## 14. Deferred Commitment

**One-line:** Generate options without choosing between them; delay binding decisions until more information arrives.

**Problem it solves:** Agents make hard commitments early — choosing an approach, an architecture, a specific fix — before they have enough information. Later information that would change the decision is ignored or causes expensive rework.

**How it works:** Instead of choosing a single path at a decision point, the agent maintains multiple options as data and continues gathering information. Each option is annotated with what additional information would confirm or refute it. When enough information arrives to clearly favor one option, the agent commits. The commitment point is the *last responsible moment* — the point where further delay would prevent meeting the deadline.

```
phase 1: understand the problem
  option A: "This is a race condition" (would confirm: adding a lock fixes it)
  option B: "This is a cache staleness issue" (would confirm: cache invalidation fixes it)
  option C: "This is a config error" (would confirm: correct config value resolves it)
phase 2: gather discriminating evidence
  test: add lock → problem persists → option A refuted
  test: check config values → all correct → option C refuted
  remaining: option B
phase 3: commit to option B, implement fix
```

**Use cases:**
- Debugging (don't commit to a root cause prematurely)
- Design decisions (keep multiple architectures viable until trade-offs are clear)
- Research (maintain competing hypotheses)
- Planning (don't fix the plan until you understand the constraints)

**Expressibility in Spell:** Expressible as a discipline using existing primitives. Options can be stored as data in `def` bindings, and evidence can be gathered via `!call-now`. The `think`/`rethink` system supports updating beliefs. What's missing is a structured representation of options-with-evidence that persists across extensions and supports automated reasoning about which option to investigate next. This overlaps with the hypothesis-driven pattern but focuses specifically on *delaying the decision* rather than *actively testing hypotheses*.

**Design tension:** Deferred commitment costs working memory (you must track all active options). In a context-limited agent, there's a natural pressure to commit early just to free up context. The interplay between this pattern and context distillation is important: you need efficient representations of uncommitted options.

---

## Cross-Cutting Observations

### Patterns That Compose

Several patterns naturally combine:

- **Progressive Narrowing + Speculative Branching:** Narrow the candidate space cheaply, then branch on the survivors.
- **Proof-Carrying Completion + Reflexion:** When the proof fails, generate a reflection. When it passes, commit.
- **Hypothesis-Driven + Deferred Commitment:** Hypotheses are the options; experiments are the evidence; commitment happens when one hypothesis dominates.
- **Watchdog + Adversarial Contract:** The watchdog enforces invariants in real time; postconditions are checked on completion.
- **Checkpoint & Backtrack + Forward Simulation:** Simulate before acting; if simulation predicts failure, skip the action without needing to checkpoint and roll back.
- **Context Distillation + Reflexion:** When compressing context for a fresh agent, include accumulated reflections from failed attempts — the new agent starts with both a clean context and the lessons learned.

### What Current Spell Lacks

The `-send!` primitive is more powerful than it first appears — it can replace an agent's inbox with an arbitrary function, enabling cancellation, one-shot observation, and retry signaling. After accounting for this, the genuinely missing primitives are:

1. **Persistent observation:** One-shot observation via `-send!` works, but surviving across `!llm-self` turns requires either an async re-queue hack or a small change to `-llm` (compose with existing inbox instead of resetting it). A registry-level `:observer` slot would be the clean solution.
2. **File state management:** Checkpoint & backtrack for file-editing tasks needs file-level rollback. Git provides this via branches/stash, but the agent must orchestrate it manually through `io/sh`.
3. **Race primitive:** Speculative branching wants a `race` variant of `agents/!ask` that returns the first result meeting a predicate rather than waiting for all targets.
4. **Efficient blocking on state:** Polling `globals/` works but wastes cycles. A `globals/wait-until` using Clojure's `add-watch` would be event-driven.

Previously flagged as gaps but expressible via `-send!`:

- **Cancellation:** `-send!` with a short-circuit function; cooperative at next `box` entry.
- **Hook-mediated retries:** Return hook validates result, sends retry via `-send!` on failure; `orphan-box` wakes the agent.
- **Difficulty classification:** Application-level concern, not a language gap.

### Patterns Expressible Now

Most patterns are expressible with current primitives — some as strategies (requiring no new code), others via `-send!` (requiring Clojure-level orchestration):

**Strategy-level** (agent can adopt with existing builtins):
- **Progressive Narrowing:** grep → read → analyze, using `!call-now` at each stage
- **Hypothesis-Driven Exploration:** `think` for hypotheses, `!call-now` for experiments, `rethink` for updates
- **Forward Simulation:** `leaf-llm` to predict consequences before `!call-now` to execute
- **Ensemble with Diversity:** `agents/spawn` with different prompts, `agents/!ask [...]` to collect
- **Deferred Commitment:** maintain options as data, gather evidence, commit late
- **Stigmergic Task Board:** `globals/pop` for claiming, `globals/update` for posting, polling for completion detection

**Via `-send!`** (requires Clojure-level orchestration, not yet exposed to Spell agents):
- **Speculative Branching:** spawn + cancel losers via `-send!` short-circuit function
- **Adversarial Contract:** return hooks validate postconditions, `-send!` triggers retries
- **Proof-Carrying Completion:** same mechanism — hook checks proof, `-send!` retries on failure

These could be packaged as library patterns (like `patterns/check-result`), as macros, or as new builtins that wrap `-send!` for Spell-level access.

### The Meta-Pattern

All fourteen patterns share a common structure: **separate generation from commitment**. The naive agent generates an action and commits to it in a single step. Every pattern above introduces a gap between generation and commitment — filling that gap with verification (proof-carrying), exploration (branching, backtracking), prediction (forward simulation), deliberation (deferred commitment), or monitoring (watchdog). The more consequential the action, the wider this gap should be.
