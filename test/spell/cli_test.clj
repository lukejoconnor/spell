(ns spell.cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.cli :as cli]))

(deftest print-usage-context-stats-test
  (testing "verbose usage output includes mean/max total context"
    (let [usage-atom (atom {:by-model {"model-a" {:input_tokens 300
                                                  :output_tokens 120
                                                  :cache_creation_input_tokens 10
                                                  :cache_read_input_tokens 20
                                                  :calls 2
                                                  :mean_total_tokens 225
                                                  :max_total_tokens 260}
                                       "model-b" {:input_tokens 50
                                                  :output_tokens 10
                                                  :calls 1
                                                  :mean_total_tokens 60
                                                  :max_total_tokens 60}}
                            :total {:input_tokens 350
                                    :output_tokens 130
                                    :cache_creation_input_tokens 10
                                    :cache_read_input_tokens 20
                                    :calls 3
                                    :mean_total_tokens 170
                                    :max_total_tokens 260}})
          output (with-out-str ((var cli/print-usage) usage-atom))]
      (is (str/includes? output "[context: mean 225 / max 260]"))
      (is (str/includes? output "[context: mean 170 / max 260]")))))

(deftest validate-args-accepts-new-openai-reasoning-levels
  (testing "xhigh reasoning effort is accepted"
    (let [result (cli/validate-args ["--reasoning-effort" "xhigh" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "xhigh" (get-in result [:options :reasoning-effort])))))

  (testing "none reasoning effort is accepted"
    (let [result (cli/validate-args ["--reasoning-effort" "none" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "none" (get-in result [:options :reasoning-effort]))))))
