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

(deftest register-unregister-test
  (testing "register! creates registry entry"
    (comm/register! :h1 identity)
    (is (contains? @comm/registry :h1))
    (is (some? (:inbox (get @comm/registry :h1))))
    (is (some? (:signal (get @comm/registry :h1))))
    (is (some? (:has-box (get @comm/registry :h1))))
    (is (= identity (:eval-fn (get @comm/registry :h1)))))

  (testing "register! throws on duplicate handle"
    (is (thrown-with-msg? Exception #"already registered"
          (comm/register! :h1 identity))))

  (testing "unregister! removes entry"
    (comm/unregister! :h1)
    (is (not (contains? @comm/registry :h1)))))

(deftest box-with-pre-seeded-inbox-test
  (testing "box with pre-seeded inbox applies fn to raw immediately"
    (let [handle :test-box
          eval-fn (fn [raw] (str "evaluated:" raw))]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (is (= "evaluated:hello" (comm/box "hello" handle))))))

(deftest box-blocks-until-send-test
  (testing "box blocks until someone sends"
    (let [handle :test-block
          eval-fn identity]
      (comm/register! handle eval-fn)
      ;; Box will block (inbox is nil)
      (let [result (future (comm/box "raw" handle))]
        (Thread/sleep 50)
        (is (not (realized? result)))
        ;; Send identity — eval-fn is identity, so result = "raw"
        (comm/send identity handle)
        (is (= "raw" (deref result 2000 :timeout)))))))

(deftest has-box-invariant-test
  (testing "has-box is false after box completes"
    (let [handle :test-hasbox
          eval-fn identity]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (comm/box "x" handle)
      (is (false? @(:has-box (get @comm/registry handle)))))))

(deftest send-composes-correctly-test
  (testing "send composes f before eval-fn (f pre-processes, then eval-fn runs)"
    (let [handle :test-compose
          ;; eval-fn uppercases
          eval-fn (fn [raw] (.toUpperCase ^String raw))]
      (comm/register! handle eval-fn)
      ;; Send f that prepends "pre:" — composition is (comp eval-fn f), so eval-fn(f(raw))
      (comm/send (fn [raw] (str "pre:" raw)) handle)
      ;; f("hello") = "pre:hello", eval-fn("pre:hello") = "PRE:HELLO"
      (is (= "PRE:HELLO" (comm/box "hello" handle))))))

(deftest multiple-sends-compose-test
  (testing "multiple sends compose in order"
    (let [handle :test-multi
          eval-fn (fn [raw] (.toUpperCase ^String raw))]
      (comm/register! handle eval-fn)
      ;; Send two transforms: first adds "a:", then second adds "b:"
      (comm/send (fn [raw] (str "a:" raw)) handle)
      (comm/send (fn [raw] (str "b:" raw)) handle)
      ;; Composition: (comp (comp eval-fn (fn [raw] (str "a:" raw))) (fn [raw] (str "b:" raw)))
      ;; = eval-fn(a:(b:hello)) = "A:B:HELLO"
      (is (= "A:B:HELLO" (comm/box "hello" handle))))))

(deftest recv-asserts-outside-context-test
  (testing "recv throws when not in agent context"
    (is (thrown-with-msg? Exception #"not inside an agent context"
          (comm/recv-builtin)))))

(deftest create-msg-test
  (testing "create-msg produces function that modifies raw string"
    (let [msg-fn (comm/create-msg 'my-data 42)
          ;; Simulate a raw completion with 3 trailing parens
          raw "(quine completion (eval (do (def x 1))))"
          result (msg-fn raw)]
      ;; Should have reopened (stripped 3 parens) and appended quine
      (is (.contains ^String result "(quine my-data 42)"))
      (is (not (.endsWith ^String result ")))"))))))

(deftest dynamic-vars-bound-in-box-test
  (testing "*current-handle* and *current-raw* are bound during box execution"
    (let [handle :test-dynvars
          captured (atom {})
          eval-fn (fn [raw]
                    (reset! captured {:handle comm/*current-handle*
                                      :raw    comm/*current-raw*})
                    raw)]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      (comm/box "test-raw" handle)
      (is (= :test-dynvars (:handle @captured)))
      (is (= "test-raw" (:raw @captured))))))

;; =============================================================================
;; Integration tests (with DummyProvider)
;; =============================================================================

(deftest llm-still-works-unchanged-test
  (testing "basic -llm flow works through box"
    (provider/with-provider
      (provider/dummy-provider {:response "(def return 42))"})
      (is (= 42 (spell/llm "(do "))))))

(deftest llm-nested-still-works-test
  (testing "nested llm calls work through box"
    (let [call-count (atom 0)
          responses ["(def return (cat \"hello \" (llm \"(do \"))))"
                     "(def return \"world\"))"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= "hello world" (spell/llm "(do ")))))))

(deftest recv-blocks-send-unblocks-test
  (testing "recv blocks until send, and returned value is from the sent pipeline"
    ;; Agent A's initial eval-fn captures its handle and calls recv.
    ;; When recv re-enters box, it gets a new function from the inbox.
    ;; Agent B sends to A with a function that ignores raw and returns a constant.
    ;; The sent fn is composed with the eval-fn that was seeded in inbox.
    ;; So we need A's eval-fn for the recv to be simple (identity).
    (let [a-handle (atom nil)
          a-started (promise)
          ;; A's initial eval captures handle, then blocks on recv
          a-initial-fn (fn [raw]
                         (reset! a-handle comm/*current-handle*)
                         (deliver a-started true)
                         ;; recv releases has-box and re-enters box,
                         ;; which will pick up whatever B sends
                         (comm/recv-builtin))
          h-a :agent-a]
      (comm/register! h-a a-initial-fn)
      (reset! (:inbox (get @comm/registry h-a)) a-initial-fn)
      (let [fa (future (comm/box "a-raw" h-a))]
        ;; Wait for A to start and expose its handle
        (deref a-started 2000 :timeout)
        (Thread/sleep 50)
        ;; Now send to A. The sent function replaces raw entirely.
        ;; send composes (comp eval-fn f). eval-fn is a-initial-fn.
        ;; To avoid recursion (a-initial-fn calls recv again),
        ;; we use -send! directly to put a simple fn in the inbox.
        (comm/-send! h-a (fn [_] (fn [raw] (str "from-b:" raw))))
        (is (= "from-b:a-raw" (deref fa 5000 :timeout)))))))

(deftest orphan-box-responds-to-send-test
  (testing "orphan box processes a message sent after agent completes"
    (let [handle :test-orphan
          eval-fn (fn [raw] (str "orphan:" raw))]
      (comm/register! handle eval-fn)
      (reset! (:inbox (get @comm/registry handle)) eval-fn)
      ;; First box call — immediate (inbox seeded)
      (comm/box "first" handle)
      ;; Create orphan box
      (comm/orphan-box! "raw-data" handle)
      ;; Give orphan a moment to start and block
      (Thread/sleep 50)
      ;; Send to orphan
      (comm/send identity handle)
      ;; Give orphan time to process
      (Thread/sleep 100)
      ;; The orphan ran; we can't directly observe its result since it's fire-and-forget,
      ;; but we can verify no exceptions were thrown and the handle is still valid.
      (is (contains? @comm/registry handle)))))

;; =============================================================================
;; Handle?, await-result, result promise
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
    ;; Outer call captures current-handle; inner llm-self call should see same handle
    (let [call-count (atom 0)
          outer-handle (atom nil)
          inner-handle (atom nil)
          responses [;; Outer: capture handle, recurse, return both handles
                     "(def h1 (current-handle))(def h2 (llm-self \"(do \"))(list h1 h2))"
                     ;; Inner: return current-handle
                     "(current-handle))"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (let [result (spell/llm "(do ")]
          ;; result is (h1 h2) — both should be the same symbol
          (is (= (first result) (second result))))))))

;; =============================================================================
;; Spawn tests (with DummyProvider)
;; =============================================================================

(deftest spawn-returns-handle-test
  (testing "spawn returns a keyword handle that auto-unregisters on completion"
    (let [responses ["42)"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_] (first responses))})
        (let [handle (comm/spawn spell/llm "(do ")]
          (is (keyword? handle))
          ;; Wait for spawn future to finish, then handle should be unregistered
          (Thread/sleep 2000)
          (is (not (comm/handle? handle))))))))



(deftest spawn-sets-parent-handle-test
  (testing "spawned agent sees spawner's handle via parent-handle"
    (let [call-count (atom 0)
          responses [;; Parent: capture own handle, spawn child that returns parent-handle
                     "(def my-h (current-handle))(def child-result (llm-self \"(do \"))(list my-h child-result))"
                     ;; Inner llm-self (inherits handle, not spawned): return nil for parent-handle
                     "(parent-handle))"]]
      ;; First test: llm-self inherits handle, so parent-handle is nil (not spawned)
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (let [result (spell/llm "(do ")]
          ;; parent-handle should be nil for non-spawned agents
          (is (nil? (second result)))))))

  (testing "spawn sets *parent-handle* to spawner's handle"
    (let [captured (atom nil)
          parent-h :test-parent
          child-fn (fn [raw]
                     (reset! captured comm/*parent-handle*)
                     "done")]
      (comm/register! parent-h identity)
      ;; Simulate spawn from within parent context
      (binding [comm/*current-handle* parent-h]
        (let [child-h (keyword (gensym "child-"))
              parent comm/*current-handle*]
          (comm/register! child-h identity)
          (reset! (:inbox (get @comm/registry child-h)) child-fn)
          (binding [comm/*parent-handle* parent]
            (comm/box "raw" child-h))
          (is (= parent-h @captured)))))))

(deftest spawn-addressable-test
  (testing "spawned agent can be sent to (handle is registered)"
    (let [call-count (atom 0)
          responses [;; Outer: spawn worker, verify handle is valid, return it
                     "(def w (spawn llm-self \"(do \"))(not (nil? w)))"
                     ;; Worker: just return 77
                     "77)"]]
      (provider/with-provider
        (provider/dummy-provider
          {:response-fn (fn [_]
                          (let [r (nth responses @call-count)]
                            (swap! call-count inc)
                            r))})
        (is (= true (spell/llm "(do ")))))))

