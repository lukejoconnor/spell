(ns spell.installable-modules-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [spell.core :as spell]
            [spell.api :as api]
            [spell.provider :as provider]
            [spell.runtime :as runtime]
            [spell.globals :as globals]
            [spell.test-helpers :as th]))

(use-fixtures :each th/with-test-run)

(defn- program [form]
  (pr-str (list 'eval (list 'quote form))))

(defn- run-form
  ([form] (run-form form spell/all-namespaces))
  ([form namespaces]
   (th/run-agent-init
     (th/make-test-agent {:response-fn (fn [_] (throw (ex-info "Unexpected LLM call" {})))}
                         :namespaces namespaces :recover false)
     (program form))))

(defn- core-receipt [r]
  (select-keys r [:module :installed? :fns]))

(def sample
  '{:doc "A small editable module"
    :functions {:run {:doc "Add caller offset" :requires []
                      :source (fn [x] (+ offset x))}}})

(deftest five-verbs-and-no-legacy-shims
  (is (= #{:install :catalog :source :update :call}
         (set (remove #{:short-docs :docs :detail}
                      (keys (get spell/all-namespaces 'patterns)))))))

(deftest catalog-is-discovery-not-installation
  (let [[catalog missing-source unknown]
        (run-form '(vector (patterns/catalog)
                           (patterns/source :check-result)
                           (patterns/catalog :unknown-module)))
        by-name (into {} (map (juxt :module identity) catalog))]
    (is (= #{:check-result :ralph :team :fix-loop :relay :mailing-list}
           (set (keys by-name))))
    (is (every? false? (map :installed? catalog)))
    (is (nil? missing-source))
    (is (nil? unknown))
    (is (= #{:run} (set (keys (:functions (by-name :check-result))))))
    (is (= #{:init :call :change :digest :deliver}
           (set (keys (:functions (by-name :mailing-list))))))
    (is (not (str/includes? (pr-str catalog) ":source")))))

(deftest install-preserves-edits-and-independent-state
  (let [[first-receipt second-receipt source state]
        (run-form
          '(do
             (def first-receipt (patterns/install :check-result))
             (patterns/update :check-result assoc :doc "edited documentation")
             (globals/set :module-user-state {:counter 17})
             (vector first-receipt (patterns/install :check-result)
                     (patterns/source :check-result)
                     (globals/get :module-user-state))))]
    (is (= {:module :check-result :installed? true :fns [:run]} (core-receipt first-receipt)))
    (is (= {:module :check-result :installed? false :fns [:run]} (core-receipt second-receipt)))
    (is (= "edited documentation" (:doc source)))
    (is (= {:counter 17} state))))

(deftest source-is-selective-complete-executable-data
  (let [[receipt source entry result]
        (run-form
          '(do
             (def receipt (do (patterns/install :custom '{:doc "bootstrap" :functions {}}) (patterns/update :custom (fn [_] '{:doc "A small editable module" :functions {:run {:doc "Add caller offset" :requires [] :source (fn [x] (+ offset x))}}}))))
             (def offset 20)
             (vector receipt (patterns/source :custom)
                     (patterns/source :custom :run)
                     (patterns/call :custom :run 22))))]
    (is (= {:module :custom :fns [:run]} (core-receipt receipt)))
    (is (= sample source))
    (is (= (get-in sample [:functions :run]) entry))
    (is (= '(fn [x] (+ offset x)) (:source entry)))
    (is (= 42 result))))

(deftest add-remove-and-absent-source
  (let [[added value removed entry absent]
        (run-form
          '(do
             (def added
               (do (patterns/install :scratch '{:doc "bootstrap" :functions {}}) (patterns/update :scratch
                 (fn [_] '{:doc "scratch"
                           :functions {:value {:doc "constant" :requires []
                                               :source (fn [] 11)}}}))))
             (def value (patterns/call :scratch :value))
             (def removed (patterns/update :scratch assoc :functions {}))
             (vector added value removed (patterns/source :scratch :value)
                     (patterns/source :not-installed))))]
    (is (= {:module :scratch :fns [:value]} (core-receipt added)))
    (is (= 11 value))
    (is (= {:module :scratch :fns []} (core-receipt removed)))
    (is (nil? entry))
    (is (nil? absent))))

(deftest caller-dynamic-scope-arity-and-recur
  (is (= [42 55]
         (run-form
           '(do
              (do (patterns/install :dynamic '{:doc "bootstrap" :functions {}}) (patterns/update :dynamic
                (fn [_] '{:doc "dynamic"
                          :functions
                          {:add {:doc "caller binding" :requires []
                                 :source (fn [x] (+ x offset))}
                           :sum {:doc "tail recursion" :requires []
                                 :source (fn [n acc]
                                           (if (zero? n) acc
                                             (recur (dec n) (+ n acc))))}}})))
              (let [offset 40]
                [(patterns/call :dynamic :add 2)
                 (patterns/call :dynamic :sum 10 0)]))))))

(deftest no-lazy-argument-traversal
  (let [forced (atom 0)
        opaque (map (fn [x] (swap! forced inc) x) (range 10000))
        result (run-form
                 '(do
                    (do (patterns/install :opaque '{:doc "bootstrap" :functions {}}) (patterns/update :opaque
                      (fn [_] '{:doc "opaque"
                                :functions {:ignore {:doc "Never inspect argument"
                                                     :requires []
                                                     :source (fn [x] :untouched)}}})))
                    (patterns/call :opaque :ignore (audit/value)))
                 (assoc spell/all-namespaces 'audit {:value (fn [] opaque)}))]
    (is (= :untouched result))
    (is (zero? @forced) "Calling does not walk, print, count or realize argument trees")))

(deftest nested-call-observes-latest-with-in-flight-snapshot
  (is (= [:old-frame :new]
         (run-form
           '(do
              (do (patterns/install :live '{:doc "bootstrap" :functions {}}) (patterns/update :live
                (fn [_] '{:doc "live"
                          :functions
                          {:outer {:doc "Running frame stays old" :requires [patterns]
                                   :source
                                   (fn []
                                     (patterns/update :live assoc-in
                                       [:functions :outer :source] '(fn [] :replacement))
                                     (patterns/update :live assoc-in
                                       [:functions :inner :source] '(fn [] :new))
                                     [:old-frame (patterns/call :live :inner)])}
                           :inner {:doc "Nested lookup" :requires []
                                   :source (fn [] :old)}}})))
              (patterns/call :live :outer))))))

(deftest concurrent-install-one-winner-and-preserved-state
  (run-form '(do (globals/set :mailing-list {:sentinel :board})
                 (globals/set :user-state {:counter 17})))
  (let [namespaces spell/all-namespaces
        a (th/make-test-agent "nil" :namespaces namespaces :recover false)
        b (th/make-test-agent "nil" :namespaces namespaces :recover false)
        gate (promise)
        call (fn [agent handle]
               @gate (agent (program '(patterns/install :check-result)) handle))
        fa (future (call a :installer-a))
        fb (future (call b :installer-b))]
    (deliver gate true)
    (let [ra (deref fa 10000 ::timeout) rb (deref fb 10000 ::timeout)]
      (is (not= ::timeout ra))
      (is (not= ::timeout rb))
      (is (= #{{:module :check-result :installed? true :fns [:run]}
               {:module :check-result :installed? false :fns [:run]}}
             (set (map core-receipt [ra rb]))))
      (is (= [{:sentinel :board} {:counter 17}]
             (run-form '[(globals/get :mailing-list) (globals/get :user-state)]))))))

(deftest separate-runs-do-not-share-definitions
  (let [first-run (th/with-test-run
                    #(run-form '(do (patterns/install :check-result)
                                    (patterns/source :check-result))))
        second-run (th/with-test-run
                     #(run-form '(patterns/source :check-result)))]
    (is (map? first-run))
    (is (nil? second-run))))

(deftest custom-install-is-data-only-and-repeat-preserves-edits
  (let [executions (atom 0)
        definition '{:doc "custom install"
                     :functions {:run {:doc "explicit execution" :requires [audit]
                                       :source (fn [] (audit/tick))}}}
        namespaces (assoc spell/all-namespaces 'audit {:tick #(swap! executions inc)})
        install (run-form (list 'patterns/install :custom (list 'quote definition)) namespaces)]
    (is (= {:module :custom :installed? true :fns [:run]} (core-receipt install)))
    (is (zero? @executions) "Installing source must not execute it")
    (is (= definition (run-form '(patterns/source :custom) namespaces)))
    (is (= 1 (run-form '(patterns/call :custom :run) namespaces)))
    (run-form '(patterns/update :custom assoc :doc "customized") namespaces)
    (is (= {:module :custom :installed? false :fns [:run]}
           (core-receipt (run-form (list 'patterns/install :custom (list 'quote definition)) namespaces))))
    (is (= "customized" (:doc (run-form '(patterns/source :custom) namespaces))))
    (is (= 1 @executions))))

(deftest actual-agents-cross-customize-with-opaque-rendered-context
  (let [prefixes (atom [])
        dispatches (atom [])
        edited (promise)
        installed (run-form
                    '(patterns/install :shared
                       '{:doc "shared module"
                         :functions
                         {:run {:doc "public entry" :requires []
                                :source (fn [] (let [hidden "ORIGINAL_BODY_SENTINEL"] :original))}
                          :private {:doc "private helper" :requires []
                                    :source (fn [] "UNRELATED_BODY_SENTINEL")}}}))
        editor (th/make-test-agent
                 {:response-fn (fn [_] (throw (ex-info "Editor must not call provider" {})))}
                 :namespaces (assoc spell/all-namespaces
                               'audit {:edited (fn [receipt] (deliver edited receipt) receipt)})
                 :recover false)
        caller (th/make-test-agent
                 {:response-fn
                  (fn [prefix]
                    (swap! prefixes conj prefix)
                    (case (count @prefixes)
                      1 (do
                          (swap! dispatches conj
                            (runtime/spawn editor
                              (program
                                '(audit/edited
                                   (patterns/update :shared {:owner :main} assoc-in
                                     [:functions :run :source]
                                     '(fn [] (let [hidden "EDITED_BODY_SENTINEL"] :edited)))))
                              :module-editor))
                          (when (= ::timed-out (deref edited 5000 ::timed-out))
                            (throw (ex-info "Spawned editor did not report its update" {})))
                          (str (pr-str '(quote (!call-now after (patterns/call :shared :run)))) ")))"))
                      2 (str (pr-str '(quote [before after])) ")))")
                      (throw (ex-info "Unexpected extra model turn" {:count (count @prefixes)}))))}
                 :namespaces spell/all-namespaces :prefill? false :recover false)
        result (caller
                 "(quine completion (eval (do '(!call-now install-receipt (patterns/install :shared) before (patterns/call :shared :run)))))"
                 :module-caller)]
    (is (= {:module :shared :installed? true :fns [:private :run]} (core-receipt installed)))
    (is (= [:module-editor] @dispatches))
    (is (= {:module :shared :fns [:private :run]} (core-receipt (deref edited 0 ::missing))))
    (is (= [:original :edited] result)
        "A supported spawned editor changes the definition between caller turns")
    (is (= 2 (count @prefixes)))
    (is (str/includes? (first @prefixes) "install-receipt"))
    (is (str/includes? (first @prefixes) ":original"))
    (is (str/includes? (second @prefixes) ":edited"))
    (doseq [prefix @prefixes
            sentinel ["ORIGINAL_BODY_SENTINEL" "EDITED_BODY_SENTINEL"
                      "UNRELATED_BODY_SENTINEL"]]
      (is (not (str/includes? prefix sentinel))
          "Actual provider-facing context contains receipts/results, not module bodies"))))
(deftest call-cost-does-not-scale-with-unrelated-source
  (let [calls (atom 0)
        definition {:doc "large unrelated source"
                    :functions
                    {:run {:doc "small hot entry" :requires '[audit]
                           :source '(fn [x] (audit/tick x))}
                     :unused {:doc "unrelated" :requires []
                              :source (list 'fn [] (apply str (repeat 100000 "x")))}}}
        namespaces (assoc spell/all-namespaces 'audit {:tick (fn [x] (swap! calls inc) x)})]
    (run-form (list 'patterns/install :cost (list 'quote definition)) namespaces)
    (is (zero? @calls))
    (is (= (vec (range 40))
           (run-form
             '(loop [n 0 result []]
                (if (= n 40) result
                  (recur (inc n) (conj result (patterns/call :cost :run n)))))
             namespaces)))
    (is (= 40 @calls) "One requested function invocation per call; no helper work")
    (is (< (count (pr-str (run-form '(patterns/catalog :cost) namespaces))) 500)
        "Discovery remains bounded despite a 100KB unused source body")))



(deftest public-api-runs-isolate-module-definitions-and-state
  (let [run (fn [form]
              (api/run {:init (program form)
                        :model-profile
                        (provider/test-provider
                          {:response-fn (fn [_] (throw (ex-info "No provider call expected" {})))})
                        :agent-profile "config/agent-profiles/cli.agent.edn"}))
        first-run (run
                    '(do
                       (patterns/install :isolated
                         '{:doc "first run" :functions
                           {:run {:doc "first" :requires [] :source (fn [] :first)}}})
                       (globals/set :isolation-state :first)
                       [(patterns/call :isolated :run) (globals/get :isolation-state)]))
        second-run (run
                     '(let [before (patterns/source :isolated)
                            state (globals/get :isolation-state)
                            receipt (patterns/install :isolated
                                      '{:doc "second run" :functions
                                        {:run {:doc "second" :requires [] :source (fn [] :second)}}})]
                        [before state receipt (patterns/call :isolated :run)]))]
    (is (not (contains? first-run :error)) (pr-str (:error first-run)))
    (is (not (contains? second-run :error)) (pr-str (:error second-run)))
    (is (= [:first :first] (:result first-run)))
    (is (= [nil nil {:module :isolated :installed? true :fns [:run]} :second]
           (update (:result second-run) 2 core-receipt)))))

(deftest concurrent-edit-keeps-selected-frame-but-nested-call-is-latest
  (let [started (promise)
        release (promise)
        namespaces (assoc spell/all-namespaces 'audit
                     {:pause (fn []
                               (deliver started true)
                               (when (= ::timeout (deref release 10000 ::timeout))
                                 (throw (ex-info "In-flight test release timed out" {}))))})
        caller (th/make-test-agent "nil" :namespaces namespaces :recover false)]
    (run-form
      '(patterns/install :in-flight
         '{:doc "overlapping edit"
           :functions
           {:outer {:doc "selected frame" :requires [audit patterns]
                    :source (fn [] (audit/pause) [:old-frame (patterns/call :in-flight :inner)])}
            :inner {:doc "nested lookup" :requires [] :source (fn [] :old)}}})
      namespaces)
    (let [running (future (caller (program '(patterns/call :in-flight :outer)) :in-flight-caller))]
      (try
        (is (= true (deref started 10000 ::timeout)) "The old frame is running before update")
        (is (= {:module :in-flight :fns [:inner :outer]}
               (core-receipt (run-form
                 '(patterns/update :in-flight
                    (fn [definition]
                      (-> definition
                          (assoc-in [:functions :outer :source] '(fn [] :replacement))
                          (assoc-in [:functions :inner :source] '(fn [] :new)))))
                 namespaces))))
        (finally (deliver release true)))
      (is (= [:old-frame :new] (deref running 10000 ::timeout)))
      (is (= :replacement (run-form '(patterns/call :in-flight :outer) namespaces))))))

(deftest invalid-definitions-rollback-and-missing-functions
  (run-form (list 'patterns/install :valid (list 'quote sample)))
  (doseq [bad [nil [] {} (assoc sample :doc 17)
               (assoc sample :functions [])
               (assoc sample :functions {'run (get-in sample [:functions :run])})
               (assoc-in sample [:functions :run :doc] nil)
               (assoc-in sample [:functions :run :requires] '(globals))
               (assoc-in sample [:functions :run :requires] [:globals])
               (assoc-in sample [:functions :run :requires] '[globals/get])
               (assoc-in sample [:functions :run :source] '(do 1))
               (assoc-in sample [:functions :run :source] '(fn []))]]
    (testing (pr-str bad)
      (is (thrown? Exception
            (run-form (list 'patterns/install :invalid (list 'quote bad)))))
      (is (nil? (run-form '(patterns/source :invalid))))
      (is (thrown? Exception
            (run-form (list 'patterns/update :valid
                       (list 'fn ['_] (list 'quote bad))))))
      (is (= sample (run-form '(patterns/source :valid))))))
  (is (thrown? Exception
        (run-form '(patterns/update :valid (fn [_] (/ 1 0))))))
  (is (= sample (run-form '(patterns/source :valid))))
  (doseq [form ['(patterns/install "not-a-keyword")
                '(patterns/install :unknown-bundle)
                '(patterns/call :missing :run)
                '(patterns/call :valid :missing)]]
    (is (thrown? Exception (run-form form))))
  (is (= 42 (run-form '(let [offset 40] (patterns/call :valid :run 2))))))

(deftest requirements-never-grant-caller-capabilities
  (let [definition '{:doc "permissions"
                     :functions
                     {:declared {:doc "Requires globals" :requires [globals]
                                 :source (fn [] (globals/get :permission-value))}
                      :undeclared {:doc "Still uses caller permissions" :requires []
                                   :source (fn [] (globals/get :permission-value))}
                      :pure {:doc "Core namespace requirement" :requires [strings]
                             :source (fn [] (strings/upper-case "ok"))}}}
        restricted (dissoc spell/all-namespaces 'globals)]
    (run-form (list 'patterns/install :permissions (list 'quote definition)))
    (run-form '(globals/set :permission-value 42))
    (doseq [entry [:declared :undeclared]]
      (is (= 42 (run-form (list 'patterns/call :permissions entry))))
      (is (thrown? Exception
            (run-form (list 'patterns/call :permissions entry) restricted))))
    (is (= "OK" (run-form '(patterns/call :permissions :pure) restricted)))
    (is (= definition (run-form '(patterns/source :permissions) restricted)))))

(deftest arity-errors-and-selected-source-remain-executable
  (run-form
    '(patterns/install :arity
       '{:doc "arity"
         :functions
         {:fixed {:doc "Fixed arity" :requires [] :source (fn [x] (+ offset x))}
          :pair {:doc "Two arguments" :requires [] :source (fn [x y] (+ x y))}}}))
  (doseq [form ['(patterns/call :arity :fixed)
                '(patterns/call :arity :fixed 1 2)
                '(patterns/call :arity :pair 1)]]
    (is (thrown? Exception (run-form form))))
  (is (= [42 5 42]
         (run-form
           '(let [offset 40
                  selected (patterns/source :arity :fixed)
                  executable (eval (:source selected))]
              [(patterns/call :arity :fixed 2)
               (patterns/call :arity :pair 2 3)
               (executable 2)])))))

(deftest opaque-and-lazy-return-values-are-not-walked
  (let [forced (atom 0)
        opaque (Object.)
        lazy-value (map (fn [x] (swap! forced inc) x) (range 10000))
        namespaces (assoc spell/all-namespaces 'audit
                     {:opaque (fn [] opaque) :lazy (fn [] lazy-value)})]
    (run-form
      '(patterns/install :identity
         '{:doc "identity"
           :functions {:run {:doc "Pass value through" :requires [] :source (fn [x] x)}}})
      namespaces)
    (is (identical? opaque
          (run-form '(patterns/call :identity :run (audit/opaque)) namespaces)))
    (is (identical? lazy-value
          (run-form '(patterns/call :identity :run (audit/lazy)) namespaces)))
    (is (zero? @forced))))

(deftest concurrent-updates-return-their-own-commit-and-preserve-state
  (run-form
    '(do
       (globals/set :mailing-list {:sentinel :board-state})
       (globals/set :user-state {:count 17})
       (patterns/install :race
         '{:doc "race" :functions {:run {:doc "base" :requires [] :source (fn [] :base)}}})))
  (let [gate (promise)
        update-form (fn [key]
                      (list 'patterns/update :race {:owner :main}
                        '(fn [definition key]
                           (assoc-in definition [:functions key]
                             '{:doc "added" :requires [] :source (fn [] :added)}))
                        key))
        a (th/make-test-agent "nil" :namespaces spell/all-namespaces :recover false)
        b (th/make-test-agent "nil" :namespaces spell/all-namespaces :recover false)
        fa (future @gate (a (program (update-form :a)) :update-a))
        fb (future @gate (b (program (update-form :b)) :update-b))]
    (deliver gate true)
    (let [ra (deref fa 10000 ::timeout)
          rb (deref fb 10000 ::timeout)]
      (is (contains?
            #{[{:module :race :fns [:a :run]} {:module :race :fns [:a :b :run]}]
              [{:module :race :fns [:a :b :run]} {:module :race :fns [:b :run]}]}
            (mapv core-receipt [ra rb]))
          "Receipts describe each atomic commit, not a later registry read")
      (is (= [:a :b :run]
             (sort (keys (:functions (run-form '(patterns/source :race)))))))
      (is (= [{:sentinel :board-state} {:count 17} :base :added :added]
             (run-form
               '[(globals/get :mailing-list) (globals/get :user-state)
                 (patterns/call :race :run) (patterns/call :race :a)
                 (patterns/call :race :b)]))))))
