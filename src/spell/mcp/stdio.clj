(ns spell.mcp.stdio
  "Newline-delimited MCP stdio transport with request-ID demultiplexing."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [spell.mcp.protocol :as protocol]
            [spell.mcp.redaction :as redaction])
  (:import [java.io BufferedReader BufferedWriter Closeable]
           [java.util.concurrent TimeUnit]))

(def default-timeout-ms 120000)
(def default-stderr-lines 200)
(def ^:private inherited-env-allowlist
  #{"HOME" "JAVA_HOME" "LANG" "LC_ALL" "PATH" "TMPDIR"})

(defn- resolve-env-value [value]
  (if (and (map? value) (:env value))
    (or (System/getenv (str (:env value)))
        (throw (ex-info (str "Missing MCP subprocess environment variable " (:env value))
                        {:type :missing-mcp-secret :env (str (:env value))})))
    (str value)))

(defn- bounded-conj [values value limit]
  (let [next (conj values value)]
    (if (> (count next) limit) (subvec next (- (count next) limit)) next)))

(defrecord StdioTransport [process ^BufferedWriter writer pending listeners stderr-lines write-lock closed?
                           redactions]
  Closeable
  (close [_]
    (when (compare-and-set! closed? false true)
      (try (.close writer) (catch Exception _))
      (when-not (.waitFor process 2 TimeUnit/SECONDS)
        (.destroy process)
        (when-not (.waitFor process 2 TimeUnit/SECONDS)
          (.destroyForcibly process)))
      (doseq [[_ response] @pending]
        (deliver response {:transport-error "MCP stdio process closed"}))
      (reset! pending {}))))

(defn start
  [{:keys [command cwd env stderr-max-lines]
    :or {stderr-max-lines default-stderr-lines}}]
  (when-not (and (vector? command) (seq command) (every? string? command))
    (throw (ex-info "MCP stdio :command must be a non-empty vector of strings"
                    {:type :invalid-stdio-command :command command})))
  (let [builder (ProcessBuilder. ^java.util.List command)
        _ (when cwd (.directory builder (io/file (str cwd))))
        process-env (.environment builder)
        resolved-env (into {}
                           (map (fn [[name value]] [(str name) (resolve-env-value value)]))
                           env)
        redactions (redaction/secret-values
                    (keep (fn [[name value]]
                            (when (and (map? value) (:env value))
                              (get resolved-env (str name))))
                          env))
        inherited-env (select-keys (System/getenv) inherited-env-allowlist)
        _ (.clear process-env)
        _ (doseq [[name value] (merge inherited-env resolved-env)]
            (.put process-env name value))
        process (.start builder)
        writer (io/writer (.getOutputStream process))
        pending (atom {})
        listeners (atom #{})
        stderr-lines (atom [])
        transport (->StdioTransport process writer pending listeners stderr-lines (Object.)
                                    (atom false) redactions)
        stdout-reader (io/reader (.getInputStream process))
        stderr-reader (io/reader (.getErrorStream process))]
    (doto (Thread.
           (fn []
             (try
               (doseq [line (line-seq stdout-reader)]
                 (let [message (try
                                 (redaction/redact (json/read-str line) redactions)
                                 (catch Exception e
                                   (throw (ex-info "MCP stdio server wrote non-JSON stdout"
                                                   {:type :invalid-stdio-output} e))))]
                   (if-let [id (get message "id")]
                     (when-let [response (get @pending id)]
                       (deliver response message))
                     (doseq [listener @listeners]
                       (try (listener message) (catch Exception _))))))
               (catch Throwable e
                 (doseq [[_ response] @pending]
                   (deliver response {:transport-error (.getMessage e)})))
               (finally
                 (try (.close stdout-reader) (catch Exception _)))))
           "spell-mcp-stdio-stdout")
      (.setDaemon true)
      (.start))
    (doto (Thread.
           (fn []
             (try
               (doseq [line (line-seq stderr-reader)]
                 (swap! stderr-lines bounded-conj
                        (redaction/redact-string line redactions) stderr-max-lines))
               (finally
                 (try (.close stderr-reader) (catch Exception _)))))
           "spell-mcp-stdio-stderr")
      (.setDaemon true)
      (.start))
    transport))

(defn add-listener! [transport listener]
  (swap! (:listeners transport) conj listener)
  #(swap! (:listeners transport) disj listener))

(defn stderr-tail [transport] @(:stderr-lines transport))

(defn- write-message! [transport message]
  (locking (:write-lock transport)
    (.write ^BufferedWriter (:writer transport) (protocol/json-encode message))
    (.write ^BufferedWriter (:writer transport) "\n")
    (.flush ^BufferedWriter (:writer transport))))

(defn send-notification! [transport method params]
  (write-message! transport (protocol/notification method params)))

(defn send-request!
  ([transport message] (send-request! transport message default-timeout-ms))
  ([transport message timeout-ms]
   (when @(:closed? transport)
     (throw (ex-info "MCP stdio transport is closed" {:type :stdio-closed})))
   (let [id (get message "id")
         response (promise)]
     (swap! (:pending transport) assoc id response)
     (try
       (write-message! transport message)
       (let [value (deref response timeout-ms ::timeout)]
         (when (= value ::timeout)
           (send-notification! transport "notifications/cancelled" {"requestId" id})
           (throw (ex-info "MCP stdio request timed out"
                           {:type :mcp-timeout :timeout-ms timeout-ms})))
         (when-let [transport-error (:transport-error value)]
           (throw (ex-info transport-error
                           {:type :mcp-stdio-error
                            :stderr (stderr-tail transport)})))
         (protocol/parse-response (protocol/json-encode value) id))
       (finally
         (swap! (:pending transport) dissoc id))))))
