(ns spell.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [spell.web :as web]))

(deftest config-loads-overrides
  (let [f (java.io.File/createTempFile "spell-web-config-" ".edn")]
    (try
      (spit f "{:search {:max-results 3 :backend :duckduckgo} :fetch {:timeout-ms 1234}}")
      (binding [web/*config-path* (.getAbsolutePath f)]
        (let [cfg (:out (web/config))]
          (is (= 3 (get-in cfg [:search :max-results])))
          (is (= 1234 (get-in cfg [:fetch :timeout-ms])))
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
    (with-redefs [web/http-get-text (fn [_ _ _] {:ok true :err nil :truncated false :out html})]
      (let [result (web/search "clojure" {:max-results 2 :backend :duckduckgo})]
        (is (contains? result :ok))
        (is (= 2 (count (:out result))))
        (is (= "Functions - Clojure" (get-in result [:out 0 :title])))
        (is (= "https://clojure.org/guides/learn/functions"
               (get-in result [:out 0 :url])))
        (is (= "https://example.com/transducers"
               (get-in result [:out 1 :url])))))))

(deftest search-detects-duckduckgo-captcha
  (let [captcha-html "<html><body>
                      <div class='anomaly-modal__mask'>
                        <div class='anomaly-modal__modal'>
                          <div class='anomaly-modal__title'>Unfortunately, bots use DuckDuckGo too.</div>
                          <div class='anomaly-modal__description'>Please complete the following challenge.</div>
                        </div>
                      </div>
                      </body></html>"]
    (with-redefs [web/http-get-text (fn [_ _ _] {:ok true :err nil :truncated false :out captcha-html})]
      (let [result (web/search "test query" {:backend :duckduckgo})]
        (is (contains? result :err))
        (is (str/includes? (:err result) "CAPTCHA"))))))

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
                                         {:ok true :err nil :truncated false :out serper-response})]
        (binding [web/*config-path* (.getAbsolutePath cfg-file)]
          (let [result (web/search "clojure" {:backend :serper})]
            (is (contains? result :ok))
            (is (= 2 (count (:out result))))
            (is (= "Clojure - Functional Programming" (get-in result [:out 0 :title])))
            (is (= "https://clojure.org" (get-in result [:out 0 :url])))
            (is (= "Clojure is a dynamic functional language." (get-in result [:out 0 :snippet])))
            (is (= "https://clojure.org/guides/learn" (get-in result [:out 1 :url]))))))
      (finally
        (.delete cfg-file)))))

(deftest search-serper-requires-api-key
  (with-redefs [web/serper-api-key (fn [_] nil)]
    (binding [web/*config-path* "/dev/null"]
      (let [result (web/search "test" {:backend :serper})]
        (is (contains? result :err))
        (is (str/includes? (:err result) "SERPER_API_KEY"))))))

(deftest search-serper-handles-api-error
  (let [cfg-file (java.io.File/createTempFile "spell-web-serper-" ".edn")]
    (try
      (spit cfg-file "{:search {:serper-api-key \"bad-key\"}}")
      (with-redefs [web/http-post-json (fn [_ _ _ _] {:ok false :out nil :truncated false :err "HTTP 401 from https://google.serper.dev/search: Unauthorized"})]
        (binding [web/*config-path* (.getAbsolutePath cfg-file)]
          (let [result (web/search "test" {:backend :serper})]
            (is (contains? result :err))
            (is (str/includes? (:err result) "401")))))
      (finally
        (.delete cfg-file)))))

(deftest search-defaults-to-serper-when-key-available
  (let [serper-response {:organic [{:title "Serper result"
                                    :link "https://serper.example"
                                    :snippet "from serper"}]}]
    (with-redefs [web/serper-api-key (fn [_] "env-or-config-key")
                  web/http-post-json (fn [_ _ _ _] {:ok true :err nil :truncated false :out serper-response})
                  web/http-get-text (fn [& _]
                                      (throw (ex-info "DuckDuckGo should not be called" {})))]
      (binding [web/*config-path* "/dev/null"]
        (let [result (web/search "test")]
          (is (contains? result :ok))
          (is (= "https://serper.example" (get-in result [:out 0 :url]))))))))

(deftest search-defaults-to-duckduckgo-when-key-missing
  (let [duck-html "<html><body>
                   <div class='result'>
                     <a class='result__a' href='https://duck.example'>Duck</a>
                   </div>
                   </body></html>"]
    (with-redefs [web/serper-api-key (fn [_] nil)
                  web/http-get-text (fn [_ _ _] {:ok true :err nil :truncated false :out duck-html})
                  web/http-post-json (fn [& _]
                                       (throw (ex-info "Serper should not be called" {})))]
      (binding [web/*config-path* "/dev/null"]
        (let [result (web/search "test")]
          (is (contains? result :ok))
          (is (= "https://duck.example" (get-in result [:out 0 :url]))))))))

(deftest search-prefers-configured-backend-over-runtime-default
  (let [cfg-file (java.io.File/createTempFile "spell-web-backend-" ".edn")
        duck-html "<html><body><div class='result'><a class='result__a' href='https://duck.example'>Duck</a></div></body></html>"]
    (try
      (spit cfg-file "{:search {:backend :duckduckgo}}")
      (with-redefs [web/serper-api-key (fn [_] "available-key")
                    web/http-get-text (fn [_ _ _] {:ok true :err nil :truncated false :out duck-html})
                    web/http-post-json (fn [& _]
                                         (throw (ex-info "Serper should not be called" {})))]
        (binding [web/*config-path* (.getAbsolutePath cfg-file)]
          (let [result (web/search "test")]
            (is (contains? result :ok))
            (is (= "https://duck.example" (get-in result [:out 0 :url]))))))
      (finally
        (.delete cfg-file)))))


(deftest fetch-jina-truncates-content
  (let [content (apply str (repeat 3000 "x"))]
    (with-redefs [web/http-get-text (fn [url _ _]
                                      (if (str/includes? url "r.jina.ai")
                                        {:ok true :err nil :truncated false :out content}
                                        {:ok false :out nil :truncated false :err "unexpected url"}))]
      (let [result (web/fetch "https://example.com" {:max-chars 1000})]
        (is (contains? result :ok))
        (is (= (subs content 0 1000) (:out result)))
        (is (true? (:truncated result)))))))

(deftest fetch-falls-back-to-raw-when-jina-fails
  (let [html "<html><head><title>Example</title></head><body><article><p>Hello world.</p></article></body></html>"]
    (with-redefs [web/http-get-text (fn [url _ _]
                                      (if (str/includes? url "r.jina.ai")
                                        {:ok false :out nil :truncated false :err "jina failed"}
                                        {:ok true :err nil :truncated false :out html}))]
      (let [result (web/fetch "https://example.com" {})]
        (is (= "# Example\n\nHello world.\n\nSource: https://example.com" (:out result)))))))

(deftest fetch-is-full-unless-explicitly-clipped
  (let [content (str (apply str (repeat 50000 "x")) "😀 tail\n")
        response {:ok true :out content :err nil :truncated false :status 200}]
    (with-redefs [web/effective-config (constantly {:fetch {:backend :jina}})
                  web/http-get-text (fn [& _] response)]
      (is (= response (web/fetch "https://example.com")))
      (doseq [[limit expected] [[0 ""] [1 "x"] [50001 (subs content 0 50000)]
                                [50002 (subs content 0 50002)]]]
        (let [result (web/fetch "https://example.com" {:max-chars limit})]
          (is (= expected (:out result)))
          (is (true? (:truncated result)))
          (is (= 200 (:status result)))
          (is (true? (:ok result)))))
      (is (= response (web/fetch "https://example.com" {:max-chars (count content)})))
      (doseq [limit [-1 1.5 "4"]]
        (is (thrown? IllegalArgumentException (web/fetch "https://example.com" {:max-chars limit})))))))

(deftest fetch-failure-retains-http-evidence
  (let [body (apply str (repeat 45000 "e"))
        failed {:ok false :out body :err "HTTP 503" :truncated false :status 503}]
    (with-redefs [web/http-get-text (fn [& _] failed)]
      (is (= failed (web/fetch "https://example.com" {:backend :raw})))
      (is (= (assoc failed :out "eee" :truncated true)
             (web/fetch "https://example.com" {:backend :raw :max-chars 3}))))
    (with-redefs [web/http-get-text (fn [url & _]
                                    (if (str/includes? url "r.jina.ai") failed
                                        {:ok false :out nil :err "offline" :truncated false}))]
      (let [result (web/fetch "https://example.com" {:backend :jina})]
        (is (= 503 (:status result)))
        (is (= body (:out result)))
        (is (false? (:ok result)))
        (is (str/includes? (:err result) "fallback: offline"))))))

(deftest raw-extraction-does-not-drop-paragraphs
  (let [paragraphs (map #(str "<p>paragraph-" % "</p>") (range 200))]
    (with-redefs [web/http-get-text (fn [& _]
                                    {:ok true :out (str "<html><body>" (apply str paragraphs) "</body></html>")
                                     :err nil :truncated false :status 201})]
      (let [result (web/fetch "https://example.com" {:backend :raw})]
        (is (= 201 (:status result)))
        (is (str/includes? (:out result) "paragraph-199"))
        (is (false? (:truncated result)))))))

(deftest search-preserves-status-and-failure-body
  (let [captcha "<html><div class='anomaly-modal'>captcha body</div></html>"
        failure {:ok false :out "upstream body" :err "HTTP 502" :truncated false :status 502}]
    (with-redefs [web/http-get-text (fn [& _] {:ok true :out captcha :err nil :truncated false :status 200})]
      (let [result (web/search "query" {:backend :duckduckgo})]
        (is (false? (:ok result)))
        (is (= captcha (:out result)))
        (is (= 200 (:status result)))
        (is (str/includes? (:err result) "CAPTCHA"))))
    (with-redefs [web/http-get-text (fn [& _] failure)]
      (is (= failure (web/search "query" {:backend :duckduckgo}))))
    (with-redefs [web/serper-api-key (constantly "test-key")
                  web/http-post-json (fn [& _] failure)]
      (is (= failure (web/search "query" {:backend :serper}))))
    (with-redefs [web/serper-api-key (constantly "test-key")
                  web/http-post-json (fn [& _]
                                       {:ok true :out {:organic [{:title "T" :link "https://example.com"}]}
                                        :err nil :truncated false :status 201})]
      (let [result (web/search "query" {:backend :serper})]
        (is (= 201 (:status result)))
        (is (= [{:title "T" :url "https://example.com" :snippet ""}] (:out result))))))
  (doseq [result [(web/search "") (web/fetch "not a url") (web/search "q" {:backend :unknown})]]
    (is (false? (:ok result)))
    (is (string? (:err result)))
    (is (not (contains? result :status)))))

(deftest actual-http-envelopes-preserve-body-status-and-parse-errors
  (let [server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        handler (fn [status body]
                  (reify com.sun.net.httpserver.HttpHandler
                    (handle [_ exchange]
                      (let [bytes (.getBytes ^String body "UTF-8")]
                        (.sendResponseHeaders exchange status (alength bytes))
                        (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
        body (apply str (repeat 45000 "e"))]
    (.createContext server "/fail" (handler 503 body))
    (.createContext server "/invalid" (handler 200 "not-json\n"))
    (.createContext server "/ok" (handler 200 "  body\n"))
    (.start server)
    (try
      (let [base (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            fail (web/http-get-text (str base "/fail") {} 5000)
            invalid (#'web/http-post-json (str base "/invalid") {} {} 5000)]
        (is (= {:ok true :out "  body\n" :err nil :truncated false :status 200}
               (web/http-get-text (str base "/ok") {} 5000)))
        (is (= body (:out fail)))
        (is (= 503 (:status fail)))
        (is (false? (:ok fail)))
        (is (false? (:truncated fail)))
        (is (false? (:ok invalid)))
        (is (= 200 (:status invalid)))
        (is (= "not-json\n" (:out invalid)))
        (is (str/includes? (:err invalid) "Invalid JSON"))
        (let [post-fail (#'web/http-post-json (str base "/fail") {} {} 5000)]
          (is (= body (:out post-fail)))
          (is (= 503 (:status post-fail)))
          (is (false? (:ok post-fail)))))
      (finally (.stop server 0)))))
