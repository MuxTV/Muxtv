# M3U parser hotspot hypothesis map — 2026-08-24

Status: runner-free static preparation only.

Reviewed production source: `catalog/ingest/.../StreamingM3uParser.kt` on accepted `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`.

Owner: #27 measurement authority. Compatibility correctness fixtures are separately owned by #186.

## Objective

Define measurable parser hypotheses before changing a bounded streaming parser that already has important correctness/security properties.

This document intentionally does **not** implement a faster reader and does not claim the current implementation is slow enough to matter.

## Current correctness contract to preserve

Any optimization must preserve all of the following:

- streaming operation; no whole-playlist materialization;
- `maxLineBytes` enforced before unbounded line accumulation;
- `maxEntries` bound;
- `maxAttributesPerRecord` bound;
- `maxAttributeCharactersPerRecord` bound;
- bounded reported warnings;
- coroutine cancellation through the parse loop;
- first-line UTF BOM handling;
- CRLF/LF semantics;
- selected charset behavior;
- deterministic malformed/unmappable text failure with typed `M3uEncodingException` and line number;
- typed input-bound failure with `M3uLimitExceededException` and reason/line/limit;
- quoted attribute escaping semantics;
- current EXTINF/VLC/Kodi/catch-up/header semantics;
- secret-safe model/diagnostic representation;
- byte-for-byte equivalent normalized semantic output for supported fixtures.

Performance work is invalid if it weakens any of these boundaries.

---

## H1 — per-line byte-at-a-time input loop

### Static observation

`BoundedTextLineReader.readLine()` repeatedly calls `InputStream.read()` for one byte at a time until LF/EOF and copies non-CR bytes into a per-line `ByteArrayOutputStream`.

For a large M3U file this can multiply Java/Kotlin call overhead by input byte count even though the underlying input is buffered.

### Hypothesis

Chunk scanning with a reusable read buffer can reduce CPU overhead while preserving exact line and byte-limit semantics.

### Evidence required

Compare current vs candidate on deterministic 1k/10k/50k corpus:

- wall-time distribution;
- processed bytes;
- CPU time if the harness exposes it reproducibly;
- allocated bytes/objects;
- GC count/time;
- semantic output digest;
- all boundary/encoding tests.

### Candidate design only after RED/measurement

- reusable bounded input chunk buffer;
- scan for LF inside the chunk;
- append only the current line's bounded bytes;
- correctly preserve bytes after a delimiter for the next call;
- reject the byte that would exceed `maxLineBytes` with the same line-number contract.

### Risks

- off-by-one line-limit behavior;
- mishandling CRLF split across chunks;
- losing bytes after the first delimiter;
- cancellation granularity becoming worse on huge input;
- retaining a large buffer unnecessarily after one pathological line.

---

## H2 — fresh `ByteArrayOutputStream` per line

### Static observation

A new `ByteArrayOutputStream(minOf(512, maxLineBytes))` is created for every line and later materialized with `toByteArray()`.

### Hypothesis

A bounded reusable line buffer can reduce allocation and copy pressure on 10k/50k playlists.

### Evidence required

H1 and H2 should initially be profiled together because byte-at-a-time reading and line-buffer allocation occur in the same hot path.

Capture:

- allocated bytes/op or per playlist;
- object count if available;
- GC count/time;
- maximum observed line length distribution;
- wall time;
- semantic equivalence.

### Candidate design

Use a reusable growable byte array with an explicit upper bound. Reset logical length per line, not capacity.

Do not permanently size it to the configured 64 KiB default merely to avoid growth if real line-length evidence shows typical lines are much smaller. A bounded adaptive capacity policy is preferable to unbounded retention.

---

## H3 — line materialization copy

### Static observation

The line path currently accumulates bytes and converts them into a new byte array before decoding.

### Hypothesis

Decoding directly from the valid region of a reusable byte buffer may remove one copy per line.

### Required proof

This is lower priority than H1/H2. Only pursue if allocation profiling shows the copy remains material after the reader is buffered.

Correctness must preserve malformed/unmappable input detection and line-specific errors.

---

## H4 — decoder construction/reset cost

### Static observation

Line decoding configures charset error behavior around decoding. Depending on the exact implementation path, decoder creation/configuration/reset can be repeated per line.

### Hypothesis

A reusable `CharsetDecoder` with deterministic reset may reduce object churn.

### Risks

Charset decoders have mutable state. Reuse must not leak state from a malformed line to the next line or change error positions/contracts.

### Required evidence

- allocation profile after H1/H2 candidate;
- malformed/unmappable fixture suite;
- mixed short/long line corpus;
- output digest.

Do not optimize this first if input scanning dominates.

---

## H5 — quoted attribute `StringBuilder` slow path

### Static observation

Quoted attribute parsing builds a `StringBuilder` while scanning quoted content so escaped characters can be interpreted.

Many ordinary IPTV attributes contain quoted values with no escapes.

### Hypothesis

An escape-free fast path can return a substring directly and allocate a builder only after the first actual escape.

### Priority

Secondary. Fold into the parser optimization only if post-H1/H2 profiling shows attribute parsing is material.

### Correctness risks

- escaped quote/backslash behavior;
- unterminated quoted value behavior;
- attribute character-count accounting;
- whitespace/index advancement.

---

## H6 — repeated key normalization / map work

### Static observation

Attribute keys are normalized to lowercase and stored in a linked map, then common keys are read again when building `M3uEntry`.

### Hypothesis

For large playlists attribute handling may become measurable after line-reading overhead is reduced.

### Decision rule

Do not introduce a custom parser table, enum-key map or code generation without profiling. The current map preserves unknown metadata, which is a compatibility property and must not be silently lost.

---

## H7 — warning callback / object cost on malformed input

### Static observation

Warnings are counted for every warning but emitted to the sink only up to `maxReportedWarnings`.

### Hypothesis

Malformed/adversarial corpora could be dominated by warning handling rather than normal parser throughput.

### Measurement policy

Measure malformed-stress separately from normal playlist performance. Do not optimize normal code based on intentionally pathological warning-heavy input.

The hard bound on reported warnings must remain.

---

## Measurement matrix

| Case | Purpose | Required metrics |
| --- | --- | --- |
| 1k normal | functional baseline | time, alloc, digest |
| 10k normal | large realistic | p50/p95 time, alloc, GC, digest |
| 50k normal | stress | p50/p95 time, alloc, GC, digest |
| long-but-valid lines | line buffer pressure | time, peak/capacity evidence, digest |
| line at exact limit | boundary | exact pass/fail semantics |
| line over limit | safety | reason + line + limit unchanged |
| malformed encoding | decoder correctness | identical typed failure |
| escaped attributes | slow-path correctness | semantic digest |
| warning-heavy bounded sample | robustness | bounded warnings + time |

Do not turn 50k timings into a mandatory PR/release threshold until repeated variance evidence supports one.

## Attribution sequence

When executable measurement returns, use this order:

1. measure the current parser unchanged;
2. if line-reader CPU/allocation is material, write RED equivalence/boundary tests for a replacement reader;
3. implement H1/H2 together only if they are inseparable at the reader boundary;
4. remeasure;
5. stop if the remaining parser cost is no longer material;
6. only then consider H3/H4/H5 individually.

Do not stack five micro-optimizations into one PR and then attribute the gain to all of them.

## Rust/native decision gate

Rust/UniFFI/native parsing remains deferred.

It becomes eligible for an ADR only if all are true:

- optimized bounded Kotlin remains a top measured bottleneck on accepted corpus/device/host evidence;
- a native implementation demonstrates a material end-to-end win, not just a microbenchmark win;
- JNI/UniFFI copying, packaging size, ABI coverage, crash handling and supply-chain cost are included in the comparison;
- Android API26/36 and release/R8/package evidence is available;
- parser security/correctness contracts remain at least equivalent.

Until then, native code would add architecture/packaging risk without evidence.

## Stop condition while runner is unavailable

No production parser change should be made from these hypotheses alone. The next implementation action is an observable benchmark/profile baseline followed by RED equivalence tests.