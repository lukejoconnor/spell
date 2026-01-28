(ns spell.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.core :refer [spell-eval run-spell find-free-vars substitute extract expand
                                prepend-hooks-to-llm recurse make-llm read-all
                                default-tools read-name-tool bash-tool
                                with-env with-env-hints prefix-prompt]]
            [spell.llm :as llm-provider]
            [spell.prompt :as prompt]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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
;; Extract tests
;; =============================================================================

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
      (is (= 42 (extract '[prompt x] env))))))

;; =============================================================================
;; Expand tests
;; =============================================================================

(deftest expand-test
  (testing "simple expansion"
    (let [closure '(do (def x 42))
          sub-thunk '(+ x 1)]
      (is (= '(+ 42 1) (expand closure sub-thunk {})))))

  (testing "multiple free variables"
    (let [closure '(do (def x 10) (def y 20))
          sub-thunk '(+ x y)]
      (is (= '(+ 10 20) (expand closure sub-thunk {})))))

  (testing "no free variables unchanged"
    (let [closure '(do (def x 10))
          sub-thunk '(+ 1 2)]
      (is (= '(+ 1 2) (expand closure sub-thunk {})))))

  (testing "expansion with computed values"
    (let [closure '(do (def x (+ 1 2)))
          sub-thunk '(* x x)]
      (is (= '(* 3 3) (expand closure sub-thunk {})))))

  (testing "expansion respects quotes"
    (let [closure '(do (def x 42))
          sub-thunk '(list x (quote x))]  ; first x expanded, quoted x not
      (is (= '(list 42 (quote x)) (expand closure sub-thunk {})))))

  (testing "expansion with let - bound vars not expanded"
    (let [closure '(do (def x 100))
          sub-thunk '(let [x 1] (+ x y))]  ; x bound locally, y free
      ;; x should NOT be substituted (locally bound), y should be if defined
      (is (= '(let [x 1] (+ x y)) (expand closure sub-thunk {})))))

  (testing "expansion with fn - params not expanded"
    (let [closure '(do (def x 100) (def y 200))
          sub-thunk '(fn [x] (+ x y))]  ; x is param, y is free
      (is (= '(fn [x] (+ x 200)) (expand closure sub-thunk {})))))

  (testing "partial expansion - only defined vars substituted"
    (let [closure '(do (def x 10))
          sub-thunk '(+ x y)]  ; y not defined in closure
      (is (= '(+ 10 y) (expand closure sub-thunk {})))))

  (testing "expansion uses env for closure evaluation"
    (let [env {'z 100}
          closure '(do (def x z))  ; x gets value from env
          sub-thunk '(+ x 1)]
      (is (= '(+ 100 1) (expand closure sub-thunk env))))))

;; =============================================================================
;; Integration: extract and expand together
;; =============================================================================

(deftest extract-expand-integration
  (testing "expand then evaluate"
    (let [closure '(do (def x 42))
          sub-thunk '(+ x 1)
          expanded (expand closure sub-thunk {})]
      (is (= 43 (run-spell expanded)))))

  (testing "extract thunk then expand it"
    (let [env {'prompt '(do
                          (def context 100)
                          (def task '(+ context 1)))}
          ;; Extract the task thunk
          task-thunk (extract '[prompt task] env)
          ;; The task references 'context' which is defined in prompt
          ;; Expand it using prompt as closure
          closure (get env 'prompt)
          expanded (expand closure task-thunk {})]
      (is (= '(+ 100 1) expanded))
      (is (= 101 (run-spell expanded)))))

  (testing "nested scenario - parent passes expanded thunk to child"
    ;; Parent has: (def x 10) (def thunk '(+ x 1))
    ;; Child receives expanded thunk: (+ 10 1)
    (let [parent-closure '(do (def x 10) (def thunk '(+ x 1)))
          [_ parent-env] (spell-eval parent-closure {})
          child-thunk (get parent-env 'thunk)
          expanded (expand parent-closure child-thunk {})]
      (is (= '(+ 10 1) expanded))
      (is (= 11 (run-spell expanded))))))

;; =============================================================================
;; read-name tool tests
;; =============================================================================

(deftest read-name-test
  (testing "read-name reads from name.txt"
    ;; Create a temporary name.txt file
    (spit "name.txt" "Alice")
    (try
      (is (= "Alice" (run-spell '(read-name))))
      (finally
        (io/delete-file "name.txt"))))

  (testing "read-name trims whitespace"
    (spit "name.txt" "  Bob  \n")
    (try
      (is (= "Bob" (run-spell '(read-name))))
      (finally
        (io/delete-file "name.txt"))))

  (testing "read-name throws when file missing"
    (is (thrown-with-msg? Exception #"name.txt not found"
          (run-spell '(read-name)))))

  (testing "read-name can be used in expressions"
    (spit "name.txt" "Charlie")
    (try
      (is (= "Hello, Charlie!" (run-spell '(cat "Hello, " (read-name) "!"))))
      (finally
        (io/delete-file "name.txt")))))

;; =============================================================================
;; bash tool tests
;; =============================================================================

(deftest bash-test
  (testing "bash returns a map with :exit :out :err"
    (let [result (run-spell '(bash "echo hello"))]
      (is (map? result))
      (is (= 0 (:exit result)))
      (is (= "hello" (:out result)))
      (is (= "" (:err result)))))

  (testing "bash captures exit code on failure"
    (let [result (run-spell '(bash "exit 42"))]
      (is (= 42 (:exit result)))))

  (testing "bash captures stderr"
    (let [result (run-spell '(bash "echo oops >&2; exit 1"))]
      (is (= 1 (:exit result)))
      (is (= "oops" (:err result)))))

  (testing "bash output accessible with keywords"
    (is (= "hi" (run-spell '(:out (bash "echo hi")))))
    (is (= 0 (run-spell '(:exit (bash "true"))))))

  (testing "bash output usable with get"
    (is (= "world" (run-spell '(get (bash "echo world") :out)))))

  (testing "bash output usable in expressions"
    (is (= "result: ok"
           (run-spell '(cat "result: " (:out (bash "echo ok")))))))

  (testing "bash timeout"
    (binding [spell.core/*bash-timeout* 1]
      (let [result (run-spell '(bash "sleep 10"))]
        (is (= -1 (:exit result)))
        (is (str/includes? (:err result) "timed out"))))))

;; =============================================================================
;; read-all tests
;; =============================================================================

(deftest read-all-test
  (testing "single form"
    (is (= ['(+ 1 2)] (read-all "(+ 1 2)"))))

  (testing "multiple forms"
    (is (= ['(def x 1) '(def y 2) '(+ x y)]
           (read-all "(def x 1) (def y 2) (+ x y)"))))

  (testing "empty string"
    (is (= [] (read-all ""))))

  (testing "do block followed by defs (call-now pattern)"
    (is (= ['(do (def response "hi") (def return 42))
            '(def files "result")]
           (read-all "(do (def response \"hi\") (def return 42))\n(def files \"result\")"))))

  (testing "mixed form types"
    (is (= [42 "hello" '(+ 1 2)]
           (read-all "42 \"hello\" (+ 1 2)")))))

;; =============================================================================
;; prepend-hooks-to-llm tests
;; =============================================================================

(deftest prepend-hooks-to-llm-test
  (testing "adds hooks to llm call without existing hooks"
    (let [hooks ['hook1 'hook2]
          code '(llm "prompt")
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(llm "prompt" [hook1 hook2]) result))))

  (testing "prepends to existing hooks"
    (let [hooks ['new-hook]
          code '(llm "prompt" [existing-hook])
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(llm "prompt" [new-hook existing-hook]) result))))

  (testing "processes nested llm calls"
    (let [hooks ['h]
          code '(do (def x (llm "outer")) (llm "inner"))
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(do (def x (llm "outer" [h])) (llm "inner" [h])) result))))

  (testing "does not descend into quotes"
    (let [hooks ['h]
          code '(quote (llm "quoted"))
          result (prepend-hooks-to-llm hooks code)]
      (is (= '(quote (llm "quoted")) result))))

  (testing "processes vectors"
    (let [hooks ['h]
          code '[(llm "a") (llm "b")]
          result (prepend-hooks-to-llm hooks code)]
      (is (= '[(llm "a" [h]) (llm "b" [h])] result))))

  (testing "processes maps"
    (let [hooks ['h]
          code '{:x (llm "val")}
          result (prepend-hooks-to-llm hooks code)]
      (is (= '{:x (llm "val" [h])} result))))

  (testing "leaves non-llm code unchanged"
    (let [hooks ['h]
          code '(+ 1 2)]
      (is (= '(+ 1 2) (prepend-hooks-to-llm hooks code)))))

  (testing "processes prompt recursively"
    (let [hooks ['h]
          code '(llm (do (llm "inner")))
          result (prepend-hooks-to-llm hooks code)]
      ;; Inner llm gets hooks, outer llm gets hooks, prompt is processed
      (is (= '(llm (do (llm "inner" [h])) [h]) result)))))

;; =============================================================================
;; recurse tests
;; =============================================================================

(deftest recurse-test
  (testing "recurse returns a function form"
    (let [hook '(fn [code] code)
          result (recurse hook)]
      (is (seq? result))
      (is (= 'fn (first result)))))

  (testing "recurse hook applies inner hook"
    ;; The recursive hook should first apply the inner hook
    (let [;; Inner hook adds a binding
          inner-hook '(fn [code] (list 'do '(def injected 1) code))
          recursive-hook (recurse inner-hook)
          ;; Evaluate the recursive hook to get a function
          [hook-fn _] (spell-eval recursive-hook {})
          ;; Apply it to some code
          input '(do (def x 10))
          result (hook-fn input)]
      ;; Should have injected binding
      (is (some #(= '(def injected 1) %) (tree-seq coll? seq result)))))

  (testing "recurse hook adds hooks to llm calls"
    (let [inner-hook '(fn [code] code)  ; identity hook
          recursive-hook (recurse inner-hook)
          [hook-fn _] (spell-eval recursive-hook {})
          ;; Input has an llm call
          input '(do (llm "test"))
          result (hook-fn input)]
      ;; result is (do (llm "test" [hook1 hook2]))
      (let [llm-form (second result)  ; (llm "test" [hooks])
            hooks (nth llm-form 2)]
        (is (vector? hooks))
        (is (= 2 (count hooks)))
        ;; First hook is the inner hook (quoted)
        (is (= '(fn [code] code) (first hooks))))))

  (testing "recurse hook preserves existing llm hooks"
    (let [inner-hook '(fn [code] code)
          recursive-hook (recurse inner-hook)
          [hook-fn _] (spell-eval recursive-hook {})
          ;; Input has llm with existing hook
          input '(do (llm "test" [existing-hook]))
          result (hook-fn input)]
      ;; result is (do (llm "test" [inner-hook recursive-hook existing-hook]))
      (let [llm-form (second result)
            hooks (nth llm-form 2)]
        (is (= 3 (count hooks)))
        (is (= 'existing-hook (nth hooks 2)))))))

;; =============================================================================
;; Dynamic builtins tests
;; =============================================================================

(deftest dynamic-builtins-test
  (testing "spell-eval uses *builtins* for symbol resolution"
    (let [custom-builtins (merge @#'spell.core/core-builtins
                                 {'my-fn (fn [] 99)})]
      (binding [spell.core/*builtins* custom-builtins]
        (is (= 99 (first (spell-eval '(my-fn) {})))))))

  (testing "symbol not in *builtins* throws"
    (binding [spell.core/*builtins* @#'spell.core/core-builtins]
      ;; bash is not in core-builtins, should be unbound
      (is (thrown-with-msg? Exception #"Unbound symbol"
            (spell-eval 'bash {})))))

  (testing "find-free-vars respects *builtins*"
    (let [custom-builtins (merge @#'spell.core/core-builtins
                                 {'custom-sym identity})]
      (binding [spell.core/*builtins* custom-builtins]
        ;; custom-sym is a builtin, not free
        (is (= #{} (find-free-vars 'custom-sym)))
        ;; unknown-sym is free
        (is (= #{'unknown-sym} (find-free-vars 'unknown-sym)))))))

;; =============================================================================
;; make-llm factory tests
;; =============================================================================

(deftest make-llm-test
  (testing "make-llm with custom tool resolves during evaluation"
    (let [test-tool {:name 'my-tool
                     :fn   (fn [] "tool-result")
                     :doc  "A test tool."}
          custom-llm (make-llm {:tools [test-tool]
                                :llms  {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response "(def return (my-tool))))"})
        (is (= "tool-result" (custom-llm "use tool"))))))

  (testing "make-llm without tool excludes it from evaluation"
    ;; Create an llm with NO tools - bash should be unbound
    (let [bare-llm (make-llm {:llms {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response "(def return \"no tools here\")))"})
        ;; Should work for basic expressions
        (is (= "no tools here" (bare-llm "test"))))))

  (testing "make-llm with named agent function"
    (let [helper-fn (fn
                      ([prompt] "helper-result")
                      ([prompt hooks] "helper-result"))
          parent-llm (make-llm {:tools []
                                :llms  {'llm #'spell.core/llm
                                        'helper helper-fn}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response "(def return (helper \"do something\"))))"})
        (is (= "helper-result" (parent-llm "delegate"))))))

  (testing "default-tools contains read-name and bash"
    (is (= 2 (count default-tools)))
    (is (= #{'read-name 'bash} (set (map :name default-tools)))))

  (testing "tool definitions have required keys"
    (doseq [tool default-tools]
      (is (contains? tool :name))
      (is (contains? tool :fn))
      (is (contains? tool :doc)))))

;; =============================================================================
;; call-now tests
;; =============================================================================

(deftest call-now-test
  (testing "basic call-now with string result"
    (let [call-count (atom 0)
          test-llm (make-llm {:tools [] :llms {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              ;; First call: use call-now with a literal value
                              "\"thinking\") (def return (call-now {:result \"tool-output\"})))"
                              ;; Continuation: use the bound result
                              "(def return (cat \"got: \" result))")))})
        (is (= "got: tool-output" (test-llm "test"))))))

  (testing "call-now with map result (like bash tool)"
    (let [call-count (atom 0)
          test-llm (make-llm {:tools [bash-tool] :llms {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              ;; First call: bash returns a map, pass via call-now
                              "\"running\") (def return (call-now {:output (:out (bash \"echo hello\"))})))"
                              ;; Continuation: use the bound output
                              "(def return output)")))})
        (is (= "hello" (test-llm "test"))))))

  (testing "call-now with multiple bindings"
    (let [call-count (atom 0)
          test-llm (make-llm {:tools [] :llms {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "\"plan\") (def return (call-now {:a \"first\" :b \"second\"})))"
                              "(def return (cat a \" and \" b))")))})
        (is (= "first and second" (test-llm "test"))))))

  (testing "call-now passes completion to continuation"
    ;; The continuation should have access to the extended completion string
    (let [call-count (atom 0)
          test-llm (make-llm {:tools [] :llms {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "\"hi\") (def return (call-now {:x \"val\"})))"
                              ;; Verify completion is a string containing original code
                              "(def return (if (and (not (nil? completion)) (> (count completion) 0)) \"has-completion\" \"no-completion\"))")))})
        (is (= "has-completion" (test-llm "test"))))))

  (testing "recursive call-now (continuation uses call-now again)"
    (let [call-count (atom 0)
          test-llm (make-llm {:tools [] :llms {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (case n
                              1 "\"start\") (def return (call-now {:step1 \"one\"})))"
                              2 "(def return (call-now {:step2 (cat step1 \"-two\")}))"
                              3 "(def return (cat step2 \"-three\"))")))})
        (is (= "one-two-three" (test-llm "test"))))))

  (testing "call-now with empty bindings"
    (let [call-count (atom 0)
          test-llm (make-llm {:tools [] :llms {'llm #'spell.core/llm}})]
      (llm-provider/with-provider
        (llm-provider/dummy-provider
          {:response-fn (fn [_prompt]
                          (let [n (swap! call-count inc)]
                            (if (= n 1)
                              "\"start\") (def return (call-now {})))"
                              "(def return \"continued\")")))})
        (is (= "continued" (test-llm "test")))))))

;; =============================================================================
;; System prompt generation tests
;; =============================================================================

(deftest generate-system-prompt-test
  (testing "includes tool documentation"
    (let [p (prompt/generate-system-prompt
              [{:name 'my-tool :doc "Does things."}]
              {})]
      (is (str/includes? p "my-tool: Does things."))))

  (testing "includes agent documentation"
    (let [p (prompt/generate-system-prompt
              []
              {'helper {:doc "Helps with stuff."}})]
      (is (str/includes? p "(helper \"prompt\") - Helps with stuff."))))

  (testing "self-recursion listed in builtins"
    (let [p (prompt/generate-system-prompt [] {'llm #'spell.core/llm})]
      (is (str/includes? p "Self: llm"))))

  (testing "agents section only appears for non-self llms"
    (let [self-only (prompt/generate-system-prompt [] {'llm #'spell.core/llm})
          with-agent (prompt/generate-system-prompt [] {'llm #'spell.core/llm
                                                        'helper {:doc "Helps."}})]
      (is (not (str/includes? self-only "AGENTS")))
      (is (str/includes? with-agent "AGENTS"))))

  (testing "default prompt contains expected sections"
    (let [p (prompt/generate-system-prompt default-tools {'llm #'spell.core/llm})]
      (is (str/includes? p "SPELL INTERPRETER"))
      (is (str/includes? p "BUILTINS"))
      (is (str/includes? p "TOOLS"))
      (is (str/includes? p "read-name"))
      (is (str/includes? p "bash"))
      (is (str/includes? p "ERROR HANDLING"))
      (is (str/includes? p "EXAMPLES")))))

;; =============================================================================
;; prefix-prompt tests
;; =============================================================================

(deftest prefix-prompt-test
  (testing "string prompt gets docs prepended"
    (let [result (prefix-prompt "DOCS" "task")]
      (is (string? result))
      (is (str/starts-with? result "DOCS"))
      (is (str/ends-with? result "task"))))

  (testing "thunk prompt gets env-hints binding"
    (let [result (prefix-prompt "DOCS" '(do (def return 1)))]
      (is (seq? result))
      (is (= 'do (first result)))
      ;; Should contain (def env-hints "DOCS")
      (is (= '(def env-hints "DOCS") (second result)))
      ;; Original thunk is third element
      (is (= '(do (def return 1)) (nth (seq result) 2)))))

  (testing "non-string non-thunk returned unchanged"
    (is (= 42 (prefix-prompt "DOCS" 42)))
    (is (= nil (prefix-prompt "DOCS" nil)))))

;; =============================================================================
;; inject-docs-into-llm-prompts tests
;; =============================================================================

(deftest inject-docs-into-llm-prompts-test
  (testing "rewrites llm call prompt"
    (let [inject @#'spell.core/inject-docs-into-llm-prompts
          result (inject "DOCS" '(llm "task"))]
      (is (= 'llm (first result)))
      ;; prompt should be (prefix-prompt "DOCS" "task")
      (is (= '(prefix-prompt "DOCS" "task") (second result)))))

  (testing "handles llm with hooks"
    (let [inject @#'spell.core/inject-docs-into-llm-prompts
          result (inject "DOCS" '(llm "task" [hook1]))]
      (is (= 'llm (first result)))
      (is (= '(prefix-prompt "DOCS" "task") (second result)))
      (is (= '[hook1] (nth (seq result) 2)))))

  (testing "skips quoted forms"
    (let [inject @#'spell.core/inject-docs-into-llm-prompts
          result (inject "DOCS" '(quote (llm "task")))]
      (is (= '(quote (llm "task")) result))))

  (testing "recurses into nested structures"
    (let [inject @#'spell.core/inject-docs-into-llm-prompts
          result (inject "DOCS" '(do (def x (llm "inner"))))]
      ;; The llm call inside should be rewritten
      (is (= 'do (first result)))
      (let [def-form (second result)
            llm-call (nth def-form 2)]
        (is (= 'llm (first llm-call)))
        (is (= '(prefix-prompt "DOCS" "inner") (second llm-call)))))))

;; =============================================================================
;; with-env tests
;; =============================================================================

(deftest with-env-test
  (testing "basic binding injection"
    (let [hook (with-env {:x 42})
          code '(+ x 1)
          result (hook code)]
      ;; Result should be (do (def x 42) (+ x 1))
      (is (= 43 (run-spell result)))))

  (testing "multiple bindings"
    (let [hook (with-env {:x 10 :y 20})
          result (hook '(+ x y))]
      (is (= 30 (run-spell result)))))

  (testing "error on non-keyword keys"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"keys must be keywords"
                          (with-env {'x 42}))))

  (testing "quoted values survive re-evaluation"
    ;; A symbol value needs quoting to not be re-evaluated
    (let [hook (with-env {:s 'hello})
          result (hook '(list s))]
      (is (= '(hello) (run-spell result)))))

  (testing "list values survive re-evaluation"
    (let [hook (with-env {:xs '(1 2 3)})
          result (hook '(first xs))]
      (is (= 1 (run-spell result))))))

;; =============================================================================
;; with-env-hints tests
;; =============================================================================

(deftest with-env-hints-test
  (testing "binding injection works"
    (let [hook (with-env-hints {:x [42 "the answer"]})
          code '(+ x 1)
          result (hook code)]
      ;; Should evaluate correctly with x bound
      (is (= 43 (run-spell result)))))

  (testing "multiple bindings with docs"
    (let [hook (with-env-hints {:x [10 "first number"] :y [20 "second number"]})
          result (hook '(+ x y))]
      (is (= 30 (run-spell result)))))

  (testing "llm calls get rewritten with prefix-prompt"
    (let [hook (with-env-hints {:x [42 "the answer"]})
          code '(llm "task")
          result (hook code)]
      ;; The result should contain a prefix-prompt wrapping the llm prompt
      (let [llm-forms (filter #(and (seq? %) (= 'llm (first %)))
                              (tree-seq coll? seq result))]
        (is (seq llm-forms))
        ;; Each llm's prompt arg should be a (prefix-prompt ...) call
        (doseq [llm-form llm-forms]
          (let [prompt-arg (second llm-form)]
            (is (and (seq? prompt-arg) (= 'prefix-prompt (first prompt-arg)))))))))

  (testing "documentation includes all binding descriptions"
    (let [hook (with-env-hints {:api-key ["sk-123" "API key for service"]
                                :timeout [30 "Timeout in seconds"]})
          result (hook '(llm "task"))
          ;; Find the docs string in the prefix-prompt call
          llm-form (first (filter #(and (seq? %) (= 'llm (first %)))
                                  (tree-seq coll? seq result)))
          prefix-call (second llm-form)
          docs-str (second prefix-call)]
      (is (str/includes? docs-str "api-key"))
      (is (str/includes? docs-str "API key for service"))
      (is (str/includes? docs-str "timeout"))
      (is (str/includes? docs-str "Timeout in seconds"))))

  (testing "error on non-keyword keys"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"keys must be keywords"
                          (with-env-hints {'x [42 "doc"]})))))
