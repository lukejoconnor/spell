(ns spell.jline-pty-fixture
  "Subprocess fixture for exercising JLine against a real OS pseudo-terminal."
  (:require [clojure.string :as str]
            [spell.runtime :as runtime]
            [spell.user :as user])
  (:import [java.lang.reflect InvocationHandler InvocationTargetException Proxy]
           [java.nio.charset StandardCharsets]
           [java.util Base64]
           [org.jline.reader LineReader LineReaderBuilder]
           [org.jline.terminal Attributes Terminal TerminalBuilder]))

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

(defn- restored-attributes? [before after]
  ;; macOS sets PENDIN while cooked input is pending reprocessing. It is a
  ;; transient kernel status bit, independent of the restored terminal settings.
  (= (str/replace before " pendin" "") (str/replace after " pendin" "")))

(defn- open-reader! []
  (let [^Terminal terminal (-> (TerminalBuilder/builder) (.system true) (.build))
        saved-attributes (Attributes. (.getAttributes terminal))
        original-attributes (str saved-attributes)
        reader-finished (promise)
        ^LineReader reader (-> (LineReaderBuilder/builder) (.terminal terminal) (.build))
        stopping? (atom false)]
    (#'user/install-newline-bindings! reader)
    (reset! @#'user/interactive-session {:reader reader :lock (Object.)})
    (let [reader-task (#'user/start-jline-reader! reader stopping?
                                                   {:finished reader-finished})]
      (when-not (wait-until #(.isReading reader) 5000)
        (throw (ex-info "JLine reader did not become ready" {})))
      (println "SPELL_READY")
      (flush)
      {:terminal terminal
       :reader reader
       :reader-task reader-task
       :reader-finished reader-finished
       :saved-attributes saved-attributes
       :stopping? stopping?
       :original-attributes original-attributes})))

(defn- close-reader!
  [{:keys [^Terminal terminal reader-task reader-finished stopping?
           saved-attributes original-attributes]}]
  (reset! stopping? true)
  (try
    (future-cancel reader-task)
    (when (= ::reader-timeout (deref reader-finished 2000 ::reader-timeout))
      (throw (ex-info "Fixture reader did not stop" {})))
    (finally
      (try (.close terminal)
           (finally (.setAttributes terminal saved-attributes)))))
  (restored-attributes? original-attributes (str (.getAttributes terminal))))

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

(defn- run-cleanup-mode! [startup-race?]
  (reset! runtime/registry {})
  (user/reset-state!)
  (let [open-terminal @#'user/open-terminal!
        raw-entered (promise)
        terminal-closed (promise)
        terminal-factory
        (fn []
          (let [terminal (open-terminal)]
            (Proxy/newProxyInstance
              (.getClassLoader Terminal) (into-array Class [Terminal])
              (reify InvocationHandler
                (invoke [_ _ method args]
                  (let [method-name (.getName method)]
                    (when (= "enterRawMode" method-name)
                      (deliver raw-entered true)
                      @terminal-closed)
                    (try
                      (let [result (.invoke method terminal args)]
                        (when (= "close" method-name)
                          (deliver terminal-closed true))
                        result)
                      (catch InvocationTargetException e (throw (.getCause e))))))))))
        session (if startup-race?
                  (with-redefs-fn {#'user/open-terminal! terminal-factory}
                    #(user/register-interactive-user-agent!))
                  (user/register-interactive-user-agent!))
        same-session (user/register-interactive-user-agent!)
        {:keys [^Terminal terminal ^LineReader reader original-attributes reader-finished]}
        @@#'user/interactive-session]
    (if startup-race?
      (when (= ::timeout (deref raw-entered 5000 ::timeout))
        (throw (ex-info "Reader did not enter delayed raw-mode setup" {})))
      (when-not (wait-until #(.isReading reader) 5000)
        (throw (ex-info "Production JLine reader did not become ready" {}))))
    (.close ^java.io.Closeable session)
    (emit-result! {:restored? (restored-attributes? original-attributes (str (.getAttributes terminal)))
                   :reader-stopped? (realized? reader-finished)
                   :original-attributes original-attributes
                   :closed-attributes (str (.getAttributes terminal))
                   :session-cleared? (nil? @@#'user/interactive-session)
                   :idempotent? (identical? session same-session)})
    (shutdown-agents)))

(defn- run-full-flow-mode! []
  (reset! runtime/registry {})
  (user/reset-state!)
  (let [response-sent (promise)
        main-eval (fn [raw]
                    (when (and (str/includes? raw "single-submission")
                               (not (realized? response-sent)))
                      (runtime/send :user "KNOWN_AGENT_RESPONSE")
                      (deliver response-sent true))
                    raw)]
    ;; This is the production runtime shape used by chat: an idle :main root box
    ;; plus the production interactive JLine-backed :user agent.
    (runtime/start-box :main main-eval
                       "(quine completion (eval (do )))" nil)
    (let [session (user/register-interactive-user-agent!)
          {:keys [^LineReader reader]} @@#'user/interactive-session]
      (try
        (when-not (wait-until #(.isReading reader) 5000)
          (throw (ex-info "Production JLine reader did not become ready" {})))
        (println "SPELL_READY")
        (flush)
        (when (= ::timeout (deref response-sent 10000 ::timeout))
          (throw (ex-info "Main agent did not receive the submitted message" {})))
        ;; Keep the PTY alive so the parent can prove the visible response arrived
        ;; before terminating us out-of-band, with no second input byte.
        @(promise)
        (finally
          (.close ^java.io.Closeable session)
          (shutdown-agents))))))

(defn -main [& [mode]]
  (case mode
    "cleanup" (run-cleanup-mode! false)
    "cleanup-startup-race" (run-cleanup-mode! true)
    "full-flow" (run-full-flow-mode!)
    (run-reader-mode! mode))
  (System/exit 0))
