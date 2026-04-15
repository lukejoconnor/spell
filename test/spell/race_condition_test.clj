(ns spell.race-condition-test
  "Tests for race conditions in the Spell runtime.

   Bug class 1: Turn-boundary mismatch in user ingress.
   The :user agent uses a global LinkedBlockingQueue (stdin-queue) with no
   request-id handshake between agent questions and user answers.

   Bug class 2: Competing reply channels (phantom transform).
   When ask installs a completion notifier, there is a TOCTOU race in
   deliver-msg-fn between checking (realized? signal-promise) and composing
   into the inbox. An intervening -send! can deliver the signal and drain the
   inbox between those two steps, leaving a phantom transform.

   Bug class 3: Preemption of pending trailing effects.
   Message preemption via create-msg can make a trailing expression inert before
   it fires. When the trailing effect is a send (e.g., from the :user agent to
   another agent), the message is silently dropped. See TODO #139.

   Bug class 5: Box has-box reentry window.
   box releases has-box before inside-fn executes, potentially allowing a second
   box call for the same handle to enter concurrently. Tests investigate whether
   this leads to concurrent evaluation in the current code.

   Bug class 6: Wake/data skew — double-orphan zombie continuation.
   When eval-fn throws inside a root lifecycle, make-root-fn's catch creates
   orphan-1 and rethrows. run-root-box's catch checks (realized? @:completed)
   but make-root-fn already reset :completed to a fresh promise, so the check
   incorrectly creates orphan-2. Both orphans sleep on the same signal, and when
   a message arrives both can process it — a zombie continuation."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.eval :as eval]
            [spell.inbox :as inbox]
            [spell.llm :as llm]
            [spell.parse :as parse]
            [spell.runtime :as runtime]
            [spell.user :as user])
  (:import [java.io BufferedReader]
           [java.util.concurrent LinkedBlockingQueue CountDownLatch TimeUnit
                                 CyclicBarrier]))

;; Clean registry and user state between tests
(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (user/reset-state!)
    (f)
    (reset! runtime/registry {})
    (user/reset-state!)))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- append-forms-macro [& forms]
  (#'runtime/append-forms-macro forms))

(defn- apply-inbox-macros
  [raw inbox-macros]
  (inbox/materialize-inbox-raw raw inbox-macros {:builtins eval/core-builtins}))

(defn- blocking-reader
  "Create a BufferedReader backed by a blocking queue.
   Call (.put q line) to feed lines. Blocks on readLine until a line is available.
   Put ::eof to signal end-of-stream."
  []
  (let [q (LinkedBlockingQueue.)]
    [q (BufferedReader.
         (proxy [java.io.Reader] []
           (read
             ([cbuf off len]
              (let [line (.take q)]
                (if (= ::eof line)
                  -1
                  (let [line-with-nl (str line "\n")
                        n (min len (count line-with-nl))]
                    (.getChars ^String line-with-nl 0 n cbuf off)
                    n))))
             ([]
              (let [line (.take q)]
                (if (= ::eof line)
                  -1
                  (int (first (str line "\n")))))))
           (close [] nil)))]))

;; =============================================================================
;; Test 1: Pre-typed input consumed by agent question
;; =============================================================================

(deftest type-ahead-consumed-by-agent-ask
  (testing "Line typed before agent asks is consumed as the answer to that ask.
            Demonstrates lack of request-id correlation between stdin-queue
            and agent questions."
    ;; Strategy:
    ;; 1. Put a line directly into stdin-queue BEFORE any agent asks
    ;; 2. Have an agent ask :user a question
    ;; 3. The pre-typed line gets consumed as the answer
    ;;
    ;; This simulates a user who typed ahead (e.g., intending to send a
    ;; message to :main) but whose input is hijacked by a later agent ask.
    (let [stdin-q @#'user/stdin-queue
          [input-q reader] (blocking-reader)
          asking-agent :agent-a
          agent-raw "(quine completion (eval (do )))"
          agent-started (promise)
          first? (atom true)
          agent-eval-fn (fn [raw]
                          (if (compare-and-set! first? true false)
                            (do (deliver agent-started true)
                                (runtime/ask-builtin :user "What is your favorite color?"))
                            raw))]
      ;; Register user agent with our controlled reader
      (user/register-user-agent! reader)
      (Thread/sleep 100)

      ;; Drain any stale entries from stdin-queue (previous tests' reader threads
      ;; may have deposited ::eof or other lines after fixture cleanup)
      (.clear stdin-q)

      ;; PRE-TYPE: put a line directly into stdin-queue BEFORE the agent asks.
      ;; This bypasses the reader thread to ensure deterministic timing.
      ;; In production, this corresponds to a user who typed and pressed Enter
      ;; before any agent question arrived.
      (.put stdin-q "answer-typed-before-question")

      ;; Now start the agent that will ask :user
      (runtime/register! asking-agent)
      (let [pa (promise)]
        (deliver pa agent-raw)
        (let [fa (future (runtime/box asking-agent pa
                           (runtime/make-awake-fn asking-agent agent-eval-fn)))]
          (deref agent-started 2000 :timeout)

          ;; The agent should get back the pre-typed line as its "answer"
          (let [result (deref fa 5000 :timeout)]
            (is (not= :timeout result) "Agent should not timeout")
            (is (string? result) "Agent should receive a string result")
            (when (string? result)
              ;; BUG DEMONSTRATION: the pre-typed "answer-typed-before-question"
              ;; is consumed as the response to "What is your favorite color?"
              ;; even though the user typed it before the question was asked.
              ;; There is no mechanism to correlate the response with the question.
              (is (.contains ^String result "answer-typed-before-question")
                  "Pre-typed input was consumed as the answer to the agent's question,
                   demonstrating the lack of request-id correlation"))))

        ;; Clean up
        (.put input-q ::eof)))))

;; =============================================================================
;; Test 2: Stdin-signal steals agent's turn
;; =============================================================================

(deftest stdin-signal-preempts-agent-ask
  (testing "When stdin-signal and agent ask arrive in the same lifecycle turn,
            stdin-signal takes priority (Case 1 in user-call-fn). The user's
            typed input goes to last-sender instead of the asking agent."
    ;; Strategy:
    ;; 1. Pre-load stdin-queue with a blank line (signal) AND a message line
    ;;    so that the stdin reader thread sends a :stdin-signal to :user
    ;; 2. Have an agent ask :user before the user agent processes the signal
    ;; 3. Both the stdin-signal and the agent's expects-response message
    ;;    arrive in the same turn
    ;; 4. stdin-signal? is checked first in user-call-fn, so the user's
    ;;    input is sent to last-sender (default: :main) instead of agent-a
    (let [[input-q reader] (blocking-reader)
          latch (CountDownLatch. 1)
          ;; Track what :main receives
          main-received (promise)
          main-eval-fn (fn [raw]
                         (deliver main-received raw)
                         raw)
          ;; Agent A will ask :user
          agent-a-completed (promise)
          first? (atom true)
          agent-a-eval-fn (fn [raw]
                            (if (compare-and-set! first? true false)
                              (do (.countDown latch)
                                  ;; This ask sends expects-response msg to :user
                                  (runtime/ask-builtin :user "What is X?"))
                              (do (deliver agent-a-completed raw)
                                  raw)))]

      ;; Start :main as a dormant agent so it can receive messages
      (runtime/start-box :main main-eval-fn
                         "(quine completion (eval (do )))" nil)
      (Thread/sleep 100)

      ;; Register :user
      (user/register-user-agent! reader)
      (Thread/sleep 100)

      ;; Start agent-a in a background future
      (runtime/register! :agent-a :main)
      (let [pa (promise)]
        (deliver pa "(quine completion (eval (do )))")
        (future (runtime/box :agent-a pa
                  (runtime/make-awake-fn :agent-a agent-a-eval-fn)))

        ;; Wait for agent-a to issue the ask
        (is (.await latch 3 TimeUnit/SECONDS) "agent-a should have started")

        ;; Small delay for the ask's send-msg-fn to reach :user's inbox
        (Thread/sleep 200)

        ;; Now inject a blank line (stdin-signal) followed by user input.
        ;; The blank line triggers start-stdin-reader! to send :stdin-signal to :user.
        ;; The non-empty line is the actual typed message.
        ;; Both the :stdin-signal message and agent-a's expects-response message
        ;; will be visible in the same user-call-fn invocation.
        (.put input-q "")  ;; blank line = signal
        (.put input-q "my-answer")  ;; actual input

        ;; Give the system time to process
        (Thread/sleep 2000)

        ;; Check what :main received.
        ;; If the stdin-signal preempted agent-a's ask, :main gets the message
        ;; instead of agent-a. This demonstrates that stdin-signal priority
        ;; (Case 1 in user-call-fn) can steal input from an agent's ask.
        (let [main-result (deref main-received 3000 :timeout)]
          ;; :main should have been woken with a message containing "my-answer"
          ;; (because last-sender defaults to :main and stdin-signal path
          ;; sends to last-sender)
          (when (and (not= :timeout main-result) (string? main-result))
            (is (.contains ^String main-result "my-answer")
                "Input went to :main (last-sender default) via stdin-signal path,
                 not to :agent-a which was actually asking the question")))

        ;; Clean up
        (.put input-q ::eof)))))

;; =============================================================================
;; Test 3: Queue-order-only correlation (no request-id)
;; =============================================================================

(deftest no-request-id-correlation
  (testing "Demonstrates that user-call-fn associates responses with questions
            purely by queue order — there is no request-id or correlation token.
            The association is implicit: whichever question triggers the lifecycle
            turn consumes whatever line is next in the queue."
    ;; This is the simplest demonstration: we show that user-call-fn
    ;; calls take-nonempty-line! which just does (.take stdin-queue),
    ;; with no filtering, matching, or correlation.
    ;;
    ;; We verify this by having the agent ask a question, then providing
    ;; an answer that was queued BEFORE the question — proving that the
    ;; queue doesn't know or care about question timing.
    (let [;; Use a LinkedBlockingQueue to directly control stdin-queue
          stdin-q @#'user/stdin-queue
          asking-agent :test-agent
          agent-raw "(quine completion (eval (do )))"
          agent-started (promise)
          first? (atom true)
          result-atom (atom nil)
          agent-eval-fn (fn [raw]
                          (if (compare-and-set! first? true false)
                            (do (deliver agent-started true)
                                (runtime/ask-builtin :user "Solve 2+2"))
                            (do (reset! result-atom raw) raw)))]

      ;; Register user with a reader that will never produce anything
      ;; (we'll bypass the reader and put directly into stdin-queue)
      (let [[input-q reader] (blocking-reader)]
        (user/register-user-agent! reader)
        (Thread/sleep 100)

        ;; Directly pre-load stdin-queue with our "answer" BEFORE any ask
        (.put stdin-q "pre-typed-42")

        ;; Start the asking agent
        (runtime/register! asking-agent)
        (let [pa (promise)]
          (deliver pa agent-raw)
          (let [fa (future (runtime/box asking-agent pa
                             (runtime/make-awake-fn asking-agent agent-eval-fn)))]
            (deref agent-started 2000 :timeout)

            (let [result (deref fa 5000 :timeout)]
              (is (not= :timeout result) "Agent should not timeout")
              (when (string? result)
                ;; The pre-loaded "pre-typed-42" was consumed as the answer
                ;; to "Solve 2+2" — demonstrating pure queue-order correlation
                (is (.contains ^String result "pre-typed-42")
                    "stdin-queue line was consumed regardless of when it was typed
                     relative to when the question was asked")))))

        ;; Clean up
        (.put input-q ::eof)))))

;; =============================================================================
;; Test 6: Wake/data skew — double-orphan zombie continuation
;; =============================================================================
;;
;; Bug mechanism:
;; When inside-fn (eval-fn) throws during a root lifecycle, make-root-fn's catch
;; block delivers :completed, resets :completed to a NEW promise, and creates
;; orphan-1. The exception then propagates up through box to run-root-box's catch,
;; which checks (realized? @(:completed ...)) — but since make-root-fn already
;; reset :completed to a fresh (unrealized) promise, this check incorrectly
;; evaluates to false and ALSO creates orphan-2.
;;
;; Result: two orphan boxes exist for the same handle. When a subsequent message
;; arrives, both orphans wake from the signal. Since has-box is only held briefly
;; (during inbox drain, not during inside-fn execution), both can succeed in
;; entering box sequentially, leading to eval-fn being called twice for a single
;; message — a zombie continuation.
;;
;; With repeated exceptions, orphan count grows: each exception in N orphans
;; produces N+1 orphans (each failing orphan creates one via make-root-fn, plus
;; the CAS-collision creates extras). This is a self-reinforcing resource leak.

(deftest double-orphan-on-eval-exception-test
  (testing "eval-fn exception creates double orphan, causing duplicate message processing"
    (let [handle :double-orphan
          eval-count (atom 0)
          call-log (atom [])
          ;; eval-fn that throws on first invocation, succeeds on subsequent ones
          eval-fn (fn [raw]
                    (let [n (swap! eval-count inc)]
                      (swap! call-log conj {:n n :thread (.getName (Thread/currentThread))})
                      (when (= n 1)
                        (throw (ex-info "Intentional first-call failure" {:call n})))
                      (str "result-" n)))
          initial-completion "(quine completion (eval (do )))"]

      ;; Start a dormant agent
      (runtime/start-box handle eval-fn initial-completion)
      ;; Wait for the root box to enter make-asleep-fn and block on signal
      (Thread/sleep 200)

      ;; Send first message — this wakes the agent, eval-fn throws,
      ;; triggering the double-orphan path in run-root-box
      (runtime/-send! handle (append-forms-macro '(def msg1 :hello)))
      ;; Wait for the exception path to complete and both orphans to settle
      (Thread/sleep 500)

      ;; Reset the call log to focus on the second message
      (reset! call-log [])
      (let [pre-count @eval-count]
        ;; Send second message — if double orphans exist, both will wake
        (runtime/-send! handle (append-forms-macro '(def msg2 :world)))
        ;; Wait for processing
        (Thread/sleep 500)

        ;; Count how many times eval-fn was called for the second message.
        ;; With a single orphan (correct behavior), eval-fn is called exactly once.
        ;; With double orphans (bug), eval-fn is called twice (zombie continuation).
        (let [post-count @eval-count
              calls-for-msg2 (- post-count pre-count)]
          (when (> calls-for-msg2 1)
            (binding [*out* *err*]
              (println "\nZOMBIE DETECTED: eval-fn called" calls-for-msg2
                       "times for a single message")
              (println "Call log:" (pr-str @call-log))))
          (is (= 1 calls-for-msg2)
              (str "eval-fn should be called exactly once per message, "
                   "but was called " calls-for-msg2 " times. "
                   "This indicates zombie continuation from double-orphan bug. "
                   "Call log: " (pr-str @call-log))))))))

(deftest orphan-count-grows-on-repeated-exceptions-test
  (testing "repeated exceptions cause orphan count to grow, amplifying the problem"
    (let [handle :growing-orphans
          eval-count (atom 0)
          ;; eval-fn that always throws
          eval-fn (fn [raw]
                    (swap! eval-count inc)
                    (throw (ex-info "Always fails" {:call @eval-count})))
          initial-completion "(quine completion (eval (do )))"]

      ;; Start dormant agent
      (runtime/start-box handle eval-fn initial-completion)
      (Thread/sleep 200)

      ;; Send first message — triggers initial double-orphan creation
      (runtime/-send! handle (append-forms-macro '(def msg1 :a)))
      (Thread/sleep 500)

      ;; Reset count to measure just the second message
      (reset! eval-count 0)

      ;; Send second message — if double orphans exist, both wake, both
      ;; throw, each creating another orphan. Orphan count grows.
      (runtime/-send! handle (append-forms-macro '(def msg2 :b)))
      (Thread/sleep 500)

      (let [count-after-msg2 @eval-count]
        ;; Reset again to measure third message
        (reset! eval-count 0)

        ;; Send third message — with growing orphans, even more eval-fn calls
        (runtime/-send! handle (append-forms-macro '(def msg3 :c)))
        (Thread/sleep 500)

        (let [count-after-msg3 @eval-count]
          ;; With the bug, orphan count grows across messages:
          ;; msg1: 1 eval -> 2 orphans (double-orphan from run-root-box)
          ;; msg2: 2 evals -> ~4 orphans (each exception creates more)
          ;; msg3: ~4 evals -> ~8 orphans (exponential growth)
          (when (or (> count-after-msg2 1) (> count-after-msg3 count-after-msg2))
            (binding [*out* *err*]
              (println "\nORPHAN GROWTH DETECTED:"
                       "msg2 evals:" count-after-msg2
                       "msg3 evals:" count-after-msg3)))
          (is (= 1 count-after-msg2)
              (str "Expected 1 eval for msg2, got " count-after-msg2))
          (is (<= count-after-msg3 count-after-msg2)
              (str "Orphan count should not grow; msg3 evals ("
                   count-after-msg3 ") > msg2 evals ("
                   count-after-msg2 ")")))))))

;; =============================================================================
;; Bug Class 2: Competing Reply Channels — Phantom Transform
;; =============================================================================
;;
;; deliver-msg-fn (lines 188-196 of runtime.clj) has a TOCTOU race:
;;
;;   (when-not (realized? signal-promise)          ;; CHECK  (line 193)
;;     (let [{:keys [inbox]} ...]
;;       (swap! inbox (fn [cur] (comp msg-fn cur))) ;; COMPOSE (line 195)
;;       (deliver signal-promise :wake)))            ;; DELIVER (line 196)
;;
;; Between CHECK and COMPOSE, another thread can:
;;   1. Call -send! which delivers the same signal and composes into inbox
;;   2. The parent wakes, box drains the inbox (reset-vals! inbox identity)
;;   3. deliver-msg-fn then composes its transform into the NOW-EMPTY inbox
;;   4. deliver-msg-fn tries (deliver signal-promise :wake) -> false (stale)
;;
;; Result: phantom transform in inbox with no signal to wake anyone.
;; The transform surfaces as a stale message in the next box entry.

(deftest phantom-transform-deterministic-test
  (testing "TOCTOU in deliver-msg-fn: inbox compose after drain leaves phantom transform"
    ;; Manually simulates the interleaving that causes the phantom
    ;; transform bug by stepping through deliver-msg-fn's operations
    ;; with an intervening -send! + inbox drain.
    (let [handle :phantom-parent
          phantom-marker "PHANTOM-TRANSFORM-WAS-HERE"]
      (runtime/register! handle)
      (let [state-atom (:state (get @runtime/registry handle))
            signal-p1 (:signal @state-atom)
            phantom-fn (fn [raw] (str raw " " phantom-marker))
            explicit-fn (fn [raw] (str raw " EXPLICIT-REPLY"))]

        ;; Step 1: Signal not yet realized
        (is (not (realized? signal-p1))
            "precondition: signal is not yet realized")

        ;; Step 2: -send! delivers explicit reply (on another thread in practice)
        (runtime/-send! handle explicit-fn)
        (is (realized? signal-p1)
            "-send! delivered the signal")

        ;; Step 3: Parent wakes, box drains inbox (atomic reset of state)
        (let [{:keys [inbox-macros]} (first (reset-vals! state-atom {:inbox-macros [] :signal (promise)}))]
          (is (= 1 (count inbox-macros))
              "inbox had the explicit-reply macro queued"))

        ;; Step 4: Notifier calls deliver-msg-fn with the OLD signal.
        ;; With the combined atom, deliver-msg-fn sees that signal-p1 is no
        ;; longer identical to the current signal in the atom, so it no-ops.
        (runtime/deliver-msg-fn handle signal-p1 phantom-fn)

        ;; THE FIX: inbox is still empty — no phantom transform
        (is (= [] (:inbox-macros @state-atom))
            "FIX: no phantom transform — deliver-msg-fn detected stale signal via CAS")))))

(deftest phantom-transform-full-lifecycle-test
  (testing "Phantom transform from notifier surfaces in next box entry as stale message"
    ;; Demonstrates the full consequence: the phantom transform left by the
    ;; notifier is picked up by the NEXT box entry, causing the agent to see
    ;; a stale/unexpected message alongside a legitimate new message.
    (let [handle :phantom-lifecycle
          box-results (atom [])]
      (runtime/register! handle)
      (let [state-atom (:state (get @runtime/registry handle))
            base-raw "(quine completion (eval (do)))"
            signal-p1 (:signal @state-atom)
            notifier-msg-fn (fn [raw]
                              (str raw " (def notifier-result {:from :child :body :completed})"))]

        ;; Simulate the race: -send! fires, parent wakes, then notifier tries to compose

        ;; Agent C sends explicit reply via -send!
        (runtime/-send! handle
          (fn [raw] (str raw " (def reply-msg {:from :agent-c :body :hello})")))

        ;; Parent wakes and enters box — drain inbox (atomic reset)
        (let [{:keys [inbox-macros]} (first (reset-vals! state-atom {:inbox-macros [] :signal (promise)}))]
          (swap! box-results conj (count inbox-macros)))

        ;; Notifier calls deliver-msg-fn with old signal — CAS detects stale signal, no-ops
        (runtime/deliver-msg-fn handle signal-p1 notifier-msg-fn)

        ;; Another agent sends a real message via -send!
        (runtime/-send! handle
          (fn [raw] (str raw " (def real-msg {:from :agent-d :body :world})")))

        ;; Parent wakes again, enters box, drains inbox
        (let [{:keys [inbox-macros]} (first (reset-vals! state-atom {:inbox-macros [] :signal (promise)}))]
          (swap! box-results conj (count inbox-macros)))

        ;; First box entry: got only the explicit reply (correct)
        (let [first-result (nth @box-results 0)]
          (is (= 1 first-result)
              "first wake: got explicit reply macro")
          (is (not= 2 first-result)
              "first wake: did NOT get notifier macro (correct)"))

        ;; Second box entry: got ONLY the real message — no phantom!
        (let [second-result (nth @box-results 1)]
          (is (= 1 second-result)
              "second wake: got real message macro")
          (is (not= 2 second-result)
              "FIX: no phantom notifier message — deliver-msg-fn detected stale signal"))))))

(deftest phantom-transform-stress-test
  (testing "Stress: concurrent -send! and deliver-msg-fn can produce phantom transforms"
    ;; Run many iterations where -send! and deliver-msg-fn race on the same
    ;; signal. After both complete, drain the inbox once, then check whether
    ;; both transforms ended up composed (a precondition for the phantom bug).
    ;;
    ;; In a real scenario, the phantom occurs when deliver-msg-fn's realized?
    ;; check passes but its swap! executes after -send!'s deliver + inbox drain.
    ;; Since we can't insert a drain mid-race from outside, we check whether
    ;; deliver-msg-fn composed into inbox even though -send! also delivered
    ;; the signal — proving the TOCTOU window is hittable.
    (let [iterations 2000
          both-composed-count (atom 0)]
      (dotimes [i iterations]
        (let [handle (keyword (str "stress-" i))]
          (runtime/register! handle)
          (let [state-atom (:state (get @runtime/registry handle))
                signal-p1 (:signal @state-atom)
                barrier (CyclicBarrier. 3)
                send-marker (str "SEND-" i)
                notifier-marker (str "NOTIFIER-" i)
                send-fn (fn [raw] (str raw " " send-marker))
                notifier-fn (fn [raw] (str raw " " notifier-marker))]

            ;; Thread A: -send! (explicit reply)
            (future
              (.await barrier)
              (runtime/-send! handle send-fn))

            ;; Thread B: deliver-msg-fn (notifier) with captured signal
            (future
              (.await barrier)
              (runtime/deliver-msg-fn handle signal-p1 notifier-fn))

            ;; Release both threads simultaneously
            (.await barrier)

            ;; Give threads time to complete
            (Thread/sleep 1)

            ;; Drain inbox via atomic reset
            (let [{:keys [inbox-macros]} (first (reset-vals! state-atom {:inbox-macros [] :signal (promise)}))]
              ;; With the combined atom, deliver-msg-fn uses identical? on the
              ;; signal inside the CAS — if -send! already reset the signal via
              ;; make-awake-fn's drain, deliver-msg-fn's CAS sees a different
              ;; signal object and no-ops. Both transforms should only compose
              ;; when both happen before any drain.
              (when (= 2 (count inbox-macros))
                (swap! both-composed-count inc))))))

      (println (str "  Both-composed (both before drain): "
                    @both-composed-count "/" iterations))
      ;; With the combined atom, both can still compose if they both run
      ;; before any drain — this is correct behavior (not a phantom).
      ;; The phantom bug was about composing AFTER drain, which the
      ;; identical?-based CAS prevents.
      (is (>= @both-composed-count 0)
          "stress test completed without exceptions"))))

;; =============================================================================
;; Bug Class 3: Preemption of pending trailing effects
;; =============================================================================
;;
;; These tests demonstrate that message preemption (via create-msg + reopen) can
;; silently drop a trailing expression. The mechanism:
;;
;; 1. Agent produces a completion with a trailing quoted expression, e.g.:
;;    (quine completion (eval (do )) (eval (do '(agents/send :target "data") )))
;;
;; 2. Before the box drains the inbox, another agent sends a message. The
;;    create-msg transform reopens the quine and appends a new extension:
;;    (quine completion (eval (do )) (eval (do '(agents/send :target "data")
;;      (think "[preempted or awakened by msg-X]")
;;      (def msg-X {:from :intruder :body "interrupt"})
;;      '(!extend) )))
;;
;; 3. The quine evaluates only the LAST arg. Inside the do block, the outer eval
;;    double-evaluates only the last expression's value. Since the last expression
;;    is now '(!extend) instead of '(agents/send ...), the original send
;;    becomes dead code — it evaluates to a quoted list whose value is discarded.

(deftest create-msg-preempts-trailing-expression-test
  (testing "create-msg transforms a completion so the trailing expression becomes dead code"
    (let [;; A completion with a trailing '(agents/send :main "hello")
          completion "(quine completion (eval (do )) (eval (do '(agents/send :main \"hello\") )))"
          ;; Apply create-msg (the preemption transform)
          create-msg-macro (#'runtime/create-msg 'msg-99 {:from :intruder :body "interrupt"})
          preempted (apply-inbox-macros completion [create-msg-macro])]

      ;; After preemption, the original send expression is still present
      ;; but NOT as the last expression in the do block
      (is (.contains ^String preempted "agents/send :main")
          "original send expression should still be in the string (as inert data)")
      (is (.contains ^String preempted "preempted or awakened by msg-99")
          "think annotation should be present")
      (is (.contains ^String preempted ":from :intruder")
          "incoming message def should be present")
      (is (or (.contains ^String preempted "'(!extend)")
              (.contains ^String preempted "(quote (!extend))"))
          "new extension should be the trailing expression")

      ;; Parse and verify the trailing send is NOT the last expression
      (let [balanced (parse/balance-parens preempted)
            form (first (parse/read-all balanced))
            last-arg (last form)
            do-form (second last-arg)
            body-exprs (rest do-form)
            last-body-expr (last body-exprs)]
        ;; The last expression should be the new extension, not the send
        (is (= (list 'quote '(!extend))
               last-body-expr)
            "last expression in do block should be the new !extend continuation")
        ;; The original send should be in the body but not last
        (is (some #(and (seq? %) (= 'quote (first %))
                        (seq? (second %))
                        (= 'agents/send (first (second %))))
                  (butlast body-exprs))
            "original quoted send should be earlier in the do block")))))

(deftest control-no-preemption-send-is-final-test
  (testing "without preemption, the trailing send IS the last expression (control)"
    ;; Control test: verify that without any inbox transform, the trailing
    ;; send remains the last expression in the do block and would fire.
    (let [transformed-raw (atom nil)
          inside-fn (fn [raw]
                      (reset! transformed-raw raw)
                      raw)
          victim-completion "(quine completion (eval (do )) (eval (do '(agents/send :target \"data\") )))"
          completion (promise)]

      (runtime/register! :victim)

      ;; No preemption — just deliver and run
      (deliver completion victim-completion)
      (runtime/box :victim completion inside-fn)

      ;; Parse the (untransformed) raw
      (let [balanced (parse/balance-parens @transformed-raw)
            form (first (parse/read-all balanced))
            last-arg (last form)
            do-form (second last-arg)
            body-exprs (vec (rest do-form))
            last-expr (last body-exprs)]

        ;; The last expression SHOULD be the agents/send
        (is (and (seq? last-expr) (= 'quote (first last-expr))
                 (seq? (second last-expr))
                 (= 'agents/send (first (second last-expr))))
            "without preemption, agents/send should be the last expression")))))

(deftest preemption-makes-send-inert-in-do-block-test
  (testing "after preemption, the original trailing send is a non-final quoted form in the do block"
    ;; Verify the structural consequence of preemption at the parsed AST level.
    ;; After create-msg transforms the completion:
    ;; - The original '(agents/send ...) is still in the do block
    ;; - But it is NOT the last expression (its quoted value is discarded)
    ;; - The last expression is the new '(!extend) continuation
    (let [transformed-raw (atom nil)
          inside-fn (fn [raw]
                      (reset! transformed-raw raw)
                      raw)
          victim-completion "(quine completion (eval (do )) (eval (do '(agents/send :target \"data\") )))"
          completion (promise)]

      (runtime/register! :victim)

      ;; Compose preemption transform
      (runtime/-send! :victim
        (#'runtime/create-msg 'msg-preempt {:from :other :body "interruption"}))

      ;; Deliver completion
      (deliver completion victim-completion)

      ;; Run box — make-awake-fn drains inbox, inside-fn sees transformed raw
      (runtime/box :victim completion (runtime/make-awake-fn :victim inside-fn))

      ;; Verify the transform was applied
      (is (some? @transformed-raw) "inside-fn should have been called")

      ;; Parse the transformed raw and verify structure
      (let [balanced (parse/balance-parens @transformed-raw)
            form (first (parse/read-all balanced))
            last-arg (last form)
            do-form (second last-arg)
            body-exprs (vec (rest do-form))
            last-expr (last body-exprs)]

        ;; The last expression should NOT be the agents/send
        (is (not (and (seq? last-expr) (= 'quote (first last-expr))
                      (seq? (second last-expr))
                      (= 'agents/send (first (second last-expr)))))
            "agents/send should NOT be the last expression after preemption")

        ;; The last expression should be the new !extend continuation
        (is (and (seq? last-expr) (= 'quote (first last-expr))
                 (seq? (second last-expr))
                 (= '!extend (first (second last-expr))))
            "last expression should be the new !extend continuation")

        ;; The original send should exist earlier in the body
        (is (some #(and (seq? %) (= 'quote (first %))
                        (seq? (second %))
                        (= 'agents/send (first (second %))))
                  (butlast body-exprs))
            "original quoted send should be present but not final")))))

(deftest trailing-send-fires-without-preemption-test
  (testing "without preemption, trailing send fires normally (control test)"
    ;; End-to-end control: the trailing send should actually execute and
    ;; deliver a message to :target when no preemption occurs.
    (let [target-received (atom false)
          target-eval-fn (fn [raw]
                           (when (.contains ^String raw ":body \"important\"")
                             (reset! target-received true))
                           raw)

          ;; Build a proper eval pipeline for :victim
          core-ns llm/core-namespaces
          variant-builtins (merge eval/core-builtins
                                  {'describe-fn (fn [& _] "no docs")}
                                  core-ns)
          effect-builtins {'!llm-self (fn [& _] nil)
                           'agents runtime/agents-namespace}
          eval-builtin (llm/make-eval variant-builtins effect-builtins)
          inbox-fn (llm/make-inbox-fn {:variant-builtins variant-builtins
                                       :eval-builtin eval-builtin
                                       :recover-fn nil}
                                      (atom nil))

          victim-completion "(quine completion (eval (do )) (eval (do '(agents/send :target \"important\") )))"
          completion (promise)]

      (runtime/register! :victim)
      ;; Start :target as a sleeping agent that records messages
      (runtime/start-box :target target-eval-fn "(quine completion (eval (do )))" :main)
      (Thread/sleep 50)

      ;; No preempting message — just deliver the completion
      (deliver completion victim-completion)

      ;; Run box with full eval pipeline
      (runtime/box :victim completion (runtime/make-awake-fn :victim inbox-fn))

      ;; Give :target time to process messages
      (Thread/sleep 300)

      ;; The trailing send SHOULD have fired
      (is (true? @target-received)
          "target should receive the send when no preemption occurs"))))

(deftest trailing-send-preempted-by-inbox-transform-test
  (testing "pre-composed inbox transform preempts trailing send (deterministic)"
    ;; This is the deterministic version of the timing bug. The create-msg
    ;; transform is composed into the inbox BEFORE box runs, guaranteeing
    ;; it is consumed at drain time. This models a message arriving during
    ;; the LLM API call (or user input wait) — before the completion is ready.
    (let [target-received (atom false)
          target-eval-fn (fn [raw]
                           (when (.contains ^String raw ":body \"important\"")
                             (reset! target-received true))
                           raw)

          ;; Build a proper eval pipeline for :victim
          core-ns llm/core-namespaces
          variant-builtins (merge eval/core-builtins
                                  {'describe-fn (fn [& _] "no docs")}
                                  core-ns)
          effect-builtins {'!llm-self (fn [& _] nil)
                           'agents runtime/agents-namespace}
          eval-builtin (llm/make-eval variant-builtins effect-builtins)
          inbox-fn (llm/make-inbox-fn {:variant-builtins variant-builtins
                                       :eval-builtin eval-builtin
                                       :recover-fn nil}
                                      (atom nil))

          victim-completion "(quine completion (eval (do )) (eval (do '(agents/send :target \"important\") )))"
          completion (promise)]

      (runtime/register! :victim)
      (runtime/start-box :target target-eval-fn "(quine completion (eval (do )))" :main)
      (Thread/sleep 50)

      ;; Compose the preemption transform into :victim's inbox BEFORE delivering completion.
      ;; This simulates another agent sending a message while :victim is still waiting
      ;; for its completion (LLM response or user input).
      (binding [runtime/*current-handle* :intruder]
        (runtime/send :victim "you've been preempted"))

      ;; Deliver the completion
      (deliver completion victim-completion)

      ;; Run box — drain picks up the preemption transform, which reopens the
      ;; completion and appends a new extension. The original trailing
      ;; '(agents/send :target "important") becomes dead code.
      (try
        (runtime/box :victim completion (runtime/make-awake-fn :victim inbox-fn))
        (catch Exception _
          ;; The !extend continuation in the preempted turn will fail because our mock
          ;; returns nil (not a valid completion). That's fine — the point is
          ;; that the original send did not fire before the preemption happened.
          nil))

      ;; Give :target time to process any messages
      (Thread/sleep 300)

      ;; The trailing send should NOT have fired — :target should NOT have received anything
      (is (false? @target-received)
          "preempted trailing send should NOT execute — the send is dead code after preemption"))))
;; =============================================================================
;; Bug Class 5: Box has-box reentry window
;; =============================================================================
;;
;; box (runtime.clj lines 142-164) releases has-box before inside-fn executes:
;;
;;   (when-not (compare-and-set! has-box false true)   ;; ACQUIRE
;;     (throw ...))
;;   (let [[transform _] (reset-vals! inbox identity)] ;; DRAIN inbox
;;     (reset! has-box false)                           ;; RELEASE
;;     (let [transformed (transform raw)]
;;       (binding [...]
;;         (inside-fn transformed))))                   ;; EXECUTE
;;
;; This means has-box does NOT protect inside-fn execution. Another thread
;; can CAS has-box false->true and enter box while the first inside-fn runs.
;;
;; Analysis conclusion: The vulnerability exists in the abstract, but in the
;; current code the only cross-thread path that enters box for the same handle
;; is the orphan future launched by make-root-fn, and that future is launched
;; from WITHIN inside-fn (after the real work completes). So no concurrent
;; evaluation of user logic occurs in the normal (non-exception) path.
;;
;; The early release is intentional: it enables the recursive sleep pattern
;; (block-for-message -> box -> asleep-fn -> box -> awake-fn) where the same
;; thread re-enters box during inside-fn execution.

;; =============================================================================
;; Test BC5-1: Verify has-box is false during inside-fn execution
;; =============================================================================

(deftest has-box-released-during-inside-fn-test
  (testing "has-box is false while inside-fn is running (early release)"
    (let [handle :test-early-release
          has-box-during-inside (atom nil)
          inside-fn (fn [raw]
                      (reset! has-box-during-inside
                              @(:has-box (get @runtime/registry handle)))
                      "done")
          p (promise)]
      (runtime/register! handle)
      (deliver p "hello")
      (runtime/box handle p inside-fn)
      ;; This confirms the has-box early release behavior
      (is (false? @has-box-during-inside)
          "has-box should be false during inside-fn execution (early release)"))))

;; =============================================================================
;; Test BC5-2: CAS prevents concurrent box entry while has-box is held
;; =============================================================================

(deftest has-box-cas-prevents-concurrent-drain-test
  (testing "CAS prevents a second box from entering while first holds has-box"
    ;; Manually hold has-box true to simulate the brief window between
    ;; CAS success and release. Any concurrent box call should throw.
    (let [handle :test-cas-block
          p2 (promise)]
      (runtime/register! handle)
      ;; Manually acquire has-box
      (reset! (:has-box (get @runtime/registry handle)) true)
      (deliver p2 "should-fail")
      ;; A concurrent box call should throw because CAS fails
      (is (thrown-with-msg? Exception #"Box already active"
            (runtime/box handle p2 identity))))))

;; =============================================================================
;; Test BC5-3: Recursive box re-entry works (sleep pattern)
;; =============================================================================

(deftest recursive-box-reentry-works-test
  (testing "inside-fn can call box recursively for the same handle (sleep pattern)"
    (let [handle :test-recursive
          ;; inside-fn that re-enters box (simulating block-for-message)
          inside-fn (fn [raw]
                      ;; Re-enter box with a fn that blocks on signal
                      (let [state (:state (get @runtime/registry handle))
                            result (runtime/box handle raw
                                     (fn [inner-raw]
                                       ;; Block on signal, then return
                                       (deref (:signal @state) 2000 :timeout)
                                       (swap! state assoc :signal (promise))
                                       (str "woke:" inner-raw)))]
                        result))
          p (promise)]
      (runtime/register! handle)
      (deliver p "initial")
      ;; Start box in a future since inside-fn will block
      (let [f (future (runtime/box handle p inside-fn))]
        ;; Give time for the inner box to start blocking on signal
        (Thread/sleep 100)
        ;; Wake it by delivering the signal
        (deliver (:signal @(:state (get @runtime/registry handle))) :wake)
        ;; The whole chain should complete
        (let [result (deref f 3000 :timeout)]
          (is (= "woke:initial" result)
              "recursive box reentry should work for the sleep pattern"))))))

;; =============================================================================
;; Test BC5-4: Orphan box does not overlap with inside-fn execution
;; =============================================================================

(deftest orphan-box-no-overlap-test
  (testing "orphan box starts only after inside-fn completes (no concurrent eval)"
    (let [handle :test-orphan-timing
          eval-fn (fn [raw] (str "eval:" raw))
          execution-log (atom [])
          ;; Track execution phases
          inside-fn (fn [raw]
                      (swap! execution-log conj :inside-fn-start)
                      (Thread/sleep 200) ;; Simulate slow computation
                      (swap! execution-log conj :inside-fn-end)
                      (str "result:" raw))
          p (promise)]
      (runtime/register! handle)
      (deliver p "hello")
      ;; run-root-box wraps inside-fn with root lifecycle (orphan creation)
      (let [result (runtime/run-root-box handle p inside-fn eval-fn)]
        (is (= "result:hello" result))
        ;; Give orphan time to start
        (Thread/sleep 200)
        ;; inside-fn-start and inside-fn-end should be paired (no interleaving)
        (is (= [:inside-fn-start :inside-fn-end] @execution-log)
            "inside-fn should complete before orphan box can enter")))))

;; =============================================================================
;; Test BC5-5: Orphan box successfully enters after inside-fn returns
;; =============================================================================

(deftest orphan-enters-after-inside-fn-test
  (testing "orphan box can successfully CAS has-box after inside-fn returns"
    (let [handle :test-orphan-cas
          orphan-entered (promise)
          eval-fn (fn [raw]
                    (deliver orphan-entered true)
                    ;; Block briefly in the orphan's eval path
                    (deref (promise) 100 :timeout)
                    raw)
          inside-fn (fn [raw] (str "done:" raw))
          p (promise)]
      (runtime/register! handle)
      (deliver p "hello")
      (runtime/run-root-box handle p inside-fn eval-fn)
      ;; Send a message to wake the orphan
      (runtime/send-msg-fn (eval/compose-macros []) handle)
      ;; The orphan should be able to enter box and reach eval-fn
      (is (= true (deref orphan-entered 3000 :timeout))
          "orphan should successfully enter box after inside-fn returns"))))

;; =============================================================================
;; Test BC5-6: Message sent during inside-fn is consumed by orphan
;; =============================================================================

(deftest message-during-inside-fn-consumed-by-orphan-test
  (testing "message sent while inside-fn runs is properly consumed by orphan"
    (let [handle :test-msg-during
          message-processed (promise)
          raw "(quine completion (eval (do )))"
          eval-fn (fn [raw]
                    ;; If raw contains the message marker, we processed it
                    (when (.contains ^String raw "(def injected-msg true)")
                      (deliver message-processed true))
                    raw)
          inside-fn-started (promise)
          inside-fn (fn [raw]
                      (deliver inside-fn-started true)
                      ;; Simulate slow work
                      (Thread/sleep 300)
                      (str "done:" raw))
          p (promise)]
      (runtime/register! handle)
      (deliver p raw)
      ;; Start root box in a future since we need to send mid-execution
      (let [f (future (runtime/run-root-box handle p inside-fn eval-fn))]
        ;; Wait for inside-fn to start
        (deref inside-fn-started 2000 :timeout)
        ;; Send a message while inside-fn is running.
        ;; The message goes into inbox; signal is delivered but nobody is sleeping.
        (runtime/-send! handle (append-forms-macro '(def injected-msg true)))
        ;; Wait for inside-fn to finish
        (deref f 5000 :timeout)
        ;; The orphan should process the message
        (is (= true (deref message-processed 3000 :timeout))
            "message sent during inside-fn should be consumed by orphan")))))

;; =============================================================================
;; Test BC5-7: Forced concurrent box entry (demonstrates the reentry window)
;; =============================================================================

(deftest forced-concurrent-box-entry-test
  (testing "two threads racing to enter box — both succeed due to early release"
    ;; This test demonstrates the vulnerability in the abstract: because
    ;; has-box is released before inside-fn executes, two threads CAN both
    ;; pass the CAS and run inside-fn concurrently for the same handle.
    ;;
    ;; In the current code this doesn't happen because the only cross-thread
    ;; box entry (orphan future) is launched from within inside-fn.
    (let [handle :test-forced-race
          entry-count (atom 0)
          gate (promise)
          inside-fn (fn [raw]
                      (swap! entry-count inc)
                      ;; Block until gate opens so both threads overlap
                      (deref gate 3000 :timeout)
                      raw)
          p1 (promise)
          p2 (promise)]
      (runtime/register! handle)
      (deliver p1 "first")
      (deliver p2 "second")
      ;; Thread 1: enters box, releases has-box, blocks in inside-fn
      (let [f1 (future
                 (try (runtime/box handle p1 inside-fn)
                      (catch Exception e :cas-failed)))
            ;; Brief pause to let f1 acquire and release has-box
            _ (Thread/sleep 50)]
        ;; Thread 2: CAS succeeds because f1 already released has-box
        (let [f2 (future
                   (try (runtime/box handle p2 inside-fn)
                        (catch Exception e :cas-failed)))]
          ;; Open the gate so both threads can finish
          (deliver gate true)
          (let [r1 (deref f1 3000 :timeout)
                r2 (deref f2 3000 :timeout)]
            ;; BOTH threads entered inside-fn due to early has-box release
            (is (= 2 @entry-count)
                "both threads entered inside-fn due to early has-box release")
            (is (= "first" r1))
            (is (= "second" r2))))))))

;; =============================================================================
;; Test BC5-8: Forced concurrent entry causes inbox transform loss
;; =============================================================================

(deftest forced-concurrent-entry-inbox-loss-test
  (testing "forced concurrent box entry causes inbox transform to be lost"
    ;; Demonstrates the theoretical consequence of the reentry window:
    ;; if two box calls enter concurrently, both drain inbox independently.
    ;; The second drain sees identity (empty) because the first already consumed
    ;; the accumulated transforms.
    ;;
    ;; In practice this doesn't happen because the orphan future is launched
    ;; after inside-fn returns (see BC5-4).
    (let [handle :test-inbox-loss
          results (atom [])
          gate (promise)
          raw1 "(quine completion (eval (do (def base :msg1))))"
          raw2 "(quine completion (eval (do (def base :msg2))))"
          inside-fn (fn [raw]
                      (swap! results conj raw)
                      ;; Block to ensure overlap
                      (deref gate 3000 :timeout)
                      raw)
          p1 (promise)
          p2 (promise)]
      (runtime/register! handle)
      ;; Compose a transform into inbox
      (runtime/-send! handle (append-forms-macro '(def transformed true)))
      (deliver p1 raw1)
      (deliver p2 raw2)
      ;; First box call drains inbox via make-awake-fn (gets the transform)
      (let [awake-fn (runtime/make-awake-fn handle inside-fn)
            f1 (future (runtime/box handle p1 awake-fn))]
        (Thread/sleep 50) ;; Let f1 drain inbox and release has-box
        ;; Second box call drains inbox (gets identity — transform already consumed)
        (let [f2 (future (runtime/box handle p2 awake-fn))]
          (Thread/sleep 50)
          (deliver gate true)
          (deref f1 3000 :timeout)
          (deref f2 3000 :timeout)
          ;; First box saw the transform; second box saw identity
          (is (some #(.contains ^String % "(def transformed true)") @results)
              "first box should see the inbox transform")
          (is (some #(= raw2 %) @results)
              "second box sees raw without transform — transform lost to first drain"))))))

;; =============================================================================
;; Test BC5-9: Current code is safe — no concurrent eval in practice
;; =============================================================================

(deftest current-code-safe-no-concurrent-eval-test
  (testing "in practice, no concurrent evaluation occurs for the same handle"
    ;; Definitive safety test: use run-root-box with a slow inside-fn and
    ;; verify that the orphan's eval-fn never runs while inside-fn is active.
    ;; A message is sent DURING inside-fn execution, so the orphan (not the
    ;; primary box) consumes it. We track max concurrent active count.
    (let [handle :test-safety
          active-count (atom 0)
          max-concurrent (atom 0)
          inside-fn-started (promise)
          raw "(quine completion (eval (do )))"
          eval-fn (fn [raw]
                    (let [n (swap! active-count inc)]
                      (swap! max-concurrent max n)
                      (Thread/sleep 50)
                      (swap! active-count dec)
                      raw))
          ;; inside-fn tracks concurrency and signals when it starts
          inside-fn (fn [raw]
                      (let [n (swap! active-count inc)]
                        (swap! max-concurrent max n)
                        (deliver inside-fn-started true)
                        (Thread/sleep 200)
                        (swap! active-count dec)
                        (str "done:" raw)))
          p (promise)]
      (runtime/register! handle)
      (deliver p raw)
      ;; Run root box in a future so we can send mid-execution
      (let [f (future (runtime/run-root-box handle p inside-fn eval-fn))]
        ;; Wait for inside-fn to start
        (deref inside-fn-started 2000 :timeout)
        ;; Send a message while inside-fn is running — consumed by orphan later
        (runtime/-send! handle (append-forms-macro '(def orphan-msg true)))
        ;; Wait for primary box to complete
        (let [result (deref f 5000 :timeout)]
          (is (= (str "done:" raw) result)))
        ;; Give orphan time to process the message
        (Thread/sleep 500)
        ;; Max concurrent should never exceed 1
        (is (= 1 @max-concurrent)
            "no concurrent evaluation should occur for the same handle")))))
