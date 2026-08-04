# CI Host-Before-Device Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run complete host acceptance once before Android TV emulator startup, then run connected instrumentation only per API profile while preserving existing evidence and manual `Device` behavior.

**Architecture:** `verify-local.ps1` gains an explicit `DeviceOnly` mode that contains only connected instrumentation and result-count validation. `Invoke-TvDeviceValidation.ps1` invokes `Full` once before resolving/creating AVDs, then invokes `DeviceOnly` inside each profile loop. Static PowerShell harness checks make this ordering a repository contract.

**Tech Stack:** PowerShell 7, Gradle 9.5, Android Gradle Plugin, Android TV emulator harness, GitHub Actions self-hosted Windows runner.

## Global Constraints

- Preserve `Fast`, `Full`, and `Device` behavior for existing callers.
- Do not weaken non-zero connected Android test count validation.
- Do not change measurement methodology or comparison identities.
- Do not add dependencies.
- Keep API26 old-edge and current API profiles sequential.

---

### Task 1: Lock the host/device split with a failing harness contract

**Files:**
- Modify: `tools/android/Test-TvHarnessSyntax.ps1`

**Interfaces:**
- Consumes: repository PowerShell scripts as text/AST inputs.
- Produces: syntax/harness validation that requires `DeviceOnly`, a pre-AVD `Full` gate, and per-profile `DeviceOnly` execution.

- [ ] **Step 1: Add failing static assertions**

Add `tools/verify-local.ps1` to the parsed script set and assert:

```powershell
$verifyLocalContent = Get-Content -Path $verifyLocalPath -Raw
if ($verifyLocalContent -notmatch 'DeviceOnly') {
    $messages += "verify-local must expose DeviceOnly connected-test mode."
}

$tvValidationContent = Get-Content -Path $tvValidationPath -Raw
$hostValidationIndex = $tvValidationContent.IndexOf('-Mode", "Full"')
$profileLoopIndex = $tvValidationContent.IndexOf('foreach ($profile in $profiles)')
$deviceOnlyIndex = $tvValidationContent.IndexOf('-Mode", "DeviceOnly"')
if ($hostValidationIndex -lt 0 -or $profileLoopIndex -lt 0 -or $hostValidationIndex -gt $profileLoopIndex) {
    $messages += "TV device validation must complete Full host validation before the profile loop."
}
if ($deviceOnlyIndex -lt $profileLoopIndex) {
    $messages += "TV profile validation must use DeviceOnly inside the profile loop."
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
pwsh -NoProfile -File .\tools\android\Test-TvHarnessSyntax.ps1
```

Expected: FAIL because `verify-local.ps1` does not yet expose `DeviceOnly` and `Invoke-TvDeviceValidation.ps1` does not yet run `Full` before the profile loop.

- [ ] **Step 3: Commit the RED contract**

```bash
git add tools/android/Test-TvHarnessSyntax.ps1
git commit -m "test: require host-before-device TV validation"
```

### Task 2: Add `DeviceOnly` without weakening `Device`

**Files:**
- Modify: `tools/verify-local.ps1`

**Interfaces:**
- Consumes: `-Mode DeviceOnly`, existing Gradle connected test tasks and `Assert-AndroidTestCount`.
- Produces: connected-only validation mode with evidence and non-zero test-count enforcement.

- [ ] **Step 1: Extend mode validation**

Change:

```powershell
[ValidateSet("Fast", "Full", "Device")]
```

to:

```powershell
[ValidateSet("Fast", "Full", "Device", "DeviceOnly")]
```

- [ ] **Step 2: Keep host steps out of `DeviceOnly`**

Wrap the existing host/build step registration so it executes only when `$Mode -ne "DeviceOnly"`.

- [ ] **Step 3: Run connected tests for both device modes**

Change both device-mode conditions from:

```powershell
if ($Mode -eq "Device")
```

to:

```powershell
if ($Mode -in @("Device", "DeviceOnly"))
```

This applies to connected task registration and instrumentation result-count verification.

- [ ] **Step 4: Verify targeted behavior**

Run syntax validation and, with a connected emulator available:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode DeviceOnly -NoDaemon
```

Expected: only connected Gradle tasks execute; result counts are non-zero.

- [ ] **Step 5: Commit**

```bash
git add tools/verify-local.ps1
git commit -m "perf: add connected-only TV validation mode"
```

### Task 3: Move full host acceptance before AVD startup

**Files:**
- Modify: `tools/android/Invoke-TvDeviceValidation.ps1`

**Interfaces:**
- Consumes: `verify-local.ps1 -Mode Full` and `-Mode DeviceOnly`.
- Produces: one host gate plus sequential API device-only validation.

- [ ] **Step 1: Add the pre-device Full gate**

After Android SDK tools are made available but before `Reset-AdbServer`, image resolution or profile creation, run:

```powershell
$hostValidationRoot = Join-Path $evidenceDirectory "host-validation"
$hostValidationArguments = @(
    "-NoProfile",
    "-File", $verifyScript,
    "-Mode", "Full",
    "-EvidenceRoot", $hostValidationRoot,
    "-SourceBranch", $branch,
    "-SourceCommit", $commit
)
if ($NoDaemon) {
    $hostValidationArguments += "-NoDaemon"
}

Write-Host "`n==> Host validation before Android TV emulator startup"
& pwsh @hostValidationArguments
if ($LASTEXITCODE -ne 0) {
    throw "Host validation failed before Android TV emulator startup."
}
```

- [ ] **Step 2: Switch each profile to `DeviceOnly`**

Inside the existing profile loop change the child verification mode from `Device` to `DeviceOnly`.

- [ ] **Step 3: Verify GREEN syntax contract**

Run:

```powershell
pwsh -NoProfile -File .\tools\android\Test-TvHarnessSyntax.ps1
```

Expected: PASS.

- [ ] **Step 4: Verify a current-device run**

Run:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 -Mode DeviceCurrent -NoDaemon
```

Expected: host Full validation finishes before AVD creation; current device runs connected tests only.

- [ ] **Step 5: Verify full old-edge/current matrix**

Run:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 -Mode DeviceMatrix -NoDaemon
```

Expected: one host Full gate, then old-edge and current connected suites with non-zero result counts.

- [ ] **Step 6: Commit**

```bash
git add tools/android/Invoke-TvDeviceValidation.ps1
git commit -m "perf: run host acceptance before TV emulators"
```

### Task 4: Exact-head acceptance and review

**Files:**
- No product code changes.

**Interfaces:**
- Consumes: exact PR head.
- Produces: CI evidence proving semantics and performance-oriented orchestration are correct.

- [ ] **Step 1: Run full self-hosted validation**
- [ ] **Step 2: Run Android TV product DeviceMatrix**
- [ ] **Step 3: Run database migration DeviceMatrix**
- [ ] **Step 4: Confirm host validation precedes any emulator-start log line**
- [ ] **Step 5: Confirm connected test counts match prior accepted suites**
- [ ] **Step 6: Review base-to-head diff and unresolved threads**
- [ ] **Step 7: Squash merge only on exact-head green evidence**
