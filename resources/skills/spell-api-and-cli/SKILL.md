---
name: spell-api-and-cli
description: Use Spell from the CLI, the Clojure API, and the Python benchmark adapter. Use when writing commands, embedding Spell in Clojure, calling Spell from benchmark Python code, or checking current API vocabulary.
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
bin/spell --init "(do (+ 20 22))"
bin/spell --init-file scratch/my-program.spl
bin/spell -m openai-tc:gpt-5.4 "Explain this repository in three bullets."
```

Useful controls:

- `-a FILE`: use an agent profile.
- `-m MODEL`: use a provider-prefixed model spec.
- `--init PROGRAM`: run a complete Spell program directly.
- `--init-file FILE`: run a complete Spell program from a file.
- `-b DOLLARS`: cap spend.
- `-d DEPTH`: cap recursive LLM depth.
- `-T`: record a trace.
- `--log FILE` or `-v`: inspect raw model responses.

Run `bin/spell -h` for the authoritative option list in the current checkout.

## Clojure API

`spell.api/run` takes a map with exactly one of `:prompt` or `:init`, an `:agent-profile` path, and a `:model-profile` path, inline model profile map, or low-level provider instance.

```clojure
(require '[spell.api :as spell])

(spell/run {:prompt "Return 42."
            :model-profile {:provider :test
                         :response "(def x 42)"}
            :agent-profile "config/agent-profiles/base-msg.agent.edn"})
```

For a complete Spell program:

```clojure
(spell/run {:init "(do 42)"
            :model-profile {:provider :test
                         :response "unused"}
            :agent-profile "config/agent-profiles/base-msg.agent.edn"})
```

`docs/api.md` describes the public API and configuration surface for this checkout.

## Python Benchmark Adapter

`spell_benchmark_client.py` is for benchmark harnesses that need to call Spell from Python. It starts a Clojure subprocess running `spell.benchmark-api`, sends a JSON request, and parses the JSON response.

```python
from pathlib import Path
from spell_benchmark_client import SpellBenchmarkClient

client = SpellBenchmarkClient(project_root=Path("."))
response = client.run(
    {
        "mode": "spell",
        "prompt": "Return 42.",
        "agent_profile": "config/agent-profiles/base-msg.agent.edn",
        "model_profile": "config/model-profiles/openai-tc.edn",
    },
    timeout=300,
)
```

Use this adapter when working on the benchmark runner or other Python code that needs the benchmark JSON contract. For user-facing examples and public API docs, prefer the CLI and `spell.api/run`; `docs/api.md` describes the intended Python adapter shape if a general Python API is added.
