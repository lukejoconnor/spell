(ns spell.recovery
  "Error recovery for Spell programs.

   Namespace recovery — deterministic symbol fixup (unbound or misqualified).
   Quine-extension recovery — reopens the same do-tail for trailing-expression
   failures, or appends error info plus a one-turn prune marker to an inert
   recovery branch for other failures."
  (:require [clojure.string :as str]
            [spell.eval :as eval]))

;; ---------------------------------------------------------------------------
;; Error message cleanup
;; ---------------------------------------------------------------------------

(defn clean-error-message
  "Strip wrapping noise from error messages.
   Removes 'Function call failed: <spell-name>: ' prefix and trailing ' {...}' ex-data."
  [err-str]
  (let [;; Strip fn-call prefix
        s (cond-> err-str
            (str/starts-with? err-str eval/fn-call-prefix)
            (subs (count eval/fn-call-prefix)))
        ;; Strip spell-name prefix (e.g. 'agents/send: ')
        ;; Find the first known error prefix and strip everything before it
        s (if-let [idx (some #(str/index-of s %)
                              [eval/unbound-symbol-prefix eval/namespace-lookup-prefix])]
            (subs s idx)
            s)
        ;; Strip trailing ex-data map (e.g. ' {:handle "hi"}')
        s (if-let [idx (str/last-index-of s " {")]
            (let [after (subs s idx)]
              (if (str/ends-with? after "}")
                (subs s 0 idx)
                s))
            s)]
    s))

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
