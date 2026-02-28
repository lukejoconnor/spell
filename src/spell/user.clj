(ns spell.user
  "User-as-agent: treat the human as an agent with handle :user.
   Supports both agent-initiated communication (agents/!ask :user msg)
   and user-initiated messaging (press Enter to signal readiness).
   Uses a LinkedBlockingQueue to decouple stdin reading from message
   processing, avoiding contention between the reader thread and
   user-call-fn."
  (:require [clojure.string :as str]
            [spell.runtime :as runtime]
            [spell.eval :as eval]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.parse :as parse]
            [spell.stdlib :as stdlib])
  (:import [java.io BufferedReader InputStreamReader]
           [java.util.concurrent LinkedBlockingQueue]))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private last-sender
  "Last agent that sent a message to :user. Used as default recipient."
  (atom :main))

(def ^:private stdin-queue
  "Queue decoupling stdin reading from message processing.
   The reader thread puts lines; user-call-fn takes them."
  (LinkedBlockingQueue.))

(def ^:private signal-pending
  "Whether a stdin-signal is pending. Prevents duplicate signals from
   rapid Enter presses — only one signal is sent until processed."
  (atom false))

(def ^:private seen-msg-names
  "Set of message def symbol names already displayed/processed.
   Prevents re-display when reopen rebuilds the AST including
   historical message defs from inert quine args."
  (atom #{}))

;; =============================================================================
;; Stdin reader thread
;; =============================================================================

(defn- start-stdin-reader!
  "Start a persistent thread that reads lines from reader into stdin-queue.
   On empty lines (the signal), also wakes :user via runtime/send.
   On EOF, puts ::eof sentinel."
  [^BufferedReader reader]
  (future
    (loop []
      (let [line (.readLine reader)]
        (if (nil? line)
          (.put stdin-queue ::eof)
          (do
            (.put stdin-queue line)
            ;; Empty line = signal → wake :user (debounced)
            (when (= "" (str/trim line))
              (when (compare-and-set! signal-pending false true)
                (binding [runtime/*current-handle* :stdin-watch]
                  (runtime/send :user :stdin-signal))))
            (recur)))))))

;; =============================================================================
;; Queue helpers
;; =============================================================================

(defn- take-line!
  "Block on queue for the next line. Throws on EOF."
  []
  (let [line (.take stdin-queue)]
    (when (= line ::eof)
      (throw (ex-info "EOF on user input" {})))
    line))

;; =============================================================================
;; Pure functions
;; =============================================================================

(defn- parse-keyword-at
  "Parse keyword token at index i. Returns [kw next-index] or nil."
  [s i]
  (let [n (count s)]
    (when (and (< i n) (= \: (.charAt s i)))
      (let [j (loop [k (inc i)]
                (if (or (>= k n)
                        (Character/isWhitespace (.charAt s k))
                        (= \) (.charAt s k)))
                  k
                  (recur (inc k))))
            token (subs s i j)]
        (when (> (count token) 1)
          [(keyword (subs token 1)) j])))))

(defn- parse-recipient-spec-at
  "Parse recipient spec at index i.
   Supports :handle and (:a :b) forms.
   Returns [recipients next-index] or nil."
  [s i]
  (let [n (count s)]
    (when (< i n)
      (let [ch (.charAt s i)]
        (cond
          (= \: ch)
          (when-let [[kw j] (parse-keyword-at s i)]
            [[kw] j])

          (= \( ch)
          (loop [k (inc i) recipients []]
            (let [k (loop [p k]
                      (if (and (< p n) (Character/isWhitespace (.charAt s p)))
                        (recur (inc p))
                        p))]
              (cond
                (>= k n) nil
                (= \) (.charAt s k))
                (when (seq recipients)
                  [recipients (inc k)])
                :else
                (if-let [[kw next-k] (parse-keyword-at s k)]
                  (recur next-k (conj recipients kw))
                  nil))))

          :else nil)))))

(defn- find-next-recipient-spec-start
  "Find the next index >= from where a recipient spec starts."
  [s from]
  (let [n (count s)]
    (loop [i from]
      (cond
        (>= i n) nil
        :else
        (let [ch (.charAt s i)
              boundary? (or (zero? i) (Character/isWhitespace (.charAt s (dec i))))
              starter? (or (= \: ch) (= \( ch))
              parsed (when (and boundary? starter?)
                       (parse-recipient-spec-at s i))]
          (if parsed
            i
            (recur (inc i))))))))

(defn parse-user-inputs
  "Parse one input line into routed message segments.
   \"hello\" -> [{:recipients nil :msg \"hello\"}]
   \":main hi :other yo\" ->
     [{:recipients [:main] :msg \"hi\"}
      {:recipients [:other] :msg \"yo\"}]
   \"(:main :other) hi\" ->
     [{:recipients [:main :other] :msg \"hi\"}]"
  [input]
  (let [s (str/trim input)]
    (if (str/blank? s)
      []
      (let [first-start (or (find-next-recipient-spec-start s 0) (count s))
            bare (str/trim (subs s 0 first-start))
            init (if (str/blank? bare) [] [{:recipients nil :msg bare}])]
        (loop [i first-start segments init]
          (if-let [[recipients after-spec] (parse-recipient-spec-at s i)]
            (let [next-start (find-next-recipient-spec-start s after-spec)
                  end (or next-start (count s))
                  msg (str/trim (subs s after-spec end))
                  next-segments (if (str/blank? msg)
                                  segments
                                  (conj segments {:recipients recipients :msg msg}))]
              (if next-start
                (recur next-start next-segments)
                (if (seq next-segments)
                  next-segments
                  [{:recipients nil :msg s}])))
            (if (seq segments) segments [{:recipients nil :msg s}])))))))
(defn parse-user-input
  "Backward-compatible single-message parser.
   Returns [recipient-or-nil message] using the first parsed segment."
  [input]
  (let [{:keys [recipients msg]} (first (parse-user-inputs input))
        recipient (when (= 1 (count recipients)) (first recipients))]
    [recipient msg]))

(defn resolve-recipient
  "Resolve the actual recipient. Uses explicit if provided,
   otherwise falls back to last-sender-val, then :main."
  [explicit last-sender-val]
  (or explicit last-sender-val :main))

(defn- lookup-recipients
  "Look up the current roles map from globals."
  []
  (or (globals/get-val :roles) {}))

;; =============================================================================
;; Message extraction
;; =============================================================================

(defn- extract-messages
  "Extract ALL messages from a raw completion string.
   Walks the parsed AST to find all (def msg-N {:from h ...}) forms.
   Returns a vector of {:name sym :msg map}."
  [raw]
  (try
    (let [form (first (parse/read-all (parse/balance-parens raw)))
          msgs (->> (tree-seq seq? seq form)
                    (keep (fn [f]
                            (when (and (seq? f) (= 'def (first f)) (>= (count f) 3))
                              (let [sym (second f)
                                    val (nth f 2)]
                                (when (and (map? val) (contains? val :from))
                                  {:name sym :msg val})))))
                    vec)]
      (when (seq msgs) msgs))
    (catch Exception _e nil)))

;; =============================================================================
;; IO helpers
;; =============================================================================

(defn- drain-blank-lines!
  "Remove blank lines from the head of the queue (signal residue)."
  []
  (while (let [head (.peek stdin-queue)]
           (and (some? head) (not= head ::eof) (str/blank? head)))
    (.poll stdin-queue)))

(defn- prompt-and-read
  "Print recipients + prompt, read a line from the queue.
   Returns nil on blank input (user cancels text entry)."
  []
  (let [recipients (lookup-recipients)]
    (binding [*out* *err*]
      (when (seq recipients)
        (doseq [[handle desc] recipients]
          (println (str "  " handle (when desc (str " — " desc))))))
      (print "> ")
      (flush))
    (let [line (take-line!)]
      (when-not (str/blank? line) line))))

;; =============================================================================
;; User call function (the "API call" equivalent)
;; =============================================================================

(def ^:private quine-restart
  "Close current eval/do and open a new one within the same quine.
   Creates a new inert arg: (quine completion (eval (do ...old...)) (eval (do ...new...))).
   Quine evaluates only the last arg — old evals become inert context (visible but not executed)."
  ")) (eval (do ")

(def ^:private split-top-level-restart
  "Close the current top-level quine with inert nil, then open a fresh
   top-level dummy quine:
   (quine completion (eval (do ...old... nil)))
   (quine completion (eval (do ...new...))
   This keeps user-originated sends out of trailing-expression preemption paths."
  "nil ))) (quine completion (eval (do ")

(defn- display-messages!
  "Print messages to stderr, updating last-sender as we go."
  [messages]
  (binding [*out* *err*]
    (doseq [{:keys [from body expects-response]} messages]
      (cond
        body             (do (reset! last-sender from)
                             (println (str "[agent " from "] " body)))
        expects-response (do (reset! last-sender from)
                             (println (str "[agent " from " is waiting for input]")))))))

(defn- user-call-fn
  "The 'API call' for the user agent.
   Takes a prompt string (the reopened completion) and returns a response string
   (code to append). Analogous to call-fn in make-llm.

   Two cases, checked in order (using only NEW messages):
   1. stdin-signal or expects-reply: display messages, show agent list,
      read input, parse :target routing, send to resolved recipient.
   2. fire-and-forget: display messages, quine-restart (no stdin read)."
  [prompt-str]
  (let [balanced    (parse/balance-parens prompt-str)
        all-entries (or (extract-messages balanced) [])
        new-entries (remove #(@seen-msg-names (:name %)) all-entries)
        new-msgs    (mapv :msg new-entries)
        agent-msgs    (vec (remove #(= :stdin-watch (:from %)) new-msgs))
        expects-reply? (some :expects-response new-msgs)
        stdin-signal?  (some #(= :stdin-watch (:from %)) new-msgs)
        result
        (cond
          ;; No new messages — nothing to do
          (empty? new-entries)
          "nil "

          ;; Interactive: user pressed Enter or agent asked for reply
          (or stdin-signal? expects-reply?)
          (do
            (when stdin-signal?
              (reset! signal-pending false)
              (drain-blank-lines!))
            (when (seq agent-msgs)
              (display-messages! agent-msgs))
            (if-let [input (prompt-and-read)]
              (let [segments (parse-user-inputs input)]
                (reduce
                  (fn [default-target {:keys [recipients msg]}]
                    (let [targets (or recipients
                                      [(resolve-recipient nil default-target)])]
                      (doseq [target targets]
                        (runtime/send target msg)
                        (reset! last-sender target))
                      (or (last targets) default-target)))
                  @last-sender
                  segments)
                split-top-level-restart)
              ;; Blank input — cancel text entry, return to idle
              quine-restart))

          ;; Fire-and-forget — no stdin read needed
          :else
          (do
            (display-messages! new-msgs)
            quine-restart))]

    ;; Mark all new messages as seen
    (swap! seen-msg-names into (map :name new-entries))
    result))

;; =============================================================================
;; User-self (box-based, analogous to -llm)
;; =============================================================================

(defn- user-self
  "Box-based execution for the user agent.
   Structurally similar to -llm but simpler (no trace, no retry, no verbose).
   Uses make-awake-fn to construct the inside-fn from the eval-fn."
  [eval-fn handle parent-handle prompt-str]
  (let [completion (promise)
        awake-fn (runtime/make-awake-fn handle eval-fn)]
    (future
      (try
        (let [response (user-call-fn prompt-str)]
          (deliver completion (str prompt-str response)))
        (catch Exception e
          (deliver completion e))))
    (runtime/box handle completion awake-fn)))

;; =============================================================================
;; Registration
;; =============================================================================

(defn- make-user-inbox-fn*
  "Build the inbox function for :user using the standard eval pipeline."
  []
  (let [variant-builtins (merge eval/core-builtins
                                {'describe-fn stdlib/describe}
                                llm/core-namespaces)
        ;; user-self-fn reads eval-fn dynamically via *current-eval-fn*
        user-self-fn (fn [prompt-str]
                       (user-self runtime/*current-eval-fn*
                                  runtime/*current-handle* runtime/*current-handle* prompt-str))
        ;; Effect builtins: !llm-self (user-self) + agents namespace
        effect-builtins {'!llm-self user-self-fn
                         'agents runtime/agents-namespace}
        eval-builtin (llm/make-eval variant-builtins effect-builtins)
        config {:variant-builtins variant-builtins
                :eval-builtin eval-builtin
                :allow-multiple-top-level? true
                :recover-fn nil}]
    (llm/make-inbox-fn config (atom nil))))

(defn reset-state!
  "Reset module-level state. For use in test fixtures."
  []
  (reset! last-sender :main)
  (.clear stdin-queue)
  (reset! signal-pending false)
  (reset! seen-msg-names #{}))

(defn register-user-agent!
  "Register :user as an agent in the runtime system.
   0-arity: uses System/in as reader. 1-arity: accepts a BufferedReader.
   Starts a persistent stdin reader thread that feeds the queue.
   Idempotent: no-op if :user is already registered.
   Parent-handle is :main (the initial agent)."
  ([]
   (register-user-agent! (BufferedReader. (InputStreamReader. System/in))))
  ([^BufferedReader reader]
   (when-not (runtime/handle? :user)
     (reset! seen-msg-names #{})
     (let [eval-fn (make-user-inbox-fn*)
           initial "(quine completion (eval (do )))"]
       (runtime/start-box :user eval-fn initial :main)
       (globals/set-val :roles (assoc (or (globals/get-val :roles) {})
                                      :user "human user — interactive terminal"))
       (start-stdin-reader! reader)))))
