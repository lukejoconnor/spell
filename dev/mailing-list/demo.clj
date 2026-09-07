(require '[spell.test-helpers :as th]
         '[spell.runtime :as runtime]
         '[spell.coordinator :as coordinator]
         '[spell.globals :as globals]
         '[spell.stdlib :as stdlib])

;; Deterministic provider; all board calls, bootstrap, edges and receipt are real.
;; Run from repository root with the command in implementation-report.md.
(def evidence (atom []))
(defn observe [kind value]
  (swap! evidence conj {:kind kind :handle runtime/*current-handle* :value value})
  value)

(th/with-test-run
 (fn []
  (let [agent
        (th/make-test-agent
         {:response-fn
          (fn [p]
            (let [h runtime/*current-handle*]
              (observe :generation h)
              (if (not= h :main)
                (do
                  (assert (get-in (globals/get-val :mailing-list)
                                  [:lists :research :subscriptions h]))
                  "'(let [posted (patterns/mail :post {:list :research :summary (str (agents/current-handle) \" found evidence\") :provenance {:experiment \"E7\"}}) page (patterns/mail :digest {:list :research})] (patterns/mail :ack {:token (:token page)}) (audit/observe :worker-result {:posted posted :custom (:custom page) :ids (map :id (:messages page))}))))")
                (let [edges (runtime/out-edges)]
                  (observe :remaining-edges (count edges))
                  (if (seq edges)
                    "'(agents/!wait)))"
                    "'(audit/finish (patterns/mail :lists {}))))")))))}
         :prefill? false :recover false
         :namespaces
         {'patterns stdlib/patterns 'globals globals/globals-namespace
          'agents runtime/agents-namespace
          'audit {:observe observe
                  :finish (fn [v]
                            (observe :final v)
                            (coordinator/close!) v)}})
        result
        (th/run-agent-init
         agent
         "(quine completion (eval (do '(do
            (patterns/mailing-list {:retention 3 :page-size 2})
            (patterns/mail :create {:list :research :description \"Four-agent experiment evidence\"})
            (globals/update :mailing-list
              (fn [b] (-> b
                          (assoc-in [:code :original-digest] (get-in b [:code :digest]))
                          (assoc-in [:code :digest]
                            '(fn [board args]
                               (assoc ((eval (get-in board [:code :original-digest])) board args)
                                      :custom :visible-to-new-agents))))))
            (patterns/mail :spawn {:task \"Post evidence and acknowledge your digest.\" :handle :reader-1 :lists [:research]})
            (patterns/mail :spawn {:task \"Post evidence and acknowledge your digest.\" :handle :reader-2 :lists [:research]})
            (patterns/mail :spawn {:task \"Post evidence and acknowledge your digest.\" :handle :reader-3 :lists [:research]})
            (patterns/mail :spawn {:task \"Post evidence and acknowledge your digest.\" :handle :reader-4 :lists [:research]})
            (agents/!wait)))))")
        workers (filter #(= :worker-result (:kind %)) @evidence)]
    (assert (= 4 (count workers)))
    (assert (every? #(= :visible-to-new-agents (get-in % [:value :custom])) workers))
    (assert (= #{1 2 3 4} (set (map #(get-in % [:value :posted :id]) workers))))
    (assert (= 3 (:retained (first result))))
    (assert (= 4 (count (:subscribers (first result)))))
    (spit "dev/mailing-list/dogfood-evidence.edn" (pr-str {:result result :events @evidence}))
    (println "PASS: four real Spell agents onboarded, invoked customized shared source, posted unique IDs, digested/acknowledged, and returned through coordinator edges; retention=3."))))
(shutdown-agents)
