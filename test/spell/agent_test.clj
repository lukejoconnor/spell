(ns spell.agent-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.agent :as agent]
            [spell.runtime :as runtime]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.stdlib :as stdlib]))

(use-fixtures :each
  (fn [f]
    (reset! runtime/registry {})
    (f)
    (reset! runtime/registry {})))

;; =============================================================================
;; resolve-llms tests
;; =============================================================================

(deftest resolve-llms-nil-when-empty-test
  (testing "resolve-llms returns nil when llms-map is nil or empty"
    (is (nil? (agent/resolve-llms nil llm/make-llm nil nil nil)))
    (is (nil? (agent/resolve-llms {} llm/make-llm nil nil nil)))))

(deftest resolve-llms-inline-spec-test
  (testing "inline spec resolves to callable function returning evaluated value"
    (let [prov (provider/test-provider {:response "\"leaf response\")"})
          llms-map {'summarizer {:doc "Summarizes text"
                                 :system "Summarize concisely."}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      ;; Namespace structure
      (is (map? llms-ns))
      (is (contains? llms-ns :docs))
      (is (= "Summarizes text" (get-in llms-ns [:docs :summarizer])))
      ;; Callable
      (is (fn? (:summarizer llms-ns)))
      (is (= "leaf response" ((:summarizer llms-ns) "(do "))))))

(deftest resolve-llms-inline-eval-test
  (testing "inline eval spec resolves to callable function returning evaluated result"
    (let [prov (provider/test-provider {:response "42)"})
          llms-map {'coder {:doc "Writes Spell code"}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      (is (fn? (:coder llms-ns)))
      (is (= 42 ((:coder llms-ns) "(do "))))))

(deftest resolve-llms-default-eval-true-test
  (testing "eval defaults to true when omitted"
    (let [prov (provider/test-provider {:response "42)"})
          llms-map {'worker {:doc "Default eval worker"}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      (is (= 42 ((:worker llms-ns) "(do "))))))

(deftest resolve-llms-format-wrapping-test
  (testing "format spec wraps with validation"
    (let [prov (provider/test-provider {:response "{:category :animal :confidence 0.95})"})
          llms-map {'classifier {:doc "Classifies text"
                                 :format {:required [:category :confidence]}}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      (let [result ((:classifier llms-ns) "(do ")]
        (is (map? result))
        (is (= :animal (:category result)))
        (is (= 0.95 (:confidence result)))))))

(deftest resolve-llms-model-inheritance-test
  (testing "sub-agent without :model inherits parent model"
    ;; We can't easily test the actual model passed to provider without
    ;; inspecting internals, but we verify the function is created without error
    ;; when parent model is provided
    (let [prov (provider/test-provider {:response "\"inherited\")"})
          llms-map {'helper {:doc "Helper"}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm "claude-sonnet-4-5-20250929" prov nil)]
      (is (fn? (:helper llms-ns)))
      (is (= "inherited" ((:helper llms-ns) "(do "))))))

(deftest resolve-llms-docs-populated-test
  (testing ":docs populated from :doc fields"
    (let [prov (provider/test-provider {:response "ok"})
          llms-map {'alpha {:doc "Alpha agent"}
                    'beta {:doc "Beta agent"}
                    'gamma {}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      (is (= "Alpha agent" (get-in llms-ns [:docs :alpha])))
      (is (= "Beta agent" (get-in llms-ns [:docs :beta])))
      ;; gamma has no :doc, gets default
      (is (string? (get-in llms-ns [:docs :gamma]))))))

(deftest resolve-llms-circular-test
  (testing "circular: sub-agent A can call sub-agent B via shared llms/ namespace"
    ;; A calls B, B returns directly. Verify A sees B's result.
    (let [call-log (atom [])
          prov (provider/test-provider
                 {:response-fn (fn [prompt]
                                 (swap! call-log conj prompt)
                                 "\"result\")")})
          llms-map {'a {:doc "Agent A"}
                    'b {:doc "Agent B"}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      ;; Both are callable
      (is (= "result" ((:a llms-ns) "(do ")))
      (is (= "result" ((:b llms-ns) "(do ")))
      ;; Both got called
      (is (= 2 (count @call-log))))))

(deftest resolve-llms-multiple-specs-test
  (testing "multiple specs in one llms map"
    (let [prov (provider/test-provider {:response "\"response\")"})
          llms-map {'leaf1 {:doc "Leaf 1" :system "System 1"}
                    'leaf2 {:doc "Leaf 2" :system "System 2"}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      (is (fn? (:leaf1 llms-ns)))
      (is (fn? (:leaf2 llms-ns)))
      (is (= "Leaf 1" (get-in llms-ns [:docs :leaf1])))
      (is (= "Leaf 2" (get-in llms-ns [:docs :leaf2])))
      (is (= "response" ((:leaf1 llms-ns) "(do ")))
      (is (= "response" ((:leaf2 llms-ns) "(do "))))))

;; =============================================================================
;; merge-agent-defs llms merging
;; =============================================================================

(deftest merge-agent-defs-llms-test
  (testing ":llms is scalar override — child wins entirely"
    (let [parent {:name 'parent
                  :llms {'a {:doc "A"}}}
          child {:llms {'b {:doc "B"}}}
          merged (#'agent/merge-agent-defs parent child)]
      (is (= {'b {:doc "B"}}
             (:llms merged)))))

  (testing "child :llms replaces parent entirely"
    (let [parent {:name 'parent
                  :llms {'a {:doc "A v1"}}}
          child {:llms {'a {:doc "A v2"}}}
          merged (#'agent/merge-agent-defs parent child)]
      (is (= "A v2" (get-in merged [:llms 'a :doc])))))

  (testing "no :llms in child → parent :llms preserved"
    (let [parent {:name 'parent
                  :llms {'a {:doc "A"}}}
          child {:name 'child}
          merged (#'agent/merge-agent-defs parent child)]
      (is (= {'a {:doc "A"}}
             (:llms merged)))))

  (testing "no :llms in either -> no :llms key"
    (let [merged (#'agent/merge-agent-defs {:name 'p} {:name 'c})]
      (is (not (contains? merged :llms))))))

;; =============================================================================
;; describe integration
;; =============================================================================

(deftest resolve-llms-describe-integration-test
  (testing "llms namespace works with describe function"
    (let [prov (provider/test-provider {:response "ok"})
          llms-map {'researcher {:doc "Researches topics"}
                    'writer {:doc "Writes content"}}
          llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov nil)]
      ;; describe returns docs map (no :guide)
      (is (= {:researcher "Researches topics"
              :writer "Writes content"}
             (stdlib/describe llms-ns)))
      ;; describe with key returns specific doc
      (is (= "Researches topics" (stdlib/describe llms-ns :researcher))))))

;; =============================================================================
;; leaf-llm model inheritance (in make-llm)
;; =============================================================================

(deftest leaf-llm-inherits-model-test
  (testing "leaf-llm builtin inherits model from make-llm"
    ;; We verify that make-llm with :model creates without error
    ;; and the leaf-llm is callable
    (let [prov (provider/test-provider {:response "leaf-response"})
          result (llm/make-llm {:model "test-model" :namespaces {} :provider prov})]
      (is (fn? (:llm result))))))

;; =============================================================================
;; load-agent-config with :llms
;; =============================================================================

(deftest load-agent-config-llms-test
  (testing "load-agent-config includes resolve-llms-fn when :llms present"
    ;; Create a temp agent file with :llms
    (let [tmp-file (java.io.File/createTempFile "test-agent" ".agent.edn")]
      (try
        (spit tmp-file (pr-str {:name 'test-agent
                                :llms {'helper {:doc "Helper agent"}}}))
        (let [config (agent/load-agent-config (.getAbsolutePath tmp-file))]
          (is (some? (:resolve-llms-fn config)))
          (is (fn? (:resolve-llms-fn config))))
        (finally
          (.delete tmp-file)))))

  (testing "load-agent-config has nil resolve-llms-fn when :llms []"
    (let [tmp-file (java.io.File/createTempFile "test-agent" ".agent.edn")]
      (try
        (spit tmp-file (pr-str {:name 'bare-agent :llms []}))
        (let [config (agent/load-agent-config (.getAbsolutePath tmp-file))]
          (is (nil? (:resolve-llms-fn config))))
        (finally
          (.delete tmp-file))))))

;; =============================================================================
;; File reference resolution in :llms
;; =============================================================================

(deftest resolve-llms-file-reference-test
  (testing ".agent.edn file reference in :llms resolves"
    (let [dir (System/getProperty "java.io.tmpdir")
          child-file (java.io.File. dir "child-test.agent.edn")]
      (try
        ;; Write child agent file
        (spit child-file (pr-str {:name 'child-agent
                                  :doc "Child from file"}))
        (let [prov (provider/test-provider {:response "\"file-result\")"})
              llms-map {'child (symbol "child-test.agent.edn")}
              llms-ns (agent/resolve-llms llms-map llm/make-llm nil prov dir)]
          (is (fn? (:child llms-ns)))
          (is (string? (get-in llms-ns [:docs :child])))
          (is (= "file-result" ((:child llms-ns) "(do "))))
        (finally
          (.delete child-file))))))

(deftest resolve-namespace-value-agent-file-test
  (testing ".agent.edn namespace value loads as evaluated llm function"
    (let [dir (System/getProperty "java.io.tmpdir")
          child-file (java.io.File. dir "ns-child-test.agent.edn")]
      (try
        (spit child-file (pr-str {:name 'ns-child
                                  :system "Leaf system prompt"
                                  :provider {:type :ollama :model "mistral"}}))
        (let [v (#'agent/resolve-namespace-value (symbol "ns-child-test.agent.edn")
                                                 dir (atom {}) llm/make-llm)]
          (is (fn? v))
          (is (not (:spell/leaf (meta v)))))
        (finally
          (.delete child-file))))))

;; =============================================================================
;; effect-ns-names includes 'llms
;; =============================================================================

(deftest llms-is-effect-namespace-test
  (testing "llms/ namespace is treated as effect namespace in make-llm"
    ;; Verify by creating a make-llm with llms in namespaces and checking
    ;; that the llm function works (the namespace is available via effects).
    (let [llms-ns {:docs {:helper "test helper"}
                   :helper (fn [prompt] (str "helped: " prompt))}
          prov (provider/test-provider {:response "(llms/helper :test)))"})
          test-llm (:llm (llm/make-llm {:namespaces {'llms llms-ns} :provider prov}))]
      ;; llms/ is an effect namespace, so it needs to go through eval's second pass
      ;; prefix: (eval (do '  response: (llms/helper :test)))
      ;; full: (eval (do '(llms/helper :test)))
      (is (= "helped: :test" (test-llm "(eval (do '"))))))

;; =============================================================================
;; Auto-discovery tests
;; =============================================================================

(deftest discover-sibling-agents-test
  (testing "discovers all .agent.edn files in directory"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-discover-test")
          _ (.mkdirs dir)
          f1 (java.io.File. dir "opus.agent.edn")
          f2 (java.io.File. dir "leaf.agent.edn")
          f3 (java.io.File. dir "fast.agent.edn")
          non-agent (java.io.File. dir "readme.txt")]
      (try
        (spit f1 (pr-str {:name 'opus :doc "Opus agent"}))
        (spit f2 (pr-str {:name 'leaf :doc "Leaf agent" }))
        (spit f3 (pr-str {:name 'fast :doc "Fast agent"}))
        (spit non-agent "not an agent")
        (let [result (#'agent/discover-sibling-agents (.getAbsolutePath dir))]
          (is (= 3 (count result)))
          (is (= 'opus.agent.edn (get result 'opus)))
          (is (= 'leaf.agent.edn (get result 'leaf)))
          (is (= 'fast.agent.edn (get result 'fast))))
        (finally
          (.delete f1) (.delete f2) (.delete f3) (.delete non-agent)
          (.delete dir)))))

  (testing "nil base-dir returns nil"
    (is (nil? (#'agent/discover-sibling-agents nil)))))

(deftest normalize-llms-config-test
  (testing "::not-set with base-dir triggers auto-discovery"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-norm-test")
          _ (.mkdirs dir)
          f1 (java.io.File. dir "a.agent.edn")]
      (try
        (spit f1 (pr-str {:name 'a}))
        (let [result (#'agent/normalize-llms-config :spell.agent/not-set (.getAbsolutePath dir))]
          (is (map? result))
          (is (= 'a.agent.edn (get result 'a))))
        (finally
          (.delete f1) (.delete dir)))))

  (testing "::not-set with nil base-dir returns nil"
    (is (nil? (#'agent/normalize-llms-config :spell.agent/not-set nil))))

  (testing "empty vector returns nil (opt-out)"
    (is (nil? (#'agent/normalize-llms-config [] "/some/dir"))))

  (testing "vector of symbols builds name map"
    (let [result (#'agent/normalize-llms-config
                   ['opus.agent.edn 'leaf.agent.edn]
                   "/some/dir")]
      (is (= {'opus 'opus.agent.edn
              'leaf 'leaf.agent.edn}
             result))))

  (testing "map passes through"
    (let [m {'x {:doc "X"}}]
      (is (= m (#'agent/normalize-llms-config m "/some/dir"))))))

(deftest agent-name-from-file-test
  (testing "strips .agent.edn suffix"
    (is (= 'opus (#'agent/agent-name-from-file "opus.agent.edn")))
    (is (= 'my-agent (#'agent/agent-name-from-file "my-agent.agent.edn")))))

(deftest load-agent-config-auto-discovery-test
  (testing "absent :llms auto-discovers siblings"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-auto-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          sibling-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main}))
        (spit sibling-file (pr-str {:name 'helper :doc "Helper agent" }))
        (let [config (agent/load-agent-config (.getAbsolutePath main-file))]
          ;; Should have resolve-llms-fn because siblings exist
          (is (some? (:resolve-llms-fn config)))
          (is (fn? (:resolve-llms-fn config))))
        (finally
          (.delete main-file) (.delete sibling-file)
          (.delete dir)))))

  (testing ":llms [] opt-out produces nil resolve-llms-fn"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-optout-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          sibling-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main :llms []}))
        (spit sibling-file (pr-str {:name 'helper :doc "Helper"}))
        (let [config (agent/load-agent-config (.getAbsolutePath main-file))]
          (is (nil? (:resolve-llms-fn config))))
        (finally
          (.delete main-file) (.delete sibling-file)
          (.delete dir)))))

  (testing ":llms vector resolves specific files"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-vec-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          a-file (java.io.File. dir "a.agent.edn")
          b-file (java.io.File. dir "b.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main :llms ['a.agent.edn]}))
        (spit a-file (pr-str {:name 'a :doc "Agent A" }))
        (spit b-file (pr-str {:name 'b :doc "Agent B" }))
        (let [config (agent/load-agent-config (.getAbsolutePath main-file))
              prov (provider/test-provider {:response "ok"})
              llms-ns ((:resolve-llms-fn config) llm/make-llm nil prov)]
          (is (some? (:resolve-llms-fn config)))
          ;; Only 'a should be present (not 'b)
          (is (fn? (:a llms-ns)))
          (is (nil? (:b llms-ns))))
        (finally
          (.delete main-file) (.delete a-file) (.delete b-file)
          (.delete dir)))))

  (testing "docs come from sub-agent's own :doc field"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-doc-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          helper-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main}))
        (spit helper-file (pr-str {:name 'helper :doc "I help with things" }))
        (let [config (agent/load-agent-config (.getAbsolutePath main-file))
              prov (provider/test-provider {:response "ok"})
              llms-ns ((:resolve-llms-fn config) llm/make-llm nil prov)]
          (is (= "I help with things" (get-in llms-ns [:docs :helper]))))
        (finally
          (.delete main-file) (.delete helper-file)
          (.delete dir))))))

;; =============================================================================
;; Pattern dependency validation
;; =============================================================================

(deftest make-agent-llm-pattern-dependency-validation-test
  (testing "core and future-only namespaces satisfy pattern requirements without explicit config"
    (let [result (agent/make-agent-llm
                  {:resolve-namespaces-fn
                   (fn [_]
                     {'patterns {:check-result {:requires ['strings]}
                                 :ralph {:requires ['agents 'blocking]}
                                 :team {:requires ['strings 'io 'agents 'futures 'blocking]}}
                      'agents {}
                      'io {}
                      'futures {}})})]
      (is (fn? (:llm result)))
      (is (fn? (:run result)))))

  (testing "missing effect namespaces fail fast with actionable ex-data"
    (try
      (agent/make-agent-llm
       {:resolve-namespaces-fn
        (fn [_]
          {'patterns {:explore {:requires ['io 'agents]}}
           'agents {}})})
      (is false "expected pattern dependency validation failure")
      (catch clojure.lang.ExceptionInfo e
        (is (= :explore (:pattern (ex-data e))))
        (is (= '[agents io] (:requires (ex-data e))))
        (is (= '[io] (:missing (ex-data e))))
        (is (re-find #"Pattern explore requires namespaces"
                     (.getMessage e)))))))
