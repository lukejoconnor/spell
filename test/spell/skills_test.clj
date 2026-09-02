(ns spell.skills-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [spell.llm :as llm]
            [spell.provider :as provider]
            [spell.prompt :as prompt]
            [spell.skills :as skills]
            [spell.stdlib :as stdlib]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "spell-skills-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-skill! [root dir-name frontmatter body]
  (let [dir (io/file root dir-name)]
    (.mkdirs dir)
    (spit (io/file dir "SKILL.md")
          (str "---\n" frontmatter "\n---\n\n" body))
    dir))

(deftest safe-frontmatter-validation-and-diagnostics-test
  (let [root (temp-dir)]
    (write-skill! root "valid-skill"
                  "name: valid-skill\ndescription: >-\n  Handles YAML folded\n  descriptions safely."
                  "# Complete body")
    (write-skill! root "wrong-dir"
                  "name: other-name\ndescription: mismatch" "body")
    (write-skill! root "unsafe"
                  "name: unsafe\ndescription: !!java.net.URL [https://example.com]" "body")
    (write-skill! root "missing-description" "name: missing-description" "body")
    (write-skill! root "long-description"
                  (str "name: long-description\ndescription: "
                       (apply str (repeat 1025 "x")))
                  "body")
    (let [{:keys [skills diagnostics]} (skills/discover-skills [root])]
      (is (= ["valid-skill"] (mapv :name skills)))
      (is (= "Handles YAML folded descriptions safely."
             (:description (first skills))))
      (is (= 4 (count diagnostics)))
      (is (every? #(<= (count (:message %)) 300) diagnostics))
      (is (some #(str/includes? (:message %) "agree with directory") diagnostics))
      (is (some #(str/includes? (:message %) "requires non-blank description") diagnostics))
      (is (some #(str/includes? (:message %) "at most 1024") diagnostics)))))

(deftest duplicate-symlink-and-detail-disclosure-test
  (let [root-a (temp-dir)
        root-b (temp-dir)
        skill-a (write-skill! root-a "duplicate"
                              "name: duplicate\ndescription: first candidate"
                              "FIRST COMPLETE SKILL")
        _skill-b (write-skill! root-b "duplicate"
                               "name: duplicate\ndescription: second candidate"
                               "SECOND COMPLETE SKILL")
        link-root (temp-dir)
        link (.toPath (io/file link-root "duplicate"))]
    (java.nio.file.Files/createSymbolicLink
     link (.toPath skill-a) (make-array java.nio.file.attribute.FileAttribute 0))
    (let [snapshot (skills/discover-skills [root-a root-b link-root])
          ns-map (skills/skills-namespace snapshot)
          detail (stdlib/describe ns-map :duplicate)]
      (is (= 1 (count (:skills snapshot))) "duplicates are resolved to a single winner")
      (is (= (.getCanonicalPath root-a) (:root (first (:skills snapshot))))
          "the nearest root wins over later roots")
      (is (str/includes? detail "FIRST COMPLETE SKILL"))
      (is (not (str/includes? detail "SECOND COMPLETE SKILL")))
      (is (not (str/includes? detail "CANDIDATE")))
      (is (str/includes? detail "relative-resource base"))
      (is (str/includes? detail "Source root:"))
      (is (str/includes? detail (.getCanonicalPath root-a))
          "the winning candidate retains its source root")
      (is (str/includes? detail "no new tools, permissions, namespaces, or capability escalation"))
      (is (nil? (:skill-snapshot ns-map)) "namespace is prompt/docs only"))))

(deftest deterministic-bounded-catalog-test
  (let [skills (mapv (fn [i]
                       {:name (format "skill-%03d" i)
                        :description (apply str (repeat 500 (char (+ 97 (mod i 26)))))
                        :path (str "/catalog/skill-" i "/SKILL.md")})
                     (range 300))
        snapshot {:skills skills
                  :diagnostics [{:path "/bad/SKILL.md" :message "bad metadata"}]}
        a (skills/initial-catalog snapshot)
        b (skills/initial-catalog snapshot)]
    (is (= a b))
    (is (<= (count a) 8000))
    (is (str/includes? a "Activation:"))
    (is (str/includes? a "(!describe skills :name)"))
    (is (str/includes? a "omitted"))
    (is (str/includes? a "WARNING:"))))
(deftest discovery-roots-stop-at-worktree-and-include-home-test
  (let [worktree (temp-dir)
        nested (io/file worktree "a" "b")
        home (temp-dir)]
    (.mkdirs nested)
    (.mkdir (io/file worktree ".git"))
    (let [paths (mapv #(.getCanonicalPath %) (skills/discovery-roots nested home))]
      (is (some #(= % (.getCanonicalPath (io/file nested ".agents" "skills"))) paths))
      (is (some #(= % (.getCanonicalPath (io/file worktree ".agents" "skills"))) paths))
      (is (some #(= % (.getCanonicalPath (io/file home ".agents" "skills"))) paths))
      (is (not-any? #(str/includes? % (str (.getParent worktree) "/.agents/skills"))
                    paths)))))

(deftest discovery-roots-outside-repository-use-cwd-and-home-only-test
  (let [parent (temp-dir)
        cwd (io/file parent "plain" "nested")
        home (temp-dir)]
    (.mkdirs cwd)
    (let [paths (mapv #(.getCanonicalPath %) (skills/discovery-roots cwd home))]
      (is (= [(.getCanonicalPath (io/file cwd ".agents" "skills"))
              (.getCanonicalPath (io/file home ".agents" "skills"))]
             paths)))))

(deftest catalog-excludes-discovery-diagnostics-test
  (let [catalog (skills/initial-catalog
                 {:skills [{:name "valid"
                            :description (apply str (repeat 1000 "description "))
                            :path "/valid/SKILL.md"}]
                  :diagnostics [{:path "/bad/SKILL.md"
                                 :message "malformed YAML"}]})]
    (is (<= (count catalog) 8000))
    (is (not (str/includes? catalog "DISCOVERY DIAGNOSTICS")))
    (is (not (str/includes? catalog "/bad/SKILL.md")))
    (is (not (str/includes? catalog "malformed YAML")))))

(deftest discovery-diagnostics-go-to-stderr-test
  (let [stderr (java.io.StringWriter.)
        message (apply str (repeat 1000 "message "))]
    (binding [*err* stderr]
      (skills/skills-namespace
       {:skills []
        :diagnostics [{:path "/bad/SKILL.md"
                       :message message}]}))
    (let [reported (str stderr)]
      (is (str/includes? reported "Spell skill skipped: /bad/SKILL.md"))
      (is (str/includes? reported "..."))
      (is (<= (count reported) 350)))))

(deftest guide-named-skill-does-not-replace-namespace-guide-test
  (let [snapshot {:skills [{:name "guide"
                            :description "A valid skill named guide."
                            :path "/guide/SKILL.md"
                            :directory "/guide"
                            :root "/"
                            :content "---\nname: guide\ndescription: A valid skill named guide.\n---\nGUIDE SKILL BODY"}]
                  :diagnostics []}
        ns-map (skills/skills-namespace snapshot)]
    (is (str/includes? (stdlib/describe ns-map) "SKILLS — Discovered Agent Skills"))
    (is (not (str/includes? (stdlib/describe ns-map) "GUIDE SKILL BODY")))
    (is (str/includes? (stdlib/describe ns-map :guide) "GUIDE SKILL BODY"))))

(deftest bundled-skills-are-classpath-resources-test
  (let [snapshot (skills/discover-skills)
        names (set (map :name (:skills snapshot)))]
    (is (some? (io/resource "skills/.spell-skills-root")))
    (is (every? names ["coding"
                        "context-efficiency"
                        "spell-api-and-cli"
                        "spell-custom-agents"
                        "spell-developer"
                        "spell-setup"]))))

(deftest compile-discovers-once-and-makes-skills-always-available-test
  (let [calls (atom 0)
        snapshot {:skills [{:name "compile-skill"
                            :description "compile snapshot marker"
                            :path "/compile-skill/SKILL.md"
                            :directory "/compile-skill"
                            :root "/"
                            :content "---\nname: compile-skill\ndescription: compile snapshot marker\n---\nBODY"}
                           {:name "compile-skill"
                            :description "losing duplicate marker"
                            :path "/dup/compile-skill/SKILL.md"
                            :directory "/dup/compile-skill"
                            :root "/dup"
                            :content "---\nname: compile-skill\ndescription: losing duplicate marker\n---\nLOSER BODY"}]
                  :diagnostics []}
        prov (provider/test-provider {:response "42"})]
    (with-redefs [skills/discover-skills (fn [] (swap! calls inc) snapshot)]
      (llm/compile-agent {:provider prov :namespaces {} :recover false})
      (is (= 1 @calls)))
    (let [skills-ns (skills/skills-namespace snapshot)
          system (prompt/compose-system-prompt
                  {:core-namespaces (assoc llm/core-namespaces 'skills skills-ns)})]
      (is (str/includes? system "compile snapshot marker"))
      (is (not (str/includes? system "losing duplicate marker"))
          "compiled agents receive the deduplicated catalog")
      (is (= 1 (count (re-seq #"- compile-skill " system)))
          "the winner appears exactly once in the catalog")
      (is (<= (count (:short-docs skills-ns)) 8000)
          "compiled agents receive the bounded catalog")
      (is (str/includes? (stdlib/describe skills-ns :compile-skill) "BODY"))
      (is (not (str/includes? (stdlib/describe skills-ns :compile-skill) "LOSER BODY"))
          "detail disclosure returns the winning content")
      (is (str/includes? system "skills — SKILLS")))))

(deftest bundled-workflow-skills-test
  (let [coding (skills/bundled-skill-content "coding")
        context-efficiency (skills/bundled-skill-content "context-efficiency")]
    (is (str/includes? coding "CODING TASKS"))
    (is (str/includes? context-efficiency "CONTEXT EFFICIENCY"))))

(deftest invalid-bundled-skill-is-omitted-test
  (with-redefs-fn {(ns-resolve 'spell.skills 'discover-bundled-skills)
                   (fn [] {:skills []
                           :diagnostics [{:path "coding/SKILL.md"
                                          :message "malformed YAML"}]})}
    (fn []
      (is (nil? (skills/bundled-skill-content "coding"))))))

(deftest dedupe-precedence-repo-over-user-over-bundled-test
  (let [near (temp-dir)
        far (temp-dir)
        user (temp-dir)]
    (write-skill! near "shared" "name: shared\ndescription: near repo root" "NEAR BODY")
    (write-skill! far "shared" "name: shared\ndescription: far repo root" "FAR BODY")
    (write-skill! user "shared" "name: shared\ndescription: user root" "USER BODY")
    (write-skill! user "user-only" "name: user-only\ndescription: only in user root" "USER ONLY")
    (let [snapshot (skills/discover-skills [near far user])
          names (mapv :name (:skills snapshot))
          shared (first (filter #(= "shared" (:name %)) (:skills snapshot)))]
      (is (= ["shared" "user-only"] names) "winner ordering is deterministic")
      (is (= "near repo root" (:description shared)))
      (is (str/includes? (:content shared) "NEAR BODY")))))

(deftest filesystem-skill-content-truncation-test
  (let [root (temp-dir)
        body (apply str (repeat 70000 "x"))]
    (write-skill! root "big-skill"
                  "name: big-skill\ndescription: a very large skill body"
                  body)
    (let [{:keys [skills diagnostics]} (skills/discover-skills [root])
          skill (first skills)
          content (:content skill)]
      (is (empty? diagnostics))
      (is (= "big-skill" (:name skill)) "metadata is validated before truncation")
      (is (= "a very large skill body" (:description skill)))
      (is (re-find #"\.\.\. \[truncated, \d+ chars total\]$" content))
      (is (<= (count content) (+ skills/max-skill-content-chars 64)))
      (is (str/starts-with? content "---") "the truncated prefix is preserved"))))

(deftest truncate-skill-content-boundary-test
  (let [short "tiny"
        exact (apply str (repeat skills/max-skill-content-chars "a"))
        long-text (str exact "overflow")]
    (is (identical? short (skills/truncate-skill-content short)))
    (is (identical? exact (skills/truncate-skill-content exact)))
    (let [truncated (skills/truncate-skill-content long-text)]
      (is (str/includes? truncated (str "[truncated, " (count long-text) " chars total]")))
      (is (str/starts-with? truncated (subs exact 0 100))))
    (let [surrogate (str (apply str (repeat (dec skills/max-skill-content-chars) "a"))
                         "\ud83d\ude00tail")
          truncated (skills/truncate-skill-content surrogate)
          cut (subs truncated 0 (str/index-of truncated "\n... [truncated"))]
      (is (not (Character/isHighSurrogate (last cut)))
          "truncation never splits a surrogate pair"))))

(deftest bundled-entry-skill-name-inclusion-and-exclusion-test
  (is (= "good-skill" (skills/bundled-entry-skill-name "skills/good-skill/SKILL.md")))
  (is (nil? (skills/bundled-entry-skill-name "unrelated/SKILL.md"))
      "unrelated top-level entries in a shaded jar are rejected")
  (is (nil? (skills/bundled-entry-skill-name "SKILL.md")))
  (is (nil? (skills/bundled-entry-skill-name "skills/Bad_Name/SKILL.md"))
      "invalid skill names are rejected")
  (is (nil? (skills/bundled-entry-skill-name "skills/nested/dir/SKILL.md")))
  (is (nil? (skills/bundled-entry-skill-name "other/skills/good-skill/SKILL.md"))
      "the bundled layout must be anchored at the classpath root"))

(defn- write-test-jar! [^java.io.File jar-file entries]
  (with-open [out (java.util.jar.JarOutputStream.
                   (java.io.FileOutputStream. jar-file))]
    (doseq [[entry-name content] entries]
      (.putNextEntry out (java.util.jar.JarEntry. ^String entry-name))
      (.write out (.getBytes ^String content "UTF-8"))
      (.closeEntry out))))

(deftest jar-bundled-skills-anchored-layout-test
  (let [jar-file (java.io.File/createTempFile "spell-skills" ".jar")
        skill-md (str "---\nname: good-skill\ndescription: bundled winner\n---\n\n"
                      (apply str (repeat 70000 "y")))]
    (write-test-jar! jar-file
                     [["skills/.spell-skills-root" ""]
                      ["skills/good-skill/SKILL.md" skill-md]
                      ["unrelated/SKILL.md" "---\nname: unrelated\ndescription: shaded intruder\n---\nINTRUDER"]
                      ["skills/Bad_Name/SKILL.md" "---\nname: Bad_Name\ndescription: invalid\n---\nBAD"]])
    (let [url (java.net.URL. (str "jar:" (.toURL (.toURI jar-file)) "!/skills/.spell-skills-root"))
          jar-skills (ns-resolve 'spell.skills 'jar-bundled-skills)
          {:keys [skills diagnostics]} (jar-skills (.openConnection url))]
      (is (= ["good-skill"] (mapv :name skills))
          "only skills/<valid-name>/SKILL.md entries are accepted")
      (is (empty? diagnostics))
      (is (str/includes? (:content (first skills)) "[truncated, ")
          "bundled skill content is capped after validation")
      (is (= "bundled winner" (:description (first skills)))
          "metadata is parsed before truncation"))))

(deftest bundled-loses-to-filesystem-duplicate-test
  (let [root (temp-dir)]
    (write-skill! root "coding"
                  "name: coding\ndescription: local coding override"
                  "LOCAL CODING BODY")
    (with-redefs [skills/discovery-roots (fn [] [root])]
      (let [snapshot (skills/discover-skills)
            coding (first (filter #(= "coding" (:name %)) (:skills snapshot)))]
        (is (= 1 (count (filter #(= "coding" (:name %)) (:skills snapshot)))))
        (is (= "local coding override" (:description coding))
            "filesystem skills win over bundled duplicates")))))
