(ns spell.mcp.http
  "Stateless MCP Streamable HTTP transport for protocol version 2026-07-28."
  (:require [clojure.string :as str]
            [spell.http :as http]
            [spell.mcp.protocol :as protocol]
            [spell.mcp.redaction :as redaction])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net.http HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration]))

(def ^:private reserved-headers
  #{"authorization" "content-type" "accept" "mcp-protocol-version" "mcp-method"
    "mcp-name" "mcp-session-id"})

(defn- env-value
  [value]
  (if (and (map? value) (:env value))
    (or (System/getenv (str (:env value)))
        (throw (ex-info (str "Missing MCP header environment variable " (:env value))
                        {:type :missing-mcp-secret :env (str (:env value))})))
    (str value)))

(defn resolve-auth-headers
  [{:keys [auth headers]}]
  (let [header-names (mapv (comp str key) headers)
        duplicate-names (->> header-names
                             (group-by str/lower-case)
                             (keep (fn [[name names]] (when (> (count names) 1) name)))
                             vec)
        _ (when (seq duplicate-names)
            (throw (ex-info "MCP custom header names must be case-insensitively unique"
                            {:type :duplicate-mcp-header
                             :headers duplicate-names})))
        custom (into {} (map (fn [[name value]] [(str name) (env-value value)])) headers)
        collision (some #(let [header (str/lower-case %)]
                           (when (or (contains? reserved-headers header)
                                     (str/starts-with? header "mcp-param-"))
                             %))
                        (keys custom))]
    (when collision
      (throw (ex-info (str "MCP custom header may not override reserved header " collision)
                      {:type :reserved-mcp-header :header collision})))
    (cond-> custom
      (:bearer-token-env auth)
      (assoc "Authorization"
             (str "Bearer "
                  (or (System/getenv (str (:bearer-token-env auth)))
                      (throw (ex-info (str "Missing MCP bearer-token environment variable "
                                           (:bearer-token-env auth))
                                      {:type :missing-mcp-secret
                                      :env (str (:bearer-token-env auth))}))))))))

(defn credential-fingerprint
  "Return a one-way credential-context identifier without retaining header values."
  [config]
  (let [headers (into (sorted-map)
                      (map (fn [[name value]] [(str/lower-case name) value]))
                      (resolve-auth-headers config))
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str headers) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- sanitized-exception [e secrets fallback-data]
  (let [message (or (redaction/redact-string (.getMessage ^Exception e) secrets)
                    "MCP request failed")
        data (redaction/redact
              (if (instance? clojure.lang.ExceptionInfo e)
                (ex-data e)
                fallback-data)
              secrets)]
    ;; A secret-bearing original cause defeats wrapper redaction for structured
    ;; exception loggers. Preserve it only when there is no credential material.
    (if (seq secrets)
      (ex-info message data)
      (ex-info message data e))))

(defn- parse-http-result
  [{:keys [status content-type body] :as response} request-id]
  (let [parse-json #(protocol/parse-response body request-id)
        result (cond
                 (= content-type "application/json")
                 (parse-json)

                 (= content-type "text/event-stream")
                 (:result (protocol/parse-sse-response
                           (keep http/sse-event-data (http/split-sse-events body))
                           request-id))

                 (str/blank? body)
                 (throw (ex-info "MCP HTTP response had no body"
                                 {:type :empty-mcp-response :status status}))

                 :else
                 (try
                   (parse-json)
                   (catch Exception e
                     (throw (ex-info "MCP HTTP response had an unsupported content type"
                                     {:type :unsupported-content-type
                                      :status status :content-type content-type} e)))))]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "MCP HTTP request failed with status " status)
                      {:type :mcp-http-error :status status :result result})))
    result))

(defn send-request!
  [config message tool]
  (let [request-id (get message "id")
        auth-headers (resolve-auth-headers config)
        secrets (redaction/secret-values (vals auth-headers))
        headers (merge auth-headers
                       (protocol/http-headers message tool))
        response (http/post-json {:client (:client config)
                                  :url (:url config)
                                  :headers headers
                                  :body (protocol/json-encode message)
                                  :timeout-sec (or (:timeout-sec config)
                                                   http/default-request-timeout-sec)
                                  :max-response-bytes (or (:max-response-bytes config)
                                                          http/default-max-response-bytes)})]
    (try
      (redaction/redact (parse-http-result response request-id) secrets)
      (catch clojure.lang.ExceptionInfo e
        (throw (sanitized-exception e secrets {:type :mcp-http-error}))))))

(defn- require-sse! [content-type ^java.io.InputStream body]
  (when-not (= content-type "text/event-stream")
    (try
      (.close body)
      (finally
        (throw (ex-info "subscriptions/listen requires an SSE response"
                        {:type :unsupported-content-type :content-type content-type}))))))

(defn listen!
  "Open a blocking subscriptions/listen SSE request and call on-notification for
   each JSON-RPC notification. Returns the final complete result."
  ([config message on-notification]
   (listen! config message on-notification (fn [_]) (fn [_])))
  ([config message on-notification on-open on-close]
   (let [auth-headers (resolve-auth-headers config)
         secrets (redaction/secret-values (vals auth-headers))]
     (try
       (let [request-id (get message "id")
             timeout-sec (or (:timeout-sec config) (* 24 60 60))
             max-event-chars (or (:max-response-bytes config) http/default-max-response-bytes)
             headers (merge auth-headers (protocol/http-headers message nil))
             uri (http/validate-http-uri (:url config))
             builder (doto (HttpRequest/newBuilder uri)
                       (.timeout (Duration/ofSeconds (long timeout-sec)))
                       (.header "Content-Type" "application/json")
                       (.POST (HttpRequest$BodyPublishers/ofString
                               (protocol/json-encode message))))]
         (doseq [[name value] headers]
           (.header builder (str name) (str value)))
         (let [response (http/send-request (or (:client config) (http/make-client))
                                           (.build builder)
                                           (HttpResponse$BodyHandlers/ofInputStream)
                                           (or (:connect-timeout-sec config)
                                               http/default-connect-timeout-sec))
               status (.statusCode response)
               content-type (http/content-type response)]
           (when-not (<= 200 status 299)
             (let [body (http/read-bounded (.body response)
                                           (or (:max-response-bytes config)
                                               http/default-max-response-bytes))]
               (try
                 (protocol/parse-response body request-id)
                 (catch Exception e
                   (throw (ex-info (str "MCP subscription failed with HTTP " status)
                                   {:type :mcp-http-error :status status} e))))))
           (let [body (.body response)]
             (require-sse! content-type body)
             (try
               (on-open body)
               (try
                 (with-open [reader (BufferedReader.
                                     (InputStreamReader. body StandardCharsets/UTF_8))]
                   (loop [data-lines []
                          event-chars 0
                          final-result nil]
                     (let [line (.readLine reader)]
                       (cond
                         (nil? line)
                         (or final-result
                             (throw (ex-info "MCP subscription ended without a final response"
                                             {:type :missing-sse-response})))

                         (str/blank? line)
                         (if (seq data-lines)
                           (let [data (str/join "\n" data-lines)
                                 decoded (protocol/json-decode data)]
                             (if (contains? decoded "id")
                               (recur [] 0 (redaction/redact
                                            (protocol/parse-response data request-id) secrets))
                               (do (on-notification (redaction/redact decoded secrets))
                                   (recur [] 0 final-result))))
                           (recur [] 0 final-result))

                         (str/starts-with? line "data:")
                         (let [value (subs line 5)
                               value (if (str/starts-with? value " ") (subs value 1) value)
                               next-chars (+ event-chars (count value))]
                           (when (> next-chars max-event-chars)
                             (throw (ex-info "MCP SSE event exceeded configured size limit"
                                             {:type :response-too-large
                                              :max-bytes max-event-chars})))
                           (recur (conj data-lines value) next-chars final-result))

                         :else
                         (recur data-lines event-chars final-result)))))
                 (finally
                   (on-close body)))
               (catch Throwable e
                 (try (.close ^java.io.InputStream body) (catch Exception _))
                 (throw e))))))
       (catch Exception e
         (when (instance? InterruptedException e)
           (.interrupt (Thread/currentThread)))
         (throw (sanitized-exception e secrets {:type :mcp-subscription-error})))))))
