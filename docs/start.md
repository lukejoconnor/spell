# Install and run

Get from a fresh checkout to a locally running Spell program. Java and Clojure are runtime prerequisites; Node is used only to build this documentation site.

## Prerequisites

- Java 11 or newer
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- A provider credential for model-backed runs, or the built-in test provider for a smoke test

On macOS:

```bash
brew install clojure/tools/clojure
```

Clone the repository:

```bash
git clone https://github.com/lukejoconnor/spell.git
cd spell
```

No application build step is required. `bin/spell` invokes the Clojure CLI entry point.

## Verify the checkout

These commands make no model API call:

```bash
bin/spell -h
bin/spell -t "Return a short greeting"
```

The test provider verifies Java, Clojure dependencies, argument parsing, and evaluator wiring.

## Run a model-backed task

The CLI default uses OpenAI and requires `OPENAI_API_KEY`:

```bash
export OPENAI_API_KEY=...
bin/spell "Inspect the examples directory and suggest one program to run."
```

Select another configured provider with `-m`; run `bin/spell -h` for the options in your checkout.

## Try an example

```bash
bin/spell -e hello-world
bin/spell -e coin-flip
bin/spell -e twenty-questions -d 40
```

| Example | What it demonstrates |
| --- | --- |
| [Hello world](https://github.com/lukejoconnor/spell/blob/main/examples/hello-world.md) | A minimal self-call |
| [Coin flip](https://github.com/lukejoconnor/spell/blob/main/examples/coin-flip.md) | Recursion and a programmatic stopping condition |
| [Twenty questions](https://github.com/lukejoconnor/spell/blob/main/examples/twenty-questions.md) | A multi-agent game loop |
| [Telephone](https://github.com/lukejoconnor/spell/blob/main/examples/telephone.md) | Sequential agent relay |
| [Auction](https://github.com/lukejoconnor/spell/blob/main/examples/auction.md) | Parallel agent fan-out |
| [Chat](https://github.com/lukejoconnor/spell/blob/main/examples/chat.md) | Interactive agent communication |
| [MCP everything](https://github.com/lukejoconnor/spell/blob/main/examples/mcp-everything.md) | A local MCP integration |

The [examples index](https://github.com/lukejoconnor/spell/blob/main/examples/README.md) lists every bundled program and command.

## Preview this documentation locally

Node is documentation tooling only; it is not a Spell runtime dependency.

```bash
npm ci
npm run docs:dev       # development server
npm run docs:build     # production build
npm run docs:preview   # serve the production build
```

The development and preview commands print the local URL. `npm run docs:check` also checks conflict markers and the production build.

## Next

Read [How Spell works](./language-overview.md) to understand why a completion is a program, or go to [capabilities](./capabilities.md) to choose what an agent can do.
