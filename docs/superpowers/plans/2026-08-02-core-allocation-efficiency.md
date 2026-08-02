# Core Allocation Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce avoidable allocation/copying pressure in MuxTV's core ingestion and playback-control hot paths, establish Android allocation microbenchmarks, then optimize XMLTV/matching/guide and Room staging from measured evidence while preserving all correctness/security/identity contracts.

**Architecture:** Keep streaming ingestion, immutable revisions, Room ownership and process-owned Media3 unchanged. Stage 1 applies local allocation reductions that do not alter domain semantics. Stage 2 is rebased after PR #80 so XMLTV/matching/NowNext work operates on accepted production contracts. Structural database/native changes require repeated device evidence and a separate ADR.

**Tech Stack:** Kotlin 2.4, Coroutines/Flow, Room 3.0, AndroidX Benchmark 1.4.1, Compose for TV, Media3 1.10.1, OkHttp 5.3, Android API 26 minimum with current API 36 evidence and API 37 memory-limit readiness planned.

## Global Constraints

- Stable M3U-derived IDs must remain byte-for-byte identical.
- Do not change M3U/XMLTV input/security bounds in an allocation-only commit.
- Do not weaken caller-owned immutable snapshot semantics.
- Do not change source/EPG publication ownership, cancellation authority or previous-good reader boundaries.
- Do not change EPG normalization/matching semantics before issue #82 policy-version provenance exists.
- Do not remove the importer batch snapshot unless its ownership contract is replaced and proven.
- No performance threshold from fewer than the required comparable repetitions.
- No Rust/UniFFI, bundled SQLite, libmpv, second player engine or schema denormalization without measured evidence and ADR.
- Release performance comparisons use R8-enabled builds; emulator data is never represented as physical-TV performance.

---

### Task 0: Close the existing correctness stack without contaminating performance work

**Files:**
- No production files on `perf/core-allocation-stage1`.
- After PR #80 merges, update `README.md`, `.work/CURRENT-STATE.md`, `.work/meta/status.yaml` in a separate truth-sync branch.
- Rebuild PR #81 branch from the new `main` after #80 merge.

**Interfaces:**
- Consumes: PR #80 head `6f05b0db27e8b8d564caffab43d372c197c157fd` exact-head Full + API26/API36 evidence.
- Produces: `main` with Room v7/#71/#28 complete, then a clean #81 delta.

- [ ] **Step 1: Inspect PR #80 exact-head jobs**

Expected: Full and database/device matrix execute on the exact head. A queued/pending state is not a failure and does not justify changing product code.

- [ ] **Step 2: If both exact-head gates are green, run final review checks**

Check: PR head unchanged, unresolved review threads/comments, secret/redaction diff, generated Room v7 schema, direct and remote EPG E2E contracts.

- [ ] **Step 3: Mark #80 ready and squash-merge**

Expected: #71 and #28 close via the PR body only after the merge succeeds.

- [ ] **Step 4: Create repository-truth sync**

Record: new `main` SHA, Room v7, #76/#71/#28 completed, #29 active, #82 next correctness boundary, issue #27 parallel performance evidence.

- [ ] **Step 5: Rebuild/rebase #81 on the merged main**

Expected: #81 diff contains only app/navigation/Channels/player-session projection and tests, not #80 Room/matching history.

- [ ] **Step 6: Verify and merge #81**

Run focused player/api + Channels unit tests, app compile, real MediaController→MediaSessionService→ExoPlayer state smoke, focus/Player-Back journeys and applicable device gate before merge.

---

### Task 1: Reuse M3U line-buffer and decoder objects

**Files:**
- Modify: `catalog/ingest/src/main/kotlin/app/muxtv/catalog/ingest/StreamingM3uParser.kt`
- Test: `catalog/ingest/src/test/kotlin/app/muxtv/catalog/ingest/StreamingM3uParserTest.kt`
- Measure: existing `:core:testing:measureM3uParse`

**Interfaces:**
- Consumes: `BoundedTextLineReader.readLine(lineNumber: Long): String?` existing behavior.
- Produces: identical text/error behavior with retained bounded reusable byte storage and one configured `CharsetDecoder` per parse reader.

- [ ] **Step 1: Preserve behavioral contracts**

Retain tests for UTF-8 metadata, bare locators, CR/LF handling, BOM handling, malformed encoding and exact `maxLineBytes` rejection. Add a focused multi-line test if an existing test does not exercise buffer reset after a long line followed by a short line.

- [ ] **Step 2: Establish allocation baseline from existing deterministic corpus**

Record current `small-1k` host allocation median (~5.53 MiB from PR #53) and rerun on the current base when the runner is available. Do not turn the number into a unit-test threshold.

- [ ] **Step 3: Replace per-line accumulator construction with one reusable bounded accumulator**

Implementation shape:

```kotlin
private val bytes = ReusableByteArrayOutputStream(minOf(512, maxLineBytes))
private val decoder = charset.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)

fun readLine(lineNumber: Long): String? {
    bytes.reset()
    // existing byte-limit/newline semantics
    decoder.reset()
    return decoder.decode(bytes.asByteBuffer()).toString()
}
```

The reusable output stream exposes only a bounded read view of its protected backing array; it must not publish mutable bytes outside the reader.

- [ ] **Step 4: Replace private `firstNonBlank(vararg ...)` with fixed-arity selection**

Use null-coalescing calls so channel-number/user-agent/referrer extraction creates no vararg array. Preserve trimming semantics exactly.

- [ ] **Step 5: Run parser tests and deterministic measurement**

Compare parsed/skipped/warning counts, corpus SHA/identity, wall time and allocated bytes against the same profile/seed/source environment. Reject changes that alter parser output.

- [ ] **Step 6: Commit**

Commit message: `perf: reduce M3U line parsing allocations`.

---

### Task 2: Reuse SHA-256 and hex-output buffers in catalog identity generation

**Files:**
- Modify: `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogEntryIdentityFactory.kt`
- Test: `catalog/importer/src/test/kotlin/app/muxtv/catalog/importer/CatalogEntryIdentityFactoryTest.kt`

**Interfaces:**
- Consumes: exact existing `CatalogEntryIdentityFactory.create(...)` stable IDs.
- Produces: byte-identical stable IDs while reusing one 32-byte digest output buffer and one 64-char hex buffer per factory/import.

- [ ] **Step 1: Treat existing golden IDs as the mandatory RED/GREEN guard**

Do not modify expected golden IDs. Add repeated-call golden coverage if necessary to prove reused buffers cannot leak a previous digest into the next result.

- [ ] **Step 2: Use `MessageDigest.digest(output, offset, length)`**

Keep `value.toByteArray(UTF_8)` for this stage, but avoid a new digest-result byte array. Check that SHA-256 writes exactly 32 bytes.

- [ ] **Step 3: Reuse a 64-character hex buffer**

Fill all positions for every digest before converting to `String`; never return or expose the mutable buffer.

- [ ] **Step 4: Run importer identity/batching tests**

All IDs and 250/1 ordering must remain unchanged.

- [ ] **Step 5: Commit**

Commit message: `perf: reuse catalog identity digest buffers`.

---

### Task 3: Reduce playback request snapshot allocations

**Files:**
- Modify: `player/api/src/main/kotlin/app/muxtv/player/PlaybackModels.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSessionRequest.kt`
- Test: `player/api/src/test/kotlin/app/muxtv/player/PlaybackModelsTest.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSessionRequestOwnershipTest.kt`
- Test: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/PlaybackSessionRequestOwnershipAndroidTest.kt`

**Interfaces:**
- Consumes: immutable header snapshot semantics and Bundle backward compatibility.
- Produces: zero-entry shared immutable map, one-entry immutable singleton, normal linked snapshot for 2+ entries; optional absence of empty headers Bundle.

- [ ] **Step 1: Preserve mutation/ownership contracts**

Caller mutation after construction must not affect requests; mutation through a mutable cast must throw; conversion from `PlaybackRequest` to `PlaybackSessionRequest` remains independently owned.

- [ ] **Step 2: Add Android contract that empty request bundles omit the nested header container**

`PlaybackSessionRequest.fromBundle(request.toBundle())` must still round-trip as an empty header map.

- [ ] **Step 3: Implement size-specialized immutable snapshot helper**

```kotlin
return when (size) {
    0 -> Collections.emptyMap()
    1 -> entries.first().let { Collections.singletonMap(it.key, it.value) }
    else -> Collections.unmodifiableMap(LinkedHashMap(this))
}
```

- [ ] **Step 4: Omit `KEY_HEADERS` when `requestHeaders.isEmpty()`**

Do not change decoding of old bundles that contain an empty nested bundle.

- [ ] **Step 5: Run player API, Media3 unit and Android ownership tests**

- [ ] **Step 6: Commit**

Commit message: `perf: avoid empty playback header containers`.

---

### Task 4: Add Android allocation Microbenchmark infrastructure

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `benchmark/micro/build.gradle.kts`
- Create: `benchmark/micro/src/main/AndroidManifest.xml`
- Create: M3U/XMLTV benchmark sources and deterministic fixture adapters under `benchmark/micro/src/androidTest/...`
- Modify: validation/measurement scripts only as needed for explicit benchmark mode; do not add expensive microbenchmarks to every Fast build.

**Interfaces:**
- Consumes: production parser APIs and repository-owned deterministic fixtures.
- Produces: non-debuggable Android instrumentation benchmark reports containing time and allocation metrics plus exact device/build/workload identity.

- [ ] **Step 1: Add dedicated benchmark module**

Use AndroidX Benchmark JUnit4 with a non-debuggable benchmark build. Keep benchmark dependencies outside production runtime modules.

- [ ] **Step 2: Add M3U parse benchmark**

Benchmark a no-retention sink over deterministic input; fixture construction is outside `measureRepeated` where possible.

- [ ] **Step 3: Add XMLTV parse benchmark**

Use repository-owned bounded fixtures first; add a generated larger synthetic fixture only if its generator is deterministic and provider-neutral.

- [ ] **Step 4: Archive JSON benchmark reports separately from correctness evidence**

Do not define pass/fail latency/allocation budgets in the first dataset.

- [ ] **Step 5: Commit**

Commit message: `perf: add Android core allocation microbenchmarks`.

---

### Task 5: Optimize XMLTV object churn after PR #80 merge

**Files:**
- Modify: `catalog/ingest/src/main/kotlin/app/muxtv/catalog/ingest/StreamingXmltvParser.kt`
- Modify: `catalog/ingest/src/main/kotlin/app/muxtv/catalog/ingest/XmltvModels.kt`
- Test: existing XMLTV parser/security/structural tests
- Benchmark: `benchmark/micro` XMLTV benchmark

**Interfaces:**
- Consumes: accepted PR #80 parser behavior and immutable public XMLTV models.
- Produces: same records/warnings/security failures with lower empty-list/string/scratch-buffer churn.

- [ ] **Step 1: Fast-path immutable snapshots**

Use canonical immutable empty list for size 0, singleton immutable list for size 1, unmodifiable `ArrayList` copy otherwise.

- [ ] **Step 2: Remove duplicate `StringBuilder.toString().trim()` conversion**

Construct `XmltvText` from the already-computed `value` in `finishTextCapture()`.

- [ ] **Step 3: Reuse `GuardedXmltvInputStream.skip()` scratch storage**

Keep DOCTYPE scanning and byte accounting identical.

- [ ] **Step 4: Measure before adding lazy builder collections**

If allocations are still dominated by empty `ProgrammeBuilder` collections, convert those fields to lazily initialized mutable lists with read-only empty views at freeze time. Do not combine this with event-bridge redesign.

- [ ] **Step 5: Commit each independently measurable optimization**

Example messages: `perf: reduce XMLTV snapshot allocations`, `perf: reuse XMLTV skip buffer`.

---

### Task 6: Optimize EPG matching and Now/Next projection after PR #80/#82 contracts are stable

**Files:**
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomEpgMatchingStore.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomEpgGuideRepository.kt`
- Test: matching decision/normalizer/store and guide repository tests
- Benchmark: matching/guide microbenchmarks

**Interfaces:**
- Consumes: exact deterministic matching ladder and policy-version contract.
- Produces: identical match entities/summary/NowNext rows with fewer temporary sets/sequences/passes.

- [ ] **Step 1: Replace per-key evidence sets with collision-on-demand storage**

A unique normalized key stores one canonical ID without a set; allocate a set only when another distinct ID collides. Preserve duplicate same-ID collapse and ambiguity count semantics.

- [ ] **Step 2: Build match entities and summary counters in one pass**

Remove three full `matches.count { ... }` passes.

- [ ] **Step 3: Replace tiny Now/Next sequence chains with direct loops**

The SQL candidate set is already bounded to previous/next; avoid sequence objects for 0–2 rows.

- [ ] **Step 4: Benchmark and verify exact outputs**

No matcher normalization change is permitted in this task.

---

### Task 7: Decide Room staging optimization from repeated device evidence

**Files:**
- Measure first; production files only after decision.
- Potentially modify: `catalog/importer/.../CatalogRevisionImporter.kt`
- Potentially modify: `core/database/.../RoomSourceRevisionStore.kt`
- Existing deterministic corpus/measurement infrastructure.

**Interfaces:**
- Consumes: five-run `current-normal`, `old-edge-normal`, `current-low-ram` data plus allocation/GC evidence.
- Produces: evidence-backed batch-size or Room materialization change, or an explicit decision to keep current code.

- [ ] **Step 1: Complete issue #27 repeated datasets**

Do not pool different API/RAM/runtime classes into one distribution.

- [ ] **Step 2: A/B 250 vs 500 staging batches**

Record wall time, allocations, peak Java heap/GC and database footprint on current and low-RAM profiles.

- [ ] **Step 3: Change batch size only if evidence supports it**

The independent immutable batch snapshot remains required unless a replacement ownership mechanism is explicitly proven.

- [ ] **Step 4: Investigate three-list entity materialization only if allocation traces identify it as material**

Avoid schema denormalization unless database and allocation evidence justify the migration cost.

---

### Task 8: Release/Compose memory hardening before alpha

**Files:**
- `app/tv/build.gradle.kts`
- new macrobenchmark/baseline-profile module(s)
- Compose compiler metrics configuration
- release checklist/evidence docs

**Interfaces:**
- Consumes: stable daily-use Channels/Player/Guide/Search flows.
- Produces: R8-minified release, app-specific Baseline/Startup Profiles, macrobenchmarks, API 37 memory-limit evidence.

- [ ] **Step 1: Enable and validate R8/resource shrinking for release**

Use release-mode performance evidence; do not benchmark debug performance as product performance.

- [ ] **Step 2: Enable Compose compiler stability/metrics reports**

Inspect actual unstable/skippable composables before annotating types or introducing immutable collections.

- [ ] **Step 3: Add Macrobenchmark critical user journeys**

Cold startup, Channels scroll, Channels→Player→Back, Guide/Search journeys.

- [ ] **Step 4: Generate app-specific Baseline and Startup Profiles**

Measure profile-on/profile-off on physical hardware before making improvement claims.

- [ ] **Step 5: Add Android 17/API37 memory-limiter stress**

Exercise supported flows under platform memory constraints and inspect `ApplicationExitInfo` for memory-limiter termination.

---

## Self-review

- Spec coverage: immediate M3U, identity and player allocations are implementable independently; XMLTV/matching are sequenced after #80; Room structural work is gated by #27; release work is left for the alpha gate.
- Correctness coverage: stable IDs, immutable request ownership, security limits, durable publication and matching semantics are explicit invariants.
- Measurement coverage: host allocation baseline, Android Microbenchmark, allocation profiler, repeated Room datasets and Macrobenchmark are separated by purpose.
- No placeholders/TODOs remain; structural/native alternatives are explicitly non-goals until evidence exists.
