(ns spell.api-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.api :as api]
            [spell.runtime :as runtime]
            [spell.globals :as globals]
            [spell.mcp.namespace :as mcp]
            [spell.provider :as provider])
  (:import [java.io StringReader StringWriter]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (runtime/reset-wait-graph!)
    (f)
    (reset! runtime/registry {})
    (runtime/reset-wait-graph!)))

;; =============================================================================
;; Basic run tests
;; =============================================================================

(def test-agent "config/agent-profiles/base-msg.agent.edn")

(defn- observing-provider [seen-opts response]
  (reify provider/LLMProvider
    (call-llm [this prompt] (provider/call-llm this prompt {}))
    (call-llm [_ _prompt opts]
      (reset! seen-opts opts)
      response)
    (plain-text-provider [this] this)
    (supports-prefill [_] true)))

(deftest run-prompt-test
  (testing "run with :prompt triggers LLM call and returns result"
    ;; build-init wraps "Return 42" into a quine with '(extend).
    ;; extend calls !llm-self which calls the provider.
    ;; The response completes the program.
    (let [p (provider/test-provider {:response "(def x 42))"})
          result (api/run {:prompt "Return 42"
                           :model-profile p
                           :agent-profile test-agent})]
      (is (contains? result :result))
      (is (= 42 (:result result)))
      (is (some? (:usage-tracker result)))))

  (testing "run with :init evaluates complete program directly"
    ;; :init takes a COMPLETE Spell program (balanced, no LLM needed)
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do 42)"
                           :model-profile p
                           :agent-profile test-agent})]
      (is (= 42 (:result result)))))

  (testing "run with :prompt preserves prompt wrapping even when it starts with ("
    (let [call-count (atom 0)
          p (provider/test-provider
              {:response-fn (fn [_]
                              (swap! call-count inc)
                              "(def answer 42))")})
          result (api/run {:prompt "(+ 1 2)"
                           :model-profile p
                           :agent-profile test-agent})]
      (is (= 42 (:result result)))
      (is (= 1 @call-count))))

  (testing "run catches errors gracefully"
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do undefined-symbol)"
                           :model-profile p
                           :agent-profile test-agent})]
      (is (contains? result :error))
      (is (some? (:usage-tracker result))))))

(deftest independent-runs-reset-wait-graph-test
  (testing "a stale claimed edge cannot bind a reused handle in a later run"
    (let [p (provider/test-provider {:response "unused"})]
      (is (= 41 (:result (api/run {:init "(do 41)"
                                   :model-profile p
                                   :agent-profile test-agent}))))
      ;; Model an edge left by an earlier run whose target lifecycle had
      ;; already claimed its result position. Without a graph reset, the next
      ;; :main lifecycle fills this edge and tries to notify a source removed
      ;; by the registry reset.
      (runtime/register! :stale-source)
      (let [edge-id (runtime/create-edge! :stale-source [:main])]
        (#'runtime/claim-slot! edge-id :main 1)
        (is (= :pending
               (get-in @runtime/wait-graph
                       [:edges edge-id :slots :main :status]))))
      (let [result (api/run {:init "(do 42)"
                             :model-profile p
                             :agent-profile test-agent})]
        (is (= 42 (:result result)))
        (is (nil? (:error result)))
        (is (empty? (:edges @runtime/wait-graph)))))))

(deftest detached-old-lifecycle-cannot-mutate-new-run-test
  (testing "a detached lifecycle cannot finish a reused handle in a later API run"
    (runtime/register! :worker)
    (let [old-started (promise)
          release-old (promise)
          initial (promise)
          old-worker
          (future
            (runtime/run-root-box
              :worker initial
              (fn [_raw]
                (deliver old-started true)
                @release-old
                :old-result)
              identity))]
      (deliver initial "(do 0)")
      (is (true? (deref old-started 2000 false)))

      ;; api/run advances the runtime epoch without waiting for :worker.
      (let [p (provider/test-provider {:response "unused"})]
        (is (= 42 (:result (api/run {:init "(do 42)"
                                     :model-profile p
                                     :agent-profile test-agent})))))

      ;; Reuse the same handle and install a claimed result position in the
      ;; new run before allowing the old lifecycle to return.
      (runtime/register! :new-source)
      (runtime/register! :worker)
      (let [edge-id (runtime/create-edge! :new-source [:worker])
            generation (get-in @runtime/wait-graph [:nodes :worker :generation])
            _ (#'runtime/claim-slot! edge-id :worker generation)
            edge-before (get-in @runtime/wait-graph [:edges edge-id])
            node-before (get-in @runtime/wait-graph [:nodes :worker])
            source-state-before @(:state (get @runtime/registry :new-source))
            worker-state-before @(:state (get @runtime/registry :worker))
            new-completion @(:completed (get @runtime/registry :worker))]
        (deliver release-old true)
        (is (= :old-result (deref old-worker 2000 :timeout)))
        (is (= edge-before (get-in @runtime/wait-graph [:edges edge-id])))
        (is (= node-before (get-in @runtime/wait-graph [:nodes :worker])))
        (is (= source-state-before @(:state (get @runtime/registry :new-source))))
        (is (= worker-state-before @(:state (get @runtime/registry :worker))))
        (is (not (realized? new-completion)))

        ;; The new lifecycle still owns the claimed slot and completes it
        ;; normally after the stale lifecycle has been ignored.
        (is (= :new-result
               (runtime/run-root-box :worker "(do 0)"
                                     (fn [_raw] :new-result)
                                     identity)))
        (is (= :new-result (deref new-completion 2000 :timeout)))
        (is (nil? (get-in @runtime/wait-graph [:edges edge-id])))
        (is (= :finished (get-in @runtime/wait-graph [:nodes :worker :status])))
        (is (= 1 (count (:inbox-macros
                         @(:state (get @runtime/registry :new-source))))))))))

(deftest run-closes-namespace-embedded-agent-test
  (testing "api/run closes MCP resources owned by an agent profile in :namespaces"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                         "spell-api-namespace-close-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
          parent-file (java.io.File. root "parent.agent.edn")
          child-file (java.io.File. root "child.agent.edn")
          opened (atom 0)
          closed (atom 0)]
      (try
        (spit child-file (pr-str {:provider {:type :test :response "unused"}
                                  :mcp-servers {'child-mcp {}}}))
        (spit parent-file (pr-str {:namespaces {'child 'child.agent.edn}}))
        (let [result (with-redefs [mcp/compile-servers
                                   (fn [_ _]
                                     (swap! opened inc)
                                     {:namespaces {}
                                      :close! #(swap! closed inc)})]
                       (api/run {:init "(do 42)"
                                 :model-profile (provider/test-provider {:response "unused"})
                                 :agent-profile (.getPath parent-file)}))]
          (is (= 42 (:result result)))
          (is (= 1 @opened))
          (is (= 1 @closed)))
        (finally
          (doseq [file (reverse (file-seq root))]
            (.delete file)))))))

(deftest run-internal-agent-namespace-override-test
  (testing "internal callers can overlay the dogfood namespace onto the selected profile"
    (let [seen-opts (atom nil)
          p (observing-provider seen-opts "(def answer 42))")
          result (api/run-internal
                  {:prompt "Return 42"
                   :model-profile p
                   :agent-profile test-agent
                   :agent-namespace-overrides {'feedback 'stdlib/feedback}})]
      (is (= 42 (:result result)))
      (is (re-find #"Spell developer dogfooding" (:system @seen-opts))))))

;; =============================================================================
;; Validation tests
;; =============================================================================

(deftest run-validation-test
  (testing "throws when both :prompt and :init provided"
    (is (thrown-with-msg? Exception #"exactly one"
          (api/run {:prompt "hello" :init "(do )"
                    :model-profile (provider/test-provider {:response "unused"})
                    :agent-profile test-agent}))))

  (testing "throws when neither :prompt nor :init provided"
    (is (thrown-with-msg? Exception #"Must specify"
          (api/run {:model-profile (provider/test-provider {:response "unused"})
                    :agent-profile test-agent}))))

  (testing "throws when :agent-profile missing"
    (is (thrown-with-msg? Exception #"Must specify :agent-profile"
          (api/run {:prompt "hello"
                    :model-profile (provider/test-provider {:response "unused"})}))))

  (testing "throws when :model-profile missing"
    (is (thrown-with-msg? Exception #"Must specify :model-profile"
          (api/run {:prompt "hello"
                    :agent-profile test-agent}))))

  (testing "throws on unknown public option"
    (is (thrown-with-msg? Exception #"Unknown public run option"
          (api/run {:prompt "hello"
                    :model-profile (provider/test-provider {:response "unused"})
                    :agent-profile test-agent
                    :bogus true})))))

;; =============================================================================
;; Init program tests
;; =============================================================================

(deftest run-init-program-test
  (testing "init program evaluates without LLM call for first pass"
    (let [call-count (atom 0)
          p (provider/test-provider
              {:response-fn (fn [_]
                              (swap! call-count inc)
                              "42)")})
          result (api/run {:init "(do 42)"
                           :model-profile p
                           :agent-profile test-agent})]
      ;; The init program (do 42) evaluates directly — no LLM call needed
      (is (= 42 (:result result)))
      (is (= 0 @call-count))))

  (testing "init with extend triggers LLM call"
    (let [call-count (atom 0)
          p (provider/test-provider
              {:response-fn (fn [_]
                              (swap! call-count inc)
                              "(def answer 42))")})
          result (api/run {:init "(quine completion (eval (do '(!extend))))"
                           :model-profile p
                           :agent-profile test-agent})]
      (is (= 42 (:result result)))
      (is (= 1 @call-count))))

  (testing "non-list init programs evaluate directly"
    (doseq [[program expected] [["42" 42]
                                ["\"hello\"" "hello"]]]
      (let [call-count (atom 0)
            p (provider/test-provider
                {:response-fn (fn [_]
                                (swap! call-count inc)
                                "unused")})
            result (api/run {:init program
                             :model-profile p
                             :agent-profile test-agent})]
        (is (= expected (:result result)))
        (is (= 0 @call-count))))))

;; =============================================================================
;; Budget and options tests
;; =============================================================================

(deftest run-options-test
  (testing "budget option is respected"
    (let [p (provider/test-provider {:response "should not be called"})
          result (api/run {:init "(do 42)"
                           :model-profile p
                           :agent-profile test-agent
                           :budget 10.0})]
      (is (= 42 (:result result)))))

  (testing "trace boolean is rejected"
    (is (thrown-with-msg? Exception #"Removed public run option"
          (api/run {:prompt "Return 42"
                    :model-profile (provider/test-provider {:response "(def x 42))"})
                    :agent-profile test-agent
                    :trace true}))))

  (testing "trace option respects provided trace-dir"
    (let [p (provider/test-provider {:response "(def x 42))"})
          trace-dir (.toString (java.nio.file.Files/createTempDirectory
                                 "spell-api-trace-"
                                 (make-array java.nio.file.attribute.FileAttribute 0)))
          result (api/run {:prompt "Return 42"
                           :model-profile p
                           :agent-profile test-agent
                           :trace-dir trace-dir})]
      (is (= 42 (:result result)))
      (is (= trace-dir (:trace-dir result)))
      (is (.exists (java.io.File. trace-dir "trace.edn")))))

  (testing "removed format option is rejected at the public API"
    (is (thrown-with-msg? Exception #"Removed public run option"
          (api/run {:prompt "Return 42"
                    :model-profile (provider/test-provider {:response "{:result 42}))"})
                    :agent-profile test-agent
                    :format {:required [:result]}})))))

  (testing "inline model profile map is accepted"
    (let [result (api/run {:prompt "Return 42"
                           :model-profile {:provider :test
                                        :response "(def x 42))"}
                           :agent-profile test-agent})]
      (is (= 42 (:result result)))))

  (testing "model profile path is accepted"
    (let [tmp (java.io.File/createTempFile "spell-model-profile-" ".edn")]
      (try
        (spit tmp (pr-str {:provider :test
                           :response "(def x 42))"}))
        (let [result (api/run {:prompt "Return 42"
                               :model-profile (.getAbsolutePath tmp)
                               :agent-profile test-agent})]
          (is (= 42 (:result result))))
        (finally
          (.delete tmp)))))

  (testing "run-level model and reasoning effort override provider defaults"
    (let [seen (atom nil)
          result (api/run {:prompt "Return 42"
                           :model-profile (observing-provider seen "(def x 42))")
                           :agent-profile test-agent
                           :model "override-model"
                           :reasoning-effort "high"})]
      (is (= 42 (:result result)))
      (is (= "override-model" (:model @seen)))
      (is (= "high" (:reasoning-effort @seen)))))

  (testing "log-writer is flushed but not closed"
    (let [closed? (atom false)
          writer (proxy [StringWriter] []
                   (close [] (reset! closed? true)))
          result (api/run {:prompt "Return 42"
                           :model-profile (provider/test-provider {:response "(def x 42))"})
                           :agent-profile test-agent
                           :log-writer writer})]
      (is (= 42 (:result result)))
      (is (false? @closed?))))

  (testing "user-reader registers :user"
    (let [result (api/run {:init "(do 42)"
                           :model-profile (provider/test-provider {:response "unused"})
                           :agent-profile test-agent
                           :user-reader (StringReader. "")})]
      (is (= 42 (:result result)))
      (is (contains? (globals/get-val :roles) :user))))
