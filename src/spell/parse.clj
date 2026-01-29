(ns spell.parse
  "Parsing and string utilities for Spell.")

(defn paren-balance
  "Count open parens minus close parens in a string."
  [s]
  (reduce (fn [n c]
            (case c
              \( (inc n)
              \) (dec n)
              n))
          0 s))

(defn balance-parens
  "Append closing parens to balance the string if needed."
  [s]
  (let [balance (paren-balance s)]
    (if (pos? balance)
      (str s (apply str (repeat balance \))))
      s)))

(defn read-all
  "Read all forms from a string. Returns a vector of parsed forms."
  [s]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [forms []]
      (let [form (try (read rdr) (catch Exception _ ::eof))]
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
