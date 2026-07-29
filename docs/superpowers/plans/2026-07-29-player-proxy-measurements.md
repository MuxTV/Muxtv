# Player Proxy Measurements Implementation Plan

> **Status:** implementation and focused Android evidence complete; final permanent-tree Full is running before merge.

**Goal:** Measure repository-owned Player request construction, SET envelope codec, setup coordination and reconnect registry proxies on Android without invoking ExoPlayer, network, Binder service startup or UI.

**Architecture:** Debug-only model/runner/writer in `player:media3`; one dedicated instrumentation entry; repository-owned PowerShell host validation; canonical result-bundle JSON; permanent manual self-hosted mode.

## Constraints preserved

- no production request/setup/reconnect behaviour change;
- no public API expansion or release dependency;
- no ExoPlayer, MediaSource, network, Surface, Binder-service or first-frame work;
- 2 warmup samples, 10 retained samples and 1,000 operations per sample;
- five fixed operations and all raw samples retained;
- nearest-rank min/p50/p90/p95/max;
- `thresholdApplied=false` and no failing budget;
- complete deterministic request-profile SHA-256;
- no locator/header/setup/controller values in report diagnostics;
- ordinary DeviceCurrent/DeviceMatrix exclude the measurement class without skips.

## Completed TDD sequence

### Task 1 — opening RED contracts

- [x] nearest-rank and immutable-snapshot unit contracts;
- [x] request-profile field/header digest contracts;
- [x] canonical LF JSON and payload-redaction contract;
- [x] fixed five-operation instrumentation contract;
- [x] opening Full failed on absent measurement types.

### Task 2 — model, statistics and identity

- [x] bounded workload/spec/environment/sample/operation/report models;
- [x] immutable snapshots;
- [x] nearest-rank statistics;
- [x] length-prefixed digest over every request field, nullable boundary and sorted header pair;
- [x] payload-redacted request/report diagnostics;
- [x] fixed-order UTF-8/LF JSON writer.

### Task 3 — Android proxy runner

- [x] synthetic `.example` fixture and identities prepared outside timers;
- [x] request construction batch;
- [x] SET envelope encode/decode round-trip;
- [x] install + active clear through `PlaybackSetupCoordinator`;
- [x] cancel-before-install rejection path;
- [x] `ControllerConnectionRegistry` disconnect + reacquire sequence;
- [x] exact result-count verification;
- [x] Android environment capture;
- [x] no publishable report after invariant failure.

### Task 4 — dedicated instrumentation boundary

- [x] `PlayerProxyMeasurement` annotation;
- [x] default-suite exclusion;
- [x] focused class execution through `playerProxyMeasurements=true`;
- [x] strict bounded arguments;
- [x] fixed operation order/count assertions;
- [x] Base64 instrumentation result-bundle publication.

### Task 5 — host and self-hosted execution

- [x] `Invoke-PlayerProxyMeasurement.ps1` for one booted device;
- [x] stale-result cleanup;
- [x] exact class execution;
- [x] one fresh result payload decode;
- [x] schema/build/source/digest/operation/sample host validation;
- [x] current-TV AVD wrapper;
- [x] bounded failure metadata and child logs;
- [x] permanent manual `PlayerMeasurement` workflow mode;
- [x] deterministic Android SDK bootstrap when runner env variables are absent.

## Evidence

Reviewed focused head: `a5e8756f03716873531db9da2155f3a7de21bb15`.

- dedicated run: `30478201477` — success;
- artifact: `pr-player-proxy-measurement-30478201477-1`;
- artifact digest: `sha256:59566b3da0d34eb111adb2d471fe23d60b74444344e11d4996bdd8bcd87feba1`;
- Android TV API 36 x86_64, 2 cores, memory class 192 MB, no fallback;
- request profile SHA-256: `de27c2dad7cb740dab5a62189b7ff5da78b851a217d18e1698497fd44c135a75`;
- ten retained samples for each of five operations;
- 1,000 successful results per sample;
- `failureCount=0`, `thresholdApplied=false`;
- durable interpretation: `docs/performance/2026-07-29-player-proxy-baseline.md`.

## Final merge gates

- [x] focused Android evidence on the reviewed source head;
- [x] raw distributions and limitations documented;
- [x] temporary PR workflow removed;
- [ ] Full run `30479409421` on final permanent-tree head `ec6abd67958ac07ee956e86e52a10c72cabde8ff`;
- [x] no unresolved review threads before final Full;
- [ ] mark ready and squash merge;
- [ ] update issue #27.

## Follow-up after this package

1. Repeat parse, Room and Player proxy series on comparable current, old-edge and low-RAM virtual profiles.
2. Calculate cross-series median, range and coefficient of variation.
3. Decide whether any dedicated threshold gate is justified.
4. Fix playback-request header snapshot ownership in a separate compatibility-focused package.
5. Do not infer codec, zapping, first-frame, Fire OS or physical-TV performance from emulator proxy data.
