(ns trace-export-probe
  "Portable synthetic trace export probe. No provider calls or private inputs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [spell.trace :as trace])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def defaults {:nodes 36 :forms 24 :warmup 1 :runs 1})

(defn fixture [{:keys [nodes forms]}]
  (let [source (list 'quine 'completion
                     (list 'eval
                           (cons 'do
                                 (cons '(quine prompt "Synthetic source: quotes \" newline\nUnicode λ; no private data.")
                                       (for [i (range forms)]
                                         (list 'def (symbol (str "step-" i))
                                               {:index i :context ["repeated-source" {:nested '(+ 40 2)}]
                                                :result '(quote (!extend))}))))))
        raw (pr-str source)
        records (mapv (fn [id]
                        (let [parent (when (pos? id) (quot (dec id) 2))]
                          (cond-> {:id id :parent parent
                                   :depth (loop [n id d 0]
                                            (if (zero? n) d (recur (quot (dec n) 2) (inc d))))
                                   :variant (if (zero? (mod id 5)) :leaf :self)
                                   :prompt raw :raw-text raw
                                   :response (str "Synthetic completion " id "\n" raw)
                                   :program source :hooked source
                                   :start-ms (+ 1700000000000 id) :end-ms (+ 1700000000100 id)
                                   :children (mapv (fn [child] {:child-id child :call-prompt raw})
                                                   (filter #(< % nodes) [(inc (* 2 id)) (+ 2 (* 2 id))]))}
                            (zero? (mod id 7)) (assoc :error "Synthetic failure: \"quoted\"\nλ")
                            (not (zero? (mod id 7))) (assoc :value {:ok true :nil nil :ratio 2/3
                                                                  :big 123456789012345678901234567890N
                                                                  :set #{:a :b} :items [id '(+ 40 2)]}))))
                      (range nodes))]
    {:nodes records :next-id nodes :root 0
     :warnings [{:message "Synthetic warning" :data {:source source :escape "\\\"\n\tλ"}
                 :timestamp-ms 1700000000200}]}))

(defn filename [{:keys [id variant]}]
  (format "%04d%s" id (if (= :leaf variant) ".txt" ".spl")))

(defn expected-edn [value]
  (update value :nodes #(mapv (fn [node] (assoc (dissoc node :raw-text) :file (filename node))) %)))

(defn verify! [value dir]
  (let [file (io/file dir "trace.edn")
        restored (with-open [r (java.io.PushbackReader. (io/reader file))]
                   (binding [*read-eval* false]
                     (let [v (read {:eof ::eof} r)]
                     (assert (= ::eof (read {:eof ::eof} r)) "Extra trace forms")
                     v)))]
    (assert (= (expected-edn value) restored) "Trace data changed")
    (doseq [node (:nodes value)]
      (assert (= (:raw-text node) (slurp (io/file dir (filename node)))) "Program file changed"))
    (assert (= (str (trace/tree-str value) "\n") (slurp (io/file dir "tree.txt"))) "Tree changed")
    {:readback-ok true :edn-bytes (.length file)
     :total-bytes (reduce + (map #(.length %) (filter #(.isFile %) (file-seq (io/file dir)))))}))

(defn thread-meters []
  (let [bean (ManagementFactory/getThreadMXBean)
        cpu? (.isCurrentThreadCpuTimeSupported bean)
        alloc? (and (instance? com.sun.management.ThreadMXBean bean)
                    (.isThreadAllocatedMemorySupported ^com.sun.management.ThreadMXBean bean))]
    (when cpu? (.setThreadCpuTimeEnabled bean true))
    (when alloc? (.setThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean bean true))
    {:bean bean :cpu? cpu? :alloc? alloc? :thread-id (.getId (Thread/currentThread))}))

(defn snapshot [{:keys [bean cpu? alloc? thread-id]}]
  {:wall (System/nanoTime)
   :cpu (when cpu? (.getCurrentThreadCpuTime ^java.lang.management.ThreadMXBean bean))
   :allocated (when alloc? (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id))})

(defn delta [a b key divisor]
  (when (and (get a key) (get b key)) (/ (double (- (get b key) (get a key))) divisor)))

(defn export-once! [value meters]
  (let [before (snapshot meters)
        dir (.toFile (Files/createTempDirectory "spell-trace-probe-" (make-array FileAttribute 0)))
        result (try
                 (let [export-before (snapshot meters)
                       returned (trace/write-trace! value (.getPath dir))
                       export-after (snapshot meters)]
                   (assert (= (.getPath dir) returned) "Return path changed")
                   (merge {:export-wall-ms (delta export-before export-after :wall 1e6)
                           :export-thread-cpu-ms (delta export-before export-after :cpu 1e6)
                           :export-thread-allocated-bytes (delta export-before export-after :allocated 1)}
                          (verify! value dir)))
                 (finally
                   (doseq [f (reverse (file-seq dir))] (io/delete-file f true))))
        after (snapshot meters)]
    (merge result {:wall-ms (delta before after :wall 1e6)
                   :thread-cpu-ms (delta before after :cpu 1e6)
                   :thread-allocated-bytes (delta before after :allocated 1)})))

(defn -main [& [arg]]
  (let [opts (merge defaults (when arg (edn/read-string arg)))
        _ (assert (and (pos-int? (:nodes opts)) (pos-int? (:forms opts))
                       (nat-int? (:warmup opts)) (pos-int? (:runs opts))))
        value (fixture opts)
        meters (thread-meters)]
    (dotimes [_ (:warmup opts)] (export-once! value meters))
    (prn {:probe-version 2 :options opts
          :java-version (System/getProperty "java.version")
          :clojure-version (clojure-version)
          :max-heap-bytes (.maxMemory (Runtime/getRuntime))
          :jvm-args (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
          :measurements (mapv (fn [_] (export-once! value meters)) (range (:runs opts)))})))
