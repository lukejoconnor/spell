(ns spell.context
  "Pure, bounded ordinary snapshots at explicit model-display boundaries."
  (:require [spell.parse :as parse]
            [clojure.string :as str])
  (:import [java.io Writer]))

(def default-max-chars 10000)
(def min-max-chars 128)

(defn- checked-limit [n]
  (when-not (and (integer? n) (<= min-max-chars n))
    (throw (ex-info "Context character limit must be an integer of at least 128"
                    {:type :invalid-context-limit :max-chars n})))
  n)

(defn new-context
  ([] (new-context {}))
  ([{:keys [max-chars] :or {max-chars default-max-chars}}]
   {:max-chars (checked-limit max-chars)}))

(def ^:dynamic *context* "Display defaults only; no retained values or lookup backend." nil)

(defn effective-limit [limit]
  (checked-limit (if (nil? limit)
                   (or (:max-chars *context*) default-max-chars)
                   limit)))

(defn- bounded-output [limit print!]
  (let [out (StringBuilder.)
        overflow (ex-info "Snapshot exceeds target" {::overflow true})
        append! (fn [s off len]
                  (when (> (+ (.length out) len) limit) (throw overflow))
                  (if (string? s)
                    (.append out ^CharSequence s (int off) (int (+ off len)))
                    (.append out ^chars s (int off) (int len))))
        writer (proxy [Writer] []
                 (write
                   ([x] (cond
                          (number? x) (do (when (>= (.length out) limit) (throw overflow))
                                          (.append out (char x)))
                          (string? x) (append! x 0 (count x))
                          :else (append! x 0 (alength ^chars x))))
                   ([x off len] (append! x off len)))
                 (flush []) (close []))]
    (try
      (binding [*out* writer *print-readably* true *print-dup* false
                *print-length* nil *print-level* nil *print-meta* false
                *print-namespace-maps* false]
        (print!))
      (str out)
      (catch clojure.lang.ExceptionInfo e
        (if (::overflow (ex-data e)) nil (throw e))))))

(defn numbered? [v]
  (and (vector? v) (contains? (meta v) :spell/first-line)))

(defn line-position [v index]
  (if-let [segments (:spell/segments (meta v))]
    (loop [segments (seq segments) index index]
      (when-let [[start n] (first segments)]
        (if (< index n)
          (when (some? start) (+ start index))
          (recur (next segments) (- index n)))))
    (when-some [start (:spell/first-line (meta v))] (+ start index))))

(defn- position-segments [positions]
  (reduce (fn [segments p]
            (let [[start n] (peek segments)]
              (if (and n (or (and (nil? start) (nil? p))
                             (and (some? start) (= p (+ start n)))))
                (conj (pop segments) [start (inc n)])
                (conj segments [p 1]))))
          [] positions))

(defn- with-positions [rows positions fallback]
  (let [segments (position-segments positions)]
    (with-meta (vec rows)
      (cond-> {:spell/first-line (if (seq positions) (first positions) fallback)}
        (> (count segments) 1) (assoc :spell/segments segments)))))

(defn first-line-vector
  "Join literal start/vector pairs. nil starts mark synthetic rows, not source lines."
  [pairs]
  (when-not (and (seq pairs) (even? (count pairs)))
    (throw (ex-info "first-line expects start/vector pairs" {})))
  (let [segments (partition 2 pairs)]
    (doseq [[start rows] segments]
      (when-not (vector? rows)
        (throw (ex-info "first-line expects a vector literal" {})))
      (when-not (or (nil? start) (and (integer? start) (pos? start)))
        (throw (ex-info "first-line expects positive line numbers or nil" {}))))
    (with-meta (vec (mapcat second segments))
      (cond-> {:spell/first-line (ffirst segments)}
        (> (count segments) 1)
        (assoc :spell/segments (mapv (fn [[start rows]] [start (count rows)]) segments))))))

(defn subvec-lines
  ([v start] (subvec-lines v start (count v)))
  ([v start end]
   (let [rows (subvec v start end)]
     (if (numbered? v)
       (with-positions rows (mapv #(line-position v %) (range start end))
         (line-position v start))
       rows))))

(defn- reader-atom? [v]
  (try (= v (parse/read-first (pr-str v))) (catch Throwable _ false)))

(declare value-form print-form!)

(defn- literal-data? [v]
  (cond
    (numbered? v) false
    (and (map? v) (:spell/fn v)) false
    (or (symbol? v) (keyword? v) (number? v)) (reader-atom? v)
    (or (nil? v) (string? v) (boolean? v) (char? v)) true
    (map? v) (every? (fn [[k x]] (and (literal-data? k) (literal-data? x))) v)
    (coll? v) (every? literal-data? v)
    :else false))

(defn value-form
  "Expression for an already materialized value. No truncation or retention policy."
  [v]
  (cond
    (or (nil? v) (string? v) (boolean? v) (number? v) (char? v)) v
    (symbol? v) (if (reader-atom? v) (list 'quote v)
                   (if-let [ns (namespace v)] (list 'symbol ns (name v))
                     (list 'symbol (name v))))
    (keyword? v) (if (reader-atom? v) v
                    (if-let [ns (namespace v)] (list 'keyword ns (name v))
                      (list 'keyword (name v))))
    (and (map? v) (:spell/fn v) (vector? (:params v)) (seq? (:body v)))
    (list* 'fn (:params v) (:body v))
    (numbered? v)
    (let [segments (or (:spell/segments (meta v))
                       [[(:spell/first-line (meta v)) (count v)]])]
      (loop [segments (seq segments) offset 0 args []]
        (if-let [[start n] (first segments)]
          (recur (next segments) (+ offset n)
            (conj args start (subvec v offset (+ offset n))))
          (list* 'first-line (if (seq args) args [(:spell/first-line (meta v)) []])))))
    (and (or (map? v) (set? v) (seq? v)) (literal-data? v)) (list 'quote v)
    (vector? v) (mapv value-form v)
    (map? v) (if (every? literal-data? (keys v))
               (into {} (map (fn [[k x]] [k (value-form x)])) v)
               (list 'into {} (mapv (fn [[k x]] [(value-form k) (value-form x)]) v)))
    (set? v) (list 'set (mapv value-form v))
    (sequential? v) (list* 'list (map value-form v))
    :else (throw (ex-info "Value has no ordinary source representation" {}))))

(defn- first-line-form? [form]
  (and (seq? form) (= 'first-line (first form))
       (seq (rest form)) (even? (count (rest form)))
       (every? (fn [[start rows]]
                 (and (or (nil? start) (integer? start)) (vector? rows)))
               (partition 2 (rest form)))))

(defn- print-delimited! [open close values]
  (print open)
  (doseq [[i v] (map-indexed vector values)]
    (when (pos? i) (print " "))
    (print-form! v))
  (print close))

(defn print-form!
  "One exact recursive renderer for both inserted snapshots and retained prefixes."
  [form]
  (cond
    (first-line-form? form)
    (do (print "(first-line")
        (doseq [[start rows] (partition 2 (rest form))]
          (print " ") (pr start) (print " [")
          (doseq [[i row] (map-indexed vector rows)]
            (print "\n ") (print-form! row)
            (when (some? start) (print " ; ") (print (+ start i))))
          (when (seq rows) (print "\n"))
          (print "]"))
        (print ")"))
    (map? form) (print-delimited! "{" "}" (mapcat identity form))
    (vector? form) (print-delimited! "[" "]" form)
    (set? form) (print-delimited! "#{" "}" form)
    (seq? form) (print-delimited! "(" ")" form)
    :else (pr form)))

(defn render-form [form] (with-out-str (print-form! form)))

(defn- fits [v limit]
  (bounded-output limit #(print-form! (value-form v))))

(defn- diagnostic [v reason]
  {:spell/representation-unavailable true
   :type (if (nil? v) "nil" (.getName (class v)))
   :reason reason})

(defn- omitted [n] {:spell/omitted n})

(defn- add-map-marker [m n]
  (let [k (first (remove #(contains? m %)
                  (cons :spell/omitted
                    (map #(keyword "spell" (str "omitted-" %)) (range 1 Long/MAX_VALUE)))))]
    (assoc m k n)))

(defn- external-result? [v]
  (and (map? v) (boolean? (:ok v))
       (or (not (contains? v :truncated)) (boolean? (:truncated v)))
       (every? #(contains? v %) [:out :err])
       (every? #(or (nil? (get v %)) (integer? (get v %))) [:exit :status])))

(def ^:private result-control-keys [:ok :exit :status :truncated])
(def ^:private result-envelope-keys (into result-control-keys [:out :err]))

(defn- gap-string [v start end]
  (str "... [omitted " (- end start) " rows; source "
       (or (line-position v start) "unknown") ".."
       (or (line-position v (dec end)) "unknown") "] ..."))

(defn- select-vector [v indices gap-start gap-end]
  (let [head (take-while #(< % gap-start) indices)
        tail (drop (count head) indices)
        gap? (< gap-start gap-end)
        rows (concat (map #(nth v %) head)
                     (when gap? [(if (numbered? v)
                                   (gap-string v gap-start gap-end)
                                   (omitted (- gap-end gap-start)))])
                     (map #(nth v %) tail))]
    (if (numbered? v)
      (with-positions rows
        (vec (concat (map #(line-position v %) head)
               (when gap? [nil]) (map #(line-position v %) tail)))
        (:spell/first-line (meta v)))
      (vec rows))))

(def ^:dynamic ^:private *snapshot-char-limit* nil)

(declare normalize-value)

(defn- normalize-payload [v fuel depth changed]
  (try (normalize-value v fuel depth changed)
       (catch Throwable _
         (vreset! changed true)
         (diagnostic v "conversion failed"))))

(defn- normalize-map [v fuel depth changed]
  (loop [entries (seq v) result {} omitted? false]
    (cond
      (nil? entries) (if omitted? (add-map-marker result :unknown) result)
      (<= @fuel 0) (do (vreset! changed true) (add-map-marker result :unknown))
      :else
      (let [[k x] (first entries)
            key-changed (volatile! false)
            key (normalize-value k fuel (inc depth) key-changed)]
        (if @key-changed
          (do (vreset! changed true) (recur (next entries) result true))
          (recur (next entries)
            (assoc result key (normalize-payload x fuel (inc depth) changed)) omitted?))))))

(defn- normalize-value [v fuel depth changed]
  (vswap! fuel dec)
  (cond
    (or (neg? @fuel) (> depth 64))
    (do (vreset! changed true) (diagnostic v "bounded traversal"))
    (or (nil? v) (string? v) (boolean? v) (symbol? v) (keyword? v) (char? v)) v
    (number? v) (if (reader-atom? v) v
                 (do (vreset! changed true) (diagnostic v "unreadable number")))
    (and (map? v) (:spell/fn v) (vector? (:params v)) (seq? (:body v)))
    (if (try (let [text (bounded-output (or *snapshot-char-limit* (quot (*' (effective-limit nil) 6) 5))
                                      #(pr (list* 'fn (:params v) (:body v))))]
               (and text (= (list* 'fn (:params v) (:body v)) (parse/read-first text))))
             (catch Throwable _ false))
      v (do (vreset! changed true) (diagnostic v "source unavailable")))
    (vector? v)
    (let [n (count v) k (min n (max 0 (quot @fuel 2)))
          head (if (= k n) n (quot (* k 3) 4)) tail (- k head)
          indices (vec (concat (range head) (range (- n tail) n)))
          selected (select-vector v indices head (- n tail))
          result (mapv #(if (and (numbered? selected) (string? %))
                          (do (vswap! fuel dec) %)
                          (normalize-payload % fuel (inc depth) changed)) selected)]
      (when (< k n) (vreset! changed true))
      (if (numbered? selected) (with-meta result (meta selected)) result))
    (external-result? v)
    (let [local-changed (volatile! false)
          out (normalize-payload (:out v) fuel (inc depth) local-changed)
          err (normalize-payload (:err v) fuel (inc depth) local-changed)
          extras (normalize-map (apply dissoc v result-envelope-keys)
                   fuel depth local-changed)]
      (when @local-changed (vreset! changed true))
      (assoc (merge extras (select-keys v result-control-keys))
             :out out :err err
             :truncated (or (true? (:truncated v)) @local-changed)))
    (map? v) (normalize-map v fuel depth changed)
    (or (set? v) (sequential? v))
    (loop [items (seq v) result []]
      (if (and items (pos? @fuel))
        (recur (next items) (conj result (normalize-value (first items) fuel (inc depth) changed)))
        (let [result (if items (do (vreset! changed true) (conj result (omitted :unknown))) result)]
          (if (set? v) (set result) (apply list result)))))
    :else (do (vreset! changed true) (diagnostic v "unsupported host value"))))

(defn- safe-head [^String s end]
  (if (and (pos? end) (< end (.length s))
           (Character/isHighSurrogate (.charAt s (dec end)))
           (Character/isLowSurrogate (.charAt s end)))
    (dec end) end))

(defn- safe-tail [^String s start]
  (if (and (pos? start) (< start (.length s))
           (Character/isLowSurrogate (.charAt s start))
           (Character/isHighSurrogate (.charAt s (dec start))))
    (inc start) start))

(defn- short-string [s budget]
  (let [n (count s)
        make (fn [k]
               (let [h (safe-head s (min n (quot (* k 3) 4)))
                     t (safe-tail s (max h (- n (- k (quot (* k 3) 4)))))]
                 (str (subs s 0 h) "... [omitted " (- t h) " UTF-16; " h ".." t
                      " of " n "] ..." (subs s t))))]
    (loop [lo 0 hi (min n budget) best (make 0)]
      (if (> lo hi) best
        (let [mid (quot (+ lo hi) 2) candidate (make mid)]
          (if (fits candidate budget)
            (recur (inc mid) hi candidate)
            (recur lo (dec mid) best)))))))

(declare shorten)

(defn- short-vector [v budget]
  (loop [k (min (count v) (max 0 (quot budget 24)))]
    (let [n (count v) head (if (= k n) n (quot (* k 3) 4)) tail (- k head)
          indices (vec (concat (range head) (range (- n tail) n)))
          selected (select-vector v indices head (- n tail))
          each (max 8 (quot (max 0 (- budget 80)) (max 1 (count selected))))
          rows (mapv (fn [i row]
                       (if (and (numbered? selected) (nil? (line-position selected i)))
                         row
                         (shorten row each)))
                 (range (count selected)) selected)
          candidate (if (numbered? selected) (with-meta rows (meta selected)) rows)]
      (if (or (fits candidate budget) (zero? k)) candidate
        (recur (quot k 2))))))

(defn- short-map [v budget]
  (loop [entries (seq v) result {} remaining (- budget 32) omitted-count 0]
    (if-let [[k x] (first entries)]
      (let [key-text (fits k (max 0 remaining))
            allowance (max 8 (quot remaining (max 1 (count entries))))
            value (when key-text (shorten x (- allowance (count key-text))))
            candidate (when key-text (assoc result k value))]
        (if (and candidate (fits candidate (- budget 32)))
          (recur (next entries) candidate
            (- budget 32 (count (fits candidate budget))) omitted-count)
          (recur (next entries) result remaining (inc omitted-count))))
      (if (pos? omitted-count) (add-map-marker result omitted-count) result))))

(defn- short-result [v budget]
  (let [controls (select-keys v result-control-keys)
        extras (apply dissoc v result-envelope-keys)
        base-overhead (count (render-form (value-form (assoc controls :out nil :err nil :truncated true))))
        extras (if (seq extras)
                 (short-map extras (max 32 (quot (- budget base-overhead) 4))) {})
        fixed (merge extras controls)
        overhead (count (render-form (value-form (assoc fixed :out nil :err nil :truncated true))))
        available (max 8 (- budget overhead))
        err (shorten (:err v) (max 8 (quot available 4)))
        out (shorten (:out v) (max 8 (- available (count (render-form (value-form err))))))]
    (assoc fixed :out out :err err :truncated true)))

(defn- shorten [v budget]
  (let [budget (max 0 budget)]
    (if (fits v budget) v
      (cond
        (string? v) (short-string v budget)
        (vector? v) (short-vector v budget)
        (external-result? v) (short-result v budget)
        (map? v) (short-map v budget)
        (or (set? v) (sequential? v))
        (let [result (short-vector (vec v) budget)]
          (if (set? v) (set result) (apply list result)))
        :else (diagnostic v "representation exceeds target")))))

(defn snapshot
  "Return {:value ordinary-value :form reader-stable-expression :truncated? boolean}.
   Explicit limits override the run default. Grace is 20% of rendered UTF-16 units.
   Mandatory external-envelope fields may exceed tiny targets; never corrupt status."
  ([value] (snapshot value nil))
  ([value limit]
   (let [limit (effective-limit limit) grace (quot (*' limit 6) 5)]
     (try
       (let [changed (volatile! false)
             normalized (binding [*snapshot-char-limit* grace]
                          (normalize-value value (volatile! (*' grace 2)) 0 changed))
             normalized (if (and @changed (external-result? normalized))
                          (assoc normalized :truncated true) normalized)
             inline (fits normalized grace)
             result (if inline normalized (shorten normalized limit))
             result (if (and (external-result? result) (or @changed (nil? inline)))
                      (assoc result :truncated true) result)]
         {:value result :form (value-form result) :truncated? (or @changed (nil? inline))})
       (catch Throwable _
         (let [v {:spell/representation-unavailable true :reason "conversion failed"}]
           {:value v :form (value-form v) :truncated? true}))))))

(defn- descriptor-form [{:keys [name value body-only?] :as descriptor} limit]
  (if (contains? descriptor :form)
    (:form descriptor)
    (let [value (if body-only?
                  (if (contains? value :body)
                    (assoc value :body (:value (snapshot (:body value) limit))) value)
                  (:value (snapshot value limit)))
          form (value-form value)]
      (if name (list 'def name form) form))))

(defn contribution-forms
  "Materialize independent bounded snapshots. Fixed syntax and host envelope metadata are outside caps."
  ([descriptors] (contribution-forms descriptors nil))
  ([descriptors limit]
   (effective-limit limit)
   (mapv #(descriptor-form % limit) descriptors)))

(defn serialize-contribution
  ([descriptors] (serialize-contribution descriptors nil))
  ([descriptors limit]
   (str/join " " (map render-form (contribution-forms descriptors limit)))))

(defn serialize-value
  ([value] (serialize-value value nil))
  ([value limit] (render-form (:form (snapshot value limit)))))
