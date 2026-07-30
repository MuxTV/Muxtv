---
status: accepted
last_reviewed: 2026-07-30
architecture_version: 3
implementation_source_commit: a99461b0f54e42d95aea8bf31d81215ced2a49e3
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует и исполняется. Явное HTTP trust переносится из encrypted source access в exact-origin playback resolution с warning, повторным разрешением active variant и revocation.

Deterministic IPTV evidence foundation включает M3U profiles 1k/10k/50k, canonical artifacts, repository CLI, bounded HLS/XMLTV fixtures, descriptive M3U parse, Android Room и Player proxy measurements, immutable variance identity, strict report adapters и последовательный multi-run orchestrator. Первый двухпрогонный `current-normal` smoke прошёл на API 36. Issue #27 остаётся открытой для пятипрогонных current/old-edge/low-RAM datasets и threshold/warning/descriptive decision. XMLTV/EPG, daily-use discovery, fallback/Doctor, visual modernization, release pipeline и physical-device evidence остаются открытыми.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- В `settings.gradle.kts` подключены 23 Gradle-проекта плюс included build `build-logic`.
- Room schema v4; measurement packages не потребовали production migration.
- CI использует Windows self-hosted runner и режимы Fast, Full, DeviceCurrent, DeviceMatrix, CatalogMeasurement, PlayerMeasurement и отдельный Measurement variance smoke.
- PR #38 слит `8665f80d6e38bc90d10ead0d3a3618fbecd4e304` и закрыл issue #26.
- PR #42 слит `764ec102808c4df57e826d05ce7b1334063bb520` и закрыл issue #39.
- PR #43 слит `80dff5132f624ffedacfdbab0d7bdfe67d85f2a8`; Windows workspace очищается с repository-local `core.longpaths`.
- PR #44/#47/#48/#50/#51 завершили deterministic corpus, canonical manifest, artifact publication, executable entry point и typed starter fixtures.
- PR #53 слит `ccf61362b61b2097cb56a4589f83edc7fa068ca1`; descriptive M3U parse measurements завершены.
- PR #54 слит `d5afef744de71715ce3d34acd6ef80c4bd8fa957`; Android Room measurements завершены.
- PR #56 слит `e020d747b59e07c45db5076b38409f4319c51b96`; Android Player proxy measurements завершены.
- PR #57 слит `f1a92a2d8ef05f0dd61b0492b51a732a4978bd54`; playback request/session header ownership исправлен.
- PR #58 слит `fae4373...`; repository truth синхронизирован после Player/header packages.
- PR #59 слит `76bc9ad9a55d1535a7d6e5ff408502f2062fd8d5`; immutable variance foundation и provenance contracts завершены.
- PR #60 слит `da1b377032a7eb66fcf4086ee7518616047b672b`; strict M3U/Room/Player report adapters завершены.
- PR #61 слит `a99461b0f54e42d95aea8bf31d81215ced2a49e3`; sequential series orchestration, trusted smoke, interrupted evidence finalization и stable ADB readiness завершены.
- Final PR #61 Full: run `30568786155`.
- Final current-profile variance smoke: run `30568786175`.
- Последняя API 26/API 36 correctness DeviceMatrix: run `30287803018`.

## Реализованный рабочий путь

### Источники, trust и каталог

- URL policy отклоняет unsupported schemes, embedded credentials, fragments и encoded control separators до persistence.
- Remote source access хранится в Android Keystore-backed credential store вне Room public projections.
- Один singleton `RemoteSourceAccessManager` владеет encrypted save/read/update/remove для onboarding, refresh и playback approvals; read-modify-write mutations сериализованы.
- Source-entry поддерживает HTTPS и отдельное явное подтверждение HTTP.
- `RemoteSourceAccess` codec v2 хранит bounded exact HTTP playback origins и читает legacy v1 records.
- Source-level HTTP refresh approval отделён от playback origins.
- Locator остаётся только в bounded transient state и не попадает в Navigation, SavedState или stable semantics.
- M3U обрабатывается bounded streaming parser.
- Source revisions immutable; импорт идёт через staging с atomic activation/rollback.
- Source refresh поддерживает manual/periodic WorkManager scheduling и typed attempt state.

### Каналы и Player

- PlaybackCatalog строит active channel/variant projections из Room.
- Credential reference выбирается только во внутреннем DAO row и не добавляется в public channel/variant models.
- `resolveVariant()` возвращает typed Ready / HTTP approval required / access unavailable.
- Approval identity: `http + normalized host + effective port`; другой host/port не наследует trust.
- Stale variant ID не падает назад на другой active stream.
- Player показывает только canonical origin; до подтверждения SET в MediaSession не отправляется.
- После approval Player заново разрешает current active variant.
- Playback request/session владеют unmodifiable insertion-preserving header snapshots.
- Constructor/defaults, `copy`, `component1..7`, value equality/hash и redacted diagnostics сохранены.
- Channels использует stable channel identity, bounded viewport state и explicit FocusRequester ownership.
- Player → Back восстанавливает канал по stable identity; после удаления применяется nearest-previous fallback.
- Один process-owned MediaSessionService/ExoPlayer сохраняется при Activity recreation и reconnect.
- Setup protocol использует opaque `PlaybackSetupId`, SET/CANCEL и защищён от late install/stale cancel.

### Diagnostics и security

- Channel, approval, request и M3U diagnostic representations не раскрывают untrusted/sensitive values.
- Search text, provider/source identity, locator, query, exact origin, cookies, Authorization/Referer и credential values не должны появляться в logs/errors/traces.
- HTTPS → HTTP redirect запрещён; cross-origin sensitive headers снимаются.
- Production manifest не содержит process-wide cleartext opt-in.
- Request-scoped repository clients являются HTTP security boundary на всех поддерживаемых API.

### Deterministic corpus и measurements

- `core:testing` владеет provider-neutral M3U generator, canonical writer/publisher, commands, fixtures, measurement identities/adapters/analyzer; production runtime не зависит от testing module.
- Profiles corpus: 1k, 10k, 50k. Equal profile + seed + source commit дают byte-identical output и SHA-256.
- Corpus использует reserved `.example` hosts и synthetic identities.
- Canonical JSON имеет fixed field order, LF и одну trailing newline.
- Manifest публикуется последним; implicit overwrite запрещён.
- M3U measurement использует production parser, no-retention sink и raw samples.
- Room measurement использует fresh file-backed WAL database per sample и отдельно измеряет staging/activation/reads.
- Player measurement измеряет request construction, SET codec, coordinator и registry proxy operations, не first frame.
- Report adapters проверяют exact bytes, schema/method, workload/environment и child SHA-256.
- Variance identity включает exact source commit, fixture, runner, runtime, API/image/ABI/RAM/CPU и workload.
- Aggregate report хранит distinct child SHA-256, per-run medians, range, standard deviation, CV и worst p95; `thresholdApplied=false`.
- Series orchestrator выполняет host M3U отдельно, затем fresh AVD per repetition, Room → Player → shutdown.
- Stable boot требует двух последовательных `device + sys.boot_completed=1` и package-manager readiness.
- Cancelled workflow finalizes still-running manifest as `interrupted`.
- Dedicated smoke workflow не исполняет fork PR code на self-hosted runner.

## Current-profile variance smoke

Run `30568786175`, exact head `5091d3a1bdc005a5682b5d0915c617f7491885eb`:

| Field | Value |
|---|---|
| Profile | `current-normal` |
| Image | `system-images;android-36;android-tv;x86_64` |
| RAM / CPU | 2048 MiB / 2 |
| Repetitions | 2 fresh AVD |
| Fallback | false |
| Result | passed |

Initial descriptive signals:

- M3U range 4.32%, CV 2.99%;
- Room activation range 1.47%; first-page query 0.01%; source overview 5.54%;
- Room stage-batch range 67.14%; stage-total range 20.76%;
- Player request construction range 11.06%; setup-envelope range 29.95%;
- very short coordinator/registry operations have high relative variance at microsecond absolute duration.

These values are smoke evidence only, not budgets. Durable record: `docs/performance/2026-07-30-current-variance-smoke.md`.

## Android TV evidence limits

API 26/API 36 matrix and current smoke prove Android API/lifecycle/Room/Keystore/focus/MediaSession and measurement-harness contracts. They do not prove vendor MediaCodec, HDR, passthrough, Fire OS, weak ARM performance, real network zapping or physical-device thermal behavior. The staged virtual-matrix strategy remains old edge + current now, representative middle/low-RAM next, physical Android/Google TV/Fire TV before alpha.

## Ближайший milestone

Issue #27 остаётся активной.

Завершено:

1. deterministic corpus/canonical artifacts;
2. safe repository entry point и typed HLS/XMLTV fixtures;
3. descriptive M3U, Room и Player baselines;
4. immutable header ownership;
5. immutable variance foundation;
6. strict report adapters;
7. sequential orchestrator и current two-run smoke.

Осталось:

1. five-run `current-normal` Room/Player dataset;
2. five-run `old-edge-normal` dataset;
3. five-run `current-low-ram` dataset;
4. separated cross-profile interpretation;
5. explicit per-operation hard-gate / warning-only / descriptive-only decision;
6. fixture consumer binding при появлении issue #28/#30 runtime consumers.

## Последовательность после issue #27

1. issue #28 — bounded XMLTV ingest и immutable EPG revisions;
2. issue #29 — now/next, Guide, Search, Favorites и Recent;
3. issue #30 — bounded variant fallback и TV Doctor Lite;
4. issue #33 — TV-first visual modernization без новой state architecture;
5. issue #31 — R8, Baseline/Startup Profiles, signing, SBOM, release checklist и physical-device alpha gate.

## Сохраняемые архитектурные решения

- Kotlin + Compose остаются Android TV baseline.
- Room/SQLite остаётся Android-first storage boundary.
- Media3 остаётся primary playback engine behind stable contracts.
- Source/EPG updates используют immutable revisions, staging и atomic commit.
- Provider data, canonical channels и profile overlays разделены.
- Remote playlists/XML/images/provider endpoints считаются untrusted и bounded.
- Testing/corpus utilities не становятся production runtime dependencies.
- Measurement packages не создают failing budgets без repeated evidence.
- Rust/UniFFI, libmpv, bundled SQLite, Paging и второй engine требуют corpus-backed bottleneck/security ADR.
- Физические Android/Google TV/Fire TV проверки дополняют, но не заменяют автоматическую API-матрицу.
