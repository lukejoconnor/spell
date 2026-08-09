(ns spell.mcp-protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [spell.mcp.protocol :as protocol]
            [spell.mcp.schema :as schema]
            [spell.mcp.http :as mcp-http]
            [spell.http :as http]))

(deftest exact-request-metadata-test
  (let [request (protocol/request "tools/list" {})]
    (is (= "2026-07-28"
           (get-in request ["params" "_meta" "io.modelcontextprotocol/protocolVersion"])))
    (is (= {} (get-in request ["params" "_meta"
                               "io.modelcontextprotocol/clientCapabilities"])))
    (is (not= "initialize" (get request "method")))))

(deftest header-encoding-test
  (is (= "plain" (protocol/encode-header-value "plain")))
  (is (= "=?base64?SGVsbG8sIOS4lueVjA==?="
         (protocol/encode-header-value "Hello, 世界")))
  (is (= "=?base64?IHBhZGRlZCA=?="
         (protocol/encode-header-value " padded "))))

(deftest standard-and-parameter-headers-test
  (let [tool {"inputSchema" {"type" "object"
                             "properties" {"region" {"type" "string"
                                                       "x-mcp-header" "Region"}}}}
        request (protocol/request "tools/call"
                                  {"name" "execute_sql"
                                   "arguments" {"region" "us-west1"}})
        headers (protocol/http-headers request tool)]
    (is (= "2026-07-28" (get headers "MCP-Protocol-Version")))
    (is (= "tools/call" (get headers "Mcp-Method")))
    (is (= "execute_sql" (get headers "Mcp-Name")))
    (is (= "us-west1" (get headers "Mcp-Param-Region")))))

(deftest invalid-x-mcp-header-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid x-mcp-header"
                        (protocol/x-mcp-header-fields
                         {"type" "object"
                          "properties" {"x" {"type" "object"
                                               "x-mcp-header" "Bad Header"}}})))
  (doseq [schema
          [{"type" "object"
            "properties" {"x" {"allOf" [{"type" "string"
                                             "x-mcp-header" "X"}]}}}
           {"type" "object"
            "$defs" {"x" {"type" "string" "x-mcp-header" "X"}}
            "properties" {"x" {"$ref" "#/$defs/x"}}}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"properties-only"
                          (protocol/x-mcp-header-fields schema))))
  (is (= [{:path ["outer" "inner"] :header-name "Nested" :schema-type "integer"}]
         (protocol/x-mcp-header-fields
          {"type" "object"
           "properties" {"outer" {"type" "object"
                                      "properties" {"inner" {"type" "integer"
                                                               "x-mcp-header" "Nested"}}}}}))))

(deftest response-result-types-test
  (let [id "1"]
    (is (= {"resultType" "complete" "value" 42}
           (protocol/parse-response
            (protocol/json-encode {"jsonrpc" "2.0" "id" id
                                   "result" {"resultType" "complete" "value" 42}})
            id)))
    (is (= :unsupported-mrtr
           (try
             (protocol/parse-response
              (protocol/json-encode {"jsonrpc" "2.0" "id" id
                                     "result" {"resultType" "input_required"}})
              id)
             nil
             (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))

(deftest json-schema-2020-test
  (let [definition {"type" "object"
                    "$defs" {"value" {"anyOf" [{"type" "string"}
                                                 {"type" "null"}]}}
                    "properties" {"value" {"$ref" "#/$defs/value"}}
                    "required" ["value"]}]
    (is (= {"value" nil} (schema/validate! definition {"value" nil})))
    (is (= :schema-validation
           (try (schema/validate! definition {"value" 2}) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (is (= :external-schema-reference
           (try (schema/compile-schema {"$ref" "https://example.com/schema"}) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))))

(deftest network-and-secret-boundary-test
  (is (= :invalid-http-url
         (try (http/validate-http-uri "file:///etc/passwd") nil
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
  (doseq [header ["Authorization" "Mcp-Session-Id" "Mcp-Param-Anything"]]
    (is (= :reserved-mcp-header
           (try (mcp-http/resolve-auth-headers {:headers {header "secret"}}) nil
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
  (is (= :invalid-x-mcp-header-value
         (try
           (protocol/tool-parameter-headers
            {"inputSchema" {"type" "object"
                             "properties" {"n" {"type" "integer"
                                                  "x-mcp-header" "N"}}}}
            {"n" 9007199254740992})
           nil
           (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))))
