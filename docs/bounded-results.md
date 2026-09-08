# Bounded results and explicit paging

Spell separates a full computation from the ordinary snapshot shown to the next model turn. Tool effects return their complete ordinary values unless the caller explicitly requests a range or limit. `!call-now`, `!peek`, `!print`, explicit `serialize`, and incoming communication bodies are display boundaries.

## Snapshots are the next turn's values

`!call-now` binds the displayed snapshot, **not** a hidden full original. A clipped string or collection contains ordinary omission data. There is no automatic result store, retrieval ID, preview wrapper, or invisible backing value. Compute reductions and select fields **inside the effect expression**, before insertion:

```clojure
'(!call-now summary
   (let [r (io/read-lines "src/server.py")]
     (if (:ok r)
       {:line-count (count (:out r)) :ok true}
       r)))
```

This counts the full raw line vector. Counting the next-turn binding from a broad read instead would count a possibly partial snapshot. A snapshot's omissions are missing evidence, not evidence of absence. Never replay a command, edit, request, or other effect just to recover omitted output. Save exact values deliberately in a file or an exposed `globals` entry when the task requires later access; that is explicit program state, not automatic serialization storage.

## Independent display targets

The run option `:context-max-chars` (CLI `--context-max-chars CHARS`) defaults to 10000 reader-rendered UTF-16 code units. It is a **per-output target**, not one shared budget for a multi-binding call or aggregate report. Model output tokens (`--max-tokens`) remain separate.

```clojure
'(!call-now {:max-chars 2000} a expression-a b expression-b)
'(!peek {:max-chars 2000} page (io/read-lines "src/server.py" 181 191))
'(!print {:max-chars 2000} page)
(serialize value 2000)
```

An override is an integer at least 128; `nil` selects the run default. Negative values, nonintegers, and smaller values are invalid. An explicit override may exceed the run default. An output no larger than 120% of its target stays whole; larger outputs are shortened toward the target, accounting for reader syntax, escaping, and ordinary omission markers. Envelope/structural minimum overhead can exceed a very small target. Exact lifecycle/request/acknowledgement metadata stays outside bounded message bodies; display clipping must not erase obligations.

Strings retain surrogate-safe head and tail where feasible. Ordinary vectors, lists, sets, and maps retain their ordinary kind with omission data; sorted collections may become ordinary unsorted collections. Map keys are not shortened into collisions, and a map omission key is chosen unused among retained keys. Lazy realization and depth are bounded; an infinite sequence is not counted or exhausted to obtain a tail. Opaque host objects, functions, and exceptions may become truthful diagnostic maps rather than callable/dereferenceable handles. Representable Spell function source and quoting are preserved where supported.

A later continuation, `persist`, or recursive container renders an already retained snapshot without silently applying another cap. Explicit `serialize` can deliberately choose a new view. Other arbitrary host metadata is not a promise of round-trip preservation.

## External result envelopes

Operational IO, web, and MCP calls return:

```clojure
{:ok true :out payload :err nil}
```

Failures use `:ok false` and explanatory `:err`, keeping available output under `:out`. Process results additionally have `:exit`; HTTP responses preserve `:status` when known. Serialization adds `:truncated false` when the returned result fits, or `:truncated true` precisely when serialization omits something, without changing `:ok`, `:exit`, or `:status`. An existing true flag survives reserialization and ordinary retained rendering. Line ranges and character windows are request selectors, not truncation. Raw envelopes have no `:truncated` field. Third-party data remains under `:out` rather than overwriting the envelope fields; its own truncated property is ordinary payload data.

| Calls | `:out` on success |
| --- | --- |
| `io/slurp`, `io/read-file` | Complete plain text (or explicit requested range) |
| `io/read-lines` | Detached vector of string rows, with supported source-line positions |
| `io/slurp-bytes` | Full byte array computationally; display may diagnose an opaque value |
| IO write/edit/mkdir/delete/copy/move/temp-file operations | Path/destination receipt |
| `io/ls`, `io/stat` | Entry vector / metadata map |
| `io/sh`, `io/exec`, `io/grep`, `io/glob`, `io/git` | Exact stdout; `:err` is exact stderr or `nil` when empty; `:exit` is preserved |
| `web/search`, `web/fetch`, `web/config` | Requested result vector / full text by default / effective configuration |
| Generated MCP tools, `mcp/read-resource`, `mcp/get-prompt`, `mcp/complete`, `mcp/info`, `mcp/refresh` | Full attributed structured payload or receipt |

`grep` exit 1 with empty stderr is a valid no-match result; other failures need diagnosis. Process launch failure has `:exit nil`. Existing timeout behavior returns `:out ""`, `:exit -1`, and an explanatory timeout error: partial stdout is discarded, not retained for later inspection. The exact-stdout convention above applies to completed processes, not this timeout path. This limitation does not add retries or change timeout policy. MCP semantic errors preserve their structured payload under `:out`; exposed operational transport failures may return envelopes, while argument/schema/permission errors remain explicit exceptions.

Narrow exceptions retain their existing control/accessor values: raw `io/exists?`, `io/directory?`, `io/cwd`, `io/env`; executable `io/sh-test` thunks; asynchronous IO watchers and MCP listeners; and cached MCP `servers`, `resources`, `resource-templates`, and `prompts` listings. Check namespace documentation rather than assuming every namespaced call has an envelope.

## Read current files deliberately

Both `io/read-file` and `io/read-lines` accept `[path]`, `[path start end]`, `[path opts]`, and `[path start end opts]`. Line coordinates are **one-based, half-open** `[start,end)`: `181 221` selects source lines 181 through 220. Past EOF is empty. Ranged reads stream selected/skipped lines rather than slurping the entire file.

```clojure
'(!peek page (io/read-lines "src/server.py" 181 191))
;; Next turn: inspect :ok, :err and :truncated before relying on :out.
(persist receipt (select-keys page [:ok :err :truncated]))
(persist focus (subvec (:out page) 0 (min 2 (count (:out page)))))
'(!extend)
```

`subvec` indices are **local, zero-based, half-open**, not source line numbers. Numbered vectors remain vectors of strings. Rendering can use `first-line` with start/vector pairs and `nil`/gap-string rows for omissions; gaps have no invented source coordinate. Supported line positions are remapped by `subvec`, including recursive vectors and persisted snapshots. Individual long lines can be clipped too.

For a giant line, use per-line **zero-based, half-open UTF-16** character windows:

```clojure
'(!peek page (io/read-lines "data.txt" 37 38 {:char-start 0 :char-end 900}))
;; A later read is new evidence of current contents, not retrieval of the earlier value:
'(!peek next-page (io/read-lines "data.txt" 37 38 {:char-start 900 :char-end 1800}))
```

Offsets remain source-relative; boundaries that bisect surrogate pairs move inward. `read-file` preserves original line terminators, including with character windows; `read-lines` strips terminators but preserves selected string rows even if a character window is empty. Keep paths, source coordinates, inspected excerpts, effect receipts, and the next requested offset before pruning. If exact earlier output was not explicitly saved and has been omitted, report that evidence as unavailable.

## Keep futures in explicit globals

When `globals` is exposed, create a communication future once in a quoted effect action, and keep the live object there rather than passing it through a snapshot binding:

```clojure
'(do
   (globals/set :task-future
     (future (blocking/await (blocking/request :worker "Multiply 23 by 41."))))
   (!extend))
'(!ask-await (globals/get :task-future))
```

After handling an unrelated message, rejoin the same global future. Do not recreate its request. A snapshot diagnostic for a future is not the future. `globals` remains explicit effectful shared state; do not assume it is available without checking the exposed namespace.
