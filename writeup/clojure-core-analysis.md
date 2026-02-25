# Clojure Core Functions Analysis for Spell

This document catalogs Clojure core functions and their suitability for Spell.

## Legend

| Column | Meaning |
|--------|---------|
| **In Spell** | ✓ = implemented, ~ = partial/different |
| **Dangerous** | ✓ = IO/side-effects, ⚠ = state mutation |
| **Useful** | H = high, M = medium, L = low, - = not applicable |

---

## Registry Organization Proposal

```clojure
;; Core (always available - in eval.clj core-builtins)
;; No import needed

;; Stdlib registries (pure, safe - require import)
strings   ; String manipulation
seqs      ; Sequence operations
math      ; Extended math
colls     ; Collection utilities
fns       ; Higher-order function utilities

;; Dangerous registries (IO, mutation - require import)
io        ; File I/O (already: read-file, write-file, str-replace)
shell     ; Shell execution (already: bash)
state     ; Atoms, refs (if ever needed)
```

---

## Arithmetic & Numeric

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `+` | ✓ | | | core-builtins |
| `-` | ✓ | | | core-builtins |
| `*` | ✓ | | | core-builtins |
| `/` | ✓ | | | core-builtins |
| `inc` | ✓ | | | core-builtins |
| `dec` | ✓ | | | core-builtins |
| `mod` | | | H | Useful for cycling, pagination |
| `rem` | | | M | Like mod but different sign behavior |
| `quot` | | | M | Integer division |
| `max` | | | H | Common pattern |
| `min` | | | H | Common pattern |
| `abs` | | | H | Common need |
| `zero?` | | | H | Common predicate |
| `pos?` | | | H | Common predicate |
| `neg?` | | | M | |
| `even?` | | | M | |
| `odd?` | | | M | |
| `pos-int?` | | | L | |
| `neg-int?` | | | L | |
| `nat-int?` | | | L | |
| `int?` | | | M | |
| `float?` | | | M | |
| `double?` | | | L | |
| `rational?` | | | L | |
| `ratio?` | | | L | |
| `integer?` | | | M | |
| `rand` | ✓ | ⚠ | M | Non-deterministic (dangerous for reproducibility) |
| `rand-int` | | ⚠ | M | Non-deterministic |
| `rand-nth` | | ⚠ | M | Non-deterministic |

---

## Comparison & Equality

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `=` | ✓ | | | core-builtins |
| `not=` | ✓ | | | core-builtins |
| `<` | ✓ | | | core-builtins |
| `>` | ✓ | | | core-builtins |
| `<=` | ✓ | | | core-builtins |
| `>=` | ✓ | | | core-builtins |
| `==` | | | L | Numeric equality (vs structural) |
| `identical?` | | | L | Object identity |
| `compare` | | | M | For sorting |

---

## Logic & Predicates

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `not` | ✓ | | | core-builtins |
| `nil?` | ✓ | | | core-builtins |
| `some?` | | | H | Complement of nil? |
| `true?` | | | M | |
| `false?` | | | M | |
| `boolean` | | | M | Coerce to boolean |
| `and` | ✓ | | | special form |
| `or` | ✓ | | | special form |

---

## String Operations

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `str` | ✓ | | | core-builtins |
| `pr-str` | ✓ | | | core-builtins |
| `cat` | ✓ | | | Spell-specific (= str) |
| `subs` | | | H | Substring extraction |
| `count` | ✓ | | | Works on strings too |
| `format` | | | H | Printf-style formatting |
| `name` | | | M | Keyword/symbol -> string |
| `keyword` | | | M | String -> keyword |
| `symbol` | | | M | String -> symbol |
| `char` | | | L | Int -> char |
| `int` | | | M | Char/number -> int |

### clojure.string (require import)

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `clojure.string/trim` | | | H | Remove whitespace |
| `clojure.string/triml` | | | M | Left trim |
| `clojure.string/trimr` | | | M | Right trim |
| `clojure.string/lower-case` | | | H | |
| `clojure.string/upper-case` | | | H | |
| `clojure.string/capitalize` | | | M | |
| `clojure.string/split` | | | H | String -> vector |
| `clojure.string/join` | | | H | Collection -> string |
| `clojure.string/replace` | | | H | Regex/string replace |
| `clojure.string/replace-first` | | | M | |
| `clojure.string/blank?` | | | H | Empty or whitespace? |
| `clojure.string/includes?` | | | H | Substring check |
| `clojure.string/starts-with?` | | | H | |
| `clojure.string/ends-with?` | | | H | |
| `clojure.string/index-of` | | | M | |
| `clojure.string/last-index-of` | | | M | |
| `clojure.string/reverse` | | | L | |
| `clojure.string/escape` | | | M | HTML escaping etc |
| `clojure.string/re-quote-replacement` | | | L | |

---

## Collections: Core

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `list` | ✓ | | | core-builtins |
| `vector` | ✓ | | | core-builtins |
| `vec` | | | H | Coerce to vector |
| `hash-map` | | | M | Create map |
| `hash-set` | | | M | Create set |
| `set` | | | H | Coerce to set |
| `sorted-map` | | | L | |
| `sorted-set` | | | L | |
| `first` | ✓ | | | core-builtins |
| `rest` | ✓ | | | core-builtins |
| `next` | | | M | Like rest but nil for empty |
| `last` | ✓ | | | core-builtins |
| `butlast` | | | M | All but last |
| `second` | | | H | Common need |
| `ffirst` | | | L | (first (first x)) |
| `fnext` | | | L | (first (next x)) |
| `nnext` | | | L | (next (next x)) |
| `nfirst` | | | L | (next (first x)) |
| `cons` | ✓ | | | core-builtins |
| `conj` | ✓ | | | core-builtins |
| `get` | ✓ | | | core-builtins |
| `get-in` | | | H | Nested access |
| `assoc` | ✓ | | | core-builtins |
| `assoc-in` | | | H | Nested assoc |
| `dissoc` | | | H | Remove key |
| `update` | | | H | Apply fn to value |
| `update-in` | | | H | Nested update |
| `select-keys` | | | H | Subset of map |
| `merge` | | | H | Combine maps |
| `merge-with` | | | M | Combine with conflict resolution |
| `zipmap` | | | M | Keys + vals -> map |
| `count` | ✓ | | | core-builtins |
| `nth` | ✓ | | | core-builtins |
| `peek` | | | M | Like last but O(1) for vectors |
| `pop` | | | M | Like butlast but O(1) for vectors |
| `keys` | ✓ | | | core-builtins |
| `vals` | ✓ | | | core-builtins |
| `into` | ✓ | | | core-builtins |
| `concat` | ✓ | | | core-builtins |
| `flatten` | | | M | Deep flatten |
| `empty?` | ✓ | | | core-builtins |
| `not-empty` | | | M | Returns nil or coll |
| `seq` | | | M | Coerce to seq or nil |
| `seq?` | ✓ | | | core-builtins |
| `list?` | ✓ | | | core-builtins |
| `vector?` | ✓ | | | core-builtins |
| `map?` | ✓ | | | core-builtins (excludes spell-fn) |
| `set?` | | | M | |
| `coll?` | | | M | Any collection? |
| `sequential?` | | | M | Ordered collection? |
| `associative?` | | | L | Map or vector? |
| `counted?` | | | L | O(1) count? |
| `reversible?` | | | L | |
| `seqable?` | | | L | |
| `contains?` | | | H | Key existence (maps/sets/vectors) |
| `find` | | | M | Returns MapEntry or nil |
| `distinct` | | | M | Remove duplicates |
| `distinct?` | | | L | All args distinct? |
| `frequencies` | | | H | Count occurrences |
| `group-by` | | | H | Group by key fn |
| `partition` | | | H | Split into chunks |
| `partition-all` | | | M | Like partition, include remainder |
| `partition-by` | | | M | Partition when fn result changes |
| `split-at` | | | M | Split at index |
| `split-with` | | | M | Split by predicate |
| `reverse` | | | H | Reverse order |
| `rseq` | | | L | Fast reverse for sorted colls |
| `shuffle` | | ⚠ | M | Non-deterministic |
| `sort` | | | H | Sort collection |
| `sort-by` | | | H | Sort by key fn |
| `subvec` | | | M | Vector slice |
| `interleave` | | | M | Merge sequences alternately |
| `interpose` | | | M | Insert separator |
| `mapcat` | | | H | map + concat |
| `range` | | | H | Number sequence |
| `repeat` | | | M | Repeated value |
| `repeatedly` | | ⚠ | M | Repeated fn calls (non-det if fn is) |
| `iterate` | | | M | Successive fn applications |
| `cycle` | | | L | Infinite repetition |

---

## Higher-Order Functions

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `apply` | ✓ | | | core-builtins |
| `map` | ✓ | | | core-builtins |
| `filter` | ✓ | | | core-builtins |
| `reduce` | ✓ | | | core-builtins |
| `take` | ✓ | | | core-builtins |
| `drop` | ✓ | | | core-builtins |
| `remove` | | | H | Opposite of filter |
| `keep` | | | M | map + remove nils |
| `keep-indexed` | | | M | keep with index |
| `map-indexed` | | | H | map with index |
| `filter-indexed` | | | M | filter with index (not in core, but useful) |
| `every?` | | | H | All pass predicate? |
| `some` | | | H | Find first truthy |
| `not-every?` | | | M | |
| `not-any?` | | | M | |
| `some?` | | | H | Not nil? |
| `take-while` | | | H | Take until pred fails |
| `drop-while` | | | H | Drop until pred fails |
| `take-last` | | | M | |
| `drop-last` | | | M | |
| `take-nth` | | | L | Every nth element |
| `reductions` | | | M | reduce showing intermediates |
| `reduce-kv` | | | M | reduce for maps |
| `comp` | | | H | Function composition |
| `partial` | | | H | Partial application |
| `juxt` | | | M | Apply multiple fns |
| `complement` | | | M | Negate predicate |
| `constantly` | | | M | Return constant |
| `identity` | | | H | Return argument |
| `fnil` | | | L | Handle nil args |
| `memoize` | | ⚠ | L | Caching (hidden state) |
| `trampoline` | | | L | Tail-call optimization |

---

## I/O & Side Effects (Dangerous)

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `print` | | ✓ | L | Spell returns values, not prints |
| `println` | | ✓ | L | |
| `pr` | | ✓ | L | |
| `prn` | | ✓ | L | |
| `printf` | | ✓ | L | |
| `read` | | ✓ | L | Read from stdin |
| `read-line` | | ✓ | L | |
| `read-string` | | ⚠ | M | Parse s-expr (for spell-eval) |
| `slurp` | | ✓ | H | Read file (via read-file tool) |
| `spit` | | ✓ | H | Write file (via write-file tool) |
| `line-seq` | | ✓ | M | |
| `file-seq` | | ✓ | M | |
| `with-open` | | ✓ | M | |
| `with-in-str` | | ✓ | L | |
| `with-out-str` | | ✓ | M | Capture output |
| `flush` | | ✓ | L | |
| `newline` | | ✓ | L | |

---

## State & Mutation (Dangerous)

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `atom` | | ⚠ | L | Spell uses env threading |
| `deref` / `@` | | ⚠ | L | |
| `reset!` | | ⚠ | L | |
| `swap!` | | ⚠ | L | |
| `compare-and-set!` | | ⚠ | L | |
| `ref` | | ⚠ | L | |
| `dosync` | | ⚠ | L | |
| `alter` | | ⚠ | L | |
| `commute` | | ⚠ | L | |
| `ref-set` | | ⚠ | L | |
| `agent` | | ⚠ | L | |
| `send` | | ⚠ | L | |
| `send-off` | | ⚠ | L | |
| `volatile!` | | ⚠ | L | |
| `vreset!` | | ⚠ | L | |
| `vswap!` | | ⚠ | L | |
| `transient` | | ⚠ | L | |
| `persistent!` | | ⚠ | L | |
| `conj!` | | ⚠ | L | |
| `assoc!` | | ⚠ | L | |
| `dissoc!` | | ⚠ | L | |
| `pop!` | | ⚠ | L | |

---

## Concurrency (Dangerous)

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `future` | ✓ | ⚠ | | special form in Spell |
| `await` | ✓ | ⚠ | | core-builtins |
| `deref` | | ⚠ | M | Spell uses await |
| `future?` | | | M | |
| `future-done?` | | ⚠ | M | |
| `future-cancel` | | ⚠ | M | |
| `future-cancelled?` | | ⚠ | L | |
| `promise` | | ⚠ | L | |
| `deliver` | | ⚠ | L | |
| `realized?` | | ⚠ | L | |
| `pmap` | | ⚠ | M | Parallel map |
| `pcalls` | | ⚠ | L | |
| `pvalues` | | ⚠ | L | |
| `locking` | | ⚠ | L | |

---

## Java Interop (Dangerous)

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `new` | | ✓ | L | Java constructors |
| `.method` | | ✓ | L | Instance methods |
| `Class/staticMethod` | | ✓ | L | Static methods |
| `Class/staticField` | | ✓ | L | Static fields |
| `class` | | ✓ | L | Get class |
| `instance?` | | ✓ | M | Type check |
| `cast` | | ✓ | L | |
| `proxy` | | ✓ | L | |
| `reify` | | ✓ | L | |
| `deftype` | | ✓ | L | |
| `defrecord` | | ✓ | L | |
| `defprotocol` | | ✓ | L | |
| `extend-type` | | ✓ | L | |
| `extend-protocol` | | ✓ | L | |

---

## Metadata

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `meta` | | | L | Get metadata |
| `with-meta` | | | L | Set metadata |
| `vary-meta` | | | L | Update metadata |
| `alter-meta!` | | ⚠ | L | |
| `reset-meta!` | | ⚠ | L | |

---

## Regex

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `re-pattern` | | | M | Compile regex |
| `re-find` | | | H | Find match |
| `re-matches` | | | H | Full string match |
| `re-seq` | | | M | All matches |
| `re-groups` | | | M | Capture groups |
| `re-matcher` | | ⚠ | L | Stateful matcher |

---

## Exception Handling

| Function | In Spell | Dangerous | Useful | Notes |
|----------|----------|-----------|--------|-------|
| `try` | | ⚠ | M | Spell uses error values |
| `catch` | | ⚠ | M | |
| `finally` | | ⚠ | M | |
| `throw` | | ⚠ | L | Spell uses error values |
| `ex-info` | | | M | Create exception |
| `ex-data` | | | M | Get exception data |
| `ex-message` | | | M | Get exception message |
| `ex-cause` | | | L | |

---

## Macros & Special Forms (Not Applicable)

These are compile-time constructs that don't translate to Spell:

- `defmacro`, `macroexpand`, `macroexpand-1`
- `gensym`, `&`, `~`, `` ` ``
- `var`, `binding`, `with-bindings`
- `ns`, `require`, `use`, `import`, `refer`
- `loop`, `recur` (could implement but Spell prefers recursion via `llm`)

---

## Recommended Priority Implementation

### Phase 1: High-value pure functions

Add to `core-builtins` (always available):
```clojure
;; Numeric
mod max min abs zero? pos? neg?

;; Logic
some?

;; Collections
second vec get-in assoc-in dissoc update merge select-keys contains?
reverse sort sort-by range

;; HOF
remove every? some take-while drop-while map-indexed
comp partial identity
```

### Phase 2: Strings registry

```clojure
{:name 'strings
 :desc {:trim "Remove leading/trailing whitespace"
        :lower "Lowercase string"
        :upper "Uppercase string"
        :split "Split string by regex/string"
        :join "Join collection with separator"
        :blank? "Empty or whitespace only?"
        :includes? "Contains substring?"
        :starts-with? "Prefix match?"
        :ends-with? "Suffix match?"
        :replace "Replace all occurrences"
        :replace-first "Replace first occurrence"
        :index-of "Find substring position"
        :format "Printf-style formatting"}
 :items {...}}
```

### Phase 3: Seqs registry (extended sequence ops)

```clojure
{:name 'seqs
 :desc {:group-by "Group by key function"
        :frequencies "Count occurrences"
        :partition "Split into n-sized chunks"
        :interleave "Interleave multiple sequences"
        :interpose "Insert separator between elements"
        :mapcat "Map then concatenate"
        :distinct "Remove duplicates"
        :flatten "Deep flatten"
        :butlast "All but last element"
        :reductions "Reduce with intermediate values"
        :update-in "Nested update"}
 :items {...}}
```

### Phase 4: Regex registry

```clojure
{:name 'regex
 :desc {:find "Find first match"
        :matches "Full string match"
        :seq "All matches"
        :split "Split by pattern"  ; overlaps with strings?
        :replace "Replace matches"}
 :items {...}}
```

### Phase 5: Dangerous registries (already exist)

Already have `io` and `shell` via tools. Could add:
- `rand` registry for non-deterministic ops
- `state` registry if mutable state ever needed

---

## Discussion Points

1. **Core vs Registry**: Where's the line? Current core has ~50 functions. Should we keep it minimal (current) or expand for LLM convenience?

2. **String operations**: Currently bare minimum. LLMs often need text manipulation. Worth expanding core-builtins?

3. **Non-determinism**: `rand`, `shuffle`, `repeatedly` break reproducibility. Separate registry? Or just document the risk?

4. **Error handling**: Spell uses error values not exceptions. Should we expose `try/catch` anyway? Or stick with `spell-error?` pattern?

5. **Regex**: Powerful but complex. Worth the complexity? LLMs might misuse.

6. **Java interop**: Generally avoid, but sometimes needed. Keep forbidden or allow in a `java` registry?
