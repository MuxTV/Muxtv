# Android Room catalog measurement baseline — 2026-07-28

## Purpose

This report records the first reproducible Android Room baseline for issue #27. It is descriptive evidence for the exact recorded environment. It is not a regression threshold and does not justify a production schema, index, query, transaction, batch-size or storage-engine change by itself.

## Evidence identity

- Source commit: `93a117c054bab09bd240284328ce0ca9731c38b3`
- Full validation run: `30393595286` — success
- Dedicated measurement run: `30393595299` — success
- Artifact: `pr-catalog-database-measurement-30393595299-1`
- Artifact digest: `sha256:d4e495b2275dfab244b8847b3b726cc11517d3b8e230759600ae0f292f8ed1a1`
- Report schema: `1`
- Method version: `1`
- Build mode: `debug-instrumentation`
- Threshold applied: `false`
- Failure count: `0`

The dedicated run completed one instrumentation test with no failure, error or skip. The host command decoded the report from the instrumentation result bundle and independently validated the source commit, schema, fixture identity, operation order, result counts, raw sample counts and threshold-free contract.

## Environment

| Field | Value |
|---|---|
| Runner label | `self-hosted-android-tv-api36-x86_64` |
| Android API | 36 |
| Manufacturer | Google |
| Model | `sdk_google_atv64_x86_64` |
| Build fingerprint | `google/sdk_google_atv64_x86_64/emu64xa:16/BT2A.260319.001/15058170:user/dev-keys` |
| Supported ABIs | `x86_64`, `arm64-v8a` |
| Low-RAM device | `false` |
| Android memory class | 192 MB |
| Available processors | 2 |
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
| Fixture SHA-256 | `97810d12d88527ceb6ca1aee059531328752c266256ce1d6d3bfc08ea3d8737b` |

The synthetic `StagedCatalogEntry` fixture is prepared before measurement. Every retained sample uses a fresh file-backed Room database with WAL. Database creation, prerequisite staging and prerequisite activation are outside the measured interval for operations that do not own those steps.

## Wall-time distributions

Times are milliseconds. Percentiles use nearest-rank statistics and all raw samples are retained.

| Operation | Min | p50 | p90 | p95 | Max |
|---|---:|---:|---:|---:|---:|
| `stage-batch-250` | 142.007 | 215.523 | 924.192 | 924.192 | 924.192 |
| `stage-total-10k` | 2,571.270 | 3,041.258 | 4,993.001 | 4,993.001 | 4,993.001 |
| `activate-10k` | 5.082 | 6.797 | 7.923 | 7.923 | 7.923 |
| `active-channel-first-page` | 39.272 | 45.697 | 47.739 | 47.739 | 47.739 |
| `source-overview-32` | 5.836 | 7.066 | 7.529 | 7.529 | 7.529 |

### Raw wall-time samples

| Operation | Samples, ms |
|---|---|
| `stage-batch-250` | 924.192, 524.233, 201.001, 215.523, 142.007 |
| `stage-total-10k` | 4,993.001, 3,041.258, 2,761.925, 2,571.270, 3,083.463 |
| `activate-10k` | 7.923, 6.930, 6.797, 5.141, 5.082 |
| `active-channel-first-page` | 45.729, 41.922, 47.739, 45.697, 39.272 |
| `source-overview-32` | 5.836, 6.724, 7.353, 7.529, 7.066 |

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

The measured staging path is materially more expensive than activation and the two read queries in this exact debug-emulator environment. The first two staging samples are also substantially slower than the later samples. Five retained samples are insufficient to distinguish host warmup, emulator scheduling, JIT, SQLite/WAL behaviour and stable application cost.

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
5. split staging into mapping, canonical/provider/variant writes and transaction/WAL commit only if the repeated result still identifies staging as the dominant boundary;
6. measure a proposed change before and after under the same method version and environment class.

Issue #27 remains open for repeated variance evidence, Player request installation/reconnect proxy measurements and the later decision whether a dedicated threshold gate is warranted.
