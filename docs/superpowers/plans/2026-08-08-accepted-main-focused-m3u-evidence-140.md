# Accepted-main focused M3U evidence lane — #140

**Issue:** #140  
**Branch state:** RED-only preparation, no PR/CI until #134 is accepted  
**Product runtime impact:** none

## Goal

Turn the focused M3U series harness accepted through #134 into reproducible claim-eligible evidence on the actual accepted `main` commit, without running expensive 5×10k + 5×50k series on every pull request.

## Execution topology

The dedicated workflow should have only:

- `workflow_dispatch` for explicit reruns of an accepted ref;
- `push` to `main` scoped to the focused workflow/harness paths so merging #140 itself produces the first accepted-main dataset;
- one self-hosted Windows X64 job;
- one concurrency group with `cancel-in-progress: false`.

The workflow must **not** have a `pull_request` trigger. PR acceptance remains the existing host/static harness. The expensive focused dataset is intentionally a post-merge accepted-main evidence lane.

## TDD 1 — focused interrupted finalizer

Current production finalizer only discovers `measurement-series-run-manifest.json`; focused series writes `m3u-series-run-manifest.json`.

### RED

`Test-M3uSeriesFinalizerContract.ps1` creates focused fixtures for `running`, `passed`, `failed` and already-`interrupted` states. The running fixture contains one completed repetition plus corpus identity.

It invokes the existing finalizer and requires:

- running -> interrupted;
- non-empty completion timestamp;
- partial run evidence preserved;
- corpus identity preserved;
- passed/failed/already-interrupted manifests unchanged.

Current production must RED because it never discovers the focused manifest.

### GREEN

Refactor `Finalize-MeasurementSeriesEvidence.ps1` into bounded schema-aware handling:

- preserve existing general-series behavior;
- discover focused manifests separately;
- update only focused `running` state;
- do not assign general-manifest-only fields blindly to a focused `PSCustomObject`;
- stage the replacement in the same directory and replace the original;
- preserve partial evidence fields.

`status=interrupted` is already the bounded terminal marker needed for this first GREEN. Do not introduce a second failure-code schema merely to solve discovery/finalization.

## TDD 2 — atomic focused manifest publication

Current `Write-M3uSeriesManifest` rewrites the live JSON through direct `Set-Content`. A runner/process termination during that write can truncate the only manifest, after which the finalizer cannot recover its state.

### RED

Add a repository-owned contract that rejects direct in-place publication for the focused run manifest and requires a same-directory stage/replace path.

### GREEN

Change only focused manifest publication:

1. serialize the complete next JSON before replacing the live path;
2. write to a deterministic/safely unique stage file in the same series directory;
3. move/replace the stage file over `m3u-series-run-manifest.json` without deleting the previous valid manifest first;
4. best-effort remove an abandoned stage file in `finally`;
5. preserve current manifest schema/fields.

The focused finalizer uses the same durability principle when publishing `interrupted` state.

Do not combine this with parser/report schema changes.

## TDD 3 — workflow contract

Before creating the workflow, extend repository static harness tests to require:

- dedicated focused workflow exists;
- `workflow_dispatch` exists;
- `push` is scoped to `main` and relevant focused harness/workflow paths;
- no `pull_request` trigger;
- self-hosted Windows X64 runner;
- `cancel-in-progress: false`;
- checkout ref is `${{ github.sha }}`;
- repository cleanup occurs before evidence;
- `Assert-EvidenceCommit.ps1` runs before focused series;
- fixed seed `20260728`;
- exactly `Repetitions 5` for `medium-10k`;
- exactly `Repetitions 5` for `large-50k`;
- medium series precedes large series;
- no parallel PowerShell/job matrix for the two profiles;
- finalizer step uses `if: always()`;
- artifact upload uses `if: always()` and includes JSON/log evidence.

Observe RED before adding the workflow.

## GREEN workflow order

1. checkout exact `${{ github.sha }}` with `clean: false`;
2. explicit long-path reset/clean, matching accepted CI provenance hygiene;
3. `Assert-EvidenceCommit.ps1 -ExpectedCommit ${{ github.sha }}`;
4. inspect runner/JDK environment;
5. `Test-MeasurementHarnessSyntax.ps1`;
6. `Invoke-M3uCorpusSeries.ps1` medium-10k, seed 20260728, repetitions 5, controlled runner label, `-NoDaemon`;
7. `Invoke-M3uCorpusSeries.ps1` large-50k with identical seed/repetition/runner class;
8. `Finalize-MeasurementSeriesEvidence.ps1 -EvidenceRoot .work/evidence` under `if: always()`;
9. upload `.work/evidence/**/*.json` and logs under `if: always()`.

## Existing analyzer ownership

Do not add a PowerShell statistics implementation.

`MeasurementSeriesCommand` already:

- consumes exactly request-listed reports;
- adapts and identity-checks each input;
- writes variance JSON;
- writes a sibling audit manifest;
- records `seriesCount`;
- records each input report name and SHA-256;
- records identity fingerprint SHA-256;
- records variance-report SHA-256;
- atomically publishes the variance/audit pair.

If audit publication fails after variance publication, the analyzer removes the variance file and returns publication failure. Therefore successful wrapper completion plus variance existence already implies the paired audit publication path succeeded. Review still validates the audit contents explicitly.

## Accepted-main evidence review

For each profile (`medium-10k`, `large-50k`) require:

- series manifest `status=passed`;
- `claimEligible=true`;
- `thresholdApplied=false`;
- commit equals the accepted workflow checkout SHA;
- seed is exactly 20260728;
- repetitions is exactly 5;
- five run records exist;
- all five run reports exist;
- corpus SHA-256 identical across repetitions;
- byte count identical across repetitions;
- expected parsed/skipped/warning counts identical;
- analyzer request lists exactly those five reports;
- audit `seriesCount=5`;
- audit input names/hashes match the five reports;
- audit variance SHA matches the published variance JSON;
- identity fingerprint is stable for the series.

## Environment comparability review

Each M3U report already records real environment fields. Compare across all five repetitions:

- OS name/version/architecture;
- JVM vendor/version/runtime;
- available processor count;
- max heap;
- allocation measurement mode.

Runner label is descriptive metadata, not sufficient proof by itself. If material environment fields drift during one dataset, mark the dataset non-comparable and rerun rather than hiding the drift.

## Statistical interpretation

This lane remains descriptive:

- report p50/p90/p95/max and allocation summaries already produced by the measurement report/analyzer;
- report variance/distribution first;
- do not create a regression threshold from one series;
- do not claim parser optimization need until repeated evidence identifies a material, reproducible bottleneck.

## Post-#140 gates

1. #27 evidence review determines whether parser work is justified.
2. #139 separately adds tracked-dirty-worktree rejection for claim-eligible manual evidence.
3. #30A pure recovery policy may proceed independently, but product retry/deadline defaults require playback/network/device evidence rather than M3U parse timing alone.

## Stop conditions

Stop rather than publish claim-eligible evidence if:

- checked-out SHA differs from manifest source commit;
- tracked source is dirty for a manual claim-eligible run;
- focused manifest remains `running` after interrupted finalization;
- focused manifest publication can truncate the only valid JSON in place;
- either profile has fewer/more than five accepted reports;
- corpus identity changes across repetitions;
- analyzer audit does not bind exactly the intended five inputs;
- environment materially changes within the series;
- workflow runs profiles in parallel;
- a threshold is introduced before repeated variance review;
- the implementation touches parser/Room/Media3/UI behavior.
