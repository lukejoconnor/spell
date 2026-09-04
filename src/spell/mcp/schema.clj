(ns spell.mcp.schema
  "JSON Schema validation for MCP definitions and values."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk])
  (:import [com.networknt.schema InputFormat SchemaRegistry SpecificationVersion]))

(def ^:private registry
  (delay (SchemaRegistry/withDefaultDialect SpecificationVersion/DRAFT_2020_12)))

(defn external-refs
  [schema]
  (let [refs (volatile! [])]
    (walk/postwalk
     (fn [node]
       (when (map? node)
         (when-let [ref (or (get node "$ref") (get node :$ref))]
           (when-not (.startsWith (str ref) "#")
             (vswap! refs conj (str ref)))))
       node)
     schema)
    (vec (distinct @refs))))

(defn compile-schema
  [schema]
  (when-not (map? schema)
    (throw (ex-info "MCP JSON Schema must be an object"
                    {:type :invalid-schema :schema schema})))
  (when-let [refs (seq (external-refs schema))]
    (throw (ex-info "External JSON Schema references are disabled"
                    {:type :external-schema-reference :refs (vec refs)})))
  (try
    (.getSchema ^SchemaRegistry @registry (json/write-str schema) InputFormat/JSON)
    (catch Exception e
      (throw (ex-info "Invalid MCP JSON Schema"
                      {:type :invalid-schema :message (.getMessage e)} e)))))

(defn validation-errors
  [schema value]
  (let [compiled (compile-schema schema)]
    (->> (.validate compiled (json/write-str value) InputFormat/JSON)
         (mapv (fn [error]
                 {:path (str (.getInstanceLocation error))
                  :keyword (.getKeyword error)
                  :message (.getMessage error)})))))

(defn validate!
  ([schema value] (validate! schema value nil))
  ([schema value context]
   (let [errors (validation-errors schema value)]
     (when (seq errors)
       (throw (ex-info (str (or context "Value") " does not match JSON Schema: "
                            (:message (first errors)))
                       {:type :schema-validation
                        :context context
                        :errors errors})))
     value)))
