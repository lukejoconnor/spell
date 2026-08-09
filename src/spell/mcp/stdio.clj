(ns spell.mcp.stdio
  "Newline-delimited MCP stdio transport with request-ID demultiplexing."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [spell.mcp.protocol :as protocol]
            [spell.mcp.redaction :as redaction])
  (:import [java.io BufferedInputStream BufferedWriter ByteArrayOutputStream Closeable InputStream
            OutputStreamWriter]
           [java.nio ByteBuffer]
           [java.nio.charset CodingErrorAction StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def default-timeout-ms 120000)
(def default-stderr-lines 200)
(def default-max-message-bytes (* 16 1024 1024))
(def default-stderr-max-line-bytes (* 64 1024))
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
    (if (> (count next) limit)
      (into [] (subvec next (- (count next) limit)))
      next)))

(defn- decode-utf8
  [bytes replace-invalid?]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput (if replace-invalid?
                                       CodingErrorAction/REPLACE
                                       CodingErrorAction/REPORT))
                  (.onUnmappableCharacter (if replace-invalid?
                                            CodingErrorAction/REPLACE
                                            CodingErrorAction/REPORT)))]
    (str (.decode decoder (ByteBuffer/wrap bytes)))))

(defn- strip-trailing-cr [line]
  (if (and (seq line) (= \return (last line)))
    (subs line 0 (dec (count line)))
    line))

(defn- read-bounded-line!
  "Read one newline-delimited UTF-8 line without allocating beyond max-bytes.
   Returns nil only when EOF occurs before any bytes are read. In truncate mode,
   excess bytes are drained without retention so stderr cannot block the child."
  [^InputStream input max-bytes truncate?]
  (let [output (ByteArrayOutputStream.)]
    (loop [bytes-read 0
           truncated? false]
      (let [byte-value (.read input)]
        (cond
          (and (= -1 byte-value) (zero? bytes-read))
          nil

          (or (= -1 byte-value) (= 10 byte-value))
          (let [line (-> (.toByteArray output)
                         (decode-utf8 truncate?)
                         strip-trailing-cr)]
            (if truncated?
              (str line "… [truncated stderr line]")
              line))

          (< bytes-read max-bytes)
          (do (.write output byte-value)
              (recur (inc bytes-read) truncated?))

          truncate?
          (recur (inc bytes-read) true)

          :else
          (throw (ex-info "MCP stdio message exceeded configured size limit"
                          {:type :stdio-message-too-large
                           :max-bytes max-bytes})))))))

(declare fail-transport!)

(defrecord StdioTransport [process ^BufferedWriter writer pending listeners stderr-lines write-lock closed?
                           redactions]
  Closeable
  (close [this]
    (fail-transport! this "MCP stdio process closed")))

(defn- fail-transport!
  "Atomically make a transport unusable, fail every registered request, and
   terminate its subprocess. Safe to invoke concurrently from readers/close."
  [transport message]
  (let [transition
        (locking (:write-lock transport)
          (when (compare-and-set! (:closed? transport) false true)
            (let [responses (vals @(:pending transport))]
              (reset! (:pending transport) {})
              (try (.close ^BufferedWriter (:writer transport)) (catch Exception _))
              {:responses (vec responses)})))]
    (when transition
      ;; Deliver before waiting for process termination so callers fail promptly.
      (doseq [response (:responses transition)]
        (deliver response {:transport-error message}))
      (let [^Process process (:process transport)]
        (when-not (.waitFor process 2 TimeUnit/SECONDS)
          (.destroy process)
          (when-not (.waitFor process 2 TimeUnit/SECONDS)
            (.destroyForcibly process)))))
    nil))

(defn start
  [{:keys [command cwd env stderr-max-lines max-message-bytes stderr-max-line-bytes]
    :or {stderr-max-lines default-stderr-lines
         max-message-bytes default-max-message-bytes
         stderr-max-line-bytes default-stderr-max-line-bytes}}]
  (when-not (and (vector? command) (seq command) (every? string? command))
    (throw (ex-info "MCP stdio :command must be a non-empty vector of strings"
                    {:type :invalid-stdio-command :command command})))
  (doseq [[field value] [[:stderr-max-lines stderr-max-lines]
                         [:max-message-bytes max-message-bytes]
                         [:stderr-max-line-bytes stderr-max-line-bytes]]]
    (when-not (and (integer? value) (pos? value))
      (throw (ex-info (str "MCP stdio " field " must be a positive integer")
                      {:type :invalid-stdio-limit :field field :value value}))))
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
        writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream process)
                                                     StandardCharsets/UTF_8))
        pending (atom {})
        listeners (atom #{})
        stderr-lines (atom [])
        transport (->StdioTransport process writer pending listeners stderr-lines (Object.)
                                    (atom false) redactions)
        stdout-reader (BufferedInputStream. (.getInputStream process))
        stderr-reader (BufferedInputStream. (.getErrorStream process))]
    (doto (Thread.
           (fn []
             (try
               (loop []
                 (if-let [line (read-bounded-line! stdout-reader max-message-bytes false)]
                   (let [message (try
                                   (redaction/redact (json/read-str line) redactions)
                                   (catch Exception e
                                     (throw (ex-info "MCP stdio server wrote non-JSON stdout"
                                                     {:type :invalid-stdio-output} e))))]
                     (if-let [id (get message "id")]
                       (when-let [response (get @pending id)]
                         (deliver response message))
                       (doseq [listener @listeners]
                         (try (listener message) (catch Exception _))))
                     (recur))
                   (fail-transport! transport "MCP stdio server closed stdout")))
               (catch Throwable e
                 (fail-transport! transport
                                  (or (redaction/redact-string (.getMessage e) redactions)
                                      "MCP stdio reader failed")))
               (finally
                 (try (.close stdout-reader) (catch Exception _)))))
           "spell-mcp-stdio-stdout")
      (.setDaemon true)
      (.start))
    (doto (Thread.
           (fn []
             (try
               (loop []
                 (when-let [line (read-bounded-line! stderr-reader stderr-max-line-bytes true)]
                   (swap! stderr-lines bounded-conj
                          (redaction/redact-string line redactions) stderr-max-lines)
                   (recur)))
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

(defn- write-message-under-lock! [transport message]
  (.write ^BufferedWriter (:writer transport) (protocol/json-encode message))
  (.write ^BufferedWriter (:writer transport) "\n")
  (.flush ^BufferedWriter (:writer transport)))

(defn- write-message! [transport message]
  (locking (:write-lock transport)
    (when @(:closed? transport)
      (throw (ex-info "MCP stdio transport is closed" {:type :stdio-closed})))
    (write-message-under-lock! transport message)))

(defn send-notification! [transport method params]
  (write-message! transport (protocol/notification method params)))

(defn abort-request!
  "Immediately fail one local request and notify the server that it was
   cancelled, without tearing down unrelated requests on the transport."
  [transport request-id error]
  (locking (:write-lock transport)
    (when-not @(:closed? transport)
      (when-let [response (get @(:pending transport) request-id)]
        (deliver response {:request-error error})
        (swap! (:pending transport) dissoc request-id))
      (write-message-under-lock!
       transport
       (protocol/notification "notifications/cancelled" {"requestId" request-id}))))
  nil)

(defn send-request!
  ([transport message] (send-request! transport message default-timeout-ms))
  ([transport message timeout-ms]
   (let [id (get message "id")
         response (promise)]
     (try
       (locking (:write-lock transport)
         (when @(:closed? transport)
           (throw (ex-info "MCP stdio transport is closed" {:type :stdio-closed})))
         (swap! (:pending transport) assoc id response)
         (write-message-under-lock! transport message))
       (let [value (deref response timeout-ms ::timeout)]
         (when (= value ::timeout)
           (send-notification! transport "notifications/cancelled" {"requestId" id})
           (throw (ex-info "MCP stdio request timed out"
                           {:type :mcp-timeout :timeout-ms timeout-ms})))
         (when-let [transport-error (:transport-error value)]
           (throw (ex-info transport-error
                           {:type :mcp-stdio-error
                            :stderr (stderr-tail transport)})))
         (when-let [request-error (:request-error value)]
           (throw request-error))
         (protocol/parse-response (protocol/json-encode value) id))
       (finally
         (swap! (:pending transport) dissoc id))))))
