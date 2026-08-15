# Risk-Based PR Gates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Keep exact-head validation on every PR while moving expensive emulator/matrix/benchmark work out of unrelated PR critical paths.

**Architecture:** Preserve the existing self-hosted validation entry point and required check identity, but execute `Fast` mode for `pull_request`. Keep heavy modes available through `workflow_dispatch`. Add one current-API focused device lane for TV/player changes, limit automatic measurement/database evidence to direct risk paths, keep benchmark runtime evidence manual, and provide one manual integration gate for Full + DeviceMatrix evidence.

**Tech Stack:** GitHub Actions, PowerShell repository harness, Gradle, Android TV AVD self-hosted runners.

## Global Constraints

- Every PR must still produce exact-head host validation evidence.
- Do not make timed/repeated 50k stress a PR, S6, or release gate.
- Preserve existing `Full validation` check name on PRs because branch-protection settings are not readable by the GitHub integration.
- Benchmark runtime evidence, full product DeviceMatrix and integration acceptance are manual lanes.
- Measurement variance and database migration matrices may auto-run only for direct changes to the contracts they measure.
- Current-device tests remain automatic for TV/player/presentation/design-system changes.
- Final Full + DeviceMatrix evidence is collected once for an integration candidate, not independently for every small PR.

---

### Task 1: Make ordinary PR validation fast

**Files:**
- Modify: `.github/workflows/self-hosted-validation.yml`

- [x] Keep the PR check name `Full validation` for compatibility.
- [x] Select `Fast` instead of `Full` for `pull_request` events.
- [x] Preserve manual `Fast`, `Full`, `DeviceCurrent`, `DeviceMatrix`, `CatalogMeasurement`, and `PlayerMeasurement` dispatch modes.

### Task 2: Remove unrelated heavy automatic work

**Files:**
- Modify: `.github/workflows/benchmark-foundation.yml`
- Modify: `.github/workflows/measurement-variance-smoke.yml`
- Modify: `.github/workflows/database-migration-device-matrix.yml`
- Modify: `.github/workflows/android-tv-product-device-matrix.yml`

- [x] Benchmark runtime dry-run is manual-only; Fast still compiles benchmark/JMH and macrobenchmark artifacts.
- [x] Measurement variance auto-triggers only for measurement implementation/tests/tools, not generic `core/testing` dependency changes.
- [x] Database matrix auto-triggers only for schema/migration/importer-migration code, not shared Android harness edits.
- [x] Full Android TV product matrix is manual/integration evidence instead of an every-UI-PR gate.

**Rollout finding:** the first policy revision still produced false-positive heavy runs: #167 matched variance through `core/testing/build.gradle.kts`; #168 matched benchmark through a macrobenchmark CUJ and DB matrix through shared `AndroidSdk.ps1`. Those generic couplings are deliberately removed by the final policy.

### Task 3: Add risk-based focused current-device evidence

**Files:**
- Create: `.github/workflows/android-tv-focused-device.yml`

- [x] Trigger only on app TV, UI/focus/player/design-system paths and the focused TV harness itself.
- [x] Run `Invoke-TvDeviceValidation.ps1 -Mode DeviceCurrent` on API36.
- [x] Upload exact-head evidence and reset runner state.

### Task 4: Add a single heavy integration gate

**Files:**
- Create: `.github/workflows/integration-gate.yml`

- [x] Manual-only workflow.
- [x] Run host `Full` validation followed by `DeviceMatrix` on the selected integration SHA.
- [x] Upload evidence once for the integrated candidate.

### Task 5: Apply policy to active PRs

**Targets at rollout:** `#167`, `#168`, `#169`; `#170` merged to `main` while rollout was in progress.

- [x] Push the same final workflow policy to the current #167/#168/#169 heads.
- [x] Confirm #169 creates only host `Full validation` (Fast internally).
- [x] Confirm #167/#168 create host `Full validation` (Fast internally) plus `Android TV focused device`.
- [x] Confirm no new benchmark/variance/database/full-product-matrix run is created on the final rollout heads.
- [x] Do not treat missing manual Full/DeviceMatrix evidence as a per-PR failure.

### Task 6: Verify and monitor

- [x] Verify exact-head workflow selection on #167/#168/#169.
- [x] Start an hourly condition watch for failed gates, unusual self-hosted queue stalls, merge readiness, new/closed PRs and accidental heavy-lane regressions.
- [ ] Wait for the new Fast/focused jobs themselves to execute; workflow selection is verified, test success is not claimed while jobs are queued.
- [ ] Run the manual integration gate once an integrated candidate is ready.
