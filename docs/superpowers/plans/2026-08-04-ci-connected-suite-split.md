# CI Connected Suite Split Implementation Plan

> **Execution rule:** keep one Android TV AVD lifecycle and one result-count implementation. Split only which connected modules execute. Do not duplicate emulator orchestration or weaken API26/current evidence.

**Goal:** Stop `Database migration device matrix` from rerunning the complete product instrumentation suite while preserving a full Product matrix and migration-owned API26/current proof.

**Architecture:** `verify-local.ps1` accepts an explicit connected suite (`Product` or `Database`) for `Device`/`DeviceOnly`. `Invoke-TvDeviceValidation.ps1` forwards the suite into each profile. Product and DB workflows select their owner-specific suite. Static harness checks lock workflow-to-suite routing.

## Constraints

- `Product` remains the default for manual/direct `Device` callers.
- `Product` keeps importer, remote EPG, credentials, database, Media3 and app instrumentation.
- `Database` initially runs importer and database instrumentation only.
- Full host validation still precedes any AVD startup.
- API26 old-edge fallback and current API remain sequential.
- XML failures/errors and zero-test rejection remain enforced per selected module.
- Do not claim a speedup from one run; capture equivalent wall-time series.

## Task 1 — Static routing contract

**Modify:** `tools/android/Test-TvHarnessSyntax.ps1`

Require:

- `verify-local.ps1` exposes `ConnectedSuite` with `Product` and `Database`;
- `Invoke-TvDeviceValidation.ps1` accepts and forwards `ConnectedSuite`;
- product workflow passes `-ConnectedSuite Product`;
- database workflow passes `-ConnectedSuite Database`;
- DB selected task text excludes remote EPG, credentials, Media3 and app connected tasks.

## Task 2 — Select connected modules centrally

**Modify:** `tools/verify-local.ps1`

- add `[ValidateSet("Product", "Database")] [string]$ConnectedSuite = "Product"`;
- define one Product module/task catalog and one Database subset;
- register and count only selected modules for `Device`/`DeviceOnly`;
- add `connectedSuite` to the evidence manifest;
- preserve all host modes and Product default behavior.

## Task 3 — Forward suite through the shared AVD harness

**Modify:** `tools/android/Invoke-TvDeviceValidation.ps1`

- add the same validated parameter;
- record it in `tv-device-manifest.json` and profile records;
- pass it to each `verify-local.ps1 -Mode DeviceOnly` invocation;
- do not branch AVD creation/readiness/cleanup by suite.

## Task 4 — Route workflows

**Modify:**

- `.github/workflows/android-tv-product-device-matrix.yml`
- `.github/workflows/database-migration-device-matrix.yml`

Product selects `Product`; DB selects `Database`.

## Acceptance

1. exact-head Full validation green;
2. Product API26/current green with all six connected module counts;
3. DB API26/current green with importer+database counts only;
4. DB evidence contains no remote-EPG, credentials, Media3 or app connected task;
5. both matrices still run Full before AVD startup;
6. zero unresolved review threads and clean base-to-head comparison;
7. record several equivalent old/new matrix wall times before making a quantitative performance claim.
