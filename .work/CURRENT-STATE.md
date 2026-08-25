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

Этот файл — **durable reviewed snapshot**, а не динамический снимок GitHub. Он синхронизирован с принятым `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97` после принятия single service-owned seek authority и точного двух-AVD Android TV contract.

Точный `HEAD`, текущая ветка и dirty-state берутся из Git во время выполнения. Состояние PR/Issues берётся из GitHub во время выполнения и намеренно не фиксируется здесь как «текущее»: оно может измениться без единого commit и поэтому не является versioned repository truth.

`implementation_source_commit` сохранён как compatibility alias и равен `reviewed_main_commit`. Он **не означает**, что live HEAD обязан совпадать с этим SHA; descendant branch является нормальным `ancestor` drift.

## Классификация

MuxTV находится в стадии **functional pre-alpha / stabilization before MVP 0.1 alpha**.

Reviewed snapshot включает закрытый Source/Catalog/EPG/Search/Guide контур, service-owned Media3 playback/recovery, Doctor Lite, внешний HTTP/HTTPS playback, EP-08 progressive resilience evidence, Lounge Light TV shell/focus stabilization, один service-owned semantic seek authority, dependency-aware architecture guards, risk-based CI и точный repository-owned API26/API36 device contract.

Главный причинный контур перед измерениями и dependency freeze теперь не seek ownership. Стабилизационная последовательность — **U0 UI characterization → U1 evidence-driven correction → M0 measurement correctness**. Только после неё dependency/performance conclusions получают стабильную attribution boundary.

## Принятая база snapshot

- Repository: `MuxTV/Muxtv`, default branch `main`, private, BSD 3-Clause.
- Reviewed main: `5aa9c108cc63187d8066494fb30c73b82f4e0f97` — принятый D0/#181 exact two-AVD baseline поверх ранее принятого playback/input stabilization.
- Android application: `app.muxtv.tv`, versionCode=1001, versionName=`0.1.0-alpha.1`, minSdk=26.
- Architecture: нормативная v2; implementation progress не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp, Media3.
- Room schema: v10.
- Gradle graph snapshot: 27 модулей плюс included build `build-logic`.
- Repository-owned Android TV AVD identities: **ровно** `MuxTV_TV_OLD_API26` и `MuxTV_TV_CURRENT_API36`.
- CI: exact-source-head host validation, risk-routed focused/device lanes, API26/API36 Product Matrix и отдельные measurement/benchmark/migration lanes.

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
- **PR #175 accepted:** один generation-aware service-owned semantic seek/mutation/coalescing authority; UI остаётся presentation/provisional boundary и не является вторым final mutation owner;
- Android lint boundaries для Media3 unstable API и Activity-level external D-pad dispatch зафиксированы targeted gates (#183/#185).

### Lounge Light TV

- Lounge Light shared shell, Home/Channels/Guide/Search/Settings workspace и warm player presentation;
- bidirectional D-pad filter graph;
- deterministic focus restoration в Channels/Settings;
- bounded `LazyColumn` Source details с 720p focus containment;
- reduced-motion policy для optional scaling;
- real-data-only Home/Guide/Search/Settings presentation;
- U0 characterization требуется перед следующей production Compose correction, потому что более поздний reference-fidelity push изменил shared rail reservation/visual scale и должен быть отделён от ранее принятого shell behavior по runtime evidence.

### Diagnostics, measurement и release foundation

- Doctor Lite presentation/export без secrets;
- deterministic 1k/10k/50k M3U corpus и repeated-series tooling;
- JMH и Macrobenchmark/Baseline Profile foundation;
- release identity, R8/resource optimization и signing/evidence contracts;
- self-hosted runner preflight/cleanup и exact-source-head provenance;
- dependency-aware architecture guards;
- reviewed-snapshot/live-state separation;
- risk-based PR routing и exact API26/API36 Product Matrix;
- canonical AVD ownership/cleanup/runtime contract, запрещающий отдельные 720p/low-RAM/benchmark AVD identities.

## Последние принятые этапы reviewed snapshot

- PR #175 — единый service-owned semantic seek authority;
- PR #183 — targeted Media3 lint boundary для session command filtering;
- PR #185 — targeted App TV lint boundary для external D-pad dispatch;
- PR #181 — D0 exact two-AVD repository/device-matrix contract, merged as reviewed `main@5aa9c108`;
- PR #169 — dependency-aware architecture guards;
- PR #170 — корректная API37 boundary для `ACCESS_LOCAL_NETWORK`;
- PR #171 — risk-based PR gates и single integration acceptance lane;
- PR #173 — корректные `SessionResult` codes из MediaSession player-command callback;
- PR #172 — разделение durable reviewed snapshot и live Git/GitHub state;
- PR #167 — EP-08 progressive resilience + native external D-pad seek evidence;
- PR #168 — Lounge Light TV stabilization и интеграция поверх принятого EP-08.

Порядок списка отражает причинную стабилизационную цепочку, а не числовую сортировку PR.

## Следующая последовательность перед MVP 0.1 alpha

1. **U0 / #188 — runtime characterization без production UI mutation.** Один byte-identical probe сравнивает immutable A/B/C на canonical `MuxTV_TV_CURRENT_API36`; 1080p/320 и 720p/213 являются representative TV modes, 720p/320 — только compact stress.
2. **U1 — только evidence-driven correction.** Каждый production UI change начинается с observed RED contract; shared-shell, rail visual state и typography/tokens имеют отдельных owners.
3. **M0 / #178 — measurement correctness.** До принятия M0 DB/query/performance conclusions считаются недостаточно надёжными для tuning decisions.
4. **Dependency hardening.** Combined stack probe используется только как compatibility evidence; финальные Room/Navigation/Paging/Media3/Compose/toolchain изменения остаются независимо reviewable и revertible.
5. **Observability.** Typed bounded WorkManager/network/player evidence boundaries без generic raw telemetry bus и без secrets.
6. **Measured performance decisions.** Buffer/cache/Room/parser/HTTP optimization принимаются только по trustworthy #27/#31 evidence; valid outcome может быть «оставить defaults».
7. **Alpha release evidence (#31).** Baseline Profile/CUJ, R8 review, signing/SBOM, API37 smoke и physical Android/Google/Fire TV evidence перед hardware-specific claims.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution не является обязательным PR или release gate. 50k corpus остаётся synthetic correctness/stress asset и manual stress lane. Absolute performance claims требуют отдельного hardware/release evidence.

## Известные gaps reviewed snapshot

- U0 Lounge shared-shell/visual characterization и последующий U1 только по observed evidence;
- measurement correctness M0/#178 до DB/performance tuning conclusions;
- source-refresh Doctor diagnostics residual (#30);
- reboot/unlock/package-replace lifecycle contract (#118);
- Baseline Profile/CUJ closure;
- signing/SBOM/physical-device release evidence (#31);
- API37 private-LAN permission smoke;
- isolated dependency owner PRs после стабилизационного baseline.

**Не является gap:** dual seek ownership. Он закрыт принятым #175 и не должен снова появляться в execution plans как незавершённая архитектурная миграция.

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
