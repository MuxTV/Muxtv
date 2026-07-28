---
status: accepted
last_reviewed: 2026-07-29
architecture_version: 3
implementation_source_commit: d5afef744de71715ce3d34acd6ef80c4bd8fa957
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует и исполняется. Явное HTTP trust переносится из encrypted source access в exact-origin playback resolution с warning, повторным разрешением active variant и revocation.

Deterministic IPTV evidence foundation включает M3U profiles 1k/10k/50k, canonical manifest JSON, безопасную публикацию `.m3u8 + .manifest.json`, repository-owned CLI/Gradle entry point, bounded typed HLS/XMLTV starter fixtures, descriptive M3U parse measurements и реальные Android Room stage/activate/query measurements. Следующий незавершённый package issue #27 — Player request installation/reconnect proxy measurements, затем повторные current/old-edge/low-RAM серии и cross-series variance. XMLTV/EPG, daily-use discovery, release pipeline и physical-device evidence остаются открытыми.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- В `settings.gradle.kts` подключены 23 Gradle-проекта плюс included build `build-logic`.
- Room schema v4; HTTP approval и measurement code не потребовали production migration.
- CI использует Windows self-hosted runner и режимы Fast, Full, DeviceCurrent, DeviceMatrix и ручной CatalogMeasurement через repository-owned PowerShell harness.
- PR #38 слит squash commit `8665f80d6e38bc90d10ead0d3a3618fbecd4e304` и закрыл issue #26.
- PR #42 слит squash commit `764ec102808c4df57e826d05ce7b1334063bb520` и закрыл issue #39.
- PR #43 слит squash commit `80dff5132f624ffedacfdbab0d7bdfe67d85f2a8`; persistent Windows workspace очищается с repository-local `core.longpaths`.
- PR #45 слит squash commit `dc6e6b2357de12de65932857ca637ff9631782f1` и закрыл M3U diagnostic leak.
- PR #44 слит squash commit `3e24cccb188b53652285929a11e3b50697aad5f7`; deterministic M3U generator foundation завершён.
- PR #47 слит squash commit `f992e8269cd402d679905efb57a2af633a99772c`; canonical manifest JSON завершён.
- PR #48 слит squash commit `d7bc58a398c065018d9131c176e8e1c131766c88`; deterministic artifact pair publication и rollback semantics завершены.
- PR #50 слит squash commit `7134a4aaf2968ae0b7d62cf01bab254eb97e6b9f`; corpus command и configuration-cache-safe Gradle task завершены.
- PR #51 слит squash commit `a26fd4ba492948c413b317c168db5678db4ed00e`; bounded typed HLS/XMLTV starter fixtures завершены.
- PR #52 слит squash commit `0865e604ddce4f524de47e77cfb1366853484eb4`; repository truth переведён к measurement phase.
- PR #53 слит squash commit `ccf61362b61b2097cb56a4589f83edc7fa068ca1`; descriptive M3U parse measurements завершены.
- PR #54 слит squash commit `d5afef744de71715ce3d34acd6ef80c4bd8fa957`; Android Room stage/activate/query measurements завершены.
- Cleaned-tree Full для PR #42: run `30295592181`.
- Corpus foundation Full: run `30365096484`; `:core:testing:test` исполняется в permanent gate.
- Canonical manifest Full: run `30367547682`.
- Artifact pair publisher Full: run `30370708253`.
- Corpus entry point Full + real generation: run `30377371429`.
- Typed starter fixtures Full: run `30378845744`.
- M3U parse measurement Full: run `30383400416`.
- Reviewed Android Room Full: run `30400010584`.
- Reviewed Android Room focused measurement: run `30400010579`.
- Final permanent-tree PR #54 Full: run `30401263889`.
- Последняя успешная API 26/API 36 HTTP-approval DeviceMatrix: run `30287803018`, без fallback/failures/errors/skips.

## Реализованный рабочий путь

### Источники, trust и каталог

- URL policy отклоняет unsupported schemes, embedded credentials, fragments и encoded control separators до persistence.
- Remote source access хранится в Android Keystore-backed credential store вне Room public projections.
- Один singleton `RemoteSourceAccessManager` владеет encrypted save/read/update/remove для onboarding, refresh и playback approvals; read-modify-write mutations сериализованы.
- Source-entry поддерживает HTTPS и отдельное явное подтверждение HTTP.
- `RemoteSourceAccess` codec v2 хранит bounded exact HTTP playback origins и читает legacy v1 records.
- Source-level HTTP refresh approval отделён от playback origins: reset не ломает уже подтверждённый playlist refresh.
- Locator остаётся только в bounded transient state и не попадает в Navigation, SavedState или stable semantics.
- M3U обрабатывается bounded streaming parser.
- Source revisions immutable; импорт идёт через staging с atomic activation/rollback.
- Source refresh поддерживает manual/periodic WorkManager scheduling и typed attempt state.

### Каналы и Player

- PlaybackCatalog строит active channel/variant projections из Room.
- Credential reference выбирается только во внутреннем DAO row и не добавляется в public channel/variant models.
- `resolveVariant()` возвращает typed Ready / HTTP approval required / access unavailable.
- Approval identity: `http + normalized host + effective port`; другой host/port не наследует trust.
- Stale variant ID не падает назад на другой active stream и не может мутировать его credential.
- Player показывает только canonical origin; до подтверждения SET в MediaSession не отправляется.
- После approval Player заново разрешает current active variant и только затем создаёт `PlaybackSessionRequest`.
- Revocation приводит к повторному warning на следующем входе в Player.
- Channels использует stable channel identity, bounded viewport state и explicit FocusRequester ownership.
- Player → Back восстанавливает канал по stable identity; после удаления применяется nearest-previous fallback.
- Один process-owned MediaSessionService/ExoPlayer сохраняется при Activity recreation и reconnect.
- Setup protocol использует opaque `PlaybackSetupId`, SET/CANCEL и защищён от late install/stale cancel.

### Diagnostics и security

- `ChannelQuery`, channel summary, variant и approval outcomes имеют redacted diagnostic representation.
- `M3uPlaylistHeader` и `M3uEntry` diagnostics публикуют только counts/presence flags, но не untrusted keys или values.
- Search text, provider/source identity, locator, query, exact origin, cookies, Authorization/Referer и credential values не должны появляться в logs/errors/traces.
- HTTPS → HTTP redirect остаётся запрещённым; cross-origin sensitive headers снимаются.
- Production manifest не содержит process-wide cleartext opt-in или network-wide HTTP allow-list.
- Request-scoped repository clients, а не platform default, являются HTTP security boundary на всех поддерживаемых API.

### Deterministic corpus, artifacts и fixtures

- `core:testing` владеет provider-neutral M3U generator, canonical manifest writer, artifact publisher, command и starter fixture catalog; production ingest/runtime не зависят от testing module.
- Profiles: 1k, 10k и 50k entries.
- Equal profile + seed + source commit дают byte-identical UTF-8 output и SHA-256.
- Generator пишет в caller-owned `OutputStream`, flushes, но не closes его.
- Corpus использует только reserved `.example` hosts и synthetic identities.
- Controlled duplicates, long metadata, mixed line endings, relative locators, optional User-Agent/referrer directives и parser-recognized malformed fixture имеют manifest expectations.
- `StreamingM3uParser` проверяет parsed/skipped/warning/duplicate expectations в реальном test contract.
- Canonical JSON имеет fixed field order, LF и ровно одну trailing newline; serialization не зависит от reflection/map order/platform separator.
- Source commit валидируется как полный lowercase 40-character Git SHA.
- Artifact filenames детерминированы по profile, signed seed token и source commit.
- Manifest публикуется последним как commit marker.
- Implicit overwrite запрещён; explicit overwrite использует staging и backup/restore.
- Partial publication очищается; rollback failure типизирован и сохраняет recoverable backup.
- `M3uCorpusCommand` имеет stable usage/publish/internal exit codes и не раскрывает supplied values или full paths.
- `:core:testing:generateM3uCorpus` исполняет command через configuration-cache-compatible `JavaExec`.
- Full validation реально генерирует и архивирует только synthetic `small-1k` pair.
- Starter fixtures имеют stable IDs, per-fixture 16 KiB и aggregate 64 KiB bounds.
- HLS fixtures описывают relative variants, encrypted key/segments, malformed master, header names и synthetic redirect.
- XMLTV fixtures описывают DST/Unicode, missing channel reference и malformed timestamp.
- Fixture diagnostics не публикуют payload; unknown lookup не повторяет supplied identifier.
- `:core:testing:test` включён в permanent Fast/Full gate.

### Descriptive measurements

- M3U parse measurement генерирует immutable corpus вне timers и измеряет production `StreamingM3uParser` с no-retention sink.
- M3U report хранит raw wall-time/allocation samples, nearest-rank distributions, corpus SHA и exact environment metadata.
- Android Room measurement использует fresh file-backed WAL database для каждой sample.
- Room boundaries: 250-entry batch, total 10k staging, 10k activation, first 100 active channels и 32 source overviews.
- Room report хранит raw samples, DB/WAL/SHM footprint, Android environment, `buildMode=debug-instrumentation` и `thresholdApplied=false`.
- 10k fixture identity использует length-prefixed SHA-256 по всем `StagedCatalogEntry` fields, null markers и entry boundaries.
- Dedicated measurement test исключён из обычных correctness DeviceCurrent/DeviceMatrix suites.
- Focused report возвращается через instrumentation result bundle и повторно валидируется host script.
- Ни parse, ни Room baseline пока не являются failing budget или основанием для structural optimization.

## Android TV evidence

Последняя успешная HTTP-approval матрица:

| Профиль | System image | RAM / CPU | Credentials | Database | Media3 | App |
|---|---|---:|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | 4 | 21 | 10 | 12 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | 4 | 21 | 10 | 12 |

Run `30287803018` прошёл без fallback, failures, errors или skips. Android Room focused run `30400010579` прошёл на current API 36 profile с одним measurement test, пятью операциями и пятью retained samples на операцию.

Эмуляторная матрица доказывает Android API/lifecycle/Room/Keystore/focus/MediaSession contracts. Она не доказывает vendor MediaCodec, HDR, passthrough, Fire OS, слабый ARM SoC или реальные zapping/performance характеристики.

## Ближайший production milestone

Issue #27 остаётся активной.

Завершено:

1. deterministic M3U profiles 1k/10k/50k;
2. explicit seed/source commit и generator schema version;
3. manifest с expected counts, byte size и SHA-256;
4. canonical serialized manifest JSON;
5. deterministic `.m3u8 + .manifest.json` artifact pair publication;
6. explicit overwrite, staging, backup/restore и typed rollback;
7. repository-owned safe command и Gradle entry point;
8. bounded typed HLS/XMLTV starter fixtures;
9. byte-identical repeated output;
10. parser agreement и permanent testing gate;
11. descriptive M3U parse measurements;
12. Android Room stage/activate/query measurements и durable baseline.

Следующие packages:

1. Player request construction/codec/setup/reconnect proxy measurements;
2. repeated parse и Room current/old-edge/low-RAM series;
3. cross-series median/range/coefficient-of-variation analysis;
4. threshold decision только после repeated evidence;
5. fixture consumer binding по мере появления issue #28/#30 runtime consumers.

## Последовательность после issue #27

1. issue #28 — bounded XMLTV ingest и immutable EPG revisions;
2. issue #29 — now/next, Guide, Search, Favorites и Recent;
3. issue #30 — bounded variant fallback и TV Doctor Lite;
4. issue #33 — светлая TV-first visual modernization без новой state architecture;
5. issue #31 — R8, Baseline Profile, signing, SBOM, release checklist и physical-device alpha gate.

## Сохраняемые архитектурные решения

- Kotlin + native Compose остаются Android TV baseline.
- Room/SQLite остаётся Android-first storage boundary.
- Media3 остаётся primary playback engine behind stable contracts.
- Source/EPG updates используют immutable revisions, staging и atomic commit.
- Provider data, canonical channels и profile overlays разделены.
- Remote playlists/XML/images/provider endpoints считаются untrusted и bounded.
- Testing/corpus utilities не становятся production runtime dependencies.
- Measurement packages публикуют distributions и raw samples, но не failing budgets без repeated evidence.
- Rust/UniFFI, libmpv, bundled SQLite, Paging и второй engine допускаются только после corpus-backed benchmark/security ADR.
- Физические Android/Google TV/Fire TV проверки дополняют, но не заменяют автоматическую API-матрицу.
