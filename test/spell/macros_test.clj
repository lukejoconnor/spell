(ns spell.macros-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.macros :as macros]))

(defn expand1
  [form]
  (macros/spell-macroexpand-1 form))

(defmacro with-stable-gensyms
  [& body]
  `(let [counter# (atom 0)]
     (with-redefs [clojure.core/gensym (fn
                                         ([] (symbol (str "g__" (swap! counter# inc))))
                                         ([prefix#] (symbol (str prefix# (swap! counter# inc)))))]
       ~@body)))

(defn ex-info-with-message?
  [message-re f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (boolean (re-find message-re (ex-message e))))))

(deftest rethink-and-prune-helper-test
  (testing "rethink-form? recognizes rethink expressions"
    (is (true? (macros/rethink-form? '(rethink "reason" x))))
    (is (true? (macros/rethink-form? '(rethink 2 "reason" x))))
    (is (false? (macros/rethink-form? '(think "reason" x))))
    (is (false? (macros/rethink-form? '[:rethink "reason" x]))))

  (testing "prune-form? accepts only supported prune shapes"
    (is (true? (macros/prune-form? '(prune))))
    (is (true? (macros/prune-form? '(prune 2))))
    (is (false? (macros/prune-form? '(prune "x"))))
    (is (false? (macros/prune-form? '(prune 1 2))))
    (is (false? (macros/prune-form? '(rethink "x")))))

  (testing "prune-n and rethink-n default to one unless given a numeric count"
    (is (= 1 (macros/prune-n '(prune))))
    (is (= 3 (macros/prune-n '(prune 3))))
    (is (= 1 (macros/rethink-n '(rethink "reason" x))))
    (is (= 2 (macros/rethink-n '(rethink 2 "reason" x)))))

  (testing "rethink->think drops the optional count argument"
    (is (= '(think "reason" x y)
           (macros/rethink->think '(rethink "reason" x y))))
    (is (= '(think "reason" x y)
           (macros/rethink->think '(rethink 4 "reason" x y)))))

  (testing "process-siblings applies prune and rethink markers left-to-right"
    (is (= []
           (macros/process-siblings [])))
    (is (= ['(def a 1)]
           (macros/process-siblings ['(def a 1) '(def b 2) '(prune)])))
    (is (= ['(def a 1) '(think "retry" (def c 3))]
           (macros/process-siblings ['(def a 1)
                                     '(def b 2)
                                     '(rethink "retry" (def c 3))])))
    (is (= ['(think "retry" (def d 4)) '(def e 5)]
           (macros/process-siblings ['(def a 1)
                                     '(def b 2)
                                     '(def c 3)
                                     '(prune 2)
                                     '(rethink 2 "retry" (def d 4))
                                     '(def e 5)])))))

(deftest call-now-expansion-test
  (testing "one renderer receives all evaluated bindings and any local limit"
    (let [single (expand1 '(!call-now result value 100))
          multiple (expand1 '(!call-now a expr-a b expr-b))]
      (is (= 100 (last (last (second single)))))
      (is (= 'context-forms (first (last (second multiple)))))
      (is (= 2 (count (second (last (second multiple))))))))
  (testing "!call-now rejects odd arg counts"
    (is (ex-info-with-message?
          #"!call-now: expected 2 args"
          #(expand1 '(!call-now a expr-a b expr-b c))))))

(deftest peek-print-and-describe-expansion-test
  (testing "!peek is an alias for !peek-now"
    (is (= (with-stable-gensyms (expand1 '(!peek-now snapshot expr)))
           (with-stable-gensyms (expand1 '(!peek snapshot expr))))))
  (testing "!print and print share the same expansion"
    (is (= (with-stable-gensyms (expand1 '(!print a b)))
           (with-stable-gensyms (expand1 '(print a b))))))

  (testing "!describe handles single namespaces, keyed lookups, and mixed groups"
    (is (= '(!print (describe-fn io))
           (expand1 '(!describe io))))
    (is (= '(!print (describe-fn io :read-file))
           (expand1 '(!describe io :read-file))))
    (is (= '(!print (cat "## io\n"
                         (describe-fn io)
                         "\n\n"
                         "## web :search\n"
                         (describe-fn web :search)
                         "\n\n"
                         "## math\n"
                         (describe-fn math)
                         "\n\n"))
           (expand1 '(!describe io web :search math))))))

(deftest simple-macro-expansion-test
  (testing "!extend defaults to completion and accepts an explicit continuation"
    (is (= '(!llm-self (edit-reopen completion))
           (expand1 '(!extend))))
    (is (= '(!llm-self (edit-reopen saved))
           (expand1 '(!extend saved)))))

  (testing "!compact defaults to completion and reuses the shared suffix"
    (let [suffix (var-get #'macros/compact-suffix)]
      (is (= '(!compact completion)
             (expand1 '(!compact))))
      (is (= (list '!llm-self
                   (list 'str
                         (list 'serialize-prefix (list 'edit-reopen 'saved))
                         suffix))
             (expand1 '(!compact saved))))))

  (testing "first-line wraps vector literals with metadata and rejects non-vectors"
    (let [expanded (expand1 '(first-line 7 ["a" "b"]))]
      (is (= 'quote (first expanded)))
      (is (= ["a" "b"] (second expanded)))
      (is (= {:spell/first-line 7} (meta (second expanded)))))
    (is (ex-info-with-message?
          #"first-line expects a vector literal"
          #(expand1 '(first-line 7 "ab")))))

  (testing "define and defmacro expand to the expected def forms"
    (is (= '(def answer 42)
           (expand1 '(define answer 42))))
    (is (= '(def unless
              {:spell/macro true
               :expander (fn [test body]
                           (list (quote if) test nil body))})
           (expand1 '(defmacro unless [test body]
                       (list 'if test nil body))))))

  (testing "spell-macroexpand-1 leaves non-macro input unchanged"
    (is (= ['not 'a 'macro]
           (expand1 ['not 'a 'macro])))
    (is (= '(unknown 1 2 3)
           (expand1 '(unknown 1 2 3))))))
