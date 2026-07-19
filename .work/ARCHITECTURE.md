---
status: accepted
last_reviewed: 2026-07-19
architecture_version: 1
---

# Целевая архитектура MuxTV

## 1. Архитектурный стиль

MuxTV строится как **модульный монолит Android TV-first** с однонаправленным потоком данных, единым источником истины и изолированными контрактами для provider, playback, EPG и compute engine.

Начальная реализация остаётся в одном репозитории и одном APK. Модульность должна ускорять тестирование и замену компонентов, а не имитировать микросервисы.

## 2. Платформенная граница

### Android application

Отвечает за:

- Compose for TV UI и focus management;
- Media3, MediaSession, audio focus и device capabilities;
- Room/SQLite, WorkManager, PackageInstaller;
- локальный Ktor server;
- lifecycle и Android permissions.

### Shared core

Kotlin Multiplatform используется только для логики, которую реально планируется повторно применять:

- domain models;
- M3U/XMLTV parsing contracts;
- normalization и duplicate matching;
- stream scoring;
- backup schema;
- search query interpretation;
- extension contracts без Android типов.

UI остаётся нативным Android TV. Общий UI на Compose Multiplatform в первой линии запрещён архитектурным решением.

## 3. Слои

```text
UI / Feature
    ↓ intents, state
Application / Use Cases
    ↓ domain ports
Domain
    ↓ repository and engine contracts
Data / Platform adapters
    ↓
Room · OkHttp · Media3 · WorkManager · Ktor
```

Правила:

- `domain` не зависит от Android, Room, Media3, OkHttp и DI;
- feature-модули не обращаются к DAO или network client напрямую;
- provider adapters не управляют UI и playback lifecycle;
- playback engine не знает о M3U/Xtream и получает нормализованный `PlaybackRequest`;
- пользовательские overlay-данные не перезаписываются при refresh источника.

## 4. Состояние и конкурентность

- UI использует immutable `UiState`, события и side effects.
- Coroutines/Flow являются основным механизмом async и observability.
- Импорт и refresh работают через staging tables и атомарный commit.
- Один source refresh имеет unique work key; параллельные refresh одного источника запрещены.
- Playback state живёт в process-scoped service/controller, а не в экране.
- Тяжёлые операции parsing/matching выполняются batch-ами и не удерживают весь XMLTV в памяти.

## 5. Данные

Ключевые агрегаты:

- `Source`
- `ProviderChannel`
- `CanonicalChannel`
- `StreamVariant`
- `UserChannelOverlay`
- `EpgSource`, `EpgChannel`, `EpgProgram`, `EpgBinding`
- `StreamHealthSnapshot`
- `PlaybackAttempt`
- `UserProfile`, `ParentalRule`
- `ExtensionDescriptor`

Provider data, canonical data и user overlays хранятся раздельно. Это позволяет обновлять source без потери пользовательского порядка, имён, групп, избранного и ручного EPG.

## 6. Воспроизведение

Основной engine — Media3. За ним расположен собственный `PlaybackEngine` contract, чтобы:

- тестировать orchestration без реального player;
- добавить optional libmpv compatibility flavor;
- реализовать failover между Stream Variant;
- собирать нормализованную диагностику;
- не протекать типами Media3 в domain и feature.

`PlaybackOrchestrator` отвечает за state machine: resolving → preparing → playing → recovering → failed/stopped.

## 7. Расширения

Порядок расширяемости:

1. Встроенные adapters в репозитории.
2. Декларативные manifests/rules без исполняемого кода.
3. Companion APK через версионированный Binder/AIDL contract и capability grants.

Загрузка случайного DEX/JS/native-кода в основной процесс запрещена.

## 8. Rust

Rust не является обязательной частью MVP. С первого дня существует `CatalogComputeEngine` contract и Kotlin implementation. Rust/UniFFI допускается для parser, matching или fingerprinting только после benchmark, если достигается измеримое преимущество без ухудшения APK size, startup и crash diagnostics.

## 9. Безопасность и приватность

- credentials шифруются Android Keystore-backed механизмом;
- секреты исключаются из логов и backup по умолчанию;
- локальная web-панель требует одноразовый pairing token и работает только в LAN;
- cleartext HTTP разрешается только для media/source hosts, добавленных пользователем, с явным предупреждением;
- extension получает минимальный набор capabilities;
- telemetry выключена по умолчанию и не является обязательной для работы.

## 10. Производительность

Обязательны benchmark corpus и performance gates для:

- cold start;
- first interactive frame;
- импорт 1k/10k/100k каналов;
- XMLTV 1/5/20 GB decompressed stream;
- открытие Live TV и EPG;
- channel zapping и first video frame;
- час непрерывного playback;
- прокрутка rail и EPG без sustained jank.

Baseline Profiles покрывают startup, Live TV, EPG, поиск и переключение каналов.