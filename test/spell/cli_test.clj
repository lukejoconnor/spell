(ns spell.cli-test
  (:require [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.api :as api]
            [spell.cli :as cli]
            [spell.coordinator :as coordinator]
            [spell.parse :as parse]
            [spell.provider :as provider]
            [spell.runtime :as runtime]))

(defn- run-cli-entry-point [& args]
  (let [program (str
                 "(require '[spell.cli :as cli] '[spell.api :as api] '[spell.provider :as provider]) "
                 "(with-redefs [provider/openai-provider identity "
                 "api/run-internal (fn [config] "
                 "{:result (str \"CLI-RUN-CONFIG \" (pr-str "
                 "{:context-max-chars (:context-max-chars config) "
                 ":max-tokens (get-in config [:model-profile :max-tokens])}))})] "
                 "(apply cli/-main "
                 (pr-str (into ["--model" "openai-tc/gpt-4.1" "Return 42"] args))
                 "))")]
    (shell/sh (str (System/getProperty "java.home") "/bin/java")
              "-cp" (System/getProperty "java.class.path")
              "clojure.main" "-e" program)))

(deftest ^:slow context-max-chars-public-entry-point-test
  (testing "the default, minimum, and raised cap reach the actual runner config"
    (doseq [[args expected] [[[] {:context-max-chars 10000 :max-tokens nil}]
                             [["--context-max-chars" "128"]
                              {:context-max-chars 128 :max-tokens nil}]
                             [["--context-max-chars" "50000" "--max-tokens" "321"]
                              {:context-max-chars 50000 :max-tokens 321}]]]
      (let [{:keys [exit out err]} (apply run-cli-entry-point args)
            config-text (second (re-find #"CLI-RUN-CONFIG (\{[^\n]*\})" out))]
        (is (zero? exit) (str args "\n" out "\n" err))
        (is (= expected (some-> config-text edn/read-string)) (str args "\n" out)))))
  (testing "invalid caps are rejected before the runner is called"
    (doseq [cap ["invalid" "128.5" "127" "0" "-1"]]
      (let [{:keys [exit out err]} (run-cli-entry-point "--context-max-chars" cap)]
        (is (= 1 exit) (str cap "\n" out "\n" err))
        (is (str/includes? out "--context-max-chars") out)
        (is (not (str/includes? out "CLI-RUN-CONFIG")) out)))))

;; Deterministic execution of the portable pilot; no live/paid model calls.
;; The host observer gates the peer until actual wait admission, not a sleep.
(defn- nodes [prompt] (tree-seq coll? seq (parse/read-all (parse/balance-parens prompt))))
(defn- suffix [action] (str (pr-str (list 'quote action)) ")))"))
(defn- stored-ids [prompt]
  (mapv second (filter #(and (seq? %) (= 'stored (first %))) (nodes prompt))))
(defn- actual-string [prompt needle]
  (last (filter #(and (string? %) (str/includes? % needle)) (nodes prompt))))
(defn- live-await! [p label]
  (let [v (deref p 10000 ::timeout)]
    (when (= ::timeout v) (throw (ex-info label {}))) v))
(deftest ^:slow live-acceptance-retained-document-pages-across-real-peer-wait
  (let [calls (atom {}) prompts (atom {}) doc-id (atom nil)
        received (atom nil) dispatched (atom nil) wait-entered (promise)
        original-wait coordinator/wait!
        response
        (fn [prompt]
          (let [handle runtime/*current-handle*
                n (get (swap! calls update handle (fnil inc 0)) handle)]
            (swap! prompts assoc [handle n] prompt)
            (suffix
              (case handle
                :main
                (case n
                  1 (list '!print (list 'subs (list 'stored (first (stored-ids prompt))) 0 900))
                  2 '(!call-now setup (globals/set :live-acceptance {:fetches 0 :peer-replies 0 :wait-entries 0 :release false}))
                  3 '(!call-now document
                       (do (globals/update :live-acceptance (fn [s] (update s :fetches inc)))
                           (apply str (map marked-page (range 24)))))
                  4 (let [binding-form (first (filter #(and (seq? %) (= 'def (first %)) (= 'document (second %))) (nodes prompt)))
                          id (second (first (filter #(and (seq? %) (= 'stored (first %))) (tree-seq coll? seq binding-form))))]
                      (reset! doc-id id)
                      '(!print (subs document 0 900)))
                  5 '(!call-now peer-edge (agents/spawn-ask peer-task :live-peer))
                  6 (do (reset! dispatched
                                 (nth (first (filter #(and (seq? %) (= 'def (first %)) (= 'peer-edge (second %))) (nodes prompt))) 2))
                        '(!call-now before-wait (agents/status)))
                  7 '(do (globals/update :live-acceptance (fn [s] (-> s (assoc :release true) (update :wait-entries inc))))
                         (agents/!wait))
                  8 (do (reset! received
                                 (first (filter #(and (map? %) (= :live-peer (:from %)) (:edge-id %)) (nodes prompt))))
                        '(!print (subs document 900 1800)))
                  9 '(!call-now final-state (globals/get :live-acceptance))
                  10 '(do {:status :complete
                           :artifact {:offsets [[0 900] [900 1800]]
                                      :markers [(subs document 0 7) (subs document 900 907)]}
                           :length (count document)
                           :page0-ok (= (marked-page 0) (subs document 0 900))
                           :page1-ok (= (marked-page 1) (subs document 900 1800))
                           :state final-state})
                  (throw (ex-info "Unexpected parent call" {:n n})))
                :live-peer
                (case n
                  1 '(!describe globals agents)
                  2 '(!call-now gate (globals/wait-until (fn [s] (true? (get-in s [:live-acceptance :release])))))
                  3 (do (live-await! wait-entered "Parent did not enter a non-idle wait")
                        '(!call-now parent (agents/parent-handle)))
                  4 '(!call-now parent-status (agents/status parent)
                               fetch-state (globals/get :live-acceptance))
                  5 '(!call-now peer-count (globals/update :live-acceptance (fn [s] (update s :peer-replies inc))))
                  6 '(do {:marker :live-peer-return :parent-status parent-status :fetches (:fetches fetch-state)})
                  (throw (ex-info "Unexpected peer call" {:n n})))
                (throw (ex-info "Unexpected handle" {:handle handle}))))))
        result
        (with-redefs [coordinator/wait!
                      (fn [handle]
                        (let [outcome (original-wait handle)]
                          (when (and (= :main handle) (= :waiting (:status outcome)))
                            (deliver wait-entered outcome))
                          outcome))]
          (api/run {:init (slurp "LIVE-ACCEPTANCE.spl")
                    :agent-profile "config/agent-profiles/cli.agent.edn"
                    :model-profile (provider/test-provider {:prefill? false :response-fn response})}))]
    (is (nil? (:error result)) (pr-str (dissoc result :usage-tracker)))
    (is (= {:main 10 :live-peer 6} @calls))
    (is (string? @doc-id))
    (is (string? (actual-string (get @prompts [:main 2] "") "Expect early verification failures.")))
    (is (= 900 (count (actual-string (get @prompts [:main 5] "") "PAGE-0|"))))
    (is (= 900 (count (actual-string (get @prompts [:main 9] "") "PAGE-1|"))))
    (is (some #{@doc-id} (stored-ids (get @prompts [:main 8] ""))) "Original document ID survives receipt")
    (is (some #{@doc-id} (stored-ids (get @prompts [:main 10] ""))) "Same document ID survives second page")
    (is (some? @dispatched))
    (is (= @dispatched (:edge-id @received)))
    (is (= :live-peer-return (get-in @received [:body :marker])))
    (is (= :asleep (get-in @received [:body :parent-status :status])))
    (is (= 1 (get-in @received [:body :fetches])))
    (is (realized? wait-entered))
    (is (str/includes? (get @prompts [:main 8] "") "wait resumed"))
    (is (= {:status :complete
            :artifact {:offsets [[0 900] [900 1800]] :markers ["PAGE-0|" "PAGE-1|"]}
            :length 21600 :page0-ok true :page1-ok true
            :state {:fetches 1 :peer-replies 1 :wait-entries 1 :release true}}
           (:result result)))))

(deftest print-usage-context-stats-test
  (testing "verbose usage output includes mean/max total context"
    (let [usage-atom (atom {:by-model {"model-a" {:input_tokens 300
                                                  :output_tokens 120
                                                  :cache_creation_input_tokens 10
                                                  :cache_read_input_tokens 20
                                                  :calls 2
                                                  :mean_total_tokens 225
                                                  :max_total_tokens 260}
                                       "model-b" {:input_tokens 50
                                                  :output_tokens 10
                                                  :calls 1
                                                  :mean_total_tokens 60
                                                  :max_total_tokens 60}}
                            :total {:input_tokens 350
                                    :output_tokens 130
                                    :cache_creation_input_tokens 10
                                    :cache_read_input_tokens 20
                                    :calls 3
                                    :mean_total_tokens 170
                                    :max_total_tokens 260}})
          output (with-out-str ((var cli/print-usage) usage-atom))]
      (is (str/includes? output "[context: mean 225 / max 260]"))
      (is (str/includes? output "[context: mean 170 / max 260]")))))

(deftest validate-args-accepts-new-openai-reasoning-levels
  (testing "xhigh reasoning effort is accepted"
    (let [result (cli/validate-args ["--reasoning-effort" "xhigh" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "xhigh" (get-in result [:options :reasoning-effort])))))

  (testing "none reasoning effort is accepted"
    (let [result (cli/validate-args ["--reasoning-effort" "none" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "none" (get-in result [:options :reasoning-effort]))))))

(deftest trace-dir-option-test
  (testing "--trace-dir enables tracing at the requested path"
    (let [result (cli/validate-args ["--trace-dir" "notebook/results/run-1/trace"
                                     "Return 42"])]
      (is (= "notebook/results/run-1/trace"
             (get-in result [:options :trace-dir])))))

  (testing "an explicit trace directory takes precedence over -T"
    (with-redefs [api/run-internal identity]
      (let [result (cli/run-input {:prompt "Return 42"}
                                  {:test true
                                   :trace true
                                   :trace-dir "durable/trace"}
                                  (atom {:by-model {}}))]
        (is (= "durable/trace" (:trace-dir result)))))))

(deftest agents-md-option-test
  (testing "--agents-md prepends cwd instructions to a natural-language prompt"
    (let [result (with-redefs-fn
                   {#'spell.cli/load-cwd-agents-md
                    (constantly {:path "/repo/AGENTS.md"
                                 :text "Keep changes scoped."
                                 :truncated? false})}
                   #(cli/validate-args ["--agents-md" "Implement the feature"]))]
      (is (str/includes? (:prompt result) "Project instructions from /repo/AGENTS.md"))
      (is (str/includes? (:prompt result) "<agents_md>\nKeep changes scoped.\n</agents_md>"))
      (is (str/ends-with? (:prompt result) "Task:\nImplement the feature"))))

  (testing "--agents-md is rejected for direct Spell programs"
    (let [result (cli/validate-args ["--agents-md" "--init" "(do 42)"])]
      (is (false? (:ok? result)))
      (is (str/includes? (:exit-message result) "requires a natural-language prompt"))))

  (testing "AGENTS.md truncation preserves valid UTF-8 within the 32 KiB cap"
    (let [tmp (java.io.File/createTempFile "spell-agents-md-" ".md")]
      (try
        (spit tmp (str (apply str (repeat 32767 "a")) "界tail"))
        (let [{:keys [text truncated?]} (#'spell.cli/read-agents-md-file tmp)]
          (is truncated?)
          (is (= 32767
                 (alength (.getBytes text java.nio.charset.StandardCharsets/UTF_8))))
          (is (not (str/includes? text "界"))))
        (finally
          (.delete tmp))))))

(deftest validate-args-supports-direct-init-programs
  (testing "--init passes a complete Spell program instead of a prompt"
    (let [result (cli/validate-args ["--init" "(do 42)"])]
      (is (= "(do 42)" (:init result)))
      (is (nil? (:prompt result)))))

  (testing "--init-file loads a complete Spell program"
    (let [tmp (java.io.File/createTempFile "spell-cli-init-" ".spl")]
      (try
        (spit tmp "(do (+ 20 22))")
        (let [result (cli/validate-args ["--init-file" (.getAbsolutePath tmp)])]
          (is (= "(do (+ 20 22))" (:init result)))
          (is (nil? (:prompt result))))
        (finally
          (.delete tmp)))))

  (testing "prompt/file modes remain distinct from init modes"
    (is (false? (:ok? (cli/validate-args ["--init" "(do 42)" "Return 42"]))))
    (is (false? (:ok? (cli/validate-args ["--init-file" "program.spl" "Return 42"]))))
    (is (false? (:ok? (cli/validate-args ["--init" "(do 42)" "--init-file" "program.spl"]))))
    (is (false? (:ok? (cli/validate-args ["--example" "hello-world" "--init" "(do 42)"]))))))

(deftest dogfood-flag-test
  (testing "--dogfood is parsed as an explicit run-level gate"
    (let [result (cli/validate-args ["--dogfood" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (true? (get-in result [:options :dogfood])))))

  (testing "dogfood adds feedback to the selected profile without changing the profile path"
    (let [seen-opts (atom nil)
          profile "config/agent-profiles/base-msg.agent.edn"]
      (with-redefs [api/run-internal (fn [opts]
                                      (reset! seen-opts opts)
                                      {:result :ok})]
        (is (= :ok (:result (cli/run-input {:init "(do 42)"}
                                            {:test true
                                             :dogfood true
                                             :agent-profile profile}
                                            (atom {:by-model {}})))))
        (is (= profile (:agent-profile @seen-opts)))
        (is (= {'feedback 'stdlib/feedback}
               (:agent-namespace-overrides @seen-opts))))))

  (testing "ordinary runs do not receive the feedback override"
    (let [seen-opts (atom nil)]
      (with-redefs [api/run-internal (fn [opts]
                                      (reset! seen-opts opts)
                                      {:result :ok})]
        (cli/run-input {:init "(do 42)"} {:test true} (atom {:by-model {}}))
        (is (not (contains? @seen-opts :agent-namespace-overrides)))))))

(deftest run-input-evaluates-direct-init-without-llm-call
  (let [result (cli/run-input {:init "(do (+ 20 22))"} {:test true} (atom {:by-model {}}))]
    (is (= 42 (:result result))))

  (testing "atom and literal init programs are not treated as prompts"
    (doseq [[program expected] [["42" 42]
                                ["\"hello\"" "hello"]]]
      (let [result (cli/run-input {:init program} {:test true} (atom {:by-model {}}))]
        (is (= expected (:result result)))))))

(deftest make-provider-resolves-shared-model-aliases-test
  (testing "the CLI default uses GPT-6 Astra through Codex tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/codex-tc-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :codex-tc :opts opts})]
        (is (= :codex-tc
               (:provider ((var cli/make-provider) {}))))
        (is (= "gpt-6-astra" (:model @captured))))))

  (testing "bare gpt alias routes to OpenAI tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/openai-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :openai-tc :opts opts})]
        (is (= :openai-tc
               (:provider ((var cli/make-provider) {:model "gpt"}))))
        (is (= "gpt-6-astra" (:model @captured)))
        (is (:use-responses-api @captured))
        (is (:force-tool-call @captured)))))

  (testing "bare opus alias routes to Anthropic tool-call transport"
    (let [captured (atom nil)]
      (with-redefs [provider/anthropic-tc-provider
                    (fn [opts]
                      (reset! captured opts)
                      {:provider :anthropic-tc :opts opts})]
        (is (= :anthropic-tc
               (:provider ((var cli/make-provider) {:model "opus"}))))
        (is (= "claude-opus-4-8" (:model @captured))))))

  (testing "Fable aliases route to the expected Anthropic tool-call models"
    (doseq [[alias expected] [["fable" "claude-fable-5-1"]
                              ["fable51" "claude-fable-5-1"]
                              ["fable5" "claude-fable-5"]]]
      (let [captured (atom nil)]
        (with-redefs [provider/anthropic-tc-provider
                      (fn [opts]
                        (reset! captured opts)
                        {:provider :anthropic-tc :opts opts})]
          (is (= :anthropic-tc
                 (:provider ((var cli/make-provider) {:model alias}))))
          (is (= expected (:model @captured)))))))

  (testing "bare open-weight aliases route to Fireworks tool-call transport"
    (doseq [[alias expected] [["glm" "glm-5p2"]
                             ["kimi" "kimi-k2p7-code"]
                             ["qwen" "qwen3p7-plus"]
                             ["glm51" "glm-5p1"]
                             ["kimi26" "kimi-k2p6"]
                             ["kimi3" "kimi-k3"]
                             ["kimik3" "kimi-k3"]
                             ["qwen36p" "qwen3p6-plus"]]]
      (let [captured (atom nil)]
        (with-redefs [provider/fireworks-tc-provider
                      (fn [opts]
                        (reset! captured opts)
                        {:provider :fireworks-tc :opts opts})]
          (is (= :fireworks-tc
                 (:provider ((var cli/make-provider) {:model alias}))))
          (is (= expected (:model @captured))))))))

(deftest run-input-default-reasoning-effort-test
  (let [seen (atom nil)
        run! (fn [opts]
               (with-redefs [provider/codex-tc-provider
                             (fn [_] (provider/test-provider {:response "unused"}))
                             provider/openai-provider
                             (fn [_] (provider/test-provider {:response "unused"}))
                             api/run-internal
                             (fn [run-opts]
                               (reset! seen run-opts)
                               {:result 42})]
                 (cli/run-input {:prompt "Return 42"} opts (atom {:by-model {}}))))]
    (testing "the default model uses medium reasoning"
      (run! {})
      (is (= "medium" (:reasoning-effort @seen))))

    (testing "an explicit reasoning effort overrides the default"
      (run! {:reasoning-effort "high"})
      (is (= "high" (:reasoning-effort @seen))))

    (testing "explicit Astra specs and aliases retain medium reasoning"
      (doseq [model ["gpt" "astra" "gpt6" "gpt6astra"
                     "openai-tc:gpt-6-astra" "codex-tc:gpt-6-astra"]]
        (run! {:model model})
        (is (= "medium" (:reasoning-effort @seen)) model)))

    (testing "an explicit older model keeps its provider-specific reasoning default"
      (run! {:model "gpt55"})
      (is (nil? (:reasoning-effort @seen))))))

(deftest help-text-uses-public-provider-specs-and-curated-examples
  (let [{:keys [exit-message ok?]} (cli/validate-args ["--help"])]
    (is ok?)
    (is (str/includes? exit-message "default: codex-tc:gpt-6-astra"))
    (is (str/includes? exit-message "default: medium for the default model"))
    (is (str/includes? exit-message "token budget for extended thinking; adaptive for supported models"))
    (is (str/includes? exit-message "Reasoning effort for OpenAI and adaptive Anthropic models"))
    (is (str/includes? exit-message "codex-tc:<model>"))
    (is (str/includes? exit-message "openai-tc:gpt-6-astra"))
    (is (str/includes? exit-message "anthropic-tc:claude-opus-4-8"))
    (is (str/includes? exit-message "spell -m fable 'Use Claude Fable 5.1'"))
    (is (str/includes? exit-message "fireworks-tc:kimi-k2p7-code"))
    (is (str/includes? exit-message "--dogfood"))
    (is (str/includes? exit-message "--agents-md"))
    (is (str/includes? exit-message "spell -t --init '(do (+ 20 22))'"))
    (is (str/includes? exit-message "spell --init-file scratch/my-program.spl"))
    (is (str/includes? exit-message "--trace-dir DIR"))
    (doseq [example ["hello-world" "coin-flip" "twenty-questions"
                     "telephone" "auction" "chat"]]
      (is (str/includes? exit-message example)))
    (doseq [removed ["famous-greeting" "fix-bug" "comm-ask"
                     "globals-basic" "negotiate" "test-compact"]]
      (is (not (str/includes? exit-message removed))))))

(deftest reasoning-effort-accepts-gpt-5-6-max-test
  (testing "CLI accepts GPT-5.6's max reasoning effort"
    (let [result (cli/validate-args ["-m" "gpt56sol" "-R" "max" "Return 42"])]
      (is (= "Return 42" (:prompt result)))
      (is (= "max" (get-in result [:options :reasoning-effort]))))))
