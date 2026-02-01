# Literature Review: LLM Self-Orchestration and Related Concepts

## Executive Summary

This review surveys existing work related to Spell, a domain-specific language for LLM self-orchestration. The core innovation of Spell—enabling LLMs to write programs that control their own recursive execution—occupies a distinctive position in the literature. While related concepts exist across multiple research traditions, no prior work combines all of Spell's key properties: natural language as orchestration medium, arbitrary control flow (not fixed patterns), and the LLM as both author and subject of execution programs.

**Closest prior work:**
- Kimi K2.5 PARL - Jan 2026 - trainable orchestrator learns task decomposition via RL; orchestration in weights
- Cursor Agent Swarm - Jan 2026 - human-designed Planner/Worker/Judge hierarchy; orchestration in harness code
- Recursive Language Models (RLM) - Dec 2024 - treats prompts as variables, LLM writes code to call itself
- MemGPT - Oct 2023 - self-directed memory management via function calls
- 3-Lisp - 1982 - reflective lambdas execute one meta-level above caller

**Key gap identified:** PARL demonstrates that model-designed orchestration can achieve state-of-the-art results, but the orchestration policy is opaque (learned in weights). Cursor demonstrates that orchestration topology is critical, but relies on human design. No existing system represents model-designed orchestration as inspectable, composable source code.

---

## 1. Recent LLM Agent Architectures (2023-2026)

### 1.1 Kimi K2.5 PARL (Parallel-Agent Reinforcement Learning)

**Source:** Moonshot AI, Jan 2026 - kimi.com/blog/kimi-k2-5.html

The closest existing system to Spell's core thesis: model-designed orchestration. A 1-trillion-parameter Mixture-of-Experts model (32B activated per token) with a trainable orchestrator that learns to decompose tasks into parallel subtasks via RL. During inference, the model dynamically spawns up to 100 sub-agents across 1500 tool calls with no predefined agent roles.

**Key innovations:**
- **Frozen sub-agents, trained orchestrator:** Only the orchestration policy is trained via RL; spawned agents are frozen copies of the base model
- **PARL reward function:** Rt = λaux(e) · r_parallel + (1 − λaux(e)) · (I[success] · Q(τ)). Early training rewards parallelism; late training rewards task quality. Prevents "serial collapse" and "fake parallelism" pathologies
- **Critical Steps metric:** Latency-aware evaluation measuring the longest dependency chain, not just concurrency
- **Results:** 3-4.5x speedup on coding tasks; 76.8% on SWE-bench Verified (state-of-the-art at release); open weights

**Relevance to Spell:** PARL validates Spell's core bet—that models can design their own orchestration and outperform fixed topologies. The key difference is the representation of the orchestration policy:

| Aspect | PARL | Spell |
|--------|------|-------|
| Orchestration is... | In weights (learned via RL) | In source code (written by LLM) |
| Inspectable? | No | Yes |
| Composable? | No (monolithic policy) | Yes (function composition) |
| Debuggable? | No | Yes (read the program) |
| Trainable? | Yes (RL on reward signal) | Not yet (zero-shot generation) |

PARL is the "compiled" approach to model-designed orchestration; Spell is the "interpreted" approach. The natural synthesis is training a model with RL to write Spell programs—combining PARL's learned orchestration with Spell's transparency and composability. PARL's critical-steps reward metric could apply directly to Spell program outputs.

### 1.2 Cursor Agent Swarm

**Source:** Cursor, Jan 2026 - cursor.com/blog/scaling-agents

Cursor ran hundreds of GPT-5.2 agents for ~1 week to build a web browser from scratch in Rust (1M+ lines, 1000 files, 3342 commits, zero human intervention during execution). The experiment went through two failed orchestration topologies before finding one that worked.

**Failed topologies:**
1. **Flat peer coordination with file locking:** Deadlocks everywhere; 20 agents reduced to 2-3 effective throughput
2. **Optimistic concurrency control:** Agents became risk-averse, avoiding hard tasks to minimize merge conflicts
3. **Planner/Worker/Judge hierarchy** (the breakthrough): Planners decompose tasks recursively, workers execute in isolation (deliberately oblivious to each other), judges evaluate progress

**Key findings:**
- Orchestration topology dramatically affects outcomes—same model, different topology, different results
- Success came from *restricting* agent autonomy (workers had no inter-worker communication)
- Prompts mattered more than harness or model choice
- Distributed computing patterns (locks, optimistic concurrency) did not transfer to LLM agents

**Relevance to Spell:** The Cursor experiment provides evidence both for and against self-orchestration:

- **For:** Topology is a first-class concern that requires iteration. Spell makes topology programmable, enabling faster iteration than rewriting harness code.
- **Against:** Agents failed when given coordination freedom (attempts 1-2). Success required human-imposed structure. A self-orchestrating model might reproduce the failure modes rather than the working topology.
- **Reconciliation:** Models may not need to *invent* topologies from scratch. A pattern library (planner-worker, map-reduce, debate) lets the model *select and compose* known-good patterns. Composition is where Spell has a structural advantage: in harness-based systems, combining patterns requires new glue code; in Spell, it is function composition.

### 1.3 Recursive Language Models (RLM)

**Source:** Zhang & Khattab, Dec 2024 - arxiv.org/abs/2512.24601

The closest recent work to Spell's core concept. RLM treats prompts as Python variables in a REPL environment. The LLM writes code to examine, decompose, and recursively process its input.

**Key innovations:**
- Prompt as external environment rather than direct input
- LLM controls decomposition strategy
- Handles inputs 2 orders of magnitude beyond context windows

**Distinction from Spell:** RLM uses Python as the orchestration layer. The LLM writes Python code that manipulates prompts. Spell proposes the LLM write S-expressions/natural language that *directly specify* execution topology. RLM's recursion is mediated by code; Spell's is declarative.

### 1.4 MemGPT

**Source:** Packer et al., Oct 2023 - arxiv.org/abs/2310.08560

OS-inspired virtual context management where the LLM manages its own memory via function calls.

**Key innovations:**
- Two-tier memory: in-context (main) + external (archival/recall)
- Self-directed memory editing
- LLM decides when to page information in/out

**Distinction from Spell:** MemGPT focuses specifically on memory management within a fixed agent loop. Spell addresses arbitrary control flow—memory management is one possible application but not the central concern.

### 1.5 Tree of Thoughts (ToT)

**Source:** Yao et al., May 2023 - arxiv.org/abs/2305.10601

Explores multiple reasoning paths as a tree structure with LLM self-evaluation.

**Key features:**
- Intermediate states evaluated by LLM
- Search algorithms (BFS/DFS) explore branches
- Deliberate backtracking when paths fail

**Distinction from Spell:** ToT uses pre-defined search algorithms. The tree structure and search strategy are external. In Spell, the LLM would write the branching logic itself—potentially ToT-like patterns, but also patterns ToT cannot express.

### 1.6 ReAct

**Source:** Yao et al., 2022 - react-lm.github.io

The foundational Thought → Action → Observation loop pattern.

**Key features:**
- Synergizes reasoning and acting
- LLM decides when to continue or terminate
- Widely adopted in agent frameworks

**Distinction from Spell:** ReAct is a fixed pattern. Every iteration follows the same structure. Spell enables the LLM to write arbitrary patterns—including ReAct, but also parallel branches, recursive decomposition, context surgery, etc.

### 1.7 DSPy

**Source:** Stanford NLP, Oct 2023 - github.com/stanfordnlp/dspy

"Programming not prompting" framework with automatic prompt optimization.

**Key features:**
- Signatures define input/output contracts
- Modules define prompting strategies
- Compilers (MIPROv2, BootstrapFewShot) optimize prompts

**Distinction from Spell:** DSPy's programs are written by humans and optimized by algorithms. The LLM is the *subject* of optimization, not the *author* of orchestration logic. Spell inverts this: the LLM writes its own orchestration.

### 1.8 Other Notable Recent Work

| System | Date | Key Idea | Distinction from Spell |
|--------|------|----------|----------------------|
| **Voyager** | May 2023 | Skill library in Minecraft | Skills are executable code, not self-referential orchestration |
| **SICA** | Sep 2025 | Self-improving coding agent | Optimizes prompts/code, but external overseer controls loop |
| **Sculptor** | Aug 2025 | Active Context Management | LLM manages attention/memory, not execution topology |
| **MARS** | Jan 2025 | Metacognitive reflection | Single recurrence cycle, not arbitrary recursion |

### 1.9 Open-Source Coding Agent Implementations

Examining how production coding agents implement their core loops reveals a structural invariant relevant to Spell's positioning.

#### Opencode (opencode-ai/opencode)

A Go-based coding agent whose entire orchestration logic is a ~35-line for-loop in `internal/llm/agent/agent.go`:

```go
for {
    agentMessage, toolResults, err := a.streamAndHandleEvents(ctx, sessionID, msgHistory)
    // ...
    if (agentMessage.FinishReason() == message.FinishReasonToolUse) && toolResults != nil {
        msgHistory = append(msgHistory, agentMessage, *toolResults)
        continue
    }
    return AgentEvent{Type: AgentEventTypeResponse, Message: agentMessage, Done: true}
}
```

Each iteration streams an LLM response, executes any tool calls sequentially, appends results as a `Tool`-role message, and loops. The LLM controls the loop solely by choosing whether to emit tool calls.

#### LangGraph (langchain-ai/langgraph)

Expresses the same pattern as a declarative graph over a Pregel (Bulk Synchronous Parallel) execution engine. The standard React agent defines two nodes and a conditional edge:

```python
workflow.add_node("agent", call_model)
workflow.add_node("tools", tool_node)
workflow.add_conditional_edges("agent", should_continue, path_map=["tools", END])
workflow.add_edge("tools", "agent")
```

Where `should_continue` performs the same check as opencode's `if FinishReason == ToolUse`:

```python
def should_continue(state):
    last_message = state["messages"][-1]
    if not isinstance(last_message, AIMessage) or not last_message.tool_calls:
        return END
    return "tools"
```

The Pregel engine adds parallel node execution, checkpoint-based resumability, and dynamic branching via `Send()` objects—but for the basic agent case, the control flow is identical to opencode's for-loop.

#### The agent loop invariant

Despite architectural differences (simple loop vs. graph engine, sequential vs. parallel, mutable vs. immutable state), the LLM's role is structurally identical in both systems: it receives the current message history, produces one response, and external code decides what happens next. The LLM never plans multiple steps, never sees the graph topology, and never specifies its own control flow. It is a passive, one-step-at-a-time participant in a developer-authored harness.

**Distinction from Spell:** This invariant is precisely what Spell breaks. In these frameworks, the developer writes the execution topology and the LLM fills in content. In Spell, the LLM writes the execution topology itself—deciding what recursive calls to make, what context to pass, and how to branch—using the same natural language medium it already operates in.

---

## 2. Constrained Generation and LLM Programming Languages

A distinct research thread focuses on programming languages and frameworks for controlling LLM output. These are highly relevant to Spell because they address the same fundamental problem: how to structure interaction with LLMs programmatically.

### 2.1 LMQL (Language Model Query Language)

**Source:** Beurer-Kellner, Fischer & Vechev, ETH Zurich, Dec 2022 - arxiv.org/abs/2212.06094

LMQL is a SQL-like programming language for LLM interaction that combines natural language prompting with scripting and output constraints.

**Key features:**
- Python superset with special query syntax
- Template variables `[ANSWER]` filled by LLM generation
- `where` clauses for output constraints (length, stopping, type)
- Control flow (loops, conditionals) interleaved with generation
- Nested queries for modular prompt composition
- Multiple decoding strategies (argmax, sample, beam search)
- Logit masking for efficient constraint enforcement

**Example:**
```python
@lmql.query
def chain_of_thought():
    '''lmql
    "A: Let's think step by step.\n [REASONING]"
    "Therefore the answer is[ANSWER]" where STOPS_AT(ANSWER, ".")
    return ANSWER.strip()
    '''
```

**Key innovation:** "Language Model Programming" (LMP) - the idea that prompting is programming, with control flow and constraints as first-class citizens.

**Relevance to Spell:** LMQL and Spell share the insight that LLM interaction should be programmable. Key differences:

| Aspect | LMQL | Spell |
|--------|------|-------|
| Who writes programs | Human developer | LLM itself |
| Control flow | Python-based, external | S-expressions, LLM-generated |
| Primary goal | Constrain output format | Enable self-orchestration |
| Execution model | Human-defined queries | Recursive self-calls |

LMQL's nested queries are structurally similar to Spell's `llm` calls—both spawn sub-computations with local context. However, LMQL programs are written by humans; Spell programs are written by LLMs about themselves.

### 2.2 Guidance (Microsoft)

**Source:** Microsoft Research, 2023 - github.com/guidance-ai/guidance

Guidance is a framework for "steering" LLM generation through interleaved control and constrained generation.

**Key features:**
- Template-based syntax mixing text and Python
- Constrained generation via regex, JSON schemas, context-free grammars
- Token-level control through logit manipulation
- `@guidance` decorator for composable generation functions
- Role-based chat syntax (`with system():`, `with user():`)
- "Fast-forwarding" deterministic tokens

**Example:**
```python
from guidance import gen, select

lm = guidance.models.Transformers("microsoft/Phi-4-mini-instruct")
lm += "Pick a color: " + select(["red", "green", "blue"])
lm += "\nNow describe it: " + gen(max_tokens=50)
```

**Key innovation:** Constraints compiled to token masks, applied during generation (not post-hoc validation). Deterministic structure is "fast-forwarded" without LLM calls.

**Relevance to Spell:** Guidance's approach to constrained generation maps to Spell's pattern system. Both enforce output structure, but:

| Aspect | Guidance | Spell |
|--------|----------|-------|
| Constraint source | Human-specified schemas | LLM-specified patterns |
| Execution | Single LLM call with steering | Recursive LLM calls |
| Purpose | Format enforcement | Self-orchestration |

Spell's patterns (§8 in spec) could potentially be implemented using Guidance-style logit masking for efficiency.

### 2.3 Outlines

**Source:** .txt (dottxt), 2023 - github.com/dottxt-ai/outlines

Outlines guarantees structured output during generation using finite state machines derived from constraints.

**Key features:**
- Regex, JSON Schema, and context-free grammar support
- FSM-based token masking
- "Coalescence" - skipping deterministic token sequences
- Zero overhead over vanilla generation (constraint checking is ~free)
- Rust core (`outlines-core`) for portability

**Key innovation:** Compiling constraints to finite state machines that efficiently compute valid next tokens. Deterministic paths through the FSM can be "coalesced" and generated without LLM calls.

**Relevance to Spell:** Outlines focuses purely on output format, not orchestration. However, its techniques could be used to implement Spell's pattern matching efficiently. The coalescence optimization (skipping deterministic tokens) is analogous to what Spell achieves when the LLM's response follows predictable structure.

### 2.4 Comparison: Constrained Generation vs Self-Orchestration

These frameworks (LMQL, Guidance, Outlines) solve a different problem than Spell:

| Property | LMQL/Guidance/Outlines | Spell |
|----------|------------------------|-------|
| Central question | "How do I get structured output?" | "How does the LLM control its own execution?" |
| Program author | Human developer | LLM itself |
| Recursion | Limited (LMQL nested queries) | Arbitrary recursive `llm` calls |
| Context manipulation | Not addressed | Core feature (context surgery, hooks) |
| Meta-level reasoning | Not addressed | Explicit (hooks, thunk expansion) |

However, there's potential synergy:
- Spell's pattern system could use Guidance/Outlines for efficient constraint enforcement
- LMQL's nested query semantics informed similar mechanisms in Spell
- The "prompting is programming" insight from LMQL validates Spell's approach

### 2.5 XGrammar and llguidance

Worth noting: recent work on efficient grammar-based constrained decoding (XGrammar, llguidance) achieves near-zero overhead structured generation. These could serve as implementation backends for Spell's pattern system.

---

## 3. Classical Cognitive Architectures

### 3.1 SOAR

**Source:** Laird et al., 1980s-present - soar.eecs.umich.edu

Production system with metacognitive capabilities through "impasses."

**Key features:**
- Impasses trigger substates for metareasoning
- Recursive metacognition for planning
- Object-level vs meta-level processing

**Relevance to Spell:** SOAR's impasse mechanism is structurally similar to Spell's `llm` call—when the system can't proceed, it spawns a meta-level process. However, SOAR operates on symbolic representations with fixed production rules, not natural language programs written by the agent itself.

### 3.2 ACT-R

**Source:** Anderson et al.

Cognitive modeling architecture with procedural and declarative memory.

**Key features:**
- Buffer-based processing
- Meta-level control via buffer states
- Part of "Common Model of Cognition" with SOAR, Sigma

**Relevance to Spell:** ACT-R's production rules fire based on buffer contents—a form of pattern-matching that drives control flow. Spell's approach is more explicit: the LLM writes out what should happen next rather than having it emerge from rule matching.

### 3.3 MIDCA (Metacognitive Integrated Dual-Cycle Architecture)

Explicit two-layer architecture: object-level + meta-level.

**Key features:**
- Meta-level monitors and controls object-level
- Reflective loop between cycles
- Explicit separation of concerns

**Relevance to Spell:** MIDCA's dual-cycle structure maps onto Spell's parent/child LLM relationship. The parent operates at a meta-level, orchestrating children. The key difference: in MIDCA, the meta-level is architecturally fixed; in Spell, the LLM writes the meta-level behavior.

---

## 4. Foundational Concepts

### 4.1 Meta-Circular Evaluators

**Source:** McCarthy (1960s), popularized by SICP Chapter 4

An interpreter written in the language it interprets.

**Key properties:**
- Language features defined in terms of themselves
- Self-hosting enables modification of language semantics
- "Eval/apply" loop as universal pattern

**Relevance to Spell:** Spell's `spell-eval` is intentionally meta-circular in spirit—Lisp evaluating Lisp. The manuscript's treatment of evaluation semantics draws directly from this tradition. The novel element is that the *LLM* writes the expressions being evaluated.

### 4.2 3-Lisp and Reflective Towers

**Source:** Brian Cantwell Smith, 1982 dissertation

Introduced computational reflection with "reflective lambdas."

**Key innovation:** A reflective lambda executes one tower level *above* its caller. This creates an infinite tower of meta-circular interpreters, each interpreting the level below.

**Direct relevance to Spell:** 3-Lisp's reflective lambdas are the closest historical precedent to Spell's core mechanism. When an LLM calls `(llm ...)`, it's spawning a process that runs "above" it—with access to the caller's structure. The child LLM sees its parent's completion as its prefix.

**Key paper:** "Procedural Reflection in Programming Languages" - link.springer.com/article/10.1007/BF01806174

### 4.3 Continuation-Passing Style (CPS)

Makes control flow explicit by passing "what to do next" as a parameter.

**Relevance to Spell:** Spell's thunks (quoted expressions passed to children) function similarly to continuations—they represent "deferred computation." The `expand` function ensures free variables are captured, analogous to closure capture in CPS transformation.

---

## 5. Meta-Level Reasoning and Reflection

### 5.1 Metareasoning

**Source:** Cox & Raja

The study of reasoning about reasoning—deciding whether to act or continue deliberating.

**Key distinction:**
- Object-level: reasoning about the world
- Meta-level: reasoning about one's own reasoning process

**Relevance to Spell:** Spell's hooks (return hooks, recursive hooks) implement metareasoning. A return hook examines the output of a child LLM and decides what to do—that's reasoning about reasoning. The "Ralph loop" pattern (judge completion, recurse if incomplete) is explicit metareasoning.

### 5.2 Reflective AI (2025 discourse)

Emerging concept of AI systems with recursive feedback loops and meta-level process control.

**Key themes:**
- Context retention across iterations
- Policy adaptation without human input
- Self-monitoring and correction

**Relevance to Spell:** This discourse provides contemporary framing for Spell. The language can be positioned as enabling "reflective AI" capabilities—the LLM monitors its own progress and adapts its execution strategy.

### 5.3 Introspective Awareness in LLMs

**Source:** Anthropic, 2025 - transformer-circuits.pub/2025/introspection/

Research showing Claude can detect concepts in its own activations.

**Relevance to Spell:** If LLMs develop reliable introspective capabilities, Spell could leverage these. An LLM might write orchestration logic based on self-assessment of confidence, confusion, or uncertainty.

---

## 6. Program Synthesis and Self-Modification

### 6.1 Gödel Machine

**Source:** Schmidhuber, 2003-2007 - arxiv.org/abs/cs/0309048

Self-referential universal problem solver that rewrites any part of its own code when provably useful.

**Key properties:**
- Global optimality (no local maxima)
- Self-referential proofs
- Theoretically optimal but incomputable

**Relevance to Spell:** The Gödel Machine represents the theoretical limit of self-improvement. Spell is more modest—it doesn't require proofs of improvement—but shares the self-referential structure. An LLM writing its own execution graph is a soft version of the Gödel Machine's self-rewriting.

### 6.2 Darwin Gödel Machine

**Source:** Clune et al., 2025

LLM agent that iteratively modifies its own prompts, tools, and code.

**Relevance to Spell:** This recent work brings Gödel Machine ideas into the LLM era. The key difference: Darwin Gödel Machine modifies code/prompts over many episodes; Spell enables modification *within* a single execution through recursive structure.

### 6.3 AIXI

**Source:** Hutter, 2000-2005 - arxiv.org/abs/cs/0012011

Universal reinforcement learning agent combining Solomonoff induction with sequential decision theory.

**Relevance to Spell:** AIXI is the theoretical optimum for sequential decision-making. Spell's practical contribution is enabling LLMs to approximate aspects of meta-level reasoning that AIXI would compute perfectly (if it were computable).

---

## 7. Homoiconicity and Code-as-Data

### 7.1 The Homoiconicity Property

A language where code and data share the same representation.

**Key enablers in Lisp:**
- S-expressions for both code and data
- `quote` prevents evaluation
- `eval` executes data as code

**Relevance to Spell:** The manuscript explicitly notes that Spell inherits homoiconicity from Lisp. This is why an LLM completion can be passed as context to a child—it's both a program (to be evaluated) and data (to be read).

### 7.2 Natural Language as Homoiconic Medium

A novel observation: natural language is naturally "homoiconic" for LLMs.

- The LLM's input is text
- The LLM's output is text
- Instructions about text are themselves text

**This may be Spell's deepest insight:** Just as Lisp's homoiconicity enables metacircular evaluation, natural language's "homoiconicity" for LLMs enables self-orchestration. The LLM can write instructions for future LLM calls because instructions and content share the same medium.

---

## 8. Synthesis: What's Novel About Spell

### 8.1 Comparison Matrix

| Property | PARL | Cursor | RLM | MemGPT | ToT | ReAct | DSPy | LMQL | Spell |
|----------|------|--------|-----|--------|-----|-------|------|------|-------|
| Model-designed orchestration | **Yes** (weights) | No (human) | Partial | No | No | No | No | No | **Yes** (code) |
| Inspectable orchestration | No | Yes (harness) | Via Python | No | No | No | No | No | **Yes** |
| Arbitrary control flow | Learned | Fixed | Via Python | No | No | No | No | Via Python | **Yes** |
| Recursive self-calls | Spawns agents | No | Yes | No | No | Loop only | No | Nested queries | **Yes** |
| Natural language medium | No | No | No (Python) | No | No | Yes | No | Hybrid | **Yes** |
| Context surgery | No | No | No | Memory ops | Backtrack | No | No | No | **Yes** |
| Meta-level hooks | No | No | No | No | No | No | No | No | **Yes** |
| Trainable via RL | **Yes** | No | No | No | No | No | **Yes** (prompts) | No | Possible |

### 8.2 Unique Contributions

1. **Orchestration as inspectable code:** PARL demonstrates that model-designed orchestration works, but the policy is opaque (in weights). Spell represents the same capability as source code—readable, debuggable, composable. This is the "interpreted" vs "compiled" distinction applied to orchestration.

2. **Composable orchestration patterns:** Cursor's experiment shows that topology matters and requires iteration. In harness-based systems, combining patterns (e.g., planner-worker + map-reduce) requires new glue code. In Spell, it is function composition—a structural advantage for rapid iteration over topologies.

3. **Natural language as orchestration language:** RLM uses Python; Spell proposes the orchestration *is* the natural language output. The S-expression syntax is a formalization, but the LLM's "native" output is the program.

4. **Context surgery:** No prior work explicitly addresses the LLM's ability to manipulate what context its children see. Hooks enable progressive disclosure, closures across LLM boundaries, and CoT pruning.

5. **Hooks as metareasoning:** The undisclosed context hooks, return hooks, and recursive hooks are a novel architectural contribution for implementing meta-level control.

6. **Formal semantics in Lisp tradition:** By grounding Spell in Lisp, the project connects to decades of formal semantics work. This enables precise reasoning about scope, evaluation order, and environment threading.

7. **RL action space for self-orchestration:** Spell provides a small, formal language that could serve as the action space for RL-driven improvement in self-orchestration—analogous to how structured function-calling schemas enabled RL to make tool use reliable.

### 8.3 Gap in Literature

PARL proves model-designed orchestration can outperform fixed topologies. Cursor proves orchestration topology is a first-class concern requiring iteration. But no existing system represents model-designed orchestration as inspectable, composable source code with formal semantics. Spell occupies this gap:
- PARL: model-designed, opaque, learned
- Cursor: human-designed, inspectable, fixed
- Spell: model-designed, inspectable, composable

---

## 9. Recommendations for Manuscript

### 9.1 Position Against Prior Work

Frame Spell in relation to:

1. **PARL (primary comparison):** "PARL demonstrates that model-designed orchestration achieves state-of-the-art results via RL. Spell proposes a complementary representation: orchestration as source code rather than weights. This provides transparency, composability, and debuggability. The natural next step is RL training over Spell programs, combining PARL's learning with Spell's inspectability."

2. **Cursor Agent Swarm:** "Cursor's experiment shows orchestration topology is a first-class concern requiring iteration. Three topologies failed before one succeeded. Spell makes topology programmable, enabling faster iteration. A pattern library of known-good topologies lets models select and compose rather than invent from scratch."

3. **RLM:** "While RLM treats prompts as Python variables, Spell treats the LLM's output itself as a program. The orchestration is not mediated by code—it *is* the output."

4. **3-Lisp:** "Smith's reflective lambdas (1982) execute one tower level above their caller. Spell's `llm` primitive creates analogous meta-level execution, but with natural language as the medium and LLMs as the processors."

### 9.2 Key Claims to Support

1. **Model-designed orchestration works:** PARL provides the evidence. The question is not whether models can orchestrate, but how to represent the orchestration policy.

2. **Code representation offers unique advantages:** Transparency (read the strategy), composability (combine patterns), debuggability (see why decomposition failed), transferability (patterns work across tasks).

3. **Spell as RL action space:** The language provides a formal, constrained action space for training orchestration policies—analogous to how function-calling schemas enabled RL to make tool use reliable.

4. **Generality:** Spell subsumes ReAct, ToT, and similar patterns as special cases.

5. **Formalization:** Lisp grounding provides formal semantics unavailable in natural-language-only approaches.

### 9.3 Terminology Alignment

The literature uses various terms. Suggested mappings:

| Literature term | Spell equivalent |
|-----------------|-----------------|
| Meta-level | Parent LLM / hooks |
| Object-level | Child LLM |
| Impasse (SOAR) | Recursive `llm` call |
| Continuation | Thunk / quoted expression |
| Reflective lambda | `llm` with quoted completion |

---

## 10. Further Reading

### Essential Papers

1. **Kimi K2.5 PARL:** kimi.com/blog/kimi-k2-5.html - Primary comparison; trainable orchestrator via RL
2. **Cursor Agent Swarm:** cursor.com/blog/scaling-agents - Orchestration topology matters; human-designed hierarchy
3. **RLM:** arxiv.org/abs/2512.24601 - LLM writes recursive Python to call itself
4. **3-Lisp:** link.springer.com/article/10.1007/BF01806174 - Historical foundation
5. **MemGPT:** arxiv.org/abs/2310.08560 - Self-directed memory
6. **SICP Ch. 4:** sarabander.github.io/sicp/html/4_002e1.xhtml - Meta-circular evaluation
7. **Gödel Machine:** arxiv.org/abs/cs/0309048 - Theoretical limit
8. **LMQL:** arxiv.org/abs/2212.06094 - "Prompting Is Programming" (PLDI'23)
9. **XGrammar:** arxiv.org/pdf/2411.15100 - Efficient constrained decoding

### Surveys

1. **Code Generation Agents:** arxiv.org/pdf/2508.00083 - Recent landscape
2. **Reflective Towers:** blog.sigplan.org/2021/08/12/reflective-towers-of-interpreters/
3. **Constrained Decoding:** github.com/Saibo-creator/Awesome-LLM-Constrained-Decoding

### Implementations

1. **RLM code:** github.com/ysz/recursive-llm
2. **3-Lisp code:** github.com/nikitadanilov/3-lisp
3. **DSPy:** github.com/stanfordnlp/dspy
4. **LMQL:** github.com/eth-sri/lmql
5. **Guidance:** github.com/guidance-ai/guidance
6. **Outlines:** github.com/dottxt-ai/outlines

---

## 11. Conclusion

Spell occupies a distinctive position in the landscape of LLM orchestration. PARL (Kimi K2.5) validates the core premise—model-designed orchestration can outperform fixed topologies—but represents the orchestration policy in weights. Cursor's agent swarm demonstrates that orchestration topology is a first-class engineering concern requiring iteration. Spell proposes a third path: orchestration as inspectable, composable source code written by the model itself.

The contribution is the representation choice: orchestration as code rather than weights (PARL) or harness configuration (Cursor/LangGraph). This provides transparency, debuggability, and composability—properties that become increasingly important as orchestration strategies grow more sophisticated. The formal Lisp grounding connects to decades of work on meta-circular evaluation, reflective programming (3-Lisp), and program semantics.

The natural next step is RL training over Spell programs, combining PARL's demonstrated ability to learn orchestration with Spell's transparent, composable representation. The language provides a constrained, formal action space—analogous to how structured function-calling schemas enabled RL to make tool use reliable.
