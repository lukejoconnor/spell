(ns spell.module-discovery-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [spell.api :as api]
            [spell.coordinator :as coordinator]
            [spell.feedback :as feedback]
            [spell.globals :as globals]
            [spell.patterns :as patterns]
            [spell.provider :as provider]
            [spell.runtime :as runtime]
            [spell.test-helpers :as th])
  (:import [java.nio.file Files]
           [java.util.jar JarEntry JarOutputStream]))

(def ^:dynamic *dir*)
(defn- temp-dir []
  (.toFile (Files/createTempDirectory "spell-module-discovery-"
                                     (make-array java.nio.file.attribute.FileAttribute 0))))
(defn- canonical [file] (.getCanonicalPath (io/file file)))
(defn- mkdir [file] (.mkdirs (io/file file)) (io/file file))
(defn- worktree! [file]
  ;; Temp roots can live inside a real checkout. Bound every writable project
  ;; explicitly instead of allowing production discovery to reach its ancestor.
  (let [root (mkdir file)] (mkdir (io/file root ".git")) root))
(defn- roots [project home] (patterns/discovery-context project home))
(defn- seed! [project home]
  (globals/set-val :module-discovery (roots project home)))
(defn- definition [doc]
  {:doc doc :functions {:run {:doc "echo without traversing" :requires []
                            :source '(fn [x] x)}}})
(defn- save! [root module value]
  (let [file (io/file root ".spell" "modules" (str (name module) ".spl"))]
    (when-not (.startsWith (.toPath (.getCanonicalFile file))
                          (.toPath (.getCanonicalFile *dir*)))
      (throw (ex-info "Refusing module fixture write outside its temporary root"
                      {:path (canonical file) :fixture-root (canonical *dir*)})))
    (io/make-parents file)
    (spit file (pr-str value))
    file))
(defn- failure [f]
  (try (f) nil (catch Exception e e)))
(defn- program [action]
  (pr-str (list 'quine 'completion (list 'eval (list 'do (list 'quote action))))))
(defn- suffix [action] (str (pr-str (list 'quote action)) ")))"))

(use-fixtures :each
  (fn [f]
    (binding [*dir* (temp-dir)]
      (try
        (th/with-test-run
          #(do (doseq [h [:owner :editor]] (coordinator/register! h))
               (binding [runtime/*current-handle* :owner feedback/*dogfood* nil]
                 (seed! (worktree! (io/file *dir* "project")) (mkdir (io/file *dir* "home")))
                 (f))))
        (finally
          (doseq [file (reverse (file-seq *dir*))] (io/delete-file file true)))))))

(deftest fixture-writes-cannot-escape-their-root
  (let [project (:project-root (globals/get-val :module-discovery))
        escaped (io/file *dir* ".." (str "escape-" (java.util.UUID/randomUUID)))
        candidate (io/file escaped ".spell" "modules" "probe.spl")]
    (is (= (canonical (io/file *dir* "project")) project))
    (is (thrown-with-msg? Exception #"Refusing module fixture write"
                          (save! escaped :probe (definition "must-not-write"))))
    (is (not (.exists candidate)))))

(deftest canonical-worktree-and-outside-git-roots
  (let [project (mkdir (io/file *dir* "worktree"))
        nested (mkdir (io/file project "a" "b"))
        home (mkdir (io/file *dir* "user"))]
    ;; Linked worktrees have a .git file, not a directory.
    (spit (io/file project ".git") "gitdir: /unused-for-discovery")
    (is (= {:project-root (canonical project) :user-root (canonical home)}
           (roots (io/file nested ".." "b") (io/file home "."))))
    (io/delete-file (io/file project ".git"))
    ;; Exercise the outside-Git fallback independently of java.io.tmpdir being
    ;; inside/outside a checkout. Only marker existence is simulated; no writes
    ;; escape the temporary tree and canonical path handling remains real.
    (let [file io/file]
      (with-redefs [io/file (fn [& args]
                             (let [f (apply file args)]
                               (if (= ".git" (last args))
                                 (proxy [java.io.File] [(str f)] (exists [] false))
                                 f)))]
        (is (= (canonical nested) (:project-root (roots nested nil))))))
    (is (nil? (:user-root (roots nested nil))))
    (mkdir (io/file project ".git"))
    (is (= (canonical project) (:project-root (roots nested home))))))

(deftest precedence-origins-and-inert-catalog
  (let [{:keys [project-root user-root]} (globals/get-val :module-discovery)
        marker (io/file *dir* "must-not-exist")
        project-definition (assoc-in (definition "project") [:functions :run :source]
                                     (list 'fn [] (list 'spit (str marker) "executed")))
        project-file (save! project-root :relay project-definition)
        user-file (save! user-root :relay (definition "user"))
        before @globals/*store*
        summary (patterns/catalog :relay)]
    (is (= "project" (:doc summary)))
    (is (= {:kind :project :path (canonical project-file)} (:origin summary)))
    (is (false? (:installed? summary)))
    (is (nil? (:owner summary)))
    (is (= #{:doc :params :requires} (set (keys (get-in summary [:functions :run])))))
    (is (not (str/includes? (pr-str summary) "must-not-exist")))
    (is (= before @globals/*store*) "catalog neither installs nor initializes mutable state")
    (is (not (.exists marker)))
    (io/delete-file project-file)
    (is (= "user" (:doc (patterns/catalog :relay))))
    (is (= {:kind :user :path (canonical user-file)} (:origin (patterns/catalog :relay))))
    (io/delete-file user-file)
    (is (= {:kind :bundle :path (str (io/resource "modules/relay.spl"))}
           (:origin (patterns/catalog :relay))))
    (is (= #{:relay :mailing-list} (set (map :module (patterns/catalog)))))))

(deftest missing-directories-and-custom-keywords
  (globals/set-val :module-discovery
                   {:project-root (canonical (io/file *dir* "missing-project"))
                    :user-root (canonical (io/file *dir* "missing-home"))})
  (is (= #{:relay :mailing-list} (set (map :module (patterns/catalog)))))
  (is (nil? (patterns/catalog :unknown)))
  (doseq [k [:Custom.Name :my.ns/Thing :with_under_score]]
    (is (:installed? (patterns/install k (definition (str k)))))
    (is (nil? (:origin (patterns/catalog k))))
    (is (= (definition (str k)) (patterns/source k))))
  (is (= 5 (count (patterns/catalog))))
  (is (thrown-with-msg? Exception #"keyword" (patterns/catalog "relay"))))

(deftest malformed-selected-files-never-fall-back
  (let [{:keys [project-root user-root]} (globals/get-val :module-discovery)
        project (save! project-root :relay (definition "valid"))
        user (save! user-root :relay (definition "fallback"))
        read-effects (atom 0)]
    (doseq [text ["" "'{}" "{} {}" "{:doc \"bad\" :functions {}})"
                  "{:doc \"bad\" :functions {:run {:doc \"x\" :requires [] :source (do :oops)}}}"
                  "#=(System/setProperty \"spell.module.read-executed\" \"yes\")"
                  "#danger/reader {}" "{:doc \"a\" :doc \"b\" :functions {}}"]]
      (spit project text)
      (binding [*data-readers* {'danger/reader (fn [_] (swap! read-effects inc) (definition "evil"))}]
        (doseq [f [#(patterns/catalog :relay) #(patterns/install :relay)]]
          (let [error (failure f)]
            (is (some? error) text)
            (is (= (canonical project) (:path (ex-data error))) text)
            (is (str/includes? (or (some-> error .getMessage) "") (canonical project)) text)))))
    (is (zero? @read-effects))
    (is (nil? (System/getProperty "spell.module.read-executed")))
    (is (nil? (patterns/source :relay)))
    ;; A malformed *shadowed* definition is not read.
    (spit project (pr-str (definition "selected")))
    (spit user "not a definition")
    (is (= "selected" (:doc (patterns/catalog :relay))))))

(deftest filename-validation-and-source-alias-collisions
  (let [root (:project-root (globals/get-val :module-discovery))
        invalid (save! root :Bad_Name (definition "bad"))]
    (let [error (failure #(patterns/catalog))]
      (is (str/includes? (.getMessage error) "Invalid module filename"))
      (is (= (str invalid) (:path (ex-data error)))))
    (io/delete-file invalid)
    (let [original (save! root :original (definition "one identity"))
          alias (io/file (.getParentFile original) "alias.spl")]
      (Files/createSymbolicLink (.toPath alias) (.toPath original)
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (let [error (failure #(patterns/catalog))]
        (is (str/includes? (.getMessage error) "Duplicate module source identity"))
        (is (= (canonical original) (:canonical-path (ex-data error))))))))

(deftest installed-origin-ownership-journal-and-reinstall-preservation
  (let [root (:project-root (globals/get-val :module-discovery))
        file (save! root :saved (definition "disk baseline"))
        origin {:kind :project :path (canonical file)}
        records (atom [])]
    (binding [feedback/*dogfood* {:path "unused-captured" :run-id "discovery-journal-test"}]
      (with-redefs [feedback/append-entry! (fn [_ text] (swap! records conj (edn/read-string text)))]
        (let [installed (patterns/install :saved)]
          (is (= origin (:origin installed)))
          (is (= :owner (:owner installed)))
          (is (= {:status :ok :sequence 1} (:journal installed))))
        (binding [runtime/*current-handle* :editor]
          (is (thrown-with-msg? Exception #"owner mismatch" (patterns/update :saved assoc :doc "forbidden")))
          (let [edited (patterns/update :saved {:owner :owner} assoc :doc "shared edit")]
            (is (= origin (:origin edited)))
            (is (= [:owner :editor true 2 2]
                   ((juxt :owner :editor :explicit-owner? :revision :sequence) edited)))
            (is (= {:status :ok :sequence 2} (:journal edited)))))
        (spit file "malformed now")
        (let [state @globals/*store*]
          (is (false? (:installed? (patterns/install :saved))))
          (is (= "shared edit" (:doc (patterns/catalog :saved))))
          (is (= origin (:origin (patterns/catalog :saved))))
          (is (= state @globals/*store*)))
        (io/delete-file file)
        (is (= origin (:origin (patterns/install :saved nil))))
        (is (= origin (:origin (patterns/update :saved identity))))
        (is (= [1 2] (mapv :sequence @records)))
        (is (= [:install :update] (mapv :operation @records)))
        (is (= [origin origin] (mapv :origin @records)))
        (is (= [:owner :owner] (mapv :owner @records)))
        (is (= [:owner :editor] (mapv :editor @records)))
        (is (nil? (:before (first @records))))
        (is (= (definition "disk baseline") (edn/read-string (:after (first @records)))))
        (is (= (definition "shared edit") (edn/read-string (:after (second @records)))))))))

(deftest explicit-save-then-fresh-api-run-reloads
  (let [root (:project-root (globals/get-val :module-discovery))
        context (globals/get-val :module-discovery)
        file (save! root :saved (definition "baseline"))
        run (fn [] (binding [runtime/*current-handle* nil]
                     (api/run {:init (program '(do (patterns/install :saved) (patterns/source :saved)))
                               :model-profile (provider/test-provider {:response "must-not-run"})
                               :agent-profile "config/agent-profiles/cli.agent.edn"})))]
    (patterns/install :saved)
    (patterns/update :saved assoc :doc "edited in memory")
    (is (= "baseline" (:doc (edn/read-string (slurp file)))) "no automatic writeback")
    ;; Ordinary explicit source-file write; no hidden reload command.
    (spit file (pr-str (patterns/source :saved)))
    (with-redefs [patterns/discovery-context (fn [] context)]
      (let [fresh (run)]
        (is (nil? (:error fresh)) (pr-str fresh))
        (is (= "edited in memory" (:doc (:result fresh))))))
    (spit file (pr-str (definition "later disk edit")))
    (is (= "edited in memory" (:doc (patterns/source :saved))))
    (with-redefs [patterns/discovery-context (fn [] context)]
      (is (= "later disk edit" (:doc (:result (run))))))))

(deftest api-root-snapshot-shared-by-real-child
  (let [context (globals/get-val :module-discovery)
        other (roots (worktree! (io/file *dir* "other")) nil)
        calls (atom 0)
        _ (save! (:project-root context) :child-module (definition "run-root"))
        _ (save! (:project-root other) :child-module (definition "wrong-child-cwd"))
        child (program '(do (patterns/install :child-module)
                            (globals/set :child-evidence
                              {:context (globals/get :module-discovery)
                               :catalog (patterns/catalog :child-module)})
                            :child-finished))]
    ;; The would-be subsequent resolution changes roots, simulating a changed
    ;; child working directory without ever mutating the host process cwd.
    (with-redefs [patterns/discovery-context (fn [] (if (= 1 (swap! calls inc)) context other))]
      (let [result (binding [runtime/*current-handle* nil]
                     (api/run {:init (program (list 'do (list 'agents/spawn-ask child :child)
                                                    '(agents/!wait)))
                               :model-profile (provider/test-provider
                                                {:response (suffix '(globals/get :child-evidence))
                                                 :prefill? false})
                               :agent-profile "config/agent-profiles/cli.agent.edn"}))]
        (is (nil? (:error result)) (pr-str result))
        (is (= 1 @calls))
        (is (= context (get-in result [:result :context])))
        (is (= "run-root" (get-in result [:result :catalog :doc])))
        (is (= :child (get-in result [:result :catalog :owner])))
        (is (= :project (get-in result [:result :catalog :origin :kind])))))))

(deftest opaque-cross-agent-call-and-shared-edit
  (let [root (:project-root (globals/get-val :module-discovery))
        _ (save! root :opaque (definition "baseline"))
        opaque (Object.)
        _ (globals/set-val :opaque-argument opaque)
        child (program '(do (globals/set :opaque-result
                              (patterns/call :opaque :run (globals/get :opaque-argument)))
                            (patterns/update :opaque {:owner :main} assoc :doc "child edit")
                            :child-finished))
        agent (th/make-test-agent {:response (suffix :collected) :prefill? false})]
    (is (= :collected
           (binding [runtime/*current-handle* nil]
             (th/run-agent-init agent
               (program (list 'do '(patterns/install :opaque)
                              (list 'agents/spawn-ask child :child) '(agents/!wait)))))))
    (is (identical? opaque (globals/get-val :opaque-result)))
    (is (= "child edit" (:doc (patterns/source :opaque))))
    (is (= :main (:owner (patterns/catalog :opaque))))
    (is (= 2 (get-in @globals/*store* [:module-installations :opaque :revision])))))

(deftest jar-resource-origin-is-loadable-without-file-coercion
  (let [jar (io/file *dir* "modules.jar")
        source (slurp (io/resource "modules/relay.spl"))]
    (with-open [out (JarOutputStream. (io/output-stream jar))]
      (.putNextEntry out (JarEntry. "modules/relay.spl"))
      (.write out (.getBytes source java.nio.charset.StandardCharsets/UTF_8))
      (.closeEntry out))
    (let [url (java.net.URL. (str "jar:" (.toURL (.toURI jar)) "!/modules/relay.spl"))
          resource io/resource]
      (with-redefs [io/resource (fn [path] (if (= path "modules/relay.spl") url (resource path)))]
        (is (= {:kind :bundle :path (str url)} (:origin (patterns/catalog :relay))))
        (is (= {:kind :bundle :path (str url)} (:origin (patterns/install :relay))))
        (is (seq? (:source (patterns/source :relay :run))))))))
