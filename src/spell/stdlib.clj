(ns spell.stdlib
  "Standard library namespaces for Spell.

   Most Clojure core functions are in core-builtins (eval.clj).
   Namespaces here match Clojure's namespace structure:
   - strings: matches clojure.string
   - math: matches Java's Math (since Clojure uses Math/ interop)
   - patterns: Spell-specific orchestration patterns"
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.runtime :as runtime]
            [spell.patterns :as patterns-lib]))

;; =============================================================================
;; strings namespace (matches clojure.string)
;; =============================================================================

(def strings
  "String manipulation and regex functions (like clojure.string)."
  {:short-docs "String manipulation and regex (mirrors clojure.string)."
   :docs {:guide "STRINGS — Mirrors clojure.string. Regex functions take string patterns (not compiled regex).

Same as Clojure: index-of, last-index-of, starts-with?, ends-with?, includes?, blank?, trim, replace, split, split-lines, join, lower-case, upper-case, capitalize.

Related builtins: subs, re-find, re-matches, re-seq.

Use (!describe strings :fn-name) for any function."}
   :detail
   {:split
    "Split string by regex pattern. Pattern is a string (not a compiled regex).

(strings/split s pattern)
  s: string to split
  pattern: regex pattern as a string

Returns a vector of substrings.

Example:
  (strings/split \"a,b,c\" \",\")       ;; => [\"a\" \"b\" \"c\"]
  (strings/split \"hello world\" \"\\\\s+\") ;; => [\"hello\" \"world\"]"

    :replace
    "Replace all occurrences of a literal string (not regex).

(strings/replace s match replacement)
  s: source string
  match: string to find (literal, not regex)
  replacement: string to substitute

Returns the new string with all occurrences replaced.

Example:
  (strings/replace \"hello world\" \"o\" \"0\") ;; => \"hell0 w0rld\""}
   :index-of (fn [s substr]
               (let [idx (.indexOf ^String (str s) ^String (str substr))]
                 (when (>= idx 0) idx)))
   :last-index-of (fn [s substr]
                    (let [idx (.lastIndexOf ^String (str s) ^String (str substr))]
                      (when (>= idx 0) idx)))
   :starts-with? (fn [s prefix] (.startsWith ^String (str s) (str prefix)))
   :ends-with? (fn [s suffix] (.endsWith ^String (str s) (str suffix)))
   :includes? (fn [s substr] (.contains ^String (str s) (str substr)))
   :blank? str/blank?
   :trim (fn [s] (str/trim (str s)))
   :replace (fn [s match replacement]
              (str/replace (str s) (str match) (str replacement)))
   :split (fn [s pattern] (str/split (str s) (re-pattern pattern)))
   :split-lines (fn [s] (str/split-lines (str s)))
   :join (fn
           ([coll] (str/join coll))
           ([sep coll] (str/join sep coll)))
   :lower-case (fn [s] (str/lower-case (str s)))
   :upper-case (fn [s] (str/upper-case (str s)))
   :capitalize (fn [s] (str/capitalize (str s)))
   })

;; =============================================================================
;; math namespace (matches Java's Math/)
;; =============================================================================

(defn- factorial
  "Compute n! for non-negative integer n."
  [n]
  (if (< n 0)
    (throw (ex-info "factorial: negative argument" {:n n}))
    (loop [i (long n) acc 1N]  ; Use bigint for large factorials
      (if (<= i 1)
        acc
        (recur (dec i) (* acc i))))))

(defn- gcd
  "Greatest common divisor using Euclidean algorithm."
  [a b]
  (let [a (Math/abs (long a))
        b (Math/abs (long b))]
    (if (zero? b) a (recur b (mod a b)))))

(defn- lcm
  "Least common multiple."
  [a b]
  (if (or (zero? a) (zero? b))
    0
    (Math/abs (* (quot a (gcd a b)) b))))

(def math
  "Mathematical functions (like Java's Math/)."
  {:short-docs "Mathematical functions (mirrors java.lang.Math)."
   :docs {:guide "MATH — Mirrors java.lang.Math with standard semantics.

  Basic:         sqrt, cbrt, pow, exp, expm1, abs, sign
  Rounding:      floor, ceil, round, trunc
  Logarithms:    log (natural), log10, log2, log1p
  Trigonometric: sin, cos, tan, asin, acos, atan, atan2
  Hyperbolic:    sinh, cosh, tanh
  Angles:        degrees (rad->deg), radians (deg->rad)
  Number theory: factorial, gcd, lcm
  Misc:          hypot, rand
  Type checks:   NaN?, infinite?
  Constants:     PI, E, INF, NEG-INF, NaN

Related builtins: rand-int, +', -', *', inc', dec', float, double, long, bigdec, rationalize.

All functions take and return numbers. Use (!describe math :fn-name) for any function.

Recommended usage pattern: Write a function, evaluate, inspect the result.

  ...▌(defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
  '(!call-now result (fib 10))"}

   ;; Basic
   :sqrt (fn [x] (Math/sqrt x))
   :cbrt (fn [x] (Math/cbrt x))
   :pow (fn [x y] (Math/pow x y))
   :exp (fn [x] (Math/exp x))
   :expm1 (fn [x] (Math/expm1 x))
   :abs (fn [x] (Math/abs (double x)))
   :sign (fn [x] (Math/signum (double x)))
   ;; Rounding
   :floor (fn [x] (long (Math/floor x)))
   :ceil (fn [x] (long (Math/ceil x)))
   :round (fn [x] (Math/round (double x)))
   :trunc (fn [x] (long x))
   ;; Logarithms
   :log (fn [x] (Math/log x))
   :log10 (fn [x] (Math/log10 x))
   :log2 (fn [x] (/ (Math/log x) (Math/log 2)))
   :log1p (fn [x] (Math/log1p x))
   ;; Trigonometric
   :sin (fn [x] (Math/sin x))
   :cos (fn [x] (Math/cos x))
   :tan (fn [x] (Math/tan x))
   :asin (fn [x] (Math/asin x))
   :acos (fn [x] (Math/acos x))
   :atan (fn [x] (Math/atan x))
   :atan2 (fn [y x] (Math/atan2 y x))
   ;; Hyperbolic
   :sinh (fn [x] (Math/sinh x))
   :cosh (fn [x] (Math/cosh x))
   :tanh (fn [x] (Math/tanh x))
   ;; Angle conversion
   :degrees (fn [r] (Math/toDegrees r))
   :radians (fn [d] (Math/toRadians d))
   ;; Number theory
   :factorial factorial
   :gcd gcd
   :lcm lcm
   ;; Misc
   :hypot (fn [x y] (Math/hypot x y))
   :rand rand
   ;; Type predicates
   :NaN? (fn [x] (Double/isNaN (double x)))
   :infinite? (fn [x] (Double/isInfinite (double x)))
   ;; Constants
   :PI Math/PI
   :E Math/E
   :INF Double/POSITIVE_INFINITY
   :NEG-INF Double/NEGATIVE_INFINITY
   :NaN Double/NaN})

;; =============================================================================
;; patterns namespace (Spell-specific)
;; =============================================================================

(def patterns
  "Reusable orchestration patterns (Spell-specific)."
  patterns-lib/patterns)

;; =============================================================================
;; reminders namespace (docs-only, context reminder for LLM)
;; =============================================================================

(def reminders-namespace
  "Docs-only namespace that reminds the LLM of the Spell execution context."
  {:short-docs "Context reminders for Spell program completion."
   :docs {:guide "REMINDER: This text belongs to the prefix of a Spell program that you are tasked with completing. Your entire response is code; embed all natural language within string literals. Follow the instructions on how to write correct Spell code in your system prompt."
          :context-efficiency "CONTEXT EFFICIENCY — Minimize total context window usage.

Context tokens are your scarcest resource. Prune aggressively to stay effective over long tasks.

Prefer !peek-now over !call-now for disposable tool calls (auto-appends rethink that prunes the command and binding on the following extension):
    '(!peek-now data (io/bash \"find . -name '*.py'\"))

On the subsequent turn, persist what you need before extending:
    (def data \"... 200 lines ...\")
    (rethink)
    ;; turn begins here — data still in scope
    (persist targets (take 5 (strings/split-lines data)))
    '(!extend)
    ;; next turn: the !peek-now call and data are pruned; targets survive as literals

When running a shell script or Python program that you do not need to rerun, keep it inside !peek-now:
    '(!peek-now verify (io/sh \"cd /repo && python - <<'PY'\\nimport ...\\nPY\"))
    (rethink \"Verification passed: the fix handles both edge cases.\")
    '(!extend)
    ;; next turn: both the command and result are gone

When you need to rerun a script later, write it to disk first and then call it with !call-now.

After extended reasoning, rethink to compress:
    (think \"Long analysis of the bug... examining stack traces, testing hypotheses... the root cause is in parse_args line 42.\")
    (rethink \"The bug is in parse_args, line 42: off-by-one in the loop bound.\")
    '(!extend)

When context grows large, compact:
    '(!compact)

Plan-clear pattern — reason and explore, then start fresh with a self-contained plan:
    (think \"analyzing the problem...\" ...)
    '(!peek-now files (io/ls \".\"))
    (def files [...])
    (def plan \"Task: fix the calculator bug in calc.py\\n1. Edit line 12: fix off-by-one\\n2. Run tests\")
    '(!llm-self plan)
    ;; next turn has only the plan as prefix — maximum working space

Each extension should carry forward only what the next step needs."}})

;; =============================================================================
;; builtins namespace (docs-only, for progressive disclosure)
;; =============================================================================

(def builtins-namespace
  "Docs-only namespace for core builtins reference.
   (!describe builtins) for overview, (!describe builtins :category) for category listing."
  {:short-docs "Core functions always available without namespace prefix."
   :docs {:guide "BUILTINS — Core functions always available without namespace prefix.

Categories (use (!describe builtins :category) for full listing):
  special-forms — quote, def, persist, do, if, let, fn, quine, loop, recur, for, try
  macros        — when, defn, cond, if-let/if-some, when-let/when-some, case, ->, ->>, !call-now, ...
  effect        — eval, !llm-self, !ask-await, leaf-llm, describe-fn, llm (trailing expression only)
  math          — +, -, *, /, inc, dec, mod, abs, integer?, numerator, denominator, rand, ...
  comparison    — <, >, =, not, nil?, empty?, identity, ...
  types         — string?, number?, vector?, map?, fn?, keyword?, integer?, ratio?, rational?, ...
  strings       — str, pr-str, subs, cat, format, read-string, re-find, ...
  collections   — list, vector, set, first, rest, nth, conj, count, get, assoc, into, ...
  maps          — keys, vals, merge, update, get-in, assoc-in, dissoc, select-keys, ...
  sequences     — map, filter, reduce, sort, group-by, take, drop, partition, range, ...
  combinators   — comp, partial, juxt, complement, constantly, ...
  bitwise       — bit-and, bit-or, bit-xor, bit-shift-left, ...
  spell         — spell-eval, reopen, wrap-cat, prune-and-reopen, serialize, stored
  concurrency   — future*
  error         — throw, ex-info, ex-data, ex-message, ex-cause, gensym

Use (!describe builtins :category) for full listing of any category.
Use (!describe builtins :fn-name) for individual function docs.
For namespace functions (io/, agents/, globals/, strings/, math/, patterns/), use (!describe <namespace>).

Common mistakes:

1. calling effect builtins outside the trailing expression: !llm-self, !ask-await, leaf-llm, eval, and describe-fn are effect functions; they must appear in the quoted trailing expression or inside !call-now / !peek / !print
2. confusing def with let: def binds in the environment (visible to later expressions); let creates local scope
3. forgetting quote on the trailing expression: the last expression must be quoted so the outer eval can run it with effect bindings
4. str vs cat vs pr-str: str joins arguments as strings; cat is an alias; pr-str serializes as Spell-readable data (vectors, maps, etc.)
5. using read-string on untrusted input: read-string parses Spell code; only use it on data you control
"}
   :detail
   {;; ---- Category listings (full per-function descriptions) ----
    :special-forms
    "  quote — return expression unevaluated as data; prevents evaluation of its argument
  def — bind a value to a symbol in the current environment
  persist — bind like def, but survive prune-and-reopen only when written explicitly in source
  do — evaluate expressions sequentially; return the value of the last one
  if — conditional branch; evaluates test, then either then-expr or else-expr
  let — introduce local bindings scoped to the body; supports destructuring
  fn — create a function; returns source-form data with dynamic scoping semantics
  fn* — internal alias for fn; produced by the #() reader macro
  quine — bind a name to the enclosing form as data, enabling self-referential programs
  loop — establish a recursion point with initial bindings; used with recur
  recur — jump back to the enclosing loop with new values; tail-recursive iteration
  for — list comprehension over a collection with optional :when and :let clauses
  try — evaluate body, catching errors in an optional (catch sym handler) clause"

    :macros
    "  when — like if without an else branch; evaluates body only when test is truthy
  defn — define a named function; shorthand for (def name (fn [params] body...))
  and — short-circuit logical and; returns last truthy value or first falsy value
  or — short-circuit logical or; returns first truthy value or last falsy value
  cond — multi-branch conditional; pairs of test/expr evaluated left to right
  if-let — bind test result; evaluate then if truthy, else otherwise
  if-some — bind test result; evaluate then when value is non-nil, else otherwise
  when-let — bind test result; evaluate body only if binding is truthy
  when-some — bind test result; evaluate body only when value is non-nil
  case — dispatch on equality; matches expr against constant values with optional default
  as-> — thread a value through forms, rebinding a name at each step
  cond-> — thread-first conditionally; applies each step only when its test is truthy
  cond->> — thread-last conditionally; applies each step only when its test is truthy
  some-> — thread-first with nil short-circuit; stops and returns nil on nil intermediate
  some->> — thread-last with nil short-circuit; stops and returns nil on nil intermediate
  !call-now — evaluate expr, extend completion with named binding; crosses the effect boundary
  !peek — same as !call-now, but automatically marks the originating call and new binding as one-turn ephemeral
  !peek-now — alias for !peek
  -> — thread-first; insert value as first argument through a chain of forms
  ->> — thread-last; insert value as last argument through a chain of forms
  future — wrap expr in a thunk and launch as a parallel future; returns a future handle
  !print — evaluate exprs, extend completion with their serialized values as visible literals
  define — Scheme-style alias for def; binds a symbol to a value
  defmacro — define a user-level macro; expander receives unevaluated argument forms
  !describe — extend completion with namespace documentation; accepts ns, ns :key, or mixed (ns1 ns2 :key)
  think — label a reasoning step; evaluates body for side effects, returns nil
  rethink — like think but prunes N previous sibling expressions from source on !extend
  !extend — prune rethink forms from the completion and continue execution via !llm-self
  !compact — prune rethinks and prompt the LLM to compress its context via wrap-cat"

    :effect
    "Per-agent effect builtins (available in trailing expression via double evaluation):

  eval — transparent evaluator; inverse of quote. Merges effect builtins with pure builtins
  !llm-self — call yourself recursively with a new prompt; child inherits your handle
  !ask-await — wait for a future via message wakeup (safe in the caller turn)
  leaf-llm — plain text-in/text-out LLM call; no Spell parsing or evaluation, returns string
  describe-fn — retrieve documentation from a namespace: (describe-fn ns) or (describe-fn ns :key)
  llm — reference to the current LLM function (when available via :llm-var configuration)"

    :math
    "  + — add any number of numeric arguments together
  - — subtract subsequent arguments from the first, or negate a single argument
  * — multiply any number of numeric arguments together
  / — divide first argument by subsequent arguments; returns ratio if exact
  inc — add 1 to a number
  dec — subtract 1 from a number
  quot — integer division truncating toward zero: (quot 7 2) => 3
  mod — modulus: (mod 10 3) => 1; result has same sign as divisor
  rem — remainder: (rem 10 3) => 1; result has same sign as dividend
  abs — absolute value of a number
  max — return the largest of the given numeric arguments
  min — return the smallest of the given numeric arguments
  max-key — return the argument for which (f arg) is greatest
  min-key — return the argument for which (f arg) is smallest
  rand — return a random float between 0 (inclusive) and 1 (exclusive)
  rand-int — return a random integer from 0 to n-1 inclusive
  rand-nth — return a random element from a collection
  random-sample — return elements from collection, each included with given probability
  random-uuid — generate and return a random UUID as a string
  +' -' *' inc' dec' — auto-promoting arithmetic (arbitrary precision on overflow)
  parse-number — parse a numeric string to an integer or float; nil if no number found
  numerator — return the numerator of a ratio in lowest terms
  denominator — return the denominator of a ratio in lowest terms
  even? odd? pos? neg? zero? integer? ratio? rational? pos-int? neg-int? — numeric predicates"

    :comparison
    "  < — return true if arguments are in strictly increasing order
  > — return true if arguments are in strictly decreasing order
  <= — return true if arguments are in non-decreasing order
  >= — return true if arguments are in non-increasing order
  = — return true if all arguments are equal by value
  not= — return true if any two arguments are not equal by value
  compare — three-way comparison returning -1, 0, or 1
  not — return true if argument is falsy (nil or false), false otherwise
  nil? — return true if value is nil
  empty? — return true if collection has no elements
  some? — return true if value is not nil
  true? false? — test for exact boolean values
  any? — always return true; useful as a universal pass-through predicate
  identity — return its single argument unchanged"

    :types
    "  string? number? list? seq? vector? set? map? fn? keyword? symbol? coll? sequential? int? boolean? integer? ratio? rational? pos-int? neg-int? — type predicates
  name — return the local name portion of a keyword or symbol as a string
  symbol — create a symbol from a string: (symbol \"foo\") => foo
  keyword — create a keyword from a string: (keyword \"foo\") => :foo
  namespace — return the namespace portion of a qualified keyword or symbol, or nil
  type — return type name as string (string, number, vector, map, set, list, function, etc.)
  int long float double bigdec bigint rationalize — type coercion
  parse-boolean — parse the string true/false to its boolean value; nil for other input
  boolean — coerce a value to boolean: false and nil become false, everything else true

Note: map? returns false for spell functions ({:spell/fn true ...}) and futures ({:spell/future true ...})."

    :error
    "  throw — raise a catchable Spell error value
  ex-info — create a plain-data exception map with message, data, and optional cause
  ex-data — extract the data map from a Spell exception value or host exception
  ex-message — extract the message string from a Spell exception value or host exception
  ex-cause — extract the cause from a Spell exception value or host exception, or nil if absent
  gensym — generate a fresh symbol, often used for macro hygiene"

    :strings
    "  str — concatenate any arguments into a single string; nil arguments are skipped
  pr-str — return a readable string representation with quotes and escape sequences
  subs — extract a substring: (subs s start) or (subs s start end)
  cat — concatenate any arguments into a string (alias for str)
  format — format a string with arguments using Java String.format conventions
  read-string — parse a single Spell expression from a string and return it as data
  re-find — return the first regex match in a string: (re-find pattern string)
  re-matches — return the full-string regex match or nil: (re-matches pattern string)
  re-seq — return all non-overlapping regex matches as a vector: (re-seq pattern string)"

    :collections
    "  list — create a list from zero or more arguments
  list* — create a list spreading last arg as tail: (list* 1 2 [3 4]) => (1 2 3 4)
  vector — create a vector from zero or more arguments
  set — create a set from a collection of values
  first second last nth ffirst — element access
  rest — all elements after the first; returns empty list if already empty
  next — all elements after the first; returns nil if already empty
  cons — prepend an element to a sequence: (cons 0 [1 2]) => (0 1 2)
  conj — add element to collection (end for vectors, front for lists)
  peek — efficient top access: last for vectors, first for lists
  pop — remove the top element: last for vectors, first for lists
  butlast — return all elements except the last, as a sequence
  count — return the number of elements in a collection
  reverse — reverse a collection, returning a vector
  seq — coerce to sequence; returns nil for empty collections
  vec — coerce a collection to a vector
  subvec — sub-vector slice: (subvec v start) or (subvec v start end)
  not-empty — return the collection if non-empty, nil if empty
  get — look up key in map, vector, or set: (get m :key) or (get m :key default)
  assoc — associate a key with a value in a map or vector
  into — pour one collection into another: (into [] '(1 2 3))
  concat — concatenate sequences
  find — return [key value] map entry for key, or nil if absent
  key val — extract key/value from a map entry
  contains? — true if collection contains key (index for vectors, key for maps/sets)
  disj — remove an element from a set: (disj #{1 2 3} 2) => #{1 3}"

    :maps
    "  keys — return all keys of a map as a sequence
  vals — return all values of a map as a sequence
  merge — merge maps left to right; last value wins for duplicate keys
  merge-with — merge maps combining duplicate values using a function
  update — update value at key by applying a function: (update m :k f)
  update-in — update value at a nested key path: (update-in m [:a :b] f)
  get-in — get value at a nested key path: (get-in m [:a :b])
  assoc-in — set value at a nested key path: (assoc-in m [:a :b] val)
  dissoc — remove one or more keys from a map
  select-keys — return a new map containing only the specified keys
  reduce-kv — reduce over map entries: (reduce-kv f init m) where f takes [acc key val]
  update-keys — transform every key in a map using a function
  update-vals — transform every value in a map using a function
  sorted-map sorted-map-by sorted-set sorted-set-by — sorted collections with optional comparator"

    :sequences
    "  apply — call function with args from a collection: (apply + [1 2 3])
  map — apply function to each element, returning a vector
  map-indexed — like map but function receives [index element]
  filter — keep elements where predicate is truthy, returning a vector
  reduce — fold collection with function: (reduce f init coll)
  keep — map and remove nil results; keep-indexed for index+element variant
  some — return first truthy result of (pred element) across collection
  every? — true if predicate returns truthy for every element
  not-any? not-every? — negated universal/existential quantifiers
  remove — keep elements where predicate is falsy, returning a vector
  mapcat — map then concatenate results
  group-by — group elements by key function, returning map of key to vectors
  sort — sort by natural ordering; sort-by for key function
  find-first — return first element for which predicate returns truthy
  memoize — wrap function to cache return values by argument list
  reduced — signal early termination from inside a reduce
  reductions — return vector of all intermediate reduce accumulator values
  tree-seq — depth-first walk: (tree-seq branch? children root)
  partition-by — split at boundaries where f changes value
  take drop take-last take-while drop-while take-nth drop-last — subsequences
  split-at split-with — split into two parts
  range — (range end), (range start end), (range start end step); returns vector
  repeat — (repeat n x); returns vector
  repeatedly — (repeatedly n f); returns vector
  distinct — remove duplicates preserving order
  flatten — recursively flatten nested collections
  frequencies — map from element to occurrence count
  partition partition-all — fixed-size groups
  interleave interpose — combine/separate sequences
  zipmap — create map from parallel key and value sequences
  dedupe — remove consecutive duplicates
  distinct? — true if all arguments are mutually distinct
  shuffle — randomly reorder"

    :combinators
    "  comp — compose functions right-to-left: ((comp f g) x) calls (f (g x))
  partial — partially apply a function, returning a new fn with args pre-filled
  juxt — apply multiple functions to same args, return vector of results
  complement — negate a predicate function: (complement even?) returns an odd?-like fn
  constantly — return a function that always returns the given value, ignoring args
  every-pred — combine predicates with AND: all must return truthy for result to be true
  some-fn — combine predicates with OR: returns first truthy result across predicates
  fnil — wrap function to substitute default values in place of nil arguments"

    :bitwise
    "  bit-and bit-or bit-xor bit-not — basic bitwise operations
  bit-shift-left — shift left by n positions, filling with zeros
  bit-shift-right — arithmetic right shift, sign-extending
  unsigned-bit-shift-right — logical right shift, zero-filling
  bit-set bit-clear bit-flip bit-test — individual bit manipulation
  bit-and-not — AND of first arg with complement of second"

    ;; ---- Individual Spell-specific function docs ----

    :persist
    "Special form. Bind like def, but mark the binding for explicit reopen-time retention.

(persist sym)
(persist sym expr)

(persist sym) is sugar for (persist sym sym).

Eval-time semantics otherwise match def: expr is evaluated, sym is bound, and
the resulting value is returned.

During prune-and-reopen, explicit source-level persist forms are rewritten to:
  (persist sym <literal-runtime-value-of-sym>)

No other forms are auto-materialized. If you need a value to survive pruning,
write persist explicitly in the source before extending.

Do not write Spell macros that emit persist. Macro-generated persist may not
materialize during reopen. Use explicit source-level persist forms."

    :quine
    "Special form. Bind a name to the entire enclosing form as data.

(quine name body)

Evaluates body, but first binds name to the literal form (quine name body) —
the entire expression as unevaluated data. The binding is available inside body.

Used in the completion wrapper to give the program access to its own source:
  (quine completion (eval (do ...)))
  ;; completion is the form (quine completion (eval (do ...)))

Use quine only when a child LLM needs to see source code.
For regular value bindings, use def."

    :spell-eval
    "Evaluate expression in a fresh environment after closing over caller bindings.

(spell-eval expr)

This is the function that evaluates your completion. It:
1. Takes the expression and the caller's environment
2. Closes over free variables in expr using the caller's environment
3. Evaluates the expanded expression in a fresh environment {}

Because it starts fresh, the child cannot see or modify the parent's bindings.
The expansion step ensures arguments are closed (no unresolved names)."

    :reopen
    "Strip exactly 3 trailing closing parens from a completion string.
Used to reopen the standard completion wrapper so new expressions can be appended.

(reopen completion)

The completion wrapper is (quine completion (eval (do ...))).
Stripping the 3 trailing parens reopens the do block for continuation.

Example:
  '(!llm-self (reopen completion))  ;; child continues your do block"

    :wrap-cat
    "Build an open completion wrapper prefix from arguments.

(wrap-cat arg1 arg2 ...)

Each argument is pr-str'd and joined with spaces. The result is the string:
  \"(quine completion (eval (do arg1-printed arg2-printed ... \"
— an open prefix (no closing parens) that a child LLM continues.

Use with !llm-self to pass context to a child in a proper Spell wrapper:
  '(!llm-self (wrap-cat \"Analyze this:\" data))
  ;; child sees: (quine completion (eval (do \"Analyze this:\" {:key \"val\"} ...
  ;; and continues writing code from there

Compare with passing a bare string to !llm-self:
  '(!llm-self \"Analyze this\")
  ;; auto-wrapped: (quine completion (eval (do (quine prompt \"Analyze this\")
  ;; child sees the string as a quine-bound prompt

wrap-cat is for when you want to embed multiple values (data, prior results)
directly into the child's do block rather than as a single string prompt."

    :prune-and-reopen
    "Destructure a quine form, prune rethink-marked expressions, rebuild as open prefix.

(prune-and-reopen completion)

Unlike reopen (which just strips 3 trailing parens), prune-and-reopen:
1. Parses the quine form
2. Removes expressions marked for pruning by rethink
3. Rewrites explicit source-level (persist sym expr) forms to
   (persist sym <literal-runtime-value-of-sym>)
4. Rebuilds the prefix from the cleaned AST

No other forms are auto-materialized. Do not write Spell macros that emit
persist; macro-generated persist may not materialize during reopen.

Used internally by !call-now, !print, !describe, and !extend."

    :serialize
    "Serialize a value for embedding in a continuation string.

(serialize value)
(serialize value limit)

If the serialized form exceeds the limit, large subvalues are stored
out-of-band and replaced with (stored \"id\") references.
Negative limit means always inline (never store out-of-band)."

    :stored
    "Retrieve a large value from the out-of-band store by its ID.

(stored id)

Values too large to inline during serialization are stored out-of-band
and referenced as (stored \"id\"). This function retrieves them."

    :deep-truncate
    "Recursively truncate string values within nested data structures to a character limit.

(deep-truncate value limit)

Walks maps, vectors, and lists. Strings exceeding the limit are truncated
with a \"...\" suffix. Non-string leaves are unchanged."

    :strip-parens
    "Strip n trailing closing parens from a string.

(strip-parens n s)

Lower-level than reopen. Use reopen for the standard 3-paren case."

    :eval
    "Per-agent effect builtin. Transparent evaluator; inverse of quote.

(eval expr)

Merges effect builtins (!llm-self, !ask-await, leaf-llm, agents/, io/, globals/)
with pure builtins, then evaluates expr. This is what makes effect functions
available in the trailing expression of the completion wrapper.

The double evaluation pattern:
  (quine completion (eval (do ... '(!llm-self ...))))
  The do block returns the quoted list as data.
  eval evaluates it with effect functions bound."

    :!llm-self
    "Call yourself recursively with a new prompt. Child inherits your handle
(same logical agent, serial execution).

(!llm-self prompt)
(!llm-self prompt handle)

prompt: string or open prefix. If a bare string, automatically wrapped in
the completion wrapper. If already an open prefix (from reopen or wrap-cat),
used as-is.

The child writes Spell code that is parsed and evaluated. Use for:
- Extending context (!extend, !compact)
- Delegating computation to a child
- Multi-step tool use chains

For parallel LLM work, use agents/spawn instead (separate handles)."

    :!ask-await
    "Wait for a Spell future via message wakeup from a normal agent turn.

(!ask-await fut)

fut: Spell future returned by (future ...)
Returns: the next wakeup message from the waiter thread

This installs a background waiter future that dereferences fut, sends the
result back to the current handle as a normal message, then blocks the current
turn until that message arrives. Use this when you need to wait on a future
without entering future-only blocking/ APIs from a normal agent turn.

Example:
  '(!ask-await some-future)
  ;; next turn receives msg with {:from :future :body result}"

    :leaf-llm
    "Plain text-in/text-out LLM call. No Spell parsing or evaluation.

(leaf-llm prompt)

prompt: string
Returns: string (the raw LLM response)

Use when you need a natural-language answer, judgment, or text generation
rather than code execution. The response is returned as a string value.

Example:
  '(!call-now answer (leaf-llm \"Is this a mammal? yes/no\"))
  ;; answer is \"yes\" or \"no\" as a string"

    :describe-fn
    "Retrieve documentation from a namespace. Pure function underlying the !describe macro.

(describe-fn ns)       — returns :guide if available, else :docs map
(describe-fn ns :key)  — returns detailed doc for :key (checks :detail, then :docs)"

    :!call-now
    "Macro. Evaluate expr (which may use effect functions), bind result to name,
and extend the completion so the child LLM continues with the binding in scope.

'(!call-now name expr)

This is the primary tool-calling pattern. The expr is evaluated with effect
functions available, the result is serialized as (def name result) in the
continuation, and a child LLM turn begins.

Example — tool call:
  '(!call-now files (io/sh \"ls\"))
  ;; next turn: files is bound to {:exit 0 :out \"...\" :err \"...\"}

Example — inspect a computation:
  '(!call-now result (+ (* 3 17) (/ 100 4)))
  ;; next turn: result is bound to 76

Use !peek when you want this command and binding to disappear on the following
extension unless you explicitly persist what you need.

If the tool call is a disposable shell script or Python program, prefer !peek-now;
the command and its binding(s) will be pruned on the next extension. If you need
to rerun the script later, write it to disk first with io/write-file."

    :!peek
    "Macro. Ephemeral version of !call-now.

'(!peek name expr)
'(!peek-now name expr)

!peek/!peek-now runs like !call-now, then appends:
  (rethink 2 \"!peek-now call and binding(s) disappear unless you persist what you need.\")

On your next extension, that rethink prunes both the peek command and its
result binding(s) from source. If you need part of the value, persist it first
with your own persist form.

Example:
  '(!peek-now code (io/read-lines \"main.py\"))
  (def code [\"... many lines ...\"])
  (rethink 2 \"!peek-now call and binding(s) disappear unless you persist what you need.\")
  (def fn-defn (subvec code 100 111))
  ;; next turn: both the !peek-now call and code are pruned; fn-defn remains"

    :!peek-now
    "Alias for !peek."

    :!print
    "Macro. Evaluate exprs and place their serialized values as bare literals in the
continuation — like !call-now but without creating named bindings.

'(!print expr)
'(!print expr1 expr2 expr3)

Use to inspect values without polluting the namespace. Accepts any number of arguments."

    :print
    "Alias for !print. Prefer !print."

    :think
    "Macro. Label a reasoning step. Evaluates body for side effects (bindings), returns nil.

(think label body...)

The label is a string describing the reasoning. Body expressions are evaluated
for their side effects (typically def bindings). Think is visible in the source
code for context but does not produce a return value.

Example:
  (think \"Sum formula is n*(n+1)/2.\" (def total (/ (* 100 101) 2)))"

    :rethink
    "Macro. Like think but marks the previous sibling expression for pruning on extend.

(rethink label body...)
(rethink N label body...)

When the completion is next extended (via !extend, !call-now, !print, or !describe),
the marked expressions are removed from the source. This keeps the context
window clean after corrections.

N defaults to 1 (prune previous sibling). Specify N to prune more siblings.

Example:
  (think \"Sum is n*(n+1) = 10100.\" (def total (* 100 101)))
  (rethink \"Wrong — formula is n*(n+1)/2.\" (def total (/ (* 100 101) 2)))
  '(!extend completion)
  ;; child sees only the corrected think"

    :!extend
    "Macro. Prune rethink-marked expressions from the completion and continue via !llm-self.

'(!extend completion)

Calls prune-and-reopen on the completion, then !llm-self with the cleaned prefix.
Use after a rethink to continue with a shorter, corrected context."

    :!compact
    "Macro. Prune rethinks, then prompt the LLM to compress its context.

'(!compact completion)

Like !extend but additionally asks the LLM to summarize/compress. Use when
context has grown large and you want to continue from a shorter prefix."

    :defmacro
    "Macro. Define a user-level source-to-source transform.

(defmacro name [params] body...)

The body receives unevaluated argument forms and returns a new form to evaluate.
Use list, cons, gensym (for hygiene), and other pure builtins to build forms.
Macros cannot use effect functions.

Example:
  (defmacro unless [test body] (list 'if test nil body))
  (unless false 42) ;; expands to (if false nil 42) => 42"}})

;; =============================================================================
;; describe
;; =============================================================================

(defn describe
  "Get documentation from a namespace.
   (!describe ns) — guide if available, else docs map
   (!describe ns :key) — detailed doc for specific item (checks :detail, then :docs)"
  ([ns] (or (get-in ns [:docs :guide]) (:docs ns)))
  ([ns key] (or (get-in ns [:detail key])
                (get-in ns [:docs key])
                (get ns key))))

(defn ask-await-builtin
  "Effect builtin for waiting on a Spell future from a normal agent turn.
   The waiter future sends the result back as a normal message, then the
   caller blocks until that wakeup arrives."
  [fut]
  (when-not (eval/spell-future? fut)
    (throw (ex-info "!ask-await requires a future" {:value fut})))
  (let [target runtime/*current-handle*]
    (when-not target
      (throw (ex-info "!ask-await: not inside an agent context" {})))
    (future
      (try
        (let [result (deref (:ref fut))]
          (binding [runtime/*current-handle* :future]
            (runtime/send target result)))
        (catch Exception e
          (binding [runtime/*current-handle* :future]
            (runtime/send target {:future-await/error (.getMessage e)})))))
    (runtime/block-for-message)))

;; =============================================================================
;; All standard library namespaces
;; =============================================================================

(def all-namespaces
  "Core standard library namespaces (always available, not gated by eval).
   patterns is an effect namespace (its functions call leaf-llm, agents/!spawn-ask).
   Note: seqs, fns, and bit- operations are in core-builtins (matching Clojure)."
  {'strings strings
   'math math})
