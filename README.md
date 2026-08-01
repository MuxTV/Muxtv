# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий private-репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. На `main` (`27bb5bc49685779251b75c6e0aa134e4aaf4d3b1`) реализованы source onboarding, immutable M3U catalog, Channels, process-owned Media3 Player и полный EPG foundation до secure conditional remote refresh включительно.

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Room 3, WorkManager, OkHttp и Media3. Room schema — **v5**. `minSdk = 26`.

### Рабочий IPTV путь

1. добавить HTTPS M3U или отдельно подтвердить HTTP-источник;
2. сохранить access в Android Keystore-backed credential store вне Room public projections;
3. потоково импортировать M3U в staging и атомарно активировать immutable source revision;
4. открыть Channels и запустить канал;
5. для нового HTTP host/port подтвердить exact origin;
6. Player заново разрешает active variant и устанавливает request в process-owned MediaSession/ExoPlayer;
7. Player → Back восстанавливает stable channel focus.

### Реализованный EPG foundation

- PR #63 — secure bounded streaming XMLTV parser без DOM, с независимыми limits, XXE/DTD protection и typed timestamp handling;
- PR #64 — Room v5 `epg_sources` / `epg_revisions` / `epg_channels` / `epg_programmes`, immutable staging, monotonic atomic activation, previous-good retention, bounded queries и migration/device contracts;
- PR #68 — bounded magic-first plain/gzip/ZIP EPG payload decoder с post-decompression limits;
- PR #72 — secure conditional remote EPG refresh через существующий encrypted access/network boundary, `If-None-Match` / `If-Modified-Since`, safe `304`, bounded `200` decode/import и cancellation preserving previous-good guide.

Последние проверочные evidence:

- XMLTV parser Full: `30576931624`;
- immutable EPG Full: `30663759211`;
- Room migration API 26/API 36 matrix: `30663759884`;
- payload decoder Full: `30666205286`;
- remote EPG refresh exact-head Full: `30668000159`.

## Что ещё не завершено

Критический EPG путь теперь:

1. **issue #70** — durable EPG refresh scheduling/state: WorkManager policy, DB lease, retry/cancel semantics, `304` success without new revision, startup reconciliation и Room v5→v6 при необходимости;
2. **issue #71** — deterministic explainable channel matching + bounded now/next projections;
3. закрытие **issue #28** после интеграционного evidence;
4. **issue #29** — Guide, Search, Favorites, Recent и real now/next UI;
5. **issue #33** — TV-first visual modernization без новой state architecture;
6. **issue #30** — bounded variant fallback + TV Doctor Lite;
7. **issue #31** — R8, Baseline Profile, signed alpha, SBOM/release checklist и physical TV evidence.

Параллельно остаётся **issue #27**: пять независимых repetitions для `current-normal`, `old-edge-normal`, `current-low-ram`, затем per-operation hard-gate/warning/descriptive decision. Эти измерения не блокируют уже реализованный EPG foundation. Rust/UniFFI, bundled SQLite, libmpv и второй player engine остаются запрещёнными без reproducible bottleneck/compatibility evidence и отдельного ADR.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- playlist/XML locators, query values, cookies, credentials, provider identities и sensitive headers не попадают в Navigation, public Room projections, logs, traces, screenshots или raw exception text;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation и network failure;
- один process-owned `ExoPlayer`/`MediaSession`;
- один in-process owner encrypted source access;
- WorkManager используется как durable orchestration boundary, а DB lease обеспечивает per-source serialization;
- UI не выполняет full-catalog/full-guide materialization;
- emulator/API matrix проверяет Android contracts, но не доказывает vendor codec/HDR/passthrough/Fire OS/weak ARM performance.

## Сборка и проверка

Debug APK:

```powershell
.\gradlew.bat :app:tv:assembleDebug
```

Полная локальная проверка:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

API 26/API 36 Android TV matrix:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -NoDaemon
```

Deterministic M3U corpus:

```powershell
.\gradlew.bat :core:testing:generateM3uCorpus `
  -PcorpusProfile=small-1k `
  -PcorpusSeed=20260728 `
  -PcorpusSourceCommit=<full-lowercase-40-character-git-sha>
```

Measurement series example:

```powershell
pwsh -NoProfile -File .\tools\measurements\Invoke-MeasurementSeries.ps1 `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -ProfileId current-normal `
  -Repetitions 5 `
  -NoDaemon
```

## Документация

- текущая repository truth: [`.work/CURRENT-STATE.md`](.work/CURRENT-STATE.md);
- machine-readable status: [`.work/meta/status.yaml`](.work/meta/status.yaml);
- активный план: [`docs/superpowers/plans/2026-08-01-post-remote-epg-execution.md`](docs/superpowers/plans/2026-08-01-post-remote-epg-execution.md);
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md);
- current-profile variance smoke: [`docs/performance/2026-07-30-current-variance-smoke.md`](docs/performance/2026-07-30-current-variance-smoke.md);
- EPG Room v5 evidence: [`docs/database/2026-07-30-epg-room-v5-evidence.md`](docs/database/2026-07-30-epg-room-v5-evidence.md);
- открытые функциональные packages ведутся через GitHub Issues и отдельные reviewable PR.

## Лицензия

BSD 3-Clause.
