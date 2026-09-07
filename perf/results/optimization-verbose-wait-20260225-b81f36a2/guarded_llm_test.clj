(require '[clojure.test :as t] 'spell.llm-test)
(try
  (let [result (t/run-tests 'spell.llm-test)
        ok? (and (pos? (:test result 0)) (>= (:test result) 77)
                 (>= (:pass result) 675) (zero? (:fail result)) (zero? (:error result)))]
    (prn {:guarded-result result :positive-test-count (pos? (:test result 0)) :ok ok?})
    (shutdown-agents)
    (System/exit (if ok? 0 1)))
  (catch Throwable e (.printStackTrace e) (shutdown-agents) (System/exit 1)))
