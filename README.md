# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов. Код лицензирован по BSD 3-Clause; текущий private-репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Android TV приложение, модульный Gradle-проект, Room, защищённое хранилище, bounded M3U ingestion, управление источниками, Channels и process-owned Media3 Player уже существуют и проходят автоматические API 26/API 36 проверки.

Рабочий пользовательский путь:

1. открыть «Источники» с пульта;
2. добавить HTTPS M3U-ссылку или отдельно подтвердить HTTP-источник;
3. сохранить source access в Android Keystore-backed credential store вне Room;
4. потоково импортировать плейлист в staging-каталог и атомарно активировать immutable revision;
5. открыть Channels;
6. выбрать канал и установить request в одну process-owned MediaSession/ExoPlayer;
7. после Player → Back восстановить ранее сфокусированный канал.

Реализованы:

- 23 подключённых Gradle-проекта и convention build logic;
- `applicationId = app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`;
- Room schema v4 с миграциями, staging/activation и rollback-контрактами;
- bounded streaming M3U parser, stable identities и immutable source revisions;
- Android Keystore credential storage, durable onboarding registry и secret-safe source entry;
- manual/periodic source refresh, WorkManager scheduling и Sources UI;
- bounded Channels browser и детерминированный D-pad focus/save-restore;
- один process-owned `ExoPlayer` и `MediaSessionService`;
- request-scoped Media3 OkHttp transport, immutable per-playback headers, redirect/downgrade и cross-origin credential policy;
- retryable MediaController ownership, remote-session reconnect epoch и setup SET/CANCEL protocol без late install;
- repository-owned Windows PowerShell TV harness с последовательной API 26/API 36 DeviceMatrix.

Последний завершённый playback пакет:

- PR #36 — request-scoped Media3 OkHttp transport и header isolation;
- PR #37 — retryable/disconnect-aware MediaController lifecycle;
- PR #38 — deterministic setup cancellation и remote-session reconnect;
- issue #26 закрыта squash commit `8665f80d6e38bc90d10ead0d3a3618fbecd4e304`.

Ближайший подтверждённый runtime-разрыв — issue #39: явное HTTP approval onboarding ещё не переносится в playback request как exact-origin решение. После него запланированы deterministic corpus/benchmarks, XMLTV/EPG и пользовательские Guide/Search/Favorites/Recent.

До первой публичной alpha ещё не завершены:

- exact-origin HTTP playback approval, warning/revocation flow;
- воспроизводимый M3U/HLS/XMLTV corpus и performance budgets;
- XMLTV/EPG, Guide, Search, Favorites и Recent;
- bounded stream fallback и TV Doctor Lite;
- полный светлый TV-first visual redesign из issue #33;
- R8/resource shrinking, Baseline Profile, signing, SBOM и release checklist;
- физические Android/Google TV, constrained-device и Fire TV проверки.

## Сборка и проверка

Debug APK:

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

Harness самостоятельно выбирает доступные Android TV system images, создаёт headless AVD, выполняет non-zero instrumentation suites, сохраняет evidence и гарантированно останавливает emulator.

## Документация

- архитектура, спецификации, ADR и machine-readable metadata: [`.work`](.work/README.md);
- текущий последовательный план: [`docs/superpowers/plans/2026-07-27-next-execution.md`](docs/superpowers/plans/2026-07-27-next-execution.md);
- Media3 setup/reconnect evidence: [`docs/superpowers/reports/2026-07-27-issue26-setup-reconnect-evidence.md`](docs/superpowers/reports/2026-07-27-issue26-setup-reconnect-evidence.md);
- открытые функциональные пакеты ведутся через GitHub Issues и отдельные PR.

## Основные принципы

- TV-first интерфейс с полноценным D-pad/remote управлением;
- local-first и privacy-first данные;
- playlist locators, query values, cookies, credentials и sensitive headers не попадают в Navigation, Room projections, logs, traces, screenshots или exception text;
- immutable revisions и atomic activation вместо частично обновлённого live state;
- один process-owned `ExoPlayer` и `MediaSession`;
- функциональные, schema/security и визуальные изменения выполняются отдельными reviewable PR;
- emulator matrix подтверждает Android API/lifecycle contracts, но не vendor MediaCodec, HDR, passthrough, Fire OS или слабый ARM SoC;
- Kotlin/Compose/Room/Media3 остаются baseline; Rust, libmpv, bundled SQLite и второй engine требуют измеримого bottleneck, corpus и отдельного ADR.

## Лицензия

BSD 3-Clause.
