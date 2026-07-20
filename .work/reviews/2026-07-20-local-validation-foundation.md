# Local validation foundation — self-review

**Branch:** `feat/local-validation-foundation`  
**Base:** `main`  
**Validation mode:** Windows X64 self-hosted runner  
**Merge policy:** the latest PR head must pass `Full` validation and publish a `status: passed` evidence manifest.

## Implemented

- Added `FocusBookmark.restoreValid` to restore TV focus only when the remembered stable item ID still exists after catalog refresh.
- Added unit tests for replacement, valid restoration, stale-item removal and scope isolation.
- Enabled JUnit and Truth tests in `core:ui` without adding production dependencies.
- Updated Kotlin Coroutines from `1.10.2` to `1.11.0`.
- Declared Coroutines as an `api` dependency of `catalog:api` because `Flow` is part of its public contract.
- Added `tools/verify-local.ps1` with `Fast`, `Full` and `Device` modes.
- Added a Windows self-hosted GitHub Actions workflow and evidence artifact upload.
- Changed the obsolete Phase 00 hosted workflow to manual-only.
- Added source branch/head provenance parameters so PR evidence does not identify only GitHub's detached merge ref.
- Moved `android:windowLightNavigationBar` to an API 27 resource variant while preserving minSdk 26.

## Source adaptation

The focus behavior follows the Android TV Samples principle of restoring focus against stable content identity rather than list position. The implementation is intentionally MuxTV-specific and keeps the existing lightweight `FocusBookmark` instead of copying sample navigation or focus infrastructure.

## Standalone behavior verification

The production `FocusBookmark` source was compiled with the standalone Kotlin compiler and executed against four scenarios equivalent to the committed unit tests:

1. replacement within one scope;
2. restoration of an available stable ID;
3. removal of a stale ID;
4. isolation between channel and guide scopes.

```text
FocusBookmark harness: 4 scenarios passed
```

## Self-hosted failure history

### Run 29760311894

The runner, checkout, JDK 17 and Android SDK were valid. `build-logic-tests` exposed a stale assertion that expected Hilt `2.59.2` while the accepted catalog used `2.60.1`.

Fix: `c420bb751cfd65a0a540e36e669fad07a7d9655a`.

### Run 29761751552

Build-logic and configuration-cache gates passed. `pure-kotlin-tests` exposed that `catalog:api` publicly returned `Flow` without exporting Coroutines.

Fixes: `6de6dcda3d968ccdf73d84a1d96239d41cca697a` and corrected catalog accessor `62a555b3474db9db50ffdaa037a327c4a0081258`.

### Run 29762946729

Build-logic, both configuration-cache runs, pure Kotlin tests, Android unit tests and debug APK assembly passed. Android lint found an API 27 navigation-bar attribute in the API 26 resource set.

Fixes: `f685bc9f47dc735b77d6dc5463a5bc4bd23af6a0` and `ca91ac697e475332aa8771185f031f5e27ac24bd`.

## Successful Full evidence

Run: `29763421755`  
Job: `88423503937`  
Source head: `ca91ac697e475332aa8771185f031f5e27ac24bd`  
Artifact: `self-hosted-validation-29763421755-1`  
Artifact digest: `sha256:69c036a5c8b4c586cac09c6af1aaa6a8c10574005c5421061dd5cc18cc306122`  
Manifest status: `passed`

Passed gates:

- Gradle/JDK inspection;
- convention build-logic tests;
- configuration-cache creation and reuse;
- pure Kotlin tests;
- Android unit tests;
- debug APK assembly;
- Android lint;
- release APK assembly.

The manifest covered the PR merge ref, so commits `3fa0af22d6a128e49d2052182693a1c0309f8098` and `f1b70c958c728404822ed2ba9ee2cc5b157bc8d1` added explicit source provenance. The latest PR check must verify those infrastructure-only changes before merge.

## Static review findings

1. `restoreValid` performs no fallback guessing. A stale ID is removed and returns `null`, leaving deterministic initial-focus selection to the caller.
2. Scope isolation is preserved because removal targets only the requested scope.
3. `catalog:api` now correctly exports the library required by its public `Flow` signature.
4. The verification script fails fast, preserves one log per step and writes exit codes and source provenance to `manifest.json`.
5. The self-hosted workflow runs one automatic Full gate per PR; Fast and Device modes remain manually selectable.
6. No lint baseline or suppression was introduced to hide the API-level problem.
7. The workflow uses self-hosted Windows/X64 capacity and no longer duplicates the legacy GitHub-hosted Phase 00 check.

## Residual scope

Connected device tests are not required for this bounded pure-Kotlin focus and CI package. They remain mandatory when Room instrumentation, TV interaction flows or playback/device behavior changes.
