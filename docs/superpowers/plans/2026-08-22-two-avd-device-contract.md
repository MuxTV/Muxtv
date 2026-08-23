# MuxTV Two-AVD Device Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every repository-owned Android TV validation, measurement and benchmark lane use exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`, with exact API resolution and no third AVD identity.

**Architecture:** `tools/android/AndroidSdk.ps1` becomes the sole owner of repository AVD identity through `Get-MuxTvCanonicalAvdName`. Device, measurement and benchmark callers reuse those identities and vary RAM/workload as configuration rather than inventing AVD names. A static PowerShell contract fails Fast/Full before any emulator boot if fallback or extra AVD naming returns.

**Tech Stack:** PowerShell 7, Android SDK `sdkmanager`/`avdmanager`, Android Emulator/ADB, GitHub Actions, repository PowerShell harness contracts.

**Spec:** `docs/superpowers/specs/2026-08-22-two-avd-device-contract-design.md`

## Global Constraints

- Canonical AVD names are exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`.
- API 26 resolution must resolve API 26 or fail; fallback is forbidden.
- API 36 resolution must resolve API 36 or fail.
- `current-low-ram` remains a measurement configuration of the API 36 AVD, not a third AVD.
- Measurement repetitions retain cold/wiped execution but reuse the canonical AVD name.
- No production Android/Kotlin behavior changes in this plan.
- Do not weaken self-hosted runner singleton preflight.

---

### Task 1: Add a failing repository contract for exactly two AVD identities

**Files:**
- Create: `tools/android/Test-TwoAvdContract.ps1`
- Modify: `tools/android/Test-TvHarnessSyntax.ps1`

**Interfaces:**
- Consumes: repository script text and the existing `AndroidSdk.ps1` helper surface.
- Produces: `Test-TwoAvdContract.ps1`, a zero-emulator static contract executed by `Test-TvHarnessSyntax.ps1`.

- [ ] **Step 1: Create the failing contract**

Create `tools/android/Test-TwoAvdContract.ps1` with the following behavior:

```powershell
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidSdkPath = Join-Path $PSScriptRoot "AndroidSdk.ps1"
$tvValidationPath = Join-Path $PSScriptRoot "Invoke-TvDeviceValidation.ps1"
$benchmarkPath = Join-Path $PSScriptRoot "Invoke-BenchmarkDryRun.ps1"
$catalogDevicePath = Join-Path $PSScriptRoot "Invoke-CatalogDatabaseDeviceValidation.ps1"
$playerDevicePath = Join-Path $PSScriptRoot "Invoke-PlayerProxyDeviceValidation.ps1"
$measurementProfilesPath = Join-Path $repositoryRoot "tools\measurements\MeasurementProfiles.ps1"
$measurementSeriesPath = Join-Path $repositoryRoot "tools\measurements\Invoke-MeasurementSeriesCore.ps1"

$errors = [System.Collections.Generic.List[string]]::new()
function Add-ContractError {
    param([Parameter(Mandatory)][string]$Message)
    $script:errors.Add($Message)
}

$androidSdk = Get-Content -LiteralPath $androidSdkPath -Raw
$tvValidation = Get-Content -LiteralPath $tvValidationPath -Raw
$benchmark = Get-Content -LiteralPath $benchmarkPath -Raw
$catalogDevice = Get-Content -LiteralPath $catalogDevicePath -Raw
$playerDevice = Get-Content -LiteralPath $playerDevicePath -Raw
$measurementProfiles = Get-Content -LiteralPath $measurementProfilesPath -Raw
$measurementSeries = Get-Content -LiteralPath $measurementSeriesPath -Raw

if ($androidSdk -notmatch '(?m)^function\s+Get-MuxTvCanonicalAvdName\s*\{') {
    Add-ContractError "AndroidSdk must own canonical MuxTV AVD identity."
}
if ($androidSdk -match 'AllowOldEdgeFallback') {
    Add-ContractError "Android TV system-image fallback is forbidden; API 26 and API 36 must resolve exactly."
}

. $androidSdkPath

try {
    $oldName = Get-MuxTvCanonicalAvdName -Api 26
    $currentName = Get-MuxTvCanonicalAvdName -Api 36
    if ($oldName -cne 'MuxTV_TV_OLD_API26') {
        Add-ContractError "API 26 canonical AVD name is incorrect."
    }
    if ($currentName -cne 'MuxTV_TV_CURRENT_API36') {
        Add-ContractError "API 36 canonical AVD name is incorrect."
    }
    try {
        $null = Get-MuxTvCanonicalAvdName -Api 30
        Add-ContractError "Canonical AVD identity must reject APIs other than 26 and 36."
    } catch {
        # Expected.
    }
} catch {
    Add-ContractError "Canonical AVD helper is not executable."
}

$forbiddenByFile = [ordered]@{
    'Invoke-BenchmarkDryRun.ps1' = @('MuxTV_BENCHMARK_API36')
    'Invoke-CatalogDatabaseDeviceValidation.ps1' = @('MuxTV_CATALOG_MEASUREMENT_API')
    'Invoke-PlayerProxyDeviceValidation.ps1' = @('MuxTV_PLAYER_MEASUREMENT_API')
    'Invoke-MeasurementSeriesCore.ps1' = @('MuxTV_VARIANCE_', 'Remove-MeasurementAvd')
}
$fileContent = @{
    'Invoke-BenchmarkDryRun.ps1' = $benchmark
    'Invoke-CatalogDatabaseDeviceValidation.ps1' = $catalogDevice
    'Invoke-PlayerProxyDeviceValidation.ps1' = $playerDevice
    'Invoke-MeasurementSeriesCore.ps1' = $measurementSeries
}
foreach ($entry in $forbiddenByFile.GetEnumerator()) {
    foreach ($forbidden in $entry.Value) {
        if ($fileContent[$entry.Key].Contains($forbidden, [System.StringComparison]::Ordinal)) {
            Add-ContractError "$($entry.Key) still owns a non-canonical AVD identity/lifecycle: $forbidden"
        }
    }
}

foreach ($caller in @(
    @{ Name = 'Invoke-TvDeviceValidation.ps1'; Content = $tvValidation },
    @{ Name = 'Invoke-BenchmarkDryRun.ps1'; Content = $benchmark },
    @{ Name = 'Invoke-CatalogDatabaseDeviceValidation.ps1'; Content = $catalogDevice },
    @{ Name = 'Invoke-PlayerProxyDeviceValidation.ps1'; Content = $playerDevice },
    @{ Name = 'Invoke-MeasurementSeriesCore.ps1'; Content = $measurementSeries }
)) {
    if ($caller.Content -notmatch 'Get-MuxTvCanonicalAvdName') {
        Add-ContractError "$($caller.Name) must obtain AVD identity from Get-MuxTvCanonicalAvdName."
    }
}

if ($measurementProfiles -match 'AllowOldEdgeFallback') {
    Add-ContractError "Measurement profiles must not expose Android TV fallback policy."
}

if ($errors.Count -gt 0) {
    throw ("MuxTV two-AVD contract failed.`n" + [string]::Join([Environment]::NewLine, $errors))
}

Write-Host "MuxTV two-AVD contract is valid."
```

- [ ] **Step 2: Wire the contract into the existing harness**

At the end of `tools/android/Test-TvHarnessSyntax.ps1`, before the final success message, add:

```powershell
$twoAvdContract = Join-Path $PSScriptRoot "Test-TwoAvdContract.ps1"
if (-not (Test-Path -LiteralPath $twoAvdContract -PathType Leaf)) {
    throw "MuxTV two-AVD contract test was not found."
}
& $twoAvdContract
```

- [ ] **Step 3: Run the RED contract**

Run on Windows PowerShell 7 from repository root:

```powershell
pwsh -NoProfile -File tools/android/Test-TwoAvdContract.ps1
```

Expected: FAIL. The diagnostic must include the missing canonical helper and the current lane-specific/fallback ownership such as `AllowOldEdgeFallback`, `MuxTV_VARIANCE_`, `MuxTV_BENCHMARK_API36`, `MuxTV_CATALOG_MEASUREMENT_API`, or `MuxTV_PLAYER_MEASUREMENT_API`.

- [ ] **Step 4: Run the aggregate harness and confirm the same RED is reachable through the permanent gate**

```powershell
pwsh -NoProfile -File tools/android/Test-TvHarnessSyntax.ps1
```

Expected: FAIL because `Test-TwoAvdContract.ps1` fails, not because of a PowerShell parse error.

- [ ] **Step 5: Commit the RED contract only**

```bash
git add tools/android/Test-TwoAvdContract.ps1 tools/android/Test-TvHarnessSyntax.ps1
git commit -m "test(android): require exactly two MuxTV AVD identities"
```

---

### Task 2: Centralize exact device identity and remove API 26 fallback

**Files:**
- Modify: `tools/android/AndroidSdk.ps1`
- Modify: `tools/android/Invoke-TvDeviceValidation.ps1`

**Interfaces:**
- Produces: `Get-MuxTvCanonicalAvdName -Api <int> -> string` for API 26/36 only.
- Produces: `Resolve-TvSystemImage -Tools <tools> -PreferredApi <int>` as exact-only resolution.
- Consumes later: all device/measurement/benchmark entry points.

- [ ] **Step 1: Add the canonical helper**

Add to `AndroidSdk.ps1` before `Resolve-TvSystemImage`:

```powershell
function Get-MuxTvCanonicalAvdName {
    [CmdletBinding()]
    param([Parameter(Mandatory)][int]$Api)

    switch ($Api) {
        26 { return "MuxTV_TV_OLD_API26" }
        36 { return "MuxTV_TV_CURRENT_API36" }
        default {
            throw "MuxTV repository AVD identity is defined only for API 26 and API 36."
        }
    }
}
```

- [ ] **Step 2: Remove fallback from image resolution**

Change `Resolve-TvSystemImage` signature from:

```powershell
param(
    [Parameter(Mandatory)]$Tools,
    [Parameter(Mandatory)][int]$PreferredApi,
    [switch]$AllowOldEdgeFallback
)
```

to:

```powershell
param(
    [Parameter(Mandatory)]$Tools,
    [Parameter(Mandatory)][int]$PreferredApi
)
```

Delete the entire `if ($AllowOldEdgeFallback) { ... }` block. Keep the existing exact match and fail-closed `Required Android TV API ... image is unavailable` diagnostic.

- [ ] **Step 3: Change DeviceMatrix to exact API26/API36 names**

In `Invoke-TvDeviceValidation.ps1`, replace the old-edge resolution/name construction with:

```powershell
$oldImage = Resolve-TvSystemImage -Tools $tools -PreferredApi 26
$profiles.Add([pscustomobject]@{
    RequestedApi = 26
    Image = $oldImage
    AvdName = Get-MuxTvCanonicalAvdName -Api 26
    RamMb = 1536
    CpuCores = 2
    FallbackUsed = $false
})
```

Replace current profile `AvdName` construction with:

```powershell
AvdName = Get-MuxTvCanonicalAvdName -Api 36
```

- [ ] **Step 4: Run the focused contract**

```powershell
pwsh -NoProfile -File tools/android/Test-TwoAvdContract.ps1
```

Expected: still FAIL, but fallback and main DeviceMatrix ownership findings are gone; remaining failures belong to measurement/benchmark callers.

- [ ] **Step 5: Run Android harness syntax**

```powershell
pwsh -NoProfile -File tools/android/Test-TvHarnessSyntax.ps1
```

Expected: FAIL only on the remaining D0 contract findings, not on parser/function-surface regressions.

- [ ] **Step 6: Commit shared identity ownership**

```bash
git add tools/android/AndroidSdk.ps1 tools/android/Invoke-TvDeviceValidation.ps1
git commit -m "fix(android): make API26 and API36 AVD identity exact"
```

---

### Task 3: Reuse canonical AVDs in measurement and benchmark lanes

**Files:**
- Modify: `tools/measurements/MeasurementProfiles.ps1`
- Modify: `tools/measurements/Invoke-MeasurementSeriesCore.ps1`
- Modify: `tools/android/Invoke-BenchmarkDryRun.ps1`
- Modify: `tools/android/Invoke-CatalogDatabaseDeviceValidation.ps1`
- Modify: `tools/android/Invoke-PlayerProxyDeviceValidation.ps1`

**Interfaces:**
- Consumes: `Get-MuxTvCanonicalAvdName` and exact `Resolve-TvSystemImage` from Task 2.
- Preserves: measurement profile IDs and evidence schema fields.

- [ ] **Step 1: Remove fallback from measurement profiles**

Change each profile in `MeasurementProfiles.ps1` to omit `AllowOldEdgeFallback`. Preserve exactly:

```powershell
"current-normal" -> RequestedApi 36, RamMb 2048, CpuCores 2
"old-edge-normal" -> RequestedApi 26, RamMb 1536, CpuCores 2
"current-low-ram" -> RequestedApi 36, RamMb 1024, CpuCores 2
```

- [ ] **Step 2: Make measurement series exact and canonical**

In `Invoke-MeasurementSeriesCore.ps1`, replace conditional image resolution with:

```powershell
$image = Resolve-TvSystemImage -Tools $tools -PreferredApi $profile.RequestedApi
```

Inside the repetition loop replace:

```powershell
$avdName = "MuxTV_VARIANCE_$($profile.Id.Replace('-', '_'))_${suffix}_API$($image.Api)"
```

with:

```powershell
$avdName = Get-MuxTvCanonicalAvdName -Api $image.Api
```

Delete the `Remove-MeasurementAvd` function and both calls that delete the AVD in repetition/final cleanup. Preserve `Stop-TvEmulator` and `-wipe-data` startup behavior.

- [ ] **Step 3: Consolidate benchmark AVD**

In `Invoke-BenchmarkDryRun.ps1` resolve:

```powershell
$avdName = Get-MuxTvCanonicalAvdName -Api 36
```

Use `$avdName` for both `New-TvAvd -Name` and `Start-TvEmulator -AvdName`. Remove the literal `MuxTV_BENCHMARK_API36`.

- [ ] **Step 4: Consolidate catalog measurement AVD**

In `Invoke-CatalogDatabaseDeviceValidation.ps1` replace:

```powershell
$avdName = "MuxTV_CATALOG_MEASUREMENT_API$($image.Api)"
```

with:

```powershell
$avdName = Get-MuxTvCanonicalAvdName -Api $image.Api
```

- [ ] **Step 5: Consolidate player measurement AVD**

In `Invoke-PlayerProxyDeviceValidation.ps1` replace:

```powershell
$avdName = "MuxTV_PLAYER_MEASUREMENT_API$($image.Api)"
```

with:

```powershell
$avdName = Get-MuxTvCanonicalAvdName -Api $image.Api
```

- [ ] **Step 6: Run the focused contract to GREEN**

```powershell
pwsh -NoProfile -File tools/android/Test-TwoAvdContract.ps1
```

Expected: PASS with `MuxTV two-AVD contract is valid.`

- [ ] **Step 7: Run aggregate Android/measurement harness contracts**

```powershell
pwsh -NoProfile -File tools/android/Test-TvHarnessSyntax.ps1
```

Expected: PASS, including measurement and benchmark contract chaining.

- [ ] **Step 8: Commit lane consolidation**

```bash
git add tools/measurements/MeasurementProfiles.ps1 tools/measurements/Invoke-MeasurementSeriesCore.ps1 tools/android/Invoke-BenchmarkDryRun.ps1 tools/android/Invoke-CatalogDatabaseDeviceValidation.ps1 tools/android/Invoke-PlayerProxyDeviceValidation.ps1
git commit -m "refactor(android): reuse canonical AVDs across evidence lanes"
```

---

### Task 4: Final review and exact-head acceptance

**Files:**
- Modify only if validation exposes a real contract defect; do not add unrelated cleanup.
- PR body/documentation: GitHub PR metadata.

**Interfaces:**
- Consumes: final Task 1-3 tree.
- Produces: accepted exact-head evidence for D0.

- [ ] **Step 1: Run Fast/static validation**

```powershell
pwsh -NoProfile -File tools/verify-local.ps1 -Mode Fast
```

Expected: PASS.

- [ ] **Step 2: Run exact-head self-hosted validation**

Dispatch the repository self-hosted validation for the final branch SHA. Expected: SUCCESS. If preflight reports more than one `Runner.Listener`, stop; fix runner administration outside repository code and rerun the same SHA.

- [ ] **Step 3: Run API36 DeviceCurrent**

Dispatch `Android TV focused device`/DeviceCurrent for the exact final SHA. Expected resolved device: API 36 and AVD identity `MuxTV_TV_CURRENT_API36`.

- [ ] **Step 4: Run API26/API36 integration matrix**

Dispatch the integration/device matrix on the exact final SHA. Expected profiles:

```text
requestedApi=26 resolvedApi=26 avdName=MuxTV_TV_OLD_API26 fallbackUsed=false
requestedApi=36 resolvedApi=36 avdName=MuxTV_TV_CURRENT_API36 fallbackUsed=false
```

- [ ] **Step 5: Inspect residual AVD identities on the Windows runner**

Run:

```powershell
& "$env:ANDROID_SDK_ROOT\cmdline-tools\latest\bin\avdmanager.bat" list avd
```

Expected MuxTV-owned definitions after repository execution:

```text
MuxTV_TV_OLD_API26
MuxTV_TV_CURRENT_API36
```

No `MuxTV_VARIANCE_*`, `MuxTV_BENCHMARK_API36`, `MuxTV_CATALOG_MEASUREMENT_*`, or `MuxTV_PLAYER_MEASUREMENT_*` definition may remain.

- [ ] **Step 6: Final diff review**

```bash
git diff --check origin/main...HEAD
git diff --stat origin/main...HEAD
```

Expected: infrastructure/test/docs only; no Kotlin product, Room schema, Media3, dependency or UI files.

- [ ] **Step 7: Update draft PR with exact evidence and request final review**

Record final SHA, host run ID/artifact digest, DeviceCurrent run ID/artifact digest, matrix run ID/artifact digest, and the exact two AVD names. Do not claim GREEN until these runs execute on the final SHA.
