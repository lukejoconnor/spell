(ns spell.terminal-interrupt-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [spell.test-helpers :as th]
            [spell.agent :as agent]
            [spell.api :as api]
            [spell.coordinator :as coordinator]
            [spell.eval :as eval]
            [spell.provider :as provider]
            [spell.user :as user])
  (:import [java.io Closeable]
           [java.lang.reflect InvocationHandler Proxy]
           [org.jline.reader LineReader UserInterruptException]))

(use-fixtures :each th/with-test-run
  (fn [f]
    (user/call-with-session #(try (f) (finally (user/reset-state!))))))

(deftest interactive-interruption-bypasses-evaluator-recovery
  (let [interrupt (ex-info "Interactive run interrupted" {:type :interactive-interrupt})
        env {'block (fn [] (throw (InterruptedException. "blocked")))}]
    (testing "ordinary/noninteractive interruption still follows existing eval semantics"
      (is (:err (eval/spell-eval '(block) env)))
      (binding [eval/*interactive-interrupt* (atom nil)]
        (is (:err (eval/spell-eval '(block) env)))))
    (testing "only a marked interactive run bypasses recoverable error conversion"
      (binding [eval/*interactive-interrupt* (atom interrupt)]
        (is (identical? interrupt
                       (try (eval/spell-eval '(block) env)
                            (catch Exception e e))))))))

(deftest jline-interrupt-stops-reader-and-completes-cleanup-once
  (let [reads (atom 0)
        interrupts (atom 0)
        stops (atom 0)
        finished (promise)
        state (atom :pending)
        reader (Proxy/newProxyInstance
                 (.getClassLoader LineReader) (into-array Class [LineReader])
                 (reify InvocationHandler
                   (invoke [_ _ method _]
                     (if (= "readLine" (.getName method))
                       (do (swap! reads inc) (throw (UserInterruptException. "")))
                       (throw (UnsupportedOperationException. (.getName method)))))))
        task (#'user/start-jline-reader!
               reader (atom false)
               {:state state :finished finished
                :on-interrupt #(swap! interrupts inc)
                :on-stop #(swap! stops inc)})]
    (try
      (is (= true (deref finished 2000 :timeout)))
      (is (= :stopped @state))
      (is (= 1 @reads))
      (is (= 1 @interrupts))
      (is (= 1 @stops))
      (finally (future-cancel task)))))

(deftest active-interactive-run-cancels-request-and-disarms-before-cleanup
  (let [callback (promise)
        provider-started (promise)
        provider-unwound (promise)
        provider-calls (atom 0)
        session-closes (atom 0)
        run-closes (atom 0)
        cleanup-interrupted? (atom nil)
        close-run coordinator/close!
        prov (provider/test-provider
               {:response-fn
                (fn [_]
                  (swap! provider-calls inc)
                  (deliver provider-started true)
                  (try (Thread/sleep 600000) "'42"
                       (finally (deliver provider-unwound true))))})]
    (with-redefs [user/register-interactive-user-agent!
                  (fn [on-interrupt]
                    (deliver callback on-interrupt)
                    (reify Closeable
                      (close [_]
                        (swap! session-closes inc)
                        ;; Simulate a delayed reader callback racing with cleanup.
                        (on-interrupt)
                        (reset! cleanup-interrupted? (.isInterrupted (Thread/currentThread))))))
                  coordinator/close! (fn [] (let [r (close-run)] (swap! run-closes inc) r))]
      (let [result (future (api/run-internal
                            {:prompt "Local deterministic cancellation test"
                             :model-profile prov
                             :agent-profile "config/agent-profiles/base-msg.agent.edn"
                             :interactive-user? true}))]
        (try
          (is (= true (deref provider-started 10000 :timeout)))
          (let [on-interrupt (deref callback 1000 nil)
                started (System/nanoTime)]
            (is (fn? on-interrupt))
            (when on-interrupt (on-interrupt))
            (let [value (deref result 5000 :timeout)]
              (is (= :interactive-interrupt (get-in value [:error-data :type])))
              (is (< (/ (- (System/nanoTime) started) 1e9) 5.0))))
          (is (= true (deref provider-unwound 0 false)) "actual provider finally ran")
          (is (= 1 @provider-calls) "interrupt must not request model recovery")
          (is (= 1 @session-closes))
          (is (= 1 @run-closes))
          (is (false? @cleanup-interrupted?) "late callback is disarmed before cleanup")
          (finally (future-cancel result)))))))

(deftest cleanup-failure-still-closes-compiled-agent-and-coordinator
  (let [compiled-closes (atom 0)
        run-closes (atom 0)
        session-closes (atom 0)
        close-run coordinator/close!]
    (with-redefs [agent/compile-agent-spec (fn [_] (fn [& _] 42))
                  agent/close-compiled-agent! (fn [_] (swap! compiled-closes inc))
                  user/register-interactive-user-agent!
                  (fn [_]
                    (reify Closeable
                      (close [_]
                        (swap! session-closes inc)
                        (throw (ex-info "Restoration failed" {:type :user-terminal-restore-failed})))))
                  coordinator/close! (fn [] (let [r (close-run)] (swap! run-closes inc) r))]
      (is (= :user-terminal-restore-failed
             (try
               (api/run-internal {:init "42"
                                  :model-profile (provider/test-provider {:response "unused"})
                                  :agent-profile "config/agent-profiles/base-msg.agent.edn"
                                  :interactive-user? true})
               (catch Exception e (:type (ex-data e))))))
      (is (= 1 @session-closes))
      (is (= 1 @compiled-closes))
      (is (= 1 @run-closes)))))


(deftest interactive-interrupt-preserves-primary-and-reports-cleanup-failures
  (doseq [failing-stages [#{:user-session} #{:user-state} #{:compiled-agent}
                          #{:user-session :user-state :compiled-agent}]]
    (let [callback (atom nil)
          stages (atom [])
          resets (atom 0)
          reset-user user/reset-state!
          close-run coordinator/close!
          finish! (fn [stage]
                    (swap! stages conj stage)
                    (when (contains? failing-stages stage)
                      (throw (ex-info (str "Cleanup failed: " (name stage))
                                      {:type :test-cleanup-failure :stage stage}))))]
      (with-redefs [agent/compile-agent-spec
                    (fn [_] (fn [& _] (@callback) (throw (InterruptedException. "test interrupt"))))
                    agent/close-compiled-agent! (fn [_] (finish! :compiled-agent))
                    user/register-interactive-user-agent!
                    (fn [on-interrupt]
                      (reset! callback on-interrupt)
                      (reify Closeable (close [_] (finish! :user-session))))
                    user/reset-state!
                    (fn []
                      (reset-user)
                      (when (> (swap! resets inc) 1) (finish! :user-state)))
                    coordinator/close!
                    (fn [] (close-run) (swap! stages conj :coordinator))]
        (let [result (try
                       (api/run-internal
                         {:init "42"
                          :model-profile (provider/test-provider {:response "unused"})
                          :agent-profile "config/agent-profiles/base-msg.agent.edn"
                          :interactive-user? true})
                       (catch Exception e {:escaped-error (ex-data e)}))
              errors (get-in result [:error-data :cleanup-errors])]
          (is (= :interactive-interrupt (get-in result [:error-data :type])) (pr-str result))
          (is (= failing-stages (set (map :stage errors))))
          (is (every? #(= :test-cleanup-failure (get-in % [:error-data :type])) errors))
          (is (every? #(string? (:error %)) errors))
          (is (= [:user-session :user-state :compiled-agent :coordinator] @stages))
          (is (false? (.isInterrupted (Thread/currentThread)))))))))

(defn- tracked-terminal [closes]
  (proxy [org.jline.terminal.impl.DumbTerminal]
    [(java.io.ByteArrayInputStream. (byte-array 0)) (java.io.ByteArrayOutputStream.)]
    (doClose []
      (swap! closes inc)
      (proxy-super doClose))))

(deftest native-snapshot-failure-closes-opened-terminal
  (let [closes (atom 0)
        terminal (tracked-terminal closes)
        failure (ex-info "snapshot failed" {:type :user-terminal-snapshot-failed})]
    (with-redefs-fn {#'user/open-terminal! (fn [] terminal)
                    #'user/native-terminal-restorer (fn [_] (throw failure))}
      #(is (identical? failure
                       (try (user/register-interactive-user-agent! (fn []))
                            (catch Exception e e)))))
    (is (= 1 @closes))
    (is (nil? @@#'user/interactive-session))))

(deftest registration-failure-restores-native-settings-and-preserves-error
  (let [closes (atom 0)
        restores (atom 0)
        terminal (tracked-terminal closes)
        failure (ex-info "registration failed" {})
        cleanup-failure (ex-info "restore failed" {:type :user-terminal-restore-failed})]
    (with-redefs-fn {#'user/open-terminal! (fn [] terminal)
                    #'user/native-terminal-restorer
                    (fn [_] (fn [] (swap! restores inc) (throw cleanup-failure)))
                    #'user/register-user-agent-core! (fn [_] (throw failure))}
      #(is (identical? failure
                       (try (user/register-interactive-user-agent! (fn []))
                            (catch Exception e e)))))
    (is (= 1 @closes))
    (is (= 1 @restores))
    (is (= [cleanup-failure] (vec (.getSuppressed failure))))
    (is (nil? @@#'user/interactive-session))))

(deftest native-restoration-failure-still-removes-reader-task
  (let [closes (atom 0)
        restores (atom 0)
        terminal (tracked-terminal closes)
        failure (ex-info "restore failed" {:type :user-terminal-restore-failed})
        task (future true)]
    (try
      (with-redefs-fn {#'user/open-terminal! (fn [] terminal)
                      #'user/native-terminal-restorer
                      (fn [_] (fn [] (swap! restores inc) (throw failure)))
                      #'user/start-jline-reader! (fn [& _] task)
                      #'user/register-user-agent-core!
                      (fn [start-reader!]
                        (let [reader-task (start-reader!)]
                          (swap! @#'user/reader-tasks conj reader-task)
                          reader-task))}
        (fn []
          (let [session (user/register-interactive-user-agent! (fn []))]
            (is (contains? @@#'user/reader-tasks task))
            (is (identical? failure (try (.close ^Closeable session)
                                        (catch Exception e e))))
            (is (empty? @@#'user/reader-tasks))
            (is (nil? @@#'user/interactive-session))
            (.close ^Closeable session)
            (is (= 1 @closes))
            (is (= 1 @restores)))))
      (finally (future-cancel task)))))


(deftest pure-evaluation-observes-interactive-exit-without-changing-noninteractive-flags
  (let [interrupt (ex-info "Interactive run interrupted" {:type :interactive-interrupt})]
    (doseq [form ['(loop [] (recur)) '((fn [] (recur)))]]
      (binding [eval/*interactive-interrupt* (atom interrupt)]
        (is (identical? interrupt (try (eval/spell-eval form {})
                                      (catch Exception e e))))))
    (try
      (.interrupt (Thread/currentThread))
      (binding [eval/*interactive-interrupt* nil]
        (let [result (eval/spell-eval 42 {})
              interrupted? (.isInterrupted (Thread/currentThread))]
          ;; Capture before test reporting can itself perform interruptible I/O.
          (is (= 42 (:ok result)))
          (is interrupted?)))
      (finally (Thread/interrupted)))))

(deftest terminal-attribute-and-reader-setup-failures-close-and-restore
  (doseq [stage [:attributes :reader]]
    (let [closes (atom 0)
          restores (atom 0)
          failure (ex-info "Controlled terminal setup failure" {:stage stage})
          terminal (proxy [org.jline.terminal.impl.DumbTerminal]
                     [(java.io.ByteArrayInputStream. (byte-array 0)) (java.io.ByteArrayOutputStream.)]
                     (getAttributes []
                       (if (= stage :attributes) (throw failure) (proxy-super getAttributes)))
                     (doClose [] (swap! closes inc) (proxy-super doClose)))]
      (with-redefs-fn {#'user/open-terminal! (fn [] terminal)
                      #'user/native-terminal-restorer (fn [_] #(swap! restores inc))
                      #'user/open-line-reader! (fn [_] (throw failure))}
        #(is (identical? failure
                         (try (user/register-interactive-user-agent! (fn []))
                              (catch Exception e e)))))
      (is (= 1 @closes))
      (is (= 1 @restores))
      (is (nil? @@#'user/interactive-session)))))
