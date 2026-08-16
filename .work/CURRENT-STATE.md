---
status: reviewed_snapshot
last_reviewed: 2026-08-16
architecture_version: 2
# Compatibility alias: reviewed snapshot, not dynamic HEAD.
implementation_source_commit: faa179a1301ab9b0977cc8991aee803b647ba7ba
reviewed_main_commit: faa179a1301ab9b0977cc8991aee803b647ba7ba
live_state_authority: git
---

# Текущее состояние

## Что означает этот документ

Этот файл — **durable reviewed snapshot**, а не динамический снимок GitHub. Он проверен против `main@faa179a1301ab9b0977cc8991aee803b647ba7ba` (через PR #171).

Точный `HEAD`, текущая ветка и dirty-state берутся из Git во время выполнения. Состояние PR/Issues берётся из GitHub во время выполнения и намеренно не фиксируется здесь как «текущее»: оно может измениться без единого commit и поэтому не является versioned repository truth.

`implementation_source_commit` временно сохранён как compatibility alias для существующего tooling и равен `reviewed_main_commit`. Он **не означает**, что live HEAD обязан совпадать с этим SHA.

## Классификация

MuxTV находится в стадии **functional pre-alpha / stabilization before MVP 0.1 alpha**.

Reviewed snapshot уже включает закрытый продуктовый контур Source/Catalog/EPG, bounded Search и Guide, service-owned Media3 playback/recovery, Doctor Lite, внешний HTTP/HTTPS playback через общий playback service, shared TV player surface, dependency-aware architecture guards и risk-based CI с отдельным exact-candidate integration lane.

Следующая долговременная цель — не расширение feature scope, а стабилизация перед alpha: repository live-state contract, EP-08 external/progressive evidence, Lounge Light TV focus/lifecycle, затем устранение dual seek ownership (#132) и release evidence.

## Принятая база snapshot

- Repository: `MuxTV/Muxtv`, default branch `main`, private, BSD 3-Clause.
- Reviewed main: `faa179a1301ab9b0977cc8991aee803b647ba7ba` (PR #171).
- Android application: `app.muxtv.tv`, versionCode=1001, versionName=`0.1.0-alpha.1`, minSdk=26.
- Architecture: нормативная v2; implementation progress не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: v10.
- Фактический Gradle graph snapshot: 27 модулей плюс included build `build-logic`.
- CI snapshot: PR Fast host validation, risk-based Android TV `DeviceCurrent`, manual exact-candidate Integration acceptance gate с host Full + API26/API36 DeviceMatrix, отдельные measurement/benchmark/migration lanes.

## Реализованный продуктовый контур

### Source, catalog и EPG

- secure source URL policy, exact-origin HTTP approval и Keystore-backed credentials;
- bounded streaming M3U/XMLTV parsing/decoding;
- immutable source/EPG revisions, staging, atomic publication и previous-good retention;
- durable refresh ownership, cancellation и supersession;
- deterministic EPG matching, bounded Now/Next и Guide windows;
- active/current-revision + selected-profile-visible truth contract;
- Channels Room Paging с bounded loaded window, Favorites и Recent.

### Search и Guide

- bounded Unicode Search top-N: 100 результатов по умолчанию, максимум 200, explicit `isTruncated`;
- Search debounce/cancellation/retry, Player/Back query и canonical focus restoration;
- Guide presentation projection вынесена из Compose;
- deterministic `READY` / `NO_GUIDE` / `SOURCE_CONFLICT` presentation;
- bounded Guide window и сохранение focus identity при reload/Player→Back.

### Playback и external playback

- один process-owned `MediaSessionService`/ExoPlayer authority;
- service-owned first-rendered-frame success boundary;
- bounded same-channel recovery и redacted playback observations;
- external `ACTION_VIEW` HTTP/HTTPS через тот же playback service;
- opaque process-local external lease, exact-origin cleartext approval и sanitized external metadata;
- runtime `ACCESS_LOCAL_NETWORK` boundary для API37+, без ложного API36 prompt;
- shared catalog/external TV player surface;
- audio/subtitle selectors, transient seek HUD и coalesced seek behavior.

**Известное архитектурное отклонение:** после PR #166 остаются два владельца seek/coalescing policy (UI + service). Это не считается целевым состоянием; residual зафиксирован в Issue #132 и должен быть устранён после стабилизации текущих external/UI изменений.

### Diagnostics, measurement и release foundation

- Doctor Lite presentation/export без secrets;
- deterministic 1k/10k/50k M3U corpus и repeated-series tooling;
- JMH и Macrobenchmark/Baseline Profile foundation;
- release identity, R8/resource optimization и signing/evidence contracts;
- self-hosted runner preflight/cleanup и exact-source-head provenance;
- dependency-aware architecture guards на реальные imports/dependencies/version-catalog coordinates;
- risk-based PR routing и один manual integration acceptance lane.

## Последние принятые этапы reviewed snapshot

- PR #159 — Search S5 measurement baseline;
- PR #160 — bounded published-results Search refresh optimization;
- PR #161 — S5 truth/evidence-policy reconciliation;
- PR #162 — Guide S6 presentation projection closure;
- PR #164 — real-time Room paging invalidation wait fix для device matrix;
- PR #165 — external playback EP-01..EP-03 через shared playback service;
- PR #166 — shared TV player surface, track selectors и coalesced seek EP-04..EP-07;
- PR #169 — dependency-aware architecture guards;
- PR #170 — корректная API37 boundary для `ACCESS_LOCAL_NETWORK`;
- PR #171 — risk-based PR gates и single integration acceptance lane.

Это список **принятых в reviewed snapshot этапов**, а не live GitHub coordination list.

## Стабилизационная последовательность перед MVP 0.1 alpha

1. **Repository control plane** — разделить reviewed snapshot и live state; Git/HEAD не должен восстанавливаться из исторического ancestor как будто это текущая ветка.
2. **External/progressive EP-08** — закрыть exact-head host/device failures, доказать first-frame/Range/seek/rebuffer/cleanup без таймаут-инфляции и без расширения ownership.
3. **Lounge Light TV** — freeze feature scope; исправить lifecycle/focus failures, включая 720p и deterministic focus restoration.
4. **Issue #132** — оставить единственного service-owned seek/coalescing authority; UI публикует intent и presentation state, но не владеет final seek scheduling.
5. **Repository/CI hygiene** — residual Issues, remote branch namespace, protection/admission enforcement и focused DB suites.
6. **Alpha release evidence** — API37 private-LAN smoke, weak/real Android TV, long playback/channel switching/network recovery и codec/HDR/audio claims только на соответствующем hardware.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution не является обязательным PR или release gate. 50k corpus остаётся synthetic correctness/stress asset и manual stress lane. Large-catalog correctness доказывается bounded architecture + reachability/correctness evidence; absolute performance claims требуют отдельного hardware/release evidence.

## Известные gaps reviewed snapshot

- EP-08 progressive/Range evidence ещё не принято;
- Lounge Light redesign ещё не имеет принятого TV focus/lifecycle evidence;
- dual seek ownership (#132);
- source-refresh Doctor diagnostics residual;
- reboot/unlock/package-replace lifecycle contract (#118);
- Baseline Profile/CUJ closure;
- signing/SBOM/physical-device release evidence;
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
