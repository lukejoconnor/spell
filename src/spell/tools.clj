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
  "Read a file and return a sorted map of {line-number content-string ...}.
   Line numbers are 1-indexed. Optionally takes start and end line numbers
   (1-indexed, inclusive) to read a range. Returns {:error message} on failure."
  ([path]
   (try
     (let [content (slurp path)]
       (if (empty? content)
         (sorted-map)
         (let [lines (clojure.string/split-lines content)]
           (into (sorted-map)
                 (map-indexed (fn [i line] [(inc i) line]) lines)))))
     (catch java.io.FileNotFoundException _
       {:error (str "File not found: " path)})
     (catch Exception e
       {:error (str "Error reading file: " (.getMessage e))})))
  ([path start end]
   (try
     (let [content (slurp path)]
       (if (empty? content)
         (sorted-map)
         (let [lines (clojure.string/split-lines content)
               n (count lines)
               start (max 1 (min start n))
               end (max start (min end n))]
           (into (sorted-map)
                 (map (fn [i] [(inc i) (nth lines i)])
                      (range (dec start) end))))))
     (catch java.io.FileNotFoundException _
       {:error (str "File not found: " path)})
     (catch Exception e
       {:error (str "Error reading file: " (.getMessage e))}))))

(def read-file-tool
  "Tool metadata for read-file."
  {:name 'read-file
   :fn   read-file
   :doc  "Read a file with line numbers. Returns a sorted map of {line-number \"content\" ...}, or {:error msg}.
(read-file \"src/main.clj\")       ; => {1 \"(ns main)\" 2 \"  (:require ...)\" ...}
(read-file \"src/main.clj\" 10 15) ; => {10 \"(defn foo\" 11 \"  [x]\" ...} — lines 10-15 only
(get (read-file \"f.txt\") 1)      ; => first line as string"})

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
  (cond
    (nil? old-str)
    {:error "old-str cannot be nil"}

    (nil? new-str)
    {:error "new-str cannot be nil"}

    :else
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
        {:error (str "Error: " (.getMessage e))}))))

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
;; replace-lines
;; =============================================================================

(defn replace-lines
  "Replace lines start through end (1-indexed, inclusive) with new content.
   If new-content is empty string, deletes the lines.
   Returns {:ok path} on success, {:error message} on failure."
  [path start end new-content]
  (try
    (let [content (slurp path)
          lines (clojure.string/split-lines content)
          n (count lines)
          trailing-newline? (and (pos? (count content))
                                (= \newline (last content)))]
      (cond
        (or (< start 1) (> start n))
        {:error (str "Start line " start " out of range (file has " n " lines)")}

        (or (< end start) (> end n))
        {:error (str "End line " end " out of range (start=" start ", file has " n " lines)")}

        :else
        (let [before (subvec (vec lines) 0 (dec start))
              after (subvec (vec lines) end)
              new-lines (if (empty? new-content)
                          []
                          (clojure.string/split-lines new-content))
              result (str (clojure.string/join "\n" (concat before new-lines after))
                          (when trailing-newline? "\n"))]
          (spit path result)
          {:ok path})))
    (catch java.io.FileNotFoundException _
      {:error (str "File not found: " path)})
    (catch Exception e
      {:error (str "Error: " (.getMessage e))})))

(def replace-lines-tool
  "Tool metadata for replace-lines."
  {:name 'replace-lines
   :fn   replace-lines
   :doc  "Replace a range of lines in a file. Takes path, start line, end line (1-indexed, inclusive), and new content string.
Use with read-file: read to see line numbers, then replace the target range.
Returns {:ok path} on success, {:error message} on failure.
(replace-lines \"main.py\" 5 7 \"    x = fixed_value\\n    return x\")  ; replace lines 5-7
(replace-lines \"main.py\" 3 3 \"\")  ; delete line 3"})

;; =============================================================================
;; All tools
;; =============================================================================

(def file-tools
  "All file manipulation tools."
  [read-file-tool write-file-tool str-replace-tool replace-lines-tool])

;; Legacy: kept for backwards compatibility with tests
(def default-tools
  "Default tool set (legacy, use registries instead)."
  [read-name-tool bash-tool read-file-tool write-file-tool str-replace-tool replace-lines-tool])
