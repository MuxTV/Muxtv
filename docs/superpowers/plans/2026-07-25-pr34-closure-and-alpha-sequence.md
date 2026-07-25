# PR34 Closure and Alpha Sequence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** close the unfinished source-entry/focus acceptance stack, merge PR #34 with old/current Android TV evidence, close issues #24 and #25, then advance MuxTV toward `0.1.0-alpha` through isolated transport, corpus, EPG, recovery and release-hardening PRs.

**Architecture:** preserve the existing Kotlin modular monolith, Room as the durable source of truth, one process-owned Media3 player/session, encrypted credentials outside Room and secret-free navigation/diagnostics. Each PR owns one functional concern and must leave a working, reviewable increment; later work never builds on an unverified branch.

**Tech Stack:** Kotlin 2.4.10, Android Gradle Plugin 9.3.0, Compose BOM 2026.06.00, Android TV Material 1.1.0, Navigation 3 1.1.4, Room 3.0.0, Hilt 2.60.1, Coroutines 1.11.0, Media3 1.10.1, OkHttp 5.3.0, WorkManager 2.11.2, Windows self-hosted Android TV harness.

## Global Constraints

- Preserve `minSdk = 26`.
- Keep the project fully Kotlin; Rust or a second player engine requires a separate measured ADR.
- Keep one active process-owned `ExoPlayer` and `MediaSession`.
- Never persist or publish playlist locators, query tokens, cookies, authorization values, referrer values, sensitive headers or preparation tokens through Room projections, navigation, saved state, logs, traces, screenshots, semantics or exception text.
- Use standard Compose Foundation lazy layouts; do not introduce deprecated TV lazy APIs.
- Execute old/current TV profiles sequentially on the self-hosted Windows runner.
- One functional concern per PR; RED → focused verification → Full → Device when required → evidence review → squash merge.
- Do not use CI success as a substitute for product behavior, architecture review or security review.

---

## Work Package A: Close PR #34 and issues #24/#25

### Task A1: Make controller shutdown application-thread safe

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt`
- Existing RED test: `app/tv/src/androidTest/kotlin/app/muxtv/MediaSessionServiceSmokeTest.kt`

**Interfaces:**
- Consumes: `MediaController.release()` application-thread requirement.
- Produces: `MuxTvMediaControllerConnector.close()` callable safely from arbitrary lifecycle/test threads without changing single-controller ownership.

- [ ] **Step 1: Preserve the existing RED evidence**

Run:

```powershell
.\gradlew.bat :app:tv:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.MediaSessionServiceSmokeTest
```

Expected before implementation: `IllegalStateException: MediaController method is called from a wrong thread` from `MuxTvMediaControllerConnector.close()`.

- [ ] **Step 2: Add a minimal release helper**

Implement the following policy inside `MuxTvMediaControllerConnector`:

```kotlin
private fun release(controller: MediaController) {
    if (Looper.myLooper() == mainHandler.looper) {
        controller.release()
    } else {
        mainHandler.post(controller::release)
    }
}
```

Replace the direct `controllerToRelease?.release()` call with `controllerToRelease?.let(::release)`. Do not redesign reconnection or pending-future ownership in this task; that belongs to issue #26.

- [ ] **Step 3: Re-run the focused instrumentation contract**

Expected: one test, zero failures/errors/skips.

- [ ] **Step 4: Run `:player:media3:testDebugUnitTest` and instrumentation compilation**

```powershell
.\gradlew.bat :player:media3:testDebugUnitTest :app:tv:assembleDebugAndroidTest
```

- [ ] **Step 5: Commit**

```text
fix: release MediaController on its application thread
```

### Task A2: Remove raw locator values from Compose semantics

**Files:**
- Modify: `feature/sources/src/main/kotlin/app/muxtv/feature/sources/AddSourceRoute.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/SourceEntrySecurityTest.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/SourceEntryFocusTest.kt`

**Interfaces:**
- Consumes: in-memory `TextFieldState` and `BasicSecureTextField` physical keyboard/IME behavior.
- Produces: a test/accessibility semantics node that exposes only a stable label, password status and a safe replacement action, never `Text`, `InputText` or `EditableText` containing the locator.

- [ ] **Step 1: Preserve the existing RED security contract**

Expected before implementation: the semantics tree contains the full URL under `EditableText` and `InputText`.

- [ ] **Step 2: Add a safe semantics boundary**

Wrap the locator field semantics with `clearAndSetSemantics` and expose:

```kotlin
contentDescription = "Ссылка M3U, значение скрыто"
password()
editableText = AnnotatedString(if (state.text.isEmpty()) "" else "Скрыто")
setText { replacement ->
    state.edit {
        replace(0, length, replacement.text.take(MAX_LOCATOR_CHARACTERS))
    }
    true
}
```

The visible field must remain `BasicSecureTextField`; do not move the locator into `rememberSaveable`, a route key, `SavedStateHandle` or Room.

- [ ] **Step 3: Use `performTextReplacement` in Compose tests**

The test action must exercise the safe `SetText` semantics contract without requiring the secret to be returned by semantics.

- [ ] **Step 4: Verify both behavior and redaction**

Run the security test and the HTTP approval focus test. The URL must reach the session, while no node in merged or unmerged semantics may contain it.

- [ ] **Step 5: Commit**

```text
fix: keep source locators out of Compose semantics
```

### Task A3: Make focus journeys D-pad-driven and condition-based

**Files:**
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/ChannelsFocusRestorationTest.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/SourceEntryFocusTest.kt`
- Modify production focus code only if the corrected journeys still reproduce a product defect.

**Interfaces:**
- Consumes: stable secret-free test tags and actual focused TV controls.
- Produces: deterministic D-pad/Enter journeys without touch-only assumptions or arbitrary sleeps.

- [ ] **Step 1: Replace touch activation with focused Enter/Center input**

Use:

```kotlin
performKeyInput {
    keyDown(Key.Enter)
    keyUp(Key.Enter)
}
```

on the already focused channel/action. For the HTTP flow, move focus from the locator to `Проверить` with D-pad navigation or address the tagged action directly and activate it with Enter.

- [ ] **Step 2: Wait for state conditions, not generic idleness**

Use `waitUntilExactlyOneExists(hasTestTag(...))` before asserting the Player Back or HTTP safe-cancel node. Do not add fixed delays.

- [ ] **Step 3: Preserve focus assertions**

After Player → Back, assert `channel-row-1` is actually focused. In the HTTP warning state, assert `source-http-cancel` is actually focused.

- [ ] **Step 4: Execute the complete app instrumentation suite**

Expected: all app tests execute, with zero failures/errors/skips.

- [ ] **Step 5: Commit**

```text
test: exercise TV focus journeys through D-pad input
```

### Task A4: Review the first corrected DeviceCurrent evidence

**Files:**
- Update: PR #34 body with exact head/run/test counts.
- Update: `docs/superpowers/plans/2026-07-25-focus-device-closure.md` checkboxes only after evidence exists.

- [ ] Run the branch-specific `DeviceCurrent` workflow on the corrected head.
- [ ] Confirm non-zero test counts for credentials, database and application suites.
- [ ] Confirm `DatabaseMigration3To4Test` and `CatalogStagingAtomicityTest` execute.
- [ ] Inspect reports/logcat/screenshots for secrets.
- [ ] If a test fails, return to root-cause investigation; do not stack speculative fixes.

### Task A5: Execute the old/current sequential matrix

**Files:**
- No production changes unless the old edge exposes a real compatibility defect.

- [ ] Run `DeviceMatrix` on the same exact head.
- [ ] Record the actual old TV image selected by `sdkmanager --list` fallback logic.
- [ ] Require non-zero tests and zero failures/errors/skips on both profiles.
- [ ] Confirm Room 3→4 migration, catalog atomicity, Keystore and app focus/source-entry journeys execute on the matrix.
- [ ] Compare screenshots and semantics evidence for secret-free output.

### Task A6: Clean the branch and merge

**Files:**
- Delete: `.github/workflows/pr34-device-current.yml`
- Update: `docs/superpowers/plans/2026-07-25-next-execution.md`
- Update: PR #34 body.

- [ ] Remove the temporary branch-only workflow.
- [ ] Run final exact-head `Full` after cleanup.
- [ ] Confirm no unresolved review threads or discussion comments.
- [ ] Mark PR #34 ready for review.
- [ ] Squash merge PR #34 into `main`.
- [ ] Close issue #24 only with source-entry journey and Room evidence linked.
- [ ] Close issue #25 only with focus journey and old/current matrix evidence linked.

---

## Work Package B: Synchronize repository truth

### Task B1: Create a documentation-only status PR

**Files:**
- Modify: `README.md`
- Modify: `.work/meta/status.yaml`
- Modify: the canonical roadmap/current-state documents referenced by `.work/meta/documents.yaml`.

- [ ] Create a new branch from merged `main`.
- [ ] Replace the obsolete claim that application code, Gradle, CI and tests are absent.
- [ ] Record current modules, Room schema v4, implemented M3U/source/player path and remaining pre-alpha scope.
- [ ] Preserve deferred Rust/libmpv/KMP/platform decisions.
- [ ] Validate all machine-readable paths and schema fields.
- [ ] Full verification, review, squash merge.

---

## Work Package C: Issue #26 — Media3 transport and reconnect

### Task C1: Remove shared mutable playback headers

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `player/media3/build.gradle.kts`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Add focused Media3/MockWebServer tests.

- [ ] Add `media3-datasource-okhttp` at Media3 `1.10.1`.
- [ ] Build one immutable data-source factory per installed playback request or use a request-scoped properties boundary.
- [ ] Prove channel A headers cannot appear on channel B manifest or segment requests.
- [ ] Align downgrade/cross-origin credential behavior with the shared network policy.

### Task C2: Make controller connection retryable and cancellation-aware

- [ ] Evict failed/cancelled connection futures.
- [ ] Release late controllers created after connector close.
- [ ] Replace blocking waits in UI adapters with bounded cancellation-aware suspension.
- [ ] Add service disconnect/reconnect instrumentation contracts.
- [ ] DeviceMatrix, evidence review, squash merge, close #26.

---

## Work Package D: Issue #27 — deterministic corpus and measurements

- [ ] Add redistributable synthetic M3U/XMLTV/HLS fixtures without provider credentials.
- [ ] Define small, medium and large catalogs plus malformed/control/encoding/header cases.
- [ ] Measure parse, 250-entry staging, activation, active catalog query, source overview query and Player setup.
- [ ] Record hardware, Android API, emulator/device and tool versions with each result.
- [ ] Use measurements before approving Paging, bundled SQLite, Rust, preload or a second player engine.

---

## Work Package E: Issues #28/#29 — EPG and daily-use discovery

- [ ] Add a bounded streaming XMLTV parser.
- [ ] Add immutable EPG revisions with previous-good retention.
- [ ] Build now/next projections keyed by canonical channel identity.
- [ ] Add Guide with bounded time windows and lazy rows.
- [ ] Add debounced bounded search across channels and active programme metadata.
- [ ] Add Favorites and Recent using profile overlays and canonical IDs.
- [ ] Execute focus/recreation journeys on old/current TV profiles.

---

## Work Package F: Issues #30/#31 — recovery and alpha release

- [ ] Add bounded deterministic variant fallback with explicit attempt/time budgets.
- [ ] Add TV Doctor Lite typed observations without raw locators or exception messages.
- [ ] Enable R8/resource shrinking with evidence-backed keep rules.
- [ ] Generate a measured Baseline Profile for startup, Channels, Player, Sources and Guide.
- [ ] Run old/mainstream/current virtual profiles and low-RAM endurance.
- [ ] Run at least one current Android/Google TV, one constrained device and Fire TV/Quality Central where available.
- [ ] Validate schema upgrades, Keystore persistence/reset, signing, SBOM, changelog and reproducible release checklist.
- [ ] Publish `0.1.0-alpha` only after the release issue acceptance criteria are evidenced.

## Current Definition of Done

1. PR #34 has a clean exact head with Full and sequential old/current DeviceMatrix evidence.
2. The locator is absent from Compose semantics while source entry remains usable by remote/IME.
3. `MediaController.release()` never violates its application-thread contract.
4. Channels → Player → Back restores actual focus, and HTTP warning focuses safe Cancel.
5. Room 3→4 migration and catalog staging atomicity execute on the matrix.
6. Temporary branch-only CI is removed before merge.
7. Issues #24 and #25 are closed only after the merge and linked evidence.
8. Subsequent work starts from merged `main`, one isolated responsibility at a time.
