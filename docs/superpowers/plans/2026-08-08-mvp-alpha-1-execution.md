# Muxtv closed MVP `0.1.0-alpha.1` execution plan

**Date:** 2026-08-08

**Baseline:** `main@c901dcc55a65f634be0c3e720cc1f9c783e6189e`

**Working model:** sequential `upd/*` vertical-slice branches from accepted `origin/main`

**Status:** active canonical ExecPlan

## Goal and authority

Deliver a private GitHub pre-release `0.1.0-alpha.1` (`versionCode=1001`) with a secure URL source flow, streaming and atomic M3U/XMLTV ingestion, useful TV destinations, one service-owned Media3 player, bounded same-channel recovery, redacted Doctor diagnostics, deterministic D-pad behavior, and measured performance on the supported device class.

This file is the only mutable execution journal for the MVP scope. The user-approved plan of 2026-08-08 and tracked source remain authoritative facts. Older plans remain historical evidence where they conflict with this file.

## Product contract

Included:

- protected IPTV source by URL;
- cancellable streaming M3U/XMLTV import with progress and atomic revision publication;
- Home, Channels, Guide, Favorites, Search, Recent, Sources, Doctor and Settings;
- a single `MediaSessionService`/ExoPlayer owner;
- bounded automatic same-channel recovery;
- secret-safe source/playback diagnosis and export;
- complete D-pad, focus restoration and Back behavior;
- API 26 and API 36 functional CI gates;
- a performance contract for API 29+ devices with 3–4 GB RAM;
- one physical Android TV or Google TV manual release/performance gate.

Excluded from this release:

- QR/local-web onboarding and local-file import;
- multiple profiles, backup/restore, Xtream, catch-up and DVR;
- Fire TV certification or compatibility claims;
- stores, a telemetry backend and an alternative playback engine;
- full localization. Alpha uses one internally consistent UI language.

## Non-negotiable architecture contracts

- Navigation, saved state and UI carry playback identity only: `profileId`, `channelId`, and optional `preferredVariantId`.
- Locators, headers and credentials are resolved inside the playback service boundary immediately before an attempt.
- `MuxTvPlaybackService` owns ExoPlayer, MediaSession, recovery generation, timeout and cancellation.
- Media3 handles retry/backoff inside one candidate; Muxtv alone advances between deterministic same-channel candidates.
- Each playback start has a generation token. Duplicate and stale callbacks are idempotently ignored.
- First rendered frame is the success boundary. A generation may attempt at most three candidates and last at most 20 seconds.
- Stop, replacement request, service destruction and timeout cancel resolution, preparation and pending callbacks.
- URI, headers, credentials and raw exception messages must never enter logs, diagnostics, navigation, saved state or export.
- Performance work is measurement-led. No mass rewrite, custom interning, second engine or native bridge without a measured residual problem and a separate decision record.

## Design source and TV adaptation

Normative external input is pinned to `emilkowalski/skills@de33dbed000212b54400a33767d1e4d03654db2a`, specifically `skills/emil-design-eng/SKILL.md` (MIT). Updates require a separate PR.

Android TV rules:

- dense D-pad focus changes immediately; frequent navigation is never animated;
- press feedback is immediate and restrained;
- rare overlays/dialogs/notifications enter in 140–220 ms ease-out and exit in 100–160 ms;
- use only alpha, scale or translation where motion explains state or spatial relation;
- no blur, animated layout sizing, heavy TV shaders, bounce or decorative delay;
- reduced-motion settings remove flourish without weakening outline/tone feedback;
- focus order, Back, scroll and focused-item restoration are deterministic;
- status is not encoded by color alone, and TV contrast, safe margins, type and targets are reviewed at viewing distance;
- every UI review records `Before | After | Why`.

## Execution checklist

### M0 — repository truth and safety

- [x] Authenticate Git HTTPS and obtain all branch, tag and PR refs.
- [x] Create and verify complete pre-cleanup bundle at `.git/bundles/pre-mvp-20260808.bundle` (470 refs).
- [x] Inventory 111 worktrees and preserve dirty, unmerged, open-PR and unknown histories.
- [x] Remove only three clean worktrees proven reachable from `origin/main`; retain all branches.
- [x] Fast-forward root checkout first to `main@e9dd0336716e27e9b51f4eb10da82169112e71d1`, then through accepted release/truth-sync PRs to `main@b30a1d745df80f0c1e6b38ee7947ceff9cdcdb17`; exclude local `worktrees/` through `.git/info/exclude`.
- [x] Create this canonical ExecPlan; retain `upd/mvp-alpha-1` as historical execution provenance and use isolated vertical-slice branches for remaining work.
- [x] Preserve the local unpacked Doctor Product Matrix evidence at `.work/doctor-product-31282812126/`; it corresponds to published artifact `9029117392` and is not cleanup residue.

### M1 — reliable self-hosted CI

- [x] Add a repository-owned, test-covered runner preflight for toolchain, disk/memory, ADB/device isolation and GitHub artifact DNS/HTTPS endpoints.
- [x] Fail closed before expensive jobs when the runtime results route or representative Azure Blob DNS/HTTPS is unavailable; successful `upload-artifact` remains the authoritative check for its service-returned signed Blob URL.
- [x] Keep Actions pinned to full SHAs, least-privilege permissions and exact source-head evidence; reject fork code on the persistent runner and never use `pull_request_target`.
- [x] Use unique artifact names, `if-no-files-found: error`, binary-friendly `compression-level: 0`, and bounded PR/release retention.
- [x] Guarantee emulator, ADB and temporary-output cleanup; retain only dependency caches.
- [x] Cancel superseded PR runs while preserving manual/accepted-main runs.
- [x] Enforce emulator/device serialization through the singleton `muxtv-device` runner label and verify that later workflows wait rather than being cancelled; do not use one shared native concurrency group because GitHub retains at most one pending member and cancels the older pending job.
- [x] Validate configuration cache with fail-on-problems before making it a permanent gate.
- [x] Apply and verify dedicated runner labels `muxtv-android` and `muxtv-device` on online repository runner `DESKTOP-0N5KM3T` before workflows depend on them.
- [x] Supersede the historical PR #145 upload-only failure with accepted CI hardening in PR #149 and subsequent exact-head Product/Full evidence for PRs #150-#153; do not re-run a merged historical head.

### M2 — bounded integration queue and truth sync

- [x] Merge #145 as the pure #30A recovery-policy slice (`1beaa675`).
- [x] Keep #144 historical; PR #150 implemented the required identity-only runtime boundary directly on accepted `main` without importing a parallel request path.
- [x] Remove the fictitious #146 integration item: GitHub has no accessible PR #146, and no Room upgrade is implied by the MVP plan.
- [x] Supersede stale draft PR #148 with this current-main truth sync instead of rebasing obsolete baseline documentation.
- [x] Merge CI, runtime recovery, observations, Doctor Lite and release identity as separate PRs #149-#153.
- [ ] Keep each remaining PR to one logical slice: CI operations, measurement, Channels, Search, Guide, ingestion, source Doctor, shell/UI, Player UX, performance or release.

### M3 — measurement foundation

- [x] Add Macrobenchmark/Baseline Profile producer infrastructure and keep release verification for generated `baseline.prof`/ProfileInstaller in the performance/release closure.
- [ ] Cover cold/warm startup, Home→Channels, 500-item scroll, Search, Guide, Player, local-HLS first frame, recovery/fallback and focus restoration.
- [x] Add the JMH module and focused executable microbenchmark foundation for parsing, ordering/policy, Room mapping, now/next and search normalization.
- [ ] Generate profiles on a non-minified profile variant and consume them in minified release.
- [ ] Capture Compose stability/recomposition reports as diagnostic evidence; add stability annotations only for proven contracts.

### M4 — playback runtime and Doctor

- [x] Add JVM/service tests for candidate order, duplicate/stale callbacks, cancellation, supersession, timeout and first-frame success.
- [x] Introduce identity-only `PlaybackStartRequest`, one-at-a-time candidate resolution, pure `PlaybackRecoveryPolicy`, safe `PlaybackObservation` and bounded `PlaybackFailureCategory`.
- [x] Implement the service-owned generation state machine with at most 3 candidates / 20 seconds and one final result.
- [x] Map DNS, TLS, HTTP, timeout, network, manifest, codec/render and credential failures without retaining raw exceptions or secrets.
- [x] Keep accepted Doctor Lite diagnostics in a bounded in-memory observation buffer; no Room schema change is required for the closed alpha.
- [x] Implement Doctor presentation/SAF export for typed redacted playback observations and expose it only when actual attempt evidence exists.
- [ ] Add bounded source-refresh diagnostics to Doctor through a redacted adapter over `SourceRefreshStore`; source names/IDs remain local UI data and are excluded from export.
- [ ] Complete Player recovery actions and auto-hiding overlay without introducing another player/retry owner.

### M5 — data and allocation hot paths

- [ ] Add screen-specific Room projections and Room-backed paging with stable keys for large data:
  - [ ] Channels: active S4 on `upd/channels-paging` from accepted `main@c901dcc5`; remove the 200-row browse limit, enrich only loaded pages and retain bounded focus state.
  - [ ] Search: S5 after accepted Channels slice.
  - [ ] Guide: S6 after accepted Search slice.
- [ ] Keep parsers streaming with bounded batch transactions, cancellation points and atomic revision publication.
- [ ] Move sorting/filtering/formatting outside composable bodies; use stable keys/content types and narrow state-read scopes.
- [ ] Update now/next in the data layer for only affected time windows.
- [ ] Require `0 B/op` after warm-up for pure recovery/ordering functions and no app-owned per-frame allocation stack in static UI.
- [ ] For each performance PR, prove no allocations/op regression and at least 30% improvement in its selected hotspot.

### S4 UI review — Channels Paging

| Before | After | Why |
| --- | --- | --- |
| Channels materialized one `List` capped by `NowNextQuery.MAX_CHANNEL_IDS` (200). | Room-backed `PagingSource` uses 64-row pages, 16-row prefetch, a 256-row maximum loaded window and no placeholders. | Every channel in a 50k catalog remains reachable without retaining 50k UI models. |
| ViewModel rebuilt complete channel/guide row lists on catalog, EPG and playback changes. | Room projects only browse fields, now/next is fetched once per loaded page, and playback identity maps over `PagingData`. | Removes whole-list copying and bounds transient allocations. |
| Focus restoration depended on a complete channel-ID list. | Restoration stores the stable channel ID plus index and scans only the bounded loaded window through `peek`. | Preserves D-pad return behavior without `itemSnapshotList.items` or a full catalog copy. |
| A page failure replaced the whole route with a terminal message. | Refresh and append failures expose explicit Retry while already loaded rows stay available. | Keeps TV navigation usable during bounded paging failures. |

### M6 — Lounge Light vertical UI slices

- [x] D1: immediate dense focus and stable geometry (accepted in PR #138 / `d109ad6a`).
- [ ] D2: OK/long-press/repeat semantics without global key interception.
- [ ] D3: independent focused/selected/playing/disabled states and reduced motion.
- [ ] D4: 720p/1080p dialog and viewport reachability.
- [ ] D5: Lounge Light design-system foundation and compact shell/navigation.
- [ ] D6: Home, Channels, Search, Recent and Guide using real state only.
- [ ] D7: Player/recovery/Doctor, Sources and Settings with auto-hiding overlay and no player recreation.
- [ ] Record `Before | After | Why` and semantic/device evidence for every UI PR.

### M7 — release hardening

- [x] Set `versionCode=1001`, `versionName=0.1.0-alpha.1`, R8 minification, resource optimization and full optimization for release in PR #153.
- [ ] Read signing material only from a manually approved GitHub Environment and never persist it in logs/artifacts/caches.
- [ ] Produce signed APK, mapping, dependency SBOM, SHA-256 checksums, license report, benchmark JSON/traces and toolchain/device/source manifest.
- [ ] Perform two clean release builds and compare artifact contents; document unavoidable timestamp differences.
- [ ] Because no predecessor alpha artifact/signing identity exists, first create a controlled signed seed APK in the protected environment with the same key lineage; then prove upgrade, installability, service playback and profile inclusion.
- [ ] Publish a private GitHub pre-release with verified-device scope, known limitations, Doctor transfer instructions and rollback procedure.

## Performance and quality gates

Absolute release measurements are manual on one pinned physical API 29+ Android/Google TV device with 3–4 GB RAM. Evidence records model, firmware, resolution, refresh rate, temperature and free memory. Emulators provide functional compatibility, not absolute performance numbers.

| Scenario | MVP ceiling |
| --- | ---: |
| Cold startup to interactive Home | p95 ≤ 1.8 s |
| Warm startup | p95 ≤ 800 ms |
| Visible D-pad response | p95 ≤ 33 ms |
| 30 s Channels/Guide scroll | p99 frame ≤ 33.3 ms; 0 frozen frames |
| Local HLS OK to first frame | p95 ≤ 1.5 s |
| Recovery | ≤ 3 attempts and ≤ 20 s |
| Playback with overlay closed | PSS ≤ 250 MB |
| 100 navigation/play cycles | retained growth ≤ 10 MB |
| M3U 50,000 parse + persist | p95 ≤ 10 s; cancellation ≤ 500 ms |
| Regression against pinned baseline | median ≤ +5%; p95 ≤ +10% |

Startup/navigation Macrobenchmarks run at least ten iterations; other CUJs run at least five. PR dry-runs prove executability; release runs retain JSON and Perfetto traces. A slice whose baseline exceeds its ceiling is incomplete until corrected; ceilings are not relaxed to fit implementation.

## Required acceptance scenarios

- valid, large, malformed and cancelled M3U/XMLTV; success/cancellation/process-death/DB-failure revision atomicity;
- 50,000 channels without full UI list materialization;
- preferred/deterministic recovery ordering, exhaustion, timeout, cancellation and replacement request;
- duplicate/late Media3 callbacks and errors before/after first frame;
- HTTP 401/403/404/429/5xx, DNS, TLS, timeout, unreachable network and unsupported media;
- forbidden secret material absent from logs, events, saved state and Doctor export;
- D-pad traversal, Back, focus restoration and recreation on API 26/API 36;
- minified signed release installation/upgrade, Baseline Profile and playback-service smoke;
- physical-device smoke, endurance and performance gate.

## Definition of done

- All required checks are green on one exact source SHA.
- #30B, #30D and the MVP subset of #111 are closed; no open P0/P1 defects remain.
- Physical-device ceilings pass with retained evidence.
- Documentation, roadmap, architecture version, issues and release notes match code.
- The private pre-release contains the signed APK, checksums, SBOM, mapping and reproducible evidence.
- No temporary shim, parallel playback path, hidden CI bypass or unresolved secret-bearing diagnostic path remains.

## Decisions and evidence log

- 2026-08-08: user fixed closed-alpha scope, `versionCode=1001`, recovery budget `3 / 20 s`, physical Android/Google TV gate and Fire TV exclusion.
- 2026-08-08: repository synchronized to `origin/main@e9dd0336716e27e9b51f4eb10da82169112e71d1`; full 470-ref bundle verified before cleanup.
- 2026-08-08: external design input pinned to `emilkowalski/skills@de33dbed000212b54400a33767d1e4d03654db2a`.
- 2026-08-08: accepted main already contains D1 through PR #138 / `d109ad6a`; historical `work/tv-design-craft-111` remains provenance only.
- 2026-08-08: no prior alpha artifact or signing lineage exists; release hardening must create a controlled same-key seed before the required signed-upgrade test.
- 2026-08-08: configuration-cache create/reuse passed locally with fail-on-problems; permanent CI enablement remains a later isolated workflow change.
- 2026-08-08: PR #149 exact head `21d043a7862e5befb1225d1c70ab1b6e14b779fc` passed Product Matrix run `31267144994`, including API 26/API 36 and artifact `9024665699` (`sha256:3a49b866ffc22eb778d18d3ceac2ebadbbb69795a2214d5090cf597bfd2da20d`).
- 2026-08-09: PR #150 merged service-owned recovery as `965e6bdd46f75fa5c74e18ffe32ab1e3e25ed61f`; PR #151 merged bounded redacted playback observations as `f8a8c84185839ecc1a5cecd64acb9cb3a1836e22`.
- 2026-08-09: PR #152 exact head `9d8c01a92c1064f0ca6b856a96bd6e4b26cd4c61` passed Product run `31284350989` and Full run `31284350987`, then merged Doctor Lite as `26240e2171421bd73412a61214a1c65d4a46139c`.
- 2026-08-09: PR #153 exact head `42bcf7558d7a637847d6a776ed9b3f40eda1c961` passed Product run `31285565922` with artifact `9029806912` (`sha256:8674233315e3d598f59713f7a640f3e71ef2677d7ba8b74a00d0fb2841daeb17`) and Full run `31285565929` with artifact `9029868593` (`sha256:d57ed0c8e34deda111f7b2cddce9e79c4319722b8aa0fed3684756c9afa26139`), then merged as `d02ae7c0bc87f7bce49b047579b5a1e1f6820192`.
- 2026-08-09: PR #154 exact head `dfbd6b7ce10272c86fce84f0fe54d483f0536880` passed Self-hosted validation run `31301806758` with artifact `9034788781` (`sha256:c1607f05d2a2fac2d9b55c0a57305e1912f2825308cfe02f607ee7a9161fd41e`), then merged truth sync as `b30a1d745df80f0c1e6b38ee7947ceff9cdcdb17`.
- 2026-08-09: S2 exact head `ca203e24de94da0ec6a76273187a21e61aa246a1` passed local Fast evidence at `.work/evidence/20260809T082409Z-ca203e24de94-fast`; repository runner `DESKTOP-0N5KM3T` is online with `muxtv-android` and `muxtv-device`, and draft PR #155 opened. Its first exact-head CI proved that a shared job-level `muxtv-device-global` group cancels an older pending workflow instead of queueing it, so serialization is owned by the singleton device label while per-workflow concurrency only cancels superseded commits.
- 2026-08-09: corrected S2 implementation head `1b2357b120f1e42e40cd666aed792cb5225cd52a` passed local Fast evidence at `.work/evidence/20260809T083153Z-1b2357b120f1-fast` and four sequential exact-head GitHub runs without pending-job cancellation: Product `31303795655`, artifact `9035464354` (`sha256:4804e560e92e1939c1fd74946c09a2f78cecb2d77338863fa2c3ffc63d22971f`); Full `31303795654`, artifact `9035530076` (`sha256:1560f6657f613dda6adbf69f0f4f6f707a6b6da9ccc2167d0ac9b0e26170966c`); variance `31303795648`, artifact `9035775451` (`sha256:8688bb353dc3bd80dbb27858579adfcf9fb5572c5320aa5c069b808e99b402fc`); database matrix `31303795664`, artifact `9035936391` (`sha256:a4ca7ab157af37e928240ea155625704be9ad3aa4067754a96bdc87d345511df`). Every run completed its mandatory upload and Android runner cleanup step.
- 2026-08-09: final S2 exact head `62c88d15fa52dd3ce822101b3b7248f00b0b51c4` passed Product `31306022361` / artifact `9036376580` (`sha256:ef4a6e1f11048a435307c54c558891c109bbd8df6a551e83a2d9cee8cc1f053c`), Database `31306022377` / artifact `9036536314` (`sha256:623c89a071617e4be843c3741f2f67512724abf4acae2cdd4643eb95ad204f1d`), Variance `31306022365` / artifact `9036224730` (`sha256:0079a82872bc5d1cbac19fb08be45f570e4f6f288178f27cf145857bd553c267`) and Full `31306022370` / artifact `9036602749` (`sha256:bbd2aaced83904437156c84664fba50710e04c4741977a22cd0f418d0dc6569b`); PR #155 merged as `d4bd02006b1d52cb0c5afa4f1c7c933b4ff1a196`.
- 2026-08-09: S3 measurement foundation is active from `main@d4bd0200`. AGP 9.3 rejects Baseline Profile Gradle Plugin 1.4.1; AndroidX Benchmark `1.5.0-alpha07` is the narrow compatibility exception because it adds AGP 9.x new-DSL support. The producer exposes `benchmarkRelease` and `nonMinifiedRelease`, and local JMH/producer compilation is green. Current executable CUJs cover startup and reachable empty-state navigation; 500-row, Player/HLS, recovery, data-backed focus and full profile generation remain owned by later data/player/performance slices rather than test-only runtime hooks.
- 2026-08-09: S3 final exact head `aa9d8b8cc5570ec040c23274be3491b77a3e183f` passed Product `31315783500`, Database `31315783509`, Full `31315783501`, Variance `31315783511` and Benchmark `31315783504`; PR #156 merged as `c901dcc55a65f634be0c3e720cc1f9c783e6189e`.
- 2026-08-09: S4 starts from accepted `main@c901dcc5`. AndroidX Paging is pinned to stable `3.5.0`; Room3 paging uses its explicit `room3-paging` DAO return converter. The browse contract contains only channel identity/display/favorite/playback and now/next projection fields; locators, headers, credentials and source URLs remain excluded. Production page settings are fixed at `64 / 64 / 16 / 256`, with placeholders disabled.
- 2026-08-09: user selected sequential vertical slices, a new protected alpha signing key and one available physical Android/Google TV device for the release/performance gate.
- 2026-08-09: issue #30 remains open only for source diagnostics, Player recovery UX and remaining fixture/physical evidence; issue #111 remains open for D2-D7; issue #27 remains open for benchmark/performance closure. Issue #112 is a future provider-adapter contract outside the closed-alpha scope.

## Stop conditions

Stop and update this plan rather than weakening evidence if source SHA differs from the claimed commit, artifact upload is unavailable, a second retry/player owner appears, secrets must cross the service boundary, two changes claim one Room schema version, a benchmark is not reproducible, or physical-device evidence is missing for a release/performance claim.
