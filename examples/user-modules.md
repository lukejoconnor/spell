# User modules: explicit source and fresh-run reload

`example-greeting.spl` contains ordinary unquoted definition data; it is not a startup program. Deliberately copy it to `<worktree-root>/.spell/modules/example-greeting.spl` (or `$HOME/.spell/modules/example-greeting.spl`) after checking that you will not overwrite an existing file. No command here creates or overwrites a user module automatically.

A run captures its canonical worktree root once; outside git the startup cwd is the root. Project `.spell/modules` wins over HOME `.spell/modules`, which wins over the bundle. File names are flat lowercase-kebab names. Catalog shows the selected `:origin` map with `:kind` and canonical path/resource URL. Invalid selected source errors at that path without falling back to a lower-priority copy. Reading/cataloging/installing is inert; only an explicit call runs an entry.

These are successive quoted trailing actions inside the usual completion wrapper:

```clojure
'(!call-now available (patterns/catalog :example-greeting))
'(!call-now installed (patterns/install :example-greeting))
'(!call-now hello (patterns/call :example-greeting :run "Ada"))
;; Fetch source only to inspect/edit/save, not merely to invoke an opaque shared entry.
'(!call-now changed
   (patterns/update :example-greeting assoc-in [:functions :run :source]
     '(fn [name] (str "Welcome, " name))))
'(!call-now saved
   (io/write-file "/CHOOSE/WORKTREE/.spell/modules/example-greeting.spl"
     (str (pr-str (patterns/source :example-greeting)) "\n")))
```

Replace the absolute destination before executing it; saving is an intentional file overwrite. Reinstalling in the same run returns `:installed? false` and preserves the installed edit, immutable owner/origin and journal history. It does **not** reload disk. A new `spell.api/run` or CLI invocation installs the saved definition afresh. The new run gets a new registry/owner baseline; saving source does not persist board state, coordinator requests or live ownership metadata.

A configured child can install/reuse then call the shared module without fetching its source into its prompt. `:requires` prechecks namespaces, not every individual function, and grants no capabilities. Functions retain caller dynamic scope; opaque arguments are not traversed. Coordinate edits with the actual owner, and retain any `EDIT COMMITTED / RECORDING FAILED` receipt without replaying the live edit. Offline fresh-runtime checks under `.spell/module-library-001` test these mechanics with scripted providers; they are not evidence of autonomous model decisions.
