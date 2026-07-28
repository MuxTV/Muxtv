# Catalog Database Measurements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add reproducible, threshold-free Android Room measurements for catalog staging, activation and the two production queries required by issue #27.

**Architecture:** Keep measurement model/runner/writer in `core:database` debug sources so release code and public APIs remain unchanged. Use one dedicated instrumentation class and one host PowerShell command to execute on a provisioned Android TV emulator, pull canonical JSON evidence and validate its invariants.

**Tech Stack:** Kotlin 2.4.10, Android SDK 26–36, Room 3.0.0, coroutines 1.11.0, AndroidX Test 1.7/1.3, PowerShell 7, existing self-hosted Android TV harness.

## Global Constraints

- `minSdk = 26` remains unchanged.
- No Room schema, index, query, transaction or batch-size production change in this package.
- Default workload is 10,000 entries, batch size 250, first page 100, source overviews 32, one warmup and five measured samples.
- Every sample uses a fresh file-backed database; setup and prerequisite seeding are outside the timer.
- Reports retain every sample and always state `thresholdApplied = false`.
- No locator, credential, provider identity, source ID, full path or exception text may enter output, logs or `toString()`.
- Ordinary DeviceCurrent/DeviceMatrix correctness suites must not execute or skip the measurement test.
- Device timing is descriptive for the exact recorded environment and is not a codec, zapping, startup or weak-TV claim.

---

### Task 1: Define RED statistics and report contracts

**Files:**
- Create: `core/database/src/test/kotlin/app/muxtv/database/measurement/CatalogDatabaseMeasurementStatisticsTest.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/CatalogDatabaseMeasurementContractTest.kt`

**Interfaces:**
- Produces required names: `CatalogDatabaseMeasurementStatistics`, `CatalogDatabaseMeasurementSpec`, `CatalogDatabaseMeasurementReport`, `CatalogDatabaseMeasurementJsonWriter`, `CatalogDatabaseMeasurementRunner`.

- [ ] **Step 1: Write failing unit tests**

Test nearest-rank min/p50/p90/p95/max, immutable raw samples, `thresholdApplied = false`, canonical LF JSON and redacted diagnostics.

- [ ] **Step 2: Write failing instrumentation contract**

Reference the absent runner and assert five operations, configured result counts, zero failures and a host-pullable report.

- [ ] **Step 3: Run Full to verify RED**

Run: `pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon`

Expected: compile failure because measurement model/runner types do not exist.

- [ ] **Step 4: Commit RED**

Commit message: `test: define catalog database measurement contracts`.

### Task 2: Implement debug-only model, statistics and JSON writer

**Files:**
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/CatalogDatabaseMeasurementModel.kt`
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/CatalogDatabaseMeasurementJsonWriter.kt`

**Interfaces:**
- `CatalogDatabaseMeasurementStatistics.summarize(List<Long>): CatalogDatabaseMeasurementSummary`
- immutable `CatalogDatabaseMeasurementSample`, `CatalogDatabaseOperationReport`, `CatalogDatabaseMeasurementReport`
- `CatalogDatabaseMeasurementJsonWriter.write(report, OutputStream)`

- [ ] **Step 1: Implement nearest-rank statistics**

Reject empty/negative inputs and preserve all samples.

- [ ] **Step 2: Implement bounded models**

Validate exact lowercase 40-character commit, safe runner label, warmups 0..20, iterations 5..100, entries 250..50,000, batch size exactly 250, page limit 1..500 and source count 1..100.

- [ ] **Step 3: Implement fixed-order JSON**

Write UTF-8, LF-only, exactly one trailing newline and no reflection/map-order dependency.

- [ ] **Step 4: Run focused unit tests**

Run: `.\gradlew.bat :core:database:testDebugUnitTest --tests "app.muxtv.database.measurement.*" --no-daemon --stacktrace --console=plain`

Expected: statistics/model/writer tests pass; instrumentation runner contract still fails to compile until Task 3.

- [ ] **Step 5: Commit**

Commit message: `feat: add catalog database measurement report model`.

### Task 3: Implement the real Room measurement runner

**Files:**
- Create: `core/database/src/debug/kotlin/app/muxtv/database/measurement/CatalogDatabaseMeasurementRunner.kt`

**Interfaces:**
- `suspend fun CatalogDatabaseMeasurementRunner.run(spec, output): CatalogDatabaseMeasurementReport`
- Operations: `stage-batch-250`, `stage-total-10k`, `activate-10k`, `active-channel-first-page`, `source-overview-32`.

- [ ] **Step 1: Prepare immutable synthetic rows outside timers**

Generate stable synthetic IDs and `.example` locators once per run. Do not retain provider/user values in reports.

- [ ] **Step 2: Implement fresh database lifecycle**

Create unique file-backed Room databases, use WAL, close and delete DB/WAL/SHM after each sample.

- [ ] **Step 3: Implement staging measurements**

Measure one 250-row transaction and forty 250-row transactions for 10,000 rows. Verify persisted counts after timing.

- [ ] **Step 4: Implement activation measurement**

Stage outside the timer, time only `activate`, require `Activated(entryCount = 10_000)`.

- [ ] **Step 5: Implement query measurements**

Prepare active rows outside timers. Time first `observeActiveChannels(... limit = 100).first()` and first `observeOverviews().first()` with 32 sources.

- [ ] **Step 6: Capture environment/storage metadata**

Record manufacturer/model/fingerprint/API/ABI, low-RAM flag, memory class, processors and per-sample DB/WAL/SHM bytes.

- [ ] **Step 7: Keep failures explicit**

A correctness mismatch throws a secret-free fixed message. No successful report is emitted after a failed sample.

- [ ] **Step 8: Run unit and instrumentation compile gates**

Run: `.\gradlew.bat :core:database:testDebugUnitTest :core:database:assembleDebugAndroidTest --no-daemon --stacktrace --console=plain`

Expected: PASS.

- [ ] **Step 9: Commit**

Commit message: `feat: measure real Room catalog boundaries`.

### Task 4: Add dedicated instrumentation entry and normal-suite exclusion

**Files:**
- Modify: `core/database/build.gradle.kts`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/CatalogDatabaseMeasurement.kt`
- Create: `core/database/src/androidTest/kotlin/app/muxtv/database/CatalogDatabaseMeasurementTest.kt`

**Interfaces:**
- Annotation: `app.muxtv.database.CatalogDatabaseMeasurement`
- Arguments: `measurementSourceCommit`, `measurementRunnerLabel`, `measurementWarmups`, `measurementIterations`, `measurementEntryCount`, `measurementOutputName`.

- [ ] **Step 1: Exclude annotation by default**

Set `notAnnotation` unless Gradle property `catalogMeasurements=true` is present. Set explicit test application ID `app.muxtv.database.test`.

- [ ] **Step 2: Implement bounded argument parsing**

Reject absent/malformed values without echoing supplied values.

- [ ] **Step 3: Write report atomically**

Use the test context external files `measurements/` directory and rename a staged file to the final safe filename.

- [ ] **Step 4: Assert execution invariants**

Require all five operation reports, five samples each, zero failures and expected result counts.

- [ ] **Step 5: Run ordinary instrumentation compile**

Run: `.\gradlew.bat :core:database:assembleDebugAndroidTest --no-daemon --stacktrace --console=plain`

Expected: PASS with measurement test excluded from ordinary connected runs.

- [ ] **Step 6: Commit**

Commit message: `test: add dedicated catalog measurement instrumentation`.

### Task 5: Add reusable host execution and harness mode

**Files:**
- Create: `tools/android/Invoke-CatalogDatabaseMeasurement.ps1`
- Modify: `tools/android/Invoke-TvDeviceValidation.ps1`
- Modify: `.github/workflows/self-hosted-validation.yml`
- Modify: `tools/android/Test-TvHarnessSyntax.ps1` if its explicit script list requires the new command.

**Interfaces:**
- New harness mode: `CatalogMeasurement`.
- Host output: `catalog-database-measurement.json` and `catalog-database-measurement.log`.

- [ ] **Step 1: Implement host command**

Run only the measurement class with `catalogMeasurements=true`, pull from `/sdcard/Android/data/app.muxtv.database.test/files/measurements/`, parse JSON and validate exact commit, schema, five operations, non-empty samples, zero failures and `thresholdApplied=false`.

- [ ] **Step 2: Extend current-device harness**

Provision one current Android TV AVD and call the host measurement command instead of the full correctness suite when mode is `CatalogMeasurement`.

- [ ] **Step 3: Extend manual workflow choice**

Add `CatalogMeasurement` to `workflow_dispatch` without changing pull-request Full behavior.

- [ ] **Step 4: Validate PowerShell syntax**

Run: `pwsh -NoProfile -File .\tools\android\Test-TvHarnessSyntax.ps1`

Expected: PASS.

- [ ] **Step 5: Run Full**

Run: `pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon`

Expected: PASS.

- [ ] **Step 6: Commit**

Commit message: `chore: add catalog measurement harness mode`.

### Task 6: Exact-head evidence, review and merge

**Files:**
- Temporarily create/remove: `.github/workflows/pr-catalog-measurement.yml`
- Modify after evidence: PR body, issue #27 progress comment and repository status docs if merged.

- [ ] **Step 1: Open draft PR from the RED head**

Record opening RED commit and expected missing-type failure.

- [ ] **Step 2: Run ordinary Full on final head**

Require all permanent correctness/build/lint gates green.

- [ ] **Step 3: Run dedicated current-device measurement**

Use the temporary PR workflow or manual `CatalogMeasurement` dispatch. Require exact-head report artifact and no fallback.

- [ ] **Step 4: Review report interpretation**

Publish raw distributions and environment. Do not infer weak-TV, codec, startup or zapping performance.

- [ ] **Step 5: Remove temporary workflow**

Final tree retains only the reusable manual harness mode.

- [ ] **Step 6: Review diff and threads**

Confirm no schema/index/query/batch production change, no release dependency and no unresolved review threads.

- [ ] **Step 7: Squash merge**

Merge only when exact head and final tree are verified.

- [ ] **Step 8: Update issue #27**

Record completed Room evidence and keep issue open only for Player proxy measurements and repeated variance/threshold decision.
