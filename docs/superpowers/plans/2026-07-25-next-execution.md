# MuxTV Next Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use subagent-driven development or execute tasks sequentially with RED/GREEN checkpoints. Every task must leave a reviewable, working increment.

**Goal:** Close the durable onboarding and catalog-hardening stack, then deliver the first safe Android TV flow for adding and activating a remote M3U source.

**Architecture:** Keep credentials and full locators outside Room, navigation keys, saved Compose state, logs, traces and diagnostics. Navigation 3 owns only serializable screen keys. The add-source session owns the opaque preparation token privately and reconstructs it after process death through the durable registry merged in PR #20.

**Tech Stack:** Kotlin 2.4.10, Android Gradle Plugin 9.3.0, Compose BOM 2026.06.00, Navigation 3 1.1.4, Room 3.0.0, Hilt 2.60.1, Coroutines 1.11.0, Media3 1.10.1, OkHttp 5.3.0.

## Global Constraints

- Preserve `minSdk = 26`.
- No raw playlist locator, query, user-info, authorization value, cookie, referrer value or opaque preparation token in navigation, `rememberSaveable`, `SavedStateHandle`, Room projections, logs, traces, screenshots or exception text.
- Use standard Compose Foundation `LazyColumn`/`LazyRow`; do not introduce deprecated TV lazy layouts.
- One functional concern per PR; squash merge to `main`.
- API/device validation is a release gate, not a substitute for product implementation.
- Do not add Rust, a second player engine, bundled SQLite, Paging, Retrofit, Ktor or global state frameworks without a separate evidence-backed ADR.

---

## Task 1: Close durable onboarding registry (PR #20)

- [x] Reparent to `main`.
- [x] Add Room 3 migration test for 3 → 4.
- [x] Add deterministic latest-active recovery.
- [x] Add bounded corrupted-row skip and RED/GREEN contract.
- [x] Commit exact generated schema v4.
- [x] Remove temporary schema-export workflow.
- [x] Confirm exact-head Full #172.
- [x] Mark ready and squash merge as `05ffb62d97034d17ed2cb00064a6a8d81d0e3344`.
- [ ] Execute migration instrumentation in the consolidated source-entry DeviceMatrix gate.

**Acceptance:** `main` contains one squash commit; schema v4 contains only `preparationId`, `scheme`, `host`, `createdAtEpochMillis`, `expiresAtEpochMillis`; recovery never exposes the token through public UI state.

## Task 2: Close catalog staging hardening (PR #22)

**Files:**
- `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogRevisionImporter.kt`
- `catalog/importer/src/main/kotlin/app/muxtv/catalog/importer/CatalogEntryIdentityFactory.kt`
- `core/database/src/main/kotlin/app/muxtv/database/SourceRevisionDao.kt`
- `core/database/src/androidTest/kotlin/app/muxtv/database/CatalogStagingAtomicityTest.kt`

- [ ] Rebuild as one commit over merged PR #20.
- [ ] Replace the provider-only rollback assertion with direct canonical, provider and stream-variant counts.
- [ ] Verify cancellation preserves the original cancellation when discard fails.
- [ ] Verify async trace names are static and secret-free.
- [ ] Run importer unit/lint and database instrumentation.
- [ ] Squash merge.

**Acceptance:** duplicate stream-variant insertion rolls back all three write groups; stable IDs remain byte-compatible; no per-entry trace or user-controlled trace name exists.

## Task 3: Secure TV source-entry wizard (issue #24)

**Files:**
- `app/tv/src/main/kotlin/app/muxtv/navigation/AppDestination.kt`
- `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- `app/tv/src/main/kotlin/app/muxtv/MainActivity.kt`
- `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- `feature/sources/src/main/kotlin/app/muxtv/feature/sources/SourcesRoute.kt`
- `feature/sources/src/main/kotlin/app/muxtv/feature/sources/SourceEntrySession.kt`
- `feature/sources/src/main/kotlin/app/muxtv/feature/sources/AddSourceRoute.kt`
- `feature/sources/src/test/kotlin/app/muxtv/feature/sources/SourceEntrySessionTest.kt`

- [x] Define no-argument `AppDestination.AddSource`.
- [x] Make destinations serializable `NavKey` values.
- [x] Replace the transient back stack with `rememberNavBackStack`.
- [x] Add “Добавить источник” from loading, empty, failed and content states.
- [x] Build a session with private token and public redacted state.
- [x] Implement HTTPS prepare, HTTP approval, restore, activate and cancel states.
- [x] Mask the locator by default and clear it after preparation/disposal.
- [x] Intercept system Back and require cleanup before leaving.
- [x] Add unit contracts for sanitization, HTTP approval, restore, activation and cleanup retention.
- [ ] Compile the complete Hilt/navigation graph.
- [ ] Add Compose semantics contracts proving the masked field does not expose the locator.
- [ ] Add API 26/API 30/highest-available-TV D-pad journey.
- [ ] Squash merge and close issue #24.

**Acceptance:** HTTPS source can be prepared and activated; HTTP requires explicit approval; process recreation restores only a sanitized endpoint; Back cannot silently abandon a stored credential.

## Task 4: Real TV focus restoration (issue #25)

- [ ] Introduce `FocusAnchor(key, index, scrollOffset)` for Channels and Sources.
- [ ] Restore exact key, nearest previous item, previous index, then first focusable item.
- [ ] Scroll before `FocusRequester.requestFocus()`.
- [ ] Verify Player → Back restores actual focus, not only a visual marker.
- [ ] Add UI Automator only in the PR that introduces real D-pad journey tests.

## Task 5: Media3 transport and reconnect hardening (issue #26)

- [ ] Add `media3-datasource-okhttp` aligned to Media3 1.10.1.
- [ ] Use immutable per-playback request headers; never mutate one shared factory between channels.
- [ ] Prove A → B manifest and segment requests do not leak headers.
- [ ] Replace blocking future waits with cancellation-aware suspend/timeout logic.
- [ ] Evict failed controller connection futures so retry can reconnect.

## Task 6: Deterministic corpus and measured decisions (issue #27)

- [ ] Add a secret-free M3U/HLS corpus with malformed, large, redirect and encoding cases.
- [ ] Benchmark parse, staging, activation and first-frame paths.
- [ ] Record device/API/tool versions in evidence.
- [ ] Use measurements before adopting bundled SQLite, Rust, a second player engine or preload.

## Task 7: XMLTV, Guide and user value (issues #28–#29)

- [ ] Stream XMLTV into immutable EPG revisions.
- [ ] Keep active/previous-good revision semantics.
- [ ] Build now/next first, then bounded Guide windows and search.
- [ ] Add Favorites and Recent using canonical channel identity, not raw stream URLs.

## Task 8: Playback recovery and release hardening (issues #30–#31)

- [ ] Add bounded variant fallback and TV Doctor Lite with typed secret-free diagnostics.
- [ ] Add Baseline Profile only with before/after measurements.
- [ ] Update command-line tools before claiming the highest available TV API image.
- [ ] Use matrix: API 26, representative API 30, and highest actually available Android TV image.
- [ ] Run physical Android TV/Google TV/Fire TV playback and codec checks before alpha.
