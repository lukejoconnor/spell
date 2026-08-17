(ns spell.skills
  "Agent Skills discovery and prompt-only namespace generation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.net JarURLConnection]
           [org.yaml.snakeyaml Yaml LoaderOptions]
           [org.yaml.snakeyaml.constructor SafeConstructor]))

(def ^:private max-diagnostics 20)
(def ^:private max-diagnostic-chars 300)
(def ^:private max-catalog-chars 8000)
(def ^:private skill-name-pattern #"^[a-z0-9]+(?:-[a-z0-9]+)*$")

(defn- canonical-path [^File file]
  (try (.getCanonicalPath file)
       (catch Exception _ (.getAbsolutePath file))))
(defn- shorten [s limit]
  (let [s (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (if (> (count s) limit)
      (str (subs s 0 (max 0 (- limit 3))) "...")
      s)))

(defn- bounded-message [x]
  (shorten x max-diagnostic-chars))

(defn- diagnostic [path message]
  {:path (shorten (canonical-path path) max-diagnostic-chars)
   :message (bounded-message message)})

(defn- git-worktree-root [^File cwd]
  (loop [dir (.getCanonicalFile cwd)]
    (cond
      (.exists (io/file dir ".git")) dir
      (.getParentFile dir) (recur (.getParentFile dir))
      :else nil)))

(defn- bundled-marker []
  (io/resource ".spell-skills-root"))

(defn discovery-roots
  "Return deterministic filesystem skill roots: cwd-to-worktree .agents/skills, then HOME.
  Outside a Git worktree only cwd/.agents/skills is searched. Bundled classpath skills are
  discovered separately so this function behaves identically from source and packaged jars."
  ([]
   (discovery-roots (io/file (System/getProperty "user.dir"))
                    (some-> (or (System/getenv "HOME")
                                (System/getProperty "user.home"))
                            io/file)))
  ([^File cwd ^File home]
   (let [cwd (.getCanonicalFile cwd)
         worktree (git-worktree-root cwd)
         ancestors (if worktree
                     (take-while (fn [^File f]
                                   (or (= (canonical-path f) (canonical-path worktree))
                                       (str/starts-with? (canonical-path f)
                                                         (str (canonical-path worktree) File/separator))))
                                 (take-while some? (iterate #(.getParentFile ^File %) cwd)))
                     [cwd])
         roots (concat
                (map #(io/file ^File % ".agents" "skills") ancestors)
                (when home [(io/file home ".agents" "skills")]))]
     (vec roots))))

(defn- frontmatter [text]
  (let [lines (str/split text #"\r?\n" -1)]
    (when-not (= "---" (first lines))
      (throw (ex-info "SKILL.md must start with YAML frontmatter delimited by ---" {})))
    (let [end (first (keep-indexed #(when (and (pos? %1) (= "---" %2)) %1) lines))]
      (when-not end
        (throw (ex-info "SKILL.md frontmatter is missing its closing ---" {})))
      (str/join "\n" (subvec (vec lines) 1 end)))))

(defn- parse-yaml [yaml-text]
  (let [options (doto (LoaderOptions.)
                  (.setAllowDuplicateKeys false)
                  (.setMaxAliasesForCollections 20)
                  (.setCodePointLimit 100000))
        parsed (.load (Yaml. (SafeConstructor. options)) yaml-text)]
    (when-not (instance? java.util.Map parsed)
      (throw (ex-info "SKILL.md frontmatter must be a YAML mapping" {})))
    (into {} parsed)))

(defn- required-string [metadata key-name]
  (let [value (get metadata key-name)]
    (when-not (and (string? value) (not (str/blank? value)))
      (throw (ex-info (str "SKILL.md frontmatter requires non-blank " key-name) {})))
    (str/trim value)))
(defn- load-skill-text [dirname path directory root text]
  (let [metadata (parse-yaml (frontmatter text))
        name (required-string metadata "name")
        description (required-string metadata "description")]
    (when-not (and (<= (count name) 64)
                   (re-matches skill-name-pattern name))
      (throw (ex-info
              "skill name must be 1-64 lowercase letters, digits, and single hyphens"
              {:name name})))
    (when-not (= name dirname)
      (throw (ex-info (str "skill name " (pr-str name)
                           " must agree with directory name " (pr-str dirname))
                      {:name name :directory dirname})))
    (when (> (count description) 1024)
      (throw (ex-info "skill description must be at most 1024 characters"
                      {:name name :description-length (count description)})))
    {:name name
     :description description
     :path path
     :directory directory
     :root root
     :content text}))

(defn- load-skill [^File source-root ^File dir]
  (let [file (io/file dir "SKILL.md")]
    (load-skill-text (.getName dir)
                     (canonical-path file)
                     (canonical-path dir)
                     (canonical-path source-root)
                     (slurp file))))

(defn- discover-filesystem-skills [roots]
  (let [diagnostics (atom [])
        add-diagnostic! (fn [path message]
                          (when (< (count @diagnostics) max-diagnostics)
                            (swap! diagnostics conj (diagnostic path message))))
        skills
        (reduce
         (fn [found ^File root]
           (if-not (.isDirectory root)
             found
             (let [children (try
                              (if-let [listed (.listFiles root)]
                                (seq listed)
                                (do (add-diagnostic! root "skill root is unreadable") []))
                              (catch Exception e
                                (add-diagnostic! root (.getMessage e))
                                []))]
               (reduce
                (fn [acc ^File child]
                  (if (.isDirectory child)
                    (let [skill-file (io/file child "SKILL.md")]
                      (if (.exists skill-file)
                        (try
                          (conj acc (load-skill root child))
                          (catch Throwable e
                            (add-diagnostic! skill-file
                                             (or (.getMessage e) (.getName (class e))))
                            acc))
                        acc))
                    acc))
                found
                (sort-by canonical-path children)))))
         []
         roots)]
    {:skills skills :diagnostics @diagnostics}))

(defn- jar-bundled-skills [^JarURLConnection connection]
  (let [jar (.getJarFile connection)
        root (str (.getJarFileURL connection) "!/")
        entries (->> (enumeration-seq (.entries jar))
                     (map #(.getName %))
                     (keep #(second (re-matches #"([^/]+)/SKILL\.md" %)))
                     sort)]
    (reduce
     (fn [{:keys [skills diagnostics]} name]
       (let [entry-name (str name "/SKILL.md")
             path (str root entry-name)
             directory (str root name)]
         (try
           (with-open [stream (.getInputStream jar (.getJarEntry jar entry-name))]
             {:skills (conj skills
                            (load-skill-text name path directory root (slurp stream)))
              :diagnostics diagnostics})
           (catch Throwable e
             {:skills skills
              :diagnostics (if (< (count diagnostics) max-diagnostics)
                             (conj diagnostics {:path (shorten path max-diagnostic-chars)
                                                :message (bounded-message
                                                          (or (.getMessage e)
                                                              (.getName (class e))))})
                             diagnostics)}))))
     {:skills [] :diagnostics []}
     entries)))

(defn- discover-bundled-skills []
  (if-let [marker (bundled-marker)]
    (try
      (case (.getProtocol marker)
        "file" (discover-filesystem-skills [(-> marker io/file .getParentFile)])
        "jar" (jar-bundled-skills ^JarURLConnection (.openConnection marker))
        {:skills []
         :diagnostics [{:path (shorten marker max-diagnostic-chars)
                        :message (str "unsupported bundled skill resource protocol: "
                                      (.getProtocol marker))}]})
      (catch Throwable e
        {:skills []
         :diagnostics [{:path (shorten marker max-diagnostic-chars)
                        :message (bounded-message
                                  (or (.getMessage e) (.getName (class e))))}]}))
    {:skills []
     :diagnostics [{:path ".spell-skills-root"
                    :message "bundled skills resource marker was not found"}]}))

(defn discover-skills
  "Discover and load skills once. Invalid/unreadable entries become bounded diagnostics."
  ([]
   (let [bundled (discover-bundled-skills)
         external (discover-filesystem-skills (discovery-roots))]
     {:skills (into (:skills bundled) (:skills external))
      :diagnostics (->> (concat (:diagnostics bundled) (:diagnostics external))
                        (take max-diagnostics)
                        vec)}))
  ([roots]
   (discover-filesystem-skills roots)))

(defn bundled-skill-content
  "Read a complete bundled SKILL.md by its validated directory/name, when available."
  [name]
  (some->> (:skills (discover-bundled-skills))
           (filter #(= name (:name %)))
           first
           :content))
(defn- catalog-text [skills description-limit omitted-skills]
  (str "SKILLS — Discovered Agent Skills (prompt-only; no capability escalation).\n\n"
       "Activation: when task text explicitly names $name, or task intent implicitly matches a description below, use (!describe skills :name) before acting.\n"
       "The catalog is a deterministic snapshot taken once when this agent was compiled.\n\n"
       (str/join "\n" (map (fn [{:keys [name description path]}]
                               (str "- " name " — " (shorten description description-limit)
                                    " — " path))
                             skills))
       (when (pos? omitted-skills)
         (str "\nWARNING: " omitted-skills
              " skill catalog entr" (if (= omitted-skills 1) "y was" "ies were")
              " omitted to keep this catalog within 8000 characters."))))
(defn initial-catalog
  "Build a deterministic catalog no longer than 8000 characters, shortening descriptions
  before omitting skill entries. Discovery diagnostics are not inserted into model context."
  [{:keys [skills]}]
  (let [skills (vec skills)
        render (fn [included desc-limit omitted]
                 (catalog-text included desc-limit omitted))
        full (some (fn [limit]
                     (let [text (render skills limit 0)]
                       (when (<= (count text) max-catalog-chars) text)))
                   [400 240 160 100 60 30])]
    (or full
        (loop [included [] remaining skills]
          (let [omitted (count remaining)
                text (render included 30 omitted)
                next-text (when (seq remaining)
                            (render (conj included (first remaining))
                                    30 (dec omitted)))]
            (if (or (empty? remaining)
                    (> (count next-text) max-catalog-chars))
              text
              (recur (conj included (first remaining)) (subvec remaining 1))))))))

(defn- report-diagnostics! [diagnostics]
  (binding [*out* *err*]
    (doseq [{:keys [path message]} (take max-diagnostics diagnostics)]
      (println (str "Spell skill skipped: "
                    (shorten path max-diagnostic-chars) " — "
                    (bounded-message message))))))

(defn- skill-detail [name candidates]
  (str "SKILL DETAIL — " name "\n\n"
       "All duplicate candidates are shown in deterministic discovery order. Choose the candidate whose source/root applies to the task.\n"
       "Relative resource references must be resolved from that candidate's skill directory; its discovery root is also listed for provenance.\n"
       "Skill disclosure provides instructions only and grants no new tools, permissions, namespaces, or capability escalation.\n\n"
       (str/join
        "\n\n"
        (map-indexed
         (fn [idx {:keys [path directory root content]}]
           (str "CANDIDATE " (inc idx) "\n"
                "SKILL.md: " path "\n"
                "Skill directory (relative-resource base): " directory "\n"
                "Source root: " root "\n\n"
                content))
         candidates))))

(defn skills-namespace
  "Generate the always-available prompt-only skills namespace from a discovery snapshot."
  ([] (skills-namespace (discover-skills)))
  ([snapshot]
   (report-diagnostics! (:diagnostics snapshot))
   (let [skills (:skills snapshot)
         by-name (group-by :name skills)
         catalog (initial-catalog snapshot)
         details (into {}
                       (map (fn [[name candidates]]
                              [(keyword name) (skill-detail name candidates)]))
                       (sort-by key by-name))]
     {:short-docs catalog
      :docs {:guide catalog}
      :detail details})))
