(ns spell.comm-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.comm :as comm]
            [spell.core :as spell]
            [spell.eval :as eval]
            [spell.provider :as provider]))

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
    (comm/register! :h1 identity)
    (is (contains? @comm/registry :h1))
    (is (some? (:inbox (get @comm/registry :h1))))
    (is (some? (:signal (get @comm/registry :h1))))
    (is (some? (:has-box (get @comm/registry :h1))))
    (is (= identity (:default-inbox-fn (get @comm/registry :h1)))))

  (testing "register! throws on duplicate handle"
    (is (thrown-with-msg? Exception #"already registered"
          (comm/register! :h1 identity)))))

(deftest box-with-pre-seeded-inbox-test
  (testing "box with pre-seeded inbox applies fn to raw immediately"
    (let [handle :test-box
          eval-fn (fn [raw] (str "evaluated:" raw))
          p (promise)]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (deliver p "hello")
      (is (= "evaluated:hello" (comm/box handle handle p))))))

(deftest box-blocks-on-signal-via-sleep-test
  (testing "box with sleep-fn blocks until someone sends"
    (let [handle :test-block
          eval-fn (fn [raw] (str "got:" raw))
          p (promise)]
      (comm/register! handle eval-fn)
      ;; Seed inbox with a sleep fn (simulating what orphan-box would do)
      (reset! (:inbox (get @comm/registry handle)) (#'comm/make-sleep-fn handle))
      (deliver p "raw")
      (let [result (future (comm/box handle handle p))]
        (Thread/sleep 50)
        (is (not (realized? result)))
        ;; Send identity — compose with default-inbox-fn (eval-fn)
        (comm/send identity handle)
        (is (= "got:raw" (deref result 2000 :timeout)))))))

(deftest has-box-invariant-test
  (testing "has-box is false after box completes"
    (let [handle :test-hasbox
          eval-fn identity
          p (promise)]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (deliver p "x")
      (comm/box handle handle p)
      (is (false? @(:has-box (get @comm/registry handle)))))))

(deftest send-composes-correctly-test
  (testing "send composes f before default-inbox-fn"
    (let [handle :test-compose
          ;; default-inbox-fn uppercases
          eval-fn (fn [raw] (.toUpperCase ^String raw))
          p (promise)]
      (comm/register! handle eval-fn)
      ;; Send f that prepends "pre:" — composition: eval-fn(f(raw))
      (comm/send (fn [raw] (str "pre:" raw)) handle)
      (deliver p "hello")
      ;; f("hello") = "pre:hello", eval-fn("pre:hello") = "PRE:HELLO"
      (is (= "PRE:HELLO" (comm/box handle handle p))))))

(deftest multiple-sends-compose-test
  (testing "multiple sends compose in order"
    (let [handle :test-multi
          eval-fn (fn [raw] (.toUpperCase ^String raw))
          p (promise)]
      (comm/register! handle eval-fn)
      ;; Send two transforms: first adds "a:", then second adds "b:"
      (comm/send (fn [raw] (str "a:" raw)) handle)
      (comm/send (fn [raw] (str "b:" raw)) handle)
      ;; Composition: eval-fn(a:(b:raw)) = "A:B:HELLO"
      (deliver p "hello")
      (is (= "A:B:HELLO" (comm/box handle handle p))))))

(deftest ask-asserts-outside-context-test
  (testing "ask with msg throws when not in agent context"
    (is (thrown-with-msg? Exception #"not inside an agent context"
          (comm/ask-builtin :some-target "hello"))))
  (testing "ask without msg throws when not in agent context"
    (is (thrown-with-msg? Exception #"not inside an agent context"
          (comm/ask-builtin :some-target)))))

(deftest send-msg-test
  (testing "send-msg sends def message with :from and :value to target"
    (let [h-sender :test-sender
          h-target :test-target
          received (atom nil)
          eval-fn (fn [raw] (reset! received raw) raw)
          p (promise)]
      (comm/register! h-sender identity)
      (comm/register! h-target eval-fn)
      (reset! (:inbox (get @comm/registry h-target)) eval-fn)
      (binding [comm/*current-handle* h-sender]
        (comm/send-msg 42 h-target))
      ;; Process the message through box
      (deliver p "(quine completion (eval (do )))")
      (comm/box h-target h-target p)
      ;; Should contain def with :from and :value
      (is (.contains ^String @received ":from :test-sender"))
      (is (.contains ^String @received ":value 42"))
      (is (.contains ^String @received "(def msg-")))))

(deftest reply-send-test
  (testing "reply-send extracts :from from message map and sends back"
    (let [h-a :reply-a
          h-b :reply-b
          b-received (atom nil)
          eval-fn (fn [raw] (reset! b-received raw) raw)
          p (promise)]
      (comm/register! h-a identity)
      (comm/register! h-b eval-fn)
      (reset! (:inbox (get @comm/registry h-b)) eval-fn)
      ;; Simulate a message map that h-a would have received from h-b
      (let [fake-msg {:from :reply-b :value "hello"}]
        (binding [comm/*current-handle* h-a]
          (comm/reply-send fake-msg "reply-value")))
      ;; Process the message at h-b
      (deliver p "(quine completion (eval (do )))")
      (comm/box h-b h-b p)
      (is (.contains ^String @b-received ":from :reply-a"))
      (is (.contains ^String @b-received ":value \"reply-value\"")))))

(deftest dynamic-vars-bound-in-box-test
  (testing "*current-handle* and *current-raw* are bound during box execution"
    (let [handle :test-dynvars
          captured (atom {})
          eval-fn (fn [raw]
                    (reset! captured {:handle comm/*current-handle*
                                      :raw    comm/*current-raw*})
                    raw)
          p (promise)]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (deliver p "test-raw")
      (comm/box handle handle p)
      (is (= :test-dynvars (:handle @captured)))
      (is (= "test-raw" (:raw @captured))))))

(deftest box-root-detection-test
  (testing "root box (parent != handle) calls notify-waiters and orphan-box"
    (let [handle :test-root
          parent :some-parent
          eval-fn (fn [raw] :result)
          p (promise)]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (deliver p "raw")
      ;; parent != handle → root
      (let [result (comm/box handle parent p)]
        (is (= :result result))
        ;; Waiters should be reset (empty, no waiters registered)
        (is (= #{} @(:waiters (get @comm/registry handle)))))))

  (testing "non-root box (parent = handle) skips root cleanup"
    (let [handle :test-nonroot
          eval-fn (fn [raw] :result)
          p (promise)]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (deliver p "raw")
      ;; parent = handle → not root
      (is (= :result (comm/box handle handle p))))))

(deftest box-throws-on-empty-inbox-test
  (testing "box throws when inbox is nil (not seeded)"
    (let [handle :test-empty
          p (promise)]
      (comm/register! handle identity)
      ;; Don't seed inbox
      (deliver p "raw")
      (is (thrown-with-msg? Exception #"empty inbox"
            (comm/box handle handle p))))))

(deftest box-handles-exception-promise-test
  (testing "box rethrows exception delivered to promise"
    (let [handle :test-ex
          p (promise)]
      (comm/register! handle identity)
      (reset! (:inbox (get @comm/registry handle)) identity)
      (deliver p (ex-info "API error" {:status 500}))
      (is (thrown-with-msg? Exception #"API error"
            (comm/box handle handle p))))))

;; =============================================================================
;; Integration tests (with DummyProvider)
;; =============================================================================

(deftest llm-still-works-unchanged-test
  (testing "basic -llm flow works through box"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return 42))"})
      (is (= 42 (spell/llm "(do "))))))

(deftest llm-nested-still-works-test
  (testing "nested llm calls work through box (llm is effect-only)"
    (let [call-count (atom 0)
          responses ["'(cat \"hello \" (llm \"(eval (do \")))"
                     "'\"world\")))"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= "hello world" (spell/llm "(eval (do ")))))))

(deftest ask-no-msg-blocks-send-unblocks-test
  (testing "ask(target) pokes target and blocks until send"
    (let [a-started (promise)
          h-a :agent-a
          h-b :agent-b
          ;; A's initial eval captures handle, then blocks via ask(B)
          a-initial-fn (fn [raw]
                         (deliver a-started true)
                         ;; ask with no msg = poke + block
                         (comm/ask-builtin h-b))
          p (promise)]
      ;; Register both handles (ask pokes target, so target must be registered)
      (comm/register! h-a a-initial-fn)
      (comm/register! h-b identity)
      (reset! (:inbox (get @comm/registry h-a)) a-initial-fn)
      (deliver p "a-raw")
      (let [fa (future (comm/box h-a h-a p))]
        ;; Wait for A to start
        (deref a-started 2000 :timeout)
        (Thread/sleep 50)
        ;; Now send to A via -send! to avoid composition with a-initial-fn
        (comm/-send! h-a (fn [_] (fn [raw] (str "from-b:" raw))))
        (is (= "from-b:a-raw" (deref fa 5000 :timeout)))))))

(deftest orphan-box-responds-to-send-test
  (testing "orphan box processes a message sent after agent completes"
    (let [handle :test-orphan
          eval-fn (fn [raw] (str "orphan:" raw))
          p (promise)]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (deliver p "first")
      ;; First box call — immediate (inbox seeded), non-root
      (comm/box handle handle p)
      ;; Create orphan box
      (comm/orphan-box! handle "raw-data")
      ;; Give orphan a moment to start and block
      (Thread/sleep 50)
      ;; Send to orphan
      (comm/send identity handle)
      ;; Give orphan time to process
      (Thread/sleep 100)
      ;; The orphan ran; we can verify no exceptions and handle still valid
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
      ;; Give the orphan box time to start and block on signal
      (Thread/sleep 100)
      ;; Agent should be registered and sleeping
      (is (contains? @comm/registry handle))
      ;; Send a transform that appends to the stored raw
      (comm/send (fn [raw] (str raw "extra")) handle)
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
    (comm/register! :handle-q identity)
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
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (let [result (spell/llm "(eval (do ")]
          ;; result is (h1 h2) — both should be the same handle
          (is (= (first result) (second result))))))))

;; =============================================================================
;; Spawn tests (with DummyProvider)
;; =============================================================================

(deftest spawn-returns-handle-test
  (testing "spawn returns a keyword handle (handle persists after completion)"
    (let [responses ["42)"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_] (first responses))})
        (let [handle (comm/spawn spell/llm "(do ")]
          (is (keyword? handle))
          ;; Wait for spawn future to finish — handle persists (no unregister)
          (Thread/sleep 2000)
          (is (comm/handle? handle)))))))



(deftest spawn-sets-parent-handle-test
  (testing "spawned agent sees spawner's handle via parent-handle"
    (let [call-count (atom 0)
          responses [;; Parent: all effect builtins via eval
                     "'(let [my-h (agents/current-handle) child-result (llm-self \"(eval (do \")] (list my-h child-result)))"
                     ;; Inner llm-self (inherits handle, not spawned): return nil for parent-handle
                     "'(agents/parent-handle)))"]]
      ;; First test: llm-self inherits handle, so parent-handle is nil (not spawned)
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (let [result (spell/llm "(eval (do ")]
          ;; parent-handle should be nil for non-spawned agents
          (is (nil? (second result)))))))

  (testing "spawn stores parent-handle in registry"
    (let [parent-h :test-parent
          child-fn (fn [raw] "done")
          p (promise)]
      (comm/register! parent-h identity)
      ;; Simulate spawn from within parent context
      (binding [comm/*current-handle* parent-h]
        (let [child-h (keyword (gensym "child-"))]
          (comm/register! child-h identity parent-h)
          (reset! (:inbox (get @comm/registry child-h)) child-fn)
          (deliver p "raw")
          (comm/box child-h child-h p)
          (is (= parent-h (:parent-handle (get @comm/registry child-h)))))))))

(deftest spawn-recv-test
  (testing "spawn-recv spawns child and blocks until child sends back"
    (let [parent-h :sr-parent
          ;; Mock child llm-fn: send 42 to parent
          ;; In the new design, spawn registers synchronously,
          ;; so child's llm-fn just needs to send to parent
          child-llm-fn (fn [_prompt handle]
                          ;; Simulate the-llm behavior: register is done by spawn,
                          ;; just need to seed inbox and use box to run
                          (let [parent (:parent-handle (get @comm/registry handle))
                                inbox-fn (fn [_raw]
                                          (comm/send-msg 42 parent)
                                          :done)
                                p (promise)]
                            (reset! (:inbox (get @comm/registry handle)) inbox-fn)
                            (deliver p "(quine completion (eval (do )))")
                            (comm/box handle parent p)))]
      (comm/register! parent-h identity)
      ;; Do NOT seed inbox — recv should block until child sends
      (let [parent-result
            (future
              (binding [comm/*current-handle* parent-h
                        comm/*current-raw* "(quine completion (eval (do )))"]
                (comm/spawn-recv child-llm-fn "test")))]
        ;; spawn-recv blocks until child sends; child runs in a future
        (let [result (deref parent-result 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result ":value 42")))))))

(deftest spawn-addressable-test
  (testing "spawned agent can be sent to (handle is registered)"
    ;; spawn and llm-self are effect-builtins: accessed via eval double-evaluation.
    (let [call-count (atom 0)
          responses [;; Outer: use eval to access spawn+llm-self via double-eval
                     "(eval (do '(let [w (agents/spawn llm-self \"(do \")] (not (nil? w)))))"
                     ;; Worker: just return 77
                     "77)"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= true (spell/llm "(do ")))))))

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
          ;; A's eval-fn: calls ask(B, "hello"), which sends to B and blocks
          a-eval-fn (fn [raw]
                      (deliver a-started true)
                      (comm/ask-builtin h-b "hello"))
          ;; B's eval-fn: captures what it receives, then replies to A
          b-eval-fn (fn [raw]
                      (reset! b-received raw)
                      ;; Reply to A via -send! to avoid re-evaluating a-eval-fn
                      (comm/-send! h-a (fn [_] (fn [_raw] "reply-from-b")))
                      "b-done")
          pa (promise)
          pb (promise)]
      (comm/register! h-a a-eval-fn)
      (comm/register! h-b b-eval-fn)
      (reset! (:inbox (get @comm/registry h-a)) a-eval-fn)
      ;; B starts in a box waiting for messages (sleep-fn)
      (reset! (:inbox (get @comm/registry h-b)) (#'comm/make-sleep-fn h-b))
      (deliver pb b-raw)
      (future (comm/box h-b h-b pb))
      (deliver pa a-raw)
      (let [fa (future (comm/box h-a h-a pa))]
        (deref a-started 2000 :timeout)
        ;; A should unblock when B replies
        (let [result (deref fa 5000 :timeout)]
          (is (= "reply-from-b" result))
          ;; B should have received the ask message
          (is (some? @b-received))
          (is (.contains ^String @b-received ":value \"hello\""))
          (is (.contains ^String @b-received ":from :ask-agent-a")))))))

(deftest ask-no-msg-wakes-target-test
  (testing "ask(target) sends a poke to target, waking it"
    (let [h-a :ask-wake-a
          h-b :ask-wake-b
          a-raw "(quine completion (eval (do )))"
          b-raw "(quine completion (eval (do )))"
          a-started (promise)
          b-received (atom nil)
          ;; A's eval-fn: calls ask(B) which pokes B and blocks
          a-eval-fn (fn [raw]
                      (deliver a-started true)
                      (comm/ask-builtin h-b))
          ;; B's eval-fn: captures the poke, then replies to A
          b-eval-fn (fn [raw]
                      (reset! b-received raw)
                      (comm/-send! h-a (fn [_] (fn [_raw] "reply-from-b")))
                      "b-done")
          pa (promise)
          pb (promise)]
      (comm/register! h-a a-eval-fn)
      (comm/register! h-b b-eval-fn)
      (reset! (:inbox (get @comm/registry h-a)) a-eval-fn)
      ;; B starts with sleep-fn (waiting for messages)
      (reset! (:inbox (get @comm/registry h-b)) (#'comm/make-sleep-fn h-b))
      (deliver pb b-raw)
      (future (comm/box h-b h-b pb))
      (deliver pa a-raw)
      (let [fa (future (comm/box h-a h-a pa))]
        (deref a-started 2000 :timeout)
        (let [result (deref fa 5000 :timeout)]
          (is (= "reply-from-b" result))
          (is (some? @b-received))
          (is (.contains ^String @b-received "(def waiting-for :ask-wake-a)")))))))

;; =============================================================================
;; Multi-target ask tests
;; =============================================================================

(deftest ask-multi-asserts-outside-context-test
  (testing "ask with vector throws when not in agent context"
    (is (thrown-with-msg? Exception #"not inside an agent context"
          (comm/ask-builtin [:a :b]))))
  (testing "ask with empty vector throws"
    (comm/register! :dummy identity)
    (binding [comm/*current-handle* :dummy
              comm/*current-raw* "(quine completion (eval (do )))"]
      (is (thrown-with-msg? Exception #"empty target list"
            (comm/ask-builtin []))))))

(deftest ask-multi-collector-basic-test
  (testing "collector accumulates messages from multiple targets"
    (let [h-parent :multi-parent
          h-a :multi-child-a
          h-b :multi-child-b
          parent-raw "(quine completion (eval (do)))"]
      (comm/register! h-parent identity)
      (comm/register! h-a identity)
      (comm/register! h-b identity)
      ;; Start ask-multi in a future with the parent's context
      (let [result-future
            (future
              (binding [comm/*current-handle* h-parent
                        comm/*current-raw*    parent-raw]
                (comm/ask-builtin [h-a h-b])))]
        (Thread/sleep 50)
        ;; Child A sends to parent
        (binding [comm/*current-handle* h-a]
          (comm/send-msg 42 h-parent))
        ;; Child B sends to parent
        (binding [comm/*current-handle* h-b]
          (comm/send-msg 99 h-parent))
        ;; Parent should unblock with accumulated raw containing both messages
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result ":value 42"))
          (is (.contains ^String result ":value 99")))))))

(deftest ask-multi-single-target-test
  (testing "ask with single-element vector works"
    (let [h-parent :multi-single-parent
          h-child :multi-single-child
          parent-raw "(quine completion (eval (do)))"]
      (comm/register! h-parent identity)
      (comm/register! h-child identity)
      (let [result-future
            (future
              (binding [comm/*current-handle* h-parent
                        comm/*current-raw*    parent-raw]
                (comm/ask-builtin [h-child])))]
        (Thread/sleep 50)
        (binding [comm/*current-handle* h-child]
          (comm/send-msg 7 h-parent))
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result ":value 7")))))))

(deftest ask-multi-concurrent-sends-test
  (testing "concurrent sends from multiple targets are collected correctly"
    (let [h-parent :multi-conc-parent
          targets (mapv #(keyword (str "multi-conc-child-" %)) (range 5))
          parent-raw "(quine completion (eval (do)))"]
      (comm/register! h-parent identity)
      (doseq [t targets] (comm/register! t identity))
      (let [result-future
            (future
              (binding [comm/*current-handle* h-parent
                        comm/*current-raw*    parent-raw]
                (comm/ask-builtin targets)))]
        (Thread/sleep 50)
        ;; All children send concurrently
        (let [send-futures
              (mapv (fn [t]
                      (future
                        (binding [comm/*current-handle* t]
                          (comm/send-msg (name t) h-parent))))
                    targets)]
          (doseq [sf send-futures] (deref sf 2000 :timeout)))
        ;; Parent should have all 5 messages
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          (doseq [t targets]
            (is (.contains ^String result
                           (str ":value \"" (name t) "\"")))))))))

(deftest ask-multi-notify-waiters-test
  (testing "notify-waiters with correct *current-handle* triggers collector"
    (let [h-parent :multi-nw-parent
          h-child :multi-nw-child
          parent-raw "(quine completion (eval (do)))"]
      (comm/register! h-parent identity)
      (comm/register! h-child identity)
      (let [result-future
            (future
              (binding [comm/*current-handle* h-parent
                        comm/*current-raw*    parent-raw]
                (comm/ask-builtin [h-child])))]
        (Thread/sleep 50)
        ;; Simulate child completing — notify-waiters sends spawn-result
        (comm/notify-waiters! h-child :child-returned)
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result "(def spawn-result :child-returned)")))))))

;; =============================================================================
;; Inbox preservation tests (#89)
;; =============================================================================

(deftest inherited-llm-preserves-inbox-test
  (testing "CAS preserves pending inbox content for inherited calls"
    (let [handle :test-cas
          eval-fn (fn [raw] (str "evaluated:" raw))
          sent-fn (fn [raw] (str "sent:" raw))
          p (promise)]
      (comm/register! handle eval-fn)
      ;; Simulate: box drained inbox (nil), then a send happened during eval
      (comm/send sent-fn handle)
      ;; Now inbox has composition from the send
      (let [inbox-before @(:inbox (get @comm/registry handle))]
        (is (some? inbox-before) "inbox should have the sent function")
        ;; Simulate inherited -llm: CAS only seeds if nil
        (let [new-eval-fn (fn [raw] (str "new:" raw))
              cas-result (compare-and-set! (:inbox (get @comm/registry handle))
                                           nil new-eval-fn)]
          (is (false? cas-result) "CAS should fail (inbox non-nil)")
          ;; Inbox should be unchanged — the sent function is preserved
          (is (= inbox-before @(:inbox (get @comm/registry handle)))
              "inbox should still have the sent function"))
        ;; Box should process the preserved sent function
        ;; Composition is (fn [raw] (eval-fn (sent-fn raw)))
        (deliver p "hello")
        (is (= "evaluated:sent:hello" (comm/box handle handle p))
              "box should apply the preserved composition"))))

  (testing "CAS seeds inbox when empty for inherited calls"
    (let [handle :test-cas-empty
          eval-fn (fn [raw] (str "evaluated:" raw))
          p (promise)]
      (comm/register! handle eval-fn)
      ;; inbox is nil (no pending sends)
      (is (nil? @(:inbox (get @comm/registry handle))))
      ;; Simulate inherited -llm: CAS succeeds when nil
      (let [new-eval-fn (fn [raw] (str "new:" raw))
            cas-result (compare-and-set! (:inbox (get @comm/registry handle))
                                         nil new-eval-fn)]
        (is (true? cas-result) "CAS should succeed (inbox was nil)")
        ;; Box should use the CAS-seeded function
        (deliver p "hello")
        (is (= "new:hello" (comm/box handle handle p)))))))

(deftest inbox-cas-seeds-when-empty-test
  (testing "inherited -llm seeds inbox when it's empty (no pending sends)"
    ;; Simple recursive llm-self without any sends — should work as before
    (let [call-count (atom 0)
          responses ["'(llm-self \"(eval (do \"))"
                     "99))"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= 99 (spell/llm "(eval (do ")))))))

;; =============================================================================
;; Spurious wake tests
;; =============================================================================

(deftest sleep-fn-ignores-spurious-wake-test
  (testing "sleep-fn loops when signal fires but inbox is still nil"
    (let [handle :test-spurious
          eval-fn (fn [raw] (str "got:" raw))
          p (promise)]
      (comm/register! handle eval-fn)
      ;; Set up sleep-fn in inbox, enter box
      (reset! (:inbox (get @comm/registry handle)) (#'comm/make-sleep-fn handle))
      (deliver p "raw")
      (let [result (future (comm/box handle handle p))]
        (Thread/sleep 50)
        ;; Pre-deliver the signal (simulating a -send! that was overwritten)
        (deliver @(:signal (get @comm/registry handle)) :spurious)
        ;; Sleep-fn should wake, find empty inbox, and go back to sleep
        (Thread/sleep 100)
        (is (not (realized? result)) "should still be sleeping after spurious wake")
        ;; Now do a real send
        (comm/send identity handle)
        (is (= "got:raw" (deref result 2000 :timeout)))))))

(deftest lazy-default-inbox-fn-resolution-test
  (testing "send resolves default-inbox-fn at call time, not composition time"
    (let [handle :test-lazy
          initial-fn (fn [raw] (str "initial:" raw))
          updated-fn (fn [raw] (str "updated:" raw))
          p (promise)]
      (comm/register! handle initial-fn)
      ;; Send when inbox is nil — composition uses lazy default-inbox-fn
      (comm/send (fn [raw] (str "pre:" raw)) handle)
      ;; Update default-inbox-fn (simulating what the-llm does)
      (swap! comm/registry assoc-in [handle :default-inbox-fn] updated-fn)
      ;; Box processes — should use the UPDATED default-inbox-fn
      (deliver p "hello")
      (is (= "updated:pre:hello" (comm/box handle handle p))))))

;; =============================================================================
;; Effect guard tests
;; =============================================================================

(deftest effect-guard-blocks-in-first-pass-test
  (testing "effect builtins are unbound in eval's first pass (do block)"
    ;; agents/ — communication effects (namespace-qualified)
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/send-msg 42 :nobody))))
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
    ;; (eval (do '(agents/ask ...))) — the quoted expression is double-evaluated
    ;; ask will still fail at runtime (no registered handle), but the symbol resolves
    (is (thrown-with-msg? Exception #"not inside an agent context|not registered"
          (eval/run-spell '(eval (do '(agents/ask :nobody "hello"))))))))
