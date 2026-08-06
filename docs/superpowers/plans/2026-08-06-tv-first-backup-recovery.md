# TV-first Backup Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a pure, testable recovery-entry capability policy and restore-preparation boundary for #113 without pretending that app-specific storage or SAF-only UX satisfies Android TV durable recovery.

**Architecture:** Extend `:core:backup` only. Package B adds transport capability metadata/policy and a preparation coordinator that composes the existing Package A decoder + previewer, always stopping at `PreviewRequired`. Concrete Android transport I/O, UI and Room mutation remain outside this package.

**Tech Stack:** Kotlin/JVM, JUnit4, Truth, existing `:core:backup`, `kotlinx-serialization-json` transitively through Package A.

## Global Constraints

- Keep `work/portable-backup-envelope-113` based on accepted `main@ec2b7743183b227ef54c16989d061ae5d4775dee` until Package A/B validation is available.
- Do not touch Room entities, schema JSON, migrations, Hilt, WorkManager, Media3 or network code in Package B.
- Do not implement Google Drive, SAF UI, local HTTP server, companion transfer or a concrete `TV_NATIVE_DURABLE` adapter in this package.
- `APP_SPECIFIC` must never be classified as durable recovery because Android removes app-specific files on uninstall.
- SAF/system picker is optional and may be shown only when its capability is detected; it cannot be the sole TV-native recovery promise.
- Every valid import returns `PreviewRequired`; there is no direct apply path.
- No URI/path/provider/account/volume/file-name/backup payload/credential/access material may enter diagnostics.
- No production Package B code may be committed before the test-only head has been executed and shown the expected RED.

---

### Task 1: Recovery transport capability policy

**Files:**
- Test: `core/backup/src/test/kotlin/app/muxtv/backup/BackupRecoveryEntryPolicyTest.kt`
- Create after RED: `core/backup/src/main/kotlin/app/muxtv/backup/BackupRecoveryTransport.kt`

**Interfaces:**
- Produces:
  - `enum class BackupRecoveryTransportKind { APP_SPECIFIC, SYSTEM_DOCUMENT_PICKER, TV_NATIVE_DURABLE }`
  - `data class BackupRecoveryTransportCapability(...)`
  - `enum class BackupRecoveryEntryAction { RESTORE_FROM_TV_NATIVE, RESTORE_FROM_SYSTEM_PICKER, CONTINUE_WITHOUT_RESTORE }`
  - `object BackupRecoveryEntryPolicy { fun actions(capabilities: List<BackupRecoveryTransportCapability>): List<BackupRecoveryEntryAction> }`

- [ ] **Step 1: Write the failing contract test**

Create `BackupRecoveryEntryPolicyTest.kt`:

```kotlin
package app.muxtv.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupRecoveryEntryPolicyTest {
    @Test
    fun `continue without restore is always available`() {
        assertThat(BackupRecoveryEntryPolicy.actions(emptyList()))
            .containsExactly(BackupRecoveryEntryAction.CONTINUE_WITHOUT_RESTORE)
            .inOrder()
    }

    @Test
    fun `app specific storage never qualifies as durable tv recovery`() {
        val actions = BackupRecoveryEntryPolicy.actions(
            listOf(
                BackupRecoveryTransportCapability(
                    kind = BackupRecoveryTransportKind.APP_SPECIFIC,
                    readableNow = true,
                    tvOperableWithoutSystemPicker = true,
                    durableOutsideAppSandbox = false,
                ),
            ),
        )

        assertThat(actions).doesNotContain(BackupRecoveryEntryAction.RESTORE_FROM_TV_NATIVE)
        assertThat(actions).contains(BackupRecoveryEntryAction.CONTINUE_WITHOUT_RESTORE)
    }

    @Test
    fun `system picker appears only when readable now`() {
        val unavailable = BackupRecoveryTransportCapability(
            kind = BackupRecoveryTransportKind.SYSTEM_DOCUMENT_PICKER,
            readableNow = false,
            tvOperableWithoutSystemPicker = false,
            durableOutsideAppSandbox = true,
        )
        val available = unavailable.copy(readableNow = true)

        assertThat(BackupRecoveryEntryPolicy.actions(listOf(unavailable)))
            .doesNotContain(BackupRecoveryEntryAction.RESTORE_FROM_SYSTEM_PICKER)
        assertThat(BackupRecoveryEntryPolicy.actions(listOf(available)))
            .contains(BackupRecoveryEntryAction.RESTORE_FROM_SYSTEM_PICKER)
    }

    @Test
    fun `tv native action requires readable picker independent durable capability`() {
        val valid = BackupRecoveryTransportCapability(
            kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
            readableNow = true,
            tvOperableWithoutSystemPicker = true,
            durableOutsideAppSandbox = true,
        )

        val actions = BackupRecoveryEntryPolicy.actions(listOf(valid))

        assertThat(actions).containsExactly(
            BackupRecoveryEntryAction.RESTORE_FROM_TV_NATIVE,
            BackupRecoveryEntryAction.CONTINUE_WITHOUT_RESTORE,
        ).inOrder()
    }

    @Test
    fun `tv native kind does not bypass missing capability properties`() {
        val candidates = listOf(
            BackupRecoveryTransportCapability(
                kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
                readableNow = false,
                tvOperableWithoutSystemPicker = true,
                durableOutsideAppSandbox = true,
            ),
            BackupRecoveryTransportCapability(
                kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
                readableNow = true,
                tvOperableWithoutSystemPicker = false,
                durableOutsideAppSandbox = true,
            ),
            BackupRecoveryTransportCapability(
                kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
                readableNow = true,
                tvOperableWithoutSystemPicker = true,
                durableOutsideAppSandbox = false,
            ),
        )

        assertThat(BackupRecoveryEntryPolicy.actions(candidates))
            .doesNotContain(BackupRecoveryEntryAction.RESTORE_FROM_TV_NATIVE)
    }

    @Test
    fun `capability diagnostics expose only safe metadata`() {
        val capability = BackupRecoveryTransportCapability(
            kind = BackupRecoveryTransportKind.TV_NATIVE_DURABLE,
            readableNow = true,
            tvOperableWithoutSystemPicker = true,
            durableOutsideAppSandbox = true,
        )

        assertThat(capability.toString()).isEqualTo(
            "BackupRecoveryTransportCapability(kind=TV_NATIVE_DURABLE, readableNow=true, " +
                "tvOperableWithoutSystemPicker=true, durableOutsideAppSandbox=true)",
        )
    }
}
```

- [ ] **Step 2: Run focused test and verify RED**

```powershell
./gradlew.bat :core:backup:test --tests "app.muxtv.backup.BackupRecoveryEntryPolicyTest" --no-daemon
```

Expected: compile/test failure because Package B transport/policy symbols do not exist. Do not proceed to production code unless this exact failure is observed.

- [ ] **Step 3: Implement the minimal policy**

Create `BackupRecoveryTransport.kt`:

```kotlin
package app.muxtv.backup

enum class BackupRecoveryTransportKind {
    APP_SPECIFIC,
    SYSTEM_DOCUMENT_PICKER,
    TV_NATIVE_DURABLE,
}

data class BackupRecoveryTransportCapability(
    val kind: BackupRecoveryTransportKind,
    val readableNow: Boolean,
    val tvOperableWithoutSystemPicker: Boolean,
    val durableOutsideAppSandbox: Boolean,
)

enum class BackupRecoveryEntryAction {
    RESTORE_FROM_TV_NATIVE,
    RESTORE_FROM_SYSTEM_PICKER,
    CONTINUE_WITHOUT_RESTORE,
}

object BackupRecoveryEntryPolicy {
    fun actions(
        capabilities: List<BackupRecoveryTransportCapability>,
    ): List<BackupRecoveryEntryAction> = buildList {
        if (
            capabilities.any {
                it.kind == BackupRecoveryTransportKind.TV_NATIVE_DURABLE &&
                    it.readableNow &&
                    it.tvOperableWithoutSystemPicker &&
                    it.durableOutsideAppSandbox
            }
        ) {
            add(BackupRecoveryEntryAction.RESTORE_FROM_TV_NATIVE)
        }
        if (
            capabilities.any {
                it.kind == BackupRecoveryTransportKind.SYSTEM_DOCUMENT_PICKER && it.readableNow
            }
        ) {
            add(BackupRecoveryEntryAction.RESTORE_FROM_SYSTEM_PICKER)
        }
        add(BackupRecoveryEntryAction.CONTINUE_WITHOUT_RESTORE)
    }
}
```

- [ ] **Step 4: Run focused test and verify GREEN**

Use the same command; expected PASS.

- [ ] **Step 5: Commit**

```bash
git add core/backup/src/test/kotlin/app/muxtv/backup/BackupRecoveryEntryPolicyTest.kt core/backup/src/main/kotlin/app/muxtv/backup/BackupRecoveryTransport.kt
git commit -m "feat: add TV recovery capability policy (#113)"
```

---

### Task 2: Restore preparation boundary

**Files:**
- Test: `core/backup/src/test/kotlin/app/muxtv/backup/BackupRestorePreparationTest.kt`
- Create after RED: `core/backup/src/main/kotlin/app/muxtv/backup/BackupRestorePreparation.kt`

**Interfaces:**
- Consumes: `PortableBackupCodec.decode(bytes)`, `BackupRestorePreviewer.preview(document, existingState)`.
- Produces:
  - `sealed interface BackupRestorePreparationResult`
  - `data class Rejected(val reason: PortableBackupRejectReason)`
  - `data class PreviewRequired(val document: PortableBackupDocument, val preview: BackupRestorePreview)`
  - `object BackupRestorePreparer { fun prepare(bytes: ByteArray, existingState: ExistingBackupState): BackupRestorePreparationResult }`

- [ ] **Step 1: Write the failing preparation tests**

Create `BackupRestorePreparationTest.kt` with a small valid Package A fixture helper and these assertions:

```kotlin
@Test
fun `valid backup always requires preview before mutation`() {
    val bytes = PortableBackupCodec.encode(validSnapshot())

    val result = BackupRestorePreparer.prepare(bytes, ExistingBackupState())

    assertThat(result).isInstanceOf(BackupRestorePreparationResult.PreviewRequired::class.java)
}

@Test
fun `conflicts remain explicit in required preview`() {
    val bytes = PortableBackupCodec.encode(validSnapshot())

    val result = BackupRestorePreparer.prepare(
        bytes,
        ExistingBackupState(profileIds = setOf("profile-1")),
    ) as BackupRestorePreparationResult.PreviewRequired

    assertThat(result.preview.requiresExplicitConflictDecision).isTrue()
    assertThat(result.preview.conflicts).hasSize(1)
}

@Test
fun `portable sources remain reauth required in preview`() {
    val bytes = PortableBackupCodec.encode(validSnapshot())

    val result = BackupRestorePreparer.prepare(
        bytes,
        ExistingBackupState(),
    ) as BackupRestorePreparationResult.PreviewRequired

    assertThat(result.preview.sourcesRequiringReauth).isEqualTo(result.preview.sourceCount)
}

@Test
fun `malformed backup is rejected without preview`() {
    val result = BackupRestorePreparer.prepare(
        byteArrayOf('{'.code.toByte()),
        ExistingBackupState(),
    )

    assertThat(result).isEqualTo(
        BackupRestorePreparationResult.Rejected(PortableBackupRejectReason.MALFORMED),
    )
}
```

Use the existing Package A model constructors exactly as defined in `PortableBackupModels.kt`; do not duplicate a second backup model in tests.

- [ ] **Step 2: Run and verify RED**

```powershell
./gradlew.bat :core:backup:test --tests "app.muxtv.backup.BackupRestorePreparationTest" --no-daemon
```

Expected: missing `BackupRestorePreparer` / result symbols.

- [ ] **Step 3: Implement minimal composition**

Create `BackupRestorePreparation.kt`:

```kotlin
package app.muxtv.backup

sealed interface BackupRestorePreparationResult {
    data class Rejected(
        val reason: PortableBackupRejectReason,
    ) : BackupRestorePreparationResult

    data class PreviewRequired(
        val document: PortableBackupDocument,
        val preview: BackupRestorePreview,
    ) : BackupRestorePreparationResult
}

object BackupRestorePreparer {
    fun prepare(
        bytes: ByteArray,
        existingState: ExistingBackupState,
    ): BackupRestorePreparationResult = when (val decoded = PortableBackupCodec.decode(bytes)) {
        is PortableBackupDecodeResult.Rejected ->
            BackupRestorePreparationResult.Rejected(decoded.reason)

        is PortableBackupDecodeResult.Success ->
            BackupRestorePreparationResult.PreviewRequired(
                document = decoded.document,
                preview = BackupRestorePreviewer.preview(decoded.document, existingState),
            )
    }
}
```

- [ ] **Step 4: Run focused test and verify GREEN**

Expected: PASS.

- [ ] **Step 5: Add diagnostic-safety regression**

Test that `Rejected.toString()` exposes only the enum reason and `PreviewRequired.toString()` does not expose payload ids/names. If default data-class `toString()` leaks `PortableBackupDocument`, replace the public result's default `toString()` with a payload-free implementation before accepting Package B.

- [ ] **Step 6: Run all `:core:backup` tests**

```powershell
./gradlew.bat :core:backup:test --no-daemon
```

Expected: all Package A + Package B tests PASS.

- [ ] **Step 7: Commit**

```bash
git add core/backup/src/test/kotlin/app/muxtv/backup/BackupRestorePreparationTest.kt core/backup/src/main/kotlin/app/muxtv/backup/BackupRestorePreparation.kt
git commit -m "feat: require preview before backup restore apply (#113)"
```

---

### Task 3: Package B repository acceptance

**Files:**
- Modify: `docs/superpowers/specs/2026-08-06-tv-first-backup-recovery-design.md`
- Modify: `docs/superpowers/plans/2026-08-06-tv-first-backup-recovery.md`
- Modify: `.work/CURRENT-STATE.md` only if Package A/B exact-head execution is actually successful and the repository uses it for current truth.

- [ ] **Step 1: Compile exact head**

```powershell
./gradlew.bat :core:backup:compileKotlin :core:backup:test --no-daemon
```

Expected: exit code 0.

- [ ] **Step 2: Run repository host regression**

```powershell
./gradlew.bat test --no-daemon
```

Expected: exit code 0. If unavailable due environment, do not substitute static review for GREEN.

- [ ] **Step 3: Static secret-boundary scan**

Review changed production files and assert there is no URI/path/file/account/provider/credential/header/locator field added to the capability/preparation domain.

- [ ] **Step 4: Diff boundary review**

Compare against accepted main and confirm Package B does not touch:

```text
core/database/**
schemas/**
player/**
catalog/refresh/**
app/tv/src/main/**
```

Package B may contain only `core/backup/**` plus #113 docs/state truth.

- [ ] **Step 5: Truth-sync #113**

Record exact head and distinguish:
- executed RED evidence;
- focused GREEN evidence;
- repository compile/test evidence;
- still-open concrete Android TV durable transport/UI/Room apply work.

- [ ] **Step 6: Commit documentation**

```bash
git add docs/superpowers/specs/2026-08-06-tv-first-backup-recovery-design.md docs/superpowers/plans/2026-08-06-tv-first-backup-recovery.md .work/CURRENT-STATE.md
git commit -m "docs: record TV-first recovery Package B evidence (#113)"
```

## Offline execution status

While the self-hosted runner is disabled, execute only the documentation and test-authoring portions. Do **not** write Task 1/2 production code until the corresponding focused test has been run and shown the expected RED.
