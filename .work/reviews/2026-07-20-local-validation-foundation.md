# Local validation foundation — self-review

**Branch:** `feat/local-validation-foundation`  
**Base:** `plan/reference-adoption-local-validation`  
**Runtime verification:** pending local Android/Gradle execution; GitHub Actions quota is exhausted and the connector environment cannot clone GitHub or run the Android toolchain.

## Implemented

- Added `FocusBookmark.restoreValid` to restore TV focus only when the stable item ID still exists after catalog refresh.
- Added unit tests for replacement, valid restoration, stale-item removal and scope isolation.
- Enabled JUnit/Truth unit tests in `core:ui` without adding production dependencies.
- Updated Kotlin Coroutines from `1.10.2` to `1.11.0` as an isolated version-catalog change.
- Added `tools/verify-local.ps1` with `Fast`, `Full` and `Device` modes.
- Added ignored local evidence storage under `.work/evidence`.

## Source adaptation

The focus behavior follows the Android TV Samples principle of restoring focus against stable content identity rather than list position. The implementation is intentionally MuxTV-specific and keeps the existing lightweight `FocusBookmark` instead of copying sample navigation or focus infrastructure.

## Static review findings

1. `core:ui` retains its existing production dependency boundary; only test-scoped JUnit and Truth were added.
2. `restoreValid` performs no fallback guessing. A stale ID is removed and returns `null`, leaving the caller to choose deterministic initial focus.
3. Scope isolation is preserved because removal targets only the requested scope.
4. The verification script fails fast, preserves each Gradle step log and writes command exit codes to `manifest.json`.
5. Fast verification includes build-logic tests, two configuration-cache runs, pure Kotlin tests, Android unit tests and debug APK assembly.
6. Full mode adds lint and release assembly; Device mode adds connected instrumentation tests.
7. The existing workflow triggers pushes only for `feat/phase-00-foundation`; this implementation branch does not consume Actions minutes unless a PR to `main` is opened.

## Required runtime evidence

Run on Windows with JDK 17 and Android SDK configured:

```powershell
pwsh -File .\tools\verify-local.ps1 -Mode Fast -NoDaemon
```

Before merging the branch, also run:

```powershell
pwsh -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

For Room instrumentation and app device tests:

```powershell
pwsh -File .\tools\verify-local.ps1 -Mode Device -NoDaemon
```

Do not mark this work package runtime-verified until the generated `manifest.json` reports `status: passed` for the required mode.
