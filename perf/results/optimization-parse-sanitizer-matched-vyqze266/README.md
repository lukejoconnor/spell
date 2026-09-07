# Lazy sanitizer matched comparison

Control: detached 4abaa1d. Candidate: lazy builder creation in only the two
sanitizers in src/spell/parse.clj. Exact commands, working directories,
parser/probe hashes, fixture order, exits and durations: manifest.json.
The identical measured script is preserved as probe.clj; raw EDN outputs include
loaded-source URLs, versions and all batch samples.

Verification: 12 parser tests / 1496 assertions, zero failures/errors. Three
fresh control/candidate pairs all exited 0; each passed 55 semantic assertions.
All seven JVMs completed in 16.174 seconds and the sole JVM slot was explicitly
released. Fixed -Xms256m -Xmx256m, 30 warmups and five ten-call batches per
operation. Fixtures are generated; no private data or paid model calls.

Entries below are medians across three fresh-process medians.

| Chunks | Input | Operation | Control B/call | Candidate B/call | Allocation change | Control us/call | Candidate us/call |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 64 | unchanged | :ordered-sanitizers | 79568.8 | 4728.8 | -94.06% | 187.1 | 36.7 |
| 64 | unchanged | :read-first | 195504.8 | 120664.8 | -38.28% | 398.0 | 223.3 |
| 64 | recovery | :ordered-sanitizers | 34136.8 | 34560.8 | +1.24% | 255.2 | 276.4 |
| 64 | recovery | :read-first | 96192.8 | 99808.8 | +3.76% | 343.1 | 341.9 |
| 256 | unchanged | :ordered-sanitizers | 306128.8 | 7800.8 | -97.45% | 260.6 | 229.9 |
| 256 | unchanged | :read-first | 748104.8 | 462184.8 | -38.22% | 575.1 | 446.3 |
| 256 | recovery | :ordered-sanitizers | 113048.8 | 113472.8 | +0.38% | 161.8 | 153.4 |
| 256 | recovery | :read-first | 352272.8 | 365104.8 | +3.64% | 362.0 | 340.8 |
| 1024 | unchanged | :ordered-sanitizers | 1212368.8 | 20088.8 | -98.34% | 927.1 | 698.7 |
| 1024 | unchanged | :read-first | 2967352.8 | 1824344.8 | -38.52% | 1993.9 | 1714.3 |
| 1024 | recovery | :ordered-sanitizers | 440984.8 | 441408.8 | +0.10% | 625.5 | 588.3 |
| 1024 | recovery | :read-first | 1384960.8 | 1434656.8 | +3.59% | 1370.1 | 1306.5 |

Recommendation: accept as an asymmetric allocation optimization, not an
unconditional latency improvement. Unchanged public read-first allocation is
roughly 38–40% lower. Rewritten ordered sanitizers add approximately 424 B/call;
rewritten public-reader allocation varies from near parity to roughly 3.8%
higher. Already-sanitized raw-reader allocation also varies with JIT state.
Fixed operation order, short batches and JIT-sensitive timing preclude a blanket
speed claim; rewrite slowdowns and unchanged timing outliers are not excluded.
Measured sanitizer bytes are not all string-copy storage: boxing/counter/call
overhead remains. Caller-thread allocation is not whole-process allocation.

Differential tests use independent original sanitizer snapshots, targeted
boundary cases, 256 seeded generated inputs, exact composed output, and public
reader result/error parity. Design reviewer approved source/tests and recommended
acceptance with the measured tradeoff. Lead owns final acceptance and commits.

The separately discovered multiline-string comment-rewrite bug remains unchanged
and is pinned as baseline behavior. No correctness repair is mixed into this
comparison. Initial failed assertion/probe is retained in sibling directory
optimization-parse-sanitizer-9afqr8ho; original attribution is retained in
optimization-parse-sanitizer-7ps8pufa. No further JVM or source changes were made
while finalizing this report.
