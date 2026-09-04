(ns spell.http
  "Shared bounded HTTP and Server-Sent Events helpers."
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpClient$Version
                          HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandler HttpResponse$BodySubscriber]
           [java.time Duration]
           [java.util.concurrent CompletableFuture ExecutionException Flow$Subscription
            TimeUnit TimeoutException]))

(def default-connect-timeout-sec 20)
(def default-request-timeout-sec 120)
(def default-max-response-bytes (* 16 1024 1024))

(defn validate-http-uri
  [url]
  (let [uri (URI/create (str url))
        scheme (some-> (.getScheme uri) str/lower-case)]
    (when-not (contains? #{"http" "https"} scheme)
      (throw (ex-info "HTTP URL must use http or https"
                      {:type :invalid-http-url :url (str url)})))
    (when-not (.getHost uri)
      (throw (ex-info "HTTP URL must include a host"
                      {:type :invalid-http-url :url (str url)})))
    uri))

(defn make-client
  ([] (make-client nil))
  ([{:keys [connect-timeout-sec http-version follow-redirects?]
     :or {connect-timeout-sec default-connect-timeout-sec}}]
   (let [builder (doto (HttpClient/newBuilder)
                   (.followRedirects (if follow-redirects?
                                       HttpClient$Redirect/NORMAL
                                       HttpClient$Redirect/NEVER)))]
     (when connect-timeout-sec
       (.connectTimeout builder (Duration/ofSeconds (long connect-timeout-sec))))
     (when http-version
       (.version builder http-version))
     (.build builder))))

(defn await-response
  [response-future timeout-sec]
  (try
    (if timeout-sec
      (.get response-future (long timeout-sec) TimeUnit/SECONDS)
      (.get response-future))
    (catch TimeoutException _
      (.cancel response-future true)
      (throw (java.net.http.HttpTimeoutException.
              (str "HTTP response timed out after " timeout-sec "s"))))
    (catch InterruptedException e
      (.cancel response-future true)
      (.interrupt (Thread/currentThread))
      (throw e))
    (catch ExecutionException e
      (throw (or (.getCause e) e)))))

(defn send-request
  ([client request body-handler]
   (send-request client request body-handler default-request-timeout-sec))
  ([client request body-handler timeout-sec]
   (await-response (.sendAsync ^HttpClient client request body-handler) timeout-sec)))

(defn read-bounded
  ([input-stream] (read-bounded input-stream default-max-response-bytes))
  ([^InputStream input-stream max-bytes]
   (let [buffer (byte-array 8192)
         output (ByteArrayOutputStream.)]
     (try
       (loop [total 0]
         (let [n (.read input-stream buffer)]
           (if (neg? n)
             (.toString output "UTF-8")
             (let [next-total (+ total n)]
               (when (> next-total max-bytes)
                 (throw (ex-info "HTTP response exceeded configured size limit"
                                 {:type :response-too-large
                                  :max-bytes max-bytes})))
               (.write output buffer 0 n)
               (recur next-total)))))
       (finally
         (.close input-stream))))))

(defn header
  [^HttpResponse response name]
  (let [optional (.firstValue (.headers response) name)]
    (when (.isPresent optional) (.get optional))))

(defn content-type
  [response]
  (some-> (header response "Content-Type")
          (str/split #";" 2)
          first
          str/trim
          str/lower-case))

(defn split-sse-events
  [text]
  (-> text
      (str/replace "\r\n" "\n")
      (str/replace "\r" "\n")
      (str/split #"\n\n")))

(defn sse-event-data
  [event]
  (let [data-lines (keep (fn [line]
                           (when (str/starts-with? line "data:")
                             (let [value (subs line 5)]
                               (if (str/starts-with? value " ")
                                 (subs value 1)
                                 value))))
                         (str/split-lines event))]
    (when (seq data-lines)
      (str/join "\n" data-lines))))

(defn read-sse-line!
  "Read a UTF-8 SSE line within the remaining event byte budget. The shared
   skip-lf? volatile recognizes CRLF without delaying dispatch after a CR."
  [^InputStream input skip-lf? remaining-bytes max-event-bytes]
  (let [output (ByteArrayOutputStream.)]
    (loop [consumed 0]
      (let [value (.read input)]
        (if (= -1 value)
          (when (pos? (.size output))
            {:line (.toString output "UTF-8") :bytes consumed})
          (let [consumed (inc consumed)]
            (when (> consumed remaining-bytes)
              (throw (ex-info "MCP SSE event exceeded configured size limit"
                              {:type :response-too-large :max-bytes max-event-bytes})))
            (if (and @skip-lf? (= 10 value))
              (do (vreset! skip-lf? false) (recur consumed))
              (do
                (vreset! skip-lf? (= 13 value))
                (if (or (= 10 value) (= 13 value))
                  {:line (.toString output "UTF-8") :bytes consumed}
                  (do (.write output value) (recur consumed)))))))))))

(defn- bounded-body-handler
  "Complete only after the bounded body is read, so the request deadline covers
   the full exchange. Explicit cancellation also stops subscriptions on Java 11."
  [max-bytes]
  (let [result (CompletableFuture.)
        subscription (atom nil)
        cancelled? (atom false)
        cancel! (fn []
                  (reset! cancelled? true)
                  (when-let [incoming @subscription]
                    (.cancel ^Flow$Subscription incoming))
                  (.cancel result true))]
    {:cancel! cancel!
     :handler
     (reify HttpResponse$BodyHandler
       (apply [_ _]
         (let [output (ByteArrayOutputStream.)]
           (reify HttpResponse$BodySubscriber
             (getBody [_] result)
             (onSubscribe [_ incoming]
               (reset! subscription incoming)
               (if @cancelled?
                 (.cancel ^Flow$Subscription incoming)
                 (.request ^Flow$Subscription incoming Long/MAX_VALUE)))
             (onNext [_ buffers]
               (try
                 (doseq [^java.nio.ByteBuffer buffer buffers
                         :while (not (.isDone result))]
                   (let [size (.remaining buffer)]
                     (when (> (+ (.size output) size) max-bytes)
                       (throw (ex-info "HTTP response exceeded configured size limit"
                                       {:type :response-too-large :max-bytes max-bytes})))
                     (let [bytes (byte-array size)]
                       (.get buffer bytes)
                       (.write output bytes 0 size))))
                 (catch Exception e
                   (.cancel ^Flow$Subscription @subscription)
                   (.completeExceptionally result e))))
             (onError [_ error] (.completeExceptionally result error))
             (onComplete [_] (.complete result (.toString output "UTF-8")))))))}))

(defn response-map
  [response]
  {:status (.statusCode ^HttpResponse response)
   :content-type (content-type response)
   :headers (into {}
                  (map (fn [[k values]] [(str/lower-case k) (vec values)]))
                  (.map (.headers ^HttpResponse response)))
   :body (.body ^HttpResponse response)})

(defn post-json
  [{:keys [client url headers body timeout-sec max-response-bytes]
    :or {timeout-sec default-request-timeout-sec
         max-response-bytes default-max-response-bytes}}]
  (let [uri (validate-http-uri url)
        {:keys [handler cancel!]} (bounded-body-handler max-response-bytes)
        builder (doto (HttpRequest/newBuilder uri)
                  (.timeout (Duration/ofSeconds (long timeout-sec)))
                  (.header "Content-Type" "application/json")
                  (.POST (HttpRequest$BodyPublishers/ofString body)))]
    (doseq [[name value] headers]
      (.header builder (str name) (str value)))
    (try
      (-> (send-request (or client (make-client))
                        (.build builder)
                        handler
                        timeout-sec)
          response-map)
      (catch Throwable e
        (cancel!)
        (throw e)))))
