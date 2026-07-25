# MuxTV Next Execution Plan

> Execute sequentially. Each functional package starts from merged `main`, uses RED/GREEN contracts, and ends as a reviewable squash-merged increment. A green Action is evidence, not a substitute for product, architecture and security review.

## Current status — 2026-07-25

### Completed product foundations

- PR #20: durable pending-source preparation registry and Room schema v4.
- PR #22: transactional catalog staging and rollback hardening.
- PR #32: secure Android TV source-entry wizard.
- PR #34 implementation: deterministic focus ownership, secure locator semantics, D-pad source-entry routing, Player return restoration and app-level Navigation 3 source journey.

### PR #34 exact evidence

Matrix head before cleanup: `f3d9d21a6e6e67450ca42479c0ca70448a60916b`.

Sequential DeviceMatrix run `30169291048` passed:

| Profile | Image | RAM / CPU | Credentials | Database | App |
|---|---|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | 4 | 19 | 9 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | 4 | 19 | 9 |

Both profiles completed with zero failures, errors and skips. API 26 was available directly; no fallback image was used. The matrix executed the Room 3→4 migration, catalog staging rollback, Android Keystore contracts, source-entry security/focus contracts, Channels → Player → Back, save/restore and the full Home → Sources → Add Source → activate → Channels D-pad journey.

Secret review found no known locator/token fixtures in reports, logcat, manifests or screenshots. The final screenshots show the system launcher after instrumentation shutdown, not a screen containing source data.

Full run `30169291040` also passed on the same pre-cleanup head. No PR discussion or review comments are open.

## Non-negotiable constraints

- Preserve `minSdk = 26` until an explicit compatibility decision changes it.
- Keep playlist locators, queries, cookies, authorization values, referrers, sensitive headers and preparation tokens out of Navigation 3 keys, `SavedStateHandle`, `rememberSaveable`, Room projections, logs, traces, screenshots, semantics and exception text.
- Keep one process-owned `ExoPlayer` and `MediaSession`.
- Use standard Compose Foundation lazy containers; do not reintroduce deprecated TV lazy APIs.
- Keep functional concerns isolated by PR. Do not mix playback transport, visual redesign, EPG and release hardening.
- Do not adopt Rust, libmpv, bundled SQLite, Paging or a second player engine without corpus-backed measurements and an ADR.

---

## Package A — close the source-entry/focus stack

### A1. Clean PR #34

- [x] Implement stable `FocusAnchor` resolution and fallback.
- [x] Restore actual channel focus after Player → Back and save/restore.
- [x] Give Home/top-level navigation deterministic initial focus.
- [x] Give Sources, Add Source and Player states explicit safe focus targets.
- [x] Route D-pad Up/Down explicitly through ordinary and secure text fields.
- [x] Remove raw locator values from merged and unmerged Compose semantics.
- [x] Release `MediaController` on its application thread.
- [x] Add the full touch-free HTTPS source journey through Navigation 3.
- [x] Pass API 26/API 36 DeviceMatrix with non-zero tests.
- [x] Execute Room 3→4 migration and catalog atomicity on both profiles.
- [x] Review matrix artifacts for known secret fixtures.
- [ ] Delete the temporary `.github/workflows/pr34-device-current.yml`.
- [ ] Pass final Full on the cleaned exact head.
- [ ] Update PR #34 with final head, Full and matrix evidence.
- [ ] Mark ready and squash merge.
- [ ] Close #24 and #25 with linked acceptance evidence.

### A2. Repository truth PR

Create a documentation-only branch from the PR #34 squash commit.

Files:

- `README.md`
- `.work/meta/status.yaml`
- canonical roadmap/current-state documents referenced by `.work/meta/documents.yaml`

Deliverables:

- replace obsolete statements that application code, Gradle, CI and tests are absent;
- record the current module graph, Room schema v4 and implemented M3U/source/player path;
- state the actual pre-alpha limitations;
- preserve deferred Rust/libmpv/KMP/platform decisions;
- validate machine-readable metadata and paths;
- Full verification, review and squash merge.

---

## Package B — issue #26: Media3 transport and reconnect

This is the next production-code package after repository-truth synchronization.

### B1. RED transport contracts

Files:

- `player/media3/build.gradle.kts`
- `gradle/libs.versions.toml`
- new focused tests under `player/media3/src/test` or `src/androidTest`
- MockWebServer fixtures where JVM/Android boundaries permit

Contracts:

1. Installing playback request A and then request B must never send A headers on B manifest or segment requests.
2. Redirects must strip credentials across origins and reject HTTPS → HTTP downgrade unless an explicit playback policy allows it.
3. Request factories must be immutable or request-scoped; no mutable singleton default-header state.
4. Locator/header values must remain redacted in `toString`, logs and failures.

### B2. Production transport

- add `media3-datasource-okhttp` aligned exactly with Media3 `1.10.1`;
- use the repository OkHttp/network policy boundary rather than a separate `DefaultHttpDataSource.Factory` policy island;
- build one immutable request-scoped data-source chain per installed playback request;
- preserve one process-owned player/session;
- keep fallback and TV Doctor out of this PR.

### B3. Controller lifecycle

RED contracts first:

- failed or cancelled connect future is evicted;
- a later `connect()` retries instead of returning a poisoned future;
- controller created after connector close is released on its application thread;
- close is idempotent;
- service disconnect permits a bounded reconnect;
- UI adapters use cancellation-aware suspension/timeout, not unbounded blocking `Future.get()`.

Verification:

- focused unit tests;
- Full;
- current-TV controller/service journey;
- old/current DeviceMatrix when lifecycle behavior changes;
- secret review;
- squash merge and close #26.

---

## Package C — issue #27: deterministic corpus and measurements

Create redistributable, provider-neutral fixtures:

- small, medium and large M3U;
- malformed attributes, controls, duplicate identities and mixed encodings;
- HLS master/media playlists and redirects;
- header-sensitive and cross-origin cases;
- starter XMLTV fixtures for the following package.

Measure and record:

- parse time and allocation;
- 250-entry staging batches;
- activation transaction;
- active channel and source-overview queries;
- player request installation and first-frame proxy;
- hardware, API, emulator/device, JDK, Gradle and Android tool versions.

Do not approve Paging, bundled SQLite, Rust, preload or another player engine without a measured bottleneck and ADR.

---

## Package D — issues #28/#29: EPG and daily-use value

Order:

1. bounded streaming XMLTV parser;
2. immutable EPG revisions with previous-good retention;
3. atomic activation and cleanup;
4. now/next projections keyed by canonical channel identity;
5. bounded Guide time windows;
6. debounced bounded search;
7. Favorites and Recent as profile overlays;
8. focus/recreation journeys on old/current TV profiles.

No provider URL or programme payload belongs in navigation keys or focus tags.

---

## Package E — issues #30/#31: recovery and alpha release

### Playback recovery

- deterministic variant ordering;
- explicit maximum attempt and wall-clock budgets;
- no endless retry;
- typed, secret-free TV Doctor observations;
- safe user-confirmed recovery actions.

### Release hardening

- enable R8/resource shrinking with evidence-backed keep rules;
- measure and generate Baseline Profile for startup, Channels, Sources, Player and Guide;
- add representative API 30 and low-RAM endurance after browser/player paths are stable;
- test one current Android/Google TV, one constrained physical device and Fire TV/Quality Central where available;
- validate Room upgrades, Keystore persistence/reset, signing, SBOM, changelog and reproducible release steps;
- publish `0.1.0-alpha` only after issue #31 acceptance criteria are evidenced.

## Immediate execution order

1. Remove the temporary PR #34 workflow.
2. Pass final cleaned-head Full.
3. Merge PR #34 and close #24/#25.
4. Merge the repository-truth documentation PR.
5. Implement issue #26 with transport isolation first, reconnect second.
6. Build corpus/benchmarks before EPG and optimization decisions.
7. Implement XMLTV/Guide/Search/Favorites/Recent.
8. Implement bounded fallback/TV Doctor and complete release hardening.
