# Self programmed execution language for large language models

## Abstract

We propose a self-programmed agentic system in which a language model (LM) emits a program, and the agentic harness does nothing except execute it. The program can construct a prompt or prefix and feed it back to the model, allowing it to edit or extend its own context window. It can spawn and feed subagents the same way, and it can compose model calls with tool calls and ordinary control flow. We call this approach *self-programmed execution*. We implement a language, Spell (self-programmed execution language for LMs), a dialect of Lisp with distinctive features that support self-referencing and self-editing programs.

## Introduction

Agentic systems combine traditional, deterministic computation with generative, intelligent computation interactively. The deterministic component is called the *harness*, the intelligent component is typically a language model (LM), and the interaction between them is two-way: the harness invokes the LM and provides it with context; the LM instructs the harness to take certain actions. The prominence of agentic systems is recent, and the space of possible architectures is not fully explored. What is clear is the importance of agentic architecture for performance, especially their approach to multi-agent orchestration and context engineering.

A recent trend is to grant models greater autonomy within a system to manage their own context and orchestrate their own subagents. A typical approach is to provide custom tools which, instead of interacting with an external environment, modify the internal runtime environment of the harness. In particular, such a tool might manipulate the string of context which is fed back to the LM on its subsequent turn. This *internal autonomy* is especially attractive for general-purpose agents, as a particular context engineering heuristic may fail when faced with arbitrary tasks, and for long-running agents, as the right approach to a long-horizon task may not be apparent at the outset. This manuscript addresses the question: *what agentic architecture maximizes internal autonomy?* 

In the system which is proposed, the LM produces a program, and the harness evaluates it. This program is solely responsible for invoking the LM recursively, and for computing what context to provide. We refer to this approach as *self-programmed execution* because from the perspective of the LM, the system is entirely self-programmed, and the harness is reduced to an evaluator. 
Concretely, self-programmed execution refers to the following "algorithm":
```
completion <- lm(prefix)
eval(completion)
```
where `lm` calls an LM to complete the prefix to a program, and `eval` executes it. The language of this program should allow the LM to reproduce on its own whatever logic would ordinarily be coded in the harness. 

Self-programmed execution contrasts with the ReAct loop underlying most agentic systems. In ReAct: (1) the harness produces a prefix and passes it to the model; (2) the model generates a response, possibly encoding a tool call; (3) the harness executes the tool call and recurs to step (1). The generative step (2) controls the execution step (3), but not the orchestration step (1). More generally, existing agentic systems alternate between a step controlled by the LM and a step controlled by the harness, even when those steps differ in their specifics from those of the ReAct loop (Figure 1a). What distinguishes self-programmed executon from all existing approaches is that the LM-controlled step subsumes the harness-controlled step entirely (Figure 1b). 

For example, a LM could reproduce an iteration of the ReAct loop using the following program:
```
do:
  completion <- source code of this program
  prompt <- "..."
  result <- tool call
  context <- concatenate completion and result
  call-self(context)
```
where `call-self` invokes a new LM call and evaluates the resulting program. This LM receives as context both the original program and the result of the tool call.

Implementing self-programmed execution requires making just one high-level decision, which is the language in which the program is written. This paper proposes such a language, *Spell* (Self-Programmed Execution Language for LLMs). Spell is a dialect of Lisp embedded in Clojure. In Lisp, unlike most languages, it is idiomatic for programs to manipulate code as data (*homoiconicity*), and in particular, a program can manipulate its own source code (such programs are *quines*). Quining is a central mechanicism in Spell because LMs must constantly manipulate their own context window programmatically, and their context window is the source code of the program itself.

Section 1 introduces Spell and its core semantics. Section 2 measures the effect of using Spell on performance in practice, with current LLMs; it tests Spell on a range of benchmarks and an analysis of what features of Spell are actually utilized. Section 3 formalizes SPE and states a completeness theorem.

## Related work

Several existing methods support agentic autonomy by providing it with added capabilites from within a traditional harness. Figure 1b provides a mental model for how these methods relate: they specify different action spaces for the step controlled by the LM, and different logic for the step controlled by the harness, while retaining the same alternating topology. 

One category of capability augmentation supports context window self-management. This category includes FoldGRPO, in which the agent folds or summarizes a suffix of its previous context history; this approach has the advantage that the remaining prefix hits the KV cache. Another such method is AgentFold, in which the agent additionally may fold or summarize older context items anywhere in its context window; compared with ordinary compaction, AgentFold adds the ability to summarize or fold certain context items and not others. A third method in this category is SCULPTOR, in which the agent may fragment its context before folding or summarizing specific fragments; Spell solves the same problem by giving bindings to string literals so that they can be referenced using variables. In each case, although the model controls its own context, the harness retains control over the agent loop itself.

A second category includes methods which allow an agent to spawn and orchestrate one or more others. Claude Code implements three such architectures: a subagent architecture, a "tasks" architecture, and a "teams" architecture which additionally enables messaging between subagents. Kimi K2.5 utilizes "swarms" of subagents for tasks which are parallelizable. In the academic literature, THREAD is one example of a subagent architecture; this approach analogizes subagents with computational threads and supports orchestration primitives which are familiar from asynchronous programming, like `join`.

An especially notable subagent architecture is recursive language models (RLMs), which combine the ability to spawn subagents with two additional features. First, RLMs dispatch subagents programmatically, via a special function (akin to `llm`) which is composable with other functions (e.g., `map`). Second, RLMs store context in a subagent-specific runtime environment which is controlled programmatically. However, RLMs do not actually allow agents to manage their own context window; like in ReAct, once tokens enter their context window (as opposed to their runtime environment), they cannot be evicted.

A separate category includes systems like DSPy and the Huxley-Godel Machine which support a self-improvement or meta-optimization layer. Such systems share with Spell the motivation that human-designed harnesses are likely suboptimal, and that a better harness or scaffold could be written by LLMs themselves. The conceptual difference is that these systems separate self-improvement, which involves search and an objective function, from runtime. At runtime, the output of the self-improvement system is still an ordinary agentic harness. Spell agents, in contrast, self-orchestrate at runtime. The advantages of each approach are orthogonal: Spell permits the agent to adapt to its task as it discovers its structure in real time; self-improving systems permit the system itself to learn from prior experience.


## Self-Programmed Execution Language for LLMs

Implementing SPE requires specifying a language for execution. Although it may in principle be possible to use any interpreted language, most languages would be ill-suited for SPE in practice. 
In SPE, the program which is executed doubles as the context window of the LM. Persisting context across turns requires the program to reference and manipulate its own source code. In an imperative language like Python, this kind of metaprogramming is technically possible but at best unidiomatic; a more natural choice is to use Lisp. In Lisp, it is idiomatic to manipulate source code as data (this property is *homoiconicity*), and it is also idiomatic to create embedded dialects in support of special use cases, which is the approach taken here. Specifically, Spell is a List dialect embedded within Clojure. It adds features to Clojure which support the creation and manipulation of self-referencing programs. It also makes important modifications to rules related to variable scope and global side effects.

A *completion* refers to the concatenation of a prefix, which is passed as the input to an LLM, and a response, which is the output. In Spell, the special function `!llm-self` accepts a prefix as its argument, passes it to the LLM, obtains a completion, and evaluates the completion as code. The reason that the prefix is included in the program, and the program is not written from scratch, is that the program will almost always need to reference data (usually string literals) from the prefix. The natural way to do this is to evaluate the prefix as a part of the program, with bindings for context items (like prompts) such that they can be referenced programmatically.

In pseudocode, an iteration of the ReAct loop in Spell is as follows:

```
completion <- entire source code of this program
some-text <- some tool call
context-next-turn <- completion + some-text
result <- call LLM on context-next-turn
```

On the LLM's next turn, it sees the result of its tool call following what it just wrote; it continues its chain of thought uninterrupted.

Two issues arise. First, how does the program reference its own source code? Such a program is a *quine*; Spell contains a special form, `quine`, which produces a self-referential expression. Second, on the second turn, what prevents the LLM call written on turn one from being re-evaluated? To address these problems, Spell programs have a common *wrapper* which prevents LLM calls, or more generally any code which interacts with global state, from being re-evaluated.

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

Of course, the point is not to reproduce a ReAct loop but to generalize it. One direction in which to generalize is context window manipulation. The `!llm-self` function takes as input an arbitrary quoted expression, which will be the prefix of the subsequent turn. For example, it could perform a number of tool calls to gather information, then define a plan, and then perform a self-call with its plan together with other relavent data while excluding most prior chain of thought and tool call results. Spell also has a macro, `prune-substitute`, which pairs with the special functions `prune` and `persist` to make context management ergonomic. At runtime, `prune` is inert, and `persist` is equivalent to `def`. However, they act as signals to the `prune-substitute` macro, which walks the input expression and deletes any subexpression which preceeds the expression `(prune)`. When it encounters the expression `(persist name expr)`, it replaces `(expr)` with whatever value is currently bound to `name` in the program namespace. These combine as follows:

```clojure
;; inside the do block of (quine completion (eval (do ...)))
'(!call-now readme (io/read-file "README.md"))       ;; prior turn
(def readme "# My Project\n...\n[200 lines]")
(prune 2)
(persist title (first (strings/split-lines readme)))
'(!extend)
```

On a prior turn, the agent read a file via `!call-now`, which inlined the 200-line result. The agent now marks the large binding for removal with `(prune 2)`, extracts the title with `persist`, and calls `!extend`. `!extend` applies `prune-substitute` to the completion. On the subsequent turn, the context window contains `(persist title "# My Project")` in place of the original 200 lines.

A second direction in which to generalize is composition of the `!llm-self` function with arbitrary control flow. An agent could create a task list and self-dispatch using `map`; it could set up a worker-checker loop, from which the checker signals when to break; it could write a reusable orchestration function. The following program implements a worker-checker like pattern in which the "worker" is asked to guess a number:

```clojure
(quine completion (eval (do
  (def secret 42)
  (defn play [guess history]
    (if (= guess secret)
      (str "Correct!")
      (let [hint (if (< guess secret) "higher" "lower")
            history (conj history (str guess ": " hint))]
        (play (!llm-self (str "Guess a number 1-100. " history))
              history))))
  '(play (!llm-self "Guess a number between 1 and 100") [])
)))
```

The `play` function checks each guess against the secret, and if incorrect, appends a hint to the history and invokes a new LLM call via `!llm-self`, passing the updated history as the prompt. The subagent sees only the history and returns a number; the parent evaluates it and recurses. In this example, the subagent runs synchronously; Spell additionally supports agent parallelism and inter-agent communication (Appendix xy).

Third, Spell can be used to perform computations or to compose tool calls, similar to programmatic tool calling. This functionality does exist by default in any agent with access to bash or Python. Spell augments ordinary programmatic tool calling by persisting programmatic bindings across turns: for example, it can perform a websearch to obtain a list of URLs, and on the following turn fetch one of those URLs by reference instead of regurgitating the (possibly lengthy) URL with output tokens. 

The entrypoint to create a Spell agent involves running an *initial program*. This program usually contains one `!llm-self` call or similar, thereby producing the agent's first turn, and it contains any initial context the agent should have (in particular, a prompt). However, this program may execute arbitrary orchestration logic; Spell is compatible with custom, human-specified orchestration.

## Empirical Evaluation

## Universality of SPE

In Appendix A we prove a universality theorem for SPE. We model an agentic machine as X=(S,p,h), where the prompt function p maps states to prompts, and the harness function h maps a state/completion pair to the next state, halt, or divergence. We say that a machine (S,p,h) *embeds* (S',p',h') via the embedding e:S'->S if e is an injection which preserves the prompt function (i.e., pe=p') and commutes with the harness function (i.e., h(c,.)e=eh'(c,.)). We define the SPE machine as a particular agentic machine which wraps around a well-studied evaluator (the CEK machine []). Its states are the subset of evaluator states in which the next step of computation is to make an LM call. For some other machine, we say that it is *realizable* if its prompt and harness functions can be implemented by the underlying evaluator. For a state x of an agentic machine X, let S_x be the subset of S which is reachable in one step from x: S_x={h(c,x) for completions c}. We say that (X,x) *completion-generates* a machine X' if some X embeds X' via an embedding e such that Im(e)\subset S_x. We construct a state of the SPE machine, called an *SPE state*, which completion-generates any realizable machine. 

Our formalism adopts the "perspective" of the LM itself. States of the agentic machine correspond one-to-one with model invocations. At each state, the prompt function determines what the LM observes. When one machine is embedded in another, the embedding preserves the prompt which is observed by the model, such that the LM cannot distinguish the embedded system from the original. Likewise, the term "self-programmed" takes on a formal meaning from the perspective of the LM: the successor state of an SPE state is fully determined (up to an embedding) by its own program.

An SPE machine necessarily contains non-SPE states. Indeed, if (X,x) completion-generates a machine Y, then so does any embedding of x; an SPE machine which embeds a less expressive machine must contain similarly less expressive states. The distinction between SPE and non-SPE states is a possible definition for the distinction between "agents" and "subagents", or more precisely, "agent turns" and "subagent turns"; an agent turn corresponds to an SPE state.

Spell makes it idiomatic for the system to remain in SPE states continuously. Specifically, this  motivates the trailing expression pattern and the gating of turn-producing expressions. Suppose that the prefix passed to an LM already contained a turn-producing expression, and there was no mechanism to "cancel" this expression by appending additional expressions; then this would a non-SPE state, for example because the LM cannot cause the next state to be the halting state. With the trailing expression pattern, turn-producing expressions only fire when last and are cancelled by appending any expression at all.

## Discussion
