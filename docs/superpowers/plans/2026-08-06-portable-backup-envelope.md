# Portable Backup Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pure-Kotlin, versioned and bounded non-secret backup envelope that fails closed on untrusted input, marks every restored source `REAUTH_REQUIRED`, and produces a conflict preview before any future mutation.

**Architecture:** Add `:core:backup` with no Android/Room/network/credential dependencies. A validated domain model is encoded to canonical compact JSON; SHA-256 fingerprints the canonical unsigned document; the decoder bounds raw bytes before JSON parsing, validates exact v1 fields/invariants, and returns typed rejections. A separate previewer compares only local profile/source ids and never mutates storage.

**Tech Stack:** Kotlin 2.4.10, JDK `MessageDigest`, kotlinx.serialization JSON tree API 1.11.0, JUnit 4, Truth.

## Global Constraints

- Base exactly on accepted `main@ec2b7743183b227ef54c16989d061ae5d4775dee` while active product PR heads remain untouched.
- No Room entity, DAO, migration or database-version change.
- No Android, SAF, Google Drive, WorkManager, Hilt, Media3 or network dependency.
- No `credentialRef`, URL/locator, Authorization/Cookie/header, Keystore ciphertext/key, active revision or refresh-state field in the portable v1 model.
- Every portable source decodes as `REAUTH_REQUIRED`.
- Raw input above 2 MiB must be rejected before JSON parse.
- Unknown v1 fields and unsupported versions fail closed.
- SHA-256 is an integrity fingerprint, not a MAC/signature/authentication claim.
- No restore mutation in Package A; existing profile/source ids become explicit preview conflicts.
- With the self-hosted runner unavailable, author tests before production code but do **not** claim executed RED/GREEN or merge readiness.

---

### Task 1: Register the isolated backup module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/backup/build.gradle.kts`

**Interfaces:**
- Produces Gradle project `:core:backup`.
- Dependencies: `libs.kotlinx.serialization.json`, JUnit, Truth only.

- [ ] **Step 1: Add `:core:backup` to the root include list**

Keep the module beside the other core modules and do not change existing project ordering except for inserting the new entry.

- [ ] **Step 2: Add the module build file**

```kotlin
plugins { id("muxtv.kotlin.library") }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.test { useJUnit() }
```

- [ ] **Step 3: Commit module registration**

```bash
git add settings.gradle.kts core/backup/build.gradle.kts
git commit -m "build: register portable backup module (#113)"
```

### Task 2: Author the codec contract tests before production code

**Files:**
- Create: `core/backup/src/test/kotlin/app/muxtv/backup/PortableBackupCodecTest.kt`

**Interfaces:**
- Consumes future `PortableBackupCodec`, `PortableBackupSnapshot`, payload/value types and typed decode rejections.
- Proves canonical wire behavior, security exclusions, bounds and fail-closed parsing.

- [ ] **Step 1: Add a representative safe snapshot fixture**

Fixture content must include one profile, one source recovery stub, one channel overlay and one recent channel. Use obviously synthetic values only.

- [ ] **Step 2: Assert deterministic canonical round-trip**

Encode the same snapshot twice and require byte equality. Decode it and require a successful document whose snapshot equals the input and whose source state is `REAUTH_REQUIRED`.

- [ ] **Step 3: Assert secret/access fields are structurally impossible in output**

Check encoded JSON does not contain `credentialRef`, `Authorization`, `Cookie`, `locator`, `activeRevision` or a synthetic secret marker.

- [ ] **Step 4: Assert corruption/tamper detection**

Encode a valid document, alter one safe display-name character without recomputing the digest, and require `INTEGRITY_MISMATCH`.

- [ ] **Step 5: Assert malformed/truncated/oversized input rejection**

Test truncated JSON and a byte array of `PortableBackupLimits.MAX_DOCUMENT_BYTES + 1`.

- [ ] **Step 6: Assert unknown fields and unsupported versions fail closed**

Inject an unexpected root key and change `formatVersion` from 1 to 2. Require `UNKNOWN_FIELD` and `UNSUPPORTED_VERSION` respectively.

- [ ] **Step 7: Assert semantic safety limits**

Cover duplicate profile/source identities, overlay/recent cross-reference to a missing profile, more than 50 recent entries for one profile, invalid timestamps and invalid/oversized strings.

- [ ] **Step 8: Record the intended RED command without claiming it ran**

```powershell
./gradlew.bat :core:backup:test --tests app.muxtv.backup.PortableBackupCodecTest --no-daemon
```

When runner execution is available, the test-only commit must fail for missing production types/code; an infrastructure/compiler-harness failure unrelated to missing feature code does not count as RED.

- [ ] **Step 9: Commit the test-only contract**

```bash
git add core/backup/src/test/kotlin/app/muxtv/backup/PortableBackupCodecTest.kt
git commit -m "test: define portable backup codec contract (#113)"
```

### Task 3: Implement validated portable models

**Files:**
- Create: `core/backup/src/main/kotlin/app/muxtv/backup/PortableBackupModels.kt`

**Interfaces:**
- Produces:
  - `PortableBackupLimits`
  - `PortableSourceRecoveryState.REAUTH_REQUIRED`
  - `PortableBackupProfile`
  - `PortableBackupSource`
  - `PortableChannelOverlay`
  - `PortableRecentChannel`
  - `PortableBackupPayload`
  - `PortableBackupSnapshot`
  - `PortableBackupIntegrity`
  - `PortableBackupDocument`
  - `PortableBackupRejectReason`
  - `PortableBackupDecodeResult`

- [ ] **Step 1: Implement fixed safety ceilings**

Use:

```kotlin
const val MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
const val MAX_PROFILES = 16
const val MAX_SOURCES = 128
const val MAX_CHANNEL_OVERLAYS = 5_000
const val MAX_RECENT_CHANNELS = 800
const val MAX_RECENT_PER_PROFILE = 50
const val MAX_ID_CHARACTERS = 128
const val MAX_DISPLAY_NAME_CHARACTERS = 160
```

- [ ] **Step 2: Implement leaf-value invariants**

Ids must be nonblank, already trimmed and within 128 chars. Non-null names must meet their documented bound. Timestamps must be non-negative. Channel number must be null or non-negative.

- [ ] **Step 3: Implement payload-level invariants**

Enforce count ceilings; at most one active primary profile; profile/source identity uniqueness; overlay/recent pair uniqueness; overlay/recent profile references; and at most 50 recent rows per profile.

- [ ] **Step 4: Implement payload-free diagnostics**

Override `toString()` where a default data-class string would expose names/ids. Diagnostics may expose only counts, booleans, version/timestamp and typed status.

- [ ] **Step 5: Commit models**

```bash
git add core/backup/src/main/kotlin/app/muxtv/backup/PortableBackupModels.kt
git commit -m "feat: add portable backup domain model (#113)"
```

### Task 4: Implement canonical v1 codec and untrusted parser

**Files:**
- Create: `core/backup/src/main/kotlin/app/muxtv/backup/PortableBackupCodec.kt`

**Interfaces:**
- `fun PortableBackupCodec.encode(snapshot: PortableBackupSnapshot): ByteArray`
- `fun PortableBackupCodec.decode(bytes: ByteArray): PortableBackupDecodeResult`

- [ ] **Step 1: Reject oversized bytes before JSON parsing**

The first `decode` branch checks `bytes.size > MAX_DOCUMENT_BYTES` and returns `OVERSIZED` without invoking `Json.parseToJsonElement`.

- [ ] **Step 2: Parse a JSON object and validate exact field sets**

Root fields are exactly `formatVersion`, `createdAtEpochMillis`, `dataSchemaVersion`, `payload`, `integrity`. Apply exact allowed-field checks recursively to payload/profile/source/overlay/recent/integrity objects. Missing/wrong-typed fields become `MALFORMED`; extra fields become `UNKNOWN_FIELD`.

- [ ] **Step 3: Reject unsupported version before constructing domain content**

Only `formatVersion == 1` is accepted.

- [ ] **Step 4: Reconstruct validated domain objects**

All decoded sources are constructed with `PortableSourceRecoveryState.REAUTH_REQUIRED`; there is no wire field that can request a healthy/credential-present source.

- [ ] **Step 5: Produce canonical unsigned JSON**

Use fixed field order and compact JSON string escaping. Do not hash the `integrity` object itself. Preserve list order.

- [ ] **Step 6: Compute/verify SHA-256**

Encode computes lowercase SHA-256 of canonical unsigned UTF-8 bytes and emits algorithm `SHA-256`. Decode requires exact algorithm, 64 lowercase hex chars and compares expected/actual digest bytes using `MessageDigest.isEqual`.

- [ ] **Step 7: Map validation failures to stable rejection reasons**

Count/string ceilings -> `LIMIT_EXCEEDED`; duplicate identities -> `DUPLICATE_IDENTITY`; other semantic invariants -> `INVALID_DATA`; malformed tree/primitive errors -> `MALFORMED`.

- [ ] **Step 8: Run codec tests when execution is available**

```powershell
./gradlew.bat :core:backup:test --tests app.muxtv.backup.PortableBackupCodecTest --no-daemon
```

Expected after implementation: PASS. Do not record that expectation as evidence until a fresh run exits 0.

- [ ] **Step 9: Commit codec**

```bash
git add core/backup/src/main/kotlin/app/muxtv/backup/PortableBackupCodec.kt
git commit -m "feat: add bounded portable backup codec (#113)"
```

### Task 5: Author and implement pre-apply restore preview

**Files:**
- Create test first: `core/backup/src/test/kotlin/app/muxtv/backup/BackupRestorePreviewTest.kt`
- Create production after test: `core/backup/src/main/kotlin/app/muxtv/backup/BackupRestorePreview.kt`

**Interfaces:**
- `data class ExistingBackupState(val profileIds: Set<String>, val sourceIds: Set<String>)`
- `enum class BackupConflictKind { PROFILE_ID, SOURCE_ID }`
- `data class BackupRestoreConflict(val kind: BackupConflictKind)` with payload-free diagnostics
- `data class BackupRestorePreview(...)`
- `object BackupRestorePreviewer { fun preview(document: PortableBackupDocument, existingState: ExistingBackupState): BackupRestorePreview }`

- [ ] **Step 1: Write tests before preview production code**

Cover zero-conflict import, profile/source id conflicts, every source counted as requiring re-authentication, summary counts and diagnostics that omit ids/names.

- [ ] **Step 2: Record intended preview RED command without claiming execution**

```powershell
./gradlew.bat :core:backup:test --tests app.muxtv.backup.BackupRestorePreviewTest --no-daemon
```

- [ ] **Step 3: Implement minimal pure previewer**

Compare only id sets. Do not define overwrite/merge policy and do not mutate any repository/database.

- [ ] **Step 4: Run full module tests when execution is available**

```powershell
./gradlew.bat :core:backup:test --no-daemon
```

- [ ] **Step 5: Commit preview contract**

```bash
git add core/backup/src/test/kotlin/app/muxtv/backup/BackupRestorePreviewTest.kt core/backup/src/main/kotlin/app/muxtv/backup/BackupRestorePreview.kt
git commit -m "feat: add backup restore preview contract (#113)"
```

### Task 6: Static audit, issue truth-sync and post-runner acceptance

**Files:**
- No production expansion.
- Update issue #113 with exact branch/head and validation status.

- [ ] **Step 1: Diff against accepted main**

Verify changed paths are limited to `settings.gradle.kts`, `core/backup/**` and the two #113 docs.

- [ ] **Step 2: Secret-field scan**

Inspect production wire model/codec for `credentialRef`, URL/locator, Authorization, Cookie, headers, Keystore material and active revision. Mentions are allowed only in tests/docs asserting exclusion; no such production field may exist.

- [ ] **Step 3: Schema-owner scan**

Confirm no `core/database/**`, Room schema JSON or migration path changed.

- [ ] **Step 4: Comment issue #113**

Record Package A scope, exact head, test-first commit, security boundary, unverified status and remaining packages. Do not close #113.

- [ ] **Step 5: Acceptance commands when runner returns**

Run in order:

```powershell
./gradlew.bat :core:backup:test --no-daemon
./gradlew.bat :core:backup:compileKotlin --no-daemon
./gradlew.bat test --no-daemon
```

Only after fresh exit-0 evidence may the branch be described as GREEN or considered for PR/merge.
