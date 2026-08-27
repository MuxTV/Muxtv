---
status: reviewed_snapshot
last_reviewed: 2026-08-25
architecture_version: 2
# Compatibility alias: reviewed snapshot, not dynamic HEAD.
implementation_source_commit: 5aa9c108cc63187d8066494fb30c73b82f4e0f97
reviewed_main_commit: 5aa9c108cc63187d8066494fb30c73b82f4e0f97
live_state_authority: git
---

# Текущее состояние

## Что означает этот документ

Этот файл — **durable reviewed snapshot**, а не динамический снимок GitHub. Он проверен против принятого `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97` после принятия single service-owned seek authority и точного двух-AVD Android TV контракта.

Точный `HEAD`, текущая ветка и dirty-state берутся из Git во время выполнения. Состояние PR/Issues берётся из GitHub во время выполнения и намеренно не фиксируется здесь как «текущее»: оно может измениться без единого commit и поэтому не является versioned repository truth.

`implementation_source_commit` сохранён как compatibility alias и равен `reviewed_main_commit`. Он **не означает**, что live HEAD обязан совпадать с этим SHA; descendant branch является нормальным `ancestor` drift.

## Классификация

MuxTV находится в стадии **functional pre-alpha / stabilization before MVP 0.1 alpha**.

Reviewed snapshot включает закрытый Source/Catalog/EPG/Search/Guide контур, service-owned Media3 playback/recovery, Doctor Lite, внешний HTTP/HTTPS playback, EP-08 progressive resilience evidence, Lounge Light TV shell/focus stabilization, dependency-aware architecture guards, risk-based CI, **single service-owned seek mutation/coalescing authority** и **exact two-AVD repository/runtime contract**.

Главный стабилизационный путь перед performance/release tuning теперь не #132: seek ownership уже принят через PR #175 (`2302c114...`). Следующий evidence train — **U0 runtime UI characterization → U1 evidence-driven minimal correction → M0 measurement correctness**. Performance/DB conclusions и tuning не должны опережать M0.

## Принятая база snapshot

- Repository: `MuxTV/Muxtv`, default branch `main`, private, BSD 3-Clause.
- Reviewed main: `5aa9c108cc63187d8066494fb30c73b82f4e0f97` — принятый D0/two-AVD baseline поверх PR #175 single seek authority и последующего CI/device-contract hardening.
- Android application: `app.muxtv.tv`, versionCode=1001, versionName=`0.1.0-alpha.1`, minSdk=26.
- Architecture: нормативная v2; implementation progress не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: v10.
- Gradle graph snapshot: 27 модулей плюс included build `build-logic`.
- CI: PR Fast host validation, risk-based Android TV device routing, manual exact-candidate Integration acceptance gate с host Full + API26/API36 DeviceMatrix, отдельные measurement/benchmark/migration lanes.
- Android TV AVD policy: repository owns **ровно две** canonical identities — `MuxTV_TV_OLD_API26` и `MuxTV_TV_CURRENT_API36`. Дополнительные low-RAM/720p/mainstream/benchmark AVD identities не создаются; display/density/runtime profiles проверяются на этих же двух AVD.

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
- Lounge Light player presentation интегрирован с native remote-input boundary;
- **PR #175 принят:** final seek mutation/coalescing authority принадлежит playback service; UI сохраняет только presentation/provisional behavior и не является вторым player-mutation owner.

### Lounge Light TV

- стабильный 88dp layout slot для rail и overlay-like expansion без reflow content в принятом baseline;
- bidirectional D-pad filter graph;
- deterministic focus restoration в Channels/Settings;
- bounded `LazyColumn` Source details с 720p focus containment;
- reduced-motion policy для optional scaling;
- real-data-only Home/Guide/Search/Settings presentation;
- принятый exact-head DeviceCurrent после интеграции #167;
- дальнейшие rail/content geometry и focus assertions **не считаются доказанными текущим snapshot** до U0 runtime characterization; U1 обязан исправлять только подтверждённые U0 дефекты.

### Diagnostics, measurement и release foundation

- Doctor Lite presentation/export без secrets;
- deterministic 1k/10k/50k M3U corpus и repeated-series tooling;
- JMH и Macrobenchmark/Baseline Profile foundation;
- release identity, R8/resource optimization и signing/evidence contracts;
- self-hosted runner preflight/cleanup и exact-source-head provenance;
- dependency-aware architecture guards;
- reviewed-snapshot/live-state separation;
- risk-based PR routing и manual integration acceptance lane;
- D0 exact two-AVD contract: API26 legacy + API36 current, с canonical reuse вместо размножения AVD.

## Последние принятые этапы reviewed snapshot

- PR #175 / `2302c114...` — single service-owned seek authority;
- PR #176 / `d0133e9f...` — evidence artifact publication retries;
- PR #177 / `c9a84034...` — unlock-gated CE startup;
- PR #183 / `3fba522c...` — Media3 UnstableApi lint boundary;
- PR #185 / `e208076d...` — external D-pad lint boundary;
- PR #181 / `5aa9c108...` — D0 exact two-AVD repository/runtime contract and stabilization execution baseline.

Порядок списка отражает причинную стабилизационную цепочку, а не числовую сортировку PR.

## Следующая последовательность перед MVP 0.1 alpha

1. **U0 — runtime TV UI characterization (#188; implementation owner #189).** Frozen exact-source evidence A/B/C на `MuxTV_TV_CURRENT_API36` с representative 1080p, representative 720p и compact stress profile. Никаких production UI fixes до evidence.
2. **U1 — evidence-driven minimal UI correction.** Сначала RED regression contract только для подтверждённого U0 дефекта, затем минимальный GREEN без новой focus architecture и без расширения scope.
3. **M0 — measurement correctness (#178).** Исправить measurement authority/correctness до любых DB/performance conclusions.
4. **Measured optimization only after M0.** Buffer/cache/Room/parser/Compose tuning допускается только при evidence и соответствующем owner issue.
5. **Observability preparation (#191/#192/#193) может идти параллельно host-first.** WorkManager failure hooks, secret-safe Tracing 2.0 boundary и bounded OkHttp phase timings не являются разрешением менять performance behavior до M0.
6. **Dependency modernization после stabilization baseline.** Combined #190 остаётся compatibility probe, а финальные dependency changes режутся на изолированные owner PR.
7. **Alpha release evidence (#31).** API37 private-LAN smoke, canonical API26/API36 matrix, Baseline Profile/CUJ, signing/SBOM и physical weak/current TV evidence для hardware-specific claims.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution не является обязательным PR или release gate. 50k corpus остаётся synthetic correctness/stress asset и manual stress lane. Absolute performance claims требуют отдельного hardware/release evidence.

## Известные gaps reviewed snapshot

- U0 runtime rail/content geometry и focus characterization ещё не принят как runtime evidence;
- U1 production correction ещё не определён evidence;
- M0 measurement correctness (#178) ещё не принят;
- source-refresh Doctor diagnostics residual (#30);
- reboot/unlock/package-replace lifecycle contract (#118) имеет принятую unlock boundary, но residual lifecycle/recovery acceptance остаётся отдельным owner scope;
- WorkManager/Tracing/OkHttp observability owners #191/#192/#193 ещё не являются accepted implementation;
- Baseline Profile/CUJ closure;
- signing/SBOM/physical-device release evidence (#31);
- API37 private-LAN permission smoke.

## Live-state protocol

Перед любой новой execution-сессией:

1. получить exact Git `HEAD`, branch и dirty state из checkout;
2. сравнить exact HEAD с `reviewed_main_commit` и явно классифицировать relation (`exact`, `ancestor`, `diverged`, `missing`);
3. получить PR/Issue state из GitHub, если он нужен для задачи;
4. не использовать старые remote `tmp/*`, `backup/*`, `rebuild/*` как execution base только потому, что они существуют;
5. evidence считать действительным только для точного SHA, на котором оно было получено;
6. не создавать третий MuxTV AVD: API/profile/display variants должны использовать canonical API26/API36 identities.

`tools/ci/Get-RepositoryLiveState.ps1` является offline Git-reader для пунктов 1–2. GitHub coordination state остаётся отдельным live source.

## Evidence limits

Canonical API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, focus, MediaSession и database contracts. Display/density variants на API36 могут валидировать TV geometry/focus contracts без отдельного AVD. Эти gates **не доказывают** vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal, real-network или absolute performance. Эти claims требуют physical-device evidence.
