(ns spell.react
  "React namespace loader.

   Function bodies are sourced from config/spl-lib/react.spl and converted
   into Spell function maps at startup."
  (:require [clojure.java.io :as io]
            [spell.parse :as parse]))

(def ^:private react-spl-path
  "Filesystem path to Spell React definitions."
  "config/spl-lib/react.spl")

(def ^:private react-docs
  {:short-docs "Hidden ReAct loop: plain-text command/finish transcript driven through leaf-llm."
   :docs {:guide "REACT - Hidden ReAct loop (effect namespace).

  (react/run prompt-or-opts) - run a plain-text command loop while hiding Spell from the inner model

Use react/run from an :init program whose trailing expression calls the
react namespace:

  (eval (do
    (def prompt \"Inspect the repo and summarize the failing test.\")
    '(react/run prompt)))

Map form:

  (eval (do
    '(react/run {:task \"Inspect the repo and summarize the failing test.\"
                 :max-steps 20})))

react/run uses leaf-llm internally, but the inner model sees only a plain-text
ReAct transcript: task text, prior thoughts/actions/observations, and the
required output contract Action: Command[...] or Action: Finish[...].

   Requires an agent profile that exposes react/ plus shell execution
   capability (via io/sh)."}
   :detail {:run "(react/run prompt-or-opts) - run a hidden ReAct loop.
prompt-or-opts:
  string                   - task text
  :task                    - task text (required if map form)
  :max-steps               - maximum command/response turns (default: 30)

Returns:
  string                   - final answer text from Action: Finish[...]

Behavior:
- renders a plain-text ReAct transcript each step
- calls leaf-llm with no Spell syntax in the prompt
- executes Action: Command[...] with io/sh
- truncates command observations to keep context bounded
- returns a plain failure string on step exhaustion"}})

(defn- defn-form?
  [form]
  (and (seq? form)
       (= 'defn (first form))))

(defn- resolve-react-file
  []
  (let [cwd-file (io/file react-spl-path)
        env-file (when-let [spell-root (System/getenv "SPELL_ROOT")]
                   (io/file spell-root react-spl-path))
        classpath-root (when-let [src-url (io/resource "spell/react.clj")]
                         (-> src-url io/file .getParentFile .getParentFile .getParentFile))
        classpath-file (when classpath-root
                         (io/file classpath-root react-spl-path))
        candidates (remove nil? [cwd-file env-file classpath-file])]
    (or (first (filter #(.exists ^java.io.File %) candidates))
        (throw (ex-info "react.spl file not found"
                        {:path react-spl-path
                         :cwd (.getAbsolutePath cwd-file)
                         :spell-root (some-> env-file .getAbsolutePath)
                         :classpath-root (some-> classpath-file .getAbsolutePath)})))))

(defn- form->spell-fn
  [form]
  (let [[_ fn-name params & body] form]
    (when-not (symbol? fn-name)
      (throw (ex-info "react.spl defn name must be a symbol"
                      {:form form :name fn-name})))
    (when-not (vector? params)
      (throw (ex-info "react.spl defn params must be a vector"
                      {:form form :name fn-name :params params})))
    [(keyword (clojure.core/name fn-name))
     {:spell/fn true
      :params params
      :body body}]))

(defn- load-react-fns
  []
  (let [file (resolve-react-file)
        forms (parse/read-all (slurp file))
        entries (->> forms
                     (filter defn-form?)
                     (map form->spell-fn)
                     vec)
        fns-map (into {} entries)]
    (when (empty? entries)
      (throw (ex-info "react.spl did not contain any top-level defn forms"
                      {:path (.getPath file)})))
    (when (not= (count entries) (count fns-map))
      (throw (ex-info "react.spl contains duplicate defn names"
                      {:path (.getPath file)
                       :names (map first entries)})))
    fns-map))

(def react
  "Hidden ReAct loop implemented in Spell."
  (merge react-docs
         (load-react-fns)))
