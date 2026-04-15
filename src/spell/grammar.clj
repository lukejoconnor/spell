(ns spell.grammar
  "Prefix-aware grammar generation for OpenAI custom tool constrained decoding.

   Given a Spell/Clojure prefix, computes a compact Lark grammar that accepts
   valid suffixes which complete the prefix into balanced S-expressions."
  (:require [clojure.string :as str]))

(def ^:private unary-prefix-chars
  "Reader prefix operators that require one following expression."
  #{\' \` \~ \@})

(defn- atom-boundary?
  [c]
  (or (nil? c)
      (Character/isWhitespace ^char c)
      (contains? #{\( \) \[ \] \{ \} \" \;} c)))

(defn- map-frame
  []
  {:type :map :odd? false})

(defn- push-frame
  [state frame-type]
  (update state :frames conj (if (= frame-type :map)
                               (map-frame)
                               {:type frame-type})))

(defn- toggle-current-map-parity
  [frames]
  (let [idx (dec (count frames))]
    (if (= :map (get-in frames [idx :type]))
      (update-in frames [idx :odd?] not)
      frames)))

(defn- mark-expr-complete
  "Mark one expression as completed in the current frame.
   Any dangling unary prefix chain is satisfied by this expression."
  [state]
  (-> state
      (assoc :pending-prefix 0)
      (update :frames toggle-current-map-parity)))

(defn- close-frame
  [state close-ch]
  (let [expected (case close-ch
                   \) :list
                   \] :vec
                   \} :map)
        top (peek (:frames state))]
    (when (= :root (:type top))
      (throw (ex-info "Unmatched closing delimiter" {:char close-ch})))
    (when-not (= expected (:type top))
      (throw (ex-info "Mismatched closing delimiter"
                      {:char close-ch :expected expected :actual (:type top)})))
    ;; Closing a container completes one expression in the parent frame.
    (-> state
        (update :frames pop)
        mark-expr-complete)))

(defn analyze-prefix
  "Analyze prefix reader state.

   Returns {:mode :normal|:string|:string-escape|:comment
            :pending-prefix int
            :frames [{:type :root} ...]
            :depth int}

   Throws ex-info on irrecoverable prefix errors (e.g., mismatched closers)."
  [prefix]
  (let [s (str prefix)
        len (count s)]
    (loop [i 0
           state {:mode :normal
                  :pending-prefix 0
                  :frames [{:type :root}]}]
      (if (>= i len)
        (assoc state :depth (dec (count (:frames state))))
        (let [c (.charAt ^String s i)]
          (case (:mode state)
            :comment
            (if (= c \newline)
              (recur (inc i) (assoc state :mode :normal))
              (recur (inc i) state))

            :string
            (cond
              (= c \\) (recur (inc i) (assoc state :mode :string-escape))
              (= c \") (recur (inc i) (-> state
                                           (assoc :mode :normal)
                                           mark-expr-complete))
              :else     (recur (inc i) state))

            :string-escape
            ;; Consume one escaped char and return to string mode.
            (recur (inc i) (assoc state :mode :string))

            :normal
            (cond
              (Character/isWhitespace c)
              (recur (inc i) state)

              (= c \;)
              (recur (inc i) (assoc state :mode :comment))

              ;; Two-char reader macro must be checked before one-char ~
              (and (= c \~)
                   (< (inc i) len)
                   (= (.charAt ^String s (inc i)) \@))
              (recur (+ i 2) (update state :pending-prefix inc))

              (contains? unary-prefix-chars c)
              (recur (inc i) (update state :pending-prefix inc))

              (= c \() (recur (inc i) (push-frame state :list))
              (= c \[) (recur (inc i) (push-frame state :vec))
              (= c \{) (recur (inc i) (push-frame state :map))

              (or (= c \)) (= c \]) (= c \}))
              (recur (inc i) (close-frame state c))

              (= c \")
              (recur (inc i) (assoc state :mode :string))

              :else
              (let [j (loop [k i]
                        (if (or (>= k len)
                                (atom-boundary? (.charAt ^String s k)))
                          k
                          (recur (inc k))))]
                ;; Atom completes one expression.
                (recur j (mark-expr-complete state))))))))))

(defn- consume-one-expr-state
  "State after one additional complete expression in the current frame."
  [state]
  (-> state
      (assoc :pending-prefix 0)
      (update :frames toggle-current-map-parity)))

(defn- context-rules
  "Build frame-sensitive continuation rules.
   Returns {:current nonterminal-name :rules [rule ...]}"
  [frames stem]
  (let [depth (dec (count frames))
        ids (vec (map #(str stem %) (range (inc depth))))
        rule-for (fn [idx]
                   (let [id (nth ids idx)
                         frame (nth frames idx)]
                     (if (zero? idx)
                       (str id ": expr*")
                       (let [parent (nth ids (dec idx))]
                         (case (:type frame)
                           :list (str id ": expr* \")\" " parent)
                           :vec  (str id ": expr* \"]\" " parent)
                           :map  (if (:odd? frame)
                                   (str id ": expr (expr expr)* \"}\" " parent)
                                   (str id ": (expr expr)* \"}\" " parent)))))))
        rules (mapv rule-for (range (inc depth)))]
    {:current (nth ids depth)
     :rules rules}))

(def ^:private base-grammar-rules
  ["?expr: atom|list|vec|map|q|qq|uqs|uq|d"
   "list: \"(\" expr* \")\""
   "vec: \"[\" expr* \"]\""
   "map: \"{\" (expr expr)* \"}\""
   "q: \"'\" expr"
   "qq: \"`\" expr"
   "uqs: \"~@\" expr"
   "uq: \"~\" expr"
   "d: \"@\" expr"
   "atom: NUM|STR|KW|SYM"
   "NUM: /-?(0|[1-9][0-9]*)(\\.[0-9]+)?/"
   "STR: /\"([^\"\\\\]|\\\\.)*\"/"
   "KW: /:[a-zA-Z_+*\\-\\/?=<>!&.$%][a-zA-Z0-9_+*\\-\\/?=<>!&.$%:']*/"
   "SYM: /[a-zA-Z_+*\\-\\/?=<>!&.$%][a-zA-Z0-9_+*\\-\\/?=<>!&.$%:']*/"
   "%ignore /[\\s,]+/"
   "%ignore /;[^\\n]*/"])

(defn suffix-lark-grammar
  "Generate a compact Lark grammar definition that accepts valid suffixes for prefix.

   Throws ex-info when the prefix is already irrecoverably invalid.
   Returns a grammar string."
  ([prefix] (suffix-lark-grammar prefix {}))
  ([prefix _opts]
   (let [state (analyze-prefix prefix)
         needs-expr? (pos? (:pending-prefix state))
         base-context (context-rules (:frames state) "c")
         after-expr-context (when (or needs-expr?
                                      (= :string (:mode state))
                                      (= :string-escape (:mode state)))
                              (context-rules (:frames (consume-one-expr-state state)) "d"))
         post-comment-start (if needs-expr?
                              (str "expr " (:current (or after-expr-context base-context)))
                              (:current base-context))
         root-only? (= 1 (count (:frames state)))
         start-rule (case (:mode state)
                      :normal
                      (if needs-expr?
                        (str "start: expr " (:current (or after-expr-context base-context)))
                        (str "start: " (:current base-context)))

                      :string
                      (str "start: STR_TAIL " (:current after-expr-context))

                      :string-escape
                      (str "start: STR_ESC_CHAR STR_TAIL " (:current after-expr-context))

                      :comment
                      (if (and root-only? (not needs-expr?))
                        (str "start: COMMENT_REST | COMMENT_REST NL " post-comment-start)
                        (str "start: COMMENT_REST NL " post-comment-start)))
         state-rules (cond-> []
                       (contains? #{:string :string-escape} (:mode state))
                       (conj "STR_TAIL: /([^\"\\\\]|\\\\.)*\"/")

                       (= :string-escape (:mode state))
                       (conj "STR_ESC_CHAR: /./")

                       (= :comment (:mode state))
                       (into ["COMMENT_REST: /[^\\n]*/" "NL: /\\n+/"]))
         all-rules (-> []
                       (conj start-rule)
                       (into base-grammar-rules)
                       (into (:rules base-context))
                       (into (when after-expr-context (:rules after-expr-context)))
                       (into state-rules))
         definition (str/join "\n" all-rules)]
     definition)))

(defn suffix-lark-grammar-stats
  "Return grammar + size stats for a prefix."
  ([prefix] (suffix-lark-grammar-stats prefix {}))
  ([prefix {:keys [max-chars] :or {max-chars 2000} :as opts}]
   (let [definition (suffix-lark-grammar prefix opts)
         char-count (count definition)]
     {:definition definition
      :char-count char-count
      :max-chars max-chars
      :over-limit? (> char-count max-chars)})))

(defn openai-suffix-grammar-format
  "Return OpenAI custom-tool grammar format map for a prefix.

   Suitable for Responses API custom tools:
   {:type grammar :syntax lark :definition <grammar>}"
  ([prefix] (openai-suffix-grammar-format prefix {}))
  ([prefix opts]
   {:type "grammar"
    :syntax "lark"
    :definition (suffix-lark-grammar prefix opts)}))
