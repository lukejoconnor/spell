(ns spell.module-journal
  "Post-commit recording of patterns API changes only; never a globals watch."
  (:require [clojure.set :as set]
            [spell.feedback :as feedback]
            [spell.globals :as globals]
            [spell.module-notices :as notices]
            [spell.parse :as parse]
            [spell.trace :as trace]))

(defn- source-text [definition]
  (when definition
    (binding [*print-length* nil *print-level* nil *print-meta* false *print-readably* true *read-eval* false]
      (let [text (pr-str definition)
            forms (parse/read-all text)]
        (when-not (and (= 1 (count forms)) (= definition (first forms)))
          (throw (ex-info "Module source must roundtrip as one form" {})))
        text))))

(defn- function-changes [before after]
  (let [old (:functions before) new (:functions after)
        a (set (keys old)) b (set (keys new))]
    {:added (vec (sort (set/difference b a)))
     :removed (vec (sort (set/difference a b)))
     :modified (vec (sort (filter #(not= (get old %) (get new %))
                                  (set/intersection a b))))}))

(defn record-change
  "Augment a successful receipt; recording failure never replays a committed edit."
  [receipt operation before after]
  (if-not feedback/*dogfood*
    receipt
    (try
      (let [{:keys [path run-id]} feedback/*dogfood*
            entry (cond-> (merge (select-keys receipt [:module :owner :editor :explicit-owner? :revision :sequence])
                                {:kind :module-edit :operation operation :run-id run-id
                                 :timestamp (str (java.time.Instant/now))
                                 :functions (function-changes before after)
                                 :before (source-text before) :after (source-text after)})
                    (some? trace/*trace-node-id*) (assoc :trace-node-id trace/*trace-node-id*))
            serialized (binding [*print-length* nil *print-level* nil] (pr-str entry))]
        (feedback/append-entry! path serialized)
        (assoc receipt :journal {:status :ok :sequence (:sequence receipt)}))
      (catch Throwable cause
        (let [failure (merge (select-keys receipt [:module :revision :sequence])
                             {:status :failed :message "EDIT COMMITTED / RECORDING FAILED"
                              :error (str (.getName (class cause)) ": " (.getMessage cause))})]
          (swap! globals/*store* notices/enqueue (:editor receipt)
                 (assoc (select-keys receipt [:module :revision :sequence]) :kind :recording-failed))
          (assoc receipt :journal failure))))))
