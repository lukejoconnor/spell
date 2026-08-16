(ns spell.feedback-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.feedback :as feedback]
            [spell.runtime :as runtime]
            [spell.trace :as trace]))


(deftype TrailingForms [])

(defmethod print-method TrailingForms [_ writer]
  (.write writer "nil}} {:injected true} ;"))

(defn- temp-path []
  (let [f (java.io.File/createTempFile "spell-feedback-" ".edn")]
    (.delete f)
    (.getAbsolutePath f)))

(deftest log-appends-structured-edn-test
  (let [path (temp-path)]
    (try
      (with-redefs [feedback/feedback-path (constantly path)]
        (let [first-entry (feedback/log :friction "Hard to discover an option" {:severity :low})
              second-entry (feedback/log :idea "Add a shortcut")
              entries (mapv edn/read-string (str/split-lines (slurp path)))]
          (is (= path (:path first-entry)))
          (is (string? (:timestamp first-entry)))
          (is (= [{:category :friction
                   :message "Hard to discover an option"
                   :metadata {:severity :low}}
                  {:category :idea
                   :message "Add a shortcut"
                   :metadata {}}]
                 (mapv #(dissoc % :timestamp) entries)))
          (is (= :idea (:category second-entry)))))
      (finally
        (.delete (java.io.File. path))))))

(deftest log-validates-input-test
  (testing "category"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported feedback category"
                          (feedback/log :other "message"))))
  (testing "message"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-blank string"
                          (feedback/log :bug "  "))))
  (testing "metadata must be a map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"metadata must be a map"
                          (feedback/log :docs "message" []))))
  (testing "metadata values must be EDN-readable without modifying an existing log"
    (let [path (temp-path)
          original "{:existing true}\n"]
      (try
        (spit path original)
        (with-redefs [feedback/feedback-path (constantly path)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"only EDN-readable values"
                                (feedback/log :bug "message"
                                              {:nested {:value (Object.)}})))
          (is (= original (slurp path))))
        (finally
          (.delete (java.io.File. path))))))
  (testing "metadata printing must produce exactly one EDN form"
    (let [path (temp-path)]
      (try
        (with-redefs [feedback/feedback-path (constantly path)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"only EDN-readable values"
                                (feedback/log :bug "message"
                                              {:value (TrailingForms.)})))
          (is (not (.exists (java.io.File. path)))))
        (finally
          (.delete (java.io.File. path)))))))

(deftest log-accepts-nested-edn-metadata-test
  (let [path (temp-path)
        metadata {:context {:tags #{:parser :feedback}
                            :steps [1 2 3]
                            :optional nil}}]
    (try
      (with-redefs [feedback/feedback-path (constantly path)]
        (feedback/log :bug "Nested metadata" metadata)
        (is (= metadata
               (:metadata (edn/read-string (slurp path))))))
      (finally
        (.delete (java.io.File. path))))))

(deftest log-annotates-agent-and-trace-context-test
  (let [path (temp-path)]
    (try
      (with-redefs [feedback/feedback-path (constantly path)]
        (binding [runtime/*current-handle* :reviewer
                  trace/*trace-node-id* 42]
          (let [returned (feedback/log :bug "Contextual failure")
                persisted (edn/read-string (slurp path))]
            (is (= :reviewer (:agent-handle returned)))
            (is (= 42 (:trace-node-id returned)))
            (is (= :reviewer (:agent-handle persisted)))
            (is (= 42 (:trace-node-id persisted))))))
      (finally
        (.delete (java.io.File. path))))))

(deftest namespace-map-test
  (is (= feedback/log (:log feedback/feedback-namespace)))
  (is (str/includes? (get-in feedback/feedback-namespace [:docs :guide])
                     "SPELL_FEEDBACK_PATH")))
