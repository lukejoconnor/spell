(ns spell.test-helpers
  "Shared test utilities for Spell tests."
  (:require [spell.core :as spell]
            [spell.coordinator :as coordinator]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.provider :as provider]))

(defn with-test-run
  "Give each test an isolated communication environment, including its futures."
  [f]
  (binding [coordinator/*coordinator* (coordinator/new-coordinator)
            globals/*store* (globals/new-store)]
    (try (f) (finally (coordinator/close!)))))

(defn make-test-agent
  "Create a compiled test agent with test provider.
   response-or-opts: string (static response) or map (test-provider opts).
   Returns a compiled spawn-agent function.

   Keyword opts:
   - :namespaces — effect namespace map (default: all-namespaces)
   - :prefill? — prefill support (default: true)
   - :recover — recovery setting (default: true)
   - :model — model name override"
  [response-or-opts & {:keys [namespaces prefill? recover model]
                        :or {prefill? true recover true}}]
  (let [prov (provider/test-provider
               (if (string? response-or-opts)
                 {:response response-or-opts :prefill? prefill?}
                 (assoc response-or-opts :prefill? prefill?)))]
    (llm/compile-agent (cond-> {:namespaces (or namespaces spell/all-namespaces)
                                :provider prov
                                :prefill? prefill?
                                :recover recover}
                         model (assoc :model model)))))

(defn run-agent-init
  "Run a compiled agent directly on :main with an explicit init program."
  [agent-fn init-program]
  (agent-fn init-program :main))

(defn run-agent-prefix
  "Run a compiled agent through !llm-self to preserve same-handle prefix semantics."
  [agent-fn prefix]
  (run-agent-init agent-fn
                  (str "(eval (do '(!llm-self "
                       (pr-str prefix)
                       ")))")))

(defn compiled-agent-fn
  "Mark a test function as a compiled spawn-agent."
  [f]
  (with-meta f {:spell/compiled-agent true}))

(defn make-test-runner
  "Create a test helper that preserves the old host-level prefix call shape
   while routing through a compiled agent + !llm-self init program."
  [response-or-opts & {:as opts}]
  (let [agent-fn (apply make-test-agent response-or-opts (mapcat identity opts))]
    (fn [prefix]
      (run-agent-prefix agent-fn prefix))))

(defn make-test-leaf-llm
  "Create test leaf LLM with test provider.
   Returns (fn [prompt] response-string)."
  [response-or-opts & {:keys [system model]}]
  (let [prov (provider/test-provider
               (if (string? response-or-opts)
                 {:response response-or-opts}
                 response-or-opts))]
    (llm/make-leaf-llm (cond-> {:provider prov}
                         system (assoc :system system)
                         model (assoc :model model)))))
