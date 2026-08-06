# TV-first Backup Recovery Design — #113 Package B

## Status

Runner-independent design for issue #113 Package B. Package A already defines the canonical portable non-secret envelope, fail-closed decoder and pre-apply preview in `:core:backup`. Package B defines the recovery-entry and transport-capability contract that must sit in front of any Android storage implementation.

This package intentionally does **not** mutate Room, export credentials, implement a system picker, implement Google Drive, start a local HTTP server, or claim that any Android transport is accepted before device evidence.

## Goal

Prevent MuxTV from presenting a recovery option that is unusable on TV, depends silently on a touch-oriented picker, disappears with app uninstall while being described as durable backup, or bypasses the mandatory decode/preview/conflict/reauthentication boundary from Package A.

## Platform facts that constrain the design

Official Android storage guidance distinguishes app-specific storage from shared documents:

- app-specific internal/external files are removed when the app is uninstalled and therefore must not be treated as user backup expected to persist independently of the app;
- shared documents outside app-specific storage can survive uninstall, but ordinary document access is mediated by the Storage Access Framework/system picker;
- `MediaStore.Downloads` does not provide a general permission-free way for a reinstalled app to read arbitrary backup documents created by a previous installation.

Therefore an app-specific file path is useful only as an implementation detail/cache/export staging area, not as the sole durable recovery promise. SAF may remain an optional adapter, but issue #113 requires at least one TV-operable recovery path that is not dependent on the picker.

## Alternatives reviewed

### A. App-specific backup directory as the primary recovery path — rejected

Advantages:
- simple API;
- no picker;
- easy D-pad UI.

Failure:
- app-specific files are removed on uninstall, so this does not satisfy a durable recovery promise and is especially weak for reinstall/cross-device recovery.

### B. SAF as the only durable path — rejected

Advantages:
- system-managed user permission;
- documents survive uninstall;
- works with USB/cloud `DocumentsProvider` roots where the system UI is usable.

Failure:
- the product requirement explicitly forbids trapping Android TV behind a touch-oriented/unavailable picker. SAF can be offered only after runtime capability/UX validation and cannot be the sole recovery action.

### C. Capability-driven recovery transports — selected

Define a small pure contract in `:core:backup` that describes transport capabilities without implementing Android I/O. The TV UI/recovery coordinator consumes only transports whose declared capability matches the required recovery role. A future Android adapter can implement SAF, removable storage, companion transfer or another reviewed mechanism without changing the portable envelope or restore policy.

This approach keeps the security and preview contract stable while allowing the concrete TV transport to be chosen from device evidence instead of guessed in advance.

## Architecture

Package B adds two pure responsibilities to `:core:backup`:

1. `BackupRecoveryTransport.kt` — immutable capability metadata and policy for which recovery actions may be offered.
2. `BackupRestorePreparation.kt` — a pure preparation boundary that takes already-loaded bytes, decodes Package A, creates a bounded preview and **always** stops before mutation.

Android transport adapters are outside `:core:backup` and outside this package.

## Transport capability model

A transport descriptor has only safe metadata:

```kotlin
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
```

The descriptor contains no path, URI, provider name, account id, volume label or file name.

`TV_NATIVE_DURABLE` is deliberately a capability class, not approval of a concrete implementation. A future adapter earns that classification only after its own threat model and Android TV device evidence.

## Recovery entry policy

`BackupRecoveryEntryPolicy.actions(capabilities)` returns a stable set of user-visible action classes:

```kotlin
enum class BackupRecoveryEntryAction {
    RESTORE_FROM_TV_NATIVE,
    RESTORE_FROM_SYSTEM_PICKER,
    CONTINUE_WITHOUT_RESTORE,
}
```

Rules:

- `CONTINUE_WITHOUT_RESTORE` is always present; recovery must never trap first-run onboarding.
- `RESTORE_FROM_TV_NATIVE` is present only when at least one capability is `readableNow`, `tvOperableWithoutSystemPicker` and `durableOutsideAppSandbox`.
- `RESTORE_FROM_SYSTEM_PICKER` is present only when a readable system-picker capability is actually detected.
- `APP_SPECIFIC` can never satisfy `RESTORE_FROM_TV_NATIVE`, even if it is readable without a picker, because it is not durable outside the app sandbox.
- no action exposes transport-specific identifiers in `toString()`/diagnostics.

This means Package B can express “no accepted durable TV-native transport is available yet” truthfully instead of pretending app-private storage solves the requirement.

## Restore preparation boundary

`BackupRestorePreparer.prepare(bytes, existingState)` composes Package A only:

1. decode canonical bytes through `PortableBackupCodec.decode`;
2. on failure, return a typed rejection using the existing `PortableBackupRejectReason`;
3. on success, build `BackupRestorePreview` through `BackupRestorePreviewer`;
4. return `PreviewRequired` with the decoded document and preview;
5. perform no local mutation.

Public result:

```kotlin
sealed interface BackupRestorePreparationResult {
    data class Rejected(
        val reason: PortableBackupRejectReason,
    ) : BackupRestorePreparationResult

    data class PreviewRequired(
        val document: PortableBackupDocument,
        val preview: BackupRestorePreview,
    ) : BackupRestorePreparationResult
}
```

There is intentionally no `Applied`, `Success`, `Overwrite`, `Merge` or automatic mutation result in Package B.

## Confirmation and conflict invariants

Every successfully decoded backup produces `PreviewRequired`, even when there are no id conflicts. The UI must show the preview and require an explicit user continuation before a future applier is invoked.

When `preview.requiresExplicitConflictDecision == true`, Package C must require an explicit conflict policy in addition to the normal preview confirmation. Package B does not define overwrite/merge semantics.

All decoded sources remain `REAUTH_REQUIRED` because Package A cannot represent credentials/access locators. Package B does not create a “healthy restored source” state.

## Input bounds

Package B does not invent a second byte-size limit. Any transport adapter must stop loading at `PortableBackupLimits.MAX_DOCUMENT_BYTES` plus at most one sentinel byte and pass the resulting bounded bytes to Package A. The pure preparer relies on Package A's existing pre-parse size rejection.

A future Android streaming adapter must not call an unbounded `readBytes()` on an arbitrary `ContentResolver`/file/network source.

## Diagnostics/privacy

Capability/result `toString()` output may include only:

- capability kind;
- booleans;
- reject reason;
- preview counts already proven payload-free by Package A.

It must not contain:

- URI/path/file name;
- provider/account/volume identity;
- profile/source/channel ids or names;
- raw backup bytes;
- credential/access material;
- exception messages from transport implementations.

## Test-first contract while runner is unavailable

Author tests before production Package B types:

- continue-without-restore is always available;
- app-specific readable storage never qualifies as durable TV-native recovery;
- unavailable picker is not offered;
- readable picker may be offered as an optional action;
- only a readable + picker-independent + durable capability enables TV-native restore;
- capability diagnostics contain no user-supplied identifier surface;
- valid canonical backup always returns `PreviewRequired`, never direct apply;
- malformed/tampered/oversized input returns typed `Rejected`;
- conflicts remain visible in preview;
- all restored sources remain counted as requiring re-authentication;
- preparation has no mutation dependency/API.

Because the runner is unavailable, authored test-only commits are not observed RED and must not be described as such.

## Package B completion boundary

Package B is complete only after the test-only head has shown the expected RED, minimal production types make focused `:core:backup:test` GREEN, and repository compile is fresh.

Even after Package B is accepted, issue #113 remains open for:

- a concrete Android TV durable transport independent of a touch-only picker;
- optional SAF adapter with capability detection;
- bounded transport byte loading;
- first-run/Settings TV UI and focus journeys;
- transactional Room apply/rollback/conflict policy;
- same-device end-to-end export/import;
- any future authenticated portable secret mechanism.
