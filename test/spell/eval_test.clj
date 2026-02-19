(ns spell.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.eval :as eval :refer [spell-eval run-spell]]
            [spell.macros :as macros]
            [spell.stdlib :as stdlib]
            [spell.core :as core :refer [effect-builtins]]))

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

(defmacro with-effects
  "Run body with effect-builtins merged into *builtins* (simulates eval's second pass)."
  [& body]
  `(binding [eval/*builtins* (merge eval/*builtins* effect-builtins)]
     ~@body))

(defn eval-ok
  "Evaluate expr in env, return [value env'] or throw on error.
   Convenience wrapper for tests that used the old 2-arity spell-eval."
  ([expr env]
   (let [r (spell-eval expr env)]
     (if (eval/ok? r)
       [(:ok r) (:env r)]
       (throw (ex-info (:err r) {:result r}))))))

(def test-env-with-namespaces
  "Environment with stdlib namespaces for qualified access testing."
  {'strings stdlib/strings
   'math stdlib/math
   'patterns stdlib/patterns})

(defn run-spell-full
  "Run spell with full builtins (including stdlib) for testing."
  [program]
  (binding [eval/*builtins* test-builtins]
    (let [r (spell-eval program test-env-with-namespaces)]
      (if (eval/ok? r) (:ok r) (throw (ex-info (:err r) {:result r}))))))

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
    (deref (atom 0))])

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

(deftest read-string-builtin
  (testing "parses s-expression from string"
    (is (= '(+ 1 2) (run-spell '(read-string "(+ 1 2)"))))
    (is (= 42 (run-spell '(read-string "42"))))
    (is (= {:a 1} (run-spell '(read-string "{:a 1}"))))))

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
    (is (= [5 {'x 5}] (eval-ok '(def x 5) {}))))

  (testing "def chains in do"
    (let [[val env] (eval-ok '(do (def x 1) (+ x 2)) {})]
      (is (= 3 val))
      (is (= 1 (env 'x))))
    (let [[val env] (eval-ok '(do (def x 1) (def y 2) (+ x y)) {})]
      (is (= 3 val))
      (is (= {'x 1 'y 2} env))))

  (testing "env threads through function arguments"
    (let [[val env] (eval-ok '(+ (def x 1) x) {})]
      (is (= 2 val))
      (is (= {'x 1} env))))

  (testing "env threads through vectors"
    (let [[val env] (eval-ok '[1 (def x 2) x] {})]
      (is (= [1 2 2] val))
      (is (= {'x 2} env))))

  (testing "env threads through maps"
    (let [[val env] (eval-ok '{:a (def x 3)} {})]
      (is (= {:a 3} val))
      (is (= {'x 3} env))))

  (testing "env threads through nested structures"
    (let [[val env] (eval-ok '(do (def v [1 (def y 2)]) (+ (first v) y)) {})]
      (is (= 3 val))
      (is (= 2 (env 'y)))
      (is (= [1 2] (env 'v))))))

;; =============================================================================
;; Env input tests (passing bindings into spell-eval)
;; =============================================================================

(deftest env-input
  (testing "symbol in env resolves"
    (is (= 11 (first (eval-ok '(+ x 1) {'x 10})))))

  (testing "env shadows builtins"
    ;; If you shadow + with a value, using it as function fails
    (is (thrown? Exception (first (eval-ok '(+ 1 2) {'+ 5})))))

  (testing "user functions in env work"
    (let [square (fn [x] (* x x))]
      (is (= 9 (first (eval-ok '(square 3) {'square square})))))))

;; =============================================================================
;; New special forms tests
;; =============================================================================

(deftest let-form
  (testing "basic let"
    (is (= 3 (run-spell '(let [x 1 y 2] (+ x y))))))

  (testing "let shadows outer"
    (is (= 10 (first (eval-ok '(let [x 10] x) {'x 1})))))

  (testing "let bindings don't escape"
    (let [[val env] (eval-ok '(let [x 1] x) {})]
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
          [_ env2] (eval-ok '(defn f [x] (+ x y)) env1)
          env3 (assoc env2 'y 100)
          [result _] (eval-ok '(f 5) env3)]
      (is (= 105 result) "dynamic scoping: f should see y=100 from call site")))

  (testing "dynamic scoping - function sees def'd vars at call site"
    ;; y is not defined when f is defined, but is defined before f is called
    (is (= 8 (run-spell '(do (defn f [x] (+ x y)) (def y 5) (f 3)))))))

(deftest defn-form
  (testing "defn creates function in env"
    (let [[val env] (eval-ok '(defn square [x] (* x x)) {})]
      (is (eval/spell-fn? val))
      (is (eval/spell-fn? (env 'square)))))

  (testing "defn function can be called"
    (is (= 9 (run-spell-full '(do (defn square [x] (* x x)) (square 3))))))

  (testing "defn with multiple params"
    (is (= 7 (run-spell-full '(do (defn add [a b] (+ a b)) (add 3 4)))))))

(deftest when-form
  (testing "when truthy"
    (is (= 42 (run-spell '(when true 42)))))
  (testing "when falsy returns nil"
    (is (nil? (run-spell '(when false 42)))))
  (testing "when with multiple body forms (implicit do)"
    (is (= 3 (run-spell '(when true 1 2 3)))))
  (testing "when with nil test"
    (is (nil? (run-spell '(when nil 42))))))

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
    (let [[val _] (eval-ok '(do (def x 42) (expand '(+ x 1))) {})]
      (is (= '(+ 42 1) val))))

  (testing "multiple free variables"
    (let [[val _] (eval-ok '(do (def x 10) (def y 20) (expand '(+ x y))) {})]
      (is (= '(+ 10 20) val))))

  (testing "no free variables - unchanged"
    (let [[val _] (eval-ok '(expand '(+ 1 2)) {})]
      (is (= '(+ 1 2) val))))

  (testing "expansion with computed values"
    (let [[val _] (eval-ok '(do (def x (+ 1 2)) (expand '(* x x))) {})]
      (is (= '(* 3 3) val))))

  (testing "expansion respects quotes"
    (let [[val _] (eval-ok '(do (def x 42) (expand '(list x (quote x)))) {})]
      (is (= '(list 42 (quote x)) val))))

  (testing "let-bound vars not expanded"
    (let [[val _] (eval-ok '(do (def x 100) (expand '(let [x 1] (+ x y)))) {})]
      (is (= '(let [x 1] (+ x y)) val))))

  (testing "fn params not expanded"
    (let [[val _] (eval-ok '(do (def x 100) (def y 200) (expand '(fn [x] (+ x y)))) {})]
      (is (= '(fn [x] (+ x 200)) val))))

  (testing "partial expansion - only defined vars substituted"
    (let [[val _] (eval-ok '(do (def x 10) (expand '(+ x y))) {})]
      (is (= '(+ 10 y) val))))

  (testing "expanded result is evaluable"
    (let [[expanded _] (eval-ok '(do (def x 42) (expand '(+ x 1))) {})]
      (is (= 43 (run-spell expanded)))))

  (testing "list values get quoted for portability"
    (let [[val _] (eval-ok '(do (def xs '(1 2 3)) (expand '(first xs))) {})]
      (is (= '(first (quote (1 2 3))) val))
      (is (= 1 (run-spell val)))))

  (testing "expansion uses env bindings"
    (let [[val _] (eval-ok '(expand '(+ x 1)) {'x 99})]
      (is (= '(+ 99 1) val))))

  (testing "symbol values get quoted"
    (let [[val _] (eval-ok '(do (def s 'hello) (expand '(list s))) {})]
      (is (= '(list (quote hello)) val))))

  (testing "qualified symbols are preserved"
    (let [[val _] (eval-ok '(expand '(strings/trim x)) {'x "  hi  "})]
      (is (= '(strings/trim "  hi  ") val)))))

;; =============================================================================
;; Expand integration tests
;; =============================================================================

(deftest expand-integration-test
  (testing "expand then evaluate round-trip"
    (let [[expanded _] (eval-ok '(do (def x 42) (expand '(+ x 1))) {})]
      (is (= 43 (run-spell expanded)))))

  (testing "expand a thunk defined in the same program"
    (let [[expanded _] (eval-ok '(do (def x 10)
                                        (def thunk '(+ x 1))
                                        (expand thunk)) {})]
      (is (= '(+ 10 1) expanded))
      (is (= 11 (run-spell expanded)))))

  (testing "defn-bound vars expanded as source form"
    ;; defn creates a spell-fn map, which expand reconstructs as (fn ...) source
    (let [[val _] (eval-ok '(do (defn f [x] (* x x))
                                   (expand '(f 3))) {})]
      (is (= '((fn [x] (* x x)) 3) val))
      (is (= 9 (run-spell val)))))

  (testing "internal def shadows outer binding"
    ;; After (def x 10) inside the expression, x should NOT be substituted
    (let [[val _] (eval-ok '(do (def x 42)
                                   (expand '(do (def x 10) (+ x 1)))) {})]
      (is (= '(do (def x 10) (+ x 1)) val))))

  (testing "internal def only shadows after the def"
    ;; First x reference is free (before def), second is internal (after def)
    (let [[val _] (eval-ok '(do (def x 42)
                                   (expand '(do (def y (+ x 1)) (def x 10) (+ x y)))) {})]
      (is (= '(do (def y (+ 42 1)) (def x 10) (+ x y)) val)))))

;; =============================================================================
;; Quine tests
;; =============================================================================

(deftest quine-form
  (testing "basic quine binds name to source"
    ;; (quine x (+ 1 2)) should return 3, with x bound to the quine form
    (let [[val env] (eval-ok '(quine x (+ 1 2)) {})]
      (is (= 3 val))
      (is (= '(quine x (+ 1 2)) (env 'x)))))

  (testing "body can access quine binding"
    ;; self is bound to the quine form itself
    (let [[val _] (eval-ok '(quine self self) {})]
      (is (= '(quine self self) val))))

  (testing "defs in body propagate to outer env"
    (let [[val env] (eval-ok '(quine q (do (def z 3) z)) {})]
      (is (= 3 val))
      (is (= 3 (env 'z)))
      (is (= '(quine q (do (def z 3) z)) (env 'q)))))

  (testing "quine is stable under re-evaluation"
    ;; The source form, when evaluated, produces the same binding
    (let [[_ env] (eval-ok '(quine self 42) {})]
      (is (= '(quine self 42) (env 'self)))))

  (testing "quine works with spell-eval body"
    ;; Mirrors the actual usage in the preamble
    (let [[val _] (eval-ok '(quine c (do (def x (pr-str c)) x)) {})]
      (is (string? val))
      (is (.startsWith ^String val "(quine c"))))

  (testing "expand handles quine"
    (let [[val _] (eval-ok '(do (def x 42) (expand '(quine q (+ x 1)))) {})]
      (is (= '(quine q (+ 42 1)) val))))

  (testing "multi-arg quine evaluates only last arg"
    ;; (quine x arg1 arg2) should evaluate arg2, ignore arg1
    (let [[val env] (eval-ok '(quine x (+ 1 2) (+ 3 4)) {})]
      (is (= 7 val))
      (is (= '(quine x (+ 1 2) (+ 3 4)) (env 'x)))))

  (testing "multi-arg quine: 2-arg (standard case) still works"
    (let [[val env] (eval-ok '(quine x (+ 10 20)) {})]
      (is (= 30 val))
      (is (= '(quine x (+ 10 20)) (env 'x)))))

  (testing "multi-arg quine: inert args are visible in binding"
    ;; The full quine form (all args) is bound to the name
    (let [[val _] (eval-ok '(quine self (+ 1 2) (+ 3 4) (count (pr-str self))) {})]
      (is (> val 0))  ;; self includes all args
      (is (number? val))))

  (testing "expand handles multi-arg quine"
    (let [[val _] (eval-ok '(do (def x 42) (expand '(quine q (+ x 1) (+ x 2)))) {})]
      (is (= '(quine q (+ 42 1) (+ 42 2)) val)))))

(deftest eval-seq-containing-form-test
  (testing "error in do-body includes :containing-form"
    (let [result (spell-eval '(do (def x 1) (def y (+ x undefined)) (+ 1 2)) {})]
      (is (eval/err? result))
      (is (= '(def y (+ x undefined)) (:containing-form result)))))

  (testing "no :containing-form when do has no error"
    (let [result (spell-eval '(do (+ 1 2) (+ 3 4)) {})]
      (is (eval/ok? result))
      (is (nil? (:containing-form result)))))

  (testing ":containing-form on first failing expression"
    (let [result (spell-eval '(do undefined-sym) {})]
      (is (eval/err? result))
      (is (= 'undefined-sym (:containing-form result))))))

;; =============================================================================
;; Dynamic builtins tests
;; =============================================================================

(deftest dynamic-builtins-test
  (testing "spell-eval uses *builtins* for symbol resolution"
    (let [custom-builtins (merge eval/core-builtins
                                 {'my-fn (fn [] 99)})]
      (binding [eval/*builtins* custom-builtins]
        (is (= 99 (first (eval-ok '(my-fn) {})))))))

  (testing "symbol not in *builtins* throws"
    (binding [eval/*builtins* eval/core-builtins]
      ;; bash is not in core-builtins, should be unbound
      (is (thrown-with-msg? Exception #"Unbound symbol"
            (eval-ok 'bash {}))))))

;; =============================================================================
;; Future / Await tests
;; =============================================================================

(deftest future-await-basic
  (with-effects
    (testing "basic future + await returns value"
      (is (= 3 (run-spell '(await (future (+ 1 2)))))))

    (testing "future returns a spell future map"
      (let [[val _] (eval-ok '(future 42) {})]
        (is (map? val))
        (is (:spell/future val))
        (is (instance? clojure.lang.IDeref (:ref val)))))

    (testing "await on non-future throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"await requires a future"
            (run-spell '(await 42)))))

    (testing "double await returns cached value"
      (is (= 5 (run-spell '(do (def f (future (+ 2 3)))
                                (def first-await (await f))
                                (await f))))))))

(deftest future-env-capture
  (with-effects
    (testing "future captures enclosing env"
      (is (= 6 (run-spell '(let [x 5] (await (future (+ x 1))))))))

    (testing "future sees defs from before its creation"
      (is (= 15 (run-spell '(do (def x 10) (def y 5)
                                 (await (future (+ x y))))))))))

(deftest future-isolation
  (with-effects
    (testing "defs inside future don't leak to parent env"
      (let [[val env] (eval-ok '(do (def f (future (do (def leaked 99) leaked)))
                                        (await f))
                                   {})]
        (is (= 99 val))
        (is (not (contains? env 'leaked)))))))

(deftest future-concurrency
  (testing "two futures run concurrently (not sequentially)"
    ;; Each future sleeps 100ms. If sequential, total >= 200ms; if concurrent, ~100ms.
    (let [sleep-fn (fn [ms] (Thread/sleep (long ms)) ms)
          builtins (merge eval/core-builtins effect-builtins {'sleep sleep-fn})
          start (System/currentTimeMillis)
          result (binding [eval/*builtins* builtins]
                   (first (eval-ok
                            '(do (def a (future (sleep 100)))
                                 (def b (future (sleep 100)))
                                 (list (await a) (await b)))
                            {})))
          elapsed (- (System/currentTimeMillis) start)]
      (is (= '(100 100) result))
      ;; Allow generous margin but should be well under 200ms
      (is (< elapsed 180) (str "Expected concurrent execution, took " elapsed "ms")))))

(deftest future-error-propagation
  (with-effects
    (testing "exception in future body re-throws on await"
      (is (thrown? Exception
            (run-spell '(await (future (/ 1 0)))))))))

(deftest future-nested
  (with-effects
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
        (is (= 33 result))))))

(deftest future-dynamic-bindings
  (testing "future conveys *builtins* via bound-fn"
    (let [custom-builtins (merge eval/core-builtins effect-builtins
                                 {'my-tool (fn [] "tool-result")})]
      (binding [eval/*builtins* custom-builtins]
        (is (= "tool-result"
               (first (eval-ok '(await (future (my-tool))) {}))))))))

(deftest future-expand
  (testing "expand handles future form (macro-expanded to future*)"
    (let [[val _] (eval-ok '(do (def x 10) (expand '(future (+ x 1)))) {})]
      (is (= '(future* (fn [] (+ 10 1))) val)))))

;; =============================================================================
;; await-all, pmap, plet tests
;; =============================================================================

(deftest await-all-basic
  (with-effects
    (testing "await-all resolves multiple futures"
      (is (= [1 2 3] (run-spell '(futures/await-all [(future 1) (future 2) (future 3)])))))

    (testing "await-all on empty collection"
      (is (= [] (run-spell '(futures/await-all [])))))

    (testing "await-all on non-collection throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"await-all: argument must be a collection"
            (run-spell '(futures/await-all 42)))))

    (testing "await-all with non-future element throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"await-all: all elements must be futures"
            (run-spell '(futures/await-all [42])))))))

(deftest pmap-basic
  (with-effects
    (testing "pmap applies function in parallel"
      (is (= [2 4 6] (run-spell '(futures/pmap (fn [x] (* x 2)) [1 2 3])))))

    (testing "pmap on empty collection"
      (is (= [] (run-spell '(futures/pmap (fn [x] x) [])))))

    (testing "pmap with spell-fn"
      (is (= [1 4 9] (run-spell '(do (defn sq [x] (* x x))
                                      (futures/pmap sq [1 2 3]))))))))

(deftest pmap-concurrency
  (testing "pmap runs items concurrently"
    (let [sleep-fn (fn [ms] (Thread/sleep (long ms)) ms)
          builtins (merge eval/core-builtins effect-builtins {'sleep sleep-fn})
          start (System/currentTimeMillis)
          result (binding [eval/*builtins* builtins]
                   (first (eval-ok
                            '(futures/pmap (fn [ms] (sleep ms)) [100 100 100])
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
    (let [[val env] (eval-ok '(do (plet [x 42] x)) {})]
      (is (= 42 val))
      (is (nil? (get env 'x))))))

(deftest plet-concurrency
  (testing "plet runs bindings concurrently"
    (let [sleep-fn (fn [ms] (Thread/sleep (long ms)) ms)
          builtins (merge eval/core-builtins {'sleep sleep-fn})
          start (System/currentTimeMillis)
          result (binding [eval/*builtins* builtins]
                   (first (eval-ok
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
  (testing "expand handles plet form (macro-expanded to let + future + await)"
    (let [[val _] (eval-ok '(do (def x 10) (expand '(plet [a (+ x 1) b (+ x 2)] (list a b)))) {})]
      ;; plet expands to nested let with future* and await calls
      (is (seq? val))
      (is (= 'let (first val))))))

;; =============================================================================
;; Qualified symbol tests
;; =============================================================================

(deftest qualified-symbol-test
  (testing "qualified symbol lookup"
    (let [ns-map {:docs {:trim "trim fn"} :trim clojure.string/trim}
          [val _] (eval-ok 'strings/trim {'strings ns-map})]
      (is (fn? val))))

  (testing "qualified symbol call"
    (let [ns-map {:docs {:trim "trim fn"} :trim clojure.string/trim}
          [val _] (eval-ok '(strings/trim "  hello  ") {'strings ns-map})]
      (is (= "hello" val))))

  (testing "nested qualified symbol"
    (let [inner {:docs {:add "add fn"} :add +}
          outer {:docs {:math "math ns"} :math inner}
          [val _] (eval-ok '(regs/math/add 1 2) {'regs outer})]
      (is (= 3 val))))

  (testing "qualified symbol in expansion stays intact"
    (let [[val _] (eval-ok '(expand '(strings/trim x)) {'x "test"})]
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
;; Result map tests
;; =============================================================================

(deftest spell-eval-result-map-test
  (testing "spell-eval returns result map"
    (let [result (spell-eval '(+ 1 2) {})]
      (is (map? result))
      (is (contains? result :ok))
      (is (= 3 (:ok result)))
      (is (map? (:env result)))))

  (testing "ok? and err? predicates work"
    (let [ok-result (spell-eval '(+ 1 2) {})
          err-result (spell-eval 'undefined-symbol {})]
      (is (eval/ok? ok-result))
      (is (not (eval/err? ok-result)))
      (is (eval/err? err-result))
      (is (not (eval/ok? err-result)))))

  (testing "error result contains context"
    (let [result (spell-eval 'unbound-var {})]
      (is (eval/err? result))
      (is (string? (:err result)))
      (is (= 'unbound-var (:expr result)))
      (is (map? (:env result)))))

  (testing "eval-ok returns [value env] pair"
    (let [[val env] (eval-ok '(do (def x 5) x) {})]
      (is (= 5 val))
      (is (= 5 (env 'x))))))

(deftest error-in-middle-of-do-test
  (testing "error in middle of do block preserves partial env"
    (let [result (spell-eval '(do (def x 1) unbound (def y 2)) {})]
      (is (eval/err? result))
      ;; env should have x defined
      (is (= 1 (get (:env result) 'x))))))

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
    (let [[val env] (eval-ok '(loop [x 10] x) {})]
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
    (let [[val _] (eval-ok '(do (def start 5)
                                    (expand '(loop [x start] x))) {})]
      (is (= '(loop [x 5] x) val))))

  (testing "expand handles recur form"
    (let [[val _] (eval-ok '(do (def delta 1)
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
;; Fn-level recur tests (#72)
;; =============================================================================

(deftest fn-recur-basic
  (testing "recur in fn rebinds params and re-enters"
    (is (= 0 (run-spell '((fn [n]
                            (if (> n 0)
                              (recur (- n 1))
                              n))
                          10)))))

  (testing "fn recur with accumulator"
    ;; 5+4+3+2+1 = 15
    (is (= 15 (run-spell '((fn [n acc]
                             (if (> n 0)
                               (recur (- n 1) (+ acc n))
                               acc))
                           5 0)))))

  (testing "factorial via fn recur"
    (is (= 120 (run-spell '((fn [n acc]
                              (if (= n 0)
                                acc
                                (recur (- n 1) (* acc n))))
                            5 1))))))

(deftest defn-recur-basic
  (testing "recur in defn"
    (is (= 0 (run-spell '(do (defn countdown [n]
                               (if (> n 0)
                                 (recur (- n 1))
                                 n))
                              (countdown 100))))))

  (testing "defn recur with accumulator"
    (is (= 55 (run-spell '(do (defn sum-to [n acc]
                                (if (> n 0)
                                  (recur (- n 1) (+ acc n))
                                  acc))
                               (sum-to 10 0)))))))

(deftest fn-recur-with-loop
  (testing "loop inside fn - recur goes to loop"
    ;; The inner recur should go to loop, not fn
    (is (= 10 (run-spell '((fn [n]
                             (loop [i 0]
                               (if (< i n)
                                 (recur (+ i 1))
                                 i)))
                           10)))))

  (testing "fn recur outside loop"
    ;; Each call: increment counter, loop sums 0..n-1
    ;; fn recurs until count reaches 3
    ;; loop result at count=3: 0+1+2 = 3
    (is (= 3 (run-spell '((fn [count]
                            (if (< count 3)
                              (recur (+ count 1))
                              (loop [i 0 sum 0]
                                (if (< i count)
                                  (recur (+ i 1) (+ sum i))
                                  sum))))
                          0))))))

(deftest nested-fn-recur
  (testing "nested fns - inner recur goes to inner fn"
    ;; outer-fn calls inner-fn which recurs to itself
    (is (= 0 (run-spell '((fn [x]
                            ((fn [y]
                               (if (> y 0)
                                 (recur (- y 1))
                                 y))
                             x))
                          5)))))

  (testing "outer fn recur not captured by inner fn"
    ;; inner fn returns immediately, outer fn recurs
    (is (= 0 (run-spell '((fn [n]
                            (if (> n 0)
                              (let [inner-result ((fn [x] x) n)]
                                (recur (- inner-result 1)))
                              n))
                          5))))))

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
    (let [[val env] (eval-ok '(def pat #"\d+") {})]
      (is (instance? java.util.regex.Pattern val))
      (is (instance? java.util.regex.Pattern (env 'pat)))))

  (testing "regex pattern in let binding"
    (is (instance? java.util.regex.Pattern
          (run-spell '(let [p #"\d+"] p)))))

  (testing "expand preserves regex patterns"
    (let [[val _] (eval-ok '(expand '#"\d+") {})]
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
    (let [[val env] (eval-ok '(for [x [1 2 3]] x) {})]
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
    (let [[val env] (eval-ok '(do (try (def x 42)) x) {})]
      (is (= 42 val))
      (is (= 42 (env 'x)))))

  (testing "catch binding does not escape"
    (let [[val env] (eval-ok '(try (throw "err") (catch e (def result "ok"))) {})]
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
    (let [[val _] (eval-ok '(do (def x 42) (expand '(try (+ x 1) (catch e x)))) {})]
      (is (= '(try (+ 42 1) (catch e 42)) val))))

  (testing "expand handles throw form"
    (let [[val _] (eval-ok '(do (def msg "err") (expand '(throw msg))) {})]
      (is (= '(throw "err") val))))

  (testing "expand: catch binding shadows outer"
    (let [[val _] (eval-ok '(do (def e 99) (expand '(try x (catch e e)))) {})]
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

(deftest long-builtin
  (testing "long from double"
    (is (= 3 (run-spell '(long 3.7)))))
  (testing "long from int"
    (is (= 5 (run-spell '(long 5))))))

(deftest floor-builtin
  (testing "floor positive"
    (is (= 3 (run-spell '(floor 3.7)))))
  (testing "floor negative"
    (is (= -4 (run-spell '(floor -3.2))))))

(deftest ceil-builtin
  (testing "ceil positive"
    (is (= 4 (run-spell '(ceil 3.2)))))
  (testing "ceil negative"
    (is (= -3 (run-spell '(ceil -3.7))))))

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

(deftest promoted-core-builtins
  (testing "type coercion"
    (is (= 3.0 (run-spell '(double 3))))
    (is (= (float 3) (run-spell '(float 3))))
    (is (instance? BigDecimal (run-spell '(bigdec 3))))
    (is (ratio? (run-spell '(rationalize 0.1)))))
  (testing "rand"
    (is (<= 0 (run-spell '(rand)) 1))
    (is (int? (run-spell '(rand-int 100)))))
  (testing "auto-promoting arithmetic"
    (is (= 2 (run-spell '(+' 1 1))))
    (is (= 0 (run-spell '(-' 1 1))))
    (is (= 6 (run-spell '(*' 2 3))))
    (is (= 2 (run-spell '(inc' 1))))
    (is (= 0 (run-spell '(dec' 1)))))
  (testing "subs"
    (is (= "llo" (run-spell '(subs "hello" 2))))
    (is (= "el" (run-spell '(subs "hello" 1 3)))))
  (testing "re-find"
    (is (= "123" (run-spell '(re-find "\\d+" "abc123def")))))
  (testing "re-matches"
    (is (= "123" (run-spell '(re-matches "\\d+" "123"))))
    (is (nil? (run-spell '(re-matches "\\d+" "abc123")))))
  (testing "re-seq"
    (is (= ["1" "2" "3"] (run-spell '(re-seq "\\d" "a1b2c3"))))))

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

;; =============================================================================
;; Value store tests
;; =============================================================================

(deftest value-store-test
  (testing "store and retrieve round-trips"
    (let [id (eval/store-value! "hello world")]
      (is (= "hello world" (eval/stored id)))))

  (testing "stored builtin works in spell-eval"
    (let [id (eval/store-value! {:key "value"})]
      (is (= {:key "value"} (run-spell (list 'stored id))))))

  (testing "missing id throws"
    (is (thrown-with-msg? Exception #"No stored value"
          (eval/stored "nonexistent-id"))))

  (testing "serialize-for-continuation inlines small values"
    (is (= "42" (eval/serialize-for-continuation 42)))
    (is (= "\"short\"" (eval/serialize-for-continuation "short"))))

  (testing "serialize-for-continuation inlines medium strings"
    (let [medium-string (apply str (repeat 5000 "x"))
          result (eval/serialize-for-continuation medium-string)]
      (is (.startsWith ^String result "\""))
      (is (not (.contains ^String result "truncated")))))

  (testing "serialize-for-continuation truncates large strings"
    (let [big-string (apply str (repeat 15000 "x"))
          result (eval/serialize-for-continuation big-string)]
      (is (.contains ^String result "truncated"))
      (is (.contains ^String result "15000 chars total"))
      (is (<= (count result) 10100)))) ;; roughly at the limit

  (testing "serialize-for-continuation stores large non-strings"
    (let [big-vec (vec (range 5000))
          result (eval/serialize-for-continuation big-vec)]
      (is (.startsWith ^String result "(stored "))
      (let [forms (spell.parse/read-all result)
            id (second (first forms))]
        (is (= big-vec (eval/stored id))))))

  (testing "serialize-for-continuation with line-offset vector produces do form"
    (let [lines (with-meta ["line one" "line two" "line three"] {:spell/line-offset 10})
          result (eval/serialize-for-continuation lines)]
      (is (.startsWith ^String result "(do "))
      (is (.contains ^String result "10: line one"))
      (is (.contains ^String result "12: line three"))
      ;; Round-trip: evaluating the form should yield the original vector
      (let [parsed (first (spell.parse/read-all result))
            evaled (run-spell parsed)]
        (is (= ["line one" "line two" "line three"] evaled)))))

  (testing "serialize-for-continuation with line-offset offset=1"
    (let [lines (with-meta ["alpha" "beta"] {:spell/line-offset 1})
          result (eval/serialize-for-continuation lines)]
      (is (.contains ^String result "1: alpha"))
      (is (.contains ^String result "2: beta"))))

  (testing "serialize-for-continuation with empty line-offset vector"
    (let [lines (with-meta [] {:spell/line-offset 5})
          result (eval/serialize-for-continuation lines)]
      (is (.startsWith ^String result "(do "))
      (let [parsed (first (spell.parse/read-all result))
            evaled (run-spell parsed)]
        (is (= [] evaled))))))

;; =============================================================================
;; New special forms (from verified clojure.core audit)
;; =============================================================================

(deftest test-if-let
  (testing "truthy binding executes then branch"
    (is (= 10 (run-spell '(if-let [x 5] (* x 2) 0)))))
  (testing "falsy binding executes else branch"
    (is (= 0 (run-spell '(if-let [x nil] (* x 2) 0))))
    (is (= 0 (run-spell '(if-let [x false] 1 0)))))
  (testing "no else branch returns nil on falsy"
    (is (nil? (run-spell '(if-let [x nil] 42)))))
  (testing "binding available in then branch"
    (is (= "hello" (run-spell '(if-let [x "hello"] x "default")))))
  (testing "binding does not leak"
    (is (= 99 (run-spell '(do (def y 99) (if-let [x 1] y) y))))))

(deftest test-when-let
  (testing "truthy binding executes body"
    (is (= 10 (run-spell '(when-let [x 5] (* x 2))))))
  (testing "falsy binding returns nil"
    (is (nil? (run-spell '(when-let [x nil] 42)))))
  (testing "multiple body expressions"
    (is (= 3 (run-spell '(when-let [x 1] (+ x 1) (+ x 2)))))))

(deftest test-case
  (testing "matching clause"
    (is (= "one" (run-spell '(case 1 1 "one" 2 "two" "other"))))
    (is (= "two" (run-spell '(case 2 1 "one" 2 "two" "other")))))
  (testing "default clause"
    (is (= "other" (run-spell '(case 3 1 "one" 2 "two" "other")))))
  (testing "no default and no match throws"
    (is (thrown? Exception (run-spell '(case 99 1 "one" 2 "two")))))
  (testing "works with strings"
    (is (= :found (run-spell '(case "hello" "hello" :found "world" :nope :default)))))
  (testing "works with keywords"
    (is (= "yes" (run-spell '(case :a :a "yes" :b "no"))))))

(deftest test-as->
  (testing "basic named threading"
    (is (= 6 (run-spell '(as-> 1 x (+ x 1) (* x 2) (+ x 2))))))
  (testing "name available in any position"
    (is (= [1 2 3] (run-spell '(as-> [1 2] v (conj v 3))))))
  (testing "single form"
    (is (= 2 (run-spell '(as-> 1 x (+ x 1)))))))

(deftest test-cond->
  (testing "conditional threading"
    (is (= 3 (run-spell '(cond-> 1 true (+ 1) true (+ 1) false (+ 10))))))
  (testing "all false"
    (is (= 1 (run-spell '(cond-> 1 false (+ 10) false (+ 20))))))
  (testing "with function forms"
    (is (= 6 (run-spell '(cond-> 5 true (+ 1) false (* 100)))))))

(deftest test-cond->>
  (testing "conditional thread-last"
    ;; (conj [0] [1 2 3]) => [0 [1 2 3]] — thread-last appends to end
    (is (= [0 [1 2 3]] (run-spell '(cond->> [1 2 3] true (conj [0]) false (conj [99]))))))
  (testing "all conditions false"
    (is (= 5 (run-spell '(cond->> 5 false (+ 10) false (* 2)))))))

(deftest test-some->
  (testing "nil short-circuits"
    (is (nil? (run-spell '(some-> nil (+ 1))))))
  (testing "non-nil threads through"
    (is (= 3 (run-spell '(some-> 1 (+ 1) (+ 1))))))
  (testing "nil mid-chain short-circuits"
    (is (nil? (run-spell '(some-> {:a 1} (get :b) (+ 1))))))
  (testing "full chain succeeds"
    (is (= 2 (run-spell '(some-> {:a 1} (get :a) (+ 1)))))))

(deftest test-some->>
  (testing "nil short-circuits"
    (is (nil? (run-spell '(some->> nil (+ 1))))))
  (testing "non-nil threads through"
    (is (= 3 (run-spell '(some->> 1 (+ 1) (+ 1))))))
  (testing "thread-last position"
    (is (= [2 3 4] (run-spell '(some->> [1 2 3] (map inc)))))))

;; =============================================================================
;; New core builtins (from verified clojure.core audit)
;; =============================================================================

(deftest test-new-builtins
  (testing "any? always returns true"
    (is (true? (run-spell '(any? nil))))
    (is (true? (run-spell '(any? 42))))
    (is (true? (run-spell '(any? false)))))

  (testing "boolean coercion"
    (is (true? (run-spell '(boolean 1))))
    (is (false? (run-spell '(boolean nil))))
    (is (false? (run-spell '(boolean false))))
    (is (true? (run-spell '(boolean "hi")))))

  (testing "boolean? predicate"
    (is (true? (run-spell '(boolean? true))))
    (is (true? (run-spell '(boolean? false))))
    (is (false? (run-spell '(boolean? 1))))
    (is (false? (run-spell '(boolean? nil)))))

  (testing "dedupe removes consecutive duplicates"
    (is (= [1 2 3 1] (run-spell '(dedupe [1 1 2 2 3 1 1])))))

  (testing "distinct? checks all args distinct"
    (is (true? (run-spell '(distinct? 1 2 3))))
    (is (false? (run-spell '(distinct? 1 2 1)))))

  (testing "drop-last"
    (is (= [1 2] (run-spell '(drop-last [1 2 3]))))
    (is (= [1] (run-spell '(drop-last 2 [1 2 3])))))

  (testing "ffirst"
    (is (= 1 (run-spell '(ffirst [[1 2] [3 4]])))))

  (testing "find returns map entry or nil"
    (is (= [:a 1] (vec (run-spell '(find {:a 1 :b 2} :a)))))
    (is (nil? (run-spell '(find {:a 1} :z)))))

  (testing "format string formatting"
    (is (= "hello world" (run-spell '(format "%s %s" "hello" "world"))))
    (is (= "num: 42" (run-spell '(format "num: %d" 42)))))

  (testing "keep-indexed"
    (is (= [0 2 4] (run-spell '(keep-indexed (fn [i x] (if (even? i) x nil)) [0 1 2 3 4])))))

  (testing "list*"
    (is (= '(1 2 3 4) (run-spell '(list* 1 2 [3 4])))))

  (testing "memoize caches results"
    (is (= 6 (run-spell '(let [f (memoize (fn [x] (* x 2)))]
                            (f 3)))))
    ;; Same input returns cached
    (is (= [4 4] (run-spell '(let [f (memoize (fn [x] (* x 2)))]
                                [(f 2) (f 2)])))))

  (testing "namespace extracts namespace"
    (is (= "foo" (run-spell '(namespace :foo/bar))))
    (is (nil? (run-spell '(namespace :bar)))))

  (testing "next returns nil for empty"
    (is (= '(2 3) (run-spell '(next [1 2 3]))))
    (is (nil? (run-spell '(next [1]))))
    (is (nil? (run-spell '(next [])))))

  (testing "not-every?"
    (is (true? (run-spell '(not-every? even? [1 2 3]))))
    (is (false? (run-spell '(not-every? even? [2 4 6])))))

  (testing "parse-boolean"
    (is (true? (run-spell '(parse-boolean "true"))))
    (is (false? (run-spell '(parse-boolean "false"))))
    (is (nil? (run-spell '(parse-boolean "maybe")))))

  (testing "partition-by"
    (is (= [[1 1] [2 2] [3]] (run-spell '(partition-by identity [1 1 2 2 3])))))

  (testing "rand-nth returns element from collection"
    (is (contains? #{1 2 3} (run-spell '(rand-nth [1 2 3])))))

  (testing "random-sample returns subset"
    (let [result (run-spell '(random-sample 0.5 (range 100)))]
      (is (vector? result))
      (is (<= (count result) 100))))

  (testing "random-uuid returns string"
    (let [result (run-spell '(random-uuid))]
      (is (string? result))
      (is (= 36 (count result)))))

  (testing "reduced for early termination"
    (is (= 3 (run-spell '(reduce (fn [acc x] (if (= x 3) (reduced acc) (+ acc x))) 0 [1 2 3 4 5])))))

  (testing "reductions"
    (is (= [1 3 6 10] (run-spell '(reductions + [1 2 3 4]))))
    (is (= [0 1 3 6 10] (run-spell '(reductions + 0 [1 2 3 4])))))

  (testing "seq returns nil for empty"
    (is (nil? (run-spell '(seq []))))
    (is (nil? (run-spell '(seq nil))))
    (is (some? (run-spell '(seq [1])))))

  (testing "shuffle returns permutation"
    (let [result (run-spell '(shuffle [1 2 3 4 5]))]
      (is (= (sort result) [1 2 3 4 5]))))

  (testing "split-with"
    (is (= [[1 2] [3 4 5]] (run-spell '(split-with (fn [x] (< x 3)) [1 2 3 4 5])))))

  (testing "take-nth"
    (is (= [0 3 6 9] (run-spell '(take-nth 3 (range 10))))))

  (testing "tree-seq"
    (is (= [[1 [2 3]] 1 [2 3] 2 3] (run-spell '(tree-seq sequential? identity [1 [2 3]])))))

  (testing "type returns type name"
    (is (= "number" (run-spell '(type 42))))
    (is (= "string" (run-spell '(type "hi"))))
    (is (= "nil" (run-spell '(type nil))))
    (is (= "vector" (run-spell '(type [1 2]))))
    (is (= "map" (run-spell '(type {:a 1}))))
    (is (= "keyword" (run-spell '(type :foo))))
    (is (= "function" (run-spell '(type inc))))
    (is (= "boolean" (run-spell '(type true)))))

  (testing "update-keys"
    (is (= {"a" 1 "b" 2} (run-spell '(update-keys {:a 1 :b 2} name)))))

  (testing "update-vals"
    (is (= {:a 2 :b 3} (run-spell '(update-vals {:a 1 :b 2} inc)))))

  (testing "bit-and-not"
    (is (= 2 (run-spell '(bit-and-not 6 4))))))

(deftest gensym-test
  (testing "gensym returns a symbol"
    (is (symbol? (run-spell '(gensym)))))
  (testing "gensym with prefix"
    (let [s (run-spell '(gensym "or__"))]
      (is (symbol? s))
      (is (.startsWith (str s) "or__"))))
  (testing "gensym produces unique symbols"
    (is (not= (run-spell '(gensym)) (run-spell '(gensym))))))

(deftest macro-infrastructure-test
  (testing "registered macro expands and evaluates"
    ;; Register a trivial test macro: (double x) -> (+ x x)
    (macros/defspellmacro 'test-double (fn [x] (list '+ x x)))
    (try
      (is (= 10 (run-spell '(test-double 5))))
      (is (= 6 (run-spell '(test-double 3))))
      (finally
        (swap! macros/spell-macros dissoc 'test-double)))))

;; =============================================================================
;; Vector destructuring in fn params (#81)
;; =============================================================================

(deftest fn-vector-destructuring
  (testing "basic pair destructuring"
    (is (= 3 (run-spell '((fn [[x y]] (+ x y)) [1 2])))))

  (testing "destructuring with defn"
    (is (= 5 (run-spell '(do (defn sum-pair [[a b]] (+ a b))
                              (sum-pair [2 3]))))))

  (testing "mixed params - some destructured, some not"
    (is (= 10 (run-spell '((fn [n [x y]] (+ n x y)) 4 [3 3])))))

  (testing "nested destructuring"
    (is (= 6 (run-spell '((fn [[[a b] c]] (+ a b c)) [[1 2] 3])))))

  (testing "destructuring with & rest"
    (is (= [1 [2 3 4]] (run-spell '((fn [[x & rest]] [x rest]) [1 2 3 4])))))

  (testing "destructuring with :as"
    (is (= [1 2 [1 2 3]] (run-spell '((fn [[x y :as all]] [x y all]) [1 2 3])))))

  (testing "destructuring with & rest and :as"
    (is (= [1 [2 3] [1 2 3]]
           (run-spell '((fn [[x & rest :as all]] [x rest all]) [1 2 3])))))

  (testing "nil elements in destructuring"
    (is (= [1 nil] (run-spell '((fn [[x y]] [x y]) [1])))))

  (testing "map with destructured fn in higher-order"
    (is (= [3 5 7] (run-spell '(map (fn [[a b]] (+ a b)) [[1 2] [2 3] [3 4]])))))

  (testing "destructuring in reduce"
    (is (= 10 (run-spell '(reduce (fn [acc [k v]] (+ acc v))
                                   0
                                   [[:a 1] [:b 2] [:c 3] [:d 4]])))))

  (testing "apply with destructured fn"
    (is (= 3 (run-spell '(apply (fn [[x y]] (+ x y)) [[1 2]])))))

  (testing "recur with destructured params"
    (is (= 6 (run-spell '((fn [[x acc]]
                             (if (zero? x)
                               acc
                               (recur [(dec x) (+ acc x)])))
                           [3 0])))))

  (testing "expand preserves destructuring pattern"
    (let [[expanded _] (eval-ok '(expand '(fn [[x y]] (+ x y z))) {'z 10})]
      ;; The fn form should be preserved with destructuring params intact
      (is (= 'fn (first expanded)))
      (is (vector? (second expanded)))
      (is (vector? (first (second expanded)))))))

(deftest fn-destructuring-with-let
  (testing "destructured fn inside let"
    (is (= 15 (run-spell '(let [f (fn [[a b]] (* a b))]
                             (f [3 5]))))))

  (testing "destructured fn with dynamic scoping"
    ;; y is defined at call site, not at definition site
    (is (= 11 (run-spell '(do (defn add-y [[x]] (+ x y))
                               (def y 10)
                               (add-y [1])))))))

(deftest let-vector-destructuring
  (testing "basic let destructuring"
    (is (= 3 (run-spell '(let [[x y] [1 2]] (+ x y))))))

  (testing "nested let destructuring"
    (is (= 6 (run-spell '(let [[[a b] c] [[1 2] 3]] (+ a b c))))))

  (testing "let destructuring with & rest"
    (is (= [1 [2 3]] (run-spell '(let [[x & rest] [1 2 3]] [x rest])))))

  (testing "let destructuring with :as"
    (is (= [1 2 [1 2 3]] (run-spell '(let [[x y :as all] [1 2 3]] [x y all])))))

  (testing "expand with let destructuring"
    (let [[expanded _] (eval-ok '(expand '(let [[a b] pair] (+ a b z))) {'z 10})]
      ;; z should be substituted, a and b should not
      (is (= 'let (first expanded)))
      (is (vector? (first (second expanded)))))))

;; =============================================================================
;; Map destructuring
;; =============================================================================

(deftest map-destructuring-keys
  (testing ":keys in let"
    (is (= [1 2] (run-spell '(let [{:keys [a b]} {:a 1 :b 2}] [a b])))))

  (testing ":keys with missing key returns nil"
    (is (= [1 nil] (run-spell '(let [{:keys [a b]} {:a 1}] [a b])))))

  (testing ":keys in fn"
    (is (= 3 (run-spell '((fn [{:keys [x y]}] (+ x y)) {:x 1 :y 2})))))

  (testing ":keys in defn"
    (is (= 6 (run-spell '(do (defn sum-map [{:keys [a b c]}] (+ a b c))
                              (sum-map {:a 1 :b 2 :c 3})))))))

(deftest map-destructuring-strs
  (testing ":strs in let"
    (is (= [1 2] (run-spell '(let [{:strs [a b]} {"a" 1 "b" 2}] [a b]))))))

(deftest map-destructuring-syms
  (testing ":syms in let"
    (is (= [1 2] (run-spell '(let [m (assoc {} (quote a) 1 (quote b) 2)
                                    {:syms [a b]} m]
                               [a b]))))))

(deftest map-destructuring-or
  (testing ":or provides defaults for missing keys"
    (is (= [1 42] (run-spell '(let [{:keys [a b] :or {b 42}} {:a 1}] [a b])))))

  (testing ":or does not override present key"
    (is (= [1 2] (run-spell '(let [{:keys [a b] :or {b 42}} {:a 1 :b 2}] [a b])))))

  (testing ":or replaces explicit nil"
    (is (= [99] (run-spell '(let [{:keys [a] :or {a 99}} {:a nil}] [a]))))))

(deftest map-destructuring-as
  (testing ":as binds the whole map"
    (is (= [{:x 1 :y 2} 1 2]
           (run-spell '(let [{:keys [x y] :as m} {:x 1 :y 2}] [m x y]))))))

(deftest map-destructuring-direct
  (testing "direct {sym key} binding"
    (is (= [1 2] (run-spell '(let [{x :a y :b} {:a 1 :b 2}] [x y])))))

  (testing "direct binding with string key"
    (is (= 42 (run-spell '(let [{x "name"} {"name" 42}] x))))))

(deftest map-destructuring-nested
  (testing "map inside vector"
    (is (= [1 2 3]
           (run-spell '(let [[a {:keys [b c]}] [1 {:b 2 :c 3}]] [a b c])))))

  (testing "vector inside map"
    (is (= [1 2 3]
           (run-spell '(let [{[a b] :pair c :c} {:pair [1 2] :c 3}] [a b c])))))

  (testing "nested maps"
    (is (= 42
           (run-spell '(let [{:keys [outer]} {:outer {:inner 42}}]
                         (:inner outer)))))))

(deftest map-destructuring-higher-order
  (testing "map with :keys destructuring fn"
    (is (= [3 7 11]
           (run-spell '(map (fn [{:keys [a b]}] (+ a b))
                            [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6}])))))

  (testing "reduce with map destructuring"
    (is (= 6
           (run-spell '(reduce (fn [acc {:keys [v]}] (+ acc v))
                               0
                               [{:v 1} {:v 2} {:v 3}])))))

  (testing "filter with map destructuring"
    (is (= [{:x 3} {:x 4}]
           (run-spell '(filter (fn [{:keys [x]}] (> x 2))
                               [{:x 1} {:x 2} {:x 3} {:x 4}]))))))

(deftest map-destructuring-for
  (testing "for with map destructuring iter"
    (is (= [1 2 3]
           (run-spell '(for [{:keys [x]} [{:x 1} {:x 2} {:x 3}]] x)))))

  (testing "for with map destructuring in :let"
    (is (= [10 20 30]
           (run-spell '(for [m [{:v 1} {:v 2} {:v 3}]
                             :let [{:keys [v]} m]]
                         (* v 10)))))))

(deftest map-destructuring-loop
  (testing "loop with map destructuring"
    (is (= 6
           (run-spell '(loop [{:keys [n acc]} {:n 3 :acc 0}]
                         (if (zero? n)
                           acc
                           (recur {:n (dec n) :acc (+ acc n)}))))))))

(deftest map-destructuring-expand
  (testing "expand tracks :keys symbols"
    (let [[expanded _] (eval-ok '(expand '(let [{:keys [a b]} m] (+ a b z))) {'z 10})]
      ;; z should be substituted with 10, a and b should remain as symbols
      (is (= 'let (first expanded)))
      (is (= 10 (last (last expanded))))))

  (testing "expand tracks :keys in fn params"
    (let [[expanded _] (eval-ok '(expand '(fn [{:keys [x]}] (+ x z))) {'z 5})]
      (is (= 'fn (first expanded)))
      ;; z should be substituted
      (is (= 5 (last (last expanded))))))

  (testing "expand tracks map destructuring in loop"
    (let [[expanded _] (eval-ok '(expand '(loop [{:keys [n]} {:n start}] n)) {'start 10})]
      (is (= 'loop (first expanded)))))

  (testing "expand tracks map destructuring in for"
    (let [[expanded _] (eval-ok '(expand '(for [{:keys [x]} items] (+ x z))) {'z 5 'items []})]
      (is (= 'for (first expanded))))))

;; =============================================================================
;; define macro (#84)
;; =============================================================================

(deftest define-as-def-alias
  (testing "define works as alias for def"
    (is (= 42 (run-spell '(do (define x 42) x)))))

  (testing "define with expression"
    (is (= 10 (run-spell '(do (define y (+ 3 7)) y)))))

  (testing "define in sequence"
    (is (= 15 (run-spell '(do (define a 5)
                               (define b 10)
                               (+ a b))))))

  (testing "define is recognized as macro in expand"
    ;; expand should handle define forms without error
    (let [[expanded _] (eval-ok '(expand '(define x (+ y 1))) {'y 10})]
      ;; Should expand to (def x (+ 10 1))
      (is (= 'def (first expanded))))))

;; =============================================================================
;; print macro (#85)
;; =============================================================================

(deftest print-macro-expansion
  (testing "print macro expands to let + llm-self with serialize"
    (let [expanded (macros/spell-macroexpand-1 '(print (+ 1 2)))]
      ;; Should be (let [temp (+ 1 2)] (llm-self (str (reopen completion) (serialize temp) " ")))
      (is (= 'let (first expanded)))
      (let [body (nth expanded 2)]
        (is (= 'llm-self (first body))))))

  (testing "print macro multi-arity"
    (let [expanded (macros/spell-macroexpand-1 '(print a b c))]
      (is (= 'let (first expanded)))
      ;; bindings should have 6 elements (3 pairs)
      (is (= 6 (count (second expanded))))
      (let [body (nth expanded 2)]
        (is (= 'llm-self (first body)))))))

;; =============================================================================
;; Think / Rethink / Extend
;; =============================================================================

(deftest think-macro-test
  (testing "think evaluates body and returns nil"
    (is (nil? (run-spell '(think "reasoning" (+ 1 2))))))

  (testing "think with no body returns nil"
    (is (nil? (run-spell '(think "just a thought")))))

  (testing "think body produces side effects (bindings)"
    (is (= 42 (run-spell '(do (think "compute x" (def x 42)) x)))))

  (testing "multiple thinks, last value is from code after"
    (is (= 5 (run-spell '(do (think "step 1" (def a 2))
                              (think "step 2" (def b 3))
                              (+ a b)))))))

(deftest rethink-macro-test
  (testing "rethink evaluates body and returns nil"
    (is (nil? (run-spell '(rethink "new approach" (+ 1 2))))))

  (testing "rethink with count evaluates body and returns nil"
    (is (nil? (run-spell '(rethink 2 "new approach" (+ 1 2))))))

  (testing "rethink with no body returns nil"
    (is (nil? (run-spell '(rethink "just rethinking")))))

  (testing "rethink body produces side effects"
    (is (= 99 (run-spell '(do (think "old" (def x 1))
                               (rethink "new" (def x 99))
                               x))))))

(deftest prune-rethinks-test
  (testing "no rethinks — pass through unchanged"
    (is (= '(do (think "A" 1) (think "B" 2))
           (macros/prune-rethinks '(do (think "A" 1) (think "B" 2))))))

  (testing "rethink prunes previous sibling"
    (is (= '(do (think "B" 2))
           (macros/prune-rethinks '(do (think "A" 1) (rethink "B" 2))))))

  (testing "rethink prunes any previous sibling, not just think"
    (is (= '(do (think "B" 2))
           (macros/prune-rethinks '(do (def x 1) (rethink "B" 2))))))

  (testing "rethink with count prunes N previous siblings"
    (is (= '(do (think "C" 3))
           (macros/prune-rethinks '(do (think "A" 1) (def x 2) (rethink 2 "C" 3))))))

  (testing "rethink converts to think after pruning"
    (let [result (macros/prune-rethinks '(do (think "A" 1) (rethink "B" 2)))]
      (is (= 'think (first (second result))))))

  (testing "chained rethinks"
    (is (= '(do (think "C" 3))
           (macros/prune-rethinks '(do (think "A" 1)
                                       (rethink "B" 2)
                                       (rethink "C" 3))))))

  (testing "rethink leaves earlier non-targeted siblings intact"
    (is (= '(do (def x 1) (think "B" 3))
           (macros/prune-rethinks '(do (def x 1) (think "A" 2) (rethink "B" 3))))))

  (testing "recursive — rethink inside nested do"
    (is (= '(do (do (think "B" 2)) (def y 3))
           (macros/prune-rethinks '(do (do (think "A" 1) (rethink "B" 2)) (def y 3))))))

  (testing "inner rethink cannot prune outer think"
    ;; rethink inside think's body targets siblings within the body, not the think itself
    (let [result (macros/prune-rethinks '(do (think "outer" (def a 1) (rethink "inner" (def a 2)))))]
      ;; outer think should survive, inner rethink prunes (def a 1) within its body
      (is (= 'think (first (second result))))
      (is (= "outer" (second (second result))))))

  (testing "rethink with count larger than available siblings removes all"
    (is (= '(do (think "Z" 99))
           (macros/prune-rethinks '(do (def a 1) (rethink 5 "Z" 99))))))

  (testing "prune through quine structure"
    (let [result (macros/prune-rethinks
                   '(quine completion (eval (do
                      (think "A" (def x 1))
                      (rethink "B" (def x 2))
                      (quote (extend completion))))))]
      ;; Should prune think "A", convert rethink to think "B"
      (is (= '(quine completion (eval (do
                (think "B" (def x 2))
                (quote (extend completion)))))
             result))))

  (testing "vectors are recursed into but not sibling-processed"
    (is (= '(do [(do (think "B" 2))])
           (macros/prune-rethinks '(do [(do (think "A" 1) (rethink "B" 2))]))))))

(deftest prune-and-reopen-test
  (testing "prune-and-reopen produces open prefix string"
    (let [quine-form '(quine completion (eval (do
                         (think "A" (def x 1))
                         (rethink "B" (def x 2))
                         (quote (extend completion)))))
          result (run-spell (list 'prune-and-reopen (list 'quote quine-form)))]
      ;; Should contain the pruned body as an open prefix
      (is (string? result))
      (is (.startsWith ^String result "(quine completion (eval (do "))
      ;; Should contain think "B" (the converted rethink)
      (is (.contains ^String result "think"))
      (is (.contains ^String result "\"B\""))
      ;; Should NOT contain think "A" (pruned)
      (is (not (.contains ^String result "\"A\"")))))

  (testing "prune-and-reopen with no rethinks passes through"
    (let [quine-form '(quine completion (eval (do (def x 1) (def y 2))))
          result (run-spell (list 'prune-and-reopen (list 'quote quine-form)))]
      (is (.startsWith ^String result "(quine completion (eval (do "))
      (is (.contains ^String result "(def x 1)"))))

  (testing "prune-and-reopen with multi-arg quine preserves inert args"
    (let [quine-form '(quine completion
                        (eval (do (def x 1) (quote (extend completion))))
                        (eval (do (rethink "fix" (def y 2)) (quote (extend completion)))))
          result (run-spell (list 'prune-and-reopen (list 'quote quine-form)))]
      ;; Should start with quine completion
      (is (string? result))
      (is (.startsWith ^String result "(quine completion "))
      ;; Should contain the first (inert) arg serialized
      (is (.contains ^String result "(eval (do (def x 1)"))
      ;; Should contain the pruned last arg
      (is (.contains ^String result "(def y 2)"))))

  (testing "prune-and-reopen with standard (2-arg) quine unchanged behavior"
    (let [quine-form '(quine completion (eval (do (def a 10))))
          result (run-spell (list 'prune-and-reopen (list 'quote quine-form)))]
      (is (.startsWith ^String result "(quine completion (eval (do "))
      (is (.contains ^String result "(def a 10)")))))

(deftest extend-macro-expansion-test
  (testing "extend expands to llm-self with prune-and-reopen"
    (let [expanded (macros/spell-macroexpand-1 '(extend completion))]
      (is (= 'llm-self (first expanded)))
      (is (= '(prune-and-reopen completion) (second expanded))))))

;; =============================================================================
;; Effect-phase error tagging tests
;; =============================================================================

(deftest effect-phase-tagging-test
  (testing "eval builtin works with effect functions"
    ;; Create a custom eval with a 'boom effect function
    (let [boom-effects {'boom (fn [] (throw (Exception. "boom!")))}
          custom-eval (fn [expr]
                        (let [expanded (eval/expand-expr expr eval/*spell-env*)]
                          (binding [eval/*builtins* (merge eval/core-builtins boom-effects)]
                            (let [result (spell-eval expanded {})]
                              (if (eval/ok? result)
                                (:ok result)
                                (throw (ex-info (:err result) {:result result})))))))
          custom-builtins (assoc eval/core-builtins 'eval custom-eval)
          result (binding [eval/*builtins* custom-builtins]
                   (spell-eval '(eval '(boom)) {}))]
      (is (eval/err? result))))

  (testing "first-pass errors in eval argument"
    (let [result (spell-eval '(eval (do unbound-symbol)) {})]
      (is (eval/err? result))))

  (testing "successful eval returns value"
    (let [result (spell-eval '(eval '(+ 1 2)) {})]
      (is (eval/ok? result))
      (is (= 3 (:ok result))))))

;; =============================================================================
;; deep-truncate builtin tests
;; =============================================================================

(deftest deep-truncate-builtin-test
  (testing "short strings unchanged"
    (is (= "hello" (run-spell '(deep-truncate "hello" 100)))))

  (testing "long strings are truncated"
    (let [long-str (apply str (repeat 200 "x"))
          result (run-spell (list 'deep-truncate long-str 50))]
      (is (string? result))
      (is (< (count result) (count long-str)))
      (is (clojure.string/includes? result "truncated"))))

  (testing "maps with long string values are deep-truncated"
    (let [long-str (apply str (repeat 200 "x"))
          input {:a long-str :b "short"}
          result (run-spell (list 'deep-truncate input 50))]
      (is (map? result))
      (is (= "short" (:b result)))
      (is (clojure.string/includes? (:a result) "truncated"))))

  (testing "nested structures are recursively truncated"
    (let [long-str (apply str (repeat 200 "x"))
          input [{:a long-str}]
          result (run-spell (list 'deep-truncate input 50))]
      (is (vector? result))
      (is (clojure.string/includes? (:a (first result)) "truncated"))))

  (testing "non-string values unchanged"
    (is (= 42 (run-spell '(deep-truncate 42 10))))
    (is (= {:a 1 :b 2} (run-spell '(deep-truncate {:a 1 :b 2} 10))))))

;; =============================================================================
;; compact macro expansion tests
;; =============================================================================

(deftest compact-macro-expansion-test
  (testing "compact expands to llm-self with prune-and-reopen + compact instructions"
    (let [expanded (macros/spell-macroexpand-1 '(compact completion))]
      (is (= 'llm-self (first expanded)))
      (is (seq? (second expanded)))
      (is (= 'str (first (second expanded))))
      (is (= '(prune-and-reopen completion) (second (second expanded))))))

  (testing "compact suffix has llm-self/wrap-cat trailing expression"
    (let [expanded (macros/spell-macroexpand-1 '(compact completion))
          suffix-str (nth (second expanded) 2)]
      (is (string? suffix-str))
      (is (clojure.string/includes? suffix-str "=compact="))
      (is (clojure.string/includes? suffix-str "deep-truncate"))
      (is (clojure.string/includes? suffix-str "'(llm-self (wrap-cat ")))))

;; =============================================================================
;; User-defined macros (defmacro)
;; =============================================================================

(deftest defmacro-test
  (testing "basic defmacro defines a macro in env"
    (let [r (spell-eval '(defmacro unless [test body]
                           (list 'if test nil body))
                        {})]
      (is (eval/ok? r))
      (is (eval/spell-macro? (:ok r)))
      (is (eval/spell-fn? (:expander (:ok r))))))

  (testing "macro invocation expands and evaluates"
    (is (= 42 (run-spell '(do (defmacro unless [test body]
                                 (list 'if test nil body))
                               (unless false 42)))))
    (is (= nil (run-spell '(do (defmacro unless [test body]
                                  (list 'if test nil body))
                                (unless true 42))))))

  (testing "macro with gensym for hygiene"
    (is (= 99 (run-spell '(do (defmacro my-when [test body]
                                 (let [g (gensym "t__")]
                                   (list 'let [g test]
                                         (list 'if g body nil))))
                               (my-when true 99)))))
    (is (= nil (run-spell '(do (defmacro my-when [test body]
                                  (let [g (gensym "t__")]
                                    (list 'let [g test]
                                          (list 'if g body nil))))
                                (my-when false 99))))))

  (testing "macro calling another user macro"
    (is (= 77 (run-spell '(do (defmacro unless [test body]
                                 (list 'if test nil body))
                               (defmacro when-not [test body]
                                 (list 'unless test body))
                               (when-not false 77))))))

  (testing "macro with variadic args"
    (is (= 6 (run-spell '(do (defmacro my-do [& forms]
                                (cons 'do forms))
                              (my-do (def x 1) (def y 2) (+ x y 3)))))))

  (testing "macro sees caller env via dynamic scoping"
    ;; tag=true in caller env, so macro body resolves it → (if true nil 5) → nil
    (is (= nil (run-spell '(do (def tag true)
                               (defmacro my-mac [body]
                                 (list 'if tag nil body))
                               (my-mac 5)))))
    ;; tag=false → (if false nil 5) → 5
    (is (= 5 (run-spell '(do (def tag false)
                              (defmacro my-mac [body]
                                (list 'if tag nil body))
                              (my-mac 5))))))

  (testing "macro expansion error propagates"
    (let [r (spell-eval '(do (defmacro bad-macro [x]
                               (/ 1 0))
                             (bad-macro 5))
                        {})]
      (is (eval/err? r))
      (is (clojure.string/includes? (:err r) "Macro expansion"))))

  (testing "macro interacts with Clojure-side macros"
    ;; User macro can expand to forms that use Clojure-side macros
    (is (= 42 (run-spell '(do (defmacro my-cond [test then else]
                                 (list 'cond test then :else else))
                               (my-cond true 42 99))))))

  (testing "macro with list construction builtins"
    (is (= [1 2 3]
           (run-spell '(do (defmacro make-vec [& items]
                             (cons 'vector items))
                           (make-vec 1 2 3)))))))

(deftest defmacro-expand-test
  (testing "expand-expr expands user macros from outer-env"
    (let [;; First, create a proper macro value by evaluating defmacro
          r (spell-eval '(defmacro unless [test body]
                           (list 'if test nil body))
                        {})
          macro-val (get (:env r) 'unless)
          ;; Now test expand with this macro in outer-env
          outer-env {'unless macro-val 'x 5}
          expanded (eval/expand-expr '(unless (= x 0) "nonzero") outer-env)]
      (is (= 'if (first expanded)))
      (is (= '(= 5 0) (second expanded)))))

  (testing "expand-expr does not expand user macro if locally shadowed"
    (let [r (spell-eval '(defmacro unless [test body]
                           (list 'if test nil body))
                        {})
          macro-val (get (:env r) 'unless)
          outer-env {'unless macro-val}
          ;; (let [unless ...] (unless ...)) — unless is locally bound
          expanded (eval/expand-expr '(let [unless 1] (unless false 42)) outer-env)]
      ;; The inner (unless ...) should NOT be expanded because unless is locally bound
      (is (= 'let (first expanded))))))

(deftest defmacro-self-eval-test
  (testing "macro map re-evaluates idempotently"
    (let [r (spell-eval '(defmacro m [x] (list 'inc x)) {})
          macro-val (get (:env r) 'm)
          ;; Re-evaluate the macro map as a literal
          r2 (spell-eval macro-val {})]
      (is (eval/ok? r2))
      (is (eval/spell-macro? (:ok r2)))
      (is (= (:expander macro-val) (:expander (:ok r2)))))))
