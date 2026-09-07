(ns spell.perf.harness
  (:require [clojure.data.json :as json] [clojure.java.io :as io]
            [clojure.java.shell :as sh] [clojure.string :as str]
            [spell.perf.core :as core] [spell.perf.lifecycle :as lifecycle]
            [spell.perf.context :as context])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files StandardCopyOption]
           [java.security MessageDigest]))

(def scenarios (vec (concat core/scenarios lifecycle/scenarios context/scenarios)))
(def sink (volatile! nil))
;; A global root prevents JIT liveness from dropping the fixture before the live probe.
;; The harness is deliberately serial; this is not a concurrent benchmark runner.
(def fixture-root (volatile! nil))
(defn gc! [] (dotimes [_ 3] (System/gc) (System/runFinalization) (Thread/sleep 80)))
(defn rss-kib []
  (try (let [pid (.pid (java.lang.ProcessHandle/current))
             r (sh/sh "ps" "-o" "rss=" "-p" (str pid))]
         (when (zero? (:exit r)) (Long/parseLong (str/trim (:out r)))))
       (catch Throwable _ nil)))
(defn resources []
  (let [m (ManagementFactory/getMemoryMXBean) t (ManagementFactory/getThreadMXBean)]
    {:heap-used (.getUsed (.getHeapMemoryUsage m))
     :heap-committed (.getCommitted (.getHeapMemoryUsage m))
     :nonheap-used (.getUsed (.getNonHeapMemoryUsage m))
     :threads (.getThreadCount t) :peak-threads-since-case-reset (.getPeakThreadCount t)
     :rss-kib (rss-kib)}))
(defn allocated []
  (try (let [b (ManagementFactory/getThreadMXBean)]
         (when (and (instance? com.sun.management.ThreadMXBean b)
                    (.isThreadAllocatedMemorySupported ^com.sun.management.ThreadMXBean b))
           (.setThreadAllocatedMemoryEnabled ^com.sun.management.ThreadMXBean b true)
           (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean b (.getId (Thread/currentThread)))))
       (catch Throwable _ nil)))
(defn gc-count []
  (reduce + (map #(max 0 (.getCollectionCount %)) (ManagementFactory/getGarbageCollectorMXBeans))))
(defn stats [xs]
  (when (seq xs)
    (let [n (count xs) mean (/ (reduce + xs) (double n))
          sd (if (> n 1) (Math/sqrt (/ (reduce + (map #(Math/pow (- % mean) 2) xs)) (dec n))) 0.0)
          ordered (vec (sort xs))]
      {:n n :mean mean
       :median (if (odd? n) (nth ordered (quot n 2))
                   (/ (+ (nth ordered (dec (quot n 2))) (nth ordered (quot n 2))) 2.0))
       :min (first ordered) :max (last ordered) :sd sd :cv (when-not (zero? mean) (/ sd mean))})))
(defn setup [s p] (when-let [f (:setup s)] (f p)))
(defn measure [s p fixture] (if-let [f (:measure s)] (f p fixture) ((:run s) p)))
(defn cleanup [s fixture] (when-let [f (:cleanup s)] (f fixture)))
(defn invoke! [s p]
  (let [fixture (setup s p)]
    (try (measure s p fixture) (finally (cleanup s fixture)))))
(defn wall-sample [s p]
  (let [g (gc-count) a (allocated) t (System/nanoTime)
        evidence (invoke! s p) elapsed (- (System/nanoTime) t) b (allocated)]
    (vreset! sink evidence)
    {:wall-ms (/ elapsed 1e6) :allocation-current-thread-bytes (when (and a b) (- b a))
     :gc-collections (- (gc-count) g) :evidence evidence}))
(defn release-fixture! [s]
  (try (cleanup s @fixture-root) (finally (vreset! fixture-root nil) (vreset! sink nil))))
(defn memory-sample [s p]
  ;; Independent execution. Evidence must be small scalar/map data, never the fixture.
  (vreset! sink nil) (gc!)
  (let [before (resources)
        released? (volatile! false)]
    (vreset! fixture-root (setup s p))
    (try
      (let [evidence (measure s p @fixture-root)]
        (vreset! sink evidence) (gc!)
        (let [live (resources)]
          (vreset! released? true)
          (release-fixture! s)
          (gc!)
          (let [after (resources)]
            {:before before :live live :after-cleanup after
             :retained-live-delta-bytes (- (:heap-used live) (:heap-used before))
             :post-cleanup-delta-bytes (- (:heap-used after) (:heap-used before))
             :evidence evidence})))
      (finally
        (when-not @released?
          (vreset! released? true)
          (release-fixture! s))))))
(defn sha256 [f]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d (java.nio.file.Files/readAllBytes (.toPath (io/file f))))
    (format "%064x" (java.math.BigInteger. 1 (.digest d)))))
(defn environment []
  {:java-version (System/getProperty "java.version") :java-vendor (System/getProperty "java.vendor")
   :vm (System/getProperty "java.vm.name") :clojure-version (clojure-version)
   :jvm-args (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
   :os (System/getProperty "os.name") :os-version (System/getProperty "os.version")
   :arch (System/getProperty "os.arch") :processors (.availableProcessors (Runtime/getRuntime))
   :max-heap (.maxMemory (Runtime/getRuntime)) :seed 424242
   :revision (str/trim (:out (sh/sh "git" "rev-parse" "HEAD")))
   :dirty-status (:out (sh/sh "git" "status" "--short"))
   :source-sha256 (into (sorted-map)
                  (for [root ["src/spell" "config/spl-lib" "perf/spell"]
                        f (file-seq (io/file root)) :when (.isFile f)]
                    [(str f) (sha256 f)]))})
(def integer-options #{"--warmup" "--reps" "--memory-reps" "--params-index"})
(def allowed-options (into integer-options ["--out" "--scenario" "--list"]))
(defn parse-options [args]
  (loop [xs args opts {:warmup 2 :reps 5 :memory-reps 3 :out "perf/results/run.edn"}]
    (if (empty? xs) opts
      (let [[k v & tail] xs]
        (when-not (contains? allowed-options k) (throw (ex-info "Unknown option" {:option k})))
        (when-not v (throw (ex-info "Option requires a value" {:option k})))
        (recur tail (assoc opts (keyword (subs k 2))
                          (if (contains? integer-options k) (Long/parseLong v) v)))))))
(defn validate-options! [opts]
  (when-not (and (<= 0 (:warmup opts) 30) (<= 1 (:reps opts) 100)
                 (<= 1 (:memory-reps opts) 30)
                 (or (nil? (:params-index opts)) (<= 0 (:params-index opts)))
                 (or (nil? (:list opts)) (contains? #{"true" "false"} (:list opts))))
    (throw (ex-info "Invalid option bounds" {:options opts})))
  opts)
(defn run-case [s p opts]
  (let [started (str (java.time.Instant/now))]
    (try
      (dotimes [_ (:warmup opts)] (vreset! sink (invoke! s p)))
      (vreset! sink nil) (gc!)
      (.resetPeakThreadCount (ManagementFactory/getThreadMXBean))
      (let [before (resources)
            wall (vec (repeatedly (:reps opts) #(wall-sample s p)))
            after-wall (resources)
            memory (vec (repeatedly (:memory-reps opts) #(memory-sample s p)))
            _ (vreset! sink nil) _ (gc!) after-cleanup (resources)]
        {:id (:id s) :params p :status :ok :started started :description (:description s)
         :bounds (:bounds s) :notes (:notes s)
         :timed-region "setup + workload + correctness checks + cleanup; excludes harness resource probes and explicit GC"
         :wall wall :wall-ms (stats (map :wall-ms wall))
         :throughput-invocations-per-second (stats (map #(/ 1000.0 (:wall-ms %)) wall))
         :allocation-current-thread-bytes (stats (keep :allocation-current-thread-bytes wall))
         :memory memory :retained-live-delta-bytes (stats (map :retained-live-delta-bytes memory))
         :post-cleanup-delta-bytes (stats (map :post-cleanup-delta-bytes memory))
         :before before :after-wall after-wall :after-cleanup after-cleanup
         :post-cleanup-heap-delta-bytes (- (:heap-used after-cleanup) (:heap-used before))})
      (catch Throwable t
        {:id (:id s) :params p :status :failed :started started
         :error-class (.getName (class t)) :error (.getMessage t)
         :stack (mapv str (take 12 (.getStackTrace t)))}))))
(defn write-checkpoint! [path result]
  ;; Serialize before touching disk; rename a same-directory temporary file so
  ;; interruption leaves either the previous complete checkpoint or the new one.
  (let [target (.toPath (.getAbsoluteFile (io/file path)))
        content (if (str/ends-with? path ".json") (json/write-str result) (pr-str result))]
    (io/make-parents path)
    (let [temporary (Files/createTempFile (.getParent target) ".perf-checkpoint-" ".tmp"
                                         (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile temporary) content)
        (Files/move temporary target
                    (into-array java.nio.file.CopyOption
                                [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (finally (Files/deleteIfExists temporary))))))

(defn run-cases! [cases opts metadata]
  (let [planned (count cases)
        initial (assoc metadata :run-status :incomplete :planned-cases planned
                                :completed-cases 0 :results [])]
    (write-checkpoint! (:out opts) initial)
    (reduce
      (fn [result [scenario params]]
        (let [measured (run-case scenario params opts)
              results (conj (:results result) measured)
              checkpoint (assoc result :results results :completed-cases (count results)
                                :run-status (if (< (count results) planned) :incomplete
                                                (if (every? #(= :ok (:status %)) results)
                                                  :complete :failed)))]
          ;; Persistence and console output are outside every timed region.
          (write-checkpoint! (:out opts) checkpoint)
          (prn (select-keys measured [:id :params :status :wall-ms :error]))
          (flush)
          checkpoint))
      initial cases)))

(defn -main [& args]
  (try
    (let [opts (validate-options! (parse-options args))]
      (if (= "true" (:list opts))
        (do (println (json/write-str (mapv #(select-keys % [:id :description :params :bounds :notes]) scenarios)))
            (shutdown-agents))
        (let [selected (filter #(or (nil? (:scenario opts)) (= (name (:id %)) (:scenario opts))) scenarios)
              _ (when (empty? selected) (throw (ex-info "Unknown scenario" {:options opts})))
              cases (for [s selected [i p] (map-indexed vector (:params s))
                          :when (or (nil? (:params-index opts)) (= i (:params-index opts)))] [s p])
              _ (when (empty? cases) (throw (ex-info "No matching parameter case" {:options opts})))
              metadata {:schema 3 :environment (environment) :options opts
                      :methodology {:independent-memory-pass true :gc "3 System.gc/finalization requests + 80ms sleep each; best effort, not guaranteed"
                                    :allocation-scope :calling-thread-only :allocation-excludes "all worker/future threads"
                                    :rss-unit :KiB :rss "point samples; not peak RSS"
                                    :thread-peak "process-wide since case reset; includes harness/JVM threads"
                                    :native-memory :unmeasured :heap-is-not-rss true
                                    :live-root "global fixture-root until cleanup; fixtures must clear their internal roots"
                                    :post-cleanup "independent pass after cleanup and dropping fixture root; small evidence/results still retained"
                                    :ordering "serial cases in module/parameter order, one JVM; not fork-isolated"}}
              result (run-cases! cases opts metadata)]
          (shutdown-agents)
          (System/exit (if (= :complete (:run-status result)) 0 1)))))
    (catch Throwable t
      (binding [*out* *err*] (println (.getMessage t)))
      (shutdown-agents) (System/exit 2))))
