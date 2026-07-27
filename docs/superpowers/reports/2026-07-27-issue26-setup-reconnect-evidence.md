# Issue #26 Setup Cancellation and Reconnect Evidence

**Implementation branch:** `feat/media3-setup-reconnect`

**DeviceMatrix code head:** `542d7e5eca8fbe039177bc492f46844da68177b2`

**DeviceMatrix run:** `30222900566`

**Status:** passed

## Implemented contracts

- Every setup attempt has an opaque bounded `PlaybackSetupId` whose diagnostics are redacted.
- `MuxTvPlaybackService` owns a bounded setup coordinator beside its one process-owned `ExoPlayer` and `MediaSession`.
- A cancel received before setup prevents that setup from being installed.
- A matching active cancel stops and clears only that setup.
- A stale cancel cannot stop a newer active setup.
- Timeout and parent coroutine cancellation cancel the command future and post exactly one best-effort service-side cancel without replacing the authoritative cancellation.
- A matching remote `MediaSession` disconnect invalidates the cached controller and increments a process-local connection epoch.
- A visible `PlayerRoute` observes that epoch and starts one new bounded connect/resolve/setup attempt without putting locators or headers in navigation or saveable state.
- The debug-only disconnectable service is absent from release source sets and exists only to create a genuine remote-session disconnect in instrumentation.

## TDD and debugging record

1. RED was captured before the production setup types existed.
2. The first reconnect journey incorrectly treated local `MediaController.release()` as a remote session disconnect. API 26 proved that this does not exercise `MediaController.Listener.onDisconnected`; the journey was replaced with a debug-only service that releases and recreates the remote `MediaSession`.
3. Media3 1.10.1 accepted `SessionError.INFO_CANCELLED` at construction time but failed to deserialize the positive non-success code through the API 26 Binder `SessionResult` path. The wire result is therefore the stable negative `ERROR_INVALID_STATE`; coroutine cancellation still propagates as `CancellationException` before the protocol boundary.

## DeviceMatrix evidence

| Profile | System image | RAM / CPU | Fallback | Credentials | Database | Media3 | App |
|---|---|---:|---|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | no | 4 | 19 | 10 | 11 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | no | 4 | 19 | 10 | 11 |

Both profiles completed with zero failures, zero errors and zero skips. Every validation step recorded exit code `0`, including build logic, configuration-cache creation/reuse, pure Kotlin tests, Android unit tests, instrumentation compilation, debug/release assembly, lint and all four connected-test modules.

The matrix started at `2026-07-26T22:19:45Z` and completed at `2026-07-26T22:32:54Z`.

## Artifact review

- Known setup, locator, query and header secret fixtures were not found in collected evidence.
- No instrumentation failure marker, fatal exception or malformed `SessionResult` bundle remained.
- Final API 26/API 36 screenshots show only the Android TV launcher after instrumentation teardown.
- The evidence proves Android API/lifecycle/command ownership on the emulator profiles. It does not prove vendor MediaCodec, HDR, passthrough, Fire OS or constrained ARM hardware compatibility.

## Cleanup

The branch-specific PR #37 and PR #38 DeviceMatrix workflows were deleted after evidence capture. The permanent repository-owned `DeviceCurrent`/`DeviceMatrix` harness remains unchanged.

## Remaining gate

A fresh `Full` validation must pass on the exact cleaned PR head before the PR is marked ready or merged.
