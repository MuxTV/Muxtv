# Local validation foundation — self-review

**Branch:** `feat/local-validation-foundation`  
**Base:** `main`  
**Validation mode:** self-hosted Windows runner  
**Current verification:** the first Fast run reached Gradle and failed only on an outdated build-logic assertion; the assertion was aligned with the accepted Hilt `2.60.1` baseline in commit `c420bb751cfd65a0a540e36e669fad07a7d9655a`.

## Implemented

- Added `FocusBookmark.restoreValid` to restore TV focus only when the stable item ID still exists after catalog refresh.
- Added unit tests for replacement, valid restoration, stale-item removal and scope isolation.
- Enabled JUnit/Truth unit tests in `core:ui` without adding production dependencies.
- Updated Kotlin Coroutines from `1.10.2` to `1.11.0` as an isolated version-catalog change.
- Added `tools/verify-local.ps1` with `Fast`, `Full` and `Device` modes.
- Added a Windows self-hosted GitHub Actions workflow.
- Added ignored local evidence storage under `.work/evidence`.

## Source adaptation

The focus behavior follows the Android TV Samples principle of restoring focus against stable content identity rather than list position. The implementation is intentionally MuxTV-specific and keeps the existing lightweight `FocusBookmark` instead of copying sample navigation or focus infrastructure.

## Executed standalone verification

The production `FocusBookmark` source was compiled with the standalone Kotlin compiler and executed against four behavior scenarios equivalent to the committed unit tests:

1. replacement within one scope;
2. restoration of an available stable ID;
3. removal of a stale ID;
4. isolation between channel and guide scopes.

Result:

```text
FocusBookmark harness: 4 scenarios passed
```

This verifies the pure Kotlin API and behavior. It does not replace Android Gradle verification.

## First self-hosted run

Run: `29760311894`  
Job: `88412991545`  
Commit: `b3576640e9b293fb285317f8be614ad293d7ead3`

Environment checks passed:

- Windows X64 runner selected;
- checkout completed;
- Temurin JDK 17 available;
- Android SDK environment configured.

The run stopped at `build-logic-tests` because `ConventionFilesTest` expected obsolete Hilt `2.59.2`, while the accepted catalog already used `2.60.1`. This was a stale test invariant, not a runner or production-code failure.

Fix commit: `c420bb751cfd65a0a540e36e669fad07a7d9655a`.

## Static review findings

1. `core:ui` retains its existing production dependency boundary; only test-scoped JUnit and Truth were added.
2. `restoreValid` performs no fallback guessing. A stale ID is removed and returns `null`, leaving the caller to choose deterministic initial focus.
3. Scope isolation is preserved because removal targets only the requested scope.
4. The verification script fails fast, preserves each Gradle step log and writes command exit codes to `manifest.json`.
5. Fast verification includes build-logic tests, two configuration-cache runs, pure Kotlin tests, Android unit tests and debug APK assembly.
6. Full mode adds lint and release assembly; Device mode adds connected instrumentation tests.
7. The self-hosted workflow uses Windows/X64 labels and does not consume GitHub-hosted runner minutes.

## Required completion evidence

The work package is complete only when the latest commit has:

- successful `Fast` validation on branch push;
- successful `Full` validation on the draft PR;
- uploaded evidence manifest with `status: passed`;
- no unresolved failures in build-logic, unit tests, lint or assembly.

Do not merge before these conditions are met.
