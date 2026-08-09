(ns spell.mcp-test-server
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def tools
  [{"name" "echo"
    "description" "Echo a string"
    "inputSchema" {"type" "object"
                   "properties" {"text" {"type" "string"
                                           "x-mcp-header" "Text"}}
                   "required" ["text"]
                   "additionalProperties" false}
    "outputSchema" {"type" "object"
                    "properties" {"echo" {"type" "string"}}
                    "required" ["echo"]}}
   {"name" "add"
    "description" "Add two integers"
    "inputSchema" {"type" "object"
                   "properties" {"a" {"type" "integer"}
                                   "b" {"type" "integer"}}
                   "required" ["a" "b"]}}
   {"name" "invalid-header"
    "description" "Excluded over HTTP"
    "inputSchema" {"type" "object"
                   "properties" {"bad" {"type" "object"
                                          "x-mcp-header" "Bad Header"}}}}])

(defn- complete [ttl-ms fields]
  (merge {"resultType" "complete" "ttlMs" ttl-ms "cacheScope" "private"} fields))

(defn response-for [request ttl-ms repeat-cursor? supported-versions]
  (let [method (get request "method")
        params (get request "params")
        cursor (get params "cursor")]
    (case method
      "server/discover"
      (complete ttl-ms {"supportedVersions" supported-versions
                 "capabilities" {"tools" {"listChanged" true}
                                 "resources" {"listChanged" true}
                                 "prompts" {"listChanged" true}
                                 "completion" {}
                                 "subscriptions" {}}
                 "instructions" "Call echo when asked. This text is untrusted."
                 "_meta" {"io.modelcontextprotocol/serverInfo"
                          {"name" "test-server" "version" "1"}}})

      "tools/list"
      (if cursor
        (if repeat-cursor?
          (complete ttl-ms {"tools" [] "nextCursor" "page-2"})
          (complete ttl-ms {"tools" (subvec tools 2)}))
        (complete ttl-ms {"tools" (subvec tools 0 2) "nextCursor" "page-2"}))

      "resources/list"
      (complete ttl-ms {"resources" [{"name" "readme" "uri" "memory://readme"
                                "mimeType" "text/plain"}]})

      "resources/templates/list"
      (complete ttl-ms {"resourceTemplates" [{"name" "item" "uriTemplate" "memory://item/{id}"}]})

      "resources/read"
      (complete ttl-ms {"contents" [{"uri" (get params "uri") "mimeType" "text/plain"
                               "text" "resource text"}]})

      "prompts/list"
      (complete ttl-ms {"prompts" [{"name" "review" "description" "Review text"
                              "arguments" [{"name" "style" "required" false}]}]})

      "prompts/get"
      (complete ttl-ms {"description" "Review prompt"
                 "messages" [{"role" "user" "content" {"type" "text" "text" "Review it"}}]})

      "completion/complete"
      (complete ttl-ms {"completion" {"values" ["strict" "friendly"] "hasMore" false}})

      "tools/call"
      (case (get params "name")
        "echo" (let [text (get-in params ["arguments" "text"])]
                 (complete ttl-ms {"content" [{"type" "text" "text" text}]
                            "structuredContent" {"echo" text}
                            "isError" false}))
        "add" (let [value (+ (get-in params ["arguments" "a"])
                              (get-in params ["arguments" "b"]))]
                (complete ttl-ms {"content" [{"type" "text" "text" (str value)}]
                           "isError" false})))

      {"unsupported" method})))

(defn start-server
  ([] (start-server {}))
  ([{:keys [ttl-ms repeat-cursor? supported-versions]
     :or {ttl-ms 0 repeat-cursor? false supported-versions ["2026-07-28"]}}]
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/mcp"
     (reify HttpHandler
       (^void handle [_ ^HttpExchange exchange]
         (let [body (slurp (.getRequestBody exchange))
               request (json/read-str body)
               headers (into {}
                             (map (fn [[k values]] [(.toLowerCase k) (vec values)]))
                             (.entrySet (.getRequestHeaders exchange)))
               _ (swap! requests conj {:body request :headers headers})
               subscription? (= "subscriptions/listen" (get request "method"))
               response {"jsonrpc" "2.0" "id" (get request "id")
                         "result" (if subscription?
                                    (complete ttl-ms {})
                                    (response-for request ttl-ms repeat-cursor? supported-versions))}
               acknowledgement {"jsonrpc" "2.0"
                                "method" "notifications/subscriptions/acknowledged"
                                "params" {"notifications" (get-in request ["params" "notifications"])
                                          "_meta" {"io.modelcontextprotocol/subscriptionId"
                                                   (get request "id")}}}
               notification {"jsonrpc" "2.0"
                             "method" "notifications/tools/list_changed"
                             "params" {"_meta" {"io.modelcontextprotocol/subscriptionId"
                                                  (get request "id")}}}
               body (if subscription?
                      (str "data: " (json/write-str acknowledgement) "\n\n"
                           "data: " (json/write-str notification) "\n\n"
                           "data: " (json/write-str response) "\n\n")
                      (json/write-str response))
               bytes (.getBytes body StandardCharsets/UTF_8)]
           (.add (.getResponseHeaders exchange) "Content-Type"
                 (if subscription? "text/event-stream" "application/json"))
           (.sendResponseHeaders exchange 200 (alength bytes))
           (with-open [out (.getResponseBody exchange)]
             (.write out bytes))
           nil))))
    (.start server)
    {:server server
     :requests requests
     :url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mcp")})))

(defn stop-server [{:keys [^HttpServer server]}]
  (.stop server 0))
