(ns spell.feedback
  "Structured, append-only dogfooding feedback for Spell agents."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def categories
  "Supported feedback categories."
  #{:bug :friction :idea :docs})

(def ^:private write-lock (Object.))

(defn feedback-path
  "Return the feedback log path. SPELL_FEEDBACK_PATH overrides the project-local default."
  []
  (or (System/getenv "SPELL_FEEDBACK_PATH")
      ".spell/feedback.edn"))

(defn- validate! [category message metadata]
  (when-not (contains? categories category)
    (throw (ex-info (str "Unsupported feedback category: " category)
                    {:category category :supported categories})))
  (when-not (and (string? message) (not (str/blank? message)))
    (throw (ex-info "Feedback message must be a non-blank string"
                    {:message message})))
  (when-not (map? metadata)
    (throw (ex-info "Feedback metadata must be a map"
                    {:metadata metadata}))))

(defn log
  "Append one structured feedback entry and return it.

   Category must be one of :bug, :friction, :idea, or :docs. Metadata is optional."
  ([category message]
   (log category message {}))
  ([category message metadata]
   (validate! category message metadata)
   (let [path (feedback-path)
         file (io/file path)
         entry {:timestamp (str (java.time.Instant/now))
                :category category
                :message message
                :metadata metadata}]
     (locking write-lock
       (when-let [parent (.getParentFile file)]
         (.mkdirs parent))
       (spit file (str (pr-str entry) "\n") :append true))
     (assoc entry :path path))))

(def feedback-namespace
  {:short-docs "Structured logging for Spell bugs, friction, ideas, and documentation gaps."
   :docs {:guide "FEEDBACK — Structured dogfooding feedback for Spell.\n\n  (feedback/log category message)\n  (feedback/log category message metadata)\n\nCategories: :bug, :friction, :idea, :docs.\nEntries are appended as one EDN map per line to .spell/feedback.edn.\nSet SPELL_FEEDBACK_PATH to override the destination. Each entry automatically\nincludes an ISO-8601 :timestamp; include useful context such as :task, :agent,\nor :severity in the optional metadata map.\n\nUse feedback only for concrete observations encountered during the main task;\ndo not interrupt the task to search for possible issues.\n\nAll feedback/ calls are effect functions — quote them in the trailing expression.\n\nExample:\n  '(feedback/log :friction\n     \"Needed !describe before the expected option was discoverable\"\n     {:task \"configure agent\" :severity :low})"
          :log "Append a structured entry to the feedback log.\n\n(feedback/log category message)\n(feedback/log category message metadata)\n\ncategory: one of :bug, :friction, :idea, :docs\nmessage: non-blank string\nmetadata: optional map\n\nReturns the entry with :timestamp and :path."}
   :log log})
