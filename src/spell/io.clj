(ns spell.io
  "I/O namespace for Spell agents.

   This namespace is effectful and security-sensitive.
   The built-in default agent includes it; stricter agent profiles can omit it.

   Provides:
   - File operations: slurp, spit, read-file, read-lines, write-file, str-replace, replace-lines
   - Directory operations: exists?, directory?, ls, mkdir, mkdirs, cwd
   - File manipulation: delete, copy, move, stat, temp-file
   - Read-only exploration: grep, glob, git
   - Process execution: sh, exec, env"
  (:require [clojure.string :as str]
            [spell.runtime :as runtime])
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
  "Timeout in seconds for sh/exec commands. Set to nil to disable.
   Overridable via SPELL_SH_TIMEOUT env var."
  (if-let [env-timeout (System/getenv "SPELL_SH_TIMEOUT")]
    (Long/parseLong env-timeout)
    30))

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
   or {:error msg}. Optionally takes start and end line numbers (1-indexed, half-open [start, end))."
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
               end (max start (min end (inc n)))]
           (format-lines (map (fn [i] [(inc i) (nth lines i)])
                              (range (dec start) (dec end)))))))
     (catch java.io.FileNotFoundException _
       {:error (str "File not found: " path)})
     (catch Exception e
       {:error (str "Error reading file: " (.getMessage e))}))))

(defn read-lines
  "Read file as a vector of raw line strings with :spell/line-offset metadata.
   Returns (with-meta [\"line1\" \"line2\" ...] {:spell/line-offset 1}) or {:error msg}.
   Optionally takes start and end line numbers (1-indexed, half-open [start, end))."
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
               end (max start (min end (inc n)))]
           (with-meta (subvec (vec lines) (dec start) (dec end))
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
  "Validate a single [start end content] edit against n lines (half-open). Returns error string or nil."
  [n [start end _]]
  (cond
    (or (< start 1) (> start (inc n)))
    (str "Start line " start " out of range (file has " n " lines)")

    (or (< end start) (> end (inc n)))
    (str "End line " end " out of range (start=" start ", file has " n " lines)")))

(defn- edits-overlap?
  "Check if any two edits in sorted seq overlap (half-open ranges)."
  [edits]
  (some (fn [[[_ end1 _] [start2 _ _]]]
          (< start2 end1))
        (partition 2 1 edits)))

(defn- apply-edits
  "Apply edits to lines vector in reverse order (highest line numbers first).
   Each edit is [start end content] with half-open range [start, end). Returns new lines vector."
  [lines edits]
  (reduce (fn [ls [start end new-content]]
            (let [before (subvec ls 0 (dec start))
                  after (subvec ls (dec end))
                  new-lines (if (empty? new-content)
                              []
                              (str/split-lines new-content))]
              (vec (concat before new-lines after))))
          lines
          (reverse (sort-by first edits))))

(defn replace-lines
  "Replace lines in a file (1-indexed, half-open [start, end)). Two forms:
   (replace-lines path start end content) — single edit, replaces lines start..end-1
   (replace-lines path [[start end content] ...]) — multiple edits, applied atomically
   start == end inserts before that line. Empty content string deletes the range.
   Returns {:ok path} or {:error msg}."
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
    (binding [runtime/*current-handle* from-tag]
      (try
        (let [result (event-fn)]
          (cond
            (:ok result) (runtime/send handle (:ok result))
            (:abort result) nil))
        (catch Exception e
          (binding [*out* *err*]
            (println (str "event-send error for " handle ": " (.getMessage e))))
          (runtime/send handle {:error (.getMessage e)})))))
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

(defn- ensure-string-args
  "Throw an informative ex-info if any argument is not a string."
  [label args extra-msg]
  (when-let [bad (first (remove string? args))]
    (throw (ex-info (str label " must be strings, got "
                         (pr-str bad)
                         extra-msg)
                    {:args (vec args)}))))

(defn- normalize-natural-number-opt
  "Normalize a natural-number CLI option to a string, or throw ex-info."
  [label value]
  (cond
    (integer? value)
    (do (when (neg? value)
          (throw (ex-info (str label " must be a non-negative integer, got "
                               (pr-str value))
                          {:value value})))
        (str value))

    (string? value)
    (if (re-matches #"\d+" value)
      value
      (throw (ex-info (str label " must be a non-negative integer, got "
                           (pr-str value))
                      {:value value})))

    :else
    (throw (ex-info (str label " must be a non-negative integer, got "
                         (pr-str value))
                    {:value value}))))

(defn- shell-quote
  "POSIX-safe single-quote escaping for shell arguments."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn sh
  "Execute shell command. Returns {:exit N :out \"...\" :err \"...\"}.
   Optional last arg may be an opts map with :timeout seconds.
   :timeout 0 disables timeout for this call.
   When SPELL_DOCKER_CONTAINER env var is set, commands execute inside
  that Docker container via `docker exec`."
  [command & more]
  (let [[opts parts] (if (and (seq more) (map? (last more)))
                       [(last more) (butlast more)]
                       [nil more])
        _ (ensure-string-args "io/sh: all command segments"
                              (cons command parts)
                              ". For options, pass a map as the last argument: {:timeout 60}")
        timeout-seconds (resolve-timeout-seconds
                          (if (contains? opts :timeout)
                            (:timeout opts)
                            *sh-timeout*))
        command (if (seq parts) (apply str command parts) command)
        docker-container (System/getenv "SPELL_DOCKER_CONTAINER")
        docker-workdir (System/getenv "SPELL_DOCKER_WORKDIR")
        cmd-vec (if docker-container
                  (cond-> ["docker" "exec" "-i"]
                    docker-workdir (into ["-w" docker-workdir])
                    true (into [docker-container "bash" "-c" command]))
                  (let [shell (or (System/getenv "SHELL") "bash")]
                    [shell "-c" command]))
        pb (ProcessBuilder. cmd-vec)
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
  (ensure-string-args "io/exec: all argv entries" args "")
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

(defn grep
  "Search file contents for a pattern. Returns {:exit N :out \"...\" :err \"...\"}."
  ([pattern path] (grep pattern path {}))
  ([pattern path opts]
   (ensure-string-args "io/grep: pattern and path" [pattern path] "")
   (when-let [include (:include opts)]
     (ensure-string-args "io/grep: :include" [include] ""))
   (let [context (when (contains? opts :context)
                   (normalize-natural-number-opt "io/grep: :context" (:context opts)))
         max-count (when (contains? opts :max-count)
                     (normalize-natural-number-opt "io/grep: :max-count" (:max-count opts)))
         flags (cond-> ["-rnH"]
                 (:ignore-case opts) (conj "-i")
                 context (conj (str "-C" context))
                 max-count (conj (str "-m" max-count)))
         grep-cmd (str "grep " (str/join " " flags)
                       " -e " (shell-quote pattern))
         cmd (if-let [include (:include opts)]
               (str "find " (shell-quote path)
                    " -type f -name " (shell-quote include)
                    " -exec " grep-cmd " {} +")
               (str grep-cmd " -- " (shell-quote path)))]
     (sh cmd))))

(defn glob
  "Find files by name pattern. Returns {:exit N :out \"...\" :err \"...\"}."
  ([pattern] (glob pattern "."))
  ([pattern path] (glob pattern path {}))
  ([pattern path opts]
   (ensure-string-args "io/glob: pattern and path" [pattern path] "")
   (let [max-depth (when (contains? opts :max-depth)
                     (normalize-natural-number-opt "io/glob: :max-depth" (:max-depth opts)))
         parts (cond-> ["find" (shell-quote path)]
                 max-depth (conj "-maxdepth" max-depth)
                 (:type opts) (conj "-type" (shell-quote (str (:type opts))))
                 true (conj "-name" (shell-quote pattern) "-print"))
         cmd (str (str/join " " parts) " | sort")]
     (sh cmd))))

(def ^:private git-read-commands
  #{"blame" "diff" "log" "rev-parse" "show" "status"})

(defn git
  "Run an allowlisted read-only git subcommand.
   Returns {:exit N :out \"...\" :err \"...\"} or {:error msg}."
  [subcmd & args]
  (let [subcmd (str subcmd)
        args (mapv str args)]
    (ensure-string-args "io/git: all git arguments" (cons subcmd args) "")
    (if (contains? git-read-commands subcmd)
      (let [cmd (str "git " subcmd
                     (when (seq args)
                       (str " " (str/join " " (map shell-quote args)))))]
        (sh cmd))
      {:error (str "git subcommand not allowed: " (pr-str subcmd)
                   ". Allowed: " (str/join ", " (sort git-read-commands)))})))

(defn env
  "Get environment variable(s). No args returns all as map.
   With key, returns value or nil."
  ([] (into {} (System/getenv)))
  ([key] (System/getenv (str key))))

(defn sh-test
  "Wrap a shell command in a zero-arg Spell test thunk."
  [cmd]
  {:spell/fn true
   :params []
   :body [(list 'let ['r (list 'io/sh cmd)]
                {:pass (list '= 0 (list :exit 'r))
                 :output (list 'str "COMMAND: " cmd "\n"
                               "EXIT: " (list :exit 'r) "\n"
                               "OUT:\n" (list :out 'r) "\n"
                               "ERR:\n" (list :err 'r))})]})

;; =============================================================================
;; Namespace definition for Spell
;; =============================================================================

(def io-namespace
  "The io/ namespace map for Spell agents."
  {:short-docs "File operations, read-only exploration helpers, shell commands, process execution, and shell-backed test thunks."
   :docs {:guide "IO — File operations, read-only exploration, shell commands, process execution and file watching.

  (io/read-lines path)                      — read file as vector of line strings with line-offset metadata
  (io/read-lines path start end)             — line range [start, end) Python-style half-open
  (io/read-file path)                        — read file as numbered-lines string
  (io/read-file path start end)
  (io/grep pattern path)
  (io/grep pattern path {:ignore-case true :include \"*.clj\"})
  (io/glob pattern)
  (io/glob pattern path {:type \"f\" :max-depth 5})
  (io/git \"status\")                         — run an allowlisted read-only git subcommand
  (io/str-replace path old new)              — replace string in file (must appear exactly once)
  (io/str-replace path old new {:all true})
  (io/replace-lines path start end content)  — deletes lines in half-open range [start, end)
  (io/replace-lines path start start content) — inserts content before line start
  (io/replace-lines path [[s e c] ...])      — multi-edit (line numbers refer to original file)
  (io/sh command)                            — execute shell command, returns {:exit :out :err}
  (io/sh command {:timeout 10})
  (io/sh-test command)                       — build a zero-arg shell-backed fix-loop test thunk
  (io/exec [cmd arg1 ...])                   — execute command directly (no shell)
  (io/watch-send path handle)                — watch directory, send events as message to handle

Functions identical to Clojure: slurp, spit, write-file, ls, exists?, directory?, stat, delete, copy, move, mkdir, mkdirs, cwd, env, temp-file.

Use (!describe io :fn-name) for detailed docs on any function.
All io/ calls are effect functions — quote them in the trailing expression.
Recommended functions: read-lines, grep, glob, replace-lines.

Common mistakes:

1. calling io/* outside the quoted trailing expression
2. forgetting !call-now when you need the result: '(io/read-file \"x\") evaluates but the result is lost
3. using io/sh for everything

In examples, ▌ marks cursor position in a completion.

Recommended usage pattern: Read and replace by line number.

1. Read the file to see current contents.
  ...▌'(!call-now code (io/read-lines \"main.py\"))

2. Next turn: code is bound. Identify the line range, replace it.
  ...(def code [\"def greet():\" \"    print('hello')\" ...])
  ▌(think \"Line 2 needs updating.\")
  '(io/replace-lines \"main.py\" 2 3 \"    print('goodbye')\")

Recommended usage pattern: Explore multiple files and persist relevant snippets.

1. Peek full file with one-turn lifetime.
  ...▌'(!peek-now file-lines (io/read-lines \"main.py\"))

2. Next turn: file-lines is available. Persist relevant snippets and peek another file.
  ...(def file-lines [\"... many lines ...\"])
  (rethink 2 \"!peek-now call and binding(s) disappear unless you persist what you need.\")
  ▌(persist fn-defn (subvec file-lines 99 111))
  '(!peek-now test-lines (io/read-lines \"test_main.py\"))

3. Next turn: fn-defn stays in context while the prior !peek-now call and file-lines disappear. test-lines is now available.
  ...
  (persist fn-defn [\"def target_fn(...):\" \"    ...\"])
  '(!peek-now test-lines (io/read-lines \"test_main.py\"))
  (def test-lines [\"... many lines ...\"])
  (rethink 2 \"!peek-now call and binding(s) disappear unless you persist what you need.\")
  ▌...

Recommended usage pattern: Grep through large files.

1. Read the file.
  ...▌'(!call-now code (io/read-file \"big-module.py\"))

2. Next turn: file was too large and got truncated. Rethink to discard it, then grep for what you need.
  ...(def code \"1: import os\\n2: import sys\\n...\\n... [truncated, 58302 chars total]\")
  ▌(rethink \"File too large to scan inline. Grep for the target instead.\")
  '(!call-now matches (io/grep \"def handle_request\" \"big-module.py\"))
"
          }
   :detail
   {:slurp-bytes "Read file as byte array. Returns {:ok bytes} or {:error msg}."
    :write-file "Write content to file. Creates parent dirs. Returns {:ok path} or {:error msg}."
    :exists? "Check if path exists."
    :directory? "Check if path is a directory."
    :ls "List directory contents. Returns vector of names or {:error msg}."
    :mkdir "Create directory. Returns {:ok path} or {:error msg}."
    :mkdirs "Create directory tree. Returns {:ok path} or {:error msg}."
    :cwd "Get current working directory."
    :delete "Delete file or empty directory. Returns {:ok path} or {:error msg}."
    :copy "Copy file. (copy src dest). Returns {:ok dest} or {:error msg}."
    :move "Move/rename file. (move src dest). Returns {:ok dest} or {:error msg}."
    :stat "Get file info. Returns {:size :modified :readable :writable :executable :directory} or {:error msg}."
    :temp-file "Create temp file. Returns {:ok path} or {:error msg}."
    :env "Get env var(s). (env) returns all as map. (env \"PATH\") returns value or nil."

    :read-file
    "Read file with line numbers. Returns a formatted string or {:error msg}.

(io/read-file path)
(io/read-file path start end)
  path: file path
  start, end: 1-indexed, half-open [start, end) (clamped to file bounds)

Returns a string with numbered lines: \"1: first line\\n2: second line\\n...\"
Returns {:error msg} on failure.

Use the range form to extract a subset for a child:
  '(!call-now code (io/read-file \"main.py\" 40 61))

For raw content without line numbers, use io/slurp:
  (:ok (io/slurp \"file.txt\"))"

    :read-lines
    "Read file as a vector of raw line strings with line-offset metadata.

(io/read-lines path)
(io/read-lines path start end)
  path: file path
  start, end: 1-indexed, half-open [start, end) (clamped to file bounds)

Returns a vector of raw strings with metadata {:spell/line-offset N}.
When serialized via !call-now, displays with line numbers for readability,
but the binding evaluates to the raw vector.

  '(!call-now code (io/read-lines \"main.py\" 40 61))
  ;; child sees numbered display, but code is a plain vector
  (nth code 0)           ;; first line as string
  (subvec code 0 5)      ;; first 5 lines
  (count code)            ;; number of lines

For one-turn file peeks, use:
  '(!peek-now code (io/read-lines \"main.py\"))"

    :grep
    "Search file contents recursively with line numbers.

(io/grep pattern path)
(io/grep pattern path {:ignore-case true :include \"*.clj\" :context 2 :max-count 20})

Returns {:exit N :out \"...\" :err \"...\"}.
Use :include to restrict matches to files whose names match a glob."

    :glob
    "Find files by name pattern.

(io/glob pattern)
(io/glob pattern path)
(io/glob pattern path {:type \"f\" :max-depth 5})

Returns {:exit N :out \"...\" :err \"...\"}.
Output is newline-delimited and sorted for stable downstream use."

    :git
    "Run an allowlisted read-only git subcommand.

(io/git \"status\")
(io/git \"log\" \"--oneline\" \"-20\")
(io/git \"show\" \"HEAD~1\")

Allowed subcommands: blame, branch, diff, log, rev-parse, show, status."

    :replace-lines
    "Replace lines in a file (1-indexed, half-open [start, end)). Two forms:

(io/replace-lines path start end content)
  Single edit. Replaces lines start through end-1. Same as Python lines[start-1:end-1].
  start == end is an insert before that line (empty range, nothing deleted).

(io/replace-lines path [[start end content] ...])
  Multiple edits, applied atomically. Line numbers refer to the ORIGINAL file —
  no drift between edits. Edits must not have overlapping ranges.

Empty content string deletes the range. Returns {:ok path} or {:error msg}.

Example — replace lines 42-44:
  (io/replace-lines \"main.py\" 42 45 \"    x = fixed_value\\n    return x\")

Example — insert before line 10 (no lines deleted):
  (io/replace-lines \"main.py\" 10 10 \"    # new comment\")

Example — multiple edits (no drift):
  (io/replace-lines \"main.py\" [[10 13 \"new block 1\"] [25 31 \"new block 2\"]])"

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

    :sh-test
    "Wrap a shell command as a zero-arg Spell test thunk.

(io/sh-test command)
  command: shell command string

Returns a Spell fn that yields:
  {:pass bool :output string}

Useful for fix-loop reflector tests that should run a shell command without
relying on closure capture semantics."

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
       (agents/!ask (agents/current-handle)))
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
   :grep grep
   :glob glob
   :git git
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
   :sh-test sh-test
   :exec exec
   :env env})

(defn- subset-namespace
  [short-docs guide docs keys]
  (merge {:short-docs short-docs
          :docs (assoc docs :guide guide)
          :detail (select-keys (:detail io-namespace) keys)}
         (select-keys io-namespace keys)))

(def io-read-namespace
  "Read-only io subset for codebase inspection."
  (subset-namespace
   "Read-only filesystem inspection, codebase exploration, and environment lookup."
   "IO-READ — Read-only filesystem inspection, codebase exploration, and environment lookup.

  (io/slurp path)                  — read entire file as raw string
  (io/slurp-bytes path)            — read file as bytes
  (io/read-file path)              — read file as numbered lines
  (io/read-file path start end)    — read numbered line range [start, end)
  (io/read-lines path)             — read file as vector of raw lines
  (io/read-lines path start end)   — read raw line range [start, end)
  (io/grep pattern path)           — recursive grep with line numbers
  (io/glob pattern)                — find files by name pattern
  (io/git \"status\")               — run an allowlisted read-only git subcommand
  (io/exists? path)                — check whether a path exists
  (io/directory? path)             — check whether a path is a directory
  (io/ls path)                     — list directory contents
  (io/cwd)                         — print current working directory
  (io/stat path)                   — inspect file metadata
  (io/env) / (io/env \"NAME\")      — read env vars

Use io-read when a child should inspect the workspace without editing files or
running arbitrary commands. For process execution, add io-exec separately."
   {:slurp "Read entire file as a raw string."
    :slurp-bytes "Read entire file as raw bytes."
    :read-file "Read a file with numbered lines."
    :read-lines "Read a file as a vector of raw line strings."
    :grep "Search file contents recursively with line numbers."
    :glob "Find files by name pattern."
    :git "Run an allowlisted read-only git subcommand."
    :exists? "Check whether a path exists."
    :directory? "Check whether a path is a directory."
    :ls "List directory contents."
    :cwd "Get the current working directory."
    :stat "Inspect file metadata."
    :env "Read one env var or all env vars."}
   [:slurp :slurp-bytes :read-file :read-lines :grep :glob :git
    :exists? :directory? :ls :cwd :stat :env]))

(def io-write-namespace
  "Write-capable io subset for filesystem edits."
  (subset-namespace
   "Filesystem mutation and file editing."
   "IO-WRITE — Filesystem mutation and file editing.

  (io/write-file path content)               — write file contents
  (io/spit path content)                     — write or append file contents
  (io/str-replace path old new)              — replace a string in a file
  (io/str-replace path old new {:all true})  — replace all occurrences
  (io/replace-lines path start end content)  — replace a numbered line range
  (io/replace-lines path [[s e c] ...])      — apply multiple line edits atomically
  (io/mkdir path)                            — create one directory
  (io/mkdirs path)                           — create a directory tree
  (io/delete path)                           — delete a file or empty directory
  (io/copy src dest)                         — copy a file
  (io/move src dest)                         — move or rename a file
  (io/temp-file)                             — create a temp file

Use io-write when an agent should edit the workspace. Pair it with io-read
when the same agent also needs inspection helpers."
   {:write-file "Write file contents, creating parent directories as needed."
    :spit "Write or append file contents."
    :str-replace "Replace a string in a file."
    :replace-lines "Replace one or more numbered line ranges."
    :mkdir "Create a directory."
    :mkdirs "Create a directory tree."
    :delete "Delete a file or empty directory."
    :copy "Copy a file."
    :move "Move or rename a file."
    :temp-file "Create a temporary file."}
   [:write-file :spit :str-replace :replace-lines :mkdir :mkdirs :delete :copy :move :temp-file]))

(def io-exec-namespace
  "Process-execution io subset."
  (subset-namespace
   "Shell commands, direct exec, and filesystem watch hooks."
   "IO-EXEC — Process execution helpers.

  (io/sh command)                  — execute a shell command
  (io/sh command {:timeout 10})    — execute with a timeout override
  (io/sh-test command)             — build a zero-arg shell-backed test thunk
  (io/exec [cmd arg1 ...])         — execute a command directly without a shell
  (io/watch-send path handle)      — watch a directory and send file events

Use io-exec when an agent needs command execution. Pair it with io-read for a
read-only exploration agent or with io-write for agents that both edit and run
commands."
   {:sh "Execute a shell command."
    :sh-test "Wrap a shell command as a zero-arg test thunk."
    :exec "Execute a command directly without a shell."
    :watch-send "Watch a directory and send file events to an agent handle."}
   [:sh :sh-test :exec :watch-send]))
