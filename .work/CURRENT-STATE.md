---
status: reviewed_snapshot
last_reviewed: 2026-08-17
architecture_version: 2
# Compatibility alias: reviewed snapshot, not dynamic HEAD.
implementation_source_commit: 18b520a92836f9e61161dc9ce94e4fc7ded58b6b
reviewed_main_commit: 18b520a92836f9e61161dc9ce94e4fc7ded58b6b
live_state_authority: git
---

# Текущее состояние

## Что означает этот документ

Этот файл — **durable reviewed snapshot**, а не динамический снимок GitHub. Он проверен против принятого `main@18b520a92836f9e61161dc9ce94e4fc7ded58b6b` после стабилизационного merge train PR #172/#173/#167/#168.

Точный `HEAD`, текущая ветка и dirty-state берутся из Git во время выполнения. Состояние PR/Issues берётся из GitHub во время выполнения и намеренно не фиксируется здесь как «текущее»: оно может измениться без единого commit и поэтому не является versioned repository truth.

`implementation_source_commit` сохранён как compatibility alias и равен `reviewed_main_commit`. Он **не означает**, что live HEAD обязан совпадать с этим SHA; descendant branch является нормальным `ancestor` drift.

## Классификация

MuxTV находится в стадии **functional pre-alpha / stabilization before MVP 0.1 alpha**.

Reviewed snapshot включает закрытый Source/Catalog/EPG/Search/Guide контур, service-owned Media3 playback/recovery, Doctor Lite, внешний HTTP/HTTPS playback, EP-08 progressive resilience evidence, Lounge Light TV shell/focus stabilization, dependency-aware architecture guards и risk-based CI.

Главный оставшийся архитектурный долг перед performance/release tuning — **Issue #132: удалить dual seek ownership и оставить один service-owned seek mutation/coalescing authority**. После него решения по back-buffer/cache принимаются только на измерениях #27/#109.

## Принятая база snapshot

- Repository: `MuxTV/Muxtv`, default branch `main`, private, BSD 3-Clause.
- Reviewed main: `18b520a92836f9e61161dc9ce94e4fc7ded58b6b` — integration merge PR #168 поверх принятого #167, #172 и #173.
- Android application: `app.muxtv.tv`, versionCode=1001, versionName=`0.1.0-alpha.1`, minSdk=26.
- Architecture: нормативная v2; implementation progress не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: v10.
- Gradle graph snapshot: 27 модулей плюс included build `build-logic`.
- CI: PR Fast host validation, risk-based Android TV `DeviceCurrent`, manual exact-candidate Integration acceptance gate с host Full + API26/API36 DeviceMatrix, отдельные measurement/benchmark/migration lanes.

## Реализованный продуктовый контур

### Source, catalog, EPG, Search и Guide

- secure source URL policy, exact-origin HTTP approval и Keystore-backed credentials;
- bounded streaming M3U/XMLTV parsing/decoding;
- immutable source/EPG revisions, staging, atomic publication и previous-good retention;
- durable refresh ownership, cancellation и supersession;
- deterministic EPG matching, bounded Now/Next и Guide windows;
- Channels Room Paging с Favorites/Recent и deterministic focus restoration;
- bounded Unicode Search top-N, debounce/cancellation/retry и canonical Player/Back restoration;
- Guide presentation projection вне Compose и deterministic `READY` / `NO_GUIDE` / `SOURCE_CONFLICT` states.

### Playback и external playback

- один process-owned `MediaSessionService`/ExoPlayer;
- service-owned first-rendered-frame success boundary;
- bounded same-channel recovery и redacted playback observations;
- external `ACTION_VIEW` HTTP/HTTPS через тот же playback service;
- opaque process-local external lease, exact-origin cleartext approval и sanitized external metadata;
- runtime `ACCESS_LOCAL_NETWORK` boundary для API37+;
- shared catalog/external TV player surface;
- audio/subtitle selectors и transient seek HUD;
- EP-08: stock Media3 progressive/retry/range/no-range evidence и реальный Android D-pad non-terminal external seek после first frame;
- MediaSession callback result contract исправлен без dependency bump;
- Lounge Light player presentation интегрирован с native remote-input boundary.

**Известное архитектурное отклонение:** UI `PlayerSurfaceContent` всё ещё создаёт собственный `PlaybackSeekController` и в итоге вызывает `MediaController.seekTo(targetMs)`, тогда как service отдельно владеет `PlaybackSeekController` и перехватывает relative seek commands. Это остаточный dual ownership из #166; Issue #132 должен свести оба входа к одному service-owned semantic seek protocol.

### Lounge Light TV

- стабильный 88dp layout slot для rail и overlay-like expansion без reflow content;
- bidirectional D-pad filter graph;
- deterministic focus restoration в Channels/Settings;
- bounded `LazyColumn` Source details с 720p focus containment;
- reduced-motion policy для optional scaling;
- real-data-only Home/Guide/Search/Settings presentation;
- принятый exact-head DeviceCurrent после интеграции #167.

### Diagnostics, measurement и release foundation

- Doctor Lite presentation/export без secrets;
- deterministic 1k/10k/50k M3U corpus и repeated-series tooling;
- JMH и Macrobenchmark/Baseline Profile foundation;
- release identity, R8/resource optimization и signing/evidence contracts;
- self-hosted runner preflight/cleanup и exact-source-head provenance;
- dependency-aware architecture guards;
- reviewed-snapshot/live-state separation;
- risk-based PR routing и manual integration acceptance lane.

## Последние принятые этапы reviewed snapshot

- PR #169 — dependency-aware architecture guards;
- PR #170 — корректная API37 boundary для `ACCESS_LOCAL_NETWORK`;
- PR #171 — risk-based PR gates и single integration acceptance lane;
- PR #173 — корректные `SessionResult` codes из MediaSession player-command callback;
- PR #172 — разделение durable reviewed snapshot и live Git/GitHub state;
- PR #167 — EP-08 progressive resilience + native external D-pad seek evidence;
- PR #168 — Lounge Light TV stabilization и интеграция поверх принятого EP-08.

Порядок списка отражает причинную стабилизационную цепочку, а не числовую сортировку PR.

## Следующая последовательность перед MVP 0.1 alpha

1. **Issue #132 — single service-owned seek authority.** Один semantic request contract для relative/absolute seek; UI хранит только provisional presentation state и не вызывает final player mutation.
2. **Seek/rebuffer measurements (#27).** После единого owner измерить input→apply/render, coalescing ratio, rebuffer и memory/startup trade-offs.
3. **Measured buffer policy (#109), только если evidence оправдывает изменение.** Никаких скопированных constants или SimpleCache до измерений.
4. **Doctor residual (#30).** Подключить coarse secret-free seek/rebuffer observations к уже существующей диагностике.
5. **Repository/CI hygiene.** Закрыть остаточные issue/branch/protection и focused DB contracts без расширения feature scope.
6. **Alpha release evidence (#31).** API37 private-LAN smoke, API matrix, Baseline Profile/CUJ, signing/SBOM, physical weak/current TV и hardware-specific codec/HDR/audio claims.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution не является обязательным PR или release gate. 50k corpus остаётся synthetic correctness/stress asset и manual stress lane. Absolute performance claims требуют отдельного hardware/release evidence.

## Известные gaps reviewed snapshot

- dual seek ownership (#132);
- source-refresh Doctor diagnostics residual (#30);
- reboot/unlock/package-replace lifecycle contract (#118);
- Baseline Profile/CUJ closure;
- signing/SBOM/physical-device release evidence (#31);
- API37 private-LAN permission smoke.

## Live-state protocol

Перед любой новой execution-сессией:

1. получить exact Git `HEAD`, branch и dirty state из checkout;
2. сравнить exact HEAD с `reviewed_main_commit` и явно классифицировать relation (`exact`, `ancestor`, `diverged`, `missing`);
3. получить PR/Issue state из GitHub, если он нужен для задачи;
4. не использовать старые remote `tmp/*`, `backup/*`, `rebuild/*` как execution base только потому, что они существуют;
5. evidence считать действительным только для точного SHA, на котором оно было получено.

`tools/ci/Get-RepositoryLiveState.ps1` является offline Git-reader для пунктов 1–2. GitHub coordination state остаётся отдельным live source.

## Evidence limits

API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal, real-network или absolute performance. Эти claims требуют physical-device evidence.
