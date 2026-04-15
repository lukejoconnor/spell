(ns spell.format
  "Format validation and retry wrapping for LLM output."
  (:require [clojure.edn :as edn]))

(defn- validate-format
  "Validate value against format spec. Returns {:valid true} or {:valid false :error msg}."
  [value {:keys [required optional]}]
  (cond
    (not (map? value))
    {:valid false :error (str "Expected map, got " (type value))}

    (not-empty (remove #(contains? value %) required))
    {:valid false :error (str "Missing required keys: "
                              (vec (remove #(contains? value %) required)))}

    :else
    {:valid true}))

(defn wrap-with-format
  "Wrap an LLM function with format validation and retry.

   For eval=false (leaf LLM): parses response as EDN, validates against format.
   For eval=true (Spell LLM): validates the evaluated result against format.

   Options:
   - :format      - format spec with :required and :optional keys
   - :eval?       - true if wrapped fn returns evaluated result (vs raw string)
   - :max-retries - max retry attempts (default 3)"
  [llm-fn {:keys [format eval? max-retries] :or {max-retries 3}}]
  (with-meta
  (fn [prompt & args]
    (loop [attempt 1
           last-response nil
           last-error nil]
      (let [;; Add retry context to prompt if retrying
            prompt' (if last-error
                      (str prompt "\n\n[Previous attempt returned:\n"
                           (pr-str last-response)
                           "\n\nError: " last-error
                           "\n\nExpected format: map with keys " (:required format)
                           (when (:optional format)
                             (str " (optional: " (:optional format) ")"))
                           "\nPlease return valid EDN matching this format.]")
                      prompt)
            ;; Call the underlying LLM
            response (apply llm-fn prompt' args)
            ;; For eval?=false, parse response as EDN
            ;; For eval?=true, response is already the evaluated result
            value (if eval?
                    response
                    (try
                      (edn/read-string response)
                      (catch Exception e
                        {:__parse-error (.getMessage e)})))
            validation (if (:__parse-error value)
                         {:valid false :error (str "Failed to parse as EDN: " (:__parse-error value))}
                         (validate-format value format))]
        (if (:valid validation)
          value  ; Return the validated value (parsed EDN or Spell result)
          (if (>= attempt max-retries)
            (throw (ex-info "Format validation failed after max retries"
                            {:attempts attempt
                             :last-response response
                             :last-value value
                             :error (:error validation)}))
            (recur (inc attempt)
                   (if eval? value response)
                   (:error validation)))))))
  (meta llm-fn)))
