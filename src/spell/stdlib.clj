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
  {:docs {:subs "Substring. (subs s start) or (subs s start end)"
          :index-of "Index of substr in s, or nil if not found"
          :last-index-of "Last index of substr in s, or nil if not found"
          :starts-with? "True if s starts with prefix"
          :ends-with? "True if s ends with suffix"
          :includes? "True if s contains substr"
          :blank? "True if s is nil, empty, or only whitespace"
          :trim "Remove leading/trailing whitespace"
          :replace "Replace all occurrences: (replace s match replacement)"
          :split "Split string by regex pattern"
          :split-lines "Split string on line breaks"
          :join "Join collection with separator"
          :lower-case "Convert to lowercase"
          :upper-case "Convert to uppercase"
          :capitalize "Capitalize first character"
          :re-find "Find first regex match in string"
          :re-matches "True if entire string matches regex"
          :re-seq "Return lazy seq of all regex matches"}
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
  {:docs {:_ "Wraps java.lang.Math — sqrt pow log sin cos tan abs floor ceil round etc. all work as expected. Non-obvious extras:"
          :factorial "n! (bigint-safe)"
          :gcd "Greatest common divisor"
          :lcm "Least common multiple"
          :log2 "Base-2 logarithm"
          :+' "+' -' *' inc' dec': auto-promoting arithmetic for big numbers"
          :float "float double long bigdec rationalize: type coercion"
          :PI "PI E INF NEG-INF NaN: constants"
          :rand "Random float in [0, 1); rand-int for integers"}
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
  {:docs {:check-result "Verify an answer using leaf-llm. Returns {:ok answer} if correct, {:wrong msg} if not. Caller decides how to handle wrong results (retry, extend, etc.).
(check-result \"What is 2+2?\" 4)  ; => {:ok 4}
(check-result \"Capital of France?\" \"London\")  ; => {:wrong \"London is the capital of the UK, not France.\"}"}
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
                                         trimmed))})))}})

;; =============================================================================
;; All standard library namespaces
;; =============================================================================

(def all-namespaces
  "All standard library namespaces.
   Note: seqs, fns, and bit- operations are in core-builtins (matching Clojure)."
  {'strings strings
   'math math
   'patterns patterns})
