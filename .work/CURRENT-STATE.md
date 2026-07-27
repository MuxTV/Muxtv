---
status: accepted
last_reviewed: 2026-07-27
architecture_version: 3
implementation_source_commit: 764ec102808c4df57e826d05ce7b1334063bb520
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует и исполняется. Явное HTTP trust теперь переносится из encrypted source access в exact-origin playback resolution с warning, повторным разрешением active variant и revocation.

Benchmark/corpus baseline, XMLTV/EPG, законченные daily-use разделы, release pipeline и physical-device evidence остаются открытыми.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- В `settings.gradle.kts` подключены 23 Gradle-проекта плюс included build `build-logic`.
- Room schema v4; HTTP approval не потребовал отдельной Room security table или migration.
- CI использует Windows self-hosted runner и режимы Fast, Full, DeviceCurrent и DeviceMatrix через repository-owned PowerShell harness.
- PR #38 слит squash commit `8665f80d6e38bc90d10ead0d3a3618fbecd4e304` и закрыл issue #26.
- PR #42 слит squash commit `764ec102808c4df57e826d05ce7b1334063bb520` и закрыл issue #39.
- Cleaned-tree Full для PR #42: run `30295592181`.
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
- Search text, provider/source identity, locator, query, exact origin, cookies, Authorization/Referer и credential values не должны появляться в logs/errors/traces.
- HTTPS → HTTP redirect остаётся запрещённым; cross-origin sensitive headers снимаются.
- Production manifest не содержит process-wide cleartext opt-in или network-wide HTTP allow-list.
- Request-scoped repository clients, а не platform default, являются HTTP security boundary на всех поддерживаемых API.

## Android TV evidence

Последняя успешная HTTP-approval матрица:

| Профиль | System image | RAM / CPU | Credentials | Database | Media3 | App |
|---|---|---:|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | 4 | 21 | 10 | 12 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | 4 | 21 | 10 | 12 |

Run `30287803018` прошёл без fallback, failures, errors или skips. Последующие shared-manager, stale-variant, revocation и diagnostic-redaction изменения прошли cleaned-tree Full; revocation journey был скомпилирован, но отдельный повторный exact-head DeviceMatrix не использовался как merge gate из-за занятости единственного runner.

Эмуляторная матрица доказывает Android API/lifecycle/Room/Keystore/focus/MediaSession contracts. Она не доказывает vendor MediaCodec, HDR, passthrough, Fire OS, слабый ARM SoC или реальные zapping/performance характеристики.

## Ближайший production milestone

Issue #27: deterministic provider-neutral M3U/HLS/XMLTV corpus и воспроизводимые measurements.

Первый пакет должен дать:

1. deterministic M3U profiles 1k/10k/50k с explicit seed;
2. manifest с expected counts, byte size, generator version и SHA-256;
3. controlled duplicates, malformed attributes, long metadata, relative URLs и header variants;
4. byte-identical output для одинакового seed/profile;
5. parser/importer tests против manifest expectations;
6. descriptive measurements до назначения performance budgets.

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
- Rust/UniFFI, libmpv, bundled SQLite, Paging и второй engine допускаются только после corpus-backed benchmark/security ADR.
- Физические Android/Google TV/Fire TV проверки дополняют, но не заменяют автоматическую API-матрицу.
