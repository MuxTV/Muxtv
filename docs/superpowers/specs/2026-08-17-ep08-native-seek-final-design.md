# EP-08 native external seek — final design truth

**Date:** 2026-08-17  
**Scope:** PR #167 / EP-08 evidence and the minimum production correctness fix exposed by that evidence.  
**Supersedes for this boundary:** the native-input/liveness assumptions in `2026-08-16-external-seek-input-diagnostics-design.md`. The older document remains useful as debugging history, but this document is the durable architecture contract.

## 1. Proven input path

The Android TV journey no longer relies on Compose-local key injection.

```text
Android DPAD KeyEvent
  -> ExternalPlaybackActivity.dispatchKeyEvent
  -> PlayerRemoteInputHost
  -> one active PlayerSurfaceContent handler
  -> capability/seek policy
  -> PlaybackSeekController
  -> MediaController seek
```

Device evidence proved the platform event reaches an active registration: `attachGeneration=1`, `hasActiveHandler=true`, and the dispatch counter increments for the real system DPAD event. Registration lifetime and Android key transport are therefore not open hypotheses.

`PlayerRemoteInputHost` is single-consumer and identity-safe: a newer registration replaces the old one, and disposing a stale registration cannot detach the new handler. Mutable UI capability/sheet state is read through the current Compose handler rather than by repeatedly detaching and attaching the native boundary.

## 2. Semantic outcome observability

A native dispatch records one bounded, secret-free semantic outcome for the current command. The allowed production-generated labels are:

- `accepted`;
- `controls-visible`;
- `sheet-open`;
- `command-unavailable`;
- `unknown-duration`;
- `live-content`;
- `invalid-position`;
- `controller-rejected`.

The outcome is reset before every dispatch, so a later command cannot inherit stale diagnostic state. No URI, title, channel, token, header, query, media identity, or unrestricted exception text is retained.

This diagnostic exists to establish the causal boundary in EP-08. It is not a second playback authority.

## 3. Live-content classification

EP-08 device evidence exposed a correctness bug: a finite on-device MP4 was rejected as `live-content`.

The rejected implementation used:

```text
currentMediaItem.liveConfiguration != null
```

as the live predicate. That is the wrong semantic boundary. `MediaItem.liveConfiguration` carries application-provided live-offset/live-adjustment configuration; it is not proof that the current Media3 timeline item is live.

MuxTV capability projection now uses Media3's timeline-derived player state:

```text
Player.isCurrentMediaItemLive
```

The capability projection also re-reads the controller immediately after listener registration. This closes the subscription gap where a timeline or available-command change could occur after the initial Compose snapshot but before the listener was attached.

Invariant:

> UI seek eligibility must be projected from the current Player/MediaController timeline and available commands, never inferred from route/source kind or from the presence of MediaItem live configuration.

## 4. Meaningful external seek evidence

The previous journey encoded a 4-second H.264 fixture while `PlaybackSeekPolicy.STEP_MILLIS` is 10,000 ms. That could turn a nominal forward seek into a clamp-to-EOF and was not strong evidence of normal forward-seek behavior.

The EP-08 app journey now uses a 20-second decodable H.264 fixture and, after the service-gated first frame, reads the real active MediaController on the Activity thread and requires all of the following before sending DPAD_RIGHT:

```text
COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM available
isCurrentMediaItemLive == false
duration > PlaybackSeekPolicy.STEP_MILLIS
currentPosition >= 0
currentPosition + STEP_MILLIS < duration
```

Only then does the test send the real Android DPAD_RIGHT event. The accepted semantic outcome and transient seek HUD therefore represent a non-terminal production-sized forward seek, not an EOF clamp.

The app-level journey intentionally does **not** claim that a new HTTP Range request must occur around this seek. HTTP Range, retry, stalled-body and reconnect behavior remain player-level evidence in `ProgressiveResilienceEvidenceTest`.

## 5. First-frame and Back authority

The external Activity exposes its playback surface while setup is pending so Media3 can render. `ExternalPlaybackStartResult.Started` is completed only after the service's guarded first-frame event for the active external setup/media generation. The journey waits for that service-gated first-frame confirmation before asserting seek readiness.

Back remains a real Android system-key path. The terminal journey contract is:

```text
ACTION_VIEW
 -> exact-origin cleartext approval
 -> service-owned external setup
 -> surface attached
 -> first frame confirmed
 -> finite/seekable/nonterminal readiness
 -> real DPAD_RIGHT
 -> accepted seek + HUD
 -> real Android Back
 -> Activity destroyed
 -> no continuing external HTTP traffic
```

A failed instrumentation assertion may destroy ActivityScenario during cleanup; that cleanup must not be misdiagnosed as DPAD_RIGHT causing navigation.

## 6. Scope boundary with Issue #132

EP-08 deliberately does not redesign final seek ownership. At this point MuxTV still has a UI-side seek controller/mutation path and a service-side seek controller/command path. Issue #132 owns the consolidation to one generation-aware semantic seek protocol and one service-owned player mutation authority.

Do not solve #132 by adding another debounce, another controller, or another callback-specific policy inside EP-08.

## 7. Admission evidence

A merge candidate for #167 is admissible only when the **same exact head** has:

- green self-hosted validation;
- green Android TV DeviceCurrent;
- green `player:media3` progressive resilience evidence;
- green `ExternalPlaybackRangeJourneyTest` with the non-terminal readiness checks above;
- no recurrence of malformed `SessionResult` / surface-command timeout behavior fixed by #173.

Dynamic run IDs and current GitHub status belong in the PR/CI control plane, not this durable design document.
