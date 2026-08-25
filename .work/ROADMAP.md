---
status: accepted
last_reviewed: 2026-08-25
---

# Roadmap

## Current alpha checkpoint

MuxTV находится в **functional pre-alpha / stabilization before MVP 0.1 alpha**. Durable accepted baseline — `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97`: Source/Catalog/EPG/Search/Guide, service-owned Media3 playback/recovery, Doctor Lite, Lounge Light TV shell, single service-owned seek authority и exact two-AVD Android TV contract уже приняты.

Текущий стабилизационный critical path до alpha:

1. **U0 — runtime TV UI characterization (#188 / implementation owner #189).** Зафиксировать exact-source A/B/C evidence на canonical `MuxTV_TV_CURRENT_API36`; production UI fixes до evidence запрещены.
2. **U1 — evidence-driven minimal UI correction.** RED regression contract создаётся только для дефекта, подтверждённого U0; затем минимальный GREEN без новой focus architecture или scope expansion.
3. **M0 — measurement correctness (#178).** Measurement authority/correctness должна быть принята до DB/performance conclusions.
4. **Measured optimization only after M0.** Buffer/cache/Room/parser/Compose tuning допускается только при owner issue и before/after evidence.
5. **Dependency modernization после stabilization baseline.** PR #190 — только combined compatibility probe; финальные dependency changes должны быть изолированными owner PR.
6. **Release closure.** Baseline Profile/CUJ, API37 private-LAN smoke, signing/SBOM и physical-device evidence для weak ARM/vendor codec/HDR/passthrough/absolute performance claims.

Параллельно с critical path допускается **host-first observability preparation**, если она не меняет product/performance semantics: #191 WorkManager failure observations, #192 secret-safe AndroidX Tracing 2.0 boundary, #193 bounded secret-safe OkHttp phase timings. Эта работа не разрешает performance changes до принятого M0.

Android TV device policy фиксирован: repository использует ровно две canonical AVD identities — `MuxTV_TV_OLD_API26` и `MuxTV_TV_CURRENT_API36`. 720p/1080p/density/compact stress variants выполняются на этих же устройствах; отдельные low-RAM/mainstream/benchmark AVD не создаются.

Timed/repeated 50k Search/M3U execution является manual stress evidence, а не обязательным PR/release gate; 50k corpus остаётся synthetic correctness/stress asset.

Эта секция описывает текущий checkpoint. Фазы ниже остаются capability roadmap и не являются утверждением, что для уже реализованной функции нужно создать отдельный симметричный модуль.

## Phase 00 — Foundation

**Результат:** воспроизводимый Android TV проект с проверяемыми contracts, schema v1, premium TV shell и release-safe build foundation.

- Gradle wrapper/version catalog/convention plugins;
- минимальный module graph: `app-tv`, `core-*`, `player-api`, `player-media3`, catalog/provider ports;
- Kotlin/Android-first Room boundary по ADR-0003;
- Compose for TV Design System и Navigation 3 shell;
- deterministic D-pad/focus restoration tests;
- typed IDs/domain contracts;
- Room schema v1 с installation/profile scope и обязательным Основным профилем;
- primary profile invariant/migration tests без готового profile picker;
- Hilt composition root;
- unit/screenshot/instrumentation/macrobenchmark/baseline-profile modules;
- structured redacted logging/correlation IDs;
- debug/stable package/signing/channel model;
- GitHub Actions и draft release pipeline;
- reference device/fixture methodology scaffold.

**Exit gate:** clean build, tests, debug APK, schema export, first focus screenshots and baseline benchmark report.

## Phase 01 — Reliable Live TV

**Результат:** MuxTV безопасно импортирует M3U и стабильно воспроизводит live channels на Android TV/Google TV/Fire TV.

- bounded fetch/decode and streaming M3U parser;
- source network policy, validation and immutable revision/staging/commit;
- groups/channels/favorites/history for primary profile;
- Media3 PlaybackService/Controller and process-independent Activity lifecycle;
- MediaSession/audio focus/surface handling;
- semantic audio/subtitle track selection;
- channel zapping/numeric/previous channel;
- stable playback error catalog and bounded recovery;
- basic device capability evidence;
- first physical Android/Fire TV playback matrix;
- Baseline Profiles for startup/live browser/start playback.

**Exit gate:** rejected/malformed source cannot corrupt active catalog; Activity recreation does not stop playback; 100-switch leak test and representative channel startup budgets pass.

## Phase 02 — EPG and Personal Catalog

**Результат:** пользователь получает устойчивое персональное телевидение, а не сырой список URL.

- secure streaming XMLTV parser with gzip/zip and XXE/bomb protection;
- timezone/DST/overlap/dedup rules;
- EPG revision staging and previous-good fallback;
- now/next and high-performance lazy EPG grid;
- alias normalization and match confidence;
- complete profile overlays: order, numbering, hidden, custom groups, display metadata;
- manual EPG overrides with provenance;
- atomic source refresh and tombstones/retention;
- backup/restore schema v1 with primary profile rules;
- additional user-created profiles and optional startup picker;
- profile policies/PIN as independent settings, no built-in role types.

**Exit gate:** source/EPG refresh preserves overlays/manual bindings; guide handles configured large window with bounded heap; backup round-trip and profile isolation tests pass.

## Phase 03 — Smart Channels and TV Doctor

**Результат:** один logical channel safely aggregates variants, recovers from failures and explains its decisions.

- candidate blocking/features and labeled matching corpus;
- canonical channel merge/split lifecycle and mutation journal;
- hard conflicts/manual reject rules;
- auto-merge remains disabled until precision gate is evidenced;
- transparent stream scoring with confidence/device scope;
- primary/reserve variants and playback failover with hysteresis/cooldown;
- TV Doctor L0–L4 probes and passive observed health;
- resource-aware batch scheduler;
- findings, preview, selective apply and exact undo;
- calibration/false-positive reports.

**Exit gate:** merge/split/fixes are transactional/reversible; background audit does not degrade playback; auto decisions meet documented precision and scoring calibration gates.

## Phase 04 — Mass-user UX and Local Control

**Результат:** обычный пользователь устанавливает, настраивает и обслуживает приложение без клавиатуры и технических знаний.

- QR onboarding and short-lived TV-confirmed LAN pairing;
- local phone web panel with scoped capabilities;
- source/EPG/profile/catalog management from phone;
- simple/expert presentation modes;
- finalized profile manager and configurable startup mode;
- accessibility presets: high contrast, large UI, reduced motion;
- safe GitHub release check/download/package verification;
- PackageInstaller flow and update recovery UX;
- guided TV Doctor summaries;
- Russian/English production copy and localization tests.

**Exit gate:** fresh user adds a source by phone and starts a channel without remote text entry; unpaired LAN client has no access; updater rejects wrong package/certificate/hash/downgrade.

## Phase 05 — Provider expansion, catch-up and storage

**Результат:** расширение источников only after Live/EPG/catalog reliability is stable.

- Xtream provider adapter;
- Stalker/Ministra only after protocol/security research;
- provider capability normalization;
- catch-up templates/resolvers;
- local timeshift design and bounded ring buffer;
- DVR jobs/storage/conflict/recovery design;
- declarative extension manifests;
- extension conformance/security tooling;
- optional libmpv compatibility prototype after corpus/benchmark ADR;
- phone/desktop companion product research, not automatic commitment.

**Exit gate:** each provider passes conformance/error/redaction tests; DVR/timeshift cannot corrupt live playback or exhaust storage silently.

## Deferred until separate approval

- VOD movies/series catalog;
- cloud account/sync;
- social/recommendation network;
- arbitrary executable plugin marketplace;
- AI/LLM recommendations as core dependency;
- Tizen/webOS native clients;
- multi-view before single-stream performance is proven;
- full KMP database without second-client evidence.

## Release policy

- До `0.1.0` публичных обещаний compatibility нет, но schema/signing/applicationId decisions already treated as durable.
- Начиная с `0.1.0`, migrations/backup/update/error catalogs проходят contract tests на каждом PR.
- `1.0.0` требует Phase 00–04, physical Android/Google/Fire device matrix, threat review, update/migration recovery and documented support boundaries.
- Новая крупная функция не принимается, если она ухудшает startup, zapping, memory, focus correctness, privacy or security budgets без принятого ADR.
- Reference application feature parity is never roadmap justification by itself.