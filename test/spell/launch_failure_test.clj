(ns spell.launch-failure-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [spell.coordinator :as coordinator]
            [spell.runtime :as runtime]
            [spell.llm :as llm]
            [spell.test-helpers :as th]))

(use-fixtures :each th/with-test-run)

(def raw "(quine completion (eval (do )))")

(defn- messages [handle]
  (mapv :message (coordinator/drain! handle)))

(deftest retirement-answers-unconsumed-requests-and-rejects-stale-owners-test
  (doseq [h [:worker :parent :later :target]] (coordinator/register! h))
  (let [old (:completed (coordinator/agent :worker))]
    (coordinator/finish! :worker old :old-result)
    (let [current (:completed (coordinator/agent :worker))
          incoming (coordinator/request! :parent [:worker] true :queued)
          outgoing-result (promise)
          _ (coordinator/request! :worker [:target] true :work outgoing-result)
          before (coordinator/snapshot)]
      (is (nil? (coordinator/retire! :worker old :stale)))
      (is (= before (coordinator/snapshot)))
      (coordinator/retire! :worker current :terminal)
      (is (= :terminal (deref current 100 :timeout)))
      (is (nil? (coordinator/agent :worker)))
      (is (empty? (:edges (coordinator/snapshot))))
      (is (= :terminal (:body (first (messages :parent)))))
      (is (true? (:spell/cancelled (deref outgoing-result 100 :timeout))))
      (coordinator/register! :worker)
      (let [replacement (coordinator/agent :worker)]
        (is (nil? (coordinator/retire! :worker current :late)))
        (is (= replacement (coordinator/agent :worker)))))))

(deftest partial-batch-launch-failure-settles-unstarted-children-test
  (coordinator/register! :parent)
  (let [release (promise)
        child (th/compiled-agent-fn (fn [_ _] @release))
        launch @#'runtime/launch-spawn!
        calls (atom [])
        signal (:signal (coordinator/agent :parent))]
    (try
      (binding [runtime/*current-handle* :parent]
        (with-redefs-fn
          {#'runtime/launch-spawn!
           (fn [spec]
             (swap! calls conj (:handle spec))
             (if (= :b (:handle spec))
               (throw (java.util.concurrent.RejectedExecutionException. "rejected"))
               (launch spec)))}
          #(is (thrown? java.util.concurrent.RejectedExecutionException
                        (runtime/prepare-spawns!
                          (mapv (fn [h] {:agent child :prompt :work :handle-name h}) [:a :b :c]))))))
      (is (= [:a :b] @calls))
      (is (some? (coordinator/agent :a)))
      (is (nil? (coordinator/agent :b)))
      (is (nil? (coordinator/agent :c)))
      (let [edge (first (vals (:edges (coordinator/snapshot))))]
        (is (= :pending (get-in edge [:slots :a :status])))
        (doseq [h [:b :c]]
          (is (= :filled (get-in edge [:slots h :status])))
          (is (true? (get-in edge [:slots h :value :spell/child-failure])))))
      (deliver release :completed)
      (is (= :wake (deref signal 5000 :timeout)))
      (is (empty? (:edges (coordinator/snapshot))))
      (let [results (:body (first (messages :parent)))]
        (is (= :completed (:body (first results))))
        (is (every? #(true? (get-in % [:body :spell/child-failure])) (rest results))))
      (finally (deliver release :completed)))))

(deftest startup-error-retires-child-and-settles-edge-test
  (coordinator/register! :parent)
  (let [signal (:signal (coordinator/agent :parent))
        child (th/compiled-agent-fn (fn [_ _] (throw (AssertionError. "fatal startup"))))]
    (binding [runtime/*current-handle* :parent]
      (runtime/prepare-spawns! [{:agent child :prompt :work :handle-name :child}]))
    (is (= :wake (deref signal 5000 :timeout)))
    (is (nil? (coordinator/agent :child)))
    (is (empty? (:edges (coordinator/snapshot))))
    (is (true? (:spell/child-failure (:body (first (messages :parent))))))))

(deftest lifecycle-error-is-bookkept-and-rethrown-test
  (doseq [h [:parent :child]] (coordinator/register! h))
  (coordinator/request! :parent [:child] true :work)
  (let [failure (AssertionError. "fatal evaluation")
        evaluator (fn [_] (throw failure))
        thrown (try
                 (runtime/run-root-box :child raw (runtime/make-awake-fn :child evaluator) evaluator)
                 nil
                 (catch Error e e))]
    (is (identical? failure thrown))
    (is (nil? (coordinator/agent :child)))
    (is (empty? (:edges (coordinator/snapshot))))
    (is (true? (:spell/child-failure (:body (first (messages :parent))))))))

(deftest orphan-submission-failure-retires-next-lifecycle-test
  (doseq [h [:parent :later :child]] (coordinator/register! h))
  (coordinator/request! :parent [:child] true :first)
  (let [evaluator (fn [_]
                    (coordinator/request! :later [:child] true :next)
                    :first-result)]
    (with-redefs [clojure.core/future-call
                  (fn [_] (throw (java.util.concurrent.RejectedExecutionException. "rejected")))]
      (is (thrown? java.util.concurrent.RejectedExecutionException
                   (runtime/run-root-box :child raw
                     (runtime/make-awake-fn :child evaluator) evaluator))))
    (is (nil? (coordinator/agent :child)))
    (is (empty? (:edges (coordinator/snapshot))))
    (is (= :first-result (:body (first (messages :parent)))))
    (is (true? (:spell/child-failure (:body (first (messages :later))))))))

(deftest provider-error-reaches-root-instead-of-stranding-completion-test
  (doseq [h [:parent :child]] (coordinator/register! h))
  (coordinator/request! :parent [:child] true :work)
  (coordinator/drain! :child)
  (let [failure (AssertionError. "provider failure")
        result (future
                 (try
                   (#'llm/-llm {:call-fn (fn [_] (throw failure))}
                             :child identity identity raw (atom {}))
                   nil
                   (catch Error e e)))]
    (is (identical? failure (deref result 5000 :timeout)))
    (is (nil? (coordinator/agent :child)))
    (is (empty? (:edges (coordinator/snapshot))))
    (is (true? (:spell/child-failure (:body (first (messages :parent))))))))
