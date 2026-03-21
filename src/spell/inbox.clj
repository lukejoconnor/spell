(ns spell.inbox
  "Shared inbox macro application helpers."
  (:require [clojure.string :as str]
            [spell.eval :as eval]
            [spell.parse :as parse]))

(defn apply-inbox-macros
  "Apply inbox macros left-to-right to a parsed program.
   Macros share the same caller env; expander-local defs do not leak across
   macro boundaries. Optionally override the Spell env/builtins used during
   macro expansion."
  ([program inbox-macros]
   (apply-inbox-macros program inbox-macros {}))
  ([program inbox-macros {:keys [env builtins error-prefix error-data]
                          :or {env {}
                               error-prefix "Inbox macro expansion failed"}}]
   (let [step (fn [current msg-macro]
                (let [expand (fn []
                               (eval/apply-spell-macro msg-macro [current] env))
                      r (if (some? builtins)
                          (binding [eval/*builtins* builtins]
                            (expand))
                          (expand))]
                  (if-let [expanded (:ok r)]
                    expanded
                    (throw (ex-info (str error-prefix ": " (:err r))
                                    (merge {:macro msg-macro
                                            :program current}
                                           error-data))))))]
     (reduce step program inbox-macros))))

(defn materialize-inbox-raw
  "Apply inbox macros to the last parsed top-level form in raw text and
   serialize the transformed form back to raw. Earlier top-level forms are
   preserved as inert context ahead of the transformed form."
  ([raw inbox-macros]
   (materialize-inbox-raw raw inbox-macros {}))
  ([raw inbox-macros opts]
   (let [balanced (parse/balance-parens raw)]
     (if (empty? inbox-macros)
       balanced
       (let [forms (vec (parse/read-all balanced))]
         (if (empty? forms)
           balanced
           (let [prior-forms (butlast forms)
                 form (last forms)
                 expanded (apply-inbox-macros
                           form
                           inbox-macros
                           (assoc opts
                                  :error-prefix "Inbox macro materialization failed"
                                  :error-data {:raw raw :macros inbox-macros}))]
             (str (when (seq prior-forms)
                    (str (str/join " " (map pr-str prior-forms)) " "))
                  (pr-str expanded)))))))))
