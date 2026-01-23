# Agent Execution DSL: Language Specification

## 1. Overview

This document specifies a domain-specific language for LLM self-orchestration. In contrast to standard agent architectures where an external harness controls the execution loop, this language allows the LLM to write its own execution graph—including recursive calls, parallel branches, function definitions, and context management.

The key insight is moving recursion control *inside* the LLM's output. The LLM becomes a metaprogrammer of its own execution, rather than a passive function being called repeatedly by a harness.

---

## 2. Terminology

| Term | Definition |
|------|------------|
| **Completion** | The full text of a single LLM interaction: prefix + response |
| **Prefix** | Everything before the LLM's generation; the "input" |
| **Response** | What the LLM generates; the "output" |
| **Body** | The content between matching tags |
| **Expression (Expr)** | Syntax that can be evaluated |
| **Value** | A fully-evaluated expression; in this language, always a string |
| **Binding** | A named intermediate result: `<name> Expr </name>` |
| **Definition** | A function definition |
| **Return expression** | The final expression whose value is the output |
| **Environment** | The completion itself—all bindings are accessible via tag navigation |
| **Pattern** | A constraint on output shape; our analogue of types |

---

## 3. Document Structure

### 3.1 Completion

```
Completion ::= <completion> Prefix Response </completion>
```

A completion is the fundamental unit of execution. It contains everything: the input (prefix) and output (response).

### 3.2 Prefix

```
Prefix ::= <prefix> SystemPrompt InterpreterPrompt CallerPrompt </prefix>

SystemPrompt ::= <system-prompt> Text </system-prompt>
InterpreterPrompt ::= <interpreter-prompt> Text </interpreter-prompt>
CallerPrompt ::= <caller-prompt> Text </caller-prompt>
```

The prefix has three components:

- **System prompt**: Static instructions prepended to every prefix. Defines the language, available tools, base behavior.

- **Interpreter prompt**: Dynamic state injected by the harness. Examples: recursion depth, token budget remaining, execution status of parallel branches.

- **Caller prompt**: The first argument to the `llm()` call. Supplied by the user (for the root call) or by a parent agent (for recursive calls).

### 3.3 Response

```
Response ::= <response> (Definition | Binding)* ReturnExpr </response>

Definition ::= <fn name=id> <args> IdList? </args> Expr </fn>
IdList ::= id ( , id )*

Binding ::= < id > Expr </ id >

ReturnExpr ::= <return> Expr </return>
```

A response contains:
1. Zero or more **definitions** (functions)
2. Zero or more **bindings** (named intermediate computations)
3. Exactly one **return expression**

---

## 4. Expressions

### 4.1 Grammar

```
Expr ::=
    | Text                             -- bare text is a literal
    | $ TagRef                         -- evaluate reference
    | @ TagRef                         -- quote reference (literal text)
    | Expr Expr                        -- concatenation (adjacency)
    | Call                             -- function/tool invocation
    | Call : Pattern                   -- constrained invocation

TagRef ::= id ( . id | [ int ] )*

Call ::= id ( ArgList? )
ArgList ::= Expr ( , Expr )*
```

### 4.2 Reference Types

There are two ways to reference a binding:

**`$ref` — Evaluate**: Returns the *value* of the expression in the binding.

```
<greeting>hello</greeting>
<return>$greeting world</return>
```
Return value: `"hello world"`

**`@ref` — Quote**: Returns the *literal text* between the tags.

```
<plan>
  <step1>Do X</step1>
  <step2>Do Y</step2>
</plan>
<return>llm(Execute this plan: @plan)</return>
```
The child LLM receives the literal text `<step1>Do X</step1><step2>Do Y</step2>`.

### 4.3 Tag Navigation

Tags are navigated using dot notation and array indexing:

```
$prefix.caller-prompt         -- nested tag access
$thought[0]                   -- first element of array
$thought                      -- entire array (if multiple <thought> tags)
$prefix.caller-prompt.task    -- deeper nesting
```

When multiple tags share the same name at the same level, they form an array:

```
<thought>First</thought>
<thought>Second</thought>
```

- `$thought[0]` → `"First"`
- `$thought[1]` → `"Second"`
- `$thought` → the array `["First", "Second"]`

### 4.4 Concatenation

Adjacency is concatenation:

```
<return>Hello $name, welcome to $place</return>
```

If `$name` is `"Alice"` and `$place` is `"Wonderland"`:
Return value: `"Hello Alice, welcome to Wonderland"`

---

## 5. Calls

### 5.1 Unified Call Syntax

All callable things share the same syntax:

```
id ( arg1, arg2, ... )
id ( arg1, arg2, ... ) : Pattern
```

Three kinds of callables:

| Kind | Defined where | Examples |
|------|---------------|----------|
| Built-in | Interpreter | `llm` |
| Tool | Harness/external | `search`, `write_file`, `calculator` |
| User-defined | Response | `ralph`, `analyze`, `summarize` |

Lookup order: user-defined → tools → built-ins.

### 5.2 Constrained Calls

Any call can have an output constraint using `: Pattern`:

```
llm($prompt) : <return>%s</return>

search($query) : <results>(<result>%s</result>)+</results>
```

The pattern specifies the required shape of the output. If the output doesn't match, it's a runtime error (or retry, depending on harness policy).

### 5.3 The `llm` Built-in

The `llm` function spawns a child agent:

```
llm( caller-prompt )
llm( caller-prompt ) : Pattern
```

The child receives:
- **System prompt**: Inherited from parent
- **Interpreter prompt**: Constructed by harness (may include updated depth, budget, etc.)
- **Caller prompt**: The argument to `llm()`

---

## 6. Functions

### 6.1 Definition

```
<fn name=id> <args> param1, param2, ... </args>
  Expr
</fn>
```

Zero-argument functions are allowed:

```
<fn name=get-time> <args></args>
  current_time()
</fn>
```

### 6.2 Scope

A function is in scope for any expression that appears after its definition in the document. This includes:
- Later bindings
- The return expression
- Bodies of later-defined functions

### 6.3 Mutual Recursion

Functions may reference each other:

```
<fn name=is-even> <args> n </args>
  if($n == 0, true, is-odd(dec($n)))
</fn>

<fn name=is-odd> <args> n </args>
  if($n == 0, false, is-even(dec($n)))
</fn>
```

This is allowed because function bodies are evaluated at call time, not definition time.

### 6.4 Dependency Rule

If function `f` calls function `g`, and `f` is passed to a child via `llm()`, then `g` must also be included in the child's context. Failure to include dependencies is a runtime error.

---

## 7. Evaluation

### 7.1 Two Phases

**Phase 1 — Quotation**: Resolve all `@ref` expressions to literal text. This is pure string extraction from the generated document.

**Phase 2 — Evaluation**: Resolve all `$ref` expressions and execute calls, left-to-right, top-to-bottom.

### 7.2 Evaluation Order

Bindings are evaluated in document order (top-to-bottom). Within an expression, evaluation proceeds left-to-right.

```
<a>llm(First)</a>
<b>llm(Second)</b>
<return>llm($a $b Third)</return>
```

Evaluation order:
1. `llm(First)` → bind to `a`
2. `llm(Second)` → bind to `b`
3. Evaluate `$a` → value of `a`
4. Evaluate `$b` → value of `b`
5. `llm(...)` with concatenated arguments

### 7.3 Dependency Constraint

For `$ref`: A binding can only reference bindings that appear earlier in the document.

```
-- Valid
<a>hello</a>
<b>$a world</b>

-- Error: b is undefined when evaluating a
<a>$b world</a>
<b>hello</b>
```

For `@ref`: No constraints. Any tag can be quoted, including:
- Forward references
- Self-references
- Circular references

```
-- Valid: self-reference via quotation
<response>
  <thought>My reasoning</thought>
  <return>llm(Continue: @response)</return>
</response>
```

---

## 8. Patterns

Patterns constrain the shape of call outputs. They serve three purposes:
1. **Constraint**: Output must match
2. **Parsing**: Harness extracts values from wildcards
3. **Documentation**: Declares expected output shape

### 8.1 Pattern Grammar (Draft)

```
Pattern ::=
    | Literal                      -- exact text
    | % id : Wildcard              -- named wildcard
    | Wildcard                     -- unnamed wildcard
    | < id > Pattern </ id >       -- tag pattern
    | Pattern Pattern              -- concatenation
    | Pattern | Pattern            -- alternation
    | Pattern ?                    -- optional
    | Pattern *                    -- zero or more
    | Pattern +                    -- one or more
    | ( Pattern )                  -- grouping

Wildcard ::= s | d | f | expr
    -- s = string, d = integer, f = float, expr = expression to evaluate
```

### 8.2 Pattern Examples

**Simple string output:**
```
llm($prompt) : %answer:s
```

**Structured output:**
```
llm($prompt) : <answer>%s</answer>
```

**Branching (answer or recursive call):**
```
llm($prompt) : <return>%answer:s</return> | <return>ralph(%task:s, %ctx:s)</return>
```

**List extraction:**
```
search($query) : <results>(<result>%s</result>)+</results>
```

### 8.3 Expression Wildcards

The wildcard type `expr` indicates the captured text should be parsed and evaluated as an expression:

```
llm($prompt) : <return>%call:expr</return>
```

If the output is `<return>ralph(task, ctx)</return>`, then `%call:expr` captures `ralph(task, ctx)` and the harness evaluates it as a function call.

---

## 9. Errors

### 9.1 Parse-Time Errors

- Malformed XML structure
- Missing return expression
- Invalid tag reference syntax

### 9.2 Static Errors

- `$ref` references a binding that appears later in document
- Function body references undefined function (and it's not defined later)

### 9.3 Runtime Errors

- Undefined function or tool called
- Pattern match failure on constrained call
- Recursion depth exceeded
- Token budget exceeded
- Function passed to child without its dependencies

---

## 10. Examples

### 10.1 Basic Agent Loop

The standard ReAct-style loop, expressed in this language:

```
<response>
  <thought>I need to search for information about the query.</thought>
  <action>search($prefix.caller-prompt.query)</action>
  <return>llm($prefix.caller-prompt

I searched and found: $action

Please provide a final answer.)</return>
</response>
```

### 10.2 Parallel Research with Synthesis

Independent branches that don't pollute each other's context:

```
<response>
  <branch1>llm(Research topic A: $prefix.topicA)</branch1>
  <branch2>llm(Research topic B: $prefix.topicB)</branch2>
  <branch3>llm(Research topic C: $prefix.topicC)</branch3>
  <return>llm(Synthesize these findings:

Topic A: $branch1

Topic B: $branch2

Topic C: $branch3)</return>
</response>
```

### 10.3 Context Surgery (Pruning Failed Reasoning)

Discarding a failed attempt:

```
<response>
  <attempt1>llm($prefix Try approach A)</attempt1>
  <attempt2>llm($prefix Try approach B)</attempt2>
  <return>llm($prefix 

I tried approach B and got: $attempt2

Approach A didn't work. Continue from approach B.)</return>
</response>
```

Note: `$attempt1` is evaluated (the work is done) but its value is not passed to the child. The child doesn't know approach A was tried.

### 10.4 The Ralph Loop

Persistent task completion with external judgment:

```
<response>
  <fn name=ralph> <args> task, ctx </args>
    llm(You are a completion judge.

Task: $task

Work produced: llm($ctx Continue working on: $task)

If COMPLETE, return the final answer.
If INCOMPLETE, return ralph(task, updated_context).) : <return>%answer:s</return> | <return>ralph(%task:s, %ctx:s):expr</return>
  </fn>
  <return>ralph($prefix.task, $prefix)</return>
</response>
```

### 10.5 Self-Describing Response

Passing the response structure to a child:

```
<response>
  <plan>
    <step n=1>Gather requirements</step>
    <step n=2>Design solution</step>
    <step n=3>Implement</step>
    <step n=4>Test</step>
  </plan>
  <current-step>1</current-step>
  <return>llm(You are executing a plan.

Full plan structure: @plan

You are on step $current-step. Execute it and return the updated response structure.)</return>
</response>
```

The child sees the literal XML structure via `@plan` and can produce a similarly-structured response for the next step.

### 10.6 Debate Pattern

Two perspectives, then judgment:

```
<response>
  <position-a>llm($prefix Argue FOR this proposition.)</position-a>
  <position-b>llm($prefix Argue AGAINST this proposition.)</position-b>
  <return>llm(Two positions have been argued.

FOR: $position-a

AGAINST: $position-b

Which argument is stronger? Return the label.) : <verdict>A</verdict> | <verdict>B</verdict></return>
</response>
```

### 10.7 Recursive Summarization

Handling a document too large for one pass:

```
<response>
  <fn name=summarize> <args> doc </args>
    llm(If this document is short enough, summarize it directly.
If it's too long, split it and return recursive calls.

Document: $doc) : <summary>%s</summary> | <split><part>summarize(%s:expr)</part><part>summarize(%s:expr)</part></split>
  </fn>
  <return>summarize($prefix.document)</return>
</response>
```

### 10.8 Iterative Refinement

Self-critique loop:

```
<response>
  <fn name=refine> <args> draft, iterations </args>
    llm(Draft: $draft

Critique this draft. If it's good enough or iterations exhausted, return it.
Otherwise, improve it and call refine(improved, iterations-1).

Iterations remaining: $iterations) : <final>%s</final> | <again>refine(%s:expr, %d)</again>
  </fn>
  <draft>llm($prefix Write a first draft.)</draft>
  <return>refine($draft, 3)</return>
</response>
```

---

## 11. Open Questions

1. **Parallel execution**: Currently all evaluation is sequential. Should we add an explicit `parallel(e1, e2, ...)` construct for guaranteed concurrent evaluation?

2. **Error handling**: What happens on pattern match failure? Options: retry, default value, propagate error.

3. **Recursion limits**: Harness-enforced? Or expressible in the language via interpreter-prompt?

4. **Side effects**: Tools may have side effects. Should we mark pure vs impure calls? Affects optimization potential.

5. **Higher-order functions**: Can functions be passed as arguments? Not currently needed, but would add expressiveness.

---

## 12. Summary

This language gives LLMs control over their own execution topology. Key features:

- **Self-reference**: `@response` lets a parent describe its own structure to children
- **Context surgery**: Selective passing of intermediate results
- **Recursion**: Named functions with mutual recursion support
- **Structured I/O**: Patterns constrain and parse outputs
- **Uniform syntax**: Tools, built-ins, and user functions share call syntax

The harness role is reduced to:
- Providing the system prompt
- Injecting interpreter state
- Enforcing resource limits
- Executing tool calls
- Matching patterns and routing recursive calls

The LLM decides *what* computation to perform and *how* to structure it.
