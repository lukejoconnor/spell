(ns spell.mcp.cli
  "Human-operated CLI for inspecting MCP 2026-07-28 servers."
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [spell.agent :as agent]
            [spell.mcp.client :as client]
            [spell.mcp.namespace :as mcp-ns]
            [spell.mcp.protocol :as protocol]
            [spell.mcp.schema :as schema]))

(def usage
  (str/join "\n"
            ["Spell MCP - inspect stateless MCP 2026-07-28 servers"
             ""
             "Usage:"
             "  spell mcp list SERVER [-N|--no-truncate] [--json] [--agent-profile PATH]"
             "  spell mcp inspect SERVER TOOL [--json]"
             "  spell mcp call SERVER TOOL [ARGUMENTS_JSON|-] [-a NAME VALUE]... [--json|--raw]"
             "  spell mcp prompts SERVER [--json]"
             "  spell mcp resources SERVER [--json]"
             "  spell mcp info SERVER [--json]"
             "  spell mcp doctor SERVER [--json]"
             "  spell mcp scaffold SERVER"
             ""
             "SERVER is an HTTP(S) endpoint, .mcp.edn profile, JSON stdio command array,"
             "or configured alias when --agent-profile is supplied."]))

(defn- parse-command-args [args]
  (loop [remaining args
         opts {:argument-pairs []}
         positional []]
    (if (empty? remaining)
      {:opts opts :positional positional}
      (let [[arg & more] remaining]
        (cond
          (contains? #{"--json" "--raw" "-N" "--no-truncate"} arg)
          (recur more (assoc opts (case arg
                                    "--json" :json
                                    "--raw" :raw
                                    :no-truncate) true) positional)

          (or (= arg "-a") (= arg "--argument"))
          (if (< (count more) 2)
            (throw (ex-info (str arg " requires NAME and VALUE") {:type :mcp-cli-usage}))
            (recur (drop 2 more)
                   (update opts :argument-pairs conj [(first more) (second more)])
                   positional))

          (= arg "--agent-profile")
          (if (empty? more)
            (throw (ex-info "--agent-profile requires PATH" {:type :mcp-cli-usage}))
            (recur (rest more) (assoc opts :agent-profile (first more)) positional))

          :else
          (recur more opts (conj positional arg)))))))

(defn- configured-server [server agent-profile]
  (let [spec (agent/load-agent-spec agent-profile)
        alias (keyword server)
        entry (or (get (:mcp-servers spec) alias)
                  (get (:mcp-servers spec) (symbol server))
                  (get (:mcp-servers spec) server))]
    (when-not entry
      (throw (ex-info (str "MCP server alias is not configured: " server)
                      {:type :mcp-cli-usage :server server
                       :agent-profile agent-profile})))
    (mcp-ns/resolve-server-entry entry (:base-dir spec))))

(defn- server-config [server opts]
  (cond
    (re-find #"^https?://" server)
    {:transport {:http {:url server}}}

    (str/ends-with? server ".mcp.edn")
    (mcp-ns/resolve-server-entry {:server server} (System/getProperty "user.dir"))

    (str/starts-with? server "[")
    (let [command (json/read-str server)]
      (when-not (and (vector? command) (seq command) (every? string? command))
        (throw (ex-info "JSON stdio command must be a non-empty array of strings"
                        {:type :mcp-cli-usage})))
      {:transport {:stdio {:command command}}})

    (:agent-profile opts)
    (configured-server server (:agent-profile opts))

    :else
    (throw (ex-info "SERVER must be a URL, .mcp.edn profile, JSON stdio command, or configured alias"
                    {:type :mcp-cli-usage :server server}))))

(defn- with-client [server opts f]
  (with-open [mcp-client (client/open-client :cli
                                             (assoc (server-config server opts)
                                                    :catalog-permissions :all))]
    (f mcp-client)))

(defn- pretty-json [value]
  (with-out-str (json/pprint value)))

(defn- render-tools [tools no-truncate]
  (str/join
   "\n\n"
   (map (fn [tool]
          (if no-truncate
            (str (get tool "name")
                 (when-let [title (get tool "title")] (str " - " title))
                 "\n  " (or (get tool "description") "")
                 "\n  Input schema: " (pr-str (get tool "inputSchema"))
                 (when-let [output (get tool "outputSchema")]
                   (str "\n  Output schema: " (pr-str output))))
            (mcp-ns/compact-signature tool (keyword (get tool "name")))))
        tools)))

(defn- resolve-local-ref [root ref]
  (when (str/starts-with? (str ref) "#/")
    (reduce (fn [node part]
              (when (map? node)
                (get node (-> part (str/replace "~1" "/") (str/replace "~0" "~")))))
            root
            (str/split (subs ref 2) #"/"))))

(defn- allows-string? [root schema seen]
  (let [ref (get schema "$ref")
        schema (if (and ref (not (contains? seen ref)))
                 (or (resolve-local-ref root ref) schema)
                 schema)
        seen (cond-> seen ref (conj ref))
        type-name (get schema "type")]
    (or (= type-name "string")
        (and (vector? type-name) (some #{"string"} type-name))
        (string? (get schema "const"))
        (some string? (get schema "enum"))
        (some #(allows-string? root % seen) (get schema "anyOf"))
        (some #(allows-string? root % seen) (get schema "oneOf")))))

(defn- parse-argument-value [input-schema name value]
  (let [property (get-in input-schema ["properties" name] {})
        property (or (when-let [ref (get property "$ref")]
                       (resolve-local-ref input-schema ref))
                     property)
        string? (allows-string? input-schema property #{})]
    (if string?
      value
      (try
        (json/read-str value)
        (catch Exception e
          (throw (ex-info (str "Argument " name " must be valid JSON")
                          {:type :mcp-cli-usage :argument name} e)))))))

(defn- build-arguments [tool raw-json argument-pairs]
  (let [base (cond
               (nil? raw-json) {}
               (= raw-json "-") (json/read-str (slurp *in*))
               :else (json/read-str raw-json))
        _ (when-not (map? base)
            (throw (ex-info "Raw MCP arguments must be a JSON object"
                            {:type :mcp-cli-usage})))
        input-schema (get tool "inputSchema")
        arguments (reduce (fn [m [name value]]
                            (assoc m name (parse-argument-value input-schema name value)))
                          base argument-pairs)]
    (schema/validate! input-schema arguments "MCP CLI arguments")
    arguments))

(defn- resources-text [resources]
  (if (seq resources)
    (str/join "\n\n"
              (map (fn [resource]
                     (str (get resource "name") "\n  " (get resource "uri")
                          (when-let [description (get resource "description")]
                            (str "\n  " (first (str/split-lines description))))
                          (when-let [mime (get resource "mimeType")]
                            (str "\n  MIME type: " mime))
                          (when-let [size (get resource "size")]
                            (str "\n  Size: " size " bytes"))))
                   resources))
    "No resources available."))

(defn- prompts-text [prompts]
  (if (seq prompts)
    (str/join "\n\n"
              (map (fn [prompt]
                     (str (get prompt "name")
                          (when-let [description (get prompt "description")]
                            (str "\n  " (first (str/split-lines description))))
                          (when-let [arguments (seq (get prompt "arguments"))]
                            (str "\n  Arguments: "
                                 (str/join ", "
                                           (map #(str (get % "name")
                                                      (when-not (get % "required") "?"))
                                                arguments))))))
                   prompts))
    "No prompts available."))

(defn execute
  "Execute an MCP subcommand without exiting. Returns {:status int :out str :err str?}."
  [args]
  (try
    (let [[command & tail] args
          {:keys [opts positional]} (parse-command-args tail)
          json? (:json opts)]
      (when-not command
        (throw (ex-info usage {:type :mcp-cli-usage})))
      (case command
        "help" {:status 0 :out usage}
        "--help" {:status 0 :out usage}
        "-h" {:status 0 :out usage}

        "list"
        (let [[server & extra] positional]
          (when (or (nil? server) (seq extra))
            (throw (ex-info "Usage: spell mcp list SERVER" {:type :mcp-cli-usage})))
          (with-client server opts
            (fn [c]
              (let [tools (client/tools c)]
                {:status 0 :out (if json? (pretty-json tools)
                                  (if (seq tools) (render-tools tools (:no-truncate opts))
                                    "No tools available."))}))))

        "inspect"
        (let [[server tool-name & extra] positional]
          (when (or (nil? tool-name) (seq extra))
            (throw (ex-info "Usage: spell mcp inspect SERVER TOOL" {:type :mcp-cli-usage})))
          (with-client server opts
            (fn [c]
              (let [tool (or (client/find-tool c tool-name)
                             (throw (ex-info (str "Tool not found: " tool-name)
                                             {:type :mcp-tool-not-found})))]
                {:status 0 :out (if json? (pretty-json tool) (with-out-str (pprint/pprint tool)))}))))

        "call"
        (let [[server tool-name raw-json & extra] positional]
          (when (or (nil? tool-name) (seq extra))
            (throw (ex-info "Usage: spell mcp call SERVER TOOL [ARGUMENTS_JSON|-]"
                            {:type :mcp-cli-usage})))
          (with-client server opts
            (fn [c]
              (let [tool (or (client/find-tool c tool-name)
                             (throw (ex-info (str "Tool not found: " tool-name)
                                             {:type :mcp-tool-not-found})))
                    arguments (build-arguments tool raw-json (:argument-pairs opts))
                    result (client/call-tool-raw! c tool-name arguments)
                    semantic-error? (true? (get result "isError"))
                    output (cond
                             (:raw opts) (pretty-json result)
                             json? (pretty-json (if (contains? result "structuredContent")
                                                  (get result "structuredContent")
                                                  (protocol/bounded-text-content result)))
                             :else (let [text (protocol/bounded-text-content result)]
                                     (if (str/blank? text)
                                       (with-out-str (pprint/pprint (protocol/model-value :cli tool-name result)))
                                       text)))]
                {:status (if semantic-error? 1 0) :out output}))))

        "prompts"
        (let [[server & extra] positional]
          (when (or (nil? server) (seq extra))
            (throw (ex-info "Usage: spell mcp prompts SERVER" {:type :mcp-cli-usage})))
          (with-client server opts
            (fn [c]
              (let [prompts (client/prompts c)]
                {:status 0 :out (if json? (pretty-json prompts) (prompts-text prompts))}))))

        "resources"
        (let [[server & extra] positional]
          (when (or (nil? server) (seq extra))
            (throw (ex-info "Usage: spell mcp resources SERVER" {:type :mcp-cli-usage})))
          (with-client server opts
            (fn [c]
              (let [resources (client/resources c)]
                {:status 0 :out (if json? (pretty-json resources) (resources-text resources))}))))

        "info"
        (let [[server & extra] positional]
          (when (or (nil? server) (seq extra))
            (throw (ex-info "Usage: spell mcp info SERVER" {:type :mcp-cli-usage})))
          (with-client server opts
            (fn [c]
              (let [info (client/info c)]
                {:status 0 :out (if json? (pretty-json info) (with-out-str (pprint/pprint info)))}))))

        "doctor"
        (let [[server & extra] positional]
          (when (or (nil? server) (seq extra))
            (throw (ex-info "Usage: spell mcp doctor SERVER" {:type :mcp-cli-usage})))
          (let [started (System/nanoTime)]
            (with-client server opts
              (fn [c]
                (let [catalog (client/catalog c)
                      result {"status" "ok"
                              "protocolVersion" protocol/protocol-version
                              "latencyMs" (/ (- (System/nanoTime) started) 1000000.0)
                              "toolCount" (count (:tools catalog))
                              "excludedTools" (:excluded-tools catalog)
                              "resourceCount" (count (:resources catalog))
                              "promptCount" (count (:prompts catalog))}]
                  {:status 0 :out (if json? (pretty-json result)
                                    (format "ok — MCP %s, %d tools%s, %d resources, %d prompts (%.1f ms)"
                                            protocol/protocol-version
                                            (get result "toolCount")
                                            (if-let [n (not-empty (get result "excludedTools"))]
                                              (str ", " (count n) " excluded") "")
                                            (get result "resourceCount")
                                            (get result "promptCount")
                                            (get result "latencyMs")))})))))

        "scaffold"
        (let [[server & extra] positional]
          (when (or (nil? server) (seq extra))
            (throw (ex-info "Usage: spell mcp scaffold SERVER" {:type :mcp-cli-usage})))
          (with-client server opts
            (fn [c]
              (let [tool-names (mapv #(get % "name") (client/tools c))
                    config (server-config server opts)
                    capabilities (get (client/info c) "capabilities" {})
                    subscriptions? (or (contains? capabilities "subscriptions")
                                       (true? (get-in capabilities ["tools" "listChanged"]))
                                       (true? (get-in capabilities ["prompts" "listChanged"]))
                                       (true? (get-in capabilities ["resources" "listChanged"]))
                                       (true? (get-in capabilities ["resources" "subscribe"])))]
                {:status 0
                 :out (str ";; server.mcp.edn\n" (with-out-str (pprint/pprint config))
                           "\n;; agent profile\n"
                           (with-out-str
                             (pprint/pprint
                              {:mcp-servers
                               {'server {:server "server.mcp.edn"
                                         :tools (into {} (map (fn [name] [(symbol name) name]) tool-names))
                                         :resources (contains? capabilities "resources")
                                         :prompts (contains? capabilities "prompts")
                                         :completion (contains? capabilities "completion")
                                         :subscriptions subscriptions?}}})))}))))

        (throw (ex-info (str "Unknown spell mcp command: " command "\n\n" usage)
                        {:type :mcp-cli-usage}))))
    (catch Exception e
      (let [type (:type (ex-data e))]
        {:status (if (contains? #{:mcp-cli-usage :schema-validation :invalid-schema} type) 2 1)
         :out ""
         :err (.getMessage e)}))))
