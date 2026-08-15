(ns spell.feedback-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.feedback :as feedback]))

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
  (testing "metadata"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"metadata must be a map"
                          (feedback/log :docs "message" [])))))

(deftest namespace-map-test
  (is (= feedback/log (:log feedback/feedback-namespace)))
  (is (str/includes? (get-in feedback/feedback-namespace [:docs :guide])
                     "SPELL_FEEDBACK_PATH")))
