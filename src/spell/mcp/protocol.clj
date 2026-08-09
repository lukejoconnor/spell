(ns spell.mcp.protocol
  "Pure MCP 2026-07-28 request, header, response, and rendering helpers."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [spell.mcp.schema :as schema])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64 UUID]))

(def protocol-version "2026-07-28")
(def client-info {"name" "Spell" "version" "0.2.0-dev"})
(def max-model-text-chars 200000)
(def max-model-structured-chars 500000)
(def max-model-total-chars 500000)
(def max-model-content-blocks 128)
(def max-model-prompt-messages 128)
(def max-model-completion-values 1000)
(def required-meta-keys
  ["io.modelcontextprotocol/protocolVersion"
   "io.modelcontextprotocol/clientInfo"
   "io.modelcontextprotocol/clientCapabilities"])

(defn request-meta
  ([] (request-meta nil))
  ([capabilities]
   {"io.modelcontextprotocol/protocolVersion" protocol-version
    "io.modelcontextprotocol/clientInfo" client-info
    "io.modelcontextprotocol/clientCapabilities" (or capabilities {})}))

(defn request
  ([method params] (request method params nil))
  ([method params {:keys [id capabilities]}]
   {"jsonrpc" "2.0"
    "id" (or id (str (UUID/randomUUID)))
    "method" (str method)
    "params" (assoc (or params {}) "_meta" (request-meta capabilities))}))

(defn notification
  [method params]
  {"jsonrpc" "2.0"
   "method" (str method)
   "params" (assoc (or params {}) "_meta" (request-meta))})

(defn json-encode [value] (json/write-str value))
(defn json-decode [text] (json/read-str text))

(defn- sentinel? [value]
  (and (str/starts-with? value "=?base64?")
       (str/ends-with? value "?=")))

(defn safe-header-value?
  [value]
  (and (= value (str/trim value))
       (not (sentinel? value))
       (every? #(or (= 9 (int %)) (<= 32 (int %) 126)) value)))

(defn encode-header-value
  [value]
  (let [value (str value)]
    (if (safe-header-value? value)
      value
      (str "=?base64?"
           (.encodeToString (Base64/getEncoder)
                            (.getBytes value StandardCharsets/UTF_8))
           "?="))))

(defn request-name
  [message]
  (let [method (get message "method")
        params (get message "params")]
    (case method
      "tools/call" (get params "name")
      "resources/read" (get params "uri")
      "prompts/get" (get params "name")
      nil)))

(defn standard-http-headers
  [message]
  (cond-> {"Accept" "application/json, text/event-stream"
           "MCP-Protocol-Version" protocol-version
           "Mcp-Method" (get message "method")}
    (request-name message)
    (assoc "Mcp-Name" (encode-header-value (request-name message)))))

(def ^:private tchar-pattern #"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
(def ^:private allowed-header-types #{"string" "integer" "boolean"})

(defn x-mcp-header-fields
  "Return [{:path [...] :header-name ...}] or throw if annotations are invalid."
  [input-schema]
  (let [found (volatile! [])]
    (letfn [(visit [node path reachable? root?]
              (when (map? node)
                (when-let [header-name (get node "x-mcp-header")]
                  (when-not reachable?
                    (throw (ex-info "x-mcp-header is only valid on a properties-only path"
                                    {:type :invalid-x-mcp-header :path path})))
                  (let [type-name (get node "type")]
                    (when-not (and (string? header-name)
                                   (re-matches tchar-pattern header-name))
                      (throw (ex-info "Invalid x-mcp-header name"
                                      {:type :invalid-x-mcp-header
                                       :header-name header-name :path path})))
                    (when-not (contains? allowed-header-types type-name)
                      (throw (ex-info "x-mcp-header requires string, integer, or boolean"
                                      {:type :invalid-x-mcp-header
                                       :header-name header-name :schema-type type-name :path path})))
                    (vswap! found conj {:path path :header-name header-name
                                        :schema-type type-name})))
                (doseq [[property child] (get node "properties")]
                  (visit child (conj path property) (or root? reachable?) false))
                (doseq [[key child] node
                        :when (and (not= key "properties") (map? child))]
                  (visit child path false false))
                (doseq [[key children] node
                        :when (and (not= key "properties") (sequential? children))
                        child children
                        :when (map? child)]
                  (visit child path false false))))]
      (visit input-schema [] false true)
      (let [fields @found
            duplicates (->> fields
                            (group-by #(str/lower-case (:header-name %)))
                            (keep (fn [[name xs]] (when (> (count xs) 1) name)))
                            seq)]
        (when duplicates
          (throw (ex-info "x-mcp-header names must be case-insensitively unique"
                          {:type :invalid-x-mcp-header :duplicates (vec duplicates)})))
        fields))))

(defn valid-http-tool?
  [tool]
  (try
    (x-mcp-header-fields (get tool "inputSchema"))
    true
    (catch Exception _ false)))

(defn- get-path [value path]
  (reduce (fn [node key]
            (if (map? node) (get node key) nil))
          value path))

(defn- primitive-header-value [value]
  (cond
    (string? value) value
    (integer? value) (if (<= -9007199254740991 value 9007199254740991)
                       (str value)
                       (throw (ex-info "x-mcp-header integer exceeds the JavaScript safe range"
                                       {:type :invalid-x-mcp-header-value})))
    (true? value) "true"
    (false? value) "false"
    :else nil))

(defn tool-parameter-headers
  [tool arguments]
  (into {}
        (keep (fn [{:keys [path header-name]}]
                (let [value (get-path arguments path)]
                  (when (some? value)
                    (let [encoded (or (primitive-header-value value)
                                      (throw (ex-info "x-mcp-header value is not primitive"
                                                      {:type :invalid-x-mcp-header-value
                                                       :path path})))]
                      [(str "Mcp-Param-" header-name)
                       (encode-header-value encoded)]))))
        (x-mcp-header-fields (get tool "inputSchema")))))

(defn http-headers
  [message tool]
  (merge (standard-http-headers message)
         (when (and tool (= "tools/call" (get message "method")))
           (tool-parameter-headers tool (get-in message ["params" "arguments"])))))

(defn parse-response
  [text expected-id]
  (let [message (try
                  (json-decode text)
                  (catch Exception e
                    (throw (ex-info "MCP server returned invalid JSON"
                                    {:type :invalid-json-response} e))))]
    (when-not (= "2.0" (get message "jsonrpc"))
      (throw (ex-info "MCP response is not JSON-RPC 2.0"
                      {:type :invalid-json-rpc :response message})))
    (when-not (= expected-id (get message "id"))
      (throw (ex-info "MCP response ID did not match request"
                      {:type :response-id-mismatch
                       :expected expected-id :actual (get message "id")})))
    (when-let [error (get message "error")]
      (throw (ex-info (or (get error "message") "MCP JSON-RPC error")
                      {:type :mcp-json-rpc-error
                       :code (get error "code")
                       :data (get error "data")})))
    (let [result (get message "result")
          result-type (get result "resultType")]
      (case result-type
        "input_required"
        (throw (ex-info "MCP server requested unsupported multi-round-trip input"
                        {:type :unsupported-mrtr :result result}))

        "task"
        (throw (ex-info "MCP server returned an unsupported Tasks result"
                        {:type :unsupported-tasks :result result}))

        "complete" result

        (throw (ex-info "MCP response has an unsupported or missing resultType"
                        {:type :unsupported-result-type
                         :result-type result-type :result result}))))))

(defn parse-sse-response
  [event-data expected-id]
  (let [messages (mapv json-decode event-data)
        response (some #(when (contains? % "id") %) messages)
        notifications (filterv #(and (nil? (get % "id")) (get % "method")) messages)]
    (when-not response
      (throw (ex-info "MCP SSE stream ended without a JSON-RPC response"
                      {:type :missing-sse-response
                       :notifications notifications})))
    {:result (parse-response (json-encode response) expected-id)
     :notifications notifications}))

(defn validate-tool!
  [tool transport]
  (let [name (get tool "name")
        input-schema (get tool "inputSchema")]
    (when-not (and (string? name) (<= 1 (count name) 128))
      (throw (ex-info "MCP tool has an invalid name"
                      {:type :invalid-tool :tool name})))
    (schema/compile-schema input-schema)
    (when-let [output-schema (get tool "outputSchema")]
      (schema/compile-schema output-schema))
    (when (= transport :http)
      (x-mcp-header-fields input-schema))
    tool))

(defn text-content
  [result]
  (->> (get result "content")
       (keep #(when (= "text" (get % "type")) (get % "text")))
       (str/join "\n")))

(defn- bounded-string [value limit]
  (if (> (count value) limit)
    (str (subs value 0 limit) "\n… [truncated " (- (count value) limit) " characters]")
    value))

(defn bounded-text-content
  [result]
  (bounded-string (text-content result) max-model-text-chars))

(defn- bounded-block [block]
  (let [rendered
        (case (get block "type")
          "text" (update block "text" #(bounded-string (or % "") max-model-text-chars))
          "image" (-> block (dissoc "data")
                      (assoc "bytes" (quot (* 3 (count (get block "data" ""))) 4)))
          "audio" (-> block (dissoc "data")
                      (assoc "bytes" (quot (* 3 (count (get block "data" ""))) 4)))
          "resource" (let [resource (get block "resource")]
                       (if (contains? resource "blob")
                         (assoc block "resource"
                                (-> resource (dissoc "blob")
                                    (assoc "bytes"
                                           (quot (* 3 (count (get resource "blob" ""))) 4))))
                         block))
          block)
        chars (count (json-encode rendered))]
    (if (> chars max-model-structured-chars)
      {"type" (or (get block "type") "unknown")
       "omitted" true
       "reason" "model rendering limit"
       "jsonChars" chars}
      rendered)))

(defn bounded-content
  [result]
  (loop [remaining (seq (get result "content" []))
         rendered []
         rendered-chars 0
         block-count 0]
    (cond
      (nil? remaining)
      rendered

      (>= block-count max-model-content-blocks)
      (conj rendered {"type" "omitted"
                      "omitted" true
                      "reason" "model block-count limit"
                      "omittedBlocks" (count remaining)})

      :else
      (let [block (bounded-block (first remaining))
            block-chars (count (json-encode block))]
        (if (> (+ rendered-chars block-chars) max-model-total-chars)
          (conj rendered {"type" "omitted"
                          "omitted" true
                          "reason" "aggregate model rendering limit"
                          "omittedBlocks" (count remaining)})
          (recur (next remaining) (conj rendered block)
                 (+ rendered-chars block-chars) (inc block-count)))))))

(defn- enforce-model-total [value]
  (let [chars (count (json-encode value))]
    (if (<= chars max-model-total-chars)
      value
      (merge (select-keys value ["mcp/server" "mcp/operation" "isError"])
             {"omitted" true
              "reason" "aggregate model rendering limit"
              "jsonChars" chars}))))

(defn model-value
  [server operation result]
  (let [semantic-error? (true? (get result "isError"))
        structured? (contains? result "structuredContent")
        structured (get result "structuredContent")
        structured-size (when structured? (count (json-encode structured)))
        text (bounded-text-content result)
        content (bounded-content result)]
    (enforce-model-total
     (cond-> {"mcp/server" (name server)
              "mcp/operation" operation}
       (and structured? (<= structured-size max-model-structured-chars))
       (assoc "structuredContent" structured)
       (and structured? (> structured-size max-model-structured-chars))
       (assoc "structuredContentOmitted"
              {"reason" "model rendering limit" "jsonChars" structured-size})
       (seq content) (assoc "content" content)
       semantic-error? (assoc "isError" true "error" (if (str/blank? text)
                                                         "MCP tool call failed"
                                                         text))))))

(defn model-resource-value [server result]
  (let [content (bounded-content
                 {"content" (mapv #(hash-map "type" "resource" "resource" %)
                                   (get result "contents" []))})]
    (enforce-model-total
     {"mcp/server" (name server)
      "mcp/operation" "resources/read"
      "contents" (mapv #(or (get % "resource") %) content)})))

(defn model-prompt-value [server result]
  (let [messages (get result "messages" [])
        limited (take max-model-prompt-messages messages)
        rendered (bounded-content
                  {"content" (mapv #(hash-map "type" "prompt-message"
                                              "message" %)
                                    limited)})
        rendered (mapv #(or (get % "message") %) rendered)
        rendered (cond-> rendered
                   (> (count messages) max-model-prompt-messages)
                   (conj {"omitted" true
                          "reason" "model message-count limit"
                          "omittedMessages" (- (count messages)
                                                max-model-prompt-messages)}))]
    (enforce-model-total
     {"mcp/server" (name server)
      "mcp/operation" "prompts/get"
      "description" (some-> (get result "description")
                              (bounded-string max-model-text-chars))
      "messages" rendered})))

(defn- bounded-completion-values [values]
  (loop [remaining (seq values)
         rendered []
         rendered-chars 0
         value-count 0]
    (cond
      (nil? remaining) rendered
      (>= value-count max-model-completion-values)
      (conj rendered (str "[… omitted " (count remaining) " completion values]"))
      :else
      (let [value (first remaining)
            value (if (string? value) (bounded-string value max-model-text-chars) value)
            value-chars (count (json-encode value))]
        (if (> (+ rendered-chars value-chars) max-model-total-chars)
          (conj rendered (str "[… omitted " (count remaining) " completion values]"))
          (recur (next remaining) (conj rendered value)
                 (+ rendered-chars value-chars) (inc value-count)))))))

(defn model-completion-value [server result]
  (let [completion (get result "completion" {})]
    (enforce-model-total
     {"mcp/server" (name server)
      "mcp/operation" "completion/complete"
      "completion" (update completion "values"
                           #(bounded-completion-values (or % [])))})))

(defn model-info-value [server discovery]
  (enforce-model-total
   (cond-> (assoc (select-keys discovery
                               ["supportedVersions" "capabilities" "_meta" "ttlMs" "cacheScope"
                                "catalogCache" "excludedTools"])
                  "mcp/server" (name server)
                  "mcp/operation" "server/discover")
     (string? (get discovery "instructions"))
     (assoc "instructions" (bounded-string (get discovery "instructions")
                                           max-model-text-chars)))))
