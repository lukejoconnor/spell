(ns selected-lines-probe
  "Generated-fixture probe: run this identical script against either checkout's src.
   clojure -J-Xms256m -J-Xmx256m -M perf/selected_lines_probe.clj LABEL
   Output is EDN. Fresh JVMs and identical flags are required for comparisons.
   No private inputs. Measures retained live heap separately from transient allocation."
  (:require [clojure.java.io :as jio]
            [spell.io :as sio]))

(def retained (atom nil))

(defn settle! []
  (dotimes [_ 3] (System/gc) (Thread/sleep 100))
  (.getUsed (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))))

(defn allocated-bytes []
  ;; Optional HotSpot extension, invoked reflectively for portability.
  (try
    (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
      (when (.isThreadAllocatedMemorySupported bean)
        (when-not (.isThreadAllocatedMemoryEnabled bean)
          (.setThreadAllocatedMemoryEnabled bean true))
        (.getThreadAllocatedBytes bean (.getId (Thread/currentThread)))))
    (catch Exception _ nil)))

(defn result-shape [result]
  (let [sub? (instance? clojure.lang.APersistentVector$SubVector result)]
    {:result-class (.getName (class result))
     :returned-lines (count result)
     :metadata (meta result)
     :subvector? sub?
     :direct-backing-lines (when sub? (count (.v ^clojure.lang.APersistentVector$SubVector result)))}))

(defn measure! [path start end expected]
  (let [before (allocated-bytes)
        t0 (System/nanoTime)
        result (sio/read-lines path start end)
        elapsed (- (System/nanoTime) t0)
        after (allocated-bytes)]
    (assert (= expected result) "Selected contents changed")
    (assert (= {:spell/first-line start} (meta result)) "Line metadata changed")
    (reset! retained result)
    (assoc (result-shape result)
           :read-nanos elapsed
           :thread-allocated-bytes (when (and before after) (- after before)))))

(let [label (or (first *command-line-args*) "unlabeled")
      line-count 200000
      selected-count 100
      start 100001
      end (+ start selected-count)
      padding (apply str (repeat 96 \x))
      row (fn [i] (str i ":" padding))
      expected (mapv row (range start end))
      file (java.io.File/createTempFile "spell-selected-lines-" ".txt")]
  (try
    (with-open [writer (jio/writer file)]
      (doseq [i (range 1 (inc line-count))]
        (.write writer (str (row i) "\n"))))
    (let [path (.getPath file)]
      ;; Resolve code/JIT before retained-live baseline; do not retain warmups.
      (dotimes [_ 3] (sio/read-lines path start end))
      (let [baseline (settle!)
            measurement (measure! path start end expected)
            held (settle!)]
        (reset! retained nil)
        (let [released (settle!)]
          (prn (merge measurement
                      {:label label :java-version (System/getProperty "java.version")
                       :clojure-version (clojure-version)
                       :file-lines line-count :file-bytes (.length file)
                       :warmup-reads 3 :measured-reads 1
                       :heap-before-bytes baseline :heap-held-bytes held
                       :heap-released-bytes released
                       :retained-delta-bytes (- held baseline)
                       :released-delta-bytes (- held released)
                       :allocation-note "Both versions slurp and split the whole file; selected-vector copying targets retention, not allocation."})))))
    (finally
      (reset! retained nil)
      (.delete file))))
