---
status: accepted
last_reviewed: 2026-07-27
architecture_version: 2
implementation_source_commit: 8665f80d6e38bc90d10ead0d3a3618fbecd4e304
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**. Сквозной Android TV путь source onboarding → immutable catalog → Channels → process-owned Media3 Player существует и исполняется, но продукт ещё не готов к публичным compatibility/release обещаниям.

Phase 01 уже содержит рабочий IPTV vertical slice и hardened playback ownership. Benchmark baseline, XMLTV/EPG, законченные daily-use разделы, release pipeline и physical-device evidence остаются открытыми.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`, BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`.
- В `settings.gradle.kts` подключены 23 Gradle-проекта плюс included build `build-logic`.
- База использует Room schema v4 с исполняемыми миграционными и transactional contracts.
- CI использует Windows self-hosted runner и режимы Fast, Full, DeviceCurrent и DeviceMatrix через repository-owned PowerShell harness.
- PR #36 слит squash commit `f241d0c7eb1b8dfbd89b81e3f21dee75aa34940e`.
- PR #37 слит squash commit `66cf8dbaddafa87be7bfd619515452ceb3c46354`.
- PR #38 слит squash commit `8665f80d6e38bc90d10ead0d3a3618fbecd4e304` и закрыл issue #26.
- Финальный Full PR #38: run `30223482178` на cleaned head `f4c7731dff930200c5cefb77765d0fa37b13b02f`.
- Последняя playback DeviceMatrix: run `30222900566`.

## Реализованный рабочий путь

### Источники и каталог

- URL policy отклоняет unsupported schemes, embedded credentials, fragments и encoded control separators до persistence.
- Remote source access хранится в Android Keystore-backed credential store вне Room projections.
- Source-entry поддерживает HTTPS и отдельное явное подтверждение HTTP.
- Locator остаётся только в bounded transient state и не попадает в Navigation, SavedState, Room projections или semantics.
- Незавершённая подготовка восстанавливается через opaque durable metadata без locator.
- M3U обрабатывается bounded streaming parser.
- Source revisions immutable; импорт идёт через staging с atomic activation/rollback.
- Catalog staging использует immutable bounded batches и stable identity hashing.
- Source refresh поддерживает manual/periodic WorkManager scheduling и typed attempt state.
- Sources UI показывает активные источники и управляет refresh policy.

### Каналы и Player

- PlaybackCatalog строит active channel/variant projections из Room.
- Channels использует stable channel identity, bounded viewport state и explicit FocusRequester ownership.
- Player → Back восстанавливает канал по stable identity; после удаления применяется nearest-previous fallback.
- Player подключается к одному process-owned MediaSessionService и одному ExoPlayer.
- Каждый playback request создаёт request-scoped Media3 OkHttp datasource/media-source chain.
- Per-request headers immutable и не переходят между последовательно установленными stream requests.
- Redirect policy отклоняет HTTPS → HTTP downgrade и снимает sensitive headers при cross-origin переходе.
- Failed/cancelled controller connection не отравляет process lifetime: следующий connect может создать новый future.
- Remote MediaSession disconnect инвалидирует только matching cached controller и увеличивает connection epoch.
- Видимый Player повторяет один bounded connect/resolve/setup после epoch change без сохранения locator/header state.
- Setup protocol использует opaque `PlaybackSetupId` и отдельные SET/CANCEL команды.
- Cancel-before-install блокирует поздний setup; stale cancel не останавливает более новый playback.
- Parent coroutine cancellation и timeout отменяют waiting future и инициируют ровно один best-effort service cancel.
- Один process-owned player/session invariant сохраняется при Activity recreation и remote-session reconnect.

### TV interaction и security

- Home, Channels, Sources и Add Source проходят с D-pad/Enter без touch input.
- Text fields явно маршрутизируют D-pad Up/Down и не запирают focus внутри редактора.
- Secure locator field не публикует raw locator в merged/unmerged Compose semantics.
- Stable test/focus tags не содержат provider/source/channel secret values.
- Playback setup IDs, locators, query values, cookies, Authorization/Referer и sensitive headers редактируются на diagnostics boundary.
- Известные secret fixtures отсутствуют в проверенных reports, logcat, manifests и screenshots.

## Последняя Android TV матрица

| Профиль | System image | RAM / CPU | Credentials | Database | Media3 | App |
|---|---|---:|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | 4 | 19 | 10 | 11 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | 4 | 19 | 10 | 11 |

Run `30222900566` прошёл на обоих профилях без fallback, failures, errors или skips. Matrix доказывает Android API/lifecycle/Room/Keystore/focus/MediaSession command ownership. Она не доказывает vendor MediaCodec, HDR, passthrough, Fire OS, слабый ARM SoC или реальные zapping/performance характеристики.

## Ближайший production blocker

Issue #39: HTTP approval onboarding пока не доходит до playback request как exact-origin решение.

Фактический разрыв:

1. пользователь может явно подтвердить HTTP source в source-entry;
2. encrypted source access сохраняет это решение для refresh/onboarding;
3. `ResolvedPlaybackRequest` не несёт approval context;
4. `PlayerRoute` создаёт `PlaybackSessionRequest` с `insecureHttpApproved = false`;
5. HTTP playlist способен импортироваться, но его HTTP channel playback затем отклоняется transport policy.

Следующий runtime PR должен решить этот разрыв без process-wide cleartext opt-in и без доверия другому host/port.

## Следующие продуктовые блоки

После issue #39:

1. issue #27 — deterministic provider-neutral M3U/HLS/XMLTV corpus и performance baselines;
2. issue #28 — bounded XMLTV ingest и immutable EPG revisions;
3. issue #29 — now/next, Guide, Search, Favorites и Recent;
4. issue #30 — bounded variant fallback и TV Doctor Lite;
5. issue #33 — последовательная светлая TV-first visual modernization без новой state architecture;
6. issue #31 — R8, Baseline Profile, signing, SBOM, release checklist и physical-device alpha gate.

## Сохраняемые архитектурные решения

- Kotlin + native Compose остаются Android TV baseline.
- Room/SQLite остаётся Android-first storage boundary; full KMP database требует отдельного клиента и ADR.
- Media3 остаётся primary playback engine behind stable contracts.
- Source/EPG updates используют immutable revisions, staging и atomic commit.
- Provider data, canonical channels и profile overlays разделены.
- Remote playlists/XML/images/provider endpoints считаются untrusted.
- Rust/UniFFI, libmpv, bundled SQLite, Paging и второй player engine допускаются только после corpus-backed benchmark/security ADR.
- Физические Android/Google TV/Fire TV проверки дополняют, но не заменяют автоматическую API-матрицу.

## Следующий проверяемый результат

Issue #39 закрыта отдельным schema/security/product PR, который доказывает:

1. approval scoped к exact normalized HTTP origin, включая effective port;
2. approved origin может импортироваться и воспроизводиться;
3. другой host или port не наследует доверие;
4. source deletion/revocation инвалидирует approval;
5. HTTPS → HTTP redirect остаётся запрещённым;
6. production manifest не получает глобальный cleartext opt-in;
7. locator/query/header/credential values отсутствуют в Room approval rows, Navigation, state, logs, semantics и reports;
8. Full и API 26/API 36 evidence проходят на exact head.
