# MuxTV Post-HTTP-Approval Execution Plan

> **Status:** active; deterministic corpus, descriptive measurements, immutable variance foundation, strict report adapters and the first current-profile repeated smoke are complete.

**Goal:** move from the completed secure source → catalog → Player vertical slice to statistically defensible measurement decisions, immutable EPG and daily-use TV flows without speculative runtime complexity.

## Baseline

- `main` includes exact-origin approval, deterministic corpus/artifacts, M3U/Room/Player baselines, immutable playback headers, variance foundation, strict report adapters and sequential series orchestration through PR #61.
- Issues #26 and #39 are completed.
- Issue #27 remains active only for five-run current/old-edge/low-RAM evidence, cross-profile interpretation and per-operation gate/warning/descriptive decisions.
- Kotlin, Compose, Room and Media3 remain the production baseline.
- One process-owned ExoPlayer/MediaSession and one encrypted source-access owner remain authoritative.
- Virtual API evidence validates Android contracts, not vendor decoders, Fire OS, weak ARM performance or physical playback quality.

## Package 0 — Repository truth and runner hygiene

**Status:** completed by PR #43 and subsequent repository-truth PRs.

- [x] synchronize human and machine-readable repository truth;
- [x] deterministic persistent-Windows cleanup with repository-local `core.longpaths`;
- [x] clean-workspace evidence;
- [x] documentation sync remains separate from runtime changes.

## Package 1 — Deterministic corpus and starter fixtures

**Status:** completed by PR #44/#47/#48/#50/#51.

- [x] provider-neutral streaming M3U generator;
- [x] stable 1k/10k/50k profiles, seed and exact source commit;
- [x] expected parser counts, size and SHA-256;
- [x] canonical fixed-order UTF-8 manifest;
- [x] atomic artifact pair publication with explicit overwrite/rollback;
- [x] safe repository command and configuration-cache-compatible Gradle task;
- [x] bounded typed HLS/XMLTV starter fixtures;
- [x] no private provider, playlist or credential data.

## Package 2A — Descriptive M3U parse measurements

**Status:** completed by PR #53.

- [x] real production `StreamingM3uParser`;
- [x] generation outside measured section;
- [x] no-retention sink;
- [x] warmups and raw samples;
- [x] wall time and allocation support state;
- [x] exact corpus/environment identity;
- [x] canonical threshold-free JSON;
- [x] dedicated self-hosted evidence.

## Package 2B — Android Room measurements

**Status:** completed by PR #54.

- [x] fresh file-backed WAL database per sample;
- [x] 250-entry batch and 10k total staging;
- [x] activation transaction;
- [x] active-channel first-page and source-overview reads;
- [x] DB/WAL/SHM footprint;
- [x] real Android instrumentation;
- [x] descriptive distributions only.

## Package 2C — Player control-plane proxy measurements

**Status:** completed by PR #56.

- [x] request construction;
- [x] SET envelope round-trip;
- [x] coordinator install/clear and cancel-before-install;
- [x] registry disconnect/reacquire;
- [x] exact request-profile SHA-256;
- [x] no first-frame, codec, network or zapping claim;
- [x] one process-owned player/session remains unchanged.

## Package 2D — Variance foundation, adapters and orchestration

**Status:** completed by PR #59/#60/#61.

### Foundation

- [x] immutable environment/workload comparison identity;
- [x] exact source commit in fingerprint;
- [x] unmodifiable list/map snapshots;
- [x] duplicate report bytes rejected;
- [x] family cannot be renamed independently of identity;
- [x] median/range/mean/sample standard deviation/CV/worst p95;
- [x] child-report SHA-256 provenance;
- [x] no threshold in foundation.

### Adapters

- [x] strict M3U/Room/Player schema and method validation;
- [x] exact-byte SHA-256;
- [x] bounded UTF-8 JSON;
- [x] M3U host/runtime identity;
- [x] Android API/image/ABI/RAM/CPU/fallback agreement;
- [x] Room wall-time-only aggregation;
- [x] Player normalized per-operation aggregation;
- [x] allocation-mode consistency;
- [x] generic redacted failures.

### Orchestration

- [x] repository-owned profile catalog;
- [x] host M3U series separated from Android profiles;
- [x] fresh AVD per repetition;
- [x] Room → Player → shutdown order;
- [x] no parallel emulator execution;
- [x] strict series request and canonical aggregate/audit publication;
- [x] trusted same-repository smoke workflow;
- [x] interrupted-run evidence finalization;
- [x] stable boot readiness after reproducing ADB transport race;
- [x] final Full run `30568786155`;
- [x] final current smoke `30568786175`;
- [x] durable report `docs/performance/2026-07-30-current-variance-smoke.md`.

### Current smoke interpretation

- M3U two-run range: 4.32%;
- Room activation/read projections were comparatively stable;
- Room stage batch range: 67.14%;
- Room stage total range: 20.76%;
- Player request range: 11.06%;
- Player setup-envelope range: 29.95%;
- microsecond coordinator/registry operations show high relative variance.

No threshold is justified by two repetitions.

## Package 2E — Five-run multi-profile campaign

**Status:** next implementation package.

### Datasets

Run and preserve as separate datasets:

1. `current-normal`: API 36, 2048 MiB, 2 CPU;
2. `old-edge-normal`: requested API 26 with explicit recorded fallback only if exact image is unavailable, 1536 MiB, 2 CPU;
3. `current-low-ram`: API 36, 1024 MiB, 2 CPU.

Do not pool samples across API/image/RAM/runtime classes.

### Execution

- [ ] five fresh AVD repetitions per Android profile;
- [ ] Room then Player on each AVD;
- [ ] guaranteed stop/delete after every repetition;
- [ ] host M3U series executed once per exact host/runtime campaign, not redundantly for every Android profile;
- [ ] exact source commit and artifact SHA-256;
- [ ] archive all child and aggregate reports;
- [ ] write a cross-profile report without presenting cross-profile values as one distribution.

### Decision output

For every operation choose exactly one:

- **hard gate** — only with sufficiently stable variance and a documented budget above ordinary noise;
- **warning-only** — useful signal but environment noise remains too high for a blocking gate;
- **descriptive-only** — absolute cost is tiny, proxy meaning is weak or variance makes a gate misleading.

Likely hypotheses to test, not pre-decided outcomes:

- M3U parse may support warning or hard budget after five runs;
- Room activation/read projections may support budgets;
- Room staging likely needs environment stabilization or warning-only status;
- Player micro-operations may remain descriptive-only;
- no benchmark result alone justifies Rust/UniFFI, bundled SQLite or another player engine.

### Exit criteria for issue #27

- [ ] all three five-run Android datasets complete;
- [ ] one exact-host M3U five-run dataset complete;
- [ ] no failed/thresholded child report;
- [ ] separate current/old-edge/low-RAM interpretation;
- [ ] explicit decision per operation;
- [ ] repository performance report merged;
- [ ] issue #27 acceptance criteria reconciled and issue closed or narrowly split for fixture consumers.

## Package 3 — Issue #28 XMLTV and immutable EPG revisions

Start only after Package 2E decisions are recorded; design work may proceed in parallel, but do not mix runtime XMLTV changes into measurement PRs.

### Parser

- bind existing typed XMLTV fixtures to a bounded streaming parser;
- configure secure XML processing and prohibit external DTD/schema/entity access;
- byte, depth, element, text, channel, programme and duration limits;
- cancellation and caller-owned sink;
- timezone/DST normalization and typed malformed timestamp errors;
- no raw XML, programme text, provider identity or locator in diagnostics.

### Storage

- Room schema v5;
- immutable EPG revision and staging tables;
- atomic activation and rollback;
- current + previous-good retention;
- indexes for now/next and bounded time windows;
- migration schemas and API 26/current migration tests.

### Matching and refresh

- exact provider channel ID;
- exact `tvg-id`;
- unique normalized exact name;
- otherwise unmatched with explicit reason;
- separate EPG access/trust boundary;
- WorkManager refresh and typed attempt outcomes.

## Package 4 — Issue #29 daily-use discovery

- bounded now/next projections;
- Guide grid;
- debounced bounded Search;
- Favorites as profile-scoped user overlay;
- Recent only after confirmed successful playback;
- hidden channels excluded from normal projections;
- stable channel identity/focus ownership retained;
- Navigation carries IDs only;
- no programme payload, locator or provider secret in SavedState or semantics.

## Package 5 — Issue #30 fallback and TV Doctor

- typed DNS/TLS/HTTP/auth/redirect/manifest/decoder/playback failures;
- stable variant attempt ordering;
- maximum attempts and total time budget;
- cancellation and recreation-safe single retry owner;
- no retry storms or automatic preferred-variant overwrite;
- TV Doctor Lite with bounded attempt history and redacted export;
- bind existing HLS fixtures to runtime consumers.

## Package 6 — Issue #33 TV-first modernization

Sequence:

1. screen-level ViewModels/UDF and lifecycle-aware state collection where feature orchestration has outgrown local composable state;
2. bounded channel count/window queries;
3. dedicated channel rows;
4. hidden-by-default Player overlay;
5. Sources simplification and secondary-action menu;
6. Guide/Search visual integration after real data exists;
7. light shell/navigation refinement;
8. credential-free logo loader and visual/device QA.

Do not introduce another state framework or redesign placeholder screens before their data flows exist.

## Package 7 — Issue #31 alpha hardening

- R8/resource shrinking on production release;
- separate non-minified Baseline Profile generation variant;
- Baseline and Startup Profiles for startup and real TV critical journeys;
- Macrobenchmark comparison with/without profile;
- signing, SBOM, changelog and recovery guide;
- upgrade/migration tests;
- API 26/30/36 and low-RAM endurance;
- physical Android/Google TV, constrained device and Fire TV evidence.

## Deferred decisions

Rust/UniFFI, libmpv, bundled SQLite, Paging, a second player engine and full KMP storage require:

1. reproducible corpus;
2. measured bottleneck or compatibility gap;
3. focused ADR with migration/rollback cost;
4. tests proving no regression in security, cancellation and device behavior.

## Evidence required for every package

- reviewed exact head;
- focused RED/GREEN contracts;
- Full validation;
- DeviceCurrent/DeviceMatrix only when Android system boundaries change;
- secret-free evidence artifacts;
- synchronized human and machine-readable status after merge.
