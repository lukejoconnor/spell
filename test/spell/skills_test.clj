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
      (is (= 3 (count (:skills snapshot))) "duplicate names and paths are preserved")
      (is (str/includes? detail "FIRST COMPLETE SKILL"))
      (is (str/includes? detail "SECOND COMPLETE SKILL"))
      (is (= 3 (count (re-seq #"CANDIDATE " detail))))
      (is (str/includes? detail "relative-resource base"))
      (is (str/includes? detail "Source root:"))
      (is (str/includes? detail (.getCanonicalPath link-root))
          "symlink disclosure retains the searched source root")
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
    (is (some? (io/resource ".spell-skills-root")))
    (is (every? names ["coding"
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
                            :content "---\nname: compile-skill\ndescription: compile snapshot marker\n---\nBODY"}]
                  :diagnostics []}
        prov (provider/test-provider {:response "42"})]
    (with-redefs [skills/discover-skills (fn [] (swap! calls inc) snapshot)]
      (llm/compile-agent {:provider prov :namespaces {} :recover false})
      (is (= 1 @calls)))
    (let [skills-ns (skills/skills-namespace snapshot)
          system (prompt/compose-system-prompt
                  {:core-namespaces (assoc llm/core-namespaces 'skills skills-ns)})]
      (is (str/includes? system "compile snapshot marker"))
      (is (str/includes? system "skills — SKILLS")))))

(deftest bundled-coding-alias-test
  (let [coding (get-in stdlib/reminders-namespace [:docs :coding])]
    (is (= coding (skills/bundled-skill-content "coding")))
    (is (str/includes? coding "CODING TASKS"))
    (is (str/includes? (get-in stdlib/reminders-namespace [:docs :context-efficiency])
                       "CONTEXT EFFICIENCY"))
    (is (str/includes? (get-in stdlib/reminders-namespace [:docs :guide])
                       "(!describe skills :coding)"))))
