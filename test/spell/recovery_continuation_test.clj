(ns spell.recovery-continuation-test
  "Deterministic regression coverage for lifecycle-local consecutive recovery errors."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [spell.llm :as llm]
            [spell.coordinator :as coordinator]
            [spell.test-helpers :as th]))

(use-fixtures :each th/with-test-run)

(defn- scripted-run [responses]
  (let [depths (atom [])
        receipts (atom [])
        runner (th/make-test-runner
                {:response-fn
                 (fn [_]
                   (let [index (count @depths)]
                     (swap! depths conj (:consecutive-errors @(:execution (coordinator/agent :main))))
                     (if (< index (count responses))
                       (nth responses index)
                       (throw (ex-info "Reproducer response script exhausted" {})))))}
                :namespaces {'probe {:receipt (fn [value]
                                                (swap! receipts conj value)
                                                value)}})
        outcome (try
                  {:value (runner "(quine completion (eval (do ")}
                  (catch Exception e
                    {:error (.getMessage e)}))]
    (assoc outcome :depths @depths :receipts @receipts)))

(deftest distinct-repaired-mistakes-do-not-accumulate
  (let [result (scripted-run
                ["'(missing-a)"
                 "(def checkpoint-a {:receipt :a}) '(do (probe/receipt checkpoint-a) (!llm-self (reopen completion)))"
                 "'(missing-b)"
                 "(def checkpoint-b {:receipt :b}) '(do (probe/receipt checkpoint-b) (!llm-self (reopen completion)))"
                 "'(missing-c)"
                 "42"])]
    (println "distinct-later-mistakes" (pr-str result))
    (is (= 42 (:value result)))
    (is (= [0 1 0 1 0 1] (:depths result)))
    (is (= [{:receipt :a} {:receipt :b}] (:receipts result)))))

(deftest successful-continuation-preserves-checkpoint-without-replay
  (let [result (scripted-run
                ["'(missing-a)"
                 "(def checkpoint {:receipt :a}) '(do (probe/receipt checkpoint) (!llm-self (reopen completion)))"
                 "checkpoint"])]
    (println "successful-continuation" (pr-str result))
    (is (= {:receipt :a} (:value result)))
    (is (= [0 1 0] (:depths result)))
    (is (= [{:receipt :a}] (:receipts result)))))

(deftest unresolved-reader-and-eval-errors-share-three-failure-limit
  (doseq [[label responses phase]
          [[:eval (repeat 3 "undefined-symbol)") "eval"]
           [:reader (repeat 3 "\\invalidchar)") "reader"]
           [:mixed ["\\invalidchar)" "undefined-symbol)" "undefined-symbol)"] "eval"]]]
    (let [result (scripted-run (vec responses))]
      (println (name label) (pr-str result))
      (is (str/includes? (or (:error result) "")
                         (str "Consecutive error limit reached: 3 while handling " phase " error")))
      (is (= [0 1 2] (:depths result)))
      (is (empty? (:receipts result))))))

(deftest inert-recovery-guidance-matches-approved-text
  (is (= "The previous Spell program threw an error. The previous program is visible during this recovery turn, but it will be pruned afterward, such that you will not see it on your next turn.\n\nEmit a `(quine task \"...\")` form describing the original task, followed by a (quine context-summary \"...\") form describing history, progress, and any context which should be retained on your next turn. Preserve the exact evidence, checkpoints, pending obligations, and actual effect receipts needed next. Prefer `(stored \"ID\")` with an ID actually observed in the previous program. Page a prior local value only after its needed pure binding has been re-established in the current program. Use `subs` for strings, `subvec` for line vectors, or the documented text/vector field for maps. Keep pages within the existing contribution cap and carry IDs plus next offsets as literal data. The previous program is inert context: its local bindings are not active. Reconstruct only the needed pure bindings or use retained stored references. Do not rerun an effect just to recover its output. Read the file again only when fresh contents are actually required, and identify the result as fresh evidence rather than the original receipt. If the original value cannot be retrieved, report missing evidence rather than claiming inspection or blindly refetching. Emit Spell code only. Avoid repeating your previous error."
         @#'llm/inert-recovery-prompt)))

(deftest reader-and-inert-eval-recovery-deliver-retained-evidence-guidance
  (doseq [first-response ["undefined-symbol)" "\\invalidchar)"]]
    (let [prompts (atom [])
          runner (th/make-test-runner
                   {:response-fn (fn [prompt]
                                   (swap! prompts conj prompt)
                                   (if (= 1 (count @prompts)) first-response "42)"))}
                   :namespaces {} :prefill? false)]
      (is (= 42 (runner "(quine completion (eval (do ")))
      (is (= 2 (count @prompts)))
      (let [recovery-prefix (second @prompts)]
        (doseq [fragment ["Preserve the exact evidence, checkpoints, pending obligations, and actual effect receipts needed next."
                          "with an ID actually observed in the previous program"
                          "Page a prior local value only after its needed pure binding has been re-established in the current program."
                          "Use `subs` for strings, `subvec` for line vectors"
                          "carry IDs plus next offsets as literal data"
                          "The previous program is inert context: its local bindings are not active."
                          "Do not rerun an effect just to recover its output."
                          "identify the result as fresh evidence rather than the original receipt"
                          "report missing evidence rather than claiming inspection or blindly refetching"]]
          (is (str/includes? recovery-prefix fragment) fragment))
        (is (not (str/includes? recovery-prefix "restore these by re-reading from those files")))))))
