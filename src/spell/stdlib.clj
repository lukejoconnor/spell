(ns spell.stdlib
  "Standard library namespaces for Spell.

   Most Clojure core functions are in core-builtins (eval.clj).
   Namespaces here match Clojure's namespace structure:
   - strings: matches clojure.string
   - math: matches Java's Math (since Clojure uses Math/ interop)
   - patterns: Spell-specific orchestration patterns"
  (:require [clojure.string :as str]))

;; =============================================================================
;; strings namespace (matches clojure.string)
;; =============================================================================

(def strings
  "String manipulation and regex functions (like clojure.string)."
  {:guide "STRINGS NAMESPACE — Full Function Listing

  subs          — substring: (strings/subs s start) or (strings/subs s start end)
  index-of      — first index of substring, or nil
  last-index-of — last index of substring, or nil
  starts-with?  — test prefix
  ends-with?    — test suffix
  includes?     — test containment
  blank?        — nil, empty, or whitespace only
  trim          — strip leading/trailing whitespace
  replace       — replace all occurrences: (strings/replace s match replacement)
  split         — split by regex: (strings/split s \"pattern\")
  split-lines   — split by newlines
  join          — join: (strings/join coll) or (strings/join sep coll)
  lower-case    — to lowercase
  upper-case    — to uppercase
  capitalize    — capitalize first char
  re-find       — first regex match: (strings/re-find \"pattern\" s)
  re-matches    — full-string regex match
  re-seq        — all regex matches as vector"
   :docs {:_ "Identical to clojure.string — split, join, replace, trim, includes?, starts-with?, ends-with?, upper-case, lower-case, blank?, index-of, subs, re-find, re-seq, re-matches, split-lines, capitalize"}
   :subs (fn
           ([s start] (subs s start))
           ([s start end] (subs s start end)))
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
   :re-find (fn [pattern s] (re-find (re-pattern pattern) s))
   :re-matches (fn [pattern s] (re-matches (re-pattern pattern) s))
   :re-seq (fn [pattern s] (vec (re-seq (re-pattern pattern) s)))})

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
  {:guide "MATH NAMESPACE — Full Function Listing

  Basic:        sqrt, cbrt, pow, exp, expm1, abs, sign
  Rounding:     floor, ceil, round, trunc
  Logarithms:   log (natural), log10, log2, log1p
  Trigonometric: sin, cos, tan, asin, acos, atan, atan2
  Hyperbolic:   sinh, cosh, tanh
  Angles:       degrees (rad->deg), radians (deg->rad)
  Number theory: factorial, gcd, lcm
  Misc:         hypot, rand, rand-int
  Type checks:  NaN?, infinite?
  Auto-promoting: +', -', *', inc', dec' (arbitrary precision)
  Type coercion: float, double, long, bigdec, rationalize
  Constants:    PI, E, INF, NEG-INF, NaN"
   :docs {:_ "Wraps java.lang.Math — sqrt pow log sin cos tan abs floor ceil round, plus: factorial, gcd, lcm, log2, rand, rand-int, +' *' (auto-promoting), PI, E, float/double/long/bigdec/rationalize"}

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
   :rand-int (fn [n] (rand-int n))
   ;; Type predicates
   :NaN? (fn [x] (Double/isNaN (double x)))
   :infinite? (fn [x] (Double/isInfinite (double x)))
   ;; Auto-promoting arithmetic
   :+' +'
   :-' -'
   :*' *'
   :inc' inc'
   :dec' dec'
   ;; Type coercion
   :float float
   :double double
   :long long
   :bigdec bigdec
   :rationalize rationalize
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
  {:guide "PATTERNS NAMESPACE

call-now: Evaluates an expression, binds the result to a name, and extends to a child LLM that sees it.
  '(call-now files (io/sh \"ls\"))
  The child's next turn sees (def files {:exit 0 :out \"...\" :err \"...\"}).
  Requires the completion binding (from quine wrapper in NL prompts).
  One call-now per turn — chain across turns for multi-step tool use.

check-result: Verifies an answer using leaf-llm. Returns {:ok answer} or {:wrong msg}.
  (patterns/check-result \"What is 2+2?\" 4)            ;; => {:ok 4}
  (patterns/check-result \"Capital of France?\" \"London\") ;; => {:wrong \"London is...\"

clean-prompt: Cleans up a raw prompt (voice-to-text, quick notes) via leaf-llm, then runs it.
  '(patterns/clean-prompt \"waht is the captal of franc... like the big city\")
  leaf-llm infers intent and rewrites; llm-self executes the cleaned prompt.
  Accepts a string or quine form (serializes non-strings automatically).

explore: One-shot delegation to a child exploration agent. Spawns a child that greps, reads, and analyzes, then returns structured findings.
  '(call-now findings (patterns/explore \"Where is authentication handled?\"))
  Returns {:answer \"...\" :files [\"src/auth.py\" ...]}\""
   :docs {:call-now "Evaluate a tool, bind the result, and extend to a child LLM that sees it.
(patterns/call-now (tools/bash \"ls\") 'files)
Requires the completion binding (from quine preamble in NL prompts)."
          :check-result "Verify an answer using leaf-llm. Returns {:ok answer} if correct, {:wrong msg} if not. Caller decides how to handle wrong results (retry, extend, etc.).
(check-result \"What is 2+2?\" 4)  ; => {:ok 4}
(check-result \"Capital of France?\" \"London\")  ; => {:wrong \"London is the capital of the UK, not France.\"}"
          :clean-prompt "Clean up a raw prompt via leaf-llm and execute it. Handles voice-to-text, typos, half-sentences.
'(patterns/clean-prompt \"waht is the captal of franc\")
Accepts a string or quine form."
          :explore "One-shot exploration agent. Spawns a child that investigates the codebase and returns {:answer \"...\" :files [...]}.
'(call-now findings (patterns/explore \"Where is auth?\"))"}
   ;; call-now: evaluate tool, bind result in completion, extend to child
   :call-now {:spell/fn true
              :params ['result 'name]
              :body '((if (strings/includes? (pr-str completion) (cat "(def " (str name) " "))
                        result
                        (llm-self (cat (reopen completion) "(def " (str name) " " (pr-str result) ") "))))}
   ;; check-result: verify answer with leaf-llm (core builtin), return {:ok answer} or {:wrong msg}
   :check-result {:spell/fn true
                  :params ['prompt 'answer]
                  :body '((let [verification-prompt (cat "Verify this answer.\n\n"
                                                         "Task: " prompt "\n\n"
                                                         "Answer: " (pr-str answer) "\n\n"
                                                         "Respond with exactly one line:\n"
                                                         "- \"OK\" if correct\n"
                                                         "- \"WRONG: <reason>\" if incorrect")
                                response (leaf-llm verification-prompt)
                                trimmed (strings/trim response)]
                            (if (strings/starts-with? trimmed "OK")
                              {:ok answer}
                              {:wrong (strings/trim
                                       (if (strings/starts-with? trimmed "WRONG:")
                                         (strings/subs trimmed 6)
                                         trimmed))})))}
   ;; clean-prompt: clean up raw text via leaf-llm, then execute with llm-self
   :clean-prompt {:spell/fn true
                  :params ['raw]
                  :body '((let [text (if (string? raw) raw (pr-str raw))
                                cleaned (leaf-llm (cat "Rewrite the following as a clear, well-formed prompt. "
                                                       "Fix typos, complete half-sentences, and infer intent. "
                                                       "The input may be wrapped in code syntax — ignore that and focus on the natural language content. "
                                                       "Output ONLY the rewritten prompt.\n\n"
                                                       text))]
                            (llm-self cleaned)))}
   ;; explore: one-shot delegation to a child exploration agent
   :explore {:spell/fn true
             :params ['query]
             :body '((agents/spawn-recv llm-self
                       (cat "You are an exploration agent. Your task is to investigate the codebase and return structured findings.\n\n"
                            "Use io/sh with grep, find, and io/read-file or io/read-lines to explore.\n"
                            "Return a map with :answer (string summary) and :files (vector of relevant file paths).\n\n"
                            "Query: " query)))}})

;; =============================================================================
;; All standard library namespaces
;; =============================================================================

(def all-namespaces
  "All standard library namespaces.
   Note: seqs, fns, and bit- operations are in core-builtins (matching Clojure)."
  {'strings strings
   'math math
   'patterns patterns})
