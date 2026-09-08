(ns spell.patterns-loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [spell.parse :as parse]
            [spell.patterns :as patterns])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]
           [java.util.jar JarEntry JarOutputStream]))

(def bundles [:check-result :ralph :team :fix-loop :relay :mailing-list])

(deftest public-namespace-has-only-module-verbs
  (is (= #{:install :catalog :source :update :call}
         (set (remove #{:short-docs :docs :detail} (keys patterns/patterns))))))

(deftest bundled-definitions-are-executable-source-data
  (doseq [module bundles]
    (testing (name module)
      (let [forms (parse/read-all
                    (slurp (io/resource (str "modules/" (name module) ".spl"))))
            definition (first forms)]
        (is (= 1 (count forms)))
        (is (map? definition))
        (is (string? (:doc definition)))
        (is (map? (:functions definition)))
        (is (seq (:functions definition)))
        (doseq [[k entry] (:functions definition)]
          (is (keyword? k))
          (is (string? (:doc entry)))
          (is (vector? (:requires entry)))
          (is (every? symbol? (:requires entry)))
          (is (seq? (:source entry)))
          (is (= 'fn (first (:source entry)))))))))

(deftest bundled-modules-load-from-jar-outside-checkout
  (let [dir (.toFile (Files/createTempDirectory "spell-module-package-"
                                               (make-array java.nio.file.attribute.FileAttribute 0)))
        jar (io/file dir "application.jar")
        output (io/file dir "result.edn")]
    (try
      ;; Package the actual production paths, so omitting the bundle path from
      ;; deps.edn fails this test as well as a filesystem-only loader.
      (with-open [out (JarOutputStream. (io/output-stream jar))]
        (doseq [path (:paths (edn/read-string (slurp "deps.edn")))
                :let [root (.toPath (io/file path))]
                file (file-seq (io/file path))
                :when (.isFile file)]
          (.putNextEntry out (JarEntry. (str/replace
                                         (str (.relativize root (.toPath file)))
                                         java.io.File/separator "/")))
          (io/copy file out)
          (.closeEntry out)))
      (let [jars (filter #(.isFile (io/file %))
                         (str/split (System/getProperty "java.class.path")
                                    (re-pattern java.io.File/pathSeparator)))
            classpath (str/join java.io.File/pathSeparator
                                (cons (.getAbsolutePath jar) jars))
            code (str
                   "(require 'spell.patterns 'spell.globals 'spell.coordinator 'spell.runtime 'clojure.java.io)\n"
                   (pr-str
                     '(binding [spell.globals/*store* (spell.globals/new-store)
                                spell.coordinator/*coordinator* (spell.coordinator/new-coordinator)
                                spell.runtime/*current-handle* :packaged-installer]
                        (spell.coordinator/register! :packaged-installer)
                        (let [catalog (spell.patterns/catalog)
                              installs (mapv #(spell.patterns/install (:module %)) catalog)]
                          (spell.patterns/update :check-result assoc :doc "packaged edit")
                          (prn {:protocol (.getProtocol (clojure.java.io/resource "modules/check-result.spl"))
                                :catalog-count (count catalog)
                                :installed (every? :installed? installs)
                                :reused (false? (:installed? (spell.patterns/install :check-result)))
                                :edited-doc (:doc (spell.patterns/source :check-result))
                                :source-head (first (:source (spell.patterns/source :check-result :run)))}))))
                   "\n(shutdown-agents)")
            builder (doto (ProcessBuilder.
                            ^java.util.List
                            [(str (System/getProperty "java.home") "/bin/java")
                             "-cp" classpath "clojure.main" "-e" code])
                      (.directory dir)
                      (.redirectErrorStream true)
                      (.redirectOutput output))
            _ (.remove (.environment builder) "SPELL_ROOT")
            process (.start builder)]
        (try
          (let [finished? (.waitFor process 30 TimeUnit/SECONDS)]
            (is finished? "Packaged child JVM completes outside the source checkout")
            (when finished?
              (let [text (slurp output)]
                (is (zero? (.exitValue process)) text)
                (when (zero? (.exitValue process))
                  (is (= {:protocol "jar" :catalog-count 6 :installed true
                          :reused true :edited-doc "packaged edit" :source-head 'fn}
                         (edn/read-string text)))))))
          (finally
            (when (.isAlive process)
              (.destroyForcibly process)
              (.waitFor process 5 TimeUnit/SECONDS)))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file true))))))
