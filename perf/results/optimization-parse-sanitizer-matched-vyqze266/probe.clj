(ns parse-sanitizer-probe
  "Bounded attribution, not an optimization benchmark. Generated public fixtures only.
   clojure -J-Xms256m -J-Xmx256m -M perf/parse_sanitizer_probe.clj LABEL
   Run >=3 fresh JVMs with identical flags before interpreting allocation/timing."
  (:require [clojure.java.io :as jio]
            [spell.parse :as p]))

(def sink (volatile! nil))
(def assertion-count (atom 0))

(defn check= [description expected actual]
  (swap! assertion-count inc)
  (when-not (= expected actual)
    (throw (ex-info "Probe semantic assertion failed"
                    {:assertion description :expected expected :actual actual}))))

(defn raw-first [s]
  (with-open [r (java.io.PushbackReader. (java.io.StringReader. s))]
    (let [form (read r false ::eof)]
      (when-not (= ::eof form) form))))

(defn raw-all [s]
  (with-open [r (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [forms []]
      (let [form (read r false ::eof)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn outcome [f s]
  (try {:ok (f s)}
       (catch RuntimeException e
         {:error {:class (.getName (class e)) :message (.getMessage e)}})))

(defn ordered [s]
  (p/sanitize-string-escapes (p/sanitize-nonspell-comment-markers s)))

(defn check-semantics! []
  (doseq [[input expected]
          [["" ""]
           ["  // note\n42" "  ;/ note\n42"]
           ["\t/* note\n*/\n42" "\t;* note\n;/\n42"]
           ["\r// note\r\n42" "\r;/ note\r\n42"]
           ["(str \"// not a comment\")" "(str \"// not a comment\")"]
           ;; Preserve observed baseline behavior; a separate correctness issue.
           ["(str \"line\n// inside string\")" "(str \"line\n;/ inside string\")"]]]
    (check= [:comments input] expected (p/sanitize-nonspell-comment-markers input)))
  (doseq [[input expected]
          [["\"x\\equiv y\"" "\"x\\\\equiv y\""]
           ["\"valid\\n\\t\\\\\\\"\"" "\"valid\\n\\t\\\\\\\"\""]
           ["outside\\equiv" "outside\\equiv"]
           ["\"trailing\\" "\"trailing\\"]]]
    (check= [:escapes input] expected (p/sanitize-string-escapes input)))
  (check= :empty-first nil (p/read-first ""))
  (check= :empty-all [] (p/read-all ""))
  (check= :first-ignores-malformed-tail 42 (p/read-first "42\n("))
  (check= :first-ignores-unmatched-tail 42 (p/read-first "42 ] not-tail"))
  (check= :all-recovers-one-final-delimiter [42] (p/read-all "42 ]  "))
  (doseq [input ["(" "] not-tail" "42 ] not-tail" "\"unfinished"]]
    (let [sanitized (ordered input)]
      (doseq [[public raw] [[p/read-first raw-first] [p/read-all raw-all]]]
        (check= [:reader-outcome input] (outcome raw sanitized) (outcome public input)))))
  ;; Verify order and that read-first still sanitizes the entire input, not a prefix.
  (let [input "// note\n42\n(think \"x\\equiv y\")\n("
        expected-comments ";/ note\n42\n(think \"x\\equiv y\")\n("
        original-comments p/sanitize-nonspell-comment-markers
        original-escapes p/sanitize-string-escapes]
    (doseq [reader [p/read-first p/read-all]]
      (let [calls (atom [])]
        (with-redefs [p/sanitize-nonspell-comment-markers
                      (fn [s] (swap! calls conj [:comments s]) (original-comments s))
                      p/sanitize-string-escapes
                      (fn [s] (swap! calls conj [:escapes s]) (original-escapes s))]
          (outcome reader input))
        (check= :whole-input-ordered-sanitizers
                [[:comments input] [:escapes expected-comments]] @calls)))))

(defn fixture [kind chunks]
  (let [valid-line (str (pr-str (list 'think "Keep selected lines retrievable; quoted \"source\", slash \\, newline\n and unicode λ.")) "\n")
        raw-line (if (= kind :unchanged) valid-line
                     "  // generated annotation\n(think \"x\\equiv y; retained source\")\n")
        comments-line (if (= kind :unchanged) valid-line
                          "  ;/ generated annotation\n(think \"x\\equiv y; retained source\")\n")
        sanitized-line (if (= kind :unchanged) valid-line
                           "  ;/ generated annotation\n(think \"x\\\\equiv y; retained source\")\n")
        wrap (fn [line] (str "(quine completion (eval (do\n"
                            (apply str (repeat chunks line)) "'(!extend))))"))]
    {:kind kind :chunks chunks :input (wrap raw-line)
     :comments (wrap comments-line) :sanitized (wrap sanitized-line)}))

(defn allocated-bytes []
  ;; Optional HotSpot extension. Nil is explicitly reported on unsupported JVMs.
  (try
    (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
      (when (.isThreadAllocatedMemorySupported bean)
        (when-not (.isThreadAllocatedMemoryEnabled bean)
          (.setThreadAllocatedMemoryEnabled bean true))
        (.getThreadAllocatedBytes bean (.getId (Thread/currentThread)))))
    (catch Exception _ nil)))

(defn measure [f input]
  (dotimes [_ 30] (vreset! sink (f input)))
  (let [iterations 10
        samples (mapv (fn [_]
                        (let [before (allocated-bytes)
                              t0 (System/nanoTime)]
                          (dotimes [_ iterations] (vreset! sink (f input)))
                          (let [elapsed (- (System/nanoTime) t0)
                                after (allocated-bytes)]
                            {:nanos (/ (double elapsed) iterations)
                             :allocated (when (and before after)
                                          (/ (double (- after before)) iterations))})))
                      (range 5))
        times (sort (map :nanos samples))
        allocations (when (every? :allocated samples)
                      (sort (map :allocated samples)))]
    (vreset! sink nil)
    {:iterations-per-batch iterations :warmup-iterations 30 :batches 5
     :nanos-per-call (nth times 2) :minimum-nanos-per-call (first times)
     :thread-allocated-bytes-per-call (when allocations (nth allocations 2))
     :minimum-thread-allocated-bytes-per-call (first allocations)
     :samples samples}))

(defn run-fixture [{:keys [kind chunks input comments sanitized]}]
  (check= [kind chunks :comments] comments (p/sanitize-nonspell-comment-markers input))
  (check= [kind chunks :escapes] sanitized (p/sanitize-string-escapes comments))
  (check= [kind chunks :ordered] sanitized (ordered input))
  (check= [kind chunks :first-form] (raw-first sanitized) (p/read-first input))
  (check= [kind chunks :all-forms] (raw-all sanitized) (p/read-all input))
  {:kind kind :chunks chunks :input-chars (count input)
   :comments-chars (count comments) :sanitized-chars (count sanitized)
   :measurements
   (mapv (fn [[label f s]] (assoc (measure f s) :operation label))
         [[:comments p/sanitize-nonspell-comment-markers input]
          [:escapes-on-comment-output p/sanitize-string-escapes comments]
          [:ordered-sanitizers ordered input]
          [:raw-reader-first raw-first sanitized]
          [:read-first p/read-first input]
          [:raw-reader-all raw-all sanitized]
          [:read-all p/read-all input]])})

(check-semantics!)
(let [results (mapv run-fixture (for [chunks [64 256 1024] kind [:unchanged :recovery]]
                                (fixture kind chunks)))]
  (prn {:label (or (first *command-line-args*) "unlabeled")
        :loaded-source (str (jio/resource "spell/parse.clj"))
        :java-version (System/getProperty "java.version")
        :clojure-version (clojure-version)
        :semantic-assertions @assertion-count :results results
        :interpretation "Matched control/candidate probe. Per-operation values are medians of five ten-call batches after thirty warmups. Timings include call/volatile overhead, fixed-order JIT effects, and are not additive. Caller-thread allocation only; unsupported allocation counter yields nil. Inputs constructed and semantic assertions run outside measured regions. read-first sanitizes the whole input but reads only its first form."}))
