# Measurement Series Orchestrator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute reproducible repeated M3U, Android Room and Player proxy measurement series, validate every child report through the strict adapters merged in PR #60, and publish canonical threshold-free variance reports and audit manifests.

**Architecture:** A pure Kotlin `core:testing` command owns adaptation, identity agreement, aggregation and canonical report/manifest serialization. A repository PowerShell harness owns sequential execution and Android emulator lifecycle. Host-JVM M3U repetitions remain a separate dataset; one Android TV emulator is booted per Android profile/repetition and executes Room followed by Player before guaranteed shutdown. No script performs statistical calculations or trusts filenames as report identity.

**Tech Stack:** Kotlin/JVM 2.4.10, kotlinx.serialization JSON 1.11.0, Gradle 9/AGP 9.3, PowerShell 7, Android Emulator/ADB, existing repository `AndroidSdk.ps1`, strict adapters and variance foundation in `core:testing`.

## Global Constraints

- No production parser, Room schema, player, transport, UI, dependency version or release behavior change.
- No performance threshold, warning budget or optimization decision in this package.
- M3U host-JVM reports are never grouped with Android profiles.
- Room and Player are grouped only when each family has an identical comparison fingerprint across repetitions.
- Android emulators run sequentially; never boot two AVDs concurrently on the self-hosted runner.
- One AVD may execute Room and Player serially within the same repetition/profile, but each child measurement retains its own report and exact SHA-256.
- Every emulator is stopped in `finally`, including child failure, adapter failure, aggregation failure and cancellation.
- Series count is bounded to 2–20 for this orchestration package even though the lower-level analyzer supports up to 100.
- Initial PR executes exactly two `current-normal` smoke repetitions. Five-run current/old-edge/current-low-RAM evidence is a later evidence-only package after smoke stability.
- Current profile: requested API 36, 2048 MiB RAM, 2 CPU cores.
- Old-edge profile: requested API 26, 1536 MiB RAM, 2 CPU cores; a resolved older fallback is recorded and remains a separate dataset.
- Current-low-RAM profile: requested API 36, 1024 MiB RAM, 2 CPU cores; Android-reported `lowRamDevice` remains an observed field and is not assumed solely from configured RAM.
- No physical-TV, weak ARM SoC, decoder, HDR, passthrough, zapping or first-frame claim is made.
- Evidence and public diagnostics never contain source locators, headers, provider values, raw JSON payloads, absolute paths, runner machine name or device serial.
- Child logs may contain ordinary build/device diagnostics inside private Actions artifacts, but aggregate JSON and Markdown reports contain only bounded canonical metadata.

---

## File Structure

- Create `core/testing/src/main/kotlin/app/muxtv/testing/measurements/MeasurementSeriesCommand.kt`
  - safe CLI, request manifest parsing, adapter invocation, identity agreement, analyzer invocation and exit codes.
- Create `core/testing/src/main/kotlin/app/muxtv/testing/measurements/MeasurementSeriesManifest.kt`
  - canonical input/output manifest model and fixed-order JSON writer.
- Create `core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementSeriesCommandTest.kt`
  - CLI, exact-byte provenance, mixed-series rejection, safe-error and publication tests.
- Modify `core/testing/build.gradle.kts`
  - add configuration-cache-safe `analyzeMeasurementSeries` JavaExec entry point.
- Create `tools/measurements/MeasurementProfiles.ps1`
  - fixed repository-owned profile catalog and validation.
- Create `tools/measurements/Invoke-MeasurementSeries.ps1`
  - host M3U repetitions, sequential Android profile lifecycle, child execution, command invocation and evidence manifest.
- Create `tools/measurements/Test-MeasurementHarnessSyntax.ps1`
  - parse scripts and verify required function surface.
- Modify `tools/android/Test-TvHarnessSyntax.ps1`
  - include measurement harness scripts or delegate to the dedicated checker.
- Modify `.github/workflows/self-hosted-validation.yml`
  - permanent manual `VarianceSmoke` mode only after local/focused evidence succeeds.
- Create `docs/performance/2026-07-30-current-variance-smoke.md`
  - durable interpretation of two-run smoke evidence; explicitly not a budget.

---

### Task 1: Canonical Series Request and Output Contracts — RED

**Files:**
- Create: `core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementSeriesCommandTest.kt`

**Interfaces:**

```kotlin
enum class MeasurementSeriesCommandExitCode(val code: Int) {
    SUCCESS(0),
    USAGE(2),
    INPUT(3),
    ANALYSIS(4),
    PUBLICATION(5),
    INTERNAL(10),
}

object MeasurementSeriesCommand {
    fun run(
        args: Array<String>,
        stdout: Appendable,
        stderr: Appendable,
    ): Int
}
```

Canonical request file schema:

```json
{
  "schemaVersion": 1,
  "family": "m3u-parse|catalog-database|player-proxy",
  "outputName": "m3u-current-variance.json",
  "runs": [
    {
      "repetitionId": "current-01",
      "reportName": "m3u-current-01.json"
    }
  ],
  "androidProfile": null
}
```

Android profile object when required:

```json
{
  "requestedApiLevel": 36,
  "systemImage": "system-images;android-36;android-tv;x86_64",
  "configuredRamMb": 2048,
  "configuredCpuCores": 2,
  "fallbackUsed": false
}
```

- [ ] **Step 1: Write RED option parsing tests**

Require exactly:

```text
--request <file>
--input-directory <directory>
--output-directory <directory>
```

Reject missing, duplicate, unknown and positional options without echoing supplied values or absolute paths.

- [ ] **Step 2: Write RED bounded manifest tests**

- schema exactly `1`;
- exact top-level and run fields;
- 2–20 runs;
- unique repetition IDs and report basenames;
- `.json` basenames only, no separators, `..`, rooted paths or control characters;
- M3U requires `androidProfile=null`;
- Room/Player require an Android profile.

- [ ] **Step 3: Write RED analysis tests**

Create two real canonical child reports in a temp directory and assert:

- exact-byte SHA enters the output audit list;
- mixed source commit/environment/workload is rejected;
- duplicate child bytes under different names are rejected by report SHA;
- output remains `thresholdApplied=false`;
- family cannot be relabeled.

- [ ] **Step 4: Write RED publication tests**

- output directory is caller-owned;
- implicit overwrite rejected;
- staging file written and atomically moved;
- partial output removed on failure;
- stdout reports only status and output basename;
- stderr reports only typed failure code.

- [ ] **Step 5: Run RED**

```powershell
.\gradlew.bat :core:testing:test --tests "app.muxtv.testing.measurements.MeasurementSeriesCommandTest" --no-daemon
```

Expected: compilation failure because command/manifest types are absent.

- [ ] **Step 6: Commit**

```bash
git add core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementSeriesCommandTest.kt
git commit -m "test: define measurement series command"
```

---

### Task 2: Safe Command and Canonical Manifest Implementation

**Files:**
- Create: `MeasurementSeriesCommand.kt`
- Create: `MeasurementSeriesManifest.kt`
- Test: `MeasurementSeriesCommandTest.kt`

**Interfaces:**
- Consumes: `MeasurementReportAdapter.adapt`, `MeasurementVarianceAnalyzer.analyze`, `MeasurementVarianceJsonWriter.write`.
- Produces: one canonical variance report and one canonical audit manifest per family/profile request.

- [ ] **Step 1: Implement strict option parsing**
- [ ] **Step 2: Parse the request through strict JSON helpers**
- [ ] **Step 3: Resolve basenames under the supplied input directory and verify regular files**
- [ ] **Step 4: Read each report with the existing 1 MiB adapter bound**
- [ ] **Step 5: Adapt every run and require exact identity equality**
- [ ] **Step 6: Analyze runs and serialize canonical variance JSON**
- [ ] **Step 7: Serialize canonical audit manifest with report basenames and SHA-256 only**
- [ ] **Step 8: Publish through staging and explicit no-overwrite semantics**
- [ ] **Step 9: Run focused tests**
- [ ] **Step 10: Commit**

```bash
git commit -m "feat: add measurement series command"
```

---

### Task 3: Gradle Entry Point

**Files:**
- Modify: `core/testing/build.gradle.kts`
- Test: command integration test or Gradle invocation evidence.

**Interfaces:**
- Gradle task: `:core:testing:analyzeMeasurementSeries`.
- Properties:
  - `measurementSeriesRequest`;
  - `measurementSeriesInputDirectory`;
  - `measurementSeriesOutputDirectory`.

- [ ] **Step 1: Add provider-backed properties without execution-time Project capture**
- [ ] **Step 2: Register JavaExec using `mainSourceSet.runtimeClasspath`**
- [ ] **Step 3: Verify configuration cache twice**

```powershell
.\gradlew.bat :core:testing:analyzeMeasurementSeries `
  -PmeasurementSeriesRequest=<request> `
  -PmeasurementSeriesInputDirectory=<input> `
  -PmeasurementSeriesOutputDirectory=<output> `
  --configuration-cache --no-daemon
```

- [ ] **Step 4: Commit**

```bash
git commit -m "build: expose measurement series analysis"
```

---

### Task 4: Repository Measurement Profile Catalog

**Files:**
- Create: `tools/measurements/MeasurementProfiles.ps1`
- Create: `tools/measurements/Test-MeasurementHarnessSyntax.ps1`

**Interfaces:**

```powershell
Get-MuxTvMeasurementProfile -Id current-normal
Get-MuxTvMeasurementProfile -Id old-edge-normal
Get-MuxTvMeasurementProfile -Id current-low-ram
```

Each profile returns only:

```text
Id, RequestedApi, RamMb, CpuCores
```

The resolved system image, actual API, ABI and fallback state come from `Resolve-TvSystemImage` and are recorded after resolution.

- [ ] **Step 1: Write parser/function-surface checks**
- [ ] **Step 2: Implement the fixed profile catalog**
- [ ] **Step 3: Reject unknown/supplied arbitrary profile values**
- [ ] **Step 4: Verify syntax and commit**

```bash
git commit -m "feat: add measurement profile catalog"
```

---

### Task 5: Sequential Android Repetition Runner

**Files:**
- Create: `tools/measurements/Invoke-MeasurementSeries.ps1`
- Reuse: `tools/android/AndroidSdk.ps1`
- Reuse: `tools/android/Invoke-CatalogDatabaseMeasurement.ps1`
- Reuse: `tools/android/Invoke-PlayerProxyMeasurement.ps1`

**Execution per Android repetition:**

1. resolve/install profile image;
2. allocate one free emulator port;
3. create one uniquely named AVD;
4. start and wait for boot/package manager;
5. collect bounded environment evidence;
6. execute Room measurement;
7. execute Player measurement;
8. verify both child JSON files exist and are non-empty;
9. stop emulator in `finally`;
10. remove or replace `ANDROID_SERIAL` exactly;
11. append only basenames/profile metadata to the series manifest.

- [ ] **Step 1: Add strict parameters**

```powershell
-SourceCommit <40 lowercase SHA>
-SourceBranch <bounded label>
-ProfileId current-normal
-Repetitions 2
-EvidenceRoot .work/evidence
-NoDaemon
```

- [ ] **Step 2: Implement one-AVD-per-repetition lifecycle**
- [ ] **Step 3: Execute Room and Player sequentially**
- [ ] **Step 4: Guarantee cleanup and bounded failure metadata**
- [ ] **Step 5: Generate per-family request manifests**
- [ ] **Step 6: Invoke `analyzeMeasurementSeries` for Room and Player**
- [ ] **Step 7: Run harness syntax checks and commit**

```bash
git commit -m "feat: orchestrate Android measurement series"
```

---

### Task 6: Host M3U Repetitions

**Files:**
- Modify: `Invoke-MeasurementSeries.ps1`

- [ ] **Step 1: Execute `:core:testing:measureM3uParse` twice with unique output basenames**
- [ ] **Step 2: Keep runner label, JVM, workload, fixture and source commit fixed**
- [ ] **Step 3: Generate an M3U request with `androidProfile=null`**
- [ ] **Step 4: Aggregate through the same Kotlin command**
- [ ] **Step 5: Verify M3U is not nested below an Android profile directory or description**
- [ ] **Step 6: Commit**

```bash
git commit -m "feat: orchestrate host M3U repetitions"
```

---

### Task 7: Current-Profile Smoke Evidence

**Scope:** exactly two repetitions, current profile only.

- [ ] **Step 1: Run repository Full on the permanent tree**
- [ ] **Step 2: Run `Invoke-MeasurementSeries.ps1 -ProfileId current-normal -Repetitions 2`**
- [ ] **Step 3: Review child reports, report hashes, comparison fingerprints and variance reports**
- [ ] **Step 4: Confirm no threshold or production decision**
- [ ] **Step 5: Write `docs/performance/2026-07-30-current-variance-smoke.md`**
- [ ] **Step 6: Commit durable interpretation only after evidence exists**

---

### Task 8: Permanent Manual Workflow Mode

**Files:**
- Modify: `.github/workflows/self-hosted-validation.yml`

- [ ] **Step 1: Add manual `VarianceSmoke` choice**
- [ ] **Step 2: Invoke only two current-profile repetitions**
- [ ] **Step 3: Upload child reports, canonical variance reports, audit manifests and bounded logs**
- [ ] **Step 4: Keep pull-request Full unchanged**
- [ ] **Step 5: Run the manual mode on the reviewed exact head**
- [ ] **Step 6: Remove any temporary branch-specific workflow**

---

### Task 9: Review and Merge Gates

- [ ] focused RED/GREEN command tests;
- [ ] entire `:core:testing:test`;
- [ ] harness syntax checks;
- [ ] configuration cache reuse;
- [ ] repository Full;
- [ ] exact-head two-run current smoke;
- [ ] no unresolved review threads;
- [ ] aggregate JSON contains no absolute paths, serials, machine names or payload values;
- [ ] squash only the exact head that passed all gates;
- [ ] update issue #27 without closing it.

---

## Follow-up Evidence Packages After This PR

1. Five current-normal repetitions for each family.
2. Five old-edge-normal Room/Player repetitions; M3U remains host-only.
3. Five current-low-RAM Room/Player repetitions.
4. Cross-series durable report comparing only internally homogeneous datasets.
5. Explicit decision per operation:
   - threshold gate;
   - warning-only monitoring;
   - descriptive-only/no threshold.
6. Only after that decision may an optimization PR cite before/after evidence.
