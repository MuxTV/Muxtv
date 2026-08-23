# MuxTV Two-AVD Device Contract Design

## Status

Approved and in implementation on 2026-08-22.

## Context

MuxTV already treats Android TV API 26 as the oldest supported virtual compatibility edge and API 36 as the current virtual platform. Before this package, repository-owned tooling had several independent AVD naming/lifecycle policies:

- `tools/android/Invoke-TvDeviceValidation.ps1` used dynamic `MuxTV_TV_OLD_API*` / `MuxTV_TV_CURRENT_API*` names and allowed an old-edge fallback;
- `tools/measurements/Invoke-MeasurementSeriesCore.ps1` created per-repetition `MuxTV_VARIANCE_*` AVDs and deleted them afterward;
- `tools/android/Invoke-BenchmarkDryRun.ps1` created `MuxTV_BENCHMARK_API36`;
- `tools/android/Invoke-CatalogDatabaseDeviceValidation.ps1` created `MuxTV_CATALOG_MEASUREMENT_API36`;
- `tools/android/Invoke-PlayerProxyDeviceValidation.ps1` created `MuxTV_PLAYER_MEASUREMENT_API36`;
- `tools/measurements/MeasurementProfiles.ps1` allowed API 26 fallback and mixed workload profile identity with virtual-device policy.

That policy created unnecessary AVD definitions on the Windows runner and made repository virtual-device truth wider than the product requirement.

## Goal

All repository-owned Android TV validation, measurement and benchmark lanes use exactly two persistent AVD identities:

- `MuxTV_TV_OLD_API26` — exact Android TV API 26;
- `MuxTV_TV_CURRENT_API36` — exact Android TV API 36.

No normal repository lane may create another AVD identity.

## Non-goals

- No Android product/runtime behavior change.
- No UI change.
- No Room schema change.
- No Media3 change.
- No CI runner process-management change; the existing `Runner.Listener` singleton preflight remains fail-closed.
- No physical-device performance/codec claim.
- No persistent API 37 emulator. API 37 private-LAN permission evidence remains a separate release/platform requirement, not a third development AVD.

## Device identities

### Old edge — `MuxTV_TV_OLD_API26`

- requested API: 26;
- required resolved API: 26;
- default RAM: 1536 MiB;
- default CPU: 2 cores;
- exact discovered Android TV/Google TV API 26 system image;
- fallback: forbidden.

If API 26 cannot be resolved exactly, the lane fails closed and lists available TV images. It must not silently use API 28/29/30.

### Current — `MuxTV_TV_CURRENT_API36`

- requested API: 36;
- required resolved API: 36;
- default RAM: 2048 MiB;
- default CPU: 2 cores;
- exact discovered Android TV/Google TV API 36 system image;
- fallback: forbidden.

## Lifecycle policy

The two AVD identities are reusable repository infrastructure. A lane may recreate/reconfigure one of those exact identities with `avdmanager create avd --force`; the emulator may boot with `-wipe-data` for deterministic clean-state execution.

Measurement repetition isolation remains cold/wiped, but repetitions reuse the canonical name rather than creating `MuxTV_VARIANCE_*` identities and deleting them afterward.

Only one emulator process runs at a time. Existing self-hosted cross-workflow serialization remains authoritative.

## Scenario profiles without extra AVDs

Resolution, memory and benchmark variants are configuration of a canonical identity, not new device identities.

- 1080p UI: `MuxTV_TV_CURRENT_API36` with canonical display configuration.
- 720p UI: temporarily set display size/density on the same API 36 AVD, recreate the Activity, run capture/journey, restore canonical display state in `finally`.
- `current-low-ram`: reuse `MuxTV_TV_CURRENT_API36`, configure 1024 MiB for that measurement run, then normal runs recreate/reconfigure the same identity with 2048 MiB.
- old-edge measurements: reuse `MuxTV_TV_OLD_API26` with exact API 26 only.

## Shared canonical identity API

`tools/android/AndroidSdk.ps1` owns canonical identity selection:

```powershell
function Get-MuxTvCanonicalAvdName {
    param([Parameter(Mandatory)][int]$Api)

    switch ($Api) {
        26 { return "MuxTV_TV_OLD_API26" }
        36 { return "MuxTV_TV_CURRENT_API36" }
        default { throw "MuxTV repository AVD identity is defined only for API 26 and API 36." }
    }
}
```

All device-producing callers obtain the AVD name through this helper. `Resolve-TvSystemImage` no longer exposes `-AllowOldEdgeFallback`; exact resolution is the only supported policy.

## Measurement implications

`MeasurementProfiles.ps1` retains three workload configurations:

- `current-normal`: API 36, 2048 MiB, 2 cores;
- `old-edge-normal`: API 26, 1536 MiB, 2 cores;
- `current-low-ram`: API 36, 1024 MiB, 2 cores.

The profile describes workload/environment evidence. It does not own a separate AVD name or fallback policy.

`Invoke-MeasurementSeriesCore.ps1` keeps per-repetition cold/wiped execution, reuses `Get-MuxTvCanonicalAvdName($image.Api)`, and no longer deletes/recreates distinct variance AVD identities.

## Benchmark and focused measurement implications

These scripts reuse `MuxTV_TV_CURRENT_API36` through the shared helper:

- `Invoke-BenchmarkDryRun.ps1`;
- `Invoke-CatalogDatabaseDeviceValidation.ps1`;
- `Invoke-PlayerProxyDeviceValidation.ps1`.

Evidence directories remain lane-specific; only virtual-device identity is consolidated.

## Historical AVD cleanup safety

Stopping future AVD proliferation does not remove old definitions already present on the developer/runner PC. Historical cleanup therefore has a separate explicit boundary:

- `tools/android/MuxTvAvdOwnership.ps1` classifies only known historical MuxTV naming families;
- `tools/android/Remove-LegacyMuxTvAvds.ps1` is **dry-run by default**;
- deletion requires explicit `-Apply`;
- canonical `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36` are always excluded;
- unrelated user AVDs and unknown `MuxTV_*` names are excluded rather than guessed to be repository-owned;
- the cleanup is manual/one-shot and is deliberately not folded into `Reset-SelfHostedAndroidState.ps1`, whose repository-root safety boundary remains unchanged.

Known historical families eligible for cleanup are:

- `MuxTV_VARIANCE_*`;
- `MuxTV_BENCHMARK_API<api>`;
- `MuxTV_CATALOG_MEASUREMENT_API<api>`;
- `MuxTV_PLAYER_MEASUREMENT_API<api>`;
- non-canonical `MuxTV_TV_OLD_API<api>`;
- non-canonical `MuxTV_TV_CURRENT_API<api>`.

Recommended operator sequence after runner administration is healthy:

```powershell
pwsh -NoProfile -File tools/android/Remove-LegacyMuxTvAvds.ps1
pwsh -NoProfile -File tools/android/Remove-LegacyMuxTvAvds.ps1 -Apply
```

The first command must be reviewed before the second is used.

## Executable repository contract

`tools/android/Test-TwoAvdContract.ps1` is part of the existing `Test-TvHarnessSyntax.ps1` path and must fail if:

1. `Resolve-TvSystemImage` exposes/references `AllowOldEdgeFallback`;
2. canonical helper does not map exactly API26/API36 to the two names above;
3. DeviceMatrix constructs its own AVD identity;
4. measurement series contains `MuxTV_VARIANCE_` or per-repetition AVD deletion;
5. benchmark contains `MuxTV_BENCHMARK_API36`;
6. catalog measurement contains `MuxTV_CATALOG_MEASUREMENT_API`;
7. player measurement contains `MuxTV_PLAYER_MEASUREMENT_API`;
8. measurement profiles expose fallback policy;
9. known device-producing callers do not consume the canonical helper;
10. the legacy cleanup safety contract is absent or fails.

`Test-LegacyMuxTvAvdCleanupContract.ps1` additionally proves:

- canonical identities are never cleanup candidates;
- unrelated AVDs are never cleanup candidates;
- dry-run cannot delete;
- `-Apply` deletes exactly the allowlisted legacy candidate set in a fake/injected test environment.

These are static/pure contracts and do not start an emulator.

## Acceptance

A merge candidate is acceptable only when:

- permanent two-AVD + legacy-cleanup contracts are GREEN;
- existing Android/measurement/benchmark harness contracts are GREEN on the intended self-hosted environment;
- exact-head host validation is GREEN;
- exact-head API 36 DeviceCurrent is GREEN;
- exact-head API 26 + API 36 integration/device matrix is GREEN;
- evidence manifests record `requestedApi == resolvedApi` for both canonical devices;
- operator dry-run/review and one-shot cleanup leave no known historical MuxTV AVD identities;
- no new persistent AVD identity is introduced by measurement/benchmark lanes.

The duplicate `Runner.Listener` condition is an external execution blocker, not justification to weaken preflight. CI device evidence is admissible only after the host reports exactly one listener process.