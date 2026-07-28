# Android Room catalog measurement baseline — 2026-07-28

## Purpose

This report records the first reproducible Android Room baseline for issue #27. It is descriptive evidence for the exact recorded environment. It is not a regression threshold and does not justify a production schema, index, query, transaction, batch-size or storage-engine change by itself.

## Evidence identity

- Source commit: `7f9ae926d84a7fc89bcde9455a3ec28a5bfcfc4f`
- Full validation run: `30400010584` — success
- Dedicated measurement run: `30400010579` — success
- Artifact: `pr-catalog-database-measurement-30400010579-1`
- Artifact digest: `sha256:c9128043877e635318b96f94e9be21a53c4759e93728ebc73536a06723202731`
- Report schema: `1`
- Method version: `1`
- Build mode: `debug-instrumentation`
- Threshold applied: `false`
- Failure count: `0`

The dedicated run completed one instrumentation test with no failure, error or skip. The host command decoded the report from the instrumentation result bundle and independently validated the source commit, schema, build mode, complete fixture identity, operation order, result counts, raw sample counts and threshold-free contract.

The final review added a length-prefixed fixture digest over every `StagedCatalogEntry` field, including nullable headers and locators. It also replaced exception text and PowerShell stack traces in the device manifest with bounded failure code/type/command/line metadata. The exact-head evidence above was regenerated after those changes.

## Environment

| Field | Value |
|---|---|
| Runner label | `self-hosted-android-tv-api36-x86_64` |
| Android API | 36 |
| System image | `system-images;android-36;android-tv;x86_64` |
| Manufacturer | Google |
| Model | `sdk_google_atv64_x86_64` |
| Build fingerprint | `google/sdk_google_atv64_x86_64/emu64xa:16/BT2A.260319.001/15058170:user/dev-keys` |
| Supported ABIs | `x86_64`, `arm64-v8a` |
| Requested AVD RAM | 2,048 MB |
| Requested AVD CPU cores | 2 |
| Low-RAM device | `false` |
| Android memory class | 192 MB |
| Available processors | 2 |
| Image fallback | `false` |
| Cache state | `fresh-file-per-sample` |

This is an Android TV emulator on a Windows self-hosted runner. The result is not representative of a weak ARM SoC, physical flash storage, vendor firmware or a production release build.

## Deterministic workload

| Field | Value |
|---|---:|
| Entries | 10,000 |
| Batch size | 250 |
| First channel page | 100 |
| Source overview rows | 32 |
| Warmup iterations | 1 |
| Retained measured iterations | 5 |
| Fixture SHA-256 | `550426cde45c459c3b60e6fc54c41a8e4a6bab5b7b1724851d903f97fba8a647` |

The fixture digest is length-prefixed, distinguishes null from empty values, includes entry count and covers all staged fields. The synthetic entries are prepared before measurement. Every retained sample uses a fresh file-backed Room database with WAL. Database creation, prerequisite staging and prerequisite activation are outside the measured interval for operations that do not own those steps.

## Wall-time distributions

Times are milliseconds. Percentiles use nearest-rank statistics and all raw samples are retained.

| Operation | Min | p50 | p90 | p95 | Max |
|---|---:|---:|---:|---:|---:|
| `stage-batch-250` | 58.981 | 65.347 | 150.073 | 150.073 | 150.073 |
| `stage-total-10k` | 2,660.176 | 2,798.138 | 3,079.724 | 3,079.724 | 3,079.724 |
| `activate-10k` | 2.864 | 5.411 | 6.386 | 6.386 | 6.386 |
| `active-channel-first-page` | 25.409 | 35.102 | 39.144 | 39.144 | 39.144 |
| `source-overview-32` | 4.524 | 5.888 | 7.648 | 7.648 | 7.648 |

### Raw wall-time samples

| Operation | Samples, ms |
|---|---|
| `stage-batch-250` | 150.073, 130.538, 65.347, 61.219, 58.981 |
| `stage-total-10k` | 2,881.027, 2,660.176, 2,797.564, 2,798.138, 3,079.724 |
| `activate-10k` | 5.411, 6.232, 5.359, 6.386, 2.864 |
| `active-channel-first-page` | 35.102, 39.144, 38.877, 25.409, 28.558 |
| `source-overview-32` | 7.648, 5.888, 6.085, 4.524, 4.937 |

## Storage footprint after each measured boundary

The values below were stable across the five retained samples for each operation.

| Operation | Database bytes | WAL bytes | SHM bytes |
|---|---:|---:|---:|
| `stage-batch-250` | 12,288 | 407,912 | 32,768 |
| `stage-total-10k` | 5,984,256 | 626,272 | 32,768 |
| `activate-10k` | 5,984,256 | 524,288 | 32,768 |
| `active-channel-first-page` | 5,844,992 | 524,288 | 32,768 |
| `source-overview-32` | 176,128 | 412,032 | 32,768 |

## Interpretation

The measured staging path is materially more expensive than activation and the two read queries in this exact debug-emulator environment. The 250-entry staging samples also show a pronounced first-sample/warmup tail despite one discarded warmup. Five retained samples are insufficient to distinguish host warmup, emulator scheduling, JIT, SQLite/WAL behaviour and stable application cost.

No production optimization is selected from this run. In particular, this report does not justify:

- changing the 250-entry batch size;
- adding or removing indexes;
- denormalizing the catalog schema;
- replacing Room or bundled SQLite;
- adding Paging;
- moving ingest code to Rust/UniFFI;
- changing the playback engine.

## Required follow-up evidence

Before any Room structural change:

1. repeat at least five independent series with the same fixture SHA;
2. run comparable current and old-edge Android TV profiles;
3. add a low-RAM virtual profile without treating it as weak-ARM proof;
4. calculate cross-series median, range and coefficient of variation;
5. split staging into mapping, canonical/provider/variant writes and transaction/WAL commit only if repeated evidence still identifies staging as the dominant boundary;
6. measure a proposed change before and after under the same method version and environment class.

Issue #27 remains open for repeated variance evidence, Player request installation/reconnect proxy measurements and the later decision whether a dedicated threshold gate is warranted.
