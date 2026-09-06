(ns spell.run-isolation-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.api :as api]
            [spell.coordinator :as coordinator]
            [spell.globals :as globals]
            [spell.provider :as provider]
            [spell.runtime :as runtime])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest overlapping-api-runs-own-their-communication-state
  (testing "finishing one API invocation cannot close or modify another active invocation"
    (let [entered (CountDownLatch. 2)
          releases {:a (promise) :b (promise)}
          captured (atom {})
          start (fn [id]
                  (future
                    (api/run
                      {:prompt "Return your run marker."
                       :agent-profile "config/agent-profiles/base-msg.agent.edn"
                       :model-profile
                       (provider/test-provider
                         {:response-fn
                          (fn [_]
                            ;; Both API invocations deliberately use identical names.
                            (runtime/register! :peer)
                            (globals/set-val :run-marker id)
                            (runtime/send :peer id)
                            (swap! captured assoc id
                                   {:coordinator coordinator/*coordinator*
                                    :store globals/*store*
                                    :late-send (bound-fn [] (runtime/send :peer :late))})
                            (.countDown entered)
                            (when (= ::timeout (deref (get releases id) 5000 ::timeout))
                              (throw (ex-info "Run test release timed out" {:run id})))
                            (str (pr-str (globals/get-val :run-marker)) "))"))})})))]
      (let [a (start :a) b (start :b)]
        (try
          (is (.await entered 5 TimeUnit/SECONDS) "both runs reach the provider concurrently")
          (let [a-state (get @captured :a) b-state (get @captured :b)]
            (is (not (identical? (:coordinator a-state) (:coordinator b-state))))
            (is (not (identical? (:store a-state) (:store b-state))))
            (doseq [[id {:keys [coordinator store]}] @captured]
              (is (= id (:run-marker @store)))
              (is (= id (get-in @coordinator [:agents :peer :mailbox 0 :message :body]))))
            (deliver (:a releases) true)
            (is (= :a (:result (deref a 5000 ::timeout))))
            (is (:closed? @(:coordinator a-state)))
            (is (false? (:closed? @(:coordinator b-state))))
            (is (= :coordinator-closed
                   (try ((:late-send a-state))
                        (catch Exception e (:type (ex-data e))))))
            (is (= [:b] (mapv #(get-in % [:message :body])
                              (get-in @(:coordinator b-state) [:agents :peer :mailbox]))))
            (deliver (:b releases) true)
            (is (= :b (:result (deref b 5000 ::timeout))))
            (is (:closed? @(:coordinator b-state))))
          (finally
            (doseq [p (vals releases)] (deliver p true))
            (deref a 5000 nil)
            (deref b 5000 nil)))))))
