---
status: proposed
last_reviewed: 2026-07-20
project: MuxTV/Muxtv
execution_mode: local_validation_without_github_actions
baseline_branch: main
---

# Reference Adoption and Phase 01–05 Implementation Plan

## 1. Purpose

This plan defines how MuxTV reuses proven architecture, implementation patterns, test cases and product workflows from relevant open-source projects while retaining the current `MuxTV/Muxtv` codebase as the only product foundation.

The technical selection is intentionally independent of repository license. License does not remove a useful repository from the research set. Every adopted idea must still record its origin and adoption mode:

- `adapt`: adapt a bounded implementation pattern;
- `clean-room`: study behavior and implement independently against MuxTV contracts;
- `test-corpus`: derive fixtures, failure scenarios and expected behavior;
- `architecture-reference`: reuse boundaries and organization, not code;
- `capability-reference`: use as an alternative implementation for comparison and device/codec evidence.

No existing IPTV application becomes a fork base. MuxTV remains an Android-first modular monolith with Room behind repositories, Media3 behind `PlaybackEngine`, TV-first Compose UI and local-first storage.

## 2. Current constraint: GitHub Actions quota exhausted

GitHub-hosted workflow execution is not available for the current account period. Until quota is restored:

1. Do not open implementation PRs against `main` merely to obtain CI status.
2. Do not delete the existing workflow or weaken future quality gates.
3. Develop on isolated branches and produce local evidence for every work package.
4. Treat local Gradle output, emulator/device output, exported schemas, screenshots and benchmark JSON as the temporary verification record.
5. Store concise evidence manifests under `.work/evidence/`; do not commit large binary logs or APKs unless explicitly required.
6. A work package is not considered complete only because it compiles in the IDE.
7. When Actions quota returns, enable one consolidated CI verification pass before merging accumulated implementation branches.

### 2.1. Temporary local evidence layout

```text
.work/evidence/
  2026-07-20/
    WP-01-dependency-baseline/
      evidence.md
      commands.txt
      checksums.txt
    WP-02-build-logic/
      evidence.md
      commands.txt
    ...
```

Each `evidence.md` must contain:

```yaml
work_package:
commit:
android_studio:
jdk:
gradle:
android_sdk:
device_or_emulator:
commands:
results:
known_skips:
artifacts:
reviewer_notes:
```

### 2.2. Local verification levels

#### Fast gate — required for every commit

```bash
./gradlew --no-daemon --stacktrace \
  :core:model:test \
  :catalog:api:test \
  :player:api:test \
  :player:fake:test \
  :player:media3:test \
  :core:testing:test
```

The exact task list must grow as modules are added.

#### Build gate — required before a work-package checkpoint

```bash
./gradlew --no-daemon --stacktrace \
  clean \
  assembleDebug \
  lintDebug \
  testDebugUnitTest
```

#### Database/device gate — required for schema or UI changes

```bash
./gradlew --no-daemon --stacktrace connectedDebugAndroidTest
```

Use a pinned emulator image and at least one physical Android TV or Fire TV device for release-relevant playback changes.

#### Performance gate — required only for performance-sensitive checkpoints

```bash
./gradlew --no-daemon --stacktrace \
  :baseline-profile:connectedBenchmarkAndroidTest \
  :benchmark:connectedBenchmarkAndroidTest
```

Performance claims must come from physical-device evidence. Emulator results are smoke tests only.

### 2.3. Local scripts to add early

```text
scripts/verify-fast.ps1
scripts/verify-fast.sh
scripts/verify-full.ps1
scripts/verify-full.sh
scripts/verify-device.ps1
scripts/verify-device.sh
scripts/collect-evidence.ps1
scripts/collect-evidence.sh
```

Scripts must call Gradle tasks; they must not duplicate build logic. Windows PowerShell is first-class because the primary development environment is Windows.

Optional `act` usage is allowed only to validate GitHub workflow YAML and shell assumptions. It is not accepted as equivalent to GitHub-hosted runners.

## 3. Stable project baseline

Keep the existing stable baseline unless a dedicated upgrade work package proves compatibility:

| Area | Library/tool | Version |
|---|---|---:|
| Build | Android Gradle Plugin | `9.3.0` |
| Language | Kotlin | `2.4.10` |
| Symbol processing | KSP | `2.3.10` |
| JVM | JDK | `17` |
| SDK | compile/target SDK | `37` |
| Minimum SDK | Android | `26` |
| UI | Compose BOM | `2026.06.00` |
| TV UI | `androidx.tv:tv-material` | `1.1.0` |
| TV primitives | `androidx.tv:tv-foundation` | `1.0.0` |
| Navigation | Navigation 3 | `1.1.4` |
| Playback | Media3 | `1.10.1` |
| Database | Room 3 | `3.0.0` |
| DI | Dagger Hilt | `2.60.1` |
| AndroidX Hilt | Hilt integrations | `1.4.0` |
| Background | WorkManager | `2.11.2` |
| Preferences | DataStore | `1.2.1` |
| Activity | Activity | `1.13.0` |
| Lifecycle | Lifecycle | `2.11.0` |
| Benchmark | Macrobenchmark/Baseline Profile | `1.4.1` |
| Profile installation | ProfileInstaller | `1.4.1` |

Upgrade Coroutines from `1.10.2` to `1.11.0` in an isolated work package before introducing concurrent source refresh and playback-controller work.

## 4. Expanded source matrix

## 4.1. Build, modularization and offline-first

### `android/nowinandroid`

Use as `architecture-reference` and bounded `adapt` source.

Review:

- `settings.gradle.kts`;
- `build-logic/README.md`;
- `build-logic/convention`;
- `benchmarks`;
- `core/testing`;
- `core/screenshot-testing`;
- sync/work modules;
- baseline profile generator.

Adopt:

- included-build convention plugins;
- additive single-responsibility plugin design;
- local database as source of truth;
- background sync through WorkManager;
- test-data modules and fake repositories;
- macrobenchmark/baseline-profile structure;
- dependency-direction tests.

Reject:

- copying the complete module graph;
- splitting every MuxTV feature into `api` and `impl` before a real need;
- analytics/notification infrastructure unrelated to MuxTV.

### `android/architecture-samples`

Use as secondary `architecture-reference` for unidirectional data flow, repositories, ViewModel state and testable use cases.

Adopt only patterns that simplify MuxTV. Do not add a general `domain` or `data` module merely to resemble a sample.

## 4.2. TV UI, D-pad and focus

### `android/tv-samples`

Primary `adapt` source:

- `JetStreamCompose`;
- `ReferenceAppKotlin`;
- `TvMaterialCatalog`;
- `JetStreamCompose/.../ModifierUtils.kt`.

Adopt:

- D-pad key normalization;
- system-navigation key handling;
- deterministic initial focus;
- `FocusRequester` ownership;
- focused-child save/restore;
- focus restoration when returning from player/details;
- TV Material state visuals;
- accessibility semantics.

Do not copy sample dependency versions or demo navigation structure.

### `NuvioMedia/NuvioTV`

Use as `product-reference` and visual reference only.

Study:

- TV-first visual hierarchy;
- onboarding;
- cards, rows and settings layout;
- player overlay;
- baseline-profile journeys.

Reject its dependency topology as a template: it combines multiple network/serialization/plugin/backend stacks and includes premature plugin/runtime scope for MuxTV.

### `akshaynikhare/FireVisionIPTV`

Use as `test-corpus` for Fire TV manifests, remote-control behavior and low-end HLS scenarios.

Do not adopt its Kotlin, Compose, Room, Navigation or Media3 versions.

## 4.3. Canonical playback architecture

### `androidx/media`

Primary canonical API source.

Review:

- `libraries/ui_compose/.../PlayerSurface.kt`;
- Media3 Compose documentation snippets;
- `media3-session` samples;
- background playback documentation;
- network-stack documentation;
- error-code definitions and release notes.

Adopt:

- `MediaSessionService` owning `Player` and `MediaSession`;
- UI connection through `MediaController`;
- `PlayerSurface` as rendering primitive;
- `TrackSelectionParameters`;
- session commands and controller permissions;
- lifecycle and media-button behavior;
- Media3 error taxonomy as input to MuxTV stable errors.

### `anilbeesetti/nextplayer`

Primary modern applied-player reference. Its 2026 baseline closely matches MuxTV.

Review:

- `feature/player/.../service/PlayerService.kt`;
- `feature/player/.../extensions/Player.kt`;
- track/subtitle dialogs and state;
- Media3 renderers configuration;
- subtitle charset handling;
- session custom commands;
- first-frame and discontinuity handling.

Adopt conceptually:

- service-owned player;
- typed custom session commands;
- remembering selected audio/subtitle tracks;
- track selection mapping;
- first-frame state;
- controller-driven player UI;
- deterministic cleanup of service coroutine scope.

Reject:

- local-file URI as durable identity;
- SMB/WebDAV/FTP modules;
- volume boost, PiP and local media-library scope in Phase 01;
- FFmpeg decoder without corpus evidence.

### `jellyfin/jellyfin-androidtv`

Use as `production-test-corpus` and `clean-room` reference.

Review:

- `playback:core`;
- `playback:media3:exoplayer`;
- `playback:media3:session`;
- quality/audio/subtitle actions;
- Android TV and Fire TV device issues;
- long-session and live-TV behavior.

Adopt scenarios, not legacy Java/Leanback architecture.

## 4.4. M3U, XMLTV and IPTV behavior

### `Davidona/StreamVault-IPTV`

Review:

- `data/.../parser/M3uParser.kt`;
- `data/.../parser/XmltvParser.kt`;
- parser tests;
- sync importer;
- provider validation;
- URL policy;
- DAO/repository flows.

Use as `test-corpus` and `clean-room` source for:

- malformed real-world M3U;
- quoted commas and attributes;
- global/per-item user-agent;
- catch-up metadata;
- streaming callbacks;
- XMLTV date variants;
- gzip inputs;
- large files.

Reject:

- loading all parsed entries into a list;
- IDs derived from list index;
- direct write into active catalog;
- returning partial success from broad exception handling.

### `oxyroid/M3UAndroid`

Review:

- parser included build;
- `app:tv`;
- TV baseline profile;
- device benchmark;
- mock server;
- playlist/channel modules;
- test fixtures.

Use for test and performance organization, not for plugin architecture, JitPack dependency topology or the full module graph.

### `kodi-pvr/pvr.iptvsimple`

Review:

- `src/iptvsimple/PlaylistLoader.cpp`;
- M3U parsing/data classes;
- EPG loader;
- catch-up controller;
- stream-property mapping;
- provider settings.

Use as mature behavior corpus for:

- IPTV dialects;
- `user-agent` and `referrer`;
- channel numbering;
- radio classification;
- catch-up templates;
- timeshift metadata;
- EPG correction.

Catch-up runtime remains deferred until Phase 05.

### `4gray/iptvnator`

Use as `product-reference` for:

- adding local/remote playlists;
- category visibility;
- channel number navigation;
- favorites across playlists;
- history/recent channels;
- search;
- EPG grid UX;
- source management;
- future Xtream/Stalker inventory;
- external-player fallback UX.

Do not adopt Electron/Angular/backend architecture.

### `iptv-org`

Use only as a versioned external data source from which deterministic test snapshots are derived.

Rules:

- no live external URL in CI or required local tests;
- replace stream URLs with MockWebServer endpoints;
- retain metadata diversity but remove credentials and questionable content;
- commit expected parse reports and fixture provenance;
- pin snapshot date and source commit.

## 4.5. Codec and alternative-engine capability references

### `videolan/vlc-android`

Use as capability reference for:

- protocol/codec matrix;
- hardware decoder failures;
- audio passthrough;
- HDR/tonemapping;
- subtitle rendering;
- network-stream behavior;
- fallback-engine benchmark design.

Do not add LibVLC in Phase 01.

### `mpv-android/mpv-android`

Review:

- `MPVLib`;
- `BaseMPVView`;
- native build scripts;
- libass and dual-subtitle behavior;
- software/hardware decoder settings.

Use for a future Phase 05 comparative ADR, not as an immediate library dependency.

### `nova-video-player/aos-AVP`

Use for:

- long playback lifecycle scenarios;
- Android TV/Amazon distribution experience;
- subtitle discovery;
- FFmpeg/dav1d ABI/build considerations;
- network-filesystem and decoder corpus.

Do not adopt its multi-repository native build as MuxTV foundation.

## 5. Approved library plan

## 5.1. Phase 01 additions

### Network

```toml
okhttp = "5.3.0"
```

```kotlin
implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
implementation("com.squareup.okhttp3:okhttp")
debugImplementation("com.squareup.okhttp3:logging-interceptor")
testImplementation("com.squareup.okhttp3:mockwebserver3")
```

One connection pool and dispatcher may be shared, but source, playback and image clients must have separate policies.

### Media3 modules

```kotlin
implementation("androidx.media3:media3-exoplayer:1.10.1")
implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
implementation("androidx.media3:media3-datasource-okhttp:1.10.1")
implementation("androidx.media3:media3-session:1.10.1")
implementation("androidx.media3:media3-ui-compose:1.10.1")
```

Do not add DASH, RTSP, SmoothStreaming, Transformer or FFmpeg decoder until a real stream corpus requires them.

### Images

```toml
coil = "3.5.0"
```

```kotlin
implementation("io.coil-kt.coil3:coil-compose:3.5.0")
implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
testImplementation("io.coil-kt.coil3:coil-test:3.5.0")
```

### Paging

```toml
paging = "3.5.0"
```

```kotlin
implementation("androidx.paging:paging-runtime:3.5.0")
implementation("androidx.paging:paging-compose:3.5.0")
testImplementation("androidx.paging:paging-testing:3.5.0")
implementation("androidx.room3:room3-paging:3.0.0")
```

### Flow testing

```toml
turbine = "1.2.1"
```

```kotlin
testImplementation("app.cash.turbine:turbine:1.2.1")
```

### Optional charset detection

```toml
juniversalchardet = "2.5.0"
```

Do not add automatically. First implement BOM detection and strict UTF-8. Add charset detection only when the fixture corpus proves a material non-UTF-8 requirement.

## 5.2. Quality tooling

```toml
kover = "0.9.8"
dependency-analysis = "3.17.0"
compose-screenshot = "0.0.1-alpha15"
```

Kover and Dependency Analysis are local-first and useful while Actions is unavailable.

Compose Screenshot Testing remains experimental. Its output may be used locally, but it must not become the only UI regression mechanism. Maintain instrumented D-pad/focus tests and selected device screenshots.

## 5.3. Later phases

```toml
kotlinx-serialization = "1.11.0"
zxing = "3.5.3"
```

Use serialization for backup/update manifests, diagnostic export, LAN control and future JSON provider APIs. Use ZXing only for QR pairing.

## 5.4. Explicitly rejected for current scope

Do not add now:

- Retrofit;
- Ktor Client or Server;
- Gson;
- Moshi;
- Jsoup;
- NanoHTTPD;
- Supabase;
- Sentry;
- LibVLC;
- libmpv;
- FFmpeg decoder;
- SQLDelight;
- another DI container;
- executable plugin runtime.

## 6. Target module graph

```text
app/tv

build-logic/convention

core/common
core/model
core/database
core/designsystem
core/ui
core/testing
core/network                 # Phase 01

catalog/api
catalog/ingest               # Phase 01

player/api
player/media3
player/fake

feature/home
feature/channels             # Phase 01
feature/player               # Phase 01 UI/controller only
feature/sources              # when source-management UI starts
feature/guide                # Phase 02

benchmark
baseline-profile
```

Do not create generic `data`, `domain`, `utils`, `shared`, `platform`, `extensions` or `provider-common` modules without a concrete boundary.

# 7. Work-package sequence

## WP-00 — Synchronize state and establish local evidence protocol

### Files

- update `README.md`;
- update `.work/CURRENT-STATE.md`;
- update `.work/meta/status.yaml`;
- update `.work/meta/modules.yaml`;
- add `.work/references/repository-adoption.md`;
- add `.work/references/library-baseline.md`;
- add `.work/references/test-corpus.md`;
- add `.work/evidence/README.md`;
- add local verification scripts.

### Required result

- documentation reflects merged Phase 00;
- every source has a pinned repository commit and adoption mode;
- local evidence format is documented;
- scripts work on Windows PowerShell and Bash;
- no GitHub Actions run is required.

## WP-01 — Dependency baseline and Coroutines 1.11.0

### Scope

- update Coroutines in isolation;
- add dependency verification;
- add dependency locking;
- add Dependency Analysis Plugin;
- prohibit dynamic/snapshot dependencies and module-local repositories.

### Local verification

```bash
./gradlew buildHealth
./gradlew test
./gradlew assembleDebug
./gradlew help --configuration-cache
./gradlew help --configuration-cache
```

### Exit gate

- no new warnings from Coroutines update;
- configuration cache reused;
- dependency analysis findings triaged;
- exact checksums recorded.

## WP-02 — Complete convention plugins

### Source

`android/nowinandroid` and current MuxTV `build-logic`.

### Add

```text
muxtv.android.application
muxtv.android.library
muxtv.android.compose
muxtv.android.tv
muxtv.android.room
muxtv.android.hilt
muxtv.android.test
muxtv.android.benchmark
muxtv.kotlin.library
muxtv.quality
```

Each plugin has one responsibility. Do not create an all-in-one plugin.

### Exit gate

- no duplicated Android/Kotlin settings;
- Room schema export standardized;
- architecture tests enforce dependency directions;
- build-logic tests pass locally.

## WP-03 — Local verification harness replacing unavailable hosted CI

### Add

- fast/full/device verification scripts;
- evidence collector;
- task matrix documentation;
- pinned emulator/device configuration;
- optional local workflow syntax validation.

### Do not

- remove future GitHub workflow gates;
- claim local `act` is identical to GitHub-hosted runners;
- commit large binary logs.

### Exit gate

A new contributor can run one documented command and obtain a deterministic pass/fail result plus an evidence manifest.

## WP-04 — Shared network foundation

### Source

Official Android network-security guidance, Media3 network guidance, StreamVault URL policy, M3UAndroid mock server and Jellyfin edge cases.

### Module

`core/network`

### Add

```text
MuxTvHttpResources
SourceHttpClient
PlaybackHttpClient
ImageHttpClient
SourceNetworkPolicy
PlaybackNetworkPolicy
ImageNetworkPolicy
RedirectPolicy
AddressPolicy
HeaderPolicy
ResponseSizePolicy
DecompressionPolicy
NetworkTimeouts
NetworkFailure
RedactedRequest
```

### Policy

- HTTPS default;
- HTTP requires explicit warning/confirmation;
- credentials over HTTP disabled by default;
- cross-host redirect strips sensitive headers;
- loopback, link-local and private-address policy explicit;
- source responses bounded by downloaded and decompressed size;
- all requests cancellable;
- shared pool/dispatcher but separate policy clients.

### Local tests

MockWebServer scenarios:

- redirect loop;
- cross-host redirect;
- HTTP downgrade;
- slow/truncated responses;
- wrong content length;
- gzip bomb;
- cancellation;
- connection reset;
- 401/403/404/429/5xx;
- log redaction.

## WP-05 — Credential storage

### Use

Android Keystore plus AES-GCM. Room/DataStore store only opaque credential references and encrypted envelopes where required.

### Add

```text
CredentialId
CredentialEnvelope
CredentialStore
CredentialAccessResult
CredentialRedactor
```

### Exit gate

- no plaintext credential in database export;
- no secret in URL/log/exception;
- missing key produces recoverable re-authentication state.

## WP-06 — Streaming M3U parser and fixture corpus

### Sources

StreamVault, M3UAndroid, Kodi IPTV Simple, IPTVnator and sanitized iptv-org snapshots.

### Module

`catalog/ingest`

### API

```kotlin
interface M3uParser {
    suspend fun parse(
        input: InputStream,
        sink: M3uRecordSink,
        limits: M3uParseLimits,
    ): M3uParseReport
}
```

Never return the complete playlist as a `List`.

### Support

- BOM and strict UTF-8;
- bounded optional charset detection;
- CRLF/LF;
- quoted commas;
- quoted/unquoted attributes;
- unknown and duplicate attributes;
- `#EXTM3U`, `#EXTINF`, `#EXTGRP`, `#EXTVLCOPT`;
- TVG metadata;
- channel numbers;
- groups/language/country/radio;
- user-agent/referrer;
- catch-up metadata stored but not executed.

### Limits

- downloaded bytes;
- decompressed bytes;
- line length;
- attributes per line;
- attribute/title/URL length;
- record count;
- retained issue count;
- parse deadline;
- cancellation.

### Exit gate

- bounded memory;
- malformed record does not abort the complete import;
- fatal and record-level errors are distinct;
- parser has no Room dependency.

## WP-07 — Immutable source revisions and atomic activation

### Add Room entities

```text
SourceRevisionEntity
StagedProviderChannelEntity
StagedStreamVariantEntity
SourceRefreshAttemptEntity
SourceRefreshIssueEntity
ActiveSourceRevisionEntity
```

### Pipeline

```text
fetch → decode → parse → normalize → stage → validate
→ calculate diff → atomic activate → retain previous-good → cleanup
```

### Invariants

- active revision immutable;
- UI reads active revision only;
- failed refresh cannot alter active catalog;
- process death before commit keeps previous-good revision;
- profile overlays survive provider refresh;
- identity never depends on list order or raw URL;
- refresh is idempotent.

### Exit gate

Database instrumentation tests demonstrate rollback, process-interruption recovery, no-op refresh and overlay preservation.

## WP-08 — Process-owned Media3 playback

### Sources

Official Media3 first, NextPlayer second, Jellyfin production cases third.

### Add

```text
player/media3/service/MuxTvPlaybackService
player/media3/session/MediaSessionFactory
player/media3/controller/PlaybackControllerClient
player/media3/factory/Media3PlayerFactory
player/media3/datasource/Media3DataSourceFactory
player/media3/mapper/MediaItemMapper
player/media3/mapper/TrackMapper
player/media3/mapper/PlaybackStateMapper
player/media3/mapper/PlaybackErrorMapper
player/media3/recovery/PlaybackRetryPolicy
```

### Architecture

```text
Compose UI → MediaController → MediaSessionService
→ MediaSession → ExoPlayer → OkHttpDataSource
```

### Implement

- service-owned player;
- HLS and progressive HTTP;
- controller reconnection;
- Activity/surface recreation;
- audio focus and media buttons;
- first-frame event;
- semantic audio/subtitle tracks;
- language preference;
- per-stream headers;
- cancellation during prepare;
- bounded retry;
- stable MuxTV error codes;
- previous-channel primitive;
- no automatic failover before Phase 03.

### Exit gate

- Activity recreation does not stop playback;
- raw Media3 types do not leave `player/media3`;
- 100 channel switches show no player/surface leak on a physical device.

## WP-09 — TV focus and channel browser

### Sources

JetStreamCompose, official TV quality guidance, IPTVnator workflows, M3UAndroid and NuvioTV visual reference.

### Modules

`core/ui/focus`, `feature/channels`

### Add

```text
TvKeyEvent
TvKeyHandler
FocusBookmark
FocusAnchor
FocusRestorationState
InitialFocusController
ChannelBrowserState
ChannelCard
ChannelGrid
ChannelGroupRail
ChannelContextMenu
```

### Rules

- standard Compose lazy layouts;
- stable item keys;
- focus restored by item ID, not index;
- separate focus memory by group;
- valid focus target for loading/error/empty states;
- Paging 3.5.0;
- Coil 3.5.0;
- Navigation 3;
- five-button D-pad and Back complete all primary flows.

### Exit gate

- 100k-channel fixture is not loaded into UI memory as a full list;
- refresh preserves focus when the item survives;
- Back returns to the same channel card;
- logo failures do not alter card geometry or focus.

## WP-10 — Diagnostics and endurance

### Add

```text
DiagnosticContext
CorrelationId
SourceRefreshTrace
PlaybackAttemptTrace
NetworkTrace
RedactedUri
PlaybackRecoveryDecision
RetryBudget
DeviceCapabilitySnapshot
```

### Error families

```text
SOURCE_NETWORK
SOURCE_TOO_LARGE
SOURCE_ENCODING
SOURCE_FORMAT
CATALOG_VALIDATION
PLAYBACK_HTTP
PLAYBACK_TIMEOUT
PLAYBACK_MANIFEST
PLAYBACK_DECODER
PLAYBACK_UNSUPPORTED
PLAYBACK_SURFACE
PLAYBACK_AUDIO
DEVICE_CAPABILITY
UNKNOWN
```

### Local device evidence

- 100 channel switches;
- four-hour playback;
- repeated background/foreground;
- Activity recreation loop;
- disconnect/reconnect;
- HDMI/audio route change;
- low-memory condition;
- process recreation.

## WP-11 — Baseline profile and performance

### Modules

`benchmark`, `baseline-profile`

### Journeys

1. cold start;
2. first focus;
3. Home to Channels;
4. channel-list scroll;
5. group switch;
6. start channel;
7. first frame;
8. switch 20 channels;
9. return and restore focus;
10. import medium playlist.

### Metrics

- TTID/TTFD;
- time to first focus;
- time to channel list;
- time to first frame;
- zapping latency;
- frame timing;
- memory/allocation;
- parser throughput;
- activation duration.

### Exit gate

- baseline profile included in release APK;
- physical-device JSON results stored outside GitHub Actions;
- regression thresholds documented before automation returns.

# 8. Later phases

## Phase 02 — EPG

### Secure XMLTV ingestion

Use platform `XmlPullParser`, bounded gzip/zip streams, Room 3 staging and previous-good activation. Do not use DOM or a general XML serialization framework.

Support timezone/DST, malformed and overlapping programmes, multilingual metadata, categories, ratings and episode identifiers. Disable DTD/external entities and apply byte, depth, text and decompression limits.

### EPG identity and matching

Priority:

1. exact `tvg-id`;
2. normalized exact name;
3. alias table;
4. manual override;
5. evidence-backed fuzzy candidate.

Do not auto-bind ambiguous matches.

### Guide and search

Use Room 3 FTS5 for search. Build a bounded two-dimensional guide viewport; do not load an entire EPG horizon or rely on nested unbounded lazy rows.

## Phase 03 — Smart Channels and TV Doctor

First build a labeled matching corpus, evaluator, manual merge/split and mutation journal. Auto-merge remains disabled until precision evidence is accepted.

Implement passive observations and bounded probes before failover. Failover requires hysteresis, cooldown, reason and device-scoped evidence.

## Phase 04 — QR pairing, local control and updates

Add Kotlinx Serialization 1.11.0 and ZXing 3.5.3 only when this phase starts.

Choose a LAN server library through a separate threat-model and memory-footprint ADR; do not preselect Ktor or NanoHTTPD.

Self-update must verify release channel, version, SHA-256, package name and signing certificate before using `PackageInstaller` with explicit user confirmation.

## Phase 05 — Alternative playback-engine ADR

Compare Media3 against LibVLC and libmpv using the same representative stream/device corpus.

Measure:

- APK and ABI cost;
- startup/zapping;
- memory/CPU;
- decoder coverage;
- subtitles/HDR/passthrough;
- malformed streams;
- Fire TV behavior;
- crash isolation;
- maintenance and native update cost.

Media3 remains primary unless another engine shows a material, measured corpus advantage. Any optional engine must remain behind `PlaybackEngine` and must not leak native API into UI/domain.

# 9. Merge policy while GitHub Actions is unavailable

1. Keep work packages on dedicated branches.
2. Produce a local evidence manifest for each checkpoint commit.
3. Prefer small sequential branches based on the last accepted checkpoint rather than one long uncontrolled branch.
4. Do not merge complex playback/database/network changes directly into `main` without an independent code review.
5. Do not open PRs merely for bookkeeping while the PR workflow would immediately fail due quota.
6. When quota returns:
   - rebase active branches onto current `main`;
   - run local full gates again;
   - open a draft integration PR;
   - run one complete hosted CI pass;
   - resolve environment-specific failures;
   - merge only after hosted and local evidence agree.

# 10. Final implementation order

```text
WP-00  Project state, references and local evidence protocol
WP-01  Dependency baseline and Coroutines 1.11.0
WP-02  Convention plugins
WP-03  Local verification harness

WP-04  Shared network foundation
WP-05  Credential storage
WP-06  Streaming M3U parser
WP-07  Immutable revisions
WP-08  MediaSessionService playback
WP-09  Channel browser and TV focus
WP-10  Diagnostics and endurance
WP-11  Baseline profile and benchmarks

Phase 02  XMLTV, EPG identity, guide and FTS5 search
Phase 03  Smart Channels, TV Doctor and failover
Phase 04  QR/local control and signed update
Phase 05  Alternative playback-engine benchmark ADR
```

# 11. Non-negotiable constraints

1. No foreign IPTV application becomes the codebase.
2. No second network stack without a demonstrated requirement.
3. No second playback engine before comparative evidence.
4. No URL or row index as durable channel identity.
5. No complete M3U/XMLTV `List` in memory for import.
6. No direct parser write into active tables.
7. No generic `data`, `domain`, `utils` or plugin modules without a concrete boundary.
8. No live external IPTV dependency in required tests.
9. No credentials in database exports, URLs, logs or diagnostic payloads.
10. No automatic channel merge or failover without evidence, reason and reversal.
11. No roadmap-phase mixing inside one work package.
12. No completion claim without reproducible local evidence while hosted CI is unavailable.
