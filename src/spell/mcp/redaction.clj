(ns spell.mcp.redaction
  "Exact-value redaction for configured MCP credential material."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(defn secret-values [values]
  (->> values
       (mapcat (fn [value]
                 (let [value (str value)]
                   (cond-> [value]
                     (str/starts-with? value "Bearer ")
                     (conj (subs value (count "Bearer ")))))))
       (remove str/blank?)
       distinct
       vec))

(defn redact-string [value secrets]
  (when (some? value)
    (reduce (fn [text secret] (str/replace text secret "[REDACTED]"))
            (str value) secrets)))

(defn redact [value secrets]
  (if (seq secrets)
    (walk/postwalk (fn [node]
                     (if (string? node) (redact-string node secrets) node))
                   value)
    value))
