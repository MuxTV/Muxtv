# Measurement Report Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the existing canonical M3U parse, Android Room and Player proxy JSON reports into one strictly validated `MeasurementComparisonIdentity + MeasurementSeriesRun` boundary without changing any production parser, database, player or UI behavior.

**Architecture:** Keep all parsing and adaptation in `core:testing`. Read bounded report bytes, compute SHA-256 over the exact bytes, parse with `kotlinx.serialization.json.JsonElement`, validate each existing schema explicitly and map only timing samples into the variance foundation merged by PR #59. Android host profile facts that are not present in a child report—configured RAM, selected system image and requested CPU count—arrive through a bounded external context and must agree with report-visible facts before a run is accepted.

**Tech Stack:** Kotlin/JVM 2.4.10, kotlinx.serialization JSON 1.11.0, JUnit 4, Truth, Gradle 9/AGP 9.3 repository conventions, PowerShell 7 for the later orchestration package.

## Global Constraints

- `core:testing` remains the only module changed by runtime-independent adapter code.
- Production modules must not depend on adapter or variance types.
- Input report size is bounded to 1 MiB before JSON parsing.
- Unknown, missing, duplicated-by-semantics or mistyped required fields fail closed.
- Error messages and `toString()` output never contain JSON payloads, report paths, repetition IDs, device serials, machine names, locators, headers or provider values.
- Exact input bytes determine `sourceReportSha256`; parsed/re-serialized JSON must never be used for provenance.
- `thresholdApplied` must be exactly `false` and `failureCount` exactly `0`.
- M3U parse is a host-JVM dataset and must not be labeled as an Android API/RAM profile.
- Android Room and Player reports require an explicit host profile context and exact agreement with API/CPU/system-image expectations.
- Room variance initially analyzes operation wall time only; DB/WAL/SHM byte samples remain in child reports because zero is legitimate and must not be coerced into positive timing data.
- Player variance uses `normalizedNanosPerOperation`, not total batch time.
- No threshold, warning budget or production optimization is introduced.

---

## File Structure

- Create `core/testing/src/main/kotlin/app/muxtv/testing/measurements/MeasurementReportAdapter.kt`
  - bounded byte ownership, family dispatch, external profile context and typed adapter failures.
- Create `core/testing/src/main/kotlin/app/muxtv/testing/measurements/MeasurementJson.kt`
  - strict `JsonElement` field access, field-set validation and safe scalar normalization.
- Create `core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementReportAdapterTest.kt`
  - exact schema, provenance, comparability and redaction contracts for all three families.
- Modify `gradle/libs.versions.toml`
  - expose `kotlinx-serialization-json` from the existing serialization version.
- Modify `core/testing/build.gradle.kts`
  - add JSON implementation dependency only to `core:testing`.
- Later package, not this PR: `tools/measurements/Invoke-MeasurementSeries.ps1`
  - sequential AVD/host orchestration after adapters are merged.

---

### Task 1: JSON Dependency Boundary

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/testing/build.gradle.kts`

**Interfaces:**
- Consumes: existing version `serialization = "1.11.0"`.
- Produces: version-catalog accessor `libs.kotlinx.serialization.json` available only to `core:testing`.

- [ ] **Step 1: Add the version-catalog library alias**

```toml
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
```

- [ ] **Step 2: Add the testing-module dependency**

```kotlin
dependencies {
    implementation(project(":catalog:ingest"))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // existing test dependencies remain unchanged
}
```

- [ ] **Step 3: Verify dependency resolution**

Run:

```powershell
.\gradlew.bat :core:testing:dependencies --configuration runtimeClasspath --no-daemon
```

Expected: exit `0`, exactly one resolved `org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0` line in the dependency graph.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml core/testing/build.gradle.kts
git commit -m "build: add JSON support for measurement adapters"
```

---

### Task 2: Common Adapter Contracts — RED

**Files:**
- Create: `core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementReportAdapterTest.kt`

**Interfaces:**
- Produces expected API:

```kotlin
enum class MeasurementReportFamily(val id: String) {
    M3U_PARSE("m3u-parse"),
    CATALOG_DATABASE("catalog-database"),
    PLAYER_PROXY("player-proxy"),
}

data class AndroidMeasurementProfileContext(
    val requestedApiLevel: Int,
    val systemImage: String,
    val configuredRamMb: Int,
    val configuredCpuCores: Int,
    val fallbackUsed: Boolean,
)

data class MeasurementAdaptationRequest(
    val family: MeasurementReportFamily,
    val repetitionId: String,
    val reportBytes: ByteArray,
    val androidProfile: AndroidMeasurementProfileContext? = null,
)

data class AdaptedMeasurementRun(
    val identity: MeasurementComparisonIdentity,
    val run: MeasurementSeriesRun,
)

object MeasurementReportAdapter {
    fun adapt(request: MeasurementAdaptationRequest): AdaptedMeasurementRun
}
```

- [ ] **Step 1: Write the input-bound and safe-failure tests**

```kotlin
@Test
fun `adapter rejects empty and over-limit reports without echoing payload`() {
    val empty = assertThrows(MeasurementReportAdaptationException::class.java) {
        MeasurementReportAdapter.adapt(
            MeasurementAdaptationRequest(
                family = MeasurementReportFamily.M3U_PARSE,
                repetitionId = "host-01",
                reportBytes = byteArrayOf(),
            ),
        )
    }
    assertThat(empty.code).isEqualTo(MeasurementReportAdaptationFailure.EMPTY_REPORT)
    assertThat(empty.message).doesNotContain("host-01")

    val secret = "token=private".repeat(100_000).toByteArray()
    val large = assertThrows(MeasurementReportAdaptationException::class.java) {
        MeasurementReportAdapter.adapt(
            MeasurementAdaptationRequest(
                family = MeasurementReportFamily.M3U_PARSE,
                repetitionId = "host-02",
                reportBytes = secret,
            ),
        )
    }
    assertThat(large.code).isEqualTo(MeasurementReportAdaptationFailure.REPORT_TOO_LARGE)
    assertThat(large.message).doesNotContain("private")
}
```

- [ ] **Step 2: Write the exact-byte provenance test**

```kotlin
@Test
fun `source report identity is SHA-256 of exact input bytes`() {
    val bytes = validM3uReport().toByteArray(Charsets.UTF_8)
    val adapted = MeasurementReportAdapter.adapt(m3uRequest(bytes))

    assertThat(adapted.run.sourceReportSha256).isEqualTo(sha256(bytes))
    assertThat(adapted.run.repetitionId).isEqualTo("host-01")
}
```

- [ ] **Step 3: Write the Android-context requirement tests**

```kotlin
@Test
fun `host M3U rejects Android context and Android reports require it`() {
    assertAdaptationFailure(
        request = m3uRequest(validM3uReport().toByteArray(), androidProfile()),
        expected = MeasurementReportAdaptationFailure.UNEXPECTED_ANDROID_PROFILE,
    )
    assertAdaptationFailure(
        request = roomRequest(validRoomReport().toByteArray(), profile = null),
        expected = MeasurementReportAdaptationFailure.ANDROID_PROFILE_REQUIRED,
    )
    assertAdaptationFailure(
        request = playerRequest(validPlayerReport().toByteArray(), profile = null),
        expected = MeasurementReportAdaptationFailure.ANDROID_PROFILE_REQUIRED,
    )
}
```

- [ ] **Step 4: Run RED**

Run:

```powershell
.\gradlew.bat :core:testing:test --tests "app.muxtv.testing.measurements.MeasurementReportAdapterTest" --no-daemon
```

Expected: compilation failure because adapter types are absent.

- [ ] **Step 5: Commit the RED contracts**

```bash
git add core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementReportAdapterTest.kt
git commit -m "test: define measurement report adapter contracts"
```

---

### Task 3: Strict JSON Utilities

**Files:**
- Create: `core/testing/src/main/kotlin/app/muxtv/testing/measurements/MeasurementJson.kt`
- Extend tests: `MeasurementReportAdapterTest.kt`

**Interfaces:**
- Produces internal helpers:

```kotlin
internal class StrictJsonObject private constructor(
    private val value: JsonObject,
)

internal fun parseStrictJsonObject(bytes: ByteArray): StrictJsonObject
internal fun StrictJsonObject.requireExactFields(vararg names: String)
internal fun StrictJsonObject.requireObject(name: String): StrictJsonObject
internal fun StrictJsonObject.requireArray(name: String): JsonArray
internal fun StrictJsonObject.requireString(name: String): String
internal fun StrictJsonObject.requireInt(name: String): Int
internal fun StrictJsonObject.requireLong(name: String): Long
internal fun StrictJsonObject.requireBoolean(name: String): Boolean
```

- [ ] **Step 1: Add malformed-root and unknown-field RED tests**

```kotlin
@Test
fun `adapter rejects malformed non-object and unknown top-level fields`() {
    assertAdaptationFailure(m3uRequest("[]".toByteArray()), MeasurementReportAdaptationFailure.INVALID_JSON)
    assertAdaptationFailure(
        m3uRequest(validM3uReport(extraTopLevel = "\"unexpected\": true,").toByteArray()),
        MeasurementReportAdaptationFailure.UNSUPPORTED_SCHEMA,
    )
}
```

- [ ] **Step 2: Implement bounded parsing**

Use one `Json` instance configured as:

```kotlin
private val STRICT_JSON = Json {
    isLenient = false
    allowTrailingComma = false
    ignoreUnknownKeys = false
    explicitNulls = true
}
```

The parser must catch `SerializationException` and throw only:

```kotlin
MeasurementReportAdaptationException(
    MeasurementReportAdaptationFailure.INVALID_JSON,
)
```

- [ ] **Step 3: Implement exact field-set checks**

Compare `value.keys` with the expected set. Do not silently ignore unknown schema fields; a schema/method adapter must be reviewed before accepting them.

- [ ] **Step 4: Verify focused tests**

Run the same focused Gradle command. Expected: common parsing tests pass; family tests remain RED.

- [ ] **Step 5: Commit**

```bash
git add core/testing/src/main/kotlin/app/muxtv/testing/measurements/MeasurementJson.kt core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementReportAdapterTest.kt
git commit -m "feat: add strict measurement JSON parsing"
```

---

### Task 4: M3U Parse Adapter

**Files:**
- Create/modify: `MeasurementReportAdapter.kt`
- Test: `MeasurementReportAdapterTest.kt`

**Interfaces:**
- Input schema: canonical JSON produced by `M3uParseMeasurementJsonWriter`.
- Output identity:
  - family `m3u-parse`;
  - fixture SHA = `corpus.sha256`;
  - API/system image/RAM/low-RAM/memory class = `null`;
  - controlled runner label from the child report;
  - CPU = `environment.availableProcessors`;
  - build mode `jvm-measurement`;
  - supported ABI list contains normalized `environment.osArchitecture`;
  - runtime identity contains OS/JVM/allocation/max-heap fields;
  - workload contains profile, seed, warmups, measured iterations, corpus size and expected counts.
- Output operation: `parse-wall-time` from each `rawSamples[].wallTimeNanos`.

- [ ] **Step 1: Add valid M3U mapping test**

Assert every identity boundary and exact raw timing order.

- [ ] **Step 2: Add fail-closed tests**

Cover:
- schema or method other than `1`;
- `thresholdApplied=true`;
- `failureCount != 0`;
- fewer than five raw samples;
- summary sample count disagreement;
- source commit, runner label or corpus SHA malformed;
- allocation `null` remains legal and is not turned into a variance operation.

- [ ] **Step 3: Implement the adapter**

Parse and validate the existing report field names exactly. Normalize common architecture aliases only through a fixed mapping:

```kotlin
"amd64", "x64", "x86_64" -> "x86_64"
"aarch64", "arm64", "arm64-v8a" -> "arm64-v8a"
"x86" -> "x86"
```

Reject unknown architecture strings rather than embedding free text in the comparison identity.

- [ ] **Step 4: Verify focused M3U tests**

Expected: M3U adapter tests green; Android adapters remain RED.

- [ ] **Step 5: Commit**

```bash
git add core/testing/src/main/kotlin/app/muxtv/testing/measurements/MeasurementReportAdapter.kt core/testing/src/test/kotlin/app/muxtv/testing/measurements/MeasurementReportAdapterTest.kt
git commit -m "feat: adapt M3U measurement reports"
```

---

### Task 5: Android Room Adapter

**Interfaces:**
- Input schema: canonical JSON from `CatalogDatabaseMeasurementJsonWriter`.
- External context supplies requested API/system image/configured RAM/configured CPU/fallback state.
- Report API must equal requested API.
- Report `availableProcessors` must equal configured CPU.
- Identity runtime fields include manufacturer/model/build fingerprint and fallback state.
- Workload includes cache state and all six workload values.
- Operations map each `operationId` to ordered `rawSamples[].wallTimeNanos` only.

- [ ] **Step 1: Add valid Room mapping test**
- [ ] **Step 2: Add API, CPU and operation-order mismatch RED tests**
- [ ] **Step 3: Add fixture/workload/sample-count and failure RED tests**
- [ ] **Step 4: Implement minimal Room adapter**
- [ ] **Step 5: Run focused tests and commit**

```bash
git commit -m "feat: adapt Room measurement reports"
```

---

### Task 6: Player Proxy Adapter

**Interfaces:**
- Input schema: canonical JSON from `PlayerProxyMeasurementJsonWriter`.
- Fixture SHA = `requestProfileSha256`.
- Workload includes warmups, measured samples and operations per sample.
- Operations map each `operationId` to ordered `rawSamples[].normalizedNanosPerOperation`.
- The adapter verifies each sample operation/success count against workload.

- [ ] **Step 1: Add valid Player mapping test**
- [ ] **Step 2: Add wrong operation count/order and result-count RED tests**
- [ ] **Step 3: Add API/CPU/context disagreement tests**
- [ ] **Step 4: Implement minimal Player adapter**
- [ ] **Step 5: Run focused tests and commit**

```bash
git commit -m "feat: adapt Player measurement reports"
```

---

### Task 7: Full Verification and Review

**Files:** all files above.

- [ ] **Step 1: Focused tests**

```powershell
.\gradlew.bat :core:testing:test --tests "app.muxtv.testing.measurements.MeasurementReportAdapterTest" --no-daemon
```

Expected: all adapter tests pass, zero failures.

- [ ] **Step 2: Entire testing module**

```powershell
.\gradlew.bat :core:testing:test --no-daemon
```

Expected: all corpus, measurement, variance and adapter tests pass.

- [ ] **Step 3: Repository Full**

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

Expected: build logic, configuration cache, all JVM/Android unit tests, instrumentation compilation, lint and debug/release assembly exit `0`.

- [ ] **Step 4: Security review**

Search the final diff for:

```text
report path
raw JSON in exceptions
device serial
RUNNER_NAME
locator
Authorization
headers
thresholdApplied=true
```

Expected: no publishable diagnostics containing those values and no threshold path.

- [ ] **Step 5: Update PR metadata and merge**

PR title after GREEN:

```text
feat: add strict measurement report adapters
```

Squash only the exact head that passed Full.

---

## Follow-up Package: Sequential Series Orchestrator

This plan intentionally does not implement orchestration in the adapter PR. The next branch will:

1. add `PreferredApi`, old-edge fallback and correct `fallbackUsed` recording to Room/Player device wrappers;
2. create `tools/measurements/Invoke-MeasurementSeries.ps1`;
3. execute M3U host-JVM repetitions separately from Android profiles;
4. run Room and Player sequentially for `current`, `old-edge` and `current-low-ram`;
5. compute SHA-256 over every child report and invoke the merged strict adapters;
6. produce a basename-only canonical series manifest;
7. guarantee emulator shutdown between every repetition;
8. collect two current-profile smoke repetitions before scheduling five-series evidence.
