(ns spell.tools
  "File manipulation tools for Spell agents.

   These tools provide reliable file I/O that avoids the escaping issues
   inherent in bash echo/heredoc approaches.")

;; =============================================================================
;; read-file
;; =============================================================================

(defn read-file
  "Read the contents of a file and return as a string.
   Returns {:ok content} on success, {:error message} on failure."
  [path]
  (try
    {:ok (slurp path)}
    (catch java.io.FileNotFoundException _
      {:error (str "File not found: " path)})
    (catch Exception e
      {:error (str "Error reading file: " (.getMessage e))})))

(def read-file-tool
  "Tool metadata for read-file."
  {:name 'read-file
   :fn   read-file
   :doc  "Read a file's contents. Takes a path string, returns {:ok content} or {:error message}.
(read-file \"src/main.clj\")  ; => {:ok \"(ns main)...\"}
(:ok (read-file \"config.edn\"))  ; => file contents as string"})

;; =============================================================================
;; write-file
;; =============================================================================

(defn write-file
  "Write content to a file, creating parent directories if needed.
   Returns {:ok path} on success, {:error message} on failure."
  [path content]
  (try
    (let [file (java.io.File. path)
          parent (.getParentFile file)]
      (when (and parent (not (.exists parent)))
        (.mkdirs parent))
      (spit path content)
      {:ok path})
    (catch Exception e
      {:error (str "Error writing file: " (.getMessage e))})))

(def write-file-tool
  "Tool metadata for write-file."
  {:name 'write-file
   :fn   write-file
   :doc  "Write content to a file. Takes path and content strings. Creates parent dirs if needed.
Returns {:ok path} on success, {:error message} on failure.
(write-file \"output.txt\" \"Hello, world!\")  ; => {:ok \"output.txt\"}
(write-file \"new/dir/file.txt\" \"content\")  ; creates new/dir/ first"})

;; =============================================================================
;; str-replace
;; =============================================================================

(defn- count-occurrences
  "Count non-overlapping occurrences of substring in string."
  [s substr]
  (loop [idx 0
         cnt 0]
    (let [found (.indexOf s substr idx)]
      (if (neg? found)
        cnt
        (recur (+ found (count substr)) (inc cnt))))))

(defn str-replace
  "Find a unique string in a file and replace it with another string.
   The old-str must appear exactly once in the file (uniqueness check).
   Returns {:ok path} on success, {:error message} on failure.

   This is the pattern used by SWE-agent and Aider for reliable file editing."
  [path old-str new-str]
  (try
    (let [content (slurp path)
          occurrences (count-occurrences content old-str)]
      (cond
        (zero? occurrences)
        {:error (str "String not found in file: " (pr-str old-str))}

        (> occurrences 1)
        {:error (str "String appears " occurrences " times (must be unique): " (pr-str old-str))}

        :else
        (let [new-content (clojure.string/replace-first content old-str new-str)]
          (spit path new-content)
          {:ok path})))
    (catch java.io.FileNotFoundException _
      {:error (str "File not found: " path)})
    (catch Exception e
      {:error (str "Error: " (.getMessage e))})))

(def str-replace-tool
  "Tool metadata for str-replace."
  {:name 'str-replace
   :fn   str-replace
   :doc  "Replace a unique string in a file. Takes path, old-str, new-str.
The old-str must appear exactly once (uniqueness enforced for safety).
Returns {:ok path} on success, {:error message} on failure.
(str-replace \"main.clj\" \"(def x 1)\" \"(def x 42)\")  ; => {:ok \"main.clj\"}

Tip: include surrounding context to ensure uniqueness:
  Instead of: (str-replace f \"x\" \"y\")  ; might match multiple times
  Use: (str-replace f \"(def x\" \"(def y\")  ; more specific"})

;; =============================================================================
;; All file tools
;; =============================================================================

(def file-tools
  "All file manipulation tools."
  [read-file-tool write-file-tool str-replace-tool])
