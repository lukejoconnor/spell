(ns spell.patterns-test
  (:require [spell.test-helpers :as th]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [spell.eval :as eval]
            [spell.io :as sio]
            [spell.runtime :as runtime]
            [spell.coordinator :as coordinator]
            [spell.stdlib :as stdlib]))

(use-fixtures :each th/with-test-run)
(def sh-test (:sh-test sio/io-namespace))
(def stub-ask-await (fn [fut] (deref (:ref fut) 5000 :timeout)))

(defn- invoke-module [module opts]
  (binding [runtime/*current-handle* (or runtime/*current-handle* :pattern-test)]
    (locking coordinator/*coordinator*
      (when-not (coordinator/agent runtime/*current-handle*)
        (coordinator/register! runtime/*current-handle*)))
    (let [env (assoc eval/*spell-env* 'patterns stdlib/patterns)
          result (eval/spell-eval
                   (list 'do (list 'patterns/install module)
                         (list 'patterns/call module :run (list 'quote opts)))
                   env)]
      (if (eval/ok? result) (:ok result)
        (throw (ex-info (:err result) {:result result}))))))

(defn- run-relay [opts env]
  (binding [eval/*builtins* (assoc eval/*builtins* '!ask-await stub-ask-await)
            eval/*spell-env* (merge {'strings stdlib/strings
                                     'patterns stdlib/patterns}
                                    env)]
    (invoke-module :relay opts)))

;; Tiny test-only source, not a retry policy or a retired workflow copy.
(defn- run-lifecycle [env]
  (binding [eval/*spell-env* env]
    (let [result (eval/spell-eval
                  '(do
                     (def worker (agents/spawn "worker" :lifecycle-worker))
                     (def parent (agents/current-handle))
                     (future
                       (agents/send parent
                         (vec (map (fn [attempt]
                                      (blocking/send-await worker {:attempt attempt}))
                                    [0 1 2]))))
                     {:started true}) env)]
      (if (eval/ok? result) (:ok result)
        (throw (ex-info (:err result) {:result result}))))))

(deftest repeated-blocking-requests-own-runtime-slots-test
  (testing "three explicit blocking requests each own a tracked runtime slot"
    (let [parent-handle :lifecycle-parent
          base-raw "(quine completion (eval (do )))"
          spawn-calls (atom [])
          send-await-calls (atom [])
          send-calls (atom [])
          worker-runs (atom 0)
          owned-requests (atom [])
          done (promise)
          spawn-fn
          (fn [prompt handle-name]
            (swap! spawn-calls conj {:prompt prompt :handle handle-name})
            (runtime/start-box handle-name
                               (fn [_]
                                 (swap! owned-requests conj
                                        (coordinator/incoming (coordinator/snapshot) handle-name))
                                 (let [n (swap! worker-runs inc)]
                                   (if (>= n 3)
                                     {:ok "fixed!"}
                                     {:error (str "still-broken-" (dec n))})))
                               base-raw
                               parent-handle)
            handle-name)
          send-await-fn
          (fn [handle msg]
            (swap! send-await-calls conj {:handle handle :msg msg})
            (runtime/send-await handle msg))
          send-fn
          (fn [target msg]
            (swap! send-calls conj {:target target :msg msg})
            (when (= target parent-handle)
              (deliver done msg)))]
      (runtime/register! parent-handle)
      (let [started (binding [runtime/*current-handle* parent-handle]
                      (run-lifecycle {'agents {:current-handle (fn [] parent-handle)
                                           :spawn spawn-fn
                                           :send send-fn}
                                  'blocking {:send-await send-await-fn}}))
            final-result (deref done 5000 :timeout)
            worker-calls @send-await-calls]
        (is (= true (:started started)))
        (is (= [{:error "still-broken-0"} {:error "still-broken-1"} {:ok "fixed!"}] final-result))
        (is (= 1 (count @spawn-calls)))
        (is (= 3 @worker-runs))
        (is (= [1 1 1] (mapv count @owned-requests)))
        (is (every? #(= parent-handle (:source (first %))) @owned-requests))
        (is (every? (fn [edges]
                      (let [edge (first edges)
                            worker (first (:targets edge))]
                        (integer? (get-in edge [:slots worker :generation]))))
                    @owned-requests))
        (is (= 3 (count worker-calls)))
        (is (= [0 1 2] (mapv #(get-in % [:msg :attempt]) worker-calls)))
        (is (= parent-handle (:target (last @send-calls))))))))

(deftest sh-test-pattern-test
  (testing "io/sh-test bakes a shell command into a reusable Spell thunk"
    (let [thunk (eval/invoke-fn sh-test ["echo hello"])]
      (is (= true (:spell/fn thunk)))
      (with-redefs [sio/sh (fn [cmd & _]
                             {:exit 0 :out (str "ran " cmd) :err ""})]
        (let [result (binding [eval/*spell-env* {'io (assoc sio/io-namespace :sh sio/sh)}]
                       (eval/invoke-fn thunk []))]
          (is (= true (:pass result)))
          (is (str/includes? (:output result) "COMMAND: echo hello"))
          (is (str/includes? (:output result) "EXIT: 0")))))))

(deftest relay-solved-first-round-test
  (testing "relay returns a confirmed first-round solution and records worker handles"
    (let [register-calls (atom [])
          send-await-calls (atom [])
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (swap! send-await-calls conj {:handle handle :msg msg})
                          (if (str/starts-with? (name handle) "relay-worker-")
                            {:status :solved
                             :report "Computed the product directly."
                             :answer 42}
                            {:confirmed true
                             :feedback "Checked independently and confirmed."}))
          result (run-relay {:problem "What is 6 * 7?"
                             :max-rounds 3}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          report (first (:rounds result))]
      (is (= true (:solved result)))
      (is (= 42 (:answer result)))
      (is (= 1 (count (:rounds result))))
      (is (= 0 (:round report)))
      (is (= :solved (:status report)))
      (is (= "Computed the product directly." (:report report)))
      (is (= (-> @register-calls first :handle) (:worker-handle report)))
      (is (= 2 (count @register-calls)))
      (is (= 2 (count @send-await-calls)))
      (is (= :solve (get-in (first @send-await-calls) [:msg :kind])))
      (is (= 0 (get-in (first @send-await-calls) [:msg :round])))
      (is (= [] (get-in (first @send-await-calls) [:msg :previous-reports])))
      (is (= :verify (get-in (second @send-await-calls) [:msg :kind])))
      (is (= report (get-in (second @send-await-calls) [:msg :candidate-report])))
      (is (str/includes? (-> @register-calls first :completion) "may message previous workers"))
      (is (str/includes? (-> @register-calls first :completion) "{:kind :clarify :question str}"))
      (is (str/includes? (-> @register-calls first :completion) "{:status :clarified :report"))
      (is (str/includes? (-> @register-calls first :completion) "Do not continue your completion after that reply"))
      (is (str/includes? (-> @register-calls second :completion) "may message previous workers"))
      (is (str/includes? (-> @register-calls second :completion) "{:kind :clarify :question"))
      (is (str/includes? (-> @register-calls second :completion) "{:status :clarified :report")))))

(deftest relay-progress-then-solved-test
  (testing "relay accumulates prior reports and uses a fresh worker each round"
    (let [register-calls (atom [])
          worker-msgs (atom [])
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (if (str/starts-with? (name handle) "relay-worker-")
                            (do
                              (swap! worker-msgs conj {:handle handle :msg msg})
                              (case (:round msg)
                                0 {:status :progress
                                   :report "Tried substitution; not enough yet."}
                                {:status :solved
                                 :report "Second approach succeeds."
                                 :answer 9}))
                            {:confirmed true
                             :feedback "Verified the second approach."}))
          result (run-relay {:problem "Solve the toy problem"
                             :max-rounds 3}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          reports (:rounds result)
          worker-handles (mapv :handle (filter #(str/starts-with? (name (:handle %)) "relay-worker-")
                                               @register-calls))]
      (is (= true (:solved result)))
      (is (= 9 (:answer result)))
      (is (= 2 (count reports)))
      (is (= [:progress :solved] (mapv :status reports)))
      (is (= 2 (count worker-handles)))
      (is (= 2 (count (set worker-handles))))
      (is (= worker-handles (mapv :worker-handle reports)))
      (is (= [(first reports)]
             (get-in (second @worker-msgs) [:msg :previous-reports]))))))

(deftest relay-verification-rejects-then-continues-test
  (testing "relay re-registers the verifier per attempt and carries rejection reports forward"
    (let [register-calls (atom [])
          worker-msgs (atom [])
          verifier-msgs (atom [])
          verifier-call-count (atom 0)
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [handle msg]
                          (if (str/starts-with? (name handle) "relay-worker-")
                            (do
                              (swap! worker-msgs conj {:handle handle :msg msg})
                              (case (:round msg)
                                0 {:status :solved
                                   :report "I think the answer is 10."
                                   :answer 10}
                                {:status :solved
                                 :report "Recomputed carefully; answer is 12."
                                 :answer 12}))
                            (do
                              (swap! verifier-msgs conj {:handle handle :msg msg})
                              (case (swap! verifier-call-count inc)
                                1 "not a map"
                                2 {:confirmed false
                                   :feedback "Answer 10 is inconsistent with the problem constraints."}
                                {:confirmed true
                                 :feedback "Confirmed 12 independently."}))))
          result (run-relay {:problem "Find the correct answer"
                             :max-rounds 4}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          reports (:rounds result)
          verifier-handles (mapv :handle (filter #(str/starts-with? (name (:handle %)) "relay-verifier-")
                                                 @register-calls))
          rejected-report (second reports)]
      (is (= true (:solved result)))
      (is (= 12 (:answer result)))
      (is (= [:solved :rejected :solved] (mapv :status reports)))
      (is (= 3 (count verifier-handles)))
      (is (= 3 (count (set verifier-handles))))
      (is (= "Answer 10 is inconsistent with the problem constraints."
             (:report rejected-report)))
      (is (= (:worker-handle (first reports)) (:worker-handle rejected-report)))
      (is (= (second verifier-handles) (:verifier-handle rejected-report)))
      (is (str/includes? (get-in (second @verifier-msgs) [:msg :feedback])
                         "Verifier must return"))
      (is (= [(first reports) rejected-report]
             (get-in (second @worker-msgs) [:msg :previous-reports]))))))

(deftest relay-invalid-report-and-max-rounds-test
  (testing "relay normalizes invalid worker output into stuck reports and stops at max rounds"
    (let [register-calls (atom [])
          register-fn (fn [handle completion]
                        (swap! register-calls conj {:handle handle :completion completion})
                        handle)
          send-await-fn (fn [_handle msg]
                          (case (:round msg)
                            0 "garbage response"
                            "{:status :solved :report \"missing answer\"}"))
          result (run-relay {:problem "Keep trying"
                             :max-rounds 2}
                            {'agents {:register register-fn}
                             'blocking {:send-await send-await-fn}})
          reports (:rounds result)]
      (is (= false (:solved result)))
      (is (= 2 (count reports)))
      (is (= [:stuck :stuck] (mapv :status reports)))
      (is (= 2 (count @register-calls)))
      (is (= 2 (count (set (map :worker-handle reports)))))
      (is (str/includes? (:report (first reports)) "garbage response"))
      (is (str/includes? (:report (second reports)) ":answer is required")))))
