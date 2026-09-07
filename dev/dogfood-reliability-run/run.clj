(require '[clojure.test :as test]
         '[clojure.edn :as edn]
         '[spell.reliability-workflow-test :as workflow])
(let [[workflow-path test-path] *command-line-args*
      result (binding [workflow/*workflow-report-path* workflow-path]
               (test/run-tests 'spell.reliability-workflow-test))
      assertions (+ (:pass result) (:fail result) (:error result))
      ;; A receipt read failure is a runner failure, never a retry or test rescue.
      receipt (try (edn/read-string (slurp workflow-path))
                   (catch Exception e {:receipt-error (.getMessage e)}))
      returns (:child-returns receipt)
      checks {:nonzero-tests (pos? (:test result))
              :nonzero-assertions (pos? assertions)
              :workflow-completed (= :completed (:status receipt))
              :tracked-completed-returns
              (and (= 2 (count returns))
                   (= #{:design-worker :implementation-worker} (set (map :from returns)))
                   (every? #(= :completed (get-in % [:body :status])) returns)
                   (every? #(and (integer? (:edge-id %)) (pos? (:edge-id %))) returns)
                   (= 2 (count (set (map :edge-id returns)))))}
      passed? (and (zero? (+ (:fail result) (:error result)))
                   (every? true? (vals checks)))
      report (assoc result :assertions assertions :receipt-checks checks
                           :status (if passed? :completed :failed))]
  (spit test-path (pr-str report))
  (println "Receipt checks:" (pr-str checks))
  (shutdown-agents)
  (System/exit (if passed? 0 1)))
