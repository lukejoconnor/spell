# Module ownership: complete editable affected-text inventory

Base: `0078a4c746269a215770cb8b6c821fd97ecaf249`. Scope: ownership/dogfood changes in the current shared worktree, not a paid-live receipt. Generated deterministically by `.spell/module-ownership-prep/inventory.py`; rerun after any listed input changes. This file is editable review material, not executable module policy.

Coverage rule: every changed Clojure string literal in the seven affected implementation files; complete enclosing dynamic error forms; complete new notice constructors and journal failure construction; complete changed Markdown blocks (including code fences/tables); all changed literal strings in the explicit acceptance program, including all three complete audit instructions; and byte-for-byte preservation evidence for all three base prompts. Internal docstrings/punctuation are included conservatively rather than silently omitted. Unchanged strings, unrelated runtime code and tests are not dumped. The source literal fences retain exact escaping, including newline escapes and quotation marks. Line ranges refer to each labeled snapshot. An empty side means no predecessor/current text, not an elision.

## src/spell/api.clj — all changed exact string literals

### Literal group 1

**Before:** `src/spell/api.clj:150-158`

```clojure
"Run a Spell agent with the public API.

   Required:
     :model-profile — model profile path, inline profile map, or provider instance
     :agent-profile      — path to .agent.edn
     exactly one of :prompt or :init

   Returns {:result value :usage-tracker atom} or
   {:error message :error-data data :usage-tracker atom}."
```

**After:** `src/spell/api.clj:154-163`

```clojure
"Run a Spell agent with the public API. :dogfood true enables feedback and
   automatic exact module-edit journals at the feedback destination for this run.

   Required:
     :model-profile — model profile path, inline profile map, or provider instance
     :agent-profile      — path to .agent.edn
     exactly one of :prompt or :init

   Returns {:result value :usage-tracker atom} or
   {:error message :error-data data :usage-tracker atom}."
```

## src/spell/cli.clj — all changed exact string literals

### Literal group 1

**Before:** `src/spell/cli.clj:136-136`

```clojure
"Enable Spell developer dogfooding feedback for this run"
```

**After:** `src/spell/cli.clj:136-136`

```clojure
"Enable feedback and automatic module-edit journals for this run"
```

## src/spell/feedback.clj — all changed exact string literals

### Literal group 1

**Before:** absent.

**After:** `src/spell/feedback.clj:24-24`

```clojure
"Create run identity and remember the destination, without touching disk."
```

### Literal group 2

**Before:** `src/spell/feedback.clj:49-51`

```clojure
"Append one structured feedback entry and return it.

   Category must be one of :bug, :friction, :idea, or :docs. Metadata is optional."
```

**After:** `src/spell/feedback.clj:56-56`

```clojure
"Append one already serialized record using the shared feedback destination lock."
```

### Literal group 3

**Before:** absent.

**After:** `src/spell/feedback.clj:75-77`

```clojure
"Append one structured feedback entry and return it.

   Category must be one of :bug, :friction, :idea, or :docs. Metadata is optional."
```

### Literal group 4

**Before:** `src/spell/feedback.clj:85-85`

```clojure
"FEEDBACK — Structured developer dogfooding feedback for Spell.\n\n  (feedback/log category message)\n  (feedback/log category message metadata)\n\nThis namespace is gated. If it is available, the current run is dogfooding Spell\nand you should record concrete problems with Spell itself that arise while doing\nthe user's task. Do not search for issues, and do not report problems in the\nuser's own domain as Spell feedback.\n\nCategories: :bug, :friction, :idea, :docs.\nEntries are appended as one EDN map per line to .spell/feedback.edn.\nSet SPELL_FEEDBACK_PATH to override the destination. Each entry automatically\nincludes an ISO-8601 :timestamp, the current :agent-handle, and, when tracing is\nenabled, :trace-node-id. Include enough context to make the observation actionable,\nsuch as what you expected, what happened, and whether an ergonomic change or\nclearer documentation or prompting might help.\n\nAll feedback/ calls are effect functions — quote them in the trailing expression.\n\nExample:\n  '(feedback/log :friction\n     \"The function behaved differently from its description\"\n     {:task \"configure agent\"\n      :expected \"the configured namespace to be available\"\n      :observed \"the function returned an unknown-namespace error\"\n      :suggestion \"clarify the prompt or make namespace loading more ergonomic\"})"
```

**After:** `src/spell/feedback.clj:98-98`

```clojure
"FEEDBACK — Structured developer dogfooding feedback for Spell.\n\n  (feedback/log category message)\n  (feedback/log category message metadata)\n\nThis namespace is gated. If it is available, the current run is dogfooding Spell\nand you should record concrete problems with Spell itself that arise while doing\nthe user's task. Do not search for issues, and do not report problems in the\nuser's own domain as Spell feedback.\n\nCategories: :bug, :friction, :idea, :docs.\nEntries are appended as one EDN map per line to .spell/feedback.edn.\nSet SPELL_FEEDBACK_PATH to override the destination. Each entry automatically\nincludes an ISO-8601 :timestamp, the current :agent-handle, and, when tracing is\nenabled, :trace-node-id. Include enough context to make the observation actionable,\nsuch as what you expected, what happened, and whether an ergonomic change or\nclearer documentation or prompting might help.\n\nAutomatic module-edit journals share this destination only when CLI --dogfood or API :dogfood true is enabled. They are distinct :kind :module-edit records, not feedback/log entries: each has a unique per-run :run-id (inherited by children), :operation, immutable :owner, actual :editor, :explicit-owner?, :revision, global :sequence, function changes, and exact executable :before/:after source strings. Install baselines and unequal successful updates are recorded after commit; repeat installs, noops, invalid edits, ordinary calls and direct globals writes are not. Sequence reconstructs concurrent commit order; append order may differ. Recording is not crash-atomic. If recording fails, the normal committed receipt includes :journal {:status :failed :message \"EDIT COMMITTED / RECORDING FAILED\" ...} and the editor receives a distinct bounded warning; the edit is live, the journal has a gap, and you must not replay it.\n\nAll feedback/ calls are effect functions — quote them in the trailing expression.\n\nExample:\n  '(feedback/log :friction\n     \"The function behaved differently from its description\"\n     {:task \"configure agent\"\n      :expected \"the configured namespace to be available\"\n      :observed \"the function returned an unknown-namespace error\"\n      :suggestion \"clarify the prompt or make namespace loading more ergonomic\"})"
```

## src/spell/llm.clj — all changed exact string literals

No string-literal changes. Runtime wiring changes do not alter existing prompt/help literal text in this file.

## src/spell/patterns.clj — all changed exact string literals

### Literal group 1

**Before:** `src/spell/patterns.clj:62-62`

```clojure
"Install a bundle or custom definition only if absent; never initialize state."
```

**After:** `src/spell/patterns.clj:69-69`

```clojure
"Module "
```

**After:** `src/spell/patterns.clj:69-69`

```clojure
" requires a registered current agent; caller "
```

**After:** `src/spell/patterns.clj:89-89`

```clojure
"Install if absent. The actual registered winning installer is the immutable owner."
```

### Literal group 2

**Before:** `src/spell/patterns.clj:123-123`

```clojure
"Apply a pure, retryable transform to current-or-nil and commit its next definition."
```

**After:** `src/spell/patterns.clj:160-160`

```clojure
"patterns/update options must be exactly {:owner keyword}, followed by an evaluated transform"
```

**After:** `src/spell/patterns.clj:166-166`

```clojure
"Owner-checked whole-definition transform; install first. Transforms may retry."
```

**After:** `src/spell/patterns.clj:177-177`

```clojure
"Module "
```

**After:** `src/spell/patterns.clj:177-177`

```clojure
" is not installed; call patterns/install first (update cannot create modules)"
```

**After:** `src/spell/patterns.clj:183-183`

```clojure
"Module "
```

**After:** `src/spell/patterns.clj:183-183`

```clojure
" owner "
```

**After:** `src/spell/patterns.clj:183-183`

```clojure
", caller "
```

**After:** `src/spell/patterns.clj:184-184`

```clojure
": owner mismatch. Message the owner to request the edit, or deliberately name the recorded owner with "
```

**After:** `src/spell/patterns.clj:185-185`

```clojure
"(patterns/update "
```

**After:** `src/spell/patterns.clj:185-185`

```clojure
" {:owner "
```

**After:** `src/spell/patterns.clj:185-185`

```clojure
"} transform & args); naming the owner is acknowledgment, not approval."
```

**After:** `src/spell/patterns.clj:188-188`

```clojure
"patterns/update requires an already evaluated transform function"
```

### Literal group 3

**Before:** `src/spell/patterns.clj:159-187`

```clojure
"PATTERNS — Installable modules (effect namespace).

  (patterns/install module)             — install bundled source only if absent
  (patterns/install module definition)  — install custom source only if absent
  (patterns/catalog)                    — compact bundled/custom discovery
  (patterns/catalog module)             — compact metadata or nil
  (patterns/source module)              — complete installed definition or nil
  (patterns/source module function)     — complete installed entry or nil
  (patterns/update module transform & args) — pure transform -> next definition
  (patterns/call module function & args) — execute the selected installed source

Definition: {:doc string :functions {:key {:doc string :requires [namespace-symbol ...]
                                         :source (fn [params] body...)}}}.
Pass definitions as quoted data; each :source is a single-arity fn form.
Registry: globals :modules, separate from module state. Install never resets edits or initializes state.
Pass update an already evaluated fn or builtin value, not quoted fn source.
Transforms may retry; purity is the programmer's contract, not an enforced effect barrier.
Requirements precheck namespace availability, not function completeness, and never grant capabilities.
Calls retain caller dynamic scope and normal arity/recur.
Nested calls see latest definitions; an in-flight call keeps its selected body.

Bundles: :check-result, :ralph, :team, :fix-loop, :relay export :run.
:mailing-list exports :init, :call, :change, :digest, :deliver.
Install :mailing-list, then explicitly call :init once; installation alone does not create a board.
Context discipline: avoid pruning evidence and then rediscovering it. Before prune/!peek removes results,
retain exact needed source snippets, actual effect receipts, stored IDs/offsets, and a literal checkpoint.
A plan or sent flag is not execution evidence. Retrieve retained/stored output rather than repeating effects.
Carry this evidence through compaction; report unavailable evidence instead of claiming inspection.
No former pattern wrappers or clean-prompt remain. Quote effect calls in the trailing expression."
```

**Before:** `src/spell/patterns.clj:188-188`

```clojure
"(patterns/install module) or (patterns/install module definition). Atomic if-absent insertion. Returns {:module k :installed? boolean :fns sorted-vector}; true only for the winning insertion. Leaves all state and existing edits untouched."
```

**Before:** `src/spell/patterns.clj:189-189`

```clojure
"(patterns/catalog) or (patterns/catalog module). Summaries contain :module, :installed?, :doc, and :functions entries with :doc/:params/:requires only. Discovers uninstalled bundles and installed custom modules without source bodies. Unknown module returns nil."
```

**After:** `src/spell/patterns.clj:225-259`

```clojure
"PATTERNS — Installable modules (effect namespace).

  (patterns/install module)             — install bundled source only if absent
  (patterns/install module definition)  — install custom source only if absent
  (patterns/catalog)                    — compact bundled/custom discovery
  (patterns/catalog module)             — compact metadata or nil
  (patterns/source module)              — complete installed definition or nil
  (patterns/source module function)     — complete installed entry or nil
  (patterns/update module transform & args) — pure transform -> next definition
  (patterns/call module function & args) — execute the selected installed source

Definition: {:doc string :functions {:key {:doc string :requires [namespace-symbol ...]
                                         :source (fn [params] body...)}}}.
Pass definitions as quoted data; each :source is a single-arity fn form.
Registry: globals :modules, separate from module state. Install never resets edits or initializes state.
Owner/revision metadata is separate from definitions. Actual registered installer owns the module immutably.
Update requires install; default expected owner is the caller. A strict leading {:owner keyword} deliberately
acknowledges the recorded owner, not approval; message the owner to coordinate. Missing identity is rejected.
Pass update an already evaluated fn or builtin value, not quoted fn source.
Winning installers receive bounded next-generation guidance without mailbox receipt or extra model calls.
Dogfood automatically appends exact module changes at the feedback destination; ordinary calls and direct globals writes are unlogged.
Post-commit recording failure returns EDIT COMMITTED / RECORDING FAILED in :journal: the edit is live; do not replay.
Transforms may retry; purity is the programmer's contract, not an enforced effect barrier.
Requirements precheck namespace availability, not function completeness, and never grant capabilities.
Calls retain caller dynamic scope and normal arity/recur.
Nested calls see latest definitions; an in-flight call keeps its selected body.

Bundles: :check-result, :ralph, :team, :fix-loop, :relay export :run.
:mailing-list exports :init, :call, :change, :digest, :deliver.
Install :mailing-list, then explicitly call :init once; installation alone does not create a board.
Context discipline: avoid pruning evidence and then rediscovering it. Before prune/!peek removes results,
retain exact needed source snippets, actual effect receipts, stored IDs/offsets, and a literal checkpoint.
A plan or sent flag is not execution evidence. Retrieve retained/stored output rather than repeating effects.
Carry this evidence through compaction; report unavailable evidence instead of claiming inspection.
No former pattern wrappers or clean-prompt remain. Quote effect calls in the trailing expression."
```

**After:** `src/spell/patterns.clj:260-260`

```clojure
"(patterns/install module) or (patterns/install module definition). Atomic if-absent insertion by a registered agent; the winning actual installer is the immutable owner. Returns {:module :installed? :fns :owner :editor :explicit-owner? :revision :sequence}; repeat/concurrent install preserves owner, edits and state. Winning installers get bounded guidance in their next model generation, even if the return is discarded. Delegate installation to choose another owner; no owner option or transfer."
```

**After:** `src/spell/patterns.clj:261-261`

```clojure
"(patterns/catalog) or (patterns/catalog module). Summaries contain :module, :installed?, :owner (nil for uninstalled bundles), :doc, and :functions entries with :doc/:params/:requires only. Discovers uninstalled bundles and installed custom modules without source bodies. Unknown module returns nil."
```

### Literal group 4

**Before:** `src/spell/patterns.clj:191-191`

```clojure
"(patterns/update module transform & args). Pass an already evaluated fn or builtin value, not quoted fn source. The conventional transform of current-or-nil and args returns the whole next definition. Validation precedes atomic commit; transforms may retry. Purity is the programmer's contract; effects are not blocked inside transforms. Returns {:module k :fns sorted-vector} from that exact commit, never a later registry read."
```

**After:** `src/spell/patterns.clj:263-263`

```clojure
"(patterns/update module transform & args) or (patterns/update module {:owner recorded-owner} transform & args). Install first. Default expected owner is the registered actual caller; mismatch rejects before transform. The optional second argument must be exactly {:owner keyword}; explicitly naming the recorded owner acknowledges a deliberate edit, not approval. Message the owner to coordinate. Pass an already evaluated fn or builtin, not quoted source. The pure retryable transform receives the current whole definition and args; validation precedes atomic commit. Returns {:module :fns :owner :editor :explicit-owner? :revision :sequence} from that exact commit; unchanged definitions keep revision/sequence. Dogfood records successful changes automatically; :journal is {:status :ok :sequence s} or {:status :failed :message \"EDIT COMMITTED / RECORDING FAILED\" ...}. A recording failure leaves the edit live: do not replay. Off mode omits :journal."
```

### Complete dynamic error constructors

**Before:** absent.

**After:** `src/spell/patterns.clj:69-70`

```clojure
(ex-info (str "Module " module-key " requires a registered current agent; caller " (pr-str caller))
                      {:module module-key :caller caller :type :missing-module-editor})
```

**After:** `src/spell/patterns.clj:160-161`

```clojure
(ex-info "patterns/update options must be exactly {:owner keyword}, followed by an evaluated transform"
                        {:module module-key :options transform-or-options})
```

**After:** `src/spell/patterns.clj:177-178`

```clojure
(ex-info (str "Module " module-key " is not installed; call patterns/install first (update cannot create modules)")
                              {:module module-key :caller caller :owner nil})
```

**After:** `src/spell/patterns.clj:182-186`

```clojure
(ex-info
                         (str "Module " module-key " owner " (pr-str owner) ", caller " (pr-str caller)
                              ": owner mismatch. Message the owner to request the edit, or deliberately name the recorded owner with "
                              "(patterns/update " module-key " {:owner " (pr-str owner) "} transform & args); naming the owner is acknowledgment, not approval.")
                         {:module module-key :owner owner :caller caller :expected-owner expected-owner})
```

**After:** `src/spell/patterns.clj:188-189`

```clojure
(ex-info "patterns/update requires an already evaluated transform function"
                                {:module module-key})
```

## src/spell/module_journal.clj — all changed exact string literals

### Literal group 1

**Before:** absent.

**After:** `src/spell/module_journal.clj:2-2`

```clojure
"Post-commit recording of patterns API changes only; never a globals watch."
```

**After:** `src/spell/module_journal.clj:16-16`

```clojure
"Module source must roundtrip as one form"
```

**After:** `src/spell/module_journal.clj:28-28`

```clojure
"Augment a successful receipt; recording failure never replays a committed edit."
```

**After:** `src/spell/module_journal.clj:45-45`

```clojure
"EDIT COMMITTED / RECORDING FAILED"
```

**After:** `src/spell/module_journal.clj:46-46`

```clojure
": "
```

### Complete dynamic error constructors

**Before:** absent.

**After:** `src/spell/module_journal.clj:16-16`

```clojure
(ex-info "Module source must roundtrip as one form" {})
```

### Complete constructor: `record-change`

**Before:** absent.

**After:** `src/spell/module_journal.clj:27-49`

```clojure
(defn record-change
  "Augment a successful receipt; recording failure never replays a committed edit."
  [receipt operation before after]
  (if-not feedback/*dogfood*
    receipt
    (try
      (let [{:keys [path run-id]} feedback/*dogfood*
            entry (cond-> (merge (select-keys receipt [:module :owner :editor :explicit-owner? :revision :sequence])
                                {:kind :module-edit :operation operation :run-id run-id
                                 :timestamp (str (java.time.Instant/now))
                                 :functions (function-changes before after)
                                 :before (source-text before) :after (source-text after)})
                    (some? trace/*trace-node-id*) (assoc :trace-node-id trace/*trace-node-id*))
            serialized (binding [*print-length* nil *print-level* nil] (pr-str entry))]
        (feedback/append-entry! path serialized)
        (assoc receipt :journal {:status :ok :sequence (:sequence receipt)}))
      (catch Throwable cause
        (let [failure (merge (select-keys receipt [:module :revision :sequence])
                             {:status :failed :message "EDIT COMMITTED / RECORDING FAILED"
                              :error (str (.getName (class cause)) ": " (.getMessage cause))})]
          (swap! globals/*store* notices/enqueue (:editor receipt)
                 (assoc (select-keys receipt [:module :revision :sequence]) :kind :recording-failed))
          (assoc receipt :journal failure))))))
```

## src/spell/module_notices.clj — all changed exact string literals

### Literal group 1

**Before:** absent.

**After:** `src/spell/module_notices.clj:2-2`

```clojure
"Bounded run-local module guidance; separate from agent mailboxes."
```

**After:** `src/spell/module_notices.clj:10-10`

```clojure
"Pure transition. Deduplicate pending entries by kind/module/revision."
```

**After:** `src/spell/module_notices.clj:22-22`

```clojure
" revision "
```

**After:** `src/spell/module_notices.clj:22-22`

```clojure
" sequence "
```

**After:** `src/spell/module_notices.clj:31-31`

```clojure
"You own module(s) "
```

**After:** `src/spell/module_notices.clj:31-31`

```clojure
"[identifier exceeds notice limit]"
```

**After:** `src/spell/module_notices.clj:31-31`

```clojure
", "
```

**After:** `src/spell/module_notices.clj:32-32`

```clojure
" (first installer). Expect edit requests and coordinate edits. Use (patterns/update :module transform & args); others must deliberately name the recorded owner with (patterns/update :module {:owner :recorded-owner} transform & args), replacing :recorded-owner with your handle from patterns/catalog. "
```

**After:** `src/spell/module_notices.clj:34-34`

```clojure
"EDIT COMMITTED / RECORDING FAILED: "
```

**After:** `src/spell/module_notices.clj:34-34`

```clojure
"[identifier exceeds notice limit]"
```

**After:** `src/spell/module_notices.clj:34-34`

```clojure
", "
```

**After:** `src/spell/module_notices.clj:35-35`

```clojure
". The edit is live; do not replay it. The journal has a gap. "
```

**After:** `src/spell/module_notices.clj:36-36`

```clojure
"Inspect (patterns/catalog) and retained edit receipts for the full identifier. "
```

**After:** `src/spell/module_notices.clj:37-37`

```clojure
"and "
```

**After:** `src/spell/module_notices.clj:37-37`

```clojure
" more queued module notices."
```

**After:** `src/spell/module_notices.clj:38-38`

```clojure
"\n; MODULE NOTICE: "
```

**After:** `src/spell/module_notices.clj:38-38`

```clojure
"\r"
```

**After:** `src/spell/module_notices.clj:38-38`

```clojure
"\\r"
```

**After:** `src/spell/module_notices.clj:38-38`

```clojure
"\n"
```

**After:** `src/spell/module_notices.clj:38-38`

```clojure
"\\n"
```

**After:** `src/spell/module_notices.clj:38-38`

```clojure
"\n"
```

**After:** `src/spell/module_notices.clj:50-52`

```clojure
"Prepend a complete comment, preserving the original prefix byte-for-byte.
   Atomically dequeue one bounded page for this handle, without receiving mail.
   Best effort: a provider failure after dequeue does not requeue the page."
```

### Complete constructor: `label`

**Before:** absent.

**After:** `src/spell/module_notices.clj:19-22`

```clojure
(defn- label [entry]
  (str (pr-str (:module entry))
       (when (= :recording-failed (:kind entry))
         (str " revision " (:revision entry) " sequence " (:sequence entry)))))
```

### Complete constructor: `block`

**Before:** absent.

**After:** `src/spell/module_notices.clj:24-38`

```clojure
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
```

## AGENTS.md — complete affected prose/code blocks

### Block group 1

**Before:** `AGENTS.md:178-179`

```markdown
Use `-T` to record an execution trace under the temporary Spell trace directory, or `--trace-dir DIR` to write it to an explicit durable location. Use `--dogfood` to expose the feedback namespace to the main agent and its workers. Use `--agents-md` to prepend the current working directory's `AGENTS.md`, capped at 32 KiB, to a natural-language task. The trace tool can inspect a trace directory directly, for example:

```

**After:** `AGENTS.md:178-179`

```markdown
Use `-T` to record an execution trace under the temporary Spell trace directory, or `--trace-dir DIR` to write it to an explicit durable location. Use `--dogfood` to expose the feedback namespace to the main agent and its workers and automatically journal exact successful PATTERNS API install baselines and changed definitions at the existing feedback destination. Public API equivalent: `:dogfood true`. See `docs/installable-modules.md` for immutable module ownership, deliberate-owner update acknowledgment and `EDIT COMMITTED / RECORDING FAILED` receipts; never replay an already committed edit to repair recording. Use `--agents-md` to prepend the current working directory's `AGENTS.md`, capped at 32 KiB, to a natural-language task. The trace tool can inspect a trace directory directly, for example:

```

## docs/api.md — complete affected prose/code blocks

### Block group 1

**Before:** `docs/api.md:35-47`

```markdown
| Option | Default | Description |
|---|---:|---|
| `:model` | model profile `:default-model` | Model choice, overriding the model profile, for this run. |
| `:reasoning-effort` | model profile `:default-reasoning-effort` | Reasoning-effort override for this run. |
| `:budget` | agent profile `:default-budget` or runtime default | Maximum spend in dollars for the run. `nil` means the configured default. `0` means unlimited. |
| `:depth` | unlimited | Maximum recursive LLM depth for this run. |
| `:coordinator` | `{:max-edges 10000}` | Per-run coordination capacity. `:max-edges` must be a positive integer and counts pending hyperedges, regardless of target count. Admission rejects atomically before sending requests or launching children. |
| `:context-max-chars` | 10000 | Maximum characters inserted by one tool-result or message contribution, including binding syntax. Integer of at least 128; `nil` uses the default. |
| `:trace-dir` | none | When non-nil, record a Spell execution trace in this directory. |
| `:usage-tracker` | fresh atom | Existing usage atom to accumulate token and cost accounting into. |
| `:user-reader` | none | When non-nil, register the interactive `:user` handle and read from this reader. The caller retains ownership of the reader. Spell requests cancellation of its reader task and clears input state when the run ends, so use a finite reader or one whose blocking read responds to thread interruption. An arbitrary reader that ignores interruption must be unblocked by its owner before reuse. |
| `:log-writer` | none | Writer for raw LLM debugging output. Pass `*out*` or another writer for logging. |

```

**After:** `docs/api.md:35-48`

```markdown
| Option | Default | Description |
|---|---:|---|
| `:model` | model profile `:default-model` | Model choice, overriding the model profile, for this run. |
| `:reasoning-effort` | model profile `:default-reasoning-effort` | Reasoning-effort override for this run. |
| `:budget` | agent profile `:default-budget` or runtime default | Maximum spend in dollars for the run. `nil` means the configured default. `0` means unlimited. |
| `:depth` | unlimited | Maximum recursive LLM depth for this run. |
| `:coordinator` | `{:max-edges 10000}` | Per-run coordination capacity. `:max-edges` must be a positive integer and counts pending hyperedges, regardless of target count. Admission rejects atomically before sending requests or launching children. |
| `:context-max-chars` | 10000 | Maximum characters inserted by one tool-result or message contribution, including binding syntax. Integer of at least 128; `nil` uses the default. |
| `:trace-dir` | none | When non-nil, record a Spell execution trace in this directory. |
| `:dogfood` | false | Enable human feedback and automatic exact PATTERNS API install/edit journals at `.spell/feedback.edn` (or `SPELL_FEEDBACK_PATH`). Children share this run's mode/destination/identity; independent API runs get distinct identities. See [module ownership and journals](./installable-modules.md#automatic-dogfood-module-edit-journal) for scope and committed-edit recording failures. |
| `:usage-tracker` | fresh atom | Existing usage atom to accumulate token and cost accounting into. |
| `:user-reader` | none | When non-nil, register the interactive `:user` handle and read from this reader. The caller retains ownership of the reader. Spell requests cancellation of its reader task and clears input state when the run ends, so use a finite reader or one whose blocking read responds to thread interruption. An arbitrary reader that ignores interruption must be unblocked by its owner before reuse. |
| `:log-writer` | none | Writer for raw LLM debugging output. Pass `*out*` or another writer for logging. |

```

## docs/installable-modules.md — complete affected prose/code blocks

### Block group 1

**Before:** `docs/installable-modules.md:32-41`

```markdown
| Operation | Contract |
| --- | --- |
| `(patterns/install module-key)` | Install bundled definition only if absent. |
| `(patterns/install module-key definition)` | Install a custom definition only if absent. |
| `(patterns/catalog)` / `(patterns/catalog module-key)` | Compact discovery; unknown module-specific lookup returns nil. |
| `(patterns/source module-key)` | Complete installed module definition, or nil. |
| `(patterns/source module-key function-key)` | Complete installed function entry (doc, requires, executable source), or nil. |
| `(patterns/update module-key pure-transform & args)` | Atomically validate/store the transform's next whole definition. A transform can create a valid definition from nil. |
| `(patterns/call module-key function-key & args)` | Execute the selected current source in the caller's dynamic scope and capabilities. |

```

**Before:** `docs/installable-modules.md:42-43`

```markdown
Install receipts are `{:module k :installed? boolean :fns sorted-vector}`. Repeated or concurrent install preserves the winning definition, subsequent edits, and separate state. Update receipts are `{:module k :fns sorted-vector}` from the successful commit; the transform returns the next definition, **not** `[definition result]`.

```

**After:** `docs/installable-modules.md:32-42`

```markdown
| Operation | Contract |
| --- | --- |
| `(patterns/install module-key)` | Install bundled definition only if absent. |
| `(patterns/install module-key definition)` | Install a custom definition only if absent. |
| `(patterns/catalog)` / `(patterns/catalog module-key)` | Compact discovery; unknown module-specific lookup returns nil. |
| `(patterns/source module-key)` | Complete installed module definition, or nil. |
| `(patterns/source module-key function-key)` | Complete installed function entry (doc, requires, executable source), or nil. |
| `(patterns/update module-key pure-transform & args)` | Require installation and caller ownership; atomically validate/store the next whole definition. |
| `(patterns/update module-key {:owner recorded-owner} pure-transform & args)` | Deliberately acknowledge the immutable recorded owner; actual editor remains the caller. |
| `(patterns/call module-key function-key & args)` | Execute the selected current source in the caller's dynamic scope and capabilities. |

```

**After:** `docs/installable-modules.md:43-44`

```markdown
Install receipts include `:module`, `:installed?`, `:owner` and sorted `:fns`; catalog exposes the installed module's actual `:owner` too. Repeated or concurrent install preserves the winning definition, subsequent edits, and separate state. Update receipts include `:module`, recorded `:owner`, actual `:editor`, revision/sequence and sorted `:fns` from the successful commit; the transform returns the next definition, **not** `[definition result]`. Dogfood journal status is additive; see below.

```

**After:** `docs/installable-modules.md:45-46`

```markdown
## Ownership and deliberate edits

```

**After:** `docs/installable-modules.md:47-48`

```markdown
Every installed module has one immutable owner: the real registered agent that wins its first successful installation, using `agents/current-handle` identity. There is no installer option and no transfer operation; delegate installation if another agent should own the module. Install without a registered current handle fails before touching the store. Repeat/concurrent installs expose the winner's owner and preserve its definition, edits, metadata and separate application state.

```

**After:** `docs/installable-modules.md:49-50`

```markdown
Ownership/revision metadata lives outside the transformable definition, beside it in run-local state. Adding an `:owner` field to source data cannot transfer ownership. The default `(patterns/update module transform & args)` expects the actual caller to be the recorded owner. For a deliberate nonowner edit, use `(patterns/update module {:owner recorded-owner} transform & args)`. This acknowledges the recorded owner; it **does not claim the owner approved**. The actual editor remains the caller. Message the owner to coordinate changes rather than guessing ownership; `patterns/catalog` exposes the recorded handle.

```

**After:** `docs/installable-modules.md:51-52`

```markdown
The options map is exactly `{:owner keyword}`. Nil, false, missing/extra keys and wrong owners do not bypass checks. Options belong before the transform, not at the end; ordinary transform arguments remain ordinary arguments. Update requires prior installation and a registered caller. On every atomic retry, the host checks caller identity, installation and owner expectation against that retry's snapshot **before invoking the transform**. A rejected update never invokes it. The transform receives the whole current definition and returns the next whole definition, which is validated before commit. It may run more than once, so remain pure. Receipt metadata comes from the exact successful commit, not a later registry read.

```

**After:** `docs/installable-modules.md:53-54`

```markdown
### First-installer guidance

```

**After:** `docs/installable-modules.md:55-56`

```markdown
The winning installer receives automatic bounded guidance in its **next model generation**, even if a helper discards the install receipt: it owns the module, should expect edit requests and coordinate changes, and can use `patterns/update`. This is inert prefix context, not an extra model call, mailbox message, receive operation or replacement of the current effect receipts. Repeat/losing installs do not onboard another agent. A compact page names exact module identifiers; overflow reports how many remain queued and leaves them for later generations. Pages are limited to eight items and 2048 characters. If even one identifier cannot fit, the notice consumes that item using `[identifier exceeds notice limit]` and directs the reader to `patterns/catalog` and retained edit receipts for its full identifier; the bounded notice is not an exact-name channel in this exceptional case. Notices are consumed atomically once at prefix construction, with revision/kind deduplication.

```

**After:** `docs/installable-modules.md:57-58`

```markdown
Guidance is best-effort per generation: an agent that returns without another generation never sees it. If the provider fails after dequeue, that page is lost; it is not requeued (avoiding duplicates). Module metadata and notice queues are run-local coordination, **not security**. Direct globals writes bypass these conventions and are not journaled.

```

**After:** `docs/installable-modules.md:59-60`

```markdown
## Automatic dogfood module-edit journal

```

**After:** `docs/installable-modules.md:61-62`

```markdown
Existing CLI `--dogfood` and public API `spell.api/run` option `:dogfood true` enable local structured append-only module-edit records as well as human feedback. They reuse `.spell/feedback.edn`, or the existing `SPELL_FEEDBACK_PATH` override. Automatic entries have `:kind :module-edit`, distinct from human feedback; `:before`/`:after` are lossless executable-definition strings (nil before an install), and `:functions` lists `:added`, `:removed` and `:modified` keys. Children share the run's mode, destination and unique identity; separate API runs get distinct identities even in one process and destination. Journal entries are distinct from human `feedback/log` entries. Dogfood off performs no journal disk setup/writes and does not change ordinary module calls.

```

**After:** `docs/installable-modules.md:63-64`

```markdown
The journal covers **successful PATTERNS API installation baselines and unequal-definition updates only**. A custom install includes its exact executable baseline. Reinstall, equal-definition update (no-op), invalid/owner-rejected updates, speculative retries and direct globals mutations produce no code-change record. Each successful change records exact lossless before/after definitions, module/function changes, recorded owner, actual editor, explicit-owner acknowledgment (not approval), run identity, timestamp/trace node when available, and revision/global sequence from the same successful commit. Executable source is not truncated or automatically inserted into model context. Ordinary calls neither scan the registry nor fetch source bodies.

```

**After:** `docs/installable-modules.md:65-66`

```markdown
Appending occurs outside retryable transforms using the exact committed snapshots. Concurrent physical append order need not be commit order: reconstruct by run identity and global sequence; timestamps are advisory. This is not a crash-atomic persistence guarantee or a general audit framework. There are no globals watches, unrelated-state inspections, remote/MCP logging, or replay promises.

```

**After:** `docs/installable-modules.md:67-68`

```markdown
Journal status is additive receipt metadata: dogfood off omits `:journal`; successful recording adds `{:status :ok :sequence s}`. **A failed append does not roll back the live edit and does not throw an ordinary edit failure.** The normal edit receipt instead includes `:journal {:status :failed :message "EDIT COMMITTED / RECORDING FAILED" :module k :revision n :sequence s :error error-details}` and a bounded next-generation warning is queued for the actual editor. Preserve this receipt; **do not retry the transform to repair recording**. A failed journal append means the edit is live but the audit trail has a gap identified by sequence; the receipt is the only record unless the warning is read. Failure warnings share the bounded, deduplicated, best-effort notice lifecycle above.

```

## docs/mailing-list.md — complete affected prose/code blocks

### Block group 1

**Before:** `docs/mailing-list.md:109-110`

```markdown
Spell functions use dynamic scope, not lexical closures. Invoke shared entries through `patterns/call`, not by refetching/evaluating source merely to use it. Retryable transforms must not send, spawn, perform I/O, or change globals. Dispatch/delivery perform effects after commit. Owner is a coordination convention, not an access restriction; coordinate incompatible source/schema changes.

```

**After:** `docs/mailing-list.md:109-110`

```markdown
Spell functions use dynamic scope, not lexical closures. Invoke shared entries through `patterns/call`, not by refetching/evaluating source merely to use it. Retryable transforms must not send, spawn, perform I/O, or change globals. Dispatch/delivery perform effects after commit. Coordinate incompatible source/schema changes.

```

**After:** `docs/mailing-list.md:111-112`

```markdown
The module owner is the registered agent that wins first install, not the board state's administrator/owner field. Repeat installs preserve that immutable owner; workers never reinitialize existing board state. Discover the module owner with `patterns/catalog` and send edit requests to that handle. Default `patterns/update` is owner-only; a deliberate nonowner edit must use `(patterns/update :mailing-list {:owner recorded-owner} transform & args)`. Naming the owner is acknowledgment, not evidence of approval; the journal separately records the actual editor. Install before updating, never use update to create from nil, and keep the options map before the transform. Ownership metadata is outside the editable definition. Direct globals remains trusted coordination, not a security boundary.

```

**After:** `docs/mailing-list.md:113-114`

```markdown
The first installer receives bounded guidance in its next model generation even when install's return is discarded. No extra model call or mailbox drainage occurs; overflow is retained for later generations. Provider failure after dequeue or returning without another generation can prevent guidance from being seen. Under `--dogfood`, install baselines and changed definitions are journaled exactly; board state mutations are not. An `EDIT COMMITTED / RECORDING FAILED` receipt means code is already live: preserve the sequence and do not replay the transform.

```

## resources/skills/mailing-list/SKILL.md — complete affected prose/code blocks

### Block group 1

**Before:** `resources/skills/mailing-list/SKILL.md:52-53`

```markdown
Spell functions have dynamic scope, not lexical closures. Selected calls retain their entry snapshot; nested `patterns/call` resolves latest source. The board's separate pure `:change` returns `[next-board result]` and must remain effect-free; sends/spawns happen outside retryable state transactions. All agents trust each other; owner is a coordination convention, not security. Coordinate incompatible schema changes. See `docs/installable-modules.md` and `docs/mailing-list.md` for complete contracts.

```

**After:** `resources/skills/mailing-list/SKILL.md:52-53`

```markdown
Spell functions have dynamic scope, not lexical closures. Selected calls retain their entry snapshot; nested `patterns/call` resolves latest source. The board's separate pure `:change` returns `[next-board result]` and must remain effect-free; sends/spawns happen outside retryable state transactions. Coordinate incompatible schema changes.

```

**After:** `resources/skills/mailing-list/SKILL.md:54-55`

```markdown
The module owner is the registered agent that wins first install, not the board state's administrator/owner field. Repeat installs preserve that immutable owner; workers never reinitialize existing board state. Discover the module owner with `patterns/catalog` and send edit requests to that handle. Default `patterns/update` is owner-only; a deliberate nonowner edit must use `(patterns/update :mailing-list {:owner recorded-owner} transform & args)`. Naming the owner is acknowledgment, not evidence of approval; the journal separately records the actual editor. Install before updating, never use update to create from nil, and keep the options map before the transform. Ownership metadata is outside the editable definition. Direct globals remains trusted coordination, not a security boundary.

```

**After:** `resources/skills/mailing-list/SKILL.md:56-57`

```markdown
The first installer receives bounded guidance in its next model generation even when install's return is discarded. No extra model call or mailbox drainage occurs; overflow is retained for later generations. Provider failure after dequeue or returning without another generation can prevent guidance from being seen. Under `--dogfood`, install baselines and changed definitions are journaled exactly; board state mutations are not. An `EDIT COMMITTED / RECORDING FAILED` receipt means code is already live: preserve the sequence and do not replay the transform. See `docs/installable-modules.md` and `docs/mailing-list.md` for complete contracts.

```

## MODULE_OWNERSHIP_CHANGELOG.md — complete affected prose/code blocks

### Block group 1

**Before:** absent.

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:1-2`

```markdown
# Module ownership and automatic dogfood journals

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:3-4`

```markdown
Scope: run `2026-09-07-module-ownership-002`, predecessor base `0078a4c746269a215770cb8b6c821fd97ecaf249`. This records intentional behavior changes; it is not a paid live-acceptance receipt.

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:5-6`

```markdown
## Immutable installation ownership

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:7-10`

```markdown
- **Previous behavior:** Modules had no recorded installer ownership; install receipts/catalog were ownerless and any agent could update by default.
- **Why it was a problem:** Agents sharing editable orchestration policy had no reliable place to route edit requests or distinguish an owner from an editor.
- **What changed:** The real registered winner of first installation owns the module for that run. Runtime metadata is outside editable definitions; repeated/concurrent installation preserves owner/source/state. Default updates expect caller ownership; explicit `{:owner recorded-owner}` permits deliberate nonowner edits without implying approval. Missing identities and uninstalled updates fail before transforms; update-from-nil is removed.

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:11-12`

```markdown
## Automatic bounded ownership guidance

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:13-16`

```markdown
- **Previous behavior:** A discarded install receipt could leave its installer unaware of its module responsibility.
- **Why it was a problem:** Helper-installed policy could have no informed coordination point; mailbox-based onboarding would risk unrelated message drainage or effect-receipt loss.
- **What changed:** A winner-only per-handle queue prepends inert guidance to the next generation without another model call or mailbox receive. Bounded pages retain overflow; no duplicate/wrong-agent onboarding. Dequeue is best-effort: provider failure after dequeue loses that page, and agents returning immediately see none.

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:17-18`

```markdown
## Local code-change journals in dogfood mode

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:19-22`

```markdown
- **Previous behavior:** `--dogfood` exposed human feedback but did not automatically preserve successful module source changes.
- **Why it was a problem:** Later registry reads could miss exact committed policy, editor identity and concurrent order; retries or general globals watches would record speculative/unrelated state.
- **What changed:** Dogfood records exact install baselines and unequal-definition PATTERNS API updates, with run identity, commit sequence/revision, owner/editor/explicit acknowledgment and exact before/after executable data. Reinstall/no-op/failed/speculative/direct-globals changes are unlogged. Append happens outside transforms; sequence reconstructs commit order. Off mode performs no journal disk setup/writes, and ordinary calls do not scan the registry.

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:23-24`

```markdown
## Honest post-commit recording failure

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:25-28`

```markdown
- **Previous behavior:** No automatic module journal existed, so there was no post-commit journal-failure contract.
- **Why it was a problem:** Treating a failed append as an ordinary failed edit would invite replay of a transform whose code was already live.
- **What changed:** The successful edit receipt carries additive failed journal status and `EDIT COMMITTED / RECORDING FAILED`, plus a bounded warning for the actual editor. Preserve its sequence; never replay the edit to repair logging. A recording gap is possible; no crash-atomic guarantee is claimed. The recording boundary also catches controlled `Error` subclasses, preserving the same committed receipt/warning if serialization or append raises one. Focused ownership/feedback coverage passed 31 tests / 282 assertions; injected `StackOverflowError` and `AssertionError` cases each preserve the callable edit with one transform and recording attempt. Actual resource exhaustion/process termination remains outside any guarantee.

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:29-30`

```markdown
## Model-facing guidance and prepared live pilot

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:31-34`

```markdown
- **Previous behavior:** Module/board docs allowed update-from-nil, called ownership only a convention without a default check, and the predecessor pilot let a peer edit without acknowledgment.
- **Why it was a problem:** Those instructions would misdirect current agents and could not demonstrate hidden-return onboarding, rejected default peer edits or actual-editor journaling.
- **What changed:** Module and board docs/skill describe immutable ownership, strict options, owner routing, exact journal scope and honest failure caveats. A complete affected-text before/after inventory is editable separately from source. The small two-agent ownership pilot supplies explicit deterministic setup and future real-model audits; its companion separates static checks from a parent's reserved paid run. The three base prompts, including the exact predecessor pruning-evidence antipattern, are preserved unchanged.

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:35-36`

```markdown
## Lexically safe notice placement during acceptance

```

**After:** `MODULE_OWNERSHIP_CHANGELOG.md:37-39`

```markdown
- **Previous behavior:** The first implementation appended a notice comment at the model prefix's unfinished cursor.
- **Why it was a problem:** At an unfinished string or token, the appended text could alter executable data instead of remaining a comment.
- **What changed:** Prepend a complete escaped comment before the source, preserving the original prefix bytes. Acceptance regression runs passed 24 tests / 226 assertions focused on ownership and 102 tests / 978 assertions in adjacent coverage; these deterministic results do not replace the paid live acceptance evidence.
```

## MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md — complete affected prose/code blocks

### Block group 1

**Before:** absent.

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:1-2`

```markdown
# Module ownership: reserved fresh live acceptance

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:3-4`

```markdown
## Status: fresh live pilot PASS; final source acceptance pending

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:5-6`

```markdown
`MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl` is a complete self-contained init program for a **fresh runtime**, using root and `:ownership-peer`. Explicit program setup supplies installation and edits; real model calls audit captured effects and collect peer completion. The parent executed pilot `2026-09-07-module-ownership-003`: **exit 0, 6 model calls, $0.63907**. Independent `check.clj` / `check.log` / `acceptance.json` in that notebook run bundle report **PASS**.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:7-8`

```markdown
All six actual model prefixes excluded executable body markers. Automatic ownership-notice counts were `[1,0,0,0,0,0]`: root received its notice after the hidden installation return, with no duplicate or wrong-owner onboarding. The real peer completed edge **1**, received as **`msg-6625`**. Root and peer called 11 before the acknowledged peer edit and 12 afterward; the default peer edit was rejected without mutation. The explicit acknowledgment preserved owner `:main` and recorded actual editor `:ownership-peer`.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:9-10`

```markdown
Independent journal inspection found exactly **two records**, install and update with sequences `[1,2]`, and exact source roundtrip/equality. The observed run identity was `3ec72a2b-50af-4682-9f52-21cd7193de45`. These effects occur in explicit init before traced model nodes, so journal `:trace-node-id` is legitimately absent; timestamp, run identity, sequence, and captured peer receipts provide the linkage. This pilot does not claim autonomous setup, race/failure coverage, or final accepted source commit. Final source acceptance remains pending the parent's review and commit.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:11-12`

```markdown
## Rerun guide: parent-only paid command

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:13-14`

```markdown
From the workspace root, with the existing local Codex authentication/profile:

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:15-23`

````markdown
```bash
SPELL_FEEDBACK_PATH="$PWD/.spell/module-ownership-live/feedback.edn" \
  bin/spell --init-file MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl \
  -a config/agent-profiles/io-tc.agent.edn \
  -m codex-tc:gpt-6-astra -R xhigh \
  --dogfood --budget 8 --depth 0 --context-max-chars 10000 \
  --trace-dir "$PWD/.spell/module-ownership-live/trace"
```

````

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:24-25`

```markdown
The parent owns authorization, the reserved pilot budget and durable acceptance/notebook packet. **Do not launch this command as a preparation check.** Use a fresh destination for a new attempt, retain exact command/usage/exit status, and do not enable verbose model logs. `--depth 0` is unlimited depth; the dollar ceiling is modest, not unlimited. Root's compiled agent/profile is inherited by the peer; verify the actual provider and xhigh settings from the run receipt. Budget/provider failure is incomplete acceptance, not success.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:26-27`

```markdown
Public embedding uses `spell.api/run` with `:dogfood true` and the usual `:init`, `:model-profile` and `:agent-profile`. `SPELL_FEEDBACK_PATH` overrides the existing feedback destination (default `.spell/feedback.edn`); automatic journal records and human feedback are distinct entries in that same append-only local EDN stream.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:28-29`

```markdown
## What the explicit setup does

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:30-34`

```markdown
1. Root installs custom `:ownership-live`, discards the install receipt inside a helper expression, calls opaquely (10 → 11), then makes its next actual model generation. That generation must receive automatic owner guidance despite the hidden receipt. The audit prompt asks to identify actual runtime-added guidance; its own instructions are not evidence of such guidance.
2. Root dispatches a real tracked peer. The peer reuses the installed module, discovers its owner, calls opaquely (11), captures source only for the intended edit/equality check, and tries a default nonowner update. It captures the actual owner error and verifies the exact definition is unchanged.
3. The peer deliberately names the recorded owner in `{:owner owner}`, changes the body once (10 → 12), calls opaquely, and reinstalls without resetting. Its model audit receives receipts/results, **not executable bodies**. Actual editor must be `:ownership-peer`, distinct from immutable owner; acknowledgment is not an approval claim.
4. Root collects the actual peer edge/body, calls the changed code opaquely (12), checks reinstall preserves owner, and returns a compact audit. No model is asked to fetch source merely to invoke code. No executable module source is deliberately copied into an audit prefix.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:35-36`

```markdown
The owner generation happens before peer dispatch so it is unambiguously the winner's next generation. Subsequent root generation must not receive duplicate onboarding; peer must not be incorrectly onboarded as module owner. Actual runtime prefix injection occurs after the program builds its input prefix, so only trace/provider-level inspection can independently establish those properties.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:37-38`

```markdown
## Independent acceptance checklist (parent)

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:39-40`

```markdown
Keep raw receipts and actual edge IDs. A model's `:pass?` is not sufficient on its own.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:41-46`

```markdown
- Actual owner first prefix: hidden install return is nil; a separate automatic notice identifies `:ownership-live` as owned by root, expects edit requests and coordinates `patterns/update`. Count that runtime notice, not words inside the audit instruction. Check no duplicate notice in later root prefixes and no wrong-owner notice in peer prefixes. All model prefixes must exclude executable body sentinels `OWNERSHIP_BODY_V1`, `OWNERSHIP_BODY_V2`, `OWNERSHIP_BODY_REJECTED` and complete entry source.
- Actual root/peer reports: root before 11/after 12; real peer edge completed; peer default update rejected with actual owner/caller/actionable guidance; `:unchanged? true`; successful explicit edit records owner=root, editor=`:ownership-peer`, `:explicit-owner? true`, `:journal {:status :ok :sequence s}`; reinstall false and owner preserved. Both audit stages and opacity checks must be genuine received results.
- Read the EDN journal without executing source. Filter `:kind :module-edit`, then `:module :ownership-live` and the observed `:run-id`. Require exactly one `:operation :install` baseline and one changed `:operation :update`, with consecutive sequence/revision for this isolated pilot. Failed default edit and reinstall must have no entry. Root installs; peer edits; owner is identical in both and only peer update acknowledges explicitly. Check timestamps and trace-node linkage to actual effects when present.
- Parse the lossless `:before`/`:after` executable definition strings as data (baseline before is nil). Compare entire definitions against the literal baseline and deliberate replacement in the `.spl` file, not just sentinels. Install after equals update before; update after differs only at `[:functions :run :source]`; the rejected +99 source never commits. `:functions` added/removed/modified must match. Use recorded run identity/sequence for ordering, not physical append order or a later registry read.
- Independently verify unique run identity if comparing another API/CLI run at the same destination. A failed append means `EDIT COMMITTED / RECORDING FAILED`; preserve its receipt/sequence and report an audit gap, never rerun the edit as if it failed. This pilot's happy-path success requires successful recording; deterministic failure/concurrency/isolation tests cover the wider contract.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:47-48`

```markdown
### Preparation receipts actually observed

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:49-53`

```markdown
- Fresh JVM parse: exit 0, `PASS: one complete acceptance form; no provider run`.
- Fresh scripted TestProvider execution: exit 0, **0 paid model calls**, 5 actual provider prefixes, root 11 → 12, actual peer edge 1, hidden-return owner guidance observed by prefix assertions, no later duplicate/wrong-agent notice, and no executable body sentinels in any model prefix.
- Exact journal check: 2 records, install/update sequences `[1 2]`, owner `:main`, actual update editor `:ownership-peer`, explicit acknowledgment true, full source roundtrip/equality assertions passed. Observed run identity `cabd1eaa-c909-4b12-8c14-eeacd2af03e5`.
- Local preparation script and EDN are `.spell/module-ownership-prep/check.clj` and `.spell/module-ownership-prep/feedback.edn`. These are scripted execution evidence, **not a real-model acceptance**. The scripted root result did not independently require the peer model's audit boolean, so the parent's actual received peer report remains mandatory.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:54-55`

```markdown
## Preparation and limitations

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:56-57`

```markdown
Only parsing, scripted TestProvider execution and source/document checks may be performed by the implementation worker. Such checks have no paid model and must be labelled as scripted, not live. The parent records their actual receipt separately from its fresh real-model pilot.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:58-59`

```markdown
This small artifact does not force install/update races, notice overflow, provider failure after notice dequeue, disk failures, independent API isolation, or a crash. Maintained deterministic tests cover those concerns. Guidance is best-effort: returning without a generation sees none, and provider failure after dequeue loses that page. Direct globals mutations remain trusted coordination and unlogged. There is no crash-atomic journal guarantee, security boundary, hidden progress inference, or automatic replay.

```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md:60-60`

```markdown
Preserve the predecessor pruning-evidence rule: never prune supporting evidence and then rediscover it. Persist exact required receipts/snippets, observed stored IDs/offsets and checkpoints first. Proposed actions, source code and sent flags are not proof of execution. Preparing this artifact does not alter any of the three base prompts.
```

## MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl — complete acceptance literals, including each audit prompt

### Literal group 1

**Before:** absent.

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:7-7`

```clojure
"Ownership live acceptance."
```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:8-8`

```clojure
"Increment input."
```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:9-9`

```clojure
"OWNERSHIP_BODY_V1"
```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:22-22`

```clojure
"OWNERSHIP_BODY_REJECTED"
```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:27-27`

```clojure
"OWNERSHIP_BODY_V2"
```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:33-33`

```clojure
"PEER_AUDIT: Explicit program setup (not model-invented code) performed these effects. Audit only captured evidence: caller :ownership-peer differs from owner, reuse/reinstall installed? false, before 11, default edit rejected with actionable owner message, unchanged? true, explicit-owner edit actual editor :ownership-peer, after 12, journal status ok. Inspect your actual model prefix: no executable entry source and no automatic ownership notice assigning this module to you. Return a quoted map {:peer-ok? boolean :opaque-prefix? boolean :wrong-owner-notice? boolean}. No tools/source fetch needed. Never prune evidence then rediscover it; retain exact receipts before pruning."
```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:42-42`

```clojure
"OWNER_ONBOARDING_AUDIT: The explicit setup helper installed :ownership-live but discarded its receipt. Only real captured evidence is supplied. Inspect the actual prefix for automatically supplied ownership guidance naming :ownership-live, saying you own it, expect edit requests and should coordinate changes/use patterns/update. Do not count this audit instruction as that guidance. No entry source should appear. Return a quoted map {:owner-notice? boolean :opaque-prefix? boolean :hidden-return? boolean}; hidden-install-return must be nil. Do not install, edit, fetch source or use tools. Never prune evidence then rediscover it; retain actual receipts."
```

**After:** `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl:48-48`

```clojure
"OWNER_COLLECT_AUDIT: Explicit setup installed with hidden return, audited automatic onboarding, and dispatched a real peer. Collect the actual result for peer-edge; wait via quoted agents/!wait only while required work remains. Preserve actual received edge/body. Then capture patterns/call :ownership-live :run 10 and patterns/install :ownership-live using !call-now; require 12, installed? false, same owner. Peer must report before 11, rejected default edit, unchanged? true, explicit-owner edit owner unchanged/editor :ownership-peer/journal ok, after 12, audit peer-ok? and opaque-prefix? true and wrong-owner-notice? false. Owner-audit must have all three booleans true. Check current prefix has no executable entry source and no duplicate automatic ownership onboarding. Return a quoted compact map {:pass? boolean :receipts receipts :peer received-peer-evidence :after actual-after :reuse actual-reuse :opaque-prefix? boolean :duplicate-notice? boolean}. Do not fabricate results or fetch source merely to invoke it. Journal/prefix independent verification belongs to the external companion, not invented model evidence. If interrupted, inspect pending edges before retrying. Never prune evidence then rediscover it; retain receipts/observed IDs first."
```

## Three base prompts — exact predecessor preservation

- `config/prompts/sysprompt-message.txt`: entire file byte-identical to base; SHA-256 `f442dc47377ddc75649520c80744d1631559ff6b0d90ae2875adb2cc69ba3706`; exact preserved block lines 332–353.
- `config/prompts/sysprompt-prefill.txt`: entire file byte-identical to base; SHA-256 `14883fad9d4002d3ec3f5f91365c9ef7e89b64b9361e5c9eab3ded667f76c3c7`; exact preserved block lines 336–357.
- `config/prompts/sysprompt-toolcall.txt`: entire file byte-identical to base; SHA-256 `a8bf1670a7aab68c209d235215badf0ee1f2ceaf1bc48f9d99424c73281ef173`; exact preserved block lines 375–396.

**Before and after (identical in all three locations):**

```text
Pruning evidence and rediscovering it — `!peek` automatically removes its command and result on the following extension. Actively retain important snippets with `persist` before they disappear. Carry forward the evidence and constraints needed to complete the work, including through compaction.

  '(!peek parser-lines (io/read-lines "src/parser.py" 20 40))
  ;; next turn receives:
  (def parser-lines [...])
  (prune 2) ; inserted automatically by !peek

  ;; WRONG: the intention survives, but the supporting evidence disappears
  (think "I understand the bug. Next I will write a reproduction.")
  '(!extend)
  ;; following turn: rereads instead of writing the reproduction
  '(!peek parser-lines (io/read-lines "src/parser.py" 20 40))

  ;; correct: retain the needed evidence and act while it is available
  (persist parser-focus (subvec parser-lines 0 10))
  (think "The parser indexes the first character before checking for empty input.")
  '(!call-now _
      (io/write-file "repro.py"
        "from src.parser import parse\nprint(parse(''))\n")
      result
      (io/sh "python repro.py"))

```

## Coverage manifest and reproducible checks

Input hashes identify this exact inventory snapshot. A later source/doc change requires regeneration, not a claim of continuing coverage. New files have no base text. The inventory deliberately excludes itself to avoid self-reference.

| Input | Base SHA-256 | Current SHA-256 |
| --- | --- | --- |
| `src/spell/api.clj` | `407739429a4fb65f9704e8ae8d0f06f77511733c55726fd1c838de0a61bbabad` | `ccb62cdda2cb28453e5f9fe96a90fa6ce94eeac44f49d38aadd1bac8358096db` |
| `src/spell/cli.clj` | `ab67ae49b3c3a3460d168bd4b7ecf2ab8019e2c6cc62ad12e70a17e46a94e33c` | `a10f0ba01321bcc0de7aa0150a0e56d7aa505963f01dbaa913c2b7fe0045c879` |
| `src/spell/feedback.clj` | `9c5f6eed07589081125b4335502b7ab5b469ac74d9fa80f92ce5f9c4e34588e8` | `145f7862b395fc1285601434b974eee545b4b44dc17a8d0a00e35923c6a8dc5a` |
| `src/spell/llm.clj` | `85f8d1fa790ec29cf5187a60a7cc065f23ef0f3828ee64d40bde365d90552ec1` | `0c40bafe0d7dd99e4e34d5683577d67b2bdf7ffb0a0c2a311139b3a2083467f5` |
| `src/spell/patterns.clj` | `bddc5f2430bf707a2b822f8f94bd0f1b6e544106de5acb958c4749ec5f36c0bc` | `5e624a3659abba51f2cd69077594ce9ab5d3f47c520a034743d4ab692f5ae845` |
| `src/spell/module_journal.clj` | `ABSENT` | `b0bb0e9461ad7ac14b249091b29ec20ac9bafecca8189fbf6c3a4c73bb0b56b9` |
| `src/spell/module_notices.clj` | `ABSENT` | `03eb0877c93e3075609178a0c2790f74dd8e536bfc02b9248ec1a9953acca6e2` |
| `AGENTS.md` | `a1fb732f56aa1d65aac044b2cf2155b1a6272556277d2e84f91c191360ba5ffc` | `b4ee2b9ab00cc1eea32ebac1c876bafadd31fe12cd4808405ef7b0573e2be3b8` |
| `docs/api.md` | `4cdbb991aafbd836c2d2017363b07ba3a3709b80ade13ea5dfb35ce56cdb57f8` | `acd4a0e3dc57c37f7f2491f20fa39ce127f0eb270e34d061bdb865ef87da452c` |
| `docs/installable-modules.md` | `d19f19c7b99b66b86032172f49d0c29a17522ed3c4b030e42983a3ed5db82c89` | `cd6fa42e6ce1d1b18e4b6ab64e06905b272e1ce9105a28e5b93727e60ac8e852` |
| `docs/mailing-list.md` | `0c762f5eecf43fdda5566d3bb0c635a6f96aea2b5e317c1875efe9e9728fc18c` | `8ad182d6031cd26570d62827e3bba4f0fcdf2cc3143842a724071bbc87dfdce1` |
| `resources/skills/mailing-list/SKILL.md` | `1a9f549a8db68477ee2488122c59d73e01745c56608e3defd3c0c7bf9a25fb38` | `ce7d6f11b44833c9c80fff5e70d093cbc858d271ae22e6e1d6fd79584bd9c449` |
| `MODULE_OWNERSHIP_CHANGELOG.md` | `ABSENT` | `8f79957d9ecddb18ec185b6bb1c89713a6b7c675863f54384059d70005478620` |
| `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.md` | `ABSENT` | `9835c43803c76d05ed8fc79822940a6d4386a437af59113118df62d3a4533eac` |
| `MODULE_OWNERSHIP_LIVE_ACCEPTANCE.spl` | `ABSENT` | `491aa45a95aa7156cf2fa7c396d623b974949983a086ea5da500da7e498a3284` |
| `config/prompts/sysprompt-message.txt` | `f442dc47377ddc75649520c80744d1631559ff6b0d90ae2875adb2cc69ba3706` | `f442dc47377ddc75649520c80744d1631559ff6b0d90ae2875adb2cc69ba3706` |
| `config/prompts/sysprompt-prefill.txt` | `14883fad9d4002d3ec3f5f91365c9ef7e89b64b9361e5c9eab3ded667f76c3c7` | `14883fad9d4002d3ec3f5f91365c9ef7e89b64b9361e5c9eab3ded667f76c3c7` |
| `config/prompts/sysprompt-toolcall.txt` | `a8bf1670a7aab68c209d235215badf0ee1f2ceaf1bc48f9d99424c73281ef173` | `a8bf1670a7aab68c209d235215badf0ee1f2ceaf1bc48f9d99424c73281ef173` |

Counts: `{"dynamic_forms": 9, "markdown_change_groups": 7, "pilot_literal_change_groups": 1, "source_literal_change_groups": 12}`.

Checks performed by the generator: balanced Clojure literal/form scanning; deterministic full changed-literal/block enumeration; exactly one complete instance of each of the three audit prompts; exact whole-file base-prompt equality; identical preserved pruning-evidence block across all three. This is text-inventory validation, not execution, test-suite, model-acceptance, or approval evidence. No paid provider calls are made.
