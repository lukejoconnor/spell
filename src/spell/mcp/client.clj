(ns spell.mcp.client
  "Transport-independent MCP 2026-07-28 client."
  (:require [spell.http :as http]
            [spell.mcp.http :as mcp-http]
            [spell.mcp.protocol :as protocol]
            [spell.mcp.schema :as schema]
            [spell.mcp.stdio :as stdio])
  (:import [java.io Closeable]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def max-pagination-pages 1000)
(defonce ^:private reusable-catalogs (atom {}))
(defonce ^:private reusable-catalog-lock (Object.))

(defn- now-ms [] (System/currentTimeMillis))

(defrecord MCPClient [alias transport-type transport-config stdio-transport catalog lock cache-key
                      subscription-streams subscription-ids catalog-permissions closed? lifecycle-lock]
  Closeable
  (close [_]
    (locking lifecycle-lock
      (when (compare-and-set! closed? false true)
        (doseq [stream @subscription-streams]
          (try (.close ^Closeable stream) (catch Exception _)))
        (reset! subscription-streams #{})
        (when stdio-transport
          (doseq [request-id @subscription-ids]
            (try
              (stdio/send-notification! stdio-transport "notifications/cancelled"
                                        {"requestId" request-id})
              (catch Exception _)))
          (reset! subscription-ids #{})
          (.close ^Closeable stdio-transport))))))

(defn- sha256 [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- env-context [env]
  (into (sorted-map)
        (map (fn [[name value]]
               [(str name)
                (if (and (map? value) (:env value))
                  (System/getenv (str (:env value)))
                  value)]))
        env))

(defn- credential-context [transport-type transport-config]
  (case transport-type
    :http (mcp-http/credential-fingerprint transport-config)
    :stdio (sha256 (env-context (:env transport-config)))
    (sha256 nil)))

(defn- client-cache-key [alias transport-type transport-config catalog-permissions]
  [alias transport-type
   (case transport-type
     :http (:url transport-config)
     :stdio [(:command transport-config) (:cwd transport-config)])
   (credential-context transport-type transport-config)
   catalog-permissions])

(defn open-client
  [alias config]
  (let [transport (:transport config)
        [transport-type transport-config]
        (cond
          (:http transport)
          (let [http-config (merge (:http transport)
                                   (select-keys config [:auth :headers :timeout-sec
                                                        :max-response-bytes]))]
            [:http (assoc http-config :client
                          (or (:client http-config)
                              (http/make-client
                               {:connect-timeout-sec
                                (or (:connect-timeout-sec http-config)
                                    http/default-connect-timeout-sec)})))])

          (:stdio transport)
          [:stdio (merge (:stdio transport)
                         (select-keys config [:timeout-ms]))]

          :else
          (throw (ex-info "MCP server requires :transport {:http ...} or {:stdio ...}"
                          {:type :invalid-mcp-transport :server alias})))
        stdio-transport (when (= transport-type :stdio)
                          (stdio/start transport-config))]
    (let [catalog-permissions (get config :catalog-permissions :all)]
      (->MCPClient alias transport-type transport-config stdio-transport (atom nil) (Object.)
                   (client-cache-key alias transport-type transport-config catalog-permissions)
                   (atom #{}) (atom #{}) catalog-permissions (atom false) (Object.)))))

(defn- ensure-open! [client]
  (when @(:closed? client)
    (throw (ex-info "MCP client is closed"
                    {:type :mcp-client-closed :server (:alias client)}))))

(defn- send!
  ([client method params] (send! client method params nil))
  ([client method params tool]
   (ensure-open! client)
   (let [message (protocol/request method params)]
     (case (:transport-type client)
       :http (mcp-http/send-request! (:transport-config client) message tool)
       :stdio (stdio/send-request! (:stdio-transport client) message
                                   (or (:timeout-ms (:transport-config client))
                                       stdio/default-timeout-ms))))))

(defn discover!
  [client]
  (let [result (send! client "server/discover" {})
        received-at (now-ms)
        supported (set (get result "supportedVersions"))]
    (when-not (contains? supported protocol/protocol-version)
      (throw (ex-info (str "MCP server does not support required protocol "
                           protocol/protocol-version)
                      {:type :unsupported-mcp-version
                       :required protocol/protocol-version
                       :supported (vec supported)})))
    (with-meta result (assoc (meta result) ::received-at received-at))))

(defn- aggregate-page-cache
  "Conservatively combine cache metadata for a fully paginated result. The
   aggregate may be reused only when every page supplies a positive TTL."
  [page-caches]
  (let [ttls (map #(get % "ttlMs") page-caches)
        scopes (keep #(get % "cacheScope") page-caches)
        ttl-ms (when (and (seq ttls)
                          (every? #(and (integer? %) (pos? %)) ttls))
                 (apply min ttls))
        expires-at (when (and ttl-ms
                              (every? integer? (map ::expires-at page-caches)))
                     (apply min (map ::expires-at page-caches)))
        cache-scope (cond
                      (some #{"private"} scopes) "private"
                      (and (= (count scopes) (count page-caches))
                           (apply = scopes)) (first scopes)
                      :else nil)]
    (cond-> {}
      ttl-ms (assoc "ttlMs" ttl-ms)
      expires-at (assoc ::expires-at expires-at)
      cache-scope (assoc "cacheScope" cache-scope))))

(defn- paginate!
  [client method result-key]
  (loop [cursor nil
         seen #{}
         page 0
         items []
         page-caches []]
    (when (>= page max-pagination-pages)
      (throw (ex-info "MCP pagination exceeded page limit"
                      {:type :pagination-limit :method method
                       :max-pages max-pagination-pages})))
    (let [result (send! client method (cond-> {} cursor (assoc "cursor" cursor)))
          received-at (now-ms)
          next-cursor (get result "nextCursor")
          items' (into items (get result result-key []))
          page-cache (select-keys result ["ttlMs" "cacheScope"])
          page-cache (cond-> page-cache
                       (and (integer? (get page-cache "ttlMs"))
                            (pos? (get page-cache "ttlMs")))
                       (assoc ::expires-at (+ received-at (get page-cache "ttlMs"))))
          page-caches' (conj page-caches page-cache)]
      (cond
        (nil? next-cursor)
        (let [aggregate (aggregate-page-cache page-caches')]
          {:items items'
           :cache (dissoc aggregate ::expires-at)
           :cache-expires-at (::expires-at aggregate)})

        (contains? seen next-cursor)
        (throw (ex-info "MCP server repeated a pagination cursor"
                        {:type :repeated-pagination-cursor
                         :method method :cursor next-cursor}))

        :else
        (recur next-cursor (conj seen next-cursor) (inc page) items' page-caches')))))

(defn- capability? [discovery name]
  (contains? (get discovery "capabilities" {}) name))

(defn- catalog-enabled? [client capability]
  (let [permissions (:catalog-permissions client)
        permission (when (map? permissions) (get permissions capability))]
    (or (= :all permissions)
        (= true permission)
        (= :all permission)
        (and (map? permission) (seq permission))
        (and (coll? permission) (seq permission)))))

(defn- reusable-catalog [client]
  (when-let [{:keys [catalog expires-at]} (get @reusable-catalogs (:cache-key client))]
    (if (> expires-at (now-ms))
      catalog
      (do (swap! reusable-catalogs dissoc (:cache-key client)) nil))))

(defn- catalog-expires-at [catalog]
  (let [pairs (cons [(:discovery catalog) (get-in catalog [:cache-expiries :discovery])]
                    (keep (fn [[key metadata]]
                            (when metadata
                              [metadata (get-in catalog [:cache-expiries key])]))
                          (:cache catalog)))]
    (when (and (seq pairs)
               (every? (fn [[metadata expires-at]]
                         (and (integer? (get metadata "ttlMs"))
                              (pos? (get metadata "ttlMs"))
                              (integer? expires-at)))
                       pairs))
      (apply min (map second pairs)))))

(defn- store-reusable-catalog! [client catalog]
  (let [expires-at (catalog-expires-at catalog)]
    (when (and expires-at (> expires-at (now-ms)))
      (swap! reusable-catalogs assoc (:cache-key client)
             {:catalog catalog
              :expires-at expires-at})))
  catalog)

(defn- fetch-catalog!
  [client]
    (let [discovery (discover! client)
          discovery-received-at (or (::received-at (meta discovery)) (now-ms))
          discovery-expires-at (let [ttl-ms (get discovery "ttlMs")]
                                 (when (and (integer? ttl-ms) (pos? ttl-ms))
                                   (+ discovery-received-at ttl-ms)))
          tools-page (when (and (capability? discovery "tools")
                                (catalog-enabled? client :tools))
                       (paginate! client "tools/list" "tools"))
          tool-validation
          (reduce (fn [{:keys [tools] :as state} tool]
                    (try
                      (assoc state :tools
                             (conj tools (protocol/validate-tool! tool (:transport-type client))))
                      (catch Exception e
                        (update state :excluded-tools conj
                                {:name (get tool "name")
                                 :type (:type (ex-data e))
                                 :message (.getMessage e)}))))
                  {:tools [] :excluded-tools []}
                  (:items tools-page))
          resources-page (when (and (capability? discovery "resources")
                                    (catalog-enabled? client :resources))
                           (paginate! client "resources/list" "resources"))
          templates-page (when (and (capability? discovery "resources")
                                    (catalog-enabled? client :resources))
                           (paginate! client "resources/templates/list" "resourceTemplates"))
          prompts-page (when (and (capability? discovery "prompts")
                                  (catalog-enabled? client :prompts))
                         (paginate! client "prompts/list" "prompts"))
          catalog {:discovery discovery
                   :tools (vec (sort-by #(get % "name") (:tools tool-validation)))
                   :excluded-tools (:excluded-tools tool-validation)
                   :resources (vec (sort-by #(get % "uri") (:items resources-page)))
                   :resource-templates (vec (sort-by #(get % "uriTemplate")
                                                     (:items templates-page)))
                   :prompts (vec (sort-by #(get % "name") (:items prompts-page)))
                   :cache {:tools (:cache tools-page)
                           :resources (:cache resources-page)
                           :resource-templates (:cache templates-page)
                           :prompts (:cache prompts-page)}
                   :cache-expiries {:discovery discovery-expires-at
                                    :tools (:cache-expires-at tools-page)
                                    :resources (:cache-expires-at resources-page)
                                    :resource-templates (:cache-expires-at templates-page)
                                    :prompts (:cache-expires-at prompts-page)}}]
      (store-reusable-catalog! client catalog)))

(defn load-catalog!
  [client]
  (locking (:lock client)
    (let [loaded (or @(:catalog client)
                     (reusable-catalog client)
                     (locking reusable-catalog-lock
                       (or (reusable-catalog client)
                           (fetch-catalog! client)))
                     (throw (ex-info "Unable to load MCP catalog"
                                     {:type :mcp-catalog-error})))]
      (reset! (:catalog client) loaded)
      loaded)))

(defn catalog
  [client]
  (or @(:catalog client) (load-catalog! client)))

(defn refresh!
  [client]
  (swap! reusable-catalogs dissoc (:cache-key client))
  (reset! (:catalog client) nil)
  (load-catalog! client))

(defn invalidate!
  [client]
  (swap! reusable-catalogs dissoc (:cache-key client))
  (reset! (:catalog client) nil)
  nil)

(defn info [client]
  (let [catalog (catalog client)]
    (assoc (:discovery catalog)
           "catalogCache" (:cache catalog)
           "excludedTools" (:excluded-tools catalog))))
(defn tools [client] (:tools (catalog client)))
(defn resources [client] (:resources (catalog client)))
(defn resource-templates [client] (:resource-templates (catalog client)))
(defn prompts [client] (:prompts (catalog client)))

(defn find-tool
  [client name]
  (some #(when (= name (get % "name")) %) (tools client)))

(defn call-tool-raw!
  [client name arguments]
  (let [tool (or (find-tool client name)
                 (throw (ex-info (str "MCP tool not found or not permitted: " name)
                                 {:type :mcp-tool-not-found :tool name})))
        arguments (or arguments {})]
    (let [invoke (fn [active-tool]
                   (schema/validate! (get active-tool "inputSchema") arguments
                                     (str "Arguments for MCP tool " name))
                   (send! client "tools/call"
                          {"name" name "arguments" arguments}
                          active-tool))
          [active-tool result]
          (try
            [tool (invoke tool)]
            (catch clojure.lang.ExceptionInfo e
              (if (and (= :mcp-json-rpc-error (:type (ex-data e)))
                       (= -32020 (:code (ex-data e)))
                       (= :http (:transport-type client)))
                (let [_ (refresh! client)
                      refreshed-tool (or (find-tool client name)
                                         (throw (ex-info
                                                 (str "MCP tool disappeared after catalog refresh: " name)
                                                 {:type :mcp-tool-not-found :tool name}
                                                 e)))]
                  [refreshed-tool (invoke refreshed-tool)])
                (throw e))))]
      (when-let [output-schema (get active-tool "outputSchema")]
        (when (contains? result "structuredContent")
          (schema/validate! output-schema (get result "structuredContent")
                            (str "Output from MCP tool " name))))
      result)))

(defn call-tool!
  [client name arguments]
  (protocol/model-value (:alias client) name (call-tool-raw! client name arguments)))

(defn read-resource!
  [client uri]
  (send! client "resources/read" {"uri" uri}))

(defn get-prompt!
  [client name arguments]
  (send! client "prompts/get" (cond-> {"name" name}
                                (some? arguments) (assoc "arguments" arguments))))

(defn complete!
  [client reference argument]
  (send! client "completion/complete"
         {"ref" reference "argument" argument}))

(def ^:private subscription-filter-keys
  #{"toolsListChanged" "promptsListChanged" "resourcesListChanged"
    "resourceSubscriptions"})

(defn- validate-subscription-filter! [notifications]
  (when-not (map? notifications)
    (throw (ex-info "MCP subscription notifications must be a filter map"
                    {:type :invalid-mcp-subscription-filter})))
  (when-let [unknown (seq (remove subscription-filter-keys (keys notifications)))]
    (throw (ex-info (str "Unknown MCP subscription filter fields: " (vec unknown))
                    {:type :invalid-mcp-subscription-filter :fields (vec unknown)})))
  (doseq [field ["toolsListChanged" "promptsListChanged" "resourcesListChanged"]
          :when (contains? notifications field)]
    (when-not (boolean? (get notifications field))
      (throw (ex-info (str field " must be boolean")
                      {:type :invalid-mcp-subscription-filter :field field}))))
  (when-let [uris (get notifications "resourceSubscriptions")]
    (when-not (and (vector? uris) (every? string? uris))
      (throw (ex-info "resourceSubscriptions must be a vector of URI strings"
                      {:type :invalid-mcp-subscription-filter
                       :field "resourceSubscriptions"}))))
  notifications)

(defn listen!
  "Block on subscriptions/listen and invoke on-notification for each granted
   notification. The caller controls threading and cancellation."
  [client notifications on-notification]
  (ensure-open! client)
  (let [message (protocol/request "subscriptions/listen"
                                  {"notifications" (validate-subscription-filter! notifications)})
        request-id (get message "id")
        acknowledged? (atom false)
        protocol-error (atom nil)
        handle-notification
        (fn [notification]
          (cond
            @protocol-error
            nil

            (not @acknowledged?)
            (if (= "notifications/subscriptions/acknowledged" (get notification "method"))
              (reset! acknowledged? true)
              (let [error (ex-info "MCP subscription did not begin with an acknowledgement"
                                   {:type :invalid-mcp-subscription-stream})]
                (reset! protocol-error error)
                (case (:transport-type client)
                  :http (throw error)
                  :stdio (stdio/abort-request! (:stdio-transport client) request-id error))))

            :else
            (do
              (invalidate! client)
              (on-notification notification))))]
    (swap! (:subscription-ids client) conj request-id)
    (try
      (let [result
            (case (:transport-type client)
              :http
              (mcp-http/listen! (:transport-config client) message handle-notification
                                (fn [stream]
                                  (locking (:lifecycle-lock client)
                                    (if @(:closed? client)
                                      (do
                                        (try (.close ^Closeable stream) (catch Exception _))
                                        (throw (ex-info "MCP client closed while opening subscription"
                                                        {:type :mcp-client-closed
                                                         :server (:alias client)})))
                                      (swap! (:subscription-streams client) conj stream))))
                                (fn [stream]
                                  (locking (:lifecycle-lock client)
                                    (swap! (:subscription-streams client) disj stream))))

              :stdio
              (let [remove-listener
                    (stdio/add-listener!
                     (:stdio-transport client)
                     (fn [notification]
                       (let [subscription-id (or (get-in notification ["params" "_meta"
                                                                       "io.modelcontextprotocol/subscriptionId"])
                                                 (get-in notification ["_meta"
                                                                       "io.modelcontextprotocol/subscriptionId"]))]
                         (when (= request-id subscription-id)
                           (handle-notification notification)))))]
                (try
                  (stdio/send-request! (:stdio-transport client) message
                                       (or (:subscription-timeout-ms (:transport-config client))
                                           (* 24 60 60 1000)))
                  (finally
                    (remove-listener)))))]
        (when-let [error @protocol-error] (throw error))
        (when-not @acknowledged?
          (throw (ex-info "MCP subscription ended without an acknowledgement"
                          {:type :invalid-mcp-subscription-stream})))
        result)
      (finally
        (swap! (:subscription-ids client) disj request-id)))))
