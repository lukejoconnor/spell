(ns spell.mcp-review-fix-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.http :as http]
            [spell.mcp.client :as client]
            [spell.mcp.http :as mcp-http]
            [spell.mcp.protocol :as protocol]))

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
