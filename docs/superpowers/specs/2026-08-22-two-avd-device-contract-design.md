# MuxTV Two-AVD Device Contract Design

## Status

Proposed and approved for implementation on 2026-08-22.

## Context

MuxTV already treats Android TV API 26 as the oldest supported virtual compatibility edge and API 36 as the current virtual platform. However repository-owned tooling still contains several independent AVD naming/lifecycle policies:

- `tools/android/Invoke-TvDeviceValidation.ps1` uses `MuxTV_TV_OLD_API*` and `MuxTV_TV_CURRENT_API*`;
- `tools/measurements/Invoke-MeasurementSeriesCore.ps1` creates per-repetition `MuxTV_VARIANCE_*` AVDs and deletes them afterward;
- `tools/android/Invoke-BenchmarkDryRun.ps1` creates `MuxTV_BENCHMARK_API36`;
- `tools/android/Invoke-CatalogDatabaseDeviceValidation.ps1` creates `MuxTV_CATALOG_MEASUREMENT_API36`;
- `tools/android/Invoke-PlayerProxyDeviceValidation.ps1` creates `MuxTV_PLAYER_MEASUREMENT_API36`;
- `tools/measurements/MeasurementProfiles.ps1` still allows an API 26 fallback and models `current-low-ram` as though it needed a separate device identity.

This creates unnecessary Android Emulator definitions on the Windows runner and makes the repository's virtual-device truth wider than the actual product requirement.

## Goal

All repository-owned Android TV validation, measurement and benchmark lanes must use exactly two persistent AVD identities on the Windows runner:

- `MuxTV_TV_OLD_API26` — exact Android TV API 26;
- `MuxTV_TV_CURRENT_API36` — exact Android TV API 36.

No repository-owned script may create another AVD identity.

## Non-goals

- No Android product/runtime behavior change.
- No UI change.
- No Room schema change.
- No Media3 change.
- No CI runner process-management change; the existing `Runner.Listener` singleton preflight remains fail-closed.
- No physical-device claim.
- No API 37 emulator is added. API 37 private-LAN permission evidence remains a separate release/platform requirement and must not create a third persistent development AVD as part of this contract.

## Device identities

### Old edge

`MuxTV_TV_OLD_API26`

- requested API: 26;
- required resolved API: 26;
- default RAM: 1536 MiB;
- default CPU: 2 cores;
- system image: exact discovered TV image for API 26;
- fallback: forbidden.

If an exact API 26 Android TV/Google TV system image cannot be resolved, the lane fails closed with a diagnostic listing available TV images. It must not silently use API 28/29/30.

### Current

`MuxTV_TV_CURRENT_API36`

- requested API: 36;
- required resolved API: 36;
- default RAM: 2048 MiB;
- default CPU: 2 cores;
- system image: exact discovered TV image for API 36;
- fallback: forbidden.

## Lifecycle policy

The two AVD identities are reusable repository infrastructure. A lane may recreate/configure one of those exact identities using `avdmanager create avd --force` before execution, and the emulator process may boot with `-wipe-data` for deterministic state. Recreating the same identity is allowed because it does not create another AVD definition.

Repository tooling must not delete the canonical AVD after each measurement repetition. Repetition isolation is provided by cold boot + `-wipe-data`, not by inventing a new AVD name.

Only one emulator process runs at a time. Existing cross-workflow/self-hosted serialization remains authoritative.

## Scenario profiles without extra AVDs

Resolution, memory and benchmark variants are modes of the canonical device, not new AVD identities.

- 1080p UI: run on `MuxTV_TV_CURRENT_API36` using canonical display configuration.
- 720p UI: temporarily set display size/density on the same running API 36 device, execute the capture/journey, then restore canonical display configuration in `finally`.
- `current-low-ram`: reuse `MuxTV_TV_CURRENT_API36`; recreate/configure that same identity with the requested RAM for the measurement run, then subsequent normal runs recreate it with canonical 2048 MiB. There is no `MuxTV_*LOW_RAM*` AVD.
- old-edge measurements: reuse `MuxTV_TV_OLD_API26` with exact API 26 only.

## Shared ownership API

`tools/android/AndroidSdk.ps1` owns canonical device identity selection. Add one pure helper:

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

All callers obtain the AVD name from this helper instead of constructing lane-specific names.

`Resolve-TvSystemImage` no longer exposes `-AllowOldEdgeFallback`. Exact resolution is the only supported repository policy.

## Measurement implications

`MeasurementProfiles.ps1` retains workload profiles but removes device-identity ambiguity:

- `current-normal`: API 36, 2048 MiB, 2 cores;
- `old-edge-normal`: API 26, 1536 MiB, 2 cores;
- `current-low-ram`: API 36, 1024 MiB, 2 cores.

All three resolve exact APIs. The profile describes configuration/evidence identity, not a third AVD.

`Invoke-MeasurementSeriesCore.ps1` keeps per-repetition cold/wiped execution but reuses `Get-MuxTvCanonicalAvdName($image.Api)` and stops deleting the AVD after every repetition.

## Benchmark and focused measurement implications

The following scripts must all use `MuxTV_TV_CURRENT_API36` through the shared helper:

- `Invoke-BenchmarkDryRun.ps1`;
- `Invoke-CatalogDatabaseDeviceValidation.ps1`;
- `Invoke-PlayerProxyDeviceValidation.ps1`.

Their evidence directory names remain lane-specific. Only the AVD identity is consolidated.

## Executable repository contract

Add `tools/android/Test-TwoAvdContract.ps1` and invoke it from `tools/android/Test-TvHarnessSyntax.ps1`.

The contract must fail if:

1. `Resolve-TvSystemImage` still exposes or references `AllowOldEdgeFallback`;
2. canonical helper does not map exactly 26/36 to the two names above;
3. `Invoke-TvDeviceValidation.ps1` constructs an AVD name instead of using the canonical helper;
4. measurement series contains `MuxTV_VARIANCE_` or deletes the canonical AVD per repetition;
5. benchmark script contains `MuxTV_BENCHMARK_API36`;
6. catalog measurement device script contains `MuxTV_CATALOG_MEASUREMENT_API`;
7. player measurement device script contains `MuxTV_PLAYER_MEASUREMENT_API`;
8. `MeasurementProfiles.ps1` contains `AllowOldEdgeFallback = $true`;
9. any repository-owned Android/measurement script under the enumerated execution surface contains another literal `MuxTV_...` AVD name.

The test is static by design so it runs in Fast/Full harness validation without starting an emulator.

## Acceptance

A merge candidate is acceptable only when:

- static two-AVD contract is GREEN;
- existing Android/measurement/benchmark harness contracts are GREEN;
- exact-head host validation is GREEN;
- exact-head API 36 DeviceCurrent is GREEN;
- exact-head API 26 + API 36 integration/device matrix is GREEN before this infrastructure contract is considered fully accepted;
- evidence manifests record resolved API equal to requested API for both canonical devices;
- no new persistent AVD identity is introduced by measurement or benchmark lanes.

The current duplicate `Runner.Listener` condition is an external execution blocker, not a reason to weaken the preflight. CI evidence must be collected only after the runner has exactly one listener process.