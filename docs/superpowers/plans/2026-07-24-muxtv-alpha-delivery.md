# MuxTV Alpha Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a usable Android TV alpha that can add and refresh a remote M3U source, browse active channels with a D-pad, play them through a process-owned Media3 session, show now/next XMLTV data, and preserve the previous-good catalog when refresh or playback fails.

**Architecture:** Keep MuxTV as an Android-first modular monolith. Room remains the local source of truth; encrypted credentials are resolved only at execution boundaries; the UI reads only active catalog revisions; ExoPlayer and MediaSession are owned by a `MediaSessionService`; source refresh, playback resolution and diagnostics expose typed secret-safe results.

**Tech Stack:** Kotlin 2.4.10, AGP 9.3.0, JDK 17, Compose BOM 2026.06.00, Compose for TV, Room 3.0.0, WorkManager 2.11.2, Media3 1.10.1, OkHttp 5.3.0, Hilt, Coroutines/Flow, Windows self-hosted GitHub Actions.

## Global Constraints

- `minSdk = 26` remains an executable product promise.
- No Rust, C++, LibVLC or mpv in the alpha path; Media3 is the primary player behind `player:api`.
- No raw source URL, query token, cookie, authorization value or referrer in Room diagnostics, WorkManager Data, logs, exceptions, evidence manifests or public `toString()` output.
- UI never reads staging revisions.
- Failed refresh never replaces the current active revision.
- D-pad and focus restoration are first-class acceptance criteria.
- Every runtime boundary change passes self-hosted `Full`; Room, WorkManager, manifest and Media3 service changes additionally pass `DeviceMatrix`.
- Emulator evidence does not prove real codec, HDR, passthrough, zapping or low-end device performance.

---

## Delivery sequence

1. Finish PR #13: durable source scheduling and cancellation correctness.
2. Add active playback catalog contracts and Room projections.
3. Add process-owned Media3 playback service and controller connector.
4. Replace Channels placeholder with a real TV-first channel browser and player route.
5. Add source-management UI over the existing secure refresh/scheduling path.
6. Add streaming XMLTV import plus now/next queries.
7. Add playback recovery and TV Doctor Lite.
8. Add exact-match Smart Channels and stream variants.
9. Add performance, release and physical-device evidence gates.

---

### Task 1: Complete coroutine cancellation boundary in PR #13

**Files:**
- Existing test: `catalog/sync/src/test/kotlin/app/muxtv/catalog/sync/WorkerBoundaryTest.kt`
- Create: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/WorkerBoundary.kt`
- Existing caller: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshWorker.kt`

**Interfaces:**
- Produces: `internal suspend inline fun <T> runWorkerBoundary(crossinline block: suspend () -> T): Result<T>`
- Contract: ordinary `Exception` becomes `Result.failure`; `CancellationException` is rethrown unchanged.

- [x] **Step 1: Add the failing contract test**

The committed `WorkerBoundaryTest` covers success, ordinary failure and cancellation identity.

- [x] **Step 2: Run Full and verify RED**

Run: `pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon`

Expected before implementation: compilation failure because `runWorkerBoundary` is unresolved.

- [ ] **Step 3: Add the minimal implementation**

```kotlin
package app.muxtv.catalog.sync

import kotlinx.coroutines.CancellationException

internal suspend inline fun <T> runWorkerBoundary(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
```

- [ ] **Step 4: Run the focused unit test**

Run: `.\gradlew.bat :catalog:sync:testDebugUnitTest --tests app.muxtv.catalog.sync.WorkerBoundaryTest --no-daemon`

Expected: 3 tests, 0 failures.

- [ ] **Step 5: Run self-hosted Full on the exact head**

Expected: build logic, unit tests, Room/KSP/Hilt compilation, lint, debug assembly and release assembly all succeed.

---

### Task 2: Bound refresh runtime below the stale lease threshold

**Files:**
- Modify: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshWorker.kt`
- Modify: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshOutcomeMapper.kt`
- Test: `catalog/sync/src/test/kotlin/app/muxtv/catalog/sync/SourceRefreshOutcomeMapperTest.kt`
- Create: `catalog/sync/src/test/kotlin/app/muxtv/catalog/sync/RefreshRuntimePolicyTest.kt`

**Interfaces:**
- Produces constants `REFRESH_TIMEOUT_MILLIS = 20 * 60 * 1000L` and `LEASE_STALE_AFTER_MILLIS = 30 * 60 * 1000L`.
- Produces `SourceRefreshOutcomeMapper.runtimeTimeout()` with family `WORK`, code `TIMEOUT`, retryable `true`.

- [ ] **Step 1: Add tests for the timeout decision and invariant**

```kotlin
@Test
fun runtimeTimeoutIsRetryableAndSecretSafe() {
    val decision = SourceRefreshOutcomeMapper.runtimeTimeout()
    assertThat(decision.state).isEqualTo(SourceRefreshRunState.FAILED)
    assertThat(decision.resultFamily).isEqualTo("WORK")
    assertThat(decision.resultCode).isEqualTo("TIMEOUT")
    assertThat(decision.retryable).isTrue()
}

@Test
fun refreshTimeoutIsLowerThanStaleLeaseThreshold() {
    assertThat(REFRESH_TIMEOUT_MILLIS).isLessThan(LEASE_STALE_AFTER_MILLIS)
}
```

- [ ] **Step 2: Wrap only remote refresh/import execution in `withTimeout`**

```kotlin
return try {
    withTimeout(REFRESH_TIMEOUT_MILLIS) {
        SourceRefreshOutcomeMapper.map(sourceRefresher.refresh(request))
    }
} catch (_: TimeoutCancellationException) {
    SourceRefreshOutcomeMapper.runtimeTimeout()
}
```

Catch `TimeoutCancellationException` inside `refresh`; continue rethrowing external `CancellationException` from `doWork`.

- [ ] **Step 3: Run sync unit tests and Full**

Run: `.\gradlew.bat :catalog:sync:testDebugUnitTest --no-daemon`

Expected: all sync tests pass and no raw exception text is persisted.

---

### Task 3: Make schedule reconciliation remove orphaned WorkManager entries

**Files:**
- Modify: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshScheduler.kt`
- Modify: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshWorkNames.kt`
- Test: `catalog/sync/src/test/kotlin/app/muxtv/catalog/sync/SourceRefreshSchedulerPolicyTest.kt`

**Interfaces:**
- Produces: `suspend fun removeSource(sourceId: String)` and `suspend fun reconcile()`.
- `removeSource` cancels immediate and periodic unique work before deleting persisted scheduling state through the source lifecycle owner.

- [ ] **Step 1: Extract a `SourceWorkGateway` interface**

```kotlin
internal interface SourceWorkGateway {
    fun enqueueImmediate(sourceId: String, request: OneTimeWorkRequest)
    fun enqueuePeriodic(sourceId: String, request: PeriodicWorkRequest)
    fun cancelImmediate(sourceId: String)
    fun cancelPeriodic(sourceId: String)
}
```

Provide a WorkManager-backed implementation in the same module so scheduling policy can be unit-tested without Android instrumentation.

- [ ] **Step 2: Test disabled and removed policies**

Tests must prove:
- enabled policy enqueues periodic work;
- disabled policy cancels periodic work;
- `cancel(sourceId)` cancels both names;
- repeated reconciliation is idempotent.

- [ ] **Step 3: Keep Room update and WorkManager apply explicitly recoverable**

`updatePolicy` persists first, applies second. If WorkManager application fails, it throws; startup `reconcile()` replays Room as the source of truth.

- [ ] **Step 4: Run unit tests and Full**

Expected: deterministic policy tests without emulator dependence.

---

### Task 4: Add an exported Room v2-to-v3 migration fixture

**Files:**
- Ensure: `core/database/schemas/app.muxtv.database.MuxTvDatabase/2.json`
- Existing: `core/database/schemas/app.muxtv.database.MuxTvDatabase/3.json`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/DatabaseMigrations.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/Migration2To3Test.kt`

**Interfaces:**
- Consumes: `MIGRATION_2_3`.
- Proves preservation of sources, active revisions, channel rows and credential references while creating refresh policy/state/attempt tables.

- [ ] **Step 1: Create a version-2 database fixture with representative data**
- [ ] **Step 2: Open it through Room with `MIGRATION_2_3`**
- [ ] **Step 3: Assert legacy data remains and new foreign keys work**
- [ ] **Step 4: Run `DeviceMatrix` on API 26 and API 36**

Expected: migration test executes at least once on each API and reports zero failures.

---

### Task 5: Make instrumentation success require non-zero tests

**Files:**
- Modify: `tools/verify-local.ps1`
- Modify: `.github/workflows/self-hosted-validation.yml`

**Interfaces:**
- Produces evidence fields `executedTests`, `failures`, `skipped` per instrumentation module.
- A connected task with zero `<testcase>` elements fails validation.

- [ ] **Step 1: Parse connected-test XML after each module**
- [ ] **Step 2: Fail when `executedTests -lt 1`**
- [ ] **Step 3: Write counts into the evidence manifest**
- [ ] **Step 4: Run DeviceCurrent and verify app, credentials and database counts are non-zero**

---

### Task 6: Add active playback catalog contracts in a separate PR

**Branch after PR #13 merge:** `feat/playback-catalog`

**Files:**
- Modify: `catalog/api/build.gradle.kts`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/api/PlaybackCatalog.kt`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/api/PlayableChannel.kt`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/api/PlayableVariant.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/PlaybackCatalogDao.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/RoomPlaybackCatalog.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Test: `core/database/src/androidTest/kotlin/app/muxtv/database/PlaybackCatalogTest.kt`

**Interfaces:**

```kotlin
interface PlaybackCatalog {
    fun observeChannels(query: ChannelQuery = ChannelQuery()): Flow<List<PlayableChannelSummary>>
    suspend fun getChannel(channelId: String): PlayableChannel?
    suspend fun resolveVariant(channelId: String, preferredVariantId: String? = null): ResolvedPlaybackRequest?
}
```

`ResolvedPlaybackRequest` is memory-only and must redact its URI and headers in `toString()`.

- [ ] **Step 1: Write database tests proving only ACTIVE revisions are visible**
- [ ] **Step 2: Add bounded summary queries with stable ordering**
- [ ] **Step 3: Resolve credentials outside the DAO**
- [ ] **Step 4: Bind `PlaybackCatalog` through Hilt**
- [ ] **Step 5: Run Full and DeviceMatrix**

---

### Task 7: Add process-owned Media3 playback

**Branch:** `feat/media3-session`

**Files:**
- Modify: `player/api/src/main/kotlin/app/muxtv/player/api/PlaybackEngine.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/Media3ControllerConnector.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/MediaItemFactory.kt`
- Modify: `app/tv/src/main/AndroidManifest.xml`
- Modify: `app/tv/build.gradle.kts`
- Test: `app/tv/src/androidTest/kotlin/app/muxtv/PlaybackServiceSmokeTest.kt`

**Interfaces:**
- Service owns exactly one ExoPlayer and one MediaSession.
- Activity/UI owns only a MediaController connection.
- Resolved headers and URI remain memory-only and secret-safe.

- [ ] **Step 1: Add a failing service bind/reconnect instrumentation test**
- [ ] **Step 2: Add foreground-service permissions and service declaration**
- [ ] **Step 3: Build ExoPlayer with the shared OkHttp data source**
- [ ] **Step 4: Add controller reconnect and deterministic release**
- [ ] **Step 5: Run DeviceMatrix**

---

### Task 8: Replace placeholder Channels and Player UI

**Branch:** `feat/channel-browser-player`

**Files:**
- Create: `feature/channels/`
- Create: `feature/player/`
- Modify app navigation in `app/tv`
- Test: channel focus, selection, player open, Back restoration and D-pad zapping.

**Acceptance path:** Home → Channels → focused channel → OK → first-frame/player state → Up/Down zap → Back → same focused channel.

---

### Task 9: Source management, XMLTV, Doctor Lite and exact Smart Channels

Implement as four reviewable PRs after playback is functional:

1. `feat/source-management-ui`: create/edit/test/refresh/schedule/delete remote M3U sources.
2. `feat/xmltv-now-next`: streaming XMLTV revisions, deterministic bindings and now/next.
3. `feat/playback-recovery`: typed Media3 failures, bounded retry and variant fallback.
4. `feat/smart-channels-exact`: exact duplicate grouping, manual merge/split, variants and undo journal.

Each PR must leave the application usable at its own merge point.

---

## Current execution checkpoint

- [x] Autonomous API 26/API 36 Android TV harness exists.
- [x] Durable scheduling implementation exists in PR #13.
- [x] Real app smoke test has been added.
- [x] Worker cancellation contract test has been added and RED was observed.
- [ ] Add `WorkerBoundary.kt`.
- [ ] Obtain green exact-head Full.
- [ ] Close Tasks 2–5 or explicitly split them into a hardening PR before marking PR #13 ready.
- [ ] Merge PR #13 and create `feat/playback-catalog`.
