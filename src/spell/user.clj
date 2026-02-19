(ns spell.user
  "User-as-agent: treat the human as an agent with handle :user.
   Supports both agent-initiated communication (agents/ask :user msg)
   and user-initiated messaging (press Enter to signal readiness).
   Uses a LinkedBlockingQueue to decouple stdin reading from message
   processing, avoiding contention between the reader thread and
   user-call-fn."
  (:require [clojure.string :as str]
            [spell.comm :as comm]
            [spell.eval :as eval]
            [spell.globals :as globals]
            [spell.llm :as llm]
            [spell.parse :as parse])
  (:import [java.io BufferedReader InputStreamReader]
           [java.util.concurrent LinkedBlockingQueue]))

;; =============================================================================
;; State
;; =============================================================================

(def ^:private last-sender
  "Last agent that sent a message to :user. Used as default recipient."
  (atom :root))

(def ^:private stdin-queue
  "Queue decoupling stdin reading from message processing.
   The reader thread puts lines; user-call-fn takes them."
  (LinkedBlockingQueue.))

(def ^:private signal-pending
  "Whether a stdin-signal is pending. Prevents duplicate signals from
   rapid Enter presses — only one signal is sent until processed."
  (atom false))

;; =============================================================================
;; Stdin reader thread
;; =============================================================================

(defn- start-stdin-reader!
  "Start a persistent thread that reads lines from reader into stdin-queue.
   On empty lines (the signal), also wakes :user via comm/send.
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
                (binding [comm/*current-handle* :stdin-watch]
                  (comm/send :stdin-signal :user))))
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

(defn- take-nonempty-line!
  "Block until a non-empty line is available. Skips blank lines."
  []
  (loop []
    (let [line (take-line!)]
      (if (= "" (str/trim line))
        (recur)
        line))))

;; =============================================================================
;; Pure functions
;; =============================================================================

(defn parse-user-input
  "Parse user input into [recipient message].
   \":root hello\" → [:root \"hello\"]
   \"hello\"       → [nil \"hello\"]"
  [input]
  (if-let [[_ handle-str msg] (re-matches #":(\S+)\s+(.*)" input)]
    [(keyword handle-str) msg]
    [nil input]))

(defn resolve-recipient
  "Resolve the actual recipient. Uses explicit if provided,
   otherwise falls back to last-sender-val, then :root."
  [explicit last-sender-val]
  (or explicit last-sender-val :root))

(defn- lookup-recipients
  "Look up the current roles map from globals."
  []
  (or (globals/get-val :roles) {}))

;; =============================================================================
;; Message extraction
;; =============================================================================

(defn- extract-messages
  "Extract ALL messages from a raw completion string.
   Parses the quine/eval/do structure and collects all
   (def msg-N {:from h :value v}) and (def waiting-for h) forms.
   Returns a vector of {:from h :value v} or {:from h} maps."
  [raw]
  (try
    (let [forms (parse/read-all (parse/balance-parens raw))]
      (when (seq forms)
        (let [form (first forms)]
          ;; Unwrap (quine completion (eval (do ...)))
          (when (and (seq? form) (= 'quine (first form)))
            (let [eval-form (nth form 2 nil)]
              (when (and (seq? eval-form) (= 'eval (first eval-form)))
                (let [do-form (second eval-form)]
                  (when (and (seq? do-form) (= 'do (first do-form)))
                    (let [body (rest do-form)]
                      (vec
                        (keep (fn [f]
                                (when (and (seq? f)
                                           (= 'def (first f))
                                           (>= (count f) 3))
                                  (let [sym (second f)
                                        val (nth f 2)]
                                    (cond
                                      ;; Message: (def msg-N {:from h :value v})
                                      (and (map? val) (contains? val :from) (contains? val :value))
                                      val
                                      ;; Poke: (def waiting-for handle)
                                      (= sym 'waiting-for)
                                      {:from val}
                                      :else nil))))
                              body)))))))))))
    (catch Exception _e nil)))

;; =============================================================================
;; Send code builder
;; =============================================================================

(defn- build-send-code
  "Build the code string for sending a message to a target.
   Uses eval/serialize-for-continuation for safe value embedding."
  [value target]
  (str "(eval '(agents/send " (eval/serialize-for-continuation value) " " target ")) "))

;; =============================================================================
;; IO helpers
;; =============================================================================

(defn- prompt-and-read
  "Print recipients + prompt, read a non-empty line from the queue."
  []
  (let [recipients (lookup-recipients)]
    (binding [*out* *err*]
      (when (seq recipients)
        (doseq [[handle desc] recipients]
          (println (str "  " handle (when desc (str " — " desc))))))
      (print "> ")
      (flush))
    (take-nonempty-line!)))

;; =============================================================================
;; User call function (the "API call" equivalent)
;; =============================================================================

(defn- user-call-fn
  "The 'API call' for the user agent.
   Takes a prompt string (the reopened completion) and returns a response string
   (code to append). Analogous to call-fn in make-llm.

   Three cases, checked in order:
   1. stdin-signal present: user pressed Enter → prompt for message, parse recipient, send
   2. expects-reply? (agent asked): print messages, read reply, send to waiters
   3. fire-and-forget: print messages, quine-restart (no stdin read)"
  [prompt-str]
  (let [balanced (parse/balance-parens prompt-str)
        messages (extract-messages balanced)
        from-handles (vec (distinct (keep :from messages)))
        expects-reply? (seq @(:waiters (get @comm/registry :user)))
        ;; Check if this wake was from stdin-signal
        stdin-signal? (some #(= :stdin-watch (:from %)) messages)]

    (cond
      ;; Case 1: User-initiated (stdin signal)
      stdin-signal?
      (do
        (reset! signal-pending false)
        (let [input (prompt-and-read)
              [explicit-recipient msg] (parse-user-input input)
              target (resolve-recipient explicit-recipient @last-sender)]
          (reset! last-sender target)
          (str (build-send-code msg target)
               "nil))) (quine completion (eval (do ")))

      ;; Case 2: Agent asked — expects reply
      expects-reply?
      (do
        ;; Print messages to stderr
        (binding [*out* *err*]
          (doseq [msg messages]
            (let [{:keys [from value]} msg]
              (when from
                (reset! last-sender from))
              (if value
                (println (str "[agent " from "] " value))
                (println (str "[agent " from " is waiting for input]")))))
          (print "> ")
          (flush))
        (let [input (take-nonempty-line!)
              send-code (apply str (map #(build-send-code input %) from-handles))]
          (str send-code "nil))) (quine completion (eval (do ")))

      ;; Case 3: Fire-and-forget — no stdin read needed
      :else
      (do
        (binding [*out* *err*]
          (doseq [msg messages]
            (let [{:keys [from value]} msg]
              (when from
                (reset! last-sender from))
              (if value
                (println (str "[agent " from "] " value))
                (println (str "[agent " from " is waiting for input]"))))))
        "nil))) (quine completion (eval (do "))))

;; =============================================================================
;; User-self (box-based, analogous to -llm)
;; =============================================================================

(defn- user-self
  "Box-based execution for the user agent.
   Structurally similar to -llm but simpler (no trace, no retry, no verbose).
   Seeds inbox before calling box (like the-llm does before -llm).
   The parent-handle comes from *current-handle* at call time, making this
   box non-root. The root box is the orphan box."
  [inbox-fn handle parent-handle prompt-str]
  (let [completion (promise)]
    (future
      (try
        (let [response (user-call-fn prompt-str)]
          (deliver completion (str prompt-str response)))
        (catch Exception e
          (deliver completion e))))
    ;; Seed inbox before entering box (like the-llm seeds before -llm)
    (compare-and-set! (:inbox (get @comm/registry handle)) nil inbox-fn)
    (comm/box handle parent-handle completion)))

;; =============================================================================
;; Registration
;; =============================================================================

(defn- make-user-inbox-fn*
  "Build the inbox function for :user using the standard eval pipeline."
  []
  (let [variant-builtins (merge eval/core-builtins
                                {'describe-fn llm/describe}
                                llm/core-namespaces)
        ;; inbox-fn needs to be available for user-self to seed, so we use a promise
        inbox-fn-ref (promise)
        ;; user-self-fn uses *current-handle* as parent
        user-self-fn (fn [prompt-str]
                       (user-self @inbox-fn-ref
                                  comm/*current-handle* comm/*current-handle* prompt-str))
        ;; Effect builtins: llm-self (user-self) + agents namespace
        effect-builtins {'llm-self user-self-fn
                         'agents comm/agents-namespace}
        eval-builtin (llm/make-eval variant-builtins effect-builtins)
        config {:variant-builtins variant-builtins
                :eval-builtin eval-builtin
                :recover-fns nil
                :recovery-call-fn nil}
        inbox-fn (llm/make-inbox-fn config (atom nil))]
    (deliver inbox-fn-ref inbox-fn)
    inbox-fn))

(defn register-user-agent!
  "Register :user as an agent in the comm system.
   0-arity: uses System/in as reader. 1-arity: accepts a BufferedReader.
   Starts a persistent stdin reader thread that feeds the queue.
   Idempotent: no-op if :user is already registered.
   Parent-handle is :root (the default root agent)."
  ([]
   (register-user-agent! (BufferedReader. (InputStreamReader. System/in))))
  ([^BufferedReader reader]
   (when-not (comm/handle? :user)
     (let [inbox-fn (make-user-inbox-fn*)
           initial "(quine completion (eval (do )))"]
       (comm/register! :user inbox-fn :root)
       (comm/orphan-box! :user initial)
       (start-stdin-reader! reader)))))
