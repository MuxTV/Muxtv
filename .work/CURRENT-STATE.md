---
status: accepted
last_reviewed: 2026-07-29
architecture_version: 3
implementation_source_commit: f1a92a2d8ef05f0dd61b0492b51a732a4978bd54
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует и исполняется. Явное HTTP trust переносится из encrypted source access в exact-origin playback resolution с warning, повторным разрешением active variant и revocation.

Deterministic IPTV evidence foundation теперь включает M3U profiles 1k/10k/50k, canonical artifacts, repository CLI, bounded HLS/XMLTV fixtures, descriptive M3U parse, Android Room и Player control-plane proxy measurements. Playback request/session models владеют immutable snapshots заголовков после валидации. Issue #27 остаётся открытой только для repeated current/old-edge/low-RAM series, cross-series variance и threshold/no-threshold decision. XMLTV/EPG, daily-use discovery, release pipeline и physical-device evidence остаются открытыми.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- В `settings.gradle.kts` подключены 23 Gradle-проекта плюс included build `build-logic`.
- Room schema v4; HTTP approval и measurement packages не потребовали production migration.
- CI использует Windows self-hosted runner и режимы Fast, Full, DeviceCurrent, DeviceMatrix, CatalogMeasurement и PlayerMeasurement через repository-owned PowerShell harness.
- PR #38 слит squash commit `8665f80d6e38bc90d10ead0d3a3618fbecd4e304` и закрыл issue #26.
- PR #42 слит squash commit `764ec102808c4df57e826d05ce7b1334063bb520` и закрыл issue #39.
- PR #43 слит squash commit `80dff5132f624ffedacfdbab0d7bdfe67d85f2a8`; persistent Windows workspace очищается с repository-local `core.longpaths`.
- PR #44/#47/#48/#50/#51 завершили deterministic corpus, canonical manifest, artifact publication, executable entry point и typed starter fixtures.
- PR #53 слит squash commit `ccf61362b61b2097cb56a4589f83edc7fa068ca1`; descriptive M3U parse measurements завершены.
- PR #54 слит squash commit `d5afef744de71715ce3d34acd6ef80c4bd8fa957`; Android Room stage/activate/query measurements завершены.
- PR #55 слит squash commit `eca7982f18c12c23c6aeeecf39db8d1445c8c28d`; repository truth синхронизирован после Room evidence.
- PR #56 слит squash commit `e020d747b59e07c45db5076b38409f4319c51b96`; Android Player proxy measurements, durable baseline и SDK bootstrap завершены.
- PR #57 слит squash commit `f1a92a2d8ef05f0dd61b0492b51a732a4978bd54`; playback request/session header ownership исправлен.
- M3U parse measurement Full: run `30383400416`.
- Android Room focused measurement: run `30400010579`; final permanent-tree Full: `30401263889`.
- Player focused measurement: run `30478201477`; final permanent-tree Full: `30480086398`.
- Header ownership Full: run `30481920247`; DeviceCurrent: `30482460465`; final permanent-tree Full: `30483078985`.
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
- `PlaybackRequest` и `PlaybackSessionRequest` владеют unmodifiable insertion-preserving header snapshots; caller mutation не меняет equality/hash, Bundle или transport input.
- Source-level constructor/defaults, `copy`, `component1..7`, value equality/hash и redacted diagnostics сохранены явно.
- Revocation приводит к повторному warning на следующем входе в Player.
- Channels использует stable channel identity, bounded viewport state и explicit FocusRequester ownership.
- Player → Back восстанавливает канал по stable identity; после удаления применяется nearest-previous fallback.
- Один process-owned MediaSessionService/ExoPlayer сохраняется при Activity recreation и reconnect.
- Setup protocol использует opaque `PlaybackSetupId`, SET/CANCEL и защищён от late install/stale cancel.

### Diagnostics и security

- `ChannelQuery`, channel summary, variant, approval и playback request outcomes имеют redacted diagnostic representation.
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
- Controlled duplicates, long metadata, mixed line endings, relative locators, optional headers и parser-recognized malformed fixture имеют manifest expectations.
- Canonical JSON имеет fixed field order, LF и ровно одну trailing newline.
- Manifest публикуется последним как commit marker; implicit overwrite запрещён, explicit overwrite использует staging и backup/restore.
- `M3uCorpusCommand` имеет stable exit codes и не раскрывает supplied values или full paths.
- HLS/XMLTV starter fixtures имеют stable IDs, byte bounds, typed expected outcomes и payload-redacted diagnostics.
- `:core:testing:test` включён в permanent Fast/Full gate.

### Descriptive measurements

- M3U measurement использует production `StreamingM3uParser`, no-retention sink, raw wall/allocation samples и exact corpus/environment identity.
- Android Room measurement использует fresh file-backed WAL database для каждой sample и отдельно измеряет batch, total staging, activation и reads.
- Player measurement отдельно измеряет request construction, SET envelope codec, coordinator install/clear, cancel-before-install и registry reconnect.
- Player report хранит raw/normalized samples, request-profile SHA, Android environment, `buildMode=debug-instrumentation` и `thresholdApplied=false`.
- Deterministic SDK bootstrap разрешает standard Windows Android SDK path и экспортирует environment последующим Actions steps.
- Dedicated measurement tests исключены из обычных correctness suites без skipped tests.
- Ни parse, ни Room, ни Player baseline пока не являются failing budget или основанием для structural optimization.

## Android TV evidence

Последняя успешная HTTP-approval матрица:

| Профиль | System image | RAM / CPU | Credentials | Database | Media3 | App |
|---|---|---:|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | 4 | 21 | 10 | 12 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | 4 | 21 | 10 | 12 |

Run `30287803018` прошёл без fallback, failures, errors или skips. Android Room focused run `30400010579` и Player focused run `30478201477` прошли на current API 36 profile. Header ownership DeviceCurrent `30482460465` подтвердил реальный Bundle round-trip и полный current-TV correctness suite.

Эмуляторная матрица доказывает Android API/lifecycle/Room/Keystore/focus/MediaSession contracts. Она не доказывает vendor MediaCodec, HDR, passthrough, Fire OS, слабый ARM SoC или реальные zapping/performance характеристики.

## Ближайший production milestone

Issue #27 остаётся активной.

Завершено:

1. deterministic M3U corpus и canonical artifacts;
2. safe repository entry point и typed HLS/XMLTV fixtures;
3. descriptive M3U parse baseline;
4. Android Room stage/activate/query baseline;
5. Android Player control-plane proxy baseline;
6. durable reports, exact environment/profile identities и manual focused modes;
7. immutable playback header ownership.

Следующие packages:

1. repeated comparable parse/Room/Player current series;
2. old-edge API 26/28 measurement series;
3. current low-RAM measurement series;
4. cross-series median/range/coefficient-of-variation analysis;
5. explicit threshold gate or no-threshold decision;
6. fixture consumer binding по мере появления issue #28/#30 runtime consumers.

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
