---
status: accepted
last_reviewed: 2026-08-13
architecture_version: 2
implementation_source_commit: 1249624db5010e8140814a56553ea194c6d25d66
---

# Текущее состояние

## Классификация

MuxTV находится в стадии **functional pre-alpha**. Принятый main заканчивается PR #166 (shared TV player surface EP-04..07) и содержит закрытый MVP-контур S0–S5, repository truth contract и доказуемую GitHub/local hygiene.

Search S5 считается принятым: measurement baseline через PR #159, top-N refresh optimization через PR #160. Активный пакет — Guide S6 bounded closure, затем M4-R/M6-R/L1/D1/M3-C/M7-R и физический release candidate.

Статус M6-R (Lounge Light): полный TV-редизайн реализован в PR #168 (ветка `feat/lounge-light-tv-redesign`, 8 коммитов): тема/токены, левая rail-навигация, Home/Channels/Guide/Search, Settings workspace (Sources/Doctor/AddSource), тёплый player overlay, D-pad journeys, design-craft polish (motion easing, semantic selection markers) и фикс макробенчмарк CUJ через Settings workspace. Ждёт CI-гейтов self-hosted runner; device-acceptance на физическом телевизоре — отдельный шаг.

## Принятая база

- Repository: MuxTV/Muxtv, default branch main, private, BSD 3-Clause.
- Android application: app.muxtv.tv, versionCode=1001, versionName=0.1.0-alpha.1, minSdk=26.
- Architecture: нормативная v2; продвижение implementation не повышает architecture version.
- Stack: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp и Media3.
- Room schema: v10.
- Фактический Gradle graph: 27 модулей плюс included build build-logic.
- CI: host Fast/Full, Product API26/API36, Database API26/API36, variance/measurement и benchmark gates.

## Реализованный продуктовый контур

### Source, catalog и EPG

- secure source URL policy, exact-origin HTTP approval и Keystore-backed credentials;
- bounded streaming M3U и XMLTV parsing/decoding;
- immutable source/EPG revisions, staging, atomic publication и previous-good retention;
- durable refresh ownership, cancellation и supersession;
- deterministic EPG matching, bounded Now/Next и Guide windows;
- active/current-revision + selected-profile-visible truth contract.

### TV surfaces

- Home, Channels, Guide, Search, Player, Sources и Doctor routes;
- Channels Room Paging с bounded loaded window, Favorites, Recent, Now/Next и stable focus;
- bounded Unicode Search top-N: 100 результатов по умолчанию, максимум 200, явный isTruncated;
- Search debounce/cancellation/retry, Player/Back query и canonical focus restoration;
- service-owned Media3 playback и first-rendered-frame success boundary;
- bounded same-channel recovery и redacted playback observations;
- Doctor Lite presentation/export без secrets.

### Measurement и release foundation

- deterministic 1k/10k/50k M3U corpus и repeated-series tooling;
- JMH, Macrobenchmark/Baseline Profile producer foundation;
- release identity, R8/resource optimization и repository-owned signing/evidence contracts;
- self-hosted runner preflight, cleanup и exact-source-head provenance.

## Последние принятые этапы

- PR #149 — MVP/CI contract;
- PR #150 — service-owned bounded playback recovery;
- PR #151 — bounded redacted playback observations;
- PR #152 — Doctor Lite;
- PR #153 — alpha identity и release optimization;
- PR #154 — предыдущая closed-MVP truth sync;
- PR #155 — self-hosted runner hardening;
- PR #156 — measurement foundation;
- PR #157 — Channels Paging.
- PR #158 — repository truth contract и GitHub/local hygiene;
- PR #159 — S5 measurement baseline;
- PR #160 — S5 published-results refresh optimization.

## Активная последовательность

1. Guide S6 bounded closure поверх существующего `RoomGuideWindowRepository`/`GuideViewModel`: presentation projection, deterministic `READY`/`NO_GUIDE`/`SOURCE_CONFLICT`, focus/navigation semantics и bounded performance validation; без Paging3, full-grid materialization, schema bump или 50k timed benchmark.
2. M4-R — Player/Doctor residual: auto-hiding TV overlay, recovery presentation, source-refresh Doctor diagnostics.
3. M6-R — remote interaction, accessibility и минимальный Lounge Light shell.
4. L1 — lifecycle/reboot/package-replace contract (#118) без расширения Direct Boot scope.
5. D1 — точечный dependency hardening (Room3 3.0.1 candidate, Navigation3 1.1.5 optional) и freeze.
6. M3-C — Baseline Profile/CUJ closure и performance evidence.
7. M7-R — signing, artifact provenance и release evidence; RC — API37 smoke и physical Android/Google TV gate.

## Политика 50k evidence

Timed/repeated 50k Search/M3U execution не является обязательным PR, S6 или release gate. 50k corpus остаётся synthetic correctness/stress asset и manual stress lane (`focused-m3u-evidence.yml` — manual-only `workflow_dispatch`). Claim на large catalog подтверждается bounded architecture и reachability/correctness evidence; absolute performance claims относятся к physical release evidence.

## Открытые дорожки

- S6 — Guide bounded-performance и presentation closure.
- M4-R/#30/#33 — Player overlay, recovery UX и source-refresh Doctor diagnostics.
- M6-R/#33/#93/#111 — Lounge Light shell, accessibility и remote interaction.
- L1/#118 — reboot/unlock/package-replace WorkManager lifecycle.
- M3-C/M7-R/#27/#31/#146 — Baseline Profile, release engineering, signing и physical-device evidence.
- #100/#101/#109/#112/#113/#115/#117/#132/#141/#144 — backlog вне closed-alpha critical path.

## Evidence limits

API26/API36 emulator gates валидируют Android API, Room/migration, lifecycle, focus, MediaSession и database contracts. Они не доказывают vendor MediaCodec/HDR/passthrough, Fire OS, weak ARM, thermal, real-network или absolute performance. Такие claims требуют physical-device evidence.
