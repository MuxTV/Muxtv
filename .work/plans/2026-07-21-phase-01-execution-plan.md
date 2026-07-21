---
status: active
last_reviewed: 2026-07-21
project: MuxTV/Muxtv
baseline_branch: main
baseline_commit: 4b4a08101af77d445d29b252ba1ed6aa14ac992d
execution_mode: self_hosted_windows_validation
supersedes_execution_sections_of: .work/plans/2026-07-20-reference-adoption-and-phase-01.md
---

# MuxTV Phase 01 to Alpha Execution Plan

> This document is the executable continuation of the reference-adoption plan. The earlier document remains the research and architecture baseline; this document records current repository state, exact work-package order, file boundaries, merge gates, device gates and release criteria.

## 1. Goal

Deliver a usable Android TV IPTV alpha with:

- secure remote M3U source creation and refresh;
- immutable previous-good catalog revisions;
- reliable scheduled refresh;
- process-owned Media3 playback;
- D-pad-first channel browser and player UI;
- XMLTV now/next and guide foundation;
- bounded diagnostics, recovery and performance evidence;
- fully automated Windows self-hosted validation, including Android TV emulator modes.

The alpha is not required to include Xtream/Stalker portals, LAN control, QR pairing, LibVLC/libmpv, recording, a general plugin runtime or cloud services.

## 2. Global implementation constraints

1. MuxTV remains the only product foundation. External repositories are references, test corpora or clean-room behavior sources, never a fork base.
2. Android-first modular monolith; no generic `data`, `domain`, `utils`, `shared` or `platform` module without a concrete dependency boundary.
3. Room is the local source of truth. UI never reads staging revisions.
4. Secrets, tokenized URLs and sensitive headers remain in Android Keystore-backed credential storage; Room stores opaque references only.
5. Media3 remains the primary playback engine through the stable `PlaybackEngine` boundary.
6. Use stable dependencies already pinned in `gradle/libs.versions.toml`. Upgrade in isolated work only when an official release fixes a relevant issue.
7. Prefer one complete functional slice and one representative boundary contract over broad speculative test matrices.
8. Every implementation PR must pass self-hosted `Full`. Android-runtime boundaries additionally require `DeviceCurrent` or `DeviceMatrix` as specified below.
9. Do not claim codec, memory, startup or zapping performance from emulator-only evidence.
10. Logs, exceptions, WorkManager input/output and evidence manifests must not contain raw source URLs, cookies, authorization values or playlist query tokens.
11. `minSdk = 26` remains a product promise. The repository-owned device matrix must execute the oldest available Android TV image at or above that boundary and explicitly record a fallback.
12. Release artifacts must meet Android TV 32/64-bit and 16 KB page-size requirements before alpha distribution.

## 3. Current repository state

### Completed and merged

| Package | Pull request | Main commit | State |
|---|---:|---|---|
| Secure credential storage | #6 | `7a2865e36dfb802fc80bb7b75ab204f52f722cb0` | merged |
| Streaming M3U ingestion | #7 | `9c4de62e0209bc4b418c78eb43672b179c29222b` | merged |
| Immutable source revisions | #8 | `4b4a08101af77d445d29b252ba1ed6aa14ac992d` | merged |

### In progress

| Package | Pull request | Branch | Required action |
|---|---:|---|---|
| Secure remote source refresh | #10 | `feat/source-refresh` | fix Full gate, final security review, squash merge |
| Older remote refresh draft | #9 | `feat/source-refresh-v2` | close as superseded by #10 after merge |

### Existing foundations to retain

- AGP `9.3.0`, Kotlin `2.4.10`, KSP `2.3.10`, JDK 17.
- Compose BOM `2026.06.00`, TV Material `1.1.0`, TV Foundation `1.0.0`, Navigation 3 `1.1.4`.
- Media3 `1.10.1`, Room 3 `3.0.0`, WorkManager `2.11.2`, OkHttp `5.3.0`.
- `core/network`, `core/credentials`, `catalog/ingest`, `catalog/importer` and Room revision storage.
- Windows self-hosted workflow plus `tools/verify-local.ps1` evidence generation.

## 4. Reference adoption matrix

| Reference | Adoption mode | MuxTV use |
|---|---|---|
| Official Android / AndroidX docs | canonical | API contracts, lifecycle, permissions, release requirements |
| `androidx/media` | canonical implementation reference | MediaSessionService, MediaController, ExoPlayer, PlayerSurface, OkHttp data source |
| `kodi-pvr/pvr.iptvsimple` | behavior corpus | M3U dialects, XMLTV matching, catch-up templates, correction and redaction |
| `jellyfin/jellyfin-androidtv` | production scenario corpus | TV/service lifecycle, session/controller separation, Fire TV and long-playback cases |
| `anilbeesetti/nextplayer` | applied Media3 reference | service-owned player, first-frame state, tracks, session commands and deterministic cleanup |
| `4gray/iptvnator` | product/EPG reference | source management, streaming XMLTV batching, now/next and guide interactions |
| Android TV samples / JetStreamCompose | UI reference | focus groups, stable keys, initial focus, focus restoration and D-pad semantics |
| VLC Android / mpv-android / Nova | capability references only | later codec-engine benchmark and ADR |

No license exclusion is applied to research. Any copied code still requires provenance and a compatible distribution decision; default implementation mode is clean-room adaptation against MuxTV contracts.

---

# Work package 01 — Complete secure remote source refresh

## Purpose

Finish PR #10 and establish one production path from encrypted remote access configuration to an atomically activated catalog revision.

## Files

- Modify: `catalog/refresh/build.gradle.kts`
- Modify: `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/RemoteSourceAccess.kt`
- Modify: `catalog/refresh/src/main/kotlin/app/muxtv/catalog/refresh/RemoteSourceRefresher.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Modify: `tools/verify-local.ps1`
- Optional representative contract: `catalog/refresh/src/test/kotlin/app/muxtv/catalog/refresh/RemoteSourceRefreshContractTest.kt`

## Implementation

- [x] Persist URL, insecure-HTTP approval, User-Agent, Referrer and approved sensitive headers in a bounded encrypted record.
- [x] Store only `CredentialId` in Room.
- [x] Evaluate URLs with `SourceUrlPolicy` before network execution.
- [x] Use typed redirect, size, DNS, TLS, timeout, HTTP and importer results.
- [x] Stream `ResponseBody.byteStream()` directly into `CatalogRevisionImporter`.
- [x] Cancel the OkHttp call when the coroutine is cancelled.
- [x] Close a late response when the continuation is no longer active.
- [x] Add the direct `catalog:ingest` dependency required by public request types.
- [x] Share one `MuxTvHttpResources` dispatcher/connection pool through Hilt.
- [ ] Add stable request defaults: M3U `Accept` and `MuxTV/<version>` User-Agent when no provider-specific value exists.
- [ ] Verify cross-origin redirects remove all allowed sensitive headers and Referrer.
- [ ] Verify every result and `toString()` remains secret-safe.
- [ ] Pass fresh self-hosted `Full` on the exact PR head.
- [ ] Update PR #10 verification/evidence metadata and squash merge.
- [ ] Close PR #9 with a superseded note; do not merge or cherry-pick it.

## Merge gate

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

Required evidence:

- `catalog:refresh` compiles and its representative contract passes;
- Android lint passes for refresh and app modules;
- debug and release APK assembly pass;
- artifact branch/head match PR #10 exactly;
- final patch review confirms response closing, cancellation and redaction.

---

# Work package 02 — Repository-owned Android TV device harness

## Purpose

Make `DeviceCurrent` and `DeviceMatrix` fully autonomous on the Windows self-hosted runner. No Android Studio UI, pre-created AVD or physical device is required for CI smoke validation.

## Files

- Modify: `.github/workflows/self-hosted-validation.yml`
- Modify: `tools/verify-local.ps1`
- Create: `tools/android/AndroidSdk.ps1`
- Create: `tools/android/Resolve-TvSystemImage.ps1`
- Create: `tools/android/Prepare-TvAvd.ps1`
- Create: `tools/android/Start-TvEmulator.ps1`
- Create: `tools/android/Wait-TvBoot.ps1`
- Create: `tools/android/Collect-TvEvidence.ps1`
- Create: `tools/android/Stop-TvEmulator.ps1`
- Create: `tools/android/Invoke-TvDeviceValidation.ps1`
- Create: `.work/reviews/2026-07-21-tv-device-harness.md`

## Validation modes

```text
Fast
Full
DeviceCurrent
DeviceMatrix
```

`DeviceCurrent`:

- Android TV API 36;
- `tv_1080p` hardware profile;
- 2 GB RAM, two CPU cores;
- cold boot, no snapshot, headless, no audio;
- execute credentials, database and app instrumentation suites.

`DeviceMatrix` sequentially executes:

1. Android TV API 26 when present in `sdkmanager --list`;
2. otherwise nearest available old TV image, expected API 28, recorded as a fallback;
3. Android TV API 36.

Never run matrix AVDs concurrently on the current runner.

## Harness behavior

- [ ] Resolve `ANDROID_SDK_ROOT` / `ANDROID_HOME` and required tools.
- [ ] Run `emulator -accel-check`; fail with an actionable WHPX/virtualization message.
- [ ] Query `sdkmanager --list`; never guess the system-image ABI.
- [ ] Prefer `x86_64`, then `x86`, only when the exact TV package is available.
- [ ] Install missing platform-tools, emulator and selected system image.
- [ ] Create or recreate a deterministic AVD through `avdmanager`.
- [ ] Configure RAM, cores, heap, D-pad, keyboard, cold boot and GPU explicitly.
- [ ] Start with `-no-window -no-audio -no-boot-anim -no-snapshot -wipe-data`.
- [ ] Wait for ADB and `sys.boot_completed=1`; verify package manager readiness.
- [ ] Disable window/transition/animator scales.
- [ ] Record SDK, fingerprint, API, ABI, RAM, display and acceleration state.
- [ ] Execute one existing `verify-local.ps1 -Mode Device` session per AVD.
- [ ] Always collect logcat, emulator stdout/stderr, package/activity dumps and screenshots on failure.
- [ ] Always stop the emulator in `finally`.
- [ ] Upload combined evidence with branch/head/API identity.

## Workflow policy

- Normal PR: `Full`.
- Android-specific path changes: optional or label-triggered `DeviceCurrent`.
- Keystore, Room migration, MediaSessionService, manifest, player surface and release checkpoint: `DeviceMatrix` before merge.
- Parser-only changes: no emulator requirement.

## Exit gate

- A clean runner can create, boot, test and stop API 36 without manual AVD preparation.
- Matrix fallback is explicit, not silent.
- A failed test or boot cannot leave an emulator process running.
- Evidence contains no secrets.

---

# Work package 03 — Source registry, attempts and reliable scheduling

## Purpose

Turn one-shot refresh into a durable source lifecycle with manual refresh, periodic refresh, status, retry and overlap prevention.

## Module and files

- Create module: `catalog/sync`
- Create: `catalog/sync/build.gradle.kts`
- Create: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshWorker.kt`
- Create: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshScheduler.kt`
- Create: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshWorkNames.kt`
- Create: `catalog/sync/src/main/kotlin/app/muxtv/catalog/sync/SourceRefreshOutcomeMapper.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/SourceRefreshPolicyEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/SourceRefreshStateEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/SourceRefreshAttemptEntity.kt`
- Create: `core/database/src/main/kotlin/app/muxtv/database/SourceRefreshDao.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/MuxTvDatabase.kt`
- Create explicit Room migration v2 to v3.
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Modify: `app/tv/src/main/kotlin/app/muxtv/MuxTvApplication.kt`

## Data model

`SourceRefreshPolicyEntity`:

- `sourceId` primary/foreign key;
- `enabled`;
- `intervalMinutes` with minimum 15;
- `wifiOnly`;
- `requiresCharging` default false;
- optional flex window;
- update timestamp.

`SourceRefreshStateEntity`:

- source ID;
- state: IDLE/RUNNING/SUCCEEDED/FAILED/NEEDS_AUTH;
- opaque run token;
- started/completed timestamps;
- last success revision/time;
- typed failure family and code;
- HTTP status when safe;
- skipped/warning counts;
- no URL or secret-bearing message.

`SourceRefreshAttemptEntity`:

- bounded recent history per source;
- trigger: MANUAL/PERIODIC/STARTUP;
- start/end/duration;
- result family;
- revision and counts;
- no raw exception text.

## Concurrency and work policy

- Periodic unique work name: `muxtv-source-periodic:<sourceId>`.
- Use `ExistingPeriodicWorkPolicy.UPDATE` when policy changes.
- Immediate unique work name: `muxtv-source-refresh:<sourceId>`.
- Use `ExistingWorkPolicy.KEEP` for repeated UI presses.
- Work input contains `sourceId` only. Worker loads all configuration from Room/CredentialStore.
- DAO acquires a source-level run token with a conditional update; stale RUNNING state may be reclaimed after an explicit timeout.
- Manual and periodic work share the same database lease, preventing overlapping imports even though WorkManager names differ.

## Worker result mapping

- Success: imported or empty/no-change result after policy-defined handling.
- Retry with bounded exponential backoff: timeout, DNS, transient I/O, HTTP 408/425/429/5xx.
- Failure without automatic retry: invalid URL, insecure approval missing, corrupted credential, parser limit, permanent 4xx.
- Needs authentication: missing/invalidated credential.
- `CancellationException` always propagates; state is released in `finally`.

## Exit gate

- Periodic work survives process restart.
- Policy update does not duplicate periodic jobs.
- Manual double-press cannot create two concurrent revisions.
- Secrets never enter WorkManager Data or logs.
- Full gate plus DeviceCurrent for Hilt Worker construction and process recreation.

---

# Work package 04 — Active playback catalog contract

## Purpose

Expose only active, playable variants to the player without leaking Room entities or credentials into UI.

## Files

- Modify: `catalog/api/src/main/kotlin/app/muxtv/catalog/CatalogRepository.kt`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/PlayableChannel.kt`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/PlayableVariant.kt`
- Create: `catalog/api/src/main/kotlin/app/muxtv/catalog/ChannelQuery.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/CatalogDao.kt`
- Modify: `core/database/src/main/kotlin/app/muxtv/database/RoomCatalogRepository.kt`
- Create explicit Room projection classes instead of returning entities.

## Contract

`PlayableChannel` contains:

- canonical ID;
- provider display name and group;
- optional logo and channel number;
- active variant list in deterministic order;
- now/next placeholders that remain nullable until EPG phase.

`PlayableVariant` contains:

- stable stream variant ID;
- source/provider identity;
- raw locator confined to catalog/player boundary;
- User-Agent/Referrer and safe request metadata;
- catch-up metadata stored but not executed;
- no credential bytes.

## Query rules

- Join provider rows to `sources.activeRevision`.
- Never return staging/retained rows to UI or normal playback.
- Order channels by explicit channel number, then normalized name, then stable ID.
- Return compact projections; do not load 100k complete entities with all metadata.
- Provide group counts and bounded page/window queries before adding Paging.
- Add Paging 3 only when measurement proves the current windowed query is insufficient.

## Exit gate

- A stale retained revision cannot be selected for new playback.
- Active revision switch updates the observable catalog atomically.
- Public catalog models contain no Room or Media3 types.

---

# Work package 05 — Process-owned Media3 playback service

## Purpose

Move playback ownership from an app singleton into an Android service with a stable MediaSession/MediaController boundary.

## Dependencies

Add only:

- `androidx.media3:media3-exoplayer-hls:1.10.1`;
- `androidx.media3:media3-datasource-okhttp:1.10.1`.

Retain Media3 `1.10.1`. Do not add DASH, RTSP, SmoothStreaming, FFmpeg, LibVLC or libmpv in this package.

## Files

- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/service/MuxTvPlaybackService.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/session/MuxTvMediaSessionFactory.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/player/MuxTvExoPlayerFactory.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/datasource/MuxTvDataSourceFactory.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/item/Media3ItemMapper.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/controller/PlaybackControllerClient.kt`
- Create: `player/media3/src/main/kotlin/app/muxtv/player/media3/state/PlaybackStateMapper.kt`
- Modify: `player/api` contracts only where stable service/controller behavior requires it.
- Modify: `app/tv/src/main/AndroidManifest.xml`
- Modify: `app/tv/src/main/kotlin/app/muxtv/di/AppModule.kt`
- Remove or retire direct activity-owned/singleton playback construction.

## Service lifecycle

- Build ExoPlayer and MediaSession in `onCreate`.
- Return the session from `onGetSession`.
- Release controller callbacks, session, surface references and player in `onDestroy`.
- Use `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` declarations.
- Add Media3 session and platform browser intent filters required for system/remote controls.
- Keep video behavior TV-appropriate: background audio continuation is explicit policy, not accidental lifecycle behavior.

## Player configuration

- Shared `MuxTvHttpResources`, dedicated playback client policy.
- `OkHttpDataSource.Factory` with per-item safe headers.
- HLS plus progressive media source selection.
- Live-oriented load control values measured before customization; start with Media3 defaults.
- Audio attributes and noisy handling.
- Deterministic release and one service-owned coroutine scope.
- Stable media ID based on variant ID, never URL.

## State and commands

- Controller observes player/session state; UI never holds ExoPlayer.
- Commands: play channel, stop, play/pause, channel next/previous, retry current.
- First-frame signal.
- Tracks mapped to stable audio/subtitle models.
- Error mapped to existing MuxTV error families.
- One bounded retry for transient prepare failures; no cross-variant auto-failover yet.

## Device gate

`DeviceMatrix` is mandatory before merge.

Smoke scenarios:

- service start and controller connection;
- Activity recreation while session remains valid;
- D-pad/media button play/pause;
- surface detach/attach;
- cancellation during prepare;
- stop releases player and service resources.

---

# Work package 06 — TV channel browser and player surface

## Purpose

Deliver the first usable end-to-end TV flow: open channels, preserve focus, start playback and return to the same item.

## Modules and files

- Create module: `feature/channels`
- Create module: `feature/player`
- Create: `feature/channels/.../ChannelBrowserRoute.kt`
- Create: `feature/channels/.../ChannelBrowserViewModel.kt`
- Create: `feature/channels/.../ChannelBrowserState.kt`
- Create: `feature/channels/.../ChannelGrid.kt`
- Create: `feature/channels/.../ChannelCard.kt`
- Create: `feature/channels/.../ChannelGroupBar.kt`
- Create: `feature/channels/.../ChannelEmptyState.kt`
- Create: `feature/player/.../PlayerRoute.kt`
- Create: `feature/player/.../PlayerViewModel.kt`
- Create: `feature/player/.../PlayerControls.kt`
- Create: `feature/player/.../PlayerOverlay.kt`
- Modify Navigation 3 destination graph.
- Extend `core/ui/FocusBookmark.kt` only when a concrete focus requirement is missing.

## UI rules

- Ten-foot layout, readable typography and large focused targets.
- All primary actions reachable by five-button D-pad and Back.
- Stable item keys use canonical channel ID.
- Focus bookmark stores ID per group, not index.
- Focus restoration validates the item still exists after refresh.
- Loading/error/empty states always provide a focusable recovery action.
- Group navigation uses `focusGroup`/explicit entry-exit behavior where default spatial search is ambiguous.
- Player surface is Media3 Compose `PlayerSurface`; controller owns state.
- AndroidView fallback must delegate unhandled key events to Compose.

## Images

Do not add Coil until the first logo UI is merged. When required:

- add current stable Coil 3 after a dedicated version check;
- use shared OkHttp resources with image-specific timeout/cache policy;
- cap memory cache for TV/low-RAM devices;
- fixed card geometry independent of image success;
- placeholders contain no animated shimmer on low-end TV by default.

## Initial feature scope

- all channels;
- group filter;
- numeric/name ordering;
- play channel;
- channel next/previous;
- retry;
- return and restore focus.

Defer favorites, history, search, settings and context menus until this flow is stable.

## Exit gate

- DeviceMatrix D-pad flow passes at 720p/1080p.
- Refresh preserving the current channel does not lose focus.
- Return from player restores the same channel card.
- Channel list does not eagerly retain a 100k-item rich model.

---

# Work package 07 — Source management UI

## Purpose

Allow TV users to add, edit, refresh, disable and remove sources without exposing credentials.

## Module

`feature/sources`

## Flows

- Add remote M3U URL.
- Confirm insecure HTTP explicitly.
- Optional User-Agent, Referrer and approved authorization header modes.
- Save access descriptor to CredentialStore, then save opaque source reference.
- Manual refresh with progress/status.
- Schedule enable/interval/network constraints.
- Show last success, active channel count and typed failure.
- Re-authenticate after key loss.
- Delete source transactionally: disable work, remove catalog revisions, remove credential record last or through a recoverable tombstone flow.

## Security UX

- URL field masks query values after save.
- Never show authorization/cookie values after save.
- Error copy names the category, not exception details.
- Shared-device privacy: no source token in recent-screen snapshots or diagnostics export.

## Exit gate

A new user can add one source and reach a playable channel using D-pad only.

---

# Work package 08 — Streaming XMLTV and now/next

## Purpose

Add bounded XMLTV ingestion and show current/next programmes without building the full guide UI first.

## Modules

- Create: `epg/ingest`
- Create: `epg/importer`
- Create: `epg/api`

## Parser

Use platform `XmlPullParser` in an Android library. Do not add DOM, JAXB or a general serialization framework.

Support:

- XMLTV channel and programme;
- multilingual display name/title/description/category;
- icon, rating and episode number;
- start/stop with `+/-HHMM`, no-offset and common malformed variants;
- normalize accepted timestamps to epoch milliseconds/UTC;
- gzip stream by content/header detection;
- cancellation and hard limits for compressed bytes, decoded bytes, XML depth, text, attributes, channel count and programme count;
- DTD/external entity rejection.

Batching:

- channel batches about 100;
- programme batches about 500–1000 based on measured transaction size;
- flush channel batch before first programme when feeds put channels first;
- never materialize the entire guide.

## Database

- immutable EPG revision per source;
- previous-good activation;
- programme window pruning, initially `now - 12h` through `now + 7d`;
- indexes on XMLTV channel ID and `(channelId, start, stop)`;
- source correction and channel-specific correction stored explicitly.

## Matching priority

1. exact non-empty `tvg-id`;
2. exact normalized provider name;
3. explicit alias/manual override;
4. candidate suggestion only.

Never auto-bind an ambiguous fuzzy match.

## Initial UI

- now/next on channel cards and player overlay;
- programme progress;
- EPG unavailable state;
- no full two-dimensional guide until now/next correctness is proven.

## Exit gate

- large compressed XMLTV remains bounded;
- timezone offsets normalize correctly;
- failed EPG refresh keeps previous-good data;
- now/next query is indexed and bounded.

---

# Work package 09 — Guide, search, favorites and overlays

## Purpose

Add high-value catalog navigation after channel/playback/now-next are stable.

## Features

- bounded two-dimensional EPG viewport;
- channel/name/programme search using Room 3 FTS5;
- favorites and hidden channels through profile overlay entities;
- recent channels/history with retention cap;
- manual channel alias and merge/split journal;
- profile-scoped ordering and group visibility.

## Rules

- default profile remains `Основной`;
- provider refresh never overwrites user overlays;
- guide loads only visible time/channel windows plus small prefetch margins;
- search result identity is canonical channel/programme ID;
- no automatic fuzzy channel merge in alpha.

---

# Work package 10 — Playback health and bounded recovery

## Purpose

Improve reliability without aggressive probing or opaque automatic behavior.

## First implementation

- passive observations from actual playback attempts;
- first-frame latency;
- HTTP/manifest/decoder/surface/audio failure family;
- consecutive failure count by variant and device capability class;
- short cooldown/circuit state;
- manual retry and manual alternate-variant selection;
- one documented bounded retry for transient failures.

## Later in package

- low-frequency active probe only when user enables source health checks;
- variant ranking with hysteresis;
- failover reason shown in player UI;
- return to preferred variant after cooldown only with evidence.

## Prohibited

- continuous background probing of every stream;
- unbounded retry;
- silent cross-provider switching;
- treating emulator codec behavior as real-device capability evidence.

---

# Work package 11 — Performance, release hardening and alpha distribution

## Baseline profile and Macrobenchmark

Create modules:

- `baseline-profile`;
- `benchmark`.

Critical user journeys:

1. cold start to first focus;
2. open channel browser;
3. scroll/switch group;
4. start channel to first frame;
5. switch 20 channels;
6. return and restore focus;
7. import a medium playlist.

Generate profiles for each release. Benchmark on a physical device before making performance claims.

## Release build

- create a non-minified benchmark/profile variant;
- set production release `isMinifyEnabled = true` after keep-rule verification;
- enable resource shrinking where supported;
- add Hilt/Room/Media3 keep rules only when required by tooling output;
- verify Baseline/Startup Profile packaging;
- verify reproducible version metadata and release notes.

## Compatibility

- build APK/AAB and inspect native libraries;
- support required 32/64-bit ABIs if native dependencies are introduced;
- run 16 KB page-size compatibility checks;
- no native player dependency may enter release without ABI and page-size evidence;
- verify TV manifest, banner, launcher intent, no-touch requirements and controller flow.

## Security and supply chain

- pinned GitHub Action SHAs;
- Gradle dependency verification and lock strategy;
- dependency/license/provenance inventory;
- APK signature verification;
- secret scan of source, logs and evidence;
- diagnostic export redaction review.

## Alpha gate

- Full and DeviceMatrix green on exact release commit;
- at least one current Google/Android TV device and one constrained/older device;
- Fire TV smoke run before claiming Fire TV support;
- four-hour playback and 100-switch endurance evidence;
- no known plaintext secret or staging-catalog exposure;
- rollback/re-authentication instructions documented.

---

# Work package 12 — Alternative engine evidence gate

Media3 remains primary through alpha.

Only after a representative codec/protocol/device corpus exists, compare LibVLC and libmpv behind `PlaybackEngine` using identical inputs and measurements:

- APK/ABI/page-size cost;
- startup and zapping;
- memory/CPU;
- HLS/progressive/TS and malformed stream behavior;
- HEVC/AV1/interlaced content;
- subtitles;
- audio passthrough;
- HDR/tonemapping;
- Fire TV and vendor decoders;
- crash isolation and maintenance burden.

Rust is not added merely to wrap a native engine. A Rust/native component requires a separate ADR proving a boundary that Kotlin/Media3 cannot meet with acceptable reliability or performance.

---

# 5. Merge and verification order

```text
PR #10 secure remote refresh
  -> close PR #9
  -> TV emulator harness
  -> source scheduling/status
  -> active playback catalog
  -> MediaSessionService playback
  -> channel browser/player UI
  -> source management UI
  -> XMLTV now/next
  -> guide/search/overlays
  -> health/recovery
  -> performance/release alpha
```

Do not combine the emulator harness, WorkManager scheduling, MediaSessionService and channel UI into one PR. Each changes a different Android lifecycle boundary and needs independent rollback.

# 6. Gate matrix

| Change | Fast | Full | DeviceCurrent | DeviceMatrix | Physical device |
|---|---:|---:|---:|---:|---:|
| Parser/model only | yes | yes | no | no | no |
| Source network/refresh | yes | yes | optional | before release | no |
| Credential/Keystore | yes | yes | yes | yes | release checkpoint |
| Room migration/activation | yes | yes | yes | yes | release checkpoint |
| WorkManager/Hilt worker | yes | yes | yes | release checkpoint | no |
| MediaSession/player/surface | yes | yes | yes | yes | mandatory |
| TV focus/navigation | yes | yes | yes | yes | mandatory |
| Baseline/performance | yes | yes | smoke only | smoke only | mandatory |
| Codec support claim | yes | yes | no | no | representative devices |

# 7. Immediate execution checklist

- [x] Diagnose PR #10 Full failure.
- [x] Add direct `catalog:ingest` dependency.
- [x] Share HTTP resources and clients through Hilt.
- [ ] Add request defaults and one representative refresh contract.
- [ ] Obtain green Full run for PR #10.
- [ ] Final security/diff review.
- [ ] Merge PR #10 and close PR #9.
- [ ] Create and implement Android TV emulator harness branch.
- [ ] Run first `DeviceCurrent` and record WHPX/system-image evidence.
- [ ] Begin source scheduling/status package.

# 8. Definition of alpha-ready

MuxTV is alpha-ready only when a user can, using a TV remote:

1. add a secure remote M3U source;
2. refresh it now and on a reliable schedule;
3. browse the active channel catalog with deterministic focus;
4. start, pause, retry and switch channels through a service-owned Media3 session;
5. return to the same focused channel;
6. see now/next programme data when XMLTV is available;
7. recover from missing credentials or a failed refresh without losing the previous-good catalog;
8. run on the supported old/current Android TV matrix;
9. install a signed minified release carrying a generated Baseline Profile;
10. produce redacted diagnostics that identify the failure family without disclosing source secrets.
