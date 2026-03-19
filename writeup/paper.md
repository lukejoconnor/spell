# Self programmed execution language for large language models

## Todos

- Completeness; implementation of RLMs, others in Spell?
- Abstraction of existing methods for context management
- Diagram with A-B vs A-A
- RLMs pseudocode with harness-controlled steps highlighted

## Abstract

We propose a self-programmed agentic system in which a language model (LM) emits a program, and the agentic harness does nothing except execute it. The program can construct a prompt or prefix and feed it back to the model, allowing it to edit or extend its own context window. It can spawn and feed subagents the same way, and it can compose model calls with tool calls and ordinary control flow. We call this approach *self-programmed execution*. We implement a language, Spell (self-programmed execution language for LMs), a dialect of Lisp with distinctive features that support self-referencing and self-editing programs.

## Introduction

The performance of agentic systems depends sensitively on their approach to orchestration and context management. Traditionally, these are the responsibility of the agentic harness, which is programmed by humans to curate what prompts and what context are sent to the model each turn. Recently, however, a trend has been to grant models greater autonomy with respect to their own context and their own orchestration. 
For a nontrivial task, it may not be apparent immediately what context is relevant, nor how to decompose it into steps; in such cases, it could be advantageous to let the model discover context progressively and decompose its work iteratively.
This manuscript addresses the question: *what is the maximum degree to which an agentic system can be controlled by the language model, as opposed to the harness, autonomously?* 

In the system which is proposed, the LLM produces a program, and the harness evaluates it. This system is maximally autonomous because all of the logic which is normally wired into the harness is programmed instead by the LLM itself. 
It is also simple, at least conceptually. We refer to this approach as *self-programmed execution*: from the perspective of the LLM, the system is entirely self-programmed, and the harness is reduced to an execution layer. 
Concretely, self-programmed execution refers to the following "algorithm":
```
completion <- llm(prefix)
eval(completion)
```
where `llm` calls an LLM to complete the prefix to a program, and `eval` executes it. The language of this program should be flexible enough to reproduce the typical logic of an agentic harness ergonomically. 

Self-programmed execution contrasts with the ReAct loop underlying most agentic systems. In ReAct: (1) the harness produces a prefix and passes it to the model; (2) the model generates a response, possibly encoding a tool call; (3) the harness executes the tool call and recurs to step (1). The generative step (2) controls the execution step (3), but not the orchestration step (1). More generally, existing agentic systems alternate between a step controlled by the LM and a step controlled by the harness, even when those steps differ in their specifics from those of the ReAct loop (see Related Work). What distinguishes self-programmed executon is that the LM-controlled step subsumes the harness-controlled step entirely (Figure 1a). 

An LM could reproduce an iteration of the ReAct loop using the following program:
```
completion <- source code of this program
prompt <- "..."
result <- tool call
context <- concatenate completion and result
eval(llm(context))
```
On turn 2, the model would receive as context both the original prompt, the program written on turn 1, and the result of the tool call.

Any implementation of self-programmed execution must specify the language in which the program is written. This paper proposes such a language, *Spell* (Self-Programmed Execution Language for LLMs), a dialect of Lisp which closely resembles Clojure. Section 1 introduces Spell and its core semantics. Section 2 explores the theoretical capabilities of an agentic system using Spell through a series of examples. Section 3 measures the effect of using Spell on performance in practice, with current LLMs; it tests Spell on a range of benchmarks and an analysis of what features of Spell are actually utilized. Section 4 explores the gap between theory and practice and identifies possible bottlenecks.

## Related work

Several existing methods support agentic autonomy by providing it with added capabilites from within a traditional harness. Figure 1b provides a mental model for how these methods relate: they specify different action spaces for the step controlled by the LM, and different logic for the step controlled by the harness, while retaining the same alternating topology. 

One major category includes methods that allow the LM to manage its own context window. This category includes FoldGRPO, in which the agent folds or summarizes a suffix of its previous context history; this approach has the advantage that the remaining prefix hits the KV cache. Another such method is AgentFold, in which the agent additionally may fold or summarize older context items anywhere in its context window; compared with ordinary compaction, AgentFold adds the ability to summarize or fold certain context items and not others. A third method in this category is SCULPTOR, in which the agent may fragment its context before folding or summarizing specific fragments; Spell solves the same problem by giving bindings to string literals so that they can be referenced using variables. In each case, although the model controls its own context, the harness retains control over the agent loop itself.

A second category includes multi-agent or agent-subagent architectures, which allow the LM to deviate from the traditional agent loop. Claude Code implements three such architectures: a subagent architecture, a "tasks" architecture, and a "teams" architecture which additionally enables messaging between subagents. Kimi K2.5 utilizes "swarms" of subagents for tasks which are parallelizable. In the academic literature, THREAD is one example of a subagent architecture.

An especially notable subagent architecture is recursive language models (RLMs), which combine the ability to spawn subagents with two additional features. First, RLMs dispatch subagents programmatically, via a special function (akin to `llm`) which is composable with other functions (e.g., `map`). Second, RLMs store context in a subagent-specific runtime environment which is controlled programmatically. However, RLMs do not actually allow agents to manage their own context window; like in ReAct, once tokens enter their context window (as opposed to their runtime environment), they cannot be evicted.


## Self-Programmed Execution Language for LLMs

Spell is domain specific language embedded within Clojure, a modern dialect of Lisp. From Lisp it inherits homoiconicity, the idea that source code can be manipulated as data. In Spell, an LLM's completion is code, this code can manipulate itself as data, and in particular, it can replicate itself within the context window of a subsequent LLM call. 

A *completion* refers to the concatenation of a prefix, which is passed as the input to an LLM, and a response, which is the output. In Spell, the special function `!llm-self` accepts a prefix as its argument, passes it to the LLM, obtains a completion, and evaluates the completion as code. The reason that the LLM completes the prefix, instead of writing a new program from scratch, is that the program will almost always need to reference data (usually string literals) from its prefix; the natural way to do this is to evaluate the prefix as a part of the program. For example, a prefix could begin with an expression like `(def prompt "You are a helpful assistant...")`. If the LLM wishes to pass the prompt to a subagent, it does so by reference. If the LLM wishes to discard any part of its prefix, removing it from context, it simply calls `!llm-self` with whatever context items it wishes to retain, omitting what is not needed.

In pseudocode, an iteration of the ReAct loop in Spell is as follows:

```
completion <- entire source code of this program
some-text <- some tool call
context-next-turn <- completion + some-text
result <- call LLM on context-next-turn
```

On the LLM's next turn, it sees the result of its tool call following what it just wrote; it continues its chain of thought uninterrupted.

Two issues immediately arise. First, how does the program reference its own source code? Such a program is a *quine*; Spell contains a special form, `quine`, which produces a self-referential expression. Second, on the second turn, what prevents the LLM call written on turn one from being re-evaluated? To address these problems, Spell programs have a common *wrapper* which prevents LLM calls, or more generally any code which interacts with global state, from being re-evaluated.

The following Spell program is equivalent to the pseudocode above:
```clojure
(quine completion 
  (eval 
    (do
      (quote
        (!call-now some-text some-tool-call)
))))
```
Several layers must be unpacked. First, the outer `quine` form binds the source code of the entire program to the symbol `completion`. Second, the sequence eval-do-quote sequence prevents double-evaluation in subsequent turns. A quoted expression is not evaluated immediately, unless it is passed as the argument to `eval`. A do block returns its last expression, which in this case is its only expression, the quote. Any quote which is not the last expression of a do block is inert data; when an expression is concatenated to the do block, it deactivates the quoted expression before it. Finally, the innermost expression does the following:
1. The tool is called and its value, a string literal, is passed to `!call-now`
2. `!call-now` produces the following tool call expression: `(def some-text "some string literal")`
3. `!call-now` takes the quine form, `completion`, strips trailing parentheses, and concatenates the tool call expression
4. `!call-now` invokes the LLM and passes it the concatenation. 


## Orchestration Patterns

A central claim of this paper is that Spell's primitives---recursive `llm` calls, concurrent agents, message passing, context manipulation, and shared state---are sufficient to express a wide range of orchestration patterns. 

## Empirical Evaluation



## Old text

For example, Cursor's AI-written browser required two failed attempts before its human orchestrators devised a sucessful approach (with planners, workers and judges). 
Orchestration and context management materially affect agent performance, and the strongest systems that exist emphasize autonomy. Yet, orchestration is mostly conducted by humans: by managing separate agents simultaneously; by specifying workflows (the approach taken by LangGraph and ...); by implementing patterns (like Kimi's swarms, or Claude Code's task lists) that can be invoked by agents semi-autonomously. Are human-designed orchestration patterns actually optimal for agentic systems?
Spell also expresses ordinary programmatic control flow: for example, an agent can make a tool call, dispatch a subagent depending on its result, concatenate the subagent's output with its current context window, then pass this context to a new instance of itself, continuing its chain of thought. 
We evaluate Spell on mathematical reasoning, long-context retrieval, and coding benchmarks. On mathematical reasoning, Spell agents consistently outperform Claude Code, a state-of-the-art coding agent, by 7--13 percentage points across AIME, HMMT, and Omni-MATH competition problems. On LongBench v2, Spell outperforms Claude Code by 27 percentage points. On coding tasks, Claude Code outperforms Spell. We find that the source of Spell's advantage on mathematical reasoning is primarily the seamless integration of deterministic computation with LLM reasoning---code execution eliminates arithmetic errors and enables exhaustive search---rather than the multi-agent orchestration features that motivate the language's design. We discuss the gap between Spell's theoretical expressiveness and the orchestration strategies that current LLMs actually deploy.

Spell is a Lisp dialect implemented in Clojure. It adds a function, `!llm-self`, which calls an LLM, evaluates the response as a Spell program, and returns the result. It also adds communication primitives for coordination between agents, or between agents and users, and it adds a quining primitive that allows programs to reference their own source code. Compared with Clojure, a major difference is that Spell programs are safe by default (having no interaction with with global state), and all unsafe effects (like tool calls and `!llm-self` calls) pass through a special evaluator function. Spell also modifies the scoping rules of Clojure.


The visibility of agentic orchestration spiked in early 2026. In January, Moonshot AI introduced Kimi 2.5 with "agent swarms", orchestrated by agents themselves, who were trained to self-orchestrate via reinforcement learning ("parallel-agent reinforcement learning", PARL). Also in January, Cursor deployed an ensemble of agents to write a partially functional web browser, using a human-designed orchestration structure with planners, workers, and judges. In February, Anthropic introduced a new "Teams" feature in Claude Code, featuring a central task list and bilateral communication channels, and demonstrated the autonomous production of a C compiler.

Another prominent theme in agentic systems has been context engineering, and in particular, the *progressive disclosure* pattern. With progressive disclosure, agents control what enters their context window via tool calls. Refinements of this strategy include programmatic tool calling, which allows agents to filter tool-call results using code, and delegation, where a subagent explores for relevant context and returns a digest. 

The common theme among these innovations is that agentic systems perform best when they exercize control over context management and multi-agent orchestration autonomously. Yet, the autonomy of existing systems is partial. Agents exercize fine-grained control over the ingress of context (via progressive disclosure), but not over egress, which typically occurs automatically via compaction. They spawn subagents and coordinate using task lists, but only as specified by human-designed templates.


I propose Spell (Self-Prompting Execution Language for LLMs), a domain-specific language for agentic self-orchestration and context management. Spell programs can make LLM calls; when an LLM writes code in Spell, it can call itself recursively. The LLM controls exactly what context it passes to itself, and this mechanism is used both to inject new context and to prune context which has gone stale. Spell programs can dispatch multiple LLMs asynchronously, exercizing the same control over subagent context, and agents running in parallel are able to communicate. Communication with users occurs via the same system as communication between agents. Spell programs can also perform ordinary computation, like arithmetic and control flow; they blend intelligent computation with deterministic computation seamlessly.
