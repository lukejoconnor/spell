# Spell

Self-programmed execution (SPE) is when a language model acts as a self-orchestrating agent by writing a program which the harness evaluates. Spell is a Lisp designed for SPE. It is based upon and embedded within Clojure.

Spell is an academic research prototype described in the [Spell paper](https://arxiv.org/abs/2605.06898).

## Start here

- [Install and run](./start.md) covers prerequisites, a no-API smoke test, examples, and local documentation preview.
- [Spell language overview](./language-overview.md) explains completion-as-program, self-reference, effects, context management, and agents.
- [Multi-agent communication](./multi-agent.md) covers requests, replies, message receipt, waiting, and interaction with the user.
- [Capabilities, configuration, and API](./capabilities.md) introduces model and agent profiles, namespaces, MCP, and the two ways to invoke Spell.
- [API reference](./api.md) is the exact public Clojure API and configuration reference.
- [Changelog](./CHANGELOG.md) records release history and unreleased changes.
- [Examples](https://github.com/lukejoconnor/spell/tree/main/examples) contains runnable programs with companion explanations.

## Main ideas

A model completion is both executable source and context for a later model turn. Spell programs can refer to and edit their own source, make model and tool calls through an explicit effect boundary, and coordinate multiple agents. The selected agent profile describes the capabilities available to an agent.

## Communication model

Spell programs choose when to launch agents, exchange messages, and wait. Agents can send messages or request results from one or more agents. Requests return immediately, so the caller can continue working before waiting. Replies are associated with their requests, and a multi-target request collects one result from each target.

Waiting agents can awaken to handle new messages while their requests remain pending. A per-run coordinator tracks requests and wakeups and enforces a waiting order that prevents communication deadlock, assuming fair scheduling and eventual progress of model calls, tools, and evaluator work. When terminal input is enabled, the user participates in the same communication system as `:user`.

See [multi-agent communication](./multi-agent.md) for the operations and examples.
