(ns spell.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.eval :as eval :refer [spell-eval run-spell]]))

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
    (map inc [1 2 3])
    (filter odd? [1 2 3])
    (reduce + [1 2 3])
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
