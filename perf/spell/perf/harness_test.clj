(ns spell.perf.harness-test
  (:require [clojure.test :refer :all] [clojure.data.json :as json]
            [clojure.java.io :as io] [spell.perf.harness :as h]))
(deftest summary-and-options
  (is (= 2.5 (:median (h/stats [1 2 3 4]))))
  (is (= 3 (:median (h/stats [1 3 7]))))
  (is (nil? (h/stats [])))
  (is (thrown? Exception (h/parse-options ["--bogus" "1"])))
  (is (thrown? Exception (h/validate-options! (assoc (h/parse-options []) :reps 0)))))
(deftest independent-memory-and-cleanup
  (let [events (atom []) external (atom nil)
        s {:setup (fn [_] (swap! events conj :setup) (reset! external (byte-array 100000)) external)
           :measure (fn [_ f] (is (identical? f @h/fixture-root))
                      (swap! events conj :measure) {:bytes (alength ^bytes @f)})
           :cleanup (fn [f] (swap! events conj :cleanup) (reset! f nil))}
        r (with-redefs [h/gc! (fn []) h/resources (fn [] {:heap-used 100})]
            (h/memory-sample s {}))]
    (is (= [:setup :measure :cleanup] @events))
    (is (nil? @external))
    (is (nil? @h/fixture-root))
    (is (= 0 (:post-cleanup-delta-bytes r)))))
(deftest cleanup-on-error-exactly-once
  (doseq [fail-at [:measure :cleanup]]
    (let [n (atom 0)
          s {:setup (fn [_] :fixture)
             :measure (fn [_ _] (when (= fail-at :measure) (throw (Exception. "measure"))) :ok)
             :cleanup (fn [_] (swap! n inc) (when (= fail-at :cleanup) (throw (Exception. "cleanup"))))}]
      (with-redefs [h/gc! (fn []) h/resources (fn [] {:heap-used 0})]
        (is (thrown? Exception (h/memory-sample s {}))))
      (is (= 1 @n))
      (is (nil? @h/fixture-root)))))
(defn with-output [f]
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory
                            "spell-perf-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        output (str (io/file directory "result.json"))]
    (try (f output)
      (finally (doseq [file (reverse (file-seq directory))] (io/delete-file file))))))
(defn read-result [path] (json/read-str (slurp path) :key-fn keyword))

(deftest checkpoint-survives-interruption-before-next-case
  (with-output
    (fn [output]
      (let [calls (atom 0)
            cases [[{:id :first} {}] [{:id :interrupted} {}]]]
        (with-redefs [h/run-case
                      (fn [s _ _]
                        (case (swap! calls inc)
                          1 (do (is (= "incomplete" (:run-status (read-result output))))
                                (is (= 0 (:completed-cases (read-result output))))
                                {:id (:id s) :status :ok :evidence {:count 1}})
                          2 (do (is (= 1 (:completed-cases (read-result output))))
                                (throw (InterruptedException. "terminated during next case")))))]
          (is (thrown? InterruptedException
                       (h/run-cases! cases {:out output} {:schema 3}))))
        (let [saved (read-result output)]
          (is (= "incomplete" (:run-status saved)))
          (is (= 2 (:planned-cases saved)))
          (is (= 1 (:completed-cases saved)))
          (is (= "first" (get-in saved [:results 0 :id])))
          (is (= {:count 1} (get-in saved [:results 0 :evidence]))))))))

(deftest checkpoints-distinguish-complete-and-failed-runs
  (with-output
    (fn [output]
      (doseq [status [:ok :failed]]
        (with-redefs [h/run-case (fn [s _ _] {:id (:id s) :status status})]
          (let [result (h/run-cases! [[{:id :only} {}]] {:out output} {:schema 3})]
            (is (= (if (= status :ok) :complete :failed) (:run-status result)))
            (is (= 1 (:completed-cases (read-result output))))
            (is (= (name (:run-status result)) (:run-status (read-result output))))))))))

(deftest failed-checkpoint-write-preserves-previous-json
  (with-output
    (fn [output]
      (h/write-checkpoint! output {:run-status :incomplete :completed-cases 1})
      (let [previous (slurp output)]
        (with-redefs [clojure.core/spit (fn [path _]
                                         (with-open [w (io/writer path)] (.write w "partial"))
                                         (throw (java.io.IOException. "write interrupted")))]
          (is (thrown? java.io.IOException
                       (h/write-checkpoint! output {:run-status :complete}))))
        (is (= previous (slurp output)))
        (is (= 1 (count (.listFiles (.getParentFile (io/file output))))))))))

(defn -main [& _]
  (let [r (run-tests 'spell.perf.harness-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
