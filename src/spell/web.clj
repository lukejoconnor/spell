(ns spell.web
  "Web search and fetch namespace for Spell agents.

   Designed for the common two-step agent workflow:
   1) search for candidate sources
   2) fetch selected pages as markdown/text"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net URI URLEncoder URLDecoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
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
  {:search {:max-results 5
            :region "us-en"}
   :fetch {:backend :jina
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

(defn- serper-api-key
  "Resolve Serper API key from config, falling back to SERPER_API_KEY env var."
  [cfg]
  (let [configured (get-in cfg [:search :serper-api-key])]
    (if (str/blank? configured)
      (System/getenv "SERPER_API_KEY")
      configured)))

(defn- default-search-backend
  "Choose runtime default backend based on Serper key availability."
  [cfg]
  (if (str/blank? (serper-api-key cfg))
    :duckduckgo
    :serper))

(defn effective-config
  "Return effective web config with defaults applied."
  []
  (let [cfg (deep-merge default-config (load-config-file *config-path*))]
    (if (get-in cfg [:search :backend])
      cfg
      (assoc-in cfg [:search :backend] (default-search-backend cfg)))))

(defn config
  "Inspect effective web tool configuration."
  []
  {:ok true :err nil :out (effective-config)})

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
        {:ok true :err nil :out body :status status}
        {:ok false :out body :err (str "HTTP " status " from " url) :status status}))
    (catch Exception e
      {:ok false :out nil :err (str "HTTP request failed: " (.getMessage e))})))

(defn- http-post-json
  "POST JSON body to url, return parsed JSON response."
  [url headers body-map timeout-ms]
  (try
    (let [body-str (json/write-str body-map)
          builder (HttpRequest/newBuilder (URI/create url))
          builder (reduce (fn [b [k v]]
                            (.header b (str k) (str v)))
                          builder
                          (assoc headers "Content-Type" "application/json"))
          builder (.timeout builder (Duration/ofMillis (long timeout-ms)))
          request (.POST builder (HttpRequest$BodyPublishers/ofString body-str))
          response (.send ^HttpClient @http-client
                          (.build request)
                          (HttpResponse$BodyHandlers/ofString))
          status (.statusCode response)
          body (.body response)]
      (if (<= 200 status 299)
        (try
          {:ok true :out (json/read-str body :key-fn keyword) :err nil :status status}
          (catch Exception e
            {:ok false :out body :err (str "Invalid JSON response: " (.getMessage e)) :status status}))
        {:ok false :out body :err (str "HTTP " status " from " url) :status status}))
    (catch Exception e
      {:ok false :out nil :err (str "HTTP request failed: " (.getMessage e))})))

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
  "Parse search results from DuckDuckGo HTML. Returns {:ok true :err nil :out results} or {:ok false :out nil :err msg}.
   Detects CAPTCHA/bot-detection pages and returns an error instead of empty results."
  [html]
  (let [doc (Jsoup/parse html)]
    ;; DuckDuckGo serves CAPTCHA pages with class 'anomaly-modal' when it suspects bot traffic.
    ;; These pages contain zero .result elements, so without this check we'd silently return [].
    (if (seq (.select doc "[class*=anomaly-modal]"))
      {:ok false :out nil :err "DuckDuckGo returned a CAPTCHA challenge (bot detection). Search is temporarily unavailable."}
      (let [results (.select doc ".result")]
        {:ok true :err nil :out (->> results
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
                  vec)}))))


(defn- search-duckduckgo
  "Search via DuckDuckGo HTML scraping."
  [q cfg max-results]
  (let [region (get-in cfg [:search :region] "us-en")
        url (str "https://html.duckduckgo.com/html/?q="
                 (url-encode q)
                 "&kl="
                 (url-encode region))
        headers {"User-Agent" (get-in cfg [:http :user-agent])
                 "Accept" "text/html,application/xhtml+xml"}
        response (http-get-text url headers (get-in cfg [:fetch :timeout-ms] 15000))]
    (if-not (:ok response)
      response
      (try
        (let [parsed (parse-duckduckgo-results (:out response))]
          (if (:ok parsed)
            (assoc response :out (vec (take max-results (:out parsed))))
            (assoc response :ok false :err (:err parsed))))
        (catch Exception e
          (assoc response :ok false :err (str "Search parsing failed: " (.getMessage e))))))))

(defn- search-serper
  "Search via Serper.dev (Google results). Requires SERPER_API_KEY env var."
  [q cfg max-results]
  (let [api-key (serper-api-key cfg)]
    (if (str/blank? api-key)
      {:ok false :out nil :err "Serper search requires SERPER_API_KEY environment variable or :serper-api-key in config."}
      (let [response (http-post-json
                      "https://google.serper.dev/search"
                      {"X-API-KEY" api-key}
                      {"q" q "num" max-results}
                      (get-in cfg [:fetch :timeout-ms] 15000))]
        (if-not (:ok response)
          response
          (try
            (assoc response :out (->> (get-in response [:out :organic] [])
                                     (map (fn [r] {:title (or (:title r) "")
                                                  :url (or (:link r) "")
                                                  :snippet (or (:snippet r) "")}))
                                     (filter #(seq (:url %)))
                                     (take max-results)
                                     vec))
            (catch Exception e
              (assoc response :ok false :err (str "Search parsing failed: " (.getMessage e))))))))))

(defn search
  "Search the web. Returns {:ok true :err nil :out [{:title :url :snippet} ...]} or {:ok false :out nil :err msg}.
   Supported backends: :serper, :duckduckgo.
   Backend precedence: opts :backend > config :search :backend > runtime default
   (:serper when SERPER_API_KEY is available, else :duckduckgo)."
  ([query] (search query {}))
  ([query opts]
   (let [q (str/trim (str query))]
     (if (str/blank? q)
       {:ok false :out nil :err "web/search query must be non-empty"}
       (let [cfg (effective-config)
             backend (keyword (or (:backend opts)
                                  (get-in cfg [:search :backend])
                                  (default-search-backend cfg)))
             max-results (-> (or (:max-results opts) (get-in cfg [:search :max-results] 5))
                             int
                             (max 1)
                             (min 10))]
         (case backend
           :serper (search-serper q cfg max-results)
           :duckduckgo (search-duckduckgo q cfg max-results)
           {:ok false :out nil :err (str "Unsupported search backend: " backend)}))))))

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
                             (filter seq))
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
    (if-not (:ok response)
      response
      (try
        (assoc response :out (html->markdown url (:out response)))
        (catch Exception e
          (assoc response :ok false :err (str "HTML parsing failed: " (.getMessage e))))))))

(defn fetch
  "Fetch complete markdown/text in {:ok boolean :out payload :err string-or-nil},
   with :status when known. Serialization alone bounds context snapshots."
  ([url] (fetch url {}))
  ([url opts]
   (if-let [normalized (normalize-url url)]
       (let [cfg (effective-config)
             backend (keyword (or (:backend opts) (get-in cfg [:fetch :backend] :jina)))
             fallback-backend (keyword (or (:fallback-backend opts) (get-in cfg [:fetch :fallback-backend] :raw)))
             primary-response (case backend
                                :jina (fetch-via-jina normalized cfg)
                                :raw (fetch-via-raw normalized cfg)
                                {:ok false :out nil :err (str "Unsupported fetch backend: " backend)})
             response (if (and (not (:ok primary-response)) (= backend :jina) (= fallback-backend :raw))
                        (let [fallback (fetch-via-raw normalized cfg)]
                          ;; Keep the available HTTP evidence if fallback failed before receiving a response.
                          (if (and (not (:ok fallback)) (not (contains? fallback :status))
                                   (contains? primary-response :status))
                            (assoc primary-response :err (str (:err primary-response) "; fallback: " (:err fallback)))
                            fallback))
                        primary-response)]
         response)
       {:ok false :out nil :err (str "Invalid URL: " (pr-str url))})))

;; =============================================================================
;; Namespace definition for Spell
;; =============================================================================

(def web-namespace
  "The web/ namespace map for Spell agents."
  {:short-docs "Web search and URL fetch tools."
   :docs
   {:guide "WEB — Search and fetch web content.

Raw operations return {:ok boolean :out payload :err string-or-nil}.
HTTP-backed results retain :status when known, including parser/CAPTCHA failures.
Failures retain available response bodies in :out. Tools return full requested values.
Serialization adds :truncated false or true to bounded context snapshots; an existing
true flag remains true. Set display limits at the serialization boundary.

  (web/search query)        — search web and return [{:title :url :snippet} ...]
  (web/fetch url)           — fetch URL and return markdown/text
  (web/config)              — inspect active web config

Recommended usage pattern: Search, then fetch the most relevant result.

1. Search and peek the results.
  ...▌'(!peek results (web/search \"clojure transducers\"))

2. Next turn: results is available. Pick the best URL and fetch it.
  ...(def results {:ok true :err nil :truncated false :out [{:title \"Transducers - Clojure\" :url \"https://clojure.org/reference/transducers\" :snippet \"...\"} ...]})
  (rethink 2 \"!peek call and binding(s) disappear unless you persist what you need.\")
  ▌(persist best-url (get (first (:out results)) :url))
  '(!peek page (web/fetch best-url))"
    :search "Search the web. Returns {:ok true :err nil :out [{:title :url :snippet} ...]} or {:ok false :out nil :err msg}."
    :fetch "Fetch a URL and return markdown/text. Returns {:ok true :err nil :out text} or {:ok false :out nil :err msg}."
    :config "Return an envelope with the effective config map in :out."}
   :detail
   {:search
    "Search backends: :serper (Google results), :duckduckgo (HTML scraping).

(web/search \"clojure transducers\")
(web/search \"spell lisp\" {:max-results 8})
(web/search \"query\" {:backend :duckduckgo})

Returns:
  {:ok true :err nil :out [{:title \"...\" :url \"https://...\" :snippet \"...\"} ...]}

Result count defaults to config max-results (5), clamped to [1, 10].
Backend precedence: opts :backend > config/web.edn :search :backend >
runtime default (:serper when SERPER_API_KEY is available, else :duckduckgo)."

    :fetch
    "Fetch page content as markdown/text.

(web/fetch \"https://clojure.org/reference/transducers\")
(web/fetch \"https://example.com\" {:backend :raw})

Backends:
  :jina — uses https://r.jina.ai/<url> (default)
  :raw  — direct HTTP GET + lightweight HTML-to-markdown extraction

If :jina fails, fetch falls back to :raw by default.
No character or paragraph display cap applies. Serialization adds :truncated to the
context snapshot; raw results have no truncation flag."

    :config
    "Inspect effective config.

(web/config)

Config defaults:
  {:search {:max-results 5 :region \"us-en\"}
   :fetch  {:backend :jina :timeout-ms 15000 :fallback-backend :raw}
   :http   {:user-agent \"spell-web/0.1 ...\"}}

Set :search :backend in config/web.edn to force a backend.
You can override defaults by creating config/web.edn."}
   :search search
   :fetch fetch
   :config config})
