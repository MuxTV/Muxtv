# Single Service-Owned Seek Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the UI-side seek mutation/coalescing owner and route all private and standard current-item relative/absolute seek requests through one generation-aware service-owned `PlaybackSeekController`, with authoritative service-result evidence and replacement-safe seek confirmation.

**Architecture:** Keep the typed Media3 custom `SessionCommand` for MuxTV's generation-aware requests. Put a thin `ForwardingPlayer` action adapter in front of the raw service-owned `ExoPlayer` for standard Media3 controls. Both transports normalize into the same service `handleSeekRequest()`. Observable Player state/discontinuity remains the raw ExoPlayer's state and is not optimistically modeled by another Player layer. UI keeps only provisional HUD state. Native-input handling and service acceptance are separate evidence phases. Seek confirmation is attributed from the discontinuity's MediaItem generation rather than the service's current generation.

**Tech Stack:** Kotlin, Coroutines/Flow, AndroidX Media3 1.10.1 session/ExoPlayer, Compose for TV, JUnit/Truth, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-17-single-service-seek-authority-design.md`

## Global Constraints

- One process-owned `ExoPlayer` remains owned by `MuxTvPlaybackService`.
- One production seek mutation/coalescing authority remains `PlaybackSeekController` inside the service.
- No LoadControl, back-buffer, `SimpleCache`, codec, FFmpeg or performance-policy work in this PR.
- UI may keep provisional HUD state but may not call a Media3 seek mutation directly.
- Playback generation remains process-local, opaque, non-persistent and secret-free.
- Exact-head evidence is valid only for the exact commit tested; any hardening commit invalidates prior final-head acceptance until rerun.

---

### Task 1: Define seek identity and session contract

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSeekContract.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackSessionContract.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekContractTest.kt`

- [x] Add opaque `PlaybackSeekToken(mediaId, generation)` and relative/absolute request/result types.
- [x] Add strict custom-command bundle encoding/parsing and typed policy result encoding/parsing.
- [x] Reject extra/missing keys, blank media IDs, invalid directions, non-positive generations and negative absolute targets.
- [x] Keep normal policy rejection in successful transport payload; reserve Media3 error codes for transport/permission errors.

### Task 2: Add real install generation and one service scheduler

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackMediaSourceFactory.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSeekController.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekControllerTest.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekContractTest.kt`

- [x] Assign a monotonically increasing process-local seek generation on every installed MediaSource.
- [x] Publish the generation in safe MediaMetadata extras so a connected controller can obtain an opaque token.
- [x] Extend `PlaybackSeekController` with absolute-target requests sharing the existing pending-target/coalesce scheduler.
- [x] Implement one service `handleSeekRequest()` that validates active token, command availability, live/finite duration and position before scheduling.
- [x] Route the private custom command through that function.
- [x] Reset generation/controller state on replacement/stop.

### Task 3: Remove UI mutation ownership

**Files:**
- Modify: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerSurfaceContent.kt`
- Test: existing player/app Android journeys.

- [x] Delete the UI-owned `PlaybackSeekController` and its `controller.seekTo(targetMs)` callback.
- [x] Keep only immediate provisional HUD target state using the explicit 10s step policy; no UI coalesce quiet-window.
- [x] Send every locally admissible request through the typed service custom command.
- [x] Reconcile accepted target/rejection asynchronously and use Media3 discontinuity only for presentation confirmation.
- [x] Keep external native D-pad and Compose preview-key paths converged on the same request function.

### Task 4: Close the standard Media3 Player-command bypass

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvSessionPlayer.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlayerCommandResultPolicyTest.kt`
- Test: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/MuxTvSessionPlayerInstrumentedTest.kt`

- [x] Pass `MuxTvSessionPlayer : ForwardingPlayer`, not the raw ExoPlayer, to `MediaSession`.
- [x] Intercept standard `seekBack`, `seekForward` and current-item absolute `seekTo` and normalize them into semantic service intents.
- [x] Bind standard intents to the current service generation and run them through the same `handleSeekRequest()` / `PlaybackSeekController`.
- [x] Never delegate intercepted seek methods to the raw ExoPlayer.
- [x] Keep observable Player state/discontinuity delegated to the raw ExoPlayer; do not introduce a second optimistic seek-state machine.
- [x] Filter default-position, cross-item and previous/next seek commands from the session-facing Player until those semantics are explicitly modeled; keep defensive no-op overrides as a second boundary.
- [x] Remove deprecated `onPlayerCommandRequest` seek policy and its result helper.
- [x] Add focused current-item/cross-item/negative-target/command-filter tests.

### Task 5: Separate native-input handling from authoritative service acceptance

**Files:**
- Modify: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerSurfaceContent.kt`
- Modify: `app/tv/src/androidTest/kotlin/app/muxtv/external/ExternalPlaybackRangeJourneyTest.kt`
- Test: `feature/player/src/test/kotlin/app/muxtv/feature/player/PlayerRemoteInputHostTest.kt` only if host semantics need additional coverage.

**Interfaces:**
- Consumes: `PlaybackSeekResult.Accepted` / `PlaybackSeekResult.Rejected` from the typed custom command.
- Produces: distinct provisional `submitted` and authoritative `service-accepted`/typed rejection evidence without changing D-pad event-consumption semantics.

- [ ] **Step 1: RED — require authoritative service acceptance in EP-08.**

  Update `ExternalPlaybackRangeJourneyTest` so the D-pad journey does not treat the immediate native dispatch result as service acceptance. The test must wait for `external-seek-input-service-accepted` and treat `external-seek-input-submitted` only as transport/provisional evidence.

- [ ] **Step 2: Run focused Android test and verify RED.**

  Run:

  ```text
  ./gradlew :app:tv:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.external.ExternalPlaybackRangeJourneyTest
  ```

  Expected: the seek journey fails because production currently emits `accepted` immediately from `requestSeek()` and has no `service-accepted` semantic outcome.

- [ ] **Step 3: GREEN — split submitted from service result.**

  In `PlayerSurfaceContent`:

  - add `SUBMITTED("submitted")` and `SERVICE_ACCEPTED("service-accepted")` outcomes;
  - locally admissible D-pad input sets provisional HUD state, launches the typed request and returns/records `SUBMITTED` for event consumption;
  - only `PlaybackSeekResult.Accepted` records `SERVICE_ACCEPTED`;
  - `PlaybackSeekResult.Rejected` and timeout continue to record bounded typed rejection outcomes;
  - `handleSeekInput()` returns `true` for `SUBMITTED` without re-labeling it as authoritative acceptance.

- [ ] **Step 4: Run focused feature + external tests and verify GREEN.**

  Run:

  ```text
  ./gradlew :feature:player:testDebugUnitTest
  ./gradlew :app:tv:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.external.ExternalPlaybackRangeJourneyTest
  ```

  Expected: input is handled immediately; EP-08 passes only after the service returns `PlaybackSeekResult.Accepted`.

### Task 6: Attribute seek confirmation to the discontinuity MediaItem generation

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSeekTokenProjection.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Add/modify test: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/PlaybackSeekCommandCodecTest.kt` or a focused token-projection instrumentation test.
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekControllerTest.kt`

**Interfaces:**
- Consumes: generation stored in each installed `MediaItem.mediaMetadata.extras`.
- Produces: discontinuity confirmation carrying the event MediaItem's generation; stale-generation confirmation is ignored by `PlaybackSeekController`.

- [ ] **Step 1: RED — prove event-media generation is the confirmation source.**

  Add a focused instrumentation test that constructs two MediaItems with the same `mediaId` but different seek generations and proves token projection distinguishes them. Keep the existing controller test proving a foreign generation cannot complete an applying seek.

- [ ] **Step 2: Run focused test and verify RED.**

  Run:

  ```text
  ./gradlew :player:media3:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=app.muxtv.player.media3.PlaybackSeekCommandCodecTest
  ```

  Expected: RED until MediaItem-level seek-token projection exists.

- [ ] **Step 3: GREEN — project token from MediaItem and fail closed.**

  Add an internal `MediaItem.playbackSeekToken()` projection and make `Player.currentPlaybackSeekToken()` delegate to it. In `seekConfirmationListener`, derive the generation from `newPosition.mediaItem`; if it is absent or malformed, do not confirm. Never substitute `activeSeekGeneration` for unknown event provenance.

- [ ] **Step 4: Run controller + Android focused tests and verify GREEN.**

  Run:

  ```text
  ./gradlew :player:media3:testDebugUnitTest
  ./gradlew :player:media3:connectedDebugAndroidTest
  ```

  Expected: stale/foreign generation remains ignored and valid current-generation discontinuity completes normally.

### Task 7: Reconcile branch provenance and regenerate final evidence

**Files:**
- Modify: this plan execution state.
- Update: PR #175 body/evidence references.
- Update: Issue #132 only after merge acceptance.

- [ ] Rebase/restack or merge the accepted `main@7462338fd5514ef30b268ea250b6d92ecc71b27e` checkpoint into the PR branch so it is no longer behind main.
- [ ] Run exact-new-head host validation.
- [ ] Run exact-new-head Android TV DeviceCurrent including EP-08 external native D-pad and Player overlay/focus journeys.
- [ ] Confirm both required checks and artifacts are bound to the same final head.
- [ ] Obtain at least one independent submitted review for the player/session/service boundary.
- [ ] Merge only after repository review/branch policy is satisfied.
- [ ] Update #132 after acceptance: mark only the authority slice accepted; keep measurement/back-buffer/cache work evidence-gated under #27/#109.

## Historical evidence

`5f84c2a123c8e5840365668910bffdafd794f18e` previously passed:

- self-hosted validation run `32182646499`;
- Android TV focused device run `32182646564` / API 36 DeviceCurrent;
- `:player:media3:connectedDebugAndroidTest`: 27 tests, 0 failures/errors/skips.

These runs remain useful regression history but cease to be final acceptance evidence as soon as Task 5 or Task 6 changes the branch head.
