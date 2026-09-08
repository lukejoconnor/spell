(ns spell.prefill-policy-test
  (:require [clojure.test :refer [deftest is]]
            [spell.llm :as llm]
            [spell.agent]
            [clojure.data.json]
            [clojure.string]
            [spell.provider :as provider]))

(defn- compile-with-model [default-model effective-model]
  (llm/compile-agent
   {:provider (provider/map->AnthropicPfProvider {:model default-model})
    :model effective-model :prefill? true}))

(deftest effective-model-rejects-unsupported-prefill
  (is (thrown? clojure.lang.ExceptionInfo
               (compile-with-model "claude-sonnet-4-5-20250929"
                                   "claude-fable-5-1"))))

(deftest effective-model-accepts-supported-prefill
  (is (fn? (compile-with-model "claude-fable-5-1"
                               "claude-sonnet-4-5-20250929"))))

(deftest explicit-false-is-preserved-for-both-models
  (doseq [model ["claude-sonnet-4-5-20250929" "claude-fable-5-1"]]
    (is (fn? (llm/compile-agent
              {:provider (provider/map->AnthropicPfProvider {:model model})
               :prefill? false})))))

(deftest thinking-and-explicit-prefill-conflict
  (is (thrown? clojure.lang.ExceptionInfo
               (llm/compile-agent
                {:provider (provider/map->AnthropicPfProvider
                            {:model "claude-sonnet-4-5-20250929"})
                 :thinking 1024 :prefill? true}))))

(deftest unsupported-model-defaults-to-no-prefill
  (is (fn? (llm/compile-agent
            {:provider (provider/map->AnthropicPfProvider
                        {:model "claude-fable-5-1"})}))))

(defn- capture-tc-body [default-model effective-model]
  (let [body (atom nil)]
    (with-redefs [clojure.data.json/write-str
                  (fn [request & _]
                    (reset! body request)
                    (throw (ex-info "Offline request captured" {::captured true})))]
      (try
        (provider/call-llm
         (provider/map->AnthropicTcProvider
          {:api-key "offline-test" :model default-model :max-tokens 1024})
         "Keep this task content" {:model effective-model})
        (catch clojure.lang.ExceptionInfo e
          (when-not (::captured (ex-data e)) (throw e)))))
    @body))

(deftest tc-tool-choice-honors-effective-model
  (doseq [[default-model effective-model expected-choice]
          [["claude-sonnet-4-5-20250929" "claude-fable-5-1" "auto"]
           ["claude-fable-5-1" "claude-sonnet-4-5-20250929" "any"]]]
    (let [body (capture-tc-body default-model effective-model)]
      (is (= effective-model (:model body)))
      (is (= expected-choice (get-in body [:tool_choice :type])))
      (is (seq (:tools body))))))

;; Exercise the real compiled call closure and provider request builder without
;; starting a coordinator or permitting an HTTP request. Serialization is the
;; offline boundary; the sentinel deliberately aborts before transport.
(defn- capture-pf-body [compile-fn]
  (let [body (atom nil)
        task-prefix "(quine completion (eval (do (quine prompt \"Preserve my task\") "]
    (with-redefs-fn
      {#'spell.llm/make-inbox-fn
       (fn [config _] ((:call-fn config) task-prefix))
       #'provider/call-with-retries (fn [f _] (f nil))
       #'clojure.data.json/write-str
       (fn [request & _]
         (reset! body request)
         (throw (ex-info "Offline request captured" {::captured true})))}
      (fn []
        (try
          ((compile-fn) "Preserve my task" :prefill-offline-test)
          (catch clojure.lang.ExceptionInfo e
            (when-not (::captured (ex-data e)) (throw e))))))
    {:body @body :prefix task-prefix}))

(deftest no-prefill-keeps-task-in-user-content
  (doseq [[model policy]
          [["claude-fable-5-1" {}]
           ["claude-fable-5-1" {:prefill? false}]
           ["claude-sonnet-4-5-20250929" {:prefill? false}]]]
    (let [{:keys [body prefix]}
          (capture-pf-body
           #(llm/compile-agent
             (merge {:provider (provider/map->AnthropicPfProvider
                                {:api-key "offline-test" :model model :max-tokens 1024})}
                    policy)))]
      (is (= model (:model body)))
      (is (= ["user"] (mapv :role (:messages body))))
      (is (clojure.string/includes? (pr-str (:messages body)) "Preserve my task"))) ))

(deftest child-explicit-false-reaches-root-and-worker-dispatch
  (let [pf (provider/map->AnthropicPfProvider
            {:api-key "offline-test" :model "claude-sonnet-4-5-20250929"
             :max-tokens 1024})
        dir (.toFile (java.nio.file.Files/createTempDirectory
                      "spell-child-prefill-policy-"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        parent-file (java.io.File. dir "parent.agent.edn")
        child-file (java.io.File. dir "child.agent.edn")]
    (try
      (spit child-file (pr-str {:base "parent.agent.edn" :prefill? false}))
      (doseq [parent [{} {:prefill? true}]]
        (spit parent-file (pr-str parent))
        (let [child (spell.agent/load-agent-spec (.getAbsolutePath child-file))]
          (is (false? (:prefill? child))
              (str "Loaded child false must override parent " parent))
          (is (not (contains? child :base)) "The real :base profile was resolved")
          (doseq [compile-fn
                  [#(spell.agent/compile-agent-spec (assoc child :provider pf))
                   #(get (spell.agent/resolve-workers
                          {'child child} llm/compile-agent
                          spell.agent/compile-agent-spec nil pf nil)
                         :child)]]
            (let [{:keys [body]} (capture-pf-body compile-fn)]
              (is (= ["user"] (mapv :role (:messages body))))
              (is (clojure.string/includes? (pr-str (:messages body))
                                          "Preserve my task"))))))
      (finally
        (.delete child-file)
        (.delete parent-file)
        (.delete dir)))))

(deftest inherited-false-reaches-root-and-worker-dispatch
  (let [pf (provider/map->AnthropicPfProvider
            {:api-key "offline-test" :model "claude-sonnet-4-5-20250929"
             :max-tokens 1024})
        inherited (#'spell.agent/merge-agent-defs {:prefill? false} {})]
    (is (false? (:prefill? inherited)))
    (doseq [compile-fn
            [#(spell.agent/compile-agent-spec (assoc inherited :provider pf))
             #(get (spell.agent/resolve-workers
                    {'child inherited} llm/compile-agent
                    spell.agent/compile-agent-spec nil pf nil)
                   :child)]]
      (let [{:keys [body]} (capture-pf-body compile-fn)]
        (is (= ["user"] (mapv :role (:messages body))))
        (is (clojure.string/includes? (pr-str (:messages body)) "Preserve my task"))))))
