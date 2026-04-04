# Literature Search — 2026-03-21

## Search parameters
- **Date range:** All time, focused on 2024–present
- **Keywords used:** self-programmed execution, autonomous agent orchestration, LLM self-orchestration, context engineering, context management agent, progressive disclosure, FoldGRPO, AgentFold, SCULPTOR, context pruning, context compression, multi-agent LLM, agent swarm, PARL, inter-agent communication, recursive language models, RL agent orchestration, SWE-bench, coding agent, program-aided reasoning, code interpreter math, PAL, PoT, ToRA, DSL LLM orchestration, LangGraph, homoiconicity LLM, quine LLM, self-referential agent, test-time compute, chain-of-thought, tree-of-thought, reasoning scaling, AIME benchmark, Omni-MATH, LongBench v2, process reward model, Kimi K2.5 swarm, Manus context engineering, Claude Code teams, Cursor agent architecture, Devin, and many variants
- **Sources searched:** arXiv (cs.CL, cs.AI, cs.SE, cs.PL), Semantic Scholar, Google Scholar, company engineering blogs (Anthropic, OpenAI, Google, DeepSeek, Moonshot AI, LangChain, Manus, Cursor, Cognition, Replit, JetBrains), thought leader blogs (Karpathy, swyx, Simon Willison, Lilian Weng, Yohei Nakajima, Addy Osmani), GitHub, Hacker News

---

## HIGH priority

### Self-Programmed Execution, Self-Referential Agents, Autonomous Orchestration

#### Lisp metaprogramming for LLMs
**Title:** From Tool Calling to Symbolic Thinking: LLMs in a Persistent Lisp Metaprogramming Loop
**Authors:** Jordi de la Torre
**Date:** 2025-06-08
**Venue/Source:** arXiv (2506.10021)
**URL:** https://arxiv.org/abs/2506.10021
**Relevance:** Most directly comparable work. Proposes LLMs operating within a persistent Lisp REPL, leveraging homoiconicity for self-modifying tool creation and stateful memory. Conceptual framework (no empirical results), but addresses same design space as Spell. Unlike Spell, the LLM's completion is not itself the program.

#### Pel orchestration language
**Title:** Pel, A Programming Language for Orchestrating AI Agents
**Authors:** Behnam Mohammadi
**Date:** 2025-04-03
**Venue/Source:** arXiv (2505.13453)
**URL:** https://arxiv.org/abs/2505.13453
**Relevance:** Lisp-like S-expression language designed for LLM orchestration: homoiconic syntax, piping via `|>`, Common Lisp-style restarts, NL conditions evaluated by LLMs, "REPeL" REPL with LLM-powered error correction, automatic parallelization via static dependency analysis, grammar-level capability control. Closest language-design comparison to Spell, though designed as external orchestration language (human writes the Pel program) rather than self-programmed execution substrate (in Spell, the LLM's completion IS the program). **No implementation exists** — GitHub repo ([github.com/ibehnam/Pel](https://github.com/ibehnam/Pel)) is empty, no benchmarks, no community. Only tangible artifact is a Neovim syntax plugin. One applied paper (BEACON, on SSRN) describes using Pel for small business agent automation but its code is also not public. 1 citation total. Author's primary field is marketing (PhD CMU Tepper, now UT Dallas), not PL research. Worth citing as concurrent language design effort but Spell is far more mature.

#### Self-evolving programs via quines
**Title:** Self-Evolving Programs: A Novel Approach Leveraging LLMs and Quine Programs
**Authors:** Saghiri, Wang
**Date:** 2024-07
**Venue/Source:** IEEE ICCIMS 2024
**URL:** https://ieeexplore.ieee.org/document/10690672/
**Relevance:** The only other work explicitly combining quines with LLMs. Applied to blockchain security rather than agent orchestration. **Quality concern:** poorly written with unsupported claims (e.g., "since the suggested approach is novel, the proposed solution is also novel"). Not rigorous enough to cite as serious related work; note only as evidence that the quine+LLM combination is otherwise unexplored.

#### Godel Agent
**Title:** Godel Agent: A Self-Referential Agent Framework for Recursive Self-Improvement
**Authors:** Xunjian Yin, Xinyi Wang, Liangming Pan, Li Lin, Xiaojun Wan, William Yang Wang
**Date:** 2024-10-06
**Venue/Source:** ACL 2025 (arXiv 2410.04444)
**URL:** https://arxiv.org/abs/2410.04444
**Relevance:** Self-referential agent that dynamically modifies its own logic via LLM-guided "monkey patching." Inspired by Godel Machines. Addresses self-modification and recursive self-improvement, though through prompt rewriting rather than programmatic self-reference (quines).

#### Darwin Godel Machine
**Title:** Darwin Godel Machine: Open-Ended Evolution of Self-Improving Agents
**Authors:** Jenny Zhang, Shengran Hu, Cong Lu, Robert Lange, Jeff Clune
**Date:** 2025-05
**Venue/Source:** arXiv (2505.22954)
**URL:** https://arxiv.org/abs/2505.22954
**Relevance:** Self-improving agent that reads and modifies its own Python codebase using evolutionary search. Improves from 20% to 50% on SWE-bench. Extension of Godel Agent to open-ended evolution.

#### Huxley-Godel Machine
**Title:** Huxley-Godel Machine: Human-Level Coding Agent Development by an Approximation of the Optimal Self-Improving Machine
**Authors:** Wenyi Wang, Piotr Piekos, Li Nanbo, Firas Laakom, Yimeng Chen, Mateusz Ostaszewski, Mingchen Zhuge, Jurgen Schmidhuber
**Date:** 2025-10-24
**Venue/Source:** arXiv (2510.21614)
**URL:** https://arxiv.org/abs/2510.21614
**Relevance:** Practical Godel Machine implementation for coding agents. Searches a tree of self-modifications guided by a metaproductivity metric. Connects foundational self-referential theory (Schmidhuber) to practical LLM agent systems.

#### Godel Machines (foundational)
**Title:** Goedel Machines: Self-Referential Universal Problem Solvers Making Provably Optimal Self-Improvements
**Authors:** Jurgen Schmidhuber
**Date:** 2003-09-25
**Venue/Source:** arXiv (cs/0309048); Artificial General Intelligence (Springer, 2007)
**URL:** https://arxiv.org/abs/cs/0309048
**Relevance:** Foundational theoretical work on self-referential self-improving machines. A Godel Machine rewrites any part of its own code when it proves the rewrite is useful. Essential citation for theoretical grounding of self-programmed execution.

#### STOP
**Title:** Self-Taught Optimizer (STOP): Recursively Self-Improving Code Generation
**Authors:** Eric Zelikman, Eliana Lorch, Lester Mackey, Adam Tauman Kalai
**Date:** 2023-10-03
**Venue/Source:** COLM 2024
**URL:** https://arxiv.org/abs/2310.02304
**Relevance:** Scaffolding program recursively improves itself using a fixed LLM. The improved improver proposes optimization strategies like beam search and genetic algorithms. Directly relevant: the program modifies its own execution strategy.

#### ADAS
**Title:** Automated Design of Agentic Systems
**Authors:** Shengran Hu, Cong Lu, Jeff Clune
**Date:** 2024-08-15
**Venue/Source:** ICLR 2025 (arXiv 2408.08435)
**URL:** https://arxiv.org/abs/2408.08435
**Relevance:** Meta Agent Search iteratively programs new agents in code. Key argument: programming languages are Turing Complete, so this theoretically enables learning any agent architecture. Directly addresses automated agent design through code generation.

#### SICA
**Title:** A Self-Improving Coding Agent
**Authors:** Maxime Robeyns, Martin Szummer, Laurence Aitchison
**Date:** 2025-04-21
**Venue/Source:** arXiv (2504.15228), submitted to NeurIPS 2025
**URL:** https://arxiv.org/abs/2504.15228
**Relevance:** Agent autonomously modifies its own codebase to enhance performance (17% to 53% on SWE-Bench Verified). Demonstrates self-improvement via editing own codebase without a DSL. Key finding: limits exist for purely scaffolding-based self-improvement without weight changes.

#### Practical self-improving agents
**Title:** From Language Models to Practical Self-Improving Computer Agents
**Authors:** Alex Sheng
**Date:** 2024-04-18
**Venue/Source:** arXiv (2404.11964)
**URL:** https://arxiv.org/abs/2404.11964
**Relevance:** LLM agent with terminal access uses code generation to develop software augmenting its own capabilities, creating recursively self-improving agents. Starting from minimal tools, the agent self-bootstraps retrieval, web navigation, and editing capabilities.

#### Intrinsic metacognition
**Title:** Truly Self-Improving Agents Require Intrinsic Metacognitive Learning
**Authors:** Tennison Liu, Mihaela van der Schaar
**Date:** 2025-05-01
**Venue/Source:** ICML 2025 Position Paper (arXiv 2506.05109)
**URL:** https://arxiv.org/abs/2506.05109
**Relevance:** Argues current self-improving agents rely on extrinsic metacognitive mechanisms (fixed human-designed loops). True self-improvement requires intrinsic metacognition. Directly relevant to Spell's argument that the agent should program its own execution.

#### Promptbreeder
**Title:** Promptbreeder: Self-Referential Self-Improvement Via Prompt Evolution
**Authors:** Chrisantha Fernando, Dylan Banarse, Henryk Michalewski, Simon Osindero, Tim Rocktaschel
**Date:** 2023-09-28
**Venue/Source:** ICML 2024
**URL:** https://arxiv.org/abs/2309.16797
**Relevance:** Self-referential system that evolves both task-prompts and mutation-prompts. The self-referential meta-level is analogous to Spell's quine mechanism, though operates at the prompt string level rather than programmatically.

#### Language Model Cascades
**Title:** Language Model Cascades
**Authors:** David Dohan, Winnie Xu, Aitor Lewkowycz, Jacob Austin, David Bieber, et al.
**Date:** 2022-07-21
**Venue/Source:** ICML 2022 Beyond Bases Workshop (spotlight)
**URL:** https://arxiv.org/abs/2207.10342
**Relevance:** Frames LLM compositions as probabilistic programs with random variables over strings. Formalizes chain-of-thought, verifiers, tool use as programs. Theoretical precursor to treating LLM execution as programmable; Spell can be seen as a concrete realization of this vision.

---

### DSLs and Frameworks for LLM Orchestration

#### LMQL
**Title:** Prompting Is Programming: A Query Language for Large Language Models
**Authors:** Luca Beurer-Kellner, Marc Fischer, Martin Vechev
**Date:** 2022-12-12
**Venue/Source:** PLDI 2023
**URL:** https://arxiv.org/abs/2212.06094
**Relevance:** Combines NL prompting with Python expressiveness for constrained LLM interaction. Treats prompting as a PL design problem. 26-85% cost savings. Key DSL comparison point.

#### DSPy
**Title:** DSPy: Compiling Declarative Language Model Calls into Self-Improving Pipelines
**Authors:** Omar Khattab et al.
**Date:** 2023-10
**Venue/Source:** ICLR 2024
**URL:** https://arxiv.org/abs/2310.03714
**Relevance:** Abstracts LM pipelines as text transformation graphs with declarative modules and automatic optimization. Key contrast: DSPy is declarative/compiled vs. Spell's self-programmed execution.

#### SGLang
**Title:** SGLang: Efficient Execution of Structured Language Model Programs
**Authors:** Lianmin Zheng, Liangsheng Yin, Zhiqiang Xie, et al.
**Date:** 2023-12
**Venue/Source:** NeurIPS 2024
**URL:** https://arxiv.org/abs/2312.07104
**Relevance:** Execution engine for structured LM programs with primitives for generation and parallelism. RadixAttention for KV cache reuse. Up to 6.4x throughput. Relevant as an execution engine, though focused on efficiency rather than self-programming.

#### CodeAct
**Title:** Executable Code Actions Elicit Better LLM Agents
**Authors:** Xingyao Wang, Yangyi Chen, Lifan Yuan, Yizhe Zhang, Yunzhu Li, Hao Peng, Heng Ji
**Date:** 2024-02-01
**Venue/Source:** ICML 2024
**URL:** https://arxiv.org/abs/2402.01030
**Relevance:** Code as the unified action space for LLM agents. Up to 20% improvement over JSON/text actions. Directly relevant: code-as-action paradigm, though uses an external interpreter rather than self-programmed execution.

#### Declarative agent workflow language
**Title:** A Declarative Language for Building And Orchestrating LLM-Powered Agent Workflows
**Authors:** Ivan Daunis
**Date:** 2025-12-22
**Venue/Source:** arXiv (2512.19769)
**URL:** https://arxiv.org/abs/2512.19769
**Relevance:** DSL for agent workflows achieving 60% dev time reduction at PayPal. Key contrast with Spell: human-authored declarative DSL vs. self-programmed by the LLM.

#### ADL
**Title:** ADL: A Declarative Language for Agent-Based Chatbots
**Authors:** Sirui Zeng, Xifeng Yan
**Date:** 2025-04-21
**Venue/Source:** arXiv (2504.14787)
**URL:** https://arxiv.org/abs/2504.14787
**Relevance:** Declarative agent language defining what agents are and how they interact, with NL programming at its core. Comparison point for language design decisions.

#### StateFlow
**Title:** StateFlow: Enhancing LLM Task-Solving through State-Driven Workflows
**Authors:** Yiran Wu, Tianwei Yue, Shaokun Zhang, Chi Wang, Qingyun Wu
**Date:** 2024-03-17
**Venue/Source:** arXiv (2403.11322)
**URL:** https://arxiv.org/abs/2403.11322
**Relevance:** Conceptualizes LLM task-solving as state machines. 13-28% improvement over ReAct with 3-5x less cost. Contrast: Spell uses self-programmed execution rather than pre-defined state machines.

#### Meta-Prompting
**Title:** Meta-Prompting: Enhancing Language Models with Task-Agnostic Scaffolding
**Authors:** Mirac Suzgun, Adam Tauman Kalai
**Date:** 2024-01-23
**Venue/Source:** arXiv (2401.12954)
**URL:** https://arxiv.org/abs/2401.12954
**Relevance:** Single LLM acts as conductor orchestrating multiple expert instances. The LLM decides its own orchestration. 17% improvement. The "LLM decides its own orchestration" aspect directly relates to self-programmed execution.

#### AFlow
**Title:** AFlow: Automating Agentic Workflow Generation
**Authors:** Jiayi Zhang et al.
**Date:** 2024-10-14
**Venue/Source:** ICLR 2025 Oral (arXiv 2410.10762)
**URL:** https://arxiv.org/abs/2410.10762
**Relevance:** Automated workflow search using MCTS over code-represented workflows. Smaller models outperform GPT-4o at 4.55% cost. Contrast: AFlow searches workflow space externally; Spell has the LLM program its own workflow within the completion.

#### Conductor
**Title:** Learning to Orchestrate Agents in Natural Language with the Conductor
**Authors:** Stefan Nielsen, Edoardo Cetin, Peter Schwendeman, Qi Sun, Jinglue Xu, Yujin Tang
**Date:** 2025-12-04
**Venue/Source:** ICLR 2026 (arXiv 2512.04388)
**URL:** https://arxiv.org/abs/2512.04388
**Relevance:** RL-trained 7B Conductor that outputs full agentic workflows including task division, subtask allocation, and communication topologies. Achieves SOTA on LiveCodeBench and GPQA. Contrast: learned external orchestrator vs. Spell's in-completion self-orchestration.

---

### Context Management for LLMs

#### Agentic Context Engineering (ACE)
**Title:** Agentic Context Engineering: Evolving Contexts for Self-Improving Language Models
**Authors:** Qizheng Zhang, Changran Hu, Shubhangi Upasani, Boyuan Ma, et al.
**Date:** 2025-10-06
**Venue/Source:** ICLR 2026 (arXiv 2510.04618)
**URL:** https://arxiv.org/abs/2510.04618
**Relevance:** Treats contexts as evolving playbooks through generation/reflection/curation. Addresses "context collapse." Directly relevant to Spell's think/prune/rethink/extend primitives. +10.6% on agents.

#### MemAct
**Title:** Memory as Action: Autonomous Context Curation for Long-Horizon Agentic Tasks
**Authors:** Yuxiang Zhang, Jiangming Shu, Ye Ma, Xueyuan Lin, Shangxi Wu, Jitao Sang
**Date:** 2025-10-14
**Venue/Source:** arXiv (2510.12635)
**URL:** https://arxiv.org/abs/2510.12635
**Relevance:** Formulates working memory management as learnable policy actions (deletion, insertion) via end-to-end RL. MemAct-RL-14B matches 16x larger models while reducing context by 51%. Closest to Spell's prune/rethink/extend among RL-trained systems.

#### MemGPT
**Title:** MemGPT: Towards LLMs as Operating Systems
**Authors:** Charles Packer, Sarah Wooders, Kevin Lin, Vivian Fang, Shishir G. Patil, Joseph E. Gonzalez
**Date:** 2023-10-12
**Venue/Source:** arXiv (2310.08560)
**URL:** https://arxiv.org/abs/2310.08560
**Relevance:** Seminal work on virtual context management with OS-inspired memory hierarchies. LLM manages its own memory tiers with self-editing capabilities. Precursor to Spell's own-context management, though uses tool calls rather than a programming language.

#### ACON
**Title:** ACON: Optimizing Context Compression for Long-horizon LLM Agents
**Authors:** Minki Kang, Wei-Ning Chen, Dongge Han, et al.
**Date:** 2025-10-01
**Venue/Source:** arXiv (2510.00615)
**URL:** https://arxiv.org/abs/2510.00615
**Relevance:** Unified framework for compressing environment observations and interaction histories. Guideline optimization via failure analysis. 26-54% memory reduction while preserving task performance.

#### COMPASS
**Title:** COMPASS: Enhancing Agent Long-Horizon Reasoning with Evolving Context
**Authors:** Guangya Wan, Mingyang Ling, Xiaoqi Ren, et al.
**Date:** 2025-10-09
**Venue/Source:** Under review at ACL (arXiv 2510.08790)
**URL:** https://arxiv.org/abs/2510.08790
**Relevance:** Separates tactical execution, strategic oversight, and context organization. Context Manager maintains concise progress briefs. Up to 20% accuracy improvement. Directly relevant to Spell's separation of concerns in context management.

#### Cognitive Workspace
**Title:** Cognitive Workspace: Active Memory Management for LLMs — An Empirical Study of Functional Infinite Context
**Authors:** Tao An
**Date:** 2025-08-08
**Venue/Source:** arXiv (2508.13171)
**URL:** https://arxiv.org/abs/2508.13171
**Relevance:** Grounded in cognitive science (Baddeley's working memory model). Active memory management with deliberate information curation and hierarchical cognitive buffers. 58.6% memory reuse rate vs. 0% for RAG.

#### Active Context Compression (Focus)
**Title:** Active Context Compression: Autonomous Memory Management in LLM Agents
**Authors:** Nikhil Verma
**Date:** 2026-01-12
**Venue/Source:** arXiv (2601.07190)
**URL:** https://arxiv.org/abs/2601.07190
**Relevance:** "Focus" agent autonomously consolidates key learnings into a persistent "Knowledge" block and prunes raw interaction history. 22.7% token reduction at identical accuracy. Addresses same "Context Bloat" problem as Spell's prune/rethink.

#### Laser
**Title:** Laser: Governing Long-Horizon Agentic Search via Structured Protocol and Context Register
**Authors:** Shuting Wang, Qiaolin Xia, Vich Wang, et al.
**Date:** 2025-12-23
**Venue/Source:** arXiv (2512.20458)
**URL:** https://arxiv.org/abs/2512.20458
**Relevance:** Symbolic action protocol with compact context register storing only essential states. Structured approach to context management comparable to Spell's namespaces and context primitives.

#### Dynamic Cheatsheet
**Title:** Dynamic Cheatsheet: Test-Time Learning with Adaptive Memory
**Authors:** Mirac Suzgun, Mert Yuksekgonul, Federico Bianchi, Dan Jurafsky, James Zou
**Date:** 2025-04-10
**Venue/Source:** EACL 2026
**URL:** https://arxiv.org/abs/2504.07952
**Relevance:** Persistent, self-curating external memory that stores and reuses accumulated strategies. Doubles Claude 3.5 Sonnet accuracy on AIME. Another approach to LLM-managed persistent context.

#### Context rot (empirical)
**Title:** Context Rot: How Increasing Input Tokens Impacts LLM Performance
**Authors:** Kelly Hong, Anton Troynikov, Jeff Huber
**Date:** 2025-07-14
**Venue/Source:** Chroma Technical Report
**URL:** https://research.trychroma.com/context-rot
**Relevance:** Comprehensive study of 18 frontier models showing universal performance degradation as input grows. Every model exhibits context rot. Key supporting evidence for why active context management (Spell's approach) matters.

#### Context length alone hurts
**Title:** Context Length Alone Hurts LLM Performance Despite Perfect Retrieval
**Authors:** Yufeng Du, Minyang Tian, et al.
**Date:** 2025-10-06
**Venue/Source:** Findings of EMNLP 2025
**URL:** https://arxiv.org/abs/2510.05381
**Relevance:** Sheer input length degrades performance 13.9-85% even with perfect retrieval and no distractors. Directly supports Spell's design rationale for context management.

#### Lost in the Middle
**Title:** Lost in the Middle: How Language Models Use Long Contexts
**Authors:** Nelson F. Liu, Kevin Lin, John Hewitt, et al.
**Date:** 2023-07-06
**Venue/Source:** TACL 2024
**URL:** https://arxiv.org/abs/2307.03172
**Relevance:** Seminal paper: LLM performance degrades when relevant info is in the middle of long contexts. Foundational motivation for active context management.

#### Proactive interference in LLMs
**Title:** Unable to Forget: Proactive Interference Reveals Working Memory Limits in LLMs Beyond Context Length
**Authors:** Chupei Wang, Jiaqiu Vince Sun
**Date:** 2025-06-09
**Venue/Source:** ICML 2025 Workshop (arXiv 2506.08184)
**URL:** https://arxiv.org/abs/2506.08184
**Relevance:** Retrieval accuracy declines log-linearly as interference accumulates — errors from retrieving previously overwritten values. Reveals fundamental working memory bottleneck beyond context length, precisely what Spell's prune/rethink operations address.

#### Observation masking vs summarization
**Title:** The Complexity Trap: Simple Observation Masking Is as Efficient as LLM Summarization for Agent Context Management
**Authors:** Tobias Lindenbauer, Igor Slinko, Ludwig Felder, Egor Bogomolov, Yaroslav Zharov
**Date:** 2025-08-29
**Venue/Source:** NeurIPS 2025 DL4C Workshop
**URL:** https://arxiv.org/abs/2508.21433
**Relevance:** Simple observation masking matches LLM summarization in solve rate while halving cost. Challenges the assumption that sophisticated compression is always needed. Important baseline for Spell's context management approach.

#### Context window overflow
**Title:** Solving Context Window Overflow in AI Agents
**Authors:** Anton Bulle Labate, et al.
**Date:** 2025-11-27
**Venue/Source:** arXiv (2511.22729)
**URL:** https://arxiv.org/abs/2511.22729
**Relevance:** Shifts model interaction from raw data to memory pointers, enabling arbitrary-length tool responses. Alternative approach using indirection (pointers) rather than pruning/compression.

#### Scratchpads
**Title:** Show Your Work: Scratchpads for Intermediate Computation with Language Models
**Authors:** Maxwell Nye, Anders Johan Andreassen, et al.
**Date:** 2021-11-30
**Venue/Source:** arXiv (2112.00114)
**URL:** https://arxiv.org/abs/2112.00114
**Relevance:** Foundational: LMs perform complex multi-step computations with a "scratchpad" for intermediate results. Establishes that LLMs benefit from explicit working memory in context — precursor to Spell's approach.

#### Self-Notes
**Title:** Learning to Reason and Memorize with Self-Notes
**Authors:** Jack Lanchantin, Shubham Toshniwal, Jason Weston, Arthur Szlam, Sainbayar Sukhbaatar
**Date:** 2023-05-01
**Venue/Source:** arXiv (2305.00833)
**URL:** https://arxiv.org/abs/2305.00833
**Relevance:** LMs "deviate from input context at any time to explicitly think and write down thoughts." Self-notes act as both reasoning steps and working memory. Conceptually related to Spell's `think` form.

#### LLMLingua
**Title:** LLMLingua: Compressing Prompts for Accelerated Inference of Large Language Models
**Authors:** Huiqiang Jiang, Qianhui Wu, Chin-Yew Lin, et al.
**Date:** 2023-10-09
**Venue/Source:** EMNLP 2023
**URL:** https://arxiv.org/abs/2310.05736
**Relevance:** Foundational prompt compression: up to 20x compression with minimal loss using a small LM to identify unimportant tokens. Primary external-compressor approach that Spell's self-managed context model contrasts with.

#### ReWOO
**Title:** ReWOO: Decoupling Reasoning from Observations for Efficient Augmented Language Models
**Authors:** Binfeng Xu, Zhiyuan Peng, et al.
**Date:** 2023-05-23
**Venue/Source:** arXiv (2305.18323)
**URL:** https://arxiv.org/abs/2305.18323
**Relevance:** Decouples reasoning from observations for 5x token efficiency. Separates planning from tool execution to avoid redundant context accumulation.

#### Context engineering survey
**Title:** A Survey of Context Engineering for Large Language Models
**Authors:** Lingrui Mei, Jiayu Yao, et al. (15 authors)
**Date:** 2025-07-17
**Venue/Source:** arXiv (2507.13334), 166 pages
**URL:** https://arxiv.org/abs/2507.13334
**Relevance:** Comprehensive survey (1400+ papers analyzed) establishing Context Engineering as a formal discipline. Essential reference for positioning Spell within the broader field.

#### Prompt compression survey
**Title:** Prompt Compression for Large Language Models: A Survey
**Authors:** Zongqian Li, Yinhong Liu, Yixuan Su, Nigel Collier
**Date:** 2024-10-16
**Venue/Source:** NAACL 2025 (Selected Oral)
**URL:** https://arxiv.org/abs/2410.12388
**Relevance:** Comprehensive survey of prompt compression techniques. Context for understanding the landscape that Spell's design avoids by giving the LLM direct control.

#### Memory survey
**Title:** Memory in the Age of AI Agents
**Authors:** Yuyang Hu, Shichun Liu, et al. (47+ authors)
**Date:** 2025-12-15
**Venue/Source:** arXiv (2512.13564)
**URL:** https://arxiv.org/abs/2512.13564
**Relevance:** Comprehensive survey organizing agent memory by forms, functions, and dynamics. Useful for positioning Spell's in-context memory model.

#### Agent memory survey
**Title:** A Survey on the Memory Mechanism of Large Language Model based Agents
**Authors:** Zeyu Zhang, Xiaohe Bo, et al.
**Date:** 2024-04-21
**Venue/Source:** ACM TOIS
**URL:** https://arxiv.org/abs/2404.13501
**Relevance:** Comprehensive survey distinguishing short-term/working memory from long-term memory types. Useful for taxonomic positioning.

---

### Multi-Agent LLM Systems

#### Recursive Language Models
**Title:** Recursive Language Models
**Authors:** Alex L. Zhang, Tim Kraska, Omar Khattab
**Date:** 2025-12-31
**Venue/Source:** arXiv (2512.24601)
**URL:** https://arxiv.org/abs/2512.24601
**Relevance:** The most directly comparable paradigm to Spell. RLMs treat the prompt as an external environment and let the LLM programmatically decompose and recursively call itself via a Python REPL. Process inputs 2 orders of magnitude beyond context windows. Already discussed in paper.md Related Work.

#### Kimi K2.5 / PARL
**Title:** Kimi K2.5: Visual Agentic Intelligence
**Authors:** Kimi Team (324+ authors)
**Date:** 2026-02
**Venue/Source:** arXiv (2602.02276)
**URL:** https://arxiv.org/abs/2602.02276
**Relevance:** Introduces PARL (Parallel-Agent Reinforcement Learning) for training orchestrator to spawn and coordinate up to 100 sub-agents. Already discussed in paper.md Related Work. This is the technical report with full details.

#### Kimi K2
**Title:** Kimi K2 Technical Report: Open Agentic Intelligence
**Authors:** Kimi Team (Moonshot AI)
**Date:** 2025-07
**Venue/Source:** Technical report
**URL:** https://moonshotai.github.io/Kimi-K2/
**Relevance:** 1T parameter MoE (32B active) with large-scale agentic training. Handles 200-300 sequential tool calls without losing track. Architecturally interesting for long-horizon agent execution.

#### AgentOrchestra
**Title:** AgentOrchestra: Orchestrating Multi-Agent Intelligence with the TEA Protocol
**Authors:** Wentao Zhang, Liang Zeng, et al.
**Date:** 2025-06
**Venue/Source:** arXiv (2506.12508)
**URL:** https://arxiv.org/abs/2506.12508
**Relevance:** Hierarchical multi-agent framework with dynamic tool creation and unified lifecycle management. Achieves SOTA on GAIA (89.04%).

#### MegaAgent
**Title:** MegaAgent: A Large-Scale Autonomous LLM-based Multi-Agent System Without Predefined SOPs
**Authors:** Qian Wang, Tianyu Wang, et al.
**Date:** 2024-08
**Venue/Source:** ACL 2025 Findings (arXiv 2408.09955)
**URL:** https://arxiv.org/abs/2408.09955
**Relevance:** Dynamic agent generation and hierarchical decomposition without predefined SOPs, scaling to 590 agents. Contrast: Spell also avoids predefined SOPs but via self-programming.

#### MacNet scaling law
**Title:** Scaling Large Language Model-based Multi-Agent Collaboration
**Authors:** Chen Qian, Zihao Xie, et al.
**Date:** 2024-06
**Venue/Source:** ICLR 2025 (arXiv 2406.07155)
**URL:** https://arxiv.org/abs/2406.07155
**Relevance:** DAG topologies for multi-agent collaboration. Discovers small-world collaboration phenomenon and collaborative scaling law. Supports 1000+ agents.

#### Multi-agent debate
**Title:** Improving Factuality and Reasoning in Language Models through Multiagent Debate
**Authors:** Yilun Du, Shuang Li, Antonio Torralba, Joshua B. Tenenbaum, Igor Mordatch
**Date:** 2023-05
**Venue/Source:** ICML 2024
**URL:** https://arxiv.org/abs/2305.14325
**Relevance:** Seminal multi-agent debate paper. Multiple LLM instances propose and debate over multiple rounds. Foundational reference for multi-agent LLM collaboration.

#### MetaGPT
**Title:** MetaGPT: Meta Programming for A Multi-Agent Collaborative Framework
**Authors:** Sirui Hong et al.
**Date:** 2023-08-01
**Venue/Source:** ICLR 2024 (Oral)
**URL:** https://arxiv.org/abs/2308.00352
**Relevance:** Multi-agent framework encoding SOPs into prompt sequences for software development. Seminal multi-agent coding framework. Contrast: Spell self-orchestrates rather than following predefined SOPs.

#### ChatDev
**Title:** ChatDev: Communicative Agents for Software Development
**Authors:** Chen Qian, Wei Liu, et al.
**Date:** 2023-07
**Venue/Source:** ACL 2024
**URL:** https://arxiv.org/abs/2307.07924
**Relevance:** Chat-based multi-agent software development with specialized roles communicating via NL. Demonstrates linguistic communication as the coordination medium.

#### Generative Agents
**Title:** Generative Agents: Interactive Simulacra of Human Behavior
**Authors:** Joon Sung Park, Joseph C. O'Brien, et al.
**Date:** 2023-04
**Venue/Source:** UIST 2023
**URL:** https://arxiv.org/abs/2304.03442
**Relevance:** 25 LLM-powered agents forming social behaviors, coordinating activities, and maintaining memory/reflection. Seminal multi-agent simulation work demonstrating emergent behavior.

#### AutoGen
**Title:** AutoGen: Enabling Next-Gen LLM Applications via Multi-Agent Conversation
**Authors:** Qingyun Wu et al.
**Date:** 2023-08-16
**Venue/Source:** COLM 2024 (arXiv 2308.08155)
**URL:** https://arxiv.org/abs/2308.08155
**Relevance:** Leading multi-agent conversation framework. Represents the "external harness" approach that Spell positions against.

#### Multi-agent survey
**Title:** Large Language Model based Multi-Agents: A Survey of Progress and Challenges
**Authors:** Taicheng Guo, Xiuying Chen, et al.
**Date:** 2024-01
**Venue/Source:** IJCAI 2024
**URL:** https://arxiv.org/abs/2402.01680
**Relevance:** Comprehensive survey of LLM-based multi-agent systems covering profiling, communication, and capability acquisition.

#### Multi-agent communication survey
**Title:** Beyond Self-Talk: A Communication-Centric Survey of LLM-Based Multi-Agent Systems
**Authors:** Bingyu Yan, et al.
**Date:** 2025-02
**Venue/Source:** arXiv (2502.14321)
**URL:** https://arxiv.org/abs/2502.14321
**Relevance:** Survey focused on inter-agent communication protocols, strategies, and paradigms. Directly relevant to Spell's `agents/!ask` and `agents/send` primitives.

#### Self-evolving agents survey
**Title:** A Comprehensive Survey of Self-Evolving AI Agents
**Authors:** Jinyuan Fang, Yanwen Peng, et al.
**Date:** 2025-08
**Venue/Source:** arXiv (2508.07407)
**URL:** https://arxiv.org/abs/2508.07407
**Relevance:** Unified framework for self-evolving agents covering evolution of prompts, memory, tools, workflows, and communication. Spell is a strong instance of this category.

---

### RL for Agent Orchestration

#### ToolOrchestra
**Title:** ToolOrchestra: Elevating Intelligence via Efficient Model and Tool Orchestration
**Authors:** Hongjin Su, Shizhe Diao, et al. (NVIDIA Research)
**Date:** 2025-11
**Venue/Source:** arXiv (2511.21689)
**URL:** https://arxiv.org/abs/2511.21689
**Relevance:** RL-trained 8B orchestrator routing across tools and LLMs. Uses GRPO with outcome, efficiency, and preference rewards. Outperforms GPT-5 on HLE at 2.5x lower cost.

#### Agent-R1
**Title:** Agent-R1: Training Powerful LLM Agents with End-to-End Reinforcement Learning
**Authors:** Mingyue Cheng, Jie Ouyang, et al.
**Date:** 2025-11
**Venue/Source:** arXiv (2511.14460)
**URL:** https://arxiv.org/abs/2511.14460
**Relevance:** Extends MDP framework for multi-turn tool-calling LLM agents with end-to-end RL. Supports PPO, GRPO, REINFORCE++ across multi-tool coordination.

#### VerlTool
**Title:** VerlTool: Towards Holistic Agentic Reinforcement Learning with Tool Use
**Authors:** Dongfu Jiang, Yi Lu, et al.
**Date:** 2025-09
**Venue/Source:** ICLR 2026 Workshop (arXiv 2509.01055)
**URL:** https://arxiv.org/abs/2509.01055
**Relevance:** Unified framework for ARLT across math, QA, SQL, visual reasoning, web search, and SWE.

#### WebAgent-R1
**Title:** WebAgent-R1: Training Web Agents via End-to-End Multi-Turn Reinforcement Learning
**Authors:** Zhepei Wei, Wenlin Yao, et al.
**Date:** 2025-05
**Venue/Source:** EMNLP 2025 (arXiv 2505.16421)
**URL:** https://arxiv.org/abs/2505.16421
**Relevance:** End-to-end multi-turn RL for web agents with binary success rewards. Boosts Qwen-2.5-3B from 6.1% to 33.9% on WebArena-Lite.

#### MARSHAL
**Title:** MARSHAL: Incentivizing Multi-Agent Reasoning via Self-Play with Strategic LLMs
**Authors:** Huining Yuan, et al.
**Date:** 2025-10
**Venue/Source:** arXiv (2510.15414)
**URL:** https://arxiv.org/abs/2510.15414
**Relevance:** RL self-play for multi-agent reasoning. Generalizes to benchmarks (+10% on AIME). Demonstrates RL for multi-agent coordination.

#### SWE-RL self-play
**Title:** Toward Training Superintelligent Software Agents through Self-Play SWE-RL
**Authors:** Yuxiang Wei, Zhiqing Sun, et al.
**Date:** 2025-12
**Venue/Source:** arXiv (2512.18552)
**URL:** https://arxiv.org/abs/2512.18552
**Relevance:** Self-play RL where single LLM alternates between bug injector and solver. Demonstrates self-play as alternative training paradigm for software agents.

#### SAGE
**Title:** SAGE: Multi-Agent Self-Evolution for LLM Reasoning
**Authors:** Yulin Peng, Xinxin Zhu, et al.
**Date:** 2026-03
**Venue/Source:** arXiv (2603.15255)
**URL:** https://arxiv.org/abs/2603.15255
**Relevance:** Four-agent co-evolution framework using RL with verifiable rewards. +8.9% on LiveCodeBench, +10.7% on OlympiadBench.

#### RISE
**Title:** Recursive Introspection (RISE): Teaching Language Model Agents How to Self-Improve
**Authors:** Yuxiao Qu, Tianjun Zhang, et al.
**Date:** 2024-07
**Venue/Source:** NeurIPS 2024
**URL:** https://arxiv.org/abs/2407.18219
**Relevance:** RL-based fine-tuning for iterative self-correction across multiple turns. Relevant to Spell's rethink/extend self-correction mechanisms.

---

### Agentic Coding Systems

#### SWE-bench
**Title:** SWE-bench: Can Language Models Resolve Real-World GitHub Issues?
**Authors:** Carlos E. Jimenez, John Yang, Alexander Wettig, Shunyu Yao, Kexin Pei, Ofir Press, Karthik Narasimhan
**Date:** 2023-10
**Venue/Source:** ICLR 2024
**URL:** https://arxiv.org/abs/2310.06770
**Relevance:** The foundational benchmark for coding agents. Spell benchmarks against SWE-bench.

#### SWE-agent
**Title:** SWE-agent: Agent-Computer Interfaces Enable Automated Software Engineering
**Authors:** John Yang, Carlos E. Jimenez, et al.
**Date:** 2024-05
**Venue/Source:** NeurIPS 2024
**URL:** https://arxiv.org/abs/2405.15793
**Relevance:** Introduced the agent-computer interface (ACI) concept. Key comparison point for Spell's tool/environment interaction.

#### OpenHands
**Title:** OpenHands: An Open Platform for AI Software Developers as Generalist Agents
**Authors:** Xingyao Wang, Boxuan Li, et al.
**Date:** 2024-07
**Venue/Source:** ICLR 2025
**URL:** https://arxiv.org/abs/2407.16741
**Relevance:** Open-source agent platform achieving 53% on SWE-bench. First open agent to pass 50%.

#### Agentless
**Title:** Agentless: Demystifying LLM-based Software Engineering Agents
**Authors:** Chunqiu Steven Xia, Yinlin Deng, Soren Dunn, Lingming Zhang
**Date:** 2024-07
**Venue/Source:** ACM SIGSOFT FSE 2025
**URL:** https://arxiv.org/abs/2407.01489
**Relevance:** Simple three-phase approach (localize, repair, validate) achieves competitive SWE-bench at $0.70/issue. Important contrast to complex agent architectures.

#### Aider Polyglot (Exercism)
**Title:** Aider Polyglot Benchmark
**Authors:** Paul Gauthier
**Date:** 2024-12
**Venue/Source:** Blog / open-source
**URL:** https://aider.chat/2024/12/21/polyglot.html
**Relevance:** The Exercism-based benchmark Spell evaluates against. 225 exercises across 6 languages.

#### Competitive Programming with LRMs
**Title:** Competitive Programming with Large Reasoning Models
**Authors:** OpenAI (Ahmed El-Kishky, et al.)
**Date:** 2025-02
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2502.06807
**Relevance:** o3 achieves IOI gold and 99.8th percentile Codeforces. General-purpose RL reasoning beats domain-specific strategies. o3 writes and executes verification code.

---

### LLM + Code Execution for Reasoning

#### PAL
**Title:** PAL: Program-aided Language Models
**Authors:** Luyu Gao, Aman Madaan, et al.
**Date:** 2022-11-18
**Venue/Source:** ICML 2023
**URL:** https://arxiv.org/abs/2211.10435
**Relevance:** Seminal paper on offloading computation to a Python interpreter. Directly establishes the paradigm Spell extends. 15% over CoT on GSM8K.

#### Program of Thoughts
**Title:** Program of Thoughts Prompting: Disentangling Computation from Reasoning
**Authors:** Wenhu Chen, Xueguang Ma, Xinyi Wang, William W. Cohen
**Date:** 2022-11-22
**Venue/Source:** TMLR 2023
**URL:** https://arxiv.org/abs/2211.12588
**Relevance:** Concurrent with PAL. ~12% average improvement over CoT. Establishes the principle that LLMs should reason while code computes — a core Spell design tenet.

#### ToRA
**Title:** ToRA: A Tool-Integrated Reasoning Agent for Mathematical Problem Solving
**Authors:** Zhibin Gou, Zhihong Shao, et al.
**Date:** 2023-09
**Venue/Source:** ICLR 2024
**URL:** https://arxiv.org/abs/2309.17452
**Relevance:** Interleaves NL reasoning with Python/SymPy tool calls. ToRA-7B reached 44.6% on MATH, surpassing WizardMath-70B by 22%. Most directly comparable to Spell's code-execution approach.

#### MathCoder
**Title:** MathCoder: Seamless Code Integration in LLMs for Enhanced Mathematical Reasoning
**Authors:** Ke Wang, Houxing Ren, et al.
**Date:** 2023-10
**Venue/Source:** ICLR 2024
**URL:** https://arxiv.org/abs/2310.03731
**Relevance:** Training data interleaves NL reasoning, code, and execution results. SOTA on MATH (45.2%) for open-source models. Key precedent for Spell's interleaved execution model.

#### Code-based self-verification
**Title:** Solving Challenging Math Word Problems Using GPT-4 Code Interpreter with Code-based Self-Verification
**Authors:** Aojun Zhou, Ke Wang, et al.
**Date:** 2023-08
**Venue/Source:** ICLR 2024
**URL:** https://arxiv.org/abs/2308.07921
**Relevance:** MATH accuracy jumped from 53.9% to 84.3% using code-based self-verification. Code execution for verification (not just computation) is a major improvement source. Directly relevant to Spell's execution-based advantage.

#### Natural Language Embedded Programs
**Title:** Natural Language Embedded Programs for Hybrid Language Symbolic Reasoning
**Authors:** Tianhua Zhang, Jiaxin Ge, et al.
**Date:** 2023-09
**Venue/Source:** NAACL 2024
**URL:** https://arxiv.org/abs/2309.10814
**Relevance:** Inverts the typical paradigm: embeds NL knowledge inside executable Python programs. Conceptually close to Spell where the entire completion is a program containing NL.

#### SymCode
**Title:** SymCode: A Neurosymbolic Approach to Mathematical Reasoning via Verifiable Code Generation
**Authors:** Sina Bagheri Nezhad, Yao Li, Ameeta Agrawal
**Date:** 2025-10
**Venue/Source:** EACL 2026 Findings
**URL:** https://arxiv.org/abs/2510.25975
**Relevance:** SymPy for deterministic symbolic computation. Up to 13.6pp improvement. Shifts failures from opaque reasoning errors to transparent code bugs. Parallel to Spell's deterministic computation integration.

#### Tool-Induced Myopia
**Title:** From Proof to Program: Characterizing Tool-Induced Reasoning Hallucinations in Large Language Models
**Authors:** Farima Fatahi Bayat, Pouya Pezeshkpour, Estevam Hruschka
**Date:** 2025-11-14
**Venue/Source:** arXiv (2511.10899)
**URL:** https://arxiv.org/abs/2511.10899
**Relevance:** When code tools are available, LLMs shift from proof-like reasoning to empirical checking. Thinking models use tools ~50% more. Important nuance for Spell: tool availability changes reasoning strategy.

#### SPRINT
**Title:** SPRINT: Enabling Interleaved Planning and Parallelized Execution in Reasoning Models
**Authors:** Emil Biju, Shayan Talaei, et al.
**Date:** 2025-06-06
**Venue/Source:** NeurIPS 2025
**URL:** https://arxiv.org/abs/2506.05745
**Relevance:** Interleaves planning and parallel execution. Planner identifies independent subtasks, pool of executors runs them. ~39% fewer sequential tokens. Directly related to Spell's agent spawn/orchestration.

#### Code-enhanced reasoning survey
**Title:** Code to Think, Think to Code: A Survey on Code-Enhanced Reasoning and Reasoning-Driven Code Intelligence in LLMs
**Authors:** Dayu Yang, et al.
**Date:** 2025-02-26
**Venue/Source:** EMNLP 2025
**URL:** https://arxiv.org/abs/2502.19411
**Relevance:** Comprehensive survey on the bidirectional relationship between code and reasoning. Essential survey reference for Spell's code-as-reasoning approach.

---

### LLM Reasoning: Test-Time Compute and Benchmarks

#### Test-time compute scaling
**Title:** Scaling LLM Test-Time Compute Optimally can be More Effective than Scaling Model Parameters
**Authors:** Charlie Snell, Jaehoon Lee, Kelvin Xu, Aviral Kumar
**Date:** 2024-08-06
**Venue/Source:** ICLR 2025 (Oral)
**URL:** https://arxiv.org/abs/2408.03314
**Relevance:** Foundational paper on test-time compute scaling. Spell's code execution during generation is a form of test-time compute; this provides theoretical grounding.

#### Inference scaling laws
**Title:** Inference Scaling Laws: An Empirical Analysis of Compute-Optimal Inference
**Authors:** Yangzhen Wu, Zhiqing Sun, et al.
**Date:** 2024-08-01
**Venue/Source:** ICLR 2025
**URL:** https://arxiv.org/abs/2408.00724
**Relevance:** Smaller models + advanced inference algorithms offer Pareto-optimal tradeoffs. Llemma-7B with tree search outperforms Llemma-34B. Supports thesis that execution-time orchestration matters more than raw model scale.

#### Compound inference systems
**Title:** Are More LLM Calls All You Need? Towards Scaling Laws of Compound Inference Systems
**Authors:** Lingjiao Chen, Jared Quincy Davis, et al.
**Date:** 2024-03-04
**Venue/Source:** NeurIPS 2024
**URL:** https://arxiv.org/abs/2403.02419
**Relevance:** Scaling properties for compound systems making multiple LM calls. Shows non-monotonic behavior (more calls can hurt on hard queries). Relevant to Spell's multi-call orchestration.

#### s1 budget forcing
**Title:** s1: Simple test-time scaling
**Authors:** Niklas Muennighoff, et al.
**Date:** 2025-01-31
**Venue/Source:** EMNLP 2025
**URL:** https://arxiv.org/abs/2501.19393
**Relevance:** Budget forcing (appending "Wait" to extend thinking) with only 1K examples exceeds o1-preview on AIME24 by 27%. Controlling reasoning length is a powerful lever — analogous to Spell's programmatic control of context and reasoning depth.

#### Chain-of-Thought
**Title:** Chain-of-Thought Prompting Elicits Reasoning in Large Language Models
**Authors:** Jason Wei, Xuezhi Wang, et al.
**Date:** 2022-01-28
**Venue/Source:** NeurIPS 2022
**URL:** https://arxiv.org/abs/2201.11903
**Relevance:** Seminal CoT paper. PAL and PoT are defined in contrast to CoT. Essential background reference.

#### Tree of Thoughts
**Title:** Tree of Thoughts: Deliberate Problem Solving with Large Language Models
**Authors:** Shunyu Yao, et al.
**Date:** 2023-05-17
**Venue/Source:** NeurIPS 2023
**URL:** https://arxiv.org/abs/2305.10601
**Relevance:** Generalizes CoT to tree-structured exploration with search and backtracking. GPT-4 goes from 4% to 74% on Game of 24.

#### LATS
**Title:** Language Agent Tree Search Unifies Reasoning Acting and Planning
**Authors:** Andy Zhou, Kai Yan, et al.
**Date:** 2023-10-06
**Venue/Source:** ICML 2024
**URL:** https://arxiv.org/abs/2310.04406
**Relevance:** Unifies reasoning, acting, and planning through MCTS. LLM serves as action generator, value function, and reflection mechanism simultaneously. 92.7% on HumanEval.

#### Process verification
**Title:** Let's Verify Step by Step
**Authors:** Hunter Lightman, et al.
**Date:** 2023-05-31
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2305.20050
**Relevance:** Process supervision significantly outperforms outcome supervision for math. Spell's code execution provides a form of automatic step verification.

#### OpenAI o1
**Title:** OpenAI o1 System Card
**Authors:** OpenAI
**Date:** 2024-12-05
**Venue/Source:** Technical report
**URL:** https://arxiv.org/abs/2412.16720
**Relevance:** 83% on AIME, 89th percentile Codeforces. Chain-of-thought trained via RL. Key comparison model for Spell's math benchmarks.

#### Learning to Reason with LLMs
**Title:** Learning to Reason with LLMs
**Authors:** OpenAI
**Date:** 2024-09-12
**Venue/Source:** Blog
**URL:** https://openai.com/index/learning-to-reason-with-llms/
**Relevance:** Official introduction of o1's reasoning approach. 93% on AIME 2024 with reranking. Key benchmark numbers.

#### DeepSeek-R1
**Title:** DeepSeek-R1: Incentivizing Reasoning Capability in LLMs via Reinforcement Learning
**Authors:** DeepSeek-AI (200+ authors)
**Date:** 2025-01-22
**Venue/Source:** Nature 645, 633-638 (2025)
**URL:** https://arxiv.org/abs/2501.12948
**Relevance:** Reasoning emerges from pure RL without supervised fine-tuning. MIT-licensed. ~80% on AIME. Key open-source comparison point for Spell.

#### AlphaProof
**Title:** Olympiad-level formal mathematical reasoning with reinforcement learning
**Authors:** Thomas Hubert, et al. (Google DeepMind)
**Date:** 2025-11
**Venue/Source:** Nature 651, 607-613 (2025)
**URL:** https://www.nature.com/articles/s41586-025-09833-y
**Relevance:** AlphaZero-inspired RL for formal proofs in Lean. Silver medal at 2024 IMO. Demonstrates code-verified reasoning at the frontier of mathematics.

#### AlphaGeometry
**Title:** Solving olympiad geometry without human demonstrations
**Authors:** Trieu H. Trinh, Yuhuai Wu, Quoc V. Le, He He, Thang Luong
**Date:** 2024-01
**Venue/Source:** Nature 625, 476-482 (2024)
**URL:** https://www.nature.com/articles/s41586-023-06747-5
**Relevance:** Neuro-symbolic (LLM + symbolic deduction engine) for Olympiad geometry. 25/30 IMO problems. Demonstrates combining neural and symbolic computation.

#### Omni-MATH
**Title:** Omni-MATH: A Universal Olympiad Level Mathematic Benchmark
**Authors:** Bofei Gao, Feifan Song, et al.
**Date:** 2024-10-10
**Venue/Source:** ICLR 2025
**URL:** https://arxiv.org/abs/2410.07985
**Relevance:** 4,428 competition-level problems across 33+ sub-domains. Even o1 models struggled (52-60%). Benchmark Spell evaluates against.

#### MATH dataset
**Title:** Measuring Mathematical Problem Solving With the MATH Dataset
**Authors:** Dan Hendrycks, Collin Burns, et al.
**Date:** 2021
**Venue/Source:** NeurIPS 2021 (Datasets & Benchmarks)
**URL:** https://arxiv.org/abs/2103.03874
**Relevance:** 12,500 competition math problems. Standard evaluation suite.

#### MathArena
**Title:** MathArena: Evaluating LLMs on Uncontaminated Math Competitions
**Authors:** Mislav Balunovic, Jasper Dekoninck, et al.
**Date:** 2025-05
**Venue/Source:** NeurIPS 2025 (Datasets & Benchmarks)
**URL:** https://arxiv.org/abs/2505.23281
**Relevance:** Contamination-free math evaluation using fresh competition problems. Found strong contamination in AIME 2024. Important for contextualizing Spell's results.

#### FrontierMath
**Title:** FrontierMath: A Benchmark for Evaluating Advanced Mathematical Reasoning in AI
**Authors:** Elliot Glazer, Ege Erdil, Tamay Besiroglu, et al.
**Date:** 2024-11
**Venue/Source:** arXiv (Epoch AI)
**URL:** https://arxiv.org/abs/2411.04872
**Relevance:** Research-level math problems. No model achieved 2% at release. Created by 60+ mathematicians including Fields Medalist. The frontier.

#### Proof or Bluff
**Title:** Proof or Bluff? Evaluating LLMs on 2025 USA Math Olympiad
**Authors:** Ivo Petrov, Jasper Dekoninck, et al.
**Date:** 2025-03-27
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2503.21934
**Relevance:** When evaluated on proof quality (not just answers), only Gemini-2.5-Pro scores non-trivially (25%). All others <5%. Reveals gap between answer accuracy and reasoning quality — relevant to why Spell's verifiable execution matters.

#### LongBench v2
**Title:** LongBench v2: Towards Deeper Understanding and Reasoning on Realistic Long-context Multitasks
**Authors:** Yushi Bai, Shangqing Tu, et al.
**Date:** 2024-12-19
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2412.15204
**Relevance:** 503 challenging questions with 8k-2M word contexts. Best model: 50.1%. Benchmark Spell reports results on.

#### Reinforced reasoning survey
**Title:** Towards Large Reasoning Models: A Survey of Reinforced Reasoning with Large Language Models
**Authors:** Fengli Xu, et al.
**Date:** 2025-01-16
**Venue/Source:** Patterns (Cell Press)
**URL:** https://arxiv.org/abs/2501.09686
**Relevance:** Comprehensive survey covering the transformation from autoregressive generation to deliberate reasoning via RL and test-time compute.

---

### Foundational Agent Architectures

#### ReAct
**Title:** ReAct: Synergizing Reasoning and Acting in Language Models
**Authors:** Shunyu Yao, Jeffrey Zhao, et al.
**Date:** 2022-10-06
**Venue/Source:** ICLR 2023
**URL:** https://arxiv.org/abs/2210.03629
**Relevance:** The canonical agent loop paper. Interleaved reasoning traces and actions. Spell's central argument is that the LLM should program its own execution graph rather than being constrained to a fixed reason-act loop.

#### CoALA
**Title:** Cognitive Architectures for Language Agents
**Authors:** Theodore R. Sumers, Shunyu Yao, Karthik Narasimhan, Thomas L. Griffiths
**Date:** 2023-09-05
**Venue/Source:** TMLR 2024
**URL:** https://arxiv.org/abs/2309.02427
**Relevance:** Systematic framework for understanding language agents with modular memory, structured action spaces, and decision-making. Essential for positioning Spell within the agent architecture taxonomy.

#### Reflexion
**Title:** Reflexion: Language Agents with Verbal Reinforcement Learning
**Authors:** Noah Shinn, Federico Cassano, et al.
**Date:** 2023-03-20
**Venue/Source:** NeurIPS 2023
**URL:** https://arxiv.org/abs/2303.11366
**Relevance:** Agents self-improve through verbal reflection stored in episodic memory. Relevant to Spell's think/rethink context management — managing context through structured reflection.

#### Agent survey
**Title:** A Survey on Large Language Model based Autonomous Agents
**Authors:** Lei Wang et al.
**Date:** 2023-08-22
**Venue/Source:** Frontiers of Computer Science 2024
**URL:** https://arxiv.org/abs/2308.11432
**Relevance:** First comprehensive survey of LLM-based agents covering construction, application, evaluation.

---

### Non-Academic: Model Provider Announcements and Engineering Blogs

#### Anthropic: Building Effective Agents
**Title:** Building Effective Agents
**Authors:** Erik Schluntz, Barry Zhang (Anthropic)
**Date:** 2024-12-19
**Venue/Source:** Anthropic research blog
**URL:** https://www.anthropic.com/research/building-effective-agents
**Relevance:** Canonical taxonomy of agent patterns. Distinguishes "workflows" (predefined) from "agents" (LLM-directed). Spell transcends both categories. The most-cited non-academic reference on agent architecture.

#### Anthropic: Context Engineering
**Title:** Effective Context Engineering for AI Agents
**Authors:** Prithvi Rajasekaran, Ethan Dixon, Carly Ryan, Jeremy Hadfield (Anthropic Applied AI)
**Date:** 2025-09-29
**Venue/Source:** Anthropic engineering blog
**URL:** https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
**Relevance:** Directly addresses context as a finite resource requiring curation at each step. Covers compaction, structured note-taking, sub-agent architectures.

#### Anthropic: Advanced Tool Use
**Title:** Introducing Advanced Tool Use on the Claude Developer Platform
**Authors:** Bin Wu et al. (Anthropic)
**Date:** 2025-11-24
**Venue/Source:** Anthropic engineering blog
**URL:** https://www.anthropic.com/engineering/advanced-tool-use
**Relevance:** Tool Search Tool (85% context reduction), Programmatic Tool Calling (code-based tool orchestration keeping intermediates out of context). Directly tackles context bloat.

#### Anthropic: Agent Skills
**Title:** Equipping Agents for the Real World with Agent Skills
**Authors:** Barry Zhang, Keith Lazuka, Mahesh Murag (Anthropic)
**Date:** 2025-10-16
**Venue/Source:** Claude blog
**URL:** https://claude.com/blog/equipping-agents-for-the-real-world-with-agent-skills
**Relevance:** Progressive disclosure architecture for skill loading (L1 metadata, L2 full skill, L3+ additional files). Skills are "effectively unbounded" with filesystem access.

#### Anthropic: Claude Agent SDK
**Title:** Building Agents with the Claude Agent SDK
**Authors:** Thariq Shihipar et al. (Anthropic)
**Date:** 2025-09-29
**Venue/Source:** Claude blog
**URL:** https://claude.com/blog/building-agents-with-the-claude-agent-sdk
**Relevance:** Describes the harness powering Claude Code: agent loop, subagent parallelization with isolated context, automatic message compaction.

#### Anthropic: Writing Tools for Agents
**Title:** Writing Effective Tools for Agents — with Agents
**Authors:** Ken Aizawa (Anthropic)
**Date:** 2025-09-11
**Venue/Source:** Anthropic engineering blog
**URL:** https://www.anthropic.com/engineering/writing-tools-for-agents
**Relevance:** Tool design for agent systems. Used Claude to optimize its own tool descriptions — a weak form of self-programming.

#### Anthropic: Multi-Agent Research System
**Title:** How We Built Our Multi-Agent Research System
**Authors:** Anthropic Engineering
**Date:** 2025-06-13
**Venue/Source:** Anthropic engineering blog
**URL:** https://www.anthropic.com/engineering/multi-agent-research-system
**Relevance:** Claude's orchestrator-worker multi-agent architecture. Lead agent decomposes and spawns parallel subagents. 90.2% improvement over single-agent. Direct comparison point.

#### Claude Code Agent Teams
**Authors:** Anthropic
**Date:** 2026-02
**Venue/Source:** Documentation
**URL:** https://code.claude.com/docs/en/agent-teams
**Relevance:** Multi-agent architecture with team lead, independent context windows, shared task list with file-locking, inter-agent messaging. Significant reference for coordinated multi-agent coding.

#### OpenAI: Codex Agent Loop
**Title:** Unrolling the Codex Agent Loop
**Authors:** OpenAI
**Date:** 2025
**Venue/Source:** OpenAI blog
**URL:** https://openai.com/index/unrolling-the-codex-agent-loop/
**Relevance:** Detailed description of Codex agent loop: prompt construction, tool execution, code-as-primary-output. Sandboxed containers with no internet.

#### Google: Agent2Agent Protocol
**Title:** Announcing the Agent2Agent Protocol (A2A)
**Authors:** Rao Surapaneni, Miku Jha, et al. (Google)
**Date:** 2025-04-09
**Venue/Source:** Google Developers Blog
**URL:** https://developers.googleblog.com/en/a2a-a-new-era-of-agent-interoperability/
**Relevance:** Open inter-agent communication protocol. Agent Cards for capability discovery, task lifecycle management. Complementary to MCP (tools) — A2A handles agent-to-agent coordination. 50+ launch partners.

#### DeepSeek-V3.2 thinking with tools
**Authors:** DeepSeek
**Date:** 2025-12-01
**Venue/Source:** API docs
**URL:** https://api-docs.deepseek.com/news/news251201
**Relevance:** First model integrating thinking directly into tool-use. New agent training data covering 1,800+ environments. Reasoning and tool execution interleaved, not sequential.

#### Manus: Context Engineering
**Title:** Context Engineering for AI Agents: Lessons from Building Manus
**Authors:** Yichao "Peak" Ji (Manus)
**Date:** 2025-07-18
**Venue/Source:** Manus blog
**URL:** https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus
**Relevance:** KV-cache hit rate as most critical production metric. Append-only contexts, logit masking + state machines for action selection, file system as externalized memory. Among the most architecturally detailed non-academic posts on context management.

#### Cursor architecture
**Title:** How Cursor Shipped its Coding Agent to Production
**Authors:** ByteByteGo (with Lee Robinson at Cursor)
**Date:** 2026-01-26
**Venue/Source:** ByteByteGo blog
**URL:** https://blog.bytebytego.com/p/how-cursor-shipped-its-coding-agent
**Relevance:** Custom MoE model (Composer), speculative decoding, context compaction, edit-trajectory training. Up to 8 parallel agents via git worktree isolation.

#### Cognition: Don't Build Multi-Agents
**Title:** Don't Build Multi-Agents
**Authors:** Cognition AI (Devin team)
**Date:** 2025-06
**Venue/Source:** Cognition blog
**URL:** https://cognition.ai/blog/dont-build-multi-agents
**Relevance:** Argues against multi-agent architectures for write-heavy domains due to context isolation causing conflicting decisions. Important counterpoint. Highlights the tension Spell navigates with explicit communication primitives.

#### Cognition: Devin Performance Review
**Title:** Devin's 2025 Performance Review
**Authors:** Cognition Team
**Date:** 2025-11-14
**Venue/Source:** Cognition blog
**URL:** https://cognition.ai/blog/devin-annual-performance-review-2025
**Relevance:** Production lessons: agents excel with clear requirements and verifiable outcomes. Human oversight remains essential for senior-level decisions.

#### Replit multi-agent
**Title:** Replit Agent Case Study
**Authors:** Michele Catasta (Replit) / LangChain
**Date:** 2025
**Venue/Source:** LangChain Breakout Agents
**URL:** https://www.langchain.com/breakoutagents/replit
**Relevance:** Multi-agent architecture: manager, editor, verifier. "We don't strive for full autonomy." Each agent performs smallest possible task. Deliberate rejection of single-agent ReAct.

#### JetBrains: Context Management
**Title:** Cutting Through the Noise: Smarter Context Management for LLM-Powered Agents
**Authors:** Katie Fraser, Tobias Lindenbauer (JetBrains Research)
**Date:** 2025-12
**Venue/Source:** JetBrains Research blog
**URL:** https://blog.jetbrains.com/research/2025/12/efficient-context-management/
**Relevance:** Observation masking outperformed LLM summarization while being cheaper. Hybrid approach (masking + selective summarization) was optimal. Directly relevant to Spell's context management design.

---

### Non-Academic: Thought Leaders

#### Karpathy: Context Engineering
**Authors:** Andrej Karpathy
**Date:** 2025-06-19
**Venue/Source:** Talk + tweet (Y Combinator AI Startup School)
**URL:** https://x.com/karpathy/status/1937902205765607626
**Relevance:** Coined/popularized "context engineering" as distinct from "prompt engineering": "the delicate art and science of filling the context window with just the right information for the next step."

#### Karpathy: 2025 Year in Review
**Authors:** Andrej Karpathy
**Date:** Late 2025
**Venue/Source:** Blog
**URL:** https://karpathy.bearblog.dev/year-in-review-2025/
**Relevance:** Identifies Claude Code as "the first convincing demonstration of what an LLM Agent looks like." Defines the LLM app layer: context engineering, multi-LLM orchestration, autonomy slider.

#### swyx: Agent Engineering
**Title:** Agent Engineering
**Authors:** swyx (Shawn Wang)
**Date:** 2025-03-24
**Venue/Source:** Latent Space
**URL:** https://www.latent.space/p/agent
**Relevance:** IMPACT framework: Intent, Models with Tools, Planning, Authority, Control Flow, Memory. Synthesizes patterns from across the field.

#### Lilian Weng: Why We Think
**Title:** Why We Think
**Authors:** Lilian Weng (OpenAI)
**Date:** 2025-05-01
**Venue/Source:** Blog
**URL:** https://lilianweng.github.io/posts/2025-05-01-thinking/
**Relevance:** Comprehensive survey of test-time compute / reasoning: CoT, RLVR, parallel sampling, sequential revision, tool use integration.

#### Yohei Nakajima: Self-Improving Agents
**Title:** Better Ways to Build Self-Improving AI Agents
**Authors:** Yohei Nakajima
**Date:** 2025-12-05
**Venue/Source:** Blog
**URL:** https://yoheinakajima.com/better-ways-to-build-self-improving-ai-agents/
**Relevance:** Six self-improvement mechanisms. Dominant pattern: turning interaction traces into persistent reusable structures. Directly relevant to Spell's self-programming thesis.

#### LangChain: Context Engineering
**Title:** Context Engineering (for agents)
**Authors:** Lance Martin (LangChain)
**Date:** 2025-06-23
**Venue/Source:** Blog
**URL:** https://blog.langchain.com/context-engineering-for-agents/
**Relevance:** Four strategies: write (scratchpads), select (embeddings), compress (summarization), isolate (sub-agents). Reviews how Claude Code, Devin, and others implement these.

#### Harrison Chase: Cognitive Architecture
**Title:** What is a 'cognitive architecture'?
**Authors:** Harrison Chase
**Date:** 2024-07-05
**Venue/Source:** LangChain blog
**URL:** https://blog.langchain.com/what-is-a-cognitive-architecture/
**Relevance:** Defines cognitive architecture as "how your system thinks — the flow of code/prompts/LLM calls." Spectrum from hardcoded to autonomous. Directly relevant framing for Spell.

#### Harrison Chase: Own Your Cognitive Architecture
**Title:** Why you should outsource your agentic infrastructure, but own your cognitive architecture
**Authors:** Harrison Chase
**Date:** 2024-07-13
**Venue/Source:** LangChain blog
**URL:** https://blog.langchain.com/why-you-should-outsource-your-agentic-infrastructure-but-own-your-cognitive-architecture/
**Relevance:** Distinguishes infrastructure from cognitive architecture. Argues the latter is competitive advantage. Spell is a language for defining cognitive architecture.

#### SIGPLAN: Prompts Are Programs
**Title:** Prompts Are Programs
**Authors:** Tommy Guy, Peli de Halleux, Reshabh K Sharma, Ben Zorn
**Date:** 2024-10-22
**Venue/Source:** SIGPLAN Blog
**URL:** https://blog.sigplan.org/2024/10/22/prompts-are-programs/
**Relevance:** ACM SIGPLAN perspective arguing prompts should be understood as programs. Directly supports Spell's framing of completions as programs.

#### GitHub: Spec-driven development
**Title:** Spec-driven development: Using Markdown as a programming language when building with AI
**Authors:** Tomas Vesely (GitHub)
**Date:** 2025-09-30
**Venue/Source:** GitHub blog
**URL:** https://github.blog/ai-and-ml/generative-ai/spec-driven-development-using-markdown-as-a-programming-language-when-building-with-ai/
**Relevance:** Treats Markdown specs as primary source code that agents "compile." NL as programming interface — resonates with Spell's "prompt-as-prefix" design.

---

## LOW priority

#### SWE-bench+ (enhanced)
**Title:** SWE-Bench+: Enhanced Coding Benchmark for LLMs
**Authors:** Reem Aleithan, et al.
**Date:** 2024-10
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2410.06992
**Relevance:** Found 32.67% of SWE-bench solutions involve "cheating" via solution leakage. Context for interpreting SWE-bench numbers.

#### SWE-bench Pro
**Title:** SWE-Bench Pro: Can AI Agents Solve Long-Horizon Software Engineering Tasks?
**Authors:** Xiang Deng, et al. (Scale AI)
**Date:** 2025-09
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2509.16941
**Relevance:** Harder variant; best models ~23%.

#### LiveCodeBench
**Title:** LiveCodeBench: Holistic and Contamination Free Evaluation
**Authors:** Naman Jain, et al.
**Date:** 2024-03
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2403.07974
**Relevance:** Contamination-free code benchmark with time-stamped problems.

#### Agent interoperability protocols survey
**Title:** A Survey of Agent Interoperability Protocols: MCP, ACP, A2A, and ANP
**Authors:** Abul Ehtesham, et al.
**Date:** 2025-05
**Venue/Source:** arXiv (2505.02279)
**URL:** https://arxiv.org/abs/2505.02279
**Relevance:** Surveys emerging inter-agent standards. Contrast to Spell's language-native communication.

#### RAPS multi-agent coordination
**Title:** Towards Adaptive, Scalable, and Robust Coordination of LLM Agents
**Authors:** Rui Li, et al.
**Date:** 2026-02
**Venue/Source:** arXiv (2602.08009)
**URL:** https://arxiv.org/abs/2602.08009
**Relevance:** Multi-agent coordination as dynamic ad-hoc networking. Reputation-aware publish-subscribe.

#### DyLAN
**Title:** DyLAN: A Dynamic LLM-Powered Agent Network
**Authors:** Zijun Liu et al.
**Date:** 2023-10
**Venue/Source:** COLM 2024
**URL:** https://arxiv.org/abs/2310.02170
**Relevance:** Dynamic agent team optimization using Agent Importance Scores. Up to 25% gains from team selection.

#### DAAO difficulty-aware orchestration
**Title:** Difficulty-Aware Agentic Orchestration for Query-Specific Multi-Agent Workflows
**Authors:** Jinwei Su, et al.
**Date:** 2025-09
**Venue/Source:** WWW 2026
**URL:** https://arxiv.org/abs/2509.11079
**Relevance:** Adapts workflow depth based on query difficulty. 64% cost reduction.

#### SwarmAgentic
**Title:** SwarmAgentic: Towards Fully Automated Agentic System Generation via Swarm Intelligence
**Authors:** Yao Zhang, et al.
**Date:** 2025-06
**Venue/Source:** arXiv (2506.15672)
**URL:** https://arxiv.org/abs/2506.15672
**Relevance:** PSO to discover multi-agent configurations. +261.8% over ADAS on TravelPlanner.

#### MAD divergent thinking
**Title:** Encouraging Divergent Thinking in Large Language Models through Multi-Agent Debate
**Authors:** Liang et al.
**Date:** 2024
**Venue/Source:** EMNLP 2024
**URL:** https://aclanthology.org/2024.emnlp-main.992/
**Relevance:** MAD framework with arguing agents and a judge.

#### MAD critical evaluation
**Title:** Should we be going MAD? A Look at Multi-Agent Debate Strategies for LLMs
**Authors:** Smit et al.
**Date:** 2024
**Venue/Source:** ICML 2024
**URL:** https://proceedings.mlr.press/v235/smit24a.html
**Relevance:** MAD systems don't reliably outperform simpler strategies without tuning.

#### Multi-Agent Evolve
**Title:** Multi-Agent Evolve: LLM Self-Improve through Co-evolution
**Authors:** Yixing Chen, et al.
**Date:** 2025-10
**Venue/Source:** arXiv (2510.23595)
**URL:** https://arxiv.org/abs/2510.23595
**Relevance:** Self-play co-evolution with a Judge for general-domain settings.

#### AgentVerse
**Title:** AgentVerse: Facilitating Multi-Agent Collaboration and Exploring Emergent Behaviors
**Authors:** Weize Chen, Yusheng Su, et al.
**Date:** 2023-08
**Venue/Source:** ICLR 2024
**URL:** https://arxiv.org/abs/2308.10848
**Relevance:** Dynamic agent group formation with emergent behaviors.

#### Interlat latent communication
**Title:** Enabling Agents to Communicate Entirely in Latent Space
**Authors:** Zhuoyun Du, et al.
**Date:** 2025-11
**Venue/Source:** arXiv (2511.09149)
**URL:** https://arxiv.org/abs/2511.09149
**Relevance:** Inter-agent communication via hidden states. Up to 24x inference speedup.

#### Voyager
**Title:** Voyager: An Open-Ended Embodied Agent with Large Language Models
**Authors:** Guanzhi Wang, et al.
**Date:** 2023-05-25
**Venue/Source:** arXiv (2305.16291)
**URL:** https://arxiv.org/abs/2305.16291
**Relevance:** Lifelong learning via code-skill library. Relevant as agent building capabilities through code.

#### FunSearch
**Title:** Mathematical discoveries from program search with large language models
**Authors:** Bernardino Romera Paredes et al.
**Date:** 2023-12-14
**Venue/Source:** Nature
**URL:** https://www.nature.com/articles/s41586-023-06924-6
**Relevance:** Evolutionary program search using LLMs. First scientific discovery with LLM.

#### AlphaEvolve
**Title:** AlphaEvolve: A coding agent for scientific and algorithmic discovery
**Authors:** Alexander Novikov et al. (DeepMind)
**Date:** 2025-06-16
**Venue/Source:** arXiv (2506.13131)
**URL:** https://arxiv.org/abs/2506.13131
**Relevance:** Evolutionary coding agent. Improved Strassen's algorithm. 0.7% of Google's compute saved.

#### Parsel
**Title:** Parsel: Algorithmic Reasoning with Language Models by Composing Decompositions
**Authors:** Eric Zelikman, et al.
**Date:** 2022-12
**Venue/Source:** NeurIPS 2023
**URL:** https://arxiv.org/abs/2212.10561
**Relevance:** Hierarchical decomposition into NL function descriptions. 75%+ improvement on APPS.

#### DreamCoder
**Title:** DreamCoder: Bootstrapping Inductive Program Synthesis with Wake-Sleep Library Learning
**Authors:** Kevin Ellis, et al.
**Date:** 2021-06
**Venue/Source:** PLDI 2021
**URL:** https://dl.acm.org/doi/10.1145/3453483.3454080
**Relevance:** Learns domain-specific PLs through wake-sleep cycles. Precursor to LLM-based program synthesis.

#### Toolformer
**Title:** Toolformer: Language Models Can Teach Themselves to Use Tools
**Authors:** Timo Schick et al.
**Date:** 2023-02-09
**Venue/Source:** NeurIPS 2023
**URL:** https://arxiv.org/abs/2302.04761
**Relevance:** Self-supervised tool use learning. Foundational.

#### AIOS
**Title:** AIOS: LLM Agent Operating System
**Authors:** Mei, Li et al.
**Date:** 2024-03-25
**Venue/Source:** COLM 2025 (arXiv 2403.16971)
**URL:** https://arxiv.org/abs/2403.16971
**Relevance:** OS-level kernel for LLM agents with scheduling and context management.

#### Limits of self-improvement
**Title:** On the Limits of Self-Improving in Large Language Models
**Authors:** Hector Zenil
**Date:** 2026-01-05
**Venue/Source:** arXiv (2601.05280)
**URL:** https://arxiv.org/abs/2601.05280
**Relevance:** Theoretical analysis proving fully autonomous recursive self-improvement leads to degenerative fixed points without external grounding.

#### AGORA
**Title:** Unifying Language Agent Algorithms with Graph-based Orchestration Engine
**Authors:** Qianqian Zhang et al.
**Date:** 2025-05-30
**Venue/Source:** ACL 2025 System Demos
**URL:** https://arxiv.org/abs/2505.24354
**Relevance:** Graph-based workflow engine. Finding that simpler methods often match sophisticated approaches.

#### Chameleon
**Title:** Chameleon: Plug-and-Play Compositional Reasoning with Large Language Models
**Authors:** Pan Lu et al.
**Date:** 2023-04
**Venue/Source:** NeurIPS 2023
**URL:** https://arxiv.org/abs/2304.09842
**Relevance:** LLM planner assembles tool sequences for complex reasoning.

#### OPRO
**Title:** Large Language Models as Optimizers
**Authors:** Chengrun Yang, et al.
**Date:** 2023-09-07
**Venue/Source:** ICLR 2024
**URL:** https://arxiv.org/abs/2309.03409
**Relevance:** LLMs optimize their own prompts iteratively. Up to 50% improvement on BBH.

#### OpenCodeInterpreter
**Title:** OpenCodeInterpreter: Integrating Code Generation with Execution and Refinement
**Authors:** Tianyu Zheng, et al.
**Date:** 2024-02
**Venue/Source:** ACL 2024 Findings
**URL:** https://arxiv.org/abs/2402.14658
**Relevance:** Open-source code interpreter with 68K multi-turn interactions.

#### HybridMind
**Title:** HybridMind: Meta Selection of NL and Symbolic Language for Enhanced LLM Reasoning
**Authors:** Simeng Han, et al.
**Date:** 2024-09
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2409.19381
**Relevance:** Dynamically selects between CoT, PAL, and hybrid modes.

#### Codified Context
**Title:** Codified Context: Infrastructure for AI Agents in a Complex Codebase
**Authors:** Aristidis Vasilopoulos
**Date:** 2026-02-24
**Venue/Source:** arXiv (2602.20478)
**URL:** https://arxiv.org/abs/2602.20478
**Relevance:** Three-tier memory for 108K-line codebase. More engineering practice than research.

#### OPENDEV
**Title:** Building Effective AI Coding Agents for the Terminal
**Authors:** Nghi D. Q. Bui
**Date:** 2026-03-05
**Venue/Source:** arXiv (2603.05344)
**URL:** https://arxiv.org/abs/2603.05344
**Relevance:** Terminal-native coding agent with adaptive context compaction.

#### MECW
**Title:** Context Is What You Need: The Maximum Effective Context Window
**Authors:** Norman Paulsen
**Date:** 2025-09-21
**Venue/Source:** arXiv (2509.21361)
**URL:** https://arxiv.org/abs/2509.21361
**Relevance:** MECW is drastically smaller than advertised — some models fail at 100 tokens.

#### Chain of Agents
**Title:** Chain of Agents: Large Language Models Collaborating on Long-Context Tasks
**Authors:** Yusen Zhang, et al.
**Date:** 2024-06-04
**Venue/Source:** NeurIPS 2024
**URL:** https://arxiv.org/abs/2406.02818
**Relevance:** Workers sequentially process text segments for long context.

#### Graph of Agents
**Title:** Graph of Agents: Principled Long Context Modeling
**Authors:** Taejong Joo, et al.
**Date:** 2025-09-26
**Venue/Source:** arXiv (2509.21848)
**URL:** https://arxiv.org/abs/2509.21848
**Relevance:** Formalizes long context as a compression problem with information-theoretic objective.

#### A-MEM
**Title:** A-MEM: Agentic Memory for LLM Agents
**Authors:** Wujiang Xu, et al.
**Date:** 2025-02-17
**Venue/Source:** NeurIPS 2025
**URL:** https://arxiv.org/abs/2502.12110
**Relevance:** Zettelkasten-inspired agentic memory. Tangentially relevant.

#### Fact-based memory vs long context
**Title:** Beyond the Context Window: A Cost-Performance Analysis of Fact-Based Memory vs. Long-Context LLMs
**Authors:** Natchanon Pollertlam, et al.
**Date:** 2026-03-05
**Venue/Source:** arXiv (2603.04814)
**URL:** https://arxiv.org/abs/2603.04814
**Relevance:** Different cost profiles: long-context has per-turn growth; memory systems have constant cost.

#### KV cache survey
**Title:** A Survey on Large Language Model Acceleration based on KV Cache Management
**Authors:** Haoyang Li, et al.
**Date:** 2024-12-27
**Venue/Source:** TMLR 2025
**URL:** https://arxiv.org/abs/2412.19442
**Relevance:** KV cache management techniques at token, model, and system levels.

#### Self-Consistency
**Title:** Self-Consistency Improves Chain of Thought Reasoning
**Authors:** Xuezhi Wang, et al.
**Date:** 2022-03
**Venue/Source:** ICLR 2023
**URL:** https://arxiv.org/abs/2203.11171
**Relevance:** Majority-voting over diverse reasoning paths. +17.9% on GSM8K.

#### Graph of Thoughts
**Title:** Graph of Thoughts: Solving Elaborate Problems
**Authors:** Maciej Besta, et al.
**Date:** 2023-08
**Venue/Source:** AAAI 2024
**URL:** https://arxiv.org/abs/2308.09687
**Relevance:** Extends ToT to arbitrary graph structures.

#### STaR
**Title:** STaR: Bootstrapping Reasoning With Reasoning
**Authors:** Eric Zelikman, et al.
**Date:** 2022-03
**Venue/Source:** NeurIPS 2022
**URL:** https://arxiv.org/abs/2203.14465
**Relevance:** Self-taught reasoning via iterative rationale generation and fine-tuning.

#### Quiet-STaR
**Title:** Quiet-STaR: Language Models Can Teach Themselves to Think Before Speaking
**Authors:** Eric Zelikman, et al.
**Date:** 2024-03-14
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2403.09629
**Relevance:** Internal rationale generation at each token.

#### Coconut
**Title:** Training Large Language Models to Reason in a Continuous Latent Space
**Authors:** Shibo Hao, et al.
**Date:** 2024-12-09
**Venue/Source:** COLM 2025
**URL:** https://arxiv.org/abs/2412.06769
**Relevance:** Reasoning in continuous latent space. Alternative paradigm.

#### DeepSeekMath GRPO
**Title:** DeepSeekMath: Pushing the Limits of Mathematical Reasoning
**Authors:** Zhihong Shao, et al.
**Date:** 2024-02-05
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2402.03300
**Relevance:** Introduces GRPO for training mathematical reasoning. Became the training algorithm behind DeepSeek-R1.

#### Zero-Shot Reasoners
**Title:** Large Language Models are Zero-Shot Reasoners
**Authors:** Takeshi Kojima, et al.
**Date:** 2022-05
**Venue/Source:** NeurIPS 2022
**URL:** https://arxiv.org/abs/2205.11916
**Relevance:** "Let's think step by step" unlocks zero-shot reasoning.

#### CAMEL
**Title:** CAMEL: Communicative Agents for "Mind" Exploration
**Authors:** Guohao Li, et al.
**Date:** 2023-03
**Venue/Source:** NeurIPS 2023
**URL:** https://arxiv.org/abs/2303.17760
**Relevance:** Role-playing framework using inception prompting.

#### ProgPrompt
**Title:** ProgPrompt: Generating Situated Robot Task Plans using LLMs
**Authors:** Ishika Singh et al.
**Date:** 2022-09
**Venue/Source:** ICRA 2023
**URL:** https://arxiv.org/abs/2209.11302
**Relevance:** Programming language structures for embodied agents. Early "prompt as program."

#### Codex / HumanEval
**Title:** Evaluating Large Language Models Trained on Code
**Authors:** Mark Chen et al. (OpenAI)
**Date:** 2021-07
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2107.03374
**Relevance:** Introduced Codex and HumanEval. Foundational for code generation evaluation.

#### Test-time scaling survey
**Title:** The Art of Scaling Test-Time Compute for Large Language Models
**Authors:** Aradhye Agarwal, et al.
**Date:** 2025-12-01
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2512.02008
**Relevance:** First large-scale empirical study across 30B+ tokens, 8 models, 4 datasets.

#### Anthropic MCP
**Title:** Introducing the Model Context Protocol
**Authors:** Anthropic
**Date:** 2024-11-25
**Venue/Source:** Announcement
**URL:** https://www.anthropic.com/news/model-context-protocol
**Relevance:** Open standard for connecting agents to data sources. Became de facto tool-integration standard.

#### OpenAI Swarm
**Authors:** OpenAI
**Date:** 2024-10
**Venue/Source:** GitHub
**URL:** https://github.com/openai/swarm
**Relevance:** Lightweight multi-agent framework. Superseded by Agents SDK.

#### RLM blog (Prime Intellect)
**Title:** Recursive Language Models: The Paradigm of 2026
**Authors:** Prime Intellect
**Date:** 2026
**Venue/Source:** Blog
**URL:** https://www.primeintellect.ai/blog/rlm
**Relevance:** Industry perspective on RLMs. Context management > context length.

#### Simon Willison: Year in LLMs
**Title:** 2025: The year in LLMs
**Authors:** Simon Willison
**Date:** 2025-12-31
**Venue/Source:** Blog
**URL:** https://simonwillison.net/2025/Dec/31/the-year-in-llms/
**Relevance:** Defines agents as "LLM that runs tools in a loop." Identifies context rot.

#### swyx: Cognition / Devin
**Title:** Cognition: The Devin is in the Details
**Authors:** swyx
**Date:** 2025-09-08
**Venue/Source:** swyx.io
**URL:** https://www.swyx.io/cognition
**Relevance:** "Agent labs" vs "model labs." "Code AGI in 20% of time, 80% of value."

#### Addy Osmani: Conductors to Orchestrators
**Title:** The future of agentic coding: conductors to orchestrators
**Authors:** Addy Osmani
**Date:** 2026-01-02
**Venue/Source:** Blog
**URL:** https://addyosmani.com/blog/future-agentic-coding/
**Relevance:** Conductor (synchronous) vs Orchestrator (async delegation) models.

#### GSM8K
**Title:** Training Verifiers to Solve Math Word Problems
**Authors:** Karl Cobbe, et al.
**Date:** 2021-10
**Venue/Source:** arXiv
**URL:** https://arxiv.org/abs/2110.14168
**Relevance:** Introduced GSM8K. Foundational math reasoning benchmark.

---

## Summary statistics
- **Total items found:** ~150 (deduplicated to ~130 unique)
- **HIGH priority:** 85
- **LOW priority:** 45
- **Discarded:** ~20+ (below "feasibly might cite" bar)
