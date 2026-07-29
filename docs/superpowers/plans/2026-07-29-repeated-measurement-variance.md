# Repeated Measurement Variance Plan

> **Status:** next issue #27 package after PR #56/#57.

## Goal

Run comparable M3U parse, Room and Player proxy series across repeated current, old-edge and current low-RAM profiles; calculate cross-series variation; decide explicitly whether any dedicated regression threshold is statistically and operationally justified.

## Non-goals

- no production optimization in the evidence PR;
- no parser, Room schema/index/batch, Player ownership or engine change;
- no codec, first-frame, zapping, Fire OS or physical weak-TV claim;
- no threshold derived from one run, one emulator profile or mixed environments.

## Package A — common series identity

### Required report fields

- schema and method version;
- measurement family: `m3u-parse`, `catalog-database`, `player-proxy`;
- exact source commit;
- deterministic fixture/profile SHA;
- runner series ID and repetition index;
- Android/JVM environment fingerprint;
- API, ABI, configured RAM/CPU, low-RAM flag and memory class;
- build mode and cache state;
- warmups, measured iterations and operations per sample;
- raw samples and existing percentile summaries;
- `thresholdApplied=false`.

### Environment fingerprint

Build a stable SHA-256 from bounded normalized fields. A comparison is valid only when the expected comparison group matches:

- family/method version;
- fixture/profile SHA;
- API/system image/ABI;
- configured RAM/CPU and low-RAM flag;
- build mode;
- workload shape.

Do not include volatile paths, timestamps, runner names or secrets in the fingerprint.

## Package B — repository series orchestrator

Add a PowerShell command that:

1. accepts source commit, profile (`current`, `old-edge`, `current-low-ram`), repetition count and output root;
2. provisions one AVD at a time through the existing repository harness;
3. runs selected measurement families sequentially;
4. guarantees emulator shutdown between profiles;
5. validates every child report before accepting it;
6. writes one bounded series manifest with report basenames and SHA-256 values;
7. never treats a measurement value as pass/fail.

Default manual workload:

| Profile | API | RAM | CPU | Repetitions |
|---|---:|---:|---:|---:|
| current | 36 | 2048 MB | 2 | 5 |
| old-edge | 26, fallback 28 | 1536 MB | 2 | 5 |
| current-low-ram | 36 | 1024 MB | 2 | 5 |

Large 50k parse work is manual/scheduled, not part of each PR Full.

## Package C — cross-series analyzer

Create a pure JVM/Python-free repository analyzer over validated JSON reports.

For each operation/family/profile calculate:

- series count and total raw sample count;
- median of per-run medians;
- minimum/maximum per-run median;
- absolute and percentage range;
- arithmetic mean and sample standard deviation of per-run medians;
- coefficient of variation;
- worst observed p95;
- environment/fixture agreement.

The analyzer must reject:

- mixed method/schema versions;
- mismatched fixture/profile SHA;
- mismatched workload shape;
- duplicate repetition IDs;
- missing raw samples;
- reports with failures or thresholds already applied;
- secret/path-bearing diagnostics.

## Package D — threshold decision record

A threshold is considered only when:

- at least five independent series exist for the target environment;
- coefficient of variation is sufficiently stable and documented;
- host/emulator lifecycle failures are separated from code measurements;
- threshold has a clear user-facing or resource-risk rationale;
- dedicated benchmark workflow cost is acceptable;
- threshold includes an explicit review/retuning policy.

Permitted outcomes:

1. **No threshold yet** — evidence remains descriptive because variance/environment sensitivity is too high.
2. **Dedicated warning-only trend report** — no merge failure.
3. **Dedicated failing threshold** — only for one stable family/environment and never in Fast/Full.

## TDD sequence

1. RED tests for environment fingerprint determinism and field sensitivity.
2. RED tests for series manifest validation and path/secret redaction.
3. Implement common identity without changing existing report semantics.
4. RED tests for analyzer statistics and incompatible-report rejection.
5. Implement analyzer with canonical JSON/Markdown output.
6. Add sequential current-profile orchestration and obtain two smoke repetitions.
7. Add old-edge and low-RAM profiles after current smoke is stable.
8. Run five independent repetitions per profile manually/scheduled.
9. Publish variance report and threshold decision.
10. Close issue #27 only when the decision record is merged.

## Merge boundaries

- PR 1: common identity + analyzer contracts, pure unit/Full validation.
- PR 2: series orchestrator and current-profile smoke evidence.
- PR 3: old-edge/low-RAM evidence and threshold decision.

Production optimizations, if any are justified, must be separate before/after PRs citing the merged variance report.
