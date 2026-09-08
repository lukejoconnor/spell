(ns spell.trace-export-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [spell.trace :as trace]
            [spell.trace-tool :as trace-tool])
  (:import [java.io FilterWriter IOException PushbackReader]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-dir [f]
  (let [dir (.toFile (Files/createTempDirectory "spell-trace-test-" (make-array FileAttribute 0)))]
    (try (f dir)
         (finally
           (doseq [file (reverse (file-seq dir))] (io/delete-file file true))))))

(defn- read-trace [dir]
  (with-open [reader (PushbackReader. (io/reader (io/file dir "trace.edn")))]
    (let [value (edn/read {:eof ::eof} reader)]
      (is (= ::eof (edn/read {:eof ::eof} reader)))
      value)))

(defn- export-fixture []
  (let [source (with-meta '(quine completion (eval (do '(+ 40 2)))) {:source-line 42})]
    {:root 0 :next-id 3
     :extra {:preserve "arbitrary top-level data"}
     :warnings [{:message "warning\nλ" :data {:source source} :timestamp-ms 101}]
     :nodes [{:id 0 :parent nil :depth 0 :variant :default
              :prompt "(do \"λ\" " :response "'(quote x))" :raw-text "(do '(quote x))\nλ"
              :program source :hooked (list 'do source)
              :value {:nil nil :ratio 2/3 :big 123456789012345678901234567890N
                      :decimal 1.25M :set #{:a :b} :vector [1 2 3 4]
                      :quoted '(quote (!extend)) :deref '(clojure.core/deref x)
                      :var '(var x) :escapes "\\\"\n\tλ"}
              :start-ms 1 :end-ms 2
              :children [{:child-id 1 :call-prompt "leaf"} {:child-id 2 :call-prompt "error"}]}
             {:id 1 :parent 0 :depth 1 :variant :leaf :prompt "leaf"
              :raw-text "leaf bytes\n\"λ\"" :response "leaf bytes" :value nil :children []}
             {:id 2 :parent 0 :depth 1 :variant :default :prompt "error"
              :error "failed\n\"λ\"" :children []}]}))

(defn- expected-trace [value]
  (update value :nodes
          #(mapv (fn [node]
                   (assoc (dissoc node :raw-text) :file
                          (format "%04d%s" (:id node) (if (= :leaf (:variant node)) ".txt" ".spl")))) %)))

(defn- check-export [value dir]
  (let [path (.getPath dir)
        expected-tree (str (trace/tree-str value) "\n")]
    (is (= path (trace/write-trace! value path)))
    (is (= (expected-trace value) (read-trace dir)))
    (is (= {:dir path :trace (expected-trace value)} (trace-tool/load-trace path)))
    (is (= expected-tree (slurp (io/file dir "tree.txt"))))
    (doseq [[name expected] [["0000.spl" (get-in value [:nodes 0 :raw-text])]
                           ["0001.txt" (get-in value [:nodes 1 :raw-text])]]]
      (is (= (seq (.getBytes ^String expected "UTF-8"))
             (seq (Files/readAllBytes (.toPath (io/file dir name)))))))
    (is (not (.exists (io/file dir "0002.spl"))))))

(deftest complete-readable-export-test
  (with-temp-dir
    (fn [dir]
      (let [value (export-fixture)]
        (testing "all records, quoted source, warnings, programs and tree round-trip"
          (check-export value dir))
        (testing "caller printer settings cannot truncate or make trace data unreadable"
          (binding [*print-length* 1 *print-level* 1 *print-meta* true
                    *print-readably* false *print-dup* true]
            (check-export value dir)))
        (testing "existing trace output is overwritten without changing the return path"
          (let [empty-trace {:nodes [] :next-id 0 :root nil}]
            (is (= (.getPath dir) (trace/write-trace! empty-trace (.getPath dir))))
            (is (= empty-trace (read-trace dir)))
            (is (= (str (trace/tree-str empty-trace) "\n") (slurp (io/file dir "tree.txt"))))))))))

(deftype FailingValue [failure])
(defmethod print-method FailingValue [value _]
  (throw (.-failure ^FailingValue value)))

(deftest export-writer-closure-test
  (doseq [fail? [false true]]
    (with-temp-dir
      (fn [dir]
        (let [failure (IOException. "synthetic printer failure")
              closed (atom [])
              original-writer io/writer
              value (cond-> {:nodes [] :next-id 0 :root nil}
                      fail? (assoc :value (FailingValue. failure)))]
          (spit (io/file dir "trace.edn") "previous complete trace\n")
          (with-redefs [io/writer
                        (fn [file & opts]
                          (proxy [FilterWriter] [(apply original-writer file opts)]
                            (close []
                              (swap! closed conj (.getName (io/file file)))
                              (proxy-super close))))]
            (if fail?
              (is (identical? failure
                              (try (trace/write-trace! value (.getPath dir)) nil
                                   (catch IOException ex ex))))
              (is (= (.getPath dir) (trace/write-trace! value (.getPath dir))))))
          (is (some #{"trace.edn"} @closed))
          (if fail?
            (testing "accepted non-atomic tradeoff: printer failure may truncate existing trace data"
              (is (.exists (io/file dir "trace.edn")))
              (is (not= "previous complete trace\n" (slurp (io/file dir "trace.edn"))))
              (is (thrown? RuntimeException
                           (edn/read-string (slurp (io/file dir "trace.edn")))))
              (is (thrown? RuntimeException (trace-tool/load-trace (.getPath dir))))
              (is (not (.exists (io/file dir "tree.txt")))))
            (is (= value (read-trace dir)))))))))
