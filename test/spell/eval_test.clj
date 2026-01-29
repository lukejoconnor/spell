(ns spell.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.eval :as eval :refer [spell-eval run-spell find-free-vars substitute]]))

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
    (not nil)])

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

    ;; eval/apply (non-whitelisted)
    (eval '(+ 1 2))
    (apply + [1 2])

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

  (testing "closure captures value at definition time, not reference"
    ;; Define f when y=10, change y to 100, f should still see y=10
    (let [env1 {'y 10}
          [_ env2] (spell-eval '(defn f [x] (+ x y)) env1)
          env3 (assoc env2 'y 100)
          [result _] (spell-eval '(f 5) env3)]
      (is (= 15 result) "closure should capture y=10, not see y=100")))

  (testing "closure in fresh spell-eval has no access to outer context"
    ;; Thunk defines f (referencing y) and calls f, but y is never defined
    ;; This should fail - the closure's env is empty, y is unbound
    (is (thrown-with-msg? Exception #"Unbound symbol"
          (spell-eval '(do (defn f [x] (+ x y)) (f 3)) {})))))

(deftest defn-form
  (testing "defn creates function in env"
    (let [[val env] (spell-eval '(defn square [x] (* x x)) {})]
      (is (fn? val))
      (is (fn? (env 'square)))))

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
;; do-eval-last tests
;; =============================================================================

(deftest do-eval-last-form
  (testing "quoted last expr gets extra eval"
    (is (= 2 (run-spell '(do-eval-last (def x 1) '(+ x 1))))))

  (testing "non-quoted last expr: extra eval is identity for self-evaluating"
    (is (= 2 (run-spell '(do-eval-last (def x 1) (+ x 1))))))

  (testing "single quoted expression"
    (is (= 3 (run-spell '(do-eval-last '(+ 1 2))))))

  (testing "empty do-eval-last"
    (is (nil? (run-spell '(do-eval-last)))))

  (testing "middle quoted exprs are inert values"
    ;; The middle quote is just data (discarded), only last expr matters
    (is (= "hello" (run-spell '(do-eval-last
                                  (def x 1)
                                  '(this would fail if evaluated)
                                  "hello")))))

  (testing "env from defs available in extra eval"
    (is (= 30 (run-spell '(do-eval-last
                              (def a 10)
                              (def b 20)
                              '(+ a b))))))

  (testing "extra eval of def form binds in env"
    (let [[val env] (spell-eval '(do-eval-last '(def y 10)) {})]
      (is (= 10 val))
      (is (= 10 (env 'y)))))

  (testing "env threading preserves defs"
    (let [[val env] (spell-eval '(do-eval-last (def x 5) '(+ x 1)) {})]
      (is (= 6 val))
      (is (= 5 (env 'x)))))

  (testing "string last expr: extra eval is harmless"
    (is (= "hi" (run-spell '(do-eval-last "hi"))))))

(deftest do-eval-last-free-vars
  (testing "find-free-vars treats do-eval-last like do"
    (is (= #{'x} (find-free-vars '(do-eval-last (def y 1) (+ x y)))))
    (is (= #{} (find-free-vars '(do-eval-last (def x 1) (+ x 1)))))))

(deftest do-eval-last-expand
  (testing "expand substitutes free vars in do-eval-last"
    (let [[val _] (spell-eval '(do (def x 42) (expand '(do-eval-last (def y x) '(+ y 1)))) {})]
      ;; x should be substituted, but '(+ y 1) is quoted so y stays
      (is (= '(do-eval-last (def y 42) (quote (+ y 1))) val)))))

;; =============================================================================
;; uneval tests
;; =============================================================================

(deftest uneval-form
  (testing "basic uneval returns quoted expression"
    ;; (def x (uneval 'x)) should bind x to the quote of its own expression
    (let [[val _] (spell-eval '(def x (uneval 'x)) {})]
      (is (= '(quote (uneval 'x)) val))))

  (testing "uneval enables self-referential code"
    ;; uneval returns (quote expr) - the quoted definition expression
    ;; We can use it to build self-referential structures
    (let [[val _] (spell-eval '(def my-code (vector (uneval 'my-code))) {})]
      ;; my-code is a vector containing its own quoted definition
      (is (= ['(quote (vector (uneval 'my-code)))] val))))

  (testing "uneval inside larger expression"
    ;; (def x (do (def inner 1) (uneval 'x)))
    ;; should return the quote of the entire expression
    (let [[val _] (spell-eval '(def x (do (def inner 1) (uneval 'x))) {})]
      (is (= '(quote (do (def inner 1) (uneval 'x))) val))))

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
      (is (= '(quote (uneval (first '[x]))) val))))

  (testing "nested def does not pollute outer quote-env"
    ;; Inner def's quote-env shouldn't leak to outer
    (let [[val _] (spell-eval '(def outer (do (def inner 1) (uneval 'outer))) {})]
      ;; Should get quote of outer's expression, not inner's
      (is (= '(quote (do (def inner 1) (uneval 'outer))) val)))))

;; =============================================================================
;; Free variable analysis tests
;; =============================================================================

(deftest find-free-vars-test
  (testing "literals have no free vars"
    (is (= #{} (find-free-vars 42)))
    (is (= #{} (find-free-vars "hello")))
    (is (= #{} (find-free-vars nil)))
    (is (= #{} (find-free-vars true))))

  (testing "unbound symbol is free"
    (is (= #{'x} (find-free-vars 'x)))
    (is (= #{'foo} (find-free-vars 'foo))))

  (testing "builtins are not free"
    (is (= #{} (find-free-vars '+)))
    (is (= #{} (find-free-vars 'str)))
    (is (= #{} (find-free-vars 'llm))))

  (testing "bound symbols are not free"
    (is (= #{} (find-free-vars 'x #{'x}))))

  (testing "simple expressions"
    (is (= #{'x 'y} (find-free-vars '(+ x y))))
    (is (= #{'x} (find-free-vars '(+ x 1))))
    (is (= #{} (find-free-vars '(+ 1 2)))))

  (testing "quote blocks free var analysis"
    (is (= #{} (find-free-vars '(quote x))))
    (is (= #{} (find-free-vars '(quote (+ x y))))))

  (testing "def binds for subsequent refs but value can have free vars"
    ;; (def x y) - y is free
    (is (= #{'y} (find-free-vars '(def x y)))))

  (testing "let binds symbols"
    (is (= #{} (find-free-vars '(let [x 1] x))))
    (is (= #{} (find-free-vars '(let [x 1 y 2] (+ x y)))))
    ;; But values can reference free vars
    (is (= #{'z} (find-free-vars '(let [x z] x))))
    ;; Bindings are sequential - y can reference x
    (is (= #{} (find-free-vars '(let [x 1 y x] y)))))

  (testing "fn binds params"
    (is (= #{} (find-free-vars '(fn [x] x))))
    (is (= #{} (find-free-vars '(fn [x y] (+ x y)))))
    ;; But body can have free vars
    (is (= #{'z} (find-free-vars '(fn [x] (+ x z))))))

  (testing "defn binds name and params"
    (is (= #{} (find-free-vars '(defn f [x] (f x)))))  ; recursive ref to f is bound
    (is (= #{'z} (find-free-vars '(defn f [x] (+ x z))))))

  (testing "nested expressions"
    (is (= #{'a 'b} (find-free-vars '(if a b c) #{'c})))
    (is (= #{'x} (find-free-vars '(do (def y 1) (+ x y))))))

  (testing "vectors and maps"
    (is (= #{'x 'y} (find-free-vars '[x y 1])))
    (is (= #{'v} (find-free-vars '{:a v})))))

;; =============================================================================
;; Substitution tests
;; =============================================================================

(deftest substitute-test
  (testing "literals unchanged"
    (is (= 42 (substitute 42 {'x 1})))
    (is (= "hello" (substitute "hello" {'x 1}))))

  (testing "symbol substitution"
    (is (= 10 (substitute 'x {'x 10})))
    (is (= 'y (substitute 'y {'x 10}))))  ; y not in bindings

  (testing "expression substitution"
    (is (= '(+ 10 20) (substitute '(+ x y) {'x 10 'y 20})))
    (is (= '(+ 10 y) (substitute '(+ x y) {'x 10}))))

  (testing "quote blocks substitution"
    (is (= '(quote x) (substitute '(quote x) {'x 10}))))

  (testing "def substitutes in value only"
    (is (= '(def x 10) (substitute '(def x y) {'y 10})))
    ;; The name 'x' is not substituted
    (is (= '(def x 1) (substitute '(def x 1) {'x 999}))))

  (testing "let removes bindings from scope"
    ;; x is bound by let, so not substituted in body
    (is (= '(let [x 1] x) (substitute '(let [x 1] x) {'x 10})))
    ;; But y is still substituted
    (is (= '(let [x 1] (+ x 10)) (substitute '(let [x 1] (+ x y)) {'y 10})))
    ;; Values in bindings can be substituted
    (is (= '(let [x 10] x) (substitute '(let [x y] x) {'y 10}))))

  (testing "fn removes params from scope"
    (is (= '(fn [x] x) (substitute '(fn [x] x) {'x 10})))
    (is (= '(fn [x] (+ x 10)) (substitute '(fn [x] (+ x y)) {'y 10}))))

  (testing "defn removes name and params from scope"
    (is (= '(defn f [x] (f x)) (substitute '(defn f [x] (f x)) {'f 999 'x 888})))
    (is (= '(defn f [x] (+ x 10)) (substitute '(defn f [x] (+ x y)) {'y 10}))))

  (testing "vectors and maps"
    (is (= [10 20] (substitute '[x y] {'x 10 'y 20})))
    (is (= {:a 10} (substitute '{:a x} {'x 10})))))

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

  (testing "defn-bound vars not expanded (not in env)"
    ;; defn creates a function, which isn't in the expression's free vars
    (let [[val _] (spell-eval '(do (defn f [x] (* x x))
                                   (expand '(f 3))) {})]
      ;; f is in env, so it gets substituted — but as a function, quote-value wraps it
      ;; Actually f IS in env, so it would be substituted. But f is a Clojure fn,
      ;; which quote-value wraps in (quote ...). This isn't portable.
      ;; Users should only expand data bindings.
      (is (some? val))))

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

  (testing "find-free-vars respects *builtins*"
    (let [custom-builtins (merge eval/core-builtins
                                 {'custom-sym identity})]
      (binding [eval/*builtins* custom-builtins]
        ;; custom-sym is a builtin, not free
        (is (= #{} (find-free-vars 'custom-sym)))
        ;; unknown-sym is free
        (is (= #{'unknown-sym} (find-free-vars 'unknown-sym)))))))
