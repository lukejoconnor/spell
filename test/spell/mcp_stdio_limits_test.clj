(ns spell.mcp-stdio-limits-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.mcp.client :as client]
            [spell.mcp.stdio :as stdio]))

(defn- clojure-command [expression]
  [(str (System/getProperty "java.home") "/bin/java")
   "-cp" (System/getProperty "java.class.path")
   "clojure.main" "-e" expression])

(defn- exception-type [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:type (ex-data e)))))

(defn- process-stopped? [^Process process timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (not (.isAlive process)) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 10) (recur))
        :else false))))

(defn- exercise-reader-failure! [expression]
  (let [c (client/open-client
           :stdio-failure
           {:transport {:stdio {:command (clojure-command expression)
                                :max-message-bytes 64}}
            :timeout-ms 30000})
        transport (:stdio-transport c)
        process (:process transport)]
    (try
      (let [call (future (exception-type #(client/discover! c)))
            result (deref call 5000 ::timed-out)]
        (is (not= ::timed-out result) "reader failure should fail a pending request promptly")
        (is (= :mcp-stdio-error result))
        (is @(:closed? transport))
        (is (= :stdio-closed (exception-type #(client/discover! c))))
        (is (process-stopped? process 6000) "reader failure should terminate the subprocess"))
      (finally
        (.close ^java.io.Closeable c)))))

(deftest stdout-eof-and-invalid-input-fail-transport-test
  (testing "clean EOF immediately fails pending work and closes the transport"
    (exercise-reader-failure! "(do (read-line) nil)"))
  (testing "the stdout limit counts UTF-8 bytes, not characters"
    (exercise-reader-failure!
     "(do (read-line) (print (apply str (repeat 30 \"界\"))) (flush) (Thread/sleep 10000))"))
  (testing "malformed UTF-8 fails the transport"
    (exercise-reader-failure!
     (str "(do (read-line) "
          "(.write System/out (byte-array [(unchecked-byte 255) (byte 10)])) "
          "(.flush System/out) (Thread/sleep 10000))"))))

(deftest stderr-lines-are-bounded-and-drained-test
  (let [expression
        (str "(do (require '[clojure.data.json :as json]) "
             "(binding [*out* *err*] (println (apply str (repeat 128 \"x\"))) (flush)) "
             "(let [request (json/read-str (read-line))] "
             "(println (json/write-str {\"jsonrpc\" \"2.0\" \"id\" (get request \"id\") "
             "\"result\" {\"resultType\" \"complete\" \"ttlMs\" 0 "
             "\"cacheScope\" \"private\" \"supportedVersions\" [\"2026-07-28\"] "
             "\"capabilities\" {}}})) (flush)))")]
    (with-open [c (client/open-client
                   :stderr-limit
                   {:transport {:stdio {:command (clojure-command expression)
                                        :stderr-max-line-bytes 16}}
                    :timeout-ms 10000})]
      (is (= ["2026-07-28"] (get (client/discover! c) "supportedVersions")))
      (let [transport (:stdio-transport c)
            deadline (+ (System/currentTimeMillis) 2000)
            tail (loop []
                   (let [tail (stdio/stderr-tail transport)]
                     (if (or (seq tail) (>= (System/currentTimeMillis) deadline))
                       tail
                       (do (Thread/sleep 10) (recur)))))]
        (is (= 1 (count tail)))
        (is (str/starts-with? (first tail) (apply str (repeat 16 "x"))))
        (is (str/ends-with? (first tail) "… [truncated stderr line]"))))))
