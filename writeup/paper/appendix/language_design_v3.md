# Spell: language design and execution model

## Overview

Spell (Self-Programmed Execution Language for LMs) is a Lisp-like language embedded in Clojure for **self-programmed execution (SPE)**. In SPE, the LM does not merely propose the next tool call inside a fixed harness. Instead, it writes a program, and the harness evaluates that program. The program can call the LM again, construct the prefix for the next call, prune or compact prior context, spawn subagents, and compose all of these actions with ordinary control flow.

Spell is designed around four invariants:

1. **The model can treat its own completion as data.**
2. **Effects occur only at a controlled boundary: the trailing expression.**
3. **A self-call runs in a fresh local environment aligned with the visible completion.**
4. **Context management and orchestration are expressed as program transformations, not hand-written harness logic.**

Those invariants are what make Spell more than “ReAct in Lisp.” ReAct-style tool use is one pattern Spell can express, but Spell also supports direct context rewriting, worker-checker loops, self-compaction, asynchronous subagents, and message passing.

This appendix describes the language design at the level needed to understand the system's behavior. It begins with background on the intellectual traditions Spell draws from, then describes semantics, and only later addresses implementation details.

## Background

The intellectual roots of self-programmed execution lie in a tradition of self-referential constructions in logic and computation that stretches back nearly a century. Gödel's incompleteness proof (Gödel 1931) introduced the technique of encoding a formal object's description within the object itself — the diagonal lemma — to construct a sentence that asserts its own unprovability. Kleene's second recursion theorem (Kleene 1952) translated this into computation: for any computable transformation of programs, there exists a fixed-point program that is equivalent to its own transformation. This guarantees that self-reproducing programs exist in any sufficiently powerful computational system. Von Neumann, working independently on self-reproducing automata (von Neumann 1966), identified a duality that would prove central: a description must serve simultaneously as instructions to be executed and as data to be copied. This is precisely the dual role of the `quine` form in Spell, where the completion is both the running program and the data structure manipulated for the next LM call.

The programming language Lisp, introduced by McCarthy (McCarthy 1960), brought self-reference from theory into practice. In Lisp, programs and data share the same representation — S-expressions — a property known as *homoiconicity*. A Lisp program can straightforwardly construct, inspect, and transform other Lisp programs, including itself. This made Lisp the natural substrate for the metacircular evaluator (Abelson and Sussman 1985): a Lisp interpreter written in Lisp, in which the language's `eval` function is defined in terms of itself. The metacircular evaluator demonstrated that self-interpretation need not be a curiosity but a practical design pattern. Spell's evaluator stands in this tradition, extended with the `quine` special form and the completion wrapper to support LM-driven self-reference.

Lisp was also, for several decades, the dominant language of artificial intelligence research — from early work on symbolic reasoning and expert systems through much of the 1980s (Norvig 1992). This was largely for reasons unrelated to the specific properties Spell exploits: Lisp's flexibility, rapid prototyping capability, and support for symbolic manipulation made it well-suited to the AI problems of the era. Nevertheless, the coincidence is not entirely accidental. The properties that made Lisp useful for classical AI — treating code as data, dynamic evaluation, embedded domain-specific languages — turn out to be exactly the properties needed for an LM to program its own execution.

Hofstadter (Hofstadter 1979) coined the term *quine* for self-reproducing programs, after the logician W.V.O. Quine, and developed the concept of *strange loops*: hierarchical systems in which traversing levels eventually returns to the starting point. Self-programmed execution is a strange loop in Hofstadter's sense: the LM produces a program, the program invokes the LM, and the resulting completion is again a program. Smith (Smith 1984) took self-reference in a more explicitly architectural direction with 3-Lisp, which introduced *procedural reflection*. In 3-Lisp, a program is interpreted by a metacircular interpreter which is itself interpreted by another, forming an infinite *reflective tower*. A program can shift up one level to inspect and modify its own interpreter's state. Spell's `quine` form, which gives a program access to its own source as data, and the `prune`/`reopen`/`!extend` operators, which transform the program that will be evaluated on the next turn, constitute a form of reflective control in this tradition. The key difference is that in Spell, reflective capability is exercised by a language model rather than by handwritten code; the model decides what to preserve, what to prune, and how to restructure its own context.

Schmidhuber (Schmidhuber 2007) proposed the Gödel machine, a theoretical framework for a fully self-referential self-improving system that can rewrite any part of its own code once it proves the rewrite is beneficial. Spell shares the Gödel machine's premise that self-modification is a desirable property of an intelligent system, but differs in mechanism: the Gödel machine relies on proof-guided meta-optimization between executions, while Spell agents modify their own context at runtime, within a single execution, driven by the LM's judgment rather than formal proof. Recent LLM-era systems such as Self-Taught Optimizer (Zelikman et al. 2024) and Gödel Agent (Yin et al. 2024) have revisited self-referential self-improvement for language models, though again at the meta-optimization level rather than at runtime.

## Minimal Lisp background

Spell uses ordinary Lisp ideas: expressions are S-expressions, quoted expressions evaluate to data, and `eval` evaluates that data as code.

```clojure
(def x (+ 1 2))        ; x is bound to 3
(def thunk '(+ 41 1))  ; thunk is bound to the list
(eval thunk)           ; => 42
```

This matters because in Spell, LM completions are code. Natural-language reasoning can therefore be represented as string literals or structured forms inside the program and manipulated like any other data.

## Core execution model

### Completions and `!llm-self`

A **completion** is the prefix passed to the LM together with the suffix produced by the LM. In Spell, completions are executable programs.

The central primitive is `!llm-self`. At a high level, it behaves as follows:

```clojure
(defn !llm-self [prefix]
  ;; Pseudocode only
  (let [response   (call-llm prefix)
        completion (str prefix response)
        result     (spell-eval completion {})]
    (:ok result)))
```

The actual implementation includes parsing, balancing, error recovery, and handle management, but this pseudocode captures the key idea: Spell passes the LM an **open program prefix**, receives a completion, evaluates it, and returns its value.

Because `!llm-self` is an ordinary callable form, it can appear inside conditionals, loops, recursive functions, map-style dispatch, or user-defined orchestration helpers.

### Quines and explicit self-reference

To extend or rewrite its own context, the program must be able to refer to its current completion as structured data. Spell provides the special form `quine` for this purpose:

```clojure
(quine self (pr-str self))
;; => "(quine self (pr-str self))"
```

`(quine name body)` binds `name` to the entire quine form as data and then evaluates `body`. This allows the body to inspect or transform the very program that contains it. For example:

```clojure
(quine completion (!llm-self completion))
```

is valid because `completion` is already bound before the body is evaluated.

If a quine is given multiple body expressions, only the final expression is live; earlier expressions remain as inert context. Spell uses that property during recovery and extension: new forms can be appended to an existing quine without re-running the earlier body.

### The completion wrapper

Every normal Spell self-call is evaluated inside a standard **completion wrapper**:

```clojure
(quine completion
  (eval
    (do
      ...
      (quote (expression-with-effects))
    )))
```

This wrapper does two jobs:

1. It binds `completion` to the current program as data.
2. It ensures that only the **trailing expression** can have externally visible effects.

The outer `eval` performs a second evaluation on the value returned by the `do` block. Since `do` returns its last expression, only the trailing expression is evaluated twice. Earlier expressions are evaluated once as ordinary local computation; they do not get the second evaluation needed to access effectful builtins.

This is the mechanism behind Spell's effect discipline.

### Trailing expressions and inert extensions

The trailing expression is usually quoted:

```clojure
'(!llm-self (reopen completion))
```

Because it is quoted, it is inert as ordinary data unless it occupies the trailing position and is passed to the outer `eval`. If the completion is later extended by appending another form, that earlier quoted expression is no longer trailing and therefore becomes harmless context rather than an active LM call.

This property is essential. It lets the LM append to its own already-generated completion without accidentally re-executing old tool calls, old LM calls, or old writes to global state.

Spell therefore relies on a simple but powerful invariant:

> A completion may contain many historical effectful expressions, but at evaluation time only the current trailing expression can fire.

### `reopen` and open prefixes

A child self-call needs an **open prefix**, not a closed program string. Spell provides `reopen` for this purpose. Given the quine data bound in `completion`, `reopen` reconstructs the same wrapper, preserves the live body forms, and returns an open prefix that the child LM can continue extending.

Conceptually:

```clojure
(reopen completion)
;; => (quine completion (eval (do ...    ; no trailing close-parens yet
```

The child LM receives that open prefix as its prompt and completes the rest of the program.

### Extensions and `!call-now`

A common pattern is to call a tool, serialize the result into the completion, and then continue reasoning with the result in context. Spell packages this pattern into `!call-now`:

```clojure
'(!call-now result-name (tool-call))
```

On the next turn, the LM sees the original request together with the materialized binding:

```clojure
'(!call-now result-name (tool-call))
(def result-name "literal result of tool-call")
```

`!call-now` supports several arities:

- `(!call-now name expr)` for a single binding;
- `(!call-now name expr limit)` with an inline byte limit;
- `(!call-now name1 expr1 name2 expr2 ...)` for multiple bindings in one extension.

Large values may be stored out of band and represented in the continuation as `(stored "id")` placeholders rather than enormous inline literals.

The related primitive `!extend` simply reopens the current completion after applying source-to-source cleanup operations such as pruning and persistence.

## Local environments and effect gating

### Why ordinary lexical scope is a poor fit

In a standard language runtime, program behavior may depend on globals or lexical closures that are not visible in source. In an SPE setting, that is undesirable: the model can only reason over the program text in its context window. Hidden captured state would create behavior that the model cannot inspect or intentionally preserve.

Spell addresses this by evaluating completions in explicit environments. A self-call starts from a fresh local environment whose contents are determined by the visible completion. The model therefore has direct access to the state that matters for its future behavior.

### Environment-based functions

Spell functions are environment-based rather than closure-based. Operationally, this means there are no opaque lexical closures carried across LM boundaries. Functions are portable because what matters is their source and the current environment, both of which the model can in principle inspect or reconstruct.

This choice is deliberate. It makes local state legible to the model at the cost of departing from ordinary Clojure semantics.

### Gated effects

Spell divides builtins into three tiers.

**Core namespaces** are always available inside `spell-eval` and include pure utilities such as `strings`, `math`, and `seqs`.

**Effect namespaces** include capabilities such as `io`, `agents`, `globals`, and `patterns`. These are available only through the outer `eval`, i.e. only from the trailing expression.

**Future-only namespaces** such as `blocking/` are available only inside `(future ...)` blocks.

This design ensures that a historical expression preserved in context cannot accidentally perform I/O, spawn agents, or write global state just because the completion is extended.

## Context management

As a completion grows through repeated extensions, it accumulates stale context. Spell includes first-class operators for removing that stale context while retaining the information the model still needs.

### `prune`

`prune` is the lowest-level source-pruning primitive:

```clojure
'(!call-now dir-contents (io/sh "ls -l big-dir"))
(def dir-contents "[1000 files]")
(prune 2)
'(!call-now my-file (io/read-lines "big-dir/my-file.txt"))
```

On the next extension, the two expressions immediately preceding `(prune 2)` are removed from the source passed forward. The `prune` marker itself also disappears.

At runtime, `prune` is inert: it behaves like an empty `do`. Its meaning appears only when the source is transformed during reopening.

### `think` and `rethink`

`think` marks an explicit reasoning step:

```clojure
(think "checking edge cases"
  (def edge-result (test-edge-cases)))
```

At runtime, `think` evaluates its body and returns `nil`. Its purpose is structural: the reasoning annotation remains visible in the completion as something the LM can inspect or later compress.

`rethink` combines a reasoning annotation with pruning. A common pattern is:

```clojure
'(!call-now dir-contents (io/sh "ls -l big-dir"))
(def dir-contents "[1000 files]")
(rethink "big-dir contains many files; the one I need is my-file.txt.")
'(!call-now my-file (io/read-lines "big-dir/my-file.txt"))
```

On the next turn, the large serialized result can be removed while the shorter reasoning summary remains.

### `persist`

`persist` lets the LM keep a derived value even after pruning away the larger object from which that value was computed.

```clojure
'(!call-now big-file (io/read-lines "big-file.txt"))
(def big-file [...]) ; 1000 lines
(persist lines (subvec big-file 32 42))
'(!extend)
```

At runtime, `persist` behaves like `def`: it evaluates the expression and binds it to a name. During reopening, however, the source transformer replaces the body of a literal `persist` form with the concrete value currently bound to that name. As a result, the child LM can still read `lines` even if `big-file` has been pruned away.

Spell also supports a one-argument sugar:

```clojure
(persist x)
```

which is equivalent to `(persist x x)`.

### `!peek-now` and `!print`

`!peek-now` is an ephemeral version of `!call-now`. It evaluates expressions and materializes bindings for the next turn, but also inserts a prune marker so those bindings are automatically removed on the following extension.

```clojure
'(!peek-now contents (io/read-lines "big-file.txt"))
```

This is useful when the model needs one-turn access to a value without letting it linger in context indefinitely.

`!print` injects serialized values directly into the continuation without creating named bindings:

```clojure
'(!print (+ 1 2) (str "hello " "world"))
;; next turn sees: 3 "hello world"
```

### `!compact`

For long-running agents, even repeated pruning may not keep the completion small enough. `!compact` provides self-referential compaction: the agent is asked to rewrite its own context into a shorter, self-contained program.

Compaction works as a two-hop self-call. The first hop cleans the source and appends instructions telling the model to rewrite the current context as a sequence of quoted forms, typically using a helper such as `wrap-cat`. The second hop evaluates that compacted sequence in a fresh environment and continues execution from the shorter prefix.

Because the same agent performs the compaction, it can make informed choices about what to keep:

- key definitions,
- essential intermediate conclusions,
- small supporting values,
- abbreviated reasoning summaries.

The compacted program must be self-contained. It cannot rely on bindings that existed only before compaction.

### Line-number-preserving values

When Spell reads a file via `io/read-lines`, the resulting vector may carry metadata recording the starting line number. When serialized into a continuation, the value can be rendered as:

```clojure
(line-offset 42 [
  "code at line 42" ; 42
  "code at line 43" ; 43
])
```

This preserves positional information across extensions so the model can continue to reason about file structure without re-reading the file.

## Error recovery

LM-written programs will sometimes be wrong. Spell therefore includes both deterministic and LM-driven recovery.

### Result-map errors

`spell-eval` returns result maps rather than throwing host-language exceptions directly:

```text
Success: {:ok value :env env'}
Error:   {:err message :env env :expr failing-expression :trace [...]}
```

The `:trace` field records the Spell-level call path through which the error propagated. For host-function errors, Spell rewrites the message so it is expressed in terms of Spell-facing names rather than internal Clojure machinery.

### Deterministic namespace fixup

A frequent LM mistake is to use a symbol without the required namespace qualification—for example, `trim` instead of `strings/trim`. Spell first attempts a deterministic repair by searching the available namespaces. If there is exactly one matching resolution, the system substitutes it and re-evaluates.

### LM-driven evaluation recovery

If deterministic repair fails, Spell falls back to an LM-mediated recovery. Because the current program is already a quine, Spell can append a recovery prompt and an error binding to the existing completion without losing the failed program as context.

Conceptually, the recovery quine looks like:

```clojure
(quine completion
  ;; original failed body, now inert
  (eval (do ... <failed body> ...))
  ;; recovery additions
  (rethink "Error recovery: see _error for details.")
  (eval (do
          (def _recovery_prompt "The previous Spell program threw an error. ...")
          (def _error {:error "Handle not registered: :target"
                       :in '(agents/!ask :target)
                       :trace [my-helper]})
          '(!llm-self (reopen completion)))))
```

On the recovery turn, the model sees the failed program, the error object, and a recovery instruction. Because the new recovery block is appended as the live body, the failed program becomes inert context rather than re-running.

Spell uses a small fixed retry budget shared across reader and evaluator recovery.

### Reader recovery

If the completion cannot be parsed at all—for example because of unbalanced parentheses—Spell cannot embed it as normal code. In that case, the raw text is wrapped into a fresh recovery quine as a string:

```clojure
(quine completion
  (eval (do
          (def _recovery_prompt "The previous program had a parse error. ...")
          (def _previous_program "<raw unparseable text>")
          (def _error {:error "<parse error message>" :raw true})
          '(!llm-self (reopen completion)))))
```

The LM then gets another chance to produce a valid continuation.

## Concurrent agents and inter-agent communication

Spell distinguishes between two kinds of LM invocation.

- `!llm-self` means the **same handle** takes another turn.
- `spawn` creates a **different handle** that may run asynchronously.

This distinction gives Spell both serial self-delegation and concurrent subagent execution.

### Handles and serial call trees

Each agent handle has its own call tree. Self-calls inherit the parent's handle, so they are serial with respect to that handle. Different spawned handles may run concurrently. This prevents concurrent mutation of the same inbox state while still allowing genuine multi-agent execution.

### Inbox-based messaging

Each handle has an inbox that stores queued message macros. When a sleeping or newly awakened agent resumes, the queued inbox macro is composed with the stored completion before evaluation.

Conceptually:

```clojure
(composed-inbox-macro
  (quine completion
    (eval (do ...))))
```

A delivered message typically appends:

1. a `think` annotation recording the wake-up,
2. a binding containing the message,
3. an extension so the recipient gets a fresh turn and can act on the message immediately.

For example:

```clojure
(quine completion
  (eval (do
          ...
          (think "[awakened by message]")
          (def msg {:from :worker-1 :body "done"})
          '(!extend))))
```

If several messages arrive before evaluation, the corresponding inbox macros are composed in order.

### Sleeping instead of blocking

A normal agent turn must remain preemptable. If a handle were to block its execution thread directly while waiting for another handle, deadlock would become possible.

Spell therefore distinguishes between **blocking inside a future** and **sleeping as an agent**. The primitive `!ask-await` is used from ordinary agent turns. It installs a background waiter, returns immediately, and lets the current handle sleep in a state that can be awakened by any incoming message.

A sleeping handle stores:

- a completion that has not yet been re-evaluated,
- a queue of inbox macros,
- a signal promise used for wake-up.

Incoming messages update the queue and deliver the wake-up signal atomically.

### Why this avoids deadlock

Spell's communication design aims to make deadlock impossible for ordinary agent turns. Whenever handle `A` asks handle `B` for work, `B` is awakened if necessary. If `A` and `B` ask each other simultaneously, both are awakened; neither remains permanently blocked waiting for the other's execution thread.

Potentially unsafe primitives such as `blocking/await` still exist, but only inside `future` blocks, outside the main execution thread of an agent turn.

### Communication API

At the Spell level, the main communication forms are:

- `(agents/spawn prompt)` — spawn a new agent asynchronously and return its handle;
- `(agents/spawn agent prompt :handle-name)` — spawn with an explicit compiled agent and/or handle name;
- `(agents/send target value)` — fire-and-forget message delivery;
- `(agents/!ask target msg)` — send a message and sleep until a reply arrives;
- `(agents/!ask target)` — wake the target and sleep;
- `(agents/!ask [a b c])` — wake several targets and sleep until all return;
- `(agents/!spawn-ask prompt)` — spawn a child and sleep until it completes;
- `(agents/!spawn-ask [prompt-a prompt-b ...])` — spawn several children and sleep until all complete;
- `(!ask-await fut)` — wait safely on a future result from a normal agent turn.

When a non-root handle finishes, its completion is preserved in a sleeping state so that another agent can later wake it again.

## Additional language features

Spell also includes many conventional language features familiar from Clojure-like systems.

### Macros

Spell supports built-in macros such as `when`, `cond`, `defn`, `->`, and `->>`, as well as user-defined macros via `defmacro`:

```clojure
(defmacro unless [test & body]
  (list 'if test nil (cons 'do body)))
```

Macros are especially useful when the LM wants to transform source before passing it to a child LM. For example, a macro can extract a large literal from the current completion, normalize a context fragment, or synthesize a compact child prefix from a more verbose parent program.

### Error handling

Spell provides structured exception-style control flow:

```clojure
(try
  (/ 1 0)
  (catch e "division failed"))

(try
  (throw {:code 404})
  (catch e (:code e)))
```

Bindings created before the error remain available in the catch handler.

### Destructuring and iteration

`let`, `fn`, `loop`, and `for` support standard vector and map destructuring. `loop`/`recur` provide tail-recursive iteration, and `for` provides list-comprehension-style iteration with `:when` and `:let`.

```clojure
(loop [n 5 acc 1]
  (if (= n 0)
    acc
    (recur (- n 1) (* acc n))))

(for [x [1 2 3 4] :when (> x 1) :let [sq (* x x)]]
  sq)
```

### Futures

`(future expr)` evaluates `expr` in a new thread while capturing the current environment. Futures are isolated from the parent's later environment updates. Blocking operations on futures live in the `blocking/` namespace and are intended for deterministic computation, not for direct LM orchestration.

### Utility patterns

A few higher-level utilities are worth noting:

- `io/watch-send` lets an agent subscribe to external file events without blocking its main execution thread; the watch runs in the background and delivers events by message.
- `patterns/clean-prompt` uses a plain-text LM to clean up a noisy user prompt and then hands the cleaned directive to `!llm-self`.

These are patterns built on top of Spell's core execution model rather than separate architectural mechanisms.

## Runtime sketch

This section briefly relates the language-level semantics above to the implementation.

### `spell-eval`

`spell-eval` is the core evaluator. It takes an expression and an environment and returns a result map:

```text
{:ok value :env env'}
{:err msg :env env :expr expr :trace [...]}
```

`spell-eval` itself is common across agents.

### `eval`

Each agent gets its own `eval` builtin. This builtin provides the outer, effectful evaluation step used by the completion wrapper. It merges pure builtins with the effectful namespace set that the agent is allowed to access. In this way, all agents share the same core evaluator but differ in the effect surface exposed at the trailing-expression boundary.

### `box`

`box` is the execution primitive that processes completions. It resolves a raw completion source (for example a promise or future), balances parentheses, and then applies an inside function that handles inbox draining, evaluation, sleeping, or root-lifecycle behavior.

Different inside functions correspond to different phases:

- awake evaluation,
- asleep waiting,
- root cleanup and orphan handling.

### `call-llm`

`call-llm` makes the actual LM API call. It delivers the response into the box/execution pipeline rather than directly returning a string to the caller. This lets the same infrastructure handle normal completions, asynchronous spawning, and recovery turns.

### End-to-end flow

A typical root execution looks like this:

1. Build an LM caller and per-agent `eval`.
2. Wrap the user prompt in the completion wrapper.
3. Register a root handle and its inbox machinery.
4. Call the LM on the open prefix.
5. Receive the completion, evaluate it, and follow any trailing expression.
6. Repeat for every self-call or spawn.

The important point is that the harness is doing only generic work: calling the LM, evaluating code, routing messages, and maintaining sleeping handles. It is not imposing a task-specific agent loop.

## KV cache and limitations

SPE exposes a real systems trade-off. A conventional append-only agent loop is highly compatible with KV caching because most of the prefix remains unchanged from turn to turn. Spell, by contrast, sometimes rewrites earlier context in order to prune or compact it. That can create cache misses.

At the same time, carrying a long unedited context forward also has a cost: each newly generated token must still attend to a growing prefix. Spell therefore trades some cache locality for the ability to control context growth explicitly.

One possible mitigation is to preserve raw text when reopening a completion so that unchanged prefix segments remain token-identical. Spell already includes infrastructure for this via a `*raw-text*` binding, although current prefix serialization still reconstructs the prefix from the quine's AST rather than reusing the raw text directly.

More broadly, Spell prioritizes **transparency and programmability** over pure append-only efficiency. Whether that trade-off is worthwhile is ultimately an empirical question.

## References

- (Abelson and Sussman 1985) Harold Abelson and Gerald Jay Sussman. *Structure and Interpretation of Computer Programs*. MIT Press. https://mitpress.mit.edu/9780262510875/structure-and-interpretation-of-computer-programs/
- (Gödel 1931) Kurt Gödel. Über formal unentscheidbare Sätze der Principia Mathematica und verwandter Systeme I. *Monatshefte für Mathematik und Physik* 38, 173–198. https://doi.org/10.1007/BF01700692
- (Hofstadter 1979) Douglas Hofstadter. *Gödel, Escher, Bach: An Eternal Golden Braid*. Basic Books. https://en.wikipedia.org/wiki/G%C3%B6del,_Escher,_Bach
- (Kleene 1952) Stephen Cole Kleene. *Introduction to Metamathematics*. North-Holland. https://en.wikipedia.org/wiki/Introduction_to_Metamathematics
- (McCarthy 1960) John McCarthy. Recursive Functions of Symbolic Expressions and Their Computation by Machine, Part I. *Communications of the ACM* 3(4), 184–195. https://doi.org/10.1145/367177.367199
- (Norvig 1992) Peter Norvig. *Paradigms of Artificial Intelligence Programming: Case Studies in Common Lisp*. Morgan Kaufmann. https://github.com/norvig/paip-lisp
- (Schmidhuber 2007) Jürgen Schmidhuber. Gödel Machines: Fully Self-Referential Optimal Universal Self-Improvers. In *Artificial General Intelligence*, Springer, 199–226. https://arxiv.org/abs/cs/0309048
- (Smith 1984) Brian Cantwell Smith. Reflection and Semantics in Lisp. In *Proceedings of POPL*, 23–35. https://doi.org/10.1145/800017.800513
- (von Neumann 1966) John von Neumann. *Theory of Self-Reproducing Automata*. Edited by Arthur W. Burks, University of Illinois Press. https://en.wikipedia.org/wiki/Von_Neumann_universal_constructor
- (Yin et al. 2024) Xunjian Yin, Xinyi Wang, Liangming Pan, Xiaojun Wan, and William Yang Wang. Gödel Agent: A Self-Referential Agent Framework for Recursive Self-Improvement. https://arxiv.org/abs/2410.04444
- (Zelikman et al. 2024) Eric Zelikman, Eliana Lorch, et al. Self-Taught Optimizer (STOP): Recursively Self-Improving Code Generation. *COLM 2024*. https://arxiv.org/abs/2310.02304
