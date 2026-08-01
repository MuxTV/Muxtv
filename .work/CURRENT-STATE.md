---
status: accepted
last_reviewed: 2026-08-01
architecture_version: 3
implementation_source_commit: c31b34d65ef90848bd907a521b9a0ba8860ed83a
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует. EPG foundation теперь проходит XMLTV → bounded payload decode → secure conditional remote acquisition → immutable staging/activation → durable policy/lease/state orchestration.

Главный correctness-риск сместился дальше: сначала нужно закрыть обнаруженный stale-publication race в старом M3U/source refresh пути (#76), затем построить deterministic EPG matching/now-next (#71), после чего можно закрывать umbrella #28 и переходить к daily-use Guide/Search/Favorites/Recent, playback recovery и alpha hardening.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- 23 Gradle-проекта плюс included build `build-logic`.
- Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Room 3, WorkManager, OkHttp и Media3.
- Room schema **v6**.
- `main` после #75: `c31b34d65ef90848bd907a521b9a0ba8860ed83a` (`feat: finish durable EPG refresh orchestration (#75)`).
- Issue #70 закрыта как completed.
- Self-hosted Android TV harness и API 26/API 36 matrix являются постоянной evidence-инфраструктурой, а не отдельным product milestone.

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

### Известный source-refresh correctness debt

Ревью #70 выявило в существующем M3U пути отдельный stale-publication race, вынесенный в #76:

- remote importer сейчас может переписать `sources.credentialRef` snapshot'ом старого in-flight запроса;
- source revision activation пока не проверяет текущий credential binding и durable refresh `runToken`;
- source completion пока не сравнивает nullable credential snapshot;
- source worker имеет тот же класс cancellation-finalization masking;
- несколько source refresh diagnostic data classes требуют явного redacted `toString()`.

Это следующий correctness package и он должен быть закрыт до #71, чтобы EPG matcher опирался на стабильные immutable revision producers с обеих сторон.

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

Final Full evidence: `30576931624`.

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
- post-decompression decoded-byte bound, включая `skip` paths;
- bounded ZIP leading-entry count and entry-name length;
- stream first regular ZIP entry only; no archive extraction/full buffering;
- typed value-free rejections and explicit resource ownership.

Final Full: `30666205286`.

### PR #72 — secure remote EPG refresh

Merge: `27bb5bc49685779251b75c6e0aa134e4aaf4d3b1`.

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

### PR #74 — durable EPG persistence / Room v6

Merge: `ab96f0fee5b80ebc8ae7f5a2cc23608ee5450030`.

- EPG policy/state/attempt/validator persistence;
- explicit Room v5→v6 migration and committed schema;
- DB refresh lease, stale reclamation and old-token completion rejection;
- separate `REFRESHED` and `NOT_MODIFIED` success semantics;
- bounded attempt retention and validator ownership;
- exact-head Full `30703994191`;
- API26/API36 matrix `30703994190`.

### PR #75 — durable orchestration + publication ownership

Merge/current main: `c31b34d65ef90848bd907a521b9a0ba8860ed83a`.

- EPG WorkManager scheduling remains in the existing `catalog:sync` architecture;
- MANUAL/STARTUP have trigger-distinct deterministic one-shot identities; PERIODIC remains unique periodic work;
- STARTUP/PERIODIC inherit durable unmetered/charging policy, MANUAL remains explicit CONNECTED override;
- disabling policy cancels policy-owned STARTUP/PERIODIC without cancelling explicit MANUAL;
- remote EPG import no longer rewrites source metadata from an in-flight request;
- activation atomically proves current `accessRef` and current `RUNNING runToken` before publishing staging;
- completion compares an explicit captured nullable access snapshot and cannot publish stale success/auth/failure/validators;
- cancellation finalization cannot mask the original `CancellationException`;
- state/attempt/validator diagnostics redact run token and validator/access values.

Exact-head merge evidence:

- Full `30708756373` — success;
- API26/API36 database/device matrix `30708756357` — success;
- focused entity-redaction GREEN `30708524223`;
- lease-ownership GREEN `30705466205`.

## Measurement/corpus foundation

Issue #27 остаётся открытой, но уже завершены:

- deterministic M3U 1k/10k/50k corpus + canonical manifests/artifact publication;
- bounded HLS/XMLTV starter fixtures;
- descriptive M3U parse, Android Room and Player proxy measurements;
- immutable comparison/variance identity;
- strict report adapters + exact-byte SHA-256 provenance;
- sequential fresh-AVD measurement series orchestration;
- current-profile two-run smoke;
- XMLTV runtime-consumer fixture binding.

Осталось по #27:

1. five-run `current-normal`;
2. five-run `old-edge-normal`;
3. five-run `current-low-ram`;
4. separated cross-profile interpretation;
5. per-operation `hard-gate` / `warning-only` / `descriptive-only` decision;
6. durable performance report/repository-truth sync.

HLS runtime fixture binding остаётся за #30, где появится реальный fallback consumer.

## Текущий critical path

### P1C — issue #76: source/M3U refresh ownership hardening

Без новой state framework и, если не обнаружится отдельная persisted потребность, без новой Room migration:

- remote M3U import не переписывает mutable source metadata из in-flight snapshot;
- propagate source refresh `runToken` worker → remote request → importer → Room;
- activation atomically compares current credential binding + current `RUNNING runToken`;
- stale/reclaimed/cancelled worker получает `SUPERSEDED` и не активирует staging;
- completion compares captured nullable credential binding before publishing success/auth/failure state;
- cancellation persistence best-effort в `NonCancellable`, original cancellation remains authoritative;
- source refresh target/request/state/attempt diagnostic strings redact credential/run-token values;
- previous-good active catalog остаётся reader boundary.

### P2 — issue #71: deterministic matching + now-next

- exact normalized external/tvg identity within explicit provider relation;
- exact normalized display name within provider/source;
- constrained deterministic aliases;
- otherwise unresolved/ambiguous, no weak fuzzy winner;
- hidden/deleted channels excluded;
- deterministic decision for equal catalog/EPG revisions;
- bounded queries keyed by canonical channel ID;
- `NowNext(current,next,nextBoundary)`;
- open-ended programme effective boundary from next programme where possible;
- invalidation only on active EPG revision/programme boundary, no full-guide polling;
- если persistence matching требует storage, использовать отдельную Room v6→v7 migration, не возвращаться к #70 schema scope.

### P3 — close issue #28

После #76/#71:

- synthetic remote XMLTV → decode → import → activate → match → now-next;
- failure/cancellation/supersede previous-good preservation;
- redaction audit;
- Full + relevant API26/API36 device evidence;
- acceptance reconciliation и закрытие umbrella issue.

### P4 — issue #29 daily-use discovery

Channels real now/next → Favorites → bounded profile-scoped Recent → bounded/debounced Search → bounded/lazy Guide → stable D-pad/Player Back continuity.

### P5 — issue #33 TV-first UX

Dedicated channel rows → real now/next visual integration → hidden-by-default Player overlay → Sources simplification → real Guide/Search routes → restrained light shell → credential-free logo loader → device/focus QA.

### P6 — issue #30 bounded fallback + TV Doctor Lite

- bounded attempt/time ladder;
- typed DNS/TLS/HTTP/auth/redirect/manifest/decoder/playback families;
- auth is not generic retryable network failure;
- temporary fallback does not overwrite preferred variant;
- Activity recreation/WorkManager cannot multiply attempts;
- bind HLS fixtures to the real consumer;
- redacted local diagnostic export.

Media3 remains the only player engine unless measured evidence requires an ADR.

### P7 — issue #31 alpha hardening

R8/resource shrinking, Baseline Profile, measured startup/journey evidence, virtual old/mainstream/current/low-RAM matrix, physical Android/Google TV/constrained/Fire TV checks, upgrade/Keystore/Room recovery, signing, changelog, SBOM/licenses и release checklist.

## Native/Rust decision gate

Rust/UniFFI, bundled SQLite, libmpv и второй player engine **не являются текущими correctness dependencies**. Их нельзя выбирать по предположению. Сначала #27 должен дать повторяемый bottleneck/variance evidence; затем отдельный ADR сравнивает выигрыш с FFI ownership, ABI packaging, crash/debugging и maintenance cost. До такого evidence оптимизируется существующий Kotlin/Room/Media3 путь.

## Android TV evidence limits

Эмуляторные API26/API36 проверки доказывают Android API/lifecycle/Room/Keystore/focus/MediaSession/database contracts. Они не доказывают vendor MediaCodec, HDR, passthrough, Fire OS, слабый ARM SoC, реальные сетевые zap timings или thermal behavior. До alpha обязательна физическая проверка.

## Сохраняемые архитектурные решения

- Kotlin + Compose остаются Android TV baseline.
- Room/SQLite остаётся storage boundary.
- Media3 остаётся primary playback engine.
- Source/EPG updates используют immutable revisions, staging и atomic activation.
- Provider data, canonical channels и profile overlays разделены.
- Remote playlists/XML/images/provider endpoints считаются untrusted и bounded.
- WorkManager uniqueness — orchestration optimization; DB lease + transactional revision activation — authoritative publication boundary.
- Testing/corpus utilities не становятся production runtime dependencies.
- Rust/UniFFI, libmpv, bundled SQLite, Paging и второй engine требуют reproducible bottleneck/compatibility evidence и ADR.
