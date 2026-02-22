(ns spell.comm-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.comm :as comm]
            [spell.core :as spell]
            [spell.eval :as eval]
            [spell.provider :as provider]
            [spell.test-helpers :as th]))

;; Clean registry between tests
(use-fixtures :each
  (fn [f]
    (reset! comm/registry {})
    (f)
    (reset! comm/registry {})))

;; =============================================================================
;; Unit tests (no LLM)
;; =============================================================================

(deftest register-test
  (testing "register! creates registry entry"
    (comm/register! :h1)
    (is (contains? @comm/registry :h1))
    (is (some? (:inbox (get @comm/registry :h1))))
    (is (some? (:signal (get @comm/registry :h1))))
    (is (some? (:has-box (get @comm/registry :h1))))
    (is (some? (:completed (get @comm/registry :h1)))))

  (testing "register! throws on duplicate handle"
    (is (thrown-with-msg? Exception #"already registered"
          (comm/register! :h1)))))

(deftest box-with-inside-fn-test
  (testing "box with inside-fn applies fn to raw immediately"
    (let [handle :test-box
          inside-fn (fn [raw] (str "evaluated:" raw))
          p (promise)]
      (comm/register! handle)
      (deliver p "hello")
      (is (= "evaluated:hello" (comm/box handle p inside-fn))))))

(deftest box-with-inbox-transform-test
  (testing "box applies inbox transform before inside-fn"
    (let [handle :test-transform
          inside-fn (fn [raw] (str "eval:" raw))
          p (promise)]
      (comm/register! handle)
      ;; Pre-load inbox with a transform
      (comm/-send! handle (fn [raw] (str "pre:" raw)))
      (deliver p "hello")
      ;; transform("hello") = "pre:hello", inside-fn("pre:hello") = "eval:pre:hello"
      (is (= "eval:pre:hello" (comm/box handle p inside-fn))))))

(deftest box-no-transform-identity-test
  (testing "box with empty inbox passes raw through unchanged"
    (let [handle :test-identity
          inside-fn (fn [raw] (str "got:" raw))
          p (promise)]
      (comm/register! handle)
      (deliver p "hello")
      (is (= "got:hello" (comm/box handle p inside-fn))))))

(deftest has-box-invariant-test
  (testing "has-box is false after box completes"
    (let [handle :test-hasbox
          p (promise)]
      (comm/register! handle)
      (deliver p "x")
      (comm/box handle p identity)
      (is (false? @(:has-box (get @comm/registry handle)))))))

(deftest send-msg-fn-composes-correctly-test
  (testing "send-msg-fn composes f into inbox transform"
    (let [handle :test-compose
          inside-fn (fn [raw] (.toUpperCase ^String raw))
          p (promise)]
      (comm/register! handle)
      ;; Send f that prepends "pre:" — transform applied before inside-fn
      (comm/send-msg-fn (fn [raw] (str "pre:" raw)) handle)
      (deliver p "hello")
      ;; transform("hello") = "pre:hello", inside-fn("pre:hello") = "PRE:HELLO"
      (is (= "PRE:HELLO" (comm/box handle p inside-fn))))))

(deftest multiple-sends-compose-test
  (testing "multiple sends compose in FIFO order"
    (let [handle :test-multi
          inside-fn (fn [raw] (.toUpperCase ^String raw))
          p (promise)]
      (comm/register! handle)
      ;; Send two transforms: first adds "A:", then second adds "B:"
      (comm/send-msg-fn (fn [raw] (str "A:" raw)) handle)
      (comm/send-msg-fn (fn [raw] (str "B:" raw)) handle)
      ;; FIFO: B(A(raw)) = "B:A:HELLO" -> uppercase = "B:A:HELLO"
      (deliver p "hello")
      (is (= "B:A:HELLO" (comm/box handle p inside-fn))))))

(deftest ask-asserts-outside-context-test
  (testing "ask with msg throws when not in agent context"
    (is (thrown-with-msg? Exception #"not inside an agent context"
          (comm/ask-builtin :some-target "hello"))))
  (testing "ask without msg throws when not in agent context"
    (is (thrown-with-msg? Exception #"not inside an agent context"
          (comm/ask-builtin :some-target)))))

(deftest send-test
  (testing "send sends def message with :from and :body to target"
    (let [h-sender :test-sender
          h-target :test-target
          received (atom nil)
          inside-fn (fn [raw] (reset! received raw) raw)
          p (promise)]
      (comm/register! h-sender)
      (comm/register! h-target)
      (binding [comm/*current-handle* h-sender]
        (comm/send 42 h-target))
      ;; Process the message through box
      (deliver p "(quine completion (eval (do )))")
      (comm/box h-target p inside-fn)
      ;; Should contain def with :from and :body
      (is (.contains ^String @received ":from :test-sender"))
      (is (.contains ^String @received ":body 42"))
      (is (.contains ^String @received "(def msg-")))))

(deftest reply-test
  (testing "reply extracts :from from message map and sends back"
    (let [h-a :reply-a
          h-b :reply-b
          b-received (atom nil)
          inside-fn (fn [raw] (reset! b-received raw) raw)
          p (promise)]
      (comm/register! h-a)
      (comm/register! h-b)
      ;; Simulate a message map that h-a would have received from h-b
      (let [fake-msg {:from :reply-b :body "hello"}]
        (binding [comm/*current-handle* h-a]
          (comm/reply fake-msg "reply-value")))
      ;; Process the message at h-b
      (deliver p "(quine completion (eval (do )))")
      (comm/box h-b p inside-fn)
      (is (.contains ^String @b-received ":from :reply-a"))
      (is (.contains ^String @b-received ":body \"reply-value\"")))))

(deftest dynamic-vars-bound-in-box-test
  (testing "*current-handle* and *current-raw* are bound during box execution"
    (let [handle :test-dynvars
          captured (atom {})
          inside-fn (fn [raw]
                    (reset! captured {:handle comm/*current-handle*
                                      :raw    comm/*current-raw*})
                    raw)
          p (promise)]
      (comm/register! handle)
      (deliver p "test-raw")
      (comm/box handle p inside-fn)
      (is (= :test-dynvars (:handle @captured)))
      (is (= "test-raw" (:raw @captured))))))

(deftest box-root-detection-test
  (testing "run-root-box delivers :completed"
    (let [handle :test-root
          eval-fn (fn [raw] :result)
          p (promise)]
      (comm/register! handle)
      (deliver p "raw")
      ;; Capture the completed promise before box runs
      (let [cp @(:completed (get @comm/registry handle))
            result (comm/run-root-box handle p (comm/make-awake-fn eval-fn) eval-fn)]
        (is (= :result result))
        ;; Completed promise should have been delivered with result
        (is (= :result (deref cp 100 :timeout))))))

  (testing "plain box (non-root) skips root cleanup"
    (let [handle :test-nonroot
          inside-fn (fn [raw] :result)
          p (promise)]
      (comm/register! handle)
      (deliver p "raw")
      (is (= :result (comm/box handle p inside-fn))))))

(deftest box-handles-exception-promise-test
  (testing "box rethrows exception delivered to promise"
    (let [handle :test-ex
          p (promise)]
      (comm/register! handle)
      (deliver p (ex-info "API error" {:status 500}))
      (is (thrown-with-msg? Exception #"API error"
            (comm/box handle p identity))))))

;; =============================================================================
;; Integration tests (with DummyProvider)
;; =============================================================================

(deftest llm-still-works-unchanged-test
  (testing "basic -llm flow works through box"
    (let [{:keys [llm]} (th/make-test-llm {:response "(def return 42))"})]
      (is (= 42 (llm "(do "))))))

(deftest llm-nested-still-works-test
  (testing "nested llm calls work through box (llm is effect-only)"
    (let [call-count (atom 0)
          responses ["'(cat \"hello \" (llm-self \"(eval (do \")))"
                     "'\"world\")))"]]
      (let [{:keys [llm]} (th/make-test-llm
                            {:response-fn (fn [_]
                                            (let [r (nth responses @call-count)]
                                              (swap! call-count inc)
                                              r))})]
        (is (= "hello world" (llm "(eval (do ")))))))

(deftest ask-no-msg-blocks-send-unblocks-test
  (testing "ask(target) pokes target and blocks until send"
    (let [a-started (promise)
          h-a :agent-a
          h-b :agent-b
          first? (atom true)
          ;; A's eval-fn: first call asks B; second call (after wake) returns raw
          a-eval-fn (fn [raw]
                       (if (compare-and-set! first? true false)
                         (do (deliver a-started true)
                             (comm/ask-builtin h-b))
                         (str "from-b:" raw)))
          p (promise)]
      ;; Register both handles
      (comm/register! h-a)
      (comm/register! h-b)
      (deliver p "(quine completion (eval (do )))")
      (let [fa (future (comm/box h-a p (comm/make-awake-fn a-eval-fn)))]
        ;; Wait for A to start
        (deref a-started 2000 :timeout)
        (Thread/sleep 50)
        ;; Send a message transform to A
        (comm/-send! h-a (fn [raw] (str raw "extra ")))
        (is (string? (deref fa 5000 :timeout)))))))

(deftest start-box-responds-to-send-test
  (testing "start-box processes a message sent after initial sleep"
    (let [handle :test-orphan
          received (atom nil)
          eval-fn (fn [raw] (reset! received raw) (str "orphan:" raw))]
      (comm/start-box handle eval-fn "raw-data")
      ;; Give the root box time to start and block on signal
      (Thread/sleep 100)
      ;; Send to the sleeping agent
      (comm/send-msg-fn identity handle)
      ;; Give time to process
      (Thread/sleep 200)
      ;; The agent ran; we can verify no exceptions and handle still valid
      (is (contains? @comm/registry handle)))))

;; =============================================================================
;; Start-box (dormant agent) tests
;; =============================================================================

(deftest start-box-sleeps-until-message-test
  (testing "start-box registers and sleeps — agent wakes on send"
    (let [handle :test-dormant
          received (atom nil)
          eval-fn (fn [raw] (reset! received raw) raw)
          completion "(quine completion (eval (do )))"]
      (comm/start-box handle eval-fn completion)
      ;; Give the root box time to start and block on signal
      (Thread/sleep 100)
      ;; Agent should be registered and sleeping
      (is (contains? @comm/registry handle))
      ;; Send a transform that appends to the stored raw
      (comm/send-msg-fn (fn [raw] (str raw "extra")) handle)
      ;; Give time to process
      (Thread/sleep 200)
      ;; eval-fn saw the stored completion with the appended message
      (is (some? @received))
      (is (.contains ^String @received "quine completion")
          "stored completion is the base for message composition")
      (is (.contains ^String @received "extra")
          "sent transform was applied to stored completion"))))

(deftest start-box-no-initial-eval-test
  (testing "start-box does not evaluate the stored completion"
    (let [handle :test-no-eval
          eval-count (atom 0)
          eval-fn (fn [raw] (swap! eval-count inc) raw)]
      (comm/start-box handle eval-fn "(quine completion (eval (do )))")
      ;; Give time for any async processing
      (Thread/sleep 200)
      ;; eval-fn should NOT have been called — agent is sleeping, not evaluating
      (is (zero? @eval-count)
          "dormant agent should not evaluate at registration time"))))

;; =============================================================================
;; Handle? tests
;; =============================================================================

(deftest handle?-test
  (testing "handle? returns true for registered handles"
    (comm/register! :handle-q)
    (is (true? (comm/handle? :handle-q))))
  (testing "handle? returns false for unregistered handles"
    (is (false? (comm/handle? :nonexistent)))))


;; =============================================================================
;; Handle inheritance tests (with DummyProvider)
;; =============================================================================

(deftest handle-inheritance-test
  (testing "llm-self calls inherit the parent's handle"
    ;; All effect builtins (agents/current-handle, llm-self) go through eval's second pass.
    (let [call-count (atom 0)
          responses [;; Outer: use eval to access current-handle and llm-self via double-eval
                     "'(list (agents/current-handle) (llm-self \"(eval (do \")))"
                     ;; Inner: return current-handle (via eval)
                     "'(agents/current-handle)))"]]
      (let [{:keys [llm]} (th/make-test-llm
                            {:response-fn (fn [_]
                                            (let [r (nth responses @call-count)]
                                              (swap! call-count inc)
                                              r))})]
        (let [result (llm "(eval (do ")]
          ;; result is (h1 h2) — both should be the same handle
          (is (= (first result) (second result))))))))

;; =============================================================================
;; Spawn tests (with DummyProvider)
;; =============================================================================

(deftest spawn-returns-handle-test
  (testing "spawn returns a keyword handle (handle persists after completion)"
    (let [{:keys [llm]} (th/make-test-llm
                          {:response-fn (fn [_] "42)")})]
      (let [handle (comm/spawn llm "(do ")]
        (is (keyword? handle))
        ;; Wait for spawn future to finish — handle persists (no unregister)
        (deref @(:completed (get @comm/registry handle)) 5000 :timeout)
        (is (comm/handle? handle))))))



(deftest spawn-sets-parent-handle-test
  (testing "spawned agent sees spawner's handle via parent-handle"
    (let [call-count (atom 0)
          responses [;; Parent: all effect builtins via eval
                     "'(let [my-h (agents/current-handle) child-result (llm-self \"(eval (do \")] (list my-h child-result)))"
                     ;; Inner llm-self (inherits handle, not spawned): return nil for parent-handle
                     "'(agents/parent-handle)))"]]
      ;; First test: llm-self inherits handle, so parent-handle is nil (not spawned)
      (let [{:keys [llm]} (th/make-test-llm
                            {:response-fn (fn [_]
                                            (let [r (nth responses @call-count)]
                                              (swap! call-count inc)
                                              r))})]
        (let [result (llm "(eval (do ")]
          ;; parent-handle should be nil for non-spawned agents
          (is (nil? (second result)))))))

  (testing "spawn stores parent-handle in registry"
    (let [parent-h :test-parent
          child-fn (fn [raw] "done")
          p (promise)]
      (comm/register! parent-h)
      ;; Simulate spawn from within parent context
      (binding [comm/*current-handle* parent-h]
        (let [child-h (keyword (gensym "child-"))]
          (comm/register! child-h parent-h)
          (deliver p "raw")
          (comm/box child-h p child-fn)
          (is (= parent-h (:parent-handle (get @comm/registry child-h)))))))))

(deftest spawn-ask-test
  (testing "spawn-ask spawns child and blocks until child sends back"
    (let [parent-h :sr-parent
          ;; Mock child llm-fn: send 42 to parent
          child-llm-fn (fn [_prompt handle]
                          ;; Simulate the-llm behavior: register is done by spawn,
                          ;; just need to use box to run
                          (let [parent (:parent-handle (get @comm/registry handle))
                                inside-fn (fn [_raw]
                                          (comm/send 42 parent)
                                          :done)
                                p (promise)]
                            (deliver p "(quine completion (eval (do )))")
                            (comm/run-root-box handle p inside-fn inside-fn)))]
      (comm/register! parent-h)
      (let [parent-result
            (future
              (binding [comm/*current-handle* parent-h
                        comm/*current-raw* "(quine completion (eval (do )))"
                        comm/*current-eval-fn* identity]
                (comm/spawn-ask child-llm-fn "test")))]
        ;; spawn-ask blocks until child sends; child runs in a future
        (let [result (deref parent-result 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result ":body 42")))))))

(deftest spawn-addressable-test
  (testing "spawned agent can be sent to (handle is registered)"
    ;; spawn and llm-self are effect-builtins: accessed via eval double-evaluation.
    (let [call-count (atom 0)
          responses [;; Outer: use eval to access spawn+llm-self via double-eval
                     "(eval (do '(let [w (agents/spawn llm-self \"(do \")] (not (nil? w)))))"
                     ;; Worker: just return 77
                     "77)"]]
      (let [{:keys [llm]} (th/make-test-llm
                            {:response-fn (fn [_]
                                            (let [r (nth responses @call-count)]
                                              (swap! call-count inc)
                                              r))})]
        (is (= true (llm "(do ")))))))

;; =============================================================================
;; Ask tests
;; =============================================================================

(deftest ask-sends-and-blocks-test
  (testing "ask sends message to target and blocks until reply arrives"
    (let [h-a :ask-agent-a
          h-b :ask-agent-b
          a-raw "(quine completion (eval (do )))"
          b-raw "(quine completion (eval (do )))"
          a-started (promise)
          b-received (atom nil)
          a-first? (atom true)
          ;; A's eval-fn: first call asks(B, "hello"); second call returns raw
          a-eval-fn (fn [raw]
                      (if (compare-and-set! a-first? true false)
                        (do (deliver a-started true)
                            (comm/ask-builtin h-b "hello"))
                        raw))
          ;; B's eval-fn: captures what it receives, then replies to A
          b-eval-fn (fn [raw]
                      (reset! b-received raw)
                      ;; Reply to A via send-msg-fn
                      (comm/send-msg-fn (fn [raw] (str raw "(def reply-from-b true) ")) h-a)
                      "b-done")
          pa (promise)]
      (comm/register! h-a)
      ;; B starts sleeping in a root box via start-box
      (comm/start-box h-b b-eval-fn b-raw)
      (Thread/sleep 50)
      (deliver pa a-raw)
      (let [fa (future (comm/box h-a pa (comm/make-awake-fn a-eval-fn)))]
        (deref a-started 2000 :timeout)
        ;; A should unblock when B replies
        (let [result (deref fa 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result "reply-from-b"))
          ;; B should have received the ask message
          (is (some? @b-received))
          (is (.contains ^String @b-received ":body \"hello\""))
          (is (.contains ^String @b-received ":from :ask-agent-a")))))))

(deftest ask-no-msg-wakes-target-test
  (testing "ask(target) sends a poke to target, waking it"
    (let [h-a :ask-wake-a
          h-b :ask-wake-b
          a-raw "(quine completion (eval (do )))"
          b-raw "(quine completion (eval (do )))"
          a-started (promise)
          b-received (atom nil)
          a-first? (atom true)
          ;; A's eval-fn: first call asks(B); second call returns raw
          a-eval-fn (fn [raw]
                      (if (compare-and-set! a-first? true false)
                        (do (deliver a-started true)
                            (comm/ask-builtin h-b))
                        raw))
          ;; B's eval-fn: captures the poke, then replies to A
          b-eval-fn (fn [raw]
                      (reset! b-received raw)
                      (comm/send-msg-fn (fn [raw] (str raw "(def reply-from-b true) ")) h-a)
                      "b-done")
          pa (promise)]
      (comm/register! h-a)
      ;; B starts sleeping in a root box via start-box
      (comm/start-box h-b b-eval-fn b-raw)
      (Thread/sleep 50)
      (deliver pa a-raw)
      (let [fa (future (comm/box h-a pa (comm/make-awake-fn a-eval-fn)))]
        (deref a-started 2000 :timeout)
        (let [result (deref fa 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result "reply-from-b"))
          (is (some? @b-received))
          (is (.contains ^String @b-received ":from :ask-wake-a"))
          (is (.contains ^String @b-received ":expects-response true")))))))

;; =============================================================================
;; Multi-target ask tests
;; =============================================================================

(deftest ask-multi-asserts-outside-context-test
  (testing "ask with vector throws when not in agent context"
    (is (thrown-with-msg? Exception #"not inside an agent context"
          (comm/ask-builtin [:a :b]))))
  (testing "ask with empty vector throws"
    (comm/register! :dummy)
    (binding [comm/*current-handle* :dummy
              comm/*current-raw* "(quine completion (eval (do )))"
              comm/*current-eval-fn* identity]
      (is (thrown-with-msg? Exception #"empty target list"
            (comm/ask-builtin []))))))

(deftest ask-multi-waits-for-all-test
  (testing "multi-target ask waits for all targets to complete"
    (let [h-parent :multi-parent
          h-a :multi-child-a
          h-b :multi-child-b
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"
          eval-a (fn [raw] :result-a)
          eval-b (fn [raw] :result-b)]
      (comm/register! h-parent)
      (comm/register! h-a :some-spawner)
      (comm/register! h-b :some-spawner)
      (let [cp-a (promise)
            cp-b (promise)]
        (deliver cp-a child-raw)
        (deliver cp-b child-raw)
        (let [result-future
              (future
                (binding [comm/*current-handle* h-parent
                          comm/*current-raw*    parent-raw
                          comm/*current-eval-fn* identity]
                  (comm/ask-builtin [h-a h-b])))]
          (Thread/sleep 50)
          ;; Only child A completes — parent should NOT wake yet
          (future (comm/run-root-box h-a cp-a
                    (comm/make-awake-fn eval-a) eval-a))
          (Thread/sleep 100)
          (is (not (realized? result-future)) "parent should still be blocked")
          ;; Now child B completes — parent should wake with combined results
          (comm/run-root-box h-b cp-b
            (comm/make-awake-fn eval-b) eval-b)
          (let [result (deref result-future 5000 :timeout)]
            (is (string? result))
            (is (.contains ^String result ":result-a"))
            (is (.contains ^String result ":result-b"))))))))

(deftest ask-multi-single-target-test
  (testing "ask with single-element vector works"
    (let [h-parent :multi-single-parent
          h-child :multi-single-child
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"
          child-eval-fn (fn [raw] :child-done)]
      (comm/register! h-parent)
      (comm/register! h-child :some-spawner)
      (let [cp (promise)]
        (deliver cp child-raw)
        (let [result-future
              (future
                (binding [comm/*current-handle* h-parent
                          comm/*current-raw*    parent-raw
                          comm/*current-eval-fn* identity]
                  (comm/ask-builtin [h-child])))]
          (Thread/sleep 50)
          (comm/run-root-box h-child cp
            (comm/make-awake-fn child-eval-fn) child-eval-fn)
          (let [result (deref result-future 5000 :timeout)]
            (is (string? result))
            (is (.contains ^String result ":child-done"))))))))

(deftest ask-multi-concurrent-completions-test
  (testing "concurrent target completions all contribute to result"
    (let [h-parent :multi-conc-parent
          targets (mapv #(keyword (str "multi-conc-child-" %)) (range 5))
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"]
      (comm/register! h-parent)
      (doseq [t targets] (comm/register! t :some-spawner))
      (let [result-future
            (future
              (binding [comm/*current-handle* h-parent
                        comm/*current-raw*    parent-raw
                        comm/*current-eval-fn* identity]
                (comm/ask-builtin targets)))]
        (Thread/sleep 50)
        ;; All children complete concurrently
        (let [box-futures
              (mapv (fn [t]
                      (let [cp (promise)]
                        (deliver cp child-raw)
                        (future
                          (comm/run-root-box t cp
                            (comm/make-awake-fn (fn [_] (name t)))
                            (fn [_] (name t))))))
                    targets)]
          (doseq [bf box-futures] (deref bf 2000 :timeout)))
        ;; Parent wakes with all results
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          ;; All target names should appear in the combined result
          (doseq [t targets]
            (is (.contains ^String result (name t)))))))))

(deftest ask-multi-completion-notifier-test
  (testing "completion notifier fires when all target root boxes complete"
    (let [h-parent :multi-cn-parent
          h-child-a :multi-cn-child-a
          h-child-b :multi-cn-child-b
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"
          eval-a (fn [raw] :returned-a)
          eval-b (fn [raw] :returned-b)]
      (comm/register! h-parent)
      (comm/register! h-child-a :some-spawner)
      (comm/register! h-child-b :some-spawner)
      (let [cp-a (promise)
            cp-b (promise)]
        (deliver cp-a child-raw)
        (deliver cp-b child-raw)
        (let [result-future
              (future
                (binding [comm/*current-handle* h-parent
                          comm/*current-raw*    parent-raw
                          comm/*current-eval-fn* identity]
                  (comm/ask-builtin [h-child-a h-child-b])))]
          (Thread/sleep 50)
          ;; Both children complete
          (future (comm/run-root-box h-child-a cp-a
                    (comm/make-awake-fn eval-a) eval-a))
          (comm/run-root-box h-child-b cp-b
            (comm/make-awake-fn eval-b) eval-b)
          ;; Parent should wake via completion notifier with both results
          (let [result (deref result-future 5000 :timeout)]
            (is (string? result))
            (is (.contains ^String result ":returned-a"))
            (is (.contains ^String result ":returned-b"))))))))

;; =============================================================================
;; Inbox preservation tests (#89)
;; =============================================================================

(deftest pending-transforms-survive-test
  (testing "pending inbox transforms survive when box is entered"
    (let [handle :test-cas
          eval-fn (fn [raw] (str "evaluated:" raw))
          sent-fn (fn [raw] (str "sent:" raw))
          p (promise)]
      (comm/register! handle)
      ;; Simulate: a send happened before box entry
      (comm/send-msg-fn sent-fn handle)
      ;; inbox should have the sent function
      (let [inbox-before @(:inbox (get @comm/registry handle))]
        (is (some? inbox-before) "inbox should have the sent function"))
      ;; Box should process the transform + inside-fn
      ;; Transform: sent-fn("hello") = "sent:hello", inside-fn("sent:hello") = "evaluated:sent:hello"
      (deliver p "hello")
      (is (= "evaluated:sent:hello" (comm/box handle p eval-fn))
            "box should apply the preserved transform then inside-fn")))

  (testing "box with no transforms uses identity for raw"
    (let [handle :test-cas-empty
          eval-fn (fn [raw] (str "evaluated:" raw))
          p (promise)]
      (comm/register! handle)
      ;; inbox is identity (no pending sends)
      (is (= identity @(:inbox (get @comm/registry handle))))
      ;; Box should pass raw through unchanged to inside-fn
      (deliver p "hello")
      (is (= "evaluated:hello" (comm/box handle p eval-fn))))))

(deftest inbox-cas-seeds-when-empty-test
  (testing "inherited -llm seeds inbox when it's empty (no pending sends)"
    ;; Simple recursive llm-self without any sends — should work as before
    (let [call-count (atom 0)
          responses ["'(llm-self \"(eval (do \"))"
                     "99))"]]
      (let [{:keys [llm]} (th/make-test-llm
                            {:response-fn (fn [_]
                                            (let [r (nth responses @call-count)]
                                              (swap! call-count inc)
                                              r))})]
        (is (= 99 (llm "(eval (do ")))))))

;; =============================================================================
;; Event-send tests
;; =============================================================================

(deftest event-send-returns-nil-test
  (testing "event-send returns nil immediately"
    (comm/register! :es-nil)
    (is (nil? (comm/event-send (fn [] {:ok "data"}) :es-nil :test-sender)))))

(deftest event-send-sends-on-ok-test
  (testing "event-send sends message when event-fn returns {:ok val}"
    (let [handle :es-ok
          received (promise)
          eval-fn (fn [raw] (deliver received raw) :done)]
      (comm/start-box handle eval-fn "(quine c (eval (do 1)))")
      (Thread/sleep 50)
      (comm/event-send (fn [] {:ok "event-data"}) handle :test-event)
      ;; Wait for the event to fire and the agent to process it
      (let [raw (deref received 5000 :timeout)]
        (is (not= :timeout raw))
        (is (string? raw))
        (is (.contains ^String raw ":from :test-event"))
        (is (.contains ^String raw ":body \"event-data\""))))))

(deftest event-send-silent-on-non-ok-test
  (testing "event-send does not send when event-fn returns non-:ok"
    (let [handle :es-silent]
      (comm/register! handle)
      (comm/event-send (fn [] {:timeout true}) handle :test-event)
      ;; Wait for the future to complete
      (Thread/sleep 50)
      ;; Inbox should still be identity (no message sent)
      (is (= identity @(:inbox (get @comm/registry handle)))))))

(deftest event-send-notifies-on-exception-test
  (testing "event-send sends {:error msg} when event-fn throws"
    (let [handle :es-ex
          received (promise)
          eval-fn (fn [raw] (deliver received raw) :done)]
      (comm/start-box handle eval-fn "(quine c (eval (do 1)))")
      (Thread/sleep 50)
      (comm/event-send (fn [] (throw (ex-info "boom" {}))) handle :test-event)
      (let [raw (deref received 5000 :timeout)]
        (is (not= :timeout raw))
        (is (string? raw))
        (is (.contains ^String raw ":from :test-event"))
        (is (.contains ^String raw ":error"))))))

;; =============================================================================
;; Event-send abort tests
;; =============================================================================

(deftest event-send-abort-test
  (testing "event-send does not send when event-fn returns {:abort ...}"
    (let [handle :es-abort]
      (comm/register! handle)
      (comm/event-send (fn [] {:abort :reason}) handle :test-event)
      ;; Wait for the future to complete
      (Thread/sleep 50)
      ;; Inbox should still be identity (no message sent)
      (is (= identity @(:inbox (get @comm/registry handle)))))))

;; =============================================================================
;; Completion notifier tests
;; =============================================================================

(deftest completion-notifier-fires-test
  (testing "notifier sends target's completion result to self"
    (let [h-parent :cn-parent
          h-child :cn-child
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"
          child-eval-fn (fn [raw] :child-result)]
      (comm/register! h-parent)
      (comm/register! h-child :some-spawner)
      ;; Start parent blocking with notifier on child
      (let [result-future
            (future
              (binding [comm/*current-handle* h-parent
                        comm/*current-raw*    parent-raw
                        comm/*current-eval-fn* identity]
                (#'comm/install-completion-notifier h-child)
                (comm/block-for-message)))]
        (Thread/sleep 50)
        ;; Child's root box completes
        (let [cp (promise)]
          (deliver cp child-raw)
          (comm/run-root-box h-child cp
            (comm/make-awake-fn child-eval-fn) child-eval-fn))
        ;; Parent should wake with child's result
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result ":body :child-result")))))))

(deftest completion-notifier-stale-signal-test
  (testing "explicit reply before child completes — notifier no-ops on stale signal"
    (let [h-parent :cn-stale-parent
          h-child :cn-stale-child
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"
          received-count (atom 0)]
      (comm/register! h-parent)
      (comm/register! h-child :some-spawner)
      ;; Start parent blocking with notifier on child
      (let [result-future
            (future
              (binding [comm/*current-handle* h-parent
                        comm/*current-raw*    parent-raw
                        comm/*current-eval-fn* (fn [raw] (swap! received-count inc) raw)]
                (#'comm/install-completion-notifier h-child)
                (comm/block-for-message)))]
        (Thread/sleep 50)
        ;; Send explicit reply to parent (wakes parent, consumes signal)
        (binding [comm/*current-handle* h-child]
          (comm/send "explicit-reply" h-parent))
        ;; Parent should wake with the explicit reply
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result "explicit-reply")))
        ;; Now complete the child — notifier fires but deliver-msg-fn
        ;; sees realized signal and no-ops
        (let [cp (promise)]
          (deliver cp child-raw)
          (comm/run-root-box h-child cp
            (comm/make-awake-fn (fn [raw] :child-result)) (fn [raw] :child-result)))
        ;; Wait and verify no additional sends arrived
        (Thread/sleep 100)
        (is (= 1 @received-count)
            "stale signal prevents notifier from delivering extra message")))))

;; =============================================================================
;; deliver-msg-fn tests
;; =============================================================================

(deftest deliver-msg-fn-unrealized-test
  (testing "deliver-msg-fn delivers to unrealized signal"
    (let [handle :dmf-unrealized]
      (comm/register! handle)
      (let [sig @(:signal (get @comm/registry handle))]
        (comm/deliver-msg-fn handle sig (fn [raw] (str "msg:" raw)))
        ;; Signal should be delivered
        (is (realized? sig))
        ;; Inbox should have the transform
        (is (some? @(:inbox (get @comm/registry handle))))))))

(deftest deliver-msg-fn-realized-noop-test
  (testing "deliver-msg-fn no-ops on already-realized signal"
    (let [handle :dmf-realized]
      (comm/register! handle)
      (let [sig @(:signal (get @comm/registry handle))]
        ;; Deliver the signal first (simulate agent waking from something else)
        (deliver sig :wake)
        ;; Now deliver-msg-fn should be a no-op
        (comm/deliver-msg-fn handle sig (fn [raw] (str "msg:" raw)))
        ;; Inbox should still be identity (no transform composed)
        (is (= identity @(:inbox (get @comm/registry handle))))))))

;; =============================================================================
;; Effect guard tests
;; =============================================================================

(deftest effect-guard-blocks-in-first-pass-test
  (testing "effect builtins are unbound in eval's first pass (do block)"
    ;; agents/ — communication effects (namespace-qualified)
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/send 42 :nobody))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/spawn identity "test"))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/ask :nobody "hello"))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/current-handle))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/parent-handle))))
    ;; futures/ — concurrency effect (await is still a core builtin)
    (is (thrown-with-msg? Exception #"Unbound symbol: futures"
          (eval/run-spell '(futures/pmap inc [1 2 3]))))
    ;; io/, globals/ — side-effectful namespaces
    (is (thrown-with-msg? Exception #"Unbound symbol: io"
          (eval/run-spell '(io/sh "echo hi"))))
    (is (thrown-with-msg? Exception #"Unbound symbol: globals"
          (eval/run-spell '(globals/get :roles))))))

(deftest effect-guard-allows-in-second-pass-test
  (testing "dangerous fns work through double-evaluation (eval special form)"
    ;; agents/ask resolves through eval double-evaluation but fails at runtime
    (let [{:keys [llm]} (th/make-test-llm {:response "(agents/ask :nobody \"hello\")))"})]
      (is (thrown-with-msg? Exception #"not inside an agent context|not registered"
            (llm "(eval (do '"))))))
