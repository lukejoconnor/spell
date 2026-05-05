(ns spell.orchestration-games-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.orchestration-games :as og]))

(defn- temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "spell-og-test-" (System/nanoTime)))
    (.mkdirs)))

(defn- write-json! [path value]
  (spit path (str (json/write-str value) "\n")))

(defn- write-trial! [dir response trace]
  (write-json! (io/file dir "response.json") response)
  (doto (io/file dir "trace") (.mkdirs))
  (spit (io/file dir "trace" "trace.edn") (pr-str trace)))

(deftest prompt-profile-init-test
  (let [request-map #'og/request-map
        scaffold (request-map {:output-root "/tmp/og"
                               :depth 80
                               :budget 0.0
                               :reasoning-effort "high"
                               :prompt-profile "scaffold"}
                              :auction-agents "test:dummy" 0 "/tmp/og")
        minimal (request-map {:output-root "/tmp/og"
                              :depth 80
                              :budget 0.0
                              :reasoning-effort "high"
                              :prompt-profile "minimal"}
                             :auction-agents "test:dummy" 0 "/tmp/og")]
    (testing "scaffold profile records and seeds executable orchestration"
      (is (= "scaffold" (:prompt-profile scaffold)))
      (is (str/includes? (:init scaffold) "TASK:"))
      (is (str/includes? (:init scaffold) "agents/!spawn-ask"))
      (is (str/includes? (:init scaffold) "agents/parent-handle")))
    (testing "minimal profile is the less-instructive ablation"
      (is (= "minimal" (:prompt-profile minimal)))
      (is (str/includes? (:prompt minimal) "Use agents/"))
      (is (str/includes? (:init minimal) "'(!extend)"))
      (is (not (str/includes? (:init minimal) "TASK:")))
      (is (not (str/includes? (:init minimal) "agents/!spawn-ask")))
      (is (not (str/includes? (:init minimal) "agents/parent-handle"))))))

(deftest scorer-counts-parsed-program-ops-test
  (let [score-dir #'og/score-dir
        dir (temp-dir)]
    (write-trial!
     dir
     {:ok true
      :game "auction-agents"
      :model "test:dummy"
      :prompt-profile "scaffold"
      :attempt 0
      :result "Winner: bidder-c with bids bidder-a 300, bidder-b 500, bidder-c 700"
      :trace-dir (.getPath (io/file dir "trace"))}
     {:nodes [{:id 0
               :program '(quine completion
                            (eval
                             (do
                               (think "Mentioning agents/send in a string is not an operator.")
                               (quote
                                (agents/!spawn-ask
                                 [["child-a" :bidder-a]
                                  ["child-b" :bidder-b]
                                  ["child-c" :bidder-c]])))))
               :response "A prose mention of agents/send and agents/!ask should not be needed."}]})
    (let [score (score-dir (.getPath dir))
          evidence (:evidence score)]
      (is (:success score))
      (is (= 3 (:spawn-ask-count evidence)))
      (is (= 0 (:send-count evidence)))
      (is (= 3 (get-in evidence [:program-ops :spawn-ask-count])))
      (is (= 1 (get-in evidence [:response-ops :send-count]))))))

(deftest scorer-does-not-treat-response-prose-as-orchestration-test
  (let [score-dir #'og/score-dir
        dir (temp-dir)]
    (write-trial!
     dir
     {:ok true
      :game "auction-agents"
      :model "test:dummy"
      :prompt-profile "minimal"
      :attempt 0
      :result "Winner would be bidder-c."
      :trace-dir (.getPath (io/file dir "trace"))}
     {:nodes [{:id 0
               :program '(quine completion
                            (eval
                             (do
                               (think "The prompt string mentions :bidder-a :bidder-b :bidder-c and agents/!spawn-ask.")
                               "done")))
               :response "I should use agents/!spawn-ask and agents/send for :bidder-a :bidder-b :bidder-c, then name the winner."}]})
    (let [score (score-dir (.getPath dir))]
      (is (not (:success score)))
      (is (not (:orchestration score)))
      (is (= 0 (get-in score [:evidence :spawn-ask-count])))
      (is (pos? (get-in score [:evidence :response-ops :spawn-ask-count]))))))

(deftest audit-prompt-markdown-test
  (let [audit-prompt-markdown #'og/audit-prompt-markdown
        text (audit-prompt-markdown
              "/tmp/og"
              [{:model "fireworks-tc:glm-5p1"
                :prompt-profile "scaffold"
                :game "auction-agents"
                :attempt 0
                :success true
                :orchestration true
                :scheme true
                :dir "/tmp/og/runs/auction-agents/glm51/attempt-00"
                :evidence {:spawn-ask-count 3}
                :notes nil}])]
    (is (str/includes? text "Do not trust the agent's self-report"))
    (is (str/includes? text "real agents/ orchestration"))
    (is (str/includes? text "/tmp/og/runs/auction-agents/glm51/attempt-00"))
    (is (str/includes? text "Genuine success"))))
