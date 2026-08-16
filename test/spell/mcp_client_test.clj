(ns spell.mcp-client-test
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [spell.agent :as agent]
            [spell.mcp.client :as client]
            [spell.mcp.cli :as mcp-cli]
            [spell.mcp.namespace :as mcp-ns]
            [spell.mcp.stdio :as stdio]
            [spell.mcp-test-server :as test-server]
            [spell.prompt :as prompt]))

(defn- stdio-command []
  [(str (System/getProperty "java.home") "/bin/java")
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-m" "spell.mcp-stdio-fixture"])

(defn with-server [f]
  (let [server (test-server/start-server)]
    (try (f server) (finally (test-server/stop-server server)))))

(deftest http-discovery-and-call-test
  (with-server
    (fn [{:keys [url requests]}]
      (with-open [c (client/open-client :demo {:transport {:http {:url url}}})]
        (is (= ["add" "echo"] (mapv #(get % "name") (client/tools c))))
        (is (= {"echo" "hello"}
               (get (client/call-tool! c "echo" {"text" "hello"})
                    "structuredContent")))
        (is (= 1 (count (client/resources c))))
        (is (= 1 (count (client/resource-templates c))))
        (is (= 1 (count (client/prompts c))))
        (is (= "resource text"
               (get-in (client/read-resource! c "memory://readme")
                       ["contents" 0 "text"]))))
      (is (every? #(= "2026-07-28"
                      (get-in % [:body "params" "_meta"
                                 "io.modelcontextprotocol/protocolVersion"]))
                  @requests))
      (is (not-any? #(= "initialize" (get-in % [:body "method"])) @requests))
      (is (every? #(nil? (get-in % [:headers "mcp-session-id"])) @requests))
      (let [call-request (some #(when (= "tools/call" (get-in % [:body "method"])) %) @requests)]
        (is (= ["tools/call"] (get-in call-request [:headers "mcp-method"])))
        (is (= ["echo"] (get-in call-request [:headers "mcp-name"])))
        (is (= ["hello"] (get-in call-request [:headers "mcp-param-text"]))))
      (with-open [c (client/open-client :redaction
                                        {:transport {:http {:url url}}
                                         :headers {"X-Secret" "needle-secret"}})]
        (is (= "[REDACTED]"
               (get-in (client/call-tool! c "echo" {"text" "needle-secret"})
                       ["structuredContent" "echo"])))))))

(deftest generated-namespace-and-permissions-test
  (with-server
    (fn [{:keys [url]}]
      (let [bundle (mcp-ns/compile-servers
                    {'demo {:transport {:http {:url url}}
                            :tools {'say "echo"}
                            :resources ["memory://readme"]
                            :prompts ["review"]
                            :completion true}}
                    ".")
            demo (get-in bundle [:namespaces 'demo])
            mcp (get-in bundle [:namespaces 'mcp])]
        (is (re-find #"say\(\{\"text\" string\}\).*one argument map"
                     (get-in demo [:docs :say])))
        (is (str/includes? (get-in mcp [:docs :guide]) "ref/prompt"))
        (is (str/includes? (get-in mcp [:docs :guide]) "resourceSubscriptions"))
        (is (str/includes? (get-in mcp [:detail :complete]) "ref/prompt"))
        (is (str/includes? (get-in mcp [:detail :listen-send]) "resourceSubscriptions"))
        (is (= {"echo" "hi"}
               (get ((:say demo) {"text" "hi"}) "structuredContent")))
        (is (= ["memory://readme"] (mapv #(get % "uri") ((:resources mcp) :demo))))
        (is (= ["review"] (mapv #(get % "name") ((:prompts mcp) :demo))))
        (is (= "resource text"
               (get-in ((:read-resource mcp) :demo "memory://readme")
                       ["contents" 0 "text"])))
        (is (= "Review it"
               (get-in ((:get-prompt mcp) :demo "review" {"style" "strict"})
                       ["messages" 0 "content" "text"])))
        (is (= ["strict" "friendly"]
               (get-in ((:complete mcp) :demo
                        {"type" "ref/prompt" "name" "review"}
                        {"name" "style" "value" "s"})
                       ["completion" "values"])))
        (is (= "demo" (get ((:info mcp) :demo) "mcp/server")))
        (is (= :mcp-permission-denied
               (try ((:read-resource mcp) :demo "memory://private") nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))))

(deftest summary-disclosure-test
  (let [namespace-map {:short-docs "Large namespace"
                       :disclosure :summary
                       :docs (into {:guide "Guide"}
                                   (map (fn [i] [(keyword (str "f" i)) "detail"]) (range 25)))}
        system (prompt/generate-system-prompt {'large namespace-map})]
    (is (re-find #"Large namespace: use" system))
    (is (not (re-find #"f24: detail" system)))))

(deftest explorer-style-cli-test
  (with-server
    (fn [{:keys [url]}]
      (let [listed (mcp-cli/execute ["list" url])
            called (mcp-cli/execute ["call" url "add" "-a" "a" "2" "-a" "b" "3" "--raw"])
            info (mcp-cli/execute ["doctor" url])]
        (is (= 0 (:status listed)))
        (is (re-find #"echo\(\{\"text\" string\}\).*one argument map" (:out listed)))
        (is (= 0 (:status called)))
        (is (re-find #"\"text\":\"5\"" (str/replace (:out called) #"\s" "")))
        (is (= 0 (:status info)))
        (is (re-find #"MCP 2026-07-28" (:out info)))))))

(deftest reusable-cache-and-subscription-invalidation-test
  (let [server (test-server/start-server {:ttl-ms 60000})]
    (try
      (let [{:keys [url requests]} server
            notifications (atom [])]
        (with-open [c1 (client/open-client :cached {:transport {:http {:url url}}})]
          (is (= 2 (count (client/tools c1)))))
        (with-open [c2 (client/open-client :cached {:transport {:http {:url url}}})]
          (is (= 2 (count (client/tools c2))))
          (is (= 1 (count (filter #(= "server/discover" (get-in % [:body "method"]))
                                  @requests))))
          (client/listen! c2 {"toolsListChanged" true} #(swap! notifications conj %))
          (is (= ["notifications/tools/list_changed"]
                 (mapv #(get % "method") @notifications)))
          (client/tools c2)
          (is (= 2 (count (filter #(= "server/discover" (get-in % [:body "method"]))
                                  @requests))))))
      (finally
        (test-server/stop-server server)))))

(deftest stdio-concurrency-subscription-and-cleanup-test
  (let [c (client/open-client :stdio-test
                              {:transport {:stdio {:command (stdio-command)
                                                  :env {"MCP_SECRET" {:env "HOME"}}}}})
        process (:process (:stdio-transport c))]
    (try
      (is (= ["delayed_echo" "env_probe"] (mapv #(get % "name") (client/tools c))))
      (let [slow (future (client/call-tool! c "delayed_echo"
                                            {"value" "slow" "delayMs" 100}))
            fast (future (client/call-tool! c "delayed_echo"
                                            {"value" "fast" "delayMs" 1}))]
        (is (= "fast" (get-in @fast ["structuredContent" "value"])))
        (is (= "slow" (get-in @slow ["structuredContent" "value"]))))
      (let [notifications (atom [])]
        (client/listen! c {"toolsListChanged" true} #(swap! notifications conj %))
        (is (= "notifications/tools/list_changed" (get-in @notifications [0 "method"]))))
      (let [probe (client/call-tool! c "env_probe" {})]
        (is (= "[REDACTED]" (get-in probe ["structuredContent" "value"])))
        (is (false? (get-in probe ["structuredContent" "inheritedApiKey"])))
        (Thread/sleep 20)
        (is (= ["[REDACTED]"] (stdio/stderr-tail (:stdio-transport c)))))
      (finally
        (.close ^java.io.Closeable c)))
    (is (false? (.isAlive ^Process process)))))

(deftest version-pagination-and-credential-boundary-test
  (doseq [[options expected-type]
          [[{:supported-versions ["2025-11-25"]} :unsupported-mcp-version]
           [{:repeat-cursor? true} :repeated-pagination-cursor]]]
    (let [server (test-server/start-server options)]
      (try
        (with-open [c (client/open-client :negative
                                          {:transport {:http {:url (:url server)}}})]
          (is (= expected-type
                 (try (client/tools c) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
        (finally (test-server/stop-server server)))))
  (let [server (test-server/start-server {:ttl-ms 60000})]
    (try
      (doseq [context ["one" "two"]]
        (with-open [c (client/open-client :credential-boundary
                                          {:transport {:http {:url (:url server)}}
                                           :headers {"X-Credential-Context" context}})]
          (client/tools c)))
      (is (= 2 (count (filter #(= "server/discover" (get-in % [:body "method"]))
                              @(:requests server)))))
      (finally (test-server/stop-server server)))))

(deftest inherited-server-profile-path-test
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "spell-mcp-profile" (make-array java.nio.file.attribute.FileAttribute 0)))
        base-dir (io/file root "base")
        child-dir (io/file root "child")
        server-dir (io/file root "servers")
        _ (doseq [dir [base-dir child-dir server-dir]] (.mkdirs dir))
        server-file (io/file server-dir "demo.mcp.edn")
        base-file (io/file base-dir "base.agent.edn")
        child-file (io/file child-dir "child.agent.edn")]
    (try
      (spit server-file (pr-str {:transport {:http {:url "https://example.com/mcp"}}}))
      (spit base-file
            (pr-str {:mcp-servers {'demo {:server "../servers/demo.mcp.edn"
                                          :tools :all}}}))
      (spit child-file (pr-str {:base "../base/base.agent.edn"}))
      (let [spec (agent/load-agent-spec (.getPath child-file))
            entry (get (:mcp-servers spec) 'demo)
            resolved (mcp-ns/resolve-server-entry entry (:base-dir spec))]
        (is (= "https://example.com/mcp" (get-in resolved [:transport :http :url]))))
      (finally
        (doseq [file (reverse (file-seq root))] (.delete file))))))

(deftest runtime-discovers-only-permitted-catalogs-test
  (with-server
    (fn [{:keys [url requests]}]
      (let [bundle (mcp-ns/compile-servers
                    {'tools-only {:transport {:http {:url url}}
                                  :tools :all}}
                    ".")]
        (try
          (is (= #{"add" "echo"}
                 (set (map name (remove #{:guide}
                                        (keys (get-in bundle [:namespaces 'tools-only :docs])))))))
          (let [methods (set (map #(get-in % [:body "method"]) @requests))]
            (is (contains? methods "tools/list"))
            (is (not (contains? methods "resources/list")))
            (is (not (contains? methods "prompts/list"))))
          (finally
            ((:close! bundle))))))))
