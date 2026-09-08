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

(deftest dormant-registration-cannot-overwrite-a-concurrent-wake
  (let [observed (promise)
        register runtime/register!]
    (with-redefs [runtime/register! (fn [handle parent status]
                                    (register handle parent status)
                                    (c/send! handle {:message {:from :sender :body :wake}}))]
      (runtime/start-box :dormant
                         (fn [_] (deliver observed (:status (c/agent :dormant))))
                         "(quine completion (eval (do)))"))
    (is (= :awake (deref observed 2000 :timeout)))))

(deftest escaped-blocking-functions-refuse-the-active-runner
  (agents! :a :b)
  (let [work-started (atom false)
        token {:spell/future true :ref (promise)}
        ns runtime/blocking-namespace]
    (runtime/box :a "(quine completion (eval (do)))"
      (fn [_]
        (doseq [[f args] [[(:await ns) [token]]
                          [(:await-all ns) [[token]]]
                          [(:pmap ns) [(fn [_] (reset! work-started true)) [1]]]
                          [(:send-await ns) [:b :request]]]]
          (is (= :agent-blocking-call
                 (try (apply f args) (catch Exception e (:type (ex-data e)))))))))
    (is (false? @work-started))
    (is (empty? (:edges (c/snapshot))))
    (is (empty? (:mailbox (c/agent :b))))))

(deftest pmap-workers-capture-computation-lifecycle
  (agents! :a :b)
  (let [entered (promise) release (promise)
        completion (:completed (c/agent :a))
        work (binding [runtime/*current-handle* :a]
               (future
                 (runtime/blocking-pmap
                   (fn [_]
                     (deliver entered true) @release
                     (try (runtime/request-token :b :late)
                          (catch Exception e (:type (ex-data e)))))
                   [:item])))]
    (is (= true (deref entered 2000 :timeout)))
    (c/finish! :a completion :done)
    (c/send! :a {:message {:body :next-lifecycle}})
    (deliver release true)
    (is (= [:stale-computation-lifecycle] (deref work 2000 :timeout)))
    (is (empty? (:edges (c/snapshot))))
    (is (empty? (:mailbox (c/agent :b))))))

(deftest request-adapter-distinguishes-success-from-cancellation
  (agents! :a :b)
  (binding [runtime/*current-handle* :a]
    (doseq [value [nil {:spell/cancelled true} {:spell/run-closed true}
                   {:status :cancelled :edge-id 7}]]
      (let [token (runtime/request-token :b :question)]
        (c/fill! (:edge-id token) :b value)
        (is (= value (runtime/blocking-await token)))))
    (let [token (runtime/request-token :b :question)]
      (c/cancel! :a (:edge-id token))
      (is (= :request-cancelled
             (try (runtime/blocking-await token) (catch Exception e (:type (ex-data e)))))))
    (let [token (runtime/request-token :b :question)]
      (c/close!)
      (is (= :coordinator-closed
             (try (runtime/blocking-await token) (catch Exception e (:type (ex-data e))))))))
  (is (= {:spell/cancelled true}
         (runtime/blocking-await {:spell/future true :ref (future {:spell/cancelled true})}))))

(deftest external-wait-settles-a-direct-throwable
  (agents! :a)
  ;; A direct IDeref matters: Clojure futures wrap Error in ExecutionException.
  (let [token {:spell/future true
               :ref (reify clojure.lang.IDeref
                      (deref [_] (throw (AssertionError. "compute failed"))))}
        result (runtime/box :a "(quine completion (eval (do)))"
                 (fn [_]
                   (binding [runtime/*current-eval-fn* identity]
                     (stdlib/ask-await-builtin token))))]
    (is (.contains ^String result "compute failed"))
    (is (.contains ^String result "java.lang.AssertionError"))
    (is (empty? (:external-waits (c/snapshot))))
    (is (= :awake (:status (c/agent :a))))))

(deftest retired-orphan-cannot-acquire-a-replacement-handle
  (let [parked (promise) release (promise) attempted (promise)
        evaluations (atom 0)
        await-message @#'runtime/await-message!
        acquire c/acquire!]
    (with-redefs-fn
      {#'runtime/await-message! (fn [h] (deliver parked true) @release (await-message h))
       #'c/acquire! (fn [h runner completion]
                     (try (acquire h runner completion)
                          (catch Exception e
                            (deliver attempted (:type (ex-data e)))
                            (throw e))))}
      (fn []
        (runtime/start-box :reused (fn [_] (swap! evaluations inc))
                           "(quine completion (eval (do)))")
        (is (= true (deref parked 2000 :timeout)))
        (c/retire! :reused (:completed (c/agent :reused)) :removed)
        (c/register! :reused)
        (c/send! :reused {:message {:from :fresh :body :new-work}})
        (let [replacement (c/agent :reused)]
          (deliver release true)
          (is (= :stale-agent-lifecycle (deref attempted 2000 :timeout)))
          (is (zero? @evaluations))
          (is (= replacement (c/agent :reused))))))))

(defn- bounded [p] (deref p 10000 ::timeout))

(deftest retained-future-production-rejoin
  (agents! :a)
  (binding [runtime/*current-handle* :a runtime/*current-raw* "(quine completion (eval (do)))"]
    (let [work (promise) entered (promise) delivered (promise)
          reads (atom 0) completions (atom [])
          original-value runtime/future-value
          original-complete c/complete-external-wait!
          fut {:spell/future true :ref work}]
      (with-redefs [runtime/block-for-message (fn [] :unrelated-wake)
                    runtime/future-value (fn [f] (swap! reads inc) (deliver entered true)
                                          (original-value f))
                    c/complete-external-wait! (fn [token value]
                                               (let [r (original-complete token value)]
                                                 (swap! completions conj r)
                                                 (deliver delivered true) r))]
        (stdlib/ask-await-builtin fut)
        (is (= true (bounded entered)))
        ;; A different wrapper must still identify the same underlying computation.
        (stdlib/ask-await-builtin (assoc fut :display :other))
        (is (= 1 (count (:external-waits (c/snapshot)))))
        (deliver work 943)
        (is (= true (bounded delivered)))
        ;; Completion is queued but not yet received: still the original subscription.
        (stdlib/ask-await-builtin fut)
        (is (= 1 @reads))
        (is (= [true] @completions))
        (is (= [{:from :future :body 943}] (mapv :message (c/drain! :a))))
        (is (empty? (:external-waits (c/snapshot))))))))

(deftest external-wait-identity-and-receipt-contract
  (agents! :a :b)
  (let [x (promise) y (promise)
        a (c/begin-external-wait! :a x)
        b (c/begin-external-wait! :b x)
        other (c/begin-external-wait! :a y)]
    (is (every? :created? [a b other]))
    (is (= {:token (:token a) :created? false} (c/begin-external-wait! :a x)))
    (is (true? (c/complete-external-wait! (:token a) :x)))
    (is (false? (:created? (c/begin-external-wait! :a x))))
    (is (false? (c/complete-external-wait! (:token a) :duplicate)))
    (is (true? (c/complete-external-wait! (:token b) :b)))
    (is (true? (c/complete-external-wait! (:token other) :y)))
    (is (= [:x :y] (mapv #(get-in % [:message :body]) (c/drain! :a))))
    (is (= [:b] (mapv #(get-in % [:message :body]) (c/drain! :b))))
    (let [later (c/begin-external-wait! :a x)]
      (is (:created? later))
      (is (not (identical? (:token a) (:token later))))
      (is (true? (c/complete-external-wait! (:token later) :x)))
      (is (= [:x] (mapv #(get-in % [:message :body]) (c/drain! :a)))))
    (is (empty? (:external-waits (c/snapshot))))))

(deftest external-wait-concurrent-rejoin-and-completion
  (agents! :a)
  (let [work (promise) first (c/begin-external-wait! :a work)
        ready-a (promise) ready-b (promise) go (promise)
        join (future (deliver ready-a true) @go (c/begin-external-wait! :a work))
        done (future (deliver ready-b true) @go (c/complete-external-wait! (:token first) :result))]
    (is (= true (bounded ready-a)))
    (is (= true (bounded ready-b)))
    (deliver go true)
    (is (= {:token (:token first) :created? false} (bounded join)))
    (is (= true (bounded done)))
    (is (= [{:from :future :body :result}] (mapv :message (c/drain! :a))))
    (is (empty? (:external-waits (c/snapshot))))))

(deftest external-wait-lifecycle-cleans-pending-and-queued
  (agents! :a)
  (let [work (promise) completion (:completed (c/agent :a))
        pending (c/begin-external-wait! :a work)
        queued (c/begin-external-wait! :a (promise))]
    (c/complete-external-wait! (:token queued) :old)
    (c/send! :a {:message {:from :peer :body :keep}})
    (c/finish! :a completion :finished)
    (is (empty? (:external-waits (c/snapshot))))
    (is (false? (c/complete-external-wait! (:token pending) :late)))
    (is (= [{:from :peer :body :keep}] (mapv :message (c/drain! :a))))
    (let [next (c/begin-external-wait! :a work)]
      (is (:created? next))
      (c/retire! :a (:completed (c/agent :a)) :retired)
      (is (false? (c/complete-external-wait! (:token next) :late)))
      (is (empty? (:external-waits (c/snapshot)))))))

(deftest retained-future-production-failure-and-later-await
  (agents! :a)
  (binding [runtime/*current-handle* :a runtime/*current-raw* "(quine completion (eval (do)))"]
    (let [computations (atom 0) release (promise)
          work (future (swap! computations inc) @release (throw (ex-info "broken" {})))
          fut {:spell/future true :ref work}
          completions (atom 0) signal (atom (promise))
          original c/complete-external-wait!]
      (with-redefs [runtime/block-for-message (fn [] :wake)
                    c/complete-external-wait!
                    (fn [token result]
                      (let [r (original token result)]
                        (swap! completions inc) (deliver @signal true) r))]
        (stdlib/ask-await-builtin fut)
        (stdlib/ask-await-builtin fut)
        (deliver release true)
        (is (= true (bounded @signal)))
        (let [messages (mapv :message (c/drain! :a))]
          (is (= 1 (count messages)))
          (is (string? (get-in messages [0 :body :future-await/error]))))
        ;; Explicit await after receipt requests another delivery, not recomputation.
        (reset! signal (promise))
        (stdlib/ask-await-builtin fut)
        (is (= true (bounded @signal)))
        (is (= 1 (count (c/drain! :a))))
        (is (= 2 @completions))
        (is (= 1 @computations))
        (is (empty? (:external-waits (c/snapshot))))))))

(deftest production-watcher-submissions-and-lifecycle-ownership
  (agents! :a :b)
  (let [submitted (atom []) reads (atom 0) accepted (atom [])
        original-value runtime/future-value
        original-complete c/complete-external-wait!
        x (promise) y (promise)
        fx {:spell/future true :ref x}
        fy {:spell/future true :ref y}
        join (fn [caller f]
               (binding [runtime/*current-handle* caller
                         runtime/*current-raw* "(quine completion (eval (do)))"]
                 (stdlib/ask-await-builtin f)))
        run-task (fn [i] ((nth @submitted i)))]
    ;; Submission is synchronous: an extra watcher cannot hide on the host
    ;; executor until after the count assertions or with-redefs restoration.
    (with-redefs [clojure.core/future-call
                  (fn [f] (swap! submitted conj f) nil)
                  runtime/block-for-message (fn [] :unrelated-wake)
                  runtime/future-value
                  (fn [f] (swap! reads inc) (original-value f))
                  c/complete-external-wait!
                  (fn [token value]
                    (let [r (original-complete token value)]
                      (swap! accepted conj r) r))]
      (join :a fx)
      (join :a (assoc fx :wrapper :different))
      (is (= 1 (count @submitted)) "Pending rejoin submits no watcher")
      (join :b fx)
      (join :a fy)
      (is (= 3 (count @submitted)) "Distinct caller/ref each owns a watcher")
      (deliver x :x)
      (deliver y :y)
      (run-task 0)
      (join :a fx)
      (is (= 3 (count @submitted)) "Queued completion cannot reopen subscription")
      (c/send! :a {:message {:from :peer :body :unrelated}})
      (is (= [:x :unrelated] (mapv #(get-in % [:message :body]) (c/drain! :a))))
      (is (= 2 (count (:external-waits (c/snapshot))))
          "Receipt leaves other pending refs/callers intact")
      (join :a fx)
      (is (= 4 (count @submitted)) "Explicit post-receipt await is new delivery")
      ;; Old pending watcher 2 is still live when :a finishes. The :b
      ;; watcher and unrelated mailbox traffic must survive that settlement.
      (c/send! :a {:message {:from :peer :body :keep}})
      (c/finish! :a (:completed (c/agent :a)) :done)
      (join :a fy)
      (is (= 5 (count @submitted)) "New lifecycle may await the same ref")
      (run-task 2)
      (run-task 3)
      (run-task 1)
      (run-task 4)
      (is (= [true false false true true] @accepted))
      (is (= [:keep :y] (mapv #(get-in % [:message :body]) (c/drain! :a))))
      (is (= [:x] (mapv #(get-in % [:message :body]) (c/drain! :b))))
      (join :a fx)
      (c/retire! :a (:completed (c/agent :a)) :retired)
      (c/register! :a)
      (join :a fx)
      (is (= 7 (count @submitted)))
      (run-task 5)
      (run-task 6)
      (is (= [true false false true true false true] @accepted))
      (is (= [:x] (mapv #(get-in % [:message :body]) (c/drain! :a))))
      (is (= 7 @reads) "Every submitted watcher was executed exactly once")
      (is (empty? (:external-waits (c/snapshot)))))))

(deftest production-controlled-failure-rejoin
  (agents! :a)
  (binding [runtime/*current-handle* :a
            runtime/*current-raw* "(quine completion (eval (do)))"]
    (let [tasks (atom []) computations (atom 0)
          ;; The computation is performed once, independently of watcher tasks.
          work (future (swap! computations inc) (throw (ex-info "broken" {})))
          f {:spell/future true :ref work}]
      (try @work (catch Throwable _))
      (with-redefs [clojure.core/future-call (fn [task] (swap! tasks conj task) nil)
                    runtime/block-for-message (fn [] :wake)]
        (stdlib/ask-await-builtin f)
        (stdlib/ask-await-builtin f)
        (is (= 1 (count @tasks)))
        ((nth @tasks 0))
        (stdlib/ask-await-builtin f)
        (is (= 1 (count @tasks)))
        (let [batch (c/drain! :a)]
          (is (= 1 (count batch)))
          (is (string? (get-in batch [0 :message :body :future-await/error]))))
        (stdlib/ask-await-builtin f)
        (is (= 2 (count @tasks)))
        ((nth @tasks 1))
        (let [batch (c/drain! :a)]
          (is (= 1 (count batch)))
          (is (string? (get-in batch [0 :message :body :future-await/error]))))
        (is (= 1 @computations))
        (is (empty? (:external-waits (c/snapshot))))))))
