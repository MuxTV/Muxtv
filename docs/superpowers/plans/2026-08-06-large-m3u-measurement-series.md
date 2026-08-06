# Large deterministic M3U measurement-series plan

**Issue:** #27  
**Base:** accepted `main@ec2b7743183b227ef54c16989d061ae5d4775dee`  
**Execution constraint:** self-hosted runner unavailable while this package is authored. The change prepares evidence generation; it does not claim new performance evidence.

## Finding

The repository already has the correct deterministic corpus primitive:

- `small-1k` = 1,000 entries;
- `medium-10k` = 10,000 entries;
- `large-50k` = 50,000 entries;
- fixed seed support;
- exact UTF-8 byte count and SHA-256 in the generated manifest;
- parser measurements verify parsed/skipped/warning counts against that manifest.

The remaining gap is that `Invoke-MeasurementSeriesCore.ps1` hard-codes every repeated host M3U run to `small-1k`. The existing full Android variance harness therefore cannot directly produce a focused, auditable 10k/50k parser series, and its top-level run manifest does not promote the corpus digest for easy cross-repetition inspection.

## Objective

Add a focused sequential M3U evidence entry point for 1k/10k/50k corpora without duplicating corpus generation, changing product runtime code, or perturbing the existing Android measurement-series lifecycle while that lifecycle is already established.

## Design

1. Add `tools/measurements/Invoke-M3uCorpusSeries.ps1` with explicit `small-1k | medium-10k | large-50k` selection.
2. Default the focused evidence command to `medium-10k` and five repetitions; retain `2..20` for smoke/debug, while the manifest marks `<5` repetitions as not claim-eligible.
3. Expose deterministic `-M3uSeed`, defaulting to the existing `20260728` seed.
4. Reuse `:core:testing:measureM3uParse`; do not generate or commit huge playlist files.
5. Reuse `:core:testing:analyzeMeasurementSeries` for the final threshold-free variance report rather than creating a second statistics implementation.
6. Parse every emitted M3U measurement report and fail closed unless profile, seed, source commit, measured iteration count, threshold flag and failure count match the requested series.
7. Require all repetitions to report the same corpus SHA-256, UTF-8 byte count and expected parsed/skipped/warning counts.
8. Promote corpus identity into `m3u-series-run-manifest.json`:
   - profile;
   - seed;
   - SHA-256;
   - UTF-8 byte count;
   - expected parsed/skipped/warning counts;
   - repetition count and claim-eligibility marker.
9. Keep execution strictly sequential; forbid PowerShell job/parallel primitives.
10. Extend `Test-MeasurementHarnessSyntax.ps1` so the new entry point is syntax-parsed and its profile/digest/sequential contract cannot silently disappear.
11. Leave `Invoke-MeasurementSeriesCore.ps1` unchanged in this package. Integrating large M3U selection into the combined Android series can be considered after focused evidence exists; there is no reason to destabilize the accepted AVD harness while the runner is unavailable.

## Offline adversarial evidence review

Static review found two evidence-integrity concerns before the first real 10k/50k series is trusted.

### 1. Series-directory collision must fail closed

The current entry point derives the evidence directory from UTC time with one-second precision, short commit and profile, then creates it with `New-Item -Force`. Two invocations with the same commit/profile inside the same timestamp second can therefore reuse the same directory. That makes old/new reports indistinguishable and can contaminate an otherwise deterministic evidence package.

A historical test-only update to `Test-MeasurementHarnessSyntax.ps1` now requires the future guard token:

```text
M3U series evidence directory already exists.
```

The production series script is deliberately unchanged while the runner is unavailable. When execution returns, the syntax/contract test should first fail because that guard does not exist. Minimal GREEN should check `Test-Path $seriesDirectory` before creating any subdirectory and throw the exact fail-closed diagnostic rather than reusing/deleting evidence.

Do not solve this by silently adding random UUIDs to the report identity: deterministic run metadata should remain inspectable. A higher-resolution timestamp or explicit run id may be added later, but an already-existing selected output directory must never be reused implicitly.

### 2. Analyzer output must be validated, not merely exist

The current wrapper treats the series as passed when `analyzeMeasurementSeries` exits 0 and the requested variance JSON path exists. Before any performance claim, review the analyzer's output schema and add fail-closed checks that the produced analysis belongs to the current request/run set. At minimum, validation should bind the report to the expected family/output and number/identity of repetitions using fields the existing analyzer already emits.

Do not invent a second statistics schema in the PowerShell wrapper. First inspect the analyzer's actual JSON contract/tests, then add a test-first wrapper assertion against those existing fields.

## Why this is safe while the runner is offline

This package changes only evidence tooling. It does not touch parser behavior, Room, Media3, production DI, schema, Android UI, WorkManager or the current Android AVD lifecycle. The existing Kotlin generator remains the sole owner of corpus bytes and digest calculation.

## Validation when execution is available

### First RED: evidence directory ownership

```powershell
pwsh -NoProfile -File tools/measurements/Test-MeasurementHarnessSyntax.ps1
```

Expected on the current test-only head: failure because `Invoke-M3uCorpusSeries.ps1` does not yet contain the required fail-closed collision guard. Do not add the production guard until this RED is observed.

### Cheap host validation after minimal GREEN

```powershell
pwsh -NoProfile -File tools/measurements/Test-MeasurementHarnessSyntax.ps1
./gradlew.bat :core:testing:test --no-daemon
```

### Focused 10k series

```powershell
pwsh -NoProfile -File tools/measurements/Invoke-M3uCorpusSeries.ps1 `
  -SourceCommit <40-char-sha> `
  -SourceBranch main `
  -M3uProfile medium-10k `
  -Repetitions 5 `
  -RunnerLabel self-hosted-windows-x64-v1
```

### Focused 50k series

Repeat with `-M3uProfile large-50k -Repetitions 5`. Timing samples are expected to vary; corpus SHA-256/byte count/expected counts must not.

### Broader #27 evidence

The focused M3U series is only one lane. #27 remains open until the repository also has reviewed repeated evidence for the existing `current-normal`, `old-edge-normal` and `current-low-ram` Android measurement profiles. Do not infer Android low-RAM/device behavior from host parser timing.

## Acceptance

- syntax/contract script passes after an observed RED/GREEN cycle for the directory-ownership guard;
- `:core:testing:test` passes;
- selected evidence directory is never implicitly reused;
- exact-head 10k/50k series records requested profile/seed/source commit;
- all repetitions agree on corpus digest/byte count/expected counts;
- final variance report is produced by the existing analyzer and validated against the current request/run set;
- `thresholdApplied=false` remains explicit;
- `<5` repetitions are never marked claim-eligible;
- no product runtime file changes;
- issue #27 remains open until actual repeated distributions are captured and reviewed.

## Offline status

The new directory-ownership assertion is test-only and unexecuted. No syntax-pass, RED, GREEN or performance claim is made while the runner is unavailable.
