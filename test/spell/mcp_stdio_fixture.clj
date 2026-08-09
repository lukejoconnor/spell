(ns spell.mcp-stdio-fixture
  (:require [clojure.data.json :as json]))

(def ^:private write-lock (Object.))

(defn- complete [fields]
  (merge {"resultType" "complete" "ttlMs" 0 "cacheScope" "private"} fields))

(defn- write-message! [message]
  (locking write-lock
    (println (json/write-str message))
    (flush)))

(defn- response [request result]
  {"jsonrpc" "2.0" "id" (get request "id") "result" result})

(defn- handle! [request]
  (let [method (get request "method")
        params (get request "params")]
    (case method
      "server/discover"
      (write-message!
       (response request
                 (complete {"supportedVersions" ["2026-07-28"]
                            "capabilities" {"tools" {} "subscriptions" {}}
                            "_meta" {"io.modelcontextprotocol/serverInfo"
                                     {"name" "stdio-fixture" "version" "1"}}})))

      "tools/list"
      (write-message!
       (response request
                 (complete {"tools"
                            [{"name" "delayed_echo"
                              "description" "Echo after an optional delay"
                              "inputSchema" {"type" "object"
                                             "properties" {"value" {"type" "string"}
                                                           "delayMs" {"type" "integer"}}
                                             "required" ["value"]}
                              "outputSchema" {"type" "object"
                                              "properties" {"value" {"type" "string"}}
                                              "required" ["value"]}}
                             {"name" "env_probe"
                              "description" "Exercise fixture environment isolation"
                              "inputSchema" {"type" "object"}
                              "outputSchema" {"type" "object"
                                              "properties" {"value" {"type" "string"}
                                                            "inheritedApiKey" {"type" "boolean"}}
                                              "required" ["value" "inheritedApiKey"]}}]})))

      "tools/call"
      (if (= "env_probe" (get params "name"))
        (let [secret (or (System/getenv "MCP_SECRET") "missing")]
          (binding [*out* *err*]
            (println secret)
            (flush))
          (write-message!
           (response request
                     (complete {"content" [{"type" "text" "text" secret}]
                                "structuredContent"
                                {"value" secret
                                 "inheritedApiKey" (boolean (System/getenv "ANTHROPIC_API_KEY"))}
                                "isError" false}))))
        (future
          (Thread/sleep (long (get-in params ["arguments" "delayMs"] 0)))
          (let [value (get-in params ["arguments" "value"])]
            (write-message!
             (response request
                       (complete {"content" [{"type" "text" "text" value}]
                                  "structuredContent" {"value" value}
                                  "isError" false}))))))

      "subscriptions/listen"
      (do
        (write-message!
         {"jsonrpc" "2.0"
          "method" "notifications/subscriptions/acknowledged"
          "params" {"notifications" (get params "notifications")
                    "_meta" {"io.modelcontextprotocol/subscriptionId"
                             (get request "id")}}})
        (write-message!
         {"jsonrpc" "2.0"
          "method" "notifications/tools/list_changed"
          "params" {"_meta" {"io.modelcontextprotocol/subscriptionId"
                               (get request "id")}}})
        (write-message! (response request (complete {}))))

      "notifications/cancelled" nil
      (write-message! (response request (complete {}))))))

(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (handle! (json/read-str line))))
