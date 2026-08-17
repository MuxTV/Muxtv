# Single Service-Owned Seek Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the UI-side seek mutation/coalescing owner and route all internal relative/absolute seek requests through one generation-aware service-owned `PlaybackSeekController`.

**Architecture:** Reuse the existing Media3 custom `SessionCommand` boundary. The service assigns an opaque install generation, validates typed requests, and owns the only production `player.seekTo`. UI keeps only provisional HUD state. Deprecated `onPlayerCommandRequest` remains a compatibility adapter into the same service authority.

**Tech Stack:** Kotlin, Coroutines/Flow, AndroidX Media3 session/ExoPlayer, Compose for TV, JUnit/Truth, Android instrumentation.

---

### Task 1: Define seek identity and session contract

**Files:**
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSeekContract.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackSessionContract.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekContractTest.kt`

- [ ] Add opaque `PlaybackSeekToken(mediaId, generation)` and relative/absolute request/result types.
- [ ] Add strict custom-command bundle encoding/parsing and typed policy result encoding/parsing.
- [ ] Reject extra/missing keys, blank media IDs, invalid directions, non-positive generations and negative absolute targets.
- [ ] Keep normal policy rejection in successful transport payload; reserve Media3 error codes for transport/permission errors.

### Task 2: Add real install generation and one service scheduler

**Files:**
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackMediaSourceFactory.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSeekController.kt`
- Modify: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekControllerTest.kt`
- Test: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekContractTest.kt`

- [ ] Assign a monotonically increasing process-local seek generation on every installed MediaSource.
- [ ] Publish the generation in safe MediaMetadata extras so a connected controller can obtain an opaque token.
- [ ] Extend `PlaybackSeekController` with absolute-target requests sharing the existing pending-target/coalesce scheduler.
- [ ] Implement one service `handleSeekRequest()` that validates active token, command availability, live/finite duration and position before scheduling.
- [ ] Route the custom command and deprecated Media3 forward/back compatibility adapter into that same function.
- [ ] Reset generation/controller state on replacement/stop.

### Task 3: Remove UI mutation ownership

**Files:**
- Modify: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerSurfaceContent.kt`
- Modify if needed: `feature/player/src/main/kotlin/app/muxtv/feature/player/SeekHud.kt`
- Test: existing player/app Android journeys.

- [ ] Delete the UI-owned `PlaybackSeekController` and its `controller.seekTo(targetMs)` callback.
- [ ] Keep only immediate provisional HUD target state using the explicit 10s step policy; no UI coalesce quiet-window.
- [ ] Send every locally admissible request through the typed service custom command.
- [ ] Reconcile accepted target/rejection asynchronously and use Media3 discontinuity only for presentation confirmation.
- [ ] Keep external native D-pad and Compose preview-key paths converged on the same request function.

### Task 4: Architecture and device evidence

**Files:**
- Modify/add focused tests only where evidence is missing.
- Update: Issue #132 coordination after acceptance.

- [ ] Verify no production `PlayerSurfaceContent` path invokes `seekTo`.
- [ ] Verify burst, stale token, replacement/reset, finite boundaries and relative/absolute convergence.
- [ ] Run exact-head self-hosted validation.
- [ ] Run exact-head Android TV DeviceCurrent including EP-08 external native D-pad and Player overlay/focus journeys.
- [ ] Merge only when both exact-head gates are green.
- [ ] Update #132: mark authority slice accepted; leave measurement/back-buffer/cache work evidence-gated under #27/#109 rather than expanding this PR.
