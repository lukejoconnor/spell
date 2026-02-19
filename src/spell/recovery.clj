(ns spell.recovery
  "Error recovery for Spell programs.

   Two strategies:
   1. Namespace recovery — deterministic symbol fixup (unbound or misqualified).
   2. LLM recovery — asks the recovery LLM to rewrite the failing expression."
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.parse :as parse]))

;; ---------------------------------------------------------------------------
;; Recovery system prompt (for LLM-based recovery)
;; ---------------------------------------------------------------------------

(def recovery-system-prompt
  "You are fixing a Spell program error. Return ONLY the fixed Spell s-expression.
No explanation, no markdown code blocks, just the raw s-expression.")

;; ---------------------------------------------------------------------------
;; Error formatting
;; ---------------------------------------------------------------------------

(defn format-error-for-recovery
  "Format an error result for the recovery LLM.
   Shows the full program, failing expression, and error message."
  [{:keys [err expr program]}]
  (str "The following Spell program failed:\n\n"
       (pr-str program)
       "\n\nError at expression:\n"
       (pr-str expr)
       "\n\nError message: " err))

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
  (fn [result _recovery-call-fn]
    (let [{:keys [err expr program]} result
          ;; Unwrap "Function call failed: " prefix from invoke-fn errors
          ;; so we can match the inner error pattern.
          inner-err (if (str/starts-with? err "Function call failed: ")
                      (subs err (count "Function call failed: "))
                      err)]
      (when-let [fix
                 (cond
                   ;; Case 1: "Unbound symbol: X" — bare symbol, search all namespaces
                   (str/starts-with? inner-err "Unbound symbol: ")
                   (let [sym (symbol (subs inner-err (count "Unbound symbol: ")))
                         matches (find-in-namespaces sym namespaces)]
                     (when (= 1 (count matches))
                       (let [qualified (first matches)]
                         (eval/vlog (str "  Namespace recovery: " sym " -> " qualified))
                         (substitute-symbol program sym qualified))))

                   ;; Case 2: "Namespace lookup failed: ns/item" — wrong namespace
                   (str/starts-with? inner-err "Namespace lookup failed: ")
                   (let [qualified-str (subs inner-err (count "Namespace lookup failed: "))
                         parts (str/split qualified-str #"/")
                         item-sym (symbol (last parts))
                         bad-qualified (symbol qualified-str)
                         matches (find-in-namespaces item-sym namespaces)]
                     (when (= 1 (count matches))
                       (let [correct (first matches)]
                         (eval/vlog (str "  Namespace recovery: " bad-qualified " -> " correct))
                         (substitute-symbol program bad-qualified correct)))))]
        ;; Return the fixed program for re-evaluation from scratch
        ;; (safe because spell-eval is pure).
        fix))))

;; ---------------------------------------------------------------------------
;; LLM-based recovery (default fallback)
;; ---------------------------------------------------------------------------

(defn default-recover-fn
  "Default recovery function: calls recovery LLM, parses response as s-expression."
  [result recovery-call-fn]
  (let [prompt (format-error-for-recovery result)
        response (recovery-call-fn prompt)]
    (first (parse/read-all response))))
