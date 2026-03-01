(ns spell.web
  "Web search and fetch namespace for Spell agents.

   Designed for the common two-step agent workflow:
   1) search for candidate sources
   2) fetch selected pages as markdown/text"
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net URI URLEncoder URLDecoder]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [org.jsoup Jsoup]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def ^:dynamic *config-path*
  "Path to optional web config EDN.
   Default is project-local config/web.edn."
  "config/web.edn")

(def default-config
  {:search {:backend :duckduckgo
            :max-results 5
            :region "us-en"}
   :fetch {:backend :jina
           :max-chars 40000
           :timeout-ms 15000
           :fallback-backend :raw}
   :http {:user-agent "spell-web/0.1 (+https://github.com/loconnor/spell)"}})

(defn- deep-merge
  "Recursively merge maps. Non-map values in later maps overwrite earlier ones."
  [& maps]
  (reduce (fn [a b]
            (merge-with (fn [x y]
                          (if (and (map? x) (map? y))
                            (deep-merge x y)
                            y))
                        a b))
          {}
          maps))

(defn- load-config-file
  [path]
  (try
    (let [f (File. ^String path)]
      (if (.exists f)
        (let [cfg (edn/read-string (slurp f))]
          (if (map? cfg)
            cfg
            {}))
        {}))
    (catch Exception _
      {})))

(defn effective-config
  "Return effective web config with defaults applied."
  []
  (deep-merge default-config (load-config-file *config-path*)))

(defn config
  "Inspect effective web tool configuration."
  []
  {:ok (effective-config)})

;; =============================================================================
;; HTTP helpers
;; =============================================================================

(def ^:private http-client
  (delay (.build (HttpClient/newBuilder))))

(defn http-get-text
  [url headers timeout-ms]
  (try
    (let [builder (HttpRequest/newBuilder (URI/create url))
          builder (reduce (fn [b [k v]]
                            (.header b (str k) (str v)))
                          builder
                          headers)
          builder (.timeout builder (Duration/ofMillis (long timeout-ms)))
          request (.GET builder)
          response (.send ^HttpClient @http-client
                          (.build request)
                          (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          body (.body response)]
      (if (<= 200 status 299)
        {:ok body :status status}
        {:error (str "HTTP " status " from " url) :status status}))
    (catch Exception e
      {:error (str "HTTP request failed: " (.getMessage e))})))

(defn- url-encode [s]
  (URLEncoder/encode (str s) (str StandardCharsets/UTF_8)))

(defn- url-decode [s]
  (URLDecoder/decode (str s) (str StandardCharsets/UTF_8)))

(defn- parse-query-params
  "Parse query string into a map of key -> value."
  [query]
  (if (str/blank? query)
    {}
    (into {}
          (keep (fn [part]
                  (let [[k v] (str/split part #"=" 2)]
                    (when (seq k)
                      [k (if (nil? v) "" v)]))))
          (str/split query #"&"))))

;; =============================================================================
;; Search
;; =============================================================================

(defn- unwrap-duckduckgo-url
  "DuckDuckGo HTML results often wrap destination links in /l/?uddg=... ."
  [href]
  (cond
    (str/blank? href) nil
    (str/starts-with? href "//duckduckgo.com/l/?")
    (let [wrapped (URI/create (str "https:" href))
          params (parse-query-params (.getRawQuery wrapped))
          target (or (get params "uddg") (get params "rut"))]
      (when (seq target) (url-decode target)))
    (re-matches #"https?://duckduckgo\.com/l/\?.+" href)
    (let [wrapped (URI/create href)
          params (parse-query-params (.getRawQuery wrapped))
          target (or (get params "uddg") (get params "rut"))]
      (when (seq target) (url-decode target)))
    (str/starts-with? href "/l/?")
    (let [wrapped (URI/create (str "https://duckduckgo.com" href))
          params (parse-query-params (.getRawQuery wrapped))
          target (or (get params "uddg") (get params "rut"))]
      (when (seq target) (url-decode target)))
    (str/starts-with? href "//")
    (str "https:" href)
    (re-matches #"https?://.+" href)
    href
    :else
    (str "https://duckduckgo.com" href)))

(defn- parse-duckduckgo-results
  [html]
  (let [doc (Jsoup/parse html)
        results (.select doc ".result")]
    (->> results
         (map (fn [result]
                (let [title-el (.selectFirst result "a.result__a")
                      snippet-el (or (.selectFirst result ".result__snippet")
                                     (.selectFirst result "a.result__snippet"))
                      title (some-> title-el .text str/trim)
                      raw-url (some-> title-el (.attr "href"))
                      url (some-> raw-url unwrap-duckduckgo-url)
                      snippet (some-> snippet-el .text str/trim)]
                  (when (and (seq title) (seq url))
                    {:title title
                     :url url
                     :snippet (or snippet "")}))))
         (remove nil?)
         vec)))

(defn search
  "Search the web. Returns {:ok [{:title :url :snippet} ...]} or {:error msg}.
   Supported backend: :duckduckgo"
  ([query] (search query {}))
  ([query opts]
   (let [q (str/trim (str query))]
     (if (str/blank? q)
       {:error "web/search query must be non-empty"}
       (let [cfg (effective-config)
             backend (keyword (or (:backend opts) (get-in cfg [:search :backend] :duckduckgo)))
             region (or (:region opts) (get-in cfg [:search :region]) "us-en")
             max-results (-> (or (:max-results opts) (get-in cfg [:search :max-results] 5))
                             int
                             (max 1)
                             (min 10))]
         (if (not= backend :duckduckgo)
           {:error (str "Unsupported search backend: " backend)}
           (let [url (str "https://html.duckduckgo.com/html/?q="
                          (url-encode q)
                          "&kl="
                          (url-encode region))
                 headers {"User-Agent" (get-in cfg [:http :user-agent])
                          "Accept" "text/html,application/xhtml+xml"}
                 response (http-get-text url headers (get-in cfg [:fetch :timeout-ms] 15000))]
             (if-let [err (:error response)]
               {:error err}
               {:ok (->> (parse-duckduckgo-results (:ok response))
                         (take max-results)
                         vec)}))))))))

;; =============================================================================
;; Fetch
;; =============================================================================

(defn- normalize-url
  [url]
  (let [u (str/trim (str url))]
    (cond
      (str/blank? u) nil
      (re-matches #"https?://.+" u) u
      ;; Convenience for bare domains.
      (re-matches #"[A-Za-z0-9.-]+\.[A-Za-z]{2,}.*" u) (str "https://" u)
      :else nil)))

(defn- truncate-content
  [text max-chars]
  (if (<= (count text) max-chars)
    text
    (str (subs text 0 max-chars)
         "\n\n[truncated to "
         max-chars
         " chars from "
         (count text)
         " chars]")))

(defn- fetch-via-jina
  [url cfg]
  (let [jina-url (str "https://r.jina.ai/" url)
        headers {"User-Agent" (get-in cfg [:http :user-agent])
                 "Accept" "text/plain"}]
    (http-get-text jina-url headers (get-in cfg [:fetch :timeout-ms] 15000))))

(defn- html->markdown
  [url html]
  (let [doc (Jsoup/parse html)
        title (str/trim (.title doc))
        article-node (or (.selectFirst doc "article")
                         (.body doc))
        paragraphs (if article-node
                     (.select article-node "p")
                     [])
        paragraph-texts (->> paragraphs
                             (map (fn [p]
                                    (-> p .text (str/replace #"\s+" " ") str/trim)))
                             (filter seq)
                             (take 120))
        body-text (if (seq paragraph-texts)
                    (str/join "\n\n" paragraph-texts)
                    (-> (or article-node doc)
                        .text
                        (str/replace #"\s+" " ")
                        str/trim))]
    (str (when (seq title) (str "# " title "\n\n"))
         body-text
         "\n\nSource: "
         url)))

(defn- fetch-via-raw
  [url cfg]
  (let [headers {"User-Agent" (get-in cfg [:http :user-agent])
                 "Accept" "text/html,application/xhtml+xml"}
        response (http-get-text url headers (get-in cfg [:fetch :timeout-ms] 15000))]
    (if-let [err (:error response)]
      {:error err}
      {:ok (html->markdown url (:ok response))})))

(defn fetch
  "Fetch a URL and return markdown/text content.
   Returns {:ok markdown} or {:error msg}."
  ([url] (fetch url {}))
  ([url opts]
   (if-let [normalized (normalize-url url)]
     (let [cfg (effective-config)
           backend (keyword (or (:backend opts) (get-in cfg [:fetch :backend] :jina)))
           fallback-backend (keyword (or (:fallback-backend opts)
                                         (get-in cfg [:fetch :fallback-backend] :raw))
                                     )
           max-chars (-> (or (:max-chars opts) (get-in cfg [:fetch :max-chars] 40000))
                         int
                         (max 500))
           primary-response (case backend
                              :jina (fetch-via-jina normalized cfg)
                              :raw (fetch-via-raw normalized cfg)
                              {:error (str "Unsupported fetch backend: " backend)})
           response (if (and (:error primary-response)
                             (= backend :jina)
                             (= fallback-backend :raw))
                      (fetch-via-raw normalized cfg)
                      primary-response)]
       (if-let [err (:error response)]
         {:error err}
         {:ok (truncate-content (:ok response) max-chars)}))
     {:error (str "Invalid URL: " (pr-str url))})))

;; =============================================================================
;; Namespace definition for Spell
;; =============================================================================

(def web-namespace
  "The web/ namespace map for Spell agents."
  {:short-docs "Web search and URL fetch tools."
   :docs
   {:guide "WEB — Search and fetch web content.

  (web/search query)        — search web and return [{:title :url :snippet} ...]
  (web/fetch url)           — fetch URL and return markdown/text
  (web/config)              — inspect active web config

Default behavior:
- Search backend: DuckDuckGo HTML results
- Fetch backend: Jina Reader (r.jina.ai) with raw HTML fallback
- Truncation: 40,000 chars by default

Configuration is loaded from config/web.edn if present."
    :search "Search the web. Returns {:ok [{:title :url :snippet} ...]} or {:error msg}."
    :fetch "Fetch a URL and return markdown/text. Returns {:ok text} or {:error msg}."
    :config "Return effective web config map with defaults applied."}
   :detail
   {:search
    "Search backend currently supports :duckduckgo.

(web/search \"clojure transducers\")
(web/search \"spell lisp\" {:max-results 8})

Returns:
  {:ok [{:title \"...\" :url \"https://...\" :snippet \"...\"} ...]}

Result count defaults to config max-results (5), clamped to [1, 10]."

    :fetch
    "Fetch page content as markdown/text.

(web/fetch \"https://clojure.org/reference/transducers\")
(web/fetch \"https://example.com\" {:backend :raw :max-chars 12000})

Backends:
  :jina — uses https://r.jina.ai/<url> (default)
  :raw  — direct HTTP GET + lightweight HTML-to-markdown extraction

If :jina fails, fetch falls back to :raw by default."

    :config
    "Inspect effective config.

(web/config)

Config defaults:
  {:search {:backend :duckduckgo :max-results 5 :region \"us-en\"}
   :fetch  {:backend :jina :max-chars 40000 :timeout-ms 15000 :fallback-backend :raw}
   :http   {:user-agent \"spell-web/0.1 ...\"}}

You can override defaults by creating config/web.edn."}
   :search search
   :fetch fetch
   :config config})
