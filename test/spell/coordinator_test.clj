(ns spell.coordinator-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [spell.coordinator :as c]
            [spell.runtime :as runtime]
            [spell.eval :as eval]
            [spell.stdlib :as stdlib]
            [spell.test-helpers :as th]))

(use-fixtures :each
  (fn [f] (binding [c/*coordinator* (c/new-coordinator)]
            (try (f) (finally (c/close!))))))

(defn- agents! [& handles] (doseq [h handles] (c/register! h)))

(deftest state-and-notification-commit-together
  (agents! :a :b)
  (with-redefs [c/dispatch! (fn [])]
    (let [id (c/request! :a [:b] true :question)
          s (c/snapshot)]
      (is (get-in s [:edges id]))
      (is (= :awake (get-in s [:agents :b :status])))
      (is (= id (get-in s [:agents :b :mailbox 0 :request-edge])))
      (is (seq (:notifications s)))
      (is (not (realized? (:signal (c/agent :b)))))
      ;; A receiver need not wait for dispatch when authoritative data exists.
      (is (= :ready (:status (c/wait! :b))))))
  (c/dispatch!)
  (is (realized? (:signal (c/agent :b))))
  (is (empty? (:notifications (c/snapshot)))))

(deftest exact-once-fill-including-nil-and-unbounded-values
  (agents! :a :b :c)
  (let [id (c/request! :a [:b :c] false nil)
        large (vec (range 150))]
    (is (:filled? (c/fill! id :b nil)))
    (is (not (:filled? (c/fill! id :b :duplicate))))
    (is (empty? (:mailbox (c/agent :a))))
    (is (:completed? (c/fill! id :c large)))
    (is (= [{:from :b :body nil} {:from :c :body large}]
           (get-in (c/agent :a) [:mailbox 0 :message :body])))
    (is (not (:filled? (c/fill! id :c :again))))
    (is (= 1 (count (:mailbox (c/agent :a)))))))

(deftest undrained-request-belongs-to-next-lifecycle
  (agents! :a :b :c)
  (let [first-id (c/request! :a [:b] true :first)
        completion (:completed (c/agent :b))]
    (c/drain! :b)
    (let [second-id (c/request! :c [:b] true :second)]
      (c/finish! :b completion :first-result)
      (is (nil? (get-in (c/snapshot) [:edges first-id])))
      (is (= :pending (get-in (c/snapshot) [:edges second-id :slots :b :status])))
      (is (= 2 (:generation (c/agent :b))))
      (c/drain! :b)
      (c/finish! :b (:completed (c/agent :b)) :second-result)
      (is (= :second-result (get-in (c/agent :c) [:mailbox 0 :message :body]))))))

(deftest durable-wait-survives-unrelated-message
  (agents! :a :b :c)
  (let [id (c/request! :a [:b] true :work)]
    (is (= :waiting (:status (c/wait! :a))))
    (c/send! :a {:message {:from :c :body :interrupt}})
    (c/drain! :a)
    (is (= :waiting (:status (c/wait! :a))))
    (c/fill! id :b :result)
    (is (= :ready (:status (c/wait! :a))))))

(deftest refuse-older-outgoing-than-pending-incoming
  (agents! :a :b :c)
  (c/request! :a [:b] false nil)
  (c/request! :c [:a] false nil)
  (c/drain! :a)
  (is (= :sleep-refused
         (try (c/wait! :a) (catch Exception e (:type (ex-data e))))))
  (is (= :awake (:status (c/agent :a)))))

(deftest reply-request-replaces-single-report-atomically
  (agents! :a :b)
  (let [old (c/request! :a [:b] true :q)
        msg (:message (first (c/drain! :b)))
        new (c/reply-request! :b msg :answer)]
    (is (nil? (get-in (c/snapshot) [:edges old])))
    (is (= new (get-in (c/agent :a) [:mailbox 0 :request-edge])))
    (is (= 1 (count (:mailbox (c/agent :a)))))
    (is (= :answer (get-in (c/agent :a) [:mailbox 0 :message :body])))))

(deftest every-committed-wakeup-remains-visible-across-drain
  (agents! :a)
  (dotimes [i 60]
    (let [gate (promise)
          sent (future @gate (c/send! :a {:message {:body i}}))
          drained (future @gate (c/drain! :a))]
      (deliver gate true)
      @sent
      (let [batch @drained remaining (:mailbox (c/agent :a))]
        (is (= [i] (mapv #(get-in % [:message :body]) (concat batch remaining)))))
      (when (seq (:mailbox (c/agent :a)))
        (is (realized? (:signal (c/agent :a)))))
      (c/drain! :a))))

(deftest independent-runs-reuse-handles
  (agents! :main :child)
  (let [old c/*coordinator*
        release (promise)
        stale (future @release (try (c/send! :main {:message {:body :old}})
                                    (catch Exception e (:type (ex-data e)))))]
    (c/close!)
    (binding [c/*coordinator* (c/new-coordinator)]
      (agents! :main :child)
      (deliver release true)
      (is (= :coordinator-closed @stale))
      (is (empty? (:mailbox (c/agent :main))))
      (is (empty? (:edges (c/snapshot))))
      (c/close!))
    (is (:closed? @old))))

(deftest lifecycle-completion-token-prevents-duplicate-finishing
  (agents! :a)
  (let [completion (:completed (c/agent :a))]
    (is (map? (c/finish! :a completion nil)))
    (is (nil? @completion))
    (is (nil? (c/finish! :a completion :duplicate)))
    (is (not (realized? (:completed (c/agent :a)))))))

(deftest root-releases-runner-before-orphan-entry
  (agents! :a)
  (let [inside-entered (promise) release (promise) orphan-entered (promise)
        raw "(quine completion (eval (do)))"
        eval-fn (fn [_] (deliver orphan-entered (get (c/agent :a) :runner-depth)) :done)
        root (future
               (runtime/run-root-box :a raw
                 (fn [_] (deliver inside-entered true) @release :initial) eval-fn))]
    @inside-entered
    (runtime/-send! :a (#'runtime/identity-msg-macro))
    (is (= ::timeout (deref orphan-entered 20 ::timeout)))
    (deliver release true)
    (is (= :initial @root))
    (is (= 1 (deref orphan-entered 2000 ::timeout)))))

(deftest runner-prevents-cross-thread-entry-but-allows-nesting
  (agents! :a)
  (let [entered (promise) release (promise)
        runner (future (runtime/box :a "raw" (fn [_] (deliver entered true) @release)))]
    @entered
    (is (thrown-with-msg? Exception #"already active" (runtime/box :a "raw" identity)))
    (deliver release true)
    @runner
    (is (= "nested" (runtime/box :a "outer" (fn [_] (runtime/box :a "nested" identity)))))))

(deftest schedule-invariant-generated-transitions
  ;; Fixed random seed explores ordering, wake, fill, cancel, and drain interleavings.
  (agents! :a :b :c :d)
  (let [rng (java.util.Random. 20260905) handles [:a :b :c :d]]
    (dotimes [_ 400]
      (let [h (nth handles (.nextInt rng 4))
            t (nth handles (.nextInt rng 4))
            state (c/snapshot)
            edges (vec (vals (:edges state)))]
        (case (.nextInt rng 6)
          0 (when (not= h t) (c/request! h [t] false nil))
          1 (c/drain! h)
          2 (try (c/wait! h) (catch clojure.lang.ExceptionInfo _))
          3 (when-let [edge (first edges)] (c/fill! (:id edge) (first (:targets edge)) nil))
          4 (when-let [edge (first edges)] (c/cancel! (:source edge) (:id edge)))
          5 (c/send! h {:message {:from t :body :wake}}))
        (let [s (c/snapshot)]
          ;; Cancellation can invalidate the cancelled source's former sleep;
          ;; it must therefore become runnable when its last obligation is removed.
          (doseq [[agent a] (:agents s) :when (= :asleep (:status a))]
            (is (c/sleep-allowed? s agent))))))))

(deftest startup-failure-is-a-terminal-result-and-nil-is-success
  (agents! :parent)
  (let [child (with-meta (fn [_ handle]
                          (if (= handle :bad) (throw (ex-info "startup failed" {:detail :known})) nil))
                        {:spell/compiled-agent true})]
    (binding [runtime/*current-handle* :parent
              runtime/*current-raw* "(quine completion (eval (do)))"
              runtime/*current-eval-fn* identity]
      (runtime/prepare-spawns! [{:agent child :prompt "bad" :handle-name :bad}
                               {:agent child :prompt "ok" :handle-name :ok}])
      (let [result (runtime/sleep!)]
        (is (string? result))
        (is (.contains ^String result ":spell/child-failure true"))
        (is (.contains ^String result "startup failed"))
        (is (.contains ^String result ":from :ok, :body nil"))))))

(deftest future-cycle-wakes-instead-of-deadlocking
  (agents! :a :b)
  (let [raw "(quine completion (eval (do)))"
        a-waiting (promise)
        a (future
            (runtime/box :a raw
              (fn [_]
                (binding [runtime/*current-eval-fn* identity]
                  (let [work (future (runtime/send-await :b :from-a))]
                    (deliver a-waiting true)
                    (stdlib/ask-await-builtin {:spell/future true :ref work}))))))]
    @a-waiting
    ;; Wait deterministically until A's request exists, then let B receive it.
    (loop [n 0]
      (when (and (empty? (:mailbox (c/agent :b))) (< n 200))
        (Thread/sleep 5) (recur (inc n))))
    (is (seq (c/drain! :b)))
    (c/request! :b [:a] true :need-help)
    (is (.contains ^String (deref a 3000 "TIMEOUT") "need-help"))
    (is (= :awake (:status (c/agent :a))))))

(deftest external-wait-cannot-outrank-real-incoming-obligation
  (agents! :a :b)
  (c/request! :b [:a] false nil)
  (c/drain! :a)
  (is (= :sleep-refused
         (try (c/begin-external-wait! :a) (catch Exception e (:type (ex-data e)))))))

(deftest late-external-result-cannot-wake-next-lifecycle
  (agents! :a)
  (let [completion (:completed (c/agent :a)) token (c/begin-external-wait! :a)]
    (c/finish! :a completion :done)
    (is (false? (c/complete-external-wait! token :late)))
    (is (empty? (:mailbox (c/agent :a))))))

(deftest direct-nested-agent-and-computation-agent-calls-rejected
  (let [agent (th/make-test-agent "42)")]
    (binding [runtime/*current-handle* :parent]
      (is (= :synchronous-agent-call
             (try (agent "(do " :nested) (catch Exception e (:type (ex-data e)))))))
    (binding [runtime/*computation-future?* true]
      (is (= :agent-in-computation-future
             (try (agent "(do " :nested) (catch Exception e (:type (ex-data e)))))))))

(deftest late-computation-cannot-create-request-in-next-lifecycle
  (agents! :a :b)
  (let [release (promise)
        completion (:completed (c/agent :a))
        token (binding [runtime/*current-handle* :a]
                ((get eval/core-builtins 'future*)
                 (fn [] @release
                   (try (runtime/request-token :b :late)
                        (catch Exception e (:type (ex-data e)))))))]
    (c/finish! :a completion :done)
    (c/send! :a {:message {:body :new-lifecycle}})
    (deliver release true)
    (is (= :stale-computation-lifecycle (deref (:ref token) 2000 :timeout)))
    (is (empty? (:edges (c/snapshot))))
    (is (empty? (:mailbox (c/agent :b))))))
