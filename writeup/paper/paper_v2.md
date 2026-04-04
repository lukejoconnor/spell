# Spell: Self-Programmed Execution for Language-Model Agents

## Abstract

Agent performance increasingly depends not only on the underlying language model (LM), but also on the program that invokes the LM, manages context, executes tool calls, and orchestrates subagents. We study an extreme point in this design space: **self-programmed execution (SPE)**, in which the LM emits a program and the harness does nothing except evaluate it. This program is responsible for recursion, for context management, for tool calling, and for subagent orchestration. We propose a language for such programs, **Spell**, a dialect of Lisp with special features for self-referencing and self-editing programs. In a wide range of benchmarks, an SPE agent with Spell achieves parity with strong existing agent harnesses, despite its conceptual minimalism. We formally define SPE as an agentic machine and show that a large class of agentic machines can be reproduced within the SPE machine with an LM-written program. Together, these results suggest that almost every function of a traditional agentic harness can be delegated to the model itself. 

## Introduction

Agentic systems combine a traditional, deterministic computation with generative, intelligent computation interactively. The deterministic component is called the *harness*, the intelligent component is typically a language model (LM), and the interaction between them is two-way: the harness invokes the LM; the LM writes tool calls which the harness executes. A particularly important function of the harness is to manage the context which is supplied to the LM. In most current systems, the harness contains a large amount of hard-coded logic which is not within the agent's control.


A recent trend is to grant models greater autonomy within a system to manage their own context and orchestrate their own subagents. A typical approach is to provide custom tools which, instead of interacting with an external environment, modify the internal runtime environment of the harness. In particular, such a tool might manipulate the string of context which is fed back to the LM on its subsequent turn. This *internal autonomy* is especially attractive for general-purpose agents, as a particular context engineering heuristic may fail when faced with arbitrary tasks, and for long-running agents, as the right approach to a long-horizon task may not be apparent at the outset. This manuscript addresses the question: *what agentic architecture maximizes internal autonomy?* 

Agentic systems combine traditional, deterministic computation with generative, intelligent computation interactively. Recent progress in agentic systems has emphasized the importance of the agentic harness. This program determines when to call the model, what context to provide, and how to coordinate subagents. In most current systems, these choices are largely fixed by hand-written control logic.

This raises a simple question: **how far can internal autonomy be pushed if the harness is reduced to an evaluator?** Rather than giving the model a menu of special tools for context editing or subagent management, can we let it write the program that determines its own next call?

We study this idea through **self-programmed execution (SPE)**. In SPE, the LM receives a prefix, completes it into a program, and the harness evaluates that program:

```text
completion <- lm(prefix)
eval(completion)
```

The key difference from a standard tool-call loop is that the program produced by the model is itself responsible for deciding whether to call the LM again, what prefix to construct, what parts of prior context to preserve, and how to compose tool calls with control flow. From the model's perspective, the agent is therefore largely self-programmed: the harness executes, but does not orchestrate.

This perspective contrasts with standard ReAct-style systems. In a ReAct loop, the harness determines the iterative structure: it constructs a prompt, calls the model, interprets a tool request if one is produced, executes the tool, and then builds the next prompt. The model controls some local actions, but not the overall orchestration pattern. By contrast, in SPE the orchestration logic itself can be expressed inside model-written code. A model can reproduce a ReAct loop if that is appropriate, but it can also depart from that pattern and use recursion, branching, pruning, compaction, subagents, or task-specific control flow.

Making SPE practical requires a language in which self-reference and context transformation are natural. We propose **Spell** (Self-Programmed Execution Language for LMs), a Lisp-like language embedded in Clojure. Spell is motivated by four design principles:

1. **The model should be able to treat its own program as data.**
2. **Side effects should occur only at a controlled boundary.**
3. **The runtime environment should match what the model can actually observe.**
4. **Context management and orchestration should be ordinary program transformations, not special-case harness logic.**

These principles lead to a language with explicit self-reference, a trailing-expression discipline that gates effects, fresh local environments for self-calls, and first-class support for pruning, persistence, and subagent orchestration.

The resulting system has three complementary contributions. First, it contributes a **minimal agent architecture**: the harness is reduced to an evaluator plus ordinary tools, while orchestration is expressed in model-written code. Second, it contributes a **language and runtime** that make this architecture usable in practice. Third, it contributes both **empirical** and **formal** evidence: empirically, Spell remains competitive with strong hand-written harnesses when the underlying model is held fixed; formally, SPE is universal for realizable agentic machines.

The rest of the paper proceeds as follows. Section 2 situates SPE relative to prior work on context management, subagent systems, and self-improving scaffolds. Section 3 introduces Spell at a high level and explains the mechanisms that make SPE practical. Section 4 reports empirical results. Section 5 states the universality theorem. Appendix A describes the language design and runtime in detail.

## Related work

Spell sits at the intersection of three lines of work: context-window self-management, subagent orchestration, and self-improving scaffolds.

### Context-window self-management

A growing line of work gives agents limited control over their own context. Some methods allow the agent to summarize or fold recent history; others let it select, compress, or rewrite older parts of the prompt. These systems increase autonomy, but the overall loop is still typically controlled by a hand-written harness: the model selects from a predefined action space, and the harness decides when and how the next turn occurs.

Spell pursues the same goal from a more general angle. Rather than exposing context editing as a fixed tool inside an otherwise conventional loop, Spell lets the model construct the next prefix directly. Context pruning, persistence, compaction, and summarization are therefore expressed as program transformations over the model's own completion.

### Subagent architectures

A second line of work studies systems that spawn or coordinate multiple agents. Practical systems such as Claude Code and Codex-style command-line agents use subagents, worker-style task decomposition, or structured multi-agent interaction. Academic work has explored related ideas in thread-like or recursive language-model architectures.

Spell is closest in spirit to recursive LM systems in which model calls are composable with ordinary program logic. The main difference is that Spell treats the **completion itself** as the persistent object of computation. This makes context construction explicit and programmable. A subagent can be launched with an arbitrary program prefix, rather than only with a prompt assembled by a separate harness.

### Self-improvement and meta-optimization

Another nearby line of work attempts to optimize prompts, programs, or scaffolds through search, training, or objective-driven self-improvement. These systems share with Spell the intuition that hand-written harnesses are unlikely to be optimal.

The difference is that Spell moves adaptation into **runtime orchestration** itself. Self-improving systems search for a better harness and then deploy it. Spell instead lets the model build and modify its own harness behavior online, inside the execution loop for the current task. These directions are complementary rather than competing.

## Self-programmed execution and Spell

Spell is a language and runtime for implementing SPE. The central object is a **completion**: the prefix passed to the LM together with the suffix written by the LM. In Spell, the completion is executable code. A self-call therefore works by constructing a new prefix, asking the LM to complete it, and evaluating the result.

### Why Lisp

In SPE, the program is not merely an implementation artifact; it is also the object that carries context across turns. A practical SPE language must therefore make it easy for the model to inspect, transform, and partially rewrite its own source.

Lisp is a natural fit because code is represented as data. Spell uses this property to make self-reference explicit. The model can bind its current completion, traverse it structurally, remove stale subexpressions, replace them with compact summaries, and pass the resulting program to a child call. In more conventional languages this is possible in principle, but much less natural in practice.

### Core execution pattern

The central primitive is `!llm-self`, which accepts a prefix, calls the LM to complete that prefix, evaluates the resulting completion, and returns its value. At a high level, a ReAct-style iteration can be expressed as:

```text
completion <- source code of current program
result <- tool(...)
next-prefix <- completion + serialized(result)
!llm-self(next-prefix)
```

The important point is not that Spell reproduces ReAct. Rather, ReAct becomes just one program pattern among many. Because `!llm-self` is an ordinary callable form, it can be embedded in conditionals, loops, worker-checker patterns, map-style dispatch, or user-defined orchestration functions.

### Self-reference and inert extensions

Spell programs are wrapped so that the current completion is available as data. The wrapper also enforces a crucial invariant: **side effects occur only through the trailing expression**. When the model extends a prior completion, earlier effectful expressions become inert rather than firing again. This lets the model keep using its own completion as context without accidentally re-executing old tool calls or LM calls.

This invariant is one of the key technical ideas in Spell. It means the model can safely append to a previously generated program even though it cannot rewrite the already-evaluated prefix. The prefix remains informative but inert; only the final quoted expression is allowed to produce the next externally visible action.

### Local environments and visible state

A standard language runtime allows programs to depend on bindings that are invisible in source—for example, global variables or lexical closures. In an agentic setting, that kind of hidden state is problematic because the model cannot reason about it directly.

Spell instead evaluates self-calls in fresh local environments that are aligned with the visible completion. Bindings that matter for future behavior are represented in the program itself. This keeps the model's context, the runtime state it can rely on, and the program it manipulates closely aligned.

This design motivates another deliberate departure from ordinary Clojure: Spell functions are environment-based rather than closure-based. The benefit is that the model does not have to reason about opaque captured state outside its context window.

### Context management as a language feature

Because the completion is first-class data, Spell can provide lightweight operators for context management. In particular, the language supports:

- **pruning**, which removes stale prior subexpressions from future prefixes;
- **persistence**, which materializes selected derived values so they survive pruning;
- **compaction**, which rewrites a long completion into a shorter self-contained program;
- **tool-result serialization**, which lets the model refer to prior outputs by binding rather than by re-emitting raw text.

These mechanisms are described in detail in Appendix A. The important high-level point is that they are not external heuristics imposed by the harness. They are part of the agent's own program logic.

### Subagents and concurrency

Spell also supports subagent creation and inter-agent communication. A self-call via `!llm-self` should be understood as the same agent taking another turn. By contrast, `spawn` creates a distinct handle that can run asynchronously, exchange messages, and later reawaken a sleeping parent. This lets the model express common multi-agent patterns—parallel decomposition, ask-and-wait, message passing, worker pools—within the same execution language.

## Empirical evaluation

The empirical section should make a narrow claim: **Spell is a competitive agent architecture, not necessarily a universally superior one**.

The cleanest experimental question is:

> If we hold the underlying model, tools, and budgets fixed, how much capability is lost when a hand-written orchestration harness is replaced by self-programmed execution?

Our current results suggest that the answer is: **not much**. Across a diverse benchmark suite, Spell paired with Opus or GPT-5.4 shows rough parity with strong existing systems that use the same respective models, including Claude Code and Codex-style command-line agents. That result matters because Spell uses a much more minimal and analyzable harness.

In the final version, this section should report not only task success but also the quantities that make the comparison interpretable: number of LM calls, total input and output tokens, wall-clock time, and cost. It should also include a **feature-utilization analysis** showing when the model actually uses Spell-specific mechanisms such as pruning, persistence, compaction, or subagents. Otherwise, a reviewer can reasonably ask whether the language is central to the observed performance or merely ornamental.

Ablations would make the story stronger. In particular, it would be useful to compare against restricted Spell variants that disable pruning, persistence, or subagent support, and to analyze which benchmarks benefit from each feature.

## Universality of SPE

We formalize an agentic machine as a triple \(X = (S, p, h)\), where \(S\) is a set of states, \(p\) maps each state to the prompt shown to the model, and \(h\) maps a state together with a completion to the next state, halting, or divergence. An **embedding** of one agentic machine into another is an injective map on states that preserves both the prompt observed by the model and the transition structure induced by completions.

The SPE machine is defined by wrapping a standard evaluator around LM calls. Its states are evaluator states at which the next external action is an LM invocation. We say that another agentic machine is **realizable** if its prompt function and transition function can be implemented in the underlying evaluator. We then show that for any realizable machine, there exists an SPE state whose possible completions generate an embedded copy of that machine.

Informally, the theorem says that SPE is universal for realizable agent architectures. Any harness behavior that can itself be written in the evaluator can be compiled into model-written code so that, from the model's perspective, the induced prompt/transition structure is the same.

This result clarifies the meaning of “self-programmed.” The claim is not that the evaluator disappears, but that the evaluator no longer needs to contain bespoke orchestration logic. Up to embedding, the model's own program can determine the next agent state.

The theorem also explains why Spell's completion discipline matters. If old turn-producing expressions could fire again when a completion is extended, then the model would lose control over successor states. The trailing-expression pattern preserves the property that the next turn is determined by the current completion, not by inert remnants of earlier control flow.

## Discussion

Spell is best understood as a minimal, programmable agent substrate. Its main value is not that it hard-codes a particularly clever loop; it is that it removes most fixed loop structure and lets the model write one instead. This makes the architecture easier to analyze, easier to formalize, and potentially easier to improve through future training or search.

At the same time, SPE introduces trade-offs. Because the model may rewrite earlier context, Spell can lose KV-cache efficiency relative to a conventional append-only loop. Some of Spell's design choices, especially explicit self-reference and dynamic environments, also prioritize transparency and controllability over familiarity to programmers. These are real costs. The empirical question, then, is whether the added autonomy repays them. Our preliminary results suggest that it can.

More broadly, Spell suggests that “agent architecture” may be a temporary distinction. If orchestration logic can itself be expressed in model-written programs, then some of what is now treated as harness design may eventually become part of the model's own learned behavior. SPE provides one concrete framework in which to study that transition.
