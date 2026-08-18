# Single Service-Owned Seek Authority Design

## Context

Accepted implementation baseline `main@18b520a` has one process-owned Media3 `ExoPlayer`, but seek policy was still duplicated:

- `PlayerSurfaceContent` owned a `PlaybackSeekController` and ultimately called `MediaController.seekTo(targetMs)`;
- `MuxTvPlaybackService` owned another `PlaybackSeekController` and separately handled Media3 forward/back commands.

The player was single-owner, but **seek scheduling/mutation policy was not**. Issue #132 requires one semantic authority before any back-buffer/cache tuning.

Media3's current `MediaSession.Callback.onPlayerCommandRequest` is deprecated. The private MuxTV controller already has a typed custom `SessionCommand`, while standard Media3 controllers must continue to use standard `Player` controls. Current Media3 guidance therefore points to a session-facing `ForwardingSimpleBasePlayer`: it can change standard Player behavior and command availability without creating a second playback engine or state owner.

## Decision

Use one service-owned seek pipeline with two transports converging before scheduling:

```text
MuxTV Compose/native input                     Standard Media3 controller
           │                                             │
           ▼                                             ▼
typed PlaybackSeekRequest                     MuxTvSessionPlayer
(generation + relative/absolute)          (ForwardingSimpleBasePlayer)
           │                                             │
           │                                  current-item semantic intent
           └──────────────────────┬──────────────────────┘
                                  ▼
                        MuxTvPlaybackService
                     validate + single scheduler
                                  │
                                  ▼
                        PlaybackSeekController
                                  │
                                  ▼
                           raw ExoPlayer.seekTo
```

`PlayerSurfaceContent` may retain **presentation-only** provisional target/HUD state. It must not own the coalesce quiet-window or invoke a Media3 seek mutation.

`MuxTvSessionPlayer` is an adapter, not another player authority. It forwards state from the raw service-owned `ExoPlayer`, intercepts standard current-item `seekBack`, `seekForward` and absolute `seekTo(position)`, and emits a semantic intent back to the service. It never delegates those seek mutations to the raw player.

The deprecated `onPlayerCommandRequest` seek policy is removed. Standard and private-controller paths now converge on the same `handleSeekRequest()` and `PlaybackSeekController` before the only production `ExoPlayer.seekTo` call.

## Playback identity

A stable channel/media ID is insufficient as a stale-request guard when the same media is installed again. Every service media installation therefore receives an opaque process-local `seekGeneration: Long`.

The generation is:

- incremented by the service for each installed media source;
- embedded in safe `MediaMetadata.extras` alongside the public MediaItem projection;
- read by the connected MuxTV controller as a `PlaybackSeekToken(mediaId, generation)`;
- required on every private custom seek request;
- compared against the service's active generation before mutation;
- cleared on replacement/stop.

It is not persisted, logged, exported or treated as user data.

Standard Media3 `Player` commands cannot carry this private token. The session adapter therefore binds a standard seek intent to the **current** service token at handling time. This prevents it from resurrecting an old media generation while preserving normal controller compatibility.

## Request and result contract

```kotlin
sealed interface PlaybackSeekRequest {
    val token: PlaybackSeekToken

    data class Relative(
        override val token: PlaybackSeekToken,
        val direction: Int,
    ) : PlaybackSeekRequest

    data class Absolute(
        override val token: PlaybackSeekToken,
        val targetMs: Long,
    ) : PlaybackSeekRequest
}

sealed interface PlaybackSeekResult {
    data class Accepted(val targetMs: Long, val direction: Int) : PlaybackSeekResult
    data class Rejected(val reason: PlaybackSeekRejectReason) : PlaybackSeekResult
}
```

Normal private-command policy rejection is transported as `SessionResult.RESULT_SUCCESS` plus typed extras. Binder/session transport errors remain actual Media3 errors. This keeps policy semantics separate from transport semantics.

Reject reasons are bounded and secret-free: stale playback, command unavailable, live content, unknown duration, invalid position/target and controller rejection.

## Standard Media3 seek surface

The session-facing player advertises only the seek operations represented by #132's current-item semantic contract:

- `COMMAND_SEEK_BACK` -> service relative backward;
- `COMMAND_SEEK_FORWARD` -> service relative forward;
- `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` -> service absolute target when the target is non-negative.

The adapter removes these unsupported alternate seek commands from its exposed state rather than guessing semantics:

- `COMMAND_SEEK_TO_DEFAULT_POSITION`;
- `COMMAND_SEEK_TO_MEDIA_ITEM`;
- previous/next and previous/next-media-item commands.

A negative or default-position (`TIME_UNSET`) target is not converted to zero. If MuxTV later needs live-edge/default-position or playlist navigation, it should extend the semantic contract explicitly rather than add a raw-player bypass.

## Single scheduler

`PlaybackSeekController` remains the one coalescer. Relative and absolute requests share the same internal pending-target scheduler:

- newest request supersedes the pending target;
- one quiet-window applies the final target;
- target is clamped to the current finite duration;
- replacement/reset cancels pending jobs;
- the service scheduler callback is the only production path that invokes `player.seekTo`.

No acceleration tiers, cache policy or second playback engine are added.

## UI presentation

The UI keeps a local provisional `SeekControllerState` projection only:

1. validate currently projected capability to decide whether the key should be consumed;
2. compute immediate provisional target using the existing explicit `PlaybackSeekPolicy.STEP_MILLIS`;
3. submit a typed service request immediately for every D-pad press;
4. reconcile provisional target with the service's typed accepted target;
5. use Media3 discontinuity only as presentation confirmation;
6. clear HUD after the existing bounded linger.

There is **no UI quiet-window job and no UI call to `seekTo`**.

## Compatibility

- Catalog and external playback use the same `PlayerSurfaceContent` and therefore the same private typed seek path.
- External `Activity.dispatchKeyEvent` continues to feed `PlayerRemoteInputHost`; the host handler submits the same service request as Compose `onPreviewKeyEvent`.
- Standard external/system Media3 current-item back/forward/absolute controls remain available through `MuxTvSessionPlayer` and converge on the same service authority.
- Playlist/default-position navigation is intentionally not advertised until MuxTV defines those semantics.
- Player overlay/Back/focus behavior is otherwise unchanged.

## Testing

Required evidence:

- request bundle round-trip and strict malformed-input rejection;
- generation mismatch rejected;
- relative/absolute requests converge on one controller scheduler;
- standard Player command mapping covers back/forward/current-item absolute and rejects unsupported/default-position shapes;
- burst requests produce one final apply target;
- replacement/reset cancels pending mutation;
- no UI `controller.seekTo` production path remains;
- no deprecated `onPlayerCommandRequest` seek policy remains;
- existing Player/EP-08 native D-pad journeys stay green on the **new exact head**;
- host + Android TV DeviceCurrent green before merge.

Because the self-hosted runner is currently offline, adding the final adapter commit does **not** itself constitute fresh host/device evidence. The previous green evidence belongs to the previous exact head only.

## Deliberately deferred

- LoadControl/back-buffer changes (#109);
- `SimpleCache`/disk cache;
- performance thresholds before #27 distributions;
- broad Doctor expansion (#30);
- default-position/live-edge or playlist seek semantics beyond the current #132 contract.
