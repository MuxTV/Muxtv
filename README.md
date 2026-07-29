# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов. Код лицензирован по BSD 3-Clause; текущий private-репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Android TV приложение, модульный Gradle-проект, Room, защищённое хранилище, bounded M3U ingestion, управление источниками, Channels и process-owned Media3 Player существуют и проходят автоматические API 26/API 36 проверки.

Рабочий пользовательский путь:

1. открыть «Источники» с пульта;
2. добавить HTTPS M3U-ссылку или отдельно подтвердить HTTP-источник;
3. сохранить source access и exact-origin playback approvals в Android Keystore-backed credential store вне Room;
4. потоково импортировать плейлист в staging-каталог и атомарно активировать immutable revision;
5. открыть Channels;
6. выбрать канал;
7. для нового HTTP host/port подтвердить только canonical exact origin, после чего Player заново разрешает активный variant;
8. установить request в одну process-owned MediaSession/ExoPlayer;
9. после Player → Back восстановить ранее сфокусированный канал.

Реализованы:

- 23 подключённых Gradle-проекта и convention build logic;
- `applicationId = app.muxtv.tv`, версия `0.0.1`, `minSdk = 26`;
- Room schema v4 с миграциями, staging/activation и rollback-контрактами;
- bounded streaming M3U parser, stable identities и immutable source revisions;
- Android Keystore credential storage, durable onboarding registry и secret-safe source entry;
- один singleton `RemoteSourceAccessManager` для encrypted source access и playback approvals;
- exact-origin HTTP approval (`scheme + normalized host + effective port`), warning, re-resolution и revocation/reset;
- manual/periodic source refresh, WorkManager scheduling и Sources UI;
- bounded Channels browser и детерминированный D-pad focus/save-restore;
- один process-owned `ExoPlayer` и `MediaSessionService`;
- request-scoped Media3 OkHttp transport, immutable per-playback headers, redirect/downgrade и cross-origin credential policy;
- retryable MediaController ownership, remote-session reconnect epoch и setup SET/CANCEL protocol без late install;
- immutable owned header snapshots в `PlaybackRequest` и `PlaybackSessionRequest` с сохранёнными constructor/copy/component/value contracts;
- redacted catalog/playback/M3U diagnostics без locator, exact origin, query, provider/source identity и credential values;
- deterministic provider-neutral M3U corpus с профилями 1k/10k/50k, explicit seed/source commit, expected counts, byte size и SHA-256;
- canonical fixed-order UTF-8 manifest JSON со stable schema/profile IDs и exact source commit;
- согласованная публикация `.m3u8 + .manifest.json` через staging, explicit overwrite, backup/restore и typed rollback failures;
- repository-owned безопасный CLI и Gradle task для генерации corpus artifact pair;
- bounded typed HLS/XMLTV starter fixtures с synthetic `.example` resources и expected outcomes;
- reproducible descriptive M3U parse measurements с raw samples, wall-time/allocation distributions и environment metadata;
- reproducible Android Room measurements для 250-entry batch, 10k staging, activation, first-page channel query и source overview;
- reproducible Android Player control-plane proxy measurements для request construction, SET codec, setup coordination и controller reconnect;
- complete length-prefixed fixture/request-profile SHA-256, DB/WAL/SHM footprint и ручные `CatalogMeasurement` / `PlayerMeasurement` modes;
- deterministic Windows Android SDK bootstrap, если runner process не получил `ANDROID_SDK_ROOT`/`ANDROID_HOME`;
- permanent `core:testing` contracts в Fast/Full validation;
- repository-owned Windows cleanup с `core.longpaths`, explicit reset/clean и clean-workspace evidence;
- repository-owned PowerShell TV harness с последовательной API 26/API 36 DeviceMatrix.

Последние завершённые packages:

- PR #36 — request-scoped Media3 OkHttp transport и header isolation;
- PR #37 — retryable/disconnect-aware MediaController lifecycle;
- PR #38 — deterministic setup cancellation и remote-session reconnect;
- PR #42 — encrypted exact-origin HTTP playback approval, warning/re-resolution, revocation и shared access ownership;
- PR #43 — repository truth и deterministic Windows self-hosted cleanup;
- PR #45 — redacted untrusted M3U diagnostics;
- PR #44 — deterministic M3U corpus foundation;
- PR #47 — canonical corpus manifest JSON;
- PR #48 — deterministic corpus artifact pair publisher;
- PR #50 — repository corpus generation command и configuration-cache-safe Gradle task;
- PR #51 — bounded typed HLS/XMLTV starter fixtures;
- PR #52 — repository truth переведён к descriptive measurements;
- PR #53 — descriptive M3U parse measurements;
- PR #54 — Android Room stage/activate/query measurements и durable baseline;
- PR #55 — repository truth после Room measurements;
- PR #56 — Android Player proxy measurements, durable baseline и deterministic SDK bootstrap;
- PR #57 — immutable ownership playback request headers и real-Android Bundle contract;
- issues #26 и #39 закрыты; issue #27 остаётся активной только до repeated multi-profile series и threshold decision.

Следующий package issue #27 — повторяемые parse/Room/Player серии на current, old-edge и low-RAM virtual profiles с единым environment fingerprint и cross-series variance analysis. Failing threshold допускается только после сопоставимых повторных данных.

До первой публичной alpha ещё не завершены:

- repeated multi-profile measurement series и обоснованный threshold/no-threshold decision;
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

Генерация малого deterministic M3U corpus и canonical manifest:

```powershell
.\gradlew.bat :core:testing:generateM3uCorpus `
  -PcorpusProfile=small-1k `
  -PcorpusSeed=20260728 `
  -PcorpusSourceCommit=<полный-lowercase-40-character-git-sha>
```

По умолчанию artifacts создаются в `core/testing/build/corpus`. Повторная запись требует явного `-PcorpusOverwrite=true`. Доступные профили: `small-1k`, `medium-10k`, `large-50k`.

Последовательная Android TV матрица старого и текущего API:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch local `
  -SourceCommit local `
  -NoDaemon
```

Focused Android Room measurement на current TV profile:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-CatalogDatabaseDeviceValidation.ps1 `
  -SourceBranch local `
  -SourceCommit <полный-lowercase-40-character-git-sha> `
  -NoDaemon
```

Focused Player control-plane proxy measurement:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-PlayerProxyDeviceValidation.ps1 `
  -SourceBranch local `
  -SourceCommit <полный-lowercase-40-character-git-sha> `
  -NoDaemon
```

Harness самостоятельно выбирает доступные Android TV system images, создаёт headless AVD, выполняет non-zero instrumentation suites, сохраняет evidence и гарантированно останавливает emulator. Эмуляторная матрица проверяет Android API/lifecycle/Room/Keystore/focus/MediaSession contracts, но не vendor MediaCodec, HDR, passthrough, Fire OS или производительность слабого ARM SoC.

## Документация

- архитектура, спецификации, ADR и machine-readable metadata: [`.work`](.work/README.md);
- активный последовательный план: [`docs/superpowers/plans/2026-07-27-post-http-approval-execution.md`](docs/superpowers/plans/2026-07-27-post-http-approval-execution.md);
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md);
- M3U parse baseline: [`docs/performance/2026-07-28-m3u-parse-baseline.md`](docs/performance/2026-07-28-m3u-parse-baseline.md);
- Android Room baseline: [`docs/performance/2026-07-28-catalog-database-baseline.md`](docs/performance/2026-07-28-catalog-database-baseline.md);
- Player proxy baseline: [`docs/performance/2026-07-29-player-proxy-baseline.md`](docs/performance/2026-07-29-player-proxy-baseline.md);
- HTTP approval design/record: [`docs/superpowers/specs/2026-07-27-exact-origin-http-playback-approval-design.md`](docs/superpowers/specs/2026-07-27-exact-origin-http-playback-approval-design.md);
- Media3 setup/reconnect evidence: [`docs/superpowers/reports/2026-07-27-issue26-setup-reconnect-evidence.md`](docs/superpowers/reports/2026-07-27-issue26-setup-reconnect-evidence.md);
- открытые функциональные packages ведутся через GitHub Issues и отдельные PR.

## Основные принципы

- TV-first интерфейс с полноценным D-pad/remote управлением;
- local-first и privacy-first данные;
- playlist locators, query values, cookies, credentials, provider identities и sensitive headers не попадают в Navigation, Room public projections, logs, traces, screenshots или exception text;
- immutable revisions и atomic activation вместо частично обновлённого live state;
- один process-owned `ExoPlayer` и `MediaSession`;
- один in-process owner encrypted source access; никакого второго approval/security store;
- playback requests владеют immutable snapshots заголовков после валидации;
- функциональные, schema/security, corpus/measurement, infrastructure и визуальные изменения выполняются отдельными reviewable PR;
- Kotlin/Compose/Room/Media3 остаются baseline; Rust, libmpv, bundled SQLite и второй engine требуют измеримого bottleneck, corpus и отдельного ADR.

## Лицензия

BSD 3-Clause.
