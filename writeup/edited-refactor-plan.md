# Refactor: Simplify LLM engine and communication layer

## Context

The Sonnet-started refactor left `comm.clj` and `llm.clj` in a partially updated state with broken functions (`block-for-message`, `ask-multi`). The goal is to complete the refactor with a cleaner architecture matching the user's 4-component spec:

1. **spell-eval** — unchanged evaluator (eval.clj, not touched)
2. **eval** — per-agent function via `make-eval` (already works)
3. **box** — single execution primitive; receives parent-handle for root detection
4. **call-llm** (`-llm`) — simple: make API call → call box. No inbox/lifecycle management.

Additionally: delete hooks entirely, simplify registry, remove `*spawn-ready*`, remove `unregister!`.

## Phase 1: Delete hooks system

**Files to delete:**
- `src/spell/hooks.clj`
- `test/spell/hooks_test.clj`

**Files to modify:**

`src/spell/llm.clj`:
- Remove `(:require ... [spell.hooks :as hooks])`
- Remove `hooks` parameter from `-llm`, `make-eval-pipeline`
- Remove `hook-builtins` map and all hook refs from `make-llm` (lines 340-344)
- Remove hooks from `the-llm` and `self-fn` arities
- Remove `(hooks/apply-hooks hooks program)` from `make-eval-pipeline`

`src/spell/core.clj`:
- Remove `(:require ... [spell.hooks :as hooks])`
- Remove all hook re-exports (lines 25-29): `prepend-hooks-to-llm`, `recurse`, `with-env`, `prefix-prompt`, `with-env-hints`
- Remove hook builtins from the `pure-builtins` map (lines 92-96)

`src/spell/agent.clj`:
- Remove `:hooks` from `merge-agent-defs` (line 254-257)
- Remove `:hooks` from `load-agent` destructuring (line 310)
- Remove `:hooks` from `load-agent-config` destructuring and return map (lines 357, 376)
- Remove `:hooks` from `default-agent-config` return (line 401)

`src/spell/cli.clj`:
- Remove `hooks` from `make-agent-llm` destructuring (line 210)

`test/spell/llm_test.clj`:
- Delete 5 hook tests (lines 71-104, 181-189): `llm-hooks-basic-test`, `llm-hooks-multiple-test`, `llm-hooks-inject-binding-test`, `llm-hooks-no-hooks-unchanged-test`, `llm-recursive-hook-test`

`src/spell/comm.clj`:
- Remove `hooks` param from `spawn` (line 352: `(llm-fn prompt [] handle)` → `(llm-fn prompt handle)`)

## Phase 2: Core refactor

All changes below are coordinated — they touch the same functions and depend on each other.

### 2a. Simplify inbox function signature

Change inbox functions from `[raw eval-builtin] -> value` to `[raw] -> value`. The eval-builtin is closed over inside the function, not passed as a parameter.

**`llm.clj` — `make-eval-pipeline` → rename to `make-inbox-fn`:**
- Remove `eval-builtin` from parameter of returned fn
- Close over eval-builtin from config instead: `(let [eval-builtin (:eval-builtin config)] ...)`
- Returned fn becomes `(fn [raw] ...)` instead of `(fn [raw eval-builtin] ...)`
- Recovery logic stays unchanged (it references eval-builtin from closure)

### 2b. Add `default-eval-inbox` to comm.clj

Add a module-level default inbox function in `comm.clj`:
```clojure
(defn- default-eval-inbox
  "Default inbox: parse raw text and evaluate with current *builtins*."
  [raw]
  (let [balanced (parse/balance-parens raw)
        forms (parse/read-all balanced)
        program (if (> (count (vec forms)) 1) (list* 'do forms) (first forms))]
    (binding [eval/*llm-depth* (inc eval/*llm-depth*)
              eval/*raw-text* balanced]
      (let [result (eval/spell-eval program {})]
        (if (eval/ok? result) (:ok result)
            (throw (ex-info (:err result) {:result result})))))))
```

### 2c. Simplify `register!`

**`comm.clj`:**
- `register!` takes `[handle]` or `[handle default-inbox-fn]`
- Default inbox-fn falls back to `default-eval-inbox`
- Registry entry: `{:inbox, :signal, :has-box, :default-inbox-fn, :waiters, :collector}`
- No `:eval-builtin` in registry

### 2d. Restructure `box` with parent-handle and root detection

**`comm.clj` — `box`:**
- New signature: `[completion-promise handle parent-handle]`
- Root detection: `(let [root? (or (nil? parent-handle) (not= parent-handle handle))]`
- After main loop returns result:
  - If root?: `(notify-waiters! handle result)` + `(orphan-box! completion-promise handle)`
  - Exception path: if root?, notify-waiters with nil + create empty orphan-box
- No `unregister!` call (handles persist)
- Box calls inbox fn as `(f current-raw)` — no eval-builtin arg

**`comm.clj` — `orphan-box!`:**
- New signature: `[completion-promise handle]` (drop eval-builtin)
- Calls `(box completion-promise handle handle)` — parent=self, not root

### 2e. Fix `send` composition

**`comm.clj` — `send`:**
- Simpler composition: `(fn [raw] (base (f raw)))` instead of `(fn [raw eval-builtin] (base (f raw) eval-builtin))`
- Fallback base: `(:default-inbox-fn (get @registry handle))`

### 2f. Fix `block-for-message`

**`comm.clj` — `block-for-message`:**
```clojure
(defn- block-for-message []
  (let [{:keys [has-box]} (get @registry *current-handle*)]
    (reset! has-box false)
    (let [p (promise)]
      (deliver p *current-raw*)
      (box p *current-handle* *current-handle*))))  ;; parent=self → not root
```

### 2g. Fix `ask-multi`

**`comm.clj` — `ask-multi`:**
- Replace `(:eval-fn entry)` → `(:default-inbox-fn entry)`
- Fix box call: `(box (promise delivering final-raw) handle handle)` — not root
- Properly wrap final-raw in a promise before passing to box

### 2h. Fix `start-box`

**`comm.clj` — `start-box`:**
- New signature: `[handle default-inbox-fn initial-completion]` (drop eval-builtin)
- Calls `(register! handle default-inbox-fn)`
- Calls `(box p handle nil)` — nil parent → root behavior (orphan box after wake)

### 2i. Simplify `-llm`

**`llm.clj` — `-llm`:**
- New signature: `[{:keys [call-fn] :as config} prompt handle parent-handle]`
- Body: ~20 lines. Create completion-promise, start API call future, call box, trace.
- No root detection, no register, no inbox seeding, no unregister — all handled by box and the-llm.

### 2j. Simplify `make-llm` and `the-llm`

**`llm.clj` — `make-llm`:**
- Remove hook-builtins
- Create `default-inbox` via `make-inbox-fn` (closes over config)
- `the-llm` signature: `([prompt] [prompt handle])`
- 1-arg: inherit handle from `*current-handle*` or gensym new one
- 2-arg: explicit handle (from spawn)
- Root detection logic:
  - `*parent-handle*` set AND `*current-handle*` not set → spawn case → parent = `*parent-handle*`
  - `*current-handle*` set → inherited (!llm-self) → parent = handle (same)
  - Neither set → top-level → parent = nil
- If not registered: `(register! handle default-inbox)`
- Seed inbox: root → `reset!`, inherited → `compare-and-set!`
- Call `-llm`

**`llm.clj` — `self-fn` (!llm-self):**
- Simplify to 1-arg and 2-arg (no hooks)
- Remove `*spawn-ready*` guard

### 2k. Simplify `spawn` and remove `*spawn-ready*`

**`comm.clj` — `spawn`:**
- Register handle synchronously (before future)
- No `*spawn-ready*` binding/delivery
- `(llm-fn prompt handle)` — 2-arg call

**`comm.clj`:**
- Remove `*spawn-ready*` dynamic var declaration
- Remove from `spawn-recv` if applicable

### 2l. Update `register-agent`

**`llm.clj` — `register-agent`:**
- Use `make-inbox-fn` instead of `make-eval-pipeline`
- Call `(comm/start-box handle-name default-inbox initial-completion)` (no eval-builtin)

### 2m. Delete `unregister!`

**`comm.clj`:**
- Remove `unregister!` function entirely

## Phase 3: Update tests

**`test/spell/comm_test.clj`** — rewrite unit tests for new API:
- `register!` calls: `(comm/register! :h identity)` (2-arg) or `(comm/register! :h)` (1-arg)
- `box` calls: `(comm/box (doto (promise) (deliver "raw")) :h :h)` (3-arg with promise + parent-handle)
- Remove `:eval-fn` references
- Update `orphan-box!` calls (drop eval-builtin arg)
- Test root behavior (parent != handle triggers notify-waiters + orphan)
- Test non-root behavior (parent = handle, no root cleanup)

**`test/spell/llm_test.clj`:**
- Already deleting hook tests in Phase 1
- Update any tests that pass hooks `[]` to llm calls
- Keep recovery tests, format tests, provider tests, namespace tests

**`test/spell/hooks_test.clj`** — already deleted in Phase 1.

## Phase 4: Cleanup

- Remove dead code (unused imports, commented-out code)
- Update `CLAUDE.md` if needed (remove hooks references)
- Verify all 10 test files pass

## Key files (modification order)

1. `src/spell/hooks.clj` — DELETE
2. `test/spell/hooks_test.clj` — DELETE
3. `src/spell/comm.clj` — major changes (registry, box, send, spawn, block-for-message, ask-multi)
4. `src/spell/llm.clj` — major changes (-llm, make-llm, make-eval-pipeline → make-inbox-fn)
5. `src/spell/core.clj` — remove hook re-exports and builtins
6. `src/spell/agent.clj` — remove hooks references
7. `src/spell/cli.clj` — remove hooks from make-agent-llm
8. `test/spell/comm_test.clj` — rewrite for new API
9. `test/spell/llm_test.clj` — delete hook/recovery tests, update others

## Verification

```bash
clj -M:test              # Run all tests
clj -M:test -n spell.comm-test    # Focused comm tests
clj -M:test -n spell.llm-test     # Focused llm tests
```

Check that no references to deleted hooks remain:
```bash
grep -r "hooks" src/ test/ --include="*.clj" | grep -v ".clj.bak"
```
