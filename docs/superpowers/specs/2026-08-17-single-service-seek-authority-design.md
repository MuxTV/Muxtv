# Single Service-Owned Seek Authority Design

## Context

Accepted `main@18b520a` has one process-owned Media3 `ExoPlayer`, but seek policy is still duplicated:

- `PlayerSurfaceContent` owns a `PlaybackSeekController` and ultimately calls `MediaController.seekTo(targetMs)`;
- `MuxTvPlaybackService` owns another `PlaybackSeekController` and separately handles Media3 forward/back commands.

The player is single-owner, but **seek scheduling/mutation policy is not**. Issue #132 requires one semantic authority before any back-buffer/cache tuning.

Media3's current `MediaSession.Callback.onPlayerCommandRequest` is deprecated. New internal policy therefore must not be centered on that callback. MuxTV already has an app-internal custom `SessionCommand` boundary, which is the smallest compatible transport.

## Decision

Use one service-owned seek pipeline:

```text
Compose D-pad / External Activity native D-pad / compatibility Media3 commands
                              │
                              ▼
                  typed PlaybackSeekRequest
             (opaque generation + relative/absolute)
                              │
                              ▼
                    MediaSession custom command
                              │
                              ▼
                      MuxTvPlaybackService
                  validate + single scheduler
                              │
                              ▼
                    PlaybackSeekController
                              │
                              ▼
                        ExoPlayer.seekTo
```

`PlayerSurfaceContent` may retain **presentation-only** provisional target/HUD state. It must not own the coalesce quiet-window or invoke a Media3 seek mutation.

The deprecated `onPlayerCommandRequest` remains only as a compatibility adapter for external Media3 forward/back player commands and calls the same service request function. It is not the canonical internal API.

## Playback identity

A stable channel/media ID is insufficient as a stale-request guard when the same media is installed again. Every service media installation therefore receives an opaque process-local `seekGeneration: Long`.

The generation is:

- incremented by the service for each installed media source;
- embedded in safe `MediaMetadata.extras` alongside the public MediaItem projection;
- read by the connected controller as a `PlaybackSeekToken(mediaId, generation)`;
- required on every custom seek request;
- compared against the service's active generation before mutation;
- cleared on replacement/stop.

It is not persisted, logged, exported or treated as user data.

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

Normal policy rejection is transported as `SessionResult.RESULT_SUCCESS` plus typed extras. Binder/session transport errors remain actual Media3 errors. This keeps policy semantics separate from transport semantics and avoids relying on positive informational codes across older Binder behavior.

Reject reasons are bounded and secret-free: stale playback, command unavailable, live content, unknown duration, invalid position/target and controller rejection.

## Single scheduler

`PlaybackSeekController` remains the one coalescer and gains an absolute-target entry point. Relative and absolute requests share the same internal pending-target scheduler:

- newest request supersedes the pending target;
- one quiet-window applies the final target;
- target is clamped to the current finite duration;
- replacement/reset cancels pending jobs;
- service callback is the only production path that invokes `player.seekTo`.

No acceleration tiers, cache policy or new player are added.

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

- Catalog and external playback use the same `PlayerSurfaceContent` and therefore the same custom seek request path.
- External `Activity.dispatchKeyEvent` continues to feed `PlayerRemoteInputHost`; the host handler submits the same service request as Compose `onPreviewKeyEvent`.
- Hardware/external Media3 `COMMAND_SEEK_FORWARD/BACK` remains supported through the compatibility adapter into the same service authority.
- Player overlay/Back/focus behavior is unchanged.

## Testing

Required evidence:

- request bundle round-trip and strict malformed-input rejection;
- generation mismatch rejected;
- relative/absolute requests converge on one controller scheduler;
- burst requests produce one final apply target;
- replacement/reset cancels pending mutation;
- no UI `controller.seekTo` production path remains;
- existing Player/EP-08 native D-pad journeys stay green on exact head;
- host + Android TV DeviceCurrent green before merge.

## Deliberately deferred

- LoadControl/back-buffer changes (#109);
- `SimpleCache`/disk cache;
- performance thresholds before #27 distributions;
- broad Doctor expansion (#30);
- removal of deprecated Media3 compatibility callback if that would reduce external-controller behavior without separate evidence.
