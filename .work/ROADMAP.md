---
status: accepted
last_reviewed: 2026-08-24
---

# Roadmap

## Current alpha checkpoint

Phase 00 foundation и основной daily-use контур Phase 01/02 уже реализованы на Room v10: source/EPG revisions, Channels Paging, Favorites, Recent, bounded Search, bounded Guide, service-owned Player/recovery/seek authority и Doctor Lite.

Текущий pre-alpha checkpoint — **stabilization before MVP 0.1 alpha**, а не продолжение старой feature-последовательности.

Принятая база: `main@5aa9c108cc63187d8066494fb30c73b82f4e0f97` после PR #181/D0. PR #175 уже устранил dual seek ownership; Issue #118 lifecycle contract завершён.

### Критическая последовательность стабилизации

1. **D0 — exact two-AVD contract: принято.** Repository-owned persistent AVD identities are exactly `MuxTV_TV_OLD_API26` and `MuxTV_TV_CURRENT_API36`; нет fallback API и отдельных low-RAM/mainstream/720p/benchmark/measurement AVD.
2. **U0 — UI characterization.** Детерминированно сравнить A/B/C geometry/focus на canonical API36 без product UI fix.
3. **U1 — evidence-driven UI correction.** Исправить только proven shared/root layout owner и перепроверить focus/navigation, используя те же canonical AVD identities.
4. **M0 / #178 — measurement correctness.** Restack measurement fix на стабильную post-U1 базу; до этого DB/Search performance conclusions не считаются доверенными.
5. **Performance/release train.** Только после M0 принимать решения по Room/Search/parser/player/Compose tuning на before/after evidence.

Canonical plan: `docs/superpowers/plans/2026-08-22-muxtv-stabilization-master-plan.md`.

### Параллельный observability/evidence train

Observability можно готовить host-first параллельно U0/U1, если он не меняет U0 baseline и не используется как разрешение на speculative tuning:

- #191 — WorkManager typed secret-safe failure diagnostics;
- #192 — AndroidX Tracing 2.0 `MuxTvTrace` boundary;
- #193 — OkHttp DNS/connect/TLS/TTFB/body timing;
- #109/#27 — Media3 analytics evidence before adaptive buffer policy;
- #31 — R8 Configuration Analyzer + sustained Macrobenchmark/Baseline Profile CUJs;
- #196 — Room3 pool/FTS5/WITHOUT ROWID measurement experiments only after #178;
- #195 — Gradle 9.7 parallel configuration-cache/Isolated Projects experiment, non-blocking/post-alpha.

Design and execution: `docs/superpowers/specs/2026-08-24-observability-modernization-design.md` and `docs/superpowers/plans/2026-08-24-observability-modernization-implementation-plan.md`.

### После M0

Приоритет высоко-ROI work:

1. #100 conditional HTTP validators / 304 so unchanged source can skip body -> parser -> staging -> DB/WAL -> publication;
2. measured M3U/Search/Channels hot-path optimization only where #27 evidence shows a bottleneck;
3. #109 adaptive Media3 buffering only after first-frame/rebuffer/seek/memory evidence;
4. Room3/FTS/schema experiments only through #196;
5. release closure #31: R8, Baseline Profile/CUJ, signing/SBOM/provenance and physical weak/current TV evidence.

Timed/repeated 50k Search/M3U execution остаётся manual synthetic stress/correctness evidence, а не обязательным PR/release gate.

### Device policy

Development/CI virtual-device truth uses exactly API26/API36 canonical AVD identities. 720p/1080p/density/stress are modes of the same devices. Emulator timing is not weak-ARM/vendor-codec/HDR/passthrough/thermal truth; those claims require physical hardware.

Эта секция описывает текущий checkpoint. Фазы ниже остаются capability roadmap и не означают, что для уже реализованной функции нужно создавать новый симметричный модуль.

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
