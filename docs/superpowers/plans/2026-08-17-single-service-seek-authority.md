# Single Service-Owned Seek Authority Implementation Plan

**Goal:** Remove UI-side seek mutation/coalescing ownership and route private plus standard current-item relative/absolute seek requests through one generation-aware service-owned `PlaybackSeekController`, with authoritative service-result evidence and replacement-safe seek confirmation.

**Architecture:** Keep the typed Media3 custom `SessionCommand` for MuxTV generation-aware requests. Put a thin `ForwardingPlayer` action adapter in front of the raw service-owned `ExoPlayer` for standard Media3 controls. Both transports normalize into the same service `handleSeekRequest()`. Observable Player state/discontinuity remains raw ExoPlayer state. UI owns only provisional HUD state. Native-input submission and service acceptance are separate phases. Seek confirmation is attributed from the discontinuity MediaItem generation rather than the service's current generation.

**Tech Stack:** Kotlin, Coroutines/Flow, AndroidX Media3 1.10.1 session/ExoPlayer, Compose for TV, JUnit/Truth, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-17-single-service-seek-authority-design.md`

## Global constraints

- One process-owned `ExoPlayer` remains owned by `MuxTvPlaybackService`.
- One production seek mutation/coalescing authority remains `PlaybackSeekController` inside the service.
- No LoadControl, back-buffer, `SimpleCache`, codec, FFmpeg or performance-policy work in this PR.
- UI may keep provisional HUD state but may not call a Media3 seek mutation directly.
- Playback generation remains process-local, opaque, non-persistent and secret-free.
- Exact-head evidence is valid only for the exact commit tested; any code/doc commit after an acceptance run invalidates final-head acceptance.
- Final execution evidence belongs in PR metadata so recording run IDs does not mutate the already-tested repository head.

---

## Task 1: Define seek identity and session contract — COMPLETE

**Files:**
- `player/media3/src/main/kotlin/app/muxtv/player/media3/PlaybackSeekContract.kt`
- `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackSessionContract.kt`
- `player/media3/src/test/kotlin/app/muxtv/player/media3/PlaybackSeekContractTest.kt`

- [x] Add opaque `PlaybackSeekToken(mediaId, generation)` and relative/absolute request/result types.
- [x] Add strict custom-command bundle encoding/parsing and typed policy result encoding/parsing.
- [x] Reject extra/missing keys, blank media IDs, invalid directions, non-positive generations and negative absolute targets.
- [x] Keep normal policy rejection in successful transport payload; reserve Media3 error codes for transport/permission errors.

## Task 2: Add real install generation and one service scheduler — COMPLETE

**Files:**
- `PlaybackMediaSourceFactory.kt`
- `PlaybackSeekController.kt`
- `MuxTvPlaybackService.kt`
- controller/contract unit tests

- [x] Assign a monotonically increasing process-local seek generation on every installed MediaSource.
- [x] Publish generation in safe MediaMetadata extras so controllers can obtain an opaque token.
- [x] Share one pending-target/coalesce scheduler for relative and absolute requests.
- [x] Validate active token, command availability, live/finite duration and current position in one service `handleSeekRequest()`.
- [x] Route private custom commands through that function.
- [x] Reset generation/controller state on replacement/stop.

## Task 3: Remove UI mutation ownership — COMPLETE

**Files:**
- `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerSurfaceContent.kt`

- [x] Remove the UI-owned `PlaybackSeekController` and direct Media3 seek mutation callback.
- [x] Keep immediate provisional HUD target state using explicit 10s step policy; no UI quiet-window/coalescing authority.
- [x] Send every locally admissible request through the typed service command.
- [x] Reconcile accepted target/rejection asynchronously; use Media3 discontinuity only for presentation confirmation.
- [x] Keep external native D-pad and Compose preview-key paths converged on the same request function.

## Task 4: Close the standard Media3 Player-command bypass — COMPLETE

**Files:**
- `MuxTvSessionPlayer.kt`
- `MuxTvPlaybackService.kt`
- `MuxTvSessionPlayerInstrumentedTest.kt`
- `PlayerCommandResultPolicyTest.kt`

- [x] Pass `MuxTvSessionPlayer : ForwardingPlayer`, not raw ExoPlayer, to `MediaSession`.
- [x] Intercept `seekBack`, `seekForward` and current-item absolute `seekTo` and normalize them into semantic service intents.
- [x] Bind standard intents to the current service generation and same `handleSeekRequest()` / `PlaybackSeekController`.
- [x] Never delegate intercepted seek mutations to raw ExoPlayer.
- [x] Keep observable Player state/discontinuity delegated to raw ExoPlayer; do not create a second optimistic seek-state machine.
- [x] Filter default-position, cross-item and previous/next seek commands until explicitly modeled; keep defensive no-op overrides.
- [x] Remove deprecated `onPlayerCommandRequest` seek policy path.
- [x] Cover current-item/cross-item/negative-target/available-command behavior.

## Task 5: Separate input submission from authoritative service acceptance — IMPLEMENTED

**Files:**
- `PlayerSurfaceContent.kt`
- `SeekInputOutcomeTest.kt`
- existing EP-08 `ExternalPlaybackRangeJourneyTest.kt`

- [x] Author RED contract first.
- [x] Observe RED on test-only head `c57e1ff6f43eb336f0fbae508855800be228c231` / host run `32277087227`: compile failed specifically because production lacked `SUBMITTED`, `SERVICE_ACCEPTED` and `handlesDispatch` after checkout/provenance/preflight succeeded.
- [x] Implement provisional `SUBMITTED` that consumes the local event but does not publish authoritative remote semantic acceptance.
- [x] Publish existing `accepted` semantic tag only after `PlaybackSeekResult.Accepted`.
- [x] Keep typed rejection/timeout outcomes bounded and diagnosable.
- [x] Keep D-pad consumption bound to submission, not asynchronous service completion.
- [ ] Final integrated exact-head host/device verification. The unchanged EP-08 journey must still wait for `external-seek-input-accepted`, proving service acceptance rather than immediate UI dispatch.

## Task 6: Attribute confirmation to discontinuity MediaItem generation — IMPLEMENTED

**Files:**
- `PlaybackSeekTokenProjection.kt`
- `MuxTvPlaybackService.kt`
- `PlaybackSeekTokenProjectionInstrumentedTest.kt`
- existing `PlaybackSeekControllerTest.kt`

- [x] Author the generation-projection regression contract before GREEN implementation.
- [x] Preserve the historical fact that a standalone device RED was not observed: the test-only device run was cancelled by later PR pushes before instrumentation execution.
- [x] Add `MediaItem.playbackSeekToken()`; `Player.currentPlaybackSeekToken()` delegates to it.
- [x] Derive seek-confirmation generation from `newPosition.mediaItem` and fail closed when provenance is missing/malformed.
- [x] Do not substitute `activeSeekGeneration` for unknown event provenance.
- [x] Preserve controller rejection of foreign-generation confirmations.
- [ ] Final integrated exact-head player instrumentation verification on the restacked head.

## Task 7: Integrate with accepted main and regenerate final evidence — IN PROGRESS

The earlier branch was valid against the pre-stabilization accepted main but accumulated unrelated accepted-main commits. On 2026-08-19 it was restacked onto the current accepted product/CI baseline without replaying or overwriting accepted subtrees.

Current integration state before final acceptance:

- [x] Accepted CI evidence publication #176 is in main.
- [x] Accepted user-unlocked lifecycle #177 is in main.
- [x] Restack #175 onto `main@c9a840348f175c2d7665aec2a56916e2fb81cea3` with merge-base equal to current main.
- [x] Verify the restacked diff remains exactly the 19 seek-authority plan/spec/player files and does not revert #176/#177.
- [x] Independent adversarial self-review of service authority, standard-command adapter, command-set/event consistency, token provenance, stale-generation handling and UI acceptance semantics found no runtime merge blocker.
- [x] Reconcile this execution plan so it no longer claims already-implemented work is pending or requires a separate human reviewer.
- [ ] Run exact-final-head host validation after this documentation reconciliation commit.
- [ ] Run exact-final-head Android TV DeviceCurrent. This run is mandatory because accepted main now contains product lifecycle changes; older #175 device evidence is regression history only, not integrated acceptance.
- [ ] Confirm required checks/artifacts are bound to the same final head and no later repository commit invalidates them.
- [ ] Record final independent self-review verdict and exact run/artifact references in PR #175 metadata without changing the tested repository head.
- [ ] Squash-merge only with `expected_head_sha` guard.
- [ ] Update Issue #132 after merge: mark only the single-service authority slice accepted; keep measurement/back-buffer/cache/Doctor work evidence-gated under #27/#109 or focused follow-ups.

## Known non-blocking follow-up debt

These are deliberately not bundled into this authority slice unless final integrated evidence proves otherwise:

- `PlaybackSeekController` uses an untyped internal generation key (`Any`) although production generation is monotonic `Long`; tighten type safety separately if useful.
- UI may have one suspended custom-command waiter per locally admissible repeat even though service mutation/coalescing is bounded; waiter/request traffic can be bounded separately without restoring UI mutation ownership.
- seek-apply exceptions currently converge through controller timeout rather than a typed Doctor `SEEK_FAILED` observation; add typed diagnostic observation as a focused follow-up.
- performance work (back-buffer, LoadControl tuning, disk cache) remains measurement-gated and is outside this PR.

## Historical evidence — regression history only

Earlier heads passed host and Android TV validation, including Media3 instrumentation and external-player journeys. Those runs prove the implementation had executable coverage during development, but they are **not final merge evidence** after accepted-main integration. Final acceptance is only the exact restacked head produced after this plan reconciliation, and exact run/artifact identifiers are recorded in PR #175 metadata rather than by mutating this file after the run.
