(ns spell.trace
  "Execution trace recording for Spell.
   Records the tree of LLM calls with prompts, responses, programs, and values.

   Usage: bind *trace* to (new-trace) before running. Each -llm / leaf-llm call
   registers itself via begin-node! and complete-node!. Parent-child links are
   established through *trace-node-id*, which is bound during eval so children
   see their parent's ID."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Dynamic vars
;; ---------------------------------------------------------------------------

(def ^:dynamic *trace*
  "When bound to an atom, records the execution tree. nil = tracing disabled."
  nil)

(def ^:dynamic *trace-node-id*
  "ID of the currently executing node. Children read this to find their parent."
  nil)

;; ---------------------------------------------------------------------------
;; Trace lifecycle
;; ---------------------------------------------------------------------------

(defn new-trace
  "Create a fresh trace atom."
  []
  (atom {:nodes [] :next-id 0 :root nil}))

(defn begin-node!
  "Register a new node. Returns its ID.
   Called at the start of -llm / leaf-llm, before the LLM API call."
  [parent-id depth variant prompt]
  (let [id (:next-id @*trace*)
        node {:id id
              :parent parent-id
              :depth depth
              :variant variant
              :prompt (str prompt)
              :start-ms (System/currentTimeMillis)
              :children []}]
    (swap! *trace* (fn [t]
                     (-> t
                         (update :nodes conj node)
                         (update :next-id inc)
                         (cond-> (nil? (:root t)) (assoc :root id)))))
    (when (some? parent-id)
      (swap! *trace* update-in [:nodes parent-id :children]
             conj {:child-id id :call-prompt (str prompt)}))
    id))

(defn complete-node!
  "Record completion data for a node.
   Called after eval completes (or after response for leaf nodes).
   Keys: :response, :raw-text, :program, :hooked, :value, :error"
  [node-id {:keys [response raw-text program hooked value error]}]
  (swap! *trace* update-in [:nodes node-id]
         (fn [node]
           (cond-> (assoc node :end-ms (System/currentTimeMillis))
             response (assoc :response response)
             raw-text (assoc :raw-text raw-text)
             program  (assoc :program program)
             hooked   (assoc :hooked hooked)
             error    (assoc :error (if (instance? Exception error)
                                      (.getMessage ^Exception error)
                                      (str error)))
             (nil? error) (assoc :value value)))))

;; ---------------------------------------------------------------------------
;; Output
;; ---------------------------------------------------------------------------

(defn- truncate [s n]
  (let [s (str s)]
    (if (> (count s) n)
      (str (subs s 0 n) "...")
      s)))

(defn- file-name [node]
  (let [ext (if (= :leaf (:variant node)) ".txt" ".spl")]
    (format "%04d%s" (:id node) ext)))

(defn tree-str
  "ASCII tree of the trace. Shows node ID, prompt prefix, and result."
  [trace]
  (let [nodes (:nodes trace)]
    (letfn [(render-label [node]
              (str (:id node)
                   (when (= :leaf (:variant node)) " [leaf]")
                   " " (truncate (:prompt node) 40)
                   (cond
                     (:error node)            (str " \u2717 " (truncate (:error node) 30))
                     (contains? node :value)  (str " \u2192 " (truncate (pr-str (:value node)) 30))
                     :else                    "")))
            (render-tree [id prefix]
              (let [node (nth nodes id)
                    children (mapv :child-id (:children node))
                    sb (StringBuilder.)]
                (.append sb (str (render-label node) "\n"))
                (doseq [[i child-id] (map-indexed vector children)]
                  (let [last? (= i (dec (count children)))
                        connector (if last? "\u2514\u2500 " "\u251c\u2500 ")
                        extension (if last? "   " "\u2502  ")]
                    (.append sb (str prefix connector))
                    (.append sb (render-tree child-id (str prefix extension)))))
                (.toString sb)))]
      (when-let [root (:root trace)]
        (str/trimr (render-tree root ""))))))

(defn write-trace!
  "Write trace to a directory: .spl/.txt files + trace.edn + tree.txt.
   Returns the directory path."
  [trace dir]
  (let [dir-file (io/file dir)]
    (.mkdirs dir-file)
    ;; Program files
    (doseq [node (:nodes trace)
            :when (:raw-text node)]
      (spit (io/file dir-file (file-name node)) (:raw-text node)))
    ;; trace.edn (everything except raw-text, which lives in the program files)
    (let [clean-nodes (mapv (fn [node]
                              (-> (dissoc node :raw-text)
                                  (assoc :file (file-name node))))
                            (:nodes trace))]
      (spit (io/file dir-file "trace.edn")
            (with-out-str (pp/pprint (assoc trace :nodes clean-nodes)))))
    ;; tree.txt
    (spit (io/file dir-file "tree.txt") (str (tree-str trace) "\n"))
    dir))
