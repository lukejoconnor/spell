(ns spell.stdlib-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.eval :as eval :refer [spell-eval run-spell]]
            [spell.stdlib :as stdlib]))

;; =============================================================================
;; Test helpers
;; =============================================================================

(defn- extract-ns-fns
  "Extract all functions from a namespace map (excluding :docs)."
  [ns-map]
  (into {}
    (for [[k v] ns-map
          :when (not= k :docs)]
      [(symbol (name k)) v])))

(def test-builtins
  "Full builtins including string functions for testing."
  (merge eval/core-builtins
         (extract-ns-fns stdlib/strings)))

(def test-env-with-namespaces
  "Environment with stdlib namespaces for qualified access testing."
  {'strings stdlib/strings
   'math stdlib/math
   'patterns stdlib/patterns})

(defn run-spell-full
  "Run spell with full builtins (including stdlib) and namespaces."
  [program]
  (binding [eval/*builtins* test-builtins]
    (let [r (spell-eval program test-env-with-namespaces)]
      (if (eval/ok? r) (:ok r) (throw (ex-info (:err r) {:result r}))))))

;; =============================================================================
;; Core HOFs with spell-fns (now in core-builtins, match Clojure)
;; =============================================================================

(deftest core-every?-test
  (testing "every? all match"
    (is (true? (run-spell-full '(every? (fn [x] (> x 0)) [1 2 3])))))
  (testing "every? some fail"
    (is (false? (run-spell-full '(every? (fn [x] (> x 2)) [1 2 3])))))
  (testing "every? with spell-fn"
    (is (true? (run-spell-full '(do (defn pos? [x] (> x 0))
                                    (every? pos? [1 2 3]))))))
  (testing "every? empty is vacuously true"
    (is (true? (run-spell-full '(every? (fn [x] false) []))))))

(deftest core-remove-test
  (testing "remove keeps non-matching"
    (is (= [1 2 3] (run-spell-full '(remove (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "remove with spell-fn"
    (is (= [1 2] (run-spell-full '(do (defn big? [x] (> x 2))
                                      (remove big? [1 2 3 4]))))))
  (testing "remove nothing"
    (is (= [1 2 3] (run-spell-full '(remove (fn [x] false) [1 2 3]))))))

(deftest core-mapcat-test
  (testing "mapcat flattens one level"
    (is (= [1 1 2 2 3 3] (run-spell-full '(mapcat (fn [x] [x x]) [1 2 3])))))
  (testing "mapcat with spell-fn"
    (is (= [1 2 2 3 3 4] (run-spell-full '(do (defn pair [x] [x (+ x 1)])
                                               (mapcat pair [1 2 3]))))))
  (testing "mapcat returns vector"
    (is (vector? (run-spell-full '(mapcat (fn [x] [x]) [1 2]))))))

(deftest core-sort-by-test
  (testing "sort-by with keyfn"
    (is (= [{:n 1} {:n 2} {:n 3}]
           (run-spell-full '(sort-by (fn [x] (get x :n)) [{:n 3} {:n 1} {:n 2}])))))
  (testing "sort-by with spell-fn"
    (is (= ["a" "bb" "ccc"]
           (run-spell-full '(do (defn len [s] (count s))
                                (sort-by len ["bb" "ccc" "a"]))))))
  (testing "sort-by on already sorted"
    (is (= [1 2 3] (run-spell-full '(sort-by (fn [x] x) [1 2 3]))))))

(deftest core-take-while-test
  (testing "take-while basic"
    (is (= [1 2] (run-spell-full '(take-while (fn [x] (< x 3)) [1 2 3 4])))))
  (testing "take-while with spell-fn"
    (is (= [1 2 3] (run-spell-full '(do (defn small? [x] (< x 4))
                                        (take-while small? [1 2 3 4 5]))))))
  (testing "take-while none match"
    (is (= [] (run-spell-full '(take-while (fn [x] (> x 10)) [1 2 3]))))))

(deftest core-drop-while-test
  (testing "drop-while basic"
    (is (= [3 4 5] (run-spell-full '(drop-while (fn [x] (< x 3)) [1 2 3 4 5])))))
  (testing "drop-while with spell-fn"
    (is (= [4 5] (run-spell-full '(do (defn small? [x] (< x 4))
                                      (drop-while small? [1 2 3 4 5]))))))
  (testing "drop-while all match"
    (is (= [] (run-spell-full '(drop-while (fn [x] (< x 10)) [1 2 3]))))))

(deftest core-not-any?-test
  (testing "not-any? all fail predicate"
    (is (true? (run-spell-full '(not-any? (fn [x] (> x 10)) [1 2 3])))))
  (testing "not-any? some pass"
    (is (false? (run-spell-full '(not-any? (fn [x] (> x 2)) [1 2 3])))))
  (testing "not-any? empty is true"
    (is (true? (run-spell-full '(not-any? (fn [x] true) []))))))

(deftest core-group-by-test
  (testing "group-by basic"
    (is (= {true [4 5] false [1 2 3]}
           (run-spell-full '(group-by (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "group-by with spell-fn"
    (is (= {"small" [1 2] "big" [4 5]}
           (run-spell-full '(do (defn size [x] (if (> x 3) "big" "small"))
                                (group-by size [1 2 4 5])))))))

;; =============================================================================
;; Additional core sequence functions
;; =============================================================================

(deftest core-sort-test
  (testing "sort basic"
    (is (= [1 2 3] (run-spell-full '(sort [3 1 2])))))
  (testing "sort already sorted"
    (is (= [1 2 3] (run-spell-full '(sort [1 2 3]))))))

(deftest core-repeat-test
  (testing "repeat creates vector"
    (is (= [0 0 0] (run-spell-full '(repeat 3 0)))))
  (testing "repeat zero times"
    (is (= [] (run-spell-full '(repeat 0 "x"))))))

(deftest core-repeatedly-test
  (testing "repeatedly calls function n times"
    (let [result (run-spell-full '(repeatedly 3 (fn [] 42)))]
      (is (= [42 42 42] result))))
  (testing "repeatedly with spell-fn"
    (is (= [1 1 1] (run-spell-full '(do (defn one [] 1)
                                         (repeatedly 3 one)))))))

(deftest core-distinct-test
  (testing "distinct removes duplicates"
    (is (= [1 2 3] (run-spell-full '(distinct [1 2 1 3 2 1])))))
  (testing "distinct preserves order"
    (is (= [3 1 2] (run-spell-full '(distinct [3 1 2 3 1]))))))

(deftest core-flatten-test
  (testing "flatten nested"
    (is (= [1 2 3 4 5] (run-spell-full '(flatten [[1 2] [3 [4 5]]])))))
  (testing "flatten already flat"
    (is (= [1 2 3] (run-spell-full '(flatten [1 2 3]))))))

(deftest core-frequencies-test
  (testing "frequencies counts"
    (is (= {1 3 2 2 3 1} (run-spell-full '(frequencies [1 1 1 2 2 3]))))))

(deftest core-partition-test
  (testing "partition drops incomplete"
    (is (= [[1 2] [3 4]] (run-spell-full '(partition 2 [1 2 3 4 5])))))
  (testing "partition with step"
    (is (= [[1 2] [2 3] [3 4]] (run-spell-full '(partition 2 1 [1 2 3 4]))))))

(deftest core-partition-all-test
  (testing "partition-all includes partial"
    (is (= [[1 2] [3 4] [5]] (run-spell-full '(partition-all 2 [1 2 3 4 5])))))
  (testing "partition-all with step"
    (is (= [[1 2] [3 4] [5]] (run-spell-full '(partition-all 2 2 [1 2 3 4 5]))))))

(deftest core-interleave-test
  (testing "interleave two colls"
    (is (= [1 :a 2 :b 3 :c] (run-spell-full '(interleave [1 2 3] [:a :b :c])))))
  (testing "interleave uneven stops at shorter"
    (is (= [1 :a 2 :b] (run-spell-full '(interleave [1 2 3] [:a :b]))))))

(deftest core-interpose-test
  (testing "interpose separator"
    (is (= [1 0 2 0 3] (run-spell-full '(interpose 0 [1 2 3]))))))

(deftest core-zipmap-test
  (testing "zipmap basic"
    (is (= {:a 1 :b 2} (run-spell-full '(zipmap [:a :b] [1 2])))))
  (testing "zipmap uneven truncates"
    (is (= {:a 1 :b 2} (run-spell-full '(zipmap [:a :b :c] [1 2]))))))

(deftest core-split-at-test
  (testing "split-at basic"
    (is (= [[1 2] [3 4 5]] (run-spell-full '(split-at 2 [1 2 3 4 5])))))
  (testing "split-at at zero"
    (is (= [[] [1 2 3]] (run-spell-full '(split-at 0 [1 2 3]))))))

(deftest core-merge-with-test
  (testing "merge-with resolves conflicts"
    (is (= {:a 3} (run-spell-full '(merge-with + {:a 1} {:a 2})))))
  (testing "merge-with with spell-fn"
    (is (= {:a 2} (run-spell-full '(do (defn add [a b] (+ a b))
                                        (merge-with add {:a 1} {:a 1})))))))

(deftest core-select-keys-test
  (testing "select-keys basic"
    (is (= {:a 1 :c 3} (run-spell-full '(select-keys {:a 1 :b 2 :c 3} [:a :c])))))
  (testing "select-keys missing key"
    (is (= {:a 1} (run-spell-full '(select-keys {:a 1 :b 2} [:a :z]))))))

(deftest core-reduce-kv-test
  (testing "reduce-kv over map"
    (is (= [[:a 1] [:b 2]]
           (run-spell-full '(reduce-kv (fn [acc k v] (conj acc [k v])) [] {:a 1 :b 2})))))
  (testing "reduce-kv with spell-fn"
    (is (= 3 (run-spell-full '(do (defn sum-vals [acc k v] (+ acc v))
                                   (reduce-kv sum-vals 0 {:a 1 :b 2})))))))

(deftest core-sorted-map-test
  (testing "sorted-map creates sorted map"
    (is (sorted? (run-spell-full '(sorted-map :a 1 :b 2)))))
  (testing "sorted-map iteration order"
    (is (= [:a :b :c] (vec (keys (run-spell-full '(sorted-map :c 3 :a 1 :b 2))))))))

(deftest core-sorted-set-test
  (testing "sorted-set creates sorted set"
    (is (sorted? (run-spell-full '(sorted-set 3 1 2)))))
  (testing "sorted-set iteration order"
    (is (= [1 2 3] (vec (run-spell-full '(sorted-set 3 1 2)))))))

(deftest core-type-predicates-test
  (testing "coll? on collections"
    (is (true? (run-spell-full '(coll? [1 2 3]))))
    (is (true? (run-spell-full '(coll? {:a 1}))))
    (is (false? (run-spell-full '(coll? 42)))))
  (testing "sequential?"
    (is (true? (run-spell-full '(sequential? [1 2 3]))))
    (is (false? (run-spell-full '(sequential? {:a 1})))))
  (testing "int?"
    (is (true? (run-spell-full '(int? 42))))
    (is (false? (run-spell-full '(int? 3.14))))))

;; =============================================================================
;; Function combinators (now in core-builtins)
;; =============================================================================

(deftest core-comp-test
  (testing "comp two builtins"
    (is (= 7 (run-spell-full '((comp inc inc) 5)))))
  (testing "comp with spell-fns"
    (is (= 11 (run-spell-full '(do (defn dbl [x] (* x 2))
                                   (defn add1 [x] (+ x 1))
                                   ((comp add1 dbl) 5))))))
  (testing "comp three fns"
    (is (= 12 (run-spell-full '((comp inc inc inc) 9))))))

(deftest core-partial-test
  (testing "partial with builtin"
    (is (= 15 (run-spell-full '((partial + 10) 5)))))
  (testing "partial with spell-fn"
    (is (= 15 (run-spell-full '(do (defn add [a b] (+ a b))
                                   ((partial add 10) 5))))))
  (testing "partial with multiple frozen args"
    (is (= 6 (run-spell-full '((partial + 1 2) 3))))))

(deftest core-juxt-test
  (testing "juxt with builtins"
    (is (= [6 4] (run-spell-full '((juxt inc dec) 5)))))
  (testing "juxt with spell-fns"
    (is (= [25 10] (run-spell-full '(do (defn sq [x] (* x x))
                                        (defn dbl [x] (* x 2))
                                        ((juxt sq dbl) 5))))))
  (testing "juxt three fns"
    (is (= [5 6 4] (run-spell-full '((juxt (fn [x] x) inc dec) 5))))))

(deftest core-complement-test
  (testing "complement negates predicate"
    (is (true? (run-spell-full '((complement (fn [x] (> x 10))) 5)))))
  (testing "complement with spell-fn"
    (is (false? (run-spell-full '(do (defn big? [x] (> x 3))
                                     ((complement big?) 5))))))
  (testing "complement of complement is identity"
    (is (true? (run-spell-full '((complement (complement (fn [x] (> x 3)))) 5))))))

(deftest core-constantly-test
  (testing "constantly returns same value"
    (is (= 42 (run-spell-full '((constantly 42) 1 2 3)))))
  (testing "constantly ignores all args"
    (is (= "hi" (run-spell-full '((constantly "hi")))))))

(deftest core-every-pred-test
  (testing "every-pred all pass"
    (is (true? (run-spell-full '((every-pred (fn [x] (> x 0)) (fn [x] (< x 10))) 5)))))
  (testing "every-pred one fails"
    (is (false? (run-spell-full '((every-pred (fn [x] (> x 0)) (fn [x] (< x 10))) 15)))))
  (testing "every-pred with spell-fns"
    (is (true? (run-spell-full '(do (defn pos? [x] (> x 0))
                                     (defn small? [x] (< x 100))
                                     ((every-pred pos? small?) 50)))))))

(deftest core-some-fn-test
  (testing "some-fn first matches"
    (is (true? (run-spell-full '((some-fn (fn [x] (> x 10)) (fn [x] (< x 0))) 15)))))
  (testing "some-fn second matches"
    (is (true? (run-spell-full '((some-fn (fn [x] (> x 10)) (fn [x] (< x 0))) -5)))))
  (testing "some-fn none match returns nil"
    (is (nil? (run-spell-full '((some-fn (fn [x] (> x 10)) (fn [x] (< x 0))) 5))))))

(deftest core-fnil-test
  (testing "fnil replaces nil with default"
    (is (= 5 (run-spell-full '((fnil + 0) nil 5)))))
  (testing "fnil with non-nil arg"
    (is (= 8 (run-spell-full '((fnil + 0) 3 5)))))
  (testing "fnil with spell-fn"
    (is (= 10 (run-spell-full '(do (defn add [a b] (+ a b))
                                    ((fnil add 5 5) nil nil)))))))

;; =============================================================================
;; Bitwise operations (now in core with bit- prefix)
;; =============================================================================

(deftest bit-and-test
  (testing "bit-and"
    (is (= 2 (run-spell-full '(bit-and 6 3))))))  ; 110 & 011 = 010

(deftest bit-or-test
  (testing "bit-or"
    (is (= 7 (run-spell-full '(bit-or 6 3))))))  ; 110 | 011 = 111

(deftest bit-xor-test
  (testing "bit-xor"
    (is (= 5 (run-spell-full '(bit-xor 6 3))))))  ; 110 ^ 011 = 101

(deftest bit-not-test
  (testing "bit-not"
    (is (= -7 (run-spell-full '(bit-not 6))))))  ; ~110 = ...11111001

(deftest bit-shift-left-test
  (testing "bit-shift-left"
    (is (= 24 (run-spell-full '(bit-shift-left 6 2))))))  ; 110 << 2 = 11000

(deftest bit-shift-right-test
  (testing "bit-shift-right"
    (is (= 3 (run-spell-full '(bit-shift-right 12 2))))))  ; 1100 >> 2 = 11

(deftest bit-set-clear-flip-test
  (testing "bit-set"
    (is (= 7 (run-spell-full '(bit-set 5 1)))))  ; 101 | (1 << 1) = 111
  (testing "bit-clear"
    (is (= 5 (run-spell-full '(bit-clear 7 1)))))  ; 111 & ~(1 << 1) = 101
  (testing "bit-flip"
    (is (= 7 (run-spell-full '(bit-flip 5 1))))))  ; 101 ^ (1 << 1) = 111

(deftest bit-test-test
  (testing "bit-test set"
    (is (true? (run-spell-full '(bit-test 5 0)))))  ; bit 0 of 101 is set
  (testing "bit-test not set"
    (is (false? (run-spell-full '(bit-test 5 1))))))  ; bit 1 of 101 is not set

;; =============================================================================
;; String functions (qualified access via strings/)
;; =============================================================================

(deftest strings-trim-test
  (testing "trim whitespace"
    (is (= "hello" (run-spell-full '(strings/trim "  hello  ")))))
  (testing "trim no-op"
    (is (= "hello" (run-spell-full '(strings/trim "hello"))))))

(deftest strings-split-test
  (testing "split by comma"
    (is (= ["a" "b" "c"] (run-spell-full '(strings/split "a,b,c" ",")))))
  (testing "split by whitespace"
    (is (= ["hello" "world"] (run-spell-full '(strings/split "hello world" "\\s+"))))))

(deftest strings-join-test
  (testing "join with separator"
    (is (= "a,b,c" (run-spell-full '(strings/join "," ["a" "b" "c"])))))
  (testing "join without separator"
    (is (= "abc" (run-spell-full '(strings/join ["a" "b" "c"])))))
  (testing "join empty collection"
    (is (= "" (run-spell-full '(strings/join "," []))))))

(deftest strings-includes?-test
  (testing "includes? found"
    (is (true? (run-spell-full '(strings/includes? "hello world" "world")))))
  (testing "includes? not found"
    (is (false? (run-spell-full '(strings/includes? "hello" "xyz"))))))

(deftest strings-replace-test
  (testing "replace occurrence"
    (is (= "hello clojure" (run-spell-full '(strings/replace "hello world" "world" "clojure")))))
  (testing "replace all occurrences"
    (is (= "b-b-b" (run-spell-full '(strings/replace "a-a-a" "a" "b"))))))

(deftest strings-subs-test
  (testing "subs from start"
    (is (= "llo" (run-spell-full '(strings/subs "hello" 2)))))
  (testing "subs with start and end"
    (is (= "ell" (run-spell-full '(strings/subs "hello" 1 4))))))

(deftest strings-index-of-test
  (testing "index-of found"
    (is (= 6 (run-spell-full '(strings/index-of "hello world" "world")))))
  (testing "index-of not found returns nil"
    (is (nil? (run-spell-full '(strings/index-of "hello" "xyz")))))
  (testing "index-of at start"
    (is (= 0 (run-spell-full '(strings/index-of "hello" "hello"))))))

(deftest strings-starts-with?-test
  (testing "starts-with? true"
    (is (true? (run-spell-full '(strings/starts-with? "hello world" "hello")))))
  (testing "starts-with? false"
    (is (false? (run-spell-full '(strings/starts-with? "hello" "world"))))))

(deftest strings-ends-with?-test
  (testing "ends-with? true"
    (is (true? (run-spell-full '(strings/ends-with? "hello world" "world")))))
  (testing "ends-with? false"
    (is (false? (run-spell-full '(strings/ends-with? "hello" "world"))))))

(deftest strings-blank?-test
  (testing "blank? on empty string"
    (is (true? (run-spell-full '(strings/blank? "")))))
  (testing "blank? on whitespace"
    (is (true? (run-spell-full '(strings/blank? "   ")))))
  (testing "blank? on nil"
    (is (true? (run-spell-full '(strings/blank? nil)))))
  (testing "blank? on non-empty"
    (is (false? (run-spell-full '(strings/blank? "hello"))))))

(deftest strings-split-lines-test
  (testing "split-lines basic"
    (is (= ["a" "b" "c"] (run-spell-full '(strings/split-lines "a\nb\nc"))))))

(deftest strings-capitalize-test
  (testing "capitalize basic"
    (is (= "Hello" (run-spell-full '(strings/capitalize "hello")))))
  (testing "capitalize already capitalized"
    (is (= "Hello" (run-spell-full '(strings/capitalize "Hello"))))))

(deftest strings-last-index-of-test
  (testing "last-index-of found"
    (is (= 6 (run-spell-full '(strings/last-index-of "hello hello" "hello")))))
  (testing "last-index-of not found"
    (is (nil? (run-spell-full '(strings/last-index-of "hello" "xyz"))))))

(deftest strings-re-find-test
  (testing "re-find match"
    (is (= "123" (run-spell-full '(strings/re-find "\\d+" "abc123def")))))
  (testing "re-find no match"
    (is (nil? (run-spell-full '(strings/re-find "\\d+" "abcdef"))))))

(deftest strings-re-matches-test
  (testing "re-matches full match"
    (is (= "123" (run-spell-full '(strings/re-matches "\\d+" "123")))))
  (testing "re-matches partial does not match"
    (is (nil? (run-spell-full '(strings/re-matches "\\d+" "abc123"))))))

(deftest strings-re-seq-test
  (testing "re-seq finds all matches"
    (is (= ["1" "2" "3"] (run-spell-full '(strings/re-seq "\\d" "a1b2c3")))))
  (testing "re-seq returns vector"
    (is (vector? (run-spell-full '(strings/re-seq "\\d" "123"))))))

(deftest strings-lower-case-test
  (testing "lower-case"
    (is (= "hello" (run-spell-full '(strings/lower-case "HELLO")))))
  (testing "lower-case already lower"
    (is (= "hello" (run-spell-full '(strings/lower-case "hello"))))))

(deftest strings-upper-case-test
  (testing "upper-case"
    (is (= "HELLO" (run-spell-full '(strings/upper-case "hello")))))
  (testing "upper-case already upper"
    (is (= "HELLO" (run-spell-full '(strings/upper-case "HELLO"))))))

;; =============================================================================
;; Math functions (qualified access via math/)
;; =============================================================================

(deftest math-sqrt-test
  (testing "sqrt of perfect square"
    (is (= 3.0 (run-spell-full '(math/sqrt 9)))))
  (testing "sqrt of zero"
    (is (= 0.0 (run-spell-full '(math/sqrt 0))))))

(deftest math-abs-test
  (testing "abs of negative"
    (is (= 5.0 (run-spell-full '(math/abs -5)))))
  (testing "abs of positive"
    (is (= 3.0 (run-spell-full '(math/abs 3))))))

(deftest math-floor-test
  (testing "floor rounds down"
    (is (= 3 (run-spell-full '(math/floor 3.7)))))
  (testing "floor of negative"
    (is (= -4 (run-spell-full '(math/floor -3.2))))))

(deftest math-ceil-test
  (testing "ceil rounds up"
    (is (= 4 (run-spell-full '(math/ceil 3.2)))))
  (testing "ceil of negative"
    (is (= -3 (run-spell-full '(math/ceil -3.7))))))

(deftest math-pow-test
  (testing "pow basic"
    (is (= 8.0 (run-spell-full '(math/pow 2 3)))))
  (testing "pow zero exponent"
    (is (= 1.0 (run-spell-full '(math/pow 5 0)))))
  (testing "pow negative exponent"
    (is (= 0.25 (run-spell-full '(math/pow 2 -2))))))

(deftest math-factorial-test
  (testing "factorial of 0"
    (is (= 1 (run-spell-full '(math/factorial 0)))))
  (testing "factorial of 5"
    (is (= 120 (run-spell-full '(math/factorial 5)))))
  (testing "factorial of 10"
    (is (= 3628800 (run-spell-full '(math/factorial 10))))))

(deftest math-gcd-test
  (testing "gcd basic"
    (is (= 6 (run-spell-full '(math/gcd 12 18)))))
  (testing "gcd coprime"
    (is (= 1 (run-spell-full '(math/gcd 7 13)))))
  (testing "gcd with zero"
    (is (= 5 (run-spell-full '(math/gcd 0 5))))))

(deftest math-lcm-test
  (testing "lcm basic"
    (is (= 36 (run-spell-full '(math/lcm 12 18)))))
  (testing "lcm coprime"
    (is (= 91 (run-spell-full '(math/lcm 7 13)))))
  (testing "lcm with zero"
    (is (= 0 (run-spell-full '(math/lcm 0 5))))))

(deftest math-constants-test
  (testing "PI is close to 3.14159"
    (is (< (Math/abs (- Math/PI (run-spell-full 'math/PI))) 0.0001)))
  (testing "E is close to 2.71828"
    (is (< (Math/abs (- Math/E (run-spell-full 'math/E))) 0.0001)))
  (testing "INF is infinite"
    (is (Double/isInfinite (run-spell-full 'math/INF))))
  (testing "NaN is NaN"
    (is (Double/isNaN (run-spell-full 'math/NaN)))))

(deftest math-NaN?-test
  (testing "NaN? on NaN"
    (is (true? (run-spell-full '(math/NaN? math/NaN)))))
  (testing "NaN? on number"
    (is (false? (run-spell-full '(math/NaN? 42))))))

(deftest math-infinite?-test
  (testing "infinite? on INF"
    (is (true? (run-spell-full '(math/infinite? math/INF)))))
  (testing "infinite? on NEG-INF"
    (is (true? (run-spell-full '(math/infinite? math/NEG-INF)))))
  (testing "infinite? on number"
    (is (false? (run-spell-full '(math/infinite? 42))))))

(deftest math-auto-promoting-test
  (testing "+' with large numbers"
    (is (= 20000000000N (run-spell-full '(math/+' 10000000000 10000000000)))))
  (testing "*' with large numbers"
    (is (number? (run-spell-full '(math/*' 1000000000 1000000000))))))

(deftest math-coercion-test
  (testing "float coercion"
    (is (float? (run-spell-full '(math/float 42)))))
  (testing "double coercion"
    (is (= 42.0 (run-spell-full '(math/double 42)))))
  (testing "long coercion"
    (is (= 42 (run-spell-full '(math/long 42.9)))))
  (testing "bigdec"
    (is (= 42.5M (run-spell-full '(math/bigdec 42.5))))))

;; =============================================================================
;; Patterns namespace structural checks (Spell-specific)
;; =============================================================================

(deftest patterns-structural-test
  (testing "patterns/check-result is a spell-fn"
    (let [check-result (:check-result stdlib/patterns)]
      (is (eval/spell-fn? check-result))
      (is (= ['prompt 'answer] (:params check-result)))))
  (testing "patterns has :docs"
    (is (map? (:docs stdlib/patterns)))
    (is (contains? (:docs stdlib/patterns) :check-result)))
  (testing "patterns accessible via qualified symbol"
    (is (eval/spell-fn? (run-spell-full 'patterns/check-result)))))

;; =============================================================================
;; all-namespaces contains expected keys
;; =============================================================================

(deftest all-namespaces-test
  (testing "all-namespaces has expected keys"
    (is (contains? stdlib/all-namespaces 'strings))
    (is (contains? stdlib/all-namespaces 'math))
    (is (contains? stdlib/all-namespaces 'patterns)))
  (testing "seqs, fns, bits NOT in all-namespaces (moved to core)"
    (is (not (contains? stdlib/all-namespaces 'seqs)))
    (is (not (contains? stdlib/all-namespaces 'fns)))
    (is (not (contains? stdlib/all-namespaces 'bits))))
  (testing "all-namespaces values are the actual namespace maps"
    (is (= stdlib/strings (get stdlib/all-namespaces 'strings)))
    (is (= stdlib/math (get stdlib/all-namespaces 'math)))
    (is (= stdlib/patterns (get stdlib/all-namespaces 'patterns))))
  (testing "each namespace has :docs"
    (doseq [[ns-name ns-map] stdlib/all-namespaces]
      (is (map? (:docs ns-map))
          (str ns-name " should have :docs")))))

;; =============================================================================
;; Integration: HOFs + function combinators (all in core now)
;; =============================================================================

(deftest integration-hof-with-combinators
  (testing "remove with complement"
    (is (= [4 5] (run-spell-full '(remove (complement (fn [x] (> x 3))) [1 2 3 4 5])))))
  (testing "sort-by with comp"
    ;; Sort strings by negative length (longest first)
    (is (= ["ccc" "bb" "a"]
           (run-spell-full '(sort-by (comp (fn [x] (- 0 x)) count) ["a" "bb" "ccc"])))))
  (testing "filter with partial"
    (is (= [6 7 8] (run-spell-full '(filter (partial < 5) [1 3 5 6 7 8]))))))
