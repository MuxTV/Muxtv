# MuxTV

MuxTV — бесплатное open-source приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Android TV приложение, модульный Gradle-проект, база Room, сетевой слой, защищённое хранилище, импорт M3U, управление источниками, список каналов и базовый Media3 player уже существуют.

Рабочий пользовательский путь сейчас:

1. открыть раздел «Источники» с пульта;
2. добавить HTTPS M3U-ссылку или отдельно подтвердить доверенный HTTP-источник;
3. сохранить доступ в Android Keystore вне Room;
4. импортировать плейлист в staging-каталог и атомарно активировать ревизию;
5. увидеть источник и каналы;
6. открыть канал в process-owned Media3 session;
7. вернуться к ранее сфокусированному каналу.

Реализованы:

- 23 подключённых Gradle-модуля и convention build logic;
- `applicationId = app.muxtv.tv`, текущая версия `0.0.1`;
- Room schema v4 с миграциями, staging/activation и rollback-контрактами;
- bounded streaming M3U parser и immutable source revisions;
- Android Keystore credential storage и secret-safe onboarding;
- source refresh/scheduling и Sources UI;
- Channels browser, базовый Player и MediaSessionService;
- детерминированный D-pad focus, save/restore и fallback после удаления канала;
- self-hosted Full validation и последовательная Android TV матрица API 26/API 36.

До первой публичной alpha ещё не завершены:

- Media3 OkHttp transport/header isolation и reconnect hardening;
- воспроизводимый M3U/HLS/XMLTV corpus и performance budgets;
- XMLTV/EPG, Guide, Search, Favorites и Recent как законченные пользовательские разделы;
- bounded stream fallback и TV Doctor Lite;
- R8, Baseline Profile, подписанные release artifacts, SBOM и release checklist;
- проверка на физических Android/Google TV, слабом устройстве и Fire TV.

## Сборка и проверка

```powershell
.\gradlew.bat :app:tv:assembleDebug
```

Полная локальная проверка:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

Последовательная Android TV матрица старого и текущего API:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch local `
  -SourceCommit local `
  -NoDaemon
```

Harness самостоятельно выбирает доступные Android TV system images, создаёт headless AVD, выполняет instrumentation suites, сохраняет evidence и останавливает эмулятор.

## Документация

- рабочая архитектура, спецификации, ADR и machine-readable metadata: [`.work`](.work/README.md);
- текущий последовательный план: [`docs/superpowers/plans/2026-07-25-next-execution.md`](docs/superpowers/plans/2026-07-25-next-execution.md);
- открытые функциональные пакеты ведутся через GitHub Issues и отдельные PR.

## Основные принципы

- TV-first интерфейс, рассчитанный на D-pad и пульт;
- local-first данные и настройки;
- playlist locators, credentials и sensitive headers не попадают в navigation, Room projections, logs или screenshots;
- immutable revisions и атомарная активация вместо частичного обновления live-каталога;
- один process-owned `ExoPlayer` и `MediaSession`;
- Android TV first; Fire TV и другие платформы подтверждаются отдельной evidence-матрицей;
- Media3 остаётся основным engine; Rust, libmpv и второй player engine требуют измеримого bottleneck и отдельного ADR.

## Лицензия

BSD 3-Clause.
