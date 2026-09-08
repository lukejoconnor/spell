# Caller-owned shell work and tracked agents

There is no bundled `team`, generic git worktree framework, automatic commit, retry policy, or cleanup replacement. Compose the primitives deliberately. Use the caller's configured compiled agents (illustratively `workers/implementer` and `workers/verifier`); those symbols must already be exposed by the caller's profile. This guide does not create or modify profiles.

First inspect the selected worktree and retain its dirty/untracked baseline with ordinary shell:

```clojure
'(!call-now baseline (io/sh "git status --porcelain=v1"))
;; The next action requires an actual baseline receipt, not a proposed check.
'(!call-now implementation-edge
   (agents/spawn-ask workers/implementer
     "Implement ONLY the assigned change in the agreed worktree. Preserve pre-existing dirty and untracked files. Do not commit, reset, clean, delete worktrees or overwrite another worker's outputs. Return exact changed paths and actual test command/exit/log receipts, including failures."
     :implementation-1))
;; Do independent work, then wait only while a required collection remains.
'(agents/!wait)
```

On waking, match the actual `:edge-id` and `:from`; inspect the received `:body`, including tagged child failure or a successful nil. Receiving some unrelated report does not prove this edge completed. Preserve dispatched edge IDs and uncollected outputs; never treat an empty outgoing set alone as proof of successful collection. Incoming messages may supersede proposed actions, so establish whether dispatch actually executed before retrying it.

After collecting the implementation result, pass that **actual retained report** and the dirty baseline to a separately configured verifier, using another captured `agents/spawn-ask` edge. Give the verifier exact acceptance criteria and forbid modifications. Collect its report independently. The parent then runs the project-specific shell test command and captures `{:exit :out :err}` with a durable log path; do not infer tests from a worker's plan. Verification agreement is not a correctness guarantee.

If explicit isolation is needed, the caller chooses the worktree directory and shell commands, checks existing files/branch state, and owns integration decisions. No automatic reset, checkout, merge, worktree deletion or disposal of dirty/uncollected outputs follows success or failure. Preserve failed tests and partial artifacts for review. Only an authorized owner decides commits or cleanup. Board participation, if needed, is a separate configured child startup composition in [mailing-list.md](../docs/mailing-list.md), not a new spawning framework.
