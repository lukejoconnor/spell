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
  (:import [java.io BufferedReader Closeable InputStreamReader]
           [java.util.concurrent LinkedBlockingQueue]
           [org.jline.keymap KeyMap]
           [org.jline.reader EndOfFileException LineReader LineReaderBuilder Reference UserInterruptException Widget]
           [org.jline.terminal Attributes Terminal TerminalBuilder]))

;; =============================================================================
;; State
;; =============================================================================

(def ^:dynamic last-sender
  "Last agent that sent a message to :user. Used as default recipient."
  nil)

(def ^:dynamic stdin-queue
  "Queue decoupling stdin reading from message processing.
   The reader thread puts InputEvents; tests may also put raw values directly.
   user-call-fn takes and unwraps them."
  nil)

(defrecord ^:private InputEvent [value wake-when-idle? waiter-token])

(def ^:dynamic input-lock
  "Serializes waiter registration with enqueue-and-wake decisions."
  nil)

(def ^:dynamic input-waiting?
  "True while user-call-fn owns the one active terminal input waiter."
  nil)

(def ^:dynamic input-waiter-token
  "Identity token for the invocation that owns input-waiting?."
  nil)

(def ^:dynamic input-cycle-depth
  "Number of active :user wake/eval cycles, including the pre-drain phase."
  nil)

(def ^:dynamic input-closed?
  "Sticky EOF state. Once closed, later asks fail promptly instead of hanging."
  nil)

(def ^:dynamic signal-pending
  "Whether a stdin-signal is pending. Prevents duplicate signals from
   rapid Enter presses — only one signal is sent until processed."
  nil)

(def ^:dynamic seen-msg-names
  "Set of message def symbol names already displayed/processed.
   Prevents re-display when reopen rebuilds the AST including
   historical message defs from inert quine args."
  nil)

(def ^:dynamic interactive-session
  "Active JLine session, when the CLI is attached to a TTY."
  nil)

(def ^:dynamic reader-tasks
  "Reader futures owned by this module, cancelled during reset/session cleanup."
  nil)

(def ^:dynamic reader-generation
  "Invalidates late events from a reader that was cancelled during reset."
  nil)

(defn call-with-session [f]
  (binding [last-sender (atom :main) stdin-queue (LinkedBlockingQueue.)
            input-lock (Object.) input-waiting? (atom false) input-waiter-token (atom nil)
            input-cycle-depth (atom 0) input-closed? (atom false) signal-pending (atom false)
            seen-msg-names (atom #{}) interactive-session (atom nil)
            reader-tasks (atom #{}) reader-generation (atom 0)]
    (f)))

(defn- wake-user! []
  (when (compare-and-set! signal-pending false true)
    (binding [runtime/*current-handle* :stdin-watch]
      (runtime/send :user :stdin-signal))))

(defn- input-value [event]
  (if (instance? InputEvent event) (:value event) event))

(defn- wake-when-idle? [event]
  (and (instance? InputEvent event) (:wake-when-idle? event)))

(defn- queued-idle-wake? []
  (boolean (some wake-when-idle? (iterator-seq (.iterator stdin-queue)))))

(defn- wake-queued-input-if-idle! []
  (when (and (not @input-waiting?)
             (zero? @input-cycle-depth)
             (queued-idle-wake?))
    (wake-user!)))

(defn- begin-input-cycle! [generation]
  (locking input-lock
    (when (= generation @reader-generation)
      (swap! input-cycle-depth inc))))

(defn- end-input-cycle! [generation]
  (locking input-lock
    (when (= generation @reader-generation)
      (swap! input-cycle-depth #(max 0 (dec %)))
      (wake-queued-input-if-idle!))))

(defn- queue-input!
  "Queue an input event and wake :user only when no prompt is already waiting.
   Holding input-lock across both operations closes the reply-vs-signal race."
  [value wake-idle?]
  (locking input-lock
    (let [queue-empty? (.isEmpty stdin-queue)]
      (when (= value ::eof)
        (reset! input-closed? true))
      (.put stdin-queue (->InputEvent value wake-idle? nil))
      ;; Buffered readers can reach EOF after preloaded reply text. Do not let
      ;; that EOF wake :user ahead of the pending reply and steal it from an ask.
      (when (and wake-idle?
                 (not @input-waiting?)
                 (zero? @input-cycle-depth)
                 (or (not= value ::eof) queue-empty?))
        (wake-user!)))))

(defn- queue-buffered-submission! [submission]
  ;; Buffered/non-TTY input preserves the historical blank-line signal.
  (queue-input! submission
                (or (= submission ::eof)
                    (= submission ::cancel)
                    (and (string? submission) (str/blank? submission)))))

(defn- queue-interactive-submission! [submission]
  ;; Every JLine readLine result is a logical submission. If no ask is already
  ;; waiting, it initiates a user turn; otherwise the waiter consumes it directly.
  (queue-input! submission true))

;; =============================================================================
;; Stdin reader thread
;; =============================================================================

(defn- start-stdin-reader!
  "Start a persistent thread that reads lines from reader into stdin-queue.
   On empty lines (the signal), also wakes :user via runtime/send.
   On EOF, puts ::eof sentinel."
  [^BufferedReader reader]
  (let [generation @reader-generation]
    (future
      (loop []
        (when (= generation @reader-generation)
          (let [line (.readLine reader)]
            (when (= generation @reader-generation)
              (if (nil? line)
                (queue-buffered-submission! ::eof)
                (do
                  (queue-buffered-submission! line)
                  (recur))))))))))

(defn- install-newline-bindings!
  "Install manual-newline bindings without changing ordinary Enter submission."
  [^LineReader reader]
  (.put (.getWidgets reader) "spell-newline"
        (reify Widget
          (apply [_]
            (.write (.getBuffer reader) "\n")
            true)))
  (let [binding (Reference. "spell-newline")
        ^KeyMap keymap (get (.getKeyMaps reader) LineReader/MAIN)]
    ;; Alt+Enter is commonly ESC followed by CR. The other bindings are only
    ;; useful in terminals that report Shift+Enter distinctly.
    (.bind keymap binding (into-array String ["\033\r"
                                               "\033[13;2u"
                                               "\033[27;2;13~"]))
    ;; Some minimal terminal descriptions omit the default bracketed-paste
    ;; binding. Install the documented sequence explicitly.
    (.bind keymap (Reference. LineReader/BEGIN_PASTE) "\033[200~")))

(defn- start-jline-reader!
  "Read logical JLine submissions until Ctrl-D or close. Lifecycle completion
   records actual worker exit, including terminal restoration, after cancellation."
  ([^LineReader reader]
   (start-jline-reader! reader (atom false)))
  ([^LineReader reader stopping?]
   (start-jline-reader! reader stopping? {}))
  ([^LineReader reader stopping? {:keys [state finished on-stop]
                                :or {state (atom :pending) finished (promise)
                                     on-stop (fn [])}}]
   (let [generation @reader-generation
         active? #(and (not @stopping?) (= generation @reader-generation))]
     (future
       (when (compare-and-set! state :pending :running)
         (try
           (loop []
             (when (active?)
               (let [action
                     (try
                       (let [submission (.readLine reader "> ")]
                         (when (active?) (queue-interactive-submission! submission))
                         :continue)
                       (catch UserInterruptException _
                         (when (active?) (queue-interactive-submission! ::cancel))
                         :continue)
                       (catch EndOfFileException _
                         (when (active?) (queue-interactive-submission! ::eof))
                         :stop)
                       (catch Throwable e
                         (when (active?)
                           (when-not (instance? java.io.IOError e)
                             (binding [*out* *err*]
                               (println "Interactive input stopped:" (.getMessage e))))
                           (queue-interactive-submission! ::eof))
                         :stop))]
                 (when (= action :continue) (recur)))))
           (finally
             (try (on-stop)
                  (finally
                    (reset! state :stopped)
                    (deliver finished true))))))))))

;; =============================================================================
;; Queue helpers
;; =============================================================================

(defn- take-line!
  "Block on queue for the next line. Throws on EOF."
  ([] (take-line! nil))
  ([waiter-token]
  (when (and @input-closed? (.isEmpty stdin-queue))
    (throw (ex-info "EOF on user input" {:type :user-input-eof})))
  (let [event (.take stdin-queue)
        line (input-value event)]
    (when (and (= line ::reset)
               (or (not (instance? InputEvent event))
                   (nil? (:waiter-token event))
                   (identical? waiter-token (:waiter-token event))))
      (throw (ex-info "User input reset" {:type :user-input-reset})))
    (when (= line ::eof)
      (throw (ex-info "EOF on user input" {:type :user-input-eof})))
    line)))

(defn- remove-waiter-resets! [waiter-token]
  (doseq [event (iterator-seq (.iterator stdin-queue))]
    (when (and (instance? InputEvent event)
               (= ::reset (:value event))
               (identical? waiter-token (:waiter-token event)))
      (.remove stdin-queue event))))

(defn- clear-input-except-waiter-reset! [waiter-token]
  (doseq [event (iterator-seq (.iterator stdin-queue))]
    (when-not (and waiter-token
                   (instance? InputEvent event)
                   (= ::reset (:value event))
                   (identical? waiter-token (:waiter-token event)))
      (.remove stdin-queue event))))

;; =============================================================================
;; Pure functions
;; =============================================================================

(defn- parse-keyword-at
  "Parse keyword token at index i. Returns [kw next-index] or nil."
  [s i limit]
  (let [n (min (count s) limit)]
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
  [s i limit]
  (let [n (min (count s) limit)]
    (when (< i n)
      (let [ch (.charAt s i)]
        (cond
          (= \: ch)
          (when-let [[kw j] (parse-keyword-at s i n)]
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
                (if-let [[kw next-k] (parse-keyword-at s k n)]
                  (recur next-k (conj recipients kw))
                  nil))))

          :else nil)))))

(defn- find-next-recipient-spec-start
  "Find the next recipient spec in [from, limit)."
  [s from limit]
  (loop [i from]
    (cond
      (>= i limit) nil
      :else
      (let [ch (.charAt s i)
            boundary? (or (zero? i) (Character/isWhitespace (.charAt s (dec i))))
            starter? (or (= \: ch) (= \( ch))
            parsed (when (and boundary? starter?)
                     (parse-recipient-spec-at s i limit))]
        (if parsed i (recur (inc i)))))))

(defn- unescape-recipient-markers
  "Treat escaped recipient markers as literals in message text.
   Supported escapes: \\:."
  [s]
  (let [n (count s)
        sb (StringBuilder.)]
    (loop [i 0]
      (if (>= i n)
        (.toString sb)
        (let [ch (.charAt s i)]
          (if (and (= \\ ch) (< (inc i) n))
            (let [next-ch (.charAt s (inc i))]
              (if (= \: next-ch)
                (do
                  (.append sb next-ch)
                  (recur (+ i 2)))
                (do
                  (.append sb ch)
                  (recur (inc i)))))
            (do
              (.append sb ch)
              (recur (inc i)))))))))

(defn parse-user-inputs
  "Parse one logical submission into routed message segments.
   Recipient specs are recognized only on the first line, so multiline
   message bodies remain one message."
  [input]
  (let [s (str/trim input)]
    (if (str/blank? s)
      []
      (let [route-limit (or (str/index-of s "\n") (count s))
            first-start (or (find-next-recipient-spec-start s 0 route-limit)
                            (count s))
            bare (unescape-recipient-markers (str/trim (subs s 0 first-start)))
            init (if (str/blank? bare) [] [{:recipients nil :msg bare}])]
        (loop [i first-start segments init]
          (if-let [[recipients after-spec] (parse-recipient-spec-at s i route-limit)]
            (let [next-start (find-next-recipient-spec-start s after-spec route-limit)
                  end (or next-start (count s))
                  msg (unescape-recipient-markers (str/trim (subs s after-spec end)))
                  next-segments (if (str/blank? msg)
                                  segments
                                  (conj segments {:recipients recipients :msg msg}))]
              (if next-start
                (recur next-start next-segments)
                (if (seq next-segments)
                  next-segments
                  [{:recipients nil :msg (unescape-recipient-markers s)}])))
            (if (seq segments)
              segments
              [{:recipients nil :msg (unescape-recipient-markers s)}])))))))

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
   Reads message bindings, resolving quoted data and run-owned references.
   Returns a vector of {:name sym :msg map}."
  [raw]
  (try
    (let [form (first (parse/read-all (parse/balance-parens raw)))
          msgs (->> (tree-seq #(and (seq? %) (not= 'quote (first %))) seq form)
                    (keep (fn [f]
                            (when (and (seq? f) (= 'def (first f)) (>= (count f) 3))
                              (let [sym (second f)
                                    value-form (nth f 2)
                                    val (cond
                                          (and (seq? value-form) (= 2 (count value-form))
                                               (= 'quote (first value-form)))
                                          (second value-form)
                                          (and (seq? value-form) (= 2 (count value-form))
                                               (= 'stored (first value-form))
                                               (string? (second value-form)))
                                          (eval/stored (second value-form))
                                          :else value-form)]
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
           (and (some? head)
                (let [value (input-value head)]
                  (and (not= value ::eof)
                       (string? value)
                       (str/blank? value)))))
    (.poll stdin-queue)))

(defn- print-lines! [lines]
  (if-let [{:keys [^LineReader reader lock]} @interactive-session]
    (locking lock
      (doseq [line lines]
        (.printAbove reader (str line))))
    (binding [*out* *err*]
      (doseq [line lines] (println line))
      (flush))))

(defn- prompt-and-read
  "Display recipients and read one logical submission from the queue.
   Returns nil on blank input or Ctrl-C cancellation."
  [generation]
  (let [waiter-token (Object.)]
    (locking input-lock
      (when-not (= generation @reader-generation)
        (throw (ex-info "User input session reset" {:type :user-input-reset})))
      (when @input-waiter-token
        (throw (ex-info "User input waiter already active" {:type :user-input-waiter-active})))
      (reset! input-waiter-token waiter-token)
      (reset! input-waiting? true))
    (try
      (let [recipients (lookup-recipients)]
        (when (seq recipients)
          (print-lines! (map (fn [[handle desc]]
                               (str "  " handle (when desc (str " — " desc))))
                             recipients)))
        (when-not @interactive-session
          (binding [*out* *err*]
            (print "> ")
            (flush)))
        (locking input-lock
          (when-not (and (= generation @reader-generation)
                         (identical? waiter-token @input-waiter-token))
            (throw (ex-info "User input session reset" {:type :user-input-reset}))))
        (let [line (take-line! waiter-token)]
          (when-not (or (= line ::cancel) (str/blank? line)) line)))
      (finally
        (locking input-lock
          (when (identical? waiter-token @input-waiter-token)
            (remove-waiter-resets! waiter-token)
            (reset! input-waiter-token nil)
            (reset! input-waiting? false)
            ;; A second interactive submission may have arrived before the current
            ;; waiter released ownership. Wake exactly one follow-up idle turn.
            (wake-queued-input-if-idle!)))))))

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
  "Display messages safely above an active JLine prompt."
  [messages]
  (print-lines!
    (keep (fn [{:keys [from body expects-response]}]
            (cond
              body (str "[agent " from "] " body)
              expects-response (str "[agent " from " is waiting for input]")))
          messages)))

(defn- ensure-current-generation! [generation]
  (when-not (= generation @reader-generation)
    (throw (ex-info "User input session reset" {:type :user-input-reset}))))

(defn- newest-sender [messages fallback]
  (or (:from (last (filter #(or (:body %) (:expects-response %)) messages)))
      fallback))

(defn- user-call-fn
  "The 'API call' for the user agent.
   Takes a prompt string (the reopened completion) and returns a response string
   (code to append). Analogous to call-fn in the compiled-agent pipeline.

   Two cases, checked in order (using only NEW messages):
   1. stdin-signal or expects-reply: display messages, show agent list,
      read input, parse :target routing, send to resolved recipient.
   2. fire-and-forget: display messages, quine-restart (no stdin read)."
  ([prompt-str]
   (user-call-fn prompt-str @reader-generation))
  ([prompt-str generation]
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
            (locking input-lock
              (ensure-current-generation! generation)
              (when stdin-signal?
                (reset! signal-pending false)
                (drain-blank-lines!))
              (reset! last-sender (newest-sender agent-msgs @last-sender)))
            (when (seq agent-msgs)
              (display-messages! agent-msgs))
            (if-let [input (prompt-and-read generation)]
              (let [segments (parse-user-inputs input)]
                (locking input-lock
                  (ensure-current-generation! generation)
                  (let [final-target
                        (reduce
                          (fn [default-target {:keys [recipients msg]}]
                            (let [targets (or recipients
                                              [(resolve-recipient nil default-target)])]
                              (doseq [target targets]
                                (if-let [request (last (filter #(and (= target (:from %))
                                                                        (runtime/actionable-request-live? %)) agent-msgs))]
                                  (runtime/reply request msg)
                                  (runtime/send target msg)))
                              (or (last targets) default-target)))
                          @last-sender
                          segments)]
                    (reset! last-sender final-target)
                    (swap! seen-msg-names into (map :name new-entries)))
                  split-top-level-restart))
              ;; Blank input — cancel text entry, return to idle
              (locking input-lock
                (ensure-current-generation! generation)
                (swap! seen-msg-names into (map :name new-entries))
                quine-restart)))

          ;; Fire-and-forget — no stdin read needed
          :else
          (do
            (locking input-lock
              (ensure-current-generation! generation)
              (reset! last-sender (newest-sender new-msgs @last-sender)))
            (display-messages! new-msgs)
            (locking input-lock
              (ensure-current-generation! generation)
              (swap! seen-msg-names into (map :name new-entries))
              quine-restart)))]
    result)))

;; =============================================================================
;; User-self (box-based, analogous to -llm)
;; =============================================================================

(defn- user-self
  "Box-based execution for the user agent.
   Structurally similar to -llm but simpler (no trace, no retry, no verbose).
   Uses make-awake-fn to construct the inside-fn from the eval-fn."
  [eval-fn handle parent-handle prompt-str generation]
  (let [completion (promise)
        awake-fn (runtime/make-awake-fn handle eval-fn)]
    (future
      (try
        (when-not (= generation @reader-generation)
          (throw (ex-info "User input session reset" {:type :user-input-reset})))
        (let [response (user-call-fn prompt-str generation)]
          (deliver completion (str prompt-str response)))
        (catch Exception e
          (deliver completion e))))
    (runtime/box handle completion awake-fn)))

;; =============================================================================
;; Registration
;; =============================================================================

(defn- make-user-inbox-fn*
  "Build the inbox function for :user using the standard eval pipeline."
  [generation]
  (let [variant-builtins (merge eval/core-builtins
                                {'describe-fn stdlib/describe}
                                llm/core-namespaces)
        ;; user-self-fn reads eval-fn dynamically via *current-eval-fn*
        user-self-fn (fn [prompt]
                       (let [prompt-str (if (and (seq? prompt) (= 'quine (first prompt)))
                                          (eval/serialize-quine-prefix prompt)
                                          (str prompt))]
                         (user-self runtime/*current-eval-fn*
                                    runtime/*current-handle* runtime/*current-handle* prompt-str
                                    generation)))
        ;; Effect builtins: !llm-self (user-self) + agents namespace
        effect-builtins {'!llm-self user-self-fn
                         'agents runtime/agents-namespace}
        eval-builtin (llm/make-eval variant-builtins
                                    effect-builtins
                                    {'blocking runtime/blocking-namespace})
        config {:variant-builtins variant-builtins
                :eval-builtin eval-builtin
                :allow-multiple-top-level? true
                :recover-fn nil}
        inbox-fn (llm/make-inbox-fn config (atom nil))]
    (with-meta inbox-fn
      (assoc (meta inbox-fn)
             :spell/before-awake #(begin-input-cycle! generation)
             :spell/after-awake #(end-input-cycle! generation)))))

(defn reset-state!
  "Stop owned reader tasks and reset module-level input state between runs/tests."
  []
  ;; Invalidate a pre-waiter cycle atomically with waiter inspection. A prompt
  ;; either installs its waiter first and receives ::reset, or observes the new
  ;; generation and aborts before it can block on the queue.
  (let [waiter-token
        (locking input-lock
          (swap! reader-generation inc)
          (when-let [waiter-token @input-waiter-token]
            (.put stdin-queue (->InputEvent ::reset false waiter-token)))
          @input-waiter-token)]
    (when waiter-token
      (let [deadline (+ (System/currentTimeMillis) 1000)]
        (while (and (identical? waiter-token @input-waiter-token)
                    (< (System/currentTimeMillis) deadline))
          (Thread/sleep 5)))))
  (when-let [^Closeable session (:closeable @interactive-session)]
    (.close session))
  (doseq [task @reader-tasks]
    (future-cancel task))
  (reset! reader-tasks #{})
  (reset! interactive-session nil)
  (reset! last-sender :main)
  (locking input-lock
    (clear-input-except-waiter-reset! @input-waiter-token))
  (reset! signal-pending false)
  (when-not @input-waiter-token
    (reset! input-waiting? false))
  (reset! input-cycle-depth 0)
  (reset! input-closed? false)
  (reset! seen-msg-names #{}))

(defn- register-user-agent-core! [start-reader!]
  (when-not (runtime/handle? :user)
    (reset! seen-msg-names #{})
    (let [generation @reader-generation
          eval-fn (make-user-inbox-fn* generation)
          initial "(quine completion (eval (do )))"]
      (runtime/start-box :user eval-fn initial :main)
      (globals/set-val :roles (assoc (or (globals/get-val :roles) {})
                                     :user "human user — interactive terminal"))
      (let [task (start-reader!)]
        (swap! reader-tasks conj task)
        task))))

(defn register-user-agent!
  "Register :user with a BufferedReader for tests and non-TTY callers."
  ([]
   (register-user-agent! (BufferedReader. (InputStreamReader. System/in))))
  ([^BufferedReader reader]
   (register-user-agent-core! #(start-stdin-reader! reader))))

(defn- open-terminal! []
  (-> (TerminalBuilder/builder) (.system true) (.build)))

(defn register-interactive-user-agent!
  "Register :user with JLine for CLI TTY input. Returns a Closeable session."
  []
  (if (runtime/handle? :user)
    (or (:closeable @interactive-session)
        (throw (ex-info "The :user agent is already registered without a JLine session"
                        {:type :user-agent-already-registered})))
    (let [^Terminal terminal (open-terminal!)
        saved-attributes (Attributes. (.getAttributes terminal))
        original-attributes (str saved-attributes)
        ^LineReader reader (-> (LineReaderBuilder/builder) (.terminal terminal) (.build))
        lock (Object.)
        session-id (Object.)
        reader-task (atom nil)
        stopping? (atom false)
        reader-state (atom :pending)
        reader-finished (promise)
        session (reify Closeable
                  (close [_]
                    (when (compare-and-set! stopping? false true)
                      (when (identical? session-id (:id @interactive-session))
                        (reset! interactive-session nil))
                      ;; Prevent a queued worker from starting after terminal close.
                      (when (compare-and-set! reader-state :pending :stopped)
                        (deliver reader-finished true))
                      ;; Interrupt while raw mode still provides timed reads. Closing
                      ;; first restores canonical mode and can strand native stdin reads.
                      (try
                        (when-let [task @reader-task] (future-cancel task))
                        (when (= ::timeout (deref reader-finished 2000 ::timeout))
                          (throw (ex-info "Interactive reader did not stop"
                                          {:type :user-reader-stop-timeout})))
                        (finally
                          (try (.close terminal)
                               (finally
                                 (.setAttributes terminal saved-attributes)
                                 (when-let [task @reader-task]
                                   (swap! reader-tasks disj task)))))))))]
    (try
      (install-newline-bindings! reader)
      (reset! interactive-session {:id session-id
                                   :reader reader
                                   :terminal terminal
                                   :original-attributes original-attributes
                                   :reader-finished reader-finished
                                   :lock lock
                                   :closeable session})
      (register-user-agent-core!
        #(let [task (start-jline-reader! reader stopping?
                                             {:state reader-state
                                              :finished reader-finished
                                              :on-stop (fn []
                                                         (let [interrupted? (Thread/interrupted)]
                                                           (try (.setAttributes terminal saved-attributes)
                                                                (finally
                                                                  (when interrupted?
                                                                    (.interrupt (Thread/currentThread)))))))})]
           (reset! reader-task task)
           task))
      session
      (catch Throwable e
        (reset! interactive-session nil)
        (.close terminal)
        (throw e))))))
