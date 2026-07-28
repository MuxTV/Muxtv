---
status: accepted
last_reviewed: 2026-07-28
architecture_version: 3
implementation_source_commit: d7bc58a398c065018d9131c176e8e1c131766c88
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует и исполняется. Явное HTTP trust переносится из encrypted source access в exact-origin playback resolution с warning, повторным разрешением active variant и revocation.

Deterministic M3U corpus foundation и canonical artifact publication уже реализованы. Profiles 1k/10k/50k генерируются потоково по explicit seed/source commit; manifest имеет stable schema/profile IDs, exact counts, byte size и SHA-256; `.m3u8 + .manifest.json` публикуются согласованной парой через staging и explicit backup/restore. Repository entry point, HLS/XMLTV fixtures, measurements, XMLTV/EPG, законченные daily-use разделы, release pipeline и physical-device evidence остаются открытыми.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- В `settings.gradle.kts` подключены 23 Gradle-проекта плюс included build `build-logic`.
- Room schema v4; HTTP approval не потребовал отдельной Room security table или migration.
- CI использует Windows self-hosted runner и режимы Fast, Full, DeviceCurrent и DeviceMatrix через repository-owned PowerShell harness.
- PR #38 слит squash commit `8665f80d6e38bc90d10ead0d3a3618fbecd4e304` и закрыл issue #26.
- PR #42 слит squash commit `764ec102808c4df57e826d05ce7b1334063bb520` и закрыл issue #39.
- PR #43 слит squash commit `80dff5132f624ffedacfdbab0d7bdfe67d85f2a8`; два последовательных Full attempts подтвердили clean workspace с repository-local `core.longpaths`.
- PR #45 слит squash commit `dc6e6b2357de12de65932857ca637ff9631782f1` и закрыл M3U diagnostic leak.
- PR #44 слит squash commit `3e24cccb188b53652285929a11e3b50697aad5f7`; это Package A issue #27, а не закрытие всего milestone.
- PR #47 слит squash commit `f992e8269cd402d679905efb57a2af633a99772c`; canonical manifest JSON и strict source-commit contract завершены.
- PR #48 слит squash commit `d7bc58a398c065018d9131c176e8e1c131766c88`; deterministic artifact pair publication и rollback semantics завершены.
- Cleaned-tree Full для PR #42: run `30295592181`.
- Corpus foundation Full: run `30365096484`; `:core:testing:test` исполняется в permanent gate.
- Canonical manifest Full: run `30367547682`.
- Artifact pair publisher Full: run `30370708253`.
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

### Deterministic corpus и artifacts

- `core:testing` владеет provider-neutral M3U generator, canonical manifest writer и artifact publisher; production ingest/runtime не зависят от testing module.
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
- Request/result/error diagnostics не раскрывают filesystem paths.
- `:core:testing:test` включён в permanent Fast/Full gate; прежний false-positive validation gap закрыт.

## Android TV evidence

Последняя успешная HTTP-approval матрица:

| Профиль | System image | RAM / CPU | Credentials | Database | Media3 | App |
|---|---|---:|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | 4 | 21 | 10 | 12 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | 4 | 21 | 10 | 12 |

Run `30287803018` прошёл без fallback, failures, errors или skips. Последующие shared-manager, stale-variant, revocation, diagnostic-redaction и corpus изменения прошли cleaned-tree Full. Corpus utilities не меняют Android runtime, поэтому повторная DeviceMatrix не является merge gate для этих pure-Kotlin packages.

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
7. byte-identical repeated output;
8. parser agreement и permanent testing gate.

Следующие packages:

1. repository-owned Gradle/CLI generation entry point;
2. starter HLS/XMLTV fixtures с typed manifests;
3. importer agreement с serialized manifest where applicable;
4. descriptive parse/stage/activate/query/Player measurements;
5. repeated variance evidence до назначения budgets.

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
- Rust/UniFFI, libmpv, bundled SQLite, Paging и второй engine допускаются только после corpus-backed benchmark/security ADR.
- Физические Android/Google TV/Fire TV проверки дополняют, но не заменяют автоматическую API-матрицу.
