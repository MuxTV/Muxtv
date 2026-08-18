# Single Service-Owned Seek Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the UI-side seek mutation/coalescing owner and route all private and standard current-item relative/absolute seek requests through one generation-aware service-owned `PlaybackSeekController`.

**Architecture:** Keep the typed Media3 custom `SessionCommand` for MuxTV's generation-aware requests. Put a thin `ForwardingPlayer` action adapter in front of the raw service-owned `ExoPlayer` for standard Media3 controls. Both transports normalize into the same service `handleSeekRequest()`. Observable Player state/discontinuity remains the raw ExoPlayer's state and is not optimistically modeled by another Player layer. UI keeps only provisional HUD state. The deprecated `onPlayerCommandRequest` seek policy is removed.

**Tech Stack:** Kotlin, Coroutines/Flow, AndroidX Media3 1.10.1 session/ExoPlayer, Compose for TV, JUnit/Truth, Android instrumentation.

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
- Modify if needed: `feature/player/src/main/kotlin/app/muxtv/feature/player/SeekHud.kt`
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
- Replace focused policy test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlayerCommandResultPolicyTest.kt`

- [x] Pass `MuxTvSessionPlayer : ForwardingPlayer`, not the raw ExoPlayer, to `MediaSession`.
- [x] Intercept standard `seekBack`, `seekForward` and current-item absolute `seekTo` and normalize them into semantic service intents.
- [x] Bind standard intents to the current service generation and run them through the same `handleSeekRequest()` / `PlaybackSeekController`.
- [x] Never delegate intercepted seek methods to the raw ExoPlayer.
- [x] Keep observable Player state/discontinuity delegated to the raw ExoPlayer; do not introduce a second optimistic seek-state machine.
- [x] Filter default-position, cross-item and previous/next seek commands from the session-facing Player until those semantics are explicitly modeled; keep defensive no-op overrides as a second boundary.
- [x] Remove deprecated `onPlayerCommandRequest` seek policy and its result helper.
- [x] Add focused current-item/cross-item/negative-target/command-filter tests.

### Task 5: Architecture and exact-head evidence

**Files:**
- Modify/add focused tests only where evidence is missing.
- Update: Issue #132 coordination after acceptance.

- [x] Verify by code structure that the session is built with `MuxTvSessionPlayer`, not the raw ExoPlayer.
- [x] Verify no production `PlayerSurfaceContent` path invokes `seekTo`.
- [x] Verify burst, stale token, replacement/reset, finite boundaries and relative/absolute convergence in the existing focused test set.
- [ ] Execute the final-head host validation after the self-hosted runner is available again.
- [ ] Execute the final-head Android TV DeviceCurrent including EP-08 external native D-pad and Player overlay/focus journeys.
- [ ] Merge only when both required **final-head** gates are green.
- [ ] Update #132 after acceptance: mark authority slice accepted; leave measurement/back-buffer/cache work evidence-gated under #27/#109 rather than expanding this PR.

> Runner note: previous host/device successes are evidence for their previous exact head only. They are useful regression history, but they do not validate the final hardening head while the self-hosted/device runner is offline.
