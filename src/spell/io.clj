(ns spell.io
  "I/O namespace for Spell agents.

   This namespace is OPT-IN — not included in the default agent.
   Agents that need I/O must explicitly include it in their config.

   Provides:
   - File operations: slurp, spit, read-file, write-file, str-replace, replace-lines
   - Directory operations: exists?, directory?, ls, mkdir, mkdirs, cwd
   - File manipulation: delete, copy, move, stat, temp-file
   - Process execution: sh, exec, env"
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.nio.file Files Paths StandardCopyOption CopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:dynamic *sh-timeout*
  "Timeout in seconds for sh/exec commands. Set to nil to disable."
  30)

;; =============================================================================
;; File reading/writing
;; =============================================================================

(defn slurp-file
  "Read entire file as string. Returns {:ok content} or {:error msg}."
  [path]
  (try
    {:ok (slurp path)}
    (catch java.io.FileNotFoundException _
      {:error (str "File not found: " path)})
    (catch Exception e
      {:error (str "Error reading file: " (.getMessage e))})))

(defn spit-file
  "Write string to file. Options: :append true to append instead of overwrite.
   Creates parent directories if needed. Returns {:ok path} or {:error msg}."
  ([path content] (spit-file path content {}))
  ([path content opts]
   (try
     (let [file (File. ^String path)
           parent (.getParentFile file)]
       (when (and parent (not (.exists parent)))
         (.mkdirs parent))
       (spit path content :append (:append opts false))
       {:ok path})
     (catch Exception e
       {:error (str "Error writing file: " (.getMessage e))}))))

(defn slurp-bytes
  "Read entire file as byte array. Returns {:ok bytes} or {:error msg}."
  [path]
  (try
    {:ok (Files/readAllBytes (Paths/get path (into-array String [])))}
    (catch java.nio.file.NoSuchFileException _
      {:error (str "File not found: " path)})
    (catch Exception e
      {:error (str "Error reading file: " (.getMessage e))})))

(defn- format-lines
  "Format lines with line numbers. lines is a seq of [line-num content] pairs."
  [pairs]
  (let [max-num (reduce max 0 (map first pairs))
        width   (count (str max-num))]
    (str/join "\n" (map (fn [[n line]]
                          (str (format (str "%" width "d") n) ": " line))
                        pairs))))

(defn read-file
  "Read file with line numbers. Returns a formatted string with numbered lines,
   or {:error msg}. Optionally takes start and end line numbers (1-indexed, inclusive)."
  ([path]
   (try
     (let [content (slurp path)]
       (if (empty? content)
         ""
         (let [lines (str/split-lines content)]
           (format-lines (map-indexed (fn [i line] [(inc i) line]) lines)))))
     (catch java.io.FileNotFoundException _
       {:error (str "File not found: " path)})
     (catch Exception e
       {:error (str "Error reading file: " (.getMessage e))})))
  ([path start end]
   (try
     (let [content (slurp path)]
       (if (empty? content)
         ""
         (let [lines (str/split-lines content)
               n (count lines)
               start (max 1 (min start n))
               end (max start (min end n))]
           (format-lines (map (fn [i] [(inc i) (nth lines i)])
                              (range (dec start) end))))))
     (catch java.io.FileNotFoundException _
       {:error (str "File not found: " path)})
     (catch Exception e
       {:error (str "Error reading file: " (.getMessage e))}))))

(defn write-file
  "Write content to file, creating parent directories if needed.
   Returns {:ok path} or {:error msg}."
  [path content]
  (spit-file path content))

;; =============================================================================
;; String replacement in files
;; =============================================================================

(defn- count-occurrences
  "Count non-overlapping occurrences of substring in string."
  [s substr]
  (loop [idx 0 cnt 0]
    (let [found (.indexOf ^String s ^String substr (int idx))]
      (if (neg? found)
        cnt
        (recur (+ found (count substr)) (inc cnt))))))

(defn str-replace
  "Replace a unique string in a file. old-str must appear exactly once.
   Returns {:ok path} or {:error msg}."
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
          (let [new-content (str/replace-first content old-str new-str)]
            (spit path new-content)
            {:ok path})))
      (catch java.io.FileNotFoundException _
        {:error (str "File not found: " path)})
      (catch Exception e
        {:error (str "Error: " (.getMessage e))}))))

(defn replace-lines
  "Replace lines start through end (1-indexed, inclusive) with new content.
   Empty string deletes the lines. Returns {:ok path} or {:error msg}."
  [path start end new-content]
  (try
    (let [content (slurp path)
          lines (str/split-lines content)
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
                          (str/split-lines new-content))
              result (str (str/join "\n" (concat before new-lines after))
                          (when trailing-newline? "\n"))]
          (spit path result)
          {:ok path})))
    (catch java.io.FileNotFoundException _
      {:error (str "File not found: " path)})
    (catch Exception e
      {:error (str "Error: " (.getMessage e))})))

;; =============================================================================
;; Directory operations
;; =============================================================================

(defn exists?
  "Check if path exists (file or directory)."
  [path]
  (.exists (File. ^String path)))

(defn directory?
  "Check if path is a directory."
  [path]
  (.isDirectory (File. ^String path)))

(defn ls
  "List directory contents. Returns vector of filenames or {:error msg}."
  [path]
  (try
    (let [dir (File. ^String path)]
      (if (.isDirectory dir)
        (vec (.list dir))
        {:error (str "Not a directory: " path)}))
    (catch Exception e
      {:error (str "Error listing directory: " (.getMessage e))})))

(defn mkdir
  "Create directory. Returns {:ok path} or {:error msg}."
  [path]
  (try
    (let [dir (File. ^String path)]
      (if (.mkdir dir)
        {:ok path}
        (if (.exists dir)
          {:error (str "Already exists: " path)}
          {:error (str "Failed to create directory: " path)})))
    (catch Exception e
      {:error (str "Error creating directory: " (.getMessage e))})))

(defn mkdirs
  "Create directory and all parent directories. Returns {:ok path} or {:error msg}."
  [path]
  (try
    (let [dir (File. ^String path)]
      (if (.mkdirs dir)
        {:ok path}
        (if (.exists dir)
          {:ok path}  ; Already exists is OK for mkdirs
          {:error (str "Failed to create directories: " path)})))
    (catch Exception e
      {:error (str "Error creating directories: " (.getMessage e))})))

(defn cwd
  "Get current working directory."
  []
  (System/getProperty "user.dir"))

;; =============================================================================
;; File manipulation
;; =============================================================================

(defn delete
  "Delete file or empty directory. Returns {:ok path} or {:error msg}."
  [path]
  (try
    (let [file (File. ^String path)]
      (if (.delete file)
        {:ok path}
        (if (not (.exists file))
          {:error (str "File not found: " path)}
          {:error (str "Failed to delete (directory not empty?): " path)})))
    (catch Exception e
      {:error (str "Error deleting: " (.getMessage e))})))

(defn copy
  "Copy file from src to dest. Returns {:ok dest} or {:error msg}."
  [src dest]
  (try
    (Files/copy (Paths/get src (into-array String []))
                (Paths/get dest (into-array String []))
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
    {:ok dest}
    (catch java.nio.file.NoSuchFileException _
      {:error (str "Source not found: " src)})
    (catch Exception e
      {:error (str "Error copying: " (.getMessage e))})))

(defn move
  "Move/rename file from src to dest. Returns {:ok dest} or {:error msg}."
  [src dest]
  (try
    (Files/move (Paths/get src (into-array String []))
                (Paths/get dest (into-array String []))
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
    {:ok dest}
    (catch java.nio.file.NoSuchFileException _
      {:error (str "Source not found: " src)})
    (catch Exception e
      {:error (str "Error moving: " (.getMessage e))})))

(defn stat
  "Get file metadata. Returns {:size :modified :readable :writable :executable} or {:error msg}."
  [path]
  (try
    (let [file (File. ^String path)]
      (if (.exists file)
        {:size (.length file)
         :modified (.lastModified file)
         :readable (.canRead file)
         :writable (.canWrite file)
         :executable (.canExecute file)
         :directory (.isDirectory file)}
        {:error (str "File not found: " path)}))
    (catch Exception e
      {:error (str "Error getting file info: " (.getMessage e))})))

(defn temp-file
  "Create a temporary file. Returns {:ok path} or {:error msg}.
   Optional prefix and suffix arguments."
  ([] (temp-file "spell" ".tmp"))
  ([prefix suffix]
   (try
     (let [path (Files/createTempFile prefix suffix (into-array FileAttribute []))]
       {:ok (str path)})
     (catch Exception e
       {:error (str "Error creating temp file: " (.getMessage e))}))))

;; =============================================================================
;; Process execution
;; =============================================================================

(defn sh
  "Execute shell command. Returns {:exit N :out \"...\" :err \"...\"}."
  [command & more]
  (let [command (if (seq more) (apply str command more) command)
        shell (or (System/getenv "SHELL") "bash")
        pb (ProcessBuilder. [shell "-c" command])
        process (.start pb)
        out-future (future (slurp (.getInputStream process)))
        err-future (future (slurp (.getErrorStream process)))
        timed-out? (if *sh-timeout*
                     (not (.waitFor process (long *sh-timeout*) TimeUnit/SECONDS))
                     (do (.waitFor process) false))]
    (if timed-out?
      (do (.destroyForcibly process)
          {:exit -1
           :out ""
           :err (str "Command timed out after " *sh-timeout* " seconds")})
      {:exit (.exitValue process)
       :out (str/trim @out-future)
       :err (str/trim @err-future)})))

(defn exec
  "Execute command directly (no shell). Takes command as vector of strings.
   Returns {:exit N :out \"...\" :err \"...\"}."
  [args]
  (let [pb (ProcessBuilder. ^java.util.List (vec args))
        process (.start pb)
        out-future (future (slurp (.getInputStream process)))
        err-future (future (slurp (.getErrorStream process)))
        timed-out? (if *sh-timeout*
                     (not (.waitFor process (long *sh-timeout*) TimeUnit/SECONDS))
                     (do (.waitFor process) false))]
    (if timed-out?
      (do (.destroyForcibly process)
          {:exit -1
           :out ""
           :err (str "Command timed out after " *sh-timeout* " seconds")})
      {:exit (.exitValue process)
       :out (str/trim @out-future)
       :err (str/trim @err-future)})))

(defn env
  "Get environment variable(s). No args returns all as map.
   With key, returns value or nil."
  ([] (into {} (System/getenv)))
  ([key] (System/getenv (str key))))

;; =============================================================================
;; Namespace definition for Spell
;; =============================================================================

(def io-namespace
  "The io/ namespace map for Spell agents."
  {:docs {;; File reading/writing
          :slurp "Read entire file as string. Returns {:ok content} or {:error msg}."
          :spit "Write to file. (spit path content) or (spit path content {:append true}). Returns {:ok path} or {:error msg}."
          :slurp-bytes "Read file as byte array. Returns {:ok bytes} or {:error msg}."
          :read-file "Read file with line numbers. Returns string \"1: line1\\n2: line2\\n...\" or {:error msg}. Optional start/end for range."
          :write-file "Write content to file. Creates parent dirs. Returns {:ok path} or {:error msg}."
          ;; String replacement
          :str-replace "Replace unique string in file. Returns {:ok path} or {:error msg}."
          :replace-lines "Replace line range. (replace-lines path start end new-content). Returns {:ok path} or {:error msg}."
          ;; Directory operations
          :exists? "Check if path exists."
          :directory? "Check if path is a directory."
          :ls "List directory contents. Returns vector of names or {:error msg}."
          :mkdir "Create directory. Returns {:ok path} or {:error msg}."
          :mkdirs "Create directory tree. Returns {:ok path} or {:error msg}."
          :cwd "Get current working directory."
          ;; File manipulation
          :delete "Delete file or empty directory. Returns {:ok path} or {:error msg}."
          :copy "Copy file. (copy src dest). Returns {:ok dest} or {:error msg}."
          :move "Move/rename file. (move src dest). Returns {:ok dest} or {:error msg}."
          :stat "Get file info. Returns {:size :modified :readable :writable :executable :directory} or {:error msg}."
          :temp-file "Create temp file. Returns {:ok path} or {:error msg}."
          ;; Process execution
          :sh "Execute shell command. Returns {:exit N :out \"...\" :err \"...\"}."
          :exec "Execute command directly (no shell). (exec [\"cmd\" \"arg1\" ...]). Returns {:exit N :out \"...\" :err \"...\"}."
          :env "Get env var(s). (env) returns all as map. (env \"PATH\") returns value or nil."}
   ;; File reading/writing
   :slurp slurp-file
   :spit spit-file
   :slurp-bytes slurp-bytes
   :read-file read-file
   :write-file write-file
   ;; String replacement
   :str-replace str-replace
   :replace-lines replace-lines
   ;; Directory operations
   :exists? exists?
   :directory? directory?
   :ls ls
   :mkdir mkdir
   :mkdirs mkdirs
   :cwd cwd
   ;; File manipulation
   :delete delete
   :copy copy
   :move move
   :stat stat
   :temp-file temp-file
   ;; Process execution
   :sh sh
   :exec exec
   :env env})
