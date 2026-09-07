(ns spell.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.parse :refer [read-all read-first paren-balance balance-parens
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
    (is (= "(+ 1 2\n)" (balance-parens "(+ 1 2"))))

  (testing "needs multiple parens"
    (is (= "((+ 1 2\n))" (balance-parens "((+ 1 2"))))

  (testing "negative balance - returns unchanged"
    (is (= "(+ 1))" (balance-parens "(+ 1))"))))

  (testing "empty string"
    (is (= "" (balance-parens ""))))

  (testing "string with no parens"
    (is (= "hello" (balance-parens "hello"))))

  (testing "trailing comment does not swallow balanced parens"
    (is (= 0 (paren-balance (balance-parens "(+ 1 2 ; comment"))))
    (is (= 0 (paren-balance (balance-parens "(do (+ 1 2) ; trailing")))))

  (testing "balanced result with trailing comment is readable"
    (is (= ['(+ 1 2)] (read-all (balance-parens "(+ 1 2 ; comment"))))))

;; =============================================================================
;; read-first tests
;; =============================================================================

(deftest read-first-test
  (testing "single form"
    (is (= '(+ 1 2) (read-first "(+ 1 2)"))))

  (testing "returns first form, ignores rest"
    (is (= '(+ 1 2) (read-first "(+ 1 2) (def x 3) garbage"))))

  (testing "first form valid, garbage after — no error"
    (is (= '(quine c (eval (do 42)))
           (read-first "(quine c (eval (do 42))) But wait carefully: I should..."))))

  (testing "empty string returns nil"
    (is (nil? (read-first ""))))

  (testing "reader error on first token still throws"
    (is (thrown? RuntimeException (read-first "carefully: invalid")))))

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

;; Pre-optimization sanitizer snapshots pin exact existing behavior, including
;; the separately tracked multiline-string comment-rewrite issue.
(def ^:private baseline-valid-escape?
  #{\t \b \n \r \f \\ \" \u \0 \1 \2 \3 \4 \5 \6 \7})

(defn- baseline-sanitize-comments
  "Normalize common non-Spell comment tokens at line start when outside strings.
   Rewrites line-start //, /*, and */ to ';' comments so reader recovery can
   continue when models emit C/C++-style comment syntax."
  [s]
  (let [len (count s)
        sb  (StringBuilder. len)]
    (loop [i 0, in-string false, escape false, line-start true]
      (if (>= i len)
        (.toString sb)
        (let [c  (.charAt ^String s i)
              c2 (when (< (inc i) len) (.charAt ^String s (inc i)))]
          (cond
            escape
            (do (.append sb c)
                (recur (inc i) in-string false (= c \newline)))

            in-string
            (cond
              (= c \\) (do (.append sb c) (recur (inc i) true true false))
              (= c \") (do (.append sb c) (recur (inc i) false false false))
              :else    (do (.append sb c) (recur (inc i) true false (= c \newline))))

            (= c \newline)
            (do (.append sb c) (recur (inc i) false false true))

            (and line-start (or (= c \space) (= c \tab) (= c \return)))
            (do (.append sb c) (recur (inc i) false false true))

            (and line-start (= c \/) (or (= c2 \/) (= c2 \*)))
            (do (.append sb \;) (recur (inc i) false false false))

            (and line-start (= c \*) (= c2 \/))
            (do (.append sb \;) (recur (inc i) false false false))

            :else
            (do (.append sb c)
                (recur (inc i) false false false))))))))

(defn- baseline-sanitize-escapes
  "Fix invalid escape sequences inside string literals.
   LLMs often write LaTeX-like \\equiv, \\frac etc. in strings.
   Clojure's reader rejects \\e, \\f is formfeed, etc.
   This doubles the backslash for unknown escapes so they read as literal text."
  [s]
  (let [len (count s)
        sb (StringBuilder. len)]
    (loop [i 0, in-string false, escape false]
      (if (>= i len)
        (.toString sb)
        (let [c (.charAt ^String s i)]
          (cond
            escape
            (if (baseline-valid-escape? c)
              (do (.append sb c) (recur (inc i) in-string false))
              ;; Unknown escape: double the backslash so \e becomes \\e
              (do (.append sb \\) (.append sb c) (recur (inc i) in-string false)))

            in-string
            (cond
              (= c \\) (do (.append sb c) (recur (inc i) true true))
              (= c \") (do (.append sb c) (recur (inc i) false false))
              :else    (do (.append sb c) (recur (inc i) true false)))

            :else
            (do (.append sb c)
                (recur (inc i) (= c \") false))))))))

(defn- reader-outcome [reader input]
  (try {:ok (reader input)}
       (catch RuntimeException e
         {:error [(class e) (.getMessage e)]})))

(deftest sanitizers-match-original-output
  (let [targeted [nil "" "x" "λ" "\n" "\r\n" "//" "/*" "*/" "/" "*"
                  "  // comment\r\n42" "\t/* comment\n*/\n42"
                  "(str \"line\n// inside string\")"
                  "(str \"line\n/* inside string\n*/\")"
                  "\"x\\equiv y\"" "\"\\q\\e\"" "\"trailing\\"
                  "\"valid\\n\\t\\\\\\\"\"" "outside\\equiv"
                  "42\n(" "42 ] not-tail" "42 ] " "] not-tail"
                  "// comment\n(think \"x\\equiv y\")"
                  (str (apply str (repeat 4096 \x)) "\n// late rewrite")
                  (str "\"" (apply str (repeat 4096 \x)) "\\e\"")]
        rng (java.util.Random. 73491)
        tokens ["x" "λ" "\n" "\r" "\t" " " "\"" "\\" "\\e" "\\n"
                "//" "/*" "*/" "(" ")" "[" "]" ";" "42"]
        generated (repeatedly 256
                    #(apply str (repeatedly 32
                                  (fn [] (nth tokens (.nextInt rng (count tokens)))))))
        inputs (vec (concat targeted generated))]
    (doseq [input inputs]
      (testing (pr-str input)
        (is (= (baseline-sanitize-comments input)
               (sanitize-nonspell-comment-markers input)))
        (is (= (baseline-sanitize-escapes input)
               (sanitize-string-escapes input)))
        (is (= (baseline-sanitize-escapes (baseline-sanitize-comments input))
               (sanitize-string-escapes (sanitize-nonspell-comment-markers input))))
        (doseq [reader [read-first read-all]]
          (let [expected (with-redefs [spell.parse/sanitize-nonspell-comment-markers baseline-sanitize-comments
                                      spell.parse/sanitize-string-escapes baseline-sanitize-escapes]
                           (reader-outcome reader input))]
            (is (= expected (reader-outcome reader input)))))))))

(deftest unchanged-sanitizers-reuse-input
  (doseq [input ["" "42" "(think \"valid\\ntext and unicode λ\")"
                "  ; ordinary comment\n42" "(str \"https://example.invalid\")"]]
    (is (identical? input (sanitize-nonspell-comment-markers input)))
    (is (identical? input (sanitize-string-escapes input))))
  (testing "nil input keeps the original empty-string result"
    (is (= "" (sanitize-nonspell-comment-markers nil)))
    (is (= "" (sanitize-string-escapes nil)))))

(deftest sanitizer-reader-boundaries-unchanged
  (is (= 42 (read-first "// comment\n42\n(")))
  (is (= 42 (read-first "42 ] not-tail")))
  (is (= [42] (read-all "42 ]  ")))
  (testing "preserve baseline multiline-string rewriting; correctness fix is separate"
    (is (= "(str \"line\n;/ inside string\")"
           (sanitize-nonspell-comment-markers "(str \"line\n// inside string\")")))))

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
