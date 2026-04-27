(ns spell.cli-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.api :as api]
            [spell.cli :as cli]
            [spell.provider :as provider]))

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

(deftest model-aliases-test
  (is (= "claude-opus-4-7" (cli/resolve-model "opus")))
  (is (= "claude-opus-4-6" (cli/resolve-model "opus46")))
  (is (= "claude-opus-4-5-20251101" (cli/resolve-model "opus45")))
  (is (= "gpt-5.4" (cli/resolve-model "gpt")))
  (is (= "gpt-5.4" (cli/resolve-model "gpt54"))))

(deftest validate-args-supports-optional-log-file
  (testing "--log without FILE treats the remaining argument as the prompt"
    (let [result (cli/validate-args ["--log" "Return 42"])
          log-path (get-in result [:options :log])]
      (is (= "Return 42" (:prompt result)))
      (is (str/starts-with? log-path (str "logs" java.io.File/separator "spell-")))
      (is (str/ends-with? log-path ".log"))))

  (testing "--log FILE remains an explicit path when a prompt follows"
    (let [result (cli/validate-args ["--log" "custom.log" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "custom.log" (get-in result [:options :log])))))

  (testing "--log=FILE remains explicit"
    (let [result (cli/validate-args ["--log=custom.log" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "custom.log" (get-in result [:options :log]))))))

(defn- captured-run-options [opts]
  (let [captured (atom nil)]
    (with-redefs-fn {#'api/run (fn [run-opts]
                                 (reset! captured run-opts)
                                 {:result "ok"})
                     #'cli/make-provider (fn [_]
                                            (provider/test-provider {:response "\"ok\""}))}
      #(#'cli/run-prompt "Return 42" opts (atom {:by-model {}})))
    @captured))

(deftest cli-maps-reasoning-effort-for-anthropic
  (testing "Opus 4.7 receives adaptive effort, not a numeric thinking budget"
    (let [opts (captured-run-options {:model "opus" :reasoning-effort "medium"})]
      (is (nil? (:thinking opts)))
      (is (= "medium" (:reasoning-effort opts)))))

  (testing "non-adaptive Anthropic models receive benchmark-style thinking budgets"
    (let [opts (captured-run-options {:model "anthropic-tc:claude-sonnet-4-5"
                                      :reasoning-effort "medium"})]
      (is (= 10000 (:thinking opts)))
      (is (nil? (:reasoning-effort opts)))))

  (testing "low and none do not enable Anthropic thinking"
    (doseq [effort ["low" "none"]]
      (let [opts (captured-run-options {:model "anthropic-tc:claude-sonnet-4-5"
                                        :reasoning-effort effort})]
        (is (nil? (:thinking opts)))
        (is (nil? (:reasoning-effort opts))))))

  (testing "explicit --thinking overrides Anthropic reasoning effort mapping"
    (let [opts (captured-run-options {:model "anthropic-tc:claude-sonnet-4-5"
                                      :thinking 12345
                                      :reasoning-effort "high"})]
      (is (= 12345 (:thinking opts)))
      (is (nil? (:reasoning-effort opts)))))

  (testing "OpenAI providers keep reasoning-effort unchanged"
    (let [opts (captured-run-options {:model "openai-tc:gpt-5.4"
                                      :reasoning-effort "xhigh"})]
      (is (nil? (:thinking opts)))
      (is (= "xhigh" (:reasoning-effort opts))))))

(deftest log-writer-creates-parent-directory
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "spell-cli-log-test-" (System/nanoTime)))
        log-file (io/file dir "nested" "spell.log")
        writer (#'cli/log-writer (str log-file))]
    (try
      (.write writer "hello")
      (.close writer)
      (is (.exists log-file))
      (finally
        (when writer
          (try (.close writer) (catch Exception _)))
        (when (.exists log-file) (.delete log-file))
        (when (.exists (.getParentFile log-file)) (.delete (.getParentFile log-file)))
        (when (.exists dir) (.delete dir))))))
