(ns spell.tools
  "Tool implementations for Spell agents.

   File tools provide reliable file I/O that avoids the escaping issues
   inherent in bash echo/heredoc approaches.
   Bash and read-name tools provide shell access and name lookup."
  (:import [java.util.concurrent TimeUnit]))

;; =============================================================================
;; read-name
;; =============================================================================

(defn read-name
  "Read the name from name.txt file."
  []
  (try
    (clojure.string/trim (slurp "name.txt"))
    (catch java.io.FileNotFoundException _
      (throw (ex-info "name.txt not found" {:file "name.txt"})))))

(def read-name-tool
  "Tool metadata for read-name."
  {:name 'read-name
   :fn   read-name
   :doc  "Returns the name from name.txt. Takes no arguments. Use (read-name) to get the name."})

;; =============================================================================
;; bash
;; =============================================================================

(def ^:dynamic *bash-timeout*
  "Timeout in seconds for bash commands. Set to nil to disable."
  30)

(defn run-bash
  "Execute a bash command string. Returns {:exit N :out \"...\" :err \"...\"}."
  [command]
  (let [pb (ProcessBuilder. ["bash" "-c" command])
        process (.start pb)
        out-future (future (slurp (.getInputStream process)))
        err-future (future (slurp (.getErrorStream process)))
        timed-out? (if *bash-timeout*
                     (not (.waitFor process (long *bash-timeout*) TimeUnit/SECONDS))
                     (do (.waitFor process) false))]
    (if timed-out?
      (do (.destroyForcibly process)
          {:exit -1
           :out ""
           :err (str "Command timed out after " *bash-timeout* " seconds")})
      {:exit (.exitValue process)
       :out (clojure.string/trim @out-future)
       :err (clojure.string/trim @err-future)})))

(def bash-tool
  "Tool metadata for bash."
  {:name 'bash
   :fn   run-bash
   :doc  "Execute a shell command. Takes a command string, returns a map with :exit (integer), :out (stdout string), :err (stderr string).
(bash \"ls -la\")       ; => {:exit 0 :out \"...\" :err \"\"}
(:out (bash \"pwd\"))   ; => \"/current/dir\"
(:exit (bash \"false\")) ; => 1"})

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
;; All tools
;; =============================================================================

(def file-tools
  "All file manipulation tools."
  [read-file-tool write-file-tool str-replace-tool])

(def default-tools
  "Default tool set for the standard llm function."
  [read-name-tool bash-tool read-file-tool write-file-tool str-replace-tool])
