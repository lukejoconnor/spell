(ns spell.io-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [spell.io :as io]
            [spell.runtime :as runtime]
            [clojure.java.io :as jio]))

(def test-dir "target/test-files")

(defn setup-test-dir []
  (.mkdirs (jio/file test-dir)))

(defn cleanup-test-dir []
  (doseq [f (reverse (file-seq (jio/file test-dir)))]
    (.delete f)))

(use-fixtures :each
  (fn [f]
    (setup-test-dir)
    (try (f)
         (finally (cleanup-test-dir)))))

;; =============================================================================
;; read-file tests
;; =============================================================================

(deftest read-file-success
  (let [path (str test-dir "/read-test.txt")
        content "Hello, Spell!"]
    (spit path content)
    (is (= "1: Hello, Spell!" (io/read-file path)))))

(deftest read-file-multiline
  (let [path (str test-dir "/multiline.txt")
        content "Line 1\nLine 2\nLine 3"]
    (spit path content)
    (is (= "1: Line 1\n2: Line 2\n3: Line 3"
           (io/read-file path)))))

(deftest read-file-unicode
  (let [path (str test-dir "/unicode.txt")
        content "Hello 世界 🌍"]
    (spit path content)
    (is (= "1: Hello 世界 🌍" (io/read-file path)))))

(deftest read-file-not-found
  (let [result (io/read-file (str test-dir "/nonexistent.txt"))]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest read-file-empty
  (let [path (str test-dir "/empty.txt")]
    (spit path "")
    (is (= "" (io/read-file path)))))

(deftest read-file-line-range
  (let [path (str test-dir "/range.txt")
        content "line1\nline2\nline3\nline4\nline5"]
    (spit path content)
    (testing "middle range"
      (is (= "2: line2\n3: line3"
             (io/read-file path 2 4))))
    (testing "single line"
      (is (= "4: line4"
             (io/read-file path 4 5))))
    (testing "full range"
      (is (= "1: line1\n2: line2\n3: line3\n4: line4\n5: line5"
             (io/read-file path 1 6))))
    (testing "clamped to bounds"
      (is (= "4: line4\n5: line5"
             (io/read-file path 4 100))))))

(deftest read-file-range-not-found
  (let [result (io/read-file (str test-dir "/missing.txt") 1 5)]
    (is (contains? result :error))))

;; =============================================================================
;; read-lines tests
;; =============================================================================

(deftest read-lines-basic
  (let [path (str test-dir "/read-lines.txt")
        content "Hello\nWorld\nFoo"]
    (spit path content)
    (let [result (io/read-lines path)]
      (is (= ["Hello" "World" "Foo"] result))
      (is (= 1 (:spell/line-offset (meta result)))))))

(deftest read-lines-range
  (let [path (str test-dir "/read-lines-range.txt")
        content "line1\nline2\nline3\nline4\nline5"]
    (spit path content)
    (let [result (io/read-lines path 2 5)]
      (is (= ["line2" "line3" "line4"] result))
      (is (= 2 (:spell/line-offset (meta result)))))))

(deftest read-lines-empty
  (let [path (str test-dir "/read-lines-empty.txt")]
    (spit path "")
    (let [result (io/read-lines path)]
      (is (= [] result))
      (is (= 1 (:spell/line-offset (meta result)))))))

(deftest read-lines-not-found
  (let [result (io/read-lines (str test-dir "/nonexistent.txt"))]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest read-lines-clamped
  (let [path (str test-dir "/read-lines-clamp.txt")
        content "line1\nline2\nline3"]
    (spit path content)
    (testing "end clamped to file length"
      (let [result (io/read-lines path 2 100)]
        (is (= ["line2" "line3"] result))
        (is (= 2 (:spell/line-offset (meta result))))))
    (testing "start clamped to 1"
      (let [result (io/read-lines path 0 3)]
        (is (= ["line1" "line2"] result))
        (is (= 1 (:spell/line-offset (meta result))))))))

(deftest read-lines-single-line
  (let [path (str test-dir "/read-lines-single.txt")]
    (spit path "only line")
    (let [result (io/read-lines path)]
      (is (= ["only line"] result))
      (is (= 1 (:spell/line-offset (meta result)))))))

;; =============================================================================
;; slurp/spit tests
;; =============================================================================

(deftest slurp-file-test
  (let [path (str test-dir "/slurp-test.txt")
        content "Hello, world!"]
    (spit path content)
    (is (= {:ok content} (io/slurp-file path)))))

(deftest slurp-file-not-found
  (let [result (io/slurp-file (str test-dir "/missing.txt"))]
    (is (contains? result :error))))

(deftest spit-file-test
  (let [path (str test-dir "/spit-test.txt")
        content "Test content"]
    (is (= {:ok path} (io/spit-file path content)))
    (is (= content (slurp path)))))

(deftest spit-file-append
  (let [path (str test-dir "/append-test.txt")]
    (io/spit-file path "first")
    (io/spit-file path " second" {:append true})
    (is (= "first second" (slurp path)))))

;; =============================================================================
;; write-file tests
;; =============================================================================

(deftest write-file-success
  (let [path (str test-dir "/write-test.txt")
        content "Test content"]
    (is (= {:ok path} (io/write-file path content)))
    (is (= content (slurp path)))))

(deftest write-file-creates-dirs
  (let [path (str test-dir "/nested/deep/dir/file.txt")
        content "Nested content"]
    (is (= {:ok path} (io/write-file path content)))
    (is (= content (slurp path)))))

(deftest write-file-overwrites
  (let [path (str test-dir "/overwrite.txt")]
    (spit path "original")
    (is (= {:ok path} (io/write-file path "new content")))
    (is (= "new content" (slurp path)))))

(deftest write-file-unicode
  (let [path (str test-dir "/unicode-write.txt")
        content "写文件 📝"]
    (is (= {:ok path} (io/write-file path content)))
    (is (= content (slurp path)))))

(deftest write-file-multiline
  (let [path (str test-dir "/multiline-write.txt")
        content "Line 1\nLine 2\n\nLine 4"]
    (is (= {:ok path} (io/write-file path content)))
    (is (= content (slurp path)))))

;; =============================================================================
;; str-replace tests
;; =============================================================================

(deftest str-replace-success
  (let [path (str test-dir "/replace.txt")]
    (spit path "(def x 1)\n(def y 2)")
    (is (= {:ok path} (io/str-replace path "(def x 1)" "(def x 42)")))
    (is (= "(def x 42)\n(def y 2)" (slurp path)))))

(deftest str-replace-not-found
  (let [path (str test-dir "/replace-notfound.txt")]
    (spit path "some content")
    (let [result (io/str-replace path "nonexistent" "new")]
      (is (contains? result :error))
      (is (re-find #"not found" (:error result))))))

(deftest str-replace-not-unique
  (let [path (str test-dir "/replace-dup.txt")]
    (spit path "foo bar foo baz")
    (let [result (io/str-replace path "foo" "qux")]
      (is (contains? result :error))
      (is (re-find #"2 times" (:error result))))))

(deftest str-replace-file-not-found
  (let [result (io/str-replace (str test-dir "/missing.txt") "a" "b")]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest str-replace-multiline
  (let [path (str test-dir "/replace-multi.txt")]
    (spit path "function foo() {\n  return 1;\n}")
    (is (= {:ok path}
           (io/str-replace path
                           "function foo() {\n  return 1;\n}"
                           "function foo() {\n  return 42;\n}")))
    (is (= "function foo() {\n  return 42;\n}" (slurp path)))))

(deftest str-replace-preserves-rest
  (let [path (str test-dir "/preserve.txt")
        before "# Header\n\n(def target 1)\n\n# Footer"
        after "# Header\n\n(def target 999)\n\n# Footer"]
    (spit path before)
    (is (= {:ok path} (io/str-replace path "(def target 1)" "(def target 999)")))
    (is (= after (slurp path)))))

(deftest str-replace-special-chars
  (let [path (str test-dir "/special.txt")]
    (spit path "regex chars: $^.*+?()[]{}|\\")
    (is (= {:ok path}
           (io/str-replace path
                           "regex chars: $^.*+?()[]{}|\\"
                           "replaced!")))
    (is (= "replaced!" (slurp path)))))

(deftest str-replace-dollar-in-replacement
  (testing "$ and \\ in new-str are treated as literal characters"
    (let [path (str test-dir "/dollar.txt")]
      (spit path "price: OLD")
      (is (= {:ok path} (io/str-replace path "OLD" "$100\\n")))
      (is (= "price: $100\\n" (slurp path))))))

(deftest str-replace-empty-old-str
  (testing "empty old-str returns error (not infinite loop)"
    (let [path (str test-dir "/empty-old.txt")]
      (spit path "content")
      (let [result (io/str-replace path "" "new")]
        (is (contains? result :error))
        (is (re-find #"empty" (:error result)))))))

(deftest str-replace-all
  (let [path (str test-dir "/replace-all.txt")]
    (spit path "foo bar foo baz foo")
    (is (= {:ok path} (io/str-replace path "foo" "qux" {:all true})))
    (is (= "qux bar qux baz qux" (slurp path)))))

(deftest str-replace-all-single-occurrence
  (let [path (str test-dir "/replace-all-single.txt")]
    (spit path "one foo here")
    (is (= {:ok path} (io/str-replace path "foo" "bar" {:all true})))
    (is (= "one bar here" (slurp path)))))

(deftest str-replace-all-not-found
  (let [path (str test-dir "/replace-all-missing.txt")]
    (spit path "no match here")
    (let [result (io/str-replace path "foo" "bar" {:all true})]
      (is (contains? result :error))
      (is (re-find #"not found" (:error result))))))

;; =============================================================================
;; replace-lines tests
;; =============================================================================

(deftest replace-lines-single
  (let [path (str test-dir "/rl-single.txt")]
    (spit path "aaa\nbbb\nccc\n")
    (is (= {:ok path} (io/replace-lines path 2 3 "BBB")))
    (is (= "aaa\nBBB\nccc\n" (slurp path)))))

(deftest replace-lines-range
  (let [path (str test-dir "/rl-range.txt")]
    (spit path "line1\nline2\nline3\nline4\nline5\n")
    (is (= {:ok path} (io/replace-lines path 2 5 "new2\nnew3")))
    (is (= "line1\nnew2\nnew3\nline5\n" (slurp path)))))

(deftest replace-lines-delete
  (let [path (str test-dir "/rl-delete.txt")]
    (spit path "keep\nremove\nkeep\n")
    (is (= {:ok path} (io/replace-lines path 2 3 "")))
    (is (= "keep\nkeep\n" (slurp path)))))

(deftest replace-lines-insert
  (let [path (str test-dir "/rl-insert.txt")]
    (spit path "aaa\nccc\n")
    (is (= {:ok path} (io/replace-lines path 2 2 "bbb")))
    (is (= "aaa\nbbb\nccc\n" (slurp path)))))

(deftest replace-lines-first-line
  (let [path (str test-dir "/rl-first.txt")]
    (spit path "old\nrest\n")
    (is (= {:ok path} (io/replace-lines path 1 2 "new")))
    (is (= "new\nrest\n" (slurp path)))))

(deftest replace-lines-last-line
  (let [path (str test-dir "/rl-last.txt")]
    (spit path "rest\nold\n")
    (is (= {:ok path} (io/replace-lines path 2 3 "new")))
    (is (= "rest\nnew\n" (slurp path)))))

(deftest replace-lines-out-of-range
  (let [path (str test-dir "/rl-bounds.txt")]
    (spit path "one\ntwo\n")
    (testing "start out of range"
      (is (contains? (io/replace-lines path 0 1 "x") :error))
      (is (contains? (io/replace-lines path 5 5 "x") :error)))
    (testing "end out of range"
      (is (contains? (io/replace-lines path 1 6 "x") :error)))))

(deftest replace-lines-file-not-found
  (let [result (io/replace-lines (str test-dir "/missing.txt") 1 2 "x")]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest replace-lines-preserves-no-trailing-newline
  (let [path (str test-dir "/rl-no-nl.txt")]
    (spit path "aaa\nbbb\nccc")
    (is (= {:ok path} (io/replace-lines path 2 3 "BBB")))
    (is (= "aaa\nBBB\nccc" (slurp path)))))

;; =============================================================================
;; replace-lines multi-edit tests
;; =============================================================================

(deftest replace-lines-multi-basic
  (testing "two non-adjacent edits applied atomically"
    (let [path (str test-dir "/rl-multi.txt")]
      (spit path "aaa\nbbb\nccc\nddd\neee\n")
      (is (= {:ok path}
             (io/replace-lines path [[2 3 "BBB"] [4 5 "DDD"]])))
      (is (= "aaa\nBBB\nccc\nDDD\neee\n" (slurp path))))))

(deftest replace-lines-multi-different-sizes
  (testing "edits that change line count — line numbers are from original file"
    (let [path (str test-dir "/rl-multi-sizes.txt")]
      (spit path "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n")
      ;; Replace lines 2-3 with one line, and lines 7-9 with four lines
      (is (= {:ok path}
             (io/replace-lines path [[2 4 "two-three"] [7 10 "A\nB\nC\nD"]])))
      (is (= "1\ntwo-three\n4\n5\n6\nA\nB\nC\nD\n10\n" (slurp path))))))

(deftest replace-lines-multi-delete-and-replace
  (testing "one delete and one replace"
    (let [path (str test-dir "/rl-multi-del.txt")]
      (spit path "keep\ndelete\nkeep\nchange\nkeep\n")
      (is (= {:ok path}
             (io/replace-lines path [[2 3 ""] [4 5 "CHANGED"]])))
      (is (= "keep\nkeep\nCHANGED\nkeep\n" (slurp path))))))

(deftest replace-lines-multi-three-edits
  (testing "three edits in unsorted order"
    (let [path (str test-dir "/rl-multi-three.txt")]
      (spit path "a\nb\nc\nd\ne\nf\ng\n")
      ;; Pass out of order — should still work
      (is (= {:ok path}
             (io/replace-lines path [[6 7 "F"] [2 3 "B"] [4 5 "D"]])))
      (is (= "a\nB\nc\nD\ne\nF\ng\n" (slurp path))))))

(deftest replace-lines-multi-adjacent
  (testing "adjacent but non-overlapping edits"
    (let [path (str test-dir "/rl-multi-adj.txt")]
      (spit path "a\nb\nc\nd\n")
      (is (= {:ok path}
             (io/replace-lines path [[1 3 "AB"] [3 5 "CD"]])))
      (is (= "AB\nCD\n" (slurp path))))))

(deftest replace-lines-multi-overlap-error
  (testing "overlapping ranges return error"
    (let [path (str test-dir "/rl-multi-overlap.txt")]
      (spit path "a\nb\nc\nd\ne\n")
      (let [result (io/replace-lines path [[1 4 "X"] [2 5 "Y"]])]
        (is (contains? result :error))
        (is (re-find #"overlap" (:error result)))))))

(deftest replace-lines-multi-out-of-range
  (testing "any edit out of range returns error"
    (let [path (str test-dir "/rl-multi-bounds.txt")]
      (spit path "a\nb\nc\n")
      (is (contains? (io/replace-lines path [[1 2 "A"] [5 6 "X"]]) :error)))))

(deftest replace-lines-multi-single-edit
  (testing "vector with one edit works like the 4-arg form"
    (let [path (str test-dir "/rl-multi-one.txt")]
      (spit path "a\nb\nc\n")
      (is (= {:ok path}
             (io/replace-lines path [[2 3 "B"]])))
      (is (= "a\nB\nc\n" (slurp path))))))

(deftest replace-lines-multi-preserves-no-trailing-newline
  (testing "multi-edit preserves absence of trailing newline"
    (let [path (str test-dir "/rl-multi-no-nl.txt")]
      (spit path "a\nb\nc\nd\ne")
      (is (= {:ok path}
             (io/replace-lines path [[2 3 "B"] [4 5 "D"]])))
      (is (= "a\nB\nc\nD\ne" (slurp path))))))

;; =============================================================================
;; Directory operations tests
;; =============================================================================

(deftest exists?-test
  (let [path (str test-dir "/exists-test.txt")]
    (is (false? (io/exists? path)))
    (spit path "test")
    (is (true? (io/exists? path)))))

(deftest directory?-test
  (let [dir-path test-dir
        file-path (str test-dir "/file.txt")]
    (spit file-path "test")
    (is (true? (io/directory? dir-path)))
    (is (false? (io/directory? file-path)))))

(deftest ls-test
  (spit (str test-dir "/a.txt") "a")
  (spit (str test-dir "/b.txt") "b")
  (let [files (io/ls test-dir)]
    (is (vector? files))
    (is (contains? (set files) "a.txt"))
    (is (contains? (set files) "b.txt"))))

(deftest mkdir-test
  (let [path (str test-dir "/new-dir")]
    (is (= {:ok path} (io/mkdir path)))
    (is (io/directory? path))))

(deftest mkdirs-test
  (let [path (str test-dir "/a/b/c")]
    (is (= {:ok path} (io/mkdirs path)))
    (is (io/directory? path))))

(deftest cwd-test
  (is (string? (io/cwd)))
  (is (io/directory? (io/cwd))))

;; =============================================================================
;; File manipulation tests
;; =============================================================================

(deftest delete-test
  (let [path (str test-dir "/delete-me.txt")]
    (spit path "delete me")
    (is (io/exists? path))
    (is (= {:ok path} (io/delete path)))
    (is (not (io/exists? path)))))

(deftest copy-test
  (let [src (str test-dir "/src.txt")
        dest (str test-dir "/dest.txt")]
    (spit src "content")
    (is (= {:ok dest} (io/copy src dest)))
    (is (= "content" (slurp dest)))))

(deftest move-test
  (let [src (str test-dir "/move-src.txt")
        dest (str test-dir "/move-dest.txt")]
    (spit src "content")
    (is (= {:ok dest} (io/move src dest)))
    (is (not (io/exists? src)))
    (is (= "content" (slurp dest)))))

(deftest stat-test
  (let [path (str test-dir "/stat-test.txt")]
    (spit path "hello")
    (let [info (io/stat path)]
      (is (= 5 (:size info)))
      (is (number? (:modified info)))
      (is (boolean? (:readable info)))
      (is (false? (:directory info))))))

(deftest temp-file-test
  (let [result (io/temp-file)]
    (is (contains? result :ok))
    (is (io/exists? (:ok result)))
    (io/delete (:ok result))))

;; =============================================================================
;; Process execution tests
;; =============================================================================

(deftest sh-test
  (testing "basic command"
    (let [result (io/sh "echo hello")]
      (is (= 0 (:exit result)))
      (is (= "hello" (:out result)))
      (is (= "" (:err result)))))

  (testing "exit code"
    (let [result (io/sh "exit 42")]
      (is (= 42 (:exit result)))))

  (testing "stderr"
    (let [result (io/sh "echo error >&2")]
      (is (= "error" (:err result)))))

  (testing "per-call timeout override"
    (let [result (io/sh "sleep 2" {:timeout 1})]
      (is (= -1 (:exit result)))
      (is (re-find #"timed out" (:err result)))))

  (testing "timeout 0 disables timeout for this call"
    (let [result (io/sh "sleep 1 && echo done" {:timeout 0})]
      (is (= 0 (:exit result)))
      (is (= "done" (:out result)))
      (is (= "" (:err result)))))

  (testing "keyword args instead of opts map throws informative error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"all command segments must be strings"
                          (io/sh "echo hello" :timeout 60))))

  (testing "non-string first command segment throws informative error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"all command segments must be strings"
                          (io/sh :echo " hello")))))

(deftest exec-test
  (let [result (io/exec ["echo" "hello"])]
    (is (= 0 (:exit result)))
    (is (= "hello" (:out result))))

  (testing "non-string argv entries throw informative error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"all argv entries must be strings"
                          (io/exec ["echo" :timeout 60])))))

(deftest env-test
  (testing "get all env vars"
    (let [env-map (io/env)]
      (is (map? env-map))
      (is (contains? env-map "PATH"))))

  (testing "get single env var"
    (is (string? (io/env "PATH")))
    (is (nil? (io/env "NONEXISTENT_VAR_12345")))))

;; =============================================================================
;; watch-dir tests
;; =============================================================================

(deftest watch-dir-timeout
  (testing "returns {:timeout true} when nothing happens"
    (let [dir (str test-dir "/watch-empty")]
      (.mkdirs (jio/file dir))
      (is (= {:timeout true} (#'io/watch-dir dir 100))))))

(deftest watch-dir-detects-create
  (testing "detects file creation"
    (let [dir (str test-dir "/watch-create")]
      (.mkdirs (jio/file dir))
      (let [result (future
                     (#'io/watch-dir dir 15000))]
        (Thread/sleep 500)
        (spit (str dir "/new.txt") "hello")
        (let [r (deref result 14000 {:timeout true})]
          (is (contains? r :ok))
          (is (some #(= :create (:kind %)) (:ok r))))))))

(deftest watch-dir-detects-modify
  (testing "detects file modification"
    (let [dir (str test-dir "/watch-modify")
          file (str dir "/existing.txt")]
      (.mkdirs (jio/file dir))
      (spit file "original")
      (let [result (future
                     (#'io/watch-dir dir 15000))]
        (Thread/sleep 500)
        (spit file "modified")
        (let [r (deref result 14000 {:timeout true})]
          (is (contains? r :ok))
          (is (some #(= :modify (:kind %)) (:ok r))))))))

(deftest watch-dir-not-found
  (testing "returns error for nonexistent directory"
    (let [result (#'io/watch-dir (str test-dir "/nonexistent") 100)]
      (is (contains? result :error)))))

(deftest watch-dir-not-directory
  (testing "returns error for file path"
    (let [path (str test-dir "/watch-file.txt")]
      (spit path "not a dir")
      (let [result (#'io/watch-dir path 100)]
        (is (contains? result :error))))))

;; =============================================================================
;; watch-send tests
;; =============================================================================

(deftest watch-send-returns-nil
  (testing "returns nil immediately"
    (let [dir (str test-dir "/ws-nil")]
      (.mkdirs (jio/file dir))
      (runtime/register! :ws-test-nil)
      (is (nil? (io/watch-send dir :ws-test-nil 100))))))

(deftest watch-send-delivers-message
  (testing "sends file events to handle via runtime"
    (let [dir (str test-dir "/ws-deliver")
          received (promise)]
      (.mkdirs (jio/file dir))
      ;; Use start-box to register with an eval-fn that captures what arrives
      (runtime/start-box :ws-test-deliver
        (fn [raw] (deliver received raw) :done)
        "(quine c (eval (do 1)))")
      (Thread/sleep 50)
      (io/watch-send dir :ws-test-deliver 15000)
      (Thread/sleep 500)
      (spit (str dir "/trigger.txt") "hello")
      ;; Wait for the agent to process the watch event
      (let [raw (deref received 5000 :timeout)]
        (is (not= :timeout raw))
        (is (string? raw))
        (is (re-find #"watch-send" raw))))))

(deftest watch-send-timeout-no-message
  (testing "does not send on timeout"
    (let [dir (str test-dir "/ws-timeout")]
      (.mkdirs (jio/file dir))
      (runtime/register! :ws-test-timeout)
      (io/watch-send dir :ws-test-timeout 100)
      ;; Wait for the watcher to time out
      (Thread/sleep 200)
      ;; Inbox should still be identity (no message sent)
      (let [state (:state (get @runtime/registry :ws-test-timeout))]
        (is (= identity (:inbox-fn @state)))))))

;; =============================================================================
;; io-namespace tests
;; =============================================================================

(deftest io-read-namespace-subset-test
  (let [ns-map io/io-read-namespace]
    (is (contains? ns-map :read-file))
    (is (contains? ns-map :slurp))
    (is (contains? ns-map :grep))
    (is (contains? ns-map :glob))
    (is (contains? ns-map :git))
    (is (contains? ns-map :env))
    (is (not (contains? ns-map :write-file)))
    (is (not (contains? ns-map :sh)))
    (is (= "Read a file with numbered lines."
           (get-in ns-map [:docs :read-file])))))

(deftest io-write-namespace-subset-test
  (let [ns-map io/io-write-namespace]
    (is (contains? ns-map :write-file))
    (is (contains? ns-map :replace-lines))
    (is (contains? ns-map :temp-file))
    (is (not (contains? ns-map :read-file)))
    (is (not (contains? ns-map :sh)))
    (is (= "Write file contents, creating parent directories as needed."
           (get-in ns-map [:docs :write-file])))))

(deftest io-exec-namespace-subset-test
  (let [ns-map io/io-exec-namespace]
    (is (contains? ns-map :sh))
    (is (contains? ns-map :exec))
    (is (contains? ns-map :watch-send))
    (is (not (contains? ns-map :grep)))
    (is (not (contains? ns-map :read-file)))
    (is (not (contains? ns-map :write-file)))
    (is (= "Execute a shell command."
           (get-in ns-map [:docs :sh])))))

(deftest grep-test
  (let [clj-path (str test-dir "/grep-target.clj")
        txt-path (str test-dir "/nested/grep-target.txt")]
    (.mkdirs (jio/file (str test-dir "/nested")))
    (spit clj-path "(def needle 1)\n")
    (spit txt-path "needle in haystack\n")
    (let [result (io/grep "needle" test-dir)]
      (is (= 0 (:exit result)))
      (is (re-find (re-pattern (java.util.regex.Pattern/quote clj-path)) (:out result)))
      (is (re-find (re-pattern (java.util.regex.Pattern/quote txt-path)) (:out result))))
    (let [result (io/grep "needle" test-dir {:include "*.clj"})]
      (is (= 0 (:exit result)))
      (is (re-find (re-pattern (java.util.regex.Pattern/quote clj-path)) (:out result)))
      (is (not (re-find (re-pattern (java.util.regex.Pattern/quote txt-path)) (:out result)))))))

(deftest grep-rejects-non-numeric-options-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"io/grep: :context must be a non-negative integer"
       (io/grep "needle" test-dir {:context "1; touch /tmp/pwned >&2"})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"io/grep: :max-count must be a non-negative integer"
       (io/grep "needle" test-dir {:max-count "1; touch /tmp/pwned >&2"}))))

(deftest glob-test
  (let [clj-path (str test-dir "/alpha.clj")
        md-path (str test-dir "/nested/bravo.md")
        nested-clj-path (str test-dir "/nested/charlie.clj")]
    (.mkdirs (jio/file (str test-dir "/nested")))
    (spit clj-path "alpha")
    (spit md-path "bravo")
    (spit nested-clj-path "charlie")
    (let [result (io/glob "*.clj" test-dir {:type "f" :max-depth 3})]
      (is (= 0 (:exit result)))
      (is (= [clj-path nested-clj-path]
             (str/split-lines (:out result)))))
    (let [result (io/glob "*.md" test-dir {:type "f" :max-depth 3})]
      (is (= 0 (:exit result)))
      (is (= [md-path] (str/split-lines (:out result)))))))

(deftest glob-rejects-non-numeric-max-depth-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"io/glob: :max-depth must be a non-negative integer"
       (io/glob "*.clj" test-dir {:type "f" :max-depth "1; echo hacked >&2"}))))

(deftest git-helper-test
  (let [result (io/git "rev-parse" "--show-toplevel")
        repo-root (.getCanonicalPath (jio/file "."))]
    (is (= 0 (:exit result)))
    (is (= repo-root (:out result))))
  (let [result (io/git "checkout" "main")]
    (is (= {:error "git subcommand not allowed: \"checkout\". Allowed: blame, diff, log, rev-parse, show, status"}
           result))))

(deftest git-helper-rejects-branch-test
  (is (= {:error "git subcommand not allowed: \"branch\". Allowed: blame, diff, log, rev-parse, show, status"}
         (io/git "branch" "-D" "topic"))))

;; =============================================================================
;; Event-send tests
;; =============================================================================

(use-fixtures :once
  (fn [f]
    (setup-test-dir)
    (try (f) (finally (cleanup-test-dir)))))

(deftest event-send-returns-nil-test
  (testing "event-send returns nil immediately"
    (runtime/register! :es-io-nil)
    (try
      (is (nil? (io/event-send (fn [] {:ok "data"}) :es-io-nil :test-sender)))
      (finally (swap! runtime/registry dissoc :es-io-nil)))))

(deftest event-send-sends-on-ok-test
  (testing "event-send sends message when event-fn returns {:ok val}"
    (let [handle :es-io-ok
          received (promise)
          eval-fn (fn [raw] (deliver received raw) :done)]
      (runtime/start-box handle eval-fn "(quine c (eval (do 1)))")
      (Thread/sleep 50)
      (try
        (io/event-send (fn [] {:ok "event-data"}) handle :test-event)
        (let [raw (deref received 5000 :timeout)]
          (is (not= :timeout raw))
          (is (string? raw))
          (is (.contains ^String raw ":from :test-event"))
          (is (.contains ^String raw ":body \"event-data\"")))
        (finally (swap! runtime/registry dissoc handle))))))

(deftest event-send-silent-on-non-ok-test
  (testing "event-send does not send when event-fn returns non-:ok"
    (let [handle :es-io-silent]
      (runtime/register! handle)
      (try
        (io/event-send (fn [] {:timeout true}) handle :test-event)
        (Thread/sleep 50)
        (is (= identity (:inbox-fn @(:state (get @runtime/registry handle)))))
        (finally (swap! runtime/registry dissoc handle))))))

(deftest event-send-notifies-on-exception-test
  (testing "event-send sends {:error msg} when event-fn throws"
    (let [handle :es-io-ex
          received (promise)
          eval-fn (fn [raw] (deliver received raw) :done)]
      (runtime/start-box handle eval-fn "(quine c (eval (do 1)))")
      (Thread/sleep 50)
      (try
        (io/event-send (fn [] (throw (ex-info "boom" {}))) handle :test-event)
        (let [raw (deref received 5000 :timeout)]
          (is (not= :timeout raw))
          (is (string? raw))
          (is (.contains ^String raw ":from :test-event"))
          (is (.contains ^String raw ":error")))
        (finally (swap! runtime/registry dissoc handle))))))

(deftest event-send-abort-test
  (testing "event-send does not send when event-fn returns {:abort ...}"
    (let [handle :es-io-abort]
      (runtime/register! handle)
      (try
        (io/event-send (fn [] {:abort :reason}) handle :test-event)
        (Thread/sleep 50)
        (is (= identity (:inbox-fn @(:state (get @runtime/registry handle)))))
        (finally (swap! runtime/registry dissoc handle))))))
