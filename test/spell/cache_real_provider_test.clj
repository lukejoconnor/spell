(ns spell.cache-real-provider-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.agent :as agent]
            [spell.llm :as llm]
            [spell.prompt :as prompt]
            [spell.provider :as provider]))

(def ^:private anthropic-cache-model "claude-haiku-4-5-20251001")
(def ^:private codex-cache-model "gpt-5.3-codex")
(def ^:private cache-check-prompt
  "Call spell_suffix with input.suffix exactly \"42\" and no other content.")
(def ^:private heuristic-agent-paths
  ["config/agents/base-pf.agent.edn"
   "config/agents/io-pf.agent.edn"
   "config/agents/math-pf.agent.edn"
   "config/agents/base-tc.agent.edn"
   "config/agents/io-tc.agent.edn"
   "config/agents/cli.agent.edn"])

(defn env-key
  [env-var]
  (let [value (System/getenv env-var)]
    (when-not (str/blank? value)
      value)))

(defmacro when-api-key
  [env-var & body]
  `(if-let [api-key# (env-key ~env-var)]
     (let [~'api-key api-key#]
       ~@body)
     (do
       (println (str "Skipping ^:real-provider test; missing " ~env-var))
       (is true))))

(defn make-usage-atom
  []
  (atom {:by-model {}}))

(defn get-cache-stats
  [usage-atom model]
  (let [stats (get-in @usage-atom [:by-model model] {})]
    {:cache_creation (:cache_creation_input_tokens stats 0)
     :cache_read (:cache_read_input_tokens stats 0)
     :input_tokens (:input_tokens stats 0)}))

(defn- expand-home
  [path]
  (if (str/starts-with? path "~")
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- codex-auth-file
  []
  (let [path (expand-home "~/.codex/auth.json")
        file (io/file path)]
    (when (.isFile file)
      (.getAbsolutePath file))))

(defmacro with-codex-auth
  [& body]
  `(if-let [auth-file# (codex-auth-file)]
     (let [~'auth-file auth-file#]
       ~@body)
     (do
       (println "Skipping ^:real-provider test; missing ~/.codex/auth.json")
       (is true))))

(defn- agent-paths
  []
  (->> (file-seq (io/file "config/agents"))
       (filter #(.isFile %))
       (map #(.getPath %))
       (filter #(str/ends-with? % ".agent.edn"))
       sort
       vec))

(defn compose-agent-system-prompt
  [agent-path]
  (let [config (agent/load-agent-config agent-path)
        namespaces ((:resolve-namespaces-fn config) llm/make-llm)]
    (prompt/compose-system-prompt {:base (:system config)
                                   :namespaces namespaces
                                   :core-namespaces llm/core-namespaces
                                   :format (:format config)})))

(deftest system-prompts-clear-haiku-cache-char-heuristic-test
  (testing "shipped prefill and tool-call agent prompts stay above the cache-min-chars heuristic"
    (doseq [agent-path heuristic-agent-paths]
      (let [system-prompt (compose-agent-system-prompt agent-path)]
        (is (>= (count system-prompt) (#'provider/cache-min-chars anthropic-cache-model))
            (str agent-path " shrank below the local cache-min-chars heuristic"))))))

(deftest ^:real-provider anthropic-system-prompts-report-cache-activity-test
  (when-api-key "ANTHROPIC_API_KEY"
    (let [provider (provider/anthropic-tc-provider {:api-key api-key
                                                    :model anthropic-cache-model
                                                    :max-tokens 128})]
      (doseq [agent-path (agent-paths)]
        (testing agent-path
          (let [usage-atom (make-usage-atom)
                system-prompt (compose-agent-system-prompt agent-path)]
            (binding [provider/*usage* usage-atom
                      provider/*budget* nil]
              (provider/call-llm provider cache-check-prompt {:system system-prompt}))
            (let [{:keys [cache_creation cache_read input_tokens]} (get-cache-stats usage-atom anthropic-cache-model)]
              (is (pos? input_tokens) "expected prompt tokens to be recorded")
              (is (pos? (+ cache_creation cache_read))
                  (str "expected Anthropic cache activity for " agent-path)))))))))

(deftest ^:real-provider anthropic-cache-read-hit-test
  (when-api-key "ANTHROPIC_API_KEY"
    (let [usage-atom (make-usage-atom)
          provider (provider/anthropic-tc-provider {:api-key api-key
                                                    :model anthropic-cache-model
                                                    :max-tokens 128})
          system-prompt (compose-agent-system-prompt "config/agents/io-tc.agent.edn")]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        (provider/call-llm provider cache-check-prompt {:system system-prompt})
        (Thread/sleep 1000)
        (provider/call-llm provider cache-check-prompt {:system system-prompt}))
      (let [{:keys [cache_creation cache_read]} (get-cache-stats usage-atom anthropic-cache-model)]
        (is (pos? (+ cache_creation cache_read))
            "expected Anthropic requests to report cache activity")
        (is (pos? cache_read) "expected second Anthropic call to read from cache")))))

(deftest ^:real-provider codex-cache-usage-smoke-test
  (with-codex-auth
    (let [usage-atom (make-usage-atom)
          provider (provider/codex-tc-provider {:auth-file auth-file
                                                :model codex-cache-model
                                                :max-tokens 128})
          system-prompt (compose-agent-system-prompt "config/agents/io-tc.agent.edn")]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        (provider/call-llm provider cache-check-prompt {:system system-prompt})
        (Thread/sleep 2000)
        (provider/call-llm provider cache-check-prompt {:system system-prompt}))
      (let [{:keys [input_tokens]} (get-cache-stats usage-atom codex-cache-model)
            stats (get-in @usage-atom [:by-model codex-cache-model])]
        (is (= 2 (:calls stats)) "expected both Codex requests to be tracked")
        (is (pos? input_tokens) "expected Codex usage accounting to include input tokens")
        (is (integer? (:cache_read_input_tokens stats 0))
            "expected parsed Codex cached-token field to remain numeric")))))

(deftest ^:real-provider short-system-prompt-does-not-create-cache-test
  (when-api-key "ANTHROPIC_API_KEY"
    (let [usage-atom (make-usage-atom)
          provider (provider/anthropic-tc-provider {:api-key api-key
                                                    :model anthropic-cache-model
                                                    :max-tokens 128})]
      (binding [provider/*usage* usage-atom
                provider/*budget* nil]
        (provider/call-llm provider cache-check-prompt {:system "You are helpful."}))
      (let [{:keys [cache_creation cache_read]} (get-cache-stats usage-atom anthropic-cache-model)]
        (is (zero? cache_creation) "short prompts should not create cache entries")
        (is (zero? cache_read) "short prompts should not read cache entries")))))
