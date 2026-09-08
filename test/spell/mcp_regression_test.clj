(ns spell.mcp-regression-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [spell.agent :as agent]
            [spell.mcp.client :as client]
            [spell.mcp.http :as mcp-http]
            [spell.mcp.namespace :as mcp-ns]
            [spell.mcp.protocol :as protocol]
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
             (get-in info [:out "excludedTools"])))
      (is (= (get-in info [:out "excludedTools"]) (get-in refresh [:out :excluded-tools]))))))

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

(deftest attributed-tool-envelopes-retain-complete-content
  (let [text (str (apply str (repeat 60000 "x")) " tail")
        content [{"type" "text" "text" text}
                 {"type" "image" "mimeType" "image/png" "data" text}]
        structured {"large" text "nested" {"values" (vec (range 1000))}}]
    (doseq [semantic? [false true]]
      (let [result (protocol/model-value :demo "tools/call"
                                        {"content" content "structuredContent" structured "isError" semantic?})]
        (is (= (not semantic?) (:ok result)))
        (is (not (contains? result :truncated)))
        (is (= content (get-in result [:out "content"])))
        (is (= structured (get-in result [:out "structuredContent"])))
        (is (= "demo" (get-in result [:out "mcp/server"])))
        (is (= "tools/call" (get-in result [:out "mcp/operation"])))
        (is (= (when semantic? text) (:err result)))))
    (is (= "MCP tool call failed" (:err (protocol/model-value :demo "empty" {"isError" true}))))
    (is (= [] (get-in (protocol/model-value :demo "empty" {"content" []}) [:out "content"])))))

(deftest generated-tools-retain-adversarial-results-through-real-client
  ;; Only the HTTP transport is mocked: catalog discovery, schema validation,
  ;; client/call-tool!, protocol projection, and generated functions stay real.
  (let [text (str " \n" (apply str (repeat 60000 "x")) " 😀 tail\t\n")
        content [{"type" "text" "text" text}
                 {"type" "image" "mimeType" "image/png" "data" text}]
        structured {"large" text "nested" {"values" (vec (range 1000))}}
        tool {"name" "large_tool"
              "inputSchema" {"type" "object"
                             "properties" {"semantic" {"type" "boolean"}}
                             "additionalProperties" false}
              "outputSchema" {"type" "object"
                              "properties" {"large" {"type" "string"}
                                             "nested" {"type" "object"}}
                              "required" ["large" "nested"]}}
        calls (atom [])
        complete (fn [fields]
                   (merge {"resultType" "complete" "ttlMs" 60000
                           "cacheScope" "private"} fields))]
    (with-redefs [mcp-http/send-request!
                  (fn [_ request active-tool]
                    (swap! calls conj (get request "method"))
                    (case (get request "method")
                      "server/discover"
                      (complete {"supportedVersions" [protocol/protocol-version]
                                 "capabilities" {"tools" {}}})
                      "tools/list" (complete {"tools" [tool]})
                      "tools/call"
                      (do
                        (is (= tool active-tool))
                        (is (= "large_tool" (get-in request ["params" "name"])))
                        (complete {"content" content "structuredContent" structured
                                   "isError" (true? (get-in request ["params" "arguments" "semantic"]))}))
                      (throw (AssertionError. (str "Unexpected transport method: " request)))))]
      (with-open [c (client/open-client
                     :adversarial-generated
                     {:transport {:http {:url (str "http://127.0.0.1:1/mock/" (random-uuid))}}})]
        (let [generated (mcp-ns/tool-namespace :adversarial-generated c {'large_alias "large_tool"})]
          (doseq [[arguments semantic?] [[[] false]
                                        [[{"semantic" false}] false]
                                        [[{"semantic" true}] true]]]
            (let [result (apply (:large_alias generated) arguments)
                  expected-out (cond-> {"mcp/server" "adversarial-generated"
                                        "mcp/operation" "large_tool"
                                        "content" content
                                        "structuredContent" structured}
                                 semantic? (assoc "isError" true "error" text))]
              (is (= {:ok (not semantic?) :out expected-out
                      :err (when semantic? text)}
                     result))
              (is (= content (get-in result [:out "content"])))
              (is (= structured (get-in result [:out "structuredContent"])))
              (is (not (contains? (:out result) :out)))
              (is (not (contains? (:out result) :ok))))))
        (is (= ["server/discover" "tools/list"
                "tools/call" "tools/call" "tools/call"] @calls))))))

(deftest namespace-boundaries-envelope-only-operational-failures
  (let [tool {"name" "echo" "inputSchema" {"type" "object"}}
        generated (with-redefs [client/tools (constantly [tool])]
                    (mcp-ns/tool-namespace :demo ::client :all))
        mcp (mcp-ns/mcp-namespace {:demo ::client}
                                {:demo {:resources :all :prompts :all :completion true :tools :all}})]
    (doseq [data [{:type :mcp-http-error :status 503 :result {"body" "full failure"}}
                 {:type :mcp-timeout}
                 {:type :mcp-stdio-error}]]
      (let [fail (fn [& _] (throw (ex-info "transport failed" data)))]
        (with-redefs [client/call-tool! fail client/read-resource! fail
                      client/get-prompt! fail client/complete! fail client/info fail client/refresh! fail]
          (doseq [result [((:echo generated) {})
                          ((:read-resource mcp) :demo "memory://readme")
                          ((:get-prompt mcp) :demo "review")
                          ((:complete mcp) :demo {} {})
                          ((:info mcp) :demo)
                          ((:refresh mcp) :demo)]]
            (is (false? (:ok result)))
            (is (= "transport failed" (:err result)))
            (is (not (contains? result :truncated)))
            (is (= (:result data) (:out result)))
            (is (= (contains? data :status) (contains? result :status)))
            (is (= (:status data) (:status result)))))))
    (doseq [data [{:type :schema-validation} {:type :invalid-mcp-arguments}
                 {:type :mcp-permission-denied} {:type :programming-bug}]]
      (with-redefs [client/call-tool! (fn [& _] (throw (ex-info "must throw" data)))]
        (is (= data (try ((:echo generated) {}) nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
    (is (thrown? clojure.lang.ExceptionInfo ((:echo generated) "not-a-map")))
    (with-redefs [client/call-tool! (fn [& _] (throw (IllegalArgumentException. "bad argument")))]
      (is (thrown? IllegalArgumentException ((:echo generated) {}))))))

(deftest operational-error-envelope-preserves-captured-stdio-diagnostics
  (let [tool {"name" "echo" "inputSchema" {"type" "object"}}
        generated (with-redefs [client/tools (constantly [tool])]
                    (mcp-ns/tool-namespace :demo ::client :all))]
    (doseq [stderr [["Fatal: unable to open database" "  detail with whitespace  "]
                   [] nil]]
      (with-redefs [client/call-tool!
                    (fn [& _]
                      (throw (ex-info "MCP stdio server closed stdout"
                                      {:type :mcp-stdio-error :stderr stderr})))]
        (is (= {:ok false :out nil
                :err (if (seq stderr)
                       "MCP stdio server closed stdout\nMCP stderr:\nFatal: unable to open database\n  detail with whitespace  "
                       "MCP stdio server closed stdout")}
               ((:echo generated) {})))))))
