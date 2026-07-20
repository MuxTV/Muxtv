# Local verification evidence

GitHub Actions minutes are currently exhausted, so implementation branches use local Gradle verification as the temporary quality gate.

Run from the repository root on Windows:

```powershell
pwsh -File .\tools\verify-local.ps1 -Mode Fast -NoDaemon
pwsh -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
pwsh -File .\tools\verify-local.ps1 -Mode Device -NoDaemon
```

Modes:

- `Fast`: toolchain, configuration cache, pure Kotlin tests, focused Android unit tests and debug APK.
- `Full`: `Fast` plus Android lint and release assembly.
- `Device`: `Full` plus connected database and application instrumentation tests. An emulator or physical device must already be available through `adb`.

Each run creates a timestamped directory containing:

- `manifest.json` with branch, commit, commands, durations and exit codes;
- one plain-text log per verification step.

Generated evidence directories are intentionally ignored by Git. Commit only concise manually reviewed summaries when an implementation decision needs durable evidence. Do not commit APKs, full Gradle caches, secrets, playlist credentials or large device logs.

A connector-only code change is not considered runtime-verified until this script (or the equivalent commands) has completed successfully on a machine with the Android SDK and JDK 17.
