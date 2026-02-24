(ns spell.prompt
  "System prompt composition for Spell agents.

   Pure string functions that build system prompts from namespace metadata
   and format specs. No dependency on LLM or comm."
  (:require [clojure.string :as str]))

(defn- fn-docs
  "Extract per-function doc entries from a namespace map, filtering out
   :guide and :_ meta-entries."
  [ns-map]
  (dissoc (:docs ns-map) :guide :_))

(defn- namespaces-section
  "Generate the NAMESPACES section from namespace metadata.
   effect-namespaces get short-docs + per-function docs.
   core-namespaces get short-docs only."
  [effect-namespaces core-namespaces]
  (when (or (seq effect-namespaces) (seq core-namespaces))
    (str "\nNAMESPACES\n\n"
         "Access functions with qualified symbols: namespace/item\n\n"
         (when (seq core-namespaces)
           (str (str/join "\n"
                  (map (fn [[ns-sym ns-map]]
                         (str "  " ns-sym " — " (:short-docs ns-map)))
                       core-namespaces))
                "\n\n"))
         (when (seq effect-namespaces)
           (str (str/join "\n\n"
                  (map (fn [[ns-sym ns-map]]
                         (let [docs (fn-docs ns-map)]
                           (str "## " ns-sym
                                (when (:short-docs ns-map)
                                  (str " — " (:short-docs ns-map)))
                                "\n"
                                (str/join "\n"
                                  (map (fn [[k desc]]
                                         (str "  " (name k) ": " desc))
                                       docs)))))
                       effect-namespaces))
                "\n\n"))
         "Usage:\n"
         "  (io/sh \"ls\")              — call function directly\n"
         "  '(describe io)              — namespace overview\n"
         "  '(describe io :sh)          — detailed doc for specific function\n"
         "  '(describe agents globals)  — multiple namespaces in one describe\n"
         "\n"
         "describe is an extension — it fires as the trailing expression for that turn.\n"
         "Use it before calling an unfamiliar namespace.\n")))

(defn- format-section
  "Generate RETURN VALUE section when a format spec is provided."
  [{:keys [required optional]}]
  (str "\nRETURN VALUE\n\n"
       "Your program's last expression must be a map with "
       (if (= 1 (count required))
         (str "key " (first required))
         (str "keys " (pr-str required)))
       ".\n"
       "Example: {:answer 42}\n"
       (when optional
         (str "Optional keys: " (pr-str optional) "\n"))))

(defn compose-system-prompt
  "Build a system prompt from a base prompt plus namespace docs and format.
   :base             — system prompt text (required; nil yields namespace docs only)
   :namespaces       — map of effect namespace {symbol -> namespace-map}
   :core-namespaces  — map of core namespace {symbol -> namespace-map}
   :format           — optional format spec {:required [...] :optional [...]}"
  [{:keys [base namespaces core-namespaces format]}]
  (str (or base "")
       (namespaces-section namespaces core-namespaces)
       (when format (format-section format))))

(defn generate-system-prompt
  "Build a system prompt from namespaces (convenience wrapper).
   namespaces: map of {symbol -> namespace-map}
   format: optional format spec"
  ([namespaces] (generate-system-prompt namespaces nil))
  ([namespaces format]
   (compose-system-prompt {:namespaces namespaces :format format})))
