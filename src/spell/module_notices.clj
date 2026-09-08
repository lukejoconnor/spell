(ns spell.module-notices
  "Bounded run-local module guidance; separate from agent mailboxes."
  (:require [clojure.string :as str]
            [spell.globals :as globals]))

(def max-chars 2048)
(def max-items 8)

(defn enqueue
  "Pure transition. Deduplicate pending entries by kind/module/revision."
  [state handle entry]
  (let [entries (get-in state [:module-notices handle] [])
        identity-keys [:kind :module :revision]]
    (if (some #(= (select-keys % identity-keys)
                  (select-keys entry identity-keys)) entries)
      state
      (assoc-in state [:module-notices handle] (conj entries entry)))))

(defn- label [entry]
  (str (pr-str (:module entry))
       (when (= :recording-failed (:kind entry))
         (str " revision " (:revision entry) " sequence " (:sequence entry)))))

(defn- block [entries remaining oversized?]
  ;; A complete comment before the source is outside any unfinished token or string.
  ;; Escape embedded newlines in identifiers so no identifier becomes code.
  (let [owned (filter #(= :owner (:kind %)) entries)
        failed (filter #(= :recording-failed (:kind %)) entries)
        text (str
               (when (seq owned)
                 (str "You own module(s) " (if oversized? "[identifier exceeds notice limit]" (str/join ", " (map label owned)))
                      " (first installer). Expect edit requests and coordinate edits. Use (patterns/update :module transform & args); others must deliberately name the recorded owner with (patterns/update :module {:owner :recorded-owner} transform & args), replacing :recorded-owner with your handle from patterns/catalog. "))
               (when (seq failed)
                 (str "EDIT COMMITTED / RECORDING FAILED: " (if oversized? "[identifier exceeds notice limit]" (str/join ", " (map label failed)))
                      ". The edit is live; do not replay it. The journal has a gap. "))
               (when oversized? "Inspect (patterns/catalog) and retained edit receipts for the full identifier. ")
               (when (pos? remaining) (str "and " remaining " more queued module notices.")))]
    (str "\n; MODULE NOTICE: " (str/replace (str/replace text "\r" "\\r") "\n" "\\n") "\n")))

(defn- page [entries]
  (loop [n 1 chosen nil]
    (if (<= n (min max-items (count entries)))
      (let [text (block (subvec entries 0 n) (- (count entries) n) false)]
        (if (<= (count text) max-chars)
          (recur (inc n) [n text])
          (or chosen [1 (block (subvec entries 0 1) (dec (count entries)) true)])))
      chosen)))

(defn prepend-pending
  "Prepend a complete comment, preserving the original prefix byte-for-byte.
   Atomically dequeue one bounded page for this handle, without receiving mail.
   Best effort: a provider failure after dequeue does not requeue the page."
  [handle prefix]
  (if-not (and globals/*store* (seq (get-in @globals/*store* [:module-notices handle])))
    prefix
    (loop []
      (let [before @globals/*store*
            entries (get-in before [:module-notices handle])]
        (if-not (seq entries)
          prefix
          (let [[n text] (page entries)
                remaining (subvec entries n)
                after (if (seq remaining)
                        (assoc-in before [:module-notices handle] remaining)
                        (update before :module-notices dissoc handle))]
            (if (compare-and-set! globals/*store* before after)
              (str text prefix)
              (recur))))))))
