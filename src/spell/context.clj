(ns spell.context
  "Run-owned storage and bounded, lossless insertion of values into model context."
  (:require [spell.parse :as parse]
            [clojure.string :as str])
  (:import [java.io Writer]
           [java.util UUID]))

(def default-max-chars 10000)
(def min-max-chars 128)

(defn new-context
  ([] (new-context {}))
  ([{:keys [max-chars] :or {max-chars default-max-chars}}]
   (when-not (and (integer? max-chars) (<= min-max-chars max-chars))
     (throw (ex-info "Context character limit must be an integer of at least 128"
                     {:type :invalid-context-limit :max-chars max-chars})))
   {:max-chars max-chars :values (atom {})}))

(def ^:dynamic *context*
  "Bound once per API invocation; child futures inherit this reference."
  nil)

(defn- current-context []
  (or *context*
      (throw (ex-info "Value storage requires a run-owned context"
                      {:type :missing-context}))))

(defn store-value! [value]
  (let [id (str (UUID/randomUUID))]
    (swap! (:values (current-context)) assoc id value)
    id))

(defn stored [id]
  (let [values @(:values (current-context))]
    (if (contains? values id)
      (get values id)
      (throw (ex-info (str "No stored value: " id) {:id id})))))

(defn- effective-limit [limit]
  (let [cap (or (:max-chars *context*) default-max-chars)]
    (cond
      (nil? limit) cap
      (not (integer? limit))
      (throw (ex-info "Context limit must be an integer" {:limit limit}))
      (neg? limit) cap
      (< limit min-max-chars)
      (throw (ex-info "Context character limit must be at least 128" {:limit limit}))
      :else (min cap limit))))

(defn- bounded-output
  "Print incrementally into a bounded writer. Overflow stops printing immediately.
   Namespace-map lifting is disabled because it scans a whole map before writing."
  [limit print!]
  (let [out (StringBuilder.)
        overflow (ex-info "Context contribution exceeds its character budget" {::overflow true})
        append! (fn [s off len]
                  (when (> (+ (.length out) len) limit) (throw overflow))
                  (if (string? s)
                    (.append out ^CharSequence s (int off) (int (+ off len)))
                    (.append out ^chars s (int off) (int len))))
        writer (proxy [Writer] []
                 (write
                   ([x]
                    (cond
                      (number? x) (do (when (>= (.length out) limit) (throw overflow))
                                      (.append out (char x)))
                      (string? x) (append! x 0 (count x))
                      :else (append! x 0 (alength ^chars x))))
                   ([x off len] (append! x off len)))
                 (flush [])
                 (close []))]
    (try
      (binding [*out* writer *print-readably* true *print-dup* false
                *print-length* nil *print-level* nil *print-meta* false
                *print-namespace-maps* false]
        (print!))
      (str out)
      (catch clojure.lang.ExceptionInfo e
        (if (::overflow (ex-data e)) nil (throw e)))
      ;; A deeply nested value remains retrievable even when the host printer
      ;; cannot render it. This imposes no depth restriction on stored data.
      (catch StackOverflowError _ nil))))

(defn- value-form [v]
  (cond
    (or (nil? v) (number? v) (string? v) (boolean? v) (keyword? v) (char? v)) v
    (and (vector? v) (contains? (meta v) :spell/first-line))
    (list 'first-line (:spell/first-line (meta v)) v)
    :else (list 'quote v)))

(defn- numbered-form? [form]
  (and (seq? form) (= 'first-line (first form))
       (number? (second form)) (vector? (nth form 2 nil))))

(defn- print-form! [form]
  (if (numbered-form? form)
    (let [[_ first-line lines] form
          width (count (str (+ first-line (max 0 (dec (count lines))))))]
      (print "(first-line ") (pr first-line) (print " [")
      (doseq [[i line] (map-indexed vector lines)]
        (print "\n ") (pr line) (print " ; ")
        (print (format (str "%" width "d") (+ first-line i))))
      (when (seq lines) (print "\n"))
      (print "])"))
    (pr form)))

(defn- reader-stable? [value]
  ;; Called only after the complete candidate fit the bound. Examine that
  ;; bounded value for sorted collections, whose comparators readers discard.
  (loop [pending (list value)]
    (if-let [items (seq pending)]
      (let [v (first items)]
        (cond
          (instance? clojure.lang.Sorted v) false
          (coll? v) (recur (concat (seq v) (rest items)))
          :else (recur (rest items))))
      true)))

(defn- descriptor-form [{:keys [name value] :as descriptor} stored-form]
  (if (contains? descriptor :form)
    (:form descriptor)
    (let [v (or stored-form (value-form value))]
      (if name (list 'def name v) v))))

(defn- print-descriptor! [descriptor stored-form]
  (let [form (descriptor-form descriptor stored-form)]
    (if (and (:name descriptor) (not (contains? descriptor :form)))
      (do (print "(def ") (pr (:name descriptor)) (print " ")
          (print-form! (nth form 2)) (print ")"))
      (print-form! form))))

(defn- render-descriptors [descriptors refs limit]
  (try
    (when-let [text
               (bounded-output limit
                 #(doseq [[i descriptor] (map-indexed vector descriptors)]
                    (when (pos? i) (print " "))
                    (print-descriptor! descriptor (get refs i))))]
      ;; Only inspect a candidate that rendered completely within the bound.
      ;; Printed host objects and unusual symbols may not be faithful reader data.
      (let [forms (parse/read-all text)]
        (when (and (= (count descriptors) (count forms))
                   (every? true?
                     (map-indexed
                       (fn [i descriptor]
                         (and (= (descriptor-form descriptor (get refs i)) (nth forms i))
                              (or (contains? refs i) (reader-stable? (:value descriptor)))))
                       descriptors)))
          text)))
    (catch Exception _ nil)
    (catch StackOverflowError _ nil)))

(defn serialize-contribution
  "Render descriptors under one budget, including separators and binding syntax.
   A descriptor is {:value v}, {:name symbol :value v}, or {:form source-form}.
   Oversized contributions use complete original values through stored references.
   Fixed source forms must themselves fit; failure never silently drops a value.
   API runs bind *context* automatically. Low-level callers must bind new-context
   when values may require storage; use future/bound-fn to convey that binding."
  ([descriptors] (serialize-contribution descriptors nil))
  ([descriptors limit]
   (let [limit (effective-limit limit)
         candidates
         (mapv (fn [descriptor]
                 (let [inline (render-descriptors [descriptor] {} limit)
                       id (when-not (contains? descriptor :form) (str (UUID/randomUUID)))
                       ref (when id (render-descriptors [descriptor] {0 (list 'stored id)} limit))
                       store? (and ref (or (nil? inline) (< (count ref) (count inline))))]
                   {:inline inline :id id :value (:value descriptor)
                    :stored? store? :text (if store? ref inline)}))
               descriptors)
         minimum-size (+ (max 0 (dec (count candidates)))
                         (reduce + (map #(count (:text %)) candidates)))]
     ;; Start with the shortest faithful representation of each value.
     ;; Unlike an all-reference baseline, this also fits many small values.
     (when (or (some #(nil? (:text %)) candidates) (> minimum-size limit))
       (throw (ex-info "Context contribution cannot fit its bindings and stored references; use fewer bindings or a larger limit"
                       {:type :context-capacity :max-chars limit})))
     (let [[chosen _]
           (reduce (fn [[chosen remaining] {:keys [stored? inline text] :as candidate}]
                     (let [extra (when inline (- (count inline) (count text)))
                           restore? (and stored? inline (<= extra remaining))]
                       [(conj chosen (if restore? (assoc candidate :text inline :stored? false) candidate))
                        (if restore? (- remaining extra) remaining)]))
                   [[] (- limit minimum-size)] candidates)
           retained (keep #(when (:stored? %) [(:id %) (:value %)]) chosen)]
       (when (seq retained)
         (swap! (:values (current-context)) into retained))
       (str/join " " (map :text chosen))))))

(defn serialize-value
  ([value] (serialize-value value nil))
  ([value limit] (serialize-contribution [{:value value}] limit)))

(defn contribution-forms
  ([descriptors] (contribution-forms descriptors nil))
  ([descriptors limit] (vec (parse/read-all (serialize-contribution descriptors limit)))))
