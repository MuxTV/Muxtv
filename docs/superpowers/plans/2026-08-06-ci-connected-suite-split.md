# CI Connected Suite Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close issue #101 by keeping one Android TV emulator harness while making Product and Database workflows execute only the connected instrumentation suites they own.

**Architecture:** `tools/verify-local.ps1` owns the canonical connected-suite catalog and selects either `Product` or `Database`. `tools/android/Invoke-TvDeviceValidation.ps1` forwards the selected suite through the existing sequential API26/API36 AVD lifecycle. Product and Database workflows make their ownership explicit, while one static harness contract prevents future routing drift.

**Tech Stack:** PowerShell 7, Gradle, Android instrumentation, Android TV AVDs, GitHub Actions self-hosted Windows runner.

## Global Constraints

- Preserve one AVD creation/readiness/evidence/cleanup implementation.
- Preserve Full host validation before the first emulator starts.
- Preserve sequential old-edge API26 and current API36 coverage.
- `Product` remains the default for direct/manual `Device` / `DeviceOnly` callers.
- Product runs importer, remote EPG, credentials, database, Media3 and app instrumentation.
- Database runs only importer and database instrumentation.
- Non-zero XML test-count and failure/error validation remains mandatory for every selected module.
- Do not claim a speedup from a single run; timing claims require repeated comparable evidence.
- Do not change application/runtime behavior.

---

### Task 1: Lock suite ownership with a failing static harness contract

**Files:**
- Modify: `tools/android/Test-TvHarnessSyntax.ps1`

**Interfaces:**
- Requires `ConnectedSuite` values `Product` and `Database` in both device scripts.
- Requires explicit `Product` selection in `.github/workflows/android-tv-product-device-matrix.yml`.
- Requires explicit `Database` selection in `.github/workflows/database-migration-device-matrix.yml`.

- [ ] **Step 1: Extend the static harness checker to require the new selector and workflow routing before production scripts expose it.**
- [ ] **Step 2: Run `pwsh -NoProfile -File tools/android/Test-TvHarnessSyntax.ps1`; expected RED is a deterministic missing-ConnectedSuite/routing failure.**
- [ ] **Step 3: Commit the RED contract as `test(ci): require connected-suite ownership`.**

### Task 2: Centralize selected connected modules

**Files:**
- Modify: `tools/verify-local.ps1`

**Interfaces:**
- Produces parameter `[ValidateSet("Product", "Database")] [string]$ConnectedSuite = "Product"`.
- Produces one canonical connected-test catalog whose `Product` set has six modules and `Database` set has importer + database.
- Adds `connectedSuite` to evidence manifest.

- [ ] **Step 1: Add the selector without changing Fast/Full host behavior.**
- [ ] **Step 2: Replace six unconditional device steps with selected catalog entries containing step name, Gradle task, module path and display name.**
- [ ] **Step 3: Clear stale Android results and assert non-zero counts only for selected modules.**
- [ ] **Step 4: Record `connectedSuite` in the verification manifest.**
- [ ] **Step 5: Run the static checker; it must still fail until harness/workflows forward ownership.**
- [ ] **Step 6: Commit as `refactor(ci): select connected instrumentation suites`.**

### Task 3: Forward suite through the shared AVD harness

**Files:**
- Modify: `tools/android/Invoke-TvDeviceValidation.ps1`

**Interfaces:**
- Consumes `ConnectedSuite` with default `Product`.
- Forwards `-ConnectedSuite $ConnectedSuite` only to `verify-local.ps1 -Mode DeviceOnly`.
- Records the suite in root and per-profile device manifests.

- [ ] **Step 1: Add the validated parameter with Product default.**
- [ ] **Step 2: Add `connectedSuite` to root and profile records.**
- [ ] **Step 3: Forward the suite to every DeviceOnly invocation without branching AVD creation/readiness/cleanup.**
- [ ] **Step 4: Commit as `refactor(ci): forward connected-suite ownership`.**

### Task 4: Make workflow ownership explicit

**Files:**
- Modify: `.github/workflows/android-tv-product-device-matrix.yml`
- Modify: `.github/workflows/database-migration-device-matrix.yml`

**Interfaces:**
- Product workflow invokes `-ConnectedSuite Product`.
- Database workflow invokes `-ConnectedSuite Database`.

- [ ] **Step 1: Add Product selection to the product workflow.**
- [ ] **Step 2: Add Database selection to the database workflow.**
- [ ] **Step 3: Run `pwsh -NoProfile -File tools/android/Test-TvHarnessSyntax.ps1`; expected GREEN.**
- [ ] **Step 4: Review the diff and confirm the DB suite cannot schedule remote-EPG, credentials, Media3 or app connected tasks.**
- [ ] **Step 5: Commit as `ci: split product and database connected suites`.**

### Task 5: Acceptance and issue closure

**Files:**
- Update PR description and issue #101 evidence only.

- [ ] **Step 1: Open one draft PR from the fresh post-#123 branch and mark the old planning branch `work/ci-database-suite-split` as superseded in the PR body.**
- [ ] **Step 2: Obtain exact-head Full validation.**
- [ ] **Step 3: Obtain exact-head Product DeviceMatrix with all six non-zero connected suites on API26/API36.**
- [ ] **Step 4: Obtain exact-head Database DeviceMatrix with only importer + database non-zero suites on API26/API36.**
- [ ] **Step 5: Inspect manifests/logs to prove DB did not execute remote EPG, credentials, Media3 or app connected tasks.**
- [ ] **Step 6: Confirm zero unresolved review threads and final workflow/harness diff review.**
- [ ] **Step 7: Mark ready and squash-merge with `Closes #101`.**
- [ ] **Step 8: Record timing only as descriptive evidence until several comparable pre/post runs exist.**
