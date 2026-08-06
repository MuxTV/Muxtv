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

## Why this is safe while the runner is offline

This package changes only evidence tooling. It does not touch parser behavior, Room, Media3, production DI, schema, Android UI, WorkManager or the current Android AVD lifecycle. The existing Kotlin generator remains the sole owner of corpus bytes and digest calculation.

## Validation when execution is available

### Cheap host validation

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

- syntax/contract script passes;
- `:core:testing:test` passes;
- exact-head 10k/50k series records requested profile/seed/source commit;
- all repetitions agree on corpus digest/byte count/expected counts;
- final variance report is produced by the existing analyzer;
- `thresholdApplied=false` remains explicit;
- `<5` repetitions are never marked claim-eligible;
- no product runtime file changes;
- issue #27 remains open until actual repeated distributions are captured and reviewed.
