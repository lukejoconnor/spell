(ns spell.test-helpers
  "Shared test utilities for Spell tests."
  (:require [spell.core :as spell]
            [spell.llm :as llm]
            [spell.provider :as provider]))

(defn make-test-llm
  "Create test LLM+run map with dummy provider.
   response-or-opts: string (static response) or map (dummy-provider opts).
   Returns {:llm fn, :run fn}.

   Keyword opts:
   - :namespaces — effect namespace map (default: all-namespaces)
   - :prefill? — prefill support (default: true)
   - :recover — recovery setting (default: true)
   - :model — model name override"
  [response-or-opts & {:keys [namespaces prefill? recover model]
                        :or {prefill? true recover true}}]
  (let [prov (provider/dummy-provider
               (if (string? response-or-opts)
                 {:response response-or-opts :prefill? prefill?}
                 (assoc response-or-opts :prefill? prefill?)))]
    (llm/make-llm (cond-> {:namespaces (or namespaces spell/all-namespaces)
                            :provider prov
                            :prefill? prefill?
                            :recover recover}
                    model (assoc :model model)))))

(defn make-test-leaf-llm
  "Create test leaf LLM with dummy provider.
   Returns (fn [prompt] response-string)."
  [response-or-opts & {:keys [system model]}]
  (let [prov (provider/dummy-provider
               (if (string? response-or-opts)
                 {:response response-or-opts}
                 response-or-opts))]
    (llm/make-leaf-llm (cond-> {:provider prov}
                         system (assoc :system system)
                         model (assoc :model model)))))
