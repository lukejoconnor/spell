(ns spell.receive-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [spell.coordinator :as c]
            [spell.runtime :as runtime]))

(use-fixtures :each
  (fn [f] (binding [c/*coordinator* (c/new-coordinator)]
            (try (f) (finally (c/close!))))))

(def program
  '(quine completion (eval (do (quine prompt "task") (quote (!extend))))))

;; If receive ever evaluated the program, this form would throw.
(def program-with-trap
  '(quine completion (eval (do (quine prompt "task")
                               (throw (ex-info "receive must not evaluate" {}))
                               (quote (!extend))))))

(defn- receive-as [handle program]
  (binding [runtime/*current-handle* handle] (runtime/receive program)))

(defn- signal-of [handle] (:signal (c/agent handle)))

(deftest empty-inbox-receive-is-identity
  (c/register! :a)
  (let [before (signal-of :a)
        out (receive-as :a program)]
    (is (= program out))
    (is (empty? (:mailbox (c/agent :a))))
    (is (not (identical? before (signal-of :a))) "a valid receive takes one batch and rotates the signal")))

(deftest plain-message-receive-transforms-without-effects
  (c/register! :a)
  (c/register! :b)
  (c/send! :a {:message {:from :b :body "hello from b"}})
  (c/send! :a {:message {:from :b :body "second message"}})
  (let [out (receive-as :a program-with-trap)
        s (pr-str out)]
    (is (not= program-with-trap out))
    (is (= 'quine (first out)))
    (is (str/includes? s "hello from b"))
    (is (str/includes? s "second message"))
    (is (< (str/index-of s "hello from b") (str/index-of s "second message")) "message order preserved")
    (is (empty? (:mailbox (c/agent :a))))
    (is (= program-with-trap (receive-as :a program-with-trap)) "batch consumed exactly once")))

(deftest invalid-input-leaves-coordinator-state-untouched
  (c/register! :a)
  (c/register! :b)
  (c/send! :a {:message {:from :b :body "unread"}})
  (let [mailbox (:mailbox (c/agent :a))
        signal (signal-of :a)]
    (is (= 1 (count mailbox)))
    (testing "wrong/empty/non-quine shapes are rejected before dequeue"
      (doseq [bad [nil '() "text" '(+ 1 2) '(quine completion)
                   '(quine completion (eval (+ 1 2)))
                   '(quine completion (eval (do 1) extra))]]
        (is (thrown? clojure.lang.ExceptionInfo (receive-as :a bad)) (pr-str bad))))
    (testing "no active agent context"
      (is (thrown? clojure.lang.ExceptionInfo
                   (binding [runtime/*current-handle* nil] (runtime/receive program)))))
    (testing "computation futures are excluded"
      (is (thrown? clojure.lang.ExceptionInfo
                   (binding [runtime/*current-handle* :a
                             runtime/*computation-future?* true]
                     (runtime/receive program)))))
    (is (= mailbox (:mailbox (c/agent :a))) "queue not drained")
    (is (identical? signal (signal-of :a)) "signal not rotated")))

(deftest tracked-request-receipt-claims-slot-exactly-once
  (c/register! :a)
  (c/register! :b)
  (let [id (c/request! :a [:b] true :question)
        gen-path [:edges id :slots :b :generation]]
    (is (= id (get-in (c/snapshot) [:agents :b :mailbox 0 :request-edge])))
    (is (nil? (get-in (c/snapshot) gen-path)))
    (is (thrown? clojure.lang.ExceptionInfo (receive-as :b "not a quine")))
    (is (nil? (get-in (c/snapshot) gen-path)) "invalid receive does not claim")
    (is (= 1 (count (:mailbox (c/agent :b)))))
    (let [out (receive-as :b program)
          gen (get-in (c/snapshot) gen-path)]
      (is (not= program out))
      (is (some? gen))
      (is (= (:generation (c/agent :b)) gen))
      (is (= :pending (get-in (c/snapshot) [:edges id :slots :b :status])))
      (is (empty? (:mailbox (c/agent :b))))
      (is (= program (receive-as :b program)) "second receive sees an empty inbox")
      (is (= gen (get-in (c/snapshot) gen-path)) "claimed exactly once"))))

(deftest unregistered-handle-receive-does-not-mutate
  (c/register! :a)
  (c/register! :b)
  (c/send! :a {:message {:from :b :body "unread"}})
  (let [mailbox (:mailbox (c/agent :a))
        signal (signal-of :a)]
    (is (thrown? clojure.lang.ExceptionInfo (receive-as :nobody program)))
    (is (= mailbox (:mailbox (c/agent :a))))
    (is (identical? signal (signal-of :a)))))

(deftest invalid-shape-error-is-receive-specific
  (c/register! :a)
  (let [e (try (receive-as :a "text") nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (str/starts-with? (ex-message e) "receive expects a canonical completed quine"))
    (is (= :a (:handle (ex-data e))))))
