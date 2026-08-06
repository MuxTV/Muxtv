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

The remaining gap is in `Invoke-MeasurementSeriesCore.ps1`: every repeated host M3U run is hard-coded to `small-1k`. As a result, the current variance-series harness cannot exercise the existing 10k/50k corpus contract, and its top-level run manifest does not surface the corpus digest that makes different repetitions directly auditable.

## Objective

Make the existing sequential measurement-series harness capable of producing repeatable 10k/50k M3U parser evidence without duplicating corpus-generation logic or changing runtime product code.

## Design

1. Add explicit `-M3uProfile` with `small-1k | medium-10k | large-50k` to the public series entry point and core.
2. Add explicit deterministic `-M3uSeed`, defaulting to the existing `20260728` seed.
3. Change the manual evidence default from two repetitions to five. Keep `2..20` available for smoke/debug runs, but hard performance claims require >=5.
4. Pass profile/seed into the existing `:core:testing:measureM3uParse` task; do not generate/commit huge playlist files.
5. Name M3U reports/analysis outputs with the corpus profile to prevent accidental evidence mixing.
6. Parse every emitted M3U measurement report and fail closed unless profile, seed and source commit match the requested series.
7. Require all repetitions to report the same corpus SHA-256 and UTF-8 byte count.
8. Promote corpus identity into `measurement-series-run-manifest.json`:
   - profile;
   - seed;
   - SHA-256;
   - UTF-8 byte count;
   - expected parsed/skipped/warning counts.
9. Preserve the existing sequential execution and single-AVD lifecycle per repetition; no parallel jobs.
10. Extend the PowerShell harness contract check so future refactors cannot silently revert to `small-1k`.

## Why this is safe while the runner is offline

This package changes only evidence tooling. It does not touch parser behavior, Room, Media3, production DI, schema, Android UI or WorkManager. The existing Kotlin generator already owns corpus semantics and digest calculation.

## Validation when execution is available

### Cheap host validation

```powershell
pwsh -NoProfile -File tools/measurements/Test-MeasurementHarnessSyntax.ps1
./gradlew.bat :core:testing:test --no-daemon
```

### Determinism spot-check

Run at least two host-only `measureM3uParse` invocations for each large profile with the same seed/source commit and require identical:

- profile;
- seed;
- corpus SHA-256;
- corpus UTF-8 byte count;
- expected parser counts.

Timing samples are expected to vary; corpus identity is not.

### Full evidence series

For each Android environment profile:

- `current-normal`;
- `old-edge-normal`;
- `current-low-ram`;

run at least five repetitions for `medium-10k`, and a deliberate five-repetition `large-50k` series before structural parser/native optimization claims.

Example:

```powershell
pwsh -NoProfile -File tools/measurements/Invoke-MeasurementSeries.ps1 `
  -SourceCommit <40-char-sha> `
  -SourceBranch main `
  -ProfileId current-normal `
  -M3uProfile medium-10k `
  -Repetitions 5
```

## Acceptance

- syntax/contract script passes;
- `:core:testing:test` passes;
- exact-head measurement series records requested M3U profile/seed;
- all repetitions agree on corpus digest/byte count/expected counts;
- no threshold is introduced from a single series;
- no product runtime file changes;
- issue #27 remains open until the actual 10k/50k repeated distributions across required environments are captured and reviewed.
