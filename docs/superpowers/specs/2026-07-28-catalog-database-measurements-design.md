# Catalog Database Measurements Design

> **Status:** accepted for implementation from the existing issue #27 sequence.

## Goal

Produce reproducible descriptive evidence for the real Android Room catalog boundary before changing batch size, SQL shape, indexes, Paging, storage engine or Player architecture.

## Scope

This package measures five independent operations against a file-backed `MuxTvDatabase` on an Android runtime:

1. one `stageBatch` call with exactly 250 prepared `StagedCatalogEntry` rows;
2. complete 10,000-row staging as forty 250-row transactions;
3. activation of a previously staged 10,000-row revision;
4. the first emission of `PlaybackCatalogDao.observeActiveChannels(..., limit = 100)` over an active 10,000-row revision;
5. the first emission of `SourceRefreshDao.observeOverviews()` after preparing 32 synthetic sources.

Parser, network, Media3 and UI rendering time are deliberately excluded. Data generation, database creation and prerequisite seeding occur outside each measured interval.

## Alternatives considered

### Recommended: dedicated instrumentation measurement in `core:database`

Use the real Room database, DAO and store implementation in the module that owns them. Keep the runner in the debug source set, execute it through a dedicated instrumentation class and export a canonical JSON report.

Advantages:

- measures the actual Android SQLite/Room boundary;
- can access internal DAO/store types without widening production APIs;
- does not create a second storage abstraction;
- keeps ordinary correctness instrumentation separate through a dedicated runner argument;
- reuses the existing Android TV emulator harness.

Trade-off: this baseline describes the selected Android emulator/device and filesystem state, not all televisions.

### Rejected: JVM fake-store benchmark

A fake `SourceRevisionStore` would measure Kotlin collections and coroutine overhead, not SQLite transactions. It cannot justify database structural changes.

### Rejected: one end-to-end importer number

Combining parse, identity generation, staging, activation and query into one duration would hide the actual bottleneck and make later before/after comparisons ambiguous.

## Architecture

### Debug-only measurement model

`core/database/src/debug/kotlin/app/muxtv/database/measurement/` contains:

- immutable specification and environment models;
- raw per-operation samples;
- nearest-rank summary calculation;
- deterministic synthetic `StagedCatalogEntry` preparation;
- file-backed database runner;
- fixed-order UTF-8 JSON writer.

The release source set remains unchanged. No measurement API enters the production release artifact.

### Instrumentation entry point

`CatalogDatabaseMeasurementTest` reads bounded instrumentation arguments, runs the debug measurement runner and writes one report to the test application's external files directory.

Normal `connectedDebugAndroidTest` excludes the measurement annotation by default. A dedicated invocation enables only this class, preventing measurement runtime or skipped tests from changing the ordinary DeviceMatrix correctness evidence.

### Host-side execution

`tools/android/Invoke-CatalogDatabaseMeasurement.ps1`:

1. requires an already booted device selected by `ANDROID_SERIAL`;
2. runs the single measurement instrumentation class with explicit arguments;
3. pulls the report to the requested evidence directory;
4. validates schema, exact source commit, `thresholdApplied = false`, non-empty raw samples and zero failures;
5. never prints synthetic locators or device-internal database paths.

`Invoke-TvDeviceValidation.ps1` gains a `CatalogMeasurement` mode that provisions one current Android TV image and delegates to the measurement script. The existing `DeviceCurrent` and `DeviceMatrix` behavior remains unchanged.

## Workload and isolation

Default evidence workload:

- entry count: 10,000;
- batch size: 250;
- first-page limit: 100;
- source-overview count: 32;
- warmups: 1;
- measured iterations: 5.

Each warmup and measured sample uses a new uniquely named file-backed database. Database creation and prerequisite seeding are outside the timer. The measured operation starts immediately before the target store/DAO call and ends after its result is available.

For query samples, staging and activation occur outside the measured interval. For activation samples, staging occurs outside the measured interval. For staging samples, immutable synthetic rows are prepared outside the measured interval.

Every sample records:

- wall time in nanoseconds;
- result row count or activated entry count;
- database file bytes;
- WAL bytes;
- SHM bytes;
- failure state.

No sample is silently removed. Failed samples remain represented and increment `failureCount`; a failed correctness invariant fails the instrumentation test rather than producing a misleading successful report.

## Report contract

The canonical JSON report contains, in fixed order:

- schema and method versions;
- `thresholdApplied: false`;
- exact source commit and safe runner label;
- device manufacturer/model/fingerprint, API, ABI, low-RAM state, memory class and available processors;
- workload parameters and explicit cache state `fresh-file-per-sample`;
- raw samples for each operation;
- min/p50/p90/p95/max summaries;
- storage-size summaries;
- total failure count;
- interpretation limitations.

Output is UTF-8, LF-only and ends with exactly one newline. It contains no locator, credential, provider name, source ID, filesystem path, exception text or corpus payload.

## Correctness invariants

- staged row count equals the expected workload before activation;
- activation returns `Activated` with the expected entry count;
- first-page query returns exactly the configured limit;
- source overview returns exactly 32 rows;
- generated rows use only reserved `.example` locators and synthetic identifiers;
- database files are closed and deleted after each sample;
- report publication is atomic on the host-visible test external directory;
- normal database instrumentation excludes the measurement class;
- measurement invocation executes at least one test and produces one report.

## Non-goals

- parse measurements, already covered by Package 2A;
- importer identity/hash timing;
- UI frame, startup, zapping or first-video-frame claims;
- MediaCodec, HDR, passthrough or Fire OS claims;
- performance thresholds or pass/fail budgets;
- changing Room schema, indexes, transaction shape or batch size;
- adding Paging, bundled SQLite, Rust/UniFFI or another storage engine.

## Acceptance evidence

- opening RED contract fails before measurement types exist;
- focused unit and instrumentation contracts pass;
- ordinary Full validation passes;
- dedicated `CatalogMeasurement` run passes on the exact reviewed head;
- report is archived with exact environment metadata and raw samples;
- issue #27 remains open for Player proxy measurements and repeated variance evidence.
