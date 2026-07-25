# MuxTV Performance and Reliability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the Media3/Channels/Player/Sources/Onboarding/Registry stack without hiding predecessor failures, harden the measured source-to-playback path, and delay XMLTV/Guide expansion until correctness and performance gates are evidence-backed.

**Architecture:** Preserve the Android-first modular monolith. Room remains the local source of truth, credentials remain encrypted and opaque, imports remain streaming and revision-based, and one `MediaSessionService` owns playback. Optimize incrementally: first data integrity and confirmed allocations, then cancellation/focus/network correctness, then benchmarks before structural database or player changes.

**Tech Stack:** Kotlin 2.4.10, AGP 9.3.0, Gradle 9.5.0, JDK 17, Compose BOM 2026.06.00, Compose for TV, Room 3.0.0, WorkManager 2.11.2, Media3 1.10.1, OkHttp 5.3.0, AndroidX Tracing 1.3.0, Hilt, Coroutines/Flow, Windows self-hosted GitHub Actions.

## Global Constraints

- `minSdk = 26` remains an executable product promise.
- PR #22 is based directly on PR #20 and contains one bounded runtime package: atomic catalog staging, stable-ID-compatible importer hardening, and secret-safe measurements for that exact path.
- PR #21 was an automatically closed technical attempt created while #20 moved; it is superseded by #22 and must not be merged or used as a roadmap stage.
- PR #15–#20 must still be rebuilt, validated, and merged sequentially. A descendant result never proves its predecessor.
- PR #20 remains the durable onboarding-registry slice.
- No XMLTV schema, Guide grid, Doctor persistence, Smart Channel automation, Rust, C++, LibVLC, or mpv enters before the remaining hardening gates.
- Secrets never enter Room diagnostics, WorkManager Data, navigation routes, logs, exceptions, evidence manifests, trace section names, or public `toString()` output.
- Failed refresh, onboarding, registry persistence, staging, or EPG import never replaces the previous-good active revision or leaves an unsafe dangling credential reference.
- `CancellationException` is rethrown after bounded cleanup.
- UI never owns ExoPlayer and never reads staging revisions.
- Schema, ID, query, player, or measurement changes require compatibility tests and exact-head evidence.
- Emulator evidence does not prove real codec, HDR, passthrough, Fire OS, zapping, or low-end performance.

## Current assessment — 2026-07-24

- PR #15 continues to move through Media3 1.10, lint, release-order, and instrumentation fixes; exact-head Full remains its merge gate.
- PR #16 accumulated predecessor-repair history while #15 moved and must be rebuilt as one final functional commit.
- PR #17 targets an older #16 head; #18, #19, and #20 are transitively stale until sequential rebuilding.
- PR #19 now has domain-separated source IDs, conditional inactive-source cleanup, and credential retention when source metadata is active or changed.
- PR #20 adds the previously missing durable preparation registry: Room schema v4, 24-hour TTL, bounded startup cleanup, rollback after registry-write failure, and retention of incomplete cleanup records. It still requires final migration/schema/Hilt evidence.
- Automatic pull-request validation targets only `main`; stacked PRs targeting `feat/*` receive no automatic Full run.
- The active channel query is a future large-catalog risk, but there is not yet evidence that a materialized projection, FTS5, or pagination is required for alpha.

## Dependency adoption decision for PR #22

### Adopt now

- `androidx.tracing:tracing:1.3.0` in `catalog:importer` and `core:database`.
- Stable asynchronous trace sections for parsing, one batch materialization/transaction, and revision activation.
- Static trace names only. No URL, path, source ID, channel ID, credential ID, revision number, header, exception text, or user-controlled string is included.
- No per-entry tracing because 10k–100k playlists would create excessive trace events and alter the hot path being measured.

The operations are suspend boundaries and may resume on another thread. Therefore PR #22 uses asynchronous trace sections with unique process-local cookies rather than synchronous `beginSection`/`endSection` pairs that must end on the same thread.

### Defer to their owning PRs

- `media3-datasource-okhttp`: playback transport PR.
- UI Automator: TV focus/navigation PR.
- Coil: channel presentation PR.
- Turbine: Flow-heavy state-holder PRs.
- LeakCanary and JankStats: app-level observability PR.
- Dependency Analysis plugin: repository build-health PR.
- Baseline Profile and Macrobenchmark module: measured alpha gate.

### Do not add to PR #22

- Bundled SQLite driver, FTS5, Paging, materialized projections, ID migrations, a second player engine, Rust, or compiler plugins. None is required to prove atomic staging or the allocation changes in this PR.

## Corrections to the previous proposal

### Keep

- Bounded streaming M3U parsing.
- Immutable Room revisions and active pointer.
- Keystore-backed credentials.
- Durable pending-preparation registry from PR #20.
- One service-owned Media3 player.
- Initial bounded 200-channel slice.
- Sequential oldest/current Android TV matrix.

### Defer until measured

- Integer/BLOB ID migration.
- Materialized active-channel projection.
- FTS5/transliteration.
- Paging 3 or custom keyset pagination.
- Asynchronous old-revision pruning.
- Mandatory R8 gate.
- A second player engine.

### Reject for alpha

- Rust/UniFFI for parser, hashing, Room, networking, or Media3.
- Lampa global singleton/event-bus architecture or remote JavaScript plugins.
- GPL implementation copying into BSD-3-Clause MuxTV.
- Replacing Media3 without a reproducible unsupported-stream corpus.

---

## Implemented in PR #22

### Task 1: Make catalog staging atomic

**Files:**
- `core/database/src/main/kotlin/app/muxtv/database/SourceRevisionDao.kt`
- `core/database/src/main/kotlin/app/muxtv/database/RoomSourceRevisionStore.kt`
- `core/database/src/androidTest/kotlin/app/muxtv/database/CatalogStagingAtomicityTest.kt`

- [x] Add one `@Transaction` DAO boundary for canonical, provider, and stream-variant writes.
- [x] Build all three entity collections in one pass.
- [x] Add a duplicate-stream-variant failure contract asserting zero provider rows remain.
- [ ] Execute the Android contract on oldest/current TV images.

```kotlin
@Transaction
open suspend fun stageCatalogBatch(
    canonicalChannels: List<CanonicalChannelEntity>,
    providerChannels: List<ProviderChannelEntity>,
    streamVariants: List<StreamVariantEntity>,
) {
    upsertCanonicalChannels(canonicalChannels)
    insertProviderChannels(providerChannels)
    insertStreamVariants(streamVariants)
}
```

### Task 2: Reduce importer allocations without changing IDs

**Files:**
- `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogEntryIdentityFactory.kt`
- `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogRevisionImporter.kt`
- importer unit tests.

- [x] Capture exact golden IDs for TVG/fallback, Cyrillic normalization, source, revision, and ordinal cases.
- [x] Scope one reusable `MessageDigest` to one import.
- [x] Replace `String.format` byte conversion with direct lowercase hex conversion.
- [x] Compute `providerKey` once per entry.
- [x] Replace `batch.toList()` with buffer swapping.
- [x] Add a 251-entry 250/1 batch-handoff contract.
- [ ] Execute focused Gradle tests and exact-head Full.

### Task 3: Establish a benchmark-compatible trace contract

**Files:**
- `gradle/libs.versions.toml`
- `catalog/importer/build.gradle.kts`
- `core/database/build.gradle.kts`
- importer and Room source-revision implementations.

- [x] Add stable AndroidX Tracing 1.3.0.
- [x] Emit `MuxTV.catalog.parse` around the streaming parser.
- [x] Emit `MuxTV.catalog.stageBatch` around one-pass entity materialization and the atomic Room transaction.
- [x] Emit `MuxTV.catalog.activate` around active-revision activation.
- [x] Use asynchronous sections so suspension does not violate same-thread nesting requirements.
- [x] Skip cookie allocation and trace calls when tracing is disabled.
- [x] Keep names static and secret-free.
- [ ] Capture the sections with Benchmark 1.4.1 `TraceSectionMetric` in the later benchmark PR.

Required validation:

```powershell
.\gradlew.bat :catalog:importer:testDebugUnitTest --no-daemon
.\gradlew.bat :catalog:importer:lintDebug --no-daemon
.\gradlew.bat :core:database:assembleDebugAndroidTest --no-daemon
.\gradlew.bat :core:database:lintDebug --no-daemon
```

---

## Remaining hardening sequence

### Task 4: Close and normalize PR #15–#20

- [ ] Finish PR #15 Full on its exact head.
- [ ] Squash-merge #15 only after unit tests, instrumentation compilation, lint, debug/release assembly, and evidence manifest pass on the same SHA.
- [ ] Rebuild #16 from new `main`, replaying only Channels/navigation behavior into one commit.
- [ ] Repeat for #17, #18, #19, and #20 without carrying predecessor repair commits into descendant functional histories.
- [ ] For #20, commit generated Room schema `4.json` and prove `MIGRATION_3_4` from representative v3 data.
- [ ] Run consolidated DeviceMatrix for Channels → Player → Back, Sources mutations, onboarding, registry cleanup, MediaSession, and controller connection.

Exact-head command:

```powershell
$branch = (git branch --show-current).Trim()
$commit = (git rev-parse HEAD).Trim()
pwsh -NoProfile -File .\tools\verify-local.ps1 `
  -Mode Full `
  -SourceBranch $branch `
  -SourceCommit $commit
```

### Task 5: Add stack-aware validation

**Files:** `.github/workflows/self-hosted-validation.yml`, `tools/ci/Test-StackAncestry.ps1`, and tests.

- [ ] Test valid ancestry, moved base, missing local ref, and normal `main` target.
- [ ] Run `git merge-base --is-ancestor $ExpectedBaseSha $HeadSha` before Android/Gradle setup.
- [ ] Support future `main`, `feat/**`, `perf/**`, `infra/**`, and `docs/**` targets after the workflow exists on those target branches.
- [ ] Never use `pull_request_target` to execute untrusted branch code.
- [ ] Prove stale stacks fail before Gradle and valid stacks reach Full.

### Task 6: Preserve cancellation and real TV focus

- [ ] Replace cancellable Sources `runCatching` with explicit `try/catch/finally` and rethrow the same cancellation instance.
- [ ] Clear per-source busy state in `finally` without showing a generic error after cancellation.
- [ ] Store channel ID, list index, and offset; restore actual focus through `FocusRequester` after scroll/composition.
- [ ] Remove the visual bullet as a substitute for focus.
- [ ] Device journey: item 150 → Player → Back → item 150 visible and actually focused.
- [ ] Test removed/reordered fallback and process recreation on oldest/current TV images.

### Task 7: Unify Media3 playback networking

- [ ] Test A→B header isolation for manifest, segment, redirect, and retry requests.
- [ ] Test a failed first controller future is evicted and a second connection can succeed.
- [ ] Replace `DefaultHttpDataSource.Factory` with Media3 OkHttp datasource using the established shared dispatcher/pool and playback timeout profile.
- [ ] Apply headers immutably per media request.
- [ ] Replace blocking `Future.get` bridges with a cancellation-aware suspend adapter plus timeout.
- [ ] Run service/controller instrumentation and DeviceMatrix.

### Task 8: Measure before structural catalog changes

- [ ] Generate deterministic 1k, 10k, 50k, and 100k fixtures.
- [ ] Record importer median/p95, heap delta, transaction count, database size, activation time, and active-channel query time.
- [ ] Capture `MuxTV.catalog.parse`, `MuxTV.catalog.stageBatch`, and `MuxTV.catalog.activate` through `TraceSectionMetric`.
- [ ] Store secret-free `EXPLAIN QUERY PLAN` evidence.
- [ ] Keep the 200-channel query when measured sources fit and query p95 is acceptable.
- [ ] Design projection/keyset only if the 100k active query exceeds 120 ms p95.
- [ ] Design FTS5 only after search UX exists and LIKE scan is measured.
- [ ] Move pruning out of activation only when lock duration exceeds the accepted budget.
- [ ] Design ID storage migration only when indexes/IDs are a material database-size or query cost.

## Reference adoption

Use official `androidx/media`, `android/tv-samples`, Android Room/SQLite, Gradle, and Baseline Profile sources first. Use Jellyfin Android TV, StreamVault, M3UAndroid, Kodi IPTV Simple, IPTVnator, Lampa, mpv-android, VLC Android, and iptv-org only through pinned `adapt`, `clean-room`, `corpus-only`, or `reject` decisions.

From `yumata/lampa-source`, adapt only remote-first focus retention, screen/controller collection behavior, and weak-TV interaction economy. Reject global mutable singletons, plaintext source URLs, DOM architecture, and remotely executable JavaScript plugins.

## Corrected roadmap

```text
#20  durable onboarding preparation registry
#21  closed technical attempt; superseded by #22
#22  atomic staging + importer hardening + secret-safe performance traces
#23  stacked validation and ancestry gate
#24  source cancellation + TV focus restoration
#25  shared OkHttp Media3 transport + reconnect
#26  benchmark evidence and only justified catalog optimization
#27  streaming XMLTV revisions
#28  Guide and now/next
#29  playback recovery and TV Doctor Lite
#30  Baseline Profile, R8 and physical-device alpha gate
```

## Completion gate

- PR #22 remains a bounded descendant of the current #20 head before final rebuilding.
- #15–#20 are sequentially merged with independent exact-head evidence.
- Room v4 migration and generated schema are validated.
- Failed staging leaves no partial provider rows.
- Importer golden IDs remain unchanged.
- Catalog traces are static, secret-free, and capturable through `TraceSectionMetric`.
- Stale stacks fail before Gradle.
- Source cancellation propagates unchanged.
- Channels → Player → Back restores actual focus.
- Playback headers cannot leak between channels.
- Failed controller connection does not poison later attempts.
- 1k/10k/50k/100k evidence drives projection/FTS/pagination decisions.
- XMLTV starts only after these invariants are green or explicitly waived in a quantified ADR.
