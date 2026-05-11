(ns spell.agent-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [spell.agent :as agent]
            [spell.api :as api]
            [spell.parse :as parse]
            [spell.react :as react]
            [spell.runtime :as runtime]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.stdlib :as stdlib]
            [spell.test-helpers :as th]))

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
    (is (nil? (agent/resolve-llms nil llm/compile-agent agent/compile-agent-spec nil nil nil)))
    (is (nil? (agent/resolve-llms {} llm/compile-agent agent/compile-agent-spec nil nil nil)))))

(deftest resolve-llms-inline-spec-test
  (testing "inline spec resolves to a compiled agent returning evaluated value"
    (let [prov (provider/test-provider {:response "\"leaf response\")"})
          llms-map {'summarizer {:doc "Summarizes text"
                                 :system "Summarize concisely."}}
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      ;; Namespace structure
      (is (map? llms-ns))
      (is (contains? llms-ns :docs))
      (is (= "Summarizes text" (get-in llms-ns [:docs :summarizer])))
      (is (runtime/compiled-agent? (:summarizer llms-ns)))
      (is (= "leaf response" (th/run-agent-prefix (:summarizer llms-ns) "(do "))))))

(deftest resolve-llms-1-arity-test
  (testing "llms function accepts 1-arity (auto-generates handle)"
    (let [prov (provider/test-provider {:response "\"one-arg\")"})
          llms-map {'helper {:doc "Helper agent"}}
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)
          helper-fn (:helper llms-ns)]
      (is (runtime/compiled-agent? helper-fn))
      ;; 1-arity call should work (auto-generated handle)
      (is (= "one-arg" (th/run-agent-prefix helper-fn "(do "))))))

(deftest resolve-llms-inline-eval-test
  (testing "inline eval spec resolves to a compiled agent returning evaluated result"
    (let [prov (provider/test-provider {:response "42)"})
          llms-map {'coder {:doc "Writes Spell code"}}
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (runtime/compiled-agent? (:coder llms-ns)))
      (is (= 42 (th/run-agent-prefix (:coder llms-ns) "(do "))))))

(deftest resolve-llms-default-eval-true-test
  (testing "eval defaults to true when omitted"
    (let [prov (provider/test-provider {:response "42)"})
          llms-map {'worker {:doc "Default eval worker"}}
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (= 42 (th/run-agent-prefix (:worker llms-ns) "(do "))))))

(deftest resolve-llms-format-wrapping-test
  (testing "format spec wraps with validation"
    (let [prov (provider/test-provider {:response "{:category :animal :confidence 0.95})"})
          llms-map {'classifier {:doc "Classifies text"
                                 :format {:required [:category :confidence]}}}
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (let [result (th/run-agent-prefix (:classifier llms-ns) "(do ")]
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
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec "claude-sonnet-4-5-20250929" prov nil)]
      (is (runtime/compiled-agent? (:helper llms-ns)))
      (is (= "inherited" (th/run-agent-prefix (:helper llms-ns) "(do "))))))

(deftest resolve-llms-docs-populated-test
  (testing ":docs populated from :doc fields"
    (let [prov (provider/test-provider {:response "ok"})
          llms-map {'alpha {:doc "Alpha agent"}
                    'beta {:doc "Beta agent"}
                    'gamma {}}
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
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
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (= "result" (th/run-agent-prefix (:a llms-ns) "(do ")))
      (is (= "result" (th/run-agent-prefix (:b llms-ns) "(do ")))
      ;; Both got called
      (is (= 2 (count @call-log))))))

(deftest resolve-llms-multiple-specs-test
  (testing "multiple specs in one llms map"
    (let [prov (provider/test-provider {:response "\"response\")"})
          llms-map {'leaf1 {:doc "Leaf 1" :system "System 1"}
                    'leaf2 {:doc "Leaf 2" :system "System 2"}}
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      (is (runtime/compiled-agent? (:leaf1 llms-ns)))
      (is (runtime/compiled-agent? (:leaf2 llms-ns)))
      (is (= "Leaf 1" (get-in llms-ns [:docs :leaf1])))
      (is (= "Leaf 2" (get-in llms-ns [:docs :leaf2])))
      (is (= "response" (th/run-agent-prefix (:leaf1 llms-ns) "(do ")))
      (is (= "response" (th/run-agent-prefix (:leaf2 llms-ns) "(do "))))))

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
          llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov nil)]
      ;; describe returns docs map (no :guide)
      (is (= {:researcher "Researches topics"
              :writer "Writes content"}
             (stdlib/describe llms-ns)))
      ;; describe with key returns specific doc
      (is (= "Researches topics" (stdlib/describe llms-ns :researcher))))))

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
;; react agent wiring
;; =============================================================================

(def ^:private react-doc-keys
  #{:short-docs :docs :detail})

(defn- react-defn-keys-from-spl
  []
  (->> (parse/read-all (slurp "config/spl-lib/react.spl"))
       (keep (fn [form]
               (when (and (seq? form)
                          (= 'defn (first form))
                          (symbol? (second form)))
                 (keyword (name (second form))))))
       set))

(deftest react-loader-sync-test
  (testing "react namespace exports every top-level defn in react.spl"
    (let [expected (react-defn-keys-from-spl)
          actual (->> (keys react/react)
                      (remove react-doc-keys)
                      set)]
      (is (= expected actual))
      (doseq [k expected]
        (is (= true (get-in react/react [k :spell/fn])))
        (is (vector? (get-in react/react [k :params])))
        (is (seq (get-in react/react [k :body])))))))

(deftest react-agent-namespace-test
  (testing "react agent exposes describe docs for react/run"
    (let [prov (provider/test-provider {:response "unused"})
          result (api/run {:init "(eval (do '(describe-fn react :run)))"
                           :lm-profile prov
                           :agent "config/agents/react.agent.edn"})]
      (is (string? (:result result)))
      (is (str/includes? (:result result) "react/run"))))

  (testing "default cli agent remains unchanged"
    (let [spec (agent/load-agent-spec "config/agents/cli.agent.edn")]
      (is (not (contains? (:namespaces spec) 'react)))
      (is (= 'stdlib/io-exec (get-in (agent/load-agent-spec "config/agents/react.agent.edn")
                                     [:namespaces 'io]))))))

(deftest react-hidden-loop-init-test
  (testing "react/run drives a plain-text leaf loop from :init"
    (let [prompts (atom [])
          calls (atom 0)
          prov (provider/test-provider
                {:response-fn
                 (fn [prompt]
                   (swap! prompts conj prompt)
                   (case (swap! calls inc)
                     1 "Thought: Inspect the workspace.\nAction: Command[printf hello]"
                     2 "Thought: The command succeeded.\nAction: Finish[done]"
                     (throw (ex-info "Unexpected react leaf call" {:prompt prompt}))))})
          result (api/run {:init "(eval (do (def prompt \"Say hello by running a shell command, then finish.\") '(react/run prompt)))"
                           :lm-profile prov
                           :agent "config/agents/react.agent.edn"})]
      (is (= "done" (:result result)))
      (is (= 2 @calls))
      (is (= 2 (count @prompts)))
      (is (str/includes? (first @prompts) "Action: Command["))
      (is (str/includes? (first @prompts) "Action: Finish["))
      (is (some #(str/includes? % "Observation:") @prompts))
      (is (some #(str/includes? % "hello") @prompts))
      (doseq [prompt @prompts]
        (is (not (str/includes? prompt "!call-now")))
        (is (not (str/includes? prompt "!extend")))
        (is (not (str/includes? prompt "Spell")))
        (is (not (str/includes? prompt "react/run"))))))

  (testing "react/run returns a plain failure string on step exhaustion"
    (let [calls (atom 0)
          prov (provider/test-provider
                {:response-fn
                 (fn [_]
                   (swap! calls inc)
                   "Thought: Still working.\nAction: Command[printf hello]")})
          result (api/run {:init "(eval (do '(react/run {:task \"Say hello\" :max-steps 1})))"
                           :lm-profile prov
                           :agent "config/agents/react.agent.edn"})]
      (is (= "React loop reached max steps without a final answer." (:result result)))
      (is (= 1 @calls))))

  (testing "react/run rejects malformed multi-action replies before shell execution"
    (let [calls (atom 0)
          prompts (atom [])
          tmp-file (java.io.File/createTempFile "react-malformed" ".txt")
          tmp-path (.getAbsolutePath tmp-file)
          _ (.delete tmp-file)
          prov (provider/test-provider
                {:response-fn
                 (fn [prompt]
                   (swap! prompts conj prompt)
                   (case (swap! calls inc)
                     1 (str "Thought: Try the command.\n"
                            "Action: Command[printf hacked > " tmp-path "\n"
                            "Action: Finish[done]]")
                     2 "Thought: Retry cleanly.\nAction: Finish[safe]"
                     (throw (ex-info "Unexpected react leaf call" {:prompt prompt}))))})]
      (try
        (let [result (api/run {:init "(eval (do '(react/run \"Create a temp file, then finish.\")))"
                               :lm-profile prov
                               :agent "config/agents/react.agent.edn"})]
          (is (= "safe" (:result result)))
          (is (= 2 @calls))
          (is (= 2 (count @prompts)))
          (is (not (.exists (java.io.File. tmp-path))))
          (is (str/includes? (second @prompts) "Invalid response format.")))
        (finally
          (.delete (java.io.File. tmp-path)))))))

;; =============================================================================
;; load-agent-spec with :llms
;; =============================================================================

(deftest load-agent-spec-llms-test
  (testing "load-agent-spec preserves plain :llms data when present"
    ;; Create a temp agent file with :llms
    (let [tmp-file (java.io.File/createTempFile "test-agent" ".agent.edn")]
      (try
        (spit tmp-file (pr-str {:name 'test-agent
                                :llms {'helper {:doc "Helper agent"}}}))
        (let [spec (agent/load-agent-spec (.getAbsolutePath tmp-file))]
          (is (= {'helper {:doc "Helper agent"}} (:llms spec)))
          (is (string? (:base-dir spec))))
        (finally
          (.delete tmp-file)))))

  (testing "load-agent-spec preserves :llms [] opt-out"
    (let [tmp-file (java.io.File/createTempFile "test-agent" ".agent.edn")]
      (try
        (spit tmp-file (pr-str {:name 'bare-agent :llms []}))
        (let [spec (agent/load-agent-spec (.getAbsolutePath tmp-file))]
          (is (= [] (:llms spec))))
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
              llms-ns (agent/resolve-llms llms-map llm/compile-agent agent/compile-agent-spec nil prov dir)]
          (is (runtime/compiled-agent? (:child llms-ns)))
          (is (string? (get-in llms-ns [:docs :child])))
          (is (= "file-result" (th/run-agent-prefix (:child llms-ns) "(do "))))
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
    (let [spec (agent/load-agent-spec "config/agents/explore.agent.edn")
          namespaces (#'agent/resolve-namespaces (:namespaces spec) (:base-dir spec) agent/compile-agent-spec)
          io-ns (get namespaces 'io)]
      (is (= 'explore (:name spec)))
      (is (= [] (:llms spec)))
      (is (contains? io-ns :read-file))
      (is (contains? io-ns :grep))
      (is (contains? io-ns :glob))
      (is (contains? io-ns :git))
      (is (not (contains? io-ns :sh)))
      (is (not (contains? io-ns :write-file))))))

;; =============================================================================
;; effect-ns-names includes 'llms
;; =============================================================================

(deftest llms-is-effect-namespace-test
  (testing "llms/ namespace is treated as effect namespace in compile-agent"
    (let [llms-ns {:docs {:helper "test helper"}
                   :helper (th/compiled-agent-fn
                            (fn [_prompt _handle] :helped))}
          prov (provider/test-provider {:response "(agents/spawn llms/helper \"(do \")))"})
          test-agent (llm/compile-agent {:namespaces {'llms llms-ns
                                                      'agents runtime/agents-namespace}
                                         :provider prov})]
      ;; llms/ is an effect namespace, so it is available in the trailing expression.
      (is (keyword? (th/run-agent-prefix test-agent "(eval (do '"))))))

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

(deftest load-agent-spec-auto-discovery-test
  (testing "absent :llms auto-discovers siblings at compile time"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-auto-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          sibling-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main}))
        (spit sibling-file (pr-str {:name 'helper :doc "Helper agent" }))
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))
              compiled (agent/compile-agent-spec
                        (assoc spec :provider (provider/test-provider {:response "ok"})))]
          (is (runtime/compiled-agent? compiled)))
        (finally
          (.delete main-file) (.delete sibling-file)
          (.delete dir)))))

  (testing ":llms [] opt-out preserves explicit empty llms config"
    (let [dir (java.io.File. (System/getProperty "java.io.tmpdir") "spell-optout-test")
          _ (.mkdirs dir)
          main-file (java.io.File. dir "main.agent.edn")
          sibling-file (java.io.File. dir "helper.agent.edn")]
      (try
        (spit main-file (pr-str {:name 'main :llms []}))
        (spit sibling-file (pr-str {:name 'helper :doc "Helper"}))
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))]
          (is (= [] (:llms spec))))
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
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))
              prov (provider/test-provider {:response "ok"})
              llms (agent/resolve-llms (#'agent/normalize-llms-config (:llms spec) (:base-dir spec))
                                       llm/compile-agent agent/compile-agent-spec nil prov (:base-dir spec))]
          ;; Only 'a should be present (not 'b)
          (is (runtime/compiled-agent? (:a llms)))
          (is (nil? (:b llms))))
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
        (let [spec (agent/load-agent-spec (.getAbsolutePath main-file))
              prov (provider/test-provider {:response "ok"})
              llms-ns (agent/resolve-llms (#'agent/normalize-llms-config (get spec :llms :spell.agent/not-set)
                                                                        (:base-dir spec))
                                          llm/compile-agent agent/compile-agent-spec nil prov (:base-dir spec))]
          (is (= "I help with things" (get-in llms-ns [:docs :helper]))))
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
    (doseq [path ["config/agents/cli.agent.edn"
                  "config/agents/io-msg.agent.edn"
                  "config/agents/io-pf.agent.edn"
                  "config/agents/io-tc.agent.edn"]]
      (let [spec (assoc (agent/load-agent-spec path)
                        :provider (provider/test-provider {:response "ok"}))
            result (agent/compile-agent-spec spec)]
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
