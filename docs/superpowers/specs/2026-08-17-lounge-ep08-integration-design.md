# Lounge Light + EP-08 player integration design truth

**Date:** 2026-08-17  
**Scope:** PR #168 integration on top of accepted PR #167 / `main@b40145ed5756a03ac52130e321fd05325c1a0836`.

## 1. Why an explicit integration design exists

Lounge Light and EP-08 both modify the shared player surface, but for orthogonal reasons:

- EP-08 owns the native Android-TV remote boundary, synchronous seek diagnostics, stable handler registration and finite/live capability correctness;
- Lounge owns presentation: theme tokens, raised overlay surface and reveal animation.

The correct integration is additive. Neither side may replace the other wholesale.

## 2. Shared player-surface invariant

The integrated `PlayerSurfaceContent` must preserve this behavior path:

```text
real Android DPAD
 -> ExternalPlaybackActivity.dispatchKeyEvent
 -> PlayerRemoteInputHost
 -> current PlayerSurfaceContent handler
 -> capability-driven seek classification
 -> PlaybackSeekController
 -> MediaController
```

while retaining the Lounge overlay presentation:

```text
controlsVisible
 -> overlay composes only while visible
 -> animateFloatAsState reveal alpha
 -> TvTokens.Motion.overlayInMillis / easeOut
 -> TvTokens.Color.surfaceRaised @ 0.94
```

The animation is reveal-only. Hidden controls are not kept focusable during an exit animation; Back/focus semantics remain the pre-existing dense-TV contract.

## 3. Conflict resolution applied

The shared file was reconciled from the accepted EP-08 implementation and only the Lounge visual delta was reapplied:

- `animateFloatAsState` and `tween` imports;
- `graphicsLayer` import;
- `overlayAlpha` state;
- `graphicsLayer { alpha = overlayAlpha }` on the overlay;
- raised Lounge surface color.

The following EP-08 behavior is explicitly retained:

- `remoteInputHost` parameter;
- `rememberUpdatedState` handler indirection;
- one registration per `remoteInputHost + contentIdentity` lifetime;
- typed seek outcomes and synchronous `recordSemanticOutcome`;
- hidden-surface native Left/Right handling;
- seek HUD and service/external journey compatibility.

## 4. Capability projection invariant

The integration inherits accepted EP-08 capability projection unchanged:

- live state comes from `Player.isCurrentMediaItemLive`;
- duration/available commands come from the current Player state;
- capabilities are re-snapshotted after listener registration to close the initial snapshot/subscription race.

Lounge must not infer seekability/live state from visual route state, `MediaItem.liveConfiguration`, source labels or persisted metadata.

## 5. Gradle integration invariant

`app/tv` must contain both independent dependencies:

```text
implementation(project(":feature:settings"))
androidTestImplementation(project(":core:testing"))
```

The first is required by Lounge navigation/settings; the second is required by accepted EP-08 device evidence.

## 6. Focus and compact-height stabilization carried by Lounge

The integrated tree keeps the already-proven Lounge stabilization contracts:

- Channels restoration uses stable identity and central `FocusAnchor.resolveAgainst()` fallback after paging completion;
- TV tests activate filters by real D-pad traversal rather than touch-only `performClick()` surrogates;
- Source Details keeps `Готово` inside the same lazy focus container;
- compact-height traversal is explicit through operational actions to `close`, with focus contained at the final action;
- no sleep or timeout inflation is used as a focus/lifecycle fix.

## 7. Architectural boundaries after merge

This integration does **not** close Issue #132. The repository still has a UI-side seek mutation/coalescing path and a service-side seek controller/command path. The next seek architecture slice must converge all relative/absolute intents on one generation-aware service-owned mutation authority.

This integration also does not claim physical-TV codec/HDR/passthrough compatibility. Emulator evidence and physical acceptance remain separate; physical/vendor acceptance belongs to #31.

## 8. Acceptance

The merge candidate is admissible only when one exact integrated head containing this design has:

- green self-hosted validation;
- green Android TV DeviceCurrent;
- green Lounge Channels/Settings/Guide/Search/Player journeys;
- green compact-height Source Details journey;
- green accepted EP-08 Media3 and external-player journeys, including native DPAD seek and Back/stop;
- no loss of either shared Gradle dependency.

Current run IDs and GitHub merge state are control-plane data and intentionally do not belong in this durable design document.
