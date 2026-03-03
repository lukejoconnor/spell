(ns spell.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.parse :refer [read-all paren-balance balance-parens
                                 strip-trailing-parens escape-string
                                 sanitize-string-escapes
                                 sanitize-nonspell-comment-markers]]))

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

  (testing "do block followed by defs (!call-now pattern)"
    (is (= ['(do (def response "hi") (def return 42))
            '(def files "result")]
           (read-all "(do (def response \"hi\") (def return 42))\n(def files \"result\")"))))

  (testing "mixed form types"
    (is (= [42 "hello" '(+ 1 2)]
           (read-all "42 \"hello\" (+ 1 2)")))))

;; =============================================================================
;; paren-balance tests
;; =============================================================================

(deftest paren-balance-test
  (testing "balanced string"
    (is (= 0 (paren-balance "(+ 1 2)"))))

  (testing "unbalanced open"
    (is (= 1 (paren-balance "(+ 1"))))

  (testing "multiple unbalanced"
    (is (= 2 (paren-balance "((+ 1"))))

  (testing "extra close parens"
    (is (= -1 (paren-balance "(+ 1))"))))

  (testing "no parens"
    (is (= 0 (paren-balance "hello"))))

  (testing "empty string"
    (is (= 0 (paren-balance ""))))

  (testing "mixed brackets - only parens count"
    (is (= 1 (paren-balance "(+ [1 2]"))))

  (testing "parens inside strings are not counted"
    (is (= 1 (paren-balance "(def x \"text with ( unbalanced parens\"")))
    (is (= 0 (paren-balance "(str \"hello (world)\")")))
    (is (= 0 (paren-balance "(quine content \"He said (hello\")"))))

  (testing "escaped quotes inside strings"
    (is (= 0 (paren-balance "(str \"she said \\\"(hi\\\" ok\")")))
    (is (= 1 (paren-balance "(def x \"escaped \\\" inside ( string\"")))))

;; =============================================================================
;; balance-parens tests
;; =============================================================================

(deftest balance-parens-test
  (testing "already balanced"
    (is (= "(+ 1 2)" (balance-parens "(+ 1 2)"))))

  (testing "needs one paren"
    (is (= "(+ 1 2)" (balance-parens "(+ 1 2"))))

  (testing "needs multiple parens"
    (is (= "((+ 1 2))" (balance-parens "((+ 1 2"))))

  (testing "negative balance - returns unchanged"
    (is (= "(+ 1))" (balance-parens "(+ 1))"))))

  (testing "empty string"
    (is (= "" (balance-parens ""))))

  (testing "string with no parens"
    (is (= "hello" (balance-parens "hello")))))

;; =============================================================================
;; strip-trailing-parens tests
;; =============================================================================

(deftest strip-trailing-parens-test
  (testing "strip 1 trailing paren"
    (is (= "(do 1)" (strip-trailing-parens 1 "(do 1))"))))

  (testing "strip 3 trailing parens"
    (is (= "(do (+ 1 2" (strip-trailing-parens 3 "(do (+ 1 2)))"))))

  (testing "strip 0 is a no-op"
    (is (= "(+ 1 2)" (strip-trailing-parens 0 "(+ 1 2)"))))

  (testing "ignores trailing whitespace"
    (is (= "(do 1)" (strip-trailing-parens 1 "(do 1))  "))))

  (testing "not enough parens to strip throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not enough closing parens"
          (strip-trailing-parens 5 "))))"))))

  (testing "non-paren character where paren expected throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected '\)'"
          (strip-trailing-parens 2 "(+ 1)x)")))))

;; =============================================================================
;; escape-string tests
;; =============================================================================

(deftest escape-string-test
  (testing "backslash"
    (is (= "a\\\\b" (escape-string "a\\b"))))

  (testing "double quote"
    (is (= "say \\\"hi\\\"" (escape-string "say \"hi\""))))

  (testing "newline"
    (is (= "line1\\nline2" (escape-string "line1\nline2"))))

  (testing "tab"
    (is (= "col1\\tcol2" (escape-string "col1\tcol2"))))

  (testing "combined special characters"
    (is (= "path\\\\to\\\\file\\nhas \\\"quotes\\\"\\there"
           (escape-string "path\\to\\file\nhas \"quotes\"\there"))))

  (testing "no special characters"
    (is (= "hello world" (escape-string "hello world")))))

;; =============================================================================
;; sanitize-string-escapes tests
;; =============================================================================

(deftest sanitize-string-escapes-test
  (testing "valid escapes are preserved"
    (is (= "(str \"hello\\nworld\")" (sanitize-string-escapes "(str \"hello\\nworld\")")))
    (is (= "(str \"tab\\there\")" (sanitize-string-escapes "(str \"tab\\there\")")))
    (is (= "(str \"quote\\\"ok\")" (sanitize-string-escapes "(str \"quote\\\"ok\")")))
    (is (= "(str \"back\\\\slash\")" (sanitize-string-escapes "(str \"back\\\\slash\")"))))

  (testing "unknown escapes are doubled (prevents reader crash)"
    ;; \e is not a valid escape — would crash without sanitization
    (is (= "(def x \"a \\\\equiv b\")" (sanitize-string-escapes "(def x \"a \\equiv b\")")))
    ;; \a is not valid — doubled
    (is (= "(str \"\\\\alpha\")" (sanitize-string-escapes "(str \"\\alpha\")")))
    ;; \f IS valid (formfeed) so it's left alone; \b IS valid (backspace)
    (is (= "(str \"\\frac\")" (sanitize-string-escapes "(str \"\\frac\")")))
    (is (= "(str \"\\beta\")" (sanitize-string-escapes "(str \"\\beta\")"))))

  (testing "backslashes outside strings are unchanged"
    (is (= "\\x" (sanitize-string-escapes "\\x"))))

  (testing "read-all parses LaTeX strings without crashing"
    ;; \equiv would crash without sanitization — now reads as literal text
    (is (= ['(def x "a \\equiv b")]
           (read-all "(def x \"a \\equiv b\")")))))

;; =============================================================================
;; unmatched delimiter handling tests
;; =============================================================================

(deftest trailing-unmatched-delimiter-test
  (testing "single trailing unmatched delimiter is ignored"
    (is (= ['(+ 1 2)] (read-all "(+ 1 2)}")))
    (is (= ['(+ 1 2)] (read-all "(+ 1 2)\n]"))))

  (testing "only one trailing delimiter is tolerated"
    (is (thrown-with-msg? RuntimeException #"Unmatched delimiter"
          (read-all "(+ 1 2)))"))))

  (testing "unmatched delimiter not at end still throws"
    (is (thrown-with-msg? RuntimeException #"Unmatched delimiter"
          (read-all "(+ 1 } 2)")))
    (is (thrown-with-msg? RuntimeException #"Unmatched delimiter"
          (read-all "} (+ 1 2)")))))

;; =============================================================================
;; non-Spell comment token normalization tests
;; =============================================================================

(deftest sanitize-nonspell-comment-markers-test
  (testing "line-start // comments are normalized"
    (is (= ";/ C style comment\n(def x 1)"
           (sanitize-nonspell-comment-markers "// C style comment\n(def x 1)")))
    (is (= ['(def x 1)]
           (read-all "// C style comment\n(def x 1)"))))

  (testing "line-start /* and */ markers are normalized"
    (is (= ['(def x 1)]
           (read-all "/* block-style start\n(def x 1)")))
    (is (= ['(def x 1)]
           (read-all "*/ block-style end\n(def x 1)"))))

  (testing "indented comment markers are normalized"
    (is (= ['(def y 2)]
           (read-all "   // indented comment\n(def y 2)"))))

  (testing "markers not at line start are not rewritten"
    (is (thrown-with-msg? RuntimeException #"Invalid token: //"
          (read-all "(def x 1) // trailing non-Spell comment"))))

  (testing "markers inside strings are unchanged"
    (is (= ['(def s "// keep me") '(def t "/* keep */")]
           (read-all "(def s \"// keep me\") (def t \"/* keep */\")")))))
