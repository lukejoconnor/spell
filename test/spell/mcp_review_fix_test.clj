(ns spell.mcp-review-fix-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.http :as http]
            [spell.mcp.client :as client]
            [spell.mcp.http :as mcp-http]
            [spell.mcp.protocol :as protocol]
            [spell.mcp.stdio :as stdio])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.io BufferedWriter ByteArrayInputStream StringWriter]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient$Version HttpHeaders HttpResponse HttpTimeoutException]
           [java.util Optional]
           [java.util.concurrent Flow$Subscription]
           [java.util.function BiPredicate]))

(deftest bounded-http-body-cancellation-reaches-subscription-test
  (doseq [cancel-before-subscribe? [false true]]
    (let [{:keys [handler cancel!]} (#'spell.http/bounded-body-handler 20)
          subscriber (.apply handler nil)
          cancelled? (atom false)
          incoming (reify Flow$Subscription
                     (request [_ _])
                     (cancel [_] (reset! cancelled? true)))]
      (when cancel-before-subscribe? (cancel!))
      (.onSubscribe subscriber incoming)
      (when-not cancel-before-subscribe? (cancel!))
      (is @cancelled?)
      (is (.isCancelled (.toCompletableFuture (.getBody subscriber)))))))

(deftest http-timeout-covers-stalled-response-body-test
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        release-server (promise)
        request (atom nil)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (try
                          (slurp (.getRequestBody exchange))
                          (.sendResponseHeaders exchange 200 0)
                          (.write (.getResponseBody exchange) (.getBytes "{" "UTF-8"))
                          (.flush (.getResponseBody exchange))
                          @release-server
                          (finally (.close exchange))))))
    (.start server)
    (try
      (reset! request
              (future
                (try
                  (http/post-json {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                                   :body "{}" :timeout-sec 1})
                  (catch Exception e e))))
      (is (instance? HttpTimeoutException (deref @request 3000 ::still-blocked)))
      (finally
        (deliver release-server true)
        (.stop server 0)
        (when @request (future-cancel @request))))))

(deftest bounded-http-body-subscriber-enforces-limit-and-decodes-utf8-test
  (doseq [[body limit expected] [["héllo" 20 "héllo"] ["oversized" 3 :response-too-large]]]
    (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
      (.createContext server "/"
                      (reify HttpHandler
                        (handle [_ exchange]
                          (slurp (.getRequestBody exchange))
                          (let [bytes (.getBytes body "UTF-8")]
                            (.sendResponseHeaders exchange 200 (alength bytes))
                            (with-open [output (.getResponseBody exchange)]
                              (.write output bytes))))))
      (.start server)
      (try
        (is (= expected
               (try
                 (:body (http/post-json
                         {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                          :body "{}" :max-response-bytes limit}))
                 (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (finally (.stop server 0))))))

(deftest request-construction-errors-redact-configured-credentials-test
  (let [secret "dummy-secret-token\n"
        error (try
                (mcp-http/send-request! {:url "http://127.0.0.1:1"
                                         :headers {"X-Secret" secret}}
                                        (protocol/request "server/discover" {}) nil)
                (catch Exception e e))]
    (is (= :mcp-http-error (:type (ex-data error))))
    (is (not (.contains (.getMessage error) "dummy-secret-token")))
    (is (.contains (.getMessage error) "[REDACTED]"))
    (is (nil? (.getCause error)))))

(defn- subscription-response [body]
  (reify HttpResponse
    (statusCode [_] 200)
    (request [_] nil)
    (previousResponse [_] (Optional/empty))
    (headers [_] (HttpHeaders/of {"Content-Type" ["text/event-stream"]}
                                (reify BiPredicate (test [_ _ _] true))))
    (body [_] body)
    (sslSession [_] (Optional/empty))
    (uri [_] (URI/create "http://example.com/mcp"))
    (version [_] HttpClient$Version/HTTP_1_1)))

(deftest subscription-bounds-all-event-bytes-before-retaining-them-test
  (doseq [payload [(str "data: " (apply str (repeat 1000 "x")))
                   (str ":" (apply str (repeat 1000 "x")))
                   (apply str (repeat 100 "data:\n"))
                   (str ":" (apply str (repeat 100 "é")))]]
    (let [closed? (atom false)
          body (proxy [ByteArrayInputStream] [(.getBytes payload "UTF-8")]
                 (close [] (reset! closed? true) (proxy-super close)))
          error (with-redefs [http/send-request (fn [& _] (subscription-response body))]
                  (try
                    (mcp-http/listen! {:url "http://example.com/mcp" :max-response-bytes 128}
                                      (protocol/request "subscriptions/listen" {}) (fn [_]))
                    (catch Exception e e)))]
      (is (= :response-too-large (:type (ex-data error))))
      (is @closed?)))
  (let [body (ByteArrayInputStream. (.getBytes (apply str (repeat 1000 "x")) "UTF-8"))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (http/read-sse-line! body (volatile! false) 128 128)))
    (is (= 871 (.available body)) "reject after reading only the first excess byte")))

(deftest subscription-line-endings-and-multiline-events-test
  (doseq [newline ["\n" "\r" "\r\n"]]
    (let [payload (str/join newline
                            [": heartbeat" "" "data: {\"jsonrpc\":\"2.0\",\"id\":\"request\","
                             " " "data: \"result\":{\"resultType\":\"complete\",\"text\":\"é\"}}"
                             "" ""])
          body (ByteArrayInputStream. (.getBytes payload "UTF-8"))
          result (with-redefs [http/send-request (fn [& _] (subscription-response body))]
                   (mcp-http/listen! {:url "http://example.com/mcp" :max-response-bytes 256}
                                     (protocol/request "subscriptions/listen" {} {:id "request"})
                                     (fn [_])))]
      (is (= {"resultType" "complete" "text" "é"} result)))))

(deftest http-client-is-created-once-and-shared-by-calls-and-listen-test
  (let [shared-client (http/make-client)
        make-count (atom 0)
        observed-clients (atom [])]
    (with-redefs [http/make-client
                  (fn [& _]
                    (swap! make-count inc)
                    shared-client)
                  mcp-http/send-request!
                  (fn [config _message _tool]
                    (swap! observed-clients conj [:call (:client config)])
                    {"supportedVersions" [protocol/protocol-version]
                     "capabilities" {}})
                  mcp-http/listen!
                  (fn [config _message on-notification _on-open _on-close]
                    (swap! observed-clients conj [:listen (:client config)])
                    (on-notification
                     {"method" "notifications/subscriptions/acknowledged"})
                    {"resultType" "complete"})]
      (with-open [mcp-client
                  (client/open-client :demo
                                      {:transport {:http {:url "https://example.com/mcp"}}})]
        (client/discover! mcp-client)
        (client/listen! mcp-client {"toolsListChanged" true} (fn [_]))))
    (is (= 1 @make-count))
    (is (= [:call :listen] (mapv first @observed-clients)))
    (is (every? #(identical? shared-client (second %)) @observed-clients))))

(deftest pagination-cache-metadata-is-conservative-across-pages-test
  (let [responses (atom [{"items" [1]
                          "nextCursor" "second"
                          "ttlMs" 9000
                          "cacheScope" "public"}
                         {"items" [2]
                          "ttlMs" 4000
                          "cacheScope" "private"}])
        clock (atom [1000 5000])]
    (with-redefs-fn
      {#'spell.mcp.client/send!
       (fn [_ _ _]
         (let [response (first @responses)]
           (swap! responses subvec 1)
           response))
       #'spell.mcp.client/now-ms
       (fn []
         (let [value (first @clock)]
           (swap! clock subvec 1)
           value))}
      #(is (= {:items [1 2]
               :cache {"ttlMs" 4000 "cacheScope" "private"}
               :cache-expires-at 9000}
              (#'spell.mcp.client/paginate! nil "items/list" "items")))))
  (let [responses (atom [{"items" [1] "nextCursor" "second" "ttlMs" 9000}
                         {"items" [2]}])
        clock (atom [1000 5000])]
    (with-redefs-fn
      {#'spell.mcp.client/send!
       (fn [_ _ _]
         (let [response (first @responses)]
           (swap! responses subvec 1)
           response))
       #'spell.mcp.client/now-ms
       (fn []
         (let [value (first @clock)]
           (swap! clock subvec 1)
           value))}
      #(is (= {:items [1 2] :cache {} :cache-expires-at nil}
              (#'spell.mcp.client/paginate! nil "items/list" "items"))))))

(deftest reusable-catalog-deadline-includes-discovery-and-every-page-test
  (let [catalog {:discovery {"ttlMs" 10000}
                 :cache {:tools {"ttlMs" 9000}
                         :resources {"ttlMs" 4000}
                         :prompts nil}
                 :cache-expiries {:discovery 11000
                                  :tools 10000
                                  :resources 9000}}]
    (is (= 9000 (#'spell.mcp.client/catalog-expires-at catalog)))
    (is (nil? (#'spell.mcp.client/catalog-expires-at
               (assoc-in catalog [:cache :resources] {}))))))

(deftest non-2xx-subscription-always-raises-status-error-and-closes-body-test
  (let [closed? (atom false)
        request-id "listen-request"
        response-body (protocol/json-encode
                       {"jsonrpc" "2.0"
                        "id" request-id
                        "result" {"resultType" "complete"}})
        body (proxy [ByteArrayInputStream]
               [(.getBytes response-body "UTF-8")]
               (close []
                 (reset! closed? true)
                 (proxy-super close)))
        headers (HttpHeaders/of
                 {"Content-Type" ["application/json"]}
                 (reify BiPredicate
                   (test [_ _ _] true)))
        response (reify HttpResponse
                   (statusCode [_] 503)
                   (request [_] nil)
                   (previousResponse [_] (Optional/empty))
                   (headers [_] headers)
                   (body [_] body)
                   (sslSession [_] (Optional/empty))
                   (uri [_] (URI/create "https://example.com/mcp"))
                   (version [_] HttpClient$Version/HTTP_1_1))
        message {"jsonrpc" "2.0"
                 "id" request-id
                 "method" "subscriptions/listen"
                 "params" {}}
        error (with-redefs [http/send-request (fn [& _] response)]
                (try
                  (mcp-http/listen! {:url "https://example.com/mcp"}
                                    message (fn [_]))
                  nil
                  (catch clojure.lang.ExceptionInfo e e)))]
    (is (= :mcp-http-error (:type (ex-data error))))
    (is (= 503 (:status (ex-data error))))
    (is (= {"resultType" "complete"} (:result (ex-data error))))
    (is @closed?)))

(deftest invalid-first-stdio-subscription-notification-aborts-immediately-test
  (let [output (StringWriter.)
        transport (stdio/->StdioTransport nil (BufferedWriter. output)
                                          (atom {}) (atom #{}) (atom [])
                                          (Object.) (atom false) [])
        mcp-client (client/map->MCPClient
                    {:alias :demo
                     :transport-type :stdio
                     :transport-config {:subscription-timeout-ms 60000}
                     :stdio-transport transport
                     :subscription-streams (atom #{})
                     :subscription-ids (atom #{})
                     :closed? (atom false)
                     :lifecycle-lock (Object.)})
        delivered (atom [])
        listening (future
                    (try
                      (client/listen! mcp-client {"toolsListChanged" true}
                                      #(swap! delivered conj %))
                      nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
        [listener request-id]
        (loop [attempt 0]
          (let [listener (first @(:listeners transport))
                request-id (first (keys @(:pending transport)))]
            (if (and listener request-id)
              [listener request-id]
              (if (< attempt 100)
                (do (Thread/sleep 5) (recur (inc attempt)))
                (throw (ex-info "stdio subscription request was not registered" {}))))))]
    (listener {"jsonrpc" "2.0"
               "method" "notifications/tools/list_changed"
               "params" {"_meta" {"io.modelcontextprotocol/subscriptionId"
                                      request-id}}})
    (is (= :invalid-mcp-subscription-stream (deref listening 1000 ::timed-out)))
    (is (empty? @delivered))
    (is (= "notifications/cancelled"
           (get (protocol/json-decode (last (str/split-lines (str output)))) "method")))
    (listener {"jsonrpc" "2.0"
               "method" "notifications/subscriptions/acknowledged"
               "params" {"_meta" {"io.modelcontextprotocol/subscriptionId"
                                      request-id}}})
    (is (empty? @delivered) "a late acknowledgement must not revive the subscription")))

(deftest configured-header-names-must-be-case-insensitively-unique-test
  (is (= :duplicate-mcp-header
         (try
           (mcp-http/resolve-auth-headers
            {:headers (array-map "X-Role" "admin" "x-role" "user")})
           nil
           (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))

(deftest header-mismatch-retry-uses-refreshed-tool-test
  (let [old-tool {"name" "demo"
                  "inputSchema" {"type" "object"
                                 "properties" {"value" {"type" "string"
                                                          "x-mcp-header" "Old"}}}}
        new-tool {"name" "demo"
                  "inputSchema" {"type" "object"
                                 "properties" {"value" {"type" "string"
                                                          "x-mcp-header" "New"}}}}
        active-tool (atom old-tool)
        sent-tools (atom [])
        calls (atom 0)
        mcp-client (client/map->MCPClient {:alias :demo :transport-type :http})]
    (with-redefs-fn
      {#'spell.mcp.client/find-tool (fn [_ _] @active-tool)
       #'spell.mcp.client/refresh! (fn [_] (reset! active-tool new-tool))
       #'spell.mcp.client/send!
       (fn [_ _ _ tool]
         (swap! sent-tools conj tool)
         (if (= 1 (swap! calls inc))
           (throw (ex-info "HeaderMismatch"
                           {:type :mcp-json-rpc-error :code -32020}))
           {"resultType" "complete" "structuredContent" {"ok" true}}))}
      #(is (= {"ok" true}
              (get (client/call-tool-raw! mcp-client "demo" {"value" "x"})
                   "structuredContent"))))
    (is (= [old-tool new-tool] @sent-tools))))

(deftest subscription-errors-redact-configured-credentials-test
  (let [secret "subscription-secret"
        message (protocol/request "subscriptions/listen"
                                  {"notifications" {"toolsListChanged" true}})
        error (with-redefs [http/send-request
                            (fn [& _]
                              (throw (ex-info (str "server reflected " secret)
                                              {:reflected secret})))]
                (try
                  (mcp-http/listen! {:url "https://example.com/mcp"
                                     :headers {"X-Secret" secret}}
                                    message (fn [_]))
                  nil
                  (catch clojure.lang.ExceptionInfo e e)))]
    (is (some? error))
    (is (not (.contains (.getMessage error) secret)))
    (is (= "[REDACTED]" (:reflected (ex-data error))))
    (is (nil? (.getCause error)) "secret-bearing causes must not escape redaction")))

(deftest non-sse-subscription-response-closes-body-test
  (let [closed? (atom false)
        body (proxy [java.io.ByteArrayInputStream] [(.getBytes "{}" "UTF-8")]
               (close [] (reset! closed? true) (proxy-super close)))]
    (is (= :unsupported-content-type
           (try
             (#'spell.mcp.http/require-sse! "application/json" body)
             nil
             (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (is @closed?)))

(deftest closing-client-during-http-subscription-open-closes-stream-test
  (let [opened (promise)
        continue (promise)
        stream-closed? (atom false)
        stream (reify java.io.Closeable
                 (close [_] (reset! stream-closed? true)))
        mcp-client (client/map->MCPClient
                    {:alias :demo
                     :transport-type :http
                     :transport-config {}
                     :subscription-streams (atom #{})
                     :subscription-ids (atom #{})
                     :closed? (atom false)
                     :lifecycle-lock (Object.)})]
    (with-redefs [mcp-http/listen!
                  (fn [_ _ _ on-open _]
                    (deliver opened true)
                    @continue
                    (on-open stream))]
      (let [listener (future
                       (try
                         (client/listen! mcp-client {"toolsListChanged" true} (fn [_]))
                         nil
                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
        @opened
        (.close ^java.io.Closeable mcp-client)
        (deliver continue true)
        (is (= :mcp-client-closed @listener))))
    (is @stream-closed?)
    (is (empty? @(:subscription-streams mcp-client)))))

(deftest model-facing-values-have-one-aggregate-bound-test
  (let [large-text (apply str (repeat 10000 "x"))
        blocks (vec (repeat 100 {"type" "text" "text" large-text}))
        resources (vec (repeat 100 {"uri" "memory://large" "text" large-text}))
        messages (vec (repeat 100 {"role" "user"
                                   "content" {"type" "text" "text" large-text}}))]
    (doseq [[label value]
            [["tool" (protocol/model-value :demo "large" {"content" blocks})]
             ["resource" (protocol/model-resource-value :demo {"contents" resources})]
             ["prompt" (protocol/model-prompt-value :demo {"messages" messages})]]]
      (testing label
        (is (<= (count (protocol/json-encode value)) protocol/max-model-total-chars))
        (is (or (true? (get value "omitted"))
                (some #(true? (get % "omitted"))
                      (or (get value "content")
                          (get value "contents")
                          (get value "messages")))))))))
