# Core Allocation Efficiency Design

**Date:** 2026-08-02

## Objective

Reduce short-lived Java/Kotlin allocation churn and avoidable copying in MuxTV's repeatedly executed ingestion, matching, guide-projection and playback-control paths without changing stable identifiers, security boundaries, durable publication ownership, Room reader semantics or playback-engine ownership.

The work is evidence-led. Existing descriptive measurements remain the baseline; new Android Microbenchmarks and allocation profiling extend that evidence before structural changes such as schema denormalization, alternate storage, Rust/UniFFI or another playback engine are considered.

## Current evidence

- The production `StreamingM3uParser` already streams records and does not materialize a playlist-wide list.
- The existing deterministic `small-1k` host measurement recorded roughly 5.53 MiB of current-thread allocated bytes while parsing a 269,079-byte corpus. This is host/JDK evidence, not an Android TV or weak-ARM claim.
- Android Room measurements identified staging as the dominant measured database path: a 250-entry stage batch was approximately 128–214 ms in the initial two-run API 36 smoke and staging 10k was approximately 3.27–3.95 s. The variation is too high for a threshold or an immediate batch-size change.
- Activation and bounded read projections were comparatively small in the same smoke; Player control-plane micro-operations were measured in microseconds and are not currently a runtime bottleneck.

## Allocation review findings

### A. M3U parser — immediate high-confidence target

`BoundedTextLineReader.readLine()` currently creates, per line:

1. a new `ByteArrayOutputStream`;
2. its backing buffer growth where needed;
3. a copied `byte[]` through `toByteArray()`;
4. a new `CharsetDecoder`;
5. a `ByteBuffer` wrapper;
6. the decoded `String`.

The parser also creates vararg arrays through `firstNonBlank(vararg ...)` for several fields per entry.

Design: reuse a bounded line byte accumulator and configured decoder across lines, while retaining the exact line-byte limit and malformed/unmappable-input behavior. Replace the private vararg selector with fixed-arity nullable helpers. No parser output or public API changes.

### B. Catalog identity — immediate high-confidence target

`CatalogEntryIdentityFactory` already correctly scopes one mutable SHA-256 `MessageDigest` to one import. However each stable ID currently allocates the digest result byte array and a fresh 64-character hex array, three times per M3U entry.

Design: reuse an exact 32-byte digest output buffer and 64-character hex buffer inside the import-scoped factory. Preserve the exact UTF-8 hash inputs and all existing golden stable IDs. Normalization is explicitly out of scope because identity changes would be a data migration, not a micro-optimization.

### C. Playback request snapshots — small, safe target

`PlaybackRequest` and `PlaybackSessionRequest` must own immutable header snapshots. Their current helper always allocates a `LinkedHashMap` plus unmodifiable wrapper, including for empty and one-entry maps.

Design: preserve ownership and mutation rejection while using immutable empty/singleton maps for sizes 0/1; retain the existing linked snapshot for larger maps. `PlaybackSessionRequest.toBundle()` may omit the nested headers `Bundle` when there are no headers because the decoder already treats an absent headers bundle as empty.

This is secondary to ingest but low-risk and affects every playback request.

### D. XMLTV parser — stage 2 after PR #80 lands

The current SAX parser has strong streaming/security bounds, but allocation review found:

- `ProgrammeBuilder` eagerly allocates ten mutable lists for every programme, even when most metadata classes are absent;
- `ChannelBuilder` eagerly allocates three lists;
- `finishTextCapture()` first creates `value = text.toString().trim()` and for common `XmltvText` fields calls a helper that converts/trims the same `StringBuilder` again;
- immutable model construction copies and wraps every list, including empty lists;
- `GuardedXmltvInputStream.skip()` allocates a scratch byte array on each call;
- the blocking-SAX-to-suspending-sink bridge creates one event wrapper per emitted record.

Stage 2 should first eliminate empty/singleton immutable-list copies and duplicate text conversion, then measure lazy builder collections and bridge overhead before redesigning the event boundary. The channel bridge is currently part of the cancellation/backpressure architecture and must not be replaced only to save allocations.

### E. EPG matching — stage 2 after PR #80 lands

`RoomEpgMatchingStore` builds three evidence indices. Each index currently allocates a `MutableSet` for every normalized evidence key even when that key maps to only one canonical channel. It also creates the final match list and then traverses that list three additional times to count matched/ambiguous/unresolved rows.

Design: after PR #80 is merged and exact behavior is frozen, use a single-candidate representation that allocates a collision set only when a second distinct canonical candidate appears, and accumulate summary counters during entity construction. Preserve matching order and ambiguity semantics exactly.

Any normalization semantic change belongs to issue #82 and requires a matching-policy-version bump/rebuild; allocation work must not alter matching semantics.

### F. Now/Next projection — stage 2

`RoomEpgGuideRepository` operates on a bounded set (maximum 200 channel IDs) and the SQL returns only previous/next programme candidates. Several projection operations use Sequences over lists that normally contain zero to two rows. For such tiny bounded collections, direct loops avoid sequence iterator/lambda machinery and are clearer.

This is a secondary optimization; SQL/index behavior and query cardinality should be measured before changing the database shape.

### G. Room staging — evidence first

The importer intentionally copies each 250-entry staging batch before handing it to storage so the parser can safely reuse its mutable buffer. The Room store then materializes three entity arrays because three normalized tables are inserted transactionally. These allocations are not obviously redundant; they enforce ownership and schema representation.

Do not remove the batch snapshot or denormalize the schema in Stage 1. Complete the five-run current/old-edge/low-RAM series, add allocation evidence around staging, then A/B 250 vs 500 batch sizes. A larger batch is accepted only if wall time improves without unacceptable peak-memory/GC behavior on the low-RAM profile.

## Measurement architecture

### Host baseline

Keep the existing deterministic M3U measurement as a fast before/after allocation and timing comparison. It remains host-specific evidence.

### Android Microbenchmark

Add a dedicated non-debuggable microbenchmark module for repeatedly executed CPU/allocation hot paths:

- M3U parse over deterministic small/medium fixtures;
- XMLTV parse over bounded fixtures and a generated larger fixture;
- EPG normalization/matching once the PR #80 implementation is on `main`;
- bounded Now/Next projection.

Record time and allocation count from Jetpack Benchmark. Use profiler modes only for diagnosis, not as the normal gate.

### Memory profiler

Use Android Studio Java/Kotlin allocation recording for representative and extreme flows to identify type/call-stack churn. Allocation profiling is diagnostic evidence; the profiler overhead itself is not a product performance number.

### Macrobenchmark and profiles

Before alpha, add a benchmark release variant, Macrobenchmark and app-specific Baseline/Startup Profiles covering:

- cold startup to Channels;
- Channels scrolling;
- Channels → Player → Back;
- Guide/Search once implemented;
- source refresh entry and visible state transition where deterministic.

Release performance claims use R8-enabled builds and physical-device evidence where applicable.

### Android 17 memory-limit readiness

Add API 37 memory-limit stress to release hardening. Exercise app flows with the platform memory limiter, inspect `ApplicationExitInfo` for `REASON_OTHER` plus `MemoryLimiter:AnonSwap`, and retain exact device/RAM/build metadata. This is a compatibility/reliability gate, not a reason to target API 37 immediately.

## Reference implementations

- `android/nowinandroid`: use as the main reference for benchmark/release variants, Baseline Profiles and Compose performance tooling, not as a template for IPTV domain architecture.
- `androidx/media`: authoritative current Media3/ExoPlayer implementation reference. Keep MuxTV on Media3; the legacy `google/ExoPlayer` repository is deprecated.
- Jellyfin Android TV / VLC Android: use selectively for TV/live-playback product and device-compatibility ideas. Their architecture does not by itself justify adopting their native/player stack.

## Invariants

1. Stable M3U-derived IDs are byte-for-byte unchanged.
2. M3U/XMLTV security and input bounds remain unchanged or become stricter only in separately reviewed correctness work.
3. Caller-owned mutable headers/lists cannot mutate persisted/request model state after construction.
4. Secret/redaction behavior is unchanged.
5. Staging batch ownership remains independent from the parser's reusable mutable buffer.
6. Source/EPG immutable revision and publication-ownership semantics are unchanged.
7. MediaSessionService remains the process playback owner; allocation work does not introduce a second player/store.
8. No normalization semantic changes are bundled with allocation refactoring.
9. No performance threshold is introduced from fewer than the required comparable repetitions.
10. Rust/UniFFI, bundled SQLite, libmpv and schema denormalization remain evidence-gated follow-ups.

## Delivery order

1. Close the already-finished correctness stack: PR #80 gates/merge → repository truth sync → rebase/validate/merge PR #81.
2. Land Core Allocation Stage 1 on a clean branch: M3U line-reader/decoder reuse, fixed-arity selectors, identity digest/hex buffer reuse, empty/singleton playback snapshots.
3. Compare the existing deterministic host M3U before/after allocation/timing evidence and inspect Android allocation call stacks.
4. Add Android Microbenchmark infrastructure and allocation benchmarks.
5. After PR #80 is on `main`, execute XMLTV and matching/guide Stage 2 with behavior-preserving micro-optimizations.
6. Complete issue #27's five-run device profiles, then decide Room batch-size and other structural optimizations from evidence.
7. Add Macrobenchmark, R8 release hardening, Baseline/Startup Profiles and API 37 memory-limit stress before the alpha gate.
