(ns spell.feedback
  "Structured, append-only dogfooding feedback for Spell agents."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
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

(defn- serialize-entry! [entry]
  (try
    (let [serialized (pr-str entry)
          eof (Object.)]
      (with-open [reader (java.io.PushbackReader.
                          (java.io.StringReader. serialized))]
        (edn/read {:eof eof} reader)
        (when-not (identical? eof (edn/read {:eof eof} reader))
          (throw (ex-info "Serialized feedback entry contains trailing forms"
                          {}))))
      serialized)
    (catch RuntimeException cause
      (throw (ex-info "Feedback metadata must contain only EDN-readable values"
                      {:metadata (:metadata entry)}
                      cause)))))

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
                :metadata metadata}
         serialized (serialize-entry! entry)]
     (locking write-lock
       (when-let [parent (.getParentFile file)]
         (.mkdirs parent))
       (with-open [raf (java.io.RandomAccessFile. file "rw")
                   channel (.getChannel raf)
                   file-lock (.lock channel)]
         (.position channel (.size channel))
         (let [bytes (.getBytes (str serialized "\n")
                                java.nio.charset.StandardCharsets/UTF_8)]
           (loop [buffer (java.nio.ByteBuffer/wrap bytes)]
             (when (.hasRemaining buffer)
               (.write channel buffer)
               (recur buffer))))))
     (assoc entry :path path))))

(def feedback-namespace
  {:short-docs "Spell developer dogfooding. Its availability means you are helping improve Spell itself. Log concrete Spell bugs, surprising behavior, confusing interfaces, and missing or unclear prompting encountered during the task; run !describe feedback before first use."
   :docs {:guide "FEEDBACK — Structured developer dogfooding feedback for Spell.\n\n  (feedback/log category message)\n  (feedback/log category message metadata)\n\nThis namespace is gated. If it is available, the current run is dogfooding Spell\nand you should record concrete problems with Spell itself that arise while doing\nthe user's task. Do not search for issues, and do not report problems in the\nuser's own domain as Spell feedback.\n\nCategories: :bug, :friction, :idea, :docs.\nEntries are appended as one EDN map per line to .spell/feedback.edn.\nSet SPELL_FEEDBACK_PATH to override the destination. Each entry automatically\nincludes an ISO-8601 :timestamp. Include enough context to make the observation\nactionable, such as what you expected, what happened, and whether an ergonomic\nchange or clearer documentation or prompting might help.\n\nAll feedback/ calls are effect functions — quote them in the trailing expression.\n\nExample:\n  '(feedback/log :friction\n     \"The function behaved differently from its description\"\n     {:task \"configure agent\"\n      :expected \"the configured namespace to be available\"\n      :observed \"the function returned an unknown-namespace error\"\n      :suggestion \"clarify the prompt or make namespace loading more ergonomic\"})"
          :log "Append a structured entry to the feedback log.\n\n(feedback/log category message)\n(feedback/log category message metadata)\n\ncategory: one of :bug, :friction, :idea, :docs\nmessage: non-blank string\nmetadata: optional map\n\nReturns the entry with :timestamp and :path."}
   :log log})
