(ns spell.stdlib
  "Standard library namespaces for Spell.

   These provide extended functions beyond core builtins, organized by domain:
   - strings: String manipulation and regex
   - seqs: Sequence operations beyond core map/filter/reduce
   - fns: Function combinators"
  (:require [spell.eval :as eval]
            [clojure.string :as str]))

;; =============================================================================
;; strings namespace
;; =============================================================================

(def strings
  "String manipulation and regex functions."
  {:docs {:subs "Substring. (subs s start) or (subs s start end)"
          :index-of "Index of substr in s, or nil if not found"
          :starts-with? "True if s starts with prefix"
          :includes? "True if s contains substr"
          :trim "Remove leading/trailing whitespace"
          :replace "Replace all occurrences: (replace s match replacement)"
          :split "Split string by regex pattern"
          :join "Join collection with separator"
          :lower-case "Convert to lowercase"
          :upper-case "Convert to uppercase"
          :re-find "Find first regex match in string"
          :re-matches "True if entire string matches regex"}
   :subs (fn
           ([s start] (subs s start))
           ([s start end] (subs s start end)))
   :index-of (fn [s substr]
               (let [idx (.indexOf ^String (str s) ^String (str substr))]
                 (when (>= idx 0) idx)))
   :starts-with? (fn [s prefix] (.startsWith ^String (str s) (str prefix)))
   :includes? (fn [s substr] (.contains ^String (str s) (str substr)))
   :trim (fn [s] (str/trim (str s)))
   :replace (fn [s match replacement]
              (str/replace (str s) (str match) (str replacement)))
   :split (fn [s pattern] (str/split (str s) (re-pattern pattern)))
   :join (fn
           ([coll] (str/join coll))
           ([sep coll] (str/join sep coll)))
   :lower-case (fn [s] (str/lower-case (str s)))
   :upper-case (fn [s] (str/upper-case (str s)))
   :re-find (fn [pattern s] (re-find (re-pattern pattern) s))
   :re-matches (fn [pattern s] (re-matches (re-pattern pattern) s))})

;; =============================================================================
;; seqs namespace
;; =============================================================================

(def seqs
  "Extended sequence operations."
  {:docs {:every? "True if (pred x) is truthy for all x in coll"
          :remove "Keep elements where (pred x) is falsy"
          :mapcat "Map then concat (flatmap)"
          :take-while "Take while predicate is true"
          :drop-while "Drop while predicate is true"
          :find-first "First element where (pred x) is truthy"
          :not-any? "True if (pred x) is falsy for all x"
          :group-by "Group elements by (f x)"
          :sort-by "Sort by (keyfn x)"
          :sort "Sort collection"
          :repeat "Repeat value n times"
          :distinct "Remove duplicates"
          :flatten "Flatten nested collections"
          :frequencies "Count occurrences of each element"
          :partition "Partition into groups of n"
          :partition-all "Partition, including partial final group"
          :interleave "Interleave multiple collections"
          :interpose "Insert separator between elements"
          :zipmap "Create map from keys and values"
          :split-at "Split at index into [before after]"
          :map-slice "Slice a sorted map by key range (inclusive). (map-slice m 10 20) => submap with keys 10-20"}
   :every? (fn [pred coll] (every? #(eval/invoke-fn pred [%]) coll))
   :remove (fn [pred coll] (filterv #(not (eval/invoke-fn pred [%])) coll))
   :mapcat (fn [f coll] (vec (mapcat #(eval/invoke-fn f [%]) coll)))
   :take-while (fn [pred coll] (vec (take-while #(eval/invoke-fn pred [%]) coll)))
   :drop-while (fn [pred coll] (vec (drop-while #(eval/invoke-fn pred [%]) coll)))
   :find-first (fn [pred coll] (some #(when (eval/invoke-fn pred [%]) %) coll))
   :not-any? (fn [pred coll] (not-any? #(eval/invoke-fn pred [%]) coll))
   :group-by (fn [f coll]
               (reduce
                 (fn [m x]
                   (let [k (eval/invoke-fn f [x])]
                     (update m k (fnil conj []) x)))
                 {} coll))
   :sort-by (fn [keyfn coll] (vec (sort-by #(eval/invoke-fn keyfn [%]) coll)))
   :sort (fn [coll] (vec (sort coll)))
   :repeat (fn [n x] (vec (repeat n x)))
   :distinct (fn [coll] (vec (distinct coll)))
   :flatten (fn [coll] (vec (flatten coll)))
   :frequencies frequencies
   :partition (fn
                ([n coll] (vec (map vec (partition n coll))))
                ([n step coll] (vec (map vec (partition n step coll)))))
   :partition-all (fn
                    ([n coll] (vec (map vec (partition-all n coll))))
                    ([n step coll] (vec (map vec (partition-all n step coll)))))
   :interleave (fn [& colls] (vec (apply interleave colls)))
   :interpose (fn [sep coll] (vec (interpose sep coll)))
   :zipmap zipmap
   :split-at (fn [n coll] [(vec (take n coll)) (vec (drop n coll))])
   :map-slice (fn [m start end] (into (sorted-map) (subseq m >= start <= end)))})

;; =============================================================================
;; fns namespace
;; =============================================================================

(def fns
  "Function combinators."
  {:docs {:comp "Compose functions right-to-left: ((comp f g) x) = (f (g x))"
          :partial "Partial application: ((partial f a) b) = (f a b)"
          :juxt "Apply multiple fns, return vector: ((juxt f g) x) = [(f x) (g x)]"
          :complement "Negate predicate: ((complement f) x) = (not (f x))"}
   :comp (fn [& fns]
           (fn [x]
             (reduce (fn [v f] (eval/invoke-fn f [v])) x (reverse fns))))
   :partial (fn [f & args]
              (fn [& more]
                (eval/invoke-fn f (concat args more))))
   :juxt (fn [& fns]
           (fn [& args]
             (mapv #(eval/invoke-fn % args) fns)))
   :complement (fn [f]
                 (fn [& args]
                   (not (eval/invoke-fn f args))))})

;; =============================================================================
;; math namespace
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
  "Mathematical functions."
  {:docs {;; Basic
          :sqrt "Square root"
          :cbrt "Cube root"
          :pow "Raise x to power y"
          :exp "e^x"
          :expm1 "e^x - 1 (accurate for small x)"
          :abs "Absolute value"
          :sign "Sign of x: -1, 0, or 1"
          ;; Rounding
          :floor "Round down to nearest integer"
          :ceil "Round up to nearest integer"
          :round "Round to nearest integer"
          :trunc "Truncate toward zero"
          ;; Logarithms
          :log "Natural logarithm (base e)"
          :log10 "Base-10 logarithm"
          :log2 "Base-2 logarithm"
          :log1p "log(1 + x) (accurate for small x)"
          ;; Trigonometric (radians)
          :sin "Sine"
          :cos "Cosine"
          :tan "Tangent"
          :asin "Arcsine"
          :acos "Arccosine"
          :atan "Arctangent"
          :atan2 "Arctangent of y/x with correct quadrant"
          ;; Hyperbolic
          :sinh "Hyperbolic sine"
          :cosh "Hyperbolic cosine"
          :tanh "Hyperbolic tangent"
          ;; Angle conversion
          :degrees "Radians to degrees"
          :radians "Degrees to radians"
          ;; Number theory
          :factorial "n! (factorial)"
          :gcd "Greatest common divisor"
          :lcm "Least common multiple"
          ;; Misc
          :hypot "sqrt(x^2 + y^2) without overflow"
          :rand "Random float in [0, 1)"
          :rand-int "Random integer in [0, n)"
          ;; Constants
          :PI "Pi (3.14159...)"
          :E "Euler's number (2.71828...)"
          :INF "Positive infinity"
          :NEG-INF "Negative infinity"
          :NaN "Not a number"}
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
   ;; Constants
   :PI Math/PI
   :E Math/E
   :INF Double/POSITIVE_INFINITY
   :NEG-INF Double/NEGATIVE_INFINITY
   :NaN Double/NaN})

;; =============================================================================
;; patterns namespace
;; =============================================================================

(def patterns
  "Reusable orchestration patterns."
  {:docs {:call-now "Continuation pattern: evaluates expr, appends (def name result) to the completion, spawns child LLM that continues with the binding. Returns what the child returns. The binding exists only for the CHILD—code after call-now in your program cannot access it. Use call-now as your last expression and let the child continue. For simple tool calls where you need the result inline, just call the tool directly: (def files (tools/bash \"ls\")) (:out files)."
          :check-result "Verify an answer using leaf-llm. Returns {:ok answer} if correct, {:wrong msg} if not. Caller decides how to handle wrong results (retry, extend, etc.).
(check-result \"What is 2+2?\" 4)  ; => {:ok 4}
(check-result \"Capital of France?\" \"London\")  ; => {:wrong \"London is the capital of the UK, not France.\"}"}
   ;; call-now as a Spell function (dynamic scoping resolves 'completion' from caller's env)
   :call-now {:spell/fn true
              :params ['result 'name]
              :body '((if (strings/includes? completion (cat "(def " (str name) " "))
                        result
                        (llm (cat (reopen completion) "(def " (str name) " " (pr-str result) ") "))))}
   ;; check-result: verify answer with leaf-llm, return {:ok answer} or {:wrong msg}
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
  "All standard library namespaces."
  {'strings strings
   'seqs seqs
   'fns fns
   'math math
   'patterns patterns})
