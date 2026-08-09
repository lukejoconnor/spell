(ns spell.mcp-interop-test
  (:require [clojure.test :refer [deftest is]]
            [spell.mcp.client :as client])
  (:import [java.net ServerSocket]
           [java.util.concurrent TimeUnit]))

(def command
  ["uv" "run" "--project" "test/interop" "--frozen"
   "python" "test/interop/mcp_sdk_server.py"])

(defn- assert-fixture! [c]
  (is (= ["echo"] (mapv #(get % "name") (client/tools c))))
  (is (= "works"
         (get-in (client/call-tool! c "echo" {"value" "works"})
                 ["structuredContent" "value"])))
  (is (= ["review"] (mapv #(get % "name") (client/prompts c))))
  (is (= ["memory://item/{item_id}"]
         (mapv #(get % "uriTemplate") (client/resource-templates c)))))

(deftest official-python-sdk-stdio-test
  (with-open [c (client/open-client
                 :official-sdk
                 {:transport
                  {:stdio
                   {:command command}}})]
    (assert-fixture! c)))

(deftest official-python-sdk-http-test
  (let [port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.redirectErrorStream true))
        _ (.put (.environment builder) "SPELL_MCP_INTEROP_TRANSPORT" "streamable-http")
        _ (.put (.environment builder) "SPELL_MCP_INTEROP_PORT" (str port))
        process (.start builder)
        config {:transport {:http {:url (str "http://127.0.0.1:" port "/mcp")}}
                :timeout-sec 5}]
    (try
      (loop [attempt 0]
        (when (>= attempt 100)
          (throw (ex-info "Official SDK HTTP fixture did not start" {:port port})))
        (let [ready? (try
                       (with-open [c (client/open-client :official-sdk-http config)]
                         (client/info c)
                         true)
                       (catch Exception _ false))]
          (if ready?
            (with-open [c (client/open-client :official-sdk-http config)]
              (assert-fixture! c))
            (do (Thread/sleep 100) (recur (inc attempt))))))
      (finally
        (.destroy process)
        (when-not (.waitFor process 2 TimeUnit/SECONDS)
          (.destroyForcibly process))))))
