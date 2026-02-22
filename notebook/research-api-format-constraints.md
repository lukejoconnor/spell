# API Format/Grammar Constraints: Provider Comparison

Research date: 2026-02-22

## Summary Table

| Feature | OpenAI Chat Completions | OpenAI Responses | Anthropic | Google Gemini |
|---------|------------------------|-----------------|-----------|---------------|
| JSON mode | `response_format: {type: "json_object"}` | `text.format: {type: "json_object"}` | No simple JSON mode | `responseMimeType: "application/json"` |
| JSON Schema | `response_format: {type: "json_schema", json_schema: {schema: ...}}` | `text.format: {type: "json_schema", schema: ...}` | `output_config: {format: {type: "json_schema", schema: ...}}` | `responseSchema` (OpenAPI 3.0) or `responseJsonSchema` (JSON Schema) |
| Strict mode | `strict: true` in json_schema | `strict: true` | Strict by default (constrained decoding) | Always strict when schema provided |
| Enum constraint | Via JSON schema `enum` | Via JSON schema `enum` | Via JSON schema `enum` | `responseMimeType: "text/x.enum"` + schema |
| Regex/grammar | No | **Custom tools: Lark CFG or regex** (GPT-5+ only) | `pattern` on string fields only (limited regex) | No |
| Arbitrary grammar (CFG/BNF) | No | **Yes, via custom tools** (GPT-5+ only) | No | No |
| Streaming | Yes | Yes | Yes | Yes |
| Prefill compatible | N/A (no prefill support) | N/A (no prefill) | **No** (mutually exclusive; prefill removed on 4.6 models) | N/A (no prefill concept) |
| Function calling structured output | `strict: true` on function params | `strict: true` on function params | `strict: true` on tool `input_schema` | Via `function_declarations` with schema |

## OpenAI

### Chat Completions API

Parameter: **`response_format`** (top-level request field)

Three modes:
```json
// 1. Plain text (default)
{"type": "text"}

// 2. JSON mode — guarantees valid JSON, no schema enforcement
{"type": "json_object"}

// 3. Structured outputs — guarantees JSON conforming to schema
{"type": "json_schema", "json_schema": {
  "name": "my_schema",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {"answer": {"type": "string"}},
    "required": ["answer"],
    "additionalProperties": false
  }
}}
```

### Responses API

Parameter: **`text.format`** (nested under `text` in request body)

```json
{
  "model": "gpt-4o",
  "input": "...",
  "text": {
    "format": {
      "type": "json_schema",
      "name": "my_schema",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {"answer": {"type": "string"}},
        "required": ["answer"],
        "additionalProperties": false
      }
    }
  }
}
```

Note: `response_format` is deprecated in favor of `text.format` in the Responses API.

### Custom Tools with Grammar Constraints (Responses API, GPT-5+ only)

The Responses API supports a `"custom"` tool type that accepts raw text (not JSON) constrained by a CFG or regex. Uses LLGuidance under the hood.

```json
"tools": [{
  "type": "custom",
  "name": "spell_program",
  "description": "A Spell program",
  "format": {
    "type": "grammar",
    "syntax": "lark",       // or "regex" (Rust regex crate syntax)
    "definition": "start: sexpr\nsexpr: \"(\" atom+ \")\"\n..."
  }
}]
```

This is the **only place in any major cloud API** where you can use arbitrary grammars to constrain output. Limitations:
- GPT-5+ models only
- Responses API only (not Chat Completions)
- Community reports: complex grammars may not be perfectly followed
- Only applies to tool call output, not the main text response

### JSON Schema Limitations (OpenAI)
- `additionalProperties: false` required at every object level
- All fields must be listed in `required`
- No `pattern` (regex) support on strings
- No `minimum`/`maximum`/`minLength`/`maxLength`
- Max 100 properties, max 5 nesting levels, max 500 enum values
- Uses CFG-based constrained decoding internally

### Prefill
OpenAI does not support assistant prefill in either API. Spell already handles this — `_prefix` is ignored in `openai-request`.

---

## Anthropic

### JSON Outputs

Parameter: **`output_config.format`** (GA, no beta header needed)

```json
{
  "model": "claude-sonnet-4-5-20250514",
  "max_tokens": 1024,
  "messages": [...],
  "output_config": {
    "format": {
      "type": "json_schema",
      "schema": {
        "type": "object",
        "properties": {
          "answer": {"type": "string"},
          "confidence": {"type": "number"}
        },
        "required": ["answer", "confidence"],
        "additionalProperties": false
      }
    }
  }
}
```

Migration note: The earlier beta parameter was `output_format` (top-level, with `anthropic-beta: structured-outputs-2025-11-13`). Both work during transition but `output_config.format` is the current GA form.

### Strict Tool Use

```json
{
  "tools": [{
    "name": "get_answer",
    "strict": true,
    "input_schema": {
      "type": "object",
      "properties": {"value": {"type": "string"}},
      "required": ["value"],
      "additionalProperties": false
    }
  }]
}
```

### Regex Support (Limited)

Anthropic supports the `pattern` keyword on string fields within JSON schema:
- Supported: `^...$`, `*`, `+`, `?`, `{n,m}` (simple), `[]`, `.`, `\d`, `\w`, `\s`, `(...)`
- Not supported: backreferences, lookahead/lookbehind, word boundaries, complex `{n,m}`

No arbitrary grammar/CFG support.

### Prefill Incompatibility — Critical for Spell

**Structured outputs and prefill are mutually exclusive.** Furthermore:

| Model | Prefill Status |
|-------|---------------|
| Opus 4.6, Sonnet 4.6 | **400 error** — fully removed |
| Sonnet 4.5 | Deprecated — still works |
| Opus 4.5, Opus 4.1, Sonnet 4, 3.x | Works, but cannot combine with structured outputs |

### Schema Limitations (Anthropic)
- No recursive schemas
- No numerical constraints (`minimum`, `maximum`, `multipleOf`)
- No string length constraints (`minLength`, `maxLength`)
- No complex array constraints beyond `minItems` 0 or 1
- `additionalProperties` must be `false`
- Max 20 strict tools per request, 24 optional params, 16 union-typed params
- First request with new schema has compilation latency (cached 24h)
- Refusals and max_tokens truncation can produce non-conforming output

---

## Google Gemini

### Parameters (inside `generationConfig`)

**`responseMimeType`** (string):
- `"text/plain"` — default
- `"application/json"` — forces valid JSON
- `"text/x.enum"` — forces bare enum value

**`responseSchema`** (OpenAPI 3.0 subset) or **`responseJsonSchema`** (standard JSON Schema) — mutually exclusive. Both require `responseMimeType` to be set.

```json
{
  "generationConfig": {
    "responseMimeType": "application/json",
    "responseJsonSchema": {
      "type": "object",
      "properties": {
        "answer": {"type": "string"},
        "score": {"type": "integer"}
      },
      "required": ["answer", "score"]
    }
  }
}
```

### Two Schema Flavors

1. **`responseSchema`** — original, OpenAPI 3.0 subset. Non-standard `propertyOrdering` array.
2. **`responseJsonSchema`** — added Nov 2025. Supports `anyOf`, `$ref`/`$defs`, `minimum`/`maximum`, `prefixItems`, `additionalProperties`, `type: "null"`. Works with Pydantic/Zod.

### Enum Mode

```json
{
  "generationConfig": {
    "responseMimeType": "text/x.enum",
    "responseSchema": {
      "type": "STRING",
      "enum": ["positive", "negative", "neutral"]
    }
  }
}
```

### Limitations (Gemini)
- Complex schemas cause 400 errors (long names, large enums, deep nesting)
- Schema counts toward input tokens
- Gemini 2.0 sorts keys alphabetically unless `propertyOrdering` specified
- Unsupported schema features silently ignored
- Do not duplicate schema in prompt text (degrades quality)
- No arbitrary grammar or regex support

---

## Compatibility with Spell

### The Core Tension

Spell's architecture depends on **prompt-as-prefix semantics**: the `llm` function sends the prompt as both user message and assistant prefill, then the LLM's response is concatenated with the prefix and parsed as a Spell program. This is fundamentally incompatible with structured output constraints for two reasons:

1. **Structured outputs produce JSON, not Spell code.** The response would be `{"answer": 42}` not `(def answer 42) '(extend completion)`.

2. **Prefill and structured outputs are mutually exclusive** on Anthropic (the only provider where Spell uses prefill). On newer models (4.6), prefill is removed entirely.

### Where Structured Outputs Could Help

Structured outputs are relevant for **leaf LLM calls** — when a Spell agent calls an LLM to get a structured answer rather than to generate more Spell code. This is the `make-test-leaf-llm` / `eval?=false` path, and the existing `wrap-with-format` retry loop.

For these cases, API-level schema constraints would be strictly better than the current soft-constraint (system prompt hint) + post-hoc validation approach:
- Guaranteed valid JSON on first attempt (no retry loop needed)
- Lower latency (no retries)
- More reliable (constrained decoding vs. hoping the model follows instructions)

### Integration Points in Spell

The format would flow through as an **option on `make-llm`**, passed to the provider's `call-llm` via opts:

```
.agent.edn :format → agent/make-agent-llm → llm/make-llm → call-fn opts → provider/call-llm
```

Currently the chain breaks at `call-fn` — `:format` is not included in the opts map sent to `provider/call-llm`. Each provider would need:

| Provider | Parameter to add | Notes |
|----------|-----------------|-------|
| Anthropic | `output_config.format` in request body | Only for leaf calls (no prefill). Incompatible with thinking. |
| OpenAI Chat | `response_format` in request body | Works naturally (no prefill anyway) |
| OpenAI Responses | `text.format` in request body | Works naturally |
| Ollama | Depends on backend | Some support `format` field |
| Kimi | Unknown | Needs research |

### What This Doesn't Solve

- **Prefill removal on new Anthropic models**: Independent problem. Opus 4.6 and Sonnet 4.6 return 400 on prefill. Spell needs a strategy for these models regardless of structured outputs.

---

## S-Expression Grammar Constraints for Code Generation

### The Opportunity

OpenAI's Responses API custom tools (GPT-5+) could constrain Spell code generation at the syntax level. S-expressions are a simple grammar — a Lark definition fits in ~550 characters, well under the ~2k character limit:

```lark
start: expr+
expr: atom | list | vec | map | quoted | spliced | deref
list: "(" expr* ")"
vec: "[" expr* "]"
map: "{" (expr expr)* "}"
quoted: "'" expr
spliced: "~" expr
deref: "@" expr
atom: NUM | STR | SYM | KW | BOOL | NIL
NUM: /-?[0-9]+(\.[0-9]+)?/
STR: /"([^"\\]|\\.)*"/
SYM: /[a-zA-Z_+\-*\/?=<>!&.][a-zA-Z0-9_+\-*\/?=<>!&.:']*/
KW: /:[a-zA-Z_+\-*\/?=<>!&.][a-zA-Z0-9_+\-*\/?=<>!&.:]*/
BOOL: /true|false/
NIL: "nil"
%ignore /\s+/
%ignore /;[^\n]*/
```

Lisp syntax is well-represented in LLM training data, so the "alien format" conformance concern is likely minimal.

### API Integration

Request (force tool use, get grammar-constrained S-expression output):
```json
{
  "model": "gpt-5",
  "input": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
  "tools": [{
    "type": "custom",
    "name": "spell_program",
    "description": "A Spell program as S-expressions",
    "format": {"type": "grammar", "syntax": "lark", "definition": "..."}
  }],
  "tool_choice": "required"
}
```

Response (constrained output in `input` field of tool call):
```json
{
  "output": [{
    "type": "custom_tool_call",
    "name": "spell_program",
    "input": "(def x 42) '(extend completion)"
  }]
}
```

### Prefix-Continuation and Suffix Grammars

OpenAI has no prefill, so the model generates the entire program from scratch. Three approaches to preserve prefix semantics:

**A. Full-program grammar.** Model generates a complete program. Prefix content communicated via system/user message context only — no structural guarantee the output starts with the prefix. Simplest approach; may be sufficient since the model sees the prefix in the prompt.

**B. Prefix embedded in grammar.** Grammar starts with the exact prefix as a literal, followed by the continuation grammar. Forces exact prefix reproduction but wastes tokens. Requires new grammar per call (compilation latency ~10s on first use, cached thereafter).

**C. Suffix grammar (left quotient).** Compute the derivative of the S-expression grammar with respect to the known prefix — yielding a grammar that matches only valid continuations. Client concatenates prefix + response.

This is a well-established operation in formal language theory:
- **Left quotient** / **Brzozowski derivative**: `P\L = {s | P·s ∈ L}`
- CFLs are closed under left quotient by a string (result is always a CFL)
- Construction: advance an Earley parser through the prefix, extract grammar from the resulting chart (Melcer et al. 2024, "Constrained Decoding via Left and Right Quotienting")
- For S-expressions, the parser state after consuming a prefix is compact — essentially "stack of open delimiters + whether we're mid-atom/string"

Practical considerations for approach C:
- Suffix grammar changes per call (different prefix each time) — triggers grammar recompilation
- But for S-expressions the suffix grammar is small (few reachable states), so compilation should be fast
- Need to implement the Earley-chart-to-suffix-grammar construction (no off-the-shelf library exposes this)
- Could be done in Clojure using Spell's own parser state

### How Constrained Decoding Engines Handle This Internally

All major engines (llguidance, XGrammar, Outlines) use the same approach: maintain incremental parser state, advance it through already-generated tokens, compute next-token mask from current state. None construct explicit suffix grammars — the parser state *is* the suffix grammar in implicit form.

- **llguidance** (OpenAI): Earley parser + Brzozowski-derivative regex lexer. `Matcher.accept_token()` advances state. ~50us per mask computation.
- **XGrammar** (vLLM/SGLang): PDA-based. `accept_string()` advances through prefix. Vocabulary partitioning for fast masking.
- **Outlines**: Lark LALR(1) `InteractiveParser.feed_token()` + `accepts()` for valid terminals.

### Practical Assessment

| Approach | Feasibility | Main concern |
|----------|-------------|--------------|
| Full-program grammar (A) | High | Loses prefix-continuation semantics |
| Prefix in grammar (B) | Medium | Per-call compilation latency; token waste |
| Suffix grammar (C) | Medium | Needs custom implementation; per-call grammar |
| Wait for Anthropic grammar support | Unknown | No indication this is coming |

Approach A is the pragmatic starting point: define the S-expression grammar once, use `tool_choice: "required"`, extract the program from `custom_tool_call.input`. The model sees the prefix/context in the prompt and generates a syntactically valid complete program. This sidesteps the prefix question entirely and is the simplest integration path.

### Key References

- Brzozowski, "Derivatives of Regular Expressions" (1964)
- Might, Darais, Spiewak, "Parsing with Derivatives" (2011)
- Melcer et al., "Constrained Decoding via Left and Right Quotienting of Context-Sensitive Grammars" (2024)
- [llguidance](https://github.com/guidance-ai/llguidance) — OpenAI's constrained decoding engine
- [XGrammar](https://github.com/mlc-ai/xgrammar) — vLLM/SGLang's grammar engine
- [derivre](https://github.com/guidance-ai/derivre) — Brzozowski derivative regex engine (used by llguidance)
