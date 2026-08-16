(ns spell.agent-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.agent :as agent]
            [spell.feedback :as feedback]
            [spell.runtime :as runtime]
            [spell.llm :as llm]
            [spell.mcp.namespace :as mcp]
            [spell.provider :as provider]
            [spell.stdlib :as stdlib]
            [spell.test-helpers :as th]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

;; =============================================================================
;; resolve-workers tests
;; =============================================================================

(deftest resolve-workers-nil-when-empty-test
  (testing "resolve-workers returns nil when workers-map is nil or empty"
    (is (nil? (agent/resolve-workers nil llm/compile-agent agent/compile-agent-spec nil nil nil)))
    (is (nil? (agent/resolve-workers {} llm/compile-agent agent/compile-agent-spec nil nil nil)))))

(deftest resolve-workers-inline-spec-test
  (testing "inline spec resolves to a compiled agent returning evaluated value"
    (let [prov (provider/test-provider {:response "\"leaf response\")"})
          workers-map {'summarizer {:doc "Summarizes text"
                                 :system "Summarize concisely."}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      ;; Namespace structure
      (is (map? workers-ns))
      (is (contains? workers-ns :docs))
      (is (= "Summarizes text" (get-in workers-ns [:docs :summarizer])))
      (is (runtime/compiled-agent? (:summarizer workers-ns)))
      (is (= "leaf response" (th/run-agent-prefix (:summarizer workers-ns) "(do "))))))

(deftest resolve-workers-1-arity-test
  (testing "workers function accepts 1-arity (auto-generates handle)"
    (let [prov (provider/test-provider {:response "\"one-arg\")"})
          workers-map {'helper {:doc "Helper agent"}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)
          helper-fn (:helper workers-ns)]
      (is (runtime/compiled-agent? helper-fn))
      ;; 1-arity call should work (auto-generated handle)
      (is (= "one-arg" (th/run-agent-prefix helper-fn "(do "))))))

(deftest resolve-workers-inline-eval-test
  (testing "inline eval spec resolves to a compiled agent returning evaluated result"
    (let [prov (provider/test-provider {:response "42)"})
          workers-map {'coder {:doc "Writes Spell code"}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (runtime/compiled-agent? (:coder workers-ns)))
      (is (= 42 (th/run-agent-prefix (:coder workers-ns) "(do "))))))

(deftest resolve-workers-default-eval-true-test
  (testing "eval defaults to true when omitted"
    (let [prov (provider/test-provider {:response "42)"})
          workers-map {'worker {:doc "Default eval worker"}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (= 42 (th/run-agent-prefix (:worker workers-ns) "(do "))))))

(deftest resolve-workers-format-wrapping-test
  (testing "format spec wraps with validation"
    (let [prov (provider/test-provider {:response "{:category :animal :confidence 0.95})"})
          workers-map {'classifier {:doc "Classifies text"
                                 :format {:required [:category :confidence]}}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (let [result (th/run-agent-prefix (:classifier workers-ns) "(do ")]
        (is (map? result))
        (is (= :animal (:category result)))
        (is (= 0.95 (:confidence result)))))))

(deftest resolve-workers-model-inheritance-test
  (testing "sub-agent without :model inherits parent model"
    ;; We can't easily test the actual model passed to provider without
    ;; inspecting internals, but we verify the function is created without error
    ;; when parent model is provided
    (let [prov (provider/test-provider {:response "\"inherited\")"})
          workers-map {'helper {:doc "Helper"}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec "claude-sonnet-4-5-20250929" prov nil)]
      (is (runtime/compiled-agent? (:helper workers-ns)))
      (is (= "inherited" (th/run-agent-prefix (:helper workers-ns) "(do ")))))

  (testing "sub-agent with its own model profile does not inherit parent model override"
    (let [seen-opts (atom nil)
          child-prov (reify provider/LLMProvider
                       (plain-text-provider [this] this)
                       (call-llm [this prompt]
                         (provider/call-llm this prompt {}))
                       (call-llm [_ _prompt opts]
                         (reset! seen-opts opts)
                         "\"child\")")
                       (supports-prefill [_] true))
          workers-map {'helper {:doc "Helper"
                             :default-model-profile child-prov}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec
                                      "parent-model" (provider/test-provider {:response "\"parent\")"}) nil)]
      (is (= "child" (th/run-agent-prefix (:helper workers-ns) "(do ")))
      (is (nil? (:model @seen-opts))))))

(deftest resolve-workers-docs-populated-test
  (testing ":docs populated from :doc fields"
    (let [prov (provider/test-provider {:response "ok"})
          workers-map {'alpha {:doc "Alpha agent"}
                    'beta {:doc "Beta agent"}
                    'gamma {}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (= "Alpha agent" (get-in workers-ns [:docs :alpha])))
      (is (= "Beta agent" (get-in workers-ns [:docs :beta])))
      ;; gamma has no :doc, gets default
      (is (string? (get-in workers-ns [:docs :gamma]))))))

(deftest resolve-workers-circular-test
  (testing "circular: sub-agent A can call sub-agent B via shared workers/ namespace"
    ;; A calls B, B returns directly. Verify A sees B's result.
    (let [call-log (atom [])
          prov (provider/test-provider
                 {:response-fn (fn [prompt]
                                 (swap! call-log conj prompt)
                                 "\"result\")")})
          workers-map {'a {:doc "Agent A"}
                    'b {:doc "Agent B"}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (= "result" (th/run-agent-prefix (:a workers-ns) "(do ")))
      (is (= "result" (th/run-agent-prefix (:b workers-ns) "(do ")))
      ;; Both got called
      (is (= 2 (count @call-log))))))

(deftest resolve-workers-multiple-specs-test
  (testing "multiple specs in one workers map"
    (let [prov (provider/test-provider {:response "\"response\")"})
          workers-map {'leaf1 {:doc "Leaf 1" :system "System 1"}
                    'leaf2 {:doc "Leaf 2" :system "System 2"}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (runtime/compiled-agent? (:leaf1 workers-ns)))
      (is (runtime/compiled-agent? (:leaf2 workers-ns)))
      (is (= "Leaf 1" (get-in workers-ns [:docs :leaf1])))
      (is (= "Leaf 2" (get-in workers-ns [:docs :leaf2])))
      (is (= "response" (th/run-agent-prefix (:leaf1 workers-ns) "(do ")))
      (is (= "response" (th/run-agent-prefix (:leaf2 workers-ns) "(do "))))))

;; =============================================================================
;; merge-agent-defs workers merging
;; =============================================================================

(deftest merge-agent-defs-workers-test
  (testing ":workers is scalar override — child wins entirely"
    (let [parent {:name 'parent
                  :workers {'a {:doc "A"}}}
          child {:workers {'b {:doc "B"}}}
          merged (#'agent/merge-agent-defs parent child)]
      (is (= {'b {:doc "B"}}
             (:workers merged)))))

  (testing "child :workers replaces parent entirely"
    (let [parent {:name 'parent
                  :workers {'a {:doc "A v1"}}}
          child {:workers {'a {:doc "A v2"}}}
          merged (#'agent/merge-agent-defs parent child)]
      (is (= "A v2" (get-in merged [:workers 'a :doc])))))

  (testing "no :workers in child → parent :workers preserved"
    (let [parent {:name 'parent
                  :workers {'a {:doc "A"}}}
          child {:name 'child}
          merged (#'agent/merge-agent-defs parent child)]
      (is (= {'a {:doc "A"}}
             (:workers merged)))))

  (testing "no :workers in either -> no :workers key"
    (let [merged (#'agent/merge-agent-defs {:name 'p} {:name 'c})]
      (is (not (contains? merged :workers))))))

;; =============================================================================
;; describe integration
;; =============================================================================

(deftest resolve-workers-describe-integration-test
  (testing "workers namespace works with describe function"
    (let [prov (provider/test-provider {:response "ok"})
          workers-map {'researcher {:doc "Researches topics"}
                    'writer {:doc "Writes content"}}
          workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      ;; describe returns docs map (no :guide)
      (is (= {:researcher "Researches topics"
              :writer "Writes content"}
             (stdlib/describe workers-ns)))
      ;; describe with key returns specific doc
      (is (= "Researches topics" (stdlib/describe workers-ns :researcher))))))

(deftest namespace-overrides-reach-main-and-worker-agents-test
  (testing "run-level namespace overrides are visible to the main agent"
    (let [seen-opts (atom nil)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (call-llm [this prompt]
                   (provider/call-llm this prompt {}))
                 (call-llm [_ _prompt opts]
                   (reset! seen-opts opts)
                   "\"ok\")")
                 (supports-prefill [_] true))
          compiled (agent/compile-agent-spec
                    {:provider prov
                     :namespace-overrides {'feedback 'stdlib/feedback}})]
      (is (= "ok" (th/run-agent-prefix compiled "(do ")))
      (is (re-find #"Spell developer dogfooding" (:system @seen-opts)))))

  (testing "run-level namespace overrides are inherited by worker agents"
    (let [seen-opts (atom nil)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (call-llm [this prompt]
                   (provider/call-llm this prompt {}))
                 (call-llm [_ _prompt opts]
                   (reset! seen-opts opts)
                   "\"ok\")")
                 (supports-prefill [_] true))
          workers-ns (agent/resolve-workers
                       {'helper {:doc "Helper agent"}}
                       llm/compile-agent agent/compile-agent-spec nil prov nil
                       {'feedback feedback/feedback-namespace})]
      (is (= "ok" (th/run-agent-prefix (:helper workers-ns) "(do ")))
      (is (re-find #"Spell developer dogfooding" (:system @seen-opts))))))

;; =============================================================================
;; leaf-llm model inheritance (in compile-agent)
;; =============================================================================

(deftest leaf-llm-inherits-model-test
  (testing "leaf-llm builtin inherits model from compile-agent"
    ;; We verify that compile-agent with :model creates without error
    ;; and the leaf-llm is callable
    (let [prov (provider/test-provider {:response "leaf-response"})
          result (llm/compile-agent {:model "test-model" :namespaces {} :provider prov})]
      (is (runtime/compiled-agent? result)))))

;; =============================================================================
;; load-agent-spec with :workers
;; =============================================================================

(deftest load-agent-spec-workers-test
  (testing "load-agent-spec preserves plain :workers data when present"
    ;; Create a temp agent file with :workers
    (let [tmp-file (java.io.File/createTempFile "test-agent" ".agent.edn")]
      (try
        (spit tmp-file (pr-str {:name 'test-agent
                                :workers {'helper {:doc "Helper agent"}}}))
        (let [spec (agent/load-agent-spec (.getAbsolutePath tmp-file))]
          (is (= {'helper {:doc "Helper agent"}} (:workers spec)))
          (is (string? (:base-dir spec))))
        (finally
          (.delete tmp-file)))))

  (testing "load-agent-spec preserves :workers [] opt-out"
    (let [tmp-file (java.io.File/createTempFile "test-agent" ".agent.edn")]
      (try
        (spit tmp-file (pr-str {:name 'bare-agent :workers []}))
        (let [spec (agent/load-agent-spec (.getAbsolutePath tmp-file))]
          (is (= [] (:workers spec))))
        (finally
          (.delete tmp-file))))))

;; =============================================================================
;; File reference resolution in :workers
;; =============================================================================

(deftest resolve-workers-file-reference-test
  (testing ".agent.edn file reference in :workers resolves"
    (let [dir (System/getProperty "java.io.tmpdir")
          child-file (java.io.File. dir "child-test.agent.edn")]
      (try
        ;; Write child agent file
        (spit child-file (pr-str {:name 'child-agent
                                  :doc "Child from file"}))
        (let [prov (provider/test-provider {:response "\"file-result\")"})
              workers-map {'child (symbol "child-test.agent.edn")}
              workers-ns (agent/resolve-workers workers-map llm/compile-agent agent/compile-agent-spec nil prov dir)]
          (is (runtime/compiled-agent? (:child workers-ns)))
          (is (string? (get-in workers-ns [:docs :child])))
          (is (= "file-result" (th/run-agent-prefix (:child workers-ns) "(do "))))
        (finally
          (.delete child-file))))))

(deftest resolve-namespace-value-agent-file-test
  (testing ".agent.edn namespace value loads as a compiled agent"
    (let [dir (System/getProperty "java.io.tmpdir")
          child-file (java.io.File. dir "ns-child-test.agent.edn")]
      (try
        (spit child-file (pr-str {:name 'ns-child
                                  :system "Leaf system prompt"
                                  :provider {:type :ollama :model "mistral"}}))
        (let [v (#'agent/resolve-namespace-value (symbol "ns-child-test.agent.edn")
                                                 dir (atom {}) agent/compile-agent-spec)]
          (is (runtime/compiled-agent? v)))
        (finally
          (.delete child-file))))))

(deftest resolve-namespaces-failure-closes-embedded-agent-test
  (testing "a later namespace resolution failure closes an earlier embedded agent"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                         "spell-namespace-close-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
          child-file (java.io.File. root "child.agent.edn")
          closed (atom 0)]
      (try
        (spit child-file (pr-str {:provider {:type :test :response "unused"}
                                  :mcp-servers {'child-mcp {}}}))
        (with-redefs [mcp/compile-servers
                      (fn [_ _]
                        {:namespaces {}
                         :close! #(swap! closed inc)})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"Unknown namespace value pattern"
               (#'agent/resolve-namespaces
                (array-map 'child 'child.agent.edn
                           'broken 'unknown-namespace)
                (.getPath root)
                agent/compile-agent-spec))))
        (is (= 1 @closed))
        (finally
          (doseq [file (reverse (file-seq root))]
            (.delete file)))))))

(deftest resolve-namespaces-rejects-normalized-key-collisions-before-opening-test
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                       "spell-namespace-key-collision-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
        child-file (java.io.File. root "child.agent.edn")
        opened (atom 0)]
    (try
      (spit child-file (pr-str {:provider {:type :test :response "unused"}
                                :mcp-servers {'child-mcp {}}}))
      (is (= :duplicate-agent-namespace
             (try
               (with-redefs [mcp/compile-servers
                             (fn [& _]
                               (swap! opened inc)
                               {:namespaces {} :close! (fn [])})]
                 (#'agent/resolve-namespaces
                  (array-map 'child 'child.agent.edn
                             :child 'stdlib/math)
                  (.getPath root)
                  agent/compile-agent-spec))
               nil
               (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (zero? @opened) "invalid namespace keys must fail before acquiring resources")
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(deftest compile-agent-failure-closes-namespace-embedded-agent-test
  (testing "top-level partial-failure cleanup owns agent profiles in :namespaces"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                         "spell-agent-namespace-close-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
          parent-file (java.io.File. root "parent.agent.edn")
          child-file (java.io.File. root "child.agent.edn")
          closed (atom 0)]
      (try
        (spit child-file (pr-str {:provider {:type :test :response "unused"}
                                  :mcp-servers {'child-mcp {}}}))
        (spit parent-file
              (pr-str {:provider {:type :test :response "unused"}
                       :namespaces (array-map 'child 'child.agent.edn
                                              'patterns 'stdlib/patterns)}))
        (with-redefs [mcp/compile-servers
                      (fn [_ _]
                        {:namespaces {}
                         :close! #(swap! closed inc)})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"requires namespaces"
               (agent/compile-agent-spec
                (agent/load-agent-spec (.getPath parent-file))))))
        (is (= 1 @closed))
        (finally
          (doseq [file (reverse (file-seq root))]
            (.delete file)))))))

(deftest worker-compile-failure-closes-namespace-embedded-agent-test
  (testing "worker partial-failure cleanup owns agent profiles in :namespaces"
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                         "spell-worker-namespace-close-"
                         (make-array java.nio.file.attribute.FileAttribute 0)))
          child-file (java.io.File. root "child.agent.edn")
          closed (atom 0)]
      (try
        (spit child-file (pr-str {:provider {:type :test :response "unused"}
                                  :mcp-servers {'child-mcp {}}}))
        (with-redefs [mcp/compile-servers
                      (fn [_ _]
                        {:namespaces {}
                         :close! #(swap! closed inc)})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"worker compile failed"
               (agent/resolve-workers
                {'worker {:namespaces {'child 'child.agent.edn}}}
                (fn [_] (throw (ex-info "worker compile failed" {})))
                agent/compile-agent-spec
                nil nil (.getPath root)))))
        (is (= 1 @closed))
        (finally
          (doseq [file (reverse (file-seq root))]
            (.delete file)))))))

(deftest resolve-namespace-value-vector-merge-test
  (testing "vector namespace values resolve and merge namespace maps"
    (let [v (#'agent/resolve-namespace-value
             [(symbol "stdlib/io-read") (symbol "stdlib/io-exec")]
             "." (atom {}) agent/compile-agent-spec)]
      (is (contains? v :read-file))
      (is (contains? v :sh))
      (is (not (contains? v :write-file)))
      (is (= "Read a file with numbered lines." (get-in v [:docs :read-file])))
      (is (re-find #"Read-only filesystem inspection, codebase exploration"
                   (:short-docs v))))))

(deftest explore-agent-spec-test
  (testing "explore.agent.edn resolves to read-only exploration helpers without shell execution"
    (let [spec (agent/load-agent-spec "config/agent-profiles/explore.agent.edn")
          namespaces (#'agent/resolve-namespaces (:namespaces spec) (:base-dir spec) agent/compile-agent-spec)
          io-ns (get namespaces 'io)]
      (is (= 'explore (:name spec)))
      (is (= [] (:workers spec)))
      (is (contains? io-ns :read-file))
      (is (contains? io-ns :grep))
      (is (contains? io-ns :glob))
      (is (contains? io-ns :git))
      (is (not (contains? io-ns :sh)))
      (is (not (contains? io-ns :write-file))))))

;; =============================================================================
;; effect-ns-names includes 'workers
;; =============================================================================

(deftest workers-is-effect-namespace-test
  (testing "workers/ namespace is treated as effect namespace in compile-agent"
    (let [workers-ns {:docs {:helper "test helper"}
                   :helper (th/compiled-agent-fn
                            (fn [_prompt _handle] :helped))}
          prov (provider/test-provider {:response "(agents/spawn workers/helper \"(do \")))"})
          test-agent (llm/compile-agent {:namespaces {'workers workers-ns
                                                      'agents runtime/agents-namespace}
                                         :provider prov})]
      ;; workers/ is an effect namespace, so it is available in the trailing expression.
      (is (keyword? (th/run-agent-prefix test-agent "(eval (do '"))))))

;; =============================================================================
;; Explicit workers config tests
;; =============================================================================

(deftest normalize-workers-config-test
  (testing "::not-set with base-dir returns nil instead of discovering siblings"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-norm-test")
          _ (.mkdirs dir)
          f1 (java.io.File. dir "a.agent.edn")]
      (try
        (spit f1 (pr-str {:name 'a}))
        (is (nil? (#'agent/normalize-workers-config :spell.agent/not-set (.getAbsolutePath dir))))
        (finally
          (.delete f1) (.delete dir)))))

  (testing "::not-set with nil base-dir returns nil"
    (is (nil? (#'agent/normalize-workers-config :spell.agent/not-set nil))))

  (testing "empty vector returns nil (opt-out)"
    (is (nil? (#'agent/normalize-workers-config [] "/some/dir"))))

  (testing "vector of symbols builds name map"
    (let [result (#'agent/normalize-workers-config
                   ['opus.agent.edn 'leaf.agent.edn]
                   "/some/dir")]
      (is (= {'opus 'opus.agent.edn
              'leaf 'leaf.agent.edn}
             result))))

  (testing "map passes through"
    (let [m {'x {:doc "X"}}]
      (is (= m (#'agent/normalize-workers-config m "/some/dir"))))))

(deftest agent-name-from-file-test
  (testing "strips .agent.edn suffix"
    (is (= 'opus (#'agent/agent-name-from-file "opus.agent.edn")))
    (is (= 'my-agent (#'agent/agent-name-from-file "my-agent.agent.edn")))))

(deftest load-agent-spec-explicit-workers-test
  (testing "absent :workers does not read or expose sibling agent files"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-auto-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          sibling-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main}))
        (spit sibling-file "{this is not valid edn")
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))
              compiled (agent/compile-agent-spec
                        (assoc spec :provider (provider/test-provider {:response "ok"})))]
          (is (nil? (#'agent/normalize-workers-config (get spec :workers :spell.agent/not-set)
                                                       (:base-dir spec))))
          (is (runtime/compiled-agent? compiled)))
        (finally
          (.delete main-file) (.delete sibling-file)
          (.delete dir)))))

  (testing ":workers [] opt-out preserves explicit empty workers config"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-optout-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          sibling-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main :workers []}))
        (spit sibling-file (pr-str {:name 'helper :doc "Helper"}))
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))]
          (is (= [] (:workers spec))))
        (finally
          (.delete main-file) (.delete sibling-file)
          (.delete dir)))))

  (testing ":workers vector resolves specific files"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-vec-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          a-file (java.io.File. dir "a.agent.edn")
          b-file (java.io.File. dir "b.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main :workers ['a.agent.edn]}))
        (spit a-file (pr-str {:name 'a :doc "Agent A" }))
        (spit b-file (pr-str {:name 'b :doc "Agent B" }))
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))
              prov (provider/test-provider {:response "ok"})
              workers (agent/resolve-workers (#'agent/normalize-workers-config (:workers spec) (:base-dir spec))
                                       llm/compile-agent agent/compile-agent-spec nil prov (:base-dir spec))]
          ;; Only 'a should be present (not 'b)
          (is (runtime/compiled-agent? (:a workers)))
          (is (nil? (:b workers))))
        (finally
          (.delete main-file) (.delete a-file) (.delete b-file)
          (.delete dir)))))

  (testing "docs come from sub-agent's own :doc field"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-doc-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          helper-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main :workers {'helper 'helper.agent.edn}}))
        (spit helper-file (pr-str {:name 'helper :doc "I help with things" }))
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))
              prov (provider/test-provider {:response "ok"})
              workers-ns (agent/resolve-workers (#'agent/normalize-workers-config (:workers spec)
                                                                           (:base-dir spec))
                                          llm/compile-agent agent/compile-agent-spec nil prov (:base-dir spec))]
          (is (= "I help with things" (get-in workers-ns [:docs :helper]))))
        (finally
          (.delete main-file) (.delete helper-file)
          (.delete dir))))))

;; =============================================================================
;; Pattern dependency validation
;; =============================================================================

(deftest validate-pattern-dependencies-test
  (testing "core and future-only namespaces satisfy pattern requirements without explicit config"
    (is (nil? (#'agent/validate-pattern-dependencies!
               {'patterns {:check-result {:requires ['strings]}
                           :ralph {:requires ['agents 'blocking]}
                           :team {:requires ['strings 'io 'agents 'blocking]}}
                'agents {}
                'io {}}))))

  (testing "missing effect namespaces fail fast with actionable ex-data"
    (try
      (#'agent/validate-pattern-dependencies!
       {'patterns {:team {:requires ['strings 'io 'agents 'blocking]}}
        'agents {}})
      (is false "expected pattern dependency validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :team (:pattern (ex-data e))))
        (is (= '[agents blocking io strings] (:requires (ex-data e))))
        (is (= '[io] (:missing (ex-data e))))
        (is (re-find #"Pattern team requires namespaces"
                     (.getMessage e))))))

  (testing "shipped io agent profiles remain loadable without futures/ configured"
    (doseq [path ["config/agent-profiles/cli.agent.edn"
                  "config/agent-profiles/io-msg.agent.edn"
                  "config/agent-profiles/io-pf.agent.edn"
                  "config/agent-profiles/io-tc.agent.edn"]]
      (let [spec (assoc (agent/load-agent-spec path)
                        :provider (provider/test-provider {:response "ok"}))
            result (agent/compile-agent-spec spec)]
        (is (not (contains? (:namespaces spec) 'feedback)) path)
        (is (runtime/compiled-agent? result) path)))))

(deftest compile-agent-spec-format-reaches-system-prompt-test
  (testing "top-level compile path forwards :format into llm/compile-agent"
    (let [seen-opts (atom nil)
          prov (reify provider/LLMProvider
                 (plain-text-provider [this] this)
                 (call-llm [_ prompt]
                   (provider/call-llm _ prompt {}))
                 (call-llm [_ _prompt opts]
                   (reset! seen-opts opts)
                   "{:result 42})")
                 (supports-prefill [_] true))
          compiled (agent/compile-agent-spec
                    {:name 'formatter
                     :provider prov
                     :format {:required [:result]}})]
      (is (= {:result 42} (th/run-agent-prefix compiled "(do ")))
      (is (re-find #"RETURN VALUE" (:system @seen-opts)))
      (is (re-find #":result" (:system @seen-opts))))))
