(ns spell.mcp-regression-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [spell.agent :as agent]
            [spell.mcp.client :as client]
            [spell.mcp.namespace :as mcp-ns]
            [spell.prompt :as prompt]))

(def ^:private dummy-server
  {:transport {:http {:url "https://example.com/mcp"}}
   :tools :all})

(deftest partial-client-construction-cleans-up-test
  (let [opened (atom 0)
        closed (atom 0)
        first-client (reify java.io.Closeable
                       (close [_] (swap! closed inc)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"second client failed"
         (with-redefs [client/open-client
                       (fn [& _]
                         (if (= 1 (swap! opened inc))
                           first-client
                           (throw (ex-info "second client failed" {}))))]
           (mcp-ns/compile-servers
            (array-map 'first dummy-server 'second dummy-server)
            "."))))
    (is (= 2 @opened))
    (is (= 1 @closed))))

(deftest normalized-server-aliases-must-be-unique-test
  (let [opened (atom 0)]
    (is (= :duplicate-mcp-server-alias
           (try
             (with-redefs [client/open-client
                           (fn [& _] (swap! opened inc))]
               (mcp-ns/compile-servers
                (array-map 'demo dummy-server :demo dummy-server)
                "."))
             nil
             (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (is (zero? @opened))))

(deftest inherited-normalized-server-aliases-must-be-unique-test
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "spell-mcp-duplicate-alias"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        parent (io/file root "parent.agent.edn")
        child (io/file root "child.agent.edn")]
    (try
      (spit parent (pr-str {:mcp-servers {'demo dummy-server}}))
      (spit child (pr-str {:base "parent.agent.edn"
                           :mcp-servers {:demo dummy-server}}))
      (let [spec (agent/load-agent-spec (.getPath child))]
        (is (= :duplicate-mcp-server-alias
               (try
                 (mcp-ns/compile-servers (:mcp-servers spec) (:base-dir spec))
                 nil
                 (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(deftest catalog-documentation-is-bounded-test
  (let [long-description (apply str (repeat 50000 "x"))
        many-properties (into {}
                              (map (fn [index]
                                     [(str "parameter_" index "_"
                                           (apply str (repeat 40 "p")))
                                      {"type" "string"}]))
                              (range 100))
        tools (mapv (fn [index]
                      {"name" (str "tool" index)
                       "description" long-description
                       "inputSchema" {"type" "object"
                                      "properties" many-properties}})
                    (range 20))
        signature (mcp-ns/compact-signature (first tools) :tool0)
        namespace-map
        (with-redefs [client/open-client (fn [& _] ::client)
                      client/catalog (constantly {:tools tools})
                      client/tools (constantly tools)]
          (get-in (mcp-ns/compile-servers {'demo dummy-server} ".")
                  [:namespaces 'demo]))
        guide (get-in namespace-map [:docs :guide])
        system-prompt (prompt/generate-system-prompt {'demo namespace-map})]
    (testing "individual descriptions and signatures are bounded"
      (is (<= (count signature) mcp-ns/max-compact-signature-chars))
      (is (re-find #"truncated" signature)))
    (testing "aggregate namespace documentation cannot enter the initial prompt"
      (is (= :summary (:disclosure namespace-map)))
      (is (<= (count guide) mcp-ns/max-namespace-guide-chars))
      (is (not (.contains system-prompt long-description)))
      (is (< (count system-prompt) 2000)))))

(deftest automatic-tool-selection-excludes-unsafe-server-names-test
  (let [tools [{"name" "safe_tool"
                "description" "Safe"
                "inputSchema" {"type" "object"}}
               {"name" "unsafe/tool"
                "description" "Unsafe without an alias"
                "inputSchema" {"type" "object"}}]
        protocol-exclusion {:name "invalid-schema"
                            :type :invalid-mcp-tool-schema
                            :message "Invalid schema"}
        namespace-map (with-redefs [client/tools (constantly tools)]
                        (mcp-ns/tool-namespace :demo ::client :all))
        [info refresh]
        (with-redefs [client/tools (constantly tools)
                      client/info (constantly {"excludedTools" [protocol-exclusion]})
                      client/refresh! (constantly {:tools tools
                                                   :resources []
                                                   :resource-templates []
                                                   :prompts []
                                                   :excluded-tools [protocol-exclusion]
                                                   :cache {}})]
          (let [mcp-map (mcp-ns/mcp-namespace {:demo ::client}
                                              {:demo {:tools :all}})]
            [((:info mcp-map) :demo)
             ((:refresh mcp-map) :demo)]))]
    (testing ":all keeps safe tools and does not let server-controlled names break startup"
      (is (fn? (:safe_tool namespace-map)))
      (is (nil? (get namespace-map (keyword "unsafe" "tool")))))
    (testing "unsafe names are reported alongside protocol-level exclusions"
      (is (= [protocol-exclusion
             {:name "unsafe/tool"
               :type :invalid-mcp-tool-alias
               :message "MCP tool needs an explicit Spell-safe alias: unsafe/tool"}]
             (get info "excludedTools")))
      (is (= (get info "excludedTools") (:excluded-tools refresh))))))

(deftest explicit-unsafe-tool-selection-remains-strict-test
  (let [tools [{"name" "safe_tool" "inputSchema" {"type" "object"}}
               {"name" "unsafe/tool" "inputSchema" {"type" "object"}}]]
    (testing "an explicit safe alias can expose an otherwise unsafe remote name"
      (let [called (atom nil)
            _ (with-redefs [client/tools (constantly tools)
                            client/call-tool! (fn [_ tool arguments]
                                                (reset! called [tool arguments])
                                                {"ok" true})]
                (let [namespace-map
                      (mcp-ns/tool-namespace :demo ::client {'safe_alias "unsafe/tool"})]
                  ((:safe_alias namespace-map) {})))]
        (is (= ["unsafe/tool" {}] @called))))
    (testing "explicit selection without a safe alias is a configuration error"
      (is (= :invalid-mcp-tool-alias
             (try
               (with-redefs [client/tools (constantly tools)]
                 (mcp-ns/tool-namespace :demo ::client ["unsafe/tool"]))
               nil
               (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
    (testing "an explicitly unsafe exposed alias is a configuration error"
      (is (= :invalid-mcp-tool-alias
             (try
               (with-redefs [client/tools (constantly tools)]
                 (mcp-ns/tool-namespace :demo ::client {"unsafe/alias" "safe_tool"}))
               nil
               (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))))
