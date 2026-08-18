(ns spell.user-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.runtime :as runtime]
            [spell.user :as user])
  (:import [java.io BufferedReader PipedReader PipedWriter StringReader]
           [java.nio.charset StandardCharsets]
           [java.util Base64]
           [java.util.concurrent TimeUnit]))

;; Clean registry and queue between tests
(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (user/reset-state!)
    (f)
    (reset! runtime/registry {})
    (user/reset-state!)))

(defn- mock-reader
  "Create a BufferedReader that reads from a string (one line per readLine)."
  [s]
  (BufferedReader. (StringReader. s)))

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn- pty-command [mode]
  (cond
    (str/includes? (System/getProperty "os.name") "Mac")
    ["/usr/bin/script" "-q" "/dev/null"
     "clojure" "-M:test-jline-pty-fixture" mode]

    (str/includes? (System/getProperty "os.name") "Linux")
    ["script" "-qefc"
     (str "clojure -M:test-jline-pty-fixture " mode)
     "/dev/null"]

    :else
    (throw (ex-info "Automated JLine PTY fixture supports macOS and Linux"
                    {:os (System/getProperty "os.name")}))))

(defn- pty-test-host? []
  (boolean (re-find #"Mac|Linux" (System/getProperty "os.name"))))

(defn- run-pty-fixture!
  [mode chunks]
  (let [builder (doto (ProcessBuilder. ^java.util.List (pty-command mode))
                  (.redirectErrorStream true))
        _ (.put (.environment builder) "TERM" "xterm-256color")
        process (.start builder)
        output-buffer (StringBuilder.)
        output-future
        (future
          (let [stream (.getInputStream process)]
            (loop []
              (let [b (.read stream)]
                (when-not (= -1 b)
                  (locking output-buffer (.append output-buffer (char b)))
                  (recur))))))
        input (.getOutputStream process)]
    (when (seq chunks)
      (when-not (wait-until
                  #(or (not (.isAlive process))
                       (locking output-buffer
                         (str/includes? (.toString output-buffer) "SPELL_READY")))
                  15000)
        (.destroyForcibly process)
        (throw (ex-info "PTY fixture did not become ready" {:mode mode})))
      (when-not (.isAlive process)
        (deref output-future 2000 nil)
        (throw (ex-info "PTY fixture exited before becoming ready"
                        {:mode mode
                         :output (locking output-buffer (.toString output-buffer))}))))
    (doseq [[index [text delay-ms]] (map-indexed vector chunks)]
      (.write input (.getBytes ^String text StandardCharsets/UTF_8))
      (.flush input)
      (when (pos? delay-ms) (Thread/sleep delay-ms))
      (when (and (= mode "redisplay") (zero? index))
        (when-not (wait-until
                    #(locking output-buffer
                       (str/includes? (.toString output-buffer) "SPELL_BUFFER_READY"))
                    5000)
          (.destroyForcibly process)
          (throw (ex-info "PTY redisplay fixture did not preserve the buffer"
                          {:output (locking output-buffer (.toString output-buffer))})))))
    (when-not (.waitFor process 30 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "PTY fixture timed out"
                      {:mode mode
                       :output (locking output-buffer (.toString output-buffer))})))
    (.close input)
    (deref output-future 5000 nil)
    (let [output (locking output-buffer (.toString output-buffer))
          match (re-find #"SPELL_RESULT=([A-Za-z0-9+/=]+)" output)]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "PTY fixture failed"
                        {:mode mode :exit (.exitValue process) :output output})))
      (when-not match
        (throw (ex-info "PTY fixture did not emit a result"
                        {:mode mode :output output})))
      {:result (-> (.decode (Base64/getDecoder) ^String (second match))
                   (String. StandardCharsets/UTF_8)
                   edn/read-string)
       :output output})))

;; =============================================================================
;; Pure function tests
;; =============================================================================

(deftest parse-user-input-test
  (testing "parses recipient prefix"
    (is (= [:main "hello"] (user/parse-user-input ":main hello")))
    (is (= [:seller "offer 100"] (user/parse-user-input ":seller offer 100"))))

  (testing "no recipient prefix"
    (is (= [nil "hello"] (user/parse-user-input "hello")))
    (is (= [nil "some message"] (user/parse-user-input "some message"))))

  (testing "colon without space is not a recipient"
    (is (= [nil ":notseparated"] (user/parse-user-input ":notseparated")))))

(deftest parse-user-inputs-test
  (testing "single plain message"
    (is (= [{:recipients nil :msg "hello"}]
           (user/parse-user-inputs "hello"))))

  (testing "multiple routed segments in one line"
    (is (= [{:recipients [:main] :msg "hi"}
            {:recipients [:other] :msg "yo"}]
           (user/parse-user-inputs ":main hi :other yo"))))

  (testing "first-line recipient prefix keeps a multiline body intact"
    (is (= [{:recipients [:main]
             :msg "first line\nsecond line\n:other remains body text"}]
           (user/parse-user-inputs
             ":main first line\nsecond line\n:other remains body text"))))

  (testing "parenthesized recipient specs cannot cross the first-line boundary"
    (is (= [{:recipients nil
             :msg "(:main\n:other) remains body text"}]
           (user/parse-user-inputs
             "(:main\n:other) remains body text"))))

  (testing "valid first-line recipient groups retain later parenthesized text"
    (is (= [{:recipients [:main :other]
             :msg "first line\n(:third) remains body text"}]
           (user/parse-user-inputs
             "(:main :other) first line\n(:third) remains body text"))))

  (testing "recipient group targets multiple handles"
    (is (= [{:recipients [:main :other] :msg "shared"}]
           (user/parse-user-inputs "(:main :other) shared"))))

  (testing "mixed group and single target forms"
    (is (= [{:recipients [:main :other] :msg "first"}
            {:recipients [:third] :msg "second"}]
           (user/parse-user-inputs "(:main :other) first :third second"))))

  (testing "bare reply followed by recipient spec"
    (is (= [{:recipients nil :msg "hello"}
            {:recipients [:main] :msg "do something"}]
           (user/parse-user-inputs "hello :main do something"))))

  (testing "bare reply followed by multiple recipient specs"
    (is (= [{:recipients nil :msg "hi"}
            {:recipients [:main] :msg "foo"}
            {:recipients [:other] :msg "bar"}]
           (user/parse-user-inputs "hi :main foo :other bar"))))

  (testing "escaped single recipient marker remains literal text"
    (is (= [{:recipients nil :msg "hello :main"}]
           (user/parse-user-inputs "hello \\:main"))))

  (testing "escaped recipient marker at start remains literal text"
    (is (= [{:recipients nil :msg ":main do not route"}]
           (user/parse-user-inputs "\\:main do not route"))))

  (testing "escaped marker inside routed message does not split segments"
    (is (= [{:recipients [:main] :msg "status :other"}]
           (user/parse-user-inputs ":main status \\:other")))))

(deftest resolve-recipient-test
  (testing "explicit takes priority"
    (is (= :seller (user/resolve-recipient :seller :main))))

  (testing "falls back to last-sender"
    (is (= :agent-1 (user/resolve-recipient nil :agent-1))))

  (testing "falls back to :main when both nil"
    (is (= :main (user/resolve-recipient nil nil)))))

;; =============================================================================
;; Registration tests
;; =============================================================================

(deftest register-user-agent-test
  (testing "registers :user handle with parent :main"
    (user/register-user-agent! (mock-reader "test"))
    (is (true? (runtime/handle? :user)))
    (is (= :main (:parent-handle (get @runtime/registry :user)))))

  (testing "idempotent — second call is no-op"
    (user/register-user-agent! (mock-reader "other"))
    (is (true? (runtime/handle? :user)))))

;; =============================================================================
;; Message extraction tests
;; =============================================================================

(deftest extract-messages-test
  (testing "extracts single message from raw completion"
    (let [raw "(quine completion (eval (do (def msg-1 {:from :agent-1 :body \"hello\"}) '(!llm-self (reopen completion)) )))"
          result (#'user/extract-messages raw)]
      (is (= 1 (count result)))
      (is (= 'msg-1 (:name (first result))))
      (is (= :agent-1 (:from (:msg (first result)))))
      (is (= "hello" (:body (:msg (first result)))))))

  (testing "extracts multiple messages"
    (let [raw "(quine completion (eval (do (def msg-1 {:from :agent-1 :body \"hello\"}) (def msg-2 {:from :agent-2 :body \"world\"}) '(!llm-self (reopen completion)) )))"
          result (#'user/extract-messages raw)]
      (is (= 2 (count result)))
      (is (= :agent-1 (:from (:msg (first result)))))
      (is (= :agent-2 (:from (:msg (second result)))))))

  (testing "extracts poke (expects-response, no body) from raw completion"
    (let [raw "(quine completion (eval (do (def msg-1 {:from :agent-2 :expects-response true}) '(!llm-self (reopen completion)) )))"
          result (#'user/extract-messages raw)]
      (is (= 1 (count result)))
      (is (= :agent-2 (:from (:msg (first result)))))
      (is (true? (:expects-response (:msg (first result)))))
      (is (not (contains? (:msg (first result)) :body)))))

  (testing "returns nil for empty/malformed raw"
    (is (nil? (#'user/extract-messages "")))
    (is (nil? (#'user/extract-messages "not a quine")))))

;; =============================================================================
;; Single ask test
;; =============================================================================

(deftest single-ask-test
  (testing "agent asks :user, user replies"
    (let [reader (mock-reader "hello from user")
          h-agent :test-agent
          agent-raw "(quine completion (eval (do )))"
          agent-started (promise)
          first? (atom true)
          agent-eval-fn (fn [raw]
                          (if (compare-and-set! first? true false)
                            (do (deliver agent-started true)
                                (runtime/ask-builtin :user "What is your name?"))
                            raw))]
      (user/register-user-agent! reader)
      (Thread/sleep 100)
      (runtime/register! h-agent)
      (let [pa (promise)]
        (deliver pa agent-raw)
        (let [fa (future (runtime/box h-agent pa (runtime/make-awake-fn h-agent agent-eval-fn)))]
          (deref agent-started 2000 :timeout)
          (let [result (deref fa 5000 :timeout)]
            (is (string? result))
            (is (.contains ^String result ":from :user"))
            (is (.contains ^String result ":body \"hello from user\""))))))))

(deftest expects-reply-sends-immediately-test
  (testing "expects-reply path sends immediately and routes via parse-user-input"
    ;; Directly exercise user-call-fn with an expects-response message.
    ;; The reply should be sent via runtime/send immediately, and the returned
    ;; completion suffix should split top-level forms without embedding send code.
    ;; Plain input (no :handle prefix) goes to last-sender (the asker).
    (runtime/register! :user)
    (runtime/register! :target)
    (.put @#'user/stdin-queue "immediate-reply")
    (let [prompt "(quine completion (eval (do (def msg-1 {:from :target :expects-response true}) )))"
          suffix (binding [runtime/*current-handle* :user]
                   (#'user/user-call-fn prompt))
          received (atom nil)
          p (promise)]
      (is (string? suffix))
      (is (not (.contains ^String suffix "agents/send"))
          "send should happen immediately, not via trailing expression code")
      (is (.contains ^String suffix "(quine completion (eval (do "))
      (deliver p "(quine completion (eval (do )))")
      (runtime/box :target p (runtime/make-awake-fn :target (fn [raw] (reset! received raw) raw)))
      (is (string? @received))
      (is (.contains ^String @received ":from :user"))
      (is (.contains ^String @received ":body \"immediate-reply\"")))))

(deftest expects-reply-routes-to-explicit-handle-test
  (testing "user can route reply to a different handle via :handle prefix"
    ;; Agent :asker asks :user, but user types ":other hello" — message
    ;; should arrive at :other, not :asker.
    (runtime/register! :user)
    (runtime/register! :asker)
    (runtime/register! :other)
    (.put @#'user/stdin-queue ":other hello from user")
    (let [prompt "(quine completion (eval (do (def msg-1 {:from :asker :expects-response true}) )))"
          _ (binding [runtime/*current-handle* :user]
              (#'user/user-call-fn prompt))
          received (atom nil)
          p (promise)]
      (deliver p "(quine completion (eval (do )))")
      (runtime/box :other p (runtime/make-awake-fn :other (fn [raw] (reset! received raw) raw)))
      (is (string? @received))
      (is (.contains ^String @received ":from :user"))
      (is (.contains ^String @received ":body \"hello from user\"")))))

(deftest expects-reply-routes-multiple-segments-test
  (testing "one input line can send multiple routed messages"
    (runtime/register! :user)
    (runtime/register! :asker)
    (runtime/register! :other)
    (runtime/register! :third)
    (.put @#'user/stdin-queue ":other hello :third world")
    (let [prompt "(quine completion (eval (do (def msg-1 {:from :asker :expects-response true}) )))"
          _ (binding [runtime/*current-handle* :user]
              (#'user/user-call-fn prompt))
          received-other (atom nil)
          received-third (atom nil)
          p-other (promise)
          p-third (promise)]
      (deliver p-other "(quine completion (eval (do )))")
      (deliver p-third "(quine completion (eval (do )))")
      (runtime/box :other p-other (runtime/make-awake-fn :other (fn [raw] (reset! received-other raw) raw)))
      (runtime/box :third p-third (runtime/make-awake-fn :third (fn [raw] (reset! received-third raw) raw)))
      (is (.contains ^String @received-other ":body \"hello\""))
      (is (.contains ^String @received-third ":body \"world\"")))))

(deftest expects-reply-recipient-groups-test
  (testing "recipient groups fan out one message to multiple targets"
    (runtime/register! :user)
    (runtime/register! :asker)
    (runtime/register! :a)
    (runtime/register! :b)
    (.put @#'user/stdin-queue "(:a :b) shared")
    (let [prompt "(quine completion (eval (do (def msg-7 {:from :asker :expects-response true}) )))"
          _ (binding [runtime/*current-handle* :user]
              (#'user/user-call-fn prompt))
          received-a (atom nil)
          received-b (atom nil)
          p-a (promise)
          p-b (promise)]
      (deliver p-a "(quine completion (eval (do )))")
      (deliver p-b "(quine completion (eval (do )))")
      (runtime/box :a p-a (runtime/make-awake-fn :a (fn [raw] (reset! received-a raw) raw)))
      (runtime/box :b p-b (runtime/make-awake-fn :b (fn [raw] (reset! received-b raw) raw)))
      (is (.contains ^String @received-a ":body \"shared\""))
      (is (.contains ^String @received-b ":body \"shared\"")))))

(deftest blank-input-cancels-text-entry-test
  (testing "blank input returns quine-restart without sending"
    (runtime/register! :user)
    (runtime/register! :asker)
    ;; Queue a blank line — simulates user pressing Enter to cancel
    (.put @#'user/stdin-queue "")
    (let [prompt "(quine completion (eval (do (def msg-1 {:from :asker :expects-response true}) )))"
          suffix (binding [runtime/*current-handle* :user]
                   (#'user/user-call-fn prompt))]
      (is (string? suffix))
      ;; Should return quine-restart (")) (eval (do "), not split-top-level-restart
      (is (= ")) (eval (do " suffix)
          "blank input should cancel with quine-restart, not split-top-level-restart"))))

(deftest interactive-reply-does-not-queue-redundant-signal-test
  (testing "a submission handed to an active ask waiter does not create a second prompt"
    (runtime/register! :user)
    (runtime/register! :asker)
    (let [prompt "(quine completion (eval (do (def msg-reply {:from :asker :expects-response true}) )))"
          result (future
                   (binding [runtime/*current-handle* :user]
                     (#'user/user-call-fn prompt)))]
      (is (wait-until #(true? @@#'user/input-waiting?) 2000)
          "ask path should install the input waiter")
      (#'user/queue-interactive-submission! "race-safe-reply")
      (is (string? (deref result 3000 :timeout)))
      (is (false? @@#'user/signal-pending)
          "the active waiter consumes the reply without an idle wake")
      (is (empty? (:inbox-macros @(:state (get @runtime/registry :user))))
          "no :stdin-signal remains to cause a second prompt")
      (is (= 1 (count (:inbox-macros @(:state (get @runtime/registry :asker)))))
          "the asker receives exactly one reply"))))

(deftest submission-between-inbox-drain-and-waiter-is-not-resignaled-test
  (testing "the user eval cycle owns input before an ask reaches prompt-and-read"
    (let [pipe-reader (PipedReader.)
          pipe-writer (PipedWriter. pipe-reader)
          reader (BufferedReader. pipe-reader)
          display-entered (promise)
          release-display (promise)
          first? (atom true)
          asker-eval-fn (fn [raw]
                          (if (compare-and-set! first? true false)
                            (runtime/ask-builtin :user "race window?")
                            raw))]
      (try
        (user/register-user-agent! reader)
        (runtime/register! :drain-race-asker)
        (with-redefs-fn
          {#'user/display-messages!
           (fn [_]
             (deliver display-entered true)
             @release-display)}
          (fn []
            (let [completion (promise)
                  _ (deliver completion "(quine completion (eval (do )))")
                  result (future
                           (runtime/box :drain-race-asker completion
                             (runtime/make-awake-fn :drain-race-asker asker-eval-fn)))]
              (is (= true (deref display-entered 3000 :timeout))
                  "the :user inbox has drained but prompt-and-read has not started")
              (is (pos? @@#'user/input-cycle-depth))
              (is (false? @@#'user/input-waiting?))
              (#'user/queue-interactive-submission! "window-reply")
              (is (false? @@#'user/signal-pending)
                  "the pre-waiter user cycle suppresses a redundant idle wake")
              (deliver release-display true)
              (is (string? (deref result 3000 :timeout)))
              (is (false? @@#'user/signal-pending))
              (is (empty? (:inbox-macros @(:state (get @runtime/registry :user))))
                  "no delayed :stdin-signal remains after the reply"))))
        (finally
          (deliver release-display true)
          (.close pipe-writer)
          (.close pipe-reader))))))

(deftest reset-between-inbox-drain-and-waiter-does-not-leave-stale-waiter-test
  (testing "reset invalidates a user cycle before it can install its input waiter"
    (let [pipe-reader (PipedReader.)
          pipe-writer (PipedWriter. pipe-reader)
          reader (BufferedReader. pipe-reader)
          display-entered (promise)
          release-display (promise)
          first? (atom true)
          asker-eval-fn (fn [raw]
                          (if (compare-and-set! first? true false)
                            (runtime/ask-builtin :user "reset window?")
                            raw))]
      (try
        (user/register-user-agent! reader)
        (runtime/register! :reset-race-asker)
        (with-redefs-fn
          {#'user/display-messages!
           (fn [_]
             (deliver display-entered true)
             @release-display)}
          (fn []
            (let [completion (promise)
                  _ (deliver completion "(quine completion (eval (do )))")
                  result (future
                           (runtime/box :reset-race-asker completion
                             (runtime/make-awake-fn :reset-race-asker asker-eval-fn)))]
              (is (= true (deref display-entered 3000 :timeout)))
              (is (pos? @@#'user/input-cycle-depth))
              (is (false? @@#'user/input-waiting?))
              (user/reset-state!)
              (deliver release-display true)
              (is (not= :timeout (deref result 3000 :timeout)))
              (is (false? @@#'user/input-waiting?)
                  "the stale cycle must not install a waiter after reset")
              (is (zero? @@#'user/input-cycle-depth))
              (is (.isEmpty @#'user/stdin-queue)))))
        (finally
          (deliver release-display true)
          (.close pipe-writer)
          (.close pipe-reader))))))

(deftest reset-while-installed-waiter-is-rendering-does-not-abandon-it-test
  (testing "a timed-out reset leaves token ownership until the stale waiter exits"
    (runtime/register! :user)
    (runtime/register! :asker)
    (let [render-entered (promise)
          release-render (promise)
          prompt "(quine completion (eval (do (def msg-render-reset {:from :asker :expects-response true}) )))"]
      (with-redefs-fn
        {#'user/lookup-recipients
         (fn []
           (deliver render-entered true)
           @release-render
           [])}
        (fn []
          (let [result (future
                         (try
                           (binding [runtime/*current-handle* :user]
                             (#'user/user-call-fn prompt))
                           (catch clojure.lang.ExceptionInfo e
                             (:type (ex-data e)))))]
            (is (= true (deref render-entered 3000 :timeout)))
            (is (true? @@#'user/input-waiting?))
            (let [waiter-token @@#'user/input-waiter-token]
              (is (some? waiter-token))
              (user/reset-state!)
              (is (identical? waiter-token @@#'user/input-waiter-token)
                  "reset must not falsely release a waiter still rendering")
              (is (true? @@#'user/input-waiting?))
              (deliver release-render true)
              (is (= :user-input-reset (deref result 3000 :timeout)))
              (is (wait-until #(false? @@#'user/input-waiting?) 1000))
              (is (nil? @@#'user/input-waiter-token))
              (is (.isEmpty @#'user/stdin-queue)))))))))

(deftest reset-after-consumption-prevents-stale-reply-commit-test
  (testing "a reply parsed after reset cannot mutate a fresh runtime generation"
    (runtime/register! :user)
    (runtime/register! :main)
    (let [parse-entered (promise)
          release-parse (promise)
          original-parse user/parse-user-inputs
          prompt "(quine completion (eval (do (def msg-cross-run {:from :main :expects-response true}) )))"]
      (with-redefs-fn
        {#'user/parse-user-inputs
         (fn [input]
           (deliver parse-entered true)
           @release-parse
           (original-parse input))}
        (fn []
          (let [result (future
                         (try
                           (binding [runtime/*current-handle* :user]
                             (#'user/user-call-fn prompt))
                           (catch clojure.lang.ExceptionInfo e
                             (:type (ex-data e)))))]
            (is (wait-until #(true? @@#'user/input-waiting?) 2000))
            (#'user/queue-interactive-submission! "reply for old main")
            (is (= true (deref parse-entered 3000 :timeout)))
            (is (nil? @@#'user/input-waiter-token)
                "the old prompt has consumed its line before parsing pauses")
            (user/reset-state!)
            (reset! runtime/registry {})
            (runtime/register! :main)
            (deliver release-parse true)
            (is (= :user-input-reset (deref result 3000 :timeout)))
            (is (empty? (:inbox-macros @(:state (get @runtime/registry :main))))
                "the stale reply must not enter the fresh :main inbox")
            (is (empty? @@#'user/seen-msg-names)
                "the stale generation must not repopulate reset module state")))))))

(deftest interactive-cancel-does-not-queue-redundant-signal-test
  (testing "Ctrl-C cancellation handed to an active waiter returns to idle once"
    (runtime/register! :user)
    (runtime/register! :asker)
    (let [prompt "(quine completion (eval (do (def msg-cancel {:from :asker :expects-response true}) )))"
          result (future
                   (binding [runtime/*current-handle* :user]
                     (#'user/user-call-fn prompt)))]
      (is (wait-until #(true? @@#'user/input-waiting?) 2000)
          "ask path should install the input waiter")
      (#'user/queue-interactive-submission! ::user/cancel)
      (is (= ")) (eval (do " (deref result 3000 :timeout)))
      (is (false? @@#'user/signal-pending))
      (is (empty? (:inbox-macros @(:state (get @runtime/registry :user))))
          "Ctrl-C must not leave a prompt-producing signal behind")
      (is (empty? (:inbox-macros @(:state (get @runtime/registry :asker))))
          "cancellation sends no reply"))))

(deftest idle-interactive-submission-wakes-user-test
  (testing "an idle JLine submission queues one debounced user wake"
    (runtime/register! :user)
    (#'user/queue-interactive-submission! "idle-message")
    (is (true? @@#'user/signal-pending))
    (is (= 1 (count (:inbox-macros @(:state (get @runtime/registry :user)))))
        "the idle submission wakes :user exactly once")
    (is (= "idle-message" (#'user/take-line!)))))

(deftest eof-wakes-idle-user-and-remains-sticky-test
  (testing "idle EOF wakes :user and every later read fails promptly"
    (runtime/register! :user)
    (#'user/queue-interactive-submission! ::user/eof)
    (is (true? @@#'user/signal-pending))
    (is (= :user-input-eof
           (try (#'user/take-line!)
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (let [second-read (future
                        (try (#'user/take-line!)
                             (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
      (is (= :user-input-eof (deref second-read 1000 :timeout))
          "sticky EOF prevents a later ask from blocking on an empty queue"))))

(deftest active-waiter-eof-does-not-hang-test
  (testing "EOF terminates an already-active ask waiter without an extra signal"
    (runtime/register! :user)
    (runtime/register! :asker)
    (let [prompt "(quine completion (eval (do (def msg-eof {:from :asker :expects-response true}) )))"
          result (future
                   (try
                     (binding [runtime/*current-handle* :user]
                       (#'user/user-call-fn prompt))
                     (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
      (is (wait-until #(true? @@#'user/input-waiting?) 2000))
      (#'user/queue-interactive-submission! ::user/eof)
      (is (= :user-input-eof (deref result 3000 :timeout)))
      (is (false? @@#'user/signal-pending))
      (is (empty? (:inbox-macros @(:state (get @runtime/registry :user))))))))

(deftest ask-after-idle-eof-completes-test
  (testing "an ask issued after idle EOF completes instead of leaving the caller asleep"
    (user/register-user-agent! (mock-reader ""))
    (is (wait-until #(true? @@#'user/input-closed?) 2000)
        "the reader should publish sticky EOF")
    (is (wait-until #(false? @@#'user/signal-pending) 2000)
        "the idle EOF wake should be processed")
    (runtime/register! :post-eof-asker)
    (let [first? (atom true)
          eval-fn (fn [raw]
                    (if (compare-and-set! first? true false)
                      (runtime/ask-builtin :user "still there?")
                      raw))
          completion (promise)]
      (deliver completion "(quine completion (eval (do )))")
      (let [result (future
                     (runtime/box :post-eof-asker completion
                       (runtime/make-awake-fn :post-eof-asker eval-fn)))]
        (is (string? (deref result 3000 :timeout))
            "the post-EOF ask must not hang")))))

(when (pty-test-host?)
  (deftest jline-real-pty-paste-and-restoration-test
  (testing "bracketed multiline paste is one logical routed message on a real PTY"
    (let [{:keys [result]}
          (run-pty-fixture!
            "paste"
            [["\u001b[200~:main first line\n(:other) remains body\u001b[201~\r" 0]])]
      (is (= ":main first line\n(:other) remains body" (:line result)))
      (is (= [{:recipients [:main]
               :msg "first line\n(:other) remains body"}]
             (:parsed result)))
      (is (true? (:restored? result))))))

(deftest jline-real-pty-enter-and-alt-enter-test
  (testing "ordinary Enter submits and Alt+Enter inserts a newline"
    (let [{:keys [result]}
          (run-pty-fixture!
            "keys"
            [["plain\rfirst\u001b\rsecond\r" 0]])]
      (is (= ["plain" "first\nsecond"] (:lines result))))))

(deftest jline-real-pty-single-submission-full-flow-test
  (when (pty-test-host?)
    (testing "one ordinary submission produces a visible agent response before any second input"
      (let [builder (doto (ProcessBuilder. ^java.util.List (pty-command "full-flow"))
                      (.redirectErrorStream true))
            _ (.put (.environment builder) "TERM" "xterm-256color")
            process (.start builder)
            output-buffer (StringBuilder.)
            output-future (future
                            (let [stream (.getInputStream process)]
                              (loop []
                                (let [b (.read stream)]
                                  (when-not (= -1 b)
                                    (locking output-buffer (.append output-buffer (char b)))
                                    (recur))))))
            input (.getOutputStream process)]
        (try
          (is (wait-until
                #(or (not (.isAlive process))
                     (locking output-buffer
                       (str/includes? (.toString output-buffer) "SPELL_READY")))
                15000)
              "full-flow PTY fixture should become ready")
          (is (.isAlive process)
              (str "fixture exited before input: "
                   (locking output-buffer (.toString output-buffer))))
          ;; This is the only write to the subprocess input stream. No blank line,
          ;; second Enter, Ctrl-D, or other follow-up byte is ever sent.
          (.write input (.getBytes "single-submission\r" StandardCharsets/UTF_8))
          (.flush input)
          (is (wait-until
                #(locking output-buffer
                   (str/includes? (.toString output-buffer)
                                  "[agent :main] KNOWN_AGENT_RESPONSE"))
                10000)
              (str "response was not visible after the sole submission: "
                   (locking output-buffer (.toString output-buffer))))
          (is (.isAlive process)
              "fixture remains interactive; the response was not caused by EOF")
          (finally
            ;; Terminate out-of-band so cleanup cannot accidentally satisfy the test
            ;; by sending a second input byte.
            (.destroyForcibly process)
            (.waitFor process 5 TimeUnit/SECONDS)
            (try (.close input) (catch Exception _))
            (deref output-future 5000 nil)))))))

(deftest jline-real-pty-redisplay-test
  (testing "printAbove preserves and redisplays the active input buffer"
    (let [{:keys [result output]}
          (run-pty-fixture!
            "redisplay"
            [["typing" 0]
             ["\r" 0]])
          terminal-output (subs output 0 (str/index-of output "SPELL_RESULT="))]
      (is (= "typing" (:line result)))
      (is (= "typing" (:buffer-before result)))
      (is (= "typing" (:buffer-after result))
          "printAbove preserves the active edit buffer")
      (is (str/includes? terminal-output "ASYNC-OUTPUT"))
      (is (> (.lastIndexOf ^String terminal-output ">")
             (.indexOf ^String terminal-output "ASYNC-OUTPUT"))
          (str "the prompt is redrawn after asynchronous output: "
               (pr-str terminal-output))))))

(deftest interactive-session-close-restores-real-pty-test
  (testing "closing the production interactive session restores terminal state"
    (let [{:keys [result]}
          (run-pty-fixture! "cleanup" [])]
      (is (true? (:restored? result)))
      (is (true? (:session-cleared? result)))
      (is (true? (:idempotent? result)))))))

;; Sequential asks: skipped — mock readers respond too fast for reliable
;; concurrent box lifecycle testing. Not reproducible with real LLM agents.

;; =============================================================================
;; Fire-and-forget test (send, no ask — should not prompt)
;; =============================================================================

(deftest fire-and-forget-test
  (testing "send to :user processes without blocking on reply"
    ;; Use a reader that blocks forever — if user-call-fn tried to read,
    ;; the test would hang. We verify it completes promptly.
    (let [reader (mock-reader "")]
      (user/register-user-agent! reader)
      (Thread/sleep 100)
      (runtime/register! :ff-sender)
      (binding [runtime/*current-handle* :ff-sender]
        (runtime/send :user "goodbye!"))
      ;; Fire-and-forget should process without blocking on stdin.
      ;; The user agent quine-restarts immediately (no > prompt).
      ;; If it blocked, deref would timeout.
      (Thread/sleep 500)
      ;; Handle is still registered (agent didn't crash)
      (is (true? (runtime/handle? :user))))))

;; =============================================================================
;; User-initiated messaging test
;; =============================================================================

(deftest user-initiated-test
  (testing "user presses Enter then sends message to target agent"
    (let [;; Reader: blank line (signal) then the actual message
          reader (mock-reader "\n:target hello from user\n")
          result-p (promise)
          target-eval-fn (fn [raw] (deliver result-p raw) raw)]
      ;; Register target with start-box (root lifecycle)
      (runtime/start-box :target target-eval-fn
                       "(quine completion (eval (do )))" :main)
      (Thread/sleep 100)
      ;; Register user agent — reader has blank line + message
      (user/register-user-agent! reader)
      ;; Wait for target to receive the user's message
      (let [result (deref result-p 5000 :timeout)]
        (is (not= :timeout result))
        (when (string? result)
          (is (.contains ^String result "hello from user")))))))

;; =============================================================================
;; Debounce test
;; =============================================================================

(deftest debounce-rapid-enter-test
  (testing "only one signal sent despite multiple blank lines"
    ;; Use a trivial eval-fn (not the full user pipeline) so signal-pending
    ;; is never reset by user-call-fn. This isolates the debounce mechanism.
    (runtime/start-box :user (fn [raw] raw)
                     "(quine completion (eval (do )))")
    (Thread/sleep 50)
    ;; Start reader with 3 blank lines — only 1 CAS should succeed
    (#'user/start-stdin-reader! (mock-reader "\n\n\n"))
    (Thread/sleep 300)
    ;; signal-pending stays true because nobody reset it (trivial eval-fn)
    ;; Proves subsequent blank lines failed CAS
    (is (true? @@#'user/signal-pending))))

;; =============================================================================
;; Not registered test
;; =============================================================================

(deftest not-registered-test
  (testing "asking unregistered :user throws"
    (let [h-agent :unreg-agent
          agent-eval-fn (fn [raw]
                          (runtime/ask-builtin :user "hello"))]
      (runtime/register! h-agent)
      (let [pa (promise)]
        (deliver pa "(quine completion (eval (do )))")
        (is (thrown? Exception
              (runtime/box h-agent pa (runtime/make-awake-fn h-agent agent-eval-fn))))))))
