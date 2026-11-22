# Guide Catch-up Through Service-Owned Playback Intent — Design

Issue: #305
Parent: #184
Follows: #285 / merged #304

## Goal

Selecting a completed Guide programme starts provider catch-up through the existing service-owned bounded playback/recovery path. Guide and navigation carry only provider-neutral semantic identity/timestamps; provider URL/template materialization remains in catalog/provider resolution.

## Current gap

- Guide programme cells currently call `onOpenChannel(channelId)` for every programme/status click.
- `PlaybackCatalog.resolveIntent()` can already materialize persisted M3U catch-up for `PlaybackIntent.CatchupProgram` / `CatchupPosition`.
- `PlaybackStartRequest` carries only `profileId`, `channelId`, and optional preferred variant.
- `MuxTvPlaybackService` owns candidate ordering/recovery and calls `PlaybackCandidateResolver.resolveCandidate(...)`.
- Resolving catch-up before the service would bypass the accepted recovery/generation owner.
- Guide display bounds are clipped to the viewport; playback must use original EPG programme bounds.

## Accepted architecture

### 1. Playback start request remains provider-neutral

`PlaybackStartRequest` owns a `PlaybackIntent` plus profile/preferred-variant identity. Preserve the existing Live constructor so existing call sites remain source-compatible:

```kotlin
PlaybackStartRequest(profileId, channelId, preferredVariantId)
```

maps to `PlaybackIntent.Live(channelId)`.

`channelId` remains available as a derived property from `intent.channelId`. Equality/hash/toString include semantic intent without exposing identities.

### 2. Session command carries semantic intent only

The Media3 custom-command Bundle continues to exclude locator, headers, credentials and provider templates.

Live requests retain the existing key set. Catch-up requests add only a bounded intent discriminator plus programme/position identity and UTC epoch fields. Parsing is strict/fail-closed.

### 3. Service keeps candidate/recovery ownership

Add an intent-aware candidate seam to `PlaybackCandidateResolver`:

```kotlin
suspend fun resolveIntentCandidate(
    profileId: String,
    intent: PlaybackIntent,
    candidate: PlaybackCandidateIdentity,
): PlaybackVariantResolution?
```

Default behavior delegates Live to `resolveCandidate(...)` and reports archive unsupported for archive intents. `MuxTvPlaybackService` calls this method for the active request while leaving `PlaybackRecoveryOrchestrator` candidate ordering, attempt bounds, deadlines, generation cancellation and installation unchanged.

`RoomPlaybackCatalog` overrides the method and resolves catch-up for the exact candidate selected by the service. The final materialized locator still passes through the existing local-network/exact-origin access path.

### 4. Guide emits semantic playback selection

Guide gets a small provider-neutral selection model:

```kotlin
sealed interface GuidePlaybackSelection {
    data class Live(val channelId: String) : GuidePlaybackSelection
    data class CatchupProgram(
        val channelId: String,
        val programmeId: String,
        val startEpochMillis: Long,
        val endEpochMillis: Long,
    ) : GuidePlaybackSelection
}
```

Selection rules at click time:

- status/non-programme cell -> Live;
- current programme (`start <= now < end`) -> Live;
- completed programme (`end <= now`) -> CatchupProgram;
- future programme (`start > now`) -> no launch.

Guide projection stores original programme start/end separately from clipped visible start/end. Catch-up uses original bounds only.

`GuideProgrammeKey` is converted to a bounded opaque programme identity using revision + sequence scoped by the channel; raw title/locator/provider template is never placed in navigation.

### 5. Navigation carries only bounded semantic values

`AppDestination.Player` remains serializable and stores channel plus optional catch-up programme identity/start/end as an all-or-none tuple. AppNavigation reconstructs `PlaybackIntent` at the player boundary.

`PlayerRoute` continues to use the channel for catalog title/favorite/access UI but submits the supplied semantic intent through `PlaybackStartRequest`.

## Invariants

- one process-owned Media3 player/session;
- one service-owned recovery owner;
- #132 remains the only active seek mutation authority;
- no M3U/Kodi/Xtream template parsing in Guide/navigation/player:media3;
- no locator/query/token/Cookie/Authorization value in navigation, command diagnostics or UI semantics;
- no Room migration;
- Live playback behavior remains source-compatible and behavior-identical;
- canonical persistent AVDs remain exactly API26 + API36.

## Error/fallback behavior

Archive-unavailable candidate results remain `PlaybackVariantResolution.AccessUnavailable`; the existing recovery machine may advance to another candidate within current attempt/time bounds. Local-network permission and cleartext approval remain evaluated on the final resolved candidate transport. No new retry loop or catch-up state machine is added.

## Test contract

1. `PlaybackStartRequest` preserves Live compatibility and archive semantic identity while redacting diagnostics.
2. Media3 setup Bundle round-trips `CatchupProgram` and `CatchupPosition`, keeps Live wire shape, and rejects malformed/secret-bearing extras.
3. Intent-aware candidate resolution uses the exact candidate selected by recovery; Live defaults to current behavior.
4. `RoomPlaybackCatalog` resolves an archive intent for an explicit candidate without reselecting another candidate.
5. Guide pure selection tests prove past/current/future/status behavior and prove original programme bounds survive viewport clipping.
6. Existing local-network, cleartext, playback recovery and Guide focus tests remain green.

## Non-goals

Xtream catch-up; local timeshift; DVR; VOD/Series; a new provider framework; a new player/retry/seek owner; buffer tuning; a third persistent AVD.