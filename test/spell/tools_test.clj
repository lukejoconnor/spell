(ns spell.tools-test
  (:require [clojure.test :refer :all]
            [spell.tools :as tools]
            [clojure.java.io :as io]))

(def test-dir "target/test-files")

(defn setup-test-dir []
  (.mkdirs (io/file test-dir)))

(defn cleanup-test-dir []
  (doseq [f (reverse (file-seq (io/file test-dir)))]
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
    (is (= (sorted-map 1 "Hello, Spell!") (tools/read-file path)))))

(deftest read-file-multiline
  (let [path (str test-dir "/multiline.txt")
        content "Line 1\nLine 2\nLine 3"]
    (spit path content)
    (is (= (sorted-map 1 "Line 1" 2 "Line 2" 3 "Line 3")
           (tools/read-file path)))))

(deftest read-file-unicode
  (let [path (str test-dir "/unicode.txt")
        content "Hello 世界 🌍"]
    (spit path content)
    (is (= (sorted-map 1 "Hello 世界 🌍") (tools/read-file path)))))

(deftest read-file-not-found
  (let [result (tools/read-file (str test-dir "/nonexistent.txt"))]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest read-file-empty
  (let [path (str test-dir "/empty.txt")]
    (spit path "")
    (is (= (sorted-map) (tools/read-file path)))))

(deftest read-file-line-range
  (let [path (str test-dir "/range.txt")
        content "line1\nline2\nline3\nline4\nline5"]
    (spit path content)
    (testing "middle range"
      (is (= (sorted-map 2 "line2" 3 "line3")
             (tools/read-file path 2 3))))
    (testing "single line"
      (is (= (sorted-map 4 "line4")
             (tools/read-file path 4 4))))
    (testing "full range"
      (is (= (sorted-map 1 "line1" 2 "line2" 3 "line3" 4 "line4" 5 "line5")
             (tools/read-file path 1 5))))
    (testing "clamped to bounds"
      (is (= (sorted-map 4 "line4" 5 "line5")
             (tools/read-file path 4 100))))))

(deftest read-file-range-not-found
  (let [result (tools/read-file (str test-dir "/missing.txt") 1 5)]
    (is (contains? result :error))))

;; =============================================================================
;; write-file tests
;; =============================================================================

(deftest write-file-success
  (let [path (str test-dir "/write-test.txt")
        content "Test content"]
    (is (= {:ok path} (tools/write-file path content)))
    (is (= content (slurp path)))))

(deftest write-file-creates-dirs
  (let [path (str test-dir "/nested/deep/dir/file.txt")
        content "Nested content"]
    (is (= {:ok path} (tools/write-file path content)))
    (is (= content (slurp path)))))

(deftest write-file-overwrites
  (let [path (str test-dir "/overwrite.txt")]
    (spit path "original")
    (is (= {:ok path} (tools/write-file path "new content")))
    (is (= "new content" (slurp path)))))

(deftest write-file-unicode
  (let [path (str test-dir "/unicode-write.txt")
        content "写文件 📝"]
    (is (= {:ok path} (tools/write-file path content)))
    (is (= content (slurp path)))))

(deftest write-file-multiline
  (let [path (str test-dir "/multiline-write.txt")
        content "Line 1\nLine 2\n\nLine 4"]
    (is (= {:ok path} (tools/write-file path content)))
    (is (= content (slurp path)))))

;; =============================================================================
;; str-replace tests
;; =============================================================================

(deftest str-replace-success
  (let [path (str test-dir "/replace.txt")]
    (spit path "(def x 1)\n(def y 2)")
    (is (= {:ok path} (tools/str-replace path "(def x 1)" "(def x 42)")))
    (is (= "(def x 42)\n(def y 2)" (slurp path)))))

(deftest str-replace-not-found
  (let [path (str test-dir "/replace-notfound.txt")]
    (spit path "some content")
    (let [result (tools/str-replace path "nonexistent" "new")]
      (is (contains? result :error))
      (is (re-find #"not found" (:error result))))))

(deftest str-replace-not-unique
  (let [path (str test-dir "/replace-dup.txt")]
    (spit path "foo bar foo baz")
    (let [result (tools/str-replace path "foo" "qux")]
      (is (contains? result :error))
      (is (re-find #"2 times" (:error result))))))

(deftest str-replace-file-not-found
  (let [result (tools/str-replace (str test-dir "/missing.txt") "a" "b")]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest str-replace-multiline
  (let [path (str test-dir "/replace-multi.txt")]
    (spit path "function foo() {\n  return 1;\n}")
    (is (= {:ok path}
           (tools/str-replace path
                              "function foo() {\n  return 1;\n}"
                              "function foo() {\n  return 42;\n}")))
    (is (= "function foo() {\n  return 42;\n}" (slurp path)))))

(deftest str-replace-preserves-rest
  (let [path (str test-dir "/preserve.txt")
        before "# Header\n\n(def target 1)\n\n# Footer"
        after "# Header\n\n(def target 999)\n\n# Footer"]
    (spit path before)
    (is (= {:ok path} (tools/str-replace path "(def target 1)" "(def target 999)")))
    (is (= after (slurp path)))))

(deftest str-replace-special-chars
  (let [path (str test-dir "/special.txt")]
    (spit path "regex chars: $^.*+?()[]{}|\\")
    (is (= {:ok path}
           (tools/str-replace path
                              "regex chars: $^.*+?()[]{}|\\"
                              "replaced!")))
    (is (= "replaced!" (slurp path)))))

;; =============================================================================
;; replace-lines tests
;; =============================================================================

(deftest replace-lines-single
  (let [path (str test-dir "/rl-single.txt")]
    (spit path "aaa\nbbb\nccc\n")
    (is (= {:ok path} (tools/replace-lines path 2 2 "BBB")))
    (is (= "aaa\nBBB\nccc\n" (slurp path)))))

(deftest replace-lines-range
  (let [path (str test-dir "/rl-range.txt")]
    (spit path "line1\nline2\nline3\nline4\nline5\n")
    (is (= {:ok path} (tools/replace-lines path 2 4 "new2\nnew3")))
    (is (= "line1\nnew2\nnew3\nline5\n" (slurp path)))))

(deftest replace-lines-delete
  (let [path (str test-dir "/rl-delete.txt")]
    (spit path "keep\nremove\nkeep\n")
    (is (= {:ok path} (tools/replace-lines path 2 2 "")))
    (is (= "keep\nkeep\n" (slurp path)))))

(deftest replace-lines-first-line
  (let [path (str test-dir "/rl-first.txt")]
    (spit path "old\nrest\n")
    (is (= {:ok path} (tools/replace-lines path 1 1 "new")))
    (is (= "new\nrest\n" (slurp path)))))

(deftest replace-lines-last-line
  (let [path (str test-dir "/rl-last.txt")]
    (spit path "rest\nold\n")
    (is (= {:ok path} (tools/replace-lines path 2 2 "new")))
    (is (= "rest\nnew\n" (slurp path)))))

(deftest replace-lines-out-of-range
  (let [path (str test-dir "/rl-bounds.txt")]
    (spit path "one\ntwo\n")
    (testing "start out of range"
      (is (contains? (tools/replace-lines path 0 1 "x") :error))
      (is (contains? (tools/replace-lines path 5 5 "x") :error)))
    (testing "end out of range"
      (is (contains? (tools/replace-lines path 1 5 "x") :error)))))

(deftest replace-lines-file-not-found
  (let [result (tools/replace-lines (str test-dir "/missing.txt") 1 1 "x")]
    (is (contains? result :error))
    (is (re-find #"not found" (:error result)))))

(deftest replace-lines-preserves-no-trailing-newline
  (let [path (str test-dir "/rl-no-nl.txt")]
    (spit path "aaa\nbbb\nccc")
    (is (= {:ok path} (tools/replace-lines path 2 2 "BBB")))
    (is (= "aaa\nBBB\nccc" (slurp path)))))

;; =============================================================================
;; Tool metadata tests
;; =============================================================================

(deftest tool-metadata-complete
  (doseq [tool tools/file-tools]
    (testing (:name tool)
      (is (symbol? (:name tool)))
      (is (fn? (:fn tool)))
      (is (string? (:doc tool)))
      (is (not (empty? (:doc tool)))))))

(deftest file-tools-count
  (is (= 4 (count tools/file-tools))))
