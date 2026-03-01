(ns spell.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [spell.web :as web]))

(deftest config-loads-overrides
  (let [f (java.io.File/createTempFile "spell-web-config-" ".edn")]
    (try
      (spit f "{:search {:max-results 3} :fetch {:max-chars 1234}}")
      (binding [web/*config-path* (.getAbsolutePath f)]
        (let [cfg (:ok (web/config))]
          (is (= 3 (get-in cfg [:search :max-results])))
          (is (= 1234 (get-in cfg [:fetch :max-chars])))
          (is (= :duckduckgo (get-in cfg [:search :backend])))))
      (finally
        (.delete f)))))

(deftest search-parses-duckduckgo-html
  (let [html "<html><body>
              <div class='result'>
                <a class='result__a' href='/l/?kh=-1&uddg=https%3A%2F%2Fclojure.org%2Fguides%2Flearn%2Ffunctions'>Functions - Clojure</a>
                <a class='result__snippet'>Learn functions in Clojure.</a>
              </div>
              <div class='result'>
                <a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Ftransducers'>Transducers Guide</a>
                <a class='result__snippet'>A practical guide.</a>
              </div>
              </body></html>"]
    (with-redefs [web/http-get-text (fn [_ _ _] {:ok html})]
      (let [result (web/search "clojure" {:max-results 2})]
        (is (contains? result :ok))
        (is (= 2 (count (:ok result))))
        (is (= "Functions - Clojure" (get-in result [:ok 0 :title])))
        (is (= "https://clojure.org/guides/learn/functions"
               (get-in result [:ok 0 :url])))
        (is (= "https://example.com/transducers"
               (get-in result [:ok 1 :url])))))))

(deftest fetch-jina-truncates-content
  (let [content (apply str (repeat 3000 "x"))]
    (with-redefs [web/http-get-text (fn [url _ _]
                                      (if (str/includes? url "r.jina.ai")
                                        {:ok content}
                                        {:error "unexpected url"}))]
      (let [result (web/fetch "https://example.com" {:max-chars 1000})]
        (is (contains? result :ok))
        (is (str/includes? (:ok result) "[truncated to 1000 chars"))))))

(deftest fetch-falls-back-to-raw-when-jina-fails
  (let [html "<html><head><title>Example</title></head><body><article><p>Hello world.</p></article></body></html>"]
    (with-redefs [web/http-get-text (fn [url _ _]
                                      (if (str/includes? url "r.jina.ai")
                                        {:error "jina failed"}
                                        {:ok html}))]
      (let [result (web/fetch "https://example.com" {})]
        (is (= "# Example\n\nHello world.\n\nSource: https://example.com" (:ok result)))))))
