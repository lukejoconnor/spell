(ns spell.io
  "I/O namespace for Spell agents.

   This namespace is OPT-IN — not included in the default agent.
   Agents that need I/O must explicitly include it in their config.

   Provides:
   - File operations: slurp, spit, read-file, read-lines, write-file, str-replace, replace-lines
   - Directory operations: exists?, directory?, ls, mkdir, mkdirs, cwd
   - File manipulation: delete, copy, move, stat, temp-file
   - Process execution: sh, exec, env"
  (:require [clojure.string :as str]
            [spell.comm :as comm])
  (:import [java.io File]
           [java.nio.file FileSystems Files Paths StandardCopyOption CopyOption
                          StandardWatchEventKinds WatchEvent]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]
           [com.sun.nio.file SensitivityWatchEventModifier]))

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

(defn read-lines
  "Read file as a vector of raw line strings with :spell/line-offset metadata.
   Returns (with-meta [\"line1\" \"line2\" ...] {:spell/line-offset 1}) or {:error msg}.
   Optionally takes start and end line numbers (1-indexed, inclusive, clamped to bounds)."
  ([path]
   (try
     (let [content (slurp path)]
       (if (empty? content)
         (with-meta [] {:spell/line-offset 1})
         (with-meta (str/split-lines content) {:spell/line-offset 1})))
     (catch java.io.FileNotFoundException _
       {:error (str "File not found: " path)})
     (catch Exception e
       {:error (str "Error reading file: " (.getMessage e))})))
  ([path start end]
   (try
     (let [content (slurp path)]
       (if (empty? content)
         (with-meta [] {:spell/line-offset (max 1 start)})
         (let [lines (str/split-lines content)
               n (count lines)
               start (max 1 (min start n))
               end (max start (min end n))]
           (with-meta (subvec (vec lines) (dec start) end)
                      {:spell/line-offset start}))))
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

(defn- replace-first-literal
  "Replace first occurrence of old with new in s. Pure string ops, no regex."
  [^String s ^String old ^String new-str]
  (let [idx (.indexOf s old)]
    (if (neg? idx)
      s
      (str (subs s 0 idx) new-str (subs s (+ idx (count old)))))))

(defn str-replace
  "Replace a string in a file. By default, old-str must appear exactly once.
   With {:all true}, replaces all occurrences (must appear at least once).
   Returns {:ok path} or {:error msg}."
  ([path old-str new-str] (str-replace path old-str new-str {}))
  ([path old-str new-str opts]
   (cond
     (nil? old-str)
     {:error "old-str cannot be nil"}

     (nil? new-str)
     {:error "new-str cannot be nil"}

     (= "" old-str)
     {:error "old-str cannot be empty"}

     :else
     (try
       (let [content (slurp path)
             occurrences (count-occurrences content old-str)]
         (cond
           (zero? occurrences)
           {:error (str "String not found in file: " (pr-str old-str))}

           (and (> occurrences 1) (not (:all opts)))
           {:error (str "String appears " occurrences " times (must be unique): " (pr-str old-str))}

           :else
           (let [new-content (if (:all opts)
                               (.replace ^String content ^String old-str ^String new-str)
                               (replace-first-literal content old-str new-str))]
             (spit path new-content)
             {:ok path})))
       (catch java.io.FileNotFoundException _
         {:error (str "File not found: " path)})
       (catch Exception e
         {:error (str "Error: " (.getMessage e))})))))

(defn- validate-edit
  "Validate a single [start end content] edit against n lines. Returns error string or nil."
  [n [start end _]]
  (cond
    (or (< start 1) (> start n))
    (str "Start line " start " out of range (file has " n " lines)")

    (or (< end start) (> end n))
    (str "End line " end " out of range (start=" start ", file has " n " lines)")))

(defn- edits-overlap?
  "Check if any two edits in sorted seq overlap."
  [edits]
  (some (fn [[[_ end1 _] [start2 _ _]]]
          (<= start2 end1))
        (partition 2 1 edits)))

(defn- apply-edits
  "Apply edits to lines vector in reverse order (highest line numbers first).
   Each edit is [start end content]. Returns new lines vector."
  [lines edits]
  (reduce (fn [ls [start end new-content]]
            (let [before (subvec ls 0 (dec start))
                  after (subvec ls end)
                  new-lines (if (empty? new-content)
                              []
                              (str/split-lines new-content))]
              (vec (concat before new-lines after))))
          lines
          (reverse (sort-by first edits))))

(defn replace-lines
  "Replace lines in a file (1-indexed, inclusive). Two forms:
   (replace-lines path start end content) — single edit
   (replace-lines path [[start end content] ...]) — multiple edits, applied atomically
   Empty content string deletes the lines. Returns {:ok path} or {:error msg}."
  ([path edits]
   (try
     (let [content (slurp path)
           lines (vec (str/split-lines content))
           n (count lines)
           trailing-newline? (and (pos? (count content))
                                   (= \newline (last content)))
           errors (keep (partial validate-edit n) edits)]
       (cond
         (seq errors)
         {:error (first errors)}

         (edits-overlap? (sort-by first edits))
         {:error "Edits have overlapping line ranges"}

         :else
         (let [new-lines (apply-edits lines edits)
               result (str (str/join "\n" new-lines)
                           (when trailing-newline? "\n"))]
           (spit path result)
           {:ok path})))
     (catch java.io.FileNotFoundException _
       {:error (str "File not found: " path)})
     (catch Exception e
       {:error (str "Error: " (.getMessage e))})))
  ([path start end new-content]
   (replace-lines path [[start end new-content]])))

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
;; File watching
;; =============================================================================

(def ^:private watch-kinds
  {StandardWatchEventKinds/ENTRY_CREATE :create
   StandardWatchEventKinds/ENTRY_MODIFY :modify
   StandardWatchEventKinds/ENTRY_DELETE :delete})

(defn- watch-dir
  "Watch a directory for file changes. Blocks until events occur or timeout.
   Returns {:ok [{:kind :create|:modify|:delete :name \"file.txt\"} ...]}
   or {:timeout true} if timeout-ms elapses. Without timeout, blocks indefinitely."
  ([path] (watch-dir path nil))
  ([path timeout-ms]
   (try
     (let [dir (Paths/get path (into-array String []))]
       (with-open [watcher (.newWatchService (FileSystems/getDefault))]
         (.register dir watcher
                    (into-array [StandardWatchEventKinds/ENTRY_CREATE
                                 StandardWatchEventKinds/ENTRY_MODIFY
                                 StandardWatchEventKinds/ENTRY_DELETE])
                    (into-array [SensitivityWatchEventModifier/HIGH]))
         (let [key (if timeout-ms
                     (.poll watcher (long timeout-ms) TimeUnit/MILLISECONDS)
                     (.take watcher))]
           (if (nil? key)
             {:timeout true}
             (let [events (mapv (fn [^WatchEvent e]
                                  {:kind (get watch-kinds (.kind e) :unknown)
                                   :name (str (.context e))})
                                (.pollEvents key))]
               (.reset key)
               {:ok events})))))
     (catch java.nio.file.NoSuchFileException _
       {:error (str "Directory not found: " path)})
     (catch java.nio.file.NotDirectoryException _
       {:error (str "Not a directory: " path)})
     (catch Exception e
       {:error (str "Error watching directory: " (.getMessage e))}))))

(defn event-send
  "Run blocking event-fn in background future. When it returns {:ok val},
   send val to handle with from-tag as sender. When it returns {:abort ...},
   do nothing (silently discard). On exception, sends {:error msg} to handle
   and logs to stderr. Returns nil immediately."
  [event-fn handle from-tag]
  (future
    (binding [comm/*current-handle* from-tag]
      (try
        (let [result (event-fn)]
          (cond
            (:ok result) (comm/send (:ok result) handle)
            (:abort result) nil))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "event-send error for " handle ": " (.getMessage e))))
          (comm/send {:error (.getMessage e)} handle)))))
  nil)

(defn watch-send
  "Watch directory in background, send events to handle when they occur.
   Returns nil immediately. When file events arrive, sends a message to
   handle with :from :watch-send. Does nothing on timeout or error."
  ([path handle] (watch-send path handle nil))
  ([path handle timeout-ms]
   (event-send #(watch-dir path timeout-ms) handle :watch-send)))

;; =============================================================================
;; Process execution
;; =============================================================================

(defn- resolve-timeout-seconds
  "Normalize timeout input to seconds or nil.
   nil and 0 disable timeout. Positive values are rounded up to whole seconds."
  [timeout]
  (cond
    (nil? timeout) nil
    (not (number? timeout))
    (throw (ex-info "sh: :timeout must be numeric" {:timeout timeout}))
    (neg? (double timeout))
    (throw (ex-info "sh: :timeout must be >= 0" {:timeout timeout}))
    (zero? (double timeout)) nil
    :else (long (Math/ceil (double timeout)))))

(defn sh
  "Execute shell command. Returns {:exit N :out \"...\" :err \"...\"}.
   Optional first extra arg may be an opts map with :timeout seconds.
   :timeout 0 disables timeout for this call."
  [command & more]
  (let [[opts parts] (if (and (seq more) (map? (first more)))
                       [(first more) (rest more)]
                       [nil more])
        timeout-seconds (resolve-timeout-seconds
                          (if (contains? opts :timeout)
                            (:timeout opts)
                            *sh-timeout*))
        command (if (seq parts) (apply str command parts) command)
        shell (or (System/getenv "SHELL") "bash")
        pb (ProcessBuilder. [shell "-c" command])
        process (.start pb)
        out-future (future (slurp (.getInputStream process)))
        err-future (future (slurp (.getErrorStream process)))
        timed-out? (if timeout-seconds
                     (not (.waitFor process timeout-seconds TimeUnit/SECONDS))
                     (do (.waitFor process) false))]
    (if timed-out?
      (do (.destroyForcibly process)
          {:exit -1
           :out ""
           :err (str "Command timed out after " timeout-seconds " seconds")})
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
  {:guide "IO — File operations, process execution, and file watching (effect namespace).

  (io/read-file path)              — read file with line numbers as string
  (io/read-lines path)             — read file as vector of line strings
  (io/slurp path)                  — read entire file as string
  (io/write-file path content)     — write content to file (creates dirs)
  (io/spit path content)           — write to file; opts {:append true}
  (io/str-replace path old new)    — replace string in file; opts {:all true}
  (io/replace-lines path start end content) — replace line range
  (io/sh command)                  — execute shell command, returns {:exit :out :err}
  (io/sh command {:timeout 10})    — per-call timeout override in seconds (0 disables)
  (io/exec [cmd arg1 ...])         — execute command directly (no shell)
  (io/ls path)                     — list directory contents
  (io/exists? path)                — check if path exists
  (io/stat path)                   — get file info map
  (io/delete path)                 — delete file or empty directory
  (io/copy src dest)               — copy file
  (io/move src dest)               — move/rename file
  (io/mkdir path)                  — create directory
  (io/mkdirs path)                 — create directory tree
  (io/cwd)                         — get current working directory
  (io/env)                         — get all env vars; (io/env \"PATH\") for one
  (io/temp-file)                   — create temp file
  (io/watch-send path handle)      — watch directory, send events to handle

All io/ calls are effect functions — quote them in the trailing expression.
Use (describe io :fn-name) for detailed docs on any function."
   :docs {;; File reading/writing
          :slurp "Read entire file as string. Returns {:ok content} or {:error msg}."
          :spit "Write to file. (spit path content) or (spit path content {:append true}). Returns {:ok path} or {:error msg}."
          :slurp-bytes "Read file as byte array. Returns {:ok bytes} or {:error msg}."
          :read-file "Read file with line numbers. Returns string \"1: line1\\n2: line2\\n...\" or {:error msg}. Optional start/end for range."
          :read-lines "Read file as vector of raw line strings with line-offset metadata. Displays with line numbers via call-now; evaluates to raw vector. Optional start/end for range."
          :write-file "Write content to file. Creates parent dirs. Returns {:ok path} or {:error msg}."
          ;; String replacement
          :str-replace "Replace string in file. Unique by default; (str-replace path old new {:all true}) replaces all. Returns {:ok path} or {:error msg}."
          :replace-lines "Replace lines. (replace-lines path start end content) for one edit, (replace-lines path [[s1 e1 c1] [s2 e2 c2]]) for multiple (atomic, no drift). Returns {:ok path} or {:error msg}."
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
          ;; File watching
          :watch-send "Watch directory in background, send events to handle when they occur. (watch-send path handle) or (watch-send path handle timeout-ms). Returns nil immediately. Message arrives with :from :watch-send."
          ;; Process execution
          :sh "Execute shell command. (sh cmd) or (sh cmd {:timeout secs}); :timeout 0 disables timeout for this call. Returns {:exit N :out \"...\" :err \"...\"}."
          :exec "Execute command directly (no shell). (exec [\"cmd\" \"arg1\" ...]). Returns {:exit N :out \"...\" :err \"...\"}."
          :env "Get env var(s). (env) returns all as map. (env \"PATH\") returns value or nil."}
   :detail
   {:read-file
    "Read file with line numbers. Returns a formatted string or {:error msg}.

(io/read-file path)
(io/read-file path start end)
  path: file path
  start, end: 1-indexed, inclusive line range (clamped to file bounds)

Returns a string with numbered lines: \"1: first line\\n2: second line\\n...\"
Returns {:error msg} on failure.

Use the range form to extract a subset for a child:
  '(call-now code (io/read-file \"main.py\" 40 60))

For raw content without line numbers, use io/slurp:
  (:ok (io/slurp \"file.txt\"))"

    :read-lines
    "Read file as a vector of raw line strings with line-offset metadata.

(io/read-lines path)
(io/read-lines path start end)
  path: file path
  start, end: 1-indexed, inclusive (clamped to file bounds)

Returns a vector of raw strings with metadata {:spell/line-offset N}.
When serialized via call-now, displays with line numbers for readability,
but the binding evaluates to the raw vector.

  '(call-now code (io/read-lines \"main.py\" 40 60))
  ;; child sees numbered display, but code is a plain vector
  (nth code 0)           ;; first line as string
  (subvec code 0 5)      ;; first 5 lines
  (count code)            ;; number of lines"

    :replace-lines
    "Replace lines in a file (1-indexed, inclusive). Two forms:

(io/replace-lines path start end content)
  Single edit. Replaces lines start through end with content string.

(io/replace-lines path [[start end content] ...])
  Multiple edits, applied atomically. Line numbers refer to the ORIGINAL file —
  no drift between edits. Edits must not have overlapping ranges.

Empty content string deletes the lines. Returns {:ok path} or {:error msg}.

Example — single edit:
  (io/replace-lines \"main.py\" 42 44 \"    x = fixed_value\\n    return x\")

Example — multiple edits (no drift):
  (io/replace-lines \"main.py\" [[10 12 \"new block 1\"] [25 30 \"new block 2\"]])"

    :str-replace
    "Replace a string in a file. By default, old-str must appear exactly once.

(io/str-replace path old-str new-str)
(io/str-replace path old-str new-str {:all true})

Without :all, errors if old-str appears 0 or >1 times (uniqueness check).
With {:all true}, replaces all occurrences (must appear at least once).
Returns {:ok path} or {:error msg}.

Example:
  (io/str-replace \"config.json\" \"localhost\" \"production.example.com\")"

    :sh
    "Execute a shell command. Returns {:exit N :out \"...\" :err \"...\"}.

(io/sh command)
(io/sh command {:timeout secs})
(io/sh part1 part2 ...)

command: a string passed to the shell via `sh -c`.
Multiple arguments are concatenated into a single string:
  (io/sh \"grep -n 'pattern' \" path)  ;; args joined: \"grep -n 'pattern' /tmp/foo\"

Timeout:
  - Default comes from runtime config (30 seconds by default)
  - Per-call override: (io/sh \"cmd\" {:timeout 5})
  - Disable timeout for one call: (io/sh \"cmd\" {:timeout 0})

Timeout returns {:exit -1 :err \"...timed out...\"}."

    :exec
    "Execute command directly without a shell. Takes a vector of strings.

(io/exec [\"cmd\" \"arg1\" \"arg2\"])

Returns {:exit N :out \"...\" :err \"...\"}.
Use when you need precise argument handling without shell interpretation."

    :watch-send
    "Watch a directory in the background and send file events to an agent handle.

(io/watch-send path handle)
(io/watch-send path handle timeout-ms)

Returns nil immediately. When file events occur in the watched directory,
sends a message to handle with :from :watch-send. The message value is a
vector of {:kind :create|:modify|:delete :name \"filename\"} maps.

Does nothing on timeout or error.

Example:
  '(do (io/watch-send \"./src\" (agents/current-handle) 10000)
       (agents/ask (agents/current-handle)))
  ;; next turn: message with file change events"

    :spit
    "Write to file with options. Creates parent directories if needed.

(io/spit path content)
(io/spit path content {:append true})

Returns {:ok path} or {:error msg}. Use :append to add to existing file."

    :slurp
    "Read entire file as raw string (no line numbers).

(io/slurp path)

Returns {:ok content} or {:error msg}.
The content is the raw file contents. For numbered lines, use io/read-file."}
   ;; File reading/writing
   :slurp slurp-file
   :spit spit-file
   :slurp-bytes slurp-bytes
   :read-file read-file
   :read-lines read-lines
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
   ;; File watching
   :watch-send watch-send
   ;; Process execution
   :sh sh
   :exec exec
   :env env})
