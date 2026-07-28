# Catalog Database Measurements Implementation Plan

> **Status:** Implemented and exact-head verified in PR #54. This file is retained as the execution record for issue #27 Package 2B.

**Goal:** Add reproducible, threshold-free Android Room measurements for catalog staging, activation and the two production queries required by issue #27.

**Architecture:** Measurement model, runner and writer live in `core:database` debug sources. Release code and public APIs remain unchanged. One dedicated instrumentation class executes real file-backed Room operations. Repository-owned PowerShell commands provision Android TV, run the measurement, recover canonical JSON through the instrumentation result bundle and validate report invariants on the host.

**Tech stack:** Kotlin 2.4.10, Android SDK 26–36, Room 3.0.0, coroutines 1.11.0, AndroidX Test, PowerShell 7 and the existing self-hosted Android TV harness.

## Global constraints — completed

- [x] `minSdk = 26` unchanged.
- [x] No production Room schema, index, query, transaction or batch-size change.
- [x] Default workload: 10,000 entries, batch 250, page 100, source overviews 32, one warmup and five retained samples.
- [x] Fresh file-backed WAL database for every sample.
- [x] Setup and prerequisite seeding excluded from unrelated measured intervals.
- [x] Every raw sample retained; nearest-rank min/p50/p90/p95/max recorded.
- [x] `thresholdApplied = false` explicit in every report.
- [x] No locator, credential, provider identity, source ID or host path in report diagnostics.
- [x] Ordinary DeviceCurrent/DeviceMatrix suites exclude the measurement test without recording a skip.
- [x] Report states `buildMode = debug-instrumentation` and does not claim weak-TV, codec, startup, zapping or first-frame performance.

## Task 1 — RED contracts

- [x] Added unit contracts for statistics, immutable snapshots, canonical JSON and redaction.
- [x] Added instrumentation contract for five real Room operations.
- [x] Opening Full failed on the deliberately absent measurement types.
- [x] RED head recorded in PR history.

## Task 2 — model, statistics and canonical JSON

- [x] Added bounded workload/spec/environment/sample/operation/report models.
- [x] Added nearest-rank statistics with non-empty, non-negative inputs.
- [x] Added deterministic fixture identity with SHA-256.
- [x] Added fixed-order UTF-8, LF-only JSON with one trailing newline.
- [x] Added explicit build mode, cache state, limitations and threshold-free metadata.
- [x] Unit contracts pass.

## Task 3 — real Room measurement runner

- [x] Prepared 10,000 deterministic synthetic `StagedCatalogEntry` values outside timers.
- [x] Created a fresh file-backed Room database with WAL for each warmup and measured sample.
- [x] Measured `stage-batch-250`.
- [x] Measured `stage-total-10k` as forty 250-entry transactions.
- [x] Measured `activate-10k` with staging outside the activation timer.
- [x] Measured `active-channel-first-page` with limit 100.
- [x] Measured `source-overview-32`.
- [x] Captured Android environment and DB/WAL/SHM sizes.
- [x] Correctness mismatches fail with fixed secret-free messages and do not publish a successful report.
- [x] Final review replaced the partial fixture fingerprint with a length-prefixed SHA-256 over every staged field, including nullable values and entry boundaries.

## Task 4 — dedicated instrumentation boundary

- [x] Added `CatalogDatabaseMeasurement` annotation.
- [x] Excluded that annotation from ordinary connected tests unless `catalogMeasurements=true`.
- [x] Added strict instrumentation arguments for source commit, runner label, warmups, iterations, entry count and output name.
- [x] Added a single dedicated instrumentation test asserting operation order, result counts and zero failures.
- [x] Added atomic report publication in the test process.
- [x] Added instrumentation result-bundle transport so AGP package cleanup cannot destroy the only report copy.

## Task 5 — reusable host and self-hosted execution

- [x] Added `Invoke-CatalogDatabaseMeasurement.ps1`.
- [x] Added `Invoke-CatalogDatabaseDeviceValidation.ps1` using the repository-owned AVD lifecycle.
- [x] Added manual `CatalogMeasurement` workflow mode without changing ordinary PR Full behaviour.
- [x] Host validation requires exact commit, supported schema/method/build mode, complete fixture SHA, five ordered operations, five raw samples, correct result counts, zero failures and `thresholdApplied=false`.
- [x] Stale Android test-results are removed before execution.
- [x] Child output and bounded failure code/type/command/line metadata are retained in evidence.
- [x] Exception messages, PowerShell stack traces and full host paths are excluded from the device manifest.
- [x] Fixed null-unsafe cold-boot/package-manager readiness handling exposed by API 36.
- [x] Fixed strict-mode native exit-code handling exposed by the focused harness.

## Task 6 — evidence, review and merge preparation

- [x] Final reviewed Full run `30400010584` succeeded on source head `7f9ae926d84a7fc89bcde9455a3ec28a5bfcfc4f`.
- [x] Final reviewed dedicated measurement run `30400010579` succeeded on the same source head.
- [x] Artifact `pr-catalog-database-measurement-30400010579-1` is bound to that head with digest `sha256:c9128043877e635318b96f94e9be21a53c4759e93728ebc73536a06723202731`.
- [x] Complete fixture SHA-256 is `550426cde45c459c3b60e6fc54c41a8e4a6bab5b7b1724851d903f97fba8a647`.
- [x] Canonical report states API 36, x86_64, two processors, 192 MB memory class, debug instrumentation build and zero failures.
- [x] Raw distributions and storage footprint are recorded in `docs/performance/2026-07-28-catalog-database-baseline.md`.
- [x] Interpretation explicitly declines a production Room optimization from one five-sample series.
- [x] Review found and fixed complete-fixture identity and failure-manifest redaction gaps through a new RED/GREEN contract.
- [ ] Remove the temporary PR-only measurement workflow from the final tree.
- [ ] Run final cleaned-tree Full after documentation/workflow cleanup.
- [ ] Review final diff and unresolved threads.
- [ ] Mark PR ready and squash merge.
- [ ] Add issue #27 progress record.

## Remaining issue #27 work after Package 2B

1. Repeat parse and Room series across comparable current, old-edge and low-RAM virtual profiles to establish variance.
2. Add Player request installation/reconnect proxy measurements.
3. Decide whether evidence supports a dedicated threshold gate.
4. Introduce no structural optimization without comparable before/after evidence.
