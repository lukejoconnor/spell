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
        namespace-map (with-redefs [client/tools (constantly tools)]
                        (mcp-ns/tool-namespace :demo ::client :all))
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
