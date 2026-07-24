# Media3 Session and TV Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move MuxTV playback ownership out of Activity-scoped objects into a single process-owned Media3 session service, then connect the active playback catalog to a D-pad-first channel and player flow.

**Architecture:** `PlaybackCatalog` remains the source of active canonical channels and stream variants. `MuxTvPlaybackService` owns the only `ExoPlayer` and `MediaSession`; UI code connects through `MediaController`. A small session command carries one validated playback request to the service because IPTV variants may require per-stream HTTP headers.

**Tech Stack:** Kotlin 2.4, Android API 26+, Media3 1.10.1, ExoPlayer, MediaSessionService, Compose for TV, Navigation3, Hilt, Room 3, coroutines.

## Global Constraints

- Keep `minSdk` 26 compatibility.
- Exactly one process-owned `ExoPlayer` instance for normal playback.
- Do not place source credentials, cookies, Authorization values, raw URLs or header values in logs, exceptions, notifications or WorkManager Data.
- UI must control the service through `MediaController`; it must not own or release the service player.
- Only active catalog revisions may be playable.
- One bounded functional slice per pull request; PR #15 stays stacked on PR #14 until earlier PRs merge.
- Prefer standard Media3 player commands; use a custom session command only to install a validated IPTV request with per-item headers.

---

## File Structure

### PR #15 — Media3 session runtime

- Modify: `player/api/src/main/kotlin/app/muxtv/player/PlaybackModels.kt` — enrich neutral request metadata and redact `toString()`.
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSessionRequest.kt` — Android/session transport model and Bundle codec.
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackSessionContract.kt` — stable custom command and result codes.
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt` — service-owned player/session lifecycle.
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvMediaControllerConnector.kt` — application-side controller lifecycle.
- Create: `player/media3/src/main/AndroidManifest.xml` — foreground-service permissions and service declaration.
- Modify: `player/media3/build.gradle.kts` — HLS support and Android instrumentation dependencies.
- Create: `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSessionRequestTest.kt` — validation/redaction tests.
- Create: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/MuxTvPlaybackServiceTest.kt` — service/controller smoke contract.

### PR #16 — Channels browser

- Create: `feature/channels` module with `ChannelsViewModel`, immutable UI state and TV list/grid.
- Modify: `feature/home` navigation model to open Channels.
- Modify: `app/tv` Navigation3 host to retain focused channel ID and scroll position.
- Consume: `PlaybackCatalog.observeChannels(ChannelQuery)`.
- Produce: selected `channelId` and optional `preferredVariantId` for the player route.

### PR #17 — Player screen

- Create: `feature/player` module with controller-backed state, surface binding and D-pad overlay.
- Consume: `PlaybackCatalog.resolveVariant(...)` and `MuxTvMediaControllerConnector`.
- Produce: working channel playback, pause/resume, stop, previous/next channel and deterministic Back behavior.

### PR #18 — Source management

- Create: source list/editor UI over `SourceRefreshStore` and `SourceRefreshScheduler`.
- Support manual refresh, policy editing, typed status, recent attempts and policy removal.
- Never display credential material or raw authenticated locators.

---

## Task 1: Enrich and redact playback requests

**Interfaces:**
- Consumes: existing `PlaybackRequest(variantId, locator)` callers.
- Produces: optional `mediaId`, `displayName`, `artworkUri`, and `requestHeaders`; existing two-argument construction remains valid.

- [ ] Add optional metadata and request headers to `PlaybackRequest`.
- [ ] Reject blank header names, CR/LF in names or values, and blank locators.
- [ ] Override `toString()` to print `<redacted>` for locator and only sorted header names.
- [ ] Add JVM tests for backward-compatible construction and redaction.

## Task 2: Add session transport contract

**Interfaces:**
- Consumes: `PlaybackRequest`.
- Produces: `PlaybackSessionRequest`, `toBundle()`, `fromBundle()`, and `SET_PLAYBACK_REQUEST` `SessionCommand`.

- [ ] Encode only validated strings and a nested headers Bundle.
- [ ] Decode defensively; malformed or oversized bundles return `null`.
- [ ] Cap header count at 32 and string fields at explicit lengths.
- [ ] Keep all diagnostic strings secret-safe.

## Task 3: Implement service-owned player/session

**Interfaces:**
- Consumes: session command from Task 2.
- Produces: `MuxTvPlaybackService` discoverable by `SessionToken`.

- [ ] Create one `DefaultHttpDataSource.Factory`, one `DefaultMediaSourceFactory`, one `ExoPlayer`, and one `MediaSession` in `onCreate()`.
- [ ] Add the custom command only for controllers from the MuxTV application package.
- [ ] On the command: stop/clear current media, set per-request User-Agent/Referer properties, build a metadata-rich `MediaItem`, prepare, and play.
- [ ] Return `ERROR_BAD_VALUE` for malformed requests and `ERROR_PERMISSION_DENIED` for foreign callers.
- [ ] Release player then session in `onDestroy()` and clear references.
- [ ] Return the single session from `onGetSession()`.

## Task 4: Manifest and protocol support

- [ ] Add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions.
- [ ] Declare the service with `foregroundServiceType="mediaPlayback"` and Media3/platform intent actions.
- [ ] Add `media3-exoplayer-hls`; do not add DASH/RTSP until corpus-backed need exists.

## Task 5: Controller connector

**Interfaces:**
- Produces: one application-scoped asynchronous controller connection and explicit `release()`.

- [ ] Build a `SessionToken` from `MuxTvPlaybackService`.
- [ ] Build the `MediaController` on the main executor.
- [ ] Make repeated `connect()` calls reuse the same in-flight future.
- [ ] Release controller/future deterministically when the app process shuts down.

## Task 6: Runtime tests

- [ ] JVM: Bundle round-trip, invalid headers, field limits, and redacted output.
- [ ] Android: resolve service token, connect controller, custom command availability for own app, send a malformed request and receive `ERROR_BAD_VALUE`.
- [ ] Android: service creation/destruction does not leak a second session ID.

## Task 7: Channels browser vertical slice

- [ ] Add `feature:channels` module.
- [ ] Observe a bounded active catalog query for the primary profile.
- [ ] Implement loading, empty, content and error states.
- [ ] Use stable channel IDs as keys and restore focus after returning from player.
- [ ] Add D-pad tests at 720p and 1080p.

## Task 8: Player vertical slice

- [ ] Resolve selected channel and preferred variant through `PlaybackCatalog`.
- [ ] Convert the resolved variant into the session request without logging locator/header values.
- [ ] Send the custom setup command, then use standard `MediaController` play/pause/stop commands.
- [ ] Bind video output to the controller-backed Media3 Compose surface.
- [ ] Implement previous/next channel zapping with one in-flight selection at a time.
- [ ] On Back, detach UI surface but do not destroy the service-owned player unless product policy says stop.

## Task 9: Integration and branch completion

- [ ] Keep PR #15 based on `feat/playback-catalog` until PR #14 merges.
- [ ] Run module tests and app assembly without waiting between every implementation commit.
- [ ] Run one exact-head Full and one exact-head API 26/API 36 device matrix before marking ready.
- [ ] Retarget sequentially: #13 → `main`, #14 → `main`, #15 → `main`.
- [ ] Update each PR body with exact SHA, run IDs, evidence artifact and explicitly deferred work.
