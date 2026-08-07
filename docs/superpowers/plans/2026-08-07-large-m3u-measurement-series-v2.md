# Large deterministic M3U measurement-series v2 plan

**Issue:** #27  
**Base:** accepted `main@ef9f008a17e5e8fb8519d8e0bc05446ede675a99`  
**Execution constraint:** self-hosted Windows runner remains unavailable; this branch prepares evidence tooling and expected RED contracts only.

## Objective

Provide a focused, sequential, deterministic M3U parser evidence lane for `small-1k`, `medium-10k`, and `large-50k` corpora without changing product runtime code or duplicating the accepted corpus generator/analyzer.

## Restack state

- [x] Confirm historical `work/measurement-large-m3u-series-27@9afa284453ea6d23c94d12a07aae9581670adf0a` has only three unique paths versus current main.
- [x] Create `work/measurement-large-m3u-series-27-v2` from exact current main.
- [x] Port `Invoke-M3uCorpusSeries.ps1`.
- [x] Port the `Test-MeasurementHarnessSyntax.ps1` contract delta.
- [x] Keep parser/runtime/Room/Media3/Android lifecycle unchanged.

## Authored focused-series contract

`Invoke-M3uCorpusSeries.ps1` currently:

- accepts `small-1k | medium-10k | large-50k`;
- defaults to `medium-10k`, fixed seed `20260728`, five repetitions;
- marks fewer than five repetitions as not claim-eligible;
- executes repetitions sequentially;
- reuses `:core:testing:measureM3uParse`;
- validates profile, seed, exact source commit, measured iteration count, threshold flag, and failure count;
- requires the same corpus SHA-256, byte count, parsed/skipped/warning counts for every repetition;
- reuses `:core:testing:analyzeMeasurementSeries` for variance output;
- keeps `thresholdApplied=false`.

No timing/performance claim is made until real repetitions execute.

## Expected first RED: evidence-directory ownership

The current script constructs an evidence directory using a timestamp with one-second precision plus commit/profile, then creates subdirectories using `New-Item -Force`. Two invocations in the same second can therefore reuse/mix evidence.

The restacked harness already requires this production token:

```text
M3U series evidence directory already exists.
```

The production script intentionally does not contain it yet.

### First runner-return command

```powershell
pwsh -NoProfile -File tools/measurements/Test-MeasurementHarnessSyntax.ps1
```

Expected RED: the harness reports that `Invoke-M3uCorpusSeries.ps1` is missing the collision-guard contract token.

Do not add the production guard before this RED is captured.

## Minimal GREEN after observed RED

Immediately after the expected RED:

1. check `Test-Path $seriesDirectory` before creating any input/output/request subdirectory;
2. if it already exists, throw exactly:
   `M3U series evidence directory already exists.`;
3. do not delete, overwrite, merge, or silently reuse an existing evidence directory;
4. rerun the syntax contract.

Do not solve this by introducing random report identities that make evidence harder to compare.

## Second evidence-integrity gate: analyzer ownership

The current wrapper only checks that the requested variance JSON exists after analyzer success. Before any performance conclusion:

1. inspect the existing analyzer JSON schema/tests;
2. identify existing fields that bind output to family/run identities/count;
3. add a focused test-first assertion that the variance report belongs to the current request/repetition set;
4. reuse the existing analyzer schema rather than inventing a second PowerShell statistics schema.

## Runner-return validation sequence

```powershell
pwsh -NoProfile -File tools/measurements/Test-MeasurementHarnessSyntax.ps1
./gradlew.bat :core:testing:test --no-daemon
```

Then exact-head focused evidence:

```powershell
pwsh -NoProfile -File tools/measurements/Invoke-M3uCorpusSeries.ps1 `
  -SourceCommit <exact-40-char-head> `
  -SourceBranch work/measurement-large-m3u-series-27-v2 `
  -M3uProfile medium-10k `
  -Repetitions 5 `
  -RunnerLabel self-hosted-windows-x64-v1
```

Repeat with `-M3uProfile large-50k -Repetitions 5`.

## Acceptance

- collision ownership goes through observed RED -> minimal GREEN;
- syntax and `:core:testing:test` pass on the same exact head;
- no evidence directory is implicitly reused;
- 10k and 50k five-run series agree on deterministic corpus identity;
- variance output is validated as belonging to the current request;
- `thresholdApplied=false` remains explicit;
- no product runtime file changes;
- #27 remains open until broader normal/old-edge/low-RAM repeated evidence is captured and reviewed.

## Current status

This v2 branch is a restacked, unexecuted evidence package. It is not performance evidence and must not be called syntax-green, benchmark-green, or merge-ready before the commands above run.
