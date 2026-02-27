(ns spell.recovery
  "Error recovery for Spell programs.

   Namespace recovery — deterministic symbol fixup (unbound or misqualified).
   Quine-extension recovery — appends error info to the quine, re-enters via !extend."
  (:require [clojure.string :as str]
            [spell.eval :as eval]))

;; ---------------------------------------------------------------------------
;; Error message cleanup
;; ---------------------------------------------------------------------------

(def ^:private max-raw-error-chars
  "Maximum characters to preserve for raw recovery context."
  300)

(defn- truncate-str
  "Truncate string to max-len with ellipsis when needed."
  [s max-len]
  (let [s (or s "")]
    (if (> (count s) max-len)
      (str (subs s 0 (max 0 (- max-len 3))) "...")
      s)))

(defn- strip-fn-call-prefix
  "Strip nested Function call wrappers:
   Function call failed: <spell-name>: <inner error>"
  [err-str]
  (loop [s (or err-str "")]
    (if (str/starts-with? s eval/fn-call-prefix)
      (let [after-prefix (subs s (count eval/fn-call-prefix))
            inner        (if-let [[_ _ payload] (re-matches #"([^\s:]+): (.*)" after-prefix)]
                           payload
                           after-prefix)]
        (if (= inner s) s (recur inner)))
      s)))

(defn- canonical-prefix-error
  "Extract canonical '<prefix><symbol>' from noisy error text."
  [s prefix]
  (when-let [idx (str/index-of s prefix)]
    (let [suffix (subs s (+ idx (count prefix)))
          token  (some-> (str/triml suffix)
                         (#(re-find #"^[^\s\)\]\}\{]+" %)))]
      (when (seq token)
        (str prefix token)))))

(defn clean-error-message
  "Extract a stable, short error message for recovery prompts.
   Prefers canonical symbol errors (Unbound symbol / Namespace lookup failed)."
  [err-str]
  (let [s (-> (strip-fn-call-prefix err-str) str/trim)]
    (or (canonical-prefix-error s eval/unbound-symbol-prefix)
        (canonical-prefix-error s eval/namespace-lookup-prefix)
        (truncate-str (or (first (str/split-lines s)) s) 180))))

(defn recovery-error-map
  "Build compact structured error context for recovery quines.
   Keeps a canonical short message and preserves bounded raw text separately."
  [err-str]
  (let [cleaned (clean-error-message err-str)
        raw     (-> (or err-str "") str/trim (truncate-str max-raw-error-chars))]
    (cond-> {:error cleaned}
      (and (seq raw) (not= raw cleaned))
      (assoc :raw-error raw))))

;; ---------------------------------------------------------------------------
;; Namespace recovery (deterministic)
;; ---------------------------------------------------------------------------

(defn- find-in-namespaces
  "Search all namespaces for a keyword matching sym.
   Returns a list of qualified symbols, e.g. (seqs/distinct)."
  [sym namespaces]
  (let [kw (keyword sym)]
    (for [[ns-sym ns-map] namespaces
          :when (map? ns-map)
          :when (contains? ns-map kw)]
      (symbol (str ns-sym "/" sym)))))

(defn- substitute-symbol
  "Recursively replace occurrences of old-sym with new-sym in expr."
  [expr old-sym new-sym]
  (cond
    (= expr old-sym) new-sym
    (seq? expr) (apply list (map #(substitute-symbol % old-sym new-sym) expr))
    (vector? expr) (mapv #(substitute-symbol % old-sym new-sym) expr)
    (map? expr) (into {} (map (fn [[k v]] [(substitute-symbol k old-sym new-sym)
                                            (substitute-symbol v old-sym new-sym)]) expr))
    :else expr))

(defn make-namespace-recover-fn
  "Create a recovery fn that fixes unbound/misqualified symbols by searching namespaces.
   Returns nil if no unique match found (letting the next strategy try)."
  [namespaces]
  (fn [result]
    (let [{:keys [err expr program]} result
          inner-err (clean-error-message err)]
      (when-let [fix
                 (cond
                   ;; Case 1: "Unbound symbol: X" — bare symbol, search all namespaces
                   (str/starts-with? inner-err eval/unbound-symbol-prefix)
                   (let [sym (symbol (subs inner-err (count eval/unbound-symbol-prefix)))
                         matches (find-in-namespaces sym namespaces)]
                     (when (= 1 (count matches))
                       (let [qualified (first matches)]
                         (eval/vlog (str "  Namespace recovery: " sym " -> " qualified))
                         (substitute-symbol program sym qualified))))

                   ;; Case 2: "Namespace lookup failed: ns/item" — wrong namespace
                   (str/starts-with? inner-err eval/namespace-lookup-prefix)
                   (let [qualified-str (subs inner-err (count eval/namespace-lookup-prefix))
                         parts (str/split qualified-str #"/")
                         item-sym (symbol (last parts))
                         bad-qualified (symbol qualified-str)
                         matches (find-in-namespaces item-sym namespaces)]
                     (when (= 1 (count matches))
                       (let [correct (first matches)]
                         (eval/vlog (str "  Namespace recovery: " bad-qualified " -> " correct))
                         (substitute-symbol program bad-qualified correct)))))]
        fix))))
