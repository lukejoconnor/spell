(ns spell.io-test
  (:require [clojure.test :refer :all]
            [spell.io :as io]
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
    (is (= (sorted-map 1 "Hello, Spell!") (io/read-file path)))))

(deftest read-file-multiline
  (let [path (str test-dir "/multiline.txt")
        content "Line 1\nLine 2\nLine 3"]
    (spit path content)
    (is (= (sorted-map 1 "Line 1" 2 "Line 2" 3 "Line 3")
           (io/read-file path)))))

(deftest read-file-unicode
  (let [path (str test-dir "/unicode.txt")
        content "Hello 世界 🌍"]
    (spit path content)
    (is (= (sorted-map 1 "Hello 世界 🌍") (io/read-file path)))))

(deftest read-file-not-found
  (let [result (io/read-file (str test-dir "/nonexistent.txt"))]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest read-file-empty
  (let [path (str test-dir "/empty.txt")]
    (spit path "")
    (is (= (sorted-map) (io/read-file path)))))

(deftest read-file-line-range
  (let [path (str test-dir "/range.txt")
        content "line1\nline2\nline3\nline4\nline5"]
    (spit path content)
    (testing "middle range"
      (is (= (sorted-map 2 "line2" 3 "line3")
             (io/read-file path 2 3))))
    (testing "single line"
      (is (= (sorted-map 4 "line4")
             (io/read-file path 4 4))))
    (testing "full range"
      (is (= (sorted-map 1 "line1" 2 "line2" 3 "line3" 4 "line4" 5 "line5")
             (io/read-file path 1 5))))
    (testing "clamped to bounds"
      (is (= (sorted-map 4 "line4" 5 "line5")
             (io/read-file path 4 100))))))

(deftest read-file-range-not-found
  (let [result (io/read-file (str test-dir "/missing.txt") 1 5)]
    (is (contains? result :error))))

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

;; =============================================================================
;; replace-lines tests
;; =============================================================================

(deftest replace-lines-single
  (let [path (str test-dir "/rl-single.txt")]
    (spit path "aaa\nbbb\nccc\n")
    (is (= {:ok path} (io/replace-lines path 2 2 "BBB")))
    (is (= "aaa\nBBB\nccc\n" (slurp path)))))

(deftest replace-lines-range
  (let [path (str test-dir "/rl-range.txt")]
    (spit path "line1\nline2\nline3\nline4\nline5\n")
    (is (= {:ok path} (io/replace-lines path 2 4 "new2\nnew3")))
    (is (= "line1\nnew2\nnew3\nline5\n" (slurp path)))))

(deftest replace-lines-delete
  (let [path (str test-dir "/rl-delete.txt")]
    (spit path "keep\nremove\nkeep\n")
    (is (= {:ok path} (io/replace-lines path 2 2 "")))
    (is (= "keep\nkeep\n" (slurp path)))))

(deftest replace-lines-first-line
  (let [path (str test-dir "/rl-first.txt")]
    (spit path "old\nrest\n")
    (is (= {:ok path} (io/replace-lines path 1 1 "new")))
    (is (= "new\nrest\n" (slurp path)))))

(deftest replace-lines-last-line
  (let [path (str test-dir "/rl-last.txt")]
    (spit path "rest\nold\n")
    (is (= {:ok path} (io/replace-lines path 2 2 "new")))
    (is (= "rest\nnew\n" (slurp path)))))

(deftest replace-lines-out-of-range
  (let [path (str test-dir "/rl-bounds.txt")]
    (spit path "one\ntwo\n")
    (testing "start out of range"
      (is (contains? (io/replace-lines path 0 1 "x") :error))
      (is (contains? (io/replace-lines path 5 5 "x") :error)))
    (testing "end out of range"
      (is (contains? (io/replace-lines path 1 5 "x") :error)))))

(deftest replace-lines-file-not-found
  (let [result (io/replace-lines (str test-dir "/missing.txt") 1 1 "x")]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest replace-lines-preserves-no-trailing-newline
  (let [path (str test-dir "/rl-no-nl.txt")]
    (spit path "aaa\nbbb\nccc")
    (is (= {:ok path} (io/replace-lines path 2 2 "BBB")))
    (is (= "aaa\nBBB\nccc" (slurp path)))))

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
      (is (= "error" (:err result))))))

(deftest exec-test
  (let [result (io/exec ["echo" "hello"])]
    (is (= 0 (:exit result)))
    (is (= "hello" (:out result)))))

(deftest env-test
  (testing "get all env vars"
    (let [env-map (io/env)]
      (is (map? env-map))
      (is (contains? env-map "PATH"))))

  (testing "get single env var"
    (is (string? (io/env "PATH")))
    (is (nil? (io/env "NONEXISTENT_VAR_12345")))))

;; =============================================================================
;; io-namespace tests
;; =============================================================================

(deftest io-namespace-complete
  (let [ns io/io-namespace]
    (is (contains? ns :docs))
    (is (contains? ns :slurp))
    (is (contains? ns :spit))
    (is (contains? ns :read-file))
    (is (contains? ns :write-file))
    (is (contains? ns :sh))
    (is (contains? ns :exec))
    (is (contains? ns :env))
    (is (contains? ns :exists?))
    (is (contains? ns :ls))))
