(ns spell.module-ownership-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [spell.api :as api]
            [spell.core :as spell]
            [spell.coordinator :as coordinator]
            [spell.feedback :as feedback]
            [spell.globals :as globals]
            [spell.module-notices :as notices]
            [spell.parse :as parse]
            [spell.patterns :as patterns]
            [spell.provider :as provider]
            [spell.runtime :as runtime]
            [spell.test-helpers :as th]))

(use-fixtures :each
  (fn [f]
    (th/with-test-run
      #(do (doseq [h [:owner :editor :other]] (coordinator/register! h))
           (binding [runtime/*current-handle* :owner feedback/*dogfood* nil] (f))))))

(def definition '{:doc "baseline" :functions
                  {:run {:doc "value" :requires [] :source (fn [] :baseline)}}})
(defn install! [] (patterns/install :m definition))
(defn decode [records] (mapv edn/read-string @records))
(defn program [action]
  (pr-str (list 'quine 'completion (list 'eval (list 'do (list 'quote action))))))
(defn suffix [action] (str (pr-str (list 'quote action)) ")))"))

(deftest immutable-winning-owner-and-reinstallation
  (let [first (install!) state @globals/*store*]
    (is (= {:module :m :owner :owner :editor :owner :explicit-owner? false
            :installed? true :fns [:run] :revision 1 :sequence 1} first))
    (is (= definition (get-in state [:modules :m])))
    (is (= :owner (:owner (patterns/catalog :m))))
    (is (nil? (:owner (patterns/catalog :relay))))
    (binding [runtime/*current-handle* :editor]
      (is (= (assoc first :installed? false :editor :editor)
             (patterns/install :m nil))))
    (is (= state @globals/*store*))
    (is (= [{:kind :owner :module :m :revision 1}]
           (get-in state [:module-notices :owner])))
    (is (nil? (get-in state [:module-notices :editor])))))

(deftest concurrent-install-actual-winner
  (let [gate (promise)
        work (fn [handle doc]
               (binding [runtime/*current-handle* handle]
                 @gate (patterns/install :m (assoc definition :doc doc))))
        a (future (work :owner "a")) b (future (work :editor "b"))]
    (deliver gate true)
    (let [receipts [(deref a 5000 ::timeout) (deref b 5000 ::timeout)]
          winner (first (filter :installed? receipts))]
      (is (= 1 (count (filter :installed? receipts))))
      (is (= (:editor winner) (:owner winner)))
      (is (every? #(= (:owner winner) (:owner %)) receipts))
      (is (= (if (= :owner (:owner winner)) "a" "b") (:doc (patterns/source :m))))
      (is (= {(:owner winner) [{:kind :owner :module :m :revision 1}]}
             (:module-notices @globals/*store*)))
      (is (= 1 (:module-sequence @globals/*store*))))))

(deftest identity-and-authorization-reject-before-transform
  (install!)
  (let [calls (atom 0) transform (fn [d & _] (swap! calls inc) d)]
    (doseq [handle [nil :unregistered]]
      (binding [runtime/*current-handle* handle]
        (is (thrown-with-msg? Exception #"registered current agent" (patterns/install :m)))
        (is (thrown-with-msg? Exception #"registered current agent" (patterns/update :m transform)))))
    (is (thrown-with-msg? Exception #"not installed" (patterns/update :absent transform)))
    (binding [runtime/*current-handle* :editor]
      (doseq [args [[transform] [{:owner :wrong} transform] [nil transform]
                    [false transform] [{:owner nil} transform] [{:owner false} transform]
                    [{} transform] [{:owner :owner :extra true} transform]
                    [transform {:owner :owner}]]]
        (is (thrown? Exception (apply patterns/update :m args)) (pr-str args))))
    (is (zero? @calls))
    (binding [runtime/*current-handle* :editor]
      (is (= :editor (:editor (patterns/update :m {:owner :owner} transform)))))
    (is (= 1 @calls))))

(deftest owner-field-cannot-transfer-and-noop-does-not-revise
  (install!)
  (let [state @globals/*store* r (patterns/update :m identity)]
    (is (= state @globals/*store*))
    (is (= [1 1] ((juxt :revision :sequence) r))))
  (patterns/update :m assoc :owner :editor)
  (is (= :editor (:owner (patterns/source :m))))
  (is (= :owner (:owner (patterns/catalog :m))))
  (binding [runtime/*current-handle* :editor]
    (is (thrown-with-msg? Exception #"owner mismatch" (patterns/update :m identity))))
  (let [state @globals/*store*]
    (is (thrown? Exception (patterns/update :m (constantly nil))))
    (is (= state @globals/*store*))))

(deftest retry-rechecks-owner-before-running-transform
  (install!)
  (let [entered (promise) release (promise) calls (atom 0)
        task (future
               (try (patterns/update :m
                      (fn [d] (swap! calls inc) (deliver entered true)
                        (deref release 5000 nil) (assoc d :doc "speculative")))
                    (catch Exception e e)))]
    (is (= true (deref entered 5000 ::timeout)))
    ;; Deliberate unsupported globals write forces the retry snapshot to differ.
    (swap! globals/*store* assoc-in [:module-installations :m :owner] :other)
    (deliver release true)
    (is (instance? Exception (deref task 5000 ::timeout)))
    (is (= 1 @calls))
    (is (= definition (patterns/source :m)))))

(deftest bounded-per-handle-notices-consume-once-and-preserve-state
  (doseq [n (range 20)] (patterns/install (keyword (str "module-" n)) definition))
  (swap! globals/*store* assoc :mailing-list {:sentinel true} :user-state [1 2])
  (is (= "prefix" (notices/prepend-pending :editor "prefix")))
  (let [pages (repeatedly 3 #(notices/prepend-pending :owner ""))
        pages (vec pages)]
    (is (every? #(<= (count %) notices/max-chars) pages))
    (is (str/includes? (first pages) "and 12 more queued"))
    (is (str/includes? (second pages) "and 4 more queued"))
    (doseq [n (range 20)]
      (is (= 1 (count (re-seq (re-pattern (str ":module-" n "(?=[, ])")) (str/join pages)))))))
  (is (= "same" (notices/prepend-pending :owner "same")))
  (is (= {:sentinel true} (:mailing-list @globals/*store*)))
  (is (= [1 2] (:user-state @globals/*store*)))
  (is (empty? (:module-notices @globals/*store*))))

(deftest oversized-and-hostile-identifiers-are-inert-and-progress
  (patterns/install (keyword (apply str (repeat 5000 "x"))) definition)
  (patterns/install (keyword "line\n(do :not-code)\r") definition)
  (let [a (notices/prepend-pending :owner "") b (notices/prepend-pending :owner "")]
    (is (<= (count a) notices/max-chars))
    (is (str/includes? a "[identifier exceeds notice limit]"))
    (is (str/includes? a "and 1 more queued"))
    (is (str/includes? b "\\n"))
    (is (= [] (parse/read-all a)))
    (is (= [] (parse/read-all b)))
    (is (= "" (notices/prepend-pending :owner "")))))

(deftest racing-notice-consumers-do-not-duplicate
  (install!)
  (let [gate (promise) a (future @gate (notices/prepend-pending :owner ""))
        b (future @gate (notices/prepend-pending :owner ""))]
    (deliver gate true)
    (is (= 1 (count (filter #(str/includes? % "MODULE NOTICE")
                            [(deref a 5000 "timeout") (deref b 5000 "timeout")]))))))

(deftest notices-preserve-incomplete-lexical-prefixes
  (doseq [[label prefix suffix]
          [["string" "(str \"hel" "lo\")"]
           ["escaped quote" "(str \"hello\\" "\"world\")"]
           ["symbol" "(identity 'hel" "lo)"]
           ["keyword" "(identity :hel" "lo)"]
           ["character" "(identity \\new" "line)"]
           ["quoted form" "'[\"hel" "lo\"]"]
           ["normal completion" "(quine completion (eval (do " "'42)))"]]]
    (testing label
      (let [module (keyword (str "lexical-" label))]
        (patterns/install module definition)
        (let [actual (notices/prepend-pending :owner prefix)
              notice (subs actual 0 (- (count actual) (count prefix)))]
          (is (str/starts-with? actual "\n; MODULE NOTICE:"))
          (is (str/ends-with? actual prefix) "Original prefix remains byte-for-byte intact")
          (is (= [] (parse/read-all notice)) "Guidance is a complete inert comment")
          (is (= (parse/read-all (str prefix suffix))
                 (parse/read-all (str actual suffix)))
              "The same suffix produces exactly the original program")
          (is (= prefix (notices/prepend-pending :owner prefix))
              "This notice is consumed only once"))))))

(deftest discarded-install-notice-preserves-open-string-provider-prefix
  (let [prefixes (atom [])
        prefix "(str \"hel"
        agent (th/make-test-agent
                {:response-fn (fn [p] (swap! prefixes conj p) "lo\")")}
                :prefill? false :recover false)]
    (is (= "hello"
           (binding [runtime/*current-handle* nil]
             (agent (program '(do (patterns/install :relay)
                                  (!llm-self "(str \"hel")))
                    :notice-string))))
    (is (= 1 (count @prefixes)) "Notice delivery adds no model call")
    (is (str/includes? (first @prefixes) "You own module(s) :relay"))
    (is (str/ends-with? (first @prefixes) prefix))
    (is (empty? (get-in @globals/*store* [:module-notices :notice-string])))))

(deftest journal-exact-baseline-nonowner-editor-and-function-delta
  (let [records (atom [])]
    (binding [feedback/*dogfood* {:path "unused" :run-id "run-a"}]
      (with-redefs [feedback/append-entry! (fn [_ s] (swap! records conj s))]
        (is (= {:status :ok :sequence 1} (:journal (install!))))
        (binding [runtime/*current-handle* :editor]
          (let [receipt (patterns/update :m {:owner :owner}
                          (fn [d] (-> d (assoc :doc "changed")
                                    (assoc-in [:functions :added] (get-in d [:functions :run]))
                                    (assoc-in [:functions :run :source] '(fn [] :changed)))))]
            (is (= [:owner :editor true 2 2]
                   ((juxt :owner :editor :explicit-owner? :revision :sequence) receipt)))))
        (patterns/update :m update :functions dissoc :run)
        (patterns/install :m)
        (patterns/update :m identity)
        (is (thrown? Exception (patterns/update :m (constantly {}))))
        (swap! globals/*store* assoc :direct-write :not-journaled)
        (let [[a b c] (decode records)]
          (is (= 3 (count @records)))
          (is (= [:install :update :update] (mapv :operation [a b c])))
          (is (= [1 2 3] (mapv :sequence [a b c])))
          (is (= [1 2 3] (mapv :revision [a b c])))
          (is (every? #(= "run-a" (:run-id %)) [a b c]))
          (is (every? #(string? (:timestamp %)) [a b c]))
          (is (nil? (:before a)))
          (is (= [definition] (parse/read-all (:after a))))
          (is (= (:after a) (:before b)))
          (is (= (:after b) (:before c)))
          (is (= {:added [:run] :removed [] :modified []} (:functions a)))
          (is (= {:added [:added] :removed [] :modified [:run]} (:functions b)))
          (is (= {:added [] :removed [:run] :modified []} (:functions c)))
          (is (= :editor (:editor b)))
          (is (true? (:explicit-owner? b)))
          (is (= [(patterns/source :m)] (parse/read-all (:after c)))))))))

(deftest direct-globals-module-code-edit-bypasses-journal-and-becomes-next-baseline
  (let [records (atom [])
        direct-definition (assoc-in definition [:functions :run :source] '(fn [] :direct))]
    (binding [feedback/*dogfood* {:path "unused" :run-id "direct-code"}]
      (with-redefs [feedback/append-entry! (fn [_ s] (swap! records conj s))]
        (install!)
        (let [metadata (:module-installations @globals/*store*)
              notices (:module-notices @globals/*store*)]
          ;; Exercise the public globals API against actual executable module code.
          (binding [runtime/*current-handle* :editor]
            (globals/update-val :modules
              #(assoc-in % [:m :functions :run :source] '(fn [] :direct))))
          (is (= :direct (patterns/call :m :run)))
          (is (= direct-definition (patterns/source :m)))
          (is (= 1 (count @records)))
          (is (= 1 (:module-sequence @globals/*store*)))
          (is (= metadata (:module-installations @globals/*store*)))
          (is (= notices (:module-notices @globals/*store*))))
        (let [receipt (patterns/update :m assoc :doc "after direct write")
              [baseline edited] (decode records)]
          (is (= 2 (count @records)))
          (is (= [1 2] (mapv :sequence [baseline edited])))
          (is (= {:status :ok :sequence 2} (:journal receipt)))
          (is (= [definition] (parse/read-all (:after baseline))))
          (is (= [direct-definition] (parse/read-all (:before edited))))
          (is (= [(patterns/source :m)] (parse/read-all (:after edited))))
          (is (= {:added [] :removed [] :modified []} (:functions edited)))
          (is (= :direct (patterns/call :m :run))))))))

(deftest journal-retries-record-only-successful-cas-and-exact-receipts
  (let [records (atom []) entered (promise) release (promise) calls (atom 0)]
    (binding [feedback/*dogfood* {:path "unused" :run-id "race"}]
      (with-redefs [feedback/append-entry! (fn [_ s] (swap! records conj s))]
        (install!)
        (let [a (future
                  (patterns/update :m
                    (fn [d]
                      (when (= 1 (swap! calls inc))
                        (deliver entered true) (deref release 5000 nil))
                      (assoc-in d [:functions :a] (get-in definition [:functions :run])))))]
          (is (= true (deref entered 5000 ::timeout)))
          (let [b (patterns/update :m assoc :doc "concurrent")]
            (deliver release true)
            (let [a (deref a 5000 ::timeout) entries (sort-by :sequence (decode records))]
              (is (= 2 @calls))
              (is (= [2 3] [(:revision b) (:revision a)]))
              (is (= [:run] (:fns b)))
              (is (= [:a :run] (:fns a)))
              (is (= [1 2 3] (mapv :sequence entries)))
              (is (= 3 (count entries)))
              (is (= (:after (second entries)) (:before (nth entries 2)))))))))))

(deftest recording-failure-is-committed-receipt-and-distinct-warning
  (binding [feedback/*dogfood* {:path "unused" :run-id "failure"}]
    (with-redefs [feedback/append-entry! (fn [& _] (throw (java.io.IOException. "disk unavailable")))]
      (let [receipt (install!) failure (:journal receipt)]
        (is (true? (:installed? receipt)))
        (is (= definition (patterns/source :m)))
        (is (= {:status :failed :message "EDIT COMMITTED / RECORDING FAILED"
                :module :m :revision 1 :sequence 1}
               (dissoc failure :error)))
        (is (str/includes? (:error failure) "disk unavailable")))
      (binding [runtime/*current-handle* :editor]
        (is (= :failed (get-in (patterns/update :m {:owner :owner} assoc :doc "live") [:journal :status]))))
      (let [owner-page (notices/prepend-pending :owner "")
            editor-page (notices/prepend-pending :editor "")]
        (is (str/includes? owner-page "You own module(s)"))
        (is (str/includes? owner-page "EDIT COMMITTED / RECORDING FAILED"))
        (is (str/includes? editor-page "revision 2 sequence 2"))
        (is (not (str/includes? editor-page "You own module(s)")))
        (is (str/includes? editor-page "do not replay")))
      (is (= "live" (:doc (patterns/source :m)))))))

(deftest recording-errors-preserve-committed-edit-and-warn-without-replay
  (doseq [stage [:serialization :append]]
    (patterns/install stage definition)
    (let [transform-calls (atom 0)
          recording-calls (atom 0)
          failure (if (= stage :serialization)
                    (StackOverflowError. "controlled serialization failure")
                    (AssertionError. "controlled append failure"))
          recording-var (if (= stage :serialization)
                          #'spell.module-journal/source-text
                          #'feedback/append-entry!)]
      (binding [feedback/*dogfood* {:path "unused" :run-id "recording-error"}
                runtime/*current-handle* :editor]
        (with-redefs-fn
          {recording-var (fn [& _] (swap! recording-calls inc) (throw failure))}
          (fn []
            (let [receipt (patterns/update stage {:owner :owner}
                            (fn [d]
                              (swap! transform-calls inc)
                              (assoc-in d [:functions :run :source] '(fn [] :committed))))]
              (is (= :failed (get-in receipt [:journal :status])))
              (is (= "EDIT COMMITTED / RECORDING FAILED" (get-in receipt [:journal :message])))
              (is (str/includes? (get-in receipt [:journal :error]) (.getName (class failure))))
              (is (= [:owner :editor 2] ((juxt :owner :editor :revision) receipt)))
              (is (= 1 @transform-calls))
              (is (= 1 @recording-calls))
              (is (= :committed (patterns/call stage :run)))
              (is (= 2 (get-in @globals/*store* [:module-installations stage :revision])))
              (is (= 1 (count (get-in @globals/*store* [:module-notices :editor]))))
              (let [warning (notices/prepend-pending :editor "")]
                (is (str/includes? warning "EDIT COMMITTED / RECORDING FAILED"))
                (is (str/includes? warning "do not replay")))
              (is (= "" (notices/prepend-pending :editor ""))))))))))

(deftest dogfood-off-never-serializes-or-appends
  (with-redefs [feedback/new-dogfood-context (fn [] (throw (Exception. "disk setup")))
                feedback/append-entry! (fn [& _] (throw (Exception. "append")))
                spell.module-journal/source-text (fn [& _] (throw (Exception. "serialization")))]
    (is (not (contains? (install!) :journal)))
    (is (not (contains? (patterns/update :m assoc :doc "off") :journal)))
    (is (= :baseline (patterns/call :m :run)))))

(deftest discarded-install-notice-reaches-next-provider-only-once
  (let [prefixes (atom [])
        agent (th/make-test-agent
                {:response-fn (fn [p]
                                (swap! prefixes conj p)
                                (if (= 1 (count @prefixes))
                                  (suffix '(!llm-self "(quine completion (eval (do "))
                                  (suffix :done)))}
                :prefill? false :recover false)]
    (is (= :done (binding [runtime/*current-handle* nil]
                    (agent (program '(do (patterns/install :relay)
                                         (!llm-self "(quine completion (eval (do ")))
                           :notice-agent))))
    (is (= 2 (count @prefixes)))
    (is (str/includes? (first @prefixes) "You own module(s) :relay"))
    (is (not (str/includes? (second @prefixes) "MODULE NOTICE")))
    (is (empty? (get-in @globals/*store* [:module-notices :notice-agent])))))

(deftest public-api-run-identities-and-child-inheritance
  (let [records (atom [])
        run (fn [dogfood?]
              (binding [runtime/*current-handle* nil]
                (api/run {:init (program '(do (patterns/install :relay)
                                           (agents/spawn-ask "(eval '(patterns/install :mailing-list))" :child)
                                           (agents/!wait)))
                        :dogfood dogfood?
                        :model-profile (provider/test-provider {:response (suffix :done) :prefill? false})
                        :agent-profile "config/agent-profiles/cli.agent.edn"})))]
    (with-redefs [feedback/append-entry! (fn [_ s] (swap! records conj s))]
      (let [a (run true) b (run true) entries (decode records)
            runs (group-by :run-id entries)]
        (is (not (:error a)) (pr-str a))
        (is (not (:error b)) (pr-str b))
        (is (= 2 (count runs)))
        (is (= 4 (count entries)))
        (doseq [[id es] runs]
          (is (= id (str (java.util.UUID/fromString id))))
          (is (= #{:main :child} (set (map :owner es))))
          (is (= [1 2] (sort (map :sequence es))))))
      (reset! records [])
      (is (not (:error (run false))))
      (is (empty? @records)))))

(deftest notice-receiving-preemption-preserves-executed-tool-receipts
  (let [prefixes (atom []) ticks (atom 0) marks (atom [])
        t (spell.trace/new-trace)
        agent (th/make-test-agent
                {:response-fn
                 (fn [p]
                   (swap! prefixes conj p)
                   (case (count @prefixes)
                     1 (do (coordinator/send! :notice-receiving
                             {:message {:from :observer :body :arrival}})
                           (suffix '(audit/mark :must-not-run)))
                     2 (suffix '[install-receipt evidence])
                     (throw (Exception. "Unexpected extra generation"))))}
                :namespaces (assoc spell/all-namespaces 'audit
                              {:tick #(do (swap! ticks inc) :executed-evidence)
                               :mark #(swap! marks conj %)})
                :prefill? false :recover false)
        result (binding [runtime/*current-handle* nil spell.trace/*trace* t]
                 (agent (program '(!call-now install-receipt (patterns/install :relay)
                                            evidence (audit/tick)))
                        :notice-receiving))]
    (is (= 1 @ticks))
    (is (empty? @marks) "Receiving policy replaces only the proposed trailing action")
    (is (= :executed-evidence (second result)))
    (is (= :notice-receiving (:owner (first result))))
    (is (= 2 (count @prefixes)))
    (is (str/includes? (first @prefixes) "MODULE NOTICE"))
    (is (not (str/includes? (second @prefixes) "MODULE NOTICE")))
    (doseq [p @prefixes]
      (is (str/includes? p "install-receipt"))
      (is (str/includes? p ":executed-evidence")))
    (is (str/includes? (second @prefixes) ":arrival"))
    (is (= @prefixes (mapv :prompt (:nodes @t))))
    (is (empty? (:mailbox (coordinator/agent :notice-receiving))))))

(deftest notice-does-not-turn-raw-generation-into-mailbox-receipt
  (let [seen (atom [])
        agent (th/make-test-agent
                {:response-fn
                 (fn [p]
                   (swap! seen conj p)
                   (coordinator/send! :notice-raw
                     {:message {:from :observer :body :still-queued}})
                   (suffix '(audit/queued)))}
                :namespaces (assoc spell/all-namespaces 'audit
                              {:queued #(-> (coordinator/agent :notice-raw) :mailbox)})
                :prefill? false :recover false)]
    (is (= [{:message {:from :observer :body :still-queued}}]
           (binding [runtime/*current-handle* nil]
             (agent (program '(do (patterns/install :relay)
                                  (!llm-self "(quine completion (eval (do ")))
                    :notice-raw))))
    (is (= 1 (count @seen)))
    (is (str/includes? (first @seen) "MODULE NOTICE"))))

(deftest provider-failure-does-not-requeue-consumed-notice
  (let [seen (atom [])
        agent (th/make-test-agent
                {:response-fn #(do (swap! seen conj %) (throw (Exception. "provider failed")))}
                :prefill? false :recover false)]
    (is (thrown? Exception
          (binding [runtime/*current-handle* nil]
            (agent (program '(do (patterns/install :relay)
                                 (!llm-self "(quine completion (eval (do ")))
                   :failed-provider))))
    (is (str/includes? (first @seen) "MODULE NOTICE"))
    (is (empty? (get-in @globals/*store* [:module-notices :failed-provider])))))

(deftest real-disk-lossless-source-and-effect-trace-linkage
  (let [dir (clojure.java.io/file ".spell" (str "ownership-test-" (java.util.UUID/randomUUID)))
        file (clojure.java.io/file dir "records.edn")
        definition (assoc definition :doc "Unicode λ\nquoted \"source\" and \\slash")
        tick (atom 0)
        changed (assoc-in definition [:functions :run]
                  '{:doc "effectful" :requires [audit] :source (fn [] (audit/tick))})]
    (try
      (binding [feedback/*dogfood* {:path (str file) :run-id "disk-run"}
                spell.trace/*trace-node-id* 71]
        (let [a (patterns/install :m definition)
              b (patterns/update :m (constantly changed))]
          (is (= :ok (get-in a [:journal :status])))
          (is (= :ok (get-in b [:journal :status])))
          (is (zero? @tick))
          (let [[a b :as entries] (mapv edn/read-string (str/split-lines (slurp file)))]
            (is (= 2 (count entries)))
            (is (= [71 71] (mapv :trace-node-id entries)))
            (is (= [definition] (parse/read-all (:after a))))
            (is (= [definition] (parse/read-all (:before b))))
            (is (= [changed] (parse/read-all (:after b))))
            (let [agent (th/make-test-agent "nil" :recover false
                          :namespaces (assoc spell/all-namespaces 'audit {:tick #(swap! tick inc)}))]
              (is (= 1 (binding [runtime/*current-handle* nil]
                         (agent (program '(patterns/call :m :run)) :effect-caller))))
              (is (= 1 @tick))
              (is (= 2 (count (str/split-lines (slurp file)))))
              (is (= 2 (:module-sequence @globals/*store*)))))))
      (finally
        (clojure.java.io/delete-file file true)
        (clojure.java.io/delete-file dir true)))))

(deftest non-roundtrippable-source-fails-recording-not-the-committed-edit
  (binding [feedback/*dogfood* {:path "unused" :run-id "roundtrip"}]
    (with-redefs [feedback/append-entry! (fn [& _] (throw (Exception. "must not append")))]
      (let [d (assoc definition :opaque (Object.))
            receipt (patterns/install :m d)]
        (is (= :failed (get-in receipt [:journal :status])))
        (is (= "EDIT COMMITTED / RECORDING FAILED" (get-in receipt [:journal :message])))
        (is (identical? (:opaque d) (:opaque (patterns/source :m))))))))

(deftest public-api-dogfood-off-does-not-set-up-destination
  (with-redefs [feedback/new-dogfood-context #(throw (Exception. "off must not initialize dogfood"))
                feedback/append-entry! (fn [& _] (throw (Exception. "off must not append")))]
    (let [result (binding [runtime/*current-handle* nil]
                   (api/run {:init (program '(patterns/install :relay))
                             :model-profile (provider/test-provider {:response "nil"})
                             :agent-profile "config/agent-profiles/cli.agent.edn"}))]
      (is (nil? (:error result)) (pr-str result))
      (is (not (contains? (:result result) :journal))))))

(deftest leaf-generation-does-not-consume-agent-notices
  (install!)
  (let [before (:module-notices @globals/*store*)
        seen (atom [])
        leaf (th/make-test-leaf-llm {:response-fn #(do (swap! seen conj %) "leaf-answer")})]
    (is (= "leaf-answer" (leaf "leaf-query")))
    (is (= ["leaf-query"] @seen))
    (is (= before (:module-notices @globals/*store*)))
    (is (str/includes? (notices/prepend-pending :owner "") "You own module(s) :m"))))

(deftest journal-sequence-reconstructs-reordered-post-commit-appends
  (let [records (atom []) baseline-committed (promise) release-baseline (promise)]
    (binding [feedback/*dogfood* {:path "unused" :run-id "reordered"}]
      (with-redefs [feedback/append-entry!
                    (fn [_ text]
                      (when (= 1 (:sequence (edn/read-string text)))
                        (deliver baseline-committed true)
                        (deref release-baseline 5000 nil))
                      (swap! records conj text))]
        (let [baseline (future (install!))]
          (try
            (is (= true (deref baseline-committed 5000 ::timeout)))
            (is (= definition (patterns/source :m)) "Install is visible before its append finishes")
            (let [edited (binding [runtime/*current-handle* :editor]
                           (patterns/update :m {:owner :owner} assoc :doc "second"))]
              (is (= {:status :ok :sequence 2} (:journal edited)))
              (deliver release-baseline true)
              (is (= {:status :ok :sequence 1} (:journal (deref baseline 5000 ::timeout))))
              (let [appended (decode records) ordered (sort-by :sequence appended)]
                (is (= [2 1] (mapv :sequence appended)))
                (is (= [1 2] (mapv :sequence ordered)))
                (is (nil? (:before (first ordered))))
                (is (= (:after (first ordered)) (:before (second ordered))))
                (is (= [(patterns/source :m)] (parse/read-all (:after (second ordered)))))))
            (finally (deliver release-baseline true))))))))
