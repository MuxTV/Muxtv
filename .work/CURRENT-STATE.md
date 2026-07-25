---
status: accepted
last_reviewed: 2026-07-25
architecture_version: 2
implementation_source_commit: 4959e9ef65f2141fdacb588b15111fa607d9b20d
---

# Текущее состояние

## Классификация проекта

MuxTV находится в стадии **functional pre-alpha**: основной Android TV код и сквозной M3U → catalog → Channels → Player путь существуют и исполняются, но продукт ещё не готов к публичным compatibility/release обещаниям.

Проект фактически выполняет часть Phase 01, при этом отдельные quality/release exit criteria Phase 00 — benchmark baseline, подписанный release pipeline и physical-device evidence — ещё открыты.

## Проверенные факты

- Репозиторий: `MuxTV/Muxtv`, private, default branch `main`.
- Лицензия: BSD 3-Clause.
- Android application: `app.muxtv.tv`, версия `0.0.1`.
- `minSdk = 26`; текущая автоматическая TV-матрица покрывает API 26 и API 36.
- В `settings.gradle.kts` подключены 23 Gradle-проекта плюс included build `build-logic`.
- База данных использует Room schema v4 и содержит исполняемые миграционные/transactional contracts.
- CI использует Windows self-hosted runner и режимы Fast, Full, DeviceCurrent и DeviceMatrix через repository-owned PowerShell harness.
- PR #34 объединён squash commit `4959e9ef65f2141fdacb588b15111fa607d9b20d`.
- Финальный Full перед merge: run `30171221399`.
- Последняя acceptance-матрица: run `30170573346`.

## Реализованный рабочий путь

### Источники и каталог

- URL policy отклоняет unsupported schemes, embedded credentials, fragments и encoded control separators до persistence.
- Remote source access хранится через Android Keystore-backed credential store вне Room projections.
- Source-entry поддерживает HTTPS и отдельное явное подтверждение HTTP.
- Полный locator остаётся только в ordinary in-memory state и удаляется после подготовки/disposal.
- Подтверждение показывает только sanitized `scheme://host`.
- Незавершённая подготовка восстанавливается через opaque durable metadata без locator.
- M3U обрабатывается bounded streaming parser.
- Source revisions immutable; импорт идёт через staging с атомарной activation/rollback границей.
- Source refresh поддерживает manual/periodic scheduling и typed status/attempt history.
- Sources UI показывает активные источники и управляет refresh policy.

### Каналы и Player

- PlaybackCatalog строит active channel/variant projections из Room.
- Channels использует stable channel identity, bounded viewport state и явные `FocusRequester`.
- После Player → Back восстанавливается тот же канал, если он существует.
- После reorder сохраняется stable identity; после удаления используется документированный nearest-previous fallback.
- Player подключается к process-owned MediaSessionService и одному ExoPlayer.
- Ready/loading/error states имеют детерминированный безопасный focus.
- `MediaController.release()` выполняется на application/main looper.

### TV interaction и security

- Home, Channels, Sources и Add Source проходят с D-pad/Enter без touch input.
- Text fields явно маршрутизируют D-pad Up/Down и не запирают focus внутри редактора.
- Secure locator field не публикует raw locator в merged/unmerged Compose semantics.
- Stable test/focus tags не содержат provider/source/channel secret values.
- Известные locator/query/token fixtures отсутствуют в проверенных reports, logcat, manifests и screenshots.

## Последняя TV-матрица

| Профиль | System image | RAM / CPU | Credentials | Database | App |
|---|---|---:|---:|---:|---:|
| old edge | `system-images;android-26;android-tv;x86` | 1536 MB / 2 | 4 | 19 | 10 |
| current | `system-images;android-36;android-tv;x86_64` | 2048 MB / 2 | 4 | 19 | 10 |

На обоих профилях: zero failures, zero errors, zero skips. API 26 был доступен напрямую; fallback image не использовался.

Матрица доказывает Android API/lifecycle/Room/Keystore/focus contracts. Она не доказывает vendor MediaCodec, HDR, passthrough, слабый ARM SoC или Fire OS behavior.

## Что ещё не реализовано до публичной alpha

### Ближайший production blocker

Issue #26:

- Media3 всё ещё использует отдельный mutable `DefaultHttpDataSource.Factory`;
- per-request headers необходимо сделать immutable/request-scoped;
- playback redirects должны использовать общий downgrade/cross-origin credential policy;
- failed/cancelled controller future должен быть retryable;
- Player setup должен получить coroutine-native cancellation и late-result handling.

### Следующие продуктовые блоки

- детерминированный M3U/HLS/XMLTV corpus и performance budgets;
- streaming XMLTV parser и immutable EPG revisions;
- now/next, Guide и search;
- законченные Favorites и Recent flows;
- bounded stream fallback и TV Doctor Lite;
- R8/resource shrinking и Baseline Profile;
- signed artifacts, SBOM, changelog и release checklist;
- physical Android/Google TV, constrained-device и Fire TV evidence.

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

Следующий production PR должен закрыть issue #26 без добавления fallback/Doctor/UI redesign:

1. request-scoped Media3 OkHttp datasource;
2. executable header isolation;
3. redirect downgrade/cross-origin credential policy;
4. retry после failed/cancelled controller connection;
5. cancellation-aware Player setup без late install;
6. сохранение одного process-owned player/session;
7. Full + old/current TV evidence и secret review.

После этого проект переходит к corpus/benchmarks, затем XMLTV/EPG и пользовательским Guide/Search/Favorites/Recent flows.
