(ns spell.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.eval :as eval :refer [spell-eval run-spell]]
            [spell.stdlib :as stdlib]
            [spell.core :as core]))

;; =============================================================================
;; Test helpers - include stdlib functions directly for testing
;; =============================================================================

(defn- extract-ns-fns
  "Extract all functions from a namespace map (excluding :docs)."
  [ns-map]
  (into {}
    (for [[k v] ns-map
          :when (not= k :docs)]
      [(symbol (name k)) v])))

(def test-builtins
  "Full builtins including all stdlib functions for testing.
   Note: seqs, fns, and bit- ops are now in core-builtins."
  (merge eval/core-builtins
         (extract-ns-fns stdlib/strings)))

(def test-env-with-namespaces
  "Environment with stdlib namespaces for qualified access testing."
  {'strings stdlib/strings
   'math stdlib/math
   'patterns stdlib/patterns})

(defn run-spell-full
  "Run spell with full builtins (including stdlib) for testing."
  [program]
  (binding [eval/*builtins* test-builtins]
    (first (spell-eval program test-env-with-namespaces))))

;; =============================================================================
;; Oracle-based tests: spell-eval should match eval
;; =============================================================================

(def should-match-eval
  '[;; arithmetic
    (+ 1 2)
    (* 3 4)
    (- 5 3)
    (/ 6 3)
    (+ 1 2 3 4 5)
    (* (+ 1 2) (- 5 3))

    ;; comparisons
    (> 5 3)
    (< 5 3)
    (= 1 1)
    (= 1 2)
    (<= 3 3)
    (>= 5 3)
    (not= 1 2)

    ;; strings
    (str "hello" " " "world")
    (str)
    (str 1 2 3)

    ;; list operations
    (list 1 2 3)
    (first (list 1 2 3))
    (rest (list 1 2 3))
    (cons 0 (list 1 2 3))
    (conj [1 2] 3)
    (count [1 2 3])
    (empty? [])
    (empty? [1])
    (nil? nil)
    (nil? 1)

    ;; do
    (do (+ 1 2))
    (do (+ 1 2) (* 3 4))
    (do)

    ;; if
    (if true 1 2)
    (if false 1 2)
    (if (> 5 3) "yes" "no")
    (if (< 5 3) "yes" "no")
    (if false 1)
    (if nil "yes" "no")
    (if "" "truthy" "falsey")  ; non-empty string is truthy

    ;; quote
    (quote (+ 1 2))
    (quote x)
    (quote [1 2 3])

    ;; self-evaluating literals
    "hello"
    42
    true
    false
    nil
    :keyword

    ;; vectors
    [1 2 3]
    []
    [[1 2] [3 4]]
    [1 {:a 2} [3 4 5]]

    ;; maps
    {:a 1}
    {}
    {:a 1 :b 2}
    {:outer {:inner 42}}

    ;; vectors as functions
    ([10 20 30 40] 0)
    ([10 20 30 40] 3)
    ([:a :b :c] 1)

    ;; maps as functions
    ({:a 1 :b 2} :a)
    ({:a 1 :b 2} :c)
    ({:a 1 :b 2} :c :default)

    ;; keywords as functions
    (:a {:a 1 :b 2})
    (:c {:a 1 :b 2} :not-found)

    ;; get
    (get [10 20 30] 1)
    (get {:a 1} :a)
    (get {:a 1} :b :missing)

    ;; assoc
    (assoc {:a 1} :b 2)
    (assoc [1 2 3] 1 :x)

    ;; nested/computed
    [(+ 1 2) (* 3 4)]
    {:sum (+ 1 2) :product (* 3 4)}
    (first [(+ 1 2) 3])
    (get {:a (+ 1 2)} :a)

    ;; inc/dec
    (inc 5)
    (dec 5)

    ;; not
    (not true)
    (not false)
    (not nil)

    ;; type predicates
    (string? "hello")
    (string? 42)
    (number? 42)
    (number? "hello")
    (list? '(1 2))
    (list? [1 2])
    (vector? [1 2])
    (vector? '(1 2))

    ;; more collections
    (nth [10 20 30] 1)
    (last [1 2 3])
    (into [] '(1 2 3))
    (reverse [1 2 3])
    (sort [3 1 2])
    (range 5)
    (repeat 3 "x")
    (keys {:a 1})
    (vals {:a 1})
    (concat [1 2] [3 4])

    ;; apply
    (apply + [1 2 3])
    (apply + 1 [2 3])
    (apply str ["a" "b" "c"])])

(deftest spell-eval-matches-eval
  (doseq [expr should-match-eval]
    (testing (str expr)
      (is (= (eval expr) (run-spell-full expr))))))

;; =============================================================================
;; Expressions that should throw
;; =============================================================================

(def should-throw
  '[;; unbound symbols
    x
    (+ x 1)
    (foo 1 2)

    ;; IO
    (slurp "foo.txt")
    (spit "foo.txt" "data")
    (println "hello")

    ;; reflection/interop
    (.toString 42)
    (System/getenv "PATH")

    ;; non-whitelisted clojure.core
    (atom 0)
    (deref (atom 0))
    (read-string "(+ 1 2)")])

(deftest spell-eval-throws
  (doseq [expr should-throw]
    (testing (str expr)
      (is (thrown? Exception (run-spell expr))))))

;; =============================================================================
;; New builtins (not in should-match-eval because API differs from clojure.core)
;; =============================================================================

(deftest string-builtins
  (testing "replace"
    (is (= "hello clojure" (run-spell-full '(replace "hello world" "world" "clojure")))))
  (testing "split"
    (is (= ["a" "b" "c"] (run-spell-full '(split "a,b,c" ",")))))
  (testing "join"
    (is (= "a,b,c" (run-spell-full '(join "," ["a" "b" "c"]))))
    (is (= "abc" (run-spell-full '(join ["a" "b" "c"])))))
  (testing "lower-case"
    (is (= "hello" (run-spell-full '(lower-case "HELLO")))))
  (testing "upper-case"
    (is (= "HELLO" (run-spell-full '(upper-case "hello"))))))

(deftest regex-builtins
  (testing "re-find"
    (is (= "123" (run-spell-full '(re-find "\\d+" "abc123def")))))
  (testing "re-matches"
    (is (= "123" (run-spell-full '(re-matches "\\d+" "123"))))
    (is (nil? (run-spell-full '(re-matches "\\d+" "abc123"))))))

(deftest type-predicates
  (testing "map? excludes spell-fns"
    (is (true? (run-spell '(map? {:a 1}))))
    (is (false? (run-spell '(map? (fn [x] x))))))
  (testing "fn? includes spell-fns"
    (is (true? (run-spell '(fn? +))))
    (is (true? (run-spell '(fn? (fn [x] x)))))))

(deftest apply-with-spell-fn
  (testing "apply with spell function"
    (is (= 9 (run-spell-full '(do (defn square [x] (* x x)) (apply square [3]))))))
  (testing "apply with multi-arg spell function"
    (is (= 7 (run-spell-full '(do (defn add [a b] (+ a b)) (apply add [3 4])))))))

;; =============================================================================
;; Higher-order collection functions
;; =============================================================================

(deftest map-builtin
  (testing "map with clojure fn"
    (is (= [2 3 4] (run-spell '(map inc [1 2 3])))))
  (testing "map with spell fn"
    (is (= [1 4 9] (run-spell-full '(do (defn sq [x] (* x x)) (map sq [1 2 3]))))))
  (testing "map with inline fn"
    (is (= [2 4 6] (run-spell '(map (fn [x] (* x 2)) [1 2 3])))))
  (testing "map returns vector"
    (is (vector? (run-spell '(map inc [1 2 3])))))
  (testing "map empty"
    (is (= [] (run-spell '(map inc []))))))

(deftest filter-builtin
  (testing "filter with predicate fn"
    (is (= [3 4 5] (run-spell '(filter (fn [x] (> x 2)) [1 2 3 4 5])))))
  (testing "filter with spell fn"
    (is (= [4 5 6] (run-spell-full '(do (defn big [x] (> x 3)) (filter big [1 2 3 4 5 6]))))))
  (testing "filter truthy values"
    (is (= [1 2 3] (run-spell '(filter (fn [x] x) [nil 1 nil 2 3 nil])))))
  (testing "filter returns vector"
    (is (vector? (run-spell '(filter (fn [x] x) [1 2 3])))))
  (testing "filter empty result"
    (is (= [] (run-spell '(filter (fn [x] false) [1 2 3]))))))

(deftest remove-builtin
  (testing "remove keeps non-matching"
    (is (= [1 2 3] (run-spell-full '(remove (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "remove with spell fn"
    (is (= [1 2] (run-spell-full '(do (defn big [x] (> x 2)) (remove big [1 2 3 4])))))))

(deftest reduce-builtin
  (testing "reduce with clojure fn"
    (is (= 6 (run-spell '(reduce + [1 2 3])))))
  (testing "reduce with init"
    (is (= 10 (run-spell '(reduce + 4 [1 2 3])))))
  (testing "reduce with spell fn"
    (is (= 24 (run-spell '(do (defn mult [a b] (* a b)) (reduce mult [1 2 3 4]))))))
  (testing "reduce with spell fn and init"
    (is (= 120 (run-spell '(do (defn mult [a b] (* a b)) (reduce mult 5 [1 2 3 4]))))))
  (testing "reduce empty with init"
    (is (= 0 (run-spell '(reduce + 0 []))))))

(deftest some-builtin
  (testing "some finds first truthy result"
    (is (= 3 (run-spell-full '(some (fn [x] (if (> x 2) x nil)) [1 2 3 4])))))
  (testing "some returns nil when no match"
    (is (nil? (run-spell-full '(some (fn [x] (if (> x 10) x nil)) [1 2 3])))))
  (testing "some with spell fn"
    (is (= 4 (run-spell-full '(do (defn find-big [x] (if (> x 3) x nil)) (some find-big [1 2 3 4 5]))))))
  (testing "some empty"
    (is (nil? (run-spell-full '(some (fn [x] x) []))))))

(deftest every?-builtin
  (testing "every? all match"
    (is (true? (run-spell-full '(every? (fn [x] (> x 0)) [1 2 3])))))
  (testing "every? some fail"
    (is (false? (run-spell-full '(every? (fn [x] (> x 2)) [1 2 3])))))
  (testing "every? with spell fn"
    (is (true? (run-spell-full '(do (defn pos [x] (> x 0)) (every? pos [1 2 3]))))))
  (testing "every? empty is true"
    (is (true? (run-spell-full '(every? (fn [x] false) []))))))

(deftest keep-builtin
  (testing "keep removes nils"
    (is (= [3 4] (run-spell-full '(keep (fn [x] (if (> x 2) x nil)) [1 2 3 4])))))
  (testing "keep with spell fn"
    (is (= [9 16] (run-spell-full '(do (defn sq-if-big [x] (if (> x 2) (* x x) nil)) (keep sq-if-big [1 2 3 4]))))))
  (testing "keep empty"
    (is (= [] (run-spell-full '(keep (fn [x] nil) [1 2 3]))))))

(deftest mapcat-builtin
  (testing "mapcat flattens"
    (is (= [1 1 2 2 3 3] (run-spell-full '(mapcat (fn [x] [x x]) [1 2 3])))))
  (testing "mapcat with spell fn"
    (is (= [1 2 2 3 3 4] (run-spell-full '(do (defn expand [x] [x (+ x 1)]) (mapcat expand [1 2 3]))))))
  (testing "mapcat returns vector"
    (is (vector? (run-spell-full '(mapcat (fn [x] [x]) [1 2]))))))

(deftest take-while-builtin
  (testing "take-while basic"
    (is (= [1 2] (run-spell-full '(take-while (fn [x] (< x 3)) [1 2 3 4])))))
  (testing "take-while none match"
    (is (= [] (run-spell-full '(take-while (fn [x] (> x 10)) [1 2 3])))))
  (testing "take-while with spell fn"
    (is (= [1 2 3] (run-spell-full '(do (defn small [x] (< x 4)) (take-while small [1 2 3 4 5])))))))

(deftest drop-while-builtin
  (testing "drop-while basic"
    (is (= [3 4 5] (run-spell-full '(drop-while (fn [x] (< x 3)) [1 2 3 4 5])))))
  (testing "drop-while all match"
    (is (= [] (run-spell-full '(drop-while (fn [x] (< x 10)) [1 2 3])))))
  (testing "drop-while with spell fn"
    (is (= [4 5] (run-spell-full '(do (defn small [x] (< x 4)) (drop-while small [1 2 3 4 5])))))))

(deftest group-by-builtin
  (testing "group-by basic"
    (is (= {true [4 5] false [1 2 3]}
           (run-spell-full '(group-by (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "group-by with spell fn"
    (is (= {"small" [1 2] "big" [4 5]}
           (run-spell-full '(do (defn size [x] (if (> x 3) "big" "small")) (group-by size [1 2 4 5])))))))

(deftest sort-by-builtin
  (testing "sort-by basic"
    (is (= [{:n 1} {:n 2} {:n 3}]
           (run-spell-full '(sort-by (fn [x] (get x :n)) [{:n 3} {:n 1} {:n 2}])))))
  (testing "sort-by with spell fn"
    (is (= ["a" "bb" "ccc"]
           (run-spell-full '(do (defn len [s] (count s)) (sort-by len ["bb" "ccc" "a"])))))))

(deftest find-first-builtin
  (testing "find-first returns element"
    (is (= 3 (run-spell-full '(find-first (fn [x] (> x 2)) [1 2 3 4])))))
  (testing "find-first returns nil when not found"
    (is (nil? (run-spell-full '(find-first (fn [x] (> x 10)) [1 2 3])))))
  (testing "find-first with spell fn"
    (is (= 4 (run-spell-full '(do (defn big [x] (> x 3)) (find-first big [1 2 3 4 5])))))))

(deftest not-any?-builtin
  (testing "not-any? all fail predicate"
    (is (true? (run-spell-full '(not-any? (fn [x] (> x 10)) [1 2 3])))))
  (testing "not-any? some pass predicate"
    (is (false? (run-spell-full '(not-any? (fn [x] (> x 2)) [1 2 3])))))
  (testing "not-any? empty is true"
    (is (true? (run-spell-full '(not-any? (fn [x] true) []))))))

(deftest distinct-builtin
  (testing "distinct removes duplicates"
    (is (= [1 2 3] (run-spell-full '(distinct [1 2 1 3 2 1])))))
  (testing "distinct preserves order"
    (is (= [3 1 2] (run-spell-full '(distinct [3 1 2 3 1]))))))

(deftest flatten-builtin
  (testing "flatten nested"
    (is (= [1 2 3 4 5] (run-spell-full '(flatten [[1 2] [3 [4 5]]])))))
  (testing "flatten already flat"
    (is (= [1 2 3] (run-spell-full '(flatten [1 2 3]))))))

(deftest frequencies-builtin
  (testing "frequencies counts"
    (is (= {1 3 2 2 3 1} (run-spell-full '(frequencies [1 1 1 2 2 3]))))))

(deftest partition-builtin
  (testing "partition basic"
    (is (= [[1 2] [3 4]] (run-spell-full '(partition 2 [1 2 3 4 5])))))
  (testing "partition with step"
    (is (= [[1 2] [2 3] [3 4]] (run-spell-full '(partition 2 1 [1 2 3 4]))))))

(deftest partition-all-builtin
  (testing "partition-all includes partial"
    (is (= [[1 2] [3 4] [5]] (run-spell-full '(partition-all 2 [1 2 3 4 5])))))
  (testing "partition-all with step"
    (is (= [[1 2] [3 4] [5]] (run-spell-full '(partition-all 2 2 [1 2 3 4 5]))))))

(deftest interleave-builtin
  (testing "interleave two colls"
    (is (= [1 :a 2 :b 3 :c] (run-spell-full '(interleave [1 2 3] [:a :b :c])))))
  (testing "interleave uneven"
    (is (= [1 :a 2 :b] (run-spell-full '(interleave [1 2 3] [:a :b]))))))

(deftest interpose-builtin
  (testing "interpose separator"
    (is (= [1 0 2 0 3] (run-spell-full '(interpose 0 [1 2 3]))))))

(deftest zipmap-builtin
  (testing "zipmap basic"
    (is (= {:a 1 :b 2} (run-spell-full '(zipmap [:a :b] [1 2])))))
  (testing "zipmap uneven"
    (is (= {:a 1 :b 2} (run-spell-full '(zipmap [:a :b :c] [1 2]))))))

(deftest take-drop-builtin
  (testing "take"
    (is (= [1 2 3] (run-spell '(take 3 [1 2 3 4 5])))))
  (testing "drop"
    (is (= [4 5] (run-spell '(drop 3 [1 2 3 4 5])))))
  (testing "split-at"
    (is (= [[1 2] [3 4 5]] (run-spell-full '(split-at 2 [1 2 3 4 5]))))))

(deftest comp-builtin
  (testing "comp two fns"
    (is (= 7 (run-spell-full '((comp inc inc) 5)))))
  (testing "comp with spell-fns"
    (is (= 11 (run-spell-full '(do (defn dbl [x] (* x 2))
                              (defn add1 [x] (+ x 1))
                              ((comp add1 dbl) 5))))))
  (testing "comp three fns"
    (is (= 12 (run-spell-full '((comp inc inc inc) 9))))))

(deftest partial-builtin
  (testing "partial with clojure fn"
    (is (= 15 (run-spell-full '((partial + 10) 5)))))
  (testing "partial with spell-fn"
    (is (= 15 (run-spell-full '(do (defn add [a b] (+ a b))
                              ((partial add 10) 5))))))
  (testing "partial multiple args"
    (is (= 6 (run-spell-full '((partial + 1 2) 3))))))

(deftest juxt-builtin
  (testing "juxt basic"
    (is (= [6 4] (run-spell-full '((juxt inc dec) 5)))))
  (testing "juxt with spell-fns"
    (is (= [25 10] (run-spell-full '(do (defn sq [x] (* x x))
                                   (defn dbl [x] (* x 2))
                                   ((juxt sq dbl) 5))))))
  (testing "juxt three fns"
    (is (= [5 6 4] (run-spell-full '((juxt (fn [x] x) inc dec) 5))))))

(deftest complement-builtin
  (testing "complement basic"
    (is (true? (run-spell-full '((complement (fn [x] (> x 10))) 5)))))
  (testing "complement with spell-fn"
    (is (false? (run-spell-full '(do (defn big [x] (> x 3))
                                ((complement big) 5)))))))

;; =============================================================================
;; Env threading tests (need to check returned env, not just value)
;; =============================================================================

(deftest env-threading
  (testing "def returns value and updates env"
    (is (= [5 {'x 5}] (spell-eval '(def x 5) {}))))

  (testing "def chains in do"
    (let [[val env] (spell-eval '(do (def x 1) (+ x 2)) {})]
      (is (= 3 val))
      (is (= 1 (env 'x))))
    (let [[val env] (spell-eval '(do (def x 1) (def y 2) (+ x y)) {})]
      (is (= 3 val))
      (is (= {'x 1 'y 2} env))))

  (testing "env threads through function arguments"
    (let [[val env] (spell-eval '(+ (def x 1) x) {})]
      (is (= 2 val))
      (is (= {'x 1} env))))

  (testing "env threads through vectors"
    (let [[val env] (spell-eval '[1 (def x 2) x] {})]
      (is (= [1 2 2] val))
      (is (= {'x 2} env))))

  (testing "env threads through maps"
    (let [[val env] (spell-eval '{:a (def x 3)} {})]
      (is (= {:a 3} val))
      (is (= {'x 3} env))))

  (testing "env threads through nested structures"
    (let [[val env] (spell-eval '(do (def v [1 (def y 2)]) (+ (first v) y)) {})]
      (is (= 3 val))
      (is (= 2 (env 'y)))
      (is (= [1 2] (env 'v))))))

;; =============================================================================
;; Env input tests (passing bindings into spell-eval)
;; =============================================================================

(deftest env-input
  (testing "symbol in env resolves"
    (is (= 11 (first (spell-eval '(+ x 1) {'x 10})))))

  (testing "env shadows builtins"
    ;; If you shadow + with a value, using it as function fails
    (is (thrown? Exception (first (spell-eval '(+ 1 2) {'+ 5})))))

  (testing "user functions in env work"
    (let [square (fn [x] (* x x))]
      (is (= 9 (first (spell-eval '(square 3) {'square square})))))))

;; =============================================================================
;; New special forms tests
;; =============================================================================

(deftest let-form
  (testing "basic let"
    (is (= 3 (run-spell '(let [x 1 y 2] (+ x y))))))

  (testing "let shadows outer"
    (is (= 10 (first (spell-eval '(let [x 10] x) {'x 1})))))

  (testing "let bindings don't escape"
    (let [[val env] (spell-eval '(let [x 1] x) {})]
      (is (= 1 val))
      (is (= {} env))))

  (testing "let with multiple body exprs"
    (is (= 3 (run-spell '(let [x 1] (+ x 1) (+ x 2))))))

  (testing "empty let"
    (is (nil? (run-spell '(let [] ))))))

(deftest fn-form
  (testing "basic fn"
    (is (= 4 (run-spell '((fn [x] (* x x)) 2)))))

  (testing "fn with multiple params"
    (is (= 5 (run-spell '((fn [a b] (+ a b)) 2 3)))))

  (testing "fn closure captures env"
    (is (= 11 (run-spell '(let [y 10] ((fn [x] (+ x y)) 1))))))

  (testing "fn with multiple body exprs"
    (is (= 3 (run-spell '((fn [x] (+ x 1) (+ x 2)) 1)))))

  (testing "dynamic scoping - function sees caller's env"
    ;; Define f when y=10, change y to 100, f should see y=100 (dynamic)
    (let [env1 {'y 10}
          [_ env2] (spell-eval '(defn f [x] (+ x y)) env1)
          env3 (assoc env2 'y 100)
          [result _] (spell-eval '(f 5) env3)]
      (is (= 105 result) "dynamic scoping: f should see y=100 from call site")))

  (testing "dynamic scoping - function sees def'd vars at call site"
    ;; y is not defined when f is defined, but is defined before f is called
    (is (= 8 (run-spell '(do (defn f [x] (+ x y)) (def y 5) (f 3)))))))

(deftest defn-form
  (testing "defn creates function in env"
    (let [[val env] (spell-eval '(defn square [x] (* x x)) {})]
      (is (eval/spell-fn? val))
      (is (eval/spell-fn? (env 'square)))))

  (testing "defn function can be called"
    (is (= 9 (run-spell-full '(do (defn square [x] (* x x)) (square 3))))))

  (testing "defn with multiple params"
    (is (= 7 (run-spell-full '(do (defn add [a b] (+ a b)) (add 3 4)))))))

(deftest cond-form
  (testing "cond first match"
    (is (= 1 (run-spell '(cond true 1 true 2)))))

  (testing "cond second match"
    (is (= 2 (run-spell '(cond false 1 true 2)))))

  (testing "cond :else"
    (is (= 3 (run-spell '(cond false 1 false 2 :else 3)))))

  (testing "cond no match"
    (is (nil? (run-spell '(cond false 1 false 2)))))

  (testing "cond evaluates test"
    (is (= "big" (run-spell '(cond (> 5 3) "big" :else "small"))))))

(deftest and-form
  (testing "and all true"
    (is (= 3 (run-spell '(and 1 2 3)))))

  (testing "and short-circuits"
    (is (= false (run-spell '(and 1 false 3)))))

  (testing "and empty"
    (is (= true (run-spell '(and)))))

  (testing "and single"
    (is (= 5 (run-spell '(and 5))))))

(deftest or-form
  (testing "or first truthy"
    (is (= 1 (run-spell '(or 1 2 3)))))

  (testing "or skips falsy"
    (is (= 2 (run-spell '(or false nil 2 3)))))

  (testing "or all falsy"
    (is (= nil (run-spell '(or false nil)))))

  (testing "or empty"
    (is (nil? (run-spell '(or)))))

  (testing "or single"
    (is (= 5 (run-spell '(or 5))))))

(deftest thread-first-form
  (testing "basic thread-first"
    (is (= 5 (run-spell '(-> 1 (+ 2) (+ 2))))))

  (testing "thread-first with bare symbols"
    (is (= 2 (run-spell '(-> [1 2 3] first inc)))))

  (testing "thread-first with assoc"
    (is (= {:a 1 :b 2} (run-spell '(-> {} (assoc :a 1) (assoc :b 2))))))

  (testing "thread-first single form"
    (is (= 2 (run-spell '(-> 1 inc)))))

  (testing "thread-first no forms"
    (is (= 42 (run-spell '(-> 42))))))

(deftest thread-last-form
  (testing "basic thread-last"
    (is (= [2 4 6] (run-spell '(->> [1 2 3] (map (fn [x] (* x 2))))))))

  (testing "thread-last with reduce"
    (is (= 6 (run-spell '(->> [1 2 3] (reduce +))))))

  (testing "thread-last pipeline"
    ;; [1 2 3 4 5] -> filter < 5 -> [1 2 3 4] -> map inc -> [2 3 4 5] -> take 2 -> [2 3]
    (is (= [2 3] (run-spell '(->> [1 2 3 4 5]
                                  (filter (fn [x] (< x 5)))
                                  (map inc)
                                  (take 2))))))

  (testing "thread-last with bare symbols"
    (is (= 3 (run-spell '(->> [1 2 3] last)))))

  (testing "thread-last no forms"
    (is (= 42 (run-spell '(->> 42))))))

;; =============================================================================
;; spell-eval builtin tests
;; =============================================================================

(deftest spell-eval-builtin
  (testing "evaluate quoted expression"
    (is (= 3 (run-spell '(spell-eval '(+ 1 2))))))

  (testing "evaluate self-evaluating value"
    (is (= 42 (run-spell '(spell-eval 42))))
    (is (= "hi" (run-spell '(spell-eval "hi")))))

  (testing "evaluate quoted program"
    (is (= 6 (run-spell '(spell-eval '(do (def x 3) (+ x 3)))))))

  (testing "auto-expands free variables from caller env"
    ;; spell-eval auto-expands, so outer x is inlined before evaluation in {}
    (is (= 11 (run-spell '(do (def x 10) (spell-eval '(+ x 1)))))))

  (testing "internal bindings in spell-eval are independent"
    ;; def inside spell-eval's argument doesn't leak to outer env
    (is (= 42 (run-spell '(do (spell-eval '(do (def y 99)))
                               (def y 42) y))))))

;; =============================================================================
;; Expand tests
;; =============================================================================

(deftest expand-test
  (testing "basic expansion - substitutes free variables"
    (let [[val _] (spell-eval '(do (def x 42) (expand '(+ x 1))) {})]
      (is (= '(+ 42 1) val))))

  (testing "multiple free variables"
    (let [[val _] (spell-eval '(do (def x 10) (def y 20) (expand '(+ x y))) {})]
      (is (= '(+ 10 20) val))))

  (testing "no free variables - unchanged"
    (let [[val _] (spell-eval '(expand '(+ 1 2)) {})]
      (is (= '(+ 1 2) val))))

  (testing "expansion with computed values"
    (let [[val _] (spell-eval '(do (def x (+ 1 2)) (expand '(* x x))) {})]
      (is (= '(* 3 3) val))))

  (testing "expansion respects quotes"
    (let [[val _] (spell-eval '(do (def x 42) (expand '(list x (quote x)))) {})]
      (is (= '(list 42 (quote x)) val))))

  (testing "let-bound vars not expanded"
    (let [[val _] (spell-eval '(do (def x 100) (expand '(let [x 1] (+ x y)))) {})]
      (is (= '(let [x 1] (+ x y)) val))))

  (testing "fn params not expanded"
    (let [[val _] (spell-eval '(do (def x 100) (def y 200) (expand '(fn [x] (+ x y)))) {})]
      (is (= '(fn [x] (+ x 200)) val))))

  (testing "partial expansion - only defined vars substituted"
    (let [[val _] (spell-eval '(do (def x 10) (expand '(+ x y))) {})]
      (is (= '(+ 10 y) val))))

  (testing "expanded result is evaluable"
    (let [[expanded _] (spell-eval '(do (def x 42) (expand '(+ x 1))) {})]
      (is (= 43 (run-spell expanded)))))

  (testing "list values get quoted for portability"
    (let [[val _] (spell-eval '(do (def xs '(1 2 3)) (expand '(first xs))) {})]
      (is (= '(first (quote (1 2 3))) val))
      (is (= 1 (run-spell val)))))

  (testing "expansion uses env bindings"
    (let [[val _] (spell-eval '(expand '(+ x 1)) {'x 99})]
      (is (= '(+ 99 1) val))))

  (testing "symbol values get quoted"
    (let [[val _] (spell-eval '(do (def s 'hello) (expand '(list s))) {})]
      (is (= '(list (quote hello)) val))))

  (testing "qualified symbols are preserved"
    (let [[val _] (spell-eval '(expand '(strings/trim x)) {'x "  hi  "})]
      (is (= '(strings/trim "  hi  ") val)))))

;; =============================================================================
;; Expand integration tests
;; =============================================================================

(deftest expand-integration-test
  (testing "expand then evaluate round-trip"
    (let [[expanded _] (spell-eval '(do (def x 42) (expand '(+ x 1))) {})]
      (is (= 43 (run-spell expanded)))))

  (testing "expand a thunk defined in the same program"
    (let [[expanded _] (spell-eval '(do (def x 10)
                                        (def thunk '(+ x 1))
                                        (expand thunk)) {})]
      (is (= '(+ 10 1) expanded))
      (is (= 11 (run-spell expanded)))))

  (testing "defn-bound vars expanded as source form"
    ;; defn creates a spell-fn map, which expand reconstructs as (fn ...) source
    (let [[val _] (spell-eval '(do (defn f [x] (* x x))
                                   (expand '(f 3))) {})]
      (is (= '((fn [x] (* x x)) 3) val))
      (is (= 9 (run-spell val)))))

  (testing "internal def shadows outer binding"
    ;; After (def x 10) inside the expression, x should NOT be substituted
    (let [[val _] (spell-eval '(do (def x 42)
                                   (expand '(do (def x 10) (+ x 1)))) {})]
      (is (= '(do (def x 10) (+ x 1)) val))))

  (testing "internal def only shadows after the def"
    ;; First x reference is free (before def), second is internal (after def)
    (let [[val _] (spell-eval '(do (def x 42)
                                   (expand '(do (def y (+ x 1)) (def x 10) (+ x y)))) {})]
      (is (= '(do (def y (+ 42 1)) (def x 10) (+ x y)) val)))))

;; =============================================================================
;; Quine tests
;; =============================================================================

(deftest quine-form
  (testing "basic quine binds name to source"
    ;; (quine x (+ 1 2)) should return 3, with x bound to the quine form
    (let [[val env] (spell-eval '(quine x (+ 1 2)) {})]
      (is (= 3 val))
      (is (= '(quine x (+ 1 2)) (env 'x)))))

  (testing "body can access quine binding"
    ;; self is bound to the quine form itself
    (let [[val _] (spell-eval '(quine self self) {})]
      (is (= '(quine self self) val))))

  (testing "defs in body propagate to outer env"
    (let [[val env] (spell-eval '(quine q (do (def z 3) z)) {})]
      (is (= 3 val))
      (is (= 3 (env 'z)))
      (is (= '(quine q (do (def z 3) z)) (env 'q)))))

  (testing "quine is stable under re-evaluation"
    ;; The source form, when evaluated, produces the same binding
    (let [[_ env] (spell-eval '(quine self 42) {})]
      (is (= '(quine self 42) (env 'self)))))

  (testing "quine works with spell-eval body"
    ;; Mirrors the actual usage in the preamble
    (let [[val _] (spell-eval '(quine c (do (def x (pr-str c)) x)) {})]
      (is (string? val))
      (is (.startsWith ^String val "(quine c"))))

  (testing "expand handles quine"
    (let [[val _] (spell-eval '(do (def x 42) (expand '(quine q (+ x 1)))) {})]
      (is (= '(quine q (+ 42 1)) val)))))

;; =============================================================================
;; Dynamic builtins tests
;; =============================================================================

(deftest dynamic-builtins-test
  (testing "spell-eval uses *builtins* for symbol resolution"
    (let [custom-builtins (merge eval/core-builtins
                                 {'my-fn (fn [] 99)})]
      (binding [eval/*builtins* custom-builtins]
        (is (= 99 (first (spell-eval '(my-fn) {})))))))

  (testing "symbol not in *builtins* throws"
    (binding [eval/*builtins* eval/core-builtins]
      ;; bash is not in core-builtins, should be unbound
      (is (thrown-with-msg? Exception #"Unbound symbol"
            (spell-eval 'bash {}))))))

;; =============================================================================
;; Future / Await tests
;; =============================================================================

(deftest future-await-basic
  (testing "basic future + await returns value"
    (is (= 3 (run-spell '(await (future (+ 1 2)))))))

  (testing "future returns a spell future map"
    (let [[val _] (spell-eval '(future 42) {})]
      (is (map? val))
      (is (:spell/future val))
      (is (instance? clojure.lang.IDeref (:ref val)))))

  (testing "await on non-future throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"await: argument must be a future"
          (run-spell '(await 42)))))

  (testing "double await returns cached value"
    (is (= 5 (run-spell '(do (def f (future (+ 2 3)))
                              (def first-await (await f))
                              (await f)))))))

(deftest future-env-capture
  (testing "future captures enclosing env"
    (is (= 6 (run-spell '(let [x 5] (await (future (+ x 1))))))))

  (testing "future sees defs from before its creation"
    (is (= 15 (run-spell '(do (def x 10) (def y 5)
                               (await (future (+ x y)))))))))

(deftest future-isolation
  (testing "defs inside future don't leak to parent env"
    (let [[val env] (spell-eval '(do (def f (future (do (def leaked 99) leaked)))
                                      (await f))
                                 {})]
      (is (= 99 val))
      (is (not (contains? env 'leaked))))))

(deftest future-concurrency
  (testing "two futures run concurrently (not sequentially)"
    ;; Each future sleeps 100ms. If sequential, total >= 200ms; if concurrent, ~100ms.
    (let [sleep-fn (fn [ms] (Thread/sleep (long ms)) ms)
          builtins (merge eval/core-builtins {'sleep sleep-fn})
          start (System/currentTimeMillis)
          result (binding [eval/*builtins* builtins]
                   (first (spell-eval
                            '(do (def a (future (sleep 100)))
                                 (def b (future (sleep 100)))
                                 (list (await a) (await b)))
                            {})))
          elapsed (- (System/currentTimeMillis) start)]
      (is (= '(100 100) result))
      ;; Allow generous margin but should be well under 200ms
      (is (< elapsed 180) (str "Expected concurrent execution, took " elapsed "ms")))))

(deftest future-error-propagation
  (testing "exception in future body re-throws on await"
    (is (thrown? Exception
          (run-spell '(await (future (/ 1 0))))))))

(deftest future-nested
  (testing "nested future + await"
    (is (= 42 (run-spell '(await (future (await (future 42))))))))

  (testing "DAG: future C awaits futures A and B"
    (let [result (run-spell
                   '(do (def a (future (+ 10 1)))
                        (def b (future (+ 20 2)))
                        (def c (future (do (def ra (await a))
                                           (def rb (await b))
                                           (+ ra rb))))
                        (await c)))]
      (is (= 33 result)))))

(deftest future-dynamic-bindings
  (testing "future conveys *builtins* via bound-fn"
    (let [custom-builtins (merge eval/core-builtins
                                 {'my-tool (fn [] "tool-result")})]
      (binding [eval/*builtins* custom-builtins]
        (is (= "tool-result"
               (first (spell-eval '(await (future (my-tool))) {}))))))))

(deftest future-expand
  (testing "expand handles future form"
    (let [[val _] (spell-eval '(do (def x 10) (expand '(future (+ x 1)))) {})]
      (is (= '(future (+ 10 1)) val)))))

;; =============================================================================
;; await-all, pmap, plet tests
;; =============================================================================

(deftest await-all-basic
  (testing "await-all resolves multiple futures"
    (is (= [1 2 3] (run-spell '(await-all [(future 1) (future 2) (future 3)])))))

  (testing "await-all on empty collection"
    (is (= [] (run-spell '(await-all [])))))

  (testing "await-all on non-collection throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"await-all: argument must be a collection"
          (run-spell '(await-all 42)))))

  (testing "await-all with non-future element throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"await-all: all elements must be futures"
          (run-spell '(await-all [42]))))))

(deftest pmap-basic
  (testing "pmap applies function in parallel"
    (is (= [2 4 6] (run-spell '(pmap (fn [x] (* x 2)) [1 2 3])))))

  (testing "pmap on empty collection"
    (is (= [] (run-spell '(pmap (fn [x] x) [])))))

  (testing "pmap with spell-fn"
    (is (= [1 4 9] (run-spell '(do (defn sq [x] (* x x))
                                    (pmap sq [1 2 3])))))))

(deftest pmap-concurrency
  (testing "pmap runs items concurrently"
    (let [sleep-fn (fn [ms] (Thread/sleep (long ms)) ms)
          builtins (merge eval/core-builtins {'sleep sleep-fn})
          start (System/currentTimeMillis)
          result (binding [eval/*builtins* builtins]
                   (first (spell-eval
                            '(pmap (fn [ms] (sleep ms)) [100 100 100])
                            {})))
          elapsed (- (System/currentTimeMillis) start)]
      (is (= [100 100 100] result))
      ;; 3 x 100ms sequential would be 300ms; concurrent should be ~100ms
      (is (< elapsed 250) (str "Expected concurrent execution, took " elapsed "ms")))))

(deftest plet-basic
  (testing "plet binds parallel results and evaluates body"
    (is (= 3 (run-spell '(plet [a (+ 1 0) b (+ 2 0)] (+ a b))))))

  (testing "plet provides resolved values in body"
    (is (= [10 20] (run-spell '(plet [a (* 5 2) b (* 10 2)]
                                 (list a b))))))

  (testing "plet bindings don't escape"
    (let [[val env] (spell-eval '(do (plet [x 42] x)) {})]
      (is (= 42 val))
      (is (nil? (get env 'x))))))

(deftest plet-concurrency
  (testing "plet runs bindings concurrently"
    (let [sleep-fn (fn [ms] (Thread/sleep (long ms)) ms)
          builtins (merge eval/core-builtins {'sleep sleep-fn})
          start (System/currentTimeMillis)
          result (binding [eval/*builtins* builtins]
                   (first (spell-eval
                            '(plet [a (sleep 100)
                                    b (sleep 100)
                                    c (sleep 100)]
                               (list a b c))
                            {})))
          elapsed (- (System/currentTimeMillis) start)]
      (is (= '(100 100 100) result))
      ;; 3 x 100ms sequential would be 300ms; concurrent should be ~100ms
      (is (< elapsed 250) (str "Expected concurrent execution, took " elapsed "ms")))))

(deftest plet-expand
  (testing "expand handles plet form"
    (let [[val _] (spell-eval '(do (def x 10) (expand '(plet [a (+ x 1) b (+ x 2)] (list a b)))) {})]
      (is (= '(plet [a (+ 10 1) b (+ 10 2)] (list a b)) val)))))

;; =============================================================================
;; Qualified symbol tests
;; =============================================================================

(deftest qualified-symbol-test
  (testing "qualified symbol lookup"
    (let [ns-map {:docs {:trim "trim fn"} :trim clojure.string/trim}
          [val _] (spell-eval 'strings/trim {'strings ns-map})]
      (is (fn? val))))

  (testing "qualified symbol call"
    (let [ns-map {:docs {:trim "trim fn"} :trim clojure.string/trim}
          [val _] (spell-eval '(strings/trim "  hello  ") {'strings ns-map})]
      (is (= "hello" val))))

  (testing "nested qualified symbol"
    (let [inner {:docs {:add "add fn"} :add +}
          outer {:docs {:math "math ns"} :math inner}
          [val _] (spell-eval '(regs/math/add 1 2) {'regs outer})]
      (is (= 3 val))))

  (testing "qualified symbol in expansion stays intact"
    (let [[val _] (spell-eval '(expand '(strings/trim x)) {'x "test"})]
      (is (= '(strings/trim "test") val)))))

;; =============================================================================
;; Describe builtin tests
;; =============================================================================

(deftest describe-builtin
  (testing "describe returns all docs"
    (let [ns-map {:docs {:a "first" :b "second"} :a identity}]
      (is (= {:a "first" :b "second"} (core/describe ns-map)))))

  (testing "describe with key returns single doc"
    (let [ns-map {:docs {:a "first" :b "second"} :a identity}]
      (is (= "first" (core/describe ns-map :a)))))

  (testing "describe with missing key returns nil"
    (let [ns-map {:docs {:a "first"} :a identity}]
      (is (nil? (core/describe ns-map :missing))))))

;; =============================================================================
;; Memo-based evaluation tests
;; =============================================================================

(deftest spell-eval-4arg-test
  (testing "4-arg form returns result map"
    (let [result (spell-eval '(+ 1 2) {} [] 0)]
      (is (map? result))
      (is (contains? result :ok))
      (is (= 3 (:ok result)))
      (is (vector? (:memo result)))
      (is (number? (:idx result)))))

  (testing "memo records evaluated expressions"
    (let [result (spell-eval '(do (def x 1) (def y 2) (+ x y)) {} [] 0)]
      (is (eval/ok? result))
      (is (= 3 (:ok result)))
      ;; Memo should have entries for evaluated expressions
      (is (seq (:memo result)))))

  (testing "ok? and err? predicates work"
    (let [ok-result (spell-eval '(+ 1 2) {} [] 0)
          err-result (spell-eval 'undefined-symbol {} [] 0)]
      (is (eval/ok? ok-result))
      (is (not (eval/err? ok-result)))
      (is (eval/err? err-result))
      (is (not (eval/ok? err-result)))))

  (testing "error result contains context"
    (let [result (spell-eval 'unbound-var {} [] 0)]
      (is (eval/err? result))
      (is (string? (:err result)))
      (is (= 'unbound-var (:expr result)))
      (is (map? (:env result)))
      (is (vector? (:memo result))))))

(deftest memo-lookup-test
  (testing "memo special form retrieves cached value"
    ;; Pre-populate memo with a cached value
    (let [memo [{:expr '(+ 1 2) :value 3}]
          result (spell-eval '(memo 0) {} memo 1)]
      (is (eval/ok? result))
      (is (= 3 (:ok result)))))

  (testing "memo lookup on missing index returns error"
    (let [result (spell-eval '(memo 5) {} [] 0)]
      (is (eval/err? result))
      (is (clojure.string/includes? (:err result) "No memo entry"))))

  (testing "memo enables skip of re-evaluation"
    ;; If idx points to an existing memo entry, return cached value
    (let [memo [{:expr '(some-side-effect) :value "cached-result"}]
          result (spell-eval '(some-side-effect) {} memo 0)]
      (is (eval/ok? result))
      (is (= "cached-result" (:ok result))))))

(deftest error-in-middle-of-do-test
  (testing "error in middle of do block preserves partial memo"
    (let [result (spell-eval '(do (def x 1) unbound (def y 2)) {} [] 0)]
      (is (eval/err? result))
      ;; Should have memo entries from before the error
      (is (seq (:memo result)))
      ;; env should have x defined
      (is (= 1 (get (:env result) 'x))))))

(deftest memo-replay-test
  (testing "replay with memo skips side effects"
    (let [;; Simulate a program that did work before failing
          ;; First run: (do (def x (+ 1 2)) (def y (undefined)))
          ;; This would fail at undefined, but memo has x's computation
          first-result (spell-eval '(do (def x (+ 1 2)) undefined) {} [] 0)
          _ (is (eval/err? first-result))
          ;; Now replay with the memo, but fix the program
          ;; The (+ 1 2) computation should come from memo
          fixed-program '(do (def x (memo 0)) x)  ; Use cached value
          retry (spell-eval fixed-program {} (:memo first-result) 0)]
      ;; The memo should have the + 1 2 computation
      (when (seq (:memo first-result))
        (is (eval/ok? retry)))))

  (testing "2-arg backwards compatible form still works"
    (let [[val env] (spell-eval '(do (def x 5) x) {})]
      (is (= 5 val))
      (is (= 5 (env 'x))))))

(deftest full-recovery-flow-test
  (testing "end-to-end recovery: side effects before error aren't re-executed, new ones are"
    ;; Use atoms to track how many times each side-effect function is called
    (let [side-effect-1-count (atom 0)
          side-effect-2-count (atom 0)
          ;; Side effect functions that increment counters and return values
          side-effect-1 (fn []
                          (swap! side-effect-1-count inc)
                          100)
          side-effect-2 (fn []
                          (swap! side-effect-2-count inc)
                          200)
          ;; Builtins with our side-effect functions
          test-builtins (merge eval/core-builtins
                               {'side-effect-1 side-effect-1
                                'side-effect-2 side-effect-2})

          ;; Phase 1: Run program that succeeds partially, then fails
          ;; (do (def x (side-effect-1))    ; succeeds, x=100, side-effect-1 runs
          ;;     (def y (+ undefined 1)))   ; fails on undefined symbol
          phase1-result (binding [eval/*builtins* test-builtins]
                          (spell-eval '(do (def x (side-effect-1))
                                           (def y (+ undefined 1)))
                                      {} [] 0))]

      ;; Verify phase 1 failed
      (is (eval/err? phase1-result))
      (is (clojure.string/includes? (:err phase1-result) "undefined"))

      ;; Side-effect-1 should have run exactly once
      (is (= 1 @side-effect-1-count) "side-effect-1 should run once in phase 1")
      ;; Side-effect-2 never called yet
      (is (= 0 @side-effect-2-count) "side-effect-2 should not run in phase 1")

      ;; Env should have x from successful def
      (is (= 100 (get (:env phase1-result) 'x)))

      ;; Memo should have entries from successful evaluation
      (is (seq (:memo phase1-result)) "memo should have entries")

      ;; Phase 2: Recovery - fix the program using (memo N) to skip side-effect-1
      ;; Find which memo index has the side-effect-1 result (value 100)
      (let [memo (:memo phase1-result)
            ;; The fixed program: use memo to get x's value, then compute y with side-effect-2
            ;; We need to find the memo index that has value 100
            x-memo-idx (first (keep-indexed
                                (fn [i entry] (when (= 100 (:value entry)) i))
                                memo))
            _ (is (some? x-memo-idx) "should find memo entry with value 100")

            ;; Fixed program: skip side-effect-1 via memo, use side-effect-2 for y
            fixed-program (list 'do
                                (list 'def 'x (list 'memo x-memo-idx))
                                '(def y (side-effect-2))
                                '(+ x y))

            ;; Run recovery with the memo from phase 1
            ;; Start idx at end of memo so we don't auto-match (fixed program is different structure)
            ;; The (memo N) lookups will still work since they use explicit indices
            phase2-result (binding [eval/*builtins* test-builtins]
                            (spell-eval fixed-program
                                        (:env phase1-result)
                                        memo
                                        (count memo)))]

        ;; Verify phase 2 succeeded
        (is (eval/ok? phase2-result) (str "phase 2 should succeed: " (:err phase2-result)))

        ;; Final value should be x + y = 100 + 200 = 300
        (is (= 300 (:ok phase2-result)))

        ;; CRITICAL: side-effect-1 should NOT have run again (still 1)
        (is (= 1 @side-effect-1-count) "side-effect-1 should NOT re-run in phase 2")

        ;; side-effect-2 should have run exactly once in phase 2
        (is (= 1 @side-effect-2-count) "side-effect-2 should run once in phase 2")

        ;; Final env should have both x and y
        (is (= 100 (get (:env phase2-result) 'x)))
        (is (= 200 (get (:env phase2-result) 'y)))))))

;; =============================================================================
;; Loop/recur tests
;; =============================================================================

(deftest loop-recur-basic
  (testing "basic loop with recur"
    (is (= 10 (run-spell '(loop [x 0] (if (< x 10) (recur (+ x 1)) x))))))

  (testing "loop with multiple bindings"
    ;; sum 0+1+2+3+4 = 10
    (is (= 10 (run-spell '(loop [x 0 sum 0]
                            (if (< x 5)
                              (recur (+ x 1) (+ sum x))
                              sum))))))

  (testing "loop without recur just evaluates body"
    (is (= 42 (run-spell '(loop [x 42] x)))))

  (testing "loop with computed initial values"
    (is (= 6 (run-spell '(loop [x (+ 1 2)] (* x 2))))))

  (testing "loop bindings don't escape"
    (let [[val env] (spell-eval '(loop [x 10] x) {})]
      (is (= 10 val))
      (is (= {} env))))

  (testing "accumulator pattern"
    ;; 5+4+3+2+1 = 15
    (is (= [0 15] (run-spell '(loop [n 5 acc 0]
                                (if (> n 0)
                                  (recur (- n 1) (+ acc n))
                                  [n acc]))))))

  (testing "factorial via loop/recur"
    (is (= 120 (run-spell '(loop [n 5 acc 1]
                             (if (= n 0)
                               acc
                               (recur (- n 1) (* acc n))))))))

  (testing "countdown to zero"
    (is (= 0 (run-spell '(loop [n 100]
                           (if (> n 0)
                             (recur (- n 1))
                             n)))))))

(deftest loop-recur-env-access
  (testing "loop body can access outer env"
    (is (= 15 (run-spell '(do (def y 5)
                               (loop [x 0]
                                 (if (< x 10)
                                   (recur (+ x 1))
                                   (+ x y))))))))

  (testing "loop sees defs from same do block"
    ;; Use accumulator pattern - recur must be in tail position
    ;; 2*(5+4+3+2+1) = 2*15 = 30
    (is (= 30 (run-spell '(do (defn double [x] (* x 2))
                               (loop [n 5 acc 0]
                                 (if (= n 0)
                                   acc
                                   (recur (- n 1) (+ acc (double n)))))))))))

(deftest loop-recur-expand
  (testing "expand handles loop form"
    (let [[val _] (spell-eval '(do (def start 5)
                                    (expand '(loop [x start] x))) {})]
      (is (= '(loop [x 5] x) val))))

  (testing "expand handles recur form"
    (let [[val _] (spell-eval '(do (def delta 1)
                                    (expand '(recur (+ x delta)))) {})]
      (is (= '(recur (+ x 1)) val)))))

(deftest loop-recur-nested
  (testing "nested loops"
    ;; Inner loop counts 0..i-1, so returns i
    ;; Outer sums: 0 + 1 + 2 + 3 + 4 = 10
    (is (= 10 (run-spell '(loop [i 0 sum 0]
                            (if (< i 5)
                              (recur (+ i 1)
                                     (+ sum (loop [j 0 inner 0]
                                              (if (< j i)
                                                (recur (+ j 1) (+ inner 1))
                                                inner))))
                              sum)))))))

;; =============================================================================
;; Regex Pattern tests (#53)
;; =============================================================================

(deftest regex-pattern-self-evaluating
  (testing "regex pattern is self-evaluating"
    (is (instance? java.util.regex.Pattern (run-spell #"\d+"))))

  (testing "regex pattern in vector"
    (let [result (run-spell [#"\d+" #"[a-z]+"])]
      (is (= 2 (count result)))
      (is (instance? java.util.regex.Pattern (first result)))))

  (testing "regex pattern in map value"
    (let [result (run-spell {:pattern #"\d+"})]
      (is (instance? java.util.regex.Pattern (:pattern result)))))

  (testing "regex pattern with def"
    (let [[val env] (spell-eval '(def pat #"\d+") {})]
      (is (instance? java.util.regex.Pattern val))
      (is (instance? java.util.regex.Pattern (env 'pat)))))

  (testing "regex pattern in let binding"
    (is (instance? java.util.regex.Pattern
          (run-spell '(let [p #"\d+"] p)))))

  (testing "expand preserves regex patterns"
    (let [[val _] (spell-eval '(expand '#"\d+") {})]
      (is (instance? java.util.regex.Pattern val)))))

;; =============================================================================
;; Core keep and take-last tests (#55)
;; =============================================================================

(deftest core-keep-builtin
  (testing "keep filters nil results"
    (is (= [4 6] (run-spell '(keep (fn [x] (if (> x 1) (* x 2) nil)) [0 1 2 3])))))

  (testing "keep with spell-fn"
    (is (= [4 9] (run-spell '(do (defn sq-if-pos [x] (if (> x 0) (* x x) nil))
                                  (keep sq-if-pos [-1 0 2 3]))))))

  (testing "keep empty result"
    (is (= [] (run-spell '(keep (fn [x] nil) [1 2 3])))))

  (testing "keep all non-nil"
    (is (= [2 4 6] (run-spell '(keep (fn [x] (* x 2)) [1 2 3])))))

  (testing "keep returns vector"
    (is (vector? (run-spell '(keep (fn [x] x) [1 2 3]))))))

(deftest core-take-last-builtin
  (testing "take-last basic"
    (is (= [4 5] (run-spell '(take-last 2 [1 2 3 4 5])))))

  (testing "take-last more than length"
    (is (= [1 2 3] (run-spell '(take-last 10 [1 2 3])))))

  (testing "take-last zero"
    (is (= [] (run-spell '(take-last 0 [1 2 3])))))

  (testing "take-last one"
    (is (= [3] (run-spell '(take-last 1 [1 2 3])))))

  (testing "take-last returns vector"
    (is (vector? (run-spell '(take-last 2 [1 2 3])))))

  (testing "take-last on empty"
    (is (= [] (run-spell '(take-last 5 []))))))

;; =============================================================================
;; Math namespace tests (#62)
;; =============================================================================

(deftest math-namespace
  (testing "sqrt"
    (is (= 2.0 (run-spell-full '(math/sqrt 4))))
    (is (= 3.0 (run-spell-full '(math/sqrt 9)))))

  (testing "pow"
    (is (= 8.0 (run-spell-full '(math/pow 2 3))))
    (is (= 1.0 (run-spell-full '(math/pow 5 0)))))

  (testing "abs"
    (is (= 5.0 (run-spell-full '(math/abs -5))))
    (is (= 3.0 (run-spell-full '(math/abs 3)))))

  (testing "floor and ceil"
    (is (= 3 (run-spell-full '(math/floor 3.7))))
    (is (= 4 (run-spell-full '(math/ceil 3.2)))))

  (testing "round"
    (is (= 4 (run-spell-full '(math/round 3.7))))
    (is (= 3 (run-spell-full '(math/round 3.2)))))

  (testing "trig functions"
    (is (< (Math/abs (- 0.0 (run-spell-full '(math/sin 0)))) 0.0001))
    (is (< (Math/abs (- 1.0 (run-spell-full '(math/cos 0)))) 0.0001)))

  (testing "log and exp"
    (is (< (Math/abs (- 1.0 (run-spell-full '(math/log math/E)))) 0.0001))
    (is (< (Math/abs (- 2.0 (run-spell-full '(math/log10 100)))) 0.0001))
    (is (< (Math/abs (- Math/E (run-spell-full '(math/exp 1)))) 0.0001)))

  (testing "constants"
    (is (< (Math/abs (- Math/PI (run-spell-full 'math/PI))) 0.0001))
    (is (< (Math/abs (- Math/E (run-spell-full 'math/E))) 0.0001)))

  (testing "cbrt"
    (is (= 3.0 (run-spell-full '(math/cbrt 27)))))

  (testing "sign"
    (is (= 1.0 (run-spell-full '(math/sign 42))))
    (is (= -1.0 (run-spell-full '(math/sign -5))))
    (is (= 0.0 (run-spell-full '(math/sign 0)))))

  (testing "trunc"
    (is (= 3 (run-spell-full '(math/trunc 3.9))))
    (is (= -3 (run-spell-full '(math/trunc -3.9)))))

  (testing "log2"
    (is (< (Math/abs (- 3.0 (run-spell-full '(math/log2 8)))) 0.0001)))

  (testing "inverse trig"
    (is (< (Math/abs (- (/ Math/PI 2) (run-spell-full '(math/asin 1)))) 0.0001))
    (is (< (Math/abs (- 0.0 (run-spell-full '(math/acos 1)))) 0.0001))
    (is (< (Math/abs (- (/ Math/PI 4) (run-spell-full '(math/atan 1)))) 0.0001)))

  (testing "atan2"
    (is (< (Math/abs (- (/ Math/PI 2) (run-spell-full '(math/atan2 1 0)))) 0.0001)))

  (testing "hyperbolic"
    (is (< (Math/abs (- 0.0 (run-spell-full '(math/sinh 0)))) 0.0001))
    (is (< (Math/abs (- 1.0 (run-spell-full '(math/cosh 0)))) 0.0001))
    (is (< (Math/abs (- 0.0 (run-spell-full '(math/tanh 0)))) 0.0001)))

  (testing "angle conversion"
    (is (< (Math/abs (- 180.0 (run-spell-full '(math/degrees math/PI)))) 0.0001))
    (is (< (Math/abs (- Math/PI (run-spell-full '(math/radians 180)))) 0.0001)))

  (testing "factorial"
    (is (= 1 (run-spell-full '(math/factorial 0))))
    (is (= 1 (run-spell-full '(math/factorial 1))))
    (is (= 120 (run-spell-full '(math/factorial 5))))
    (is (= 3628800 (run-spell-full '(math/factorial 10)))))

  (testing "gcd"
    (is (= 6 (run-spell-full '(math/gcd 12 18))))
    (is (= 1 (run-spell-full '(math/gcd 7 13))))
    (is (= 5 (run-spell-full '(math/gcd 0 5)))))

  (testing "lcm"
    (is (= 36 (run-spell-full '(math/lcm 12 18))))
    (is (= 91 (run-spell-full '(math/lcm 7 13))))
    (is (= 0 (run-spell-full '(math/lcm 0 5)))))

  (testing "hypot"
    (is (= 5.0 (run-spell-full '(math/hypot 3 4)))))

  (testing "infinity constants"
    (is (Double/isInfinite (run-spell-full 'math/INF)))
    (is (Double/isInfinite (run-spell-full 'math/NEG-INF)))
    (is (Double/isNaN (run-spell-full 'math/NaN)))))

;; =============================================================================
;; Core some builtin (#60)
;; =============================================================================

(deftest core-some-builtin
  (testing "some finds first truthy result"
    (is (= 3 (run-spell '(some (fn [x] (if (> x 2) x nil)) [1 2 3 4])))))

  (testing "some returns nil when no match"
    (is (nil? (run-spell '(some (fn [x] (if (> x 10) x nil)) [1 2 3])))))

  (testing "some with spell fn"
    (is (= 4 (run-spell '(do (defn find-big [x] (if (> x 3) x nil))
                              (some find-big [1 2 3 4 5]))))))

  (testing "some on empty"
    (is (nil? (run-spell '(some (fn [x] x) [])))))

  (testing "some returns first truthy, not true"
    (is (= "found" (run-spell '(some (fn [x] (if (= x 3) "found" nil)) [1 2 3 4]))))))

;; =============================================================================
;; Core range builtin (#62)
;; =============================================================================

(deftest core-range-builtin
  (testing "range with end only"
    (is (= [0 1 2 3 4] (run-spell '(range 5)))))

  (testing "range with start and end"
    (is (= [2 3 4] (run-spell '(range 2 5)))))

  (testing "range with step"
    (is (= [0 2 4 6 8] (run-spell '(range 0 10 2)))))

  (testing "range empty"
    (is (= [] (run-spell '(range 0)))))

  (testing "range returns vector"
    (is (vector? (run-spell '(range 5)))))

  (testing "range negative step"
    (is (= [5 4 3 2 1] (run-spell '(range 5 0 -1))))))

;; =============================================================================
;; Set constructor (#62)
;; =============================================================================

(deftest set-builtin
  (testing "set from vector"
    (is (= #{1 2 3} (run-spell '(set [1 2 3 2 1])))))

  (testing "set from list"
    (is (= #{:a :b} (run-spell '(set '(:a :b :a))))))

  (testing "set? predicate"
    (is (true? (run-spell '(set? (set [1 2 3])))))
    (is (false? (run-spell '(set? [1 2 3])))))

  (testing "empty set"
    (is (= #{} (run-spell '(set [])))))

  (testing "set membership with contains?"
    ;; Note: sets act as functions for membership test
    (is (= 2 (run-spell '((set [1 2 3]) 2))))
    (is (nil? (run-spell '((set [1 2 3]) 5))))))

;; =============================================================================
;; For list comprehension (#62)
;; =============================================================================

(deftest for-comprehension
  (testing "basic for"
    (is (= [1 4 9] (run-spell '(for [x [1 2 3]] (* x x))))))

  (testing "for with :when"
    (is (= [4 9 16] (run-spell '(for [x [1 2 3 4] :when (> x 1)] (* x x))))))

  (testing "for with :let"
    (is (= [2 4 6] (run-spell '(for [x [1 2 3] :let [y (* x 2)]] y)))))

  (testing "for with :when and :let"
    (is (= [8 12 16] (run-spell '(for [x [1 2 3 4] :when (> x 1) :let [y (* x 4)]] y)))))

  (testing "for with multiple bindings (nested)"
    (is (= [[1 :a] [1 :b] [2 :a] [2 :b]]
           (run-spell '(for [x [1 2] y [:a :b]] [x y])))))

  (testing "for with range"
    (is (= [0 1 4 9 16] (run-spell '(for [x (range 5)] (* x x))))))

  (testing "for returns vector"
    (is (vector? (run-spell '(for [x [1 2 3]] x)))))

  (testing "for empty result"
    (is (= [] (run-spell '(for [x [1 2 3] :when (> x 10)] x)))))

  (testing "for with spell-fn in body"
    (is (= [1 4 9] (run-spell '(do (defn sq [n] (* n n))
                                    (for [x [1 2 3]] (sq x)))))))

  (testing "for bindings don't escape"
    (let [[val env] (spell-eval '(for [x [1 2 3]] x) {})]
      (is (= [1 2 3] val))
      (is (= {} env)))))

;; =============================================================================
;; Map-indexed builtin (#62)
;; =============================================================================

(deftest map-indexed-builtin
  (testing "map-indexed basic"
    (is (= [[0 :a] [1 :b] [2 :c]]
           (run-spell '(map-indexed (fn [i x] [i x]) [:a :b :c])))))

  (testing "map-indexed with spell-fn"
    (is (= [0 2 6]
           (run-spell '(do (defn mult-idx [i x] (* i x))
                            (map-indexed mult-idx [1 2 3]))))))

  (testing "map-indexed empty"
    (is (= [] (run-spell '(map-indexed (fn [i x] [i x]) [])))))

  (testing "map-indexed returns vector"
    (is (vector? (run-spell '(map-indexed (fn [i x] x) [1 2 3]))))))

;; =============================================================================
;; Try/catch/throw tests (#37)
;; =============================================================================

(deftest try-catch-basic
  (testing "try without error returns body value"
    (is (= 42 (run-spell '(try 42)))))

  (testing "try with multiple body forms returns last"
    (is (= 3 (run-spell '(try 1 2 3)))))

  (testing "try catches evaluation errors"
    (is (= "caught" (run-spell '(try unbound-var (catch e "caught"))))))

  (testing "try catches division by zero"
    (is (= "div-error" (run-spell '(try (/ 1 0) (catch e "div-error"))))))

  (testing "catch binding has error info for eval errors"
    (is (= "Unbound symbol: xyz"
           (run-spell '(try xyz (catch e (:message e)))))))

  (testing "catch binding has failing expr for eval errors"
    (is (= 'xyz (run-spell '(try xyz (catch e (:expr e)))))))

  (testing "no error means catch is skipped"
    (is (= 42 (run-spell '(try 42 (catch e "should not reach")))))))

(deftest throw-basic
  (testing "throw raises catchable error"
    (is (= "oops" (run-spell '(try (throw "oops") (catch e e))))))

  (testing "throw with map value"
    (is (= {:code 404} (run-spell '(try (throw {:code 404}) (catch e e))))))

  (testing "throw with number"
    (is (= 42 (run-spell '(try (throw 42) (catch e e))))))

  (testing "uncaught throw propagates as error"
    (is (thrown? Exception (run-spell '(throw "uncaught"))))))

(deftest try-catch-env-threading
  (testing "defs before error visible in catch"
    (is (= 10 (run-spell '(try (def x 10) unbound (catch e x))))))

  (testing "defs in try escape on success"
    (let [[val env] (spell-eval '(do (try (def x 42)) x) {})]
      (is (= 42 val))
      (is (= 42 (env 'x)))))

  (testing "catch binding does not escape"
    (let [[val env] (spell-eval '(try (throw "err") (catch e (def result "ok"))) {})]
      (is (= "ok" val))
      (is (contains? env 'result))
      (is (not (contains? env 'e))))))

(deftest try-catch-nested
  (testing "nested try/catch"
    (is (= "inner" (run-spell '(try
                                 (try (throw "inner-err") (catch e "inner"))
                                 (catch e "outer"))))))

  (testing "inner throw caught by inner try, outer succeeds"
    (is (= "recovered" (run-spell '(try
                                     (try (throw "fail") (catch e "recovered"))
                                     (catch e "should not reach"))))))

  (testing "inner throw not caught propagates to outer"
    (is (= "outer-caught" (run-spell '(try
                                        (do (try (+ 1 2)) (throw "propagate"))
                                        (catch e "outer-caught")))))))

(deftest try-catch-with-throw-in-body
  (testing "throw after successful defs"
    (is (= 10 (run-spell '(try
                             (def x 10)
                             (throw "stop")
                             (catch e x))))))

  (testing "throw with computed value"
    (is (= 42 (run-spell '(try (throw (+ 40 2)) (catch e e)))))))

(deftest try-catch-expand
  (testing "expand handles try form"
    (let [[val _] (spell-eval '(do (def x 42) (expand '(try (+ x 1) (catch e x)))) {})]
      (is (= '(try (+ 42 1) (catch e 42)) val))))

  (testing "expand handles throw form"
    (let [[val _] (spell-eval '(do (def msg "err") (expand '(throw msg))) {})]
      (is (= '(throw "err") val))))

  (testing "expand: catch binding shadows outer"
    (let [[val _] (spell-eval '(do (def e 99) (expand '(try x (catch e e)))) {})]
      (is (= '(try x (catch e e)) val)))))

;; =============================================================================
;; second, key, val, bigint builtins (#65)
;; =============================================================================

(deftest second-builtin
  (testing "second of vector"
    (is (= 2 (run-spell '(second [1 2 3])))))
  (testing "second of list"
    (is (= :b (run-spell '(second '(:a :b :c))))))
  (testing "second of two-element"
    (is (= 2 (run-spell '(second [1 2])))))
  (testing "second of one-element"
    (is (nil? (run-spell '(second [1]))))))

(deftest key-val-builtin
  (testing "key of map entry"
    (is (= :a (run-spell '(key (first {:a 1 :b 2}))))))
  (testing "val of map entry"
    (is (= 1 (run-spell '(val (first {:a 1})))))))

(deftest bigint-builtin
  (testing "bigint from integer"
    (is (= 42N (run-spell '(bigint 42)))))
  (testing "bigint from string"
    (is (= 99999999999999999999N (run-spell '(bigint "99999999999999999999")))))
  (testing "bigint arithmetic"
    (is (= 200N (run-spell '(+ (bigint 100) (bigint 100)))))))

;; =============================================================================
;; New core builtins tests (Clojure audit additions)
;; =============================================================================

(deftest numeric-predicates-test
  (testing "even?"
    (is (true? (run-spell '(even? 4))))
    (is (false? (run-spell '(even? 3)))))
  (testing "odd?"
    (is (true? (run-spell '(odd? 3))))
    (is (false? (run-spell '(odd? 4)))))
  (testing "pos?"
    (is (true? (run-spell '(pos? 5))))
    (is (false? (run-spell '(pos? -5))))
    (is (false? (run-spell '(pos? 0)))))
  (testing "neg?"
    (is (true? (run-spell '(neg? -5))))
    (is (false? (run-spell '(neg? 5))))
    (is (false? (run-spell '(neg? 0)))))
  (testing "zero?"
    (is (true? (run-spell '(zero? 0))))
    (is (false? (run-spell '(zero? 1))))))

(deftest rem-builtin
  (testing "rem basic"
    (is (= 1 (run-spell '(rem 10 3)))))
  (testing "rem negative"
    (is (= -1 (run-spell '(rem -10 3))))))

(deftest abs-builtin
  (testing "abs positive"
    (is (= 5 (run-spell '(abs 5)))))
  (testing "abs negative"
    (is (= 5 (run-spell '(abs -5))))))

(deftest compare-builtin
  (testing "compare equal"
    (is (= 0 (run-spell '(compare 5 5)))))
  (testing "compare less"
    (is (neg? (run-spell '(compare 3 5)))))
  (testing "compare greater"
    (is (pos? (run-spell '(compare 5 3))))))

(deftest logic-predicates-test
  (testing "some? true"
    (is (true? (run-spell '(some? 0))))
    (is (true? (run-spell '(some? false)))))
  (testing "some? false"
    (is (false? (run-spell '(some? nil)))))
  (testing "true?"
    (is (true? (run-spell '(true? true))))
    (is (false? (run-spell '(true? 1)))))
  (testing "false?"
    (is (true? (run-spell '(false? false))))
    (is (false? (run-spell '(false? nil))))))

(deftest type-predicates-extended
  (testing "keyword?"
    (is (true? (run-spell '(keyword? :foo))))
    (is (false? (run-spell '(keyword? "foo")))))
  (testing "symbol?"
    (is (true? (run-spell '(symbol? 'foo))))
    (is (false? (run-spell '(symbol? :foo))))))

(deftest type-constructors-test
  (testing "name from keyword"
    (is (= "foo" (run-spell '(name :foo)))))
  (testing "name from symbol"
    (is (= "bar" (run-spell '(name 'bar)))))
  (testing "symbol from string"
    (is (symbol? (run-spell '(symbol "foo")))))
  (testing "keyword from string"
    (is (= :foo (run-spell '(keyword "foo"))))))

(deftest identity-builtin
  (testing "identity returns argument"
    (is (= 42 (run-spell '(identity 42))))
    (is (= [1 2 3] (run-spell '(identity [1 2 3]))))))

(deftest collection-access-test
  (testing "peek on vector"
    (is (= 3 (run-spell '(peek [1 2 3])))))
  (testing "pop on vector"
    (is (= [1 2] (run-spell '(pop [1 2 3])))))
  (testing "butlast"
    (is (= '(1 2) (run-spell '(butlast [1 2 3])))))
  (testing "subvec 2-arg"
    (is (= [3 4 5] (run-spell '(subvec [1 2 3 4 5] 2)))))
  (testing "subvec 3-arg"
    (is (= [2 3] (run-spell '(subvec [1 2 3 4 5] 1 3)))))
  (testing "vec"
    (is (= [1 2 3] (run-spell '(vec '(1 2 3))))))
  (testing "not-empty on non-empty"
    (is (= [1 2 3] (run-spell '(not-empty [1 2 3])))))
  (testing "not-empty on empty"
    (is (nil? (run-spell '(not-empty []))))))

(deftest map-operations-test
  (testing "merge"
    (is (= {:a 1 :b 2 :c 3} (run-spell '(merge {:a 1} {:b 2} {:c 3})))))
  (testing "merge with override"
    (is (= {:a 2} (run-spell '(merge {:a 1} {:a 2})))))
  (testing "update with builtin"
    (is (= {:a 2} (run-spell '(update {:a 1} :a inc)))))
  (testing "update with spell-fn"
    (is (= {:a 10} (run-spell '(do (defn dbl [x] (* x 2))
                                    (update {:a 5} :a dbl))))))
  (testing "update-in"
    (is (= {:a {:b 2}} (run-spell '(update-in {:a {:b 1}} [:a :b] inc)))))
  (testing "get-in"
    (is (= 42 (run-spell '(get-in {:a {:b {:c 42}}} [:a :b :c])))))
  (testing "assoc-in"
    (is (= {:a {:b 42}} (run-spell '(assoc-in {} [:a :b] 42)))))
  (testing "dissoc"
    (is (= {:a 1} (run-spell '(dissoc {:a 1 :b 2} :b))))))

(deftest set-operations-test
  (testing "contains? on set"
    (is (true? (run-spell '(contains? #{1 2 3} 2))))
    (is (false? (run-spell '(contains? #{1 2 3} 5)))))
  (testing "contains? on map"
    (is (true? (run-spell '(contains? {:a 1} :a))))
    (is (false? (run-spell '(contains? {:a 1} :b)))))
  (testing "disj"
    (is (= #{1 3} (run-spell '(disj #{1 2 3} 2))))))
