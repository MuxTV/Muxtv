# Player request/setup/reconnect proxy measurements design

**Issue:** #27  
**Package:** 2C  
**Status:** accepted for implementation

## Problem

MuxTV has deterministic Media3 request transport, SET/CANCEL setup coordination and reconnect ownership contracts, but no reproducible cost evidence for those internal boundaries. Existing UI/device tests prove correctness. They do not describe the cost distribution of preparing and moving a playback request through the repository-owned control path.

The package must not pretend to measure stream startup, decoder work, buffering, zapping, first frame, vendor `MediaCodec`, network latency or physical-TV performance.

## Decision

Add a debug-only Android instrumentation measurement boundary in `player:media3`. It measures five repository-owned proxy operations without starting ExoPlayer, creating a MediaSource, opening a network connection or attaching a Surface.

### Operations

1. `request-construct`
   - construct a validated `PlaybackSessionRequest` from fixed synthetic fields and bounded headers;
   - include immutable header snapshot/validation performed by the production constructor;
   - exclude fixture string/header preparation.

2. `setup-envelope-roundtrip`
   - encode a pre-created request and pre-created `PlaybackSetupId` through `MuxTvPlaybackSessionContract.setupArgs`;
   - decode through `parseSetupArgs`;
   - verify exact typed agreement outside the timer batch result;
   - includes Android `Bundle` request codec and SET envelope codec, but no Binder or `MediaController.sendCustomCommand`.

3. `coordinator-install-active-clear`
   - use production `PlaybackSetupCoordinator` with synthetic install/clear callbacks;
   - for each pre-created ID, install a request and cancel the same active ID;
   - require `Installed` followed by `ActiveCleared`;
   - no ExoPlayer or MediaSource operation is executed.

4. `coordinator-cancel-before-install`
   - cancel a pre-created ID before installation;
   - require `PendingCancelled` followed by `Cancelled` on install;
   - validates and measures the late-install prevention path.

5. `registry-disconnect-reacquire`
   - use production `ControllerConnectionRegistry` with synthetic controller identities and immediate futures;
   - acquire, complete, disconnect, reacquire and complete;
   - require the disconnected controller to invalidate the active connection and the next acquire to produce a new controller;
   - no real `MediaController`, Binder or remote service is involved.

## Workload

Default workload:

- 2 warmup samples;
- 10 retained measured samples;
- 1,000 operation iterations per sample;
- one deterministic synthetic request profile;
- setup IDs/controllers/futures prepared outside timed sections where identity creation is not the operation under measurement.

Each retained sample stores:

- sample index;
- batch wall time in nanoseconds;
- operation count;
- normalized nanoseconds per operation;
- exact successful result count.

All raw samples are retained. Summaries use nearest-rank min/p50/p90/p95/max. No outlier removal is allowed.

## Environment and identity

Every report includes:

- schema and method versions;
- `buildMode=debug-instrumentation`;
- exact 40-character source commit;
- runner label;
- API level, manufacturer, model, fingerprint, ABIs, low-RAM flag, Android memory class and processor count;
- warmup/measured sample counts and operations per sample;
- deterministic request-profile SHA-256 over every constructor field and header name/value boundary;
- `thresholdApplied=false`;
- `failureCount=0` for a publishable report;
- limitations.

Request/profile values, locator, headers, setup IDs and synthetic controller identities never appear in JSON diagnostics or `toString()` output.

## Execution boundary

- Measurement model/runner/writer live in `player/media3/src/debug`.
- One dedicated Android instrumentation class lives in `src/androidTest`.
- The test is excluded from ordinary Media3 DeviceCurrent/DeviceMatrix correctness suites unless `playerProxyMeasurements=true`.
- A repository-owned PowerShell command runs only the dedicated class, materializes the canonical JSON from the instrumentation result bundle and validates the report on the host.
- A manual self-hosted workflow mode may invoke a current Android TV profile. It must not run on every ordinary PR.

## Non-goals

This package does not:

- instantiate or prepare ExoPlayer;
- create `PlaybackMediaSourceFactory` or open HTTP/HLS resources;
- measure Binder, service process startup or real `MediaController` connection;
- measure UI composition, navigation, player surface, buffering, zapping or first frame;
- compare Media3 with libmpv or another engine;
- change SET/CANCEL, reconnect, request codec or controller ownership production behavior;
- introduce AndroidX Benchmark/Macrobenchmark or a threshold gate;
- justify Rust/UniFFI, libmpv, a second engine or architectural rewrite.

## Interpretation

The output is a control-plane proxy baseline for the exact debug emulator environment. It can detect gross regressions in repository-owned request/setup/reconnect code only after repeated comparable series. It cannot support playback-performance or weak-device claims.

## Exit criteria

- five operations in fixed order;
- raw and summarized samples;
- deterministic complete request-profile identity;
- exact environment/source metadata;
- host-validated canonical JSON;
- zero secrets/user payload in diagnostics;
- exact-head Full and focused Android evidence;
- durable report that explicitly declines a production optimization from one series.
