(ns spell.jline-pty-fixture
  "Subprocess fixture for exercising JLine against a real OS pseudo-terminal."
  (:require [spell.runtime :as runtime]
            [spell.user :as user])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]
           [org.jline.reader LineReader LineReaderBuilder]
           [org.jline.terminal Terminal TerminalBuilder]))

(defn- encode-result [value]
  (.encodeToString (Base64/getEncoder)
                   (.getBytes (pr-str value) StandardCharsets/UTF_8)))

(defn- emit-result! [value]
  (println (str "SPELL_RESULT=" (encode-result value)))
  (flush))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn- open-reader! []
  (let [^Terminal terminal (-> (TerminalBuilder/builder) (.system true) (.build))
        original-attributes (str (.getAttributes terminal))
        ^LineReader reader (-> (LineReaderBuilder/builder) (.terminal terminal) (.build))
        stopping? (atom false)]
    (#'user/install-newline-bindings! reader)
    (reset! @#'user/interactive-session {:reader reader :lock (Object.)})
    (let [reader-task (#'user/start-jline-reader! reader stopping?)]
      (when-not (wait-until #(.isReading reader) 5000)
        (throw (ex-info "JLine reader did not become ready" {})))
      (println "SPELL_READY")
      (flush)
      {:terminal terminal
       :reader reader
       :reader-task reader-task
       :stopping? stopping?
       :original-attributes original-attributes})))

(defn- close-reader!
  [{:keys [^Terminal terminal reader-task stopping? original-attributes]}]
  (reset! stopping? true)
  (.close terminal)
  (when (= ::reader-timeout (deref reader-task 2000 ::reader-timeout))
    (future-cancel reader-task))
  (= original-attributes (str (.getAttributes terminal))))

(defn- run-reader-mode! [mode]
  (reset! runtime/registry {})
  (user/reset-state!)
  ;; The fixture consumes stdin-queue directly; registering :user lets the
  ;; production enqueue path send its debounced wake without another consumer.
  (runtime/register! :user)
  (let [{:keys [^Terminal terminal ^LineReader reader] :as reader-state} (open-reader!)]
    (try
      (case mode
        "paste"
        (let [line (#'user/take-line!)]
          (emit-result! {:line line
                         :parsed (user/parse-user-inputs line)
                         :restored? (close-reader! reader-state)}))

        "keys"
        (let [plain (#'user/take-line!)
              manual-newline (#'user/take-line!)]
          (emit-result! {:lines [plain manual-newline]
                         :restored? (close-reader! reader-state)}))

        "redisplay"
        (do
          (when-not (wait-until #(= "typing" (str (.getBuffer reader))) 5000)
            (throw (ex-info "input buffer did not reach deterministic prefix" {})))
          (let [buffer-before (str (.getBuffer reader))]
            (#'user/print-lines! ["ASYNC-OUTPUT"])
            (let [buffer-after (str (.getBuffer reader))]
              (println "SPELL_BUFFER_READY")
              (flush)
              (let [line (#'user/take-line!)]
                (emit-result! {:line line
                               :buffer-before buffer-before
                               :buffer-after buffer-after
                               :restored? (close-reader! reader-state)})))))

        (throw (ex-info "Unknown reader fixture mode" {:mode mode})))
      (finally
        (reset! @#'user/interactive-session nil)
        (try (.close terminal) (catch Exception _))
        (shutdown-agents)))))

(defn- run-cleanup-mode! []
  (reset! runtime/registry {})
  (user/reset-state!)
  (let [session (user/register-interactive-user-agent!)
        same-session (user/register-interactive-user-agent!)
        {:keys [^Terminal terminal ^LineReader reader original-attributes]}
        @@#'user/interactive-session]
    (when-not (wait-until #(.isReading reader) 5000)
      (throw (ex-info "Production JLine reader did not become ready" {})))
    (.close ^java.io.Closeable session)
    (emit-result! {:restored? (= original-attributes (str (.getAttributes terminal)))
                   :session-cleared? (nil? @@#'user/interactive-session)
                   :idempotent? (identical? session same-session)})
    (shutdown-agents)))

(defn -main [& [mode]]
  (if (= mode "cleanup")
    (run-cleanup-mode!)
    (run-reader-mode! mode))
  (System/exit 0))
