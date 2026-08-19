# Single Service-Owned Seek Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the UI-side seek mutation/coalescing owner and route all private and standard current-item relative/absolute seek requests through one generation-aware service-owned `PlaybackSeekController`, with authoritative service-result evidence and replacement-safe seek confirmation.

**Architecture:** Keep the typed Media3 custom `SessionCommand` for MuxTV's generation-aware requests. Put a thin `ForwardingPlayer` action adapter in front of the raw service-owned `ExoPlayer` for standard Media3 controls. Both transports normalize into the same service `handleSeekRequest()`. Observable Player state/discontinuity remains the raw ExoPlayer's state and is not optimistically modeled by another Player layer. UI keeps only provisional HUD state. Native-input submission and service acceptance are separate phases. The existing public/test `accepted` semantic tag is retained, but after hardening it is emitted only after `PlaybackSeekResult.Accepted`; provisional `submitted` is not published as an authoritative remote-input outcome. Seek confirmation is attributed from the discontinuity MediaItem generation rather than the service's current generation.

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

### Task 5: Separate native-input submission from authoritative service acceptance

**Files:**
- Modify: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerSurfaceContent.kt`
- Add: `feature/player/src/test/kotlin/app/muxtv/feature/player/SeekInputOutcomeTest.kt`
- Reuse unchanged acceptance journey: `app/tv/src/androidTest/kotlin/app/muxtv/external/ExternalPlaybackRangeJourneyTest.kt`

**Interfaces:**
- Consumes: `PlaybackSeekResult.Accepted` / `PlaybackSeekResult.Rejected` from the typed custom command.
- Produces: provisional local `submitted` state and authoritative existing `accepted`/typed rejection evidence without changing D-pad event-consumption semantics.

- [x] **RED contract authored before production change.**

  `SeekInputOutcomeTest` requires:

  - `SUBMITTED("submitted")` consumes an admissible key but does not publish a remote semantic outcome;
  - `SERVICE_ACCEPTED("accepted")` preserves the existing evidence tag and is not the immediate dispatch result;
  - local typed rejections remain diagnosable and do not consume the seek dispatch.

- [x] **RED observed in CI.**

  Self-hosted validation run `32277087227` on test-only head `c57e1ff6f43eb336f0fbae508855800be228c231` failed in `:feature:player:compileDebugUnitTestKotlin` specifically because production still lacked `SUBMITTED`, `SERVICE_ACCEPTED` and `handlesDispatch`. Checkout, provenance and runner preflight had succeeded, so this was a contract RED rather than infrastructure failure.

- [x] **GREEN implementation authored.**

  `PlayerSurfaceContent` now:

  - returns/records provisional `SUBMITTED` for locally admissible input;
  - does not publish `submitted` through `PlayerRemoteInputHost.lastSemanticOutcome`;
  - emits the existing `accepted` semantic tag only after `PlaybackSeekResult.Accepted`;
  - keeps typed rejection and timeout outcomes bounded and diagnosable;
  - consumes the D-pad event on `SUBMITTED`, not on later service completion.

- [ ] **Verify GREEN on the final integrated head.**

  Required final evidence includes `:feature:player` host tests and the unchanged EP-08 external native-D-pad journey. Because the journey still waits for `external-seek-input-accepted`, it now proves service acceptance rather than immediate local submission.

### Task 6: Attribute seek confirmation to the discontinuity MediaItem generation

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSeekTokenProjection.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Add: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/PlaybackSeekTokenProjectionInstrumentedTest.kt`
- Reuse: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekControllerTest.kt`

**Interfaces:**
- Consumes: generation stored in each installed `MediaItem.mediaMetadata.extras`.
- Produces: discontinuity confirmation carrying the event MediaItem's generation; stale-generation confirmation is ignored by `PlaybackSeekController`.

- [x] **RED contract authored before production change.**

  `PlaybackSeekTokenProjectionInstrumentedTest` constructs the same media ID with different install generations and requires distinct tokens; missing/non-positive generations must fail closed.

- [ ] **Standalone RED execution was not observed.**

  The Android TV run for test-only head `c57e1ff6f43eb336f0fbae508855800be228c231` was cancelled by subsequent PR pushes under workflow concurrency before this instrumentation RED could execute independently. Do not rewrite history as if that device RED ran. The test was present before the GREEN implementation; final exact-head instrumentation remains mandatory.

- [x] **GREEN implementation authored.**

  - Added internal `MediaItem.playbackSeekToken()` and made `Player.currentPlaybackSeekToken()` delegate to it.
  - `seekConfirmationListener` now derives generation from `newPosition.mediaItem` and fails closed when provenance is missing/malformed.
  - It no longer substitutes `activeSeekGeneration` for unknown event provenance.
  - Existing controller behavior still rejects foreign-generation confirmation.

- [ ] **Verify GREEN on final integrated host/device evidence.**

  Required evidence includes player unit tests plus `:player:media3` instrumentation so the pinned Media3 API and real Android `PositionInfo.mediaItem` semantics are compiled/executed.

### Task 7: Reconcile branch provenance and regenerate final evidence

**Files:**
- Modify: this plan execution state.
- Update: PR #175 body/evidence references.
- Update: Issue #132 only after merge acceptance.

- [x] Merge accepted `main@7462338fd5514ef30b268ea250b6d92ecc71b27e` into the PR branch.

  Merge commit `a1e07160babfe26278d770a20dcdf0d4ce6f8f4e` records `main` as the second parent. The merge tree is byte-identical to the pre-merge PR tree because the checkpoint content was already present; GitHub compare now reports `behind_by=0` with merge-base equal to accepted main.

- [ ] Run exact-final-head host validation.
- [ ] Run exact-final-head Android TV DeviceCurrent including EP-08 external native D-pad and Player overlay/focus journeys.
- [ ] Confirm both required checks and artifacts are bound to the same final head.
- [ ] Obtain at least one independent submitted review for the player/session/service boundary.
- [ ] Merge only after repository review/branch policy is satisfied.
- [ ] Update #132 after acceptance: mark only the authority slice accepted; keep measurement/back-buffer/cache work evidence-gated under #27/#109.

## Historical evidence

`5f84c2a123c8e5840365668910bffdafd794f18e` previously passed:

- self-hosted validation run `32182646499`;
- Android TV focused device run `32182646564` / API 36 DeviceCurrent;
- `:player:media3:connectedDebugAndroidTest`: 27 tests, 0 failures/errors/skips.

These runs remain useful regression history only; they are not final acceptance evidence after Tasks 5–7 changed the branch head.
