# Current-profile measurement variance smoke

- **Status:** accepted descriptive smoke evidence
- **Date:** 2026-07-30
- **Source PR:** #61
- **Source head:** `5091d3a1bdc005a5682b5d0915c617f7491885eb`
- **Merge:** `a99461b0f54e42d95aea8bf31d81215ced2a49e3`
- **Full validation:** run `30568786155`
- **Variance smoke:** run `30568786175`
- **Threshold applied:** `false`

## Scope

This record captures the first repository-owned repeated smoke series for the existing M3U parser, Android Room catalog operations and Player control-plane proxy operations.

It is not:

- a production performance budget;
- a weak-ARM TV performance claim;
- a codec, first-frame or zapping measurement;
- evidence for Rust/UniFFI, bundled SQLite, schema denormalization or another playback engine.

## Environment

| Field | Value |
|---|---|
| Profile | `current-normal` |
| Requested/resolved API | 36 / 36 |
| System image | `system-images;android-36;android-tv;x86_64` |
| ABI | `x86_64` |
| Configured RAM | 2048 MiB |
| Configured CPU | 2 |
| Fallback | false |
| Repetitions | 2 |
| Android lifecycle | fresh AVD per repetition |
| Android operation order | Room, then Player, then shutdown |
| Host M3U runner | `self-hosted-windows-x64-v1` |

Both Android repetitions reached stable boot readiness, completed Room and Player measurements, and stopped the emulator before the next phase. The run manifest finished with `status=passed` and no failure fields.

## Evidence integrity

The series command revalidated every child JSON through the strict adapters before aggregation. Each aggregate report includes:

- exact source commit;
- method/schema identity;
- environment and workload fingerprint;
- distinct child-report SHA-256 values;
- raw-sample count;
- per-run medians;
- range, sample standard deviation and coefficient of variation;
- worst observed p95;
- `thresholdApplied=false`.

A separate audit manifest records only child basenames and SHA-256 values. Aggregate outputs were checked for host paths, Android serials, runner machine names, URLs, locators, headers and credential values; none were present.

## M3U parse smoke

Workload: deterministic `small-1k`, seed `20260728`, five retained samples per repetition.

| Metric | Value |
|---|---:|
| Per-run medians | 16.216 ms / 15.545 ms |
| Median of run medians | 15.545 ms |
| Absolute range | 0.671 ms |
| Percentage range | 4.32% |
| Coefficient of variation | 2.99% |
| Worst observed p95 | 29.001 ms |

Interpretation: the host parse smoke was comparatively stable, but two repetitions remain insufficient for a hard regression budget.

## Android Room smoke

Workload: 10,000 entries, 250-entry batches, five retained samples per operation and repetition.

| Operation | Run medians | Range | CV | Worst p95 |
|---|---:|---:|---:|---:|
| Activate 10k | 2.993 / 3.037 ms | 1.47% | 1.03% | 5.593 ms |
| Active-channel first page | 24.107 / 24.110 ms | 0.01% | 0.01% | 27.180 ms |
| Source overview 32 | 3.725 / 3.530 ms | 5.54% | 3.81% | 4.644 ms |
| Stage batch 250 | 214.325 / 128.231 ms | 67.14% | 35.54% | 315.412 ms |
| Stage total 10k | 3.953 / 3.274 s | 20.76% | 13.30% | 5.550 s |

Interpretation:

- activation and read projections were stable in this two-run smoke;
- staging showed substantial run-to-run variance;
- staging must remain descriptive until a larger series separates normal emulator/runtime variation from actionable regressions.

## Player proxy smoke

Workload: ten retained samples per operation, 1,000 operations per sample. Values are normalized nanoseconds per operation.

| Operation | Run medians | Range | CV | Worst p95 |
|---|---:|---:|---:|---:|
| Request construct | 9,252 / 8,331 ns | 11.06% | 7.41% | 81,862 ns |
| Setup envelope round-trip | 20,434 / 15,724 ns | 29.95% | 18.42% | 29,629 ns |
| Install-active-clear | 2,757 / 1,984 ns | 38.96% | 23.06% | 3,242 ns |
| Cancel-before-install | 1,302 / 984 ns | 32.32% | 19.67% | 1,796 ns |
| Registry disconnect/reacquire | 3,760 / 2,482 ns | 51.49% | 28.95% | 6,515 ns |

Interpretation:

- these are synthetic control-plane micro-operations, not playback startup or first-frame timing;
- very short operations show high relative variance despite low absolute cost;
- several Player proxy operations may remain descriptive-only rather than becoming failing gates.

## Harness findings

The first two exact-head smoke attempts exposed a reproducible ADB transport race during emulator startup. Device registration had succeeded, but an immediate shared `adb wait-for-device` could fail during a brief transport transition.

The final implementation replaced that boundary for measurement series with a bounded readiness protocol:

1. `adb get-state == device`;
2. `sys.boot_completed == 1`;
3. two consecutive successful checks;
4. bounded package-manager readiness;
5. animation disabling after stable readiness.

The final smoke passed both fresh AVD repetitions. Interrupted workflows also finalize any still-running series manifest as `interrupted`, preventing ambiguous evidence.

## Decision

No threshold is introduced from this smoke.

Issue #27 remains open for:

1. five-run `current-normal` evidence;
2. five-run `old-edge-normal` evidence;
3. five-run `current-low-ram` evidence;
4. separate cross-profile interpretation;
5. an explicit decision per operation: hard gate, warning-only or descriptive-only.

Datasets from different API/image/RAM/runtime classes must not be pooled into one distribution.
