(ns spell.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.eval :as eval :refer [spell-eval run-spell]]
            [spell.registry :as registry]))

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
      (is (= (eval expr) (run-spell expr))))))

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

    ;; eval (non-whitelisted)
    (eval '(+ 1 2))

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
    (is (= "hello clojure" (run-spell '(replace "hello world" "world" "clojure")))))
  (testing "split"
    (is (= ["a" "b" "c"] (run-spell '(split "a,b,c" ",")))))
  (testing "join"
    (is (= "a,b,c" (run-spell '(join "," ["a" "b" "c"]))))
    (is (= "abc" (run-spell '(join ["a" "b" "c"])))))
  (testing "lower-case"
    (is (= "hello" (run-spell '(lower-case "HELLO")))))
  (testing "upper-case"
    (is (= "HELLO" (run-spell '(upper-case "hello"))))))

(deftest regex-builtins
  (testing "re-find"
    (is (= "123" (run-spell '(re-find "\\d+" "abc123def")))))
  (testing "re-matches"
    (is (= "123" (run-spell '(re-matches "\\d+" "123"))))
    (is (nil? (run-spell '(re-matches "\\d+" "abc123"))))))

(deftest type-predicates
  (testing "map? excludes spell-fns"
    (is (true? (run-spell '(map? {:a 1}))))
    (is (false? (run-spell '(map? (fn [x] x))))))
  (testing "fn? includes spell-fns"
    (is (true? (run-spell '(fn? +))))
    (is (true? (run-spell '(fn? (fn [x] x)))))))

(deftest apply-with-spell-fn
  (testing "apply with spell function"
    (is (= 9 (run-spell '(do (defn square [x] (* x x)) (apply square [3]))))))
  (testing "apply with multi-arg spell function"
    (is (= 7 (run-spell '(do (defn add [a b] (+ a b)) (apply add [3 4])))))))

;; =============================================================================
;; Higher-order collection functions
;; =============================================================================

(deftest map-builtin
  (testing "map with clojure fn"
    (is (= [2 3 4] (run-spell '(map inc [1 2 3])))))
  (testing "map with spell fn"
    (is (= [1 4 9] (run-spell '(do (defn sq [x] (* x x)) (map sq [1 2 3]))))))
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
    (is (= [4 5 6] (run-spell '(do (defn big [x] (> x 3)) (filter big [1 2 3 4 5 6]))))))
  (testing "filter truthy values"
    (is (= [1 2 3] (run-spell '(filter (fn [x] x) [nil 1 nil 2 3 nil])))))
  (testing "filter returns vector"
    (is (vector? (run-spell '(filter (fn [x] x) [1 2 3])))))
  (testing "filter empty result"
    (is (= [] (run-spell '(filter (fn [x] false) [1 2 3]))))))

(deftest remove-builtin
  (testing "remove keeps non-matching"
    (is (= [1 2 3] (run-spell '(remove (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "remove with spell fn"
    (is (= [1 2] (run-spell '(do (defn big [x] (> x 2)) (remove big [1 2 3 4])))))))

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
    (is (= 3 (run-spell '(some (fn [x] (if (> x 2) x nil)) [1 2 3 4])))))
  (testing "some returns nil when no match"
    (is (nil? (run-spell '(some (fn [x] (if (> x 10) x nil)) [1 2 3])))))
  (testing "some with spell fn"
    (is (= 4 (run-spell '(do (defn find-big [x] (if (> x 3) x nil)) (some find-big [1 2 3 4 5]))))))
  (testing "some empty"
    (is (nil? (run-spell '(some (fn [x] x) []))))))

(deftest every?-builtin
  (testing "every? all match"
    (is (true? (run-spell '(every? (fn [x] (> x 0)) [1 2 3])))))
  (testing "every? some fail"
    (is (false? (run-spell '(every? (fn [x] (> x 2)) [1 2 3])))))
  (testing "every? with spell fn"
    (is (true? (run-spell '(do (defn pos [x] (> x 0)) (every? pos [1 2 3]))))))
  (testing "every? empty is true"
    (is (true? (run-spell '(every? (fn [x] false) []))))))

(deftest keep-builtin
  (testing "keep removes nils"
    (is (= [3 4] (run-spell '(keep (fn [x] (if (> x 2) x nil)) [1 2 3 4])))))
  (testing "keep with spell fn"
    (is (= [9 16] (run-spell '(do (defn sq-if-big [x] (if (> x 2) (* x x) nil)) (keep sq-if-big [1 2 3 4]))))))
  (testing "keep empty"
    (is (= [] (run-spell '(keep (fn [x] nil) [1 2 3]))))))

(deftest mapcat-builtin
  (testing "mapcat flattens"
    (is (= [1 1 2 2 3 3] (run-spell '(mapcat (fn [x] [x x]) [1 2 3])))))
  (testing "mapcat with spell fn"
    (is (= [1 2 2 3 3 4] (run-spell '(do (defn expand [x] [x (+ x 1)]) (mapcat expand [1 2 3]))))))
  (testing "mapcat returns vector"
    (is (vector? (run-spell '(mapcat (fn [x] [x]) [1 2]))))))

(deftest take-while-builtin
  (testing "take-while basic"
    (is (= [1 2] (run-spell '(take-while (fn [x] (< x 3)) [1 2 3 4])))))
  (testing "take-while none match"
    (is (= [] (run-spell '(take-while (fn [x] (> x 10)) [1 2 3])))))
  (testing "take-while with spell fn"
    (is (= [1 2 3] (run-spell '(do (defn small [x] (< x 4)) (take-while small [1 2 3 4 5])))))))

(deftest drop-while-builtin
  (testing "drop-while basic"
    (is (= [3 4 5] (run-spell '(drop-while (fn [x] (< x 3)) [1 2 3 4 5])))))
  (testing "drop-while all match"
    (is (= [] (run-spell '(drop-while (fn [x] (< x 10)) [1 2 3])))))
  (testing "drop-while with spell fn"
    (is (= [4 5] (run-spell '(do (defn small [x] (< x 4)) (drop-while small [1 2 3 4 5])))))))

(deftest group-by-builtin
  (testing "group-by basic"
    (is (= {true [4 5] false [1 2 3]}
           (run-spell '(group-by (fn [x] (> x 3)) [1 2 3 4 5])))))
  (testing "group-by with spell fn"
    (is (= {"small" [1 2] "big" [4 5]}
           (run-spell '(do (defn size [x] (if (> x 3) "big" "small")) (group-by size [1 2 4 5])))))))

(deftest sort-by-builtin
  (testing "sort-by basic"
    (is (= [{:n 1} {:n 2} {:n 3}]
           (run-spell '(sort-by (fn [x] (get x :n)) [{:n 3} {:n 1} {:n 2}])))))
  (testing "sort-by with spell fn"
    (is (= ["a" "bb" "ccc"]
           (run-spell '(do (defn len [s] (count s)) (sort-by len ["bb" "ccc" "a"])))))))

(deftest find-first-builtin
  (testing "find-first returns element"
    (is (= 3 (run-spell '(find-first (fn [x] (> x 2)) [1 2 3 4])))))
  (testing "find-first returns nil when not found"
    (is (nil? (run-spell '(find-first (fn [x] (> x 10)) [1 2 3])))))
  (testing "find-first with spell fn"
    (is (= 4 (run-spell '(do (defn big [x] (> x 3)) (find-first big [1 2 3 4 5])))))))

(deftest not-any?-builtin
  (testing "not-any? all fail predicate"
    (is (true? (run-spell '(not-any? (fn [x] (> x 10)) [1 2 3])))))
  (testing "not-any? some pass predicate"
    (is (false? (run-spell '(not-any? (fn [x] (> x 2)) [1 2 3])))))
  (testing "not-any? empty is true"
    (is (true? (run-spell '(not-any? (fn [x] true) []))))))

(deftest distinct-builtin
  (testing "distinct removes duplicates"
    (is (= [1 2 3] (run-spell '(distinct [1 2 1 3 2 1])))))
  (testing "distinct preserves order"
    (is (= [3 1 2] (run-spell '(distinct [3 1 2 3 1]))))))

(deftest flatten-builtin
  (testing "flatten nested"
    (is (= [1 2 3 4 5] (run-spell '(flatten [[1 2] [3 [4 5]]])))))
  (testing "flatten already flat"
    (is (= [1 2 3] (run-spell '(flatten [1 2 3]))))))

(deftest frequencies-builtin
  (testing "frequencies counts"
    (is (= {1 3 2 2 3 1} (run-spell '(frequencies [1 1 1 2 2 3]))))))

(deftest partition-builtin
  (testing "partition basic"
    (is (= [[1 2] [3 4]] (run-spell '(partition 2 [1 2 3 4 5])))))
  (testing "partition with step"
    (is (= [[1 2] [2 3] [3 4]] (run-spell '(partition 2 1 [1 2 3 4]))))))

(deftest partition-all-builtin
  (testing "partition-all includes partial"
    (is (= [[1 2] [3 4] [5]] (run-spell '(partition-all 2 [1 2 3 4 5])))))
  (testing "partition-all with step"
    (is (= [[1 2] [3 4] [5]] (run-spell '(partition-all 2 2 [1 2 3 4 5]))))))

(deftest interleave-builtin
  (testing "interleave two colls"
    (is (= [1 :a 2 :b 3 :c] (run-spell '(interleave [1 2 3] [:a :b :c])))))
  (testing "interleave uneven"
    (is (= [1 :a 2 :b] (run-spell '(interleave [1 2 3] [:a :b]))))))

(deftest interpose-builtin
  (testing "interpose separator"
    (is (= [1 0 2 0 3] (run-spell '(interpose 0 [1 2 3]))))))

(deftest zipmap-builtin
  (testing "zipmap basic"
    (is (= {:a 1 :b 2} (run-spell '(zipmap [:a :b] [1 2])))))
  (testing "zipmap uneven"
    (is (= {:a 1 :b 2} (run-spell '(zipmap [:a :b :c] [1 2]))))))

(deftest take-drop-builtin
  (testing "take"
    (is (= [1 2 3] (run-spell '(take 3 [1 2 3 4 5])))))
  (testing "drop"
    (is (= [4 5] (run-spell '(drop 3 [1 2 3 4 5])))))
  (testing "split-at"
    (is (= [[1 2] [3 4 5]] (run-spell '(split-at 2 [1 2 3 4 5]))))))

(deftest comp-builtin
  (testing "comp two fns"
    (is (= 7 (run-spell '((comp inc inc) 5)))))
  (testing "comp with spell-fns"
    (is (= 11 (run-spell '(do (defn dbl [x] (* x 2))
                              (defn add1 [x] (+ x 1))
                              ((comp add1 dbl) 5))))))
  (testing "comp three fns"
    (is (= 12 (run-spell '((comp inc inc inc) 9))))))

(deftest partial-builtin
  (testing "partial with clojure fn"
    (is (= 15 (run-spell '((partial + 10) 5)))))
  (testing "partial with spell-fn"
    (is (= 15 (run-spell '(do (defn add [a b] (+ a b))
                              ((partial add 10) 5))))))
  (testing "partial multiple args"
    (is (= 6 (run-spell '((partial + 1 2) 3))))))

(deftest juxt-builtin
  (testing "juxt basic"
    (is (= [6 4] (run-spell '((juxt inc dec) 5)))))
  (testing "juxt with spell-fns"
    (is (= [25 10] (run-spell '(do (defn sq [x] (* x x))
                                   (defn dbl [x] (* x 2))
                                   ((juxt sq dbl) 5))))))
  (testing "juxt three fns"
    (is (= [5 6 4] (run-spell '((juxt (fn [x] x) inc dec) 5))))))

(deftest complement-builtin
  (testing "complement basic"
    (is (true? (run-spell '((complement (fn [x] (> x 10))) 5)))))
  (testing "complement with spell-fn"
    (is (false? (run-spell '(do (defn big [x] (> x 3))
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
    (is (= 9 (run-spell '(do (defn square [x] (* x x)) (square 3))))))

  (testing "defn with multiple params"
    (is (= 7 (run-spell '(do (defn add [a b] (+ a b)) (add 3 4)))))))

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
;; uneval tests
;; =============================================================================

(deftest uneval-form
  (testing "basic uneval returns raw source expression"
    ;; (def x (uneval 'x)) should bind x to its own source expression
    (let [[val _] (spell-eval '(def x (uneval 'x)) {})]
      (is (= '(uneval 'x) val))))

  (testing "uneval enables self-referential code"
    ;; uneval returns the raw source expression (no quote wrapper)
    (let [[val _] (spell-eval '(def my-code (vector (uneval 'my-code))) {})]
      ;; my-code is a vector containing its own source expression
      (is (= ['(vector (uneval 'my-code))] val))))

  (testing "uneval inside larger expression"
    ;; (def x (do (def inner 1) (uneval 'x)))
    ;; should return the source of the entire val-expr
    (let [[val _] (spell-eval '(def x (do (def inner 1) (uneval 'x))) {})]
      (is (= '(do (def inner 1) (uneval 'x)) val))))

  (testing "uneval requires symbol argument"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"argument must evaluate to a symbol"
                          (spell-eval '(def x (uneval 42)) {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"argument must evaluate to a symbol"
                          (spell-eval '(def x (uneval "str")) {}))))

  (testing "uneval on undefined symbol throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"symbol not found in quote environment"
                          (spell-eval '(do (uneval 'undefined)) {}))))

  (testing "uneval only sees current binding's quote-env"
    ;; After (def a ...) completes, its quote is no longer available
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"symbol not found in quote environment"
                          (spell-eval '(do (def a 1) (def b (uneval 'a))) {}))))

  (testing "uneval with computed symbol"
    ;; (uneval (first '[x])) should work - argument evaluates to symbol 'x
    (let [[val _] (spell-eval '(def x (uneval (first '[x]))) {})]
      (is (= '(uneval (first '[x])) val))))

  (testing "nested def does not pollute outer quote-env"
    ;; Inner def's quote-env shouldn't leak to outer
    (let [[val _] (spell-eval '(def outer (do (def inner 1) (uneval 'outer))) {})]
      ;; Should get source of outer's expression, not inner's
      (is (= '(do (def inner 1) (uneval 'outer)) val))))

  (testing "uneval + pr-str reconstructs source faithfully"
    ;; Key property: (cat "(def x " (pr-str (uneval 'x)) ")") reproduces the source
    ;; The reconstructed string should start with (def x (cat ..., not (def x (quote (cat ...
    (let [[val _] (spell-eval '(def x (cat "(def x " (pr-str (uneval 'x)) ")")) {})]
      (is (string? val))
      (is (.startsWith ^String val "(def x (cat"))
      ;; No extra (quote ...) wrapper around the val-expr
      (is (not (.startsWith ^String val "(def x (quote "))))))

;; =============================================================================
;; Extract tests - PENDING REIMPLEMENTATION
;; =============================================================================
;; extract has been removed and will be reimplemented differently.
;; These tests are preserved for reference but currently disabled.

(comment
  (deftest extract-test
    (testing "simple extraction from thunk"
      (let [env {'prompt '(do (def x 42) (def y "hello"))}]
        (is (= 42 (extract '[prompt x] env)))
        (is (= "hello" (extract '[prompt y] env)))))

    (testing "extraction with computed values"
      (let [env {'prompt '(do (def result (+ 1 2 3)))}]
        (is (= 6 (extract '[prompt result] env)))))

    (testing "extraction with conditional logic"
      (let [env {'prompt '(do (def status (if true "yes" "no")))}]
        (is (= "yes" (extract '[prompt status] env)))))

    (testing "nested thunk extraction"
      (let [env {'outer '(do (def inner '(do (def deep 999))))}]
        (is (= 999 (extract '[outer inner deep] env)))))

    (testing "extraction preserves thunks"
      (let [env {'prompt '(do (def thunk '(+ 1 2)))}
            extracted (extract '[prompt thunk] env)]
        ;; The thunk itself should be extracted, not evaluated
        (is (= '(+ 1 2) extracted))))

    (testing "extraction from env with existing bindings"
      ;; The thunk is evaluated in fresh env, so outer bindings don't interfere
      (let [env {'x 100  ; this x should NOT be seen by the thunk
                 'prompt '(do (def x 1) (def y (+ x 1)))}]
        (is (= 1 (extract '[prompt x] env)))
        (is (= 2 (extract '[prompt y] env)))))

    (testing "missing symbol throws"
      (let [env {'prompt '(do (def x 1))}]
        (is (nil? (extract '[prompt missing] env)))))  ; nil for missing, or throw?

    (testing "llm call in def value throws"
      (let [env {'prompt '(do (def x (llm "test")))}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"target binding contains llm call"
                              (extract '[prompt x] env)))))

    (testing "llm call in defn body is allowed"
      ;; defn bodies aren't evaluated during extraction - only when called
      (let [env {'prompt '(do (defn f [x] (llm x)))}
            f (extract '[prompt f] env)]
        (is (fn? f))))  ; should return a function, not throw

    (testing "llm call in preceding def throws"
      (let [env {'prompt '(do (def setup (llm "init")) (def x 42))}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"preceding definition contains llm call"
                              (extract '[prompt x] env)))))

    (testing "llm call in preceding defn body is allowed"
      ;; preceding defn bodies aren't evaluated either
      (let [env {'prompt '(do (defn helper [x] (llm x)) (def y 42))}]
        (is (= 42 (extract '[prompt y] env)))))

    (testing "llm call after target is not evaluated"
      ;; This should succeed - llm call comes AFTER the target
      (let [env {'prompt '(do (def x 42) (def y (llm "test")))}]
        (is (= 42 (extract '[prompt x] env)))))))

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

  (testing "uneval form passes through unchanged"
    (let [[val _] (spell-eval '(do (def expr (expand '(uneval 'expr)))) {})]
      (is (= '(uneval 'expr) val))))

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
      (is (= '(list (quote hello)) val)))))

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

  (testing "expand preserves uneval self-reference"
    ;; (def expr (expand '(uneval 'expr))) should bind expr to '(uneval 'expr)
    ;; because uneval forms have no free variables
    (let [[val _] (spell-eval '(def expr (expand '(uneval 'expr))) {})]
      (is (= '(uneval 'expr) val))))

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
            (spell-eval 'bash {})))))

)

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
;; Import tests
;; =============================================================================

(deftest import-form
  (testing "import tool binds fn"
    (let [reg {:name 'r :desc {:f "fn"} :items {:f {:type :tool :fn inc}}}
          [val env] (spell-eval '(do (import r :f) (f 5)) {'r reg})]
      (is (= 6 val))
      (is (fn? (env 'f)))))

  (testing "import agent binds fn"
    (let [agent-fn (fn [x] (str "result: " x))
          reg {:name 'r :desc {:a "agent"} :items {:a {:type :agent :fn agent-fn}}}
          [val env] (spell-eval '(do (import r :a) (a "test")) {'r reg})]
      (is (= "result: test" val))
      (is (fn? (env 'a)))))

  (testing "import spell evaluates form"
    (let [reg {:name 'r :desc {:sq "square"} :items {:sq {:type :spell :form '(fn [x] (* x x))}}}
          [val env] (spell-eval '(do (import r :sq) (sq 4)) {'r reg})]
      (is (= 16 val))))

  (testing "import missing key throws"
    (let [reg {:name 'r :desc {} :items {}}]
      (is (thrown-with-msg? Exception #"key not found"
            (spell-eval '(import r :missing) {'r reg})))))

  (testing "import returns the imported value"
    (let [reg {:name 'r :desc {:f "fn"} :items {:f {:type :tool :fn inc}}}
          [val _] (spell-eval '(import r :f) {'r reg})]
      (is (fn? val))))

  (testing "import updates env with correct symbol name"
    (let [reg {:name 'r :desc {:my-tool "tool"} :items {:my-tool {:type :tool :fn str}}}
          [_ env] (spell-eval '(import r :my-tool) {'r reg})]
      (is (contains? env 'my-tool)))))

(deftest import-expand
  (testing "expand handles import form"
    (let [reg {:name 'r :desc {:f "fn"} :items {:f {:type :tool :fn inc}}}
          [val _] (spell-eval '(expand '(import r :f)) {'r reg})]
      ;; Import form should be preserved (registry is substituted as value)
      (is (seq? val))
      (is (= 'import (first val))))))

(deftest describe-builtin
  (testing "describe returns all descriptions"
    (let [reg {:name 'r :desc {:a "first" :b "second"} :items {:a {:type :tool :fn identity}}}]
      (is (= {:a "first" :b "second"} (registry/describe reg)))))

  (testing "describe with key returns single description"
    (let [reg {:name 'r :desc {:a "first" :b "second"} :items {:a {:type :tool :fn identity}}}]
      (is (= "first" (registry/describe reg :a)))))

  (testing "describe with missing key returns nil"
    (let [reg {:name 'r :desc {:a "first"} :items {}}]
      (is (nil? (registry/describe reg :missing))))))
