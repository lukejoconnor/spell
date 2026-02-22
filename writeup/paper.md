# Self-prompting execution language for large language models

The performance of agentic systems on complicated tasks depends sensitively on the agentic harness, particularly its approach to orchestration and context management. An ongoing trend is to grant agents greater control over their own context and their own orchestration, within the bounds of human-designed templates. However, autonomy remains limited: for example, agents control the ingress of data into their context window via tool calls, but they lack control over egress. I propose a system in which LLMs write self-prompting programs, expressing self-orchestration and context window manipulation in code. The language of this system is Spell (self-prompting execution language for LLMs), a Lisp dialect resembling Clojure. ...



## Introduction

Very recently, *orchestration* has emerged as a key differentiator among agentic systems. The term usually implies parallelization: an ensemble of AI agents work on a task in concert, reducing latency. Orchestration is one strategy for *context engineering*; it allows tokens to be divided across multiple context windows. 

The visibility of agentic orchestration spiked in early 2026. In January, Moonshot AI introduced Kimi 2.5 with "agent swarms", orchestrated by agents themselves, who were trained to self-orchestrate via reinforcement learning ("parallel-agent reinforcement learning", PARL). Also in January, Cursor deployed an ensemble of agents to write a partially functional web browser, using a human-designed orchestration structure with planners, workers, and judges. In February, Anthropic introduced a new "Teams" feature in Claude Code, featuring a central task list and bilateral communication channels, and demonstrated the autonomous production of a C compiler.

Another prominent theme in agentic systems has been context engineering, and in particular, the *progressive disclosure* pattern. With progressive disclosure, agents control what enters their context window via tool calls. Refinements of this strategy include programmatic tool calling, which allows agents to filter tool-call results using code, and delegation, where a subagent explores for relevant context and returns a digest. 

The common theme among these innovations is that agentic systems perform best when they exercize control over context management and multi-agent orchestration autonomously. Yet, the autonomy of existing systems is partial. Agents exercize fine-grained control over the ingress of context (via progressive disclosure), but not over egress, which typically occurs automatically via compaction. They spawn subagents and coordinate using task lists, but only as specified by human-designed templates. 

I propose Spell (Self-Prompting Execution Language for LLMs), a domain-specific language for agentic self-orchestration and context management. Spell programs can make LLM calls; when an LLM writes code in Spell, it can call itself recursively. The LLM controls exactly what context it passes to itself, and this mechanism is used both to inject new context and to prune context which has gone stale. Spell programs can dispatch multiple LLMs asynchronously, exercizing the same control over subagent context, and agents running in parallel are able to communicate. Communication with users occurs via the same system as communication between agents. Spell programs can also perform ordinary computation, like arithmetic and control flow; they blend intelligent computation with deterministic computation seamlessly.

This paper is organized as follows. Section 1 introduces Spell and its core semantics. Section 2 explores the theoretical capabilities of an agentic system using Spell through a series of examples. Section 3 measures the effect of using Spell on performance in practice, with current LLMs; it tests Spell on a range of benchmarks and an analysis of what features of Spell are actually utilized. Section 4 explores the gap between theory and practice and identifies possible bottlenecks.

## Self-Prompting Execution Language for LLMs

Outline:
- react loop as recursion
- current pseudocode pulling recursion into agent completion
- naive translation into pseudo-spell
- full program
- communications


Spell is embedded within Clojure, a modern dialect of Lisp, and it resembles Clojure closely. From Lisp it inherits homoiconicity, the idea that source code can be manipulated as data. In Spell, an LLM's completion is code, this code can manipulate itself as data, and in particular, it can replicate itself within the context window of a subsequent LLM call. In pseudocode, a tool call in Spell is as follows:

```
completion <- entire source code of this program
some-text <- some tool call
context-next-turn <- completion + some-text
result <- call LLM on context-next-turn
```

On the LLM's next turn, it sees the result of its tool call following what it just wrote; it continues its chain of thought uninterrupted.

Two issues immediately arise. First, how does the program reference its own source code? Such a program is a *quine*; Spell contains a special form, `quine`, which produces a self-referential expression. Second, on the second turn, what prevents the LLM call written on turn one from being re-evaluated? Spell programs usually follow a particular structure which prevents LLM calls, or more generally any code which interacts with global state, from being re-evaluated.

The following Spell program is equivalent to the pseudocode above:
```clojure
(quine completion 
  (eval 
    (do
      (quote
        (call-now some-text some-tool-call)
))))
```
Several layers must be unpacked. First, the outer `quine` form binds the source code of the entire program to the symbol `completion`. Second, the sequence eval-do-quote sequence prevents double-evaluation in subsequent turns. A quoted expression is not evaluated immediately, unless it is passed as the argument to `eval`. A do block returns its last expression, which in this case is its only expression, the quote. Any quote which is not the last expression of a do block is inert data; when an expression is concatenated to the do block, it deactivates the quoted expression before it. Finally, the innermost expression does the following:
1. The tool is called and its value, a string literal, is passed to `call-now`
2. `call-now` produces the following expression: `(def some-text "some string literal")`
3. `call-now` takes the quine form, `completion`, strips trailing parentheses, and concatenates the tool call expression
4. `call-now` invokes the LLM and passes it the concatenation. 





Suppose we call `llm` with the following prompt:

```clojure
(llm "Greet the world using a child LLM call")
```

The `llm` function wraps this prompt into an incomplete Spell program and sends it to the LLM as both the user message and the assistant prefix. The LLM's response continues this program. For example, the LLM might produce:

```clojure
(do (def prompt "Greet the world using a child LLM call")
    (llm "Respond with 'Hello, world!'"))
```

This completion is evaluated. The inner `llm` call sends its prompt to a child LLM, which might produce:

```clojure
(do (def prompt "Respond with 'Hello, world!'")
    "Hello, world!")
```

The string `"Hello, world!"` is the return value---the value of the last expression. It propagates back through the parent, which returns it to the user. The Spell code used to produce it is hidden.


### The Spell wrapper

Most Spell programs have a certain wrapper, which supports self-replicating behavior and demonstrates Spell's distinctive features.

are generated by passing a *prefix*---an incomplete program---to the `llm` function. `llm` sends the prefix to an LLM API, concatenates the response to form a *completion*, evaluates the completion, and returns the result.

```clojure
(defn llm [prefix]
  (let [response (call-LLM prefix)
        completion (str prefix response)]
    (spell-eval completion {})))
```

The completion is evaluated by `spell-eval`, which differs from Clojure's `eval` in three ways. First, it is sandboxed: only a subset of Clojure built-ins are available, and functions with side effects (I/O, recursive LLM calls) are gated behind an effect guard that restricts when they can be called. Second, it supports special forms for self-reference (`quine`) and iteration (`loop`/`recur`, `for`). Third, `spell-eval` takes an environment as an explicit input parameter, and this environment is empty when called by `llm`. Programs are therefore self-contained---the LLM, which sees only the prefix it is completing, can predict exactly how its code will behave.

Every `llm` call is associated with a *handle*---a keyword like `:agent-42` that serves as an address for inter-agent communication. The built-in `llm-self` calls `llm` recursively; the child inherits the parent's handle, so the entire `llm-self` call tree is one logical agent with one address. To create independent agents, `agents/spawn` registers a new handle and starts the LLM function in a background thread.

### Environments and dynamic scoping

In most languages, functions have lexical scope: the environment at call time is the one from when the function was defined. In Spell, functions are dynamically scoped: the environment at call time is the caller's environment merged with parameter bindings.

This choice is motivated by a constraint specific to the agentic setting. Only source code is passed between LLMs, not environment bindings. There is no way to serialize a closure across an LLM boundary while preserving alignment between what the LLM sees in its context window and what the evaluator sees in the environment. With dynamic scoping, functions are portable data---they serialize naturally as source forms---and `expand` (described below) provides a mechanism to capture free variables when needed.

Because `spell-eval` takes an environment as input and returns an environment as output, the LLM has perfect knowledge of the evaluation environment. This reflects an important design principle: maximizing alignment between the model's context (what it can reason about), the program's environment (what determines behavior), and the program's source (what the model can manipulate).

### Self-reference and completions

Often, the LLM will want to pass its accumulated chain of thought---including tool results, intermediate computations, and reasoning---to a child LLM. This requires the program to reproduce parts of its own source code, which is a quine.

Spell defines a special form, `quine`, which makes this straightforward:

```clojure
(quine self (pr-str self))  ; => "(quine self (pr-str self))"
```

`(quine name body)` binds `name` to the entire `(quine name body)` expression as data, then evaluates `body`. The binding contains the complete source form, so self-reference is achieved without circularity.

Completions generated by `llm` always use the *completion wrapper* as their prefix:

```clojure
(quine completion (eval (do
  ...
  ; trailing expression
)))
```

The `quine` form binds `completion` to the program's own source as structured data (a list). The `do` block evaluates all expressions and returns the last. The outer `eval` evaluates this return value a second time. This double evaluation is the key mechanism: the *trailing expression*---the last expression in the `do` block---is the only expression whose return value is evaluated twice.

The LLM uses this by quoting the trailing expression:

```clojure
'(llm-self (reopen (pr-str completion)))
```

Because it is quoted, this expression returns a list from `do`. The outer `eval` evaluates the list, actually calling `llm-self`. The child LLM receives the parent's entire completion as its prefix and can continue it, producing an *extension*. If a new expression is later appended, the previously-quoted expression is no longer trailing---the quote makes it inert (its value is discarded as an intermediate result). This ensures that `llm-self` calls are not re-evaluated when the completion grows.

### Expanding quoted expressions

When passing a quoted expression to a child LLM, a problem arises if the expression contains free variables defined in the parent's scope:

```clojure
(def answer 42)
(def task '(str "The answer is " answer))
(llm task)
```

The child LLM receives `task` as its prefix, but the binding for `answer` is not present in the child's environment. Spell defines `expand`, which substitutes free variables with their values from the current environment, producing a closed expression:

```clojure
(expand '(str "The answer is " answer))
; => (str "The answer is " 42)
```

The `llm` function calls `expand` automatically on its prompt, so the parent need only reason about its own scope. Any expression that evaluates correctly in the parent will evaluate correctly in the child after expansion.

Expansion interacts with dynamic scoping: `expand` reconstructs function values as their source forms (e.g., `(fn [x] (* x x))`), so functions remain portable data across LLM boundaries.

### Tool use via extensions

The `call-now` macro packages the common pattern of calling a tool, binding its result, and extending the completion:

```clojure
'(call-now result (io/bash "ls -la"))
```

This is a quoted trailing expression. When evaluated by the outer `eval`, it executes `io/bash`, binds the result to `result` as a new `def` form appended to the completion, and calls `llm-self` with the extended prefix. The LLM sees the tool result in its context and can continue reasoning.

Functions are organized into namespaces accessed via qualified symbols (`io/bash`, `strings/trim`, `math/sqrt`). The `io` namespace provides file and process operations; `agents/` provides communication primitives; `globals/` provides shared mutable state. Side-effectful functions are only available in the trailing expression via the effect guard, ensuring that intermediate expressions in a completion are pure.

### Context management

Spell provides macros for managing chains of thought within a single completion.

`(think "label" body...)` marks a reasoning step. It evaluates `body` for its side effects (typically defining intermediate variables) and returns nil. Think blocks remain visible in the source code---the LLM can read them in subsequent turns---but their return value does not propagate.

`(rethink "label" body...)` replaces the previous sibling expression at the source level. When the completion is next extended, pruned expressions are removed from the prefix. This is context surgery: the LLM can discard unproductive reasoning to reclaim context window space.

`(extend completion)` prunes all rethought expressions from the completion's AST and calls `llm-self` to continue with clean context. Together, `think`, `rethink`, and `extend` let the LLM manage its own context window programmatically.

### Concurrent agents and communication

`(agents/spawn llm-self prompt)` starts an LLM in a background thread with its own handle and returns the handle. The spawned agent is independent and communicates via message passing.

`(agents/ask target msg)` sends a message to `target` and blocks until the target replies. Every form of `ask` wakes the target, preventing deadlocks: if agent A asks B while B asks A, both sends cross and both agents unblock. Multi-target ask `(agents/ask [a b c])` pokes all targets and blocks until all have replied, collecting responses in a single turn.

`globals/` provides shared mutable state visible to all agents. `(globals/set :key value)` writes; `(globals/get :key)` reads. `(globals/wait-until pred)` blocks until a predicate on the global state becomes true, enabling event-driven coordination without polling.

Deadlock freedom follows from two invariants. First, `llm-self` calls inherit the parent's handle, so same-handle call trees are serial---they cannot deadlock with themselves. Second, cross-handle dependencies use `agents/ask`, which always wakes the target. The concurrency model enforces this: `llm-self` for serial recursion (shared handle), `agents/spawn` for parallel work (new handle).

### Implementation

Spell is implemented in Clojure in approximately 5,000 lines. The implementation consists of four components:

1. `spell-eval`---a pure evaluator supporting 13 special forms, 26 macros, and approximately 180 built-in functions. It takes an environment as input and returns a result map containing the value and updated environment.

2. `eval`---a per-agent effectful evaluator produced by a factory function. It merges side-effectful namespaces (`agents/`, `io/`, `globals/`) with pure built-ins, making dangerous operations available only through double evaluation in the trailing expression.

3. `box`---the single point of interaction between local and global state. It awaits the LLM completion, drains the inbox (atomically), and applies the inbox function to the raw completion string. Root boxes perform lifecycle cleanup; orphan boxes keep handles responsive to messages after an agent returns.

4. `call-llm`---the API call layer, supporting four providers (Anthropic, OpenAI, Ollama, Kimi/Moonshot) with prompt caching, retry logic, and token/cost tracking.

The system passes 429 tests with 1,649 assertions.

[TODO: figure showing the cascade from prompt to evaluation to recursive call]


## Orchestration Patterns

A central claim of this paper is that Spell's primitives---recursive `llm` calls, concurrent agents, message passing, context manipulation, and shared state---are sufficient to express a wide range of orchestration patterns. In this section, we enumerate several such patterns, show that they are expressible in Spell, and present preliminary evidence that current LLMs can implement them when prompted.

### Patterns expressible in Spell

**Delegation and synthesis.** The parent LLM decomposes a task into subtasks, delegates each to a child `llm` call, and combines the results. This is the most basic orchestration pattern and is directly expressible:

```clojure
(def analysis-a (llm "Analyze topic X from perspective A"))
(def analysis-b (llm "Analyze topic X from perspective B"))
(def synthesis (llm (cat "Synthesize: " analysis-a " and " analysis-b)))
```

**Fan-out / fan-in with concurrent agents.** Multiple agents work in parallel on independent subtasks, and the coordinator collects their results:

```clojure
(def a (agents/spawn llm-self "Summarize document A"))
(def b (agents/spawn llm-self "Summarize document B"))
'(agents/ask [a b])  ; blocks until both reply
```

**Tool-augmented reasoning (ReAct).** The LLM interleaves reasoning with tool calls, each extending the completion:

```clojure
(think "I need to check the test file first")
'(call-now tests (io/read-file "test_foo.py"))
; ... LLM sees test contents, continues ...
'(call-now result (io/bash "python -m pytest"))
```

**Context pruning.** The LLM manages its own context window, discarding unproductive reasoning:

```clojure
(think "approach-1" (def x (complex-calculation)))
(rethink "approach-2" (def x (better-calculation)))  ; replaces approach-1
'(extend completion)  ; prunes rethought expressions, re-prompts
```

**Blind evaluation (information asymmetry).** A parent LLM delegates evaluation to a child who cannot see the parent's reasoning, ensuring independent judgment:

```clojure
(def essay (write-essay topic))
(def critique (llm (cat "Evaluate this essay: " essay)))
; The child sees only the essay, not the parent's reasoning process
```

**Stigmergic coordination.** Agents coordinate indirectly through shared state, without direct messaging:

```clojure
; Workers post results to globals; coordinator waits
(globals/set :results [])
(agents/spawn llm-self "Process batch A, post result to globals/:results")
(agents/spawn llm-self "Process batch B, post result to globals/:results")
'(globals/wait-until (fn [g] (= 2 (count (:results g)))))
```

**Specialist agents.** Different subtasks are handled by agents with different tool access and system prompts, configured via `make-llm`:

```clojure
(def researcher (agents/spawn research-llm "Find relevant papers"))
(def coder (agents/spawn coding-llm "Implement the algorithm"))
'(agents/ask [researcher coder])
```

**Recursive decomposition.** An agent applies the same strategy recursively to subproblems:

```clojure
(defn solve [problem]
  (if (simple? problem)
    (direct-answer problem)
    (let [parts (decompose problem)]
      (map (fn [p] (llm (cat "Solve: " p))) parts))))
```

### Preliminary evaluation: do LLMs use these patterns?

We conducted a pilot evaluation to test whether current LLMs (Claude Opus 4.5 and Sonnet 4.5) would use Spell's orchestration primitives when given appropriate tasks. The evaluation had two rounds.

**Round 1: Open-ended prompts.** We presented five tasks where orchestration *could* help but was not strictly required (e.g., "analyze this topic from multiple perspectives," "iteratively refine an essay"). Across 30 runs (5 prompts $\times$ 2 models $\times$ 3 replicates), models overwhelmingly preferred single-generation inline solutions. Only one prompt (multi-source synthesis, which structurally required separate perspectives) consistently elicited delegation, with Opus using 3--5 child `llm` calls in all replicates. Quality was high for inline solutions (mean 4.3/5) and highest for the orchestrated ones (4.7/5).

**Round 2: Orchestration-forcing prompts.** We designed four tasks that structurally required orchestration features (e.g., blind evaluation requiring information asymmetry, computation requiring tool use, independent analysts requiring separate LLM calls). All 24 runs used multiple API calls. Both models correctly implemented the intended patterns:

- *Blind evaluation*: 6/6 runs correctly delegated critique to a child LLM, achieving information asymmetry (mean quality 4.0/5).
- *Tool computation*: 6/6 runs used `call-now` for bash/Python computation.
- *Independent analysts*: 6/6 runs used exactly 3 analyst calls + 1 synthesis call.
- *Number guessing*: High variance; models attempted sophisticated recursive architectures but frequently encountered execution errors (mean quality 2.0/5).

Two findings stand out. First, models default to inline solutions when orchestration is optional, even when orchestration might help. Second, when the task structure demands it, models can and do implement the correct orchestration patterns in Spell---but execution quality degrades as patterns grow more complex. The blind evaluation pattern was executed cleanly; the interactive number-guessing game, which required state isolation and recursive loops, was fragile.

[TODO: expand this section with more systematic evaluation---more patterns, larger sample sizes, pattern-correct vs. task-correct scoring]


## Empirical Evaluation

We evaluate Spell on benchmarks spanning mathematical reasoning, long-context retrieval, and coding tasks. Our primary comparison is Claude Code, Anthropic's state-of-the-art coding agent, which uses a conventional tool-use loop where the LLM generates one response at a time and the harness manages execution. Unless otherwise noted, Spell uses Opus 4.5 with prefill (the prompt serves as both user message and assistant prefix) and Claude Code uses Opus 4.5 or 4.6.

### Mathematical reasoning

We evaluate on four mathematical reasoning benchmarks of increasing difficulty: GSM8K (grade school), AIME 2025 (high school competition), HMMT February 2025 (collegiate competition), and Omni-MATH (olympiad, difficulty-filtered).

| Benchmark | Spell | Claude Code | $\Delta$ |
|-----------|-------|-------------|----------|
| GSM8K (50 items) | 98% | 74% (baseline) | +24 |
| AIME 2025 (30) | 73.3% | 66.7% | +6.6 |
| HMMT Feb 2025 (30) | 76.7% | 66.7% | +10.0 |
| Omni-MATH hard (54) | 51.9% | 38.6% | +13.3 |

Spell outperforms Claude Code on all four benchmarks, with the advantage growing with problem difficulty: +24 points on grade school math, +6.6 on AIME, +10.0 on HMMT, and +13.3 on Omni-MATH.

The GSM8K comparison uses Sonnet 4.5 for both conditions, with the baseline being a standard chain-of-thought prompt without code execution. The other comparisons use Opus-class models for both Spell and Claude Code. HMMT and Omni-MATH comparisons are less clean: Spell uses Opus 4.5 (with prefill), while the Claude Code numbers use Opus 4.5 (HMMT) or Opus 4.5 (Omni-MATH). [TODO: rerun with matched models]

The results are not uniformly positive. On AIME 2026 (30 items), Claude Code with Opus 4.6 achieves 100% (30/30) while Spell with Opus 4.5 achieves 80% (24/30). This likely reflects the model upgrade (Opus 4.6 is stronger than 4.5 on math reasoning) rather than a framework effect, but it is worth noting.

#### Why does Spell help on math?

The advantage comes primarily from *code execution as a reasoning medium*. When a Spell agent encounters a math problem, it writes a program that computes the answer deterministically. Arithmetic, combinatorial enumeration, and numerical verification are handled by exact computation rather than "mental math."

The GSM8K results make this mechanism clear. All 13 of the baseline's failures were arithmetic errors---the LLM's "mental math" produced incorrect intermediate calculations (e.g., $12 \times 7 = 83$). Spell's 3 failures were all semantic interpretation errors (misreading the problem statement). Code execution eliminates the arithmetic error category entirely: when the model generates `(* 12 7)`, it gets exactly 84.

On harder benchmarks, the same principle applies at a higher level. AIME and HMMT problems often require exhaustive search over small solution spaces, modular arithmetic, or multi-step numerical computation. A Spell agent can write a loop that checks all candidates, whereas a prose-based agent must reason through each case mentally. On Omni-MATH, the `think`/`rethink` mechanism for context pruning contributes an additional ~12 percentage points by allowing the agent to discard unproductive reasoning chains and retry with clean context.

This is an important finding, but it is also a somewhat disappointing one from the perspective of self-orchestration. The Spell agents that solve math problems are not using multi-agent patterns, delegation, or concurrent execution. They are using Spell as a computational notebook---writing code that computes answers. The advantage is real and significant, but it is the advantage of *code execution*, not of *self-orchestration*.

### Long-context retrieval

We evaluate on LongBench v2, a benchmark requiring retrieval and reasoning over long documents (8k--128k tokens). We use a sampled subset of 48 items across 6 task types.

| Condition | Accuracy | Cost |
|-----------|----------|------|
| Spell (Opus 4.5) | 66.7% (32/48) | $48.19 |
| Claude Code (Opus 4.6, no CLAUDE.md) | 39.6% (19/48) | $12.14 |
| Claude Code (Opus 4.5, no CLAUDE.md) | 35.4% (17/48) | $16.28 |

Spell outperforms Claude Code by 27--31 percentage points. This is the largest gap in either direction across all our benchmarks.

#### Why does Spell help on long-context retrieval?

Analysis of Spell's execution traces reveals a simple strategy: the agent immediately greps for keywords from the question, narrows with focused follow-up greps, and returns a terse answer as the trailing expression. The median number of LLM calls per item is 3. No traces use orchestration features (no `agents/spawn`, no delegation, no parallel search).

Spell's advantage comes from two structural properties of code-as-output.

First, code forces the agent to express search operations as function calls rather than prose narration. A grep returns a list of matches---structured data that can be inspected programmatically. In contrast, Claude Code's natural-language responses tend to narrate the search process in prose, making the actual answer harder to extract.

Second, the trailing expression pattern ensures clean return values. A Spell program's answer is the value of its last expression---typically a bare string like `"A"` or `"C"`. Claude Code returns verbose natural-language explanations (400+ words is typical), and the benchmark's answer extractor frequently fails to find the answer buried in prose. Of Claude Code's errors, roughly 12--13 of 48 resulted in null extracted answers---the LLM answered correctly in prose but the extraction failed.

This is partly a benchmark artifact (a better extractor would close some of the gap) and partly a genuine structural advantage of code-as-output (structured data is inherently easier to extract than prose). Either way, it is not an orchestration advantage. The LLM uses Spell as a search tool, not as an orchestration framework.

### Coding and editing tasks

We evaluate on Exercism Python (programming exercises, difficulty 4--9), FeatureBench (feature implementation in real repositories), and SWE-bench Verified (bug fixing in real repositories).

| Benchmark | Spell | Claude Code |
|-----------|-------|-------------|
| Exercism Python (d4--d5, 30 items) | 100% | 100% |
| Exercism Python (d5--d9, 16 items) | 93.8% | 100% |
| FeatureBench (4 tasks, Opus) | 50% (2/4) | 100% (4/4) |
| SWE-bench Verified (5 tasks) | 60% (3/5) | 100% (5/5) |

Claude Code outperforms Spell on coding tasks, particularly on multi-file feature implementation and bug fixing.

#### Why does Spell struggle on coding?

Trace analysis identifies three systematic issues.

*Quadratic input cost.* Spell's prefix-as-prompt semantics send the entire accumulated program as both user message and assistant prefix on every turn. With $n$ turns, input tokens scale as $O(n^2)$. On FeatureBench, Spell cost 5x more than Claude Code for the same tasks despite producing worse results.

*No test-driven iteration.* Claude Code runs tests after each edit and fixes failures in subsequent turns. Spell writes forward until it exhausts its turn budget. This is the largest quality gap driver on coding tasks: Claude Code iterates toward correctness, while Spell hopes to get it right in fewer attempts.

*Depth limits.* On FeatureBench and SWE-bench, Spell's failures were predominantly caused by hitting the turn limit before completing multi-step editing tasks. Raising or removing the limit risks infinite loops; the right solution is likely orchestration patterns that compress multi-step editing into fewer turns, but current LLMs do not spontaneously adopt such patterns.

On Exercism (d4--d5), after fixing a harness bug that had caused all Spell failures (missing auxiliary test files), both Spell and Claude Code achieved 100%. At easier difficulty levels, the single-pass code generation approach is sufficient; the gap appears on harder, multi-step tasks.

### Summary: where does the advantage come from?

Spell outperforms Claude Code on mathematical reasoning (+7 to +24 points) and long-context retrieval (+27 points). Claude Code outperforms Spell on coding and editing tasks.

The source of Spell's advantages is *code as a reasoning medium*: deterministic computation eliminates arithmetic errors on math problems, and structured output ensures clean answer extraction on retrieval tasks. Both advantages derive from the basic property that the LLM's output is executable code rather than prose.

Notably, none of Spell's orchestration features---multi-agent coordination, recursive delegation, context pruning via `rethink`/`extend`, concurrent execution---appear in any benchmark traces. Across hundreds of benchmark runs, the LLM uses Spell as a computational notebook with tool access, not as a self-orchestration framework. The features that make Spell theoretically interesting (Section 3) are not what make it empirically effective (this section).

This gap has two possible explanations, which are not mutually exclusive.

First, the benchmarks may not require orchestration. AIME, HMMT, and Exercism are single-agent tasks where one capable model can produce the answer. Multi-agent patterns add overhead but not capability for these tasks. The benchmarks where orchestration would help most---long-running tasks with context exhaustion, large search spaces requiring backtracking, or problems requiring specialist expertise---are underrepresented in standard evaluation suites.

Second, current LLMs may not yet have the strategic judgment to self-orchestrate effectively. The pilot evaluation in Section 3 showed that models default to inline solutions even when orchestration is available, and execution quality degrades for complex patterns. This may improve with scale, training, or reinforcement learning over orchestration strategies.

[TODO: Section 4 --- gap analysis, deferred]


## Old text

For example, Cursor's AI-written browser required two failed attempts before its human orchestrators devised a sucessful approach (with planners, workers and judges). 
Orchestration and context management materially affect agent performance, and the strongest systems that exist emphasize autonomy. Yet, orchestration is mostly conducted by humans: by managing separate agents simultaneously; by specifying workflows (the approach taken by LangGraph and ...); by implementing patterns (like Kimi's swarms, or Claude Code's task lists) that can be invoked by agents semi-autonomously. Are human-designed orchestration patterns actually optimal for agentic systems?
Spell also expresses ordinary programmatic control flow: for example, an agent can make a tool call, dispatch a subagent depending on its result, concatenate the subagent's output with its current context window, then pass this context to a new instance of itself, continuing its chain of thought. 
We evaluate Spell on mathematical reasoning, long-context retrieval, and coding benchmarks. On mathematical reasoning, Spell agents consistently outperform Claude Code, a state-of-the-art coding agent, by 7--13 percentage points across AIME, HMMT, and Omni-MATH competition problems. On LongBench v2, Spell outperforms Claude Code by 27 percentage points. On coding tasks, Claude Code outperforms Spell. We find that the source of Spell's advantage on mathematical reasoning is primarily the seamless integration of deterministic computation with LLM reasoning---code execution eliminates arithmetic errors and enables exhaustive search---rather than the multi-agent orchestration features that motivate the language's design. We discuss the gap between Spell's theoretical expressiveness and the orchestration strategies that current LLMs actually deploy.

Spell is a Lisp dialect implemented in Clojure. It adds a function, `llm-self`, which calls an LLM, evaluates the response as a Spell program, and returns the result. It also adds communication primitives for coordination between agents, or between agents and users, and it adds a quining primitive that allows programs to reference their own source code. Compared with Clojure, a major difference is that Spell programs are safe by default (having no interaction with with global state), and all unsafe effects (like tool calls and `llm-self` calls) pass through a special evaluator function. Spell also modifies the scoping rules of Clojure.
