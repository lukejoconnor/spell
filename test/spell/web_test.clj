(ns spell.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [spell.web :as web]))

(deftest config-loads-overrides
  (let [f (java.io.File/createTempFile "spell-web-config-" ".edn")]
    (try
      (spit f "{:search {:max-results 3 :backend :duckduckgo} :fetch {:max-chars 1234}}")
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
      (let [result (web/search "clojure" {:max-results 2 :backend :duckduckgo})]
        (is (contains? result :ok))
        (is (= 2 (count (:ok result))))
        (is (= "Functions - Clojure" (get-in result [:ok 0 :title])))
        (is (= "https://clojure.org/guides/learn/functions"
               (get-in result [:ok 0 :url])))
        (is (= "https://example.com/transducers"
               (get-in result [:ok 1 :url])))))))

(deftest search-detects-duckduckgo-captcha
  (let [captcha-html "<html><body>
                      <div class='anomaly-modal__mask'>
                        <div class='anomaly-modal__modal'>
                          <div class='anomaly-modal__title'>Unfortunately, bots use DuckDuckGo too.</div>
                          <div class='anomaly-modal__description'>Please complete the following challenge.</div>
                        </div>
                      </div>
                      </body></html>"]
    (with-redefs [web/http-get-text (fn [_ _ _] {:ok captcha-html})]
      (let [result (web/search "test query" {:backend :duckduckgo})]
        (is (contains? result :error))
        (is (str/includes? (:error result) "CAPTCHA"))))))

(deftest search-serper-parses-response
  (let [serper-response {:organic [{:title "Clojure - Functional Programming"
                                    :link "https://clojure.org"
                                    :snippet "Clojure is a dynamic functional language."
                                    :position 1}
                                   {:title "Learn Clojure"
                                    :link "https://clojure.org/guides/learn"
                                    :snippet "Getting started with Clojure."
                                    :position 2}]
                         :searchParameters {:q "clojure"}}
        cfg-file (java.io.File/createTempFile "spell-web-serper-" ".edn")]
    (try
      (spit cfg-file "{:search {:serper-api-key \"test-key-123\"}}")
      (with-redefs [web/http-post-json (fn [url headers _ _]
                                         (is (= "https://google.serper.dev/search" url))
                                         (is (= "test-key-123" (get headers "X-API-KEY")))
                                         {:ok serper-response})]
        (binding [web/*config-path* (.getAbsolutePath cfg-file)]
          (let [result (web/search "clojure" {:backend :serper})]
            (is (contains? result :ok))
            (is (= 2 (count (:ok result))))
            (is (= "Clojure - Functional Programming" (get-in result [:ok 0 :title])))
            (is (= "https://clojure.org" (get-in result [:ok 0 :url])))
            (is (= "Clojure is a dynamic functional language." (get-in result [:ok 0 :snippet])))
            (is (= "https://clojure.org/guides/learn" (get-in result [:ok 1 :url]))))))
      (finally
        (.delete cfg-file)))))

(deftest search-serper-requires-api-key
  (with-redefs [web/serper-api-key (fn [_] nil)]
    (binding [web/*config-path* "/dev/null"]
      (let [result (web/search "test" {:backend :serper})]
        (is (contains? result :error))
        (is (str/includes? (:error result) "SERPER_API_KEY"))))))

(deftest search-serper-handles-api-error
  (let [cfg-file (java.io.File/createTempFile "spell-web-serper-" ".edn")]
    (try
      (spit cfg-file "{:search {:serper-api-key \"bad-key\"}}")
      (with-redefs [web/http-post-json (fn [_ _ _ _] {:error "HTTP 401 from https://google.serper.dev/search: Unauthorized"})]
        (binding [web/*config-path* (.getAbsolutePath cfg-file)]
          (let [result (web/search "test" {:backend :serper})]
            (is (contains? result :error))
            (is (str/includes? (:error result) "401")))))
      (finally
        (.delete cfg-file)))))

(deftest search-defaults-to-serper-when-key-available
  (let [serper-response {:organic [{:title "Serper result"
                                    :link "https://serper.example"
                                    :snippet "from serper"}]}]
    (with-redefs [web/serper-api-key (fn [_] "env-or-config-key")
                  web/http-post-json (fn [_ _ _ _] {:ok serper-response})
                  web/http-get-text (fn [& _]
                                      (throw (ex-info "DuckDuckGo should not be called" {})))]
      (binding [web/*config-path* "/dev/null"]
        (let [result (web/search "test")]
          (is (contains? result :ok))
          (is (= "https://serper.example" (get-in result [:ok 0 :url]))))))))

(deftest search-defaults-to-duckduckgo-when-key-missing
  (let [duck-html "<html><body>
                   <div class='result'>
                     <a class='result__a' href='https://duck.example'>Duck</a>
                   </div>
                   </body></html>"]
    (with-redefs [web/serper-api-key (fn [_] nil)
                  web/http-get-text (fn [_ _ _] {:ok duck-html})
                  web/http-post-json (fn [& _]
                                       (throw (ex-info "Serper should not be called" {})))]
      (binding [web/*config-path* "/dev/null"]
        (let [result (web/search "test")]
          (is (contains? result :ok))
          (is (= "https://duck.example" (get-in result [:ok 0 :url]))))))))

(deftest search-prefers-configured-backend-over-runtime-default
  (let [cfg-file (java.io.File/createTempFile "spell-web-backend-" ".edn")
        duck-html "<html><body><div class='result'><a class='result__a' href='https://duck.example'>Duck</a></div></body></html>"]
    (try
      (spit cfg-file "{:search {:backend :duckduckgo}}")
      (with-redefs [web/serper-api-key (fn [_] "available-key")
                    web/http-get-text (fn [_ _ _] {:ok duck-html})
                    web/http-post-json (fn [& _]
                                         (throw (ex-info "Serper should not be called" {})))]
        (binding [web/*config-path* (.getAbsolutePath cfg-file)]
          (let [result (web/search "test")]
            (is (contains? result :ok))
            (is (= "https://duck.example" (get-in result [:ok 0 :url]))))))
      (finally
        (.delete cfg-file)))))

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
