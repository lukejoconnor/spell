---
name: spell-api-and-cli
description: Use Spell from the CLI, the current Clojure API, and the current Python benchmark client. Use when writing commands, embedding Spell in Clojure, calling the benchmark JSON bridge from Python, or checking current versus proposed API vocabulary.
---

# API And CLI

## CLI

Common commands:

```bash
bin/spell -h
bin/spell -t "Return a short greeting"
bin/spell -e hello-world
bin/spell -v -e coin-flip
bin/spell examples/twenty-questions.spl -d 40
bin/spell -m openai-tc:gpt-5.4 "Explain this repository in three bullets."
```

Useful controls:

- `-a FILE`: use an agent profile.
- `-m MODEL`: use a provider-prefixed model spec.
- `-b DOLLARS`: cap spend.
- `-d DEPTH`: cap recursive LLM depth.
- `-T`: record a trace.
- `--log FILE` or `-v`: inspect raw model responses.

Run `bin/spell -h` for the authoritative option list in the current checkout.

## Current Clojure API

The checked-out `spell.api/run` takes a map with exactly one of `:prompt` or `:init`, an `:agent` path, and an `:lm-profile` path, inline LM profile map, or low-level provider instance.

```clojure
(require '[spell.api :as spell])

(spell/run {:prompt "Return 42."
            :lm-profile {:provider :test
                         :response "(def x 42)"}
            :agent "config/agents/base-msg.agent.edn"})
```

For a complete Spell program:

```clojure
(spell/run {:init "(do 42)"
            :lm-profile {:provider :test
                         :response "unused"}
            :agent "config/agents/base-msg.agent.edn"})
```

`docs/api.md` describes the public API and configuration surface for this checkout.

## Current Python Surface

The current Python file is `spell_benchmark_client.py`. It is a benchmark JSON client around `spell.benchmark-api`, not a general packaged Python API.

```python
from pathlib import Path
from spell_benchmark_client import SpellBenchmarkClient

client = SpellBenchmarkClient(project_root=Path("."))
response = client.run(
    {
        "mode": "spell",
        "prompt": "Return 42.",
        "agent": "config/agents/base-msg.agent.edn",
        "lm_profile": "config/lm-profiles/openai-tc.edn",
    },
    timeout=300,
)
```

The Python benchmark client is not the stable public Python API. When documenting the public API direction, cite `docs/api.md`.
