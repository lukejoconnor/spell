(ns spell.stdlib
  "Standard library namespaces for Spell.

   These provide extended functions beyond core builtins, organized by domain:
   - strings: String manipulation and regex
   - seqs: Sequence operations beyond core map/filter/reduce
   - fns: Function combinators"
  (:require [spell.eval :as eval]
            [clojure.string :as str]))

;; =============================================================================
;; Helper for spell-fn-aware HOFs (same pattern as eval/invoke-fn)
;; =============================================================================

(defn- invoke-fn
  "Invoke f with args. Handles both spell-fns and Clojure fns."
  [f args]
  (if (eval/spell-fn? f)
    (let [local-env (into eval/*spell-env* (map vector (:params f) args))]
      (first (eval/spell-eval (cons 'do (:body f)) local-env)))
    (apply f args)))

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
  {:docs {:some "First truthy result of (pred x) for x in coll"
          :every? "True if (pred x) is truthy for all x in coll"
          :remove "Keep elements where (pred x) is falsy"
          :keep "Map, removing nil results"
          :mapcat "Map then concat (flatmap)"
          :take-while "Take while predicate is true"
          :drop-while "Drop while predicate is true"
          :find-first "First element where (pred x) is truthy"
          :not-any? "True if (pred x) is falsy for all x"
          :group-by "Group elements by (f x)"
          :sort-by "Sort by (keyfn x)"
          :reverse "Reverse collection"
          :sort "Sort collection"
          :range "Generate range of numbers"
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
          :rand "Random float between 0 and 1"}
   :some (fn [pred coll] (some #(invoke-fn pred [%]) coll))
   :every? (fn [pred coll] (every? #(invoke-fn pred [%]) coll))
   :remove (fn [pred coll] (filterv #(not (invoke-fn pred [%])) coll))
   :keep (fn [f coll]
           (vec (for [x coll
                      :let [v (invoke-fn f [x])]
                      :when (some? v)]
                  v)))
   :mapcat (fn [f coll] (vec (mapcat #(invoke-fn f [%]) coll)))
   :take-while (fn [pred coll] (vec (take-while #(invoke-fn pred [%]) coll)))
   :drop-while (fn [pred coll] (vec (drop-while #(invoke-fn pred [%]) coll)))
   :find-first (fn [pred coll] (some #(when (invoke-fn pred [%]) %) coll))
   :not-any? (fn [pred coll] (not-any? #(invoke-fn pred [%]) coll))
   :group-by (fn [f coll]
               (reduce
                 (fn [m x]
                   (let [k (invoke-fn f [x])]
                     (update m k (fnil conj []) x)))
                 {} coll))
   :sort-by (fn [keyfn coll] (vec (sort-by #(invoke-fn keyfn [%]) coll)))
   :reverse (fn [coll] (vec (reverse coll)))
   :sort (fn [coll] (vec (sort coll)))
   :range (fn
            ([end] (vec (range end)))
            ([start end] (vec (range start end)))
            ([start end step] (vec (range start end step))))
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
   :rand rand})

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
             (reduce (fn [v f] (invoke-fn f [v])) x (reverse fns))))
   :partial (fn [f & args]
              (fn [& more]
                (invoke-fn f (concat args more))))
   :juxt (fn [& fns]
           (fn [& args]
             (mapv #(invoke-fn % args) fns)))
   :complement (fn [f]
                 (fn [& args]
                   (not (invoke-fn f args))))})

;; =============================================================================
;; patterns namespace
;; =============================================================================

(def patterns
  "Reusable orchestration patterns."
  {:docs {:call-now "Continuation pattern: evaluates expr, appends (def name result) to the completion, spawns child LLM that continues with the binding. Returns what the child returns. The binding exists only for the CHILD—code after call-now in your program cannot access it. Use call-now as your last expression and let the child continue. For simple tool calls where you need the result inline, just call the tool directly: (def files (tools/bash \"ls\")) (:out files)."}
   ;; call-now as a Spell function (dynamic scoping resolves 'completion' from caller's env)
   :call-now {:spell/fn true
              :params ['result 'name]
              :body '((if (strings/includes? completion (cat "(def " (str name) " "))
                        result
                        (llm (cat (reopen completion) "(def " (str name) " " (pr-str result) ") "))))}})

;; =============================================================================
;; All standard library namespaces
;; =============================================================================

(def all-namespaces
  "All standard library namespaces."
  {'strings strings
   'seqs seqs
   'fns fns
   'patterns patterns})
