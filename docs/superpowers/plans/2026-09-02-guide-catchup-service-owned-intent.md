# Guide Catch-up Through Service-Owned Playback Intent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route completed Guide programmes into provider catch-up without bypassing the existing service-owned candidate recovery/player path.

**Architecture:** Extend the provider-neutral playback start request with `PlaybackIntent`, keep Live source-compatible, serialize only semantic intent through the Media3 command boundary, and make candidate resolution intent-aware while preserving the existing recovery machine. Guide emits a semantic Live/Catchup selection based on original EPG programme bounds; AppNavigation stores only bounded semantic fields and reconstructs the playback intent.

**Tech Stack:** Kotlin, Coroutines, Compose for TV, Navigation3, Media3, Room3, JUnit/Truth, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-02-guide-catchup-service-owned-intent-design.md`

## Global Constraints

- One process-owned Media3 player/session.
- One service-owned candidate/recovery owner.
- #132 remains the only active seek mutation authority.
- No provider URL/template parsing in Guide, navigation or `player:media3`.
- No raw locator/query/token/Cookie/Authorization value in navigation, Bundle diagnostics, logs or UI semantics.
- No Room schema change.
- Live behavior remains source-compatible.
- Persistent AVD set remains exactly API26 + API36.
- RED must be observed before production code for each behavior group.

---

### Task 1: PlaybackStartRequest semantic intent contract

**Files:**
- Modify: `player/api/src/test/kotlin/app/muxtv/player/PlaybackStartRequestTest.kt`
- Modify after RED: `player/api/src/main/kotlin/app/muxtv/player/PlaybackStartRequest.kt`

**Interfaces:**
- Consumes: existing `PlaybackIntent`.
- Produces: `PlaybackStartRequest(profileId, intent, preferredVariantId)` plus source-compatible Live constructor and derived `channelId`.

- [ ] Add RED tests that construct `CatchupProgram` and `CatchupPosition`, assert `request.intent`, derived `channelId`, equality/hash distinction from Live, and secret-free `toString()`.
- [ ] Run the player API unit test lane and confirm failure because `PlaybackStartRequest` does not accept/preserve `PlaybackIntent`.
- [ ] Implement the minimal semantic-intent constructor, Live secondary constructor, derived `channelId`, equality/hash and redacted diagnostics.
- [ ] Re-run player API tests and keep all legacy Live tests green.

### Task 2: Media3 command codec for semantic intent

**Files:**
- Modify: `player/media3/src/androidTest/kotlin/app/muxtv/player/media3/PlaybackSetupCommandCodecTest.kt`
- Modify after RED: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackSessionContract.kt`

**Interfaces:**
- Consumes: `PlaybackStartRequest.intent`.
- Produces: strict Bundle round-trip for Live, CatchupProgram and CatchupPosition.

- [ ] Add RED tests for `CatchupProgram` and `CatchupPosition` round-trip.
- [ ] Assert existing Live requests still encode only `profile_id`, `channel_id`, optional `preferred_variant_id`.
- [ ] Add malformed catch-up payload tests: partial programme tuple, unknown intent kind, negative/invalid epoch values, and unexpected secret-bearing fields must fail closed.
- [ ] Observe RED in the API26/API36 Media3 instrumentation lane.
- [ ] Add only semantic keys: `intent_kind`, `programme_id`, `programme_start_epoch_millis`, `programme_end_epoch_millis`, `position_epoch_millis`; omit intent kind for Live.
- [ ] Parse strict key sets and reconstruct `PlaybackIntent`; no locator/header/template keys accepted.
- [ ] Re-run codec tests on canonical APIs.

### Task 3: Intent-aware exact-candidate resolution

**Files:**
- Modify: `catalog/api/src/main/kotlin/app/muxtv/catalog/PlaybackCatalog.kt`
- Add/modify tests in `catalog/api` for default `PlaybackCandidateResolver` behavior.
- Modify: `core/database/src/androidTest/kotlin/app/muxtv/database/CatchupPlaybackAccessPathTest.kt`
- Modify after RED: `core/database/src/main/kotlin/app/muxtv/database/RoomPlaybackCatalog.kt`

**Interfaces:**
- Produces:
```kotlin
suspend fun PlaybackCandidateResolver.resolveIntentCandidate(
    profileId: String,
    intent: PlaybackIntent,
    candidate: PlaybackCandidateIdentity,
): PlaybackVariantResolution?
```
- Live default delegates to `resolveCandidate`; archive default returns `ArchiveUnsupported`.

- [ ] Add RED database test proving catch-up resolution for an explicitly supplied candidate does not reselect another variant and preserves the resolved timeline.
- [ ] Add pure contract test proving default Live delegation and default archive unsupported behavior.
- [ ] Observe RED.
- [ ] Add the default method to `PlaybackCandidateResolver`.
- [ ] Refactor `RoomPlaybackCatalog.resolveIntent()` to select once then call `resolveIntentCandidate(...)`.
- [ ] Override `resolveIntentCandidate(...)` to query exactly the supplied active variant row, run archive resolver, then existing access coordinator.
- [ ] Re-run catalog/database tests including API26/API36 schema parity; schema version must remain unchanged.

### Task 4: Service-owned recovery consumes semantic intent

**Files:**
- Add/modify service/recovery tests under `player/media3/src/test` or `player/media3/src/androidTest` using the existing fake candidate resolver seam.
- Modify after RED: `player/media3/src/main/kotlin/app/muxtv/player/media3/MuxTvPlaybackService.kt`.

**Interfaces:**
- Consumes: `PlaybackStartRequest.intent`, `PlaybackCandidateResolver.resolveIntentCandidate(...)`.
- Produces: existing bounded recovery behavior for archive intents.

- [ ] Add RED test where candidate A returns archive unavailable and candidate B returns Ready only through `resolveIntentCandidate`; assert the service attempts A then B under the existing recovery sequence.
- [ ] Add RED assertion that the exact same CatchupProgram intent reaches both candidate attempts and no pre-service resolver is used.
- [ ] Observe RED.
- [ ] Change only the service candidate-resolution call from `resolveCandidate(...)` to `resolveIntentCandidate(profileId, request.intent, candidate)`.
- [ ] Keep `PlaybackRecoveryOrchestrator`, local-network gate, approval flow, install path, attempt/deadline logic and player ownership unchanged.
- [ ] Re-run recovery/service tests.

### Task 5: Guide semantic selection with original programme bounds

**Files:**
- Modify: `feature/guide/src/test/kotlin/app/muxtv/feature/guide/GuidePresentationTest.kt`
- Create: `feature/guide/src/test/kotlin/app/muxtv/feature/guide/GuidePlaybackSelectionTest.kt`
- Modify after RED: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuidePresentation.kt`
- Create after RED: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuidePlaybackSelection.kt`
- Modify after RED: `feature/guide/src/main/kotlin/app/muxtv/feature/guide/GuideRoute.kt`

**Interfaces:**
- Produces `GuidePlaybackSelection.Live` / `CatchupProgram` and pure `guidePlaybackSelection(channelId, cell, nowEpochMillis)`.

- [ ] Add RED projection test where a programme begins before the viewport; assert visible start is clipped but original programme start/end are retained for playback semantics.
- [ ] Add RED selection tests: status -> Live, current -> Live, completed -> CatchupProgram(original bounds), future -> null.
- [ ] Observe RED in `:feature:guide:test`.
- [ ] Add original programme bounds to `GuideCellProjection` as an all-or-none pair tied to a real programme key.
- [ ] Add a bounded programme identity derived from EPG revision + sequence and scoped by channel semantics; never include title or provider locator/template.
- [ ] Change Guide callbacks from channel-only to `GuidePlaybackSelection`; future programme clicks do not invoke playback.
- [ ] Re-run Guide presentation/focus/paging tests.

### Task 6: Navigation and PlayerRoute semantic launch

**Files:**
- Modify/add app navigation tests under `app/tv/src/androidTest` for Guide -> Player launch behavior.
- Modify after RED: `app/tv/src/main/kotlin/app/muxtv/navigation/AppDestination.kt`
- Modify after RED: `app/tv/src/main/kotlin/app/muxtv/navigation/AppNavigation.kt`
- Modify after RED: `app/tv/src/main/kotlin/app/muxtv/navigation/PlayerFavoriteRoute.kt`
- Modify after RED: `feature/player/src/main/kotlin/app/muxtv/feature/player/PlayerRoute.kt`
- Modify/add player tests as needed.

**Interfaces:**
- `AppDestination.Player` carries channel plus optional all-or-none catch-up programme semantic tuple.
- `PlayerRoute` receives a `PlaybackIntent` while retaining channel-derived UI/favorite behavior.

- [ ] Add RED navigation/player test that a completed Guide programme produces a CatchupProgram start request with original bounds.
- [ ] Add RED regression test that Channels/current Guide still submit Live start requests.
- [ ] Observe RED.
- [ ] Extend serializable Player destination with optional programme tuple and constructor invariants.
- [ ] Convert Guide selection -> destination and destination -> `PlaybackIntent` in AppNavigation.
- [ ] Pass semantic intent through PlayerFavoriteRoute/PlayerRoute; all existing title/favorite/permission/approval behavior continues to use `intent.channelId`.
- [ ] Re-run player/navigation journeys including local-network and cleartext approval tests.

### Task 7: Exact-head qualification and merge

**Files:** no new production files.

- [ ] Run/observe exact-head Hosted validation.
- [ ] Run/observe Hosted CI contract.
- [ ] Run/observe database API26/API36 matrix because database candidate resolution changed but schema must not.
- [ ] Run/observe App TV lint and focused API36 TV journey because Guide/navigation/player changed.
- [ ] Confirm no unresolved review threads.
- [ ] Confirm PR head is 0 behind current main and only intended files changed.
- [ ] Merge with `expected_head_sha` only after every required gate is terminal GREEN.
- [ ] Close #305 with exact merged SHA and evidence; leave Xtream catch-up for a separate child slice.