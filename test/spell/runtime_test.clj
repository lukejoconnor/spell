(ns spell.runtime-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.parse :as parse]
            [spell.inbox :as inbox]
            [spell.runtime :as runtime]
            [spell.coordinator :as coordinator]
            [spell.globals :as globals]
            [spell.core :as spell]
            [spell.eval :as eval]
            [spell.provider :as provider]
            [spell.stdlib :as stdlib]
            [spell.test-helpers :as th]))

;; Clean registry between tests
(use-fixtures :each
  (fn [f]
    (binding [coordinator/*coordinator* (coordinator/new-coordinator)
              globals/*store* (globals/new-store)]
      (try (f) (finally (coordinator/close!))))))

(defn- identity-msg-macro []
  (#'runtime/identity-msg-macro))

(defn- append-forms-macro [& forms]
  (#'runtime/append-forms-macro forms))

(defn- inbox-aware
  [f]
  (with-meta f {:spell/inbox-aware true}))

(defn- materialize-inbox-raw
  [raw inbox-macros]
  (inbox/materialize-inbox-raw raw inbox-macros {:builtins eval/core-builtins}))

(defn- apply-inbox-macros
  [raw inbox-macros]
  (-> (materialize-inbox-raw raw inbox-macros)
      parse/read-all
      vec
      last))

;; =============================================================================
;; Unit tests (no LLM)
;; =============================================================================

(deftest register-test
  (testing "register! creates registry entry"
    (runtime/register! :h1)
    (is (contains? (:agents (coordinator/snapshot)) :h1))
    (is (some? (:mailbox (coordinator/agent :h1))))
    (is (some? (:execution (coordinator/agent :h1))))
    (is (some? (:completed (coordinator/agent :h1)))))

  (testing "register! throws on duplicate handle"
    (is (thrown-with-msg? Exception #"already registered"
          (runtime/register! :h1)))))

(deftest box-with-inside-fn-test
  (testing "box with inside-fn applies fn to raw immediately"
    (let [handle :test-box
          inside-fn (fn [raw] (str "evaluated:" raw))
          p (promise)]
      (runtime/register! handle)
      (deliver p "hello")
      (is (= "evaluated:hello" (runtime/box handle p inside-fn))))))

(deftest box-with-inbox-transform-test
  (testing "make-awake-fn drains inbox transform before calling eval-fn"
    (let [handle :test-transform
          raw "(quine completion (eval (do )))"
          eval-fn (inbox-aware
                    (fn [incoming inbox-macros]
                      (pr-str (apply-inbox-macros incoming inbox-macros))))
          p (promise)]
      (runtime/register! handle)
      (runtime/-send! handle (append-forms-macro '(def pre :loaded)))
      (deliver p raw)
      (is (.contains ^String (runtime/box handle p (runtime/make-awake-fn handle eval-fn))
                     "(def pre :loaded)")))))

(deftest box-no-transform-identity-test
  (testing "box with empty inbox passes raw through unchanged"
    (let [handle :test-identity
          raw "(quine completion (eval (do )))"
          eval-fn (inbox-aware
                    (fn [incoming inbox-macros]
                      (pr-str (apply-inbox-macros incoming inbox-macros))))
          p (promise)]
      (runtime/register! handle)
      (deliver p raw)
      (is (= (pr-str (parse/read-first raw))
             (runtime/box handle p (runtime/make-awake-fn handle eval-fn)))))))

(deftest append-forms-macro-reopens-last-top-level-quine-test
  (testing "queued macros target the parsed completion form and leave earlier top-level forms inert"
    (let [first-form '(quine completion (eval (do (def first-msg "kept") nil)))
          last-form '(quine completion (eval (do (def second-msg "open-me"))))
          raw (str (pr-str first-form) " " (pr-str last-form))
          transformed-raw (materialize-inbox-raw raw [(append-forms-macro '(def injected :yes))])
          transformed (apply-inbox-macros raw [(append-forms-macro '(def injected :yes))])]
      (is (= 'quine (first transformed)))
      (is (= 'completion (second transformed)))
      (is (.contains ^String transformed-raw (pr-str first-form))
          "the earlier top-level quine remains inert input ahead of the transformed form")
      (is (some #(= '(def second-msg "open-me") %)
                (drop 1 (second (last transformed)))))
      (is (some #(= '(def injected :yes) %)
                (drop 1 (second (last transformed)))))
      (is (= '(def first-msg "kept")
             (nth (second (nth first-form 2)) 1))
          "the earlier top-level quine remains inert input, not the transformation target"))))

(deftest send-msg-fn-composes-correctly-test
  (testing "send-msg-fn queues a macro into inbox state"
    (let [handle :test-compose
          raw "(quine completion (eval (do )))"
          eval-fn (inbox-aware
                    (fn [incoming inbox-macros]
                      (pr-str (apply-inbox-macros incoming inbox-macros))))
          p (promise)]
      (runtime/register! handle)
      (runtime/send-msg-fn (append-forms-macro '(def pre :hello)) handle)
      (deliver p raw)
      (is (.contains ^String (runtime/box handle p (runtime/make-awake-fn handle eval-fn))
                     "(def pre :hello)")))))

(deftest multiple-sends-compose-test
  (testing "multiple sends compose in FIFO order"
    (let [handle :test-multi
          raw "(quine completion (eval (do )))"
          eval-fn (inbox-aware
                    (fn [incoming inbox-macros]
                      (apply-inbox-macros incoming inbox-macros)))
          p (promise)]
      (runtime/register! handle)
      (runtime/send-msg-fn (append-forms-macro '(def a :first)) handle)
      (runtime/send-msg-fn (append-forms-macro '(def b :second)) handle)
      (deliver p raw)
      (let [body-forms (drop 1 (second (last (runtime/box handle p (runtime/make-awake-fn handle eval-fn)))))]
        (is (= '(def a :first) (nth body-forms 0)))
        (is (= '(def b :second) (nth body-forms 1)))))))

(deftest ask-asserts-outside-context-test
  (testing "ask with msg throws when not in agent context"
    (is (thrown-with-msg? Exception #"active agent context"
          (runtime/ask-builtin :some-target "hello"))))
  (testing "ask without msg throws when not in agent context"
    (is (thrown-with-msg? Exception #"active agent context"
          (runtime/ask-builtin :some-target)))))

(deftest send-test
  (testing "send sends def message with :from and :body to target"
    (let [h-sender :test-sender
          h-target :test-target
          received (atom nil)
          eval-fn (fn [raw] (reset! received raw) raw)
          p (promise)]
      (runtime/register! h-sender)
      (runtime/register! h-target)
      (binding [runtime/*current-handle* h-sender]
        (runtime/send h-target 42))
      ;; Process the message through box + awake-fn (which drains inbox)
      (deliver p "(quine completion (eval (do )))")
      (runtime/box h-target p (runtime/make-awake-fn h-target eval-fn))
      ;; Should contain def with :from and :body
      (is (.contains ^String @received ":from :test-sender"))
      (is (.contains ^String @received ":body 42"))
      (is (.contains ^String @received "(def msg-")))))

(deftest reply-test
  (testing "reply extracts :from from message map and sends back"
    (let [h-a :reply-a
          h-b :reply-b
          b-received (atom nil)
          eval-fn (fn [raw] (reset! b-received raw) raw)
          p (promise)]
      (runtime/register! h-a)
      (runtime/register! h-b)
      ;; Simulate a message map that h-a would have received from h-b
      (let [fake-msg {:from :reply-b :body "hello"}]
        (binding [runtime/*current-handle* h-a]
          (runtime/reply fake-msg "reply-value")))
      ;; Process the message at h-b (awake-fn drains inbox)
      (deliver p "(quine completion (eval (do )))")
      (runtime/box h-b p (runtime/make-awake-fn h-b eval-fn))
      (is (.contains ^String @b-received ":from :reply-a"))
      (is (.contains ^String @b-received ":body \"reply-value\"")))))

(deftest dynamic-vars-bound-in-box-test
  (testing "*current-handle* and *current-raw* are bound during box execution"
    (let [handle :test-dynvars
          captured (atom {})
          inside-fn (fn [raw]
                    (reset! captured {:handle runtime/*current-handle*
                                      :raw    runtime/*current-raw*})
                    raw)
          p (promise)]
      (runtime/register! handle)
      (deliver p "test-raw")
      (runtime/box handle p inside-fn)
      (is (= :test-dynvars (:handle @captured)))
      (is (= "test-raw" (:raw @captured))))))

(deftest box-root-detection-test
  (testing "run-root-box delivers :completed"
    (let [handle :test-root
          eval-fn (fn [raw] :result)
          p (promise)]
      (runtime/register! handle)
      (deliver p "raw")
      ;; Capture the completed promise before box runs
      (let [cp (:completed (coordinator/agent handle))
            result (runtime/run-root-box handle p (runtime/make-awake-fn handle eval-fn) eval-fn)]
        (is (= :result result))
        ;; Completed promise should have been delivered with result
        (is (= :result (deref cp 100 :timeout))))))

  (testing "plain box (non-root) skips root cleanup"
    (let [handle :test-nonroot
          inside-fn (fn [raw] :result)
          p (promise)]
      (runtime/register! handle)
      (deliver p "raw")
      (is (= :result (runtime/box handle p inside-fn))))))

(deftest orphan-box-captures-innermost-raw-test
  (testing "orphan box uses the innermost extension's raw, not the root box's raw"
    (let [handle :test-orphan-raw
          inner-raw "inner-extension-raw"
          call-count (atom 0)
          orphan-received (promise)
          eval-fn (fn [raw]
                    (case (swap! call-count inc)
                      1 (runtime/box handle inner-raw (fn [r] (str "result:" r)))
                      2 (do (deliver orphan-received raw)
                            (str "orphan:" raw))))
          p (promise)]
      (runtime/register! handle)
      (deliver p "outer-root-raw")
      ;; Capture :completed before run-root-box
      (let [cp (:completed (coordinator/agent handle))]
        (runtime/run-root-box handle p (runtime/make-awake-fn handle eval-fn) eval-fn)
        ;; Wait for orphan box to start and sleep
        (Thread/sleep 100)
        ;; Wake the orphan and confirm it reuses the innermost raw.
        (runtime/send-msg-fn (identity-msg-macro) handle)
        (let [received (deref orphan-received 2000 :timeout)]
          (is (= "inner-extension-raw" received)
              "orphan should have the innermost extension raw, not outer-root-raw"))))))

(deftest box-handles-exception-promise-test
  (testing "box rethrows exception delivered to promise"
    (let [handle :test-ex
          p (promise)]
      (runtime/register! handle)
      (deliver p (ex-info "API error" {:status 500}))
      (is (thrown-with-msg? Exception #"API error"
            (runtime/box handle p identity))))))

(deftest run-root-box-completion-source-exception-restarts-lifecycle-test
  (testing "completion-source exception is handled before entry and root lifecycle restarts"
    (let [handle :test-root-completion-ex
          eval-count (atom 0)
          eval-fn (fn [raw]
                    (swap! eval-count inc)
                    raw)
          p (promise)]
      (runtime/register! handle)
      (let [cp (:completed (coordinator/agent handle))]
        (deliver p (ex-info "boom" {}))
        (is (thrown-with-msg? Exception #"boom"
              (runtime/run-root-box handle p (runtime/make-awake-fn handle eval-fn) eval-fn)))
        (is (:spell/child-failure (deref cp 100 :timeout)))
        ;; Pre-entry failure should still spawn the sleeping orphan for next turn.
        (Thread/sleep 100)
        (runtime/-send! handle (identity-msg-macro))
        (Thread/sleep 200)
        (is (= 1 @eval-count))))))

;; =============================================================================
;; Integration tests (with TestProvider)
;; =============================================================================

(deftest llm-still-works-unchanged-test
  (testing "basic -llm flow works through box"
    (let [llm (th/make-test-runner {:response "(def return 42))"})]
      (is (= 42 (llm "(do "))))))

(deftest llm-nested-still-works-test
  (testing "nested llm calls work through box (llm is effect-only)"
    (let [call-count (atom 0)
          responses ["'(cat \"hello \" (!llm-self \"(eval (do \")))"
                     "'\"world\")))"]]
      (let [llm (th/make-test-runner
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
                             (runtime/ask-builtin h-b))
                         (str "from-b:" raw)))
          p (promise)]
      ;; Register both handles
      (runtime/register! h-a)
      (runtime/register! h-b)
      (deliver p "(quine completion (eval (do )))")
      (let [fa (future (runtime/box h-a p (runtime/make-awake-fn h-a a-eval-fn)))]
        ;; Wait for A to start
        (deref a-started 2000 :timeout)
        (Thread/sleep 50)
        ;; Send a message transform to A
        (runtime/-send! h-a (append-forms-macro '(def extra true)))
        (is (string? (deref fa 5000 :timeout)))))))

(deftest start-box-responds-to-send-test
  (testing "start-box processes a message sent after initial sleep"
    (let [handle :test-orphan
          received (atom nil)
          eval-fn (fn [raw] (reset! received raw) (str "orphan:" raw))]
      (runtime/start-box handle eval-fn "raw-data")
      ;; Give the root box time to start and block on signal
      (Thread/sleep 100)
      ;; Send to the sleeping agent
      (runtime/send-msg-fn (eval/compose-macros []) handle)
      ;; Give time to process
      (Thread/sleep 200)
      ;; The agent ran; we can verify no exceptions and handle still valid
      (is (contains? (:agents (coordinator/snapshot)) handle)))))

;; =============================================================================
;; Start-box (dormant agent) tests
;; =============================================================================

(deftest start-box-sleeps-until-message-test
  (testing "start-box registers and sleeps — agent wakes on send"
    (let [handle :test-dormant
          received (atom nil)
          eval-fn (fn [raw] (reset! received raw) raw)
          completion "(quine completion (eval (do )))"]
      (runtime/start-box handle eval-fn completion)
      ;; Give the root box time to start and block on signal
      (Thread/sleep 100)
      ;; Agent should be registered and sleeping
      (is (contains? (:agents (coordinator/snapshot)) handle))
      ;; Send a macro that appends to the stored completion
      (runtime/send-msg-fn (append-forms-macro '(def extra true)) handle)
      ;; Give time to process
      (Thread/sleep 200)
      ;; eval-fn saw the stored completion with the appended message
      (is (some? @received))
      (is (.contains ^String @received "quine completion")
          "stored completion is the base for message composition")
      (is (.contains ^String @received "(def extra true)")
          "sent macro was applied to stored completion"))))

(deftest start-box-no-initial-eval-test
  (testing "start-box does not evaluate the stored completion"
    (let [handle :test-no-eval
          eval-count (atom 0)
          eval-fn (fn [raw] (swap! eval-count inc) raw)]
      (runtime/start-box handle eval-fn "(quine completion (eval (do )))")
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
    (runtime/register! :handle-q)
    (is (true? (runtime/handle? :handle-q))))
  (testing "handle? returns false for unregistered handles"
    (is (false? (runtime/handle? :nonexistent)))))


;; =============================================================================
;; Handle inheritance tests (with TestProvider)
;; =============================================================================

(deftest handle-inheritance-test
  (testing "!llm-self calls inherit the parent's handle"
    ;; All effect builtins (agents/current-handle, !llm-self) go through eval's second pass.
    (let [call-count (atom 0)
          responses [;; Outer: use eval to access current-handle and !llm-self via double-eval
                     "'(list (agents/current-handle) (!llm-self \"(eval (do \")))"
                     ;; Inner: return current-handle (via eval)
                     "'(agents/current-handle)))"]]
      (let [llm (th/make-test-runner
                 {:response-fn (fn [_]
                                 (let [r (nth responses @call-count)]
                                   (swap! call-count inc)
                                   r))})]
        (let [result (llm "(eval (do ")]
          ;; result is (h1 h2) — both should be the same handle
          (is (= (first result) (second result))))))))

;; =============================================================================
;; Spawn tests (with TestProvider)
;; =============================================================================

(deftest spawn-returns-handle-test
  (testing "spawn returns a keyword handle (handle persists after completion)"
    (let [agent (th/make-test-agent
                 {:response-fn (fn [_] "42)")})]
      (let [handle (runtime/spawn agent "(do ")]
        (is (keyword? handle))
        ;; Wait for spawn future to finish — handle persists (no unregister)
        (deref (:completed (coordinator/agent handle)) 5000 :timeout)
        (is (runtime/handle? handle))))))



(deftest spawn-sets-parent-handle-test
  (testing "spawned agent sees spawner's handle via parent-handle"
    (let [call-count (atom 0)
          responses [;; Parent: all effect builtins via eval
                     "'(let [my-h (agents/current-handle) child-result (!llm-self \"(eval (do \")] (list my-h child-result)))"
                     ;; Inner !llm-self (inherits handle, not spawned): return nil for parent-handle
                     "'(agents/parent-handle)))"]]
      ;; First test: !llm-self inherits handle, so parent-handle is nil (not spawned)
      (let [llm (th/make-test-runner
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
      (runtime/register! parent-h)
      ;; Simulate spawn from within parent context
      (binding [runtime/*current-handle* parent-h]
        (let [child-h (keyword (gensym "child-"))]
          (runtime/register! child-h parent-h)
          (deliver p "raw")
          (runtime/box child-h p child-fn)
          (is (= parent-h (:parent-handle (coordinator/agent child-h)))))))))

(deftest spawn-ask-test
  (testing "spawn-ask spawns child and blocks until child sends back"
    (let [parent-h :sr-parent
          child-agent (th/compiled-agent-fn
                       (fn [_prompt handle]
                         (let [parent (:parent-handle (coordinator/agent handle))
                               inside-fn (fn [_raw]
                                           (runtime/send parent 42)
                                           :done)
                               p (promise)]
                           (deliver p "(quine completion (eval (do )))")
                           (runtime/run-root-box handle p inside-fn inside-fn))))]
      (runtime/register! parent-h)
      (let [parent-result
            (future
              (binding [runtime/*current-handle* parent-h
                        runtime/*current-raw* "(quine completion (eval (do )))"
                        runtime/*current-eval-fn* identity]
                (runtime/spawn-ask-and-wait child-agent "test")))]
        ;; spawn-ask blocks until child sends; child runs in a future
        (let [result (deref parent-result 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result ":body 42")))))))

(deftest spawn-default-agent-arity-test
  (testing "spawn(prompt) uses bound default agent"
    (let [parent-h :spawn-default-parent
          default-agent (th/compiled-agent-fn
                         (fn [prompt _handle] (Thread/sleep 50) (str "done:" prompt)))]
      (runtime/register! parent-h)
      (binding [runtime/*current-handle* parent-h
                runtime/*default-spawn-agent* default-agent]
        (let [child-h (runtime/spawn "prompt-default")]
          (is (keyword? child-h))
          (is (= "done:prompt-default"
                 (deref (:completed (coordinator/agent child-h)) 5000 :timeout)))))))

  (testing "spawn(prompt) throws when default agent is unavailable"
    (is (thrown-with-msg? Exception #"no default agent available"
          (runtime/spawn "no-default")))))

(deftest spawn-ask-multi-specs-waits-for-all-children-test
  (testing "spawn-ask with two child specs wakes once with combined completion results"
    (let [parent-h :sa-multi-parent
          parent-raw "(quine completion (eval (do )))"
          child-a-started (promise)
          child-b-started (promise)
          child-a-may-finish (promise)
          child-b-may-finish (promise)
          parent-wake-count (atom 0)
          child-a-agent (th/compiled-agent-fn
                         (fn [_prompt _handle]
                           (deliver child-a-started true)
                           (deref child-a-may-finish 5000 :timeout)
                           :child-a-result))
          child-b-agent (th/compiled-agent-fn
                         (fn [_prompt _handle]
                           (deliver child-b-started true)
                           (deref child-b-may-finish 5000 :timeout)
                           :child-b-result))
          _ (runtime/register! parent-h)
          parent-result
          (future
            (binding [runtime/*current-handle* parent-h
                      runtime/*current-raw* parent-raw
                      runtime/*current-eval-fn* (fn [raw]
                                                  (swap! parent-wake-count inc)
                                                  raw)]
              (runtime/spawn-ask-and-wait [[child-a-agent "prompt-a" :sa-multi-child-a]
                                  [child-b-agent "prompt-b" :sa-multi-child-b]])))]
      (is (= true (deref child-a-started 5000 :timeout)))
      (is (= true (deref child-b-started 5000 :timeout)))
      ;; Only child A is allowed to complete here; parent must stay blocked for child B.
      (deliver child-a-may-finish true)
      (is (= :timeout (deref parent-result 200 :timeout))
          "parent should not wake until both child completions are available")
      (deliver child-b-may-finish true)
      (let [result (deref parent-result 5000 :timeout)]
        (is (string? result))
        ;; Combined result should include both children and both completion bodies.
        (is (.contains ^String result ":from :sa-multi-child-a"))
        (is (.contains ^String result ":from :sa-multi-child-b"))
        (is (.contains ^String result ":body :child-a-result"))
        (is (.contains ^String result ":body :child-b-result"))
        (is (= 1 @parent-wake-count)
            "parent should wake exactly once for the combined notifier message")))))

(deftest spawn-ask-multi-prompts-default-agent-test
  (testing "spawn-ask([prompt ...]) uses bound default agent for all entries"
    (let [parent-h :sa-prompt-parent
          parent-raw "(quine completion (eval (do )))"
          default-agent (th/compiled-agent-fn
                         (fn [prompt _handle] (str "result:" prompt)))]
      (runtime/register! parent-h)
      (let [parent-future
            (future
              (binding [runtime/*current-handle* parent-h
                        runtime/*current-raw* parent-raw
                        runtime/*current-eval-fn* identity
                        runtime/*default-spawn-agent* default-agent]
                (runtime/spawn-ask-and-wait ["alpha" "beta" "gamma"])))]
        (let [result (deref parent-future 5000 :timeout)]
          (is (string? result))
          (is (.contains ^String result "result:alpha"))
          (is (.contains ^String result "result:beta"))
          (is (.contains ^String result "result:gamma")))))))

(deftest spawn-addressable-test
  (testing "spawned agent can be sent to (handle is registered)"
    ;; spawn is an effect-builtin: accessed via eval double-evaluation.
    (let [call-count (atom 0)
          responses [;; Outer: use eval to access spawn via double-eval
                     "(eval (do '(let [w (agents/spawn \"(do \")] (not (nil? w)))))"
                     ;; Worker: just return 77
                     "77)"]]
      (let [llm (th/make-test-runner
                 {:response-fn (fn [_]
                                 (let [r (nth responses @call-count)]
                                   (swap! call-count inc)
                                   r))})]
        (is (= true (llm "(do ")))))))

(deftest spawn-default-arity-via-eval-test
  (testing "agents/spawn prompt defaults to the current agent in eval context"
    (let [call-count (atom 0)
          responses ["(eval (do '(let [w (agents/spawn \"(do \")] (keyword? w))))"
                     "321)"]]
      (let [llm (th/make-test-runner
                 {:response-fn (fn [_]
                                 (let [r (nth responses @call-count)]
                                   (swap! call-count inc)
                                   r))})]
        (is (= true (llm "(do ")))))))

;; =============================================================================
;; Completion promise tests
;; =============================================================================

(deftest tracked-request-token-test
  (runtime/register! :source)
  (runtime/start-box :target (fn [_] :finished) "(quine completion (eval (do)))")
  (binding [runtime/*current-handle* :source]
    (let [token (runtime/request-token :target :question)]
      (is (:spell/future token))
      (is (= :finished (deref (:ref token) 3000 :timeout)))
      (is (empty? (:mailbox (coordinator/agent :source)))))))

(deftest tracked-request-invalid-target-test
  (runtime/register! :source)
  (binding [runtime/*current-handle* :source]
    (is (thrown-with-msg? Exception #"Handle not registered" (runtime/request-token :missing)))))

(deftest blocking-await-basic-test
  (testing "blocking-await resolves a Spell future"
    (let [token {:spell/future true :ref (future :blocking-ok)}]
      (is (= :blocking-ok (runtime/blocking-await token)))
      (is (thrown-with-msg? Exception #"requires a future"
            (runtime/blocking-await 42))))))

(deftest send-await-basic-test
  (testing "send-await captures completion, sends, and awaits"
    (let [handle :send-await-child
          child-raw "(quine completion (eval (do )))"
          eval-fn (fn [_] :send-await-done)]
      (runtime/register! :send-await-parent)
      (runtime/start-box handle eval-fn child-raw)
      (Thread/sleep 100)
      (is (= :send-await-done
             (binding [runtime/*current-handle* :send-await-parent]
               (runtime/send-await handle {:kind :wake}))))))
  (testing "send-await surfaces request-context errors"
    (is (thrown-with-msg? Exception #"requires a source agent"
          (runtime/send-await :missing {:kind :wake})))))

(deftest ask-await-builtin-wakeup-test
  (testing "!ask-await blocks for wakeup and resumes with a future result message"
    (let [handle :futures-ask-await
          raw "(quine completion (eval (do )))"
          ask-await stdlib/ask-await-builtin
          first? (atom true)
          eval-fn (fn [current-raw]
                    (if (compare-and-set! first? true false)
                      (ask-await {:spell/future true :ref (future :ask-await-ok)})
                      current-raw))
          p (promise)]
      (runtime/register! handle)
      (deliver p raw)
      (let [result (deref (future (runtime/box handle p (runtime/make-awake-fn handle eval-fn)))
                          5000 :timeout)]
        (is (string? result))
        (is (.contains ^String result ":from :future"))
        (is (.contains ^String result ":body :ask-await-ok"))))))

(deftest blocking-namespace-env-gated-test
  (testing "blocking/ is unavailable outside futures and available inside futures"
    (is (thrown-with-msg? Exception #"Unbound symbol: blocking"
          (binding [eval/*future-env* {'blocking runtime/blocking-namespace}]
            (eval/run-spell '(blocking/await (future 1))))))
    (let [fut (binding [eval/*future-env* {'blocking runtime/blocking-namespace}]
                (eval/run-spell '(future (blocking/await (future 7)))))]
      (is (= 7 (deref (:ref fut) 5000 :timeout))))))

(deftest future-star-unbinds-current-raw-test
  (testing "future* unbinds runtime/*current-raw* so agent-only APIs reject in futures"
    (let [future* (get eval/core-builtins 'future*)
          result-token (binding [runtime/*current-handle* :future-parent
                                 runtime/*current-raw* "(quine completion (eval (do )))"
                                 eval/*spell-env* {}
                                 eval/*future-env* {}]
                         (future* (fn []
                                    (try
                                      (runtime/ask-builtin :nope "hi")
                                      :unexpected-success
                                      (catch Exception e
                                        (.getMessage e))))))]
      (is (re-find #"requires an active agent context"
                   (deref (:ref result-token) 5000 :timeout))))))

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
                            (runtime/ask-builtin h-b "hello"))
                        raw))
          ;; B's eval-fn: captures what it receives, then replies to A
          b-eval-fn (fn [raw]
                      (reset! b-received raw)
                      ;; Reply to A via send-msg-fn
                      (runtime/send-msg-fn (append-forms-macro '(def reply-from-b true)) h-a)
                      "b-done")
          pa (promise)]
      (runtime/register! h-a)
      ;; B starts sleeping in a root box via start-box
      (runtime/start-box h-b b-eval-fn b-raw)
      (Thread/sleep 50)
      (deliver pa a-raw)
      (let [fa (future (runtime/box h-a pa (runtime/make-awake-fn h-a a-eval-fn)))]
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
                            (runtime/ask-builtin h-b))
                        raw))
          ;; B's eval-fn: captures the poke, then replies to A
          b-eval-fn (fn [raw]
                      (reset! b-received raw)
                      (runtime/send-msg-fn (append-forms-macro '(def reply-from-b true)) h-a)
                      "b-done")
          pa (promise)]
      (runtime/register! h-a)
      ;; B starts sleeping in a root box via start-box
      (runtime/start-box h-b b-eval-fn b-raw)
      (Thread/sleep 50)
      (deliver pa a-raw)
      (let [fa (future (runtime/box h-a pa (runtime/make-awake-fn h-a a-eval-fn)))]
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
    (is (thrown-with-msg? Exception #"active agent context"
          (runtime/ask-builtin [:a :b]))))
  (testing "ask with empty vector throws"
    (runtime/register! :dummy)
    (binding [runtime/*current-handle* :dummy
              runtime/*current-raw* "(quine completion (eval (do )))"
              runtime/*current-eval-fn* identity]
      (is (thrown-with-msg? Exception #"nonempty"
            (runtime/ask-builtin []))))))

(deftest ask-multi-waits-for-all-test
  (testing "multi-target ask waits for all targets to complete"
    (let [h-parent :multi-parent
          h-a :multi-child-a
          h-b :multi-child-b
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"
          eval-a (fn [raw] :result-a)
          eval-b (fn [raw] :result-b)]
      (runtime/register! h-parent)
      (runtime/register! h-a :some-spawner)
      (runtime/register! h-b :some-spawner)
      (let [cp-a (promise)
            cp-b (promise)]
        (deliver cp-a child-raw)
        (deliver cp-b child-raw)
        (let [result-future
              (future
                (binding [runtime/*current-handle* h-parent
                          runtime/*current-raw*    parent-raw
                          runtime/*current-eval-fn* identity]
                  (runtime/ask-builtin [h-a h-b])))]
          (Thread/sleep 50)
          ;; Only child A completes — parent should NOT wake yet
          (future (runtime/run-root-box h-a cp-a
                    (runtime/make-awake-fn h-a eval-a) eval-a))
          (Thread/sleep 100)
          (is (not (realized? result-future)) "parent should still be blocked")
          ;; Now child B completes — parent should wake with combined results
          (runtime/run-root-box h-b cp-b
            (runtime/make-awake-fn h-b eval-b) eval-b)
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
      (runtime/register! h-parent)
      (runtime/register! h-child :some-spawner)
      (let [cp (promise)]
        (deliver cp child-raw)
        (let [result-future
              (future
                (binding [runtime/*current-handle* h-parent
                          runtime/*current-raw*    parent-raw
                          runtime/*current-eval-fn* identity]
                  (runtime/ask-builtin [h-child])))]
          (Thread/sleep 50)
          (runtime/run-root-box h-child cp
            (runtime/make-awake-fn h-child child-eval-fn) child-eval-fn)
          (let [result (deref result-future 5000 :timeout)]
            (is (string? result))
            (is (.contains ^String result ":child-done"))))))))

(deftest ask-multi-concurrent-completions-test
  (testing "concurrent target completions all contribute to result"
    (let [h-parent :multi-conc-parent
          targets (mapv #(keyword (str "multi-conc-child-" %)) (range 5))
          parent-raw "(quine completion (eval (do)))"
          child-raw  "(quine completion (eval (do)))"]
      (runtime/register! h-parent)
      (doseq [t targets] (runtime/register! t :some-spawner))
      (let [result-future
            (future
              (binding [runtime/*current-handle* h-parent
                        runtime/*current-raw*    parent-raw
                        runtime/*current-eval-fn* identity]
                (runtime/ask-builtin targets)))]
        (Thread/sleep 50)
        ;; All children complete concurrently
        (let [box-futures
              (mapv (fn [t]
                      (let [cp (promise)]
                        (deliver cp child-raw)
                        (future
                          (runtime/run-root-box t cp
                            (runtime/make-awake-fn t (fn [_] (name t)))
                            (fn [_] (name t))))))
                    targets)]
          (doseq [bf box-futures] (deref bf 2000 :timeout)))
        ;; Parent wakes with all results
        (let [result (deref result-future 5000 :timeout)]
          (is (string? result))
          ;; All target names should appear in the combined result
          (doseq [t targets]
            (is (.contains ^String result (name t)))))))))

(deftest pending-transforms-survive-test
  (testing "pending inbox transforms survive when box+awake-fn is entered"
    (let [handle :test-cas
          eval-fn identity
          sent-macro (append-forms-macro '(def sent :hello))
          raw "(quine completion (eval (do )))"
          p (promise)]
      (runtime/register! handle)
      ;; Simulate: a send happened before box entry
      (runtime/send-msg-fn sent-macro handle)
      ;; inbox should have the queued macro value
      (let [inbox-before (:mailbox (coordinator/agent handle))]
        (is (= 1 (count inbox-before)) "inbox should have one queued macro"))
      ;; make-awake-fn drains inbox: macro applied to the parsed program,
      ;; then eval-fn sees the transformed completion
      (deliver p raw)
      (is (.contains ^String
                      (runtime/box handle p (runtime/make-awake-fn handle eval-fn))
                      "(def sent :hello)")
          "awake-fn should drain the preserved transform then call eval-fn")))

  (testing "box with no transforms uses identity for raw"
    (let [handle :test-cas-empty
          eval-fn identity
          raw "(quine completion (eval (do )))"
          p (promise)]
      (runtime/register! handle)
      ;; inbox is empty (no pending sends)
      (is (= [] (:mailbox (coordinator/agent handle))))
      ;; make-awake-fn drains empty inbox (identity macro), raw passes through
      (deliver p raw)
      (is (= raw (runtime/box handle p (runtime/make-awake-fn handle eval-fn)))))))

(deftest inbox-cas-seeds-when-empty-test
  (testing "inherited -llm seeds inbox when it's empty (no pending sends)"
    ;; Simple recursive !llm-self without any sends — should work as before
    (let [call-count (atom 0)
          responses ["'(!llm-self \"(eval (do \"))"
                     "99))"]]
      (let [llm (th/make-test-runner
                 {:response-fn (fn [_]
                                 (let [r (nth responses @call-count)]
                                   (swap! call-count inc)
                                   r))})]
        (is (= 99 (llm "(eval (do ")))))))

;; =============================================================================
;; Completion notifier tests
;; =============================================================================

(deftest effect-guard-blocks-in-first-pass-test
  (testing "effect builtins are unbound in eval's first pass (do block)"
    ;; agents/ — communication effects (namespace-qualified)
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/send :nobody 42))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/spawn identity "test"))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/!ask :nobody "hello"))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/current-handle))))
    (is (thrown-with-msg? Exception #"Unbound symbol: agents"
          (eval/run-spell '(agents/parent-handle))))
    ;; !ask-await — effect builtin is not available in first pass
    (is (thrown-with-msg? Exception #"Unbound symbol: !ask-await"
          (eval/run-spell '(!ask-await (future 1)))))
    ;; io/, globals/ — side-effectful namespaces
    (is (thrown-with-msg? Exception #"Unbound symbol: io"
          (eval/run-spell '(io/sh "echo hi"))))
    (is (thrown-with-msg? Exception #"Unbound symbol: globals"
          (eval/run-spell '(globals/get :roles))))))

;; =============================================================================
;; Leaf-llm spawn rejection tests
;; =============================================================================

(deftest spawn-rejects-leaf-llm-test
  (testing "spawn throws when given a leaf-llm function"
    (let [leaf-fn (with-meta (fn [prompt] "response") {:spell/leaf true})]
      (runtime/register! :leaf-parent)
      (binding [runtime/*current-handle* :leaf-parent]
        (is (thrown-with-msg? Exception #"leaf-llm"
              (runtime/spawn leaf-fn "test prompt" :leaf-child))))))

  (testing "spawn-ask also rejects leaf-llm"
    (let [leaf-fn (with-meta (fn [prompt] "response") {:spell/leaf true})]
      (runtime/register! :leaf-parent-2)
      (binding [runtime/*current-handle* :leaf-parent-2
                runtime/*current-raw* "(quine completion (eval (do )))"
                runtime/*current-eval-fn* identity]
        (is (thrown-with-msg? Exception #"leaf-llm"
              (runtime/spawn-ask-and-wait leaf-fn "test prompt" :leaf-child-2)))))))

(deftest spawn-future-exception-delivers-completed-test
  (testing "spawn future exception delivers :completed (prevents deadlock)"
    (let [bad-agent (th/compiled-agent-fn
                     (fn [_prompt _handle] (throw (ex-info "boom" {}))))]
      (runtime/register! :boom-parent)
      (binding [runtime/*current-handle* :boom-parent]
        (let [child-h (runtime/spawn bad-agent "test")]
          ;; Give the future time to run and fail
          (Thread/sleep 200)
          ;; :completed should have been delivered (with nil)
          (is (= :finished (:status (coordinator/agent child-h)))))))))

(deftest spawn-rejects-non-agent-test
  (testing "spawn rejects non-agent values"
    (let [simple-fn (fn [prompt handle] (str "done:" prompt))]
      (runtime/register! :simple-parent)
      (binding [runtime/*current-handle* :simple-parent]
        (is (thrown-with-msg? Exception #"agents/spawn requires a compiled agent"
              (runtime/spawn simple-fn "test" :simple-child)))))))

;; =============================================================================
;; Spawn-ask + child extension test
;; =============================================================================

(deftest spawn-ask-child-extend-no-premature-wake-test
  (testing "spawn-ask parent does NOT wake when child extends (only on final completion)"
    ;; Scenario: parent calls spawn-ask, child receives the ask message,
    ;; child does an !llm-self (extension) before producing final result.
    ;; The parent must NOT wake until the child's root box fully completes.
    ;;
    ;; We use a gate (child-may-finish) to hold the child in its extension
    ;; so we can observe the parent state mid-extension.
    (let [call-count (atom 0)
          child-extended (promise)
          child-may-finish (promise)
          child-responses
          (fn [prompt]
            (let [n (swap! call-count inc)]
              (cond
                ;; Turn 1: initial spawn prompt
                (= n 1)
                "(def status :started) '(!extend)))"

                ;; Turn 2: extension — signal we extended, then BLOCK until test releases us
                (= n 2)
                (do (deliver child-extended true)
                    (deref child-may-finish 10000 :timeout)
                    "(def status :extended) '(!extend)))")

                ;; Turn 3: final — send result to parent
                :else
                "'(agents/send (agents/parent-handle) :child-done)))")))]
      (let [child-agent (th/make-test-agent {:response-fn child-responses})
            parent-h :sa-ext-parent
            parent-raw "(quine completion (eval (do )))"
            parent-woke (atom nil)]
        (runtime/register! parent-h)
        (let [parent-future
              (future
                (binding [runtime/*current-handle* parent-h
                          runtime/*current-raw* parent-raw
                          runtime/*current-eval-fn* (fn [raw]
                                                      (reset! parent-woke raw)
                                                      raw)]
                  (runtime/spawn-ask-and-wait child-agent "extend once, then send done")))]
          ;; Wait for child to reach extension (turn 2 blocks on gate)
          (is (= true (deref child-extended 5000 :timeout))
              "child should have reached extension")
          ;; Child is now blocked mid-extension. Check parent state.
          (Thread/sleep 100)
          (is (nil? @parent-woke)
              "parent must not wake prematurely when child is mid-extension")
          ;; Release the child to finish
          (deliver child-may-finish true)
          ;; Parent should eventually wake with the child's send
          (let [result (deref parent-future 10000 :timeout)]
            (is (string? result))
            (is (some? @parent-woke)
                "parent should wake after child fully completes")))))))

(deftest stale-signal-after-inbox-drain-test
  (testing "block-for-message blocks when inbox was drained by box (no spurious wake from stale signal)"
    ;; Scenario: an agent is inside a box waiting on a completion promise.
    ;; While it waits, a message arrives via -send! (composing into inbox
    ;; AND delivering the signal). When the completion arrives, box drains
    ;; the inbox — picking up the message. The agent processes it and then
    ;; calls block-for-message. At that point, the signal from -send! is
    ;; stale (already delivered, never consumed). block-for-message should
    ;; NOT spuriously wake from this stale signal.
    (let [handle :stale-sig
          raw "(quine completion (eval (do )))"
          completion (promise)
          reached-block (promise)
          block-result (promise)
          call-count (atom 0)
          eval-fn (fn [raw]
                    (let [n (swap! call-count inc)]
                      (if (= n 1)
                        ;; First call: we processed the inbox message.
                        ;; Now block-for-message — should actually block.
                        (do (deliver reached-block true)
                            (runtime/block-for-message))
                        ;; Second call: woke from block. Return raw.
                        (do (deliver block-result raw)
                            raw))))]
      (runtime/register! handle)
      ;; Simulate: message arrives while waiting for completion.
      ;; -send! composes into inbox AND delivers signal.
      (runtime/-send! handle (append-forms-macro '(def x 1)))
      ;; Deliver the completion (simulating LLM response arriving).
      (deliver completion raw)
      ;; Start box in a future — it will drain inbox (picking up the message)
      ;; and call eval-fn, which calls block-for-message.
      (future (runtime/box handle completion (runtime/make-awake-fn handle eval-fn)))
      ;; Wait for agent to reach block-for-message
      (is (= true (deref reached-block 2000 :timeout))
          "agent should reach block-for-message")
      ;; Give it time — if signal is stale, block-for-message wakes immediately
      (Thread/sleep 300)
      ;; block-result should NOT be delivered yet (agent should be blocked)
      (is (not (realized? block-result))
          "block-for-message should not have returned (stale signal)")
      ;; Now send a real message to wake it
      (runtime/-send! handle (append-forms-macro '(def y 2)))
      ;; Agent should wake from the real message
      (let [result (deref block-result 2000 :timeout)]
        (is (not= :timeout result)
            "agent should wake from real message")
        (is (.contains ^String result "(def y 2)")
            "woken raw should contain the new message")))))

(deftest effect-guard-allows-in-second-pass-test
  (testing "dangerous fns work through double-evaluation (eval special form)"
    ;; agents/!ask resolves through eval double-evaluation but fails at runtime
    (let [llm (th/make-test-runner {:response "(agents/!ask :nobody \"hello\")))"}
                                   :recover false)]
      (is (thrown-with-msg? Exception #"not inside an agent context|not registered"
            (llm "(eval (do '"))))))
