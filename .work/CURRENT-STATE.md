---
status: accepted
last_reviewed: 2026-08-01
architecture_version: 3
implementation_source_commit: 27bb5bc49685779251b75c6e0aa134e4aaf4d3b1
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует. После EPG-цикла также существует production foundation XMLTV → bounded payload decode → immutable EPG staging/activation → secure conditional remote refresh.

Основной риск проекта сместился от parser/network/storage correctness к durable EPG orchestration, deterministic channel matching/now-next, daily-use discovery, bounded playback recovery, TV-first polish и physical-device/release evidence.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- 23 Gradle-проекта плюс included build `build-logic`.
- Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Room 3, WorkManager, OkHttp и Media3.
- Room schema **v5**.
- `main` на момент ревью: `27bb5bc49685779251b75c6e0aa134e4aaf4d3b1` (`feat: add secure remote EPG refresh (#72)`).
- Открытых PR на момент ревью нет.

## Реализованный source/catalog/Player путь

- URL policy отклоняет unsupported schemes, embedded credentials, fragments и encoded control separators до persistence.
- Remote source access хранится в Android Keystore-backed credential store вне Room public projections.
- Один singleton `RemoteSourceAccessManager` владеет encrypted source access и exact-origin playback approvals.
- M3U обрабатывается bounded streaming parser.
- Source revisions immutable; импорт использует staging + atomic activation/rollback.
- M3U refresh поддерживает manual/periodic WorkManager scheduling, DB lease, typed attempt state и startup reconciliation.
- PlaybackCatalog разрешает active channel/variant без выдачи credential reference в public projections.
- Exact-origin HTTP approval ограничен `scheme + normalized host + effective port`; stale variant не падает на другой active stream.
- Один process-owned MediaSessionService/ExoPlayer сохраняется при Activity recreation/reconnect.
- Playback request/session владеют immutable header snapshots; cross-origin sensitive headers не протекают.
- Channels использует stable canonical identity, bounded viewport state и explicit focus ownership; Player → Back восстанавливает surviving channel.

## Реализованный EPG foundation

### PR #63 — bounded XMLTV streaming parser

Merge: `0f484905b6aefff5f2e284b521c946b35c4a70de`.

- secure SAX/streaming parsing без DOM;
- запрет external entity/DTD expansion;
- independent byte/depth/element/attribute/text/channel/programme/per-record bounds;
- caller-owned `InputStream` и suspend sink;
- deterministic timestamp precision/offset parsing;
- offsetless timestamps дают typed unresolved result, а не скрытый UTC;
- diagnostics не содержат XML/programme/provider values;
- canonical XMLTV fixtures привязаны к production parser.

Final Full evidence: run `30576931624`.

### PR #64 — immutable EPG revisions / Room v5

Merge: `1a032c232aef67553354077a3000a1a74e867bee`.

- `epg_sources`, `epg_revisions`, `epg_channels`, `epg_programmes`;
- explicit Room migration 4→5;
- immutable staging + monotonic atomic activation;
- current + previous-good retention;
- superseded activation protection;
- bounded active-programme queries и open-ended programme semantics;
- streaming parser → importer batches;
- failed/cancelled staging cleanup preserving previous-good guide;
- API 26/API 36 migration/device contracts.

Final Full: `30663759211`. Database migration matrix: `30663759884`.

### PR #68 — bounded EPG payload decoding

Merge: `34dae3ec4f2a97d574bcf6bb00132c295a707872`.

- magic-first plain/gzip/ZIP detection;
- HTTP hints only when magic is inconclusive;
- post-decompression decoded-byte bound, including `skip` paths;
- bounded ZIP leading-entry count and entry-name length;
- stream first regular ZIP entry only; no archive extraction/full buffering;
- typed value-free rejections and explicit resource ownership.

Final Full: `30666205286`.

### PR #72 — secure remote EPG refresh

Merge/current main: `27bb5bc49685779251b75c6e0aa134e4aaf4d3b1`.

- reusable cancellable OkHttp await boundary shared with source refresh;
- encrypted access through existing singleton `RemoteSourceAccessManager`;
- existing source URL policy, explicit HTTP approval, redirect/header isolation and raw response-size limits;
- conditional `If-None-Match` / `If-Modified-Since`;
- `304` accepted only when request was genuinely conditional;
- `200` streams response through bounded payload decoder → immutable EPG importer;
- returned HTTP validators are value-redacted in diagnostics;
- decoded-size overflow remains typed;
- cancellation preserves previous-good guide.

Exact-head Full: `30668000159`.

## Measurement/corpus foundation

Issue #27 остаётся открытой, но уже завершены:

- deterministic M3U 1k/10k/50k corpus + canonical manifests/artifact publication;
- bounded HLS/XMLTV starter fixtures;
- descriptive M3U parse, Android Room and Player proxy measurements;
- immutable comparison/variance identity;
- strict report adapters + exact-byte SHA-256 provenance;
- sequential fresh-AVD measurement series orchestration;
- current-profile two-run smoke.

Осталось по #27:

1. five-run `current-normal`;
2. five-run `old-edge-normal`;
3. five-run `current-low-ram`;
4. separated cross-profile interpretation;
5. per-operation `hard-gate` / `warning-only` / `descriptive-only` decision;
6. HLS runtime fixture binding остаётся за issue #30.

Эти измерения идут **параллельно** текущему EPG critical path и не требуют отката уже реализованного issue #28 foundation.

## Текущий critical path

### P0 — repository truth sync

Документация должна быть синхронизирована с Room v5, PR #63/#64/#68/#72 и текущим `main`. Активный план: `docs/superpowers/plans/2026-08-01-post-remote-epg-execution.md`.

### P1 — issue #70: durable EPG refresh scheduling/state

Требуется:

- EPG-specific policy/state/attempt contracts;
- Room v5→v6 migration, если добавляются таблицы;
- DB lease per EPG source, stale reclamation и old-token completion rejection;
- manual/startup unique `KEEP`, periodic unique periodic `UPDATE`;
- typed constraints connected/unmetered/charging;
- timeout строго меньше lease staleness;
- distinct success semantics for `REFRESHED` и `NOT_MODIFIED`;
- validator values не должны попадать в public refresh state/history/diagnostics;
- cancellation finalizes `CANCELLED` в `NonCancellable` и rethrows;
- startup reconciliation;
- API 26/API 36 migration/device coverage.

Важно: существующий M3U `SourceRefreshCompletion` нельзя переиспользовать механически, потому что он требует `revisionNumber` на любом `SUCCEEDED`, а корректный EPG `304 Not Modified` не создаёт revision.

### P2 — issue #71: deterministic matching + now-next

- exact external/tvg identity within provider relation;
- exact normalized display name within provider/source;
- constrained deterministic aliases;
- otherwise unresolved/ambiguous, no weak fuzzy winner;
- hidden/deleted channels excluded;
- bounded queries keyed by canonical channel ID;
- `NowNext(current,next,nextBoundary)`;
- open-ended programme effective boundary from next programme where possible;
- invalidation only on EPG revision change/programme boundary, no full-guide polling.

Если persistence matching требует новой схемы, она должна идти отдельной v6→v7 migration, а не смешиваться с #70.

### P3 — close issue #28

После #70/#71: Full, API 26/API 36 migration/device evidence, synthetic XMLTV→remote refresh→activation→matching→now-next integration, cancellation/failure previous-good preservation и redaction audit.

### P4 — issue #29 daily-use discovery

Channels now/next, Favorites, bounded Recent, bounded/debounced Search, bounded/lazy Guide и стабильный D-pad focus/Player Back.

### P5/P6 — issue #33 и #30

UX: dedicated channel rows → real now/next → hidden Player overlay → Sources simplification → real Guide/Search routes → light shell → credential-free logos → QA.

Fallback/Doctor: bounded variant ladder, typed failure families, no retry storms, HLS fixture runtime binding, redacted TV Doctor export. Media3 остаётся единственным player engine без отдельного evidence-backed ADR.

### P7 — issue #31 alpha hardening

R8/resource shrinking, Baseline Profile, measured startup/journey evidence, virtual old/mainstream/current/low-RAM matrix, physical Android/Google TV/constrained/Fire TV checks, signing, changelog, SBOM/licenses и release checklist.

## Android TV evidence limits

Эмуляторные API 26/API 36 проверки доказывают Android API/lifecycle/Room/Keystore/focus/MediaSession/database contracts. Они не доказывают vendor MediaCodec, HDR, passthrough, Fire OS, слабый ARM SoC, реальные сетевые zap timings или thermal behavior. До alpha обязательна физическая проверка.

## Сохраняемые архитектурные решения

- Kotlin + Compose остаются Android TV baseline.
- Room/SQLite остаётся storage boundary.
- Media3 остаётся primary playback engine.
- Source/EPG updates используют immutable revisions, staging и atomic activation.
- Provider data, canonical channels и profile overlays разделены.
- Remote playlists/XML/images/provider endpoints считаются untrusted и bounded.
- WorkManager scheduling переиспользует существующую архитектуру, но EPG semantic state не смешивается с M3U-specific completion fields.
- Testing/corpus utilities не становятся production runtime dependencies.
- Rust/UniFFI, libmpv, bundled SQLite, Paging и второй engine требуют reproducible bottleneck/compatibility evidence и ADR.
