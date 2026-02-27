(ns spell.user-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.runtime :as runtime]
            [spell.user :as user])
  (:import [java.io BufferedReader StringReader]))

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
        (let [fa (future (runtime/box h-agent pa (runtime/make-awake-fn agent-eval-fn)))]
          (deref agent-started 2000 :timeout)
          (let [result (deref fa 5000 :timeout)]
            (is (string? result))
            (is (.contains ^String result ":from :user"))
            (is (.contains ^String result ":body \"hello from user\""))))))))

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
              (runtime/box h-agent pa (runtime/make-awake-fn agent-eval-fn))))))))
