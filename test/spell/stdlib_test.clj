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
  "Full builtins including all stdlib functions for testing."
  (merge eval/core-builtins
         (extract-ns-fns stdlib/strings)
         (extract-ns-fns stdlib/seqs)
         (extract-ns-fns stdlib/fns)))

(def test-env-with-namespaces
  "Environment with stdlib namespaces for qualified access testing."
  {'strings stdlib/strings
   'seqs stdlib/seqs
   'fns stdlib/fns
   'math stdlib/math
   'patterns stdlib/patterns})

(defn run-spell-full
  "Run spell with full builtins (including stdlib) and namespaces."
  [program]
  (binding [eval/*builtins* test-builtins]
    (first (spell-eval program test-env-with-namespaces))))

;; =============================================================================
;; 1. Seqs HOFs with spell-fns (qualified access)
;; =============================================================================

(deftest seqs-every?-test
  (testing "every? all match"
    (is (true? (run-spell-full '(seqs/every? (fn [x] (> x 0)) [1 2 3])))))
  (testing "every? some fail"
    (is (false? (run-spell-full '(seqs/every? (fn [x] (> x 2)) [1 2 3])))))
  (testing "every? with spell-fn"
    (is (true? (run-spell-full '(do (defn pos? [x] (> x 0))
                                    (seqs/every? pos? [1 2 3]))))))
  (testing "every? empty is vacuously true"
    (is (true? (run-spell-full '(seqs/every? (fn [x] false) []))))))

(deftest seqs-remove-test
  (testing "remove keeps non-matching"
    (is (= [1 2 3] (run-spell-full '(seqs/remove (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "remove with spell-fn"
    (is (= [1 2] (run-spell-full '(do (defn big? [x] (> x 2))
                                      (seqs/remove big? [1 2 3 4]))))))
  (testing "remove nothing"
    (is (= [1 2 3] (run-spell-full '(seqs/remove (fn [x] false) [1 2 3]))))))

(deftest seqs-mapcat-test
  (testing "mapcat flattens one level"
    (is (= [1 1 2 2 3 3] (run-spell-full '(seqs/mapcat (fn [x] [x x]) [1 2 3])))))
  (testing "mapcat with spell-fn"
    (is (= [1 2 2 3 3 4] (run-spell-full '(do (defn pair [x] [x (+ x 1)])
                                               (seqs/mapcat pair [1 2 3]))))))
  (testing "mapcat returns vector"
    (is (vector? (run-spell-full '(seqs/mapcat (fn [x] [x]) [1 2]))))))

(deftest seqs-sort-by-test
  (testing "sort-by with keyfn"
    (is (= [{:n 1} {:n 2} {:n 3}]
           (run-spell-full '(seqs/sort-by (fn [x] (get x :n)) [{:n 3} {:n 1} {:n 2}])))))
  (testing "sort-by with spell-fn"
    (is (= ["a" "bb" "ccc"]
           (run-spell-full '(do (defn len [s] (count s))
                                (seqs/sort-by len ["bb" "ccc" "a"]))))))
  (testing "sort-by on already sorted"
    (is (= [1 2 3] (run-spell-full '(seqs/sort-by (fn [x] x) [1 2 3]))))))

(deftest seqs-take-while-test
  (testing "take-while basic"
    (is (= [1 2] (run-spell-full '(seqs/take-while (fn [x] (< x 3)) [1 2 3 4])))))
  (testing "take-while with spell-fn"
    (is (= [1 2 3] (run-spell-full '(do (defn small? [x] (< x 4))
                                        (seqs/take-while small? [1 2 3 4 5]))))))
  (testing "take-while none match"
    (is (= [] (run-spell-full '(seqs/take-while (fn [x] (> x 10)) [1 2 3]))))))

(deftest seqs-drop-while-test
  (testing "drop-while basic"
    (is (= [3 4 5] (run-spell-full '(seqs/drop-while (fn [x] (< x 3)) [1 2 3 4 5])))))
  (testing "drop-while with spell-fn"
    (is (= [4 5] (run-spell-full '(do (defn small? [x] (< x 4))
                                      (seqs/drop-while small? [1 2 3 4 5]))))))
  (testing "drop-while all match"
    (is (= [] (run-spell-full '(seqs/drop-while (fn [x] (< x 10)) [1 2 3]))))))

(deftest seqs-find-first-test
  (testing "find-first returns matching element"
    (is (= 3 (run-spell-full '(seqs/find-first (fn [x] (> x 2)) [1 2 3 4])))))
  (testing "find-first returns nil when not found"
    (is (nil? (run-spell-full '(seqs/find-first (fn [x] (> x 10)) [1 2 3])))))
  (testing "find-first with spell-fn"
    (is (= 4 (run-spell-full '(do (defn big? [x] (> x 3))
                                  (seqs/find-first big? [1 2 3 4 5])))))))

(deftest seqs-not-any?-test
  (testing "not-any? all fail predicate"
    (is (true? (run-spell-full '(seqs/not-any? (fn [x] (> x 10)) [1 2 3])))))
  (testing "not-any? some pass"
    (is (false? (run-spell-full '(seqs/not-any? (fn [x] (> x 2)) [1 2 3])))))
  (testing "not-any? empty is true"
    (is (true? (run-spell-full '(seqs/not-any? (fn [x] true) []))))))

(deftest seqs-group-by-test
  (testing "group-by basic"
    (is (= {true [4 5] false [1 2 3]}
           (run-spell-full '(seqs/group-by (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "group-by with spell-fn"
    (is (= {"small" [1 2] "big" [4 5]}
           (run-spell-full '(do (defn size [x] (if (> x 3) "big" "small"))
                                (seqs/group-by size [1 2 4 5])))))))

;; =============================================================================
;; Additional seqs functions (non-HOF, qualified access)
;; =============================================================================

(deftest seqs-sort-test
  (testing "sort basic"
    (is (= [1 2 3] (run-spell-full '(seqs/sort [3 1 2])))))
  (testing "sort already sorted"
    (is (= [1 2 3] (run-spell-full '(seqs/sort [1 2 3]))))))

(deftest seqs-repeat-test
  (testing "repeat creates vector"
    (is (= [0 0 0] (run-spell-full '(seqs/repeat 3 0)))))
  (testing "repeat zero times"
    (is (= [] (run-spell-full '(seqs/repeat 0 "x"))))))

(deftest seqs-distinct-test
  (testing "distinct removes duplicates"
    (is (= [1 2 3] (run-spell-full '(seqs/distinct [1 2 1 3 2 1])))))
  (testing "distinct preserves order"
    (is (= [3 1 2] (run-spell-full '(seqs/distinct [3 1 2 3 1]))))))

(deftest seqs-flatten-test
  (testing "flatten nested"
    (is (= [1 2 3 4 5] (run-spell-full '(seqs/flatten [[1 2] [3 [4 5]]])))))
  (testing "flatten already flat"
    (is (= [1 2 3] (run-spell-full '(seqs/flatten [1 2 3]))))))

(deftest seqs-frequencies-test
  (testing "frequencies counts"
    (is (= {1 3 2 2 3 1} (run-spell-full '(seqs/frequencies [1 1 1 2 2 3]))))))

(deftest seqs-partition-test
  (testing "partition drops incomplete"
    (is (= [[1 2] [3 4]] (run-spell-full '(seqs/partition 2 [1 2 3 4 5])))))
  (testing "partition with step"
    (is (= [[1 2] [2 3] [3 4]] (run-spell-full '(seqs/partition 2 1 [1 2 3 4]))))))

(deftest seqs-partition-all-test
  (testing "partition-all includes partial"
    (is (= [[1 2] [3 4] [5]] (run-spell-full '(seqs/partition-all 2 [1 2 3 4 5])))))
  (testing "partition-all with step"
    (is (= [[1 2] [3 4] [5]] (run-spell-full '(seqs/partition-all 2 2 [1 2 3 4 5]))))))

(deftest seqs-interleave-test
  (testing "interleave two colls"
    (is (= [1 :a 2 :b 3 :c] (run-spell-full '(seqs/interleave [1 2 3] [:a :b :c])))))
  (testing "interleave uneven stops at shorter"
    (is (= [1 :a 2 :b] (run-spell-full '(seqs/interleave [1 2 3] [:a :b]))))))

(deftest seqs-interpose-test
  (testing "interpose separator"
    (is (= [1 0 2 0 3] (run-spell-full '(seqs/interpose 0 [1 2 3]))))))

(deftest seqs-zipmap-test
  (testing "zipmap basic"
    (is (= {:a 1 :b 2} (run-spell-full '(seqs/zipmap [:a :b] [1 2])))))
  (testing "zipmap uneven truncates"
    (is (= {:a 1 :b 2} (run-spell-full '(seqs/zipmap [:a :b :c] [1 2]))))))

(deftest seqs-split-at-test
  (testing "split-at basic"
    (is (= [[1 2] [3 4 5]] (run-spell-full '(seqs/split-at 2 [1 2 3 4 5])))))
  (testing "split-at at zero"
    (is (= [[] [1 2 3]] (run-spell-full '(seqs/split-at 0 [1 2 3]))))))

(deftest seqs-map-slice-test
  (testing "map-slice filters sorted map by key range"
    (let [map-slice (:map-slice stdlib/seqs)
          sm (into (sorted-map) {1 :a 2 :b 3 :c 4 :d})]
      (is (= {2 :b 3 :c} (map-slice sm 2 3))))))

;; =============================================================================
;; 2. String functions (qualified access)
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
;; 3. Math functions (qualified access)
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

;; =============================================================================
;; 4. Fns namespace (qualified access with spell-fns)
;; =============================================================================

(deftest fns-comp-test
  (testing "comp two builtins"
    (is (= 7 (run-spell-full '((fns/comp inc inc) 5)))))
  (testing "comp with spell-fns"
    (is (= 11 (run-spell-full '(do (defn dbl [x] (* x 2))
                                   (defn add1 [x] (+ x 1))
                                   ((fns/comp add1 dbl) 5))))))
  (testing "comp three fns"
    (is (= 12 (run-spell-full '((fns/comp inc inc inc) 9))))))

(deftest fns-partial-test
  (testing "partial with builtin"
    (is (= 15 (run-spell-full '((fns/partial + 10) 5)))))
  (testing "partial with spell-fn"
    (is (= 15 (run-spell-full '(do (defn add [a b] (+ a b))
                                   ((fns/partial add 10) 5))))))
  (testing "partial with multiple frozen args"
    (is (= 6 (run-spell-full '((fns/partial + 1 2) 3))))))

(deftest fns-juxt-test
  (testing "juxt with builtins"
    (is (= [6 4] (run-spell-full '((fns/juxt inc dec) 5)))))
  (testing "juxt with spell-fns"
    (is (= [25 10] (run-spell-full '(do (defn sq [x] (* x x))
                                        (defn dbl [x] (* x 2))
                                        ((fns/juxt sq dbl) 5))))))
  (testing "juxt three fns"
    (is (= [5 6 4] (run-spell-full '((fns/juxt (fn [x] x) inc dec) 5))))))

(deftest fns-complement-test
  (testing "complement negates predicate"
    (is (true? (run-spell-full '((fns/complement (fn [x] (> x 10))) 5)))))
  (testing "complement with spell-fn"
    (is (false? (run-spell-full '(do (defn big? [x] (> x 3))
                                     ((fns/complement big?) 5))))))
  (testing "complement of complement is identity"
    (is (true? (run-spell-full '((fns/complement (fns/complement (fn [x] (> x 3)))) 5))))))

;; =============================================================================
;; 5. Patterns namespace structural checks
;; =============================================================================

(deftest patterns-structural-test
  (testing "patterns/call-now is a spell-fn"
    (let [call-now (:call-now stdlib/patterns)]
      (is (eval/spell-fn? call-now))
      (is (= ['result 'name] (:params call-now)))))
  (testing "patterns/check-result is a spell-fn"
    (let [check-result (:check-result stdlib/patterns)]
      (is (eval/spell-fn? check-result))
      (is (= ['prompt 'answer] (:params check-result)))))
  (testing "patterns has :docs"
    (is (map? (:docs stdlib/patterns)))
    (is (contains? (:docs stdlib/patterns) :call-now))
    (is (contains? (:docs stdlib/patterns) :check-result)))
  (testing "patterns accessible via qualified symbol"
    (is (eval/spell-fn? (run-spell-full 'patterns/call-now)))
    (is (eval/spell-fn? (run-spell-full 'patterns/check-result)))))

;; =============================================================================
;; 6. all-namespaces contains expected keys
;; =============================================================================

(deftest all-namespaces-test
  (testing "all-namespaces has expected keys"
    (is (contains? stdlib/all-namespaces 'strings))
    (is (contains? stdlib/all-namespaces 'seqs))
    (is (contains? stdlib/all-namespaces 'fns))
    (is (contains? stdlib/all-namespaces 'math))
    (is (contains? stdlib/all-namespaces 'patterns)))
  (testing "all-namespaces values are the actual namespace maps"
    (is (= stdlib/strings (get stdlib/all-namespaces 'strings)))
    (is (= stdlib/seqs (get stdlib/all-namespaces 'seqs)))
    (is (= stdlib/fns (get stdlib/all-namespaces 'fns)))
    (is (= stdlib/math (get stdlib/all-namespaces 'math)))
    (is (= stdlib/patterns (get stdlib/all-namespaces 'patterns))))
  (testing "each namespace has :docs"
    (doseq [[ns-name ns-map] stdlib/all-namespaces]
      (is (map? (:docs ns-map))
          (str ns-name " should have :docs")))))

;; =============================================================================
;; Integration: HOFs + fns namespace combinators
;; =============================================================================

(deftest integration-hof-with-fns-combinators
  (testing "seqs/remove with fns/complement"
    (is (= [4 5] (run-spell-full '(seqs/remove (fns/complement (fn [x] (> x 3))) [1 2 3 4 5])))))
  (testing "seqs/sort-by with fns/comp"
    ;; Sort strings by negative length (longest first)
    (is (= ["ccc" "bb" "a"]
           (run-spell-full '(seqs/sort-by (fns/comp (fn [x] (- 0 x)) count) ["a" "bb" "ccc"])))))
  (testing "seqs/find-first with fns/partial"
    (is (= 6 (run-spell-full '(seqs/find-first (fns/partial < 5) [1 3 5 6 8]))))))
