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

## Reference applications and reuse policy

| Reference | Why it matters to MuxTV | License/reuse boundary | Adopt now or next | Explicitly do not copy |
| --- | --- | --- | --- | --- |
| Android JetStreamCompose / Jetcaster TV | Official modern Compose-for-TV samples; benchmark and baseline-profile examples; D-pad/focus patterns | Apache-2.0; code/pattern adaptation is acceptable with attribution where required | Macrobenchmark/Baseline Profile structure; Foundation lazy layouts; stable focus identity; release performance measurement | sample data architecture or demo-only navigation structure |
| Jellyfin Android TV | Mature real-world TV playback client with recent startup/home performance work and broad device support | GPL-2.0; concepts only unless a compatible licensing decision is made | defer non-critical startup work after first usable rows when measured; surface-specific lightweight projections; physical/Fire TV testing | source code, GPL implementation details |
| SmartTube | Long-lived Android TV/box app with strong remote-first and weak-device compatibility experience | MIT, but architecture is older and Java-heavy | compatibility scenarios, D-pad-first acceptance, weak-device expectations | application architecture or legacy UI stack |
| StreamVault IPTV | Contemporary TV-first IPTV product on Kotlin/Compose/Room/Hilt/Media3 with Search, Recent and Guide-oriented UX | source-available non-commercial; product behavior reference only | validate Search → Recent → Guide order; numeric channel input later; provider-scoped settings; Guide-over-playback as a later UX option | source code or licensed implementation |
| NuvioTV | Modern Kotlin/Compose/Media3 TV application with a maintained baseline-profile module and Compose performance tooling | GPL-3.0; concepts only | independent confirmation that baseline profiles and TV-specific release testing belong in the toolchain | GPL source or blanket stability declarations |

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

### StreamVault IPTV

Treat StreamVault as a product/interaction reference because its source-available non-commercial license is not appropriate for code reuse in MuxTV. Useful ideas that fit the existing roadmap without new global architecture are:

- Favorites and Recent as first-class daily-use filters;
- numeric remote channel entry as a small post-Guide convenience feature;
- provider-scoped settings rather than global IPTV knobs where behavior genuinely belongs to a source/provider;
- optional Guide-over-playback only after the bounded base Guide is stable;
- manual EPG matching/troubleshooting affordances as extensions of existing deterministic matching provenance, not as a second matching system.

Do not import multi-view, DVR, timeshift or plugin-system scope into the current MVP.

### NuvioTV

Use NuvioTV as independent confirmation that modern TV applications benefit from a maintained baseline-profile/performance module. Its Compose stability configuration is specifically **not** a template to copy blindly: stability configuration is a correctness contract, so MuxTV must first inspect compiler metrics and prove affected UI models are effectively immutable before declaring them stable.

## Immediate product adaptations after Search Core acceptance

1. Search TV uses a destination-scoped ViewModel, 250–300 ms debounce, `flatMapLatest`, bounded repository results and stable canonical channel IDs.
2. Search refreshes Now/Next at `nextBoundaryEpochMillis` with one cancellable timer instead of polling.
3. Search/Channels/Home repositories expose surface-specific projections where measurements show expensive overfetch; playback/detail fields stay behind the existing authoritative boundaries instead of widening every row.
4. Recent is written only after confirmed successful playback and remains profile-scoped/bounded.
5. Guide uses a bounded two-dimensional channel/time viewport; never materialize the full XMLTV schedule into Compose.
6. Introduce channel-zap telemetry from user activation to first rendered video frame before considering preload, cache, Rust or a second playback engine.
7. Establish cold-start-to-first-focus Macrobenchmark before changing startup maintenance ordering.
8. Add R8/resource shrinking, then app-specific Baseline/Startup Profiles after Search/Guide user journeys stabilize.
9. Add numeric D-pad channel entry only after the core daily-use flow is accepted; it should resolve through the existing playback catalog rather than introduce a separate channel registry.

## Explicit non-goals

- no Rust/UniFFI without a measured native-worthy bottleneck;
- no FTS5/bundled SQLite without Search evidence showing FTS4 is insufficient;
- no second playback engine or libmpv without a reproducible Media3 compatibility gap;
- no new global state architecture;
- no Paging3 for bounded Search results;
- no speculative SQLite indexes or PRAGMAs without query-plan and benchmark evidence;
- no DVR/timeshift/multiview/plugin platform in the current MVP optimization stream;
- no blanket Compose stability configuration copied from another application.

## Acceptance

The CI optimization is accepted when:

- a host unit-test failure is observable before any `New-TvAvd`/`Start-TvEmulator` call;
- `Full` host validation runs once per `Invoke-TvDeviceValidation` invocation;
- API26/current profiles run `DeviceOnly` connected instrumentation;
- non-zero instrumentation result counts remain enforced;
- existing `Device` mode remains available for direct/manual validation;
- harness syntax validation covers the new split.
