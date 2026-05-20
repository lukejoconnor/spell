---
name: spell-install
description: Install Spell from a fresh clone and verify local prerequisites. Use when setting up the Spell repository, checking Java or Clojure prerequisites, or helping a user get to a runnable checkout.
---

# Spell Install

## Goal

Get a local Spell checkout that can run `bin/spell -h` without requiring an LLM API call.

## Workflow

1. Verify prerequisites:

   ```bash
   java -version
   clj -Sdescribe
   ```

   Spell expects Java 11+ and the Clojure CLI. On macOS with Homebrew:

   ```bash
   brew install clojure/tools/clojure
   ```

2. Clone and enter the repository:

   ```bash
   git clone https://github.com/lukejoconnor/spell.git
   cd spell
   ```

3. Check the CLI wrapper:

   ```bash
   bin/spell -h
   ```

No build step is required for normal CLI use. `bin/spell` runs the Clojure CLI entry point with `clj -M:run`.

## Notes

- Do not claim package-manager support beyond the checked-out repository unless the repo adds it.
- If `clj` is missing, install the Clojure CLI rather than Leiningen.
- Provider credentials are handled by `skills/provider-setup-and-smoke-test/SKILL.md`.
