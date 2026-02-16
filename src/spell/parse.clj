(ns spell.parse
  "Parsing and string utilities for Spell.")

(defn paren-balance
  "Count open parens minus close parens in a string, respecting string literals
   and ;-comments. Parens inside \"...\" or after ; are not counted."
  [s]
  (let [len (count s)]
    (loop [i 0, n 0, in-string false, escape false]
      (if (>= i len)
        n
        (let [c (.charAt ^String s i)]
          (cond
            ;; Previous char was backslash inside a string — skip this char
            escape
            (recur (inc i) n in-string false)

            ;; Inside a string literal
            in-string
            (cond
              (= c \\) (recur (inc i) n true true)     ; backslash — next char escaped
              (= c \") (recur (inc i) n false false)    ; closing quote
              :else     (recur (inc i) n true false))

            ;; Outside a string literal
            :else
            (case c
              \; (let [nl (.indexOf ^String s "\n" i)]  ; comment — skip to EOL
                   (recur (if (neg? nl) len (inc nl)) n false false))
              \" (recur (inc i) n true false)           ; opening quote
              \( (recur (inc i) (inc n) false false)
              \) (recur (inc i) (dec n) false false)
              (recur (inc i) n false false))))))))

(defn balance-parens
  "Append closing parens to balance the string if needed."
  [s]
  (let [balance (paren-balance s)]
    (if (pos? balance)
      (str s (apply str (repeat balance \))))
      s)))

(def ^:private valid-escape?
  "Characters that are valid after \\ in a Clojure string literal."
  #{\t \b \n \r \f \\ \" \u \0 \1 \2 \3 \4 \5 \6 \7})

(defn sanitize-string-escapes
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
            (if (valid-escape? c)
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

(defn read-all
  "Read all forms from a string. Returns a vector of parsed forms.
   Tolerates trailing unmatched delimiters (common in LLM output)
   and sanitizes invalid escape sequences in string literals."
  [s]
  (let [s   (sanitize-string-escapes s)
        rdr (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [forms []]
      (let [form (try (read rdr false ::eof)
                      (catch RuntimeException e
                        (if (.startsWith (.getMessage e) "Unmatched delimiter")
                          ::eof
                          (throw e))))]
        (if (= form ::eof)
          forms
          (recur (conj forms form)))))))

(defn strip-trailing-parens
  "Remove n trailing close-parens from a string, ignoring trailing whitespace."
  [n s]
  (let [s (clojure.string/trimr (str s))
        len (count s)]
    (loop [i (dec len), remaining n]
      (cond
        (zero? remaining) (subs s 0 (inc i))
        (< i 0) (throw (ex-info "strip: not enough closing parens" {:n n :length len}))
        (= \) (.charAt ^String s i)) (recur (dec i) (dec remaining))
        :else (throw (ex-info "strip: expected ')'" {:char (.charAt ^String s i) :position i}))))))

(defn escape-string
  "Escape a string for embedding in Lisp code."
  [s]
  (-> s
      (clojure.string/replace "\\" "\\\\")
      (clojure.string/replace "\"" "\\\"")
      (clojure.string/replace "\n" "\\n")
      (clojure.string/replace "\t" "\\t")))
