# MuxTV

MuxTV — local-first приложение для Android TV, Google TV и Fire TV, которое превращает пользовательские IPTV-источники в единый локальный каталог каналов и локальный EPG. Код лицензирован по BSD 3-Clause; текущий репозиторий и собираемые artifacts пока не являются публичным релизом.

Приложение не предоставляет телеканалы и не продаёт IPTV-подписки: пользователь подключает только источники, на использование которых у него есть права.

## Статус

Проект находится в стадии **functional pre-alpha**. Принятый `main` — `7af053ca14281d9e63a51470fbeb3cb8d708c318` (PR #107 Recent / Room v10).

Production baseline: Kotlin, Coroutines/Flow, Compose for TV, Navigation 3, Hilt, Room 3, WorkManager, OkHttp и Media3. Room schema на принятом `main` — **v10**. `minSdk = 26`.

Рабочий продуктовый контур:

1. secure source onboarding с Keystore-backed access isolation;
2. bounded streaming M3U ingest;
3. immutable source revisions, staging и atomic activation;
4. durable source refresh ownership;
5. bounded XMLTV + secure conditional remote EPG refresh;
6. immutable EPG revisions и deterministic EPG matching;
7. hardened plain/gzip/ZIP transport с bounded compressed/decoded limits;
8. bounded Now/Next;
9. Channels с destination-scoped state и profile-scoped Favorites;
10. bounded Unicode Search Core + Search TV;
11. process-owned Media3 Player;
12. Player/Search/Recent → Back со stable canonical-channel focus restoration;
13. service-owned first-rendered-frame success boundary;
14. profile-scoped bounded Recent, записываемый только после accepted first frame.

### Последние принятые продуктовые этапы

- PR #104 — bounded remote-first Search TV;
- PR #106 — service-owned first-rendered-frame success signal;
- PR #119 — EPG compressed transport hardening и device-matrix ownership для `catalog/refresh/**`;
- PR #107 — profile-scoped Recent, Channels `Недавние`, Room v10 и exact generated schema.

Final acceptance PR #107, exact head `d095fb0e99485f93f9dbed8675c13b0f5ac52537`:

- Android TV Product DeviceMatrix `31027992936` — success;
- Full host acceptance выполнен внутри матрицы до AVD;
- API26 и API36: database 120/120, app 26/26, Media3 12/12, credentials 4/4, importer EPG 1/1, remote EPG 1/1;
- Room v10 schema SHA-256 `809c0bfa812e5a86a5a84d97fe4f48f1d9ac71e515c5745ef222f24689e926c4` и identity `f6625d546ddfbad62e4e33340b17f490` совпали на host/API26/API36;
- unresolved review threads — 0;
- squash merge — `7af053ca14281d9e63a51470fbeb3cb8d708c318`.

## Что ещё не завершено

Текущий product critical path:

1. **issue #114** — один исполняемый active/current-revision + selected-profile-visible truth contract для Playback, Search, Recent и Guide; никаких fake totals/`hasMore`;
2. **Guide** — bounded channel window × bounded time window, deterministic keys и explicit completeness без full-guide materialization;
3. **issue #108 → #30** — explicit playback transport classification/raw MPEG-TS contract, затем bounded variant fallback и TV Doctor Lite;
4. **issues #33/#93** — Lounge Light TV-first polish поверх реальных Search/Recent/Guide routes;
5. **issue #31** — R8, Baseline/Startup Profiles, endurance, signing, SBOM/release checklist и physical-device evidence.

Параллельные hardening/evidence packages:

- **issue #121** — production-owned current migration chain и generated Room schema parity guard;
- **issue #101** — разделение Product/Database connected suites внутри существующего AVD harness, только с before/after runner evidence;
- **issue #100** — conditional M3U `ETag`/`Last-Modified` и корректный `304 Not Modified`, когда свободен следующий Room schema owner;
- **issue #27** — repeated `current-normal`, `old-edge-normal`, `current-low-ram` datasets и evidence-driven thresholds;
- **issues #109/#117** — buffer/FFmpeg decisions только после реального corpus/physical-device evidence.

**Rust/UniFFI, bundled SQLite, libmpv и второй player engine не являются текущими задачами реализации.** Они допускаются только после reproducible bottleneck/compatibility evidence и отдельного ADR.

## Архитектурные принципы

- TV-first и полноценный D-pad/remote flow;
- local-first/privacy-first;
- sensitive source/playback metadata не попадает в Navigation, public Room projections, logs, traces, screenshots или Recent history;
- source и EPG обновления используют immutable revisions, staging и atomic activation;
- previous-good data сохраняется при malformed input, cancellation, supersede и network failure;
- один process-owned `ExoPlayer`/`MediaSession`;
- accepted playback success означает exact service-owned first rendered frame, а не `READY`/`isPlaying`;
- WorkManager — durable orchestration boundary, но authoritative publication ownership остаётся за DB lease + transactional revision activation;
- derived Search index и Recent history не являются catalog source of truth;
- profile-facing projections обязаны повторно применять active revision + selected-profile visibility;
- UI не выполняет full-catalog/full-guide materialization;
- emulator/API matrix проверяет Android contracts, но не заменяет physical-device validation.

## Сборка и проверка

Debug APK:

```powershell
.\gradlew.bat :app:tv:assembleDebug
```

Полная локальная проверка:

```powershell
pwsh -NoProfile -File .\tools\verify-local.ps1 -Mode Full -NoDaemon
```

Old-edge/current Android TV matrix:

```powershell
pwsh -NoProfile -File .\tools\android\Invoke-TvDeviceValidation.ps1 `
  -Mode DeviceMatrix `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -NoDaemon
```

`DeviceMatrix` сначала выполняет Full host acceptance, затем последовательно поднимает old-edge Android TV image (предпочтительно API26, с явным fallback только если image недоступен) и current API36 image, запускает connected suites, собирает evidence и завершает каждый AVD.

Deterministic M3U corpus:

```powershell
.\gradlew.bat :core:testing:generateM3uCorpus `
  -PcorpusProfile=small-1k `
  -PcorpusSeed=20260728 `
  -PcorpusSourceCommit=<full-lowercase-40-character-git-sha>
```

Measurement series:

```powershell
pwsh -NoProfile -File .\tools\measurements\Invoke-MeasurementSeries.ps1 `
  -SourceBranch local `
  -SourceCommit <full-lowercase-40-character-git-sha> `
  -ProfileId current-normal `
  -Repetitions 5 `
  -NoDaemon
```

## Документация

- repository truth: [`.work/CURRENT-STATE.md`](.work/CURRENT-STATE.md);
- machine-readable status: [`.work/meta/status.yaml`](.work/meta/status.yaml);
- long-lived roadmap: [`.work/ROADMAP.md`](.work/ROADMAP.md);
- architecture: [`.work/ARCHITECTURE.md`](.work/ARCHITECTURE.md);
- benchmark methodology: [`.work/quality/benchmark-methodology.md`](.work/quality/benchmark-methodology.md);
- active implementation plans: [`docs/superpowers/plans/`](docs/superpowers/plans/).

## Лицензия

BSD 3-Clause.
