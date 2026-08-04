# TV Reference and CI Optimization Design

## Purpose

Improve MuxTV iteration speed and prepare the next TV UX/performance work without expanding the product architecture. The immediate implementation moves host validation ahead of emulator startup and runs connected instrumentation only on each TV profile. Reference applications are used selectively for proven patterns, not copied wholesale.

## Current problem

The Android TV product and database matrices call `Invoke-TvDeviceValidation.ps1`. For every requested TV profile that script currently boots an AVD and then invokes `verify-local.ps1 -Mode Device`. `Device` repeats build-logic checks, configuration-cache checks, pure Kotlin tests, Android unit tests, instrumentation compilation, debug APK assembly, lint and release assembly before the connected tests.

A host-only regression in `:core:testing:test` therefore consumes emulator boot time before failing and can repeat the same host work on both old-edge and current profiles.

## Design

### 1. Split host acceptance from connected device acceptance

Add `DeviceOnly` to `tools/verify-local.ps1`.

- `Fast`: unchanged host-oriented fast validation.
- `Full`: unchanged complete host acceptance including lint and release assembly.
- `Device`: preserved for direct/manual compatibility; continues to run the existing host + connected suite.
- `DeviceOnly`: runs only connected instrumentation tasks and validates non-zero Android test result counts.

`Invoke-TvDeviceValidation.ps1` performs `Full` exactly once before the first AVD is created, then runs `DeviceOnly` for each resolved API profile. This preserves acceptance depth while removing repeated host work and makes host failures occur before emulator boot.

### 2. Preserve evidence semantics

The TV harness keeps its existing top-level manifest and creates a dedicated `host-validation` evidence subtree. Each device profile retains its own `validation` subtree. `DeviceOnly` is treated as a device mode for instrumentation result-count verification.

### 3. Keep measurement workflows isolated

Catalog and player performance measurement harnesses keep their existing methodology. Correctness acceptance must not be merged with performance series because measurement runs require controlled environments and stable comparison identities.

## Reference applications and directly reusable patterns

### Android JetStreamCompose / Jetcaster TV

Use as the primary implementation reference because it is maintained by Android and Apache-2.0 licensed. Adopt:

- standard Compose Foundation lazy layouts for TV lists/grids as current Android guidance evolves away from TV-specific lazy primitives;
- explicit D-pad/focus ownership and stable item identity;
- Macrobenchmark module structure;
- Baseline Profile generation around critical user journeys;
- release-build performance measurement rather than debug-only conclusions.

Do not import its demo architecture or sample data layer into MuxTV.

### Jellyfin Android TV

Use as an operational reference, not a source to copy because it is GPL-2.0. Adopt ideas independently:

- defer non-critical Live TV/startup work until primary rows are usable;
- request/compute only the fields required for the current surface;
- keep focus on the currently relevant TV item when opening Guide-like experiences;
- maintain strong real-device compatibility including Fire TV.

No Jellyfin source code is copied into MuxTV.

### SmartTube

MIT-licensed, but its architecture is substantially older and Java-heavy. Use only as a compatibility reference:

- D-pad-first interaction is the primary input contract;
- old and weak Android TV/box hardware remains a first-class test target;
- codec/device capability differences must be handled as runtime facts.

Do not transplant its application architecture.

## Immediate product adaptations after Search Core acceptance

1. Search TV uses a destination-scoped ViewModel, 250–300 ms debounce, `flatMapLatest`, bounded repository results and stable canonical channel IDs.
2. Search refreshes Now/Next at `nextBoundaryEpochMillis` with one cancellable timer instead of polling.
3. Guide uses a bounded two-dimensional channel/time viewport; never materialize the full XMLTV schedule into Compose.
4. Introduce channel-zap telemetry from user activation to first rendered video frame before considering preload, cache, Rust or a second playback engine.
5. Establish cold-start-to-first-focus Macrobenchmark before changing startup maintenance ordering.
6. Add R8/resource shrinking, then app-specific Baseline/Startup Profiles after Search/Guide user journeys stabilize.

## Explicit non-goals

- no Rust/UniFFI without a measured native-worthy bottleneck;
- no FTS5/bundled SQLite without Search evidence showing FTS4 is insufficient;
- no second playback engine or libmpv without a reproducible Media3 compatibility gap;
- no new global state architecture;
- no Paging3 for bounded Search results;
- no speculative SQLite indexes or PRAGMAs without query-plan and benchmark evidence.

## Acceptance

The CI optimization is accepted when:

- a host unit-test failure is observable before any `New-TvAvd`/`Start-TvEmulator` call;
- `Full` host validation runs once per `Invoke-TvDeviceValidation` invocation;
- API26/current profiles run `DeviceOnly` connected instrumentation;
- non-zero instrumentation result counts remain enforced;
- existing `Device` mode remains available for direct/manual validation;
- harness syntax validation covers the new split.
