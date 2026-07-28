# Player Proxy Measurements Implementation Plan

> **Execution mode:** TDD on branch `perf/player-proxy-measurements`. Preserve production behaviour and publish descriptive evidence only.

**Goal:** Measure repository-owned Player request construction, SET envelope codec, setup coordination and reconnect registry proxies on Android without invoking ExoPlayer, network, Binder service startup or UI.

**Architecture:** Debug-only model/runner/writer in `player:media3`; one dedicated instrumentation entry; repository-owned PowerShell host validation; canonical result-bundle JSON; manual focused self-hosted mode.

## Constraints

- no production request/setup/reconnect behaviour change;
- no public API expansion;
- no release dependency;
- no real ExoPlayer, MediaSource, network, Surface or first-frame work;
- 2 warmup samples, 10 retained samples and 1,000 operations per sample by default;
- five fixed operations;
- all raw samples retained;
- nearest-rank min/p50/p90/p95/max;
- `thresholdApplied=false` and no failing budget;
- complete deterministic request-profile SHA-256;
- no locator/header/setup/controller values in report diagnostics;
- ordinary DeviceCurrent/DeviceMatrix must record zero skips and exclude the measurement class.

## Task 1 — opening RED contracts

1. Add unit contract for nearest-rank summaries and immutable sample snapshots.
2. Add unit contract that every request-profile field and header boundary contributes to SHA-256.
3. Add canonical JSON contract: fixed order, LF, trailing newline, threshold false and payload redaction.
4. Add instrumentation contract requiring exactly:
   - `request-construct`;
   - `setup-envelope-roundtrip`;
   - `coordinator-install-active-clear`;
   - `coordinator-cancel-before-install`;
   - `registry-disconnect-reacquire`.
5. Run Full and confirm failure is caused by absent measurement types.

## Task 2 — model, statistics and identity

1. Add bounded workload/spec/environment/sample/operation/report models.
2. Snapshot mutable inputs on construction.
3. Add nearest-rank statistics.
4. Add length-prefixed request-profile digest covering media ID, variant ID, locator, display name, artwork URI, approval flag and ordered header names/values.
5. Keep `toString()` payload-redacted.
6. Add canonical UTF-8/LF JSON writer.
7. Make unit contracts GREEN.

## Task 3 — Android proxy runner

1. Prepare synthetic `.example` fixture and identity batches outside timers.
2. Measure request construction in a 1,000-iteration batch.
3. Measure SET envelope encode/decode round-trip.
4. Measure install + active clear through `PlaybackSetupCoordinator`.
5. Measure cancel-before-install rejection path.
6. Measure `ControllerConnectionRegistry` disconnect + reacquire sequence with fake identities/immediate futures.
7. Verify exact result counts after each batch.
8. Capture Android environment.
9. Publish no report after any invariant failure.

## Task 4 — dedicated instrumentation boundary

1. Add `PlayerProxyMeasurement` annotation.
2. Exclude it in default Media3 instrumentation runner arguments.
3. Include only the measurement class when `playerProxyMeasurements=true`.
4. Parse strict source commit, runner label, warmups, samples, iterations and output name.
5. Run the proxy runner and assert fixed operation order/counts.
6. Serialize once and place Base64 JSON in the instrumentation result bundle.
7. Preserve ordinary Media3 correctness test count with zero skips.

## Task 5 — host and self-hosted execution

1. Add `Invoke-PlayerProxyMeasurement.ps1` for one booted device.
2. Remove stale test results before execution.
3. Run only the dedicated Media3 class.
4. Decode one fresh result payload.
5. Validate schema/method/build mode/source commit/profile SHA/operation order/raw counts/results/threshold/failure count.
6. Add current-TV device wrapper reusing the repository AVD lifecycle.
7. Retain child output and bounded secret-safe failure metadata.
8. Add manual `PlayerMeasurement` workflow mode; do not add an always-on PR measurement.

## Task 6 — evidence and merge

1. Run Full on reviewed source head.
2. Run focused current Android TV measurement on the same head.
3. Record artifact name/digest, exact environment, profile SHA and raw distributions.
4. Add `docs/performance/2026-07-29-player-proxy-baseline.md`.
5. Explicitly state proxy/non-playback limitations.
6. Review final diff and threads.
7. Remove any temporary PR-only workflow.
8. Run cleaned-tree Full.
9. Mark ready and squash merge.
10. Update issue #27; keep it open for repeated variance/threshold decision.

## Follow-up after this package

1. Repeat parse and Room series on current, old-edge and low-RAM virtual profiles.
2. Add comparable Player proxy series where the instrumentation APIs are supported.
3. Calculate cross-series median, range and coefficient of variation.
4. Decide whether any dedicated threshold gate is justified.
5. Do not infer codec, zapping, first-frame, Fire OS or physical-TV performance from emulator proxy data.
